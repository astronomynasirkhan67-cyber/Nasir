package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Boomer mode: a very simple UI for older people. Only big channel tiles, no menu, no search.
 * Switching it on and off needs the parental-control PIN; the exit is hidden (hold the Back key for 10 seconds).
 */
object Boomer {
    /** How long the Back key must be held before the PIN prompt appears. */
    const val EXIT_SEC = 10

    var enabled by mutableStateOf(false)
        private set

    // Appearance (stored per profile); everything can be set in Settings > Boomer mode with a live preview
    val SIZE_NAMES = listOf("Klein", "Mittel", "Groß", "Riesig")
    val SIZE_K = floatArrayOf(0.75f, 1f, 1.25f, 1.5f)
    val ROW_H = floatArrayOf(0.42f, 0.55f, 0.65f, 0.75f)
    val TEXT_K = floatArrayOf(0.85f, 1f, 1.25f, 1.5f)
    val COLOR_NAMES = listOf("Rot", "Gelb", "Grün", "Blau", "Orange", "Weiß", "Pink")
    val COLORS = listOf(Color(0xFFFF2D2D), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF3B82F6), Color(0xFFFF9F0A), Color.White, Color(0xFFFF4FA3))
    val THICK_NAMES = listOf("Dünn", "Mittel", "Dick", "Extra dick")
    val THICK_PX = floatArrayOf(5f, 9f, 13f, 20f)

    var tileSize by mutableIntStateOf(1)
        private set
    var textSize by mutableIntStateOf(1)
        private set
    var color by mutableIntStateOf(0)
        private set
    var thick by mutableIntStateOf(2)
        private set
    var inner by mutableStateOf(true)
        private set
    var anim by mutableStateOf(true)
        private set
    var logos by mutableStateOf(true)
        private set

    /** Self-assembled tiles (channel IDs in order). Empty = automatic (live favorites). */
    val custom = mutableStateListOf<String>()

    /** Self-selected movies and series (IDs in order of selection). */
    val vod = mutableStateListOf<String>()

    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_boomer")

    private fun saveVod(c: Context) { prefs(c).edit().putString("vod", vod.joinToString("\n")).apply() }

    /** Add a channel to the selection or remove it again. */
    fun toggleChannel(c: Context, id: String) { if (!custom.remove(id)) custom.add(id); saveCustom(c) }
    /** Add a movie or series to the selection or remove it again. */
    fun toggleVod(c: Context, id: String) { if (!vod.remove(id)) vod.add(id); saveVod(c) }
    fun clearVod(c: Context) { vod.clear(); saveVod(c) }

    private fun saveCustom(c: Context) { prefs(c).edit().putString("custom", custom.joinToString("\n")).apply() }

    fun setCustom(c: Context, ids: List<String>) { custom.clear(); custom.addAll(ids.distinct()); saveCustom(c) }
    fun remove(c: Context, id: String) { if (custom.remove(id)) saveCustom(c) }
    fun move(c: Context, id: String, delta: Int) {
        val i = custom.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= custom.size) return
        custom.removeAt(i); custom.add(j, id); saveCustom(c)
    }

    fun init(c: Context) {
        val p = prefs(c)
        custom.clear()
        custom.addAll((p.getString("custom", "") ?: "").split("\n").filter { it.isNotBlank() })
        vod.clear()
        vod.addAll((p.getString("vod", "") ?: "").split("\n").filter { it.isNotBlank() })
        enabled = p.getBoolean("on", false)
        tileSize = p.getInt("tile", 1).coerceIn(0, SIZE_K.lastIndex)
        textSize = p.getInt("text", 1).coerceIn(0, TEXT_K.lastIndex)
        color = p.getInt("color", 0).coerceIn(0, COLORS.lastIndex)
        thick = p.getInt("thick", 2).coerceIn(0, THICK_PX.lastIndex)
        inner = p.getBoolean("inner", true)
        anim = p.getBoolean("anim", true)
        logos = p.getBoolean("logos", true)
    }

    /** For the BootReceiver (autostart): is Boomer mode on? */
    fun isOn(c: Context): Boolean = prefs(c).getBoolean("on", false)

    fun setEnabled(c: Context, v: Boolean) { enabled = v; prefs(c).edit().putBoolean("on", v).apply() }
    fun setTileSize(c: Context, v: Int) { tileSize = v.coerceIn(0, SIZE_K.lastIndex); prefs(c).edit().putInt("tile", tileSize).apply() }
    fun setTextSize(c: Context, v: Int) { textSize = v.coerceIn(0, TEXT_K.lastIndex); prefs(c).edit().putInt("text", textSize).apply() }
    fun setColor(c: Context, v: Int) { color = v.coerceIn(0, COLORS.lastIndex); prefs(c).edit().putInt("color", color).apply() }
    fun setThick(c: Context, v: Int) { thick = v.coerceIn(0, THICK_PX.lastIndex); prefs(c).edit().putInt("thick", thick).apply() }
    fun setInner(c: Context, v: Boolean) { inner = v; prefs(c).edit().putBoolean("inner", v).apply() }
    fun setAnim(c: Context, v: Boolean) { anim = v; prefs(c).edit().putBoolean("anim", v).apply() }
    fun setLogos(c: Context, v: Boolean) { logos = v; prefs(c).edit().putBoolean("logos", v).apply() }
}

