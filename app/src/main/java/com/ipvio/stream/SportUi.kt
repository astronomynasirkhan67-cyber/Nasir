package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// Shared building blocks of the Sports Hub. They only use the existing design system
// (DS, Tok, Accent1/2, Muted, PT.Live, FilterPill, HeroButton, EmptyState, Spinner, focusGlow, focusScale).

/** 0 = upcoming, 1 = live, 2 = final, 3 = postponed, 4 = cancelled. */
internal fun statusKind(g: SportData.Game): Int = when {
    g.statusName.contains("POSTPONED", true) || g.statusName.contains("DELAY", true) -> 3
    g.statusName.contains("CANCEL", true) || g.statusName.contains("ABANDON", true) || g.statusName.contains("FORFEIT", true) -> 4
    g.state == 1 -> 1
    g.state == 2 -> 2
    else -> 0
}

/** Shared status text of a game (live minute, finished, postponed, canceled or start time). */
internal fun statusText(g: SportData.Game): String = when (statusKind(g)) {
    3 -> Lang.tr("Verschoben")
    4 -> Lang.tr("Abgesagt")
    1 -> g.detail.ifBlank { g.clock }.ifBlank { "LIVE" }
    2 -> if (g.detail.isNotBlank() && SportData.kindOf(g) != SportData.KIND_TEAM) g.detail else Lang.tr("Beendet")
    else -> hhmm(g.start)
}

/** Day and time in the chosen time zone, e.g. "Sa 26.04. 15:00". */
internal fun dayTime(sec: Long): String = if (sec <= 0) "" else dayLabel(sec) + " " + hhmm(sec)

/** Team color from ESPN ("c8102e") or null. */
internal fun teamColor(hex: String?): Color? = try {
    if (hex.isNullOrBlank()) null else Color(android.graphics.Color.parseColor("#" + hex.removePrefix("#")))
} catch (_: Exception) { null }

/** Score line "2 – 1", for upcoming games "vs". */
internal fun scoreLine(g: SportData.Game): String =
    if (g.state == 0 || (g.home.score == null && g.away.score == null)) "vs" else (g.home.score ?: "–") + " – " + (g.away.score ?: "–")

// ───────── Navigation inside the hub (pages above the tabs) ─────────

sealed class HubPage {
    class Match(val g: SportData.Game) : HubPage()
    class League(val l: SportData.League) : HubPage()
    class Team(val l: SportData.League, val t: SportData.Team) : HubPage()
    class Player(val l: SportData.League, val id: String, val name: String) : HubPage()
    class Watch(val g: SportData.Game) : HubPage()
}

/**
 * Page stack of the hub. Every page is a full-screen window over the tabs; Back closes the top page and the focus
 * returns to the element that opened it (the requester remembered via [ContentMemory]).
 */
class HubNav {
    val stack = mutableStateListOf<HubPage>()
    private val back = ArrayList<FocusRequester?>()
    var restore: FocusRequester? by mutableStateOf<FocusRequester?>(null)
        private set
    var restoreTick by mutableStateOf(0)
        private set

    fun open(p: HubPage) { back.add(ContentMemory.last); stack.add(p) }

    fun pop() {
        if (stack.isEmpty()) return
        stack.removeAt(stack.size - 1)
        restore = if (back.isNotEmpty()) back.removeAt(back.size - 1) else null
        restoreTick++
    }

    fun clear() { stack.clear(); back.clear() }
}

/** Puts the focus back on the element that opened the closed page. */
@Composable
internal fun HubFocusRestore(nav: HubNav) {
    LaunchedEffect(nav.restoreTick) {
        val r = nav.restore ?: return@LaunchedEffect
        delay(120)
        runCatching { r.requestFocus() }
    }
}

