package com.ipvio.stream

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.io.RandomAccessFile

/** Settings for Live TV: rewind (buffer) and recordings. Applies to all profiles. */
object LiveSettings {
    private const val PREFS = "ipvio_livetv"
    val BUFFER_MB = listOf(256, 512, 1024, 2048)

    /** Always play live through the buffer (rewind, pause without loss). Off = only during a recording. */
    var timeshift by mutableStateOf(false)
        private set
    var bufferMb by mutableIntStateOf(512)
        private set
    /** Storage location of the recordings: index into [Recorder.dirs]. */
    var recDir by mutableIntStateOf(0)
        private set
    /** Minutes of overrun after the end of the show according to the TV guide. */
    var recPad by mutableIntStateOf(3)
        private set
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        timeshift = p.getBoolean("ts", false)
        bufferMb = p.getInt("mb", 512).takeIf { it in BUFFER_MB } ?: 512
        recDir = p.getInt("dir", 0).coerceAtLeast(0)
        recPad = p.getInt("pad", 3).coerceIn(0, 30)
    }

    private fun save(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("ts", timeshift).putInt("mb", bufferMb).putInt("dir", recDir).putInt("pad", recPad).apply()

    fun setTimeshift(c: Context, v: Boolean) { timeshift = v; save(c) }
    fun setBufferMb(c: Context, v: Int) { bufferMb = v; save(c) }
    fun setRecDir(c: Context, v: Int) { recDir = v; save(c) }
    fun setRecPad(c: Context, v: Int) { recPad = v; save(c) }
}

/**
 * Reads from a [LiveTap] ("tap://ID/START.ts"). Media3 waits on reading as with a slow connection;
 * START is the byte position in the buffer from which playback starts (rewinding = reopen at another position).
 */
class TapDataSource : BaseDataSource(true) {
    private var tap: LiveTap? = null
    private var uri: Uri? = null
    private val at = LongArray(1)
    private val raf = arrayOfNulls<RandomAccessFile>(1)
    private val rafIdx = longArrayOf(-1)
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        val id = u.host?.toIntOrNull() ?: throw IOException("Puffer-Adresse ungültig")
        val t = LiveTaps.get(id) ?: throw IOException("Puffer beendet")
        transferInitializing(dataSpec)
        val base = u.lastPathSegment?.substringBefore('.')?.toLongOrNull() ?: 0L
        at[0] = maxOf(base + dataSpec.position, t.start)
        tap = t
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val t = tap ?: return C.RESULT_END_OF_INPUT
        var n: Int
        do { n = t.read(at, buffer, offset, length, raf, rafIdx) } while (n == 0)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf[0]?.close() }
        raf[0] = null; rafIdx[0] = -1
        tap = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Routes "tap://" to the buffer, everything else to the normal source (HTTP, file, …). */
class TapAwareDataSource(private val normal: DataSource) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var cur: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme == "tap") TapDataSource().also { s -> listeners.forEach { s.addTransferListener(it) } } else normal
        cur = d
        if (dataSpec.uri.scheme != "https") return d.open(dataSpec)
        // Provider certificate does not match its address: use the same address over http (once per host, remembered)
        val url = dataSpec.uri.toString()
        if (TlsFallback.isBrokenUrl(url)) TlsFallback.downgradeUrl(url)?.let { return d.open(dataSpec.withUri(Uri.parse(it))) }
        try {
            return d.open(dataSpec)
        } catch (e: IOException) {
            val low = if (TlsFallback.isHostMismatch(e)) TlsFallback.downgradeUrl(url) else null
            if (low == null) throw e
            dataSpec.uri.host?.let { TlsFallback.markBroken(it) }
            Diag.report("Zertifikat passt nicht zur Adresse – Wiedergabe ohne Verschlüsselung")
            runCatching { d.close() }
            return d.open(dataSpec.withUri(Uri.parse(low)))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = cur?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = cur?.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> = cur?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        val d = cur
        cur = null
        d?.close()
    }

    class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = TapAwareDataSource(inner.createDataSource())
    }
}