/** Selection of the tiles (without Android, testable). */
object BoomerLogic {
    /**
     * Live favorites in stored order. Without favorites: the most recently watched channels, otherwise the first [max] channels,
     * so the screen is never empty.
     */
    fun tiles(live: List<Item>, favs: List<String>, hist: List<String>, custom: List<String> = emptyList(), max: Int = 12): List<Item> {
        val byId = live.associateBy { it.id }
        // Self-compiled has priority (channels that no longer exist are skipped)
        val c = custom.mapNotNull { byId[it] }
        if (c.isNotEmpty()) return c
        val f = favs.mapNotNull { byId[it] }.distinct()
        if (f.isNotEmpty()) return f
        val h = hist.mapNotNull { byId[it] }.distinct()
        if (h.isNotEmpty()) return h.take(max)
        return live.take(max)
    }

    /** Which tile is selected first: the last watched channel, otherwise the first. */
    fun startIndex(tiles: List<Item>, hist: List<String>): Int {
        for (id in hist) {
            val i = tiles.indexOfFirst { it.id == id }
            if (i >= 0) return i
        }
        return 0
    }
}

/** All data the home screen needs – computed once in the background (playlists can be very large). */
class BoomerData(all: List<Item>) {
    val byId: Map<String, Item> = all.associateBy { it.id }
    val live: List<Item> = all.filter { it.kind == Kind.LIVE }
    val movies: List<Item> = all.filter { it.kind == Kind.MOVIE }
    val series: List<Item> = all.filter { it.kind == Kind.SERIES }
}

private class BTile(val key: String, val title: String, val logo: String?, val onClick: () -> Unit)
private class BRow(val id: String, val label: String, val tiles: List<BTile>)

private fun vodRow(id: String, label: String, list: List<Item>, onVod: (Item) -> Unit): BRow? =
    if (list.isEmpty()) null else BRow(id, label, list.map { i -> BTile("v" + i.id, i.title, i.logo) { onVod(i) } })

