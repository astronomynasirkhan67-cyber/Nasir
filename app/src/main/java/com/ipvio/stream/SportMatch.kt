package com.ipvio.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Single-game data from ESPN "summary": stats, lineups, events, commentary, head to head. Missing parts stay empty; their tab then does not appear. */
object SportMatch {
    class Stat(val label: String, val home: String, val away: String)
    class Player(val name: String, val jersey: String, val pos: String, val starter: Boolean, val stats: List<String>, val sub: String = "", val id: String = "")
    /** A block of players with the same columns (NHL: forwards, defense, goalies; basketball: one block). */
    class Block(val name: String, val labels: List<String>, val players: List<Player>)
    class Side(val formation: String, val blocks: List<Block>) {
        val players: List<Player> get() = blocks.flatMap { it.players }
    }
    class Ev(val time: String, val type: String, val text: String, val goal: Boolean)
    class H2H(val date: Long, val home: String, val homeLogo: String?, val away: String, val awayLogo: String?, val score: String, val comp: String)
    class Form(val team: String, val logo: String?, val games: List<H2H>, val results: List<String>)
    class Detail(
        val stats: List<Stat>, val home: Side?, val away: Side?, val events: List<Ev>, val comments: List<Ev>,
        val venue: String, val attendance: String, val summary: String,
        val h2hTitle: String, val h2h: List<H2H>, val form: List<Form>, val soccer: Boolean
    )

    private val cache = HashMap<String, Pair<Long, Detail>>()

    private fun objs(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val r = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { r.add(it) }
        return r
    }

    private fun JSONArray?.strs(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private fun scoreOf(c: JSONObject): String {
        val s = c.opt("score")
        return when (s) { is JSONObject -> s.optString("displayValue"); null -> ""; else -> s.toString() }
    }

    private fun logoOf(t: JSONObject?): String? =
        t?.optString("logo")?.takeIf { it.isNotBlank() } ?: t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }

    fun parse(json: String, g: SportData.Game): Detail {
        val o = JSONObject(json)
        val bs = o.optJSONObject("boxscore")
        val statMap = LinkedHashMap<String, Array<String>>()
        for (t in objs(bs?.optJSONArray("teams"))) {
            val tid = t.optJSONObject("team")?.optString("id").orEmpty()
            val idx = if (t.optString("homeAway") == "home" || (g.home.id.isNotBlank() && tid == g.home.id)) 0 else 1
            for (s in objs(t.optJSONArray("statistics"))) {
                val lab = s.optString("label").ifBlank { s.optString("name") }
                val v = s.optString("displayValue")
                if (lab.isNotBlank() && v.isNotBlank()) statMap.getOrPut(lab) { arrayOf("–", "–") }[idx] = v
            }
        }
        // Soccer: lineups with formation, starters, bench and substitutions
        var home: Side? = null
        var away: Side? = null
        val rosters = objs(o.optJSONArray("rosters"))
        for (r in rosters) {
            val pl = objs(r.optJSONArray("roster")).map { p ->
                val a = p.optJSONObject("athlete")
                val pos = p.optJSONObject("position")?.optString("abbreviation").orEmpty().ifBlank { a?.optJSONObject("position")?.optString("abbreviation").orEmpty() }
                val sub = when { p.optBoolean("subbedIn") -> "in"; p.optBoolean("subbedOut") -> "out"; else -> "" }
                Player(a?.optString("displayName").orEmpty(), p.optString("jersey").ifBlank { a?.optString("jersey").orEmpty() }, if (pos == "SUB") "" else pos,
                    p.optBoolean("starter"), emptyList(), sub, a?.optString("id").orEmpty()) to p.optString("formationPlace").toIntOrNull()
            }.sortedBy { it.second ?: 99 }.map { it.first }.filter { it.name.isNotBlank() }
            val s = Side(r.optString("formation"), listOf(Block("", emptyList(), pl)))
            if (r.optString("homeAway") == "home") home = s else away = s
        }
        // Other team sports: player statistics from the box score (one block per group, e.g. forwards / goalies)
        if (home == null && away == null) for (t in objs(bs?.optJSONArray("players"))) {
            val isHome = t.optJSONObject("team")?.optString("id") == g.home.id
            val blocks = objs(t.optJSONArray("statistics")).map { grp ->
                val labels = grp.optJSONArray("labels").strs()
                val pl = objs(grp.optJSONArray("athletes")).map { p ->
                    val a = p.optJSONObject("athlete")
                    Player(a?.optString("displayName").orEmpty(), a?.optString("jersey").orEmpty(), a?.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                        p.optBoolean("starter"), p.optJSONArray("stats").strs(), "", a?.optString("id").orEmpty())
                }.filter { it.name.isNotBlank() }
                Block(grp.optString("text").ifBlank { grp.optString("name") }, labels, pl)
            }.filter { it.players.isNotEmpty() }
            if (blocks.isNotEmpty()) { val s = Side("", blocks); if (isHome) home = s else away = s }
        }
        val events = objs(o.optJSONArray("keyEvents")).map { e ->
            val type = e.optJSONObject("type")?.optString("text").orEmpty()
            val who = objs(e.optJSONArray("participants")).mapNotNull { it.optJSONObject("athlete")?.optString("displayName")?.takeIf { n -> n.isNotBlank() } }
            val team = e.optJSONObject("team")?.optString("displayName").orEmpty()
            val txt = e.optString("text").ifBlank { listOf(type, who.joinToString(", "), if (team.isNotBlank()) "($team)" else "").filter { it.isNotBlank() }.joinToString(" ") }
            Ev(e.optJSONObject("clock")?.optString("displayValue").orEmpty(), type, txt, e.optBoolean("scoringPlay"))
        }.filter { it.text.isNotBlank() }.toMutableList()
        val plays = objs(o.optJSONArray("plays")).map { p ->
            val per = p.optJSONObject("period")?.optString("displayValue").orEmpty()
            val clk = p.optJSONObject("clock")?.optString("displayValue").orEmpty()
            Ev((shortPeriod(per) + " " + clk).trim(), p.optJSONObject("type")?.optString("text").orEmpty(), p.optString("text"), p.optBoolean("scoringPlay"))
        }.filter { it.text.isNotBlank() }
        // US sports: the scoring plays are the important events
        if (events.isEmpty()) events.addAll(plays.filter { it.goal })
        val comments = ArrayList<Ev>()
        objs(o.optJSONArray("commentary")).map { c -> Ev(c.optJSONObject("time")?.optString("displayValue").orEmpty(), "", c.optString("text"), false) }
            .filter { it.text.isNotBlank() }.asReversed().take(150).forEach { comments.add(it) }
        if (comments.isEmpty()) plays.asReversed().take(150).forEach { comments.add(it) }
        // Head to head: earlier games of both teams (soccer: last matchups, US sports: season series)
        val ss = objs(o.optJSONArray("seasonseries"))
        val h2h = ss.flatMap { s ->
            objs(s.optJSONArray("events")).mapNotNull { e ->
                val cs = objs(e.optJSONArray("competitors"))
                val h = cs.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
                val a = cs.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
                val ht = h.optJSONObject("team")
                val at = a.optJSONObject("team")
                val hs = scoreOf(h)
                val asc = scoreOf(a)
                H2H(SportData.parseDate(e.optString("date")), ht?.optString("displayName").orEmpty(), logoOf(ht), at?.optString("displayName").orEmpty(), logoOf(at),
                    if (hs.isNotBlank() && asc.isNotBlank() && e.optString("status") == "post") "$hs – $asc" else "", e.optString("competitionName"))
            }
        }.sortedByDescending { it.date }
        val h2hTitle = ss.firstOrNull()?.let { listOf(it.optString("title"), it.optString("summary")).filter { x -> x.isNotBlank() }.joinToString(" · ") }.orEmpty()
        // Form: last five games of each team
        val form = objs(o.optJSONArray("lastFiveGames")).map { f ->
            val t = f.optJSONObject("team")
            val evs = objs(f.optJSONArray("events"))
            Form(t?.optString("displayName").orEmpty(), logoOf(t),
                evs.map { e ->
                    val opp = e.optJSONObject("opponent")
                    val home = e.optString("atVs") != "@"
                    val me = t?.optString("displayName").orEmpty()
                    H2H(SportData.parseDate(e.optString("gameDate")), if (home) me else opp?.optString("displayName").orEmpty(), if (home) logoOf(t) else logoOf(opp),
                        if (home) opp?.optString("displayName").orEmpty() else me, if (home) logoOf(opp) else logoOf(t), e.optString("score"), e.optString("leagueAbbreviation").ifBlank { e.optString("competitionName") })
                }, evs.map { it.optString("gameResult") })
        }.filter { it.games.isNotEmpty() }
        val gi = o.optJSONObject("gameInfo")
        val art = o.optJSONObject("article")
        val summary = listOf(art?.optString("headline").orEmpty(), art?.optString("description").orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
        return Detail(
            statMap.map { Stat(it.key, it.value[0], it.value[1]) }, home, away, events, comments,
            gi?.optJSONObject("venue")?.optString("fullName").orEmpty(), gi?.optString("attendance").orEmpty().takeIf { it != "0" }.orEmpty(),
            summary, h2hTitle, h2h, form, rosters.isNotEmpty() || g.league.let { SportData.league(it)?.path?.startsWith("soccer/") == true }
        )
    }

    private fun shortPeriod(p: String): String {
        val n = Regex("\\d+").find(p)?.value ?: return p.take(3)
        return when {
            p.contains("Quarter", true) -> "Q$n"
            p.contains("Period", true) -> "P$n"
            p.contains("Inning", true) -> (if (p.contains("Top", true)) "▲" else if (p.contains("Bottom", true)) "▼" else "") + n
            p.contains("Half", true) -> "H$n"
            else -> p.take(3)
        }
    }

    suspend fun fetch(l: SportData.League, g: SportData.Game): Detail? = withContext(Dispatchers.IO) {
        if (l.kind != SportData.KIND_TEAM) return@withContext null
        val key = l.path + "|" + g.id
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[key] }
        if (hit != null && now - hit.first < 25_000) return@withContext hit.second
        try {
            val req = Request.Builder().url("https://site.api.espn.com/apis/site/v2/sports/${l.path}/summary?event=${g.id}")
                .header("User-Agent", "IPVIO-Stream").build()
            Net.withTimeouts(8, 12).newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext hit?.second
                val d = parse(r.body?.string().orEmpty(), g)
                synchronized(cache) { if (cache.size > 80) cache.clear(); cache[key] = now to d }
                d
            }
        } catch (_: Exception) {
            hit?.second
        }
    }

    /** One sentence about the game, built from the score (used if ESPN delivers no match report). */
    fun sentence(g: SportData.Game): String {
        if (g.solo) return ""
        val h = g.home.score?.toIntOrNull()
        val a = g.away.score?.toIntOrNull()
        val score = (g.home.score ?: "") + ":" + (g.away.score ?: "")
        return when {
            statusKind(g) >= 3 -> statusText(g)
            g.state == 0 -> Lang.tr("Beginn: {0}").replace("{0}", dayTime(g.start))
            h == null || a == null -> ""
            h == a -> if (g.state == 1) Lang.tr("Es steht {0} zwischen {1} und {2}.").replace("{0}", score).replace("{1}", g.home.name).replace("{2}", g.away.name)
                else Lang.tr("{0} und {1} trennen sich {2}.").replace("{0}", g.home.name).replace("{1}", g.away.name).replace("{2}", score)
            else -> {
                val lead = if (h > a) g.home.name else g.away.name
                val other = if (h > a) g.away.name else g.home.name
                val s = if (h > a) "$h:$a" else "$a:$h"
                Lang.tr(if (g.state == 1) "{0} führt {1} gegen {2}." else "{0} gewinnt {1} gegen {2}.").replace("{0}", lead).replace("{1}", s).replace("{2}", other)
            }
        }
    }
}