/** Full-screen page of the hub: back button, title, subtitle; [first] gets the focus when the page opens. */
@Composable
internal fun HubPageFrame(
    title: String, sub: String, onBack: () -> Unit, first: FocusRequester,
    rawTitle: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val backReq = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(60)
            if (runCatching { first.requestFocus() }.isFailure) runCatching { backReq.requestFocus() }
        }
        Column(
            Modifier.fillMaxSize().background(Bg).padding(horizontal = DS.PageX, vertical = DS.PageY),
            verticalArrangement = Arrangement.spacedBy(dpx(14))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
                FilterPill("Zurück", false, Modifier.focusRequester(backReq), aicon = AIcon.Left) { onBack() }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Tok.TextHi, fontSize = spx(26), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = rawTitle)
                    if (sub.isNotBlank()) Text(raw = true, text = sub, color = Muted, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            content()
        }
    }
}

// ───────── Small parts ─────────

/** Logo or flag; while it loads (or if it is missing) the abbreviation is shown. */
@Composable
internal fun TeamLogo(url: String?, fallback: String, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (fallback.isNotBlank()) Text(raw = true, text = fallback.take(3).uppercase(), color = Tok.TextMid, fontSize = spx(13), fontWeight = FontWeight.Bold, maxLines = 1)
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Status chip: LIVE (with clock), upcoming (time), final, postponed, cancelled – each with its own look. */
@Composable
internal fun MatchStatus(g: SportData.Game, big: Boolean = false) {
    val k = statusKind(g)
    val fs = spx(if (big) 17 else 13)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
        when (k) {
            1 -> {
                Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(PT.Live).padding(horizontal = dpx(8), vertical = dpx(2))) {
                    Text("LIVE", color = Color.White, fontSize = fs, fontWeight = FontWeight.ExtraBold)
                }
                Text(raw = true, text = statusText(g), color = Tok.TextHi, fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            3, 4 -> Box(
                Modifier.clip(RoundedCornerShape(DS.RadiusXs)).border(1.dp, if (k == 3) DS.Gold else DS.Danger, RoundedCornerShape(DS.RadiusXs))
                    .padding(horizontal = dpx(8), vertical = dpx(2))
            ) { Text(raw = true, text = statusText(g), color = if (k == 3) DS.Gold else DS.Danger, fontSize = fs, fontWeight = FontWeight.Bold) }
            2 -> Text(raw = true, text = statusText(g), color = Muted, fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1)
            else -> Text(raw = true, text = statusText(g), color = Accent2, fontSize = fs, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun HubSectionTitle(text: String, raw: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Tok.TextHi, fontSize = spx(21), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), raw = raw)
        if (action != null && onAction != null) FilterPill(action, false, Modifier.trackFocus(), aicon = AIcon.Right) { onAction() }
    }
}

/** Calm surface for grouped content (match summary, key events, current position …). */
@Composable
internal fun HubPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Tok.Card).border(1.dp, Tok.Line, RoundedCornerShape(DS.RadiusM)).padding(dpx(16)),
        verticalArrangement = Arrangement.spacedBy(dpx(8)), content = content
    )
}

/** Focusable list row (row focus of the design system: lighter surface plus frame in the highlight color). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FRow(
    onClick: () -> Unit = {}, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, req: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) RowFocus else Color.Transparent)
            .border(DS.Border, if (f) Accent2 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .then(if (req != null) Modifier.trackFocus(req) else Modifier.trackFocus())
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = dpx(12), vertical = dpx(8)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12)), content = content
    )
}

/** Focusable card (card focus of the design system: frame in the highlight color, larger, soft shadow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HubCardBox(modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, req: FocusRequester? = null, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "hubcard")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.focusGlow(f, shape).clip(shape)
            .background(if (f) Tok.CardFocus else Tok.Card)
            .border(if (f) DS.FocusBorder else 1.dp, if (f) Accent2 else Tok.Line, shape)
            .onFocusChanged { f = it.isFocused }
            .then(if (req != null) Modifier.trackFocus(req) else Modifier.trackFocus())
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(8)), content = content
    )
}

/** Small round tile with logo and name (leagues, teams, sports, favorites). */
@Composable
internal fun LogoTile(name: String, logo: String?, fallback: String, modifier: Modifier = Modifier, marked: Boolean = false, raw: Boolean = true, onClick: () -> Unit) {
    HubCardBox(modifier.width(dpx(150)), onClick = onClick) {
        Box(Modifier.fillMaxWidth().height(dpx(70)), contentAlignment = Alignment.Center) {
            TeamLogo(logo, fallback, dpx(64))
            if (marked) AppIcon(AIcon.Star, DS.Gold, Modifier.align(Alignment.TopEnd), dpx(18))
        }
        Text(raw = raw, text = name, color = Tok.TextHi, fontSize = spx(14), fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** One side of a game: logo and name; the loser of a finished game is dimmed. */
@Composable
internal fun TeamName(t: SportData.Team, state: Int, modifier: Modifier, end: Boolean = false, logoSize: Int = 30) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10), if (end) Alignment.End else Alignment.Start)) {
        val label = @Composable {
            Text(
                raw = true, text = t.short, color = if (state == 2 && !t.winner && t.score != null) Muted else Tok.TextHi,
                fontSize = spx(17), fontWeight = if (t.winner) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (end) TextAlign.End else TextAlign.Start
            )
        }
        if (end) { label(); TeamLogo(t.logo, t.abbr, dpx(logoSize)) } else { TeamLogo(t.logo, t.abbr, dpx(logoSize)); label() }
    }
}

