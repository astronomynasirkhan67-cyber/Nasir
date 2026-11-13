package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Results for the sports area. Source: the public but unofficial ESPN addresses (site.api.espn.com).
 * No key needed. The addresses can change without notice: on an error the sports area keeps the last
 * data or shows an error state. Fetched only while the sports area is open (and by the score alerts).
 */
object SportData {
    class Team(val name: String, val short: String, val abbr: String, val logo: String?, val score: String?, val winner: Boolean,
        val record: String? = null, val color: String? = null, val id: String = "")

    /** One entrant of a field event (F1 session, golf tournament): position, name, flag, main value, extra info. */
    class Entrant(val pos: String, val name: String, val flag: String?, val value: String, val extra: String, val id: String = "")

    /** [state]: 0 = not started yet, 1 = running, 2 = finished. [detail] e.g. "2nd half 67'" or "Q3 4:12". */
    class Game(
        val id: String, val league: String, val catName: String, val start: Long, val state: Int, val detail: String,
        val home: Team, val away: Team,
        /** ESPN status name (e.g. STATUS_POSTPONED), game clock, period, score per period, venue, note (e.g. "World Series - Game 5"), TV (US). */
        val statusName: String = "", val clock: String = "", val period: Int = 0,
        val homeLines: List<String> = emptyList(), val awayLines: List<String> = emptyList(),
        val venue: String = "", val note: String = "", val tv: String = "",
        /** Competition shown as group header (tournament, fight card, cricket series); empty = league name. */
        val group: String = "",
        /** Field event without home/away (F1 session, golf): [title] and [field] instead of two teams. */
        val solo: Boolean = false, val title: String = "", val field: List<Entrant> = emptyList(),
        /** End of a multi-day event (golf, cricket test), 0 = unknown. */
        val end: Long = 0L
    ) {
        val groupName: String get() = group.ifBlank { league }
        val key: String get() = "$league|$id"
    }

    const val KIND_TEAM = 0
    const val KIND_TENNIS = 1
    const val KIND_RACE = 2
    const val KIND_FIGHT = 3
    const val KIND_GOLF = 4
    const val KIND_CRICKET = 5

    /** [sport] = key of [SPORTS]; [kind] decides how the scoreboard is read. */
    class League(val name: String, val path: String, val catName: String, val sport: String = "soccer", val kind: Int = KIND_TEAM)

    /** The ten sports of the hub in display order (key, German label). */
    class Sport(val key: String, val label: String)

    val SPORTS = listOf(
        Sport("soccer", "Fußball"), Sport("basketball", "Basketball"), Sport("nfl", "NFL"), Sport("nhl", "NHL"),
        Sport("tennis", "Tennis"), Sport("f1", "Formel 1"), Sport("mlb", "MLB"), Sport("ufc", "UFC"),
        Sport("cricket", "Cricket"), Sport("golf", "Golf")
    )

    val LEAGUES = listOf(
        League("Bundesliga", "soccer/ger.1", "Fußball"),
        League("2. Bundesliga", "soccer/ger.2", "Fußball"),
        League("Premier League", "soccer/eng.1", "Fußball"),
        League("LaLiga", "soccer/esp.1", "Fußball"),
        League("Serie A", "soccer/ita.1", "Fußball"),
        League("Ligue 1", "soccer/fra.1", "Fußball"),
        League("Champions League", "soccer/uefa.champions", "Fußball"),
        League("Europa League", "soccer/uefa.europa", "Fußball"),
        // Wishbox: North American leagues (same ESPN scoreboard as the others)
        League("MLS", "soccer/usa.1", "Fußball"),
        League("Liga MX", "soccer/mex.1", "Fußball"),
        League("NBA", "basketball/nba", "Basketball", "basketball"),
        League("NFL", "football/nfl", "American Football", "nfl"),
        League("College Football", "football/college-football", "American Football", "nfl"),
        League("NHL", "hockey/nhl", "Eishockey", "nhl"),
        League("MLB", "baseball/mlb", "Baseball", "mlb"),
        League("ATP", "tennis/atp", "Tennis", "tennis", KIND_TENNIS),
        League("WTA", "tennis/wta", "Tennis", "tennis", KIND_TENNIS),
        League("Formel 1", "racing/f1", "Motorsport", "f1", KIND_RACE),
        League("UFC", "mma/ufc", "Kampfsport", "ufc", KIND_FIGHT),
        League("PGA Tour", "golf/pga", "Golf", "golf", KIND_GOLF),
        League("Cricket", "cricket", "Rugby & Cricket", "cricket", KIND_CRICKET)
    )