/** Rows of the home screen according to the block builder ([BoomerLayout]): empty rows are dropped. */
private fun buildRows(
    d: BoomerData, favs: List<String>, hist: List<String>, custom: List<String>, vod: List<String>, watch: List<String>, entries: List<Entry>,
    onPlay: (Item, List<Item>) -> Unit, onVod: (Item) -> Unit, onResume: (Entry, Item) -> Unit
): List<BRow> {
    val out = ArrayList<BRow>()
    for (id in BoomerLayout.order) {
        if (!BoomerLayout.shown(id)) continue
        val label = BoomerLayout.block(id)?.title ?: continue
        when (id) {
            "clock" -> Unit // drawn top right next to the title, not as a row
            "ch" -> {
                // Quality duplicates (HD/FHD/SD of the same channel) are merged unless the user picked tiles by hand
                val ch0 = BoomerLogic.tiles(d.live, favs, hist, custom)
                val ch = if (custom.isEmpty() && General.bundleLive) LiveBundleLogic.bundle(ch0).list else ch0
                if (ch.isNotEmpty()) out.add(BRow("ch", label, ch.map { c -> BTile("c" + c.id, c.title, c.logo) { onPlay(c, ch) } }))
            }
            "resume" -> {
                val r = entries.mapNotNull { e -> d.byId[e.itemId]?.takeIf { it.kind != Kind.LIVE }?.let { e to it } }.distinctBy { it.second.id }.take(20)
                if (r.isNotEmpty()) out.add(BRow(id, label, r.map { (e, i) -> BTile("r" + i.id, i.title, i.logo) { onResume(e, i) } }))
            }
            "vod" -> vodRow(id, label, vod.mapNotNull { d.byId[it] }.distinctBy { it.id }, onVod)?.let { out.add(it) }
            "wl" -> vodRow(id, label, watch.mapNotNull { d.byId[it] }.filter { it.kind != Kind.LIVE }.distinctBy { it.id }.take(30), onVod)?.let { out.add(it) }
            "hist" -> vodRow(id, label, hist.mapNotNull { d.byId[it] }.filter { it.kind != Kind.LIVE }.distinctBy { it.id }.take(20), onVod)?.let { out.add(it) }
            "newm" -> vodRow(id, label, d.movies.takeLast(20).reversed(), onVod)?.let { out.add(it) }
            "news" -> vodRow(id, label, d.series.takeLast(20).reversed(), onVod)?.let { out.add(it) }
        }
    }
    return out
}

/**
 * Home screen of Boomer mode: big tiles in rows (channels, continue watching, movies and series …) according to the block builder.
 * [onPlay] gets the channel and the list in which one can switch; [onVod] starts a movie or opens a series.
 */
