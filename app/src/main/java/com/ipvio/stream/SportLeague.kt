package com.ipvio.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Small JSON helpers shared by the ESPN readers in this file. */
private object J {
    fun get(url: String): JSONObject? = try {
        val req = Request.Builder().url(url).header("User-Agent", "IPVIO-Stream").build()
        Net.withTimeouts(8, 12).newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null }
    } catch (_: Exception) { null }

    fun objs(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val r = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { r.add(it) }
        return r
    }

    fun logo(t: JSONObject?): String? =
        t?.optString("logo")?.takeIf { it.isNotBlank() } ?: t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
}

/** Tables of all sports (ESPN standings). Columns per sport; columns without values are dropped. F1: drivers and constructors. */
object SportStandings {
    class Row(val team: SportData.Team, val rank: String, val cells: List<String>)
    class Group(val name: String, val cols: List<String>, val rows: List<Row>)

    private val cache = HashMap<String, Pair<Long, List<Group>>>()

    private fun wanted(path: String): List<Pair<String, String>> = when {
        path.startsWith("soccer/") -> listOf("gamesPlayed" to "Sp", "wins" to "S", "ties" to "U", "losses" to "N", "pointDifferential" to "TD", "points" to "Pkt")
        path.startsWith("basketball/") -> listOf("wins" to "S", "losses" to "N", "winPercent" to "PCT", "gamesBehind" to "GB", "streak" to "Serie")
        path.startsWith("hockey/") -> listOf("gamesPlayed" to "Sp", "wins" to "S", "losses" to "N", "otLosses" to "OTN", "points" to "Pkt", "pointDifferential" to "TD")
        path.startsWith("baseball/") -> listOf("wins" to "S", "losses" to "N", "winPercent" to "PCT", "gamesBehind" to "GB", "streak" to "Serie")
        path.startsWith("racing/") -> listOf("championshipPts" to "Pkt")
        else -> listOf("wins" to "S", "losses" to "N", "ties" to "U", "winPercent" to "PCT", "pointDifferential" to "TD")
    }

    private fun statOf(st: JSONArray?, name: String): String? {
        if (st == null) return null
        for (i in 0 until st.length()) {
            val s = st.optJSONObject(i) ?: continue
            if (s.optString("name") == name || s.optString("type") == name) return s.optString("displayValue").ifBlank { null }
        }
        return null
    }

    fun parse(json: String, l: SportData.League): List<Group> {
        val o = JSONObject(json)
        val nodes = ArrayList<Pair<String, JSONArray>>()
        for (c in J.objs(o.optJSONArray("children"))) {
            c.optJSONObject("standings")?.optJSONArray("entries")?.let { nodes.add(c.optString("name") to it) }
            // Some leagues have one more level (conference → division)
            for (cc in J.objs(c.optJSONArray("children"))) cc.optJSONObject("standings")?.optJSONArray("entries")?.let { nodes.add(cc.optString("name") to it) }
        }
        if (nodes.isEmpty()) o.optJSONObject("standings")?.optJSONArray("entries")?.let { nodes.add("" to it) }
        val want = wanted(l.path)
        return nodes.map { (gname, es) ->
            val raw = J.objs(es)
            val cols = want.filter { w -> raw.any { statOf(it.optJSONArray("stats"), w.first) != null } }
            val rows = raw.map { e ->
                val t = e.optJSONObject("team")
                val a = e.optJSONObject("athlete")
                val team = if (t != null) {
                    val name = t.optString("displayName")
                    SportData.Team(name, t.optString("shortDisplayName").ifBlank { name }, t.optString("abbreviation"), J.logo(t), null, false,
                        null, t.optString("color").takeIf { it.isNotBlank() }, t.optString("id"))
                } else {
                    val name = a?.optString("displayName").orEmpty()
                    SportData.Team(name, name, a?.optString("abbreviation").orEmpty(), a?.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() }, null, false,
                        null, null, a?.optString("id").orEmpty())
                }
                val st = e.optJSONArray("stats")
                Row(team, statOf(st, "rank") ?: statOf(st, "playoffSeed") ?: "", cols.map { statOf(st, it.first) ?: "–" })
            }.filter { it.team.name.isNotBlank() }
            val ordered = if (rows.all { it.rank.toIntOrNull() != null }) rows.sortedBy { it.rank.toInt() } else rows
            Group(gname, cols.map { it.second }, ordered.mapIndexed { i, r -> Row(r.team, if (r.rank.isBlank()) (i + 1).toString() else r.rank, r.cells) })
        }.filter { it.rows.isNotEmpty() }
    }

    suspend fun fetch(l: SportData.League): List<Group> = withContext(Dispatchers.IO) {
        if (l.kind == SportData.KIND_CRICKET) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[l.path] }
        if (hit != null && now - hit.first < 300_000) return@withContext hit.second
        val o = try {
            val req = Request.Builder().url("https://site.api.espn.com/apis/v2/sports/${l.path}/standings").header("User-Agent", "IPVIO-Stream").build()
            Net.withTimeouts(8, 12).newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else null }
        } catch (_: Exception) { null } ?: return@withContext hit?.second ?: emptyList()
        val g = try { parse(o, l) } catch (_: Exception) { emptyList() }
        synchronized(cache) { cache[l.path] = now to g }
        g
    }
}

/** Leader lists of a league (ESPN "statistics"): top scorers, assists … One category per list. */
object SportLeaders {
    class Leader(val name: String, val id: String, val team: String, val teamLogo: String?, val value: String, val detail: String)
    class Category(val name: String, val title: String, val leaders: List<Leader>)

    private val cache = HashMap<String, Pair<Long, List<Category>>>()

