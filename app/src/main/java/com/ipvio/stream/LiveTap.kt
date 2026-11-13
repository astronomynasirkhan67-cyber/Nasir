package com.ipvio.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Live buffer for ONE channel (see [TapLogic]): one connection to the provider, the data lands in pieces on
 * the storage. Player and recording read from it. If the connection drops, it reconnects and keeps writing.
 *
 * Lifetime via counters: [acquire]/[release]. When nobody is attached any more (player closed, no recording), it is closed
 * and the folder deleted.
 */
class LiveTap(val id: Int, val url: String, private val dir: File, private val maxBytes: Long, private val ua: String) {
    private val lock = Object()
    @Volatile var written = 0L
        private set
    /** First byte position still present (older pieces are deleted). */
    @Volatile var start = 0L
        private set
    @Volatile var closed = false
        private set
    /** Final error (e.g. login rejected); then the buffer stops writing. */
    @Volatile var failure: String? = null
        private set
    /** Number of reconnects (for the hint in the player). */
    @Volatile var reconnects = 0
        private set
    val index = TapLogic.TimeIndex()
    /** Start of the buffer: clock time (for show times from the TV guide) and monotonic time (for the distance). */
    val startedWall = System.currentTimeMillis()
    private val startedMono = System.nanoTime()
    private var refs = 0
    private var thread: Thread? = null

    private val client: OkHttpClient = Net.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(TlsFallback.interceptor())
        .build()

    /** Milliseconds since the start of the buffer. */
    fun nowMs(): Long = (System.nanoTime() - startedMono) / 1_000_000

    /** Time (ms since start) of the oldest data still present. */
    fun oldestMs(): Long = index.msAt(start)

    fun acquire(): LiveTap { synchronized(lock) { refs++ }; return this }

    /** true if nobody uses the buffer afterwards (it is then closed). */
    fun release(): Boolean {
        val last = synchronized(lock) { refs = (refs - 1).coerceAtLeast(0); refs == 0 }
        if (last) close()
        return last
    }

    fun users(): Int = synchronized(lock) { refs }

    private fun chunkFile(idx: Long) = File(dir, "c$idx.ts")

    fun begin() {
        dir.mkdirs()
        val t = Thread({ run() }, "LiveTap-$id")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Rebuild the connection immediately (player thinks it hangs). */
    fun kick() { runCatching { call?.cancel() } }

    @Volatile private var call: okhttp3.Call? = null

    private var out: FileOutputStream? = null
    private var outIdx = -1L
    private var lastSample = -1000L

    /** Append received data to the buffer (piece by piece), note the time, delete old pieces. */
    internal fun append(buf: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            val pos = written
            val idx = TapLogic.chunkOf(pos)
            if (idx != outIdx) {
                runCatching { out?.close() }
                out = FileOutputStream(chunkFile(idx), true)
                outIdx = idx
            }
            val room = (TapLogic.CHUNK - TapLogic.offsetIn(pos)).toInt()
            val k = minOf(room, n - done)
            out!!.write(buf, done, k)
            done += k
            synchronized(lock) { written = pos + k; lock.notifyAll() }
        }
        val now = nowMs()
        if (now - lastSample >= 500) { lastSample = now; index.add(now, written) }
        trim()
    }

