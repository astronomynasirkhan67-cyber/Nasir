package com.ipvio.stream

import java.util.Calendar
import java.util.TimeZone

/** A show in the program (times in seconds since 1970, UTC). */
data class EpgEntry(val title: String, val desc: String, val start: Long, val end: Long)

/**
 * Archive (catchup) of a channel: how the address of a past show is built and how many days back it goes.
 * [mode] see [CatchupLogic] (XC, DEFAULT, APPEND, SHIFT, FLUSSONIC), [source] = template of the provider (catchup-source).
 */
data class CatchupInfo(val mode: Int, val days: Int, val source: String = "")

/** An Xtream channel from its live address: server, login, channel number, file extension. */
data class XtreamLive(val host: String, val user: String, val pass: String, val id: String, val ext: String)

/**
 * Catchup without Android (testable). Supports:
 * - Xtream servers (tv_archive from the API or catchup="xc"): /timeshift/user/pass/minutes/starttime/id.ts
 * - M3U attributes as in Kodi/TiviMate: catchup="default|append|shift|flussonic|xc", catchup-days, catchup-source,
 *   plus the older spellings timeshift="3" and tvg-rec="3".
 * Placeholders in catchup-source: {utc} {start} {utcend} {end} {lutc} {now} {timestamp} {duration} {offset},
 * with divisor ({duration:60}) or format ({utc:Y-m-d H:M:S}), {Y} {m} {d} {H} {M} {S} and the ${start} spelling.
 */
object CatchupLogic {
    const val XC = 1
    const val DEFAULT = 2
    const val APPEND = 3
    const val SHIFT = 4
    const val FLUSSONIC = 5

    const val MAX_DAYS = 14
    /** Without a number of days (catchup="…" alone): offered this far back. */
    const val DEFAULT_DAYS = 3