@Composable
fun BoomerHome(
    all: List<Item>, loading: Boolean,
    onPlay: (Item, List<Item>) -> Unit, onVod: (Item) -> Unit, onResume: (Entry, Item) -> Unit
) {
    val ctx = LocalContext.current
    val data by produceState<BoomerData?>(null, all) { value = withContext(Dispatchers.Default) { BoomerData(all) } }
    val favs = Library.favs.toList()
    val hist = History.ids.toList()
    val custom = Boomer.custom.toList()
    val vod = Boomer.vod.toList()
    val watch = Library.watch.toList()
    val entries = WatchStore.entries.toList()
    val order = BoomerLayout.order
    val hidden = BoomerLayout.hidden
    val d = data
    val rows = remember(d, favs, hist, custom, vod, watch, entries, order, hidden, onPlay, onVod, onResume) {
        if (d == null) emptyList() else buildRows(d, favs, hist, custom, vod, watch, entries, onPlay, onVod, onResume)
    }
    // The first row with tiles gets the focus at the start; for channels the last watched one
    val focusRow = rows.indexOfFirst { it.id != "clock" }
    val startIdx = remember(rows) {
        val r = rows.getOrNull(focusRow)
        if (r == null || r.id != "ch") 0
        else hist.firstNotNullOfOrNull { h -> r.tiles.indexOfFirst { it.key == "c$h" }.takeIf { it >= 0 } } ?: 0
    }

    // Exit: hold the Back key
    var backHeld by remember { mutableStateOf(false) }
    var secs by remember { mutableIntStateOf(0) }
    var askPin by remember { mutableStateOf(false) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backHeld) {
        secs = 0
        if (backHeld) {
            for (i in 1..Boomer.EXIT_SEC) { delay(1000); secs = i }
            backHeld = false
            pinErr = null
            askPin = true
        }
    }
    // Never fall out of the app by accident
    BackHandler(enabled = !askPin) { }

    val firstReq = remember { FocusRequester() }
    val firstRowState = rememberLazyListState()
    LaunchedEffect(rows, askPin) {
        if (askPin || focusRow < 0) return@LaunchedEffect
        runCatching { firstRowState.scrollToItem(startIdx) }
        repeat(10) {
            delay(80)
            if (runCatching { firstReq.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B10)).onPreviewKeyEvent { e ->
            if (!askPin && e.key == Key.Back) {
                if (e.type == KeyEventType.KeyDown) backHeld = true
                else if (e.type == KeyEventType.KeyUp) backHeld = false
                true
            } else false
        }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = dpx(56), vertical = dpx(40)),
            verticalArrangement = Arrangement.spacedBy(dpx(24))
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Was möchtest du sehen?", color = Color.White, fontSize = spx(52), fontWeight = FontWeight.ExtraBold,
                    // On the phone without a remote: long-touching the title opens the PIN prompt
                    modifier = Modifier.weight(1f).pointerInput(Unit) { detectTapGestures(onLongPress = { pinErr = null; askPin = true }) }
                )
                // Clock with date, top right (follows the "clock" block switch of the layout)
                if (BoomerLayout.shown("clock")) ClockWidget(Modifier, 56, 24)
            }
            if (focusRow < 0) {
                Text(
                    if (loading || d == null) "Die Inhalte werden geladen …" else "Hier gibt es noch nichts zu sehen. Bitte lass den Bildschirm einrichten.",
                    color = Color.White, fontSize = spx(40), fontWeight = FontWeight.Bold
                )
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(dpx(28)),
                contentPadding = PaddingValues(bottom = dpx(80))
            ) {
                itemsIndexed(rows, key = { _, r -> r.id }) { ri, row ->
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        Text(
                            row.label, color = Color(0xFFD7DAE3), fontSize = spx(34 * Boomer.TEXT_K[Boomer.textSize]), fontWeight = FontWeight.Bold
                        )
                        LazyRow(
                            state = if (ri == focusRow) firstRowState else rememberLazyListState(),
                            modifier = Modifier.fillMaxWidth().height(dpx(380 * Boomer.SIZE_K[Boomer.tileSize])),
                            horizontalArrangement = Arrangement.spacedBy(dpx(36))
                        ) {
                            itemsIndexed(row.tiles, key = { i, t -> t.key + "#" + i }) { ti, t ->
                                val m = if (ri == focusRow && ti == startIdx) Modifier.focusRequester(firstReq) else Modifier
                                BoomerTile(t.title, t.logo, 1f, m, interactive = true, onClick = t.onClick)
                            }
                        }
                    }
                }
            }
            if (secs >= 2) {
                Text(
                    Lang.tr("Zum Beenden weiter halten …") + " " + (Boomer.EXIT_SEC - secs),
                    color = Color(0xFFA0A6B8), fontSize = spx(24), raw = true
                )
            }
        }
        if (askPin) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10)).padding(dpx(64)), contentAlignment = Alignment.Center) {
                PinPad(
                    "Boomer Mode beenden", "Gib die PIN ein.", pinErr,
                    { if (Parental.verify(ctx, it)) Boomer.setEnabled(ctx, false) else pinErr = Parental.pinError() },
                    { askPin = false }
                )
            }
        }
    }
}

/**
 * Big channel tile with a clearly visible highlight (color, thickness, white inner line, enlargement) according to the settings.
 * [k] shrinks everything (preview in the settings); [forceFocus] shows the highlight permanently (preview).
 */
