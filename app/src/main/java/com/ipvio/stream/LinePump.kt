package com.ipvio.stream

import java.io.BufferedReader
import java.io.Reader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads the lines of a playlist on a helper thread, so that reading, decompressing, decoding and the compressed copy
 * (see `TeeReader` in PlaylistStore) overlap with parsing instead of taking turns. On large lists this is a good third
 * of the load time on devices with several cores.
 *
 * Use it with `use { }`: [close] stops the helper and waits for it, so nothing is still reading when the caller closes
 * the underlying streams afterwards. Errors of the helper (broken file, out of memory) are rethrown in the reading thread.
 */
class LinePump(private val reader: Reader, private val batchSize: Int = 1024, queueSize: Int = 8) : java.io.Closeable {
    private class Fail(val error: Throwable)

    private val queue = ArrayBlockingQueue<Any>(queueSize)
    private val done = Any()
    @Volatile private var stop = false
    private val worker = Thread({ pump() }, "playlist-reader").apply { isDaemon = true; start() }

    private fun pump() {
        try {
            val br = BufferedReader(reader, 1 shl 16)
            var batch = ArrayList<String>(batchSize)
            while (!stop) {
                val line = br.readLine() ?: break
                batch.add(line)
                if (batch.size >= batchSize) {
                    if (!put(batch)) return
                    batch = ArrayList(batchSize)
                }
            }
            if (stop) return
            if (batch.isNotEmpty() && !put(batch)) return
            put(done)
        } catch (e: Throwable) {
            put(Fail(e))
        }
    }

    /** Waits for room in the queue; false = the consumer is gone, give up. */
    private fun put(x: Any): Boolean {
        while (!stop) if (queue.offer(x, 50, TimeUnit.MILLISECONDS)) return true
        return false
    }

    /** The lines in order. Can be read once. */
    fun lines(): Sequence<String> = sequence {
        while (true) {
            val x = queue.take()
            if (x === done) return@sequence
            if (x is Fail) throw x.error
            @Suppress("UNCHECKED_CAST")
            for (l in x as List<String>) yield(l)
        }
    }

    override fun close() {
        stop = true
        worker.join()
        runCatching { reader.close() }
    }
}