    private val LIVE_RE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?(?:\?.*)?$""")

    fun xtreamLive(url: String): XtreamLive? {
        val m = LIVE_RE.find(url.trim()) ?: return null
        val g = m.groupValues
        // Movie/series addresses have the same form but are not live channels
        if (g[2] == "movie" || g[2] == "series" || g[2] == "timeshift") return null
        return XtreamLive(g[1], g[2], g[3], g[4], g[5].ifEmpty { "ts" })
    }

    fun modeOf(s: String?): Int? = when (s?.trim()?.lowercase()) {
        null, "" -> null
        "xc" -> XC
        "default" -> DEFAULT
        "append" -> APPEND
        "shift", "timeshift" -> SHIFT
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> FLUSSONIC
        else -> null
    }

    private val ATTR_CACHE = HashMap<String, Regex>()
    private fun attr(line: String, key: String): String? {
        val re = synchronized(ATTR_CACHE) { ATTR_CACHE.getOrPut(key) { Regex("""(?:^|\s)""" + Regex.escape(key) + "=\"([^\"]*)\"") } }
        return re.find(line)?.groupValues?.get(1)
    }

    /** Quick pre-check before attributes are searched (most lines have none). */
    fun mentions(line: String): Boolean =
        line.contains("catchup", true) || line.contains("timeshift=", true) || line.contains("tvg-rec=", true)

    /**
     * Catchup from an #EXTINF line (or the #EXTM3U header line). [header] = defaults from the head of the list.
     * null = no archive.
     */
    fun fromInfo(line: String, url: String, header: CatchupInfo? = null): CatchupInfo? {
        val has = mentions(line)
        if (!has && header == null) return null
        val modeS = if (has) attr(line, "catchup") ?: attr(line, "catchup-type") else null
        val daysS = if (has) attr(line, "catchup-days") ?: attr(line, "timeshift") ?: attr(line, "tvg-rec") else null
        val source = if (has) attr(line, "catchup-source").orEmpty() else ""
        if (modeS == null && daysS == null && source.isEmpty()) {
            if (header == null) return null
            return finish(header.mode, header.days, header.source, url)
        }
        val days = daysS?.trim()?.toIntOrNull()
        if (days != null && days <= 0) return null
        val mode = modeOf(modeS) ?: when {
            source.isNotEmpty() -> DEFAULT
            header != null -> header.mode
            else -> -1
        }
        return finish(mode, days ?: header?.days ?: DEFAULT_DAYS, source.ifEmpty { header?.source.orEmpty() }, url)
    }

    /** Derive a missing kind from the address and discard obviously unusable details. */
    private fun finish(mode: Int, days: Int, source: String, url: String): CatchupInfo? {
        // Header line of the list: the kind may stay open (-1), it results per channel from its address
        if (url.isEmpty()) return CatchupInfo(mode, days.coerceIn(1, MAX_DAYS), source)
        val xt = xtreamLive(url) != null
        val m = when {
            mode > 0 -> mode
            xt -> XC
            else -> SHIFT
        }
        if (m == XC && !xt && source.isEmpty()) return null
        if (m == DEFAULT && source.isEmpty() && !xt) return CatchupInfo(SHIFT, days.coerceIn(1, MAX_DAYS))
        return CatchupInfo(m, days.coerceIn(1, MAX_DAYS), source)
    }

    /** Can the show (start [start], end [end], seconds) be played from the archive? Running shows: "From the beginning". */
    fun canPlay(info: CatchupInfo?, start: Long, end: Long, now: Long): Boolean {
        if (info == null || info.days <= 0 || end <= start) return false
        if (start > now - 60) return false
        return start >= now - info.days * 86400L - 1800
    }

    /** Address of the show in the archive or null if it cannot be built. [zone] = time zone of the server (Xtream). */
    fun url(info: CatchupInfo, live: String, start: Long, end: Long, now: Long, zone: TimeZone): String? {
        val dur = (end - start).coerceAtLeast(60)
        return when (info.mode) {
            XC -> {
                if (info.source.isNotEmpty()) return fill(info.source, start, end, now, zone)
                val x = xtreamLive(live) ?: return null
                val min = (dur + 59) / 60
                val ext = if (x.ext == "m3u8") "m3u8" else "ts"
                "${x.host}/timeshift/${x.user}/${x.pass}/$min/${stamp(start, zone)}/${x.id}.$ext"
            }
            DEFAULT -> when {
                info.source.isNotEmpty() -> fill(info.source, start, end, now, zone)
                xtreamLive(live) != null -> url(info.copy(mode = XC), live, start, end, now, zone)
                else -> shift(live, start, now)
            }
            APPEND -> live + fill(info.source.ifEmpty { "?utc={utc}&lutc={lutc}" }, start, end, now, zone).let {
                if (live.contains('?') && it.startsWith("?")) "&" + it.substring(1) else it
            }
            SHIFT -> shift(live, start, now)
            FLUSSONIC -> flussonic(live, start, dur) ?: info.source.takeIf { it.isNotEmpty() }?.let { fill(it, start, end, now, zone) }
            else -> null
        }
    }

    private fun shift(live: String, start: Long, now: Long): String =
        live + (if (live.contains('?')) "&" else "?") + "utc=$start&lutc=$now"

    /** Flussonic: …/index.m3u8 → …/index-START-DURATION.m3u8, …/mpegts → …/timeshift_abs-START.ts */
    private fun flussonic(live: String, start: Long, dur: Long): String? {
        val q = live.indexOf('?')
        val path = if (q >= 0) live.substring(0, q) else live
        val query = if (q >= 0) live.substring(q) else ""
        val slash = path.lastIndexOf('/')
        if (slash < 8) return null
        val dir = path.substring(0, slash)
        val last = path.substring(slash + 1)
        return when {
            last.endsWith(".m3u8") -> "$dir/${last.removeSuffix(".m3u8")}-$start-$dur.m3u8$query"
            last == "mpegts" || last.endsWith(".ts") -> "$dir/timeshift_abs-$start.ts$query"
            else -> null
        }
    }

    /** Start time for Xtream: "2024-05-01:20-15" in the server's time zone. */
    fun stamp(sec: Long, zone: TimeZone): String = format(sec, zone, "Y-m-d:H-M")

    private val PH = Regex("""\$?\{(\w+)(?::([^}]*))?\}""")

    /** Fill the placeholders of a template (see class description). Unknown placeholders stay. */
    fun fill(template: String, start: Long, end: Long, now: Long, zone: TimeZone): String =
        PH.replace(template) { m ->
            val name = m.groupValues[1]
            val arg = m.groupValues[2]
            val value: Long? = when (name.lowercase()) {
                "utc", "start" -> start
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                "duration" -> end - start
                "offset" -> now - start
                else -> null
            }
            when {
                value == null && name.length == 1 && name[0] in "YmdHMS" -> format(start, zone, name)
                value == null -> m.value
                arg.isEmpty() -> value.toString()
                arg.toLongOrNull()?.let { it > 0 } == true -> (value / arg.toLong()).toString()
                name.lowercase() in setOf("duration", "offset") -> value.toString()
                else -> format(value, zone, arg)
            }
        }

    /** Time according to a pattern of Y m d H M S (everything else stays as it is). */
    fun format(sec: Long, zone: TimeZone, pattern: String): String {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = sec * 1000
        val sb = StringBuilder()
        for (ch in pattern) sb.append(
            when (ch) {
                'Y' -> c.get(Calendar.YEAR).toString()
                'm' -> two(c.get(Calendar.MONTH) + 1)
                'd' -> two(c.get(Calendar.DAY_OF_MONTH))
                'H' -> two(c.get(Calendar.HOUR_OF_DAY))
                'M' -> two(c.get(Calendar.MINUTE))
                'S' -> two(c.get(Calendar.SECOND))
                else -> ch.toString()
            }
        )
        return sb.toString()
    }

    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    // ---------------------------------------------------------------- Saving

    /** One line per channel: id TAB kind TAB days TAB template. */
    fun encode(m: Map<String, CatchupInfo>): String = buildString {
        for ((id, i) in m) append(id).append('\t').append(i.mode).append('\t').append(i.days).append('\t')
            .append(i.source.replace('\t', ' ').replace('\n', ' ')).append('\n')
    }

    fun decode(s: String): Map<String, CatchupInfo> {
        val out = HashMap<String, CatchupInfo>()
        for (line in s.lineSequence()) {
            val p = line.split('\t')
            if (p.size < 3 || p[0].isEmpty()) continue
            val mode = p[1].toIntOrNull() ?: continue
            val days = p[2].toIntOrNull() ?: continue
            if (mode !in XC..FLUSSONIC || days <= 0) continue
            out[p[0]] = CatchupInfo(mode, days.coerceAtMost(MAX_DAYS), p.getOrElse(3) { "" })
        }
        return out
    }

    /**
     * Xtream API (get_live_streams): channel number → archive days. Assigns the channels of the playlist ([live] = id → address).
     * Details from the M3U ([m3u]) take precedence, they are more exact (own template).
     */
    fun merge(live: Map<String, String>, archive: Map<String, Int>, m3u: Map<String, CatchupInfo>): Map<String, CatchupInfo> {
        val out = HashMap<String, CatchupInfo>()
        if (archive.isNotEmpty()) for ((id, url) in live) {
            val x = xtreamLive(url) ?: continue
            val d = archive[x.id] ?: continue
            if (d > 0) out[id] = CatchupInfo(XC, d.coerceAtMost(MAX_DAYS))
        }
        out.putAll(m3u)
        return out
    }

    /** API value ("1", 1, "7", 7, true) as a number. */
    fun apiInt(v: String?): Int = v?.trim()?.let { it.toIntOrNull() ?: if (it.equals("true", true)) 1 else 0 } ?: 0

    /** Next/previous show in the archive (for "Next show" and automatic continued playback). */
    fun neighbour(list: List<EpgEntry>, start: Long, d: Int): EpgEntry? {
        val sorted = list.sortedBy { it.start }
        val i = sorted.indexOfFirst { it.start == start }
        if (i < 0) return if (d > 0) sorted.firstOrNull { it.start > start } else sorted.lastOrNull { it.start < start }
        return sorted.getOrNull(i + d)
    }

    /** Shows in the archive window, newest first (for the archive list in the player). */
    fun archived(info: CatchupInfo?, list: List<EpgEntry>, now: Long): List<EpgEntry> =
        list.filter { canPlay(info, it.start, it.end, now) && it.end <= now }.sortedByDescending { it.start }
}