    /** Popular competitions for the overview (shown if active). */
    val POPULAR = listOf("Premier League", "Champions League", "Bundesliga", "LaLiga", "NBA", "NFL", "NHL", "MLB")

    fun league(name: String): League? = LEAGUES.firstOrNull { it.name == name }
    fun leagueOf(g: Game): League? = league(g.league)
    fun sportOf(g: Game): String = leagueOf(g)?.sport ?: ""
    fun kindOf(g: Game): Int = leagueOf(g)?.kind ?: KIND_TEAM
    fun sportLabel(key: String): String = SPORTS.firstOrNull { it.key == key }?.label ?: key

    /** League logos as delivered by the scoreboard (path → URL). No URL is built by hand. */
    val leagueLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Did the last fetch work? (For the hint in the sports area.) */
    @Volatile var lastOk = true

    private val cache = HashMap<String, Pair<Long, List<Game>>>()

    /** Day as yyyyMMdd in the chosen time zone; [dayOffset] -1 = yesterday, 0 = today, 1 = tomorrow. */
    fun ymd(dayOffset: Int, tz: TimeZone, nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMs
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return String.format(Locale.US, "%04d%02d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** Start and end (seconds) of the day [ymd] in [tz]. */
    fun dayRange(ymd: String, tz: TimeZone): Pair<Long, Long> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        f.timeZone = tz
        val s = (f.parse(ymd)?.time ?: 0L) / 1000
        return s to s + 86_400
    }

    fun parseDate(s: String): Long {
        for (f in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            try {
                val d = SimpleDateFormat(f, Locale.US)
                d.timeZone = TimeZone.getTimeZone("UTC")
                return (d.parse(s)?.time ?: continue) / 1000
            } catch (_: Exception) { }
        }
        return 0L
    }

    private fun stateOf(type: JSONObject?): Int = when (type?.optString("state")) { "in" -> 1; "post" -> 2; else -> 0 }

    private fun objs(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val r = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { r.add(it) }
        return r
    }

    private fun team(c: JSONObject): Team {
        val t = c.optJSONObject("team")
        val name = t?.optString("displayName").orEmpty()
        val short = t?.optString("shortDisplayName").orEmpty().ifBlank { name }
        val score = c.optString("score", "").takeIf { it.isNotBlank() }
        val rec = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary")?.takeIf { it.isNotBlank() }
        val logo = t?.optString("logo")?.takeIf { it.isNotBlank() }
            ?: t?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.takeIf { it.isNotBlank() }
        return Team(name, short, t?.optString("abbreviation").orEmpty(), logo, score, c.optBoolean("winner", false),
            rec, t?.optString("color")?.takeIf { it.isNotBlank() }, t?.optString("id").orEmpty())
    }

    /** A person as competitor (tennis player, fighter): name, short name and flag. */
    private fun athlete(c: JSONObject, score: String?): Team {
        val a = c.optJSONObject("athlete") ?: c.optJSONObject("roster")
        val name = a?.optString("displayName").orEmpty().ifBlank { a?.optString("fullName").orEmpty() }
        val short = a?.optString("shortName").orEmpty().ifBlank { name }
        val flag = a?.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() }
        val rec = c.optJSONArray("records")?.optJSONObject(0)?.optString("summary")?.takeIf { it.isNotBlank() }
        return Team(name, short, "", flag, score, c.optBoolean("winner", false), rec, null, c.optString("id"))
    }