/** Card for horizontal rows (Live now, Upcoming). Field events show the leaders instead of two teams. */
@Composable
internal fun MatchCard(g: SportData.Game, hasChan: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HubCardBox(modifier.width(dpx(330)), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MatchStatus(g) }
            if (SportHubFavs.isFavGame(g)) AppIcon(AIcon.Star, DS.Gold, size = dpx(18))
        }
        Text(raw = true, text = listOf(g.groupName, g.note).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = Muted, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (g.solo) {
            Text(raw = true, text = g.title, color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            g.field.take(3).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                    Text(raw = true, text = e.pos, color = Accent2, fontSize = spx(14), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(28)))
                    TeamLogo(e.flag, "", dpx(20))
                    Text(raw = true, text = e.name, color = Tok.TextHi, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (e.value.isNotBlank()) Text(raw = true, text = e.value, color = Tok.TextHi, fontSize = spx(14), fontWeight = FontWeight.Bold)
                }
            }
            if (g.field.isEmpty()) Text("Noch keine Ergebnisse", color = Muted, fontSize = spx(13))
        } else listOf(g.home, g.away).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                TeamName(t, g.state, Modifier.weight(1f))
                Text(raw = true, text = t.score ?: "", color = Tok.TextHi, fontSize = spx(if ((t.score?.length ?: 0) > 6) 13 else 20), fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
        if (hasChan) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(6))) {
            AppIcon(AIcon.Play, Accent2, size = dpx(14))
            Text("Auf deinen Sendern", color = Accent2, fontSize = spx(13), maxLines = 1)
        }
    }
}

/**
 * Row of a scoreboard: status | home – score – away | marks. Large numbers of games stay readable because every
 * row has the same columns. Field events show title and leader.
 */