/** Columns that matter per sport for the player tables (in this order); otherwise the first five. */
private val PREFERRED = listOf("MIN", "PTS", "REB", "AST", "FG", "G", "A", "SOG", "+/-", "TOI", "SA", "SV", "SV%", "GA", "AB", "R", "H", "RBI", "HR", "IP", "ER", "K",
    "C/ATT", "YDS", "TD", "INT", "CAR", "REC")

private fun columns(b: SportMatch.Block): List<Int> {
    val pref = PREFERRED.mapNotNull { k -> b.labels.indexOf(k).takeIf { it >= 0 } }.distinct().take(6)
    return if (pref.isNotEmpty()) pref else b.labels.indices.take(5).toList()
}

/** Match Center: header with teams, score and Watch Live; tabs appear only if ESPN delivers data for them and fit the sport. */
@Composable
internal fun MatchCenterPage(g0: SportData.Game, hub: HubCtx) {
    val g = SportFeed.latest[g0.key] ?: g0
    val l = remember(g.league) { SportData.leagueOf(g) }
    val kind = l?.kind ?: SportData.KIND_TEAM
    var done by remember(g.key) { mutableStateOf(false) }
    val d by produceState<SportMatch.Detail?>(null, g.key) {
        while (true) {
            val r = if (l != null) SportMatch.fetch(l, g) else null
            if (r != null) value = r
            done = true
            if ((SportFeed.latest[g.key] ?: g).state != 1) break
            delay(30_000)
        }
    }
    // UFC: the other fights of the same card
    val card by produceState<List<SportData.Game>>(emptyList(), g.key) {
        if (kind == SportData.KIND_FIGHT && l != null) value = SportData.fetchCurrent(l).filter { it.group == g.group }.sortedByDescending { it.start }
    }
    val det = d
    val tabs = ArrayList<String>().apply {
        add("Übersicht")
        when (kind) {
            SportData.KIND_RACE, SportData.KIND_GOLF -> if (g.field.isNotEmpty()) add("Klassement")
            SportData.KIND_FIGHT -> if (card.size > 1) add("Kampfabend")
            SportData.KIND_TEAM -> if (det != null) {
                if (det.stats.isNotEmpty()) add("Statistik")
                if (det.home?.players?.isNotEmpty() == true || det.away?.players?.isNotEmpty() == true) add(if (det.soccer) "Aufstellung" else "Spieler")
                if (det.events.isNotEmpty()) add("Ereignisse")
                if (det.comments.isNotEmpty()) add("Kommentar")
                if (det.h2h.isNotEmpty() || det.form.isNotEmpty()) add("Direktvergleich")
            }
        }
    }
    var tab by remember(g.key) { mutableStateOf("Übersicht") }
    val cur = if (tab in tabs) tab else "Übersicht"
    val chans = hub.chans(g)
    val first = remember { FocusRequester() }
    HubPageFrame(g.groupName, listOf(g.note, if (l != null && l.name != g.groupName) l.name else "").filter { it.isNotBlank() }.joinToString(" · "), { hub.nav.pop() }, first) {
        HubPanel(Modifier.fillMaxWidth()) {
            MatchHeader(g, 84)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                WatchLiveButton(chans) { hub.watch(g) }
                FilterPill(if (chans.isEmpty()) "Sender zuordnen" else Lang.tr("Sender") + " (" + chans.size + ")", false, Modifier.trackFocus(), aicon = AIcon.Live, raw = chans.isNotEmpty()) {
                    hub.nav.open(HubPage.Watch(g))
                }
                if (kind == SportData.KIND_TEAM && l != null) {
                    if (g.home.id.isNotBlank()) FilterPill(g.home.short, false, Modifier.trackFocus(), raw = true) { hub.nav.open(HubPage.Team(l, g.home)) }
                    if (g.away.id.isNotBlank()) FilterPill(g.away.short, false, Modifier.trackFocus(), raw = true) { hub.nav.open(HubPage.Team(l, g.away)) }
                }
                if (l != null) FilterPill(l.name, false, Modifier.trackFocus(), raw = true, aicon = AIcon.Lists) { hub.nav.open(HubPage.League(l)) }
                // Score in a small window in the corner while you watch something else (shown until the game ends)
                if (!g.solo && g.state != 2) {
                    val pinCtx = androidx.compose.ui.platform.LocalContext.current
                    SportAlerts.init(pinCtx)
                    val on = SportAlerts.isPinned(g)
                    FilterPill(if (on) "Score-Overlay: An" else "Score-Overlay: Aus", on, Modifier.trackFocus(), aicon = AIcon.Live) { SportAlerts.togglePin(pinCtx, g) }
                }
            }
        }
        HubTabs(tabs, cur, first) { tab = it }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = dpx(30)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            when (cur) {
                "Statistik" -> statisticsItems(det?.stats.orEmpty())
                "Aufstellung", "Spieler" -> lineupItems(det, g, l, hub)
                "Ereignisse" -> timelineItems(det?.events.orEmpty())
                "Kommentar" -> timelineItems(det?.comments.orEmpty())
                "Direktvergleich" -> h2hItems(det)
                "Klassement" -> fieldItems(g)
                "Kampfabend" -> items(card, key = { it.key }) { x -> MatchRow(x, false) { hub.nav.open(HubPage.Match(x)) } }
                else -> overviewItems(g, det, done, kind)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewItems(g: SportData.Game, det: SportMatch.Detail?, done: Boolean, kind: Int) {
    item(key = "panels") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            HubPanel(Modifier.weight(1f)) {
                Text("Spielbericht", color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold)
                val text = det?.summary?.takeIf { it.isNotBlank() } ?: SportMatch.sentence(g)
                if (text.isNotBlank()) Text(raw = true, text = text, color = Tok.TextMid, fontSize = spx(16), maxLines = 6, overflow = TextOverflow.Ellipsis)
                val info = listOf(g.note, det?.venue?.takeIf { it.isNotBlank() } ?: g.venue, g.tv.takeIf { it.isNotBlank() }?.let { "TV: $it" } ?: "",
                    det?.attendance?.takeIf { it.isNotBlank() }?.let { Lang.tr("Zuschauer") + ": " + it } ?: "").filter { it.isNotBlank() }.distinct()
                info.forEach { Text(raw = true, text = it, color = Muted, fontSize = spx(15)) }
                if (kind == SportData.KIND_TEAM && det == null) Text(if (done) "Keine Details verfügbar." else "Lade Details …", color = Muted, fontSize = spx(15))
            }
            val key = det?.events.orEmpty().filter { e ->
                val t = e.type.lowercase()
                e.goal || t.contains("card") || t.contains("penalty")
            }
            if (key.isNotEmpty()) HubPanel(Modifier.weight(1f)) {
                Text("Wichtige Ereignisse", color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold)
                key.take(7).forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        Text(raw = true, text = e.time, color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(60)))
                        EventMark(e.type)
                        Text(raw = true, text = e.text, color = Tok.TextMid, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    // Score per period (quarters, periods, innings) or sets in tennis
    if (!g.solo && (g.homeLines.isNotEmpty() || g.awayLines.isNotEmpty())) {
        val n = maxOf(g.homeLines.size, g.awayLines.size)
        val tennis = kind == SportData.KIND_TENNIS
        item(key = "lh") {
            FRow {
                Text(if (tennis) "Satz" else "Abschnitt", color = Muted, fontSize = spx(15), modifier = Modifier.weight(1f))
                (1..n).forEach { i -> Text(raw = true, text = "$i", color = Muted, fontSize = spx(15), textAlign = TextAlign.Center, modifier = Modifier.width(dpx(56))) }
                Text(if (tennis) "Sätze" else "Gesamt", color = Muted, fontSize = spx(15), textAlign = TextAlign.Center, modifier = Modifier.width(dpx(80)))
            }
        }
        listOf(g.home to g.homeLines, g.away to g.awayLines).forEachIndexed { k, (t, ls) ->
            item(key = "l$k") {
                FRow {
                    TeamName(t, g.state, Modifier.weight(1f), logoSize = 24)
                    (0 until n).forEach { i -> Text(raw = true, text = ls.getOrElse(i) { "" }, color = Tok.TextHi, fontSize = spx(16), textAlign = TextAlign.Center, modifier = Modifier.width(dpx(56))) }
                    Text(raw = true, text = t.score ?: "", color = Accent2, fontSize = spx(17), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.width(dpx(80)))
                }
            }
        }
    }
    if (g.solo && g.field.isNotEmpty()) {
        item(key = "top") { Text("Spitze", color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = dpx(8))) }
        fieldItems(g, 5)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fieldItems(g: SportData.Game, max: Int = Int.MAX_VALUE) {
    val list = g.field.take(max)
    items(list.size) { i ->
        val e = list[i]
        FRow {
            Text(raw = true, text = e.pos, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(44)))
            TeamLogo(e.flag, "", dpx(24))
            Text(raw = true, text = e.name, color = Tok.TextHi, fontSize = spx(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.extra.isNotBlank()) Text(raw = true, text = e.extra, color = Muted, fontSize = spx(15))
            if (e.value.isNotBlank()) Text(raw = true, text = e.value, color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(70)))
        }
    }
    if (g.field.isEmpty()) item { FRow { Text("Noch keine Ergebnisse", color = Muted, fontSize = spx(15)) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lineupItems(det: SportMatch.Detail?, g: SportData.Game, l: SportData.League?, hub: HubCtx) {
    if (det == null) return
    listOf(g.home to det.home, g.away to det.away).forEachIndexed { k, (t, side) ->
        if (side == null) return@forEachIndexed
        item(key = "t$k") {
            Row(Modifier.fillMaxWidth().padding(top = dpx(10), start = dpx(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                TeamLogo(t.logo, t.abbr, dpx(28))
                Text(raw = true, text = t.name + (if (side.formation.isNotBlank()) "  ·  " + side.formation else ""), color = Accent2, fontSize = spx(18), fontWeight = FontWeight.Bold)
            }
        }
        side.blocks.forEachIndexed { bi, b ->
            val cols = columns(b)
            if (det.soccer) {
                val start = b.players.filter { it.starter }
                val bench = b.players.filter { !it.starter }
                if (start.isNotEmpty()) item(key = "s$k$bi") { Text("Startelf", color = Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(4))) }
                items(start.size) { i -> PlayerLine(start[i], emptyList(), emptyList(), l, hub) }
                if (bench.isNotEmpty()) item(key = "b$k$bi") { Text("Ersatzbank", color = Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(4))) }
                items(bench.size) { i -> PlayerLine(bench[i], emptyList(), emptyList(), l, hub) }
            } else {
                item(key = "h$k$bi") {
                    Row(Modifier.fillMaxWidth().padding(start = dpx(12), end = dpx(12), top = dpx(4)), verticalAlignment = Alignment.CenterVertically) {
                        Text(raw = true, text = b.name.replaceFirstChar { it.uppercase() }, color = Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        cols.forEach { c -> Text(raw = true, text = b.labels[c], color = Muted, fontSize = spx(14), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(66))) }
                    }
                }
                items(b.players.size) { i -> PlayerLine(b.players[i], cols, b.labels, l, hub) }
            }
        }
    }
}

@Composable
private fun PlayerLine(p: SportMatch.Player, cols: List<Int>, labels: List<String>, l: SportData.League?, hub: HubCtx) {
    FRow({ if (l != null && p.id.isNotBlank()) hub.nav.open(HubPage.Player(l, p.id, p.name)) }) {
        Text(raw = true, text = p.jersey, color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(40)))
        Text(raw = true, text = p.name, color = Tok.TextHi, fontSize = spx(16), fontWeight = if (p.starter && cols.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (p.pos.isNotBlank()) Text(raw = true, text = p.pos, color = Muted, fontSize = spx(14), modifier = Modifier.width(dpx(50)))
        if (p.sub == "in") Text("eingewechselt", color = Accent2, fontSize = spx(13))
        if (p.sub == "out") Text("ausgewechselt", color = Muted, fontSize = spx(13))
        cols.forEach { c -> Text(raw = true, text = p.stats.getOrElse(c) { "" }, color = Tok.TextHi, fontSize = spx(15), textAlign = TextAlign.End, modifier = Modifier.width(dpx(66))) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.h2hItems(det: SportMatch.Detail?) {
    if (det == null) return
    if (det.h2h.isNotEmpty()) {
        item(key = "h2ht") {
            Text(raw = true, text = det.h2hTitle.ifBlank { Lang.tr("Direktvergleich") }, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(6)))
        }
        items(det.h2h.size) { i ->
            val x = det.h2h[i]
            ResultRow(x.date, x.home, x.homeLogo, x.away, x.awayLogo, x.score)
        }
    }
    det.form.forEachIndexed { k, f ->
        item(key = "f$k") {
            Row(Modifier.fillMaxWidth().padding(start = dpx(12), top = dpx(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                TeamLogo(f.logo, "", dpx(26))
                Text(raw = true, text = Lang.tr("Form") + ": " + f.team, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.Bold)
                f.results.forEach { r -> FormDot(r) }
            }
        }
        items(f.games.size) { i ->
            val x = f.games[i]
            ResultRow(x.date, x.home, x.homeLogo, x.away, x.awayLogo, x.score)
        }
    }
}

@Composable
private fun FormDot(r: String) {
    val c = when (r) { "W" -> Accent2; "L" -> DS.Danger; else -> Muted }
    Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(c.copy(alpha = 0.25f)).padding(horizontal = dpx(8), vertical = dpx(2))) {
        Text(raw = true, text = when (r) { "W" -> Lang.tr("S"); "L" -> Lang.tr("N"); "D" -> Lang.tr("U"); else -> r }, color = c, fontSize = spx(14), fontWeight = FontWeight.Bold)
    }
}