    private fun lines(c: JSONObject): List<String> {
        val a = c.optJSONArray("linescores") ?: return emptyList()
        return (0 until a.length()).map { a.optJSONObject(it)?.optString("displayValue").orEmpty() }
    }

    private fun rememberLogo(o: JSONObject, l: League) {
        val logo = o.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
        if (!logo.isNullOrBlank()) leagueLogos[l.path] = logo
    }

    /** Reads the response of a scoreboard. Unknown or incomplete entries are skipped. */
    fun parse(json: String, l: League): List<Game> {
        val o = JSONObject(json)
        rememberLogo(o, l)
        return when (l.kind) {
            KIND_TENNIS -> parseTennis(o, l)
            KIND_RACE -> parseRace(o, l)
            KIND_FIGHT -> parseFight(o, l)
            KIND_GOLF -> parseGolf(o, l)
            KIND_CRICKET -> parseCricket(o, l)
            else -> parseTeams(o, l)
        }
    }

    private fun parseTeams(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        val events = o.optJSONArray("events") ?: return out
        for (i in 0 until events.length()) {
            try {
                val e = events.optJSONObject(i) ?: continue
                val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val cs = objs(comp.optJSONArray("competitors"))
                val homeC = cs.firstOrNull { it.optString("homeAway") == "home" } ?: continue
                val awayC = cs.firstOrNull { it.optString("homeAway") == "away" } ?: continue
                val type = e.optJSONObject("status")?.optJSONObject("type")
                val st = e.optJSONObject("status")
                val tv = comp.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONArray("names")?.optString(0).orEmpty()
                out.add(Game(
                    e.optString("id", "$i"), l.name, l.catName, parseDate(e.optString("date")), stateOf(type), type?.optString("shortDetail").orEmpty(),
                    team(homeC), team(awayC),
                    statusName = type?.optString("name").orEmpty(), clock = st?.optString("displayClock").orEmpty(), period = st?.optInt("period", 0) ?: 0,
                    homeLines = lines(homeC), awayLines = lines(awayC),
                    venue = comp.optJSONObject("venue")?.optString("fullName").orEmpty(),
                    note = comp.optJSONArray("notes")?.optJSONObject(0)?.optString("headline").orEmpty(), tv = tv
                ))
            } catch (_: Exception) { }
        }
        return out
    }