@Composable
internal fun MatchRow(g: SportData.Game, hasChan: Boolean, timeFirst: Boolean = false, onClick: () -> Unit) {
    FRow(onClick) {
        Box(Modifier.width(dpx(150))) {
            if (timeFirst && g.state == 0 && statusKind(g) == 0) Text(raw = true, text = hhmm(g.start), color = Accent2, fontSize = spx(17), fontWeight = FontWeight.Bold)
            else MatchStatus(g)
        }
        if (g.solo) {
            Column(Modifier.weight(1f)) {
                Text(raw = true, text = listOf(g.title, g.note).filter { it.isNotBlank() }.joinToString(" · "), color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val lead = g.field.firstOrNull()
                if (lead != null) Text(raw = true, text = "1. " + lead.name + (if (lead.value.isNotBlank()) "  " + lead.value else ""), color = Muted, fontSize = spx(14), maxLines = 1)
            }
        } else {
            TeamName(g.home, g.state, Modifier.weight(1f), end = true)
            Text(
                raw = true, text = scoreLine(g), color = if (g.state == 0) Muted else Tok.TextHi,
                fontSize = spx(if ((g.home.score?.length ?: 0) > 6) 14 else 20), fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.width(dpx(if ((g.home.score?.length ?: 0) > 6) 240 else 110))
            )
            TeamName(g.away, g.state, Modifier.weight(1f))
        }
        Row(Modifier.width(dpx(64)), horizontalArrangement = Arrangement.spacedBy(dpx(8), Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (hasChan) AppIcon(AIcon.Play, Accent2, size = dpx(18))
            if (SportHubFavs.isFavGame(g)) AppIcon(AIcon.Star, DS.Gold, size = dpx(18))
        }
    }
}

/** Header of a competition group with logo, LIVE marker and number of games. */
@Composable
internal fun CompetitionHeader(name: String, logo: String?, live: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(top = dpx(10), start = dpx(12), end = dpx(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
        if (logo != null) TeamLogo(logo, "", dpx(26))
        Text(raw = true, text = name, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (live > 0) Text("LIVE", color = PT.Live, fontSize = spx(14), fontWeight = FontWeight.ExtraBold)
        Text(if (total == 1) "1 Spiel" else "$total Spiele", color = Muted, fontSize = spx(14))
    }
}

/** Pills for the hub tabs; [first] marks the pill that gets the focus when a page opens. */
@Composable
internal fun HubTabs(tabs: List<String>, cur: String, first: FocusRequester? = null, modifier: Modifier = Modifier, onSel: (String) -> Unit) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(dpx(10)), contentPadding = PaddingValues(vertical = dpx(4))) {
        itemsIndexed(tabs, key = { _, t -> t }) { i, t ->
            FilterPill(t, cur == t, if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier.trackFocus()) { onSel(t) }
        }
    }
}

/** Sport filter: "All" plus the ten sports; [counts] shows how many games each sport has (null = no counts). */
@Composable
internal fun SportFilter(
    sel: String?, counts: Map<String, Int>?,
    /** Requester for the first pill ("Alle"): target of Down from the tab bar. */
    firstReq: FocusRequester? = null,
    /** Target of Down from every pill (e.g. the competition row below), null = default search. */
    down: FocusRequester? = null,
    onSel: (String?) -> Unit
) {
    val dn = if (down != null) Modifier.focusProperties { this.down = down } else Modifier
    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(10)), contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(4))) {
        item(key = "all") { FilterPill("Alle", sel == null, (if (firstReq != null) Modifier.trackFocus(firstReq) else Modifier.trackFocus()).then(dn)) { onSel(null) } }
        items(SportData.SPORTS, key = { it.key }) { s ->
            val n = counts?.get(s.key)
            FilterPill(Lang.tr(s.label) + (if (n != null && n > 0) "  $n" else ""), sel == s.key, Modifier.trackFocus().then(dn), raw = true) { onSel(if (sel == s.key) null else s.key) }
        }
    }
}

/** Date bar: previous/next, seven days around the chosen one, "Today". [day] is the offset to today. */
@Composable
internal fun DateSelector(day: Int, range: IntRange, onDay: (Int) -> Unit) {
    val days = ((day - 3)..(day + 3)).map { it.coerceIn(range) }.distinct()
    Row(Modifier.fillMaxWidth().padding(horizontal = DS.PageX), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
        FilterPill("", false, Modifier.trackFocus(), aicon = AIcon.Left) { onDay((day - 1).coerceIn(range)) }
        days.forEach { d ->
            val sec = System.currentTimeMillis() / 1000 + d * 86_400L
            val label = when (d) { -1 -> Lang.tr("Gestern"); 0 -> Lang.tr("Heute"); 1 -> Lang.tr("Morgen"); else -> dayLabel(sec) }
            FilterPill(label, d == day, Modifier.trackFocus(), raw = true) { onDay(d) }
        }
        FilterPill("", false, Modifier.trackFocus(), aicon = AIcon.Right) { onDay((day + 1).coerceIn(range)) }
        Spacer(Modifier.weight(1f))
        FilterPill("Heute", day == 0, Modifier.trackFocus(), aicon = AIcon.Catchup) { onDay(0) }
    }
}

@Composable
internal fun HubLoading(text: String = "Lade Daten …") {
    Box(Modifier.fillMaxWidth().padding(dpx(40)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(14))) {
            Spinner(Accent2, Modifier.size(dpx(46)))
            Text(text, color = Muted, fontSize = spx(17))
        }
    }
}