@Composable
internal fun BoomerTile(title: String, logo: String?, k: Float, modifier: Modifier, interactive: Boolean = false, forceFocus: Boolean = false, onClick: () -> Unit = {}) {
    var f by remember { mutableStateOf(false) }
    val on = f || forceFocus
    val s by animateFloatAsState(if (on && Boomer.anim) 1.06f else 1f, label = "boomerTile")
    val shape = RoundedCornerShape(dpx(28 * k))
    val hl = Boomer.COLORS[Boomer.color]
    val bw = dpx(Boomer.THICK_PX[Boomer.thick] * k)
    var m = modifier.width(dpx(420 * Boomer.SIZE_K[Boomer.tileSize] * k)).fillMaxHeight().padding(vertical = dpx(14 * k))
        .graphicsLayer { scaleX = s; scaleY = s }
        .clip(shape).background(Color(0xFF1A1B22))
        .border(bw, if (on) hl else Color.Transparent, shape)
    if (interactive) m = m.onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)
    m = m.padding(bw)
        .border(dpx(4 * k), if (on && Boomer.inner) (if (hl == Color.White) Color.Black else Color.White) else Color.Transparent, shape)
        .padding(dpx(20 * k))
    Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (Boomer.logos && logo != null) AsyncImage(logo, title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (Boomer.logos) Text(raw = true, text = title.take(3).uppercase(), color = Color.White, fontSize = spx(72 * k), fontWeight = FontWeight.ExtraBold)
        }
        if (Boomer.logos) Spacer(Modifier.height(dpx(16 * k)))
        Text(
            raw = true, text = title, color = Color.White, fontSize = spx(44 * Boomer.TEXT_K[Boomer.textSize] * k), fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, maxLines = if (Boomer.logos) 3 else 5, overflow = TextOverflow.Ellipsis,
            modifier = if (Boomer.logos) Modifier else Modifier.weight(1f)
        )
    }
}

/**
 * Display in the player: channel name large for 3 seconds after every switch. If the channel does not play ([failed]),
 * a friendly message appears and after 6 seconds automatically [onBack] (back to the tiles).
 */
@Composable
internal fun BoomerPlayerOverlay(title: String, tick: Int, failed: Boolean, onBack: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(tick, title) { show = true; delay(3000); show = false }
    LaunchedEffect(failed) { if (failed) { delay(6000); onBack() } }
    if (failed) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(dpx(48))) {
                Text(raw = true, text = Lang.tr("Dieser Sender ist gerade nicht verfügbar"), color = Color.White, fontSize = spx(56), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(dpx(24)))
                Text(raw = true, text = Lang.tr("Du kommst gleich wieder zur Auswahl."), color = Color(0xFFCCCCCC), fontSize = spx(40), textAlign = TextAlign.Center)
            }
        }
    } else if (show) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                raw = true, text = title, color = Color.White, fontSize = spx(64 * Boomer.TEXT_K[Boomer.textSize]), fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = dpx(64)).clip(RoundedCornerShape(dpx(24))).background(Color(0xCC000000)).padding(horizontal = dpx(40), vertical = dpx(20))
            )
        }
    }
}

/** Live preview in the settings: three sample tiles, the middle one highlighted – as later on the TV. */
@Composable
private fun BoomerPreview(modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(dpx(24))).background(Color(0xFF0B0B10)).padding(dpx(24)),
        verticalArrangement = Arrangement.spacedBy(dpx(16))
    ) {
        Text("So sieht es aus", color = Color(0xFFA0A6B8), fontSize = spx(22), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(dpx(300)).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            BoomerTile("Das Erste", null, 0.4f, Modifier)
            BoomerTile("ZDF", null, 0.4f, Modifier, forceFocus = true)
            BoomerTile("RTL", null, 0.4f, Modifier)
        }
    }
}