    /** Tennis: events = tournaments, groupings = draws (singles, doubles), competitions = matches. Score = sets won, lines = games per set. */
    private fun parseTennis(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        for (e in objs(o.optJSONArray("events"))) {
            val groups = objs(e.optJSONArray("groupings"))
            for (gr in groups) {
                val draw = gr.optJSONObject("grouping")?.optString("displayName").orEmpty()
                for (c in objs(gr.optJSONArray("competitions"))) {
                    try {
                        val cs = objs(c.optJSONArray("competitors")).sortedBy { it.optInt("order", 9) }
                        if (cs.size < 2) continue
                        val type = c.optJSONObject("status")?.optJSONObject("type")
                        val state = stateOf(type)
                        fun sets(x: JSONObject): Int = objs(x.optJSONArray("linescores")).count { it.optBoolean("winner") }
                        fun games(x: JSONObject): List<String> = objs(x.optJSONArray("linescores")).map { s ->
                            val v = s.opt("value")
                            val n = (v as? Number)?.toInt()?.toString() ?: s.optString("displayValue")
                            val tb = s.optInt("tiebreak", -1)
                            if (tb >= 0) "$n($tb)" else n
                        }
                        val a = athlete(cs[0], if (state == 0) null else sets(cs[0]).toString())
                        val b = athlete(cs[1], if (state == 0) null else sets(cs[1]).toString())
                        if (a.name.isBlank() || b.name.isBlank()) continue
                        out.add(Game(
                            c.optString("id"), l.name, l.catName, parseDate(c.optString("date")), state, type?.optString("shortDetail").orEmpty(), a, b,
                            statusName = type?.optString("name").orEmpty(), period = c.optJSONObject("status")?.optInt("period", 0) ?: 0,
                            homeLines = games(cs[0]), awayLines = games(cs[1]),
                            venue = listOf(c.optJSONObject("venue")?.optString("fullName").orEmpty(), c.optJSONObject("venue")?.optString("court").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            note = c.optJSONObject("round")?.optString("displayName").orEmpty(),
                            group = e.optString("name") + (if (draw.isNotBlank()) " · $draw" else "")
                        ))
                    } catch (_: Exception) { }
                }
            }
        }
        return out
    }

    private val SESSION = mapOf("FP1" to "1. Training", "FP2" to "2. Training", "FP3" to "3. Training", "Qual" to "Qualifying", "Race" to "Rennen", "SS" to "Sprint-Qualifying", "Sprint" to "Sprint")

    /** F1: one event per race weekend, competitions = sessions. Every session becomes its own entry with the classification as field. */
    private fun parseRace(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        for (e in objs(o.optJSONArray("events"))) {
            val circuit = e.optJSONObject("circuit")?.optString("fullName").orEmpty()
            for (c in objs(e.optJSONArray("competitions"))) {
                try {
                    val type = c.optJSONObject("status")?.optJSONObject("type")
                    val ab = c.optJSONObject("type")?.optString("abbreviation").orEmpty()
                    val field = objs(c.optJSONArray("competitors")).sortedBy { it.optInt("order", 999) }.map { x ->
                        val a = x.optJSONObject("athlete")
                        Entrant(x.optInt("order", 0).takeIf { it > 0 }?.toString().orEmpty(), a?.optString("displayName").orEmpty().ifBlank { a?.optString("fullName").orEmpty() },
                            a?.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() }, "", "", x.optString("id"))
                    }.filter { it.name.isNotBlank() }
                    val empty = Team("", "", "", null, null, false)
                    out.add(Game(
                        c.optString("id").ifBlank { e.optString("id") + ab }, l.name, l.catName, parseDate(c.optString("date")), stateOf(type),
                        type?.optString("shortDetail").orEmpty(), empty, empty,
                        statusName = type?.optString("name").orEmpty(), venue = circuit, note = SESSION[ab] ?: ab,
                        tv = c.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONArray("names")?.optString(0).orEmpty(),
                        group = e.optString("name"), solo = true, title = e.optString("name"), field = field
                    ))
                } catch (_: Exception) { }
            }
        }
        return out
    }

    /** UFC: one event per fight card, competitions = fights (two athletes, no score). */
    private fun parseFight(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        for (e in objs(o.optJSONArray("events"))) {
            for (c in objs(e.optJSONArray("competitions"))) {
                try {
                    val cs = objs(c.optJSONArray("competitors")).sortedBy { it.optInt("order", 9) }
                    if (cs.size < 2) continue
                    val st = c.optJSONObject("status")
                    val type = st?.optJSONObject("type")
                    val state = stateOf(type)
                    val per = st?.optInt("period", 0) ?: 0
                    val clock = st?.optString("displayClock").orEmpty()
                    val detail = if (state == 2 && per > 0) "R$per $clock".trim() else type?.optString("shortDetail").orEmpty()
                    val a = athlete(cs[0], null)
                    val b = athlete(cs[1], null)
                    if (a.name.isBlank() || b.name.isBlank()) continue
                    out.add(Game(
                        c.optString("id"), l.name, l.catName, parseDate(c.optString("date").ifBlank { e.optString("date") }), state, detail, a, b,
                        statusName = type?.optString("name").orEmpty(), clock = clock, period = per,
                        venue = c.optJSONObject("venue")?.optString("fullName").orEmpty(),
                        note = c.optJSONObject("type")?.optString("abbreviation").orEmpty(),
                        tv = c.optString("broadcast"), group = e.optString("name")
                    ))
                } catch (_: Exception) { }
            }
        }
        return out
    }

