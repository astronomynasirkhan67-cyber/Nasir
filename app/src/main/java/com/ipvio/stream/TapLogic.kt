package com.ipvio.stream

/**
 * Live buffer on the device (rewinding and recording in Live TV, round 20). Without Android, testable.
 *
 * The live stream (MPEG-TS) is written over ONE connection in pieces of [CHUNK] bytes to the storage.
 * The player reads from these pieces (a few seconds behind the live picture), a recording copies from them.
 * Old pieces are deleted as soon as the configured buffer size is exceeded (ring buffer).
 * Time and byte position are noted continuously ([TimeIndex]) so "30 s back" finds the right position.
 */
object TapLogic {
    const val CHUNK = 16L * 1024 * 1024
    /** Size of a TS packet: read positions are aligned to it. */
    const val TS = 188L

    fun chunkOf(pos: Long): Long = pos / CHUNK
    fun offsetIn(pos: Long): Long = pos % CHUNK
    fun align(pos: Long): Long = if (pos <= 0) 0 else pos - pos % TS

    /** From which position the buffer still has data if at most [maxBytes] are kept (whole pieces). */
    fun keepFrom(written: Long, maxBytes: Long): Long {
        if (written <= maxBytes) return 0L
        // The piece containing the limit is kept entirely
        return chunkOf(written - maxBytes) * CHUNK
    }

    /** Buffer size from the setting (MB) and the free space: at least [reserve] bytes always stay free. */
    fun budget(settingMb: Int, usable: Long, reserve: Long = 400L * 1024 * 1024): Long {
        val want = settingMb.toLong() * 1024 * 1024
        val can = usable - reserve
        return if (can < 3 * CHUNK) 0L else minOf(want, can).coerceAtLeast(3 * CHUNK)
    }

    /** Text for the display "LIVE −1:05". */
    fun behindText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}:" + ((s / 60) % 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
        else "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /** Can this address run through the buffer? Only continuous streams (TS), no playlists (HLS/DASH) or files. */
    fun tappable(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (u.endsWith(".m3u8") || u.endsWith(".m3u") || u.endsWith(".mpd") || u.endsWith(".mp4") || u.endsWith(".mkv")) return false
        return true
    }

    /** File name of a recording (without characters file systems do not like). */
    fun fileName(channel: String, title: String, startMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.ROOT).apply { timeZone = zone }
        val base = listOf(channel, title).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" - ")
        val clean = base.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").replace(Regex("""\s+"""), " ").take(80).trim()
        return (if (clean.isEmpty()) "Aufnahme" else clean) + " " + f.format(java.util.Date(startMs)) + ".ts"
    }

    /**
     * Time (ms since the start of the buffer) ↔ byte position. Extended about every half second while writing.
     * Between two points it is calculated linearly; before the first or after the last point the edge value applies.
     */
    class TimeIndex {
        private var t = LongArray(256)
        private var b = LongArray(256)
        private var n = 0

        @Synchronized fun add(ms: Long, bytes: Long) {
            if (n > 0 && (ms <= t[n - 1] || bytes < b[n - 1])) return
            if (n == t.size) { t = t.copyOf(n * 2); b = b.copyOf(n * 2) }
            t[n] = ms; b[n] = bytes; n++
        }

        /** Discard points before byte position [bytes] (deleted pieces); one stays as an anchor. */
        @Synchronized fun trimBefore(bytes: Long) {
            var i = 0
            while (i + 1 < n && b[i + 1] <= bytes) i++
            if (i == 0) return
            System.arraycopy(t, i, t, 0, n - i); System.arraycopy(b, i, b, 0, n - i)
            n -= i
        }

        @Synchronized fun size() = n
        @Synchronized fun firstMs(): Long = if (n == 0) 0 else t[0]
        @Synchronized fun lastMs(): Long = if (n == 0) 0 else t[n - 1]

        @Synchronized fun byteAt(ms: Long): Long {
            if (n == 0) return 0
            if (ms <= t[0]) return b[0]
            if (ms >= t[n - 1]) return b[n - 1]
            var lo = 0; var hi = n - 1
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (t[m] <= ms) lo = m else hi = m }
            val dt = t[hi] - t[lo]
            return if (dt <= 0) b[lo] else b[lo] + (b[hi] - b[lo]) * (ms - t[lo]) / dt
        }

        @Synchronized fun msAt(bytes: Long): Long {
            if (n == 0) return 0
            if (bytes <= b[0]) return t[0]
            if (bytes >= b[n - 1]) return t[n - 1]
            var lo = 0; var hi = n - 1
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (b[m] <= bytes) lo = m else hi = m }
            val db = b[hi] - b[lo]
            return if (db <= 0) t[lo] else t[lo] + (t[hi] - t[lo]) * (bytes - b[lo]) / db
        }
    }
}
