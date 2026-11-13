package com.ipvio.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

import androidx.compose.ui.platform.LocalContext
/**
 * Sports Hub (1.2): sports on the left, next to them games with results (ESPN, can be switched off) and what is running on your channels.
 * Without a results source the area stays as before: shows from playlist and TV guide. OK on a game starts the channel
 * on which it runs for you (matching via the team names in the TV guide).
 */
@Composable
fun SportTab(live: List<Item>, onPlay: (Item, List<Item>) -> Unit) {
    SideEffect { ContentFocus.tab = MenuLogic.SPORT }
    val ctx = LocalContext.current
    SportFavs.init(ctx)
    SportLeagues.init(ctx)
    val channels = remember(live) { live.filter { SportLogic.isSportChannel(it) } }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val slots by produceState<Pair<List<SportLogic.Slot>, List<SportLogic.Slot>>?>(null, live, tick, Xmltv.channelCount) {
        value = withContext(Dispatchers.Default) {
            SportLogic.slots(live, { Xmltv.forChannel(it.tvgId) }, System.currentTimeMillis() / 1000, 48 * 3600L, 600)
        }
    }
    val allNow = slots?.first.orEmpty()
    val allSoon = slots?.second.orEmpty()
    // Day: -1 = yesterday (results only), 0 = today, 1 = tomorrow; sport: -1 = all
    var day by remember { mutableIntStateOf(0) }
    var sport by remember { mutableIntStateOf(-1) }
    val zone = General.zone()
    val nowSec = remember(tick, slots) { System.currentTimeMillis() / 1000 }

    // Results: only while this area is open; if a game is running every 45 s, otherwise every 3 minutes
    var games by remember { mutableStateOf<List<SportData.Game>>(emptyList()) }
    var gamesLoaded by remember { mutableStateOf(false) }
    var gamesOk by remember { mutableStateOf(true) }
    LaunchedEffect(General.sportLive, day, SportLeagues.off.size) {
        if (!General.sportLive) { games = emptyList(); gamesLoaded = false; return@LaunchedEffect }
        gamesLoaded = false
        while (true) {
            val ymd = SportData.ymd(day, zone)
            val res = coroutineScope { SportLeagues.enabled().filter { it.kind == SportData.KIND_TEAM }.map { l -> async { SportData.fetch(l, ymd) } }.awaitAll() }
            games = res.flatten().sortedBy { it.start }
            gamesOk = SportData.lastOk
            gamesLoaded = true
            delay(if (games.any { it.state == 1 }) 45_000L else 180_000L)
        }
    }
    // Game calendar (sidebar "Calendar"): the next 7 days, only games of your teams
    var calendar by remember { mutableStateOf<List<SportData.Game>>(emptyList()) }
    var calLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(sport == -3, General.sportLive, SportLeagues.off.size, SportFavs.keys.size) {
        if (sport != -3 || !General.sportLive) return@LaunchedEffect
        calLoaded = false
        val out = ArrayList<SportData.Game>()
        for (d in 0..6) {
            val ymd = SportData.ymd(d, zone)
            val res = coroutineScope { SportLeagues.enabled().filter { it.kind == SportData.KIND_TEAM }.map { l -> async { SportData.fetch(l, ymd) } }.awaitAll() }
            out.addAll(res.flatten().filter { SportFavs.isFavGame(it) })
            calendar = out.distinctBy { it.id + "|" + it.league }.sortedBy { it.start }
        }
        calLoaded = true
    }
    val gameCats = remember(games) { games.map { SportLogic.catIndex(it.catName) } }

    // Shows from the TV guide: Today = now and the rest of the day, Tomorrow = tomorrow, Yesterday = nothing
    val nowCats = remember(allNow) { allNow.map { SportLogic.categoryOf(it.prog.title, it.ch.title + " " + it.ch.group) } }
    val soonCats = remember(allSoon) { allSoon.map { SportLogic.categoryOf(it.prog.title, it.ch.title + " " + it.ch.group) } }
    val win = if (day == 0) 1 else 2
    val soonIn = remember(allSoon, day, nowSec) { if (day < 0 || day > 1) emptyList() else allSoon.indices.filter { SportLogic.startsIn(win, allSoon[it].prog.start, nowSec, zone) } }
    val showNow = day == 0
    val counts = IntArray(SportLogic.CATS.size + 1)
    if (showNow) nowCats.forEach { counts[it]++ }
    soonIn.forEach { counts[soonCats[it]]++ }
    gameCats.forEach { if (it >= 0) counts[it]++ }
    val chipCats = counts.indices.filter { counts[it] > 0 || it == sport }.sortedByDescending { counts[it] }
    val now = if (!showNow) emptyList() else allNow.filterIndexed { i, _ -> sport == -1 || nowCats[i] == sport }
    val soon = soonIn.filter { sport == -1 || soonCats[it] == sport }.map { allSoon[it] }
    val gamesShown = if (sport == -2) games.filter { SportFavs.isFavGame(it) } else games.filterIndexed { i, _ -> sport == -1 || gameCats[i] == sport }
    val chCats = remember(channels) { channels.map { SportLogic.categoryOf("", it.title + " " + it.group) } }
    val chShown = if (sport == -1) channels else channels.filterIndexed { i, _ -> chCats[i] == sport }
    // Which channels show which game (team names in the show title, start not more than 3 hours away)
    SportFix.init(ctx)
    var fixGame by remember { mutableStateOf<SportData.Game?>(null) }
    var tableLeague by remember { mutableStateOf<SportData.League?>(null) }
    tableLeague?.let { SportTableDialog(it) { tableLeague = null } }
    // Computed off the main thread (every game is compared with every sports show). The page shows at once; the channels
    // of a game appear when the result is there, and the previous result stays visible while a new one is computed.
    val fixes = SportFix.map.toMap()
    val matched by produceState(emptyMap<String, List<Item>>(), games, allNow, allSoon, channels, live, fixes) {
        value = withContext(Dispatchers.Default) {
            val all = allNow + allSoon
            val pool = live + channels
            games.associate { g ->
                val auto = all.filter { s ->
                    (kotlin.math.abs(s.prog.start - g.start) <= 3 * 3600 || (s.live && g.state == 1)) && SportData.matches(g, s.prog.title + " " + s.prog.desc)
                }.map { it.ch }.distinctBy { it.id }
                val fixed = fixes[SportFix.key(g)]?.let { id -> pool.firstOrNull { it.id == id } }
                g.id + "|" + g.league to (if (fixed != null) listOf(fixed) + auto.filter { it.id != fixed.id } else auto)
            }
        }
    }
    fixGame?.let { g ->
        Dialog(onDismissRequest = { fixGame = null }) {
            Column(
                Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusM)).padding(dpx(16)),
                verticalArrangement = Arrangement.spacedBy(dpx(8))
            ) {
                Text("Sender korrigieren", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                Text(g.home.name + " – " + g.away.name, color = Muted, fontSize = spx(14), raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pool = remember(channels, live) { channels.ifEmpty { live } }
                LazyColumn(Modifier.height(dpx(300)).width(dpx(420)), verticalArrangement = Arrangement.spacedBy(dpx(6))) {
                    if (SportFix.map.containsKey(SportFix.key(g))) item(key = "reset") {
                        FixRow("Zurücksetzen (automatisch)", Modifier) { SportFix.set(ctx, g, null); fixGame = null }
                    }
                    itemsIndexed(pool, key = { i, c -> c.id + "#" + i }) { _, c ->
                        FixRow(c.title, Modifier) { SportFix.set(ctx, g, c.id); fixGame = null }
                    }
                }
            }
        }
    }
    val nothing = now.isEmpty() && soon.isEmpty() && chShown.isEmpty() && gamesShown.isEmpty()
    val hasAnything = channels.isNotEmpty() || allNow.isNotEmpty() || allSoon.isNotEmpty() || games.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        if (!hasAnything && slots != null && (gamesLoaded || !General.sportLive)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState(AIcon.Live, "Keine Sportsender gefunden", "Sportsender werden an Kategorie und Namen erkannt (z. B. „Sport“, „DAZN“, „Eurosport“). Sport-Sendungen erscheinen, sobald der TV-Guide geladen ist.")
                Spacer(Modifier.height(dpx(16)))
                FilterPill(if (General.sportLive) "Live-Ergebnisse: An" else "Live-Ergebnisse: Aus", false, Modifier.focusRequester(ContentFocus.req)) { General.setSportLive(ctx, !General.sportLive) }
            }
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            // Sports
            LazyColumn(
                Modifier.width(dpx(270)).fillMaxHeight(),
                contentPadding = PaddingValues(start = dpx(30), end = dpx(10), top = dpx(30), bottom = dpx(40)),
                verticalArrangement = Arrangement.spacedBy(dpx(8))
            ) {
                item(key = "t") { Text("Sport", color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = dpx(10))) }
                item(key = "all") { HubSportRow("Alle", counts.sum(), sport == -1, Modifier.focusRequester(ContentFocus.req)) { sport = -1 } }
                item(key = "cal") { HubSportRow("Kalender", calendar.size, sport == -3, Modifier) { sport = -3 } }
                item(key = "fav") { HubSportRow("Meine Teams", games.count { SportFavs.isFavGame(it) }, sport == -2, Modifier) { sport = -2 } }
                itemsIndexed(chipCats, key = { _, c -> "c$c" }) { _, c -> HubSportRow(SportLogic.catName(c), counts[c], sport == c, Modifier) { sport = c } }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().padding(end = dpx(40)),
                contentPadding = PaddingValues(top = dpx(30), bottom = dpx(60)),
                verticalArrangement = Arrangement.spacedBy(dpx(24))
            ) {
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(10)), contentPadding = PaddingValues(vertical = dpx(4), horizontal = dpx(4))) {
                        item(key = "dm1") { FilterPill("Gestern", day == -1) { day = -1 } }
                        item(key = "d0") { FilterPill("Heute", day == 0) { day = 0 } }
                        item(key = "d1") { FilterPill("Morgen", day == 1) { day = 1 } }
                        // Other days of the week: only results and schedule of the leagues
                        items(5, key = { "dx$it" }) { k ->
                            val d = k + 2
                            val f = java.text.SimpleDateFormat("EEE d.M.", java.util.Locale.getDefault()).apply { timeZone = zone }
                            FilterPill(f.format(java.util.Date(System.currentTimeMillis() + d * 86_400_000L)), day == d, raw = true) { day = d }
                        }
                        item(key = "sep") { Box(Modifier.width(2.dp).height(dpx(28)).background(DS.Overlay3)) }
                        item(key = "alert") {
                            SportAlerts.init(ctx)
                            FilterPill(if (SportAlerts.enabled) "Hinweise: An" else "Hinweise: Aus", false) { SportAlerts.setEnabled(ctx, !SportAlerts.enabled) }
                        }
                        item(key = "adelay") {
                            FilterPill(Lang.tr("Verzögerung") + ": " + SportAlerts.delaySec + " s", false) { SportAlerts.cycleDelay(ctx) }
                        }
                        item(key = "live") {
                            FilterPill(if (General.sportLive) "Live-Ergebnisse: An" else "Live-Ergebnisse: Aus", false) { General.setSportLive(ctx, !General.sportLive) }
                        }
                    }
                }
                item(key = "status") {
                    val msg = when {
                        slots == null -> "Lade …"
                        General.sportLive && !gamesLoaded -> "Ergebnisse werden geladen …"
                        General.sportLive && !gamesOk && games.isEmpty() -> "Ergebnisse gerade nicht erreichbar – es werden nur Playlist und TV-Guide gezeigt."
                        General.sportLive -> "Ergebnisse: ESPN (inoffizielle Quelle) · ${channels.size} Sportsender"
                        else -> "${channels.size} Sportsender · Ergebnisse sind aus"
                    }
                    Text(msg, color = Muted, fontSize = spx(15), modifier = Modifier.padding(horizontal = dpx(4)))
                }
                if (Xmltv.channelCount == 0 && slots != null) item(key = "noepg") {
                    Text("TV-Guide wird noch geladen", color = Muted, fontSize = spx(15), modifier = Modifier.padding(horizontal = dpx(4)))
                }
                // My teams: set notifications and mini score (the window shows an example meanwhile)
                if (sport == -2) item(key = "minicfg") {
                    SportAlerts.init(ctx)
                    androidx.compose.runtime.DisposableEffect(Unit) { SportAlerts.preview = true; onDispose { SportAlerts.preview = false } }
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text("Mini-Spielstand", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Text("Ein kleines Fenster in der Ecke zeigt den Stand, solange eines deiner Teams spielt – auch beim Film oder auf einem anderen Sender.", color = Muted, fontSize = spx(15))
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                            FilterPill(if (SportAlerts.miniOn) "Fenster: An" else "Fenster: Aus", SportAlerts.miniOn) { SportAlerts.setMini(ctx, on = !SportAlerts.miniOn) }
                            FilterPill(Lang.tr("Position") + ": " + Lang.tr(SportAlerts.POS_NAMES[SportAlerts.miniPos]), false) { SportAlerts.setMini(ctx, pos = (SportAlerts.miniPos + 1) % 4) }
                            FilterPill(Lang.tr("Größe") + ": " + Lang.tr(SportAlerts.SIZE_NAMES[SportAlerts.miniSize]), false) { SportAlerts.setMini(ctx, size = (SportAlerts.miniSize + 1) % 3) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                            FilterPill(if (SportAlerts.miniNames) "Logos und Namen" else "Nur Logos", false) { SportAlerts.setMini(ctx, names = !SportAlerts.miniNames) }
                            FilterPill(if (SportAlerts.miniTime) "Spielzeit: An" else "Spielzeit: Aus", false) { SportAlerts.setMini(ctx, time = !SportAlerts.miniTime) }
                        }
                    }
                }
                // Choose leagues (for "My teams")
                if (sport == -2) item(key = "leaguecfg") {
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text("Ligen", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Text("Abgewählte Ligen erscheinen nicht und werden nicht abgefragt.", color = Muted, fontSize = spx(15))
                        SportData.LEAGUES.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                                row.forEach { l -> FilterPill((if (SportLeagues.on(l)) "✓ " else "") + l.name, SportLeagues.on(l), raw = true) { SportLeagues.toggle(ctx, l) } }
                            }
                        }
                    }
                }
                // Calendar: games of your teams for the next 7 days, grouped by day
                if (sport == -3) {
                    item(key = "calhead") {
                        Text(
                            when {
                                !General.sportLive -> "Live-Ergebnisse sind aus. Schalte sie oben ein, dann erscheint der Kalender."
                                SportFavs.keys.isEmpty() -> "Du hast noch keine Teams gemerkt. Unter „Meine Teams“ kannst du Teams merken."
                                !calLoaded && calendar.isEmpty() -> "Kalender wird geladen …"
                                calendar.isEmpty() -> "In den nächsten 7 Tagen spielt keines deiner Teams."
                                else -> "Spiele deiner Teams in den nächsten 7 Tagen"
                            },
                            color = Muted, fontSize = spx(15), modifier = Modifier.padding(horizontal = dpx(4))
                        )
                    }
                    val dayFmt = java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.getDefault()).apply { timeZone = zone }
                    calendar.groupBy { dayFmt.format(java.util.Date(it.start * 1000)) }.forEach { (label, list) ->
                        item(key = "cal-$label") {
                            Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                                Text(label, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, raw = true)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
                                    itemsIndexed(list, key = { i, g -> g.id + "#" + g.league + "#" + i }) { _, g -> GameCard(g, emptyList(), Modifier, showChans = false) { } }
                                }
                            }
                        }
                    }
                }
                // My teams: teams of the shown games to remember or remove
                if (sport == -2) item(key = "favmgr") {
                    val teams = games.flatMap { listOf(it.home, it.away) }.distinctBy { SportFavs.key(it) }
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text("Teams merken", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Text("OK auf einem Team merkt es oder entfernt es wieder. Es erscheinen die Teams der Spiele des gewählten Tages.", color = Muted, fontSize = spx(15))
                        teams.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                                row.forEach { t -> FilterPill((if (SportFavs.has(t)) "★ " else "") + t.short, SportFavs.has(t)) { SportFavs.toggle(ctx, t) } }
                            }
                        }
                    }
                }
                // Games, one row per league
                if (gamesShown.isNotEmpty()) {
                    val byLeague = gamesShown.groupBy { it.league }
                    SportData.LEAGUES.map { it.name }.filter { it in byLeague }.forEach { lg ->
                        val list = byLeague.getValue(lg)
                        item(key = "lg-$lg") {
                            Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                                val lgObj = SportData.LEAGUES.first { it.name == lg }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                                    Text(lg, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, raw = true)
                                    if (SportTable.hasTable(lgObj)) FilterPill("Tabelle", false) { tableLeague = lgObj }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
                                    itemsIndexed(list, key = { i, g -> g.id + "#" + i }) { _, g ->
                                        val chs = matched[g.id + "|" + g.league].orEmpty()
                                        GameCard(g, chs, Modifier, onLong = { fixGame = g }) {
                                            if (chs.isEmpty()) { android.widget.Toast.makeText(ctx, Lang.tr("Kein Sender gefunden. Lange drücken zum Korrigieren."), android.widget.Toast.LENGTH_SHORT).show(); fixGame = g }
                                            else onPlay(chs.first(), channels.ifEmpty { chs })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (now.isNotEmpty()) item(key = "now") {
                    SportRow("Jetzt live auf deinen Sendern", now, first = false) { s -> onPlay(s.ch, channels.ifEmpty { now.map { it.ch } }) }
                }
                if (soon.isNotEmpty()) item(key = "soon") {
                    SportRow(if (day == 0) "Heute" else "Morgen", soon, first = false) { s -> onPlay(s.ch, channels.ifEmpty { soon.map { it.ch } }) }
                }
                if (chShown.isNotEmpty()) item(key = "chs") {
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        Text("Sportsender", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
                            itemsIndexed(chShown, key = { i, c -> c.id + "#" + i }) { _, c -> SportChannelTile(c, Modifier) { onPlay(c, chShown) } }
                        }
                    }
                }
                if (nothing && slots != null && (gamesLoaded || !General.sportLive)) item(key = "none") {
                    Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        Text("Keine Sendungen für diese Auswahl", color = Muted, fontSize = 18.sp)
                        FilterPill("Filter zurücksetzen", false) { sport = -1; day = 0 }
                    }
                }
            }
        }
    }
}