    /** Golf: one tournament per event, the field is the leaderboard (order, total to par, current round). */
    private fun parseGolf(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        for (e in objs(o.optJSONArray("events"))) {
            try {
                val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val st = c.optJSONObject("status") ?: e.optJSONObject("status")
                val type = st?.optJSONObject("type")
                val field = objs(c.optJSONArray("competitors")).sortedBy { it.optInt("order", 999) }.take(150).map { x ->
                    val a = x.optJSONObject("athlete")
                    val rounds = objs(x.optJSONArray("linescores")).filter { it.optString("displayValue").isNotBlank() }
                    val last = rounds.lastOrNull()
                    val extra = if (last != null) "R" + last.optInt("period", rounds.size) + " " + last.optString("displayValue") else ""
                    Entrant(x.optInt("order", 0).takeIf { it > 0 }?.toString().orEmpty(), a?.optString("displayName").orEmpty().ifBlank { a?.optString("fullName").orEmpty() },
                        a?.optJSONObject("flag")?.optString("href")?.takeIf { it.isNotBlank() }, x.optString("score"), extra, x.optString("id"))
                }.filter { it.name.isNotBlank() }
                val empty = Team("", "", "", null, null, false)
                out.add(Game(
                    e.optString("id"), l.name, l.catName, parseDate(e.optString("date")), stateOf(type), type?.optString("detail").orEmpty(), empty, empty,
                    statusName = type?.optString("name").orEmpty(), period = st?.optInt("period", 0) ?: 0,
                    venue = c.optJSONObject("venue")?.optString("fullName").orEmpty(),
                    group = e.optString("name"), solo = true, title = e.optString("name"), field = field,
                    end = parseDate(e.optString("endDate"))
                ))
            } catch (_: Exception) { }
        }
        return out
    }

    /** Cricket (ESPN header scoreboard: all current series). Score is text like "261 & 90/2 (17.5 ov)". */
    private fun parseCricket(o: JSONObject, l: League): List<Game> {
        val out = ArrayList<Game>()
        for (sp in objs(o.optJSONArray("sports"))) for (lg in objs(sp.optJSONArray("leagues"))) {
            val series = lg.optString("name")
            for (e in objs(lg.optJSONArray("events"))) {
                try {
                    val cs = objs(e.optJSONArray("competitors"))
                    if (cs.size < 2) continue
                    val hc = cs.firstOrNull { it.optString("homeAway") == "home" } ?: cs[0]
                    val ac = cs.firstOrNull { it.optString("homeAway") == "away" && it !== hc } ?: cs.first { it !== hc }
                    fun t(c: JSONObject): Team {
                        val name = c.optString("displayName")
                        return Team(name, name, c.optString("abbreviation"), c.optString("logo").takeIf { it.isNotBlank() },
                            c.optString("score").takeIf { it.isNotBlank() }, c.optBoolean("winner", false), null, null, c.optString("id"))
                    }
                    val fs = e.optJSONObject("fullStatus")
                    val state = when (e.optString("status")) { "in" -> 1; "post" -> 2; else -> 0 }
                    val sum = fs?.optString("summary").orEmpty().ifBlank { e.optString("summary") }
                    out.add(Game(
                        e.optString("id"), l.name, l.catName, parseDate(e.optString("date")), state,
                        if (state == 0) "" else sum, t(hc), t(ac),
                        statusName = fs?.optJSONObject("type")?.optString("name").orEmpty(), period = e.optInt("period", 0),
                        venue = e.optString("location"), note = e.optString("description"), group = series,
                        end = parseDate(e.optString("endDate"))
                    ))
                } catch (_: Exception) { }
            }
        }
        return out
    }

    private fun urlFor(l: League, ymd: String?): String = when (l.kind) {
        KIND_CRICKET -> "https://site.web.api.espn.com/apis/v2/scoreboard/header?sport=cricket"
        KIND_TEAM -> "https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard?dates=$ymd"
        // Tennis, F1, UFC, golf: the scoreboard shows the current events; days are filtered afterwards
        else -> "https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard"
    }

