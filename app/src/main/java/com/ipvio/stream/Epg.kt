package com.ipvio.stream

import android.content.Context
import android.util.Base64
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

private val FMT = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
    override fun initialValue() = HashMap<String, SimpleDateFormat>()
}

private fun fmt(pattern: String): SimpleDateFormat {
    val loc = Lang.locale()
    val m = FMT.get()!!
    val f = m.getOrPut(pattern + "|" + loc.toLanguageTag()) { SimpleDateFormat(pattern, loc) }
    f.timeZone = epgZone()
    return f
}

/** Time zone for all clock times (setting "Time zone" under Language & Region; default: system time of the device). */
fun epgZone(): TimeZone = General.zone()

/** Clock time in the chosen format (24 h / 12 h) and in the chosen time zone. The formatters are reused per thread. */
fun hhmm(sec: Long): String = fmt(SettingsLogic.timePattern(General.timeFmt, General.sys24)).format(Date(sec * 1000))

/** Day "Mo 19.10." in the chosen time zone and language. */
fun dayLabel(sec: Long): String = fmt("EE dd.MM.").format(Date(sec * 1000))

/** Shared network access for program info: one client (connections are reused) and at most 6 requests at the same time. */
object EpgNet {
    val client = Net.withTimeouts(8, 12)
    val gate = Semaphore(6)

    suspend fun fetch(url: String): String? = gate.withPermit {
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (e: Exception) {
                null
            }
        }
    }
}

object Xmltv {
    var status by mutableStateOf("Noch nicht geladen")
    var channelCount by mutableStateOf(0)
    private val progs = ConcurrentHashMap<String, List<EpgEntry>>()
    private val client = Net.withTimeouts(20, 180)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_epg", Context.MODE_PRIVATE)
    fun customUrl(c: Context): String = prefs(c).getString("custom", "") ?: ""
    fun setCustom(c: Context, u: String) { prefs(c).edit().putString("custom", u.trim()).apply() }

    fun has(id: String?): Boolean = id != null && progs.containsKey(id.lowercase())
    fun forChannel(id: String?): List<EpgEntry> = if (id == null) emptyList() else progs[id.lowercase()] ?: emptyList()