/** Empty, no-result or error state of an area (always with a way out if one exists). */
@Composable
internal fun HubEmpty(icon: AIcon, title: String, text: String, action: String? = null, actionModifier: Modifier = Modifier, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(icon, title, text, action = action, actionModifier = actionModifier, onAction = onAction)
    }
}

@Composable
internal fun FavoriteButton(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HeroButton(if (on) "Favorit" else "Als Favorit merken", if (on) AIcon.Star else AIcon.StarOutline, on, modifier, 46) { onClick() }
}

/** "Watch live" only if a channel of the playlist is matched; otherwise a calm hint instead of a dead button. */
@Composable
internal fun WatchLiveButton(chans: List<Item>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (chans.isNotEmpty()) HeroButton("Live ansehen", AIcon.Play, true, modifier, 46) { onClick() }
    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
        AppIcon(AIcon.Info, Muted, size = dpx(18))
        Text("Kein passender Sender gefunden", color = Muted, fontSize = spx(15))
    }
}

/** Simple secondary button (kept for older dialogs). */
@Composable
internal fun MBtn(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PillButton(text, modifier, onClick = onClick)
}

// ───────── Lists for LazyColumns ─────────

/** Statistics home | name | away with a bar that shows the share of both sides. */
internal fun LazyListScope.statisticsItems(stats: List<SportMatch.Stat>) {
    items(stats.size) { i ->
        val s = stats[i]
        FRow {
            Text(raw = true, text = s.home, color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(110)))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(raw = true, text = s.label, color = Muted, fontSize = spx(15), textAlign = TextAlign.Center)
                val a = s.home.removeSuffix("%").toFloatOrNull()
                val b = s.away.removeSuffix("%").toFloatOrNull()
                if (a != null && b != null && a + b > 0f) Row(Modifier.fillMaxWidth().padding(top = dpx(4)).height(dpx(6)).clip(RoundedCornerShape(50))) {
                    Box(Modifier.weight(maxOf(a, 0.01f)).fillMaxHeight().background(Accent2))
                    Box(Modifier.weight(maxOf(b, 0.01f)).fillMaxHeight().background(DS.Overlay4))
                }
            }
            Text(raw = true, text = s.away, color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(110)))
        }
    }
}

/** Timeline of a game: minute, kind of event (goal, card, substitution) and text. */
internal fun LazyListScope.timelineItems(events: List<SportMatch.Ev>, max: Int = Int.MAX_VALUE) {
    val list = events.take(max)
    items(list.size) { i ->
        val e = list[i]
        FRow {
            Text(raw = true, text = e.time, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(80)))
            EventMark(e.type)
            Text(raw = true, text = e.text, color = if (e.goal) Tok.TextHi else Tok.TextMid, fontSize = spx(16),
                fontWeight = if (e.goal) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        }
    }
}

/** Small symbol per event type, drawn with the app's icons and colors (no emoji). */
@Composable
internal fun EventMark(type: String) {
    val t = type.lowercase()
    Box(Modifier.width(dpx(26)), contentAlignment = Alignment.Center) {
        when {
            t.contains("yellow") -> Box(Modifier.size(dpx(12), dpx(16)).clip(RoundedCornerShape(2.dp)).background(DS.Gold))
            t.contains("red card") -> Box(Modifier.size(dpx(12), dpx(16)).clip(RoundedCornerShape(2.dp)).background(DS.Danger))
            t.contains("goal") || t.contains("touchdown") || t.contains("penalty - scored") -> AppIcon(AIcon.Dot, Accent2, size = dpx(18))
            t.contains("substitution") -> AppIcon(AIcon.Refresh, Muted, size = dpx(16))
            else -> {}
        }
    }
}