    /** Is the game on day [ymd]? Multi-day events (golf, cricket tests) count on every day they cover. */
    fun onDay(g: Game, ymd: String, tz: TimeZone): Boolean {
        val (s, e) = dayRange(ymd, tz)
        val end = if (g.end > g.start) g.end else g.start
        return g.start < e && end >= s
    }

    /**
     * Fetches the games of [l] on day [ymd] (at most every [maxAgeMs] per league and day). On an error the last state is kept.
     * Event sports (tennis, F1, UFC, golf, cricket) have no day parameter: their current events are filtered by day.
     */
    suspend fun fetch(l: League, ymd: String, maxAgeMs: Long = 40_000): List<Game> = withContext(Dispatchers.IO) {
        val dayParam = l.kind == KIND_TEAM
        val key = l.path + "|" + (if (dayParam) ymd else "now")
        val all = load(l, key, if (dayParam) ymd else null, maxAgeMs)
        if (dayParam) all else all.filter { onDay(it, ymd, General.zone()) }
    }

    /** Last loaded games of [l] for [ymd] regardless of their age (null = never loaded): shown at once while fresh data loads. */
    fun peek(l: League, ymd: String): List<Game>? {
        val dayParam = l.kind == KIND_TEAM
        val key = l.path + "|" + (if (dayParam) ymd else "now")
        val all = synchronized(cache) { cache[key] }?.second ?: return null
        return if (dayParam) all else all.filter { onDay(it, ymd, General.zone()) }
    }

    /** Current events of an event sport without day filter (fight card, tournament, race weekend). */
    suspend fun fetchCurrent(l: League, maxAgeMs: Long = 40_000): List<Game> = withContext(Dispatchers.IO) {
        if (l.kind == KIND_TEAM) emptyList() else load(l, l.path + "|now", null, maxAgeMs)
    }

    private fun load(l: League, key: String, ymd: String?, maxAgeMs: Long): List<Game> {
        val now = System.currentTimeMillis()
        val hit = synchronized(cache) { cache[key] }
        if (hit != null && now - hit.first < maxAgeMs) return hit.second
        return try {
            val req = Request.Builder().url(urlFor(l, ymd)).header("User-Agent", "IPVIO-Stream").build()
            Net.withTimeouts(8, 12).newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastOk = false; return hit?.second ?: emptyList() }
                val g = parse(r.body?.string().orEmpty(), l)
                synchronized(cache) {
                    if (cache.size > 300) cache.clear()
                    cache[key] = now to g
                }
                lastOk = true
                g
            }
        } catch (_: Exception) {
            lastOk = false
            hit?.second ?: emptyList()
        }
    }

    /** Was there ever an answer for this league and day? (Distinguishes "no games" from "not reachable".) */
    fun hasData(l: League, ymd: String): Boolean = synchronized(cache) { cache.containsKey(l.path + "|" + (if (l.kind == KIND_TEAM) ymd else "now")) }

    // ───────── Matching to your channels ─────────

    private val GENERIC = setOf("fc", "sc", "real", "club", "city", "united", "borussia", "athletic", "atletico", "sporting", "inter", "new", "los", "san", "the")

    private val RX_MARKS = Regex("\\p{M}+")
    private val RX_NONALNUM = Regex("[^a-z0-9 ]")
    private val RX_SPACES = Regex("\\s+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(RX_MARKS, "")
            .replace(RX_NONALNUM, " ").replace(RX_SPACES, " ").trim()

    // Channel titles are normalized again and again (every card, every recomposition): remember the result
    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun normCached(s: String): String {
        normCache[s]?.let { return it }
        if (normCache.size > 30_000) normCache.clear()
        return norm(s).also { normCache[s] = it }
    }

    private val keysCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    fun keysCached(t: Team): List<String> {
        val k = t.id + "|" + t.name + "|" + t.short
        return keysCache.getOrPut(k) { if (keysCache.size > 5_000) keysCache.clear(); keys(t) }
    }

    /** Search terms for a team: short name, full name, last and first word (if not too general). */
    fun keys(t: Team): List<String> {
        val out = ArrayList<String>()
        val short = norm(t.short)
        val full = norm(t.name)
        out.add(short); out.add(full)
        val last = full.split(" ").lastOrNull().orEmpty()
        if (last.length >= 5 && last !in GENERIC) out.add(last)
        val first = full.split(" ").firstOrNull().orEmpty()
        if (first.length >= 5 && first !in GENERIC) out.add(first)
        return out.filter { it.length >= 3 }.distinct()
    }

    fun has(text: String, key: String) = (" $text ").contains(" $key ")

    /** Do both teams appear in the show text? Field events (F1, golf) never match by team names. */
    fun matches(g: Game, text: String): Boolean {
        if (g.solo) return false
        val n = normCached(text)
        return keysCached(g.home).any { has(n, it) } && keysCached(g.away).any { has(n, it) }
    }

    private val chanCache = HashMap<String, Pair<Int, List<Item>>>()

    /** Channels of [live] whose name shows this game. The result is remembered per game as long as the channel list stays the same. */
    fun channelsFor(g: Game, live: List<Item>): List<Item> {
        val k = g.key
        val sig = System.identityHashCode(live) * 31 + live.size
        synchronized(chanCache) { chanCache[k]?.let { if (it.first == sig) return it.second } }
        val r = live.filter { matches(g, it.title) }
        synchronized(chanCache) { if (chanCache.size > 600) chanCache.clear(); chanCache[k] = sig to r }
        return r
    }
}