    /**
     * Loads the XMLTV program for the visible channels (hidden languages do not even arrive).
     * Only what is needed is stored: window −1 h to +36 h, at most 80 shows per channel, description shortened,
     * equal titles only once. If memory gets tight, parsing stops and keeps what has been read so far.
     */
    /** [delayMs]: at app start the guide waits a little so the home screen and the playlist get the CPU first. */
    fun start(c: Context, urls: List<String>, items: List<Item>, delayMs: Long = 0L) {
        val all = (urls + listOf(customUrl(c))).filter { it.startsWith("http") }.distinct()
        if (all.isEmpty()) { status = "Keine EPG-Adresse gefunden"; return }
        val wanted = items.filter { it.kind == Kind.LIVE }.mapNotNull { it.tvgId?.lowercase() }.toSet()
        if (wanted.isEmpty()) { status = "Playlist enthält keine Sender-IDs (tvg-id)"; return }
        job?.cancel()
        job = scope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            status = "Wird geladen …"
            val map = HashMap<String, MutableList<EpgEntry>>()
            var err: String? = null
            truncated = false
            // Channels with archive (catchup): also keep past shows (at most 7 days, memory)
            val past = HashMap<String, Int>()
            for (it in items) {
                val id = it.tvgId ?: continue
                val d = Catchup.of(it)?.days ?: continue
                val k = id.lowercase()
                past[k] = maxOf(past[k] ?: 0, d.coerceAtMost(7))
            }
            for (u in all) {
                try { parse(u, wanted, map, past) } catch (e: Exception) { err = if (e is PlaylistError) e.message else e.javaClass.simpleName }
            }
            progs.clear()
            for ((k, v) in map) progs[k] = v.sortedBy { it.start }
            channelCount = progs.size
            status = if (progs.isEmpty()) "Keine Programmdaten erhalten (${err ?: "leer"})"
            else if (truncated) "Geladen: ${progs.size} Sender mit Programm (gekürzt, Speicher knapp)"
            else "Geladen: ${progs.size} Sender mit Programm"
        }
    }

    private fun parseTime(s: String?): Long? = xmltvTime(s)

    private const val MAX_PER_CHANNEL = 80
    private const val MAX_DESC = 400
    @Volatile private var truncated = false

    private fun shortDesc(d: String): String =
        if (d.length <= MAX_DESC) d else d.substring(0, d.lastIndexOf(' ', MAX_DESC).takeIf { it > MAX_DESC / 2 } ?: MAX_DESC) + " …"

    private fun parse(url: String, wanted: Set<String>, out: HashMap<String, MutableList<EpgEntry>>, past: Map<String, Int> = emptyMap()) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw PlaylistError("HTTP ${r.code}")
            var ins: InputStream = BufferedInputStream((r.body ?: throw PlaylistError("leer")).byteStream())
            ins.mark(2)
            val b1 = ins.read()
            val b2 = ins.read()
            ins.reset()
            if (b1 == 0x1f && b2 == 0x8b) ins = GZIPInputStream(ins)
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            val now = System.currentTimeMillis() / 1000
            val pool = StringPool()
            var seen = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel")?.lowercase()
                    val s = parseTime(p.getAttributeValue(null, "start"))
                    val e = parseTime(p.getAttributeValue(null, "stop"))
                    var title = ""
                    var desc = ""
                    var inner = p.next()
                    while (!(inner == XmlPullParser.END_TAG && p.name == "programme") && inner != XmlPullParser.END_DOCUMENT) {
                        if (inner == XmlPullParser.START_TAG) {
                            if (p.name == "title" && title.isEmpty()) title = p.nextText()
                            else if (p.name == "desc" && desc.isEmpty()) desc = p.nextText()
                        }
                        inner = p.next()
                    }
                    val pd = if (ch != null) past[ch] ?: 0 else 0
                    if (ch != null && ch in wanted && s != null && e != null &&
                        e > now - (if (pd > 0) pd * 86400L else 3600L) && s < now + 129600 && epgSane(s, e)
                    ) {
                        val l = out.getOrPut(ch) { ArrayList() }
                        if (l.size < MAX_PER_CHANNEL + pd * 48) l.add(EpgEntry(pool.of(title), shortDesc(desc), s, e))
                    }
                    // Memory is getting tight: stop and keep what has been read so far (better gaps in the program than a crash)
                    if (++seen % 5000 == 0 && Mem.heapTight(0.70)) { truncated = true; return }
                }
                ev = p.next()
            }
        }
    }
}

/** Small cache with an upper limit: the most recently used entries stay, the oldest drop out. */
class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized operator fun get(k: K): V? = map[k]
    @Synchronized operator fun set(k: K, v: V) { map[k] = v }
    @Synchronized fun clear() = map.clear()
}

object Epg {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(400)
    fun trim() = cache.clear()
    private val LIVE_RE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")

    fun supported(item: Item): Boolean = LIVE_RE.matches(item.url) || Xmltv.has(item.tvgId)

    /** Address of the Xtream program query [action] for a channel or null if the channel has no Xtream address. */
    internal fun xtreamApi(item: Item, action: String): String? {
        val p = LIVE_RE.find(item.url)?.groupValues ?: return null
        return "${p[1]}/player_api.php?username=${p[2]}&password=${p[3]}&action=$action&stream_id=${p[4]}"
    }

    /** Xtream delivers title and description Base64-encoded, but some providers in plain text. */
    private fun decode(s: String): String {
        if (s.isBlank()) return ""
        return try {
            val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            if (d.isBlank() || d.contains('\uFFFD')) s else d
        } catch (e: Exception) {
            s
        }
    }