/** Table of one group; [mark] = team ID to highlight, [onTeam] opens the Team Center. */
internal fun LazyListScope.standingsItems(grp: SportStandings.Group, mark: String?, onTeam: ((SportData.Team) -> Unit)?) {
    item(key = "h" + grp.name + grp.cols.size) {
        Row(Modifier.fillMaxWidth().padding(top = dpx(10), start = dpx(12), end = dpx(12)), verticalAlignment = Alignment.CenterVertically) {
            Text(raw = true, text = "#", color = Muted, fontSize = spx(14), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(40)))
            Text(raw = true, text = grp.name.ifBlank { Lang.tr("Team") }, color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            grp.cols.forEach { c -> Text(c, color = Muted, fontSize = spx(14), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(62))) }
        }
    }
    items(grp.rows.size) { i ->
        val r = grp.rows[i]
        val me = mark != null && mark.isNotBlank() && r.team.id == mark
        FRow({ onTeam?.invoke(r.team) }) {
            Text(raw = true, text = r.rank, color = if (me) Accent2 else Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(28)))
            TeamName(r.team, 0, Modifier.weight(1f), logoSize = 26)
            if (SportFavs.has(r.team)) AppIcon(AIcon.Star, DS.Gold, size = dpx(16))
            r.cells.forEach { c -> Text(raw = true, text = c, color = if (me) Accent2 else Tok.TextHi, fontSize = spx(15), fontWeight = if (me) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.width(dpx(62))) }
        }
    }
}

/** A game result or fixture as compact row (Team Center, League Center). */
@Composable
internal fun ResultRow(date: Long, home: String, homeLogo: String?, away: String, awayLogo: String?, score: String, onClick: () -> Unit = {}) {
    FRow(onClick) {
        Text(raw = true, text = dayTime(date), color = Muted, fontSize = spx(14), modifier = Modifier.width(dpx(170)), maxLines = 1)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8), Alignment.End)) {
            Text(raw = true, text = home, color = Tok.TextHi, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TeamLogo(homeLogo, "", dpx(24))
        }
        Text(raw = true, text = score.ifBlank { "vs" }, color = if (score.isBlank()) Muted else Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, modifier = Modifier.width(dpx(90)))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
            TeamLogo(awayLogo, "", dpx(24))
            Text(raw = true, text = away, color = Tok.TextHi, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Big header of a game (Match Center, featured game, Watch Live): teams with logos, score, status. */
@Composable
internal fun MatchHeader(g: SportData.Game, logo: Int = 96) {
    if (g.solo) {
        Column(verticalArrangement = Arrangement.spacedBy(dpx(6))) {
            MatchStatus(g, big = true)
            Text(raw = true, text = g.title, color = Tok.TextHi, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = listOf(g.note, g.venue).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = spx(16))
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(20))) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            TeamLogo(g.home.logo, g.home.abbr, dpx(logo))
            Text(raw = true, text = g.home.name, color = Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            g.home.record?.let { Text(raw = true, text = it, color = Muted, fontSize = spx(14)) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(6))) {
            MatchStatus(g, big = true)
            val long = (g.home.score?.length ?: 0) > 6 || (g.away.score?.length ?: 0) > 6
            if (long) {
                Text(raw = true, text = g.home.score ?: "", color = Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.ExtraBold)
                Text(raw = true, text = g.away.score ?: "", color = Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.ExtraBold)
            } else Text(raw = true, text = if (g.state == 0) hhmm(g.start) else scoreLine(g), color = Tok.TextHi, fontSize = spx(54), fontWeight = FontWeight.ExtraBold)
            val info = listOf(if (g.state == 0) dayLabel(g.start) else "", g.venue).filter { it.isNotBlank() }.joinToString(" · ")
            if (info.isNotBlank()) Text(raw = true, text = info, color = Muted, fontSize = spx(15), maxLines = 1)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            TeamLogo(g.away.logo, g.away.abbr, dpx(logo))
            Text(raw = true, text = g.away.name, color = Tok.TextHi, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            g.away.record?.let { Text(raw = true, text = it, color = Muted, fontSize = spx(14)) }
        }
    }
}

/** Soft background in the team colors behind a featured game (like the hero of the home screen uses item colors). */
internal fun featuredBrush(g: SportData.Game): Brush {
    val a = teamColor(g.home.color) ?: Accent1
    val b = teamColor(g.away.color) ?: Accent2
    return Brush.horizontalGradient(listOf(a.copy(alpha = 0.30f), Tok.Card, b.copy(alpha = 0.30f)))
}
