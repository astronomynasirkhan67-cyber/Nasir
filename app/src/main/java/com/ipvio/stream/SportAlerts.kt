package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Sports notifications (1.2): a small popup when one of your favorite teams is about to play, kicks off or scores –
 * even while you watch something else. Only while the app runs; queries only if teams are remembered and the switch is on.
 * The delay holds notifications back in case you watch the game itself with a delay (IPTV often lags 20–60 s).
 */
object SportAlerts {
    class Alert(val title: String, val text: String)

    var current by mutableStateOf<Alert?>(null)
    var enabled by mutableStateOf(true)
    var delaySec by mutableIntStateOf(30)
    val DELAYS = listOf(0, 30, 60)
    // Mini score (window in the corner while one of your teams plays live)
    var miniOn by mutableStateOf(false)
    /** 0 = top left, 1 = top right, 2 = bottom left, 3 = bottom right */
    var miniPos by mutableIntStateOf(3)
    /** 0 = small, 1 = normal, 2 = large */
    var miniSize by mutableIntStateOf(1)
    var miniNames by mutableStateOf(true)
    var miniTime by mutableStateOf(true)
    /** 0 = below each other, 1 = side by side, 2 = spread over the corners (starting at the chosen corner) */
    var miniLayout by mutableIntStateOf(0)
    val LAYOUT_NAMES = listOf("Untereinander", "Nebeneinander", "In den Ecken verteilt")
    /** At most this many games are shown at once (the oldest picked game is dropped when a new one is picked). */
    const val MAX_GAMES = 4
    /** Games picked by hand in the Match Center (key "league|id"): shown in the mini window like your teams' games, until they end. */
    val pinned = androidx.compose.runtime.mutableStateListOf<String>()
    fun isPinned(g: SportData.Game) = g.key in pinned
    fun togglePin(c: Context, g: SportData.Game) {
        init(c)
        if (!pinned.remove(g.key)) {
            pinned.add(g.key)
            while (pinned.size > MAX_GAMES) pinned.removeAt(0)
            if (!miniOn) setMini(c, on = true)
        }
        savePins(c)
    }
    fun clearPins(c: Context) { pinned.clear(); savePins(c) }
    private fun savePins(c: Context) { prefs(c).edit().putString("pinned", pinned.joinToString("\n")).apply() }

    /** Running games of your teams (for the mini window). */
    var live by mutableStateOf<List<SportData.Game>>(emptyList())
    /** When setting up, the window shows an example even if nothing is running. */
    var preview by mutableStateOf(false)
    val POS_NAMES = listOf("Oben links", "Oben rechts", "Unten links", "Unten rechts")
    val SIZE_NAMES = listOf("Klein", "Normal", "Groß")
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_sportalerts")