/** Settings page "Boomer mode": PIN-protected switch and all settings for the simple screen. */
@Composable
internal fun BoomerPage(ctx: Context, onExit: () -> Unit) {
    // 0 = open, 1 = ask PIN, 2 = new PIN, 3 = confirm PIN
    var step by remember { mutableIntStateOf(if (Parental.pinSet) 1 else 2) }
    var first by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    // 0 = no selection open, 1 = choose channels, 2 = choose movies and series
    var picker by remember { mutableIntStateOf(0) }
    // After the PIN pad disappears its focus is gone; put it on the first button of the page, otherwise the next
    // OK press can land on the "back" button at the top and throw you out of this page
    val firstReq = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(step) {
        if (step == 0) { delay(200); runCatching { firstReq.requestFocus() } }
    }
    when (step) {
        1 -> PinPad("PIN eingeben", "Der Boomer Mode ist mit deiner Kindersicherungs-PIN geschützt.", err,
            { if (Parental.verify(ctx, it)) { err = null; step = 0 } else err = Parental.pinError() },
            { onExit() })
        2 -> PinPad("Neue PIN festlegen", "Für den Boomer Mode brauchst du eine 4-stellige PIN. Sie schützt auch die Kindersicherung.", err,
            { first = it; err = null; step = 3 }, { onExit() })
        3 -> PinPad("PIN bestätigen", "Gib die PIN noch einmal ein.", err,
            { if (it == first) { Parental.setPin(ctx, it); err = null; step = 0 } else { err = "Die PINs stimmen nicht überein"; step = 2 } },
            { step = 2 })
        else -> {
            Hint("Eine ganz einfache Oberfläche für Eltern und Großeltern: große Kacheln mit Bild, kein Menü, keine Suche, keine Einstellungen. Du stellst hier alles ein, die Person davor muss nur noch auswählen.")
            // No switch: once the mode runs, this page is no longer reachable (only via the hidden exit with PIN)
            var tick by remember { mutableIntStateOf(0) }
            val granted = remember(tick) { canStartFromBoot(ctx) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconLabel(
                    if (granted) AIcon.Check else AIcon.Warning,
                    if (granted) "Automatischer Start nach dem Einschalten: bereit" else "Automatischer Start: Berechtigung „Über anderen Apps einblenden“ fehlt",
                    if (granted) Accent2 else Color(0xFFFFB454), 15.sp, iconSize = 16.dp, maxLines = 2
                )
                if (!granted) SmallPill("Einstellung öffnen") { openOverlaySettings(ctx) }
                SmallPill("Prüfen", Modifier.focusRequester(firstReq)) { tick++; reportOverlayState(ctx) }
            }
            Hint("Im Boomer Mode öffnet sich IPVIO Stream von selbst, sobald der Fernseher oder die Box eingeschaltet wird. Manche Geräte (z. B. einzelne Fire-TV-Modelle) blockieren das trotzdem.")

            SectionTitle("Was der Bildschirm zeigt")
            BlockLayoutPage(
                ctx, BoomerLayout,
                "Schalte Reihen ein oder aus und schiebe sie mit „Hoch“ und „Runter“. Reihen ohne Inhalt bleiben automatisch versteckt."
            )

            SectionTitle("Sender auswählen")
            Hint("Wähle aus deiner ganzen Senderliste mit Logos die Sender, die in der Reihe „Meine Sender“ erscheinen. Ohne Auswahl zeigt der Modus deine Live-Favoriten.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Sender auswählen …", light = true) { picker = 1 }
                if (Boomer.custom.isNotEmpty()) ConfirmPill("Auswahl löschen") { Boomer.setCustom(ctx, emptyList()) }
            }
            BoomerBuilder(ctx)

            SectionTitle("Filme und Serien auswählen")
            Hint("Wähle Filme und Serien aus, die in der Reihe „Meine Filme und Serien“ erscheinen. Ein Film startet sofort, bei einer Serie öffnet sich die Folgenliste.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Filme und Serien auswählen …", light = true) { picker = 2 }
                if (Boomer.vod.isNotEmpty()) ConfirmPill("Auswahl löschen") { Boomer.clearVod(ctx) }
                Text(Lang.tr("Ausgewählt") + ": " + Boomer.vod.size, color = Muted, fontSize = 15.sp, raw = true)
            }

            SectionTitle("Aussehen")
            Row(horizontalArrangement = Arrangement.spacedBy(dpx(32))) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Größe der Kacheln")
                    BoomerChips(Boomer.SIZE_NAMES, Boomer.tileSize) { Boomer.setTileSize(ctx, it) }
                    SectionTitle("Größe der Schrift")
                    BoomerChips(Boomer.SIZE_NAMES, Boomer.textSize) { Boomer.setTextSize(ctx, it) }
                    SectionTitle("Markierung: Farbe des Rahmens")
                    BoomerChips(Boomer.COLOR_NAMES, Boomer.color, Boomer.COLORS) { Boomer.setColor(ctx, it) }
                    SectionTitle("Markierung: Dicke des Rahmens")
                    BoomerChips(Boomer.THICK_NAMES, Boomer.thick) { Boomer.setThick(ctx, it) }
                    ToggleRow("Zusätzliche helle Innenlinie", "Macht die Markierung noch deutlicher, auch bei Farbschwäche.", Boomer.inner) { Boomer.setInner(ctx, it) }
                    ToggleRow("Animationen", "Die markierte Kachel wird leicht größer. Ausschalten = alles bleibt ruhig.", Boomer.anim) { Boomer.setAnim(ctx, it) }
                    ToggleRow("Logos und Bilder zeigen", "Aus = nur der Name in großer Schrift.", Boomer.logos) { Boomer.setLogos(ctx, it) }
                }
                BoomerPreview(Modifier.width(dpx(560)))
            }
            SectionTitle("So kommst du wieder heraus")
            Hint("Zurück-Taste 10 Sekunden gedrückt halten, dann die PIN eingeben. Am Handy: die Überschrift „Was möchtest du sehen?“ lange berühren.")
            SectionTitle("Starten")
            PillButton("Boomer Mode jetzt starten", light = true) {
                Boomer.setEnabled(ctx, true)
                if (!canStartFromBoot(ctx)) openOverlaySettings(ctx)
            }
            Hint("Die App wechselt sofort zur einfachen Oberfläche. Stelle vorher alles ein. Zurück zur normalen App: Zurück-Taste 10 Sekunden halten, dann PIN.")
        }
    }
    if (picker != 0) BoomerPicker(ctx, picker == 2) { picker = 0 }
}

