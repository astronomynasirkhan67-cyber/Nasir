package com.ipvio.stream

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.Writer

/**
 * Loads an Xtream login via player_api.php and writes a list in get.php format from it (see [XtreamLogic]).
 * Large responses (tens of thousands of movies) are read entry by entry and written immediately, never completely in memory.
 */
class XtreamApi(private val client: OkHttpClient, private val ua: String) {

    /** Progress: text for the loading screen. */
    var onPhase: (String) -> Unit = {}
    /** Called regularly and throws on abort. */
    var check: () -> Unit = {}
    /**
     * Growth (or, negative, removal) of the characters in the temporary files of movies and series. The loading screen adds them
     * to the bytes of the list; without them the display stood still at the size of the channel list until everything was there.
     * Called from the download threads.
     */
    var onTempBytes: (Long) -> Unit = {}

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", ua).build()

    private fun text(url: String): String {
        client.newCall(req(url)).execute().use { r ->
            if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
            return r.body?.string() ?: ""
        }
    }

    /** Checks the login. Throws an understandable message if the login is rejected. */
    fun verify(a: XtreamLogic.Acct) {
        val body = text(XtreamLogic.api(a))
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw PlaylistError("Die Schnittstelle des Anbieters antwortet nicht wie erwartet.")
        val ui = o.optJSONObject("user_info")
        val ok = XtreamLogic.authOk(ui?.opt("auth")?.toString(), ui?.optString("status"))
        if (ok == false) throw PlaylistError("Zugang abgelehnt. Benutzername, Passwort oder Abo prüfen.")
    }

    private fun categories(a: XtreamLogic.Acct, action: String): Map<String, String> {
        val out = HashMap<String, String>()
        val arr = runCatching { JSONArray(text(XtreamLogic.api(a, action))) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.opt("category_id")?.toString()?.trim().orEmpty()
            val n = o.optString("category_name").trim()
            if (id.isNotEmpty() && n.isNotEmpty()) out[id] = n
        }
        return out
    }

    /** Reads a flat JSON object (texts and numbers; for lists only the first value, e.g. "category_ids"). */
    private fun flat(r: JsonReader): Map<String, String> {
        val f = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val n = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> f[n] = r.nextString()
                JsonToken.BOOLEAN -> f[n] = r.nextBoolean().toString()
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    var first: String? = null
                    while (r.hasNext()) {
                        if (first == null && (r.peek() == JsonToken.STRING || r.peek() == JsonToken.NUMBER)) first = r.nextString() else r.skipValue()
                    }
                    r.endArray()
                    if (first != null && !f.containsKey(n)) f[n] = first
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        // Some providers name the category only in category_ids
        if (f["category_id"].isNullOrBlank()) f["category_ids"]?.let { f["category_id"] = it }
        return f
    }

    /** Goes through a (large) list entry by entry. If the provider does not answer with a list, nothing happens. */
    private fun stream(url: String, each: (Map<String, String>) -> Unit) {
        client.newCall(req(url)).execute().use { r ->
            if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
            val body = r.body ?: return
            JsonReader(body.charStream()).use { jr ->
                jr.isLenient = true
                if (jr.peek() != JsonToken.BEGIN_ARRAY) return
                jr.beginArray()
                var k = 0
                while (jr.hasNext()) {
                    if (jr.peek() == JsonToken.BEGIN_OBJECT) each(flat(jr)) else jr.skipValue()
                    if (++k % 1000 == 0) check()
                }
                jr.endArray()
            }
        }
    }