    fun init(c: Context) {
        if (inited) return
        inited = true
        enabled = prefs(c).getBoolean("on", true)
        delaySec = prefs(c).getInt("delay", 30).takeIf { it in DELAYS } ?: 30
        miniOn = prefs(c).getBoolean("mini", false)
        miniPos = prefs(c).getInt("mpos", 3).coerceIn(0, 3)
        miniSize = prefs(c).getInt("msize", 1).coerceIn(0, 2)
        miniNames = prefs(c).getBoolean("mnames", true)
        miniTime = prefs(c).getBoolean("mtime", true)
        miniLayout = prefs(c).getInt("mlayout", 0).coerceIn(0, 2)
        pinned.clear(); pinned.addAll((prefs(c).getString("pinned", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    fun setMini(c: Context, on: Boolean = miniOn, pos: Int = miniPos, size: Int = miniSize, names: Boolean = miniNames, time: Boolean = miniTime, layout: Int = miniLayout) {
        miniOn = on; miniPos = pos.coerceIn(0, 3); miniSize = size.coerceIn(0, 2); miniNames = names; miniTime = time; miniLayout = layout.coerceIn(0, 2)
        prefs(c).edit().putInt("mlayout", miniLayout).putBoolean("mini", miniOn).putInt("mpos", miniPos).putInt("msize", miniSize).putBoolean("mnames", miniNames).putBoolean("mtime", miniTime).apply()
    }

    fun setEnabled(c: Context, v: Boolean) { enabled = v; prefs(c).edit().putBoolean("on", v).apply() }
    fun cycleDelay(c: Context) {
        delaySec = DELAYS[(DELAYS.indexOf(delaySec) + 1) % DELAYS.size]
        prefs(c).edit().putInt("delay", delaySec).apply()
    }

    private class Snap(val state: Int, val h: Int?, val a: Int?)

    /** Runs for the whole app session (started from the app scaffold). */
    suspend fun run(c: Context) {
        init(c)
        SportFavs.init(c)
        val last = HashMap<String, Snap>()
        val soonDone = HashSet<String>()
        coroutineScope {
            while (true) {
                if ((enabled || miniOn) && (SportFavs.keys.isNotEmpty() || pinned.isNotEmpty())) {
                    val ymd = SportData.ymd(0, General.zone())
                    val favOn = SportFavs.keys.isNotEmpty()
                    // Leagues of your teams plus the leagues of the games picked by hand
                    val leagues = ((if (favOn) SportData.LEAGUES.filter { it.kind == SportData.KIND_TEAM } else emptyList()) +
                        pinned.mapNotNull { SportData.league(it.substringBefore('|')) }).distinct()
                    val games = leagues.flatMap { l -> SportData.fetch(l, ymd) }.distinctBy { it.key }
                        .filter { (favOn && SportFavs.isFavGame(it)) || it.key in pinned }
                    val now = System.currentTimeMillis() / 1000
                    // Picked games first in the window; a finished picked game is removed again
                    live = games.filter { it.state == 1 }.sortedBy { if (it.key in pinned) 0 else 1 }
                    val over = games.filter { it.state == 2 && it.key in pinned }.map { it.key }
                    if (over.isNotEmpty()) { pinned.removeAll(over.toSet()); savePins(c) }
                    for (g in games) {
                        val id = g.league + "|" + g.id
                        val h = g.home.score?.toIntOrNull()
                        val a = g.away.score?.toIntOrNull()
                        val prev = last[id]
                        last[id] = Snap(g.state, h, a)
                        val line = g.home.short + " " + (h ?: 0) + " : " + (a ?: 0) + " " + g.away.short
                        if (!enabled) continue
                        if (g.state == 0 && g.start - now in 0..600 && soonDone.add(id)) {
                            post(Alert(Lang.tr("Dein Team spielt gleich"), g.home.short + " – " + g.away.short + "  ·  " + hhmm(g.start)), 0)
                        }
                        if (prev == null) continue
                        if (prev.state == 0 && g.state == 1) {
                            post(Alert(Lang.tr("Dein Team spielt jetzt"), g.home.short + " – " + g.away.short + "  ·  " + g.league), 0)
                        } else if (g.state == 1 && prev.state == 1 && (prev.h != h || prev.a != a)) {
                            launch { post(Alert(Lang.tr("Punkte!"), line), delaySec) }
                        }
                    }
                } else { last.clear(); live = emptyList() }
                delay(if (live.isNotEmpty()) 30_000L else 60_000L)
            }
        }
    }

    private suspend fun post(a: Alert, delaySec: Int) {
        if (delaySec > 0) delay(delaySec * 1000L)
        current = a
        delay(9_000L)
        if (current === a) current = null
    }
}

/** Popup (top right) and mini score (freely selectable corner) above everything; they take no focus. */
@Composable
fun SportAlertHost() {
    val a = SportAlerts.current
    if (a != null) Box(Modifier.fillMaxSize().padding(top = 28.dp, end = 36.dp), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xF2141418))
                .border(2.dp, Accent1, RoundedCornerShape(DS.RadiusM)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(a.title, color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(raw = true, text = a.text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val games = SportAlerts.live
    if (SportAlerts.miniOn && (games.isNotEmpty() || SportAlerts.preview)) {
        val k = when (SportAlerts.miniSize) { 0 -> 0.8f; 2 -> 1.25f; else -> 1f }
        fun corner(c: Int) = when (c and 3) { 0 -> Alignment.TopStart; 1 -> Alignment.TopEnd; 2 -> Alignment.BottomStart; else -> Alignment.BottomEnd }
        val shown = games.take(SportAlerts.MAX_GAMES)
        @Composable
        fun card(content: @Composable () -> Unit) = Box(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xE6141418)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusM))
                .padding(horizontal = (14 * k).dp, vertical = (10 * k).dp)
        ) { content() }
        if (SportAlerts.miniLayout == 2 && shown.size > 1) {
            // Spread over the screen: each game in its own corner (the picture edge in the middle stays free)
            shown.forEachIndexed { i, g ->
                Box(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp), contentAlignment = corner(SportAlerts.miniPos xor i)) { card { MiniGame(g, k) } }
            }
        } else Box(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp), contentAlignment = corner(SportAlerts.miniPos)) {
            card {
                if (shown.isEmpty()) MiniGame(null, k)
                else if (SportAlerts.miniLayout == 1) androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy((18 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                    shown.forEach { MiniGame(it, k) }
                }
                else Column(verticalArrangement = Arrangement.spacedBy((8 * k).dp)) { shown.forEach { MiniGame(it, k) } }
            }
        }
    }
}

/**
 * Quick settings of the score overlay (from the player and the Sports Hub): on/off, corner, arrangement, size.
 * While open, the overlay shows an example if no game is running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreOverlayDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SportAlerts.init(ctx)
    androidx.compose.runtime.DisposableEffect(Unit) { SportAlerts.preview = true; onDispose { SportAlerts.preview = false } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0.25f)
        val first = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Color(0xF2141418)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Score-Overlay", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                @Composable
                fun row(label: String, content: @Composable () -> Unit) = androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = Color(0xFFA0A6B8), fontSize = 15.sp, modifier = Modifier.widthIn(min = 100.dp))
                    content()
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row("Anzeige") {
                        FilterPill("An", SportAlerts.miniOn, Modifier.focusRequester(first)) { SportAlerts.setMini(ctx, on = true) }
                        FilterPill("Aus", !SportAlerts.miniOn) { SportAlerts.setMini(ctx, on = false) }
                    }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row("Position") { SportAlerts.POS_NAMES.forEachIndexed { i, n -> FilterPill(n, SportAlerts.miniPos == i) { SportAlerts.setMini(ctx, pos = i) } } }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row("Anordnung") { SportAlerts.LAYOUT_NAMES.forEachIndexed { i, n -> FilterPill(n, SportAlerts.miniLayout == i) { SportAlerts.setMini(ctx, layout = i) } } }
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row("Größe") { SportAlerts.SIZE_NAMES.forEachIndexed { i, n -> FilterPill(n, SportAlerts.miniSize == i) { SportAlerts.setMini(ctx, size = i) } } }
                }
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (SportAlerts.pinned.isNotEmpty()) FilterPill("Ausgewählte Spiele entfernen", false) { SportAlerts.clearPins(ctx) }
                    FilterPill("Schließen", false) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun MiniGame(g: SportData.Game?, k: Float) {
    val home = g?.home ?: SportData.Team("Team A", "Team A", "TMA", null, "1", false)
    val away = g?.away ?: SportData.Team("Team B", "Team B", "TMB", null, "0", false)
    val names = SportAlerts.miniNames
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * k).dp)) {
        MiniTeam(home, names, k, true)
        Text(raw = true, text = (home.score ?: "0") + " : " + (away.score ?: "0"), color = Color.White, fontSize = (24 * k).sp, fontWeight = FontWeight.ExtraBold)
        MiniTeam(away, names, k, false)
        if (SportAlerts.miniTime) Text(raw = true, text = g?.detail ?: "67'", color = Accent2, fontSize = (15 * k).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniTeam(t: SportData.Team, names: Boolean, k: Float, left: Boolean) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * k).dp)) {
        val logo: @Composable () -> Unit = {
            Box(Modifier.androidx_size((30 * k).dp), contentAlignment = Alignment.Center) {
                if (t.logo != null) coil.compose.AsyncImage(t.logo, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                else Text(raw = true, text = t.abbr.take(3), color = Color.White, fontSize = (11 * k).sp, fontWeight = FontWeight.Bold)
            }
        }
        val name: @Composable () -> Unit = {
            if (names) Text(raw = true, text = t.abbr.ifBlank { t.short.take(3) }, color = Color.White, fontSize = (16 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (left) { name(); logo() } else { logo(); name() }
    }
}

private fun Modifier.androidx_size(s: androidx.compose.ui.unit.Dp): Modifier = this.then(Modifier.size(s))