/** Row of choice buttons (optionally with color dots). */
@Composable
private fun BoomerChips(names: List<String>, selected: Int, colors: List<Color>? = null, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, n ->
            ChoiceChip(n, i == selected, if (colors != null) listOf(colors[i]) else emptyList()) { onPick(i) }
        }
    }
}

/** Small preview of the selected channels; OK on a tile = move left/right or remove. */
@Composable
private fun BoomerBuilder(ctx: Context) {
    val live = remember(PlaylistStore.channels) { PlaylistStore.channels.filter { it.kind == Kind.LIVE } }
    val ids = remember(live) { live.mapTo(HashSet()) { it.id } }
    val custom = Boomer.custom.toList()
    val tiles = BoomerLogic.tiles(live, Library.favs.toList(), History.ids.toList(), custom)
    var menu by remember { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    Hint(
        if (custom.isEmpty()) "Gerade automatisch: deine Live-Favoriten. Sobald du eine Kachel unten mit OK wählst oder Sender auswählst, stellst du die Reihe selbst zusammen."
        else "Du hast die Sender selbst zusammengestellt. OK auf einer Kachel: nach links oder rechts schieben, oder entfernen."
    )
    Box {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Aus Favoriten übernehmen") { Boomer.setCustom(ctx, Library.favs.filter { it in ids }) }
            PillButton("Zuletzt gesehene dazu") { Boomer.setCustom(ctx, tiles.map { it.id } + History.ids.filter { it in ids }.take(8)) }
            if (ChannelLists.lists.isNotEmpty()) PillButton("Aus Senderliste …") { listMenu = true }
        }
        if (listMenu) OptionPopup(ChannelLists.lists.map { it.name }, -1, { listMenu = false }) { i ->
            ChannelLists.lists.getOrNull(i)?.let { l -> Boomer.setCustom(ctx, l.ids.filter { it in ids }) }
            listMenu = false
        }
    }
    if (tiles.isEmpty()) {
        Hint("Noch keine Sender. Wähle oben Sender aus oder markiere sie in Live TV mit dem Stern.")
    } else Box {
        Row(
            Modifier.fillMaxWidth().height(dpx(250)).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(dpx(14))
        ) {
            tiles.forEach { t ->
                key(t.id) {
                    BoomerTile(t.title, t.logo, 0.4f, Modifier, interactive = true) {
                        // First change: "automatic" becomes an own compilation
                        if (Boomer.custom.isEmpty()) Boomer.setCustom(ctx, tiles.map { it.id })
                        menu = t.id
                    }
                }
            }
        }
        menu?.let { id ->
            OptionPopup(listOf("Nach links schieben", "Nach rechts schieben", "Entfernen"), -1, { menu = null }) { i ->
                when (i) { 0 -> Boomer.move(ctx, id, -1); 1 -> Boomer.move(ctx, id, 1); else -> Boomer.remove(ctx, id) }
                menu = null
            }
        }
    }
}