    suspend fun fetch(l: SportData.League): List<Category> = withContext(Dispatchers.IO) {
        if (l.kind != SportData.KIND_TEAM) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[l.path] }
        if (hit != null && now - hit.first < 600_000) return@withContext hit.second
        val o = J.get("https://site.api.espn.com/apis/site/v2/sports/${l.path}/statistics") ?: return@withContext hit?.second ?: emptyList()
        val r = J.objs(o.optJSONArray("stats")).map { c ->
            Category(c.optString("name"), c.optString("displayName"), J.objs(c.optJSONArray("leaders")).take(25).map { x ->
                val a = x.optJSONObject("athlete")
                val t = a?.optJSONObject("team")
                val v = x.optDouble("value", Double.NaN)
                Leader(a?.optString("displayName").orEmpty(), a?.optString("id").orEmpty(), t?.optString("displayName").orEmpty(), J.logo(t),
                    if (v.isNaN()) "" else if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v), x.optString("displayValue"))
            }.filter { it.name.isNotBlank() })
        }.filter { it.leaders.isNotEmpty() }
        synchronized(cache) { cache[l.path] = now to r }
        r
    }
}

/** Team data: record, position, games (last and next), roster and team statistics from the ESPN team endpoints. */
object SportTeamData {
    class Person(val name: String, val jersey: String, val pos: String, val extra: String, val id: String, val photo: String?)
    class StatGroup(val title: String, val stats: List<Pair<String, String>>)
    class Detail(
        val record: String, val standing: String, val rank: String, val points: String, val wld: String,
        val last: List<SportData.Game>, val next: List<SportData.Game>, val roster: List<Person>, val coach: String,
        val stats: List<StatGroup>, val venue: String
    )

    private val cache = HashMap<String, Pair<Long, Detail>>()

    private fun scoreOf(c: JSONObject): String? {
        val s = c.opt("score")
        return when (s) { is JSONObject -> s.optString("displayValue"); null -> null; else -> s.toString() }?.takeIf { it.isNotBlank() }
    }

    /** Games of the team schedule as [SportData.Game], so a click opens the normal Match Center. */
    private fun games(o: JSONObject?, l: SportData.League): List<SportData.Game> = J.objs(o?.optJSONArray("events")).mapNotNull { e ->
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
        val cs = J.objs(comp.optJSONArray("competitors"))
        val type = comp.optJSONObject("status")?.optJSONObject("type")
        val state = when (type?.optString("state")) { "in" -> 1; "post" -> 2; else -> 0 }
        fun team(c: JSONObject?): SportData.Team {
            val t = c?.optJSONObject("team")
            val name = t?.optString("displayName").orEmpty()
            return SportData.Team(name, t?.optString("shortDisplayName").orEmpty().ifBlank { name }, t?.optString("abbreviation").orEmpty(), J.logo(t),
                if (state == 0) null else c?.let { scoreOf(it) }, c?.optBoolean("winner") == true, null, t?.optString("color")?.takeIf { it.isNotBlank() }, t?.optString("id").orEmpty())
        }
        val h = cs.firstOrNull { it.optString("homeAway") == "home" }
        val a = cs.firstOrNull { it.optString("homeAway") == "away" }
        if (h == null || a == null) return@mapNotNull null
        SportData.Game(e.optString("id"), l.name, l.catName, SportData.parseDate(e.optString("date")), state, type?.optString("shortDetail").orEmpty(), team(h), team(a),
            statusName = type?.optString("name").orEmpty(), venue = comp.optJSONObject("venue")?.optString("fullName").orEmpty())
    }

    suspend fun fetch(l: SportData.League, t: SportData.Team): Detail? = withContext(Dispatchers.IO) {
        val key = l.path + "|" + t.id
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[key] }
        if (hit != null && now - hit.first < 120_000) return@withContext hit.second
        val base = "https://site.api.espn.com/apis/site/v2/sports/${l.path}/teams/${t.id}"
        val soccer = l.path.startsWith("soccer/")
        val parts = coroutineScope {
            listOf(base, "$base/schedule", "$base/roster", "$base/statistics", if (soccer) "$base/schedule?fixture=true" else "").map { u ->
                async { if (u.isBlank()) null else J.get(u) }
            }.awaitAll()
        }
        val info = parts[0]?.optJSONObject("team")
        if (parts.all { it == null }) return@withContext hit?.second
        val rec = info?.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)
        val recStats = HashMap<String, String>()
        for (s in J.objs(rec?.optJSONArray("stats"))) {
            val v = s.optDouble("value", Double.NaN)
            if (!v.isNaN()) recStats[s.optString("name")] = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
        }
        val wld = listOfNotNull(recStats["wins"]?.let { "$it " + Lang.tr("S") }, recStats["ties"]?.takeIf { soccer }?.let { "$it " + Lang.tr("U") }, recStats["losses"]?.let { "$it " + Lang.tr("N") }).joinToString(" · ")
        val all = (games(parts[1], l) + games(parts[4], l)).distinctBy { it.id }
        val people = ArrayList<Person>()
        fun add(a: JSONObject) {
            val nm = a.optString("displayName").ifBlank { a.optString("fullName") }
            if (nm.isBlank()) return
            val extra = listOf(a.optString("displayHeight"), a.optString("displayWeight"), a.optString("age").takeIf { it.isNotBlank() && it != "0" }?.let { "$it " + Lang.tr("J.") } ?: "",
                a.optString("citizenship")).filter { it.isNotBlank() }.joinToString(" · ")
            people.add(Person(nm, a.optString("jersey"), a.optJSONObject("position")?.optString("abbreviation").orEmpty(), extra, a.optString("id"),
                a.optJSONObject("headshot")?.optString("href")?.takeIf { it.isNotBlank() }))
        }
        for (a in J.objs(parts[2]?.optJSONArray("athletes"))) {
            val items = a.optJSONArray("items")
            if (items != null) J.objs(items).forEach { add(it) } else add(a)
        }
        val coachO = parts[2]?.optJSONArray("coach")?.optJSONObject(0)
        val coach = ((coachO?.optString("firstName").orEmpty()) + " " + (coachO?.optString("lastName").orEmpty())).trim()
        val stats = J.objs(parts[3]?.optJSONObject("results")?.optJSONObject("stats")?.optJSONArray("categories")).map { c ->
            StatGroup(c.optString("displayName"), J.objs(c.optJSONArray("stats")).map { s -> s.optString("displayName") to s.optString("displayValue") }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }.take(14))
        }.filter { it.stats.isNotEmpty() }
        val d = Detail(
            rec?.optString("summary").orEmpty(), info?.optString("standingSummary").orEmpty(), recStats["rank"].orEmpty(), recStats["points"].orEmpty(), wld,
            all.filter { it.state == 2 }.sortedByDescending { it.start }.take(20), all.filter { it.state != 2 }.sortedBy { it.start }.take(20),
            people, coach, stats, info?.optJSONObject("franchise")?.optJSONObject("venue")?.optString("fullName").orEmpty()
        )
        synchronized(cache) { if (cache.size > 60) cache.clear(); cache[key] = now to d }
        d
    }
}

