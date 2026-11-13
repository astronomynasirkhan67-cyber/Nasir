package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/** Targets in the header that other parts address directly (e.g. "Show all" of the first row: Up = profile). */
object HeaderFocus {
    val profile = FocusRequester()
    val firstTab = FocusRequester()
    /** Tab that is highlighted again after coming back (e.g. from "All movies"). */
    var returnTo: String? = null
}

/** Tabs at the top next to the search (movies/series: All, Newly added, Top rated, Categories). */
class HeaderTab(val label: String, val icon: AIcon, val id: String, val onClick: () -> Unit)

/**
 * Header (round 14): search field, on movies/series the tabs, on the right "View" (card size/images), bell (reminders)
 * and the profile circle. The functions behind it already existed; only tabs and View are new.
 */
@Composable
fun HeaderBar(
    modifier: Modifier,
    onSearch: () -> Unit,
    onReminder: (Reminder) -> Unit,
    onProfile: () -> Unit,
    onNewEpisode: (String) -> Unit = {},
    tabs: List<HeaderTab> = emptyList(),
    showView: Boolean = false,
    firstIsContent: Boolean = false,
    /** Area for the playlist selection: 1 movies, 2 series, otherwise the general switch. */
    areaTab: Int = -1
) {
    Row(
        modifier.fillMaxWidth().padding(start = dpx(38), end = dpx(36), top = dpx(14)).height(dpx(52)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchPill(if (tabs.isEmpty()) 467 else 330, onSearch)
        if (tabs.isNotEmpty()) {
            Spacer(Modifier.width(dpx(22)))
            Row(horizontalArrangement = Arrangement.spacedBy(dpx(8)), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, t ->
                    NavTab(t, if (i == 0) (if (firstIsContent) ContentFocus.req else HeaderFocus.firstTab) else null)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (showView) {
            ViewButton()
            Spacer(Modifier.width(dpx(14)))
        }
        if (PlaylistStore.lists.size > 1) {
            if (areaTab in 1..2) AreaPlaylistPill(areaTab) else PlaylistSwitchButton()
            Spacer(Modifier.width(dpx(14)))
        }
        // Time and date right next to the notification bell (the only clock on screen)
        if (General.showClock) {
            ClockPill()
            Spacer(Modifier.width(dpx(14)))
        }
        BellButton(onReminder, onNewEpisode)
        Spacer(Modifier.width(dpx(14)))
        ProfileCircle(onProfile)
    }
}

@Composable
private fun SearchPill(w: Int, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.width(dpx(w)).height(dpx(50)).clip(shape)
            .background(if (f) DS.FocusFill else Color(0xCC0E0E12))
            .border(1.dp, if (f) DS.FocusFill else Color(0x33FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(20)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(14))
    ) {
        MenuIcon(4, if (f) DS.FocusInk else Color(0xFFD0D0D6), Modifier.size(dpx(22)))
        Text("Suche nach Sendern, Filmen, Serien …", color = if (f) DS.FocusInk else Tok.TextLow, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tab: calm (text and icon on a light surface), selected: white surface with dark text. */
@Composable
private fun NavTab(t: HeaderTab, fixedReq: FocusRequester?) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "navtab")
    val shape = RoundedCornerShape(50)
    // Back from "All …", "Newly added", "Top rated" or "Categories": highlight the same tab again
    // Only ONE focusRequester per element (two on one element: the return focus did not work for the first tab)
    val own = remember { FocusRequester() }
    val back = fixedReq ?: own
    LaunchedEffect(Unit) {
        if (HeaderFocus.returnTo == t.id) {
            HeaderFocus.returnTo = null
            repeat(40) { kotlinx.coroutines.delay(100); if (runCatching { back.requestFocus() }.getOrDefault(false)) return@LaunchedEffect }
        }
    }
    Row(
        Modifier.graphicsLayer { scaleX = s; scaleY = s }.height(dpx(44)).clip(shape)
            .background(if (f) DS.FocusFill else Color(0x14FFFFFF))
            .onFocusChanged { f = it.isFocused }
            .trackFocus(back)
            .clickable { HeaderFocus.returnTo = t.id; t.onClick() }
            .padding(horizontal = dpx(18)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(9))
    ) {
        AppIcon(t.icon, if (f) DS.FocusInk else Color(0xFFD8D8DE), size = dpx(18))
        Text(t.label, color = if (f) DS.FocusInk else Color.White, fontSize = spx(16), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RoundHeaderButton(icon: AIcon, modifier: Modifier = Modifier, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.size(dpx(48)).clip(CircleShape)
            .background(if (f) DS.FocusFill else Tok.Glass)
            .border(1.dp, if (f) DS.FocusFill else Color(0x26FFFFFF), CircleShape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(icon, if (f) DS.FocusInk else Color.White, size = dpx(22))
        extra()
    }
}

/** "View": card size and image type of the rows (applies immediately, is remembered). */
@Composable
private fun ViewButton() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        RoundHeaderButton(AIcon.Sliders, onClick = { open = true })
        if (open) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Popup(
                alignment = Alignment.TopEnd, offset = IntOffset(0, 140),
                onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)
            ) {
                Column(
                    Modifier.width(dpx(600)).clip(RoundedCornerShape(dpx(16))).background(Color(0xF5141418))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(dpx(16))).padding(dpx(22)),
                    verticalArrangement = Arrangement.spacedBy(dpx(14))
                ) {
                    Text("Ansicht", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                    Text("Kartengröße", color = Muted, fontSize = spx(14))
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        listOf("Klein", "Normal", "Groß").forEachIndexed { i, l ->
                            ChoiceChip(l, General.cardSize == i, if (i == General.cardSize) Modifier.focusRequester(first) else Modifier) {
                                General.setCardView(ctx, i, General.cardArt)
                            }
                        }
                    }
                    Text("Bilder bei Filmen und Serien", color = Muted, fontSize = spx(14))
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        listOf("Cover", "Szenenbild").forEachIndexed { i, l ->
                            ChoiceChip(l, General.cardArt == i, Modifier) { General.setCardView(ctx, General.cardSize, i) }
                        }
                    }
                    Text("Vorschau beim Markieren", color = Muted, fontSize = spx(14))
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        listOf("Infos auf dem Poster", "Breites Szenenbild").forEachIndexed { i, l ->
                            ChoiceChip(l, General.previewStyle == i, Modifier) { General.setPreviewStyle(ctx, i) }
                        }
                    }
                    Text("Schriftgröße (gilt für die ganze App)", color = Muted, fontSize = spx(14))
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        General.TEXT_SCALES.forEachIndexed { i, l ->
                            ChoiceChip(l, General.textScaleIdx == i, Modifier) { General.setTextScale(ctx, i) }
                        }
                    }
                    Text("Zurück schließt dieses Fenster", color = Muted, fontSize = spx(13))
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(dpx(42)).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) Accent1.copy(alpha = .22f) else Color(0x1AFFFFFF))
            .border(1.dp, if (f) DS.FocusFill else if (selected) Accent1 else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(16)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        if (selected) AppIcon(AIcon.Check, if (f) DS.FocusInk else Accent1, size = dpx(16))
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun BellButton(onReminder: (Reminder) -> Unit, onNewEpisode: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var upStatus by remember { mutableStateOf("") }
    val list = Reminders.list.sortedBy { it.start }
    val news = NewEpisodes.list.toList()
    val upd = AppUpdate.available
    Box {
        RoundHeaderButton(AIcon.Bell, onClick = { open = true }) {
            // Dot: red for new episodes or an app update, otherwise in the design color once reminders are set
            if (list.isNotEmpty() || news.isNotEmpty() || upd != null) Box(Modifier.padding(start = dpx(22), bottom = dpx(22)).size(dpx(9)).clip(CircleShape).background(if (news.isNotEmpty() || upd != null) Color(0xFFFF5A5F) else Accent1))
        }
        if (open) {
            if (list.isEmpty() && news.isEmpty() && upd == null) OptionPopup(listOf("Keine Benachrichtigungen"), -1, { open = false }, listOf(AIcon.BellOff)) { open = false }
            else {
                // First an app update, then new episodes of favorite series, then the reminders for shows
                val up = if (upd != null) listOf(Lang.tr("Update verfügbar: Version {0}").replace("{0}", upd.name) + (if (upStatus.isNotEmpty()) "  ·  " + Lang.tr(upStatus) else "")) else emptyList()
                val labels = up + news.map { it.title + "  ·  " + Lang.tr(NewEpisodeLogic.text(it)) } +
                    list.map { dayLabel(it.start) + "  " + hhmm(it.start) + "  ·  " + it.title + "  ·  " + it.chTitle }
                val icons = up.map { AIcon.Sparkle } + news.map { AIcon.Sparkle } + list.map { AIcon.Bell }
                OptionPopup(labels, -1, { open = false }, icons) { i0 ->
                    open = false
                    if (upd != null && i0 == 0) {
                        scope.launch {
                            AppUpdate.downloadAndInstall(ctx, upd) { st ->
                                upStatus = st
                                if (!st.startsWith("Lade")) android.widget.Toast.makeText(ctx, Lang.tr(st), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        android.widget.Toast.makeText(ctx, Lang.tr("Update wird geladen …"), android.widget.Toast.LENGTH_SHORT).show()
                        return@OptionPopup
                    }
                    val i = i0 - up.size
                    if (i < news.size) onNewEpisode(news[i].id) else onReminder(list[i - news.size])
                }
            }
        }
    }
}

/** Clock next to the profile (setting "Show clock", default on). Follows the time zone and time format from the settings. */
@Composable
private fun ClockPill() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L - now % 60_000L + 50L)
        }
    }
    val h24 = when (General.timeFmt) { 1 -> true; 2 -> false; else -> General.sys24 }
    val fmt = java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
    fmt.timeZone = General.zone()
    val dfmt = java.text.SimpleDateFormat("EEE, d. MMM", java.util.Locale.getDefault())
    dfmt.timeZone = General.zone()
    Column(horizontalAlignment = Alignment.End) {
        Text(fmt.format(java.util.Date(now)), color = Color.White, fontSize = spx(20), fontWeight = FontWeight.SemiBold, maxLines = 1, raw = true)
        Text(dfmt.format(java.util.Date(now)), color = Tok.TextLow, fontSize = spx(12), maxLines = 1, raw = true)
    }
}

/**
 * Playlist selection for movies and series (separate per area): "All playlists" or one of the shown playlists.
 * A playlist that is not shown is activated and loaded (as in Live TV).
 */
@Composable
private fun AreaPlaylistPill(tab: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pls = PlaylistStore.lists.toList()
    val multiView = PlaylistStore.sourceNames.size > 1
    val k = if (multiView) AreaSource.get(tab) else -1
    val opts = (if (multiView) listOf(Lang.tr("Alle Playlists")) else emptyList()) + pls.map { it.name }
    val off = if (multiView) 1 else 0
    val cur = if (multiView && k < 0) 0 else {
        val si = if (multiView) k else 0
        (pls.indices.firstOrNull { PlaylistStore.shownIndex(it) == si } ?: PlaylistStore.active) + off
    }
    DropdownPill("Playlist", opts.getOrElse(cur) { "" }, opts, cur, active = multiView && k >= 0, icon = FIcon.Grid) { pick ->
        if (multiView && pick == 0) AreaSource.set(tab, -1)
        else {
            val pi = pick - off
            val si = PlaylistStore.shownIndex(pi)
            if (si >= 0) { if (multiView) AreaSource.set(tab, si) }
            else { AreaSource.set(tab, -1); scope.launch { PlaylistStore.switchTo(ctx, pi) } }
        }
    }
}

/** Switch playlist (only with several playlists): applies to movies, series, Live TV and the home screen. */
@Composable
private fun PlaylistSwitchButton() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val pls = PlaylistStore.lists.toList()
    Box {
        RoundHeaderButton(AIcon.Download, onClick = { open = true })
        if (open) OptionPopup(pls.map { it.name }, PlaylistStore.active, { open = false }) { i ->
            open = false
            if (i != PlaylistStore.active) scope.launch { PlaylistStore.switchTo(ctx, i) }
        }
    }
}

/** Profile: only a circle with a person icon (round 14, without "Hello, name"). */
@Composable
private fun ProfileCircle(onClick: () -> Unit) {
    RoundHeaderButton(AIcon.Person, Modifier.focusRequester(HeaderFocus.profile), onClick = onClick)
}