    /** Reads "epg_listings" of an Xtream response; broken entries are skipped. Result sorted by start time. */
    internal fun parseListings(body: String, keep: (start: Long, end: Long) -> Boolean = { _, _ -> true }): List<EpgEntry> {
        val out = ArrayList<EpgEntry>()
        try {
            val a = JSONObject(body).optJSONArray("epg_listings")
            if (a != null) for (x in 0 until a.length()) {
                val o = a.getJSONObject(x)
                val s = o.optString("start_timestamp").toLongOrNull() ?: continue
                val e = o.optString("stop_timestamp").toLongOrNull() ?: continue
                if (!epgSane(s, e) || !keep(s, e)) continue
                out.add(EpgEntry(decode(o.optString("title")), decode(o.optString("description")), s, e))
            }
        } catch (e: Exception) {
        }
        out.sortBy { it.start }
        return out
    }

    /** Running and upcoming shows of a channel. Order: cache, loaded XMLTV (immediately), then Xtream query. */
    suspend fun now(item: Item): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        cache[item.id]?.let { if (nowMs - it.first < 120_000) return it.second }
        // Everything below (XMLTV filter, JSON parsing, Base64) runs off the main thread, whoever calls this
        return withContext(Dispatchers.Default) { nowFresh(item, nowMs, nowSec) }
    }

    private suspend fun nowFresh(item: Item, nowMs: Long, nowSec: Long): List<EpgEntry> {
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > nowSec }
        if (local.isNotEmpty() && current(local) != null) return local.take(8).also { cache[item.id] = nowMs to it }
        val url = xtreamApi(item, "get_short_epg")
        if (url != null) {
            val body = EpgNet.fetch("$url&limit=8")
            if (body != null) {
                val out = parseListings(body)
                if (out.isNotEmpty()) { cache[item.id] = nowMs to out; return out }
            }
        }
        return local.take(8)
    }

    /** Cached result of [now] if it is still fresh, without any lookup or network (cheap enough for composition). */
    fun peek(item: Item): List<EpgEntry>? {
        val c = cache[item.id] ?: return null
        return if (System.currentTimeMillis() - c.first < 120_000) c.second else null
    }

    fun current(list: List<EpgEntry>): EpgEntry? {
        val n = System.currentTimeMillis() / 1000
        return list.firstOrNull { n >= it.start && n < it.end }
    }
}

data class Seg(val label: String, val start: Long, val end: Long)

object IntroDb {
    private val client = Net.base

    suspend fun get(tmdbId: Int, season: Int, episode: Int, durMs: Long): List<Seg> {
        val tv = if (season > 0 && episode > 0) "&season=$season&episode=$episode" else ""
        val url = "https://api.theintrodb.org/v3/media?tmdb_id=$tmdbId$tv&duration_ms=$durMs"
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList()
        val out = ArrayList<Seg>()
        try {
            val o = JSONObject(body)
            // Only plausible sections (see SegLogic): missing end times for intro/recap or an "intro" in the middle of the episode
            // would otherwise lead to buttons that do not fit
            fun read(key: String, kind: Int, label: String) {
                val a = o.optJSONArray(key) ?: return
                for (x in 0 until a.length()) {
                    val s = a.optJSONObject(x) ?: continue
                    val st = if (!s.has("start_ms") || s.isNull("start_ms")) null else s.optLong("start_ms")
                    val en = if (!s.has("end_ms") || s.isNull("end_ms")) null else s.optLong("end_ms")
                    SegLogic.clean(kind, st, en, durMs)?.let { (a0, b0) -> out.add(Seg(label, a0, b0)) }
                }
            }
            read("intro", SegLogic.INTRO, "Intro überspringen")
            read("recap", SegLogic.RECAP, "Rückblick überspringen")
            read("credits", SegLogic.CREDITS, "Abspann überspringen")
        } catch (e: Exception) {
        }
        return out
    }
}