/** Player profile (ESPN common/v3 athletes): facts and the season summary. */
object SportPlayer {
    class Detail(val name: String, val photo: String?, val flag: String?, val team: String, val teamLogo: String?, val facts: List<Pair<String, String>>,
        val statsTitle: String, val stats: List<Pair<String, String>>)

    private val cache = HashMap<String, Pair<Long, Detail>>()

    suspend fun fetch(l: SportData.League, id: String): Detail? = withContext(Dispatchers.IO) {
        val key = l.path + "|" + id
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key]?.let { if (now - it.first < 600_000) return@withContext it.second } }
        val a = J.get("https://site.api.espn.com/apis/common/v3/sports/${l.path}/athletes/$id")?.optJSONObject("athlete") ?: return@withContext null
        val t = a.optJSONObject("team")
        val facts = listOf(
            "Position" to a.optJSONObject("position")?.optString("displayName").orEmpty(),
            "Nummer" to a.optString("displayJersey"),
            "Alter" to a.optString("age").takeIf { it != "0" }.orEmpty(),
            "Geboren" to a.optString("displayDOB"),
            "Größe" to a.optString("displayHeight"),
            "Gewicht" to a.optString("displayWeight"),
            "Nation" to a.optString("citizenship")
        ).filter { it.second.isNotBlank() }
        val ss = a.optJSONObject("statsSummary")
        val d = Detail(
            a.optString("displayName"), a.optJSONObject("headshot")?.optString("href")?.takeIf { it.isNotBlank() },
            a.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() }, t?.optString("displayName").orEmpty(), J.logo(t), facts,
            ss?.optString("displayName").orEmpty(), J.objs(ss?.optJSONArray("statistics")).map { it.optString("displayName") to it.optString("displayValue") }.filter { it.first.isNotBlank() }
        )
        synchronized(cache) { if (cache.size > 100) cache.clear(); cache[key] = now to d }
        d
    }
}

/**
 * Favorites of the hub (per profile): teams with logo and league (team keys themselves stay in [SportFavs], which the score alerts use),
 * competitions, players and sports.
 */
object SportHubFavs {
    class TeamFav(val key: String, val name: String, val short: String, val logo: String?, val path: String, val id: String) {
        fun team() = SportData.Team(name, short, "", logo, null, false, null, null, id)
    }
    class PlayerFav(val id: String, val name: String, val path: String, val photo: String?)

    val leagues = androidx.compose.runtime.mutableStateListOf<String>()
    val sports = androidx.compose.runtime.mutableStateListOf<String>()
    val teams = androidx.compose.runtime.mutableStateMapOf<String, TeamFav>()
    val players = androidx.compose.runtime.mutableStateListOf<PlayerFav>()
    private var inited = false
    private fun prefs(c: android.content.Context) = Profiles.sp(c, "ipvio_sporthub")

    fun init(c: android.content.Context) {
        if (inited) return
        inited = true
        SportFavs.init(c)
        val p = prefs(c)
        leagues.addAll((p.getString("l", "") ?: "").split("|").filter { it.isNotBlank() })
        sports.addAll((p.getString("s", "") ?: "").split("|").filter { it.isNotBlank() })
        (p.getString("t", "") ?: "").split("\n").forEach { line ->
            val x = line.split("\t")
            if (x.size >= 6) teams[x[0]] = TeamFav(x[0], x[1], x[2], x[3].ifBlank { null }, x[4], x[5])
        }
        (p.getString("p", "") ?: "").split("\n").forEach { line ->
            val x = line.split("\t")
            if (x.size >= 4) players.add(PlayerFav(x[0], x[1], x[2], x[3].ifBlank { null }))
        }
    }

    private fun clean(s: String?) = (s ?: "").replace("\t", " ").replace("\n", " ")

    private fun save(c: android.content.Context) {
        prefs(c).edit()
            .putString("l", leagues.joinToString("|")).putString("s", sports.joinToString("|"))
            .putString("t", teams.values.joinToString("\n") { listOf(it.key, it.name, it.short, it.logo, it.path, it.id).joinToString("\t") { s -> clean(s) } })
            .putString("p", players.joinToString("\n") { listOf(it.id, it.name, it.path, it.photo).joinToString("\t") { s -> clean(s) } })
            .apply()
    }

    fun isTeam(t: SportData.Team) = SportFavs.has(t)

    fun toggleTeam(c: android.content.Context, l: SportData.League, t: SportData.Team) {
        init(c)
        SportFavs.toggle(c, t)
        val k = SportFavs.key(t)
        if (SportFavs.has(t)) teams[k] = TeamFav(k, t.name, t.short, t.logo, l.path, t.id) else teams.remove(k)
        save(c)
    }

    fun hasPlayer(id: String) = players.any { it.id == id }
    fun togglePlayer(c: android.content.Context, l: SportData.League, id: String, name: String, photo: String?) {
        init(c)
        val i = players.indexOfFirst { it.id == id }
        if (i >= 0) players.removeAt(i) else players.add(PlayerFav(id, name, l.path, photo))
        save(c)
    }

    fun toggleLeague(c: android.content.Context, path: String) { init(c); if (!leagues.remove(path)) leagues.add(path); save(c) }
    fun toggleSport(c: android.content.Context, key: String) { init(c); if (!sports.remove(key)) sports.add(key); save(c) }

    /** Favorite teams for display: with details if known, otherwise only the name (saved before the hub existed). */
    fun teamList(): List<TeamFav> = SportFavs.keys.map { k -> teams[k] ?: TeamFav(k, k, k, null, "", "") }