    /**
     * Goes through the episodes of a series and reports each to [sink] (episode as flat fields, season from the key).
     * "episodes" is, depending on the provider, an object (season → list) or a list. false = error (after a second attempt).
     */
    private fun walkEpisodes(a: XtreamLogic.Acct, seriesId: String, sink: (Map<String, String>, Int?) -> Unit, reset: () -> Unit): Boolean {
        for (attempt in 0 until 2) {
            try {
                var ok = true
                client.newCall(req(XtreamLogic.api(a, "get_series_info", "&series_id=$seriesId"))).execute().use { r ->
                    if (!r.isSuccessful) { ok = false; return@use }
                    val body = r.body ?: run { ok = false; return@use }
                    JsonReader(body.charStream()).use { jr ->
                        jr.isLenient = true
                        if (jr.peek() != JsonToken.BEGIN_OBJECT) { ok = false; return@use }
                        jr.beginObject()
                        while (jr.hasNext()) {
                            if (jr.nextName() != "episodes") { jr.skipValue(); continue }
                            when (jr.peek()) {
                                JsonToken.BEGIN_OBJECT -> {
                                    jr.beginObject()
                                    while (jr.hasNext()) {
                                        val season = jr.nextName().trim().toIntOrNull()
                                        epList(jr, season, sink)
                                    }
                                    jr.endObject()
                                }
                                JsonToken.BEGIN_ARRAY -> epList(jr, null, sink)
                                else -> jr.skipValue()
                            }
                        }
                        jr.endObject()
                    }
                }
                return ok
            } catch (e: IOException) {
                reset()
                if (attempt == 1) return false
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun epList(jr: JsonReader, season: Int?, sink: (Map<String, String>, Int?) -> Unit) {
        if (jr.peek() != JsonToken.BEGIN_ARRAY) { jr.skipValue(); return }
        jr.beginArray()
        while (jr.hasNext()) {
            when (jr.peek()) {
                JsonToken.BEGIN_OBJECT -> sink(flat(jr), season)
                // Some providers nest one level deeper
                JsonToken.BEGIN_ARRAY -> epList(jr, season, sink)
                else -> jr.skipValue()
            }
        }
        jr.endArray()
    }

    /** Episodes of a series as finished lines; empty on error. */
    private fun episodes(a: XtreamLogic.Acct, s: XtreamLogic.Series): String {
        val sb = StringBuilder()
        val ok = walkEpisodes(a, s.id, { f, season -> XtreamLogic.episodeEntry(a, s, f, season)?.let { sb.append(it).append('\n') } }, { sb.setLength(0) })
        return if (ok) sb.toString() else ""
    }

    /**
     * Load episodes of a series on opening (see [LazySeries]). null = provider does not answer; empty list = series without episodes.
     * Several entries with the same season/episode (duplicates at the provider) are merged, the first address counts.
     */
    fun episodeList(a: XtreamLogic.Acct, seriesId: String): List<Episode>? {
        val seen = HashSet<Long>()
        var out = ArrayList<Episode>()
        val ok = walkEpisodes(a, seriesId, { f, season ->
            XtreamLogic.episodeParts(a, f, season)?.let { (se, n, url) ->
                if (seen.add((se.toLong() shl 32) or n.toLong())) out.add(Episode(se, n, EpTitles.of(n), url, emptyList(), null))
            }
        }, { seen.clear(); out = ArrayList() })
        if (!ok) return null
        out.sortWith(compareBy({ it.season }, { it.num }))
        return out
    }

    /** Movies as finished lines into [w]; returns their number. */
    private fun writeMovies(a: XtreamLogic.Acct, w: Writer): Int {
        var n = 0
        val vodCats = categories(a, "get_vod_categories")
        stream(XtreamLogic.api(a, "get_vod_streams")) { f ->
            XtreamLogic.movieEntry(a, f, vodCats)?.let { w.write(it); w.write("\n"); n++ }
        }
        check()
        return n
    }

    /** Series (one placeholder per series, the episodes come on opening via LazySeries) into [w]; returns their number. */
    private fun writeSeries(a: XtreamLogic.Acct, w: Writer): Int {
        val serCats = categories(a, "get_series_categories")
        val series = ArrayList<XtreamLogic.Series>()
        stream(XtreamLogic.api(a, "get_series")) { f -> XtreamLogic.seriesOf(f, serCats)?.let { series.add(it) } }
        for (s in series) { w.write(XtreamLogic.lazyEntry(a, s)); w.write("\n") }
        return series.size
    }

    /**
     * Writes the whole list to [out]. Return value: number of entries (channels + movies + series).
     * For series only a placeholder arrives; [LazySeries] fetches the episodes only when a series is opened.
     * Movies and series are fetched at the same time as the channels (into temporary files, appended in the old order):
     * the three downloads no longer wait for each other, which is the biggest part of the first setup. If the provider refuses
     * the parallel requests, the missing part is simply fetched again one after the other.
     */
    fun write(a: XtreamLogic.Acct, out: Writer): Int {
        onPhase("Zugang wird geprüft …")
        verify(a)
        var n = 0
        out.write(XtreamLogic.header(a)); out.write("\n")

        val vodFile = java.io.File.createTempFile("ipvio_vod", ".part")
        val serFile = java.io.File.createTempFile("ipvio_ser", ".part")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val vodCount = java.util.concurrent.atomic.AtomicLong()
        val serCount = java.util.concurrent.atomic.AtomicLong()
        try {
            // Writes one temporary file and reports its growth for the progress display. A repeated attempt starts the file
            // again, so what the earlier attempt reported is taken back first.
            fun fill(file: java.io.File, count: java.util.concurrent.atomic.AtomicLong, work: (Writer) -> Int): Int {
                onTempBytes(-count.getAndSet(0))
                return file.bufferedWriter(Charsets.UTF_8).use { w ->
                    val cw = TempCounter(w) { k -> count.addAndGet(k); onTempBytes(k) }
                    work(cw).also { cw.flush() }
                }
            }
            fun job(file: java.io.File, count: java.util.concurrent.atomic.AtomicLong, work: (Writer) -> Int) =
                pool.submit<Int> { fill(file, count, work) }
            val vodJob = job(vodFile, vodCount) { writeMovies(a, it) }
            val serJob = job(serFile, serCount) { writeSeries(a, it) }

            onPhase("Sender werden geladen …")
            val liveCats = categories(a, "get_live_categories")
            stream(XtreamLogic.api(a, "get_live_streams")) { f ->
                XtreamLogic.liveEntry(a, f, liveCats)?.let { out.write(it); out.write("\n"); n++ }
            }
            check()

            fun take(f: java.util.concurrent.Future<Int>, file: java.io.File, tmp: java.util.concurrent.atomic.AtomicLong, again: (Writer) -> Int): Int {
                val count = try { f.get() } catch (e: java.util.concurrent.ExecutionException) {
                    val c = e.cause
                    // Only a network problem of the parallel request is tried again one by one; abort and login errors pass on
                    if (c is IOException) fill(file, tmp, again) else throw (c ?: e)
                }
                // From here the same characters are counted as part of the list: no longer count them as temporary
                onTempBytes(-tmp.getAndSet(0))
                file.reader(Charsets.UTF_8).use { it.copyTo(out) }
                return count
            }
            onPhase("Filme werden geladen …")
            n += take(vodJob, vodFile, vodCount) { writeMovies(a, it) }
            check()
            onPhase("Serien werden geladen …")
            n += take(serJob, serFile, serCount) { writeSeries(a, it) }
            out.flush()
            return n
        } finally {
            pool.shutdownNow()
            runCatching { vodFile.delete() }
            runCatching { serFile.delete() }
        }
    }

}

/** Counts the characters written (about the bytes of the mostly ASCII list) and reports the growth every 64 K and on flush. */
private class TempCounter(w: Writer, private val onGrow: (Long) -> Unit) : java.io.FilterWriter(w) {
    private var n = 0L
    private var last = 0L
    override fun write(c: Int) { out.write(c); add(1) }
    override fun write(cbuf: CharArray, off: Int, len: Int) { out.write(cbuf, off, len); add(len.toLong()) }
    override fun write(str: String, off: Int, len: Int) { out.write(str, off, len); add(len.toLong()) }
    private fun add(k: Long) { n += k; if (n - last >= 65_536) report() }
    private fun report() { if (n > last) { onGrow(n - last); last = n } }
    override fun flush() { report(); super.flush() }
}