/** Favorite teams (per profile). Key = normalized team name. Details for the hub (logo, league) are in [SportHubFavs]. */
object SportFavs {
    val keys = mutableStateListOf<String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_sportfavs")

    fun init(c: Context) {
        if (inited) return
        inited = true
        keys.addAll((prefs(c).getString("k", "") ?: "").split("|").filter { it.isNotBlank() })
    }

    fun key(t: SportData.Team) = SportData.norm(t.name)
    fun has(t: SportData.Team) = t.name.isNotBlank() && key(t) in keys
    fun isFavGame(g: SportData.Game) = !g.solo && (has(g.home) || has(g.away))

    fun toggle(c: Context, t: SportData.Team) {
        init(c)
        val k = key(t)
        if (!keys.remove(k)) keys.add(k)
        prefs(c).edit().putString("k", keys.joinToString("|")).apply()
    }
}

/** Channel chosen by hand per game ("Correct channel", per profile). Key = game ID|league, value = channel ID. */
object SportFix {
    val map = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_sportfix")
    fun key(g: SportData.Game) = g.id + "|" + g.league

    fun init(c: Context) {
        if (inited) return
        inited = true
        (prefs(c).getString("m", "") ?: "").split("\n").forEach { l ->
            val i = l.indexOf('\t')
            if (i > 0) map[l.substring(0, i)] = l.substring(i + 1)
        }
    }

    fun set(c: Context, g: SportData.Game, chId: String?) {
        init(c)
        if (chId == null) map.remove(key(g)) else map[key(g)] = chId
        prefs(c).edit().putString("m", map.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
    }
}

/** Which leagues appear in the sports area (per profile). Default: all. Deselected leagues are not queried either. */
object SportLeagues {
    val off = androidx.compose.runtime.mutableStateListOf<String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_sportleagues")

    fun init(c: Context) {
        if (inited) return
        inited = true
        off.addAll((prefs(c).getString("off", "") ?: "").split("|").filter { it.isNotBlank() })
    }

    fun on(l: SportData.League) = l.name !in off
    fun enabled(): List<SportData.League> = SportData.LEAGUES.filter { on(it) }

    fun toggle(c: Context, l: SportData.League) {
        init(c)
        if (!off.remove(l.name)) off.add(l.name)
        prefs(c).edit().putString("off", off.joinToString("|")).apply()
    }
}
