package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.lerp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shortly after closing a selection window in the guide (Back) do not perform another back step. */
object GuideBack {
    @Volatile var mutedUntil = 0L
    /** Back step of the open guide (the player intercepts the Back key first and calls this). */
    var handler: (() -> Unit)? = null
}

/** Remembers the view of the TV guide (zoom, row height, past shows). */
object GuidePrefs {
    // Default (round 14, user's wish): zoom "Large", row height "Compact"
    var zoom by mutableIntStateOf(2)
    var rowSize by mutableIntStateOf(0)
    var hidePast by mutableStateOf(false)
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = Profiles.sp(c, "ipvio_guide")
        // One-time: new default view (Large / Compact) also for everyone who had already saved
        if (p.getInt("ver", 1) < 2) p.edit().putInt("zoom", 2).putInt("row", 0).putInt("ver", 2).apply()
        zoom = p.getInt("zoom", 2).coerceIn(0, 2)
        rowSize = p.getInt("row", 0).coerceIn(0, 2)
        hidePast = p.getBoolean("past", false)
    }

    fun save(c: Context) {
        Profiles.sp(c, "ipvio_guide").edit()
            .putInt("zoom", zoom).putInt("row", rowSize).putBoolean("past", hidePast).apply()
    }
}

@Composable
fun Guide(
    list: List<Item>, curId: String, focus: FocusRequester, onClose: () -> Unit,
    onArchive: (Item, EpgEntry) -> Unit = { _, _ -> },
    /** All channels (for the categories on the left); default = only the passed list. */
    all: List<Item> = list,
    /** Channel chosen from another category: continue switching with this list. */
    onPickIn: ((Item, List<Item>) -> Unit)? = null,
    onPick: (Item) -> Unit
) {
    val ctx = LocalContext.current
    GuidePrefs.init(ctx)
    val openedAt = remember { System.currentTimeMillis() / 1000 }
    val base = (openedAt - 1800) - ((openedAt - 1800) % 1800)
    // Channels with archive (catchup): this many days can be scrolled back
    val pastDays = remember(list, Catchup.version) { Catchup.maxDays(list) }
    val minStart = base - pastDays * 86400L
    // "Now" moves along: the highlight and the state of the shows are updated every 30 s
    var nowSec by remember { mutableLongStateOf(openedAt) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }
    // dp per minute: "Small" shows a good 3 hours at once, "Medium" a good 2 hours
    val zooms = listOf(3.5f, 5f, 8f)
    val zoomNames = listOf("Klein", "Mittel", "Groß")
    val rowNames = listOf("Kompakt", "Normal", "Groß")
    val rowHeights = listOf(54, 66, 82)
    val dpm = zooms[GuidePrefs.zoom]
    val rowH = rowHeights[GuidePrefs.rowSize]
    val maxSpan = 2880L * 60
    var viewMin by remember { mutableIntStateOf(150) }
    var winStart by remember { mutableLongStateOf(base) }
    var infoE by remember { mutableStateOf<EpgEntry?>(null) }
    var infoItem by remember { mutableStateOf<Item?>(null) }
    var inBlock by remember { mutableStateOf(false) }
    var inCats by remember { mutableStateOf(false) }
    var rowReq by remember { mutableStateOf<FocusRequester?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Categories on the left (as in the design): current list, all channels, favorites, own lists, provider groups
    val cats = remember(list, all, Library.favs.size, ChannelLists.lists.size) { guideCategories(list, all) }
    var catIdx by remember { mutableIntStateOf(0) }
    val shown = cats.getOrNull(catIdx)?.items ?: list
    val catReq = remember { FocusRequester() }
    val idx = shown.indexOfFirst { it.id == curId }.coerceAtLeast(0)
    val ls = key(catIdx) { rememberLazyListState(maxOf(idx - 1, 0)) }

    // Preload program data: first the channels around the current one, 8 at a time
    LaunchedEffect(shown) {
        val order = shown.indices.sortedBy { kotlin.math.abs(it - idx) }.map { shown[it] }
        for (chunk in order.take(80).chunked(8)) {
            // No preloading while Up/Down is held: network and parsing would compete with scrolling
            while (LiveHold.active()) delay(150)
            coroutineScope { chunk.map { async { EpgFull.get(it) } }.awaitAll() }
        }
    }

    fun shiftFor(e: EpgEntry) {
        val center = e.start + minOf(e.end - e.start, viewMin * 60L) / 2
        winStart = (center - viewMin * 30L).coerceIn(minStart, base + maxSpan - viewMin * 60L)
    }

    fun jumpNow() {
        winStart = (nowSec - 20 * 60L).coerceIn(minStart, base + maxSpan - viewMin * 60L)
        runCatching { focus.requestFocus() }
    }

    // Focus on a channel cell: info and time window follow only after a short pause. Each shift moves the whole grid and
    // recomposes every visible row; while Down is held this happened on every step. Written only, read in the flow below.
    val rowFocus = remember { mutableStateOf<Pair<EpgEntry?, Item>?>(null) }
    val jumpScope = rememberCoroutineScope()
    val shiftLatest by androidx.compose.runtime.rememberUpdatedState<(EpgEntry) -> Unit> { e -> shiftFor(e) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { rowFocus.value }.collectLatest { p ->
            if (p == null) return@collectLatest
            delay(120)
            while (LiveHold.active()) delay(80)
            infoE = p.first; infoItem = p.second
            p.first?.let { shiftLatest(it) }
        }
    }

    // Back (user's wish, round 14): in the shows → to the channel of this row (whose program you are looking at);
    // in the channel column → to the chosen category; in the categories → close (Left there as well).
    // The player intercepts the Back key and calls this step via GuideBack.handler (before, it closed the guide immediately).
    val backStep by androidx.compose.runtime.rememberUpdatedState<() -> Unit> {
        if (System.currentTimeMillis() >= GuideBack.mutedUntil) when {
            inBlock -> {
                val r = rowReq
                if (r == null || !runCatching { r.requestFocus() }.isSuccess) { if (!runCatching { catReq.requestFocus() }.isSuccess) onClose() }
            }
            !inCats -> if (!runCatching { catReq.requestFocus() }.isSuccess) onClose()
            else -> onClose()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        GuideBack.handler = { backStep() }
        onDispose { GuideBack.handler = null }
    }
    LaunchedEffect(GuidePrefs.zoom) { infoE?.let { shiftFor(it) } }

    if (showSearch) GuideSearchDialog(
        all,
        onPick = { ch -> showSearch = false; val p = onPickIn; if (p != null) p(ch, all) else onPick(ch) },
        onClose = { showSearch = false; GuideBack.mutedUntil = System.currentTimeMillis() + 400 }
    )
    // Top: header + selected show on the left, the running picture on the right (PlayerScreen shrinks it to 400 × 225 dp, top right).
    // Below: categories and program grid across the full width – so several hours are visible at once.
    Column(
        Modifier.fillMaxSize()
            .drawBehind {
                val l = size.width - 424.dp.toPx(); val t = 20.dp.toPx()
                val hole = androidx.compose.ui.geometry.RoundRect(l, t, l + 400.dp.toPx(), t + 225.dp.toPx(), androidx.compose.ui.geometry.CornerRadius(DS.RadiusM.toPx()))
                val path = androidx.compose.ui.graphics.Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(hole)
                }
                drawPath(path, Color(0xF7090B12))
            }
            .padding(start = 24.dp, top = 14.dp, bottom = 10.dp, end = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(238.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 20.dp)) {
                // Header: title, date and time, Now and View
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MenuIcon(3, Accent1, Modifier.size(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TV-Guide", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Live TV · Jetzt und in den nächsten Stunden", color = Muted, fontSize = 13.sp, maxLines = 1)
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(raw = true, text = dayLabel(nowSec), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.width(1.dp).height(14.dp).background(Color(0x44FFFFFF)))
                        Text(raw = true, text = hhmm(nowSec), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = TNUM)
                    }
                    SmallPill("Suche", icon = AIcon.Edit) { showSearch = true }
                    SmallPill("Jetzt", icon = AIcon.Dot) { jumpNow() }
                    Box {
                        SmallPill("", icon = AIcon.Gear) { showSettings = true }
                        if (showSettings) {
                            val first = remember { FocusRequester() }
                            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                            Popup(
                                alignment = Alignment.TopStart, offset = IntOffset(0, 80),
                                onDismissRequest = { showSettings = false }, properties = PopupProperties(focusable = true)
                            ) {
                                Column(
                                    Modifier.width(380.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Tok.GlassStrong).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("TV-Guide – Ansicht", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Zoom", color = Color.White, fontSize = 15.sp)
                                        SmallPill(zoomNames[GuidePrefs.zoom], Modifier.focusRequester(first)) { GuidePrefs.zoom = (GuidePrefs.zoom + 1) % 3; GuidePrefs.save(ctx) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Zeilenhöhe", color = Color.White, fontSize = 15.sp)
                                        SmallPill(rowNames[GuidePrefs.rowSize]) { GuidePrefs.rowSize = (GuidePrefs.rowSize + 1) % 3; GuidePrefs.save(ctx) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Vergangene Sendungen", color = Color.White, fontSize = 15.sp)
                                        SmallPill(if (GuidePrefs.hidePast) "Ausblenden" else "Abdunkeln") { GuidePrefs.hidePast = !GuidePrefs.hidePast; GuidePrefs.save(ctx) }
                                    }
                                    Text("Zurück: Sendung, Sender, Kategorie, dann schließen", color = Muted, fontSize = 13.sp)
                                    Text("Jetzt ${hhmm(nowSec)} · Zeitzone ${epgZone().id} (änderbar unter Einstellungen > Sprache & Region)", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                GuideDetailPanel(infoE, infoItem, nowSec, Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp))
            }
            Spacer(Modifier.width(400.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            // Categories
            LazyColumn(
                Modifier.width(206.dp).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusL)).background(Color(0x0FFFFFFF)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(cats) { ci, c ->
                    GuideCatRow(c, ci == catIdx, if (ci == catIdx) Modifier.focusRequester(catReq) else Modifier,
                        onFocus = { inCats = true; inBlock = false; if (catIdx != ci) catIdx = ci }, onLeft = onClose) { runCatching { focus.requestFocus() } }
                }
            }
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusL)).background(Color(0x0AFFFFFF)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                val vm = ((maxWidth - 208.dp).value / dpm).toInt().coerceAtLeast(40)
                SideEffect { if (viewMin != vm) viewMin = vm }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(raw = true, text = cats.getOrNull(catIdx)?.name?.let { Lang.tr(it) } ?: "", color = Muted, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(208.dp).padding(start = 6.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            // only the visible time marks (with archive the grid reaches days back)
                            var t = maxOf(minStart, winStart - winStart % 1800 - 3600)
                            val tEnd = minOf(base + maxSpan, winStart + viewMin * 60L + 3600)
                            while (t < tEnd) {
                                val x = (t - winStart) / 60f * dpm
                                if (x > -100f && x < viewMin * dpm + 40f) {
                                    val isDay = SettingsLogic.isMidnight(t, epgZone())
                                    val label = if (isDay) dayLabel(t) else hhmm(t)
                                    Text(
                                        label, color = if (isDay) Accent2 else Muted, fontSize = 13.sp,
                                        fontWeight = if (isDay || t % 3600L == 0L) FontWeight.Bold else FontWeight.Medium, style = TNUM,
                                        modifier = Modifier.offset(x = x.dp).align(Alignment.CenterStart)
                                    )
                                }
                                t += 1800
                            }
                            val nx = (nowSec - winStart) / 60f * dpm
                            if (nx > 0f && nx < viewMin * dpm) Box(Modifier.offset(x = (nx - 3f).dp).size(6.dp).clip(RoundedCornerShape(50)).background(Accent2).align(Alignment.BottomStart))
                        }
                    }
                    if (shown.isEmpty()) Text("Keine Sender", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                    LazyColumn(
                        state = ls,
                        // Held Up/Down: at most ~9 steps per second (as in the Live TV list), and rows wait with loading
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp) && e.nativeKeyEvent.repeatCount > 0) {
                                val now = android.os.SystemClock.uptimeMillis()
                                LiveHold.lastRepeat = now
                                if (now - LiveHold.lastStep < 110) true else { LiveHold.lastStep = now; false }
                            } else false
                        }
                    ) {
                        itemsIndexed(shown) { i, ch ->
                            val cellReq = if (i == idx) focus else remember { FocusRequester() }
                            GuideRow(
                                ch, ch.id == curId, { winStart }, viewMin, dpm, nowSec, rowH.dp, GuidePrefs.hidePast, cellReq,
                                onInfo = { e -> infoE = e; infoItem = ch },
                                onShift = { e -> shiftFor(e) },
                                onRowFocus = { e -> rowFocus.value = e to ch; jumpScope.launch { holdJump(ls, i) } },
                                onBlock = { inB, r -> inBlock = inB; inCats = false; rowReq = r; if (inB) rowFocus.value = null },
                                // Left from the channel column: to the categories (before: close)
                                onClose = { if (!runCatching { catReq.requestFocus() }.isSuccess) onClose() },
                                onArchive = { e -> onArchive(ch, e) },
                                onPick = { val p = onPickIn; if (p != null) p(ch, shown) else onPick(ch) }
                            )
                        }
                    }
                }
            }
        }
        // Key hints
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideHint(null, "Sendungen", AIcon.Left, AIcon.Right)
            GuideHint(null, "Sender", AIcon.Up, AIcon.Down)
            // What OK does on the selected show (before a button in the detail field that was dropped for long texts)
            val okText = guideOkAction(infoE, infoItem, nowSec)
            GuideHint("OK", okText)
            if (pastDays > 0) GuideHint(null, "1 Tag", AIcon.Rewind, AIcon.FastForward)
            GuideHint("Zurück", "Sender, Kategorie, schließen")
        }
}
}

/** Opaque colors of the show fields (no transparency, see GuideRow). */
private val BlockBase = Color(0xFF1C202B)
private val BlockPast = Color(0xFF15181F)
private val BlockLine = Color(0xFF2A2F3B)

/** A category on the left in the TV guide. */
class GuideCat(val name: String, val items: List<Item>, val icon: AIcon?)

/** Categories for the TV guide: current list (if different from all), all channels, favorites, own lists, groups. */
fun guideCategories(list: List<Item>, all: List<Item>): List<GuideCat> {
    val out = ArrayList<GuideCat>()
    val allIds = all.size
    if (list.size != allIds && list.isNotEmpty()) out.add(GuideCat("Aktuelle Liste", list, AIcon.Play))
    out.add(GuideCat("Alle Sender", all, AIcon.Grid))
    val byId = all.associateBy { it.id }
    val favs = Library.favs.mapNotNull { byId[it] }
    if (favs.isNotEmpty()) out.add(GuideCat("Favoriten", favs, AIcon.Star))
    for (l in ChannelLists.lists.toList()) {
        val items = l.ids.mapNotNull { byId[it] }
        if (items.isNotEmpty()) out.add(GuideCat(l.name, items, AIcon.Lists))
    }
    val groups = LinkedHashMap<String, ArrayList<Item>>()
    for (it in all) groups.getOrPut(it.group) { ArrayList() }.add(it)
    if (groups.size > 1) for ((g, l) in groups) out.add(GuideCat(g, l, null))
    return out
}

@Composable
private fun GuideCatRow(c: GuideCat, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onLeft: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) RowFocus else if (selected) Accent1.copy(alpha = .18f) else Color.Transparent)
            .border(if (f) DS.Border else 1.dp, if (f) Accent2 else if (selected) Accent1.copy(alpha = .55f) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            // Left in the categories: close the guide (like Back)
            .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onLeft(); true } else false }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (c.icon != null) AppIcon(c.icon, if (c.icon == AIcon.Star) DS.Gold else if (selected) Accent1 else Muted, size = 15.dp)
        // Provider names stay as they are; the app's own labels are translated
        Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), raw = c.icon == null || c.icon == AIcon.Lists)
        Text(raw = true, text = c.items.size.toString(), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun GuideHint(key: String?, text: String, vararg keys: AIcon) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(Color(0x2EFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (key != null) Text(key, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
            for (k in keys) AppIcon(k, Color.White, size = 14.dp)
        }
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

/**
 * Right column (design picture 3): selected show with status, title, channel · time · duration, description,
 * what OK does (watch / from the archive / remind) and the next shows of the channel.
 */
@Composable
private fun GuideDetailPanel(e: EpgEntry?, ch: Item?, nowSec: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.clip(shape).background(Color(0x0FFFFFFF)).border(1.dp, Color(0x14FFFFFF), shape).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (e == null || ch == null) {
                Text("TV-Guide", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Wähle eine Sendung mit den Pfeiltasten, OK schaltet auf den Sender.", color = PT.T2, fontSize = 14.sp)
                Text("Zurück: zum Sender, dann zur Kategorie, dann schließen.", color = Muted, fontSize = 13.sp)
                return@Column
            }
            val isNow = nowSec >= e.start && nowSec < e.end
            val past = e.end <= nowSec
            val untilMin = (e.start - nowSec) / 60
            val archived = past && Catchup.canPlay(ch, e, nowSec)
            val status = when {
                isNow -> "JETZT"
                archived -> "IM ARCHIV"
                past -> "BEENDET"
                untilMin < 180 -> "in $untilMin Min"
                else -> dayLabel(e.start)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    status, color = if (isNow) OnAccent else Tok.TextMid, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(if (isNow) Accent1 else Color(0x2EFFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text(raw = true, text = ch.title, color = PT.T2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(raw = true, text = "·  ${hhmm(e.start)} – ${hhmm(e.end)}", color = PT.T2, fontSize = 13.sp, style = TNUM, maxLines = 1)
                Text("·  ${(e.end - e.start) / 60} Min", color = PT.T2, fontSize = 13.sp, maxLines = 1)
                if (Reminders.isSet(ch.id, e.start)) AppIcon(AIcon.Bell, Accent2, size = 14.dp)
            }
            Text(raw = true, text = e.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isNow) {
                val frac = ((nowSec - e.start).toFloat() / (e.end - e.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
                    }
                    Text("noch ${((e.end - nowSec) / 60).coerceAtLeast(0)} Min", color = Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = TNUM)
                }
            }
            // The whole width belongs to the description (the "Next shows" column was dropped at the user's request)
            if (e.desc.isNotBlank()) Text(raw = true, text = e.desc, color = DS.TextSoft, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Label for OK in the key help: watch, watch from the archive or set/delete a reminder. */
private fun guideOkAction(e: EpgEntry?, ch: Item?, nowSec: Long): String {
    if (e == null || ch == null) return "Ansehen / Erinnerung"
    return when {
        ReminderLogic.canRemind(e.start, nowSec) -> if (Reminders.isSet(ch.id, e.start)) "Erinnerung löschen" else "Erinnerung setzen"
        e.end <= nowSec && Catchup.canPlay(ch, e, nowSec) -> "Aus dem Archiv ansehen"
        else -> "Ansehen"
    }
}

@Composable
private fun GuideRow(
    ch: Item, current: Boolean, winStart: () -> Long, viewMin: Int, dpm: Float, nowSec: Long, rowH: Dp, hidePast: Boolean,
    cellReq: FocusRequester, onInfo: (EpgEntry?) -> Unit, onShift: (EpgEntry) -> Unit, onRowFocus: (EpgEntry?) -> Unit,
    onBlock: (Boolean, FocusRequester) -> Unit, onClose: () -> Unit, onArchive: (EpgEntry) -> Unit, onPick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cu = remember(ch.id, Catchup.version) { Catchup.of(ch) }
    // null = still loading (shows "loading …" instead of wrongly "No program available")
    // Cached program right away; otherwise wait briefly and not while Up/Down is held (rows that only fly past load nothing)
    // While Up/Down is held even cached rows stay empty: a row with its program builds a dozen focusable fields
    val raw by produceState<List<EpgEntry>?>(if (LiveHold.active()) null else EpgFull.peek(ch), ch.id) {
        value = if (LiveHold.active()) null else EpgFull.peek(ch)
        if (value != null) return@produceState
        delay(150)
        while (LiveHold.active()) delay(120)
        value = EpgFull.get(ch)
    }
    // "Hide past" leaves shows in the archive: they can still be watched
    val entries = remember(raw, hidePast, cu) {
        cleanEpg(raw.orEmpty()).let { l -> if (hidePast) l.filter { it.end > nowSec || CatchupLogic.canPlay(cu, it.start, it.end, nowSec) } else l }
    }
    val blockReqs = remember(entries) { HashMap<Long, FocusRequester>() }
    // The time window is read as a lambda: moving it only shifts the row content (placement), the shows are not recomposed.
    // Which shows exist in the row changes only when the window crosses a full hour.
    val origin = remember { winStart() }
    val hour by remember { androidx.compose.runtime.derivedStateOf { Math.floorDiv(winStart(), 3600L) } }
    val from = hour * 3600L - 120 * 60L
    val to = hour * 3600L + 3600L + (viewMin + 120) * 60L
    Row(Modifier.height(rowH).padding(vertical = 3.dp)) {
        var f by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(DS.RadiusS)
        Row(
            Modifier.focusRequester(cellReq).width(208.dp).fillMaxHeight().padding(end = 8.dp).clip(shape)
                .background(if (f) Accent1.copy(alpha = .30f) else if (current) DS.Overlay2 else Color(0x12FFFFFF))
                .border(if (f) DS.Border else 1.dp, if (f) Accent1 else Color(0x0FFFFFFF), shape)
                .onFocusChanged {
                    f = it.isFocused
                    if (it.isFocused) {
                        onBlock(false, cellReq)
                        onRowFocus(Epg.current(entries))
                    }
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onClose(); true } else false
                }
                .clickable(onClick = onPick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
                if (ch.logo != null) AsyncImage(ch.logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            }
            Text(
                ch.title, color = Color.White, fontSize = 14.sp,
                fontWeight = if (current || f) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val shown = remember(entries, from, to) { entries.filter { it.end > from && it.start < to } }
            if (shown.isEmpty()) Text(
                if (raw == null) "lädt …" else "Kein Programm verfügbar", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterStart)
            )
            Box(Modifier.fillMaxSize().offset { IntOffset(-((winStart() - origin) / 60f * dpm).dp.roundToPx(), 0) }) {
            shown.forEach { e ->
                key(e.start) {
                    val x = (e.start - origin) / 60f * dpm
                    val w = ((e.end - e.start) / 60f * dpm - 3f).coerceIn(3f, 6000f)
                    val isNow = nowSec >= e.start && nowSec < e.end
                    val past = e.end <= nowSec
                    var bf by remember { mutableStateOf(false) }
                    val reminded = Reminders.isSet(ch.id, e.start)
                    val inArchive = past && CatchupLogic.canPlay(cu, e.start, e.end, nowSec)
                    val dw = if (bf) maxOf(w, 200f) else w
                    val frac = if (isNow) ((nowSec - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f) else 0f
                    val req = blockReqs.getOrPut(e.start) { FocusRequester() }
                    val blockShape = RoundedCornerShape(DS.RadiusS)
                    Box(
                        Modifier.offset(x = x.dp).zIndex(if (bf) 2f else 0f).width(dw.dp).fillMaxHeight().clip(blockShape)
                            .then(
                                // All fields opaque (round 14): the selected show becomes wider and lies over its neighbors –
                                // semi-transparent, the texts of short shows would overlap
                                if (bf) Modifier.background(Brush.horizontalGradient(listOf(lerp(BlockBase, Accent1, .62f), lerp(BlockBase, Accent1, .42f))))
                                else if (isNow) Modifier.background(lerp(BlockBase, Accent1, .16f))
                                else Modifier.background(if (past && !inArchive) BlockPast else BlockBase)
                            )
                            .border(if (bf) 2.dp else 1.dp, if (bf) Accent1 else if (isNow) lerp(BlockBase, Accent1, .40f) else BlockLine, blockShape)
                            .focusRequester(req)
                            .onPreviewKeyEvent { ev ->
                                // Seek keys: one day back/forward (quickly to older shows in the archive)
                                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.MediaRewind || ev.key == Key.MediaFastForward)) {
                                    val want = e.start + (if (ev.key == Key.MediaFastForward) 86400L else -86400L)
                                    val target = entries.minByOrNull { kotlin.math.abs(it.start - want) }
                                    if (target != null && target.start != e.start) {
                                        onShift(target)
                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            androidx.compose.runtime.withFrameNanos { }
                                            runCatching { blockReqs[target.start]?.requestFocus() }
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionRight || ev.key == Key.DirectionLeft)) {
                                    val i = entries.indexOfFirst { it.start == e.start }
                                    val ni = i + (if (ev.key == Key.DirectionRight) 1 else -1)
                                    if (ni < 0) { runCatching { cellReq.requestFocus() } }
                                    else if (ni <= entries.lastIndex) {
                                        val target = entries[ni]
                                        onShift(target)
                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            androidx.compose.runtime.withFrameNanos { }
                                            runCatching { blockReqs[target.start]?.requestFocus() }
                                        }
                                    }
                                    true
                                } else false
                            }
                            .onFocusChanged { bf = it.isFocused; if (it.isFocused) { onBlock(true, cellReq); onInfo(e); onShift(e) } }
                            // Later show: OK sets/deletes the reminder (round 14: without a selection window);
                            // in the archive: play the show; otherwise watch the channel
                            .clickable {
                                val n = System.currentTimeMillis() / 1000
                                if (ReminderLogic.canRemind(e.start, n)) {
                                    val on = Reminders.toggle(ctx, Reminder(ch.id, ch.title, e.title, e.start, e.end))
                                    if (on) Reminders.askPermission(ctx as? android.app.Activity)
                                    android.widget.Toast.makeText(
                                        ctx,
                                        if (on) Lang.tr("Erinnerung gesetzt – ${Reminders.leadMin} Min vorher") else Lang.tr("Erinnerung gelöscht"),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else if (e.end <= n && CatchupLogic.canPlay(cu, e.start, e.end, n)) onArchive(e)
                                else onPick()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (reminded) AppIcon(AIcon.Bell, if (bf) Color.White else Accent2, Modifier.align(Alignment.TopEnd), 13.dp)
                        if (inArchive && dw >= 30f) AppIcon(AIcon.Catchup, if (bf) Color.White else Accent2, Modifier.align(Alignment.TopEnd), 13.dp)
                        // Design: title on top, time below
                        if (dw >= 44f) Column {
                            if (dw >= 72f) Text(
                                e.title, color = if (past && !inArchive && !bf) Color(0xFF9AA0B2) else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true
                            )
                            Text(
                                raw = true, text = hhmm(e.start) + " – " + hhmm(e.end), color = if (bf) Color(0xE6FFFFFF) else Muted,
                                fontSize = 13.sp, style = TNUM, maxLines = 1
                            )
                        }
                        if (isNow) Box(Modifier.align(Alignment.BottomStart).height(3.dp).width((dw * frac).dp).background(if (bf) Color.White else Accent1))
                    }
                }
            }
            // "Now" line: moves with the row content, the clipping hides it outside the window
            Box(Modifier.offset(x = ((nowSec - origin) / 60f * dpm).dp).width(2.dp).fillMaxHeight().background(Accent2.copy(alpha = 0.8f)))
            }
        }
    }
}