/**
 * Rewinding in Live TV through the buffer. Holds the buffer of the channel currently playing; the player plays
 * "tap://ID/START.ts". The time is calculated here: [baseMs] = buffer time at the beginning of the piece just opened.
 */
object LiveTimeshift {
    /** Buffer of the player (null = live runs directly from the server). */
    var tap: LiveTap? = null
        private set
    private var baseMs = 0L
    /** Changes with every start/stop (UI). */
    var version by mutableIntStateOf(0)
        private set

    /** Buffer active? (reads [version] so the UI redraws on start/stop) */
    val active: Boolean get() = version >= 0 && tap?.let { !it.closed } == true

    /** Should this address run through the buffer? */
    fun wanted(url: String, forRecording: Boolean = false): Boolean = (LiveSettings.timeshift || forRecording) && TapLogic.tappable(url)

    /**
     * Start a channel through the buffer. If a buffer already runs for this address (e.g. a recording), it is shared and
     * joined shortly before the live picture. false = buffer not possible (too little memory) → play directly.
     */
    fun start(c: Context, player: ExoPlayer, url: String): Boolean {
        val old = tap
        if (old != null && old.url == url && !old.closed) { goLive(player); return true }
        val t = LiveTaps.open(c, url) ?: return false
        tap = t
        old?.release()
        version++
        goLive(player)
        return true
    }

    /** Release the buffer (another channel without buffer, player closed). A running recording keeps it. */
    fun stop() {
        val t = tap ?: return
        tap = null
        t.release()
        version++
    }

    private fun openAt(player: ExoPlayer, t: LiveTap, byte: Long): Boolean {
        baseMs = t.index.msAt(byte)
        val ok = openStream(player, "tap://${t.id}/$byte.ts", 0L)
        if (!ok) stop()
        return ok
    }

    /** To the live picture (about 2 s behind so the player has some reserve). */
    fun goLive(player: ExoPlayer) {
        val t = tap ?: return
        val target = (t.nowMs() - 2000).coerceAtLeast(t.oldestMs())
        val b = if (t.index.size() == 0) t.start else t.index.byteAt(target)
        openAt(player, t, b)
    }

    /** Current position in buffer time. */
    fun posMs(player: ExoPlayer): Long = baseMs + player.currentPosition.coerceAtLeast(0)

    /** Distance to the live picture in ms. */
    fun behindMs(player: ExoPlayer): Long {
        val t = tap ?: return 0
        return (t.nowMs() - posMs(player)).coerceAtLeast(0)
    }

    /** How far back the buffer reaches (ms). */
    fun spanMs(): Long { val t = tap ?: return 0; return (t.nowMs() - t.oldestMs()).coerceAtLeast(0) }

    /** Jump by [deltaMs] (negative = back). Not beyond the live picture, not before the buffer. */
    fun seekBy(player: ExoPlayer, deltaMs: Long) {
        val t = tap ?: return
        val now = t.nowMs()
        val target = (posMs(player) + deltaMs).coerceIn(t.oldestMs(), now - 1500)
        if (now - target < 3000) { goLive(player); return }
        val wasPlaying = player.playWhenReady
        openAt(player, t, t.index.byteAt(target))
        player.playWhenReady = wasPlaying || deltaMs != 0L
    }

    /** Reconnect: with buffer to the live picture, without buffer as before. */
    fun reconnect(player: ExoPlayer) {
        val t = tap
        when {
            t != null && !t.closed && t.failure == null -> { t.kick(); goLive(player) }
            // Buffer failed (e.g. login rejected): continue directly from the server, without buffer
            t != null -> { val u = t.url; stop(); openStream(player, u, 0L) }
            else -> { player.seekToDefaultPosition(); player.prepare() }
        }
    }
}

/** Playable address from a file (recording). */
fun fileUrl(f: java.io.File): String = Uri.fromFile(f).toString()