    fun isFavGame(g: SportData.Game): Boolean {
        val l = SportData.leagueOf(g)
        return SportFavs.isFavGame(g) || (l != null && (sports.contains(l.sport) || leagues.contains(l.path))) || sports.contains(g.catName)
    }

    fun isEmpty() = SportFavs.keys.isEmpty() && leagues.isEmpty() && sports.isEmpty() && players.isEmpty()
}

/** News per league (ESPN news). Only headline, short text and image; the article itself is not loaded. */
object SportNews {
    class Article(val headline: String, val text: String, val image: String?, val published: String, val league: String)
    private val cache = HashMap<String, Pair<Long, List<Article>>>()

    suspend fun fetch(l: SportData.League): List<Article> = withContext(Dispatchers.IO) {
        if (l.kind == SportData.KIND_CRICKET) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[l.path] }
        if (hit != null && now - hit.first < 600_000) return@withContext hit.second
        val o = J.get("https://site.api.espn.com/apis/site/v2/sports/${l.path}/news") ?: return@withContext hit?.second ?: emptyList()
        val out = J.objs(o.optJSONArray("articles")).mapNotNull { a ->
            val h = a.optString("headline")
            if (h.isBlank()) return@mapNotNull null
            val img = J.objs(a.optJSONArray("images")).firstOrNull { (it.optString("type") == "header" || it.optString("type") == "Media") && it.optString("url").isNotBlank() }?.optString("url")
            Article(h, a.optString("description"), img, a.optString("published"), l.name)
        }
        synchronized(cache) { cache[l.path] = now to out }
        out
    }
}

// ───────────────────────── Tabs: Leagues, Teams, Favorites ─────────────────────────

/** Leagues tab: all competitions grouped by sport; OK opens the League Center. */
@Composable
internal fun HubLeagues(hub: HubCtx) {
    LazyColumn(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(14))) {
        SportData.SPORTS.forEach { s ->
            val ls = SportData.LEAGUES.filter { it.sport == s.key }
            if (ls.isEmpty()) return@forEach
            item(key = "s" + s.key) {
                HubSectionTitle(Lang.tr(s.label), raw = true)
                Spacer(Modifier.height(dpx(8)))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8))) {
                    items(ls, key = { it.path }) { l ->
                        LogoTile(l.name, SportData.leagueLogos[l.path], l.name, marked = SportHubFavs.leagues.contains(l.path)) { hub.nav.open(HubPage.League(l)) }
                    }
                }
            }
        }
    }
}

/** Teams tab: favorite teams first, below all teams of the chosen league; OK opens the Team Center. */
@Composable
internal fun HubTeams(hub: HubCtx) {
    val leagues = SportLeagues.enabled().filter { it.kind == SportData.KIND_TEAM }
    var sel by remember { mutableStateOf<SportData.League?>(null) }
    val cur = sel ?: leagues.firstOrNull()
    var retry by remember { mutableStateOf(0) }
    val groups by produceState<List<SportStandings.Group>?>(null, cur?.path, retry) {
        value = null
        if (cur != null) value = SportStandings.fetch(cur)
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(4)), horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            items(leagues, key = { it.path }) { l -> FilterPill(l.name, cur?.path == l.path, Modifier.trackFocus(), raw = true) { sel = l } }
        }
        val teams = groups?.flatMap { it.rows.map { r -> r.team } }?.distinctBy { it.id + it.name }?.sortedBy { it.name }
        val favs = SportHubFavs.teamList()
        LazyColumn(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
            if (favs.isNotEmpty()) item(key = "fav") {
                HubSectionTitle("Meine Teams")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8))) {
                    items(favs, key = { it.key }) { f ->
                        LogoTile(f.name, f.logo, f.short, marked = true) {
                            val l = SportData.LEAGUES.firstOrNull { it.path == f.path }
                            if (l != null && f.id.isNotBlank()) hub.nav.open(HubPage.Team(l, f.team()))
                        }
                    }
                }
            }
            item(key = "title") { HubSectionTitle(cur?.name ?: Lang.tr("Teams"), raw = true) }
            when {
                cur == null -> item(key = "none") { HubEmpty(AIcon.Info, "Keine Liga aktiv.", "Ligen schaltest du im Reiter „Sender“ unter „Ligen“ ein.") }
                teams == null -> item(key = "load") { HubLoading("Lade Teams …") }
                teams.isEmpty() -> item(key = "err") { HubEmpty(AIcon.Warning, "Keine Teams gefunden.", "Die Daten konnten nicht geladen werden. Bitte versuche es erneut.", "Erneut versuchen") { retry++ } }
                else -> teams.chunked(7).forEachIndexed { i, row ->
                    item(key = "r$i") {
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                            row.forEach { t -> LogoTile(t.name, t.logo, t.abbr, marked = SportFavs.has(t)) { cur?.let { c -> hub.nav.open(HubPage.Team(c, t)) } } }
                        }
                    }
                }
            }
        }
    }
}