/** A sport in the list on the left: name, count, selected = frame in the highlight color. */
@Composable
private fun HubSportRow(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        modifier.fillMaxWidth().height(dpx(52)).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) Color(0x33FFFFFF) else Color(0x0FFFFFFF))
            .border(if (selected && !f) 2.dp else 0.dp, Accent1, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(16)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = spx(18), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count > 0) Text("$count", color = if (f) DS.FocusInk else Muted, fontSize = spx(15))
    }
}

/** Game card: status/kickoff, both teams with crest and score, below it the channel you have. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun GameCard(g: SportData.Game, chans: List<Item>, modifier: Modifier, onLong: () -> Unit = {}, showChans: Boolean = true, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.04f) else 1f, label = "game")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.width(dpx(330)).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(10))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
            when (g.state) {
                1 -> {
                    Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(PT.Live).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(raw = true, text = g.detail, color = Accent2, fontSize = spx(14), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                2 -> Text("Beendet", color = Muted, fontSize = spx(14), fontWeight = FontWeight.Bold)
                else -> Text(hhmm(g.start), color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold)
            }
        }
        GameTeam(g.home, g.state)
        GameTeam(g.away, g.state)
        if (showChans) Text(
            if (chans.isEmpty()) Lang.tr("Nicht in deinen Sendern")
            else Lang.tr("Auf") + ": " + chans.take(2).joinToString(", ") { it.title } + (if (chans.size > 2) " +" + (chans.size - 2) else ""),
            color = if (chans.isEmpty()) Muted else Accent2, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GameTeam(t: SportData.Team, state: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
        Box(Modifier.size(dpx(36)), contentAlignment = Alignment.Center) {
            if (t.logo != null) AsyncImage(t.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(raw = true, text = t.abbr.take(3), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            raw = true, text = t.short, color = if (state == 2 && !t.winner && t.score != null) Muted else Color.White,
            fontSize = spx(18), fontWeight = if (t.winner) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (state != 0 && t.score != null) Text(raw = true, text = t.score, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SportRow(title: String, list: List<SportLogic.Slot>, first: Boolean, onClick: (SportLogic.Slot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
        Text(title, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
            // The same channel can occur several times (several playlists, duplicate entries): the index in the key prevents the crash
            itemsIndexed(list, key = { i, s -> s.ch.id + "|" + s.prog.start + "#" + i }) { i, s ->
                SportCard(s, if (first && i == 0) Modifier.focusRequester(ContentFocus.req) else Modifier) { onClick(s) }
            }
        }
    }
}

@Composable
private fun SportCard(s: SportLogic.Slot, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "sport")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.width(dpx(330)).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            Box(Modifier.size(dpx(44)).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
                if (s.ch.logo != null) AsyncImage(s.ch.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                else Text(raw = true, text = s.ch.title.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(raw = true, text = s.ch.title, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.live) Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(PT.Live).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(raw = true, text = s.prog.title, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(raw = true, text = hhmm(s.prog.start) + "–" + hhmm(s.prog.end), color = Accent2, fontSize = 14.sp)
        if (s.live) {
            val frac = ((System.currentTimeMillis() / 1000 - s.prog.start).toFloat() / (s.prog.end - s.prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent2))
            }
        } else Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun SportChannelTile(c: Item, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "sportch")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.width(dpx(170)).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(dpx(12)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(96), dpx(64)), contentAlignment = Alignment.Center) {
            if (c.logo != null) AsyncImage(c.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(raw = true, text = c.title.take(3).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(raw = true, text = c.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FixRow(label: String, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Box(
        modifier.fillMaxWidth().clip(shape).background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 2.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(12), vertical = dpx(10))
    ) {
        Text(label, color = Color.White, fontSize = spx(16), maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
    }
}