    private fun run() {
        var backoff = 1000L
        val buf = ByteArray(64 * 1024)
        try {
            while (!closed) {
                try {
                    val c = client.newCall(Request.Builder().url(url).header("User-Agent", ua).build())
                    call = c
                    c.execute().use { r ->
                        if (!r.isSuccessful) {
                            if (isPermanentHttp(r.code)) { failure = PlaylistLogic.httpError(r.code); return }
                            throw IOException("HTTP " + r.code)
                        }
                        val ins = r.body?.byteStream() ?: throw IOException("leer")
                        backoff = 1000L
                        while (!closed) {
                            val n = ins.read(buf)
                            if (n < 0) break   // server ended the stream: reconnect
                            if (n > 0) append(buf, n)
                        }
                    }
                } catch (e: IOException) {
                    if (closed) break
                }
                if (closed || failure != null) break
                reconnects++
                synchronized(lock) { lock.notifyAll() }
                try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
                backoff = minOf(backoff * 2, 8000L)
            }
        } finally {
            runCatching { out?.close() }
            out = null
            call = null
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** Delete older pieces as soon as more than [maxBytes] are stored. */
    private fun trim() {
        val keep = TapLogic.keepFrom(written, maxBytes)
        if (keep <= start) return
        var i = TapLogic.chunkOf(start)
        while (i < TapLogic.chunkOf(keep)) { chunkFile(i).delete(); i++ }
        start = keep
        index.trimBefore(keep)
    }

    /**
     * Reads from [at][0] (counted on). Waits until data is there. If the position lies before the buffer (deleted),
     * it continues from the oldest available position. -1 = buffer ended.
     */
    fun read(at: LongArray, buf: ByteArray, off: Int, len: Int, raf: Array<RandomAccessFile?>, rafIdx: LongArray): Int {
        if (at[0] < start) at[0] = start
        synchronized(lock) {
            while (at[0] >= written) {
                if (closed || (failure != null && thread?.isAlive != true)) return -1
                try { lock.wait(250) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw InterruptedIOException() }
            }
        }
        if (at[0] < start) at[0] = start
        val pos = at[0]
        val idx = TapLogic.chunkOf(pos)
        if (rafIdx[0] != idx || raf[0] == null) {
            runCatching { raf[0]?.close() }
            raf[0] = try { RandomAccessFile(chunkFile(idx), "r") } catch (e: IOException) {
                // Piece just deleted: jump to the oldest position
                at[0] = start; rafIdx[0] = -1; return 0.also { if (closed) return -1 }
            }
            rafIdx[0] = idx
        }
        val inChunk = TapLogic.offsetIn(pos)
        val avail = minOf(written - pos, TapLogic.CHUNK - inChunk).toInt()
        val r = raf[0]!!
        r.seek(inChunk)
        val n = r.read(buf, off, minOf(len, avail))
        if (n <= 0) return 0
        at[0] = pos + n
        return n
    }

    /** Estimated data rate (bytes per second) of the recent time, 0 = unknown. */
    fun rate(): Long {
        val t1 = index.lastMs(); val t0 = maxOf(index.firstMs(), t1 - 60_000)
        if (t1 - t0 < 2000) return 0
        return (index.byteAt(t1) - index.byteAt(t0)) * 1000 / (t1 - t0)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { call?.cancel() }
        thread?.interrupt()
        synchronized(lock) { lock.notifyAll() }
        Thread {
            runCatching { thread?.join(3000) }
            runCatching { dir.deleteRecursively() }
        }.apply { isDaemon = true }.start()
    }
}

/** All running live buffers (one per channel; player and recording share it). */
object LiveTaps {
    private val taps = HashMap<Int, LiveTap>()
    private var next = 1
    const val UA = "IPVIOStream/1.0"

    @Synchronized fun get(id: Int): LiveTap? = taps[id]?.takeIf { !it.closed }

    /** Take the existing buffer for this address or start a new one. null = too little memory. */
    @Synchronized fun open(c: android.content.Context, url: String): LiveTap? {
        taps.values.firstOrNull { it.url == url && !it.closed && it.failure == null }?.let { return it.acquire() }
        taps.entries.removeAll { it.value.closed }
        val root = File(c.cacheDir, "livetap")
        if (taps.isEmpty()) runCatching { root.deleteRecursively() }   // leftovers after a crash
        root.mkdirs()
        val budget = TapLogic.budget(LiveSettings.bufferMb, root.usableSpace)
        if (budget <= 0) return null
        val t = LiveTap(next++, url, File(root, "t" + System.currentTimeMillis()), budget, UA)
        taps[t.id] = t
        t.acquire()
        t.begin()
        return t
    }

    @Synchronized fun all(): List<LiveTap> = taps.values.filter { !it.closed }
}
