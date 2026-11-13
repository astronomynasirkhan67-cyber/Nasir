package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Connection to a Stalker/Ministra portal (see [StalkerLogic]): login with the MAC address, channels, movies and
 * series as a list, play address at start. Series appear as placeholders in the list; [episodeList] fetches their episodes
 * only when a series is opened.
 */
object StalkerApi {
    private val client: OkHttpClient = Net.base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).addInterceptor(TlsFallback.interceptor()).build()

    /** Logged-in session: API and token, reused for a few minutes. */
    private class Session(val src: StalkerLogic.Source, val endpoint: String, val token: String, val at: Long)
    private val sessions = HashMap<Int, Session>()
    /** API address that answered last time, per source: tried first, so a new login costs only a few requests. */
    private val knownEndpoint = HashMap<Int, String>()
    private const val SESSION_MS = 10 * 60_000L
    private const val MAX_CHANNELS = 30000
    /** Pages per step when a category is opened or scrolled further (~14 titles per page), see [loadChunk]. */
    private const val LOAD_CHUNK = 4
    /** Short pause between two pages so the portal does not block (one request per category while loading). */
    private const val PAGE_PAUSE_MS = 120L
    /** This many categories in a row without an answer: wait [COOLDOWN_MS], at most [MAX_COOLDOWNS] times. */
    private const val GIVE_UP_AFTER = 3
    private const val MAX_COOLDOWNS = 2
    private const val COOLDOWN_MS = 30_000L

    /** Last response of the portal in short form (only code and kind, never address or MAC) for understandable error texts. */
    @Volatile private var note = ""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun req(src: StalkerLogic.Source, url: String, token: String?): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", StalkerLogic.UA)
            .header("X-User-Agent", StalkerLogic.X_UA)
            .header("Accept", "*/*")
            .header("Referer", src.portal)
            .header("Cookie", StalkerLogic.cookie(src.mac))
        if (token != null) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    /** JSON from a response; portals sometimes send a BOM or text before the JSON. */
    private fun jsonOf(body: String): JSONObject? {
        val i = body.indexOf('{')
        if (i < 0) return null
        return runCatching { JSONObject(body.substring(i)) }.getOrNull()
    }

    /**
     * One request: HTTP code and body. On "429 Too Many Requests" waits as the portal asks (at most a few seconds)
     * and tries once more – portals that limit requests otherwise lock the box out for a while. null = no connection.
     */
    private fun call(src: StalkerLogic.Source, url: String, token: String?): Pair<Int, String>? {
        for (round in 0 until 2) {
            val res = try {
                client.newCall(req(src, url, token)).execute().use { r ->
                    val wait = if (r.code == 429) (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 2L).coerceIn(1L, 6L) else 0L
                    Triple(r.code, r.body?.string().orEmpty(), wait)
                }
            } catch (e: Exception) {
                return null
            }
            if (res.first == 429 && round == 0) { Thread.sleep(res.third * 1000); continue }
            return res.first to res.second
        }
        return null
    }

    /** The portal no longer accepts the session (token taken over by another box, expired or locked). */
    private fun authFailed(body: String) = body.contains("Authorization failed", ignoreCase = true)

    /** Response as the "js" part, null on error (the reason is then in [note]). */
    private fun js(src: StalkerLogic.Source, url: String, token: String?): Any? {
        val (code, body) = call(src, url, token) ?: run { note = "keine Verbindung"; return null }
        if (code !in 200..299) { note = "HTTP $code"; return null }
        if (authFailed(body)) { note = "Authorization failed"; return null }
        val o = jsonOf(body) ?: run { note = "keine gültige Antwort"; return null }
        note = ""
        return o.opt("js")
    }

    /** Log in: find the API, get the token, fetch the profile (some portals only unlock with it). */
    private fun login(src: StalkerLogic.Source, force: Boolean = false): Session {
        synchronized(sessions) {
            val s = sessions[src.key]
            if (!force && s != null && System.currentTimeMillis() - s.at < SESSION_MS) return s
        }
        var seen = ""
        val known = synchronized(sessions) { knownEndpoint[src.key] }
        val eps = StalkerLogic.endpoints(src.portal).let { l -> if (known != null) listOf(known) + (l - known) else l }
        for (ep in eps) {
            // First with, then without "prehash" (old portals do not know it)
            var hs: JSONObject? = null
            for (extra in listOf("&token=&prehash=0", "&token=")) {
                hs = js(src, StalkerLogic.api(ep, "stb", "handshake", extra), null) as? JSONObject
                if (hs?.optString("token").isNullOrBlank()) hs = null else break
                if (note.isNotEmpty()) seen = note
                if (note == "HTTP 429") break
            }
            // The portal is there but limits requests: the other addresses would only make it worse
            if (hs == null && seen == "HTTP 429") break
            val token = hs?.optString("token")?.takeIf { it.isNotBlank() } ?: continue
            js(src, StalkerLogic.api(ep, "stb", "get_profile", StalkerLogic.profileExtra(src)), token)
            val s = Session(src, ep, token, System.currentTimeMillis())
            synchronized(sessions) { sessions[src.key] = s; knownEndpoint[src.key] = ep }
            return s
        }
        throw PlaylistError(
            if (seen.isNotEmpty()) "Das Portal antwortet nicht richtig ($seen). Adresse und MAC-Adresse prüfen."
            else "Das Portal hat die Anmeldung abgelehnt. Adresse und MAC-Adresse prüfen."
        )
    }

    private fun categories(s: Session, type: String, action: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = js(s.src, StalkerLogic.api(s.endpoint, type, action), s.token) as? JSONArray ?: return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString().orEmpty()
            val t = o.optString("title").ifBlank { o.optString("name") }
            if (id.isNotEmpty() && t.isNotBlank()) out[id] = t.trim()
        }
        return out
    }

    private fun fields(o: JSONObject): Map<String, String> {
        val f = HashMap<String, String>()
        for (k in o.keys()) { val v = o.opt(k); if (v is String || v is Number) f[k] = v.toString() }
        return f
    }

    /**
     * Fetch all pages of a list ([extra] = parameters), [each] per entry. Stops at [maxItems], an empty page or
     * a repeating page. true = at least one page arrived.
     */
    private fun paged(s: Session, type: String, extra: String, maxItems: Int, check: () -> Unit, each: (JSONObject) -> Unit): Boolean {
        var page = 1
        var pages = 1
        var got = 0
        var ok = false
        var lastFirst: String? = null
        while (page <= pages && got < maxItems) {
            check()
            val o = js(s.src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$extra&p=$page"), s.token) as? JSONObject ?: break
            ok = true
            val a = o.optJSONArray("data") ?: break
            if (a.length() == 0) break
            val first = a.optJSONObject(0)?.let { it.opt("id")?.toString() ?: it.opt("name")?.toString() }
            if (first != null && first == lastFirst) break
            lastFirst = first
            val total = o.optInt("total_items", 0)
            val per = o.optInt("max_page_items", 14).coerceAtLeast(1)
            // Without a total count just keep paging until a page is empty
            pages = if (total > 0) ((total + per - 1) / per).coerceIn(1, 800) else (page + 1).coerceAtMost(800)
            for (i in 0 until a.length()) { a.optJSONObject(i)?.let { each(it); got++ } }
            page++
        }
        return ok
    }

    /**
     * Write the list to [out] (M3U): all channels, plus only the first page (newest titles) of every movie and series
     * category, fast like IPTVnator. Categories with more titles go to [onCats]; their further pages come when the
     * category is opened or scrolled ([loadChunk], [StalkerMore]). [onPhase] = progress, [check] throws on abort.
     * Return value: number of entries.
     */
    fun write(
        src: StalkerLogic.Source, out: Writer, onPhase: (String) -> Unit, check: () -> Unit,
        onCats: (List<StalkerLogic.CatRef>) -> Unit = {}
    ): Int {
        onPhase("Zugang wird geprüft …")
        val s = login(src, force = true)
        var n = 0
        out.write("#EXTM3U\n")

        // ---- Channels
        onPhase("Sender werden geladen …")
        val genres = categories(s, "itv", "get_genres")
        var live = 0
        val all = js(src, StalkerLogic.api(s.endpoint, "itv", "get_all_channels"), s.token)
        val data = (all as? JSONObject)?.optJSONArray("data") ?: (all as? JSONArray)
        if (data != null) for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            StalkerLogic.liveEntry(src, fields(o), genres)?.let { out.write(it); out.write("\n"); n++; live++ }
            if (i % 1000 == 0) check()
        }
        if (live == 0) {
            // Fallback: many portals block "all channels at once" and deliver only page by page
            paged(s, "itv", "&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0", MAX_CHANNELS, check) { o ->
                StalkerLogic.liveEntry(src, fields(o), genres)?.let { out.write(it); out.write("\n"); n++; live++ }
                if (live % 400 == 0) onPhase("Sender werden geladen ($live) …")
            }
        }

        // ---- Movies and series: only the first page (newest titles) per category, the rest on opening ([loadChunk])
        onPhase("Kategorien werden geladen …")
        val em = Emitter(src) { out.write(it); out.write("\n"); n++ }
        val cats = categories(s, "vod", "get_categories").filterKeys { it != "*" }.map { "vod" to it } +
            categories(s, "series", "get_categories").filterKeys { it != "*" }.map { "series" to it }
        val more = ArrayList<StalkerLogic.CatRef>()
        val skipped = ArrayList<Pair<String, Map.Entry<String, String>>>()
        var failedInRow = 0
        var cooldowns = 0
        fun wait() { var w = 0L; while (w < COOLDOWN_MS) { check(); Thread.sleep(1000); w += 1000 } }
        fun load(type: String, cid: String, cname: String): Paged {
            val r = firstPage(src, type, cid, cname, check, em)
            if (r.more && r.last > 0) more.add(StalkerLogic.CatRef(type, cid, StalkerLogic.groupName(cname), r.total, r.last))
            return r
        }
        for ((idx, c) in cats.withIndex()) {
            val (type, cat) = c
            // Portal stops answering several times in a row: pause briefly, then continue
            if (failedInRow >= GIVE_UP_AFTER && cooldowns < MAX_COOLDOWNS) {
                cooldowns++; failedInRow = 0
                onPhase("Portal antwortet nicht, neuer Versuch in ${COOLDOWN_MS / 1000} s …")
                wait()
            }
            if (failedInRow >= GIVE_UP_AFTER) { skipped.add(c); continue }
            val r = load(type, cat.key, cat.value)
            failedInRow = if (r.ok) 0 else failedInRow + 1
            // Without a single title the category would not appear at all: try again at the end
            if (!r.ok && r.last == 0) skipped.add(c)
            if (idx % 10 == 0) onPhase("Kategorien werden geladen (${idx + 1}/${cats.size}) …")
        }
        if (skipped.isNotEmpty()) {
            onPhase("Kategorien werden erneut versucht …")
            wait()
            var inRow = 0
            for ((type, cat) in skipped) {
                if (inRow >= GIVE_UP_AFTER) break
                inRow = if (load(type, cat.key, cat.value).ok) 0 else inRow + 1
            }
        }
        onCats(more)
        out.flush()
        if (n == 0) {
            val why = if (note.isNotEmpty()) " ($note)" else ""
            throw PlaylistError("Das Portal hat keine Sender geliefert$why. MAC-Adresse und Abo beim Anbieter prüfen.")
        }
        return n
    }

    /** Result of a run over pages: [total] entries according to the portal (-1 = unknown), [last] = last page loaded. */
    private class Paged(val ok: Boolean, val total: Int, val last: Int, val more: Boolean)

    /**
     * Pages [from] .. [from]+[max]-1 of a list to [each]. Stops at an empty or repeating page. [Paged.more] = the portal
     * has further pages; [Paged.ok] false = a page did not come even after logging in again.
     */
    private fun pageRange(
        src: StalkerLogic.Source, type: String, extra: String, from: Int, max: Int, check: () -> Unit, each: (JSONObject) -> Unit
    ): Paged {
        var page = from
        var pages = from
        var total = -1
        var lastFp: String? = null
        while (page <= pages && page < from + max) {
            check()
            var o: JSONObject? = null
            for (attempt in 0 until 2) {
                val s = runCatching { login(src, force = attempt > 0) }.getOrNull() ?: break
                o = js(src, StalkerLogic.api(s.endpoint, type, "get_ordered_list", "$extra&p=$page"), s.token) as? JSONObject
                if (o != null) break
            }
            if (o == null) return Paged(false, total, page - 1, true)
            val a = o.optJSONArray("data") ?: return Paged(true, total, page - 1, false)
            if (a.length() == 0) return Paged(true, total, page - 1, false)
            val fp = a.optJSONObject(0)?.opt("id")?.toString() + "|" + a.optJSONObject(a.length() - 1)?.opt("id")?.toString() + "|" + a.length()
            if (fp == lastFp) return Paged(true, total, page - 1, false)
            lastFp = fp
            total = o.optInt("total_items", -1).takeIf { it > 0 } ?: total
            val per = o.optInt("max_page_items", a.length()).coerceAtLeast(1)
            pages = StalkerLogic.pageCount(total, per, page + 1).coerceAtLeast(page)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let(each)
            page++
            if (page < from + max && page <= pages) Thread.sleep(PAGE_PAUSE_MS)
        }
        return Paged(true, total, page - 1, page <= pages)
    }

    /** Writes movies and series as M3U lines (each movie ID and series name only once). */
    private class Emitter(val src: StalkerLogic.Source, val put: (String) -> Unit) {
        private val movies = HashSet<String>()
        private val series = HashSet<String>()

        fun video(o: JSONObject, type: String, cid: String, cname: String) {
            val f = fields(o)
            if (type == "series" || o.optString("is_series") == "1") {
                val nm = (f["name"] ?: f["title"] ?: "").trim().lowercase()
                if (nm.isNotEmpty() && series.add(nm))
                    StalkerLogic.seriesEntry(src, f, cid, cname, if (type == "series") "s" else "v")?.let(put)
            } else if (movies.add(f["id"] ?: f["cmd"] ?: return)) {
                StalkerLogic.movieEntry(src, f, cname)?.let(put)
            }
        }
    }

    private fun catExtra(cid: String) = "&category=${enc(cid)}&sortby=added"

    /** First page of a category. The portal sends the newest first; the app reads "new" from the end of the list: write oldest first. */
    private fun firstPage(src: StalkerLogic.Source, type: String, cid: String, cname: String, check: () -> Unit, em: Emitter): Paged {
        val rows = ArrayList<JSONObject>()
        val r = pageRange(src, type, catExtra(cid), 1, 1, check) { rows.add(it) }
        for (i in rows.indices.reversed()) em.video(rows[i], type, cid, cname)
        return r
    }

    /** One step loaded on demand: new M3U lines, new state, [done] = no further pages, [ok] false = portal does not answer. */
    class Chunk(val m3u: String, val ref: StalkerLogic.CatRef, val done: Boolean, val ok: Boolean)

    /** The next [LOAD_CHUNK] pages of a category after page [StalkerLogic.CatRef.pages] (on opening or scrolling). */
    fun loadChunk(src: StalkerLogic.Source, ref: StalkerLogic.CatRef, check: () -> Unit): Chunk {
        val sb = StringBuilder()
        val em = Emitter(src) { sb.append(it).append('\n') }
        val rows = ArrayList<JSONObject>()
        val r = pageRange(src, ref.type, catExtra(ref.id), ref.pages + 1, LOAD_CHUNK, check) { rows.add(it) }
        // Oldest first (see [firstPage]); these titles are older than everything loaded so far and go in front
        for (i in rows.indices.reversed()) em.video(rows[i], ref.type, ref.id, ref.name)
        val cur = ref.copy(pages = maxOf(ref.pages, r.last), total = if (r.total > 0) r.total else ref.total)
        return Chunk(sb.toString(), cur, r.ok && !r.more, r.ok)
    }

    private fun sourceOf(key: Int): StalkerLogic.Source? =
        PlaylistStore.lists.asSequence().mapNotNull { StalkerLogic.parse(it.url) }.firstOrNull { it.key == key }

    /**
     * Play command of the first file of a movie/episode with its own files ("/media/file_<file ID>.mpg"), as the MAG box
     * asks for it when the title is opened. [listType] "vod" or "series", [extra] = movie_id (+ season_id, episode_id).
     */
    private fun fileCmd(src: StalkerLogic.Source, s: Session, listType: String, extra: String): String? {
        val o = js(src, StalkerLogic.api(s.endpoint, listType, "get_ordered_list", "$extra&p=1"), s.token)
        val a = (o as? JSONObject)?.optJSONArray("data") ?: (o as? JSONArray) ?: return null
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fields) }
        val total = (o as? JSONObject)?.optInt("total_items", items.size) ?: items.size
        return StalkerLink.fileCmd(items, total)
    }

    /**
     * What the portal answered at the last failed start of a movie/episode, with MAC, tokens, server addresses and
     * account details hidden (see [StalkerLink.mask]). Shown under the error so the cause can be found. Empty = nothing.
     */
    @Volatile var lastDetail = ""

    /**
     * One create_link request: the "js" part (or null) and a short, masked description for [lastDetail].
     * [stop] = more requests now cannot help: no connection (each further form would wait for the same timeout), or still
     * "429 Too Many Requests" after the wait in [call] (each further form would make the limit worse).
     */
    private class LinkAnswer(val js: Any?, val what: String, val authFailed: Boolean, val stop: Boolean = false)

    private fun linkCall(src: StalkerLogic.Source, url: String, token: String): LinkAnswer {
        val (code, body) = call(src, url, token) ?: return LinkAnswer(null, "no connection", false, stop = true)
        if (authFailed(body)) return LinkAnswer(null, "Authorization failed", true)
        if (code == 429) return LinkAnswer(null, "HTTP 429", false, stop = true)
        val o = if (code in 200..299) jsonOf(body) else null
        val what = if (code !in 200..299) "HTTP $code" else o?.opt("js")?.toString() ?: ("no JSON: " + body.take(80))
        return LinkAnswer(o?.opt("js"), StalkerLink.mask(what, src.mac, token), false)
    }

    /** Real play address for "stalker://…" (freshly generated by the portal). null = not possible. */
    suspend fun resolve(c: Context, url: String): String? = withContext(Dispatchers.IO) {
        val trace = ArrayList<String>()
        fun fail(why: String): String? {
            trace.add(why)
            lastDetail = trace.joinToString(" | ").take(500)
            return null
        }
        lastDetail = ""
        val ref = StalkerLogic.itemRef(url) ?: return@withContext fail("bad item address")
        val src = sourceOf(ref.key) ?: return@withContext fail("portal login not found")
        val item = StalkerLink.Play.of(ref.type, ref.cmd, ref.series)

        /**
         * create_link in its request forms. Address, or null; [rejected] = the portal rejected the session,
         * [stopped] = the portal cannot give an address right now (no connection, request limit, stream limit).
         */
        var rejected = false
        var stopped = false
        fun ask(s: Session, cmd: String, series: String?, tag: String): String? {
            for ((i, link) in StalkerLink.requests(s.endpoint, item.linkType, cmd, series).withIndex()) {
                val a = linkCall(src, link, s.token)
                trace.add("$tag.${i + 1}: ${a.what}")
                // Session no longer accepted: other request forms would fail the same way
                if (a.authFailed) { rejected = true; return null }
                if (a.stop) { stopped = true; return null }
                val f = when (val r = a.js) {
                    is JSONObject -> fields(r)
                    is String -> mapOf("cmd" to r)
                    else -> continue
                }
                StalkerLink.address(s.endpoint, src.mac, f, item.linkType, cmd)?.let { return it }
                // Stream limit of the account reached: every other form gets the same answer
                if (StalkerLink.isFinal(StalkerLink.error(f))) { stopped = true; return null }
            }
            return null
        }

        // Channels: one new login after any failure, without a pause, as before (zapping stays as it was).
        // Movies/episodes: a new login only after the portal rejected the session, with a short pause so a request
        // limit can recover.
        val live = ref.type == "itv"
        for (attempt in 0 until (if (live) 2 else 3)) {
            if (attempt > 0 && !live) delay(1500)
            rejected = false
            stopped = false
            val s = runCatching { login(src, force = attempt > 0) }.getOrNull() ?: return@withContext fail("login failed ($note)")
            val n = attempt + 1
            // 1. The command from the list (not for movies that are known to need their file)
            if (item.cmd != null) {
                ask(s, item.cmd, item.series, "$n")?.let { lastDetail = ""; return@withContext it }
                if (live) continue
                if (stopped) break
                if (rejected) continue
            }
            // 2. Movie/episode with its own files (Ministra video club, "nothing_to_play" above): ask for its first file
            if (item.lookup != null) {
                val fc = fileCmd(src, s, item.lookupType, item.lookup)
                trace.add("$n.file: ${fc ?: "none ($note)"}")
                if (fc != null) ask(s, fc, null, "$n.F")?.let { lastDetail = ""; return@withContext it }
                if (stopped) break
                if (rejected || note == "Authorization failed") continue
            }
            break
        }
        // Some portals already deliver a directly playable address in the channel
        if (ref.type == "itv") StalkerLogic.stripCmd(ref.cmd)?.takeIf { !it.contains("localhost") }?.let { return@withContext it }
        fail("item cmd: " + StalkerLink.mask(ref.cmd.take(80), src.mac, ""))
    }

    /**
     * Seasons and episodes of a series ([url] = placeholder "stalker://…/series/…"). null = portal does not answer;
     * empty list = series without episodes. Two portal constructions are recognized: seasons with episode numbers ("series" list)
     * and seasons that deliver their episodes as separate entries.
     */
    fun episodeList(url: String): List<Episode>? {
        lastSeriesDetail = ""
        val trace = ArrayList<String>()
        fun done(l: List<Episode>?, why: String? = null): List<Episode>? {
            why?.let { trace.add(it) }
            if (l.isNullOrEmpty()) lastSeriesDetail = trace.joinToString(" | ").take(500)
            return l
        }
        val ref = StalkerLogic.seriesRef(url) ?: return done(null, "bad series address")
        val src = sourceOf(ref.key) ?: return done(null, "portal login not found")
        // Own series area first ("series"), the video club ("vod") as the second try – portals file series differently
        val types = if (ref.mode == "v") listOf("vod", "series") else listOf("series", "vod")
        for (attempt in 0 until 2) {
            val s = runCatching { login(src, force = attempt > 0) }.getOrNull()
                ?: return done(null, "login failed ($note)")
            var rejected = false
            for (type in types) {
                val l = episodesOf(s, src, ref, type, trace)
                if (!l.isNullOrEmpty()) return l
                if (note == "Authorization failed") { rejected = true; break }
            }
            // Session no longer accepted (e.g. taken over by another box): log in again once
            if (!rejected) break
        }
        return done(null)
    }

    /** Episodes of [ref] read as [type] ("series"/"vod"). null = no answer; [trace] gets a masked summary. */
    private fun episodesOf(s: Session, src: StalkerLogic.Source, ref: StalkerLogic.SeriesRef, type: String, trace: MutableList<String>): List<Episode>? {
        val seen = HashSet<Long>()
        val out = ArrayList<Episode>()

        fun add(season: Int, num: Int, cmd: String, series: String?, seasonId: String = "", episodeId: String = "") {
            // Without a command an episode can still be played via its file (needs season and episode ID)
            if (cmd.isBlank() && episodeId.isEmpty()) return
            if (!seen.add((season.toLong() shl 32) or num.toLong())) return
            // Single episode of a season: keep where it sits, so its file can be looked up at play time if needed
            val u = if (series == null && episodeId.isNotEmpty())
                StalkerLogic.itemUrl(src.key, "efile", StalkerLink.Play.episode(cmd, type, ref.id, seasonId, episodeId))
            else StalkerLogic.itemUrl(src.key, "movie", cmd, series)
            out.add(Episode(season, num, EpTitles.of(num), u))
        }

        /** Entries of a season: episode numbers or single episodes. true = something found. */
        fun fill(o: JSONObject, season: Int, fallbackNum: Int, seasonId: String = ""): Boolean {
            val cmd = o.optString("cmd").trim().takeIf { it != "null" }.orEmpty()
            val nums = o.optJSONArray("series")
            if (nums != null && nums.length() > 0 && cmd.isNotEmpty()) {
                var any = false
                for (i in 0 until nums.length()) {
                    val n = nums.opt(i)?.toString()?.trim()?.toIntOrNull() ?: continue
                    add(season, n, cmd, n.toString()); any = true
                }
                if (any) return true
            }
            if (isSeason(o)) return false
            val eid = if (seasonId.isNotEmpty()) o.opt("id")?.toString()?.trim().orEmpty() else ""
            if (cmd.isNotEmpty() || eid.isNotEmpty()) {
                val before = out.size
                add(season, StalkerLogic.episodeNumber(fields(o), fallbackNum), cmd, null, seasonId, eid)
                return out.size > before
            }
            return false
        }

        val base = "&movie_id=${enc(ref.id)}&category=${enc(ref.cat)}&fav=0&sortby=added&hd=0&not_ended=0"
        val level1 = ArrayList<JSONObject>()
        val ok = paged(s, type, "$base&season_id=0&episode_id=0", 600, {}) { level1.add(it) }
        trace.add("$type: " + if (!ok) "no answer ($note)" else "${level1.size} entries" + (level1.firstOrNull()?.let { " e.g. " + StalkerLink.mask(shape(it), src.mac, s.token) } ?: ""))
        if (!ok) return null
        var idx = 0
        for (o in level1) {
            idx++
            val f = fields(o)
            val season = if (level1.size == 1 && !isSeason(o)) 1 else StalkerLogic.seasonNumber(f, idx)
            if (fill(o, season, idx)) continue
            // Season without own episodes: fetch its episodes individually
            val sid = o.opt("id")?.toString()?.trim().orEmpty()
            if (sid.isEmpty()) continue
            var k = 0
            var first: JSONObject? = null
            paged(s, type, "$base&season_id=${enc(sid)}&episode_id=0", 1000, {}) { e ->
                k++
                if (first == null) first = e
                fill(e, season, k, sid)
            }
            if (idx == 1) trace.add("season 1: $k entries" + (first?.let { " e.g. " + StalkerLink.mask(shape(it), src.mac, s.token) } ?: " ($note)"))
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        return out
    }

    /** Short form of a portal entry for [lastSeriesDetail]: the fields that decide how it is read. */
    private fun shape(o: JSONObject): String {
        val keys = listOf("id", "name", "cmd", "is_season", "is_episode", "is_file", "is_series", "has_files", "series", "season_id", "series_number")
        return keys.filter { o.has(it) }.joinToString(",", "{", "}") { k -> "$k=" + o.opt(k).toString().take(40) }
    }

    /** Why the episodes of a series could not be loaded (masked like [lastDetail]). Empty = nothing. */
    @Volatile var lastSeriesDetail = ""

    private fun isSeason(o: JSONObject): Boolean {
        val v = o.opt("is_season")
        if (v == true || v == 1 || v == "1" || v == "true") return true
        val nm = o.optString("name")
        return o.optJSONArray("series")?.length() == 0 && Regex("""(?i)(season|staffel|sezon|saison|temporada|stagione)""").containsMatchIn(nm)
    }
}