/** Favorites tab: teams, competitions, players, sports; below their live games, the next days and latest results. */
@Composable
internal fun HubFavorites(feed: SportFeed.State, hub: HubCtx) {
    val ctx = LocalContext.current
    SportHubFavs.init(ctx)
    var sub by remember { mutableStateOf("Teams") }
    val favTeams = SportHubFavs.teamList()
    // Leagues that matter: favorite competitions, leagues of favorite teams and sports; old favorites without league: all team leagues
    val paths = remember(favTeams.size, SportHubFavs.leagues.size, SportHubFavs.sports.size) {
        val ls = HashSet<String>()
        ls.addAll(SportHubFavs.leagues)
        favTeams.forEach { if (it.path.isNotBlank()) ls.add(it.path) else SportLeagues.enabled().filter { l -> l.kind == SportData.KIND_TEAM }.forEach { l -> ls.add(l.path) } }
        SportData.LEAGUES.filter { SportHubFavs.sports.contains(it.sport) }.forEach { ls.add(it.path) }
        ls.toList().sorted()
    }
    val around by produceState<List<SportData.Game>?>(null, paths) {
        value = null
        val ls = SportData.LEAGUES.filter { it.path in paths }
        val out = ArrayList<SportData.Game>()
        for (d in listOf(0, 1, -1, 2, 3, -2, 4, 5, 6)) {
            val ymd = SportData.ymd(d, General.zone())
            out.addAll(coroutineScope { ls.map { l -> async { SportData.fetch(l, ymd, if (d == 0) 40_000 else 300_000) } }.awaitAll() }.flatten())
            value = out.filter { SportHubFavs.isFavGame(it) }.distinctBy { it.key }
        }
        value = out.filter { SportHubFavs.isFavGame(it) }.distinctBy { it.key }
    }
    if (SportHubFavs.isEmpty()) {
        HubEmpty(AIcon.StarOutline, "Noch keine Favoriten", "Merke dir Teams, Wettbewerbe, Spieler oder Sportarten. Sie erscheinen hier mit ihren Spielen.", "Sport entdecken",
            Modifier) { hub.goTab(3, null) }
        return
    }
    val all = (around.orEmpty() + feed.games.filter { SportHubFavs.isFavGame(it) }).associateBy { it.key }.values.map { SportFeed.latest[it.key] ?: it }
    val liveNow = all.filter { it.state == 1 }.sortedBy { it.start }
    val next = all.filter { it.state == 0 && statusKind(it) == 0 }.sortedBy { it.start }.take(30)
    val results = all.filter { it.state == 2 }.sortedByDescending { it.start }.take(20)
    LazyColumn(contentPadding = PaddingValues(horizontal = DS.PageX, vertical = dpx(8)), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
        item(key = "sub") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
                AppIcon(AIcon.Star, DS.Gold, size = dpx(30))
                Text("Meine Favoriten", color = Tok.TextHi, fontSize = spx(24), fontWeight = FontWeight.ExtraBold)
                HubTabs(listOf("Teams", "Wettbewerbe", "Spieler", "Sportarten"), sub, null, Modifier.weight(1f)) { sub = it }
            }
        }
        item(key = "tiles") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(10))) {
                when (sub) {
                    "Wettbewerbe" -> items(SportData.LEAGUES.filter { SportHubFavs.leagues.contains(it.path) }, key = { it.path }) { l ->
                        LogoTile(l.name, SportData.leagueLogos[l.path], l.name, marked = true) { hub.nav.open(HubPage.League(l)) }
                    }
                    "Spieler" -> items(SportHubFavs.players.toList(), key = { it.id + it.path }) { p ->
                        LogoTile(p.name, p.photo, p.name.take(2), marked = true) {
                            SportData.LEAGUES.firstOrNull { it.path == p.path }?.let { l -> hub.nav.open(HubPage.Player(l, p.id, p.name)) }
                        }
                    }
                    "Sportarten" -> items(SportData.SPORTS, key = { it.key }) { s ->
                        val on = SportHubFavs.sports.contains(s.key)
                        LogoTile(Lang.tr(s.label), SportData.LEAGUES.firstOrNull { it.sport == s.key }?.let { SportData.leagueLogos[it.path] }, s.label, marked = on) {
                            SportHubFavs.toggleSport(ctx, s.key)
                        }
                    }
                    else -> items(favTeams, key = { it.key }) { f ->
                        LogoTile(f.name, f.logo, f.short, marked = true) {
                            val l = SportData.LEAGUES.firstOrNull { it.path == f.path }
                            if (l != null && f.id.isNotBlank()) hub.nav.open(HubPage.Team(l, f.team()))
                        }
                    }
                }
                item(key = "add") { LogoTile(Lang.tr("Hinzufügen"), null, "+", raw = true) { hub.goTab(if (sub == "Teams" || sub == "Spieler") 4 else 3, null) } }
            }
            if (sub == "Sportarten") Text("OK auf eine Sportart merkt sie oder entfernt sie wieder.", color = Muted, fontSize = spx(14))
        }
        if (liveNow.isNotEmpty()) {
            item(key = "lt") { HubSectionTitle("Jetzt live") }
            items(liveNow, key = { "l" + it.key }) { g -> MatchRow(g, hub.chans(g).isNotEmpty()) { hub.open(g) } }
        }
        item(key = "nt") { HubSectionTitle("Als Nächstes") }
        if (around == null && next.isEmpty()) item(key = "nl") { HubLoading("Lade Spiele …") }
        else if (next.isEmpty()) item(key = "ne") { Text("In den nächsten Tagen keine Spiele deiner Favoriten.", color = Muted, fontSize = spx(15)) }
        items(next, key = { "n" + it.key }) { g -> UpcomingRow(g, hub) }
        if (results.isNotEmpty()) {
            item(key = "rt") { HubSectionTitle("Letzte Ergebnisse") }
            items(results, key = { "r" + it.key }) { g -> MatchRow(g, false) { hub.open(g) } }
        }
    }
}

/** Upcoming game with day and time in front (favorites, League Center). */
@Composable
internal fun UpcomingRow(g: SportData.Game, hub: HubCtx) {
    FRow({ hub.open(g) }) {
        Text(raw = true, text = dayTime(g.start), color = Accent2, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(170)))
        if (g.solo) Text(raw = true, text = listOf(g.title, g.note).filter { it.isNotBlank() }.joinToString(" · "), color = Tok.TextHi, fontSize = spx(16), modifier = Modifier.weight(1f), maxLines = 1)
        else {
            TeamName(g.home, 0, Modifier.weight(1f), end = true, logoSize = 26)
            Text("vs", color = Muted, fontSize = spx(15), textAlign = TextAlign.Center, modifier = Modifier.width(dpx(50)))
            TeamName(g.away, 0, Modifier.weight(1f), logoSize = 26)
        }
        Text(raw = true, text = g.groupName, color = Muted, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(dpx(180)), textAlign = TextAlign.End)
        if (hub.chans(g).isNotEmpty()) AppIcon(AIcon.Play, Accent2, size = dpx(18))
    }
}

// ───────────────────────── League Center ─────────────────────────