/** A row in the selection: logo, name, category and a check mark if the entry is selected. */
@Composable
private fun PickRow(title: String, sub: String, logo: String?, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (f) RowFocus else Surf)
            .border(DS.Border, if (f) Accent2 else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(logo, title, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            else Text(raw = true, text = title.take(2).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(raw = true, text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(raw = true, text = sub, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) AppIcon(AIcon.Check, Accent2, size = 30.dp) else AppIcon(AIcon.Plus, Muted, size = 30.dp)
    }
}

/**
 * Full-screen selection: the whole channel list (or all movies and series) with logos, filtered by category.
 * OK selects an entry or removes it again; "Done" closes.
 */
@Composable
private fun BoomerPicker(ctx: Context, vod: Boolean, onClose: () -> Unit) {
    val all = PlaylistStore.channels
    val list = remember(all, vod) { if (vod) all.filter { it.kind != Kind.LIVE } else all.filter { it.kind == Kind.LIVE } }
    val sel = if (vod) Boomer.vod else Boomer.custom
    val selSet = sel.toSet()
    var cat by remember { mutableStateOf("*all") }
    val groups by produceState(emptyList<String>(), list) {
        value = withContext(Dispatchers.Default) { list.map { it.group }.filter { it.isNotBlank() }.distinct().take(300) }
    }
    // The list does not reorder itself on selecting (otherwise the focus jumps); only "Selected" depends on the selection
    val selKey = if (cat == "*sel") selSet else emptySet()
    val shown = remember(list, cat, selKey) {
        when (cat) {
            "*all" -> list
            "*sel" -> list.filter { it.id in selKey }
            "*movies" -> list.filter { it.kind == Kind.MOVIE }
            "*series" -> list.filter { it.kind == Kind.SERIES }
            else -> list.filter { it.group == cat }
        }
    }
    val doneReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { doneReq.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0B0B10)).padding(horizontal = dpx(48), vertical = dpx(32)),
            verticalArrangement = Arrangement.spacedBy(dpx(16))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(24))) {
                Column(Modifier.weight(1f)) {
                    Text(if (vod) "Filme und Serien auswählen" else "Sender auswählen", color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold)
                    Text(Lang.tr("Ausgewählt") + ": " + sel.size + "   ·   " + Lang.tr("OK wählt aus oder nimmt wieder heraus"), color = Muted, fontSize = spx(16), raw = true)
                }
                PillButton("Fertig", light = true, modifier = Modifier.focusRequester(doneReq)) { onClose() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ChoiceChip("Alle", cat == "*all") { cat = "*all" } }
                item { ChoiceChip("Ausgewählte", cat == "*sel") { cat = "*sel" } }
                if (vod) {
                    item { ChoiceChip("Filme", cat == "*movies") { cat = "*movies" } }
                    item { ChoiceChip("Serien", cat == "*series") { cat = "*series" } }
                }
                itemsIndexed(groups, key = { i, g -> g + "#" + i }) { _, g -> ChoiceChip(g, cat == g) { cat = g } }
            }
            if (shown.isEmpty()) {
                Text(if (cat == "*sel") "Noch nichts ausgewählt." else "Hier gibt es nichts.", color = Muted, fontSize = spx(20))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(shown, key = { i, it -> it.id + "#" + i }) { _, it ->
                    PickRow(it.title, it.group, it.logo, it.id in selSet) {
                        if (vod) Boomer.toggleVod(ctx, it.id) else Boomer.toggleChannel(ctx, it.id)
                    }
                }
            }
        }
    }
}