@Composable
internal fun LeagueCenterPage(l: SportData.League, hub: HubCtx) {
    val ctx = LocalContext.current
    SportHubFavs.init(ctx)
    val team = l.kind == SportData.KIND_TEAM
    val soccer = l.path.startsWith("soccer/")
    val standings by produceState<List<SportStandings.Group>?>(null, l.path) { value = SportStandings.fetch(l) }
    val leaders by produceState<List<SportLeaders.Category>?>(null, l.path) { value = SportLeaders.fetch(l) }
    var tab by remember { mutableStateOf("Übersicht") }
    val topTitle = if (soccer) "Torjäger" else "Topspieler"
    val tabs = ArrayList<String>().apply {
        add("Übersicht")
        if (standings?.isNotEmpty() == true) add("Tabelle")
        add("Spielplan"); add("Ergebnisse")
        if (leaders?.isNotEmpty() == true) add(topTitle)
        if ((leaders?.size ?: 0) > 1) add("Statistik")
    }
    val cur = if (tab in tabs) tab else "Übersicht"
    // Fixtures and results: team leagues day by day (7 days), event sports from the current events
    val window = when (cur) { "Spielplan" -> 0..7; "Ergebnisse" -> -7..0; "Übersicht" -> 0..0; else -> null }
    val games by produceState<List<SportData.Game>?>(null, l.path, window) {
        value = null
        if (window == null) return@produceState
        value = if (team) coroutineScope {
            window.map { d -> async { SportData.fetch(l, SportData.ymd(d, General.zone()), if (d == 0) 40_000 else 300_000) } }.awaitAll()
        }.flatten().distinctBy { it.key } else SportData.fetchCurrent(l)
    }
    val first = remember { FocusRequester() }
    val fav = SportHubFavs.leagues.contains(l.path)
    HubPageFrame(l.name, Lang.tr(SportData.sportLabel(l.sport)), { hub.nav.pop() }, first) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(20))) {
            TeamLogo(SportData.leagueLogos[l.path], l.name, dpx(70))
            HubTabs(tabs, cur, first, Modifier.weight(1f)) { tab = it }
            FavoriteButton(fav) { SportHubFavs.toggleLeague(ctx, l.path) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = dpx(30)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            val openTeam: (SportData.Team) -> Unit = { t -> hub.nav.open(HubPage.Team(l, t)) }
            val onTeam: ((SportData.Team) -> Unit)? = if (team) openTeam else null
            when (cur) {
                "Tabelle" -> standings.orEmpty().forEach { standingsItems(it, null, onTeam) }
                "Spielplan" -> gameList(games, hub, upcoming = true)
                "Ergebnisse" -> gameList(games, hub, upcoming = false)
                topTitle -> leaders.orEmpty().firstOrNull()?.let { leaderItems(it, l, hub, 25) }
                "Statistik" -> leaders.orEmpty().drop(1).forEach { leaderItems(it, l, hub, 10) }
                else -> {
                    val today = games.orEmpty().sortedBy { it.start }
                    item(key = "tt") { HubSectionTitle(if (team) "Heute" else "Aktuell") }
                    if (games == null) item(key = "tl") { HubLoading() }
                    else if (today.isEmpty()) item(key = "te") { Text(if (team) "Heute keine Spiele." else "Gerade keine Ereignisse.", color = Muted, fontSize = spx(15), modifier = Modifier.padding(start = dpx(12))) }
                    items(today.take(20), key = { "g" + it.key }) { g -> MatchRow(g, hub.chans(g).isNotEmpty()) { hub.open(g) } }
                    standings.orEmpty().firstOrNull()?.let { grp ->
                        item(key = "st") { HubSectionTitle("Tabelle", action = "Alle zeigen") { tab = "Tabelle" } }
                        standingsItems(SportStandings.Group(grp.name, grp.cols, grp.rows.take(5)), null, onTeam)
                    }
                    leaders.orEmpty().firstOrNull()?.let { c ->
                        item(key = "lt") { HubSectionTitle(topTitle, action = "Alle zeigen") { tab = topTitle } }
                        leaderItems(c, l, hub, 3)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.gameList(games: List<SportData.Game>?, hub: HubCtx, upcoming: Boolean) {
    if (games == null) { item(key = "load") { HubLoading("Lade Spiele …") }; return }
    val list = if (upcoming) games.filter { it.state != 2 }.sortedBy { it.start } else games.filter { it.state == 2 }.sortedByDescending { it.start }
    if (list.isEmpty()) { item(key = "e") { HubEmpty(AIcon.Info, if (upcoming) "Keine Spiele geplant" else "Keine Ergebnisse", "In diesem Zeitraum liefert ESPN keine Spiele.") }; return }
    var lastDay = ""
    list.forEach { g ->
        val d = dayLabel(g.start)
        if (d != lastDay) { lastDay = d; item(key = "d$d") { Text(raw = true, text = d, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(10))) } }
        item(key = "g" + g.key) { MatchRow(g, hub.chans(g).isNotEmpty(), timeFirst = true) { hub.open(g) } }
    }
}

private fun LazyListScope.leaderItems(c: SportLeaders.Category, l: SportData.League, hub: HubCtx, max: Int) {
    item(key = "c" + c.name) { Text(raw = true, text = c.title, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(10))) }
    val list = c.leaders.take(max)
    items(list.size) { i ->
        val x = list[i]
        FRow({ if (x.id.isNotBlank()) hub.nav.open(HubPage.Player(l, x.id, x.name)) }) {
            Text(raw = true, text = "${i + 1}", color = Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(36)))
            Text(raw = true, text = x.name, color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            TeamLogo(x.teamLogo, "", dpx(24))
            Text(raw = true, text = x.team, color = Muted, fontSize = spx(15), modifier = Modifier.width(dpx(220)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = x.detail, color = Muted, fontSize = spx(14), modifier = Modifier.width(dpx(260)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = x.value, color = Accent2, fontSize = spx(18), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End, modifier = Modifier.width(dpx(60)))
        }
    }
}

// ───────────────────────── Team Center ─────────────────────────

@Composable
internal fun TeamCenterPage(l: SportData.League, t: SportData.Team, hub: HubCtx) {
    val ctx = LocalContext.current
    SportHubFavs.init(ctx)
    var done by remember(t.id) { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val d by produceState<SportTeamData.Detail?>(null, t.id, retry) { value = SportTeamData.fetch(l, t); done = true }
    val standings by produceState<List<SportStandings.Group>?>(null, l.path) { value = SportStandings.fetch(l) }
    val det = d
    val myGroup = standings.orEmpty().firstOrNull { g -> g.rows.any { it.team.id == t.id } }
    val tabs = ArrayList<String>().apply {
        add("Übersicht")
        if (myGroup != null) add("Tabelle")
        if (det != null) {
            if (det.next.isNotEmpty()) add("Spielplan")
            if (det.last.isNotEmpty()) add("Ergebnisse")
            if (det.roster.isNotEmpty()) add("Kader")
            if (det.stats.isNotEmpty()) add("Statistik")
        }
    }
    var tab by remember { mutableStateOf("Übersicht") }
    val cur = if (tab in tabs) tab else "Übersicht"
    val first = remember { FocusRequester() }
    val logo = t.logo ?: myGroup?.rows?.firstOrNull { it.team.id == t.id }?.team?.logo
    HubPageFrame(t.name, l.name, { hub.nav.pop() }, first) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(20))) {
            TeamLogo(logo, t.abbr, dpx(84))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
                Text(raw = true, text = listOf(det?.standing.orEmpty(), det?.record.orEmpty(), det?.venue.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Muted, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis)
                HubTabs(tabs, cur, first) { tab = it }
            }
            FavoriteButton(SportHubFavs.isTeam(t)) { SportHubFavs.toggleTeam(ctx, l, SportData.Team(t.name, t.short, t.abbr, logo, null, false, null, t.color, t.id)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = dpx(30)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
            when (cur) {
                "Tabelle" -> myGroup?.let { standingsItems(it, t.id) { x -> if (x.id != t.id) hub.nav.open(HubPage.Team(l, x)) } }
                "Spielplan" -> items(det?.next.orEmpty(), key = { "n" + it.key }) { g -> UpcomingRow(g, hub) }
                "Ergebnisse" -> items(det?.last.orEmpty(), key = { "r" + it.key }) { g -> ResultRow(g.start, g.home.short, g.home.logo, g.away.short, g.away.logo, scoreLine(g).takeIf { it != "vs" }.orEmpty()) { hub.open(g) } }
                "Kader" -> {
                    if (det != null && det.coach.isNotBlank()) item(key = "coach") {
                        FRow { Text("Trainer", color = Muted, fontSize = spx(15), modifier = Modifier.width(dpx(110))); Text(raw = true, text = det.coach, color = Tok.TextHi, fontSize = spx(16)) }
                    }
                    items(det?.roster.orEmpty(), key = { "p" + it.id + it.name }) { p ->
                        FRow({ if (p.id.isNotBlank()) hub.nav.open(HubPage.Player(l, p.id, p.name)) }) {
                            Text(raw = true, text = p.jersey, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(50)))
                            TeamLogo(p.photo, "", dpx(34))
                            Text(raw = true, text = p.name, color = Tok.TextHi, fontSize = spx(16), modifier = Modifier.weight(1f), maxLines = 1)
                            Text(raw = true, text = p.pos, color = Muted, fontSize = spx(15), modifier = Modifier.width(dpx(60)))
                            Text(raw = true, text = p.extra, color = Muted, fontSize = spx(14), maxLines = 1)
                            if (SportHubFavs.hasPlayer(p.id)) AppIcon(AIcon.Star, DS.Gold, size = dpx(16))
                        }
                    }
                }
                "Statistik" -> det?.stats.orEmpty().forEachIndexed { i, sg ->
                    item(key = "sg$i") { Text(raw = true, text = sg.title, color = Accent2, fontSize = spx(16), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(10))) }
                    items(sg.stats.size) { k ->
                        val (n, v) = sg.stats[k]
                        FRow { Text(raw = true, text = n, color = Tok.TextMid, fontSize = spx(16), modifier = Modifier.weight(1f)); Text(raw = true, text = v, color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.Bold) }
                    }
                }
                else -> {
                    if (det == null) item(key = "x") {
                        if (done) HubEmpty(AIcon.Warning, "Keine Details verfügbar.", "ESPN hat für dieses Team gerade keine Daten geliefert.", "Erneut versuchen") { retry++ }
                        else HubLoading("Lade Details …")
                    } else {
                        item(key = "panels") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                                HubPanel(Modifier.weight(0.8f)) {
                                    Text("Aktuelle Position", color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold)
                                    val rank = det.rank.ifBlank { myGroup?.rows?.firstOrNull { it.team.id == t.id }?.rank.orEmpty() }
                                    if (rank.isNotBlank()) Text(raw = true, text = "$rank.", color = Tok.TextHi, fontSize = spx(48), fontWeight = FontWeight.ExtraBold)
                                    if (det.points.isNotBlank()) Text(raw = true, text = det.points + " " + Lang.tr("Pkt"), color = Accent2, fontSize = spx(20), fontWeight = FontWeight.Bold)
                                    Text(raw = true, text = det.wld.ifBlank { det.record }, color = Muted, fontSize = spx(15))
                                }
                                HubPanel(Modifier.weight(1.4f)) {
                                    Text("Letzte Spiele", color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold)
                                    det.last.take(3).forEach { g ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                                            TeamName(g.home, 2, Modifier.weight(1f), end = true, logoSize = 22)
                                            Text(raw = true, text = scoreLine(g), color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.width(dpx(80)))
                                            TeamName(g.away, 2, Modifier.weight(1f), logoSize = 22)
                                        }
                                    }
                                    if (det.last.isEmpty()) Text("Keine Ergebnisse", color = Muted, fontSize = spx(15))
                                }
                                HubPanel(Modifier.weight(1f)) {
                                    Text("Nächstes Spiel", color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold)
                                    val n = det.next.firstOrNull()
                                    if (n == null) Text("Kein Spiel geplant", color = Muted, fontSize = spx(15))
                                    else {
                                        Text(raw = true, text = dayLabel(n.start), color = Muted, fontSize = spx(15))
                                        val opp = if (n.home.id == t.id) n.away else n.home
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                                            TeamLogo(opp.logo, opp.abbr, dpx(34))
                                            Text(raw = true, text = (if (n.home.id == t.id) "vs " else "@ ") + opp.short, color = Tok.TextHi, fontSize = spx(17), fontWeight = FontWeight.Bold)
                                        }
                                        Text(raw = true, text = hhmm(n.start), color = Accent2, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                        if (det.next.isNotEmpty()) {
                            item(key = "nt") { HubSectionTitle("Nächste Spiele", action = "Alle zeigen") { tab = "Spielplan" } }
                            items(det.next.take(3), key = { "on" + it.key }) { g -> UpcomingRow(g, hub) }
                        }
                        if (det.last.isNotEmpty()) {
                            item(key = "lt") { HubSectionTitle("Letzte Spiele", action = "Alle zeigen") { tab = "Ergebnisse" } }
                            items(det.last.take(5), key = { "ol" + it.key }) { g -> ResultRow(g.start, g.home.short, g.home.logo, g.away.short, g.away.logo, scoreLine(g).takeIf { it != "vs" }.orEmpty()) { hub.open(g) } }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Player ─────────────────────────

@Composable
internal fun SportPlayerPage(l: SportData.League, id: String, name: String, hub: HubCtx) {
    val ctx = LocalContext.current
    SportHubFavs.init(ctx)
    var done by remember(id) { mutableStateOf(false) }
    val d by produceState<SportPlayer.Detail?>(null, id) { value = SportPlayer.fetch(l, id); done = true }
    val first = remember { FocusRequester() }
    val det = d
    HubPageFrame(det?.name ?: name, listOf(det?.team.orEmpty(), l.name).filter { it.isNotBlank() }.joinToString(" · "), { hub.nav.pop() }, first) {
        if (det == null) {
            if (done) HubEmpty(AIcon.Person, "Keine Spielerdaten", "ESPN liefert für diesen Spieler keine Daten.") else HubLoading("Lade Details …")
        } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(dpx(24))) {
            Column(Modifier.width(dpx(300)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                Box(Modifier.size(dpx(220)).clip(RoundedCornerShape(DS.RadiusL)).background(Tok.Card), contentAlignment = Alignment.Center) {
                    TeamLogo(det.photo ?: det.teamLogo, det.name.take(2), dpx(200))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                    if (det.flag != null) TeamLogo(det.flag, "", dpx(24))
                    TeamLogo(det.teamLogo, "", dpx(28))
                    Text(raw = true, text = det.team, color = Tok.TextMid, fontSize = spx(16))
                }
                FavoriteButton(SportHubFavs.hasPlayer(id)) { SportHubFavs.togglePlayer(ctx, l, id, det.name, det.photo) }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(4)), contentPadding = PaddingValues(bottom = dpx(30))) {
                items(det.facts.size) { i ->
                    val (k, v) = det.facts[i]
                    FRow { Text(k, color = Muted, fontSize = spx(16), modifier = Modifier.width(dpx(160))); Text(raw = true, text = v, color = Tok.TextHi, fontSize = spx(16), fontWeight = FontWeight.SemiBold) }
                }
                if (det.stats.isNotEmpty()) {
                    item(key = "st") { Text(raw = true, text = det.statsTitle.ifBlank { Lang.tr("Statistik") }, color = Accent2, fontSize = spx(17), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12), top = dpx(12))) }
                    items(det.stats.size) { i ->
                        val (k, v) = det.stats[i]
                        FRow { Text(raw = true, text = k, color = Tok.TextMid, fontSize = spx(16), modifier = Modifier.weight(1f)); Text(raw = true, text = v, color = Tok.TextHi, fontSize = spx(18), fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }
    }
}

// ───────────────────────── News ─────────────────────────

/** News row for the overview: up to three leagues of today's games, OK shows headline and short text. */
@Composable
internal fun HubNewsRow(leagues: List<SportData.League>) {
    val ls = remember(leagues.map { it.path }) { leagues.filter { it.kind != SportData.KIND_CRICKET }.take(3) }
    val news by produceState<List<SportNews.Article>>(emptyList(), ls) {
        value = coroutineScope { ls.map { l -> async { SportNews.fetch(l).take(4) } }.awaitAll() }.flatten()
    }
    var open by remember { mutableStateOf<SportNews.Article?>(null) }
    if (news.isEmpty()) return
    Column {
        HubSectionTitle("Nachrichten")
        Spacer(Modifier.height(dpx(8)))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8))) {
            items(news, key = { it.league + it.headline }) { a ->
                HubCardBox(Modifier.width(dpx(320)), onClick = { open = a }) {
                    Box(Modifier.fillMaxWidth().height(dpx(150)).clip(RoundedCornerShape(DS.RadiusS)).background(Tok.CardFocus)) {
                        if (a.image != null) AsyncImage(a.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(raw = true, text = a.league, color = Accent2, fontSize = spx(13), fontWeight = FontWeight.Bold)
                    Text(raw = true, text = a.headline, color = Tok.TextHi, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    open?.let { a ->
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(DS.RadiusM)).background(Bg).border(1.dp, Tok.Line, RoundedCornerShape(DS.RadiusM)).padding(dpx(20)),
                verticalArrangement = Arrangement.spacedBy(dpx(12))
            ) {
                if (a.image != null) AsyncImage(a.image, null, Modifier.fillMaxWidth().height(dpx(240)).clip(RoundedCornerShape(DS.RadiusS)), contentScale = ContentScale.Crop)
                Text(raw = true, text = a.league, color = Accent2, fontSize = spx(14), fontWeight = FontWeight.Bold)
                Text(raw = true, text = a.headline, color = Tok.TextHi, fontSize = spx(24), fontWeight = FontWeight.ExtraBold)
                if (a.text.isNotBlank()) Text(raw = true, text = a.text, color = Tok.TextMid, fontSize = spx(17))
                PillButton("Schließen", Modifier.focusRequester(first)) { open = null }
            }
        }
    }
}
