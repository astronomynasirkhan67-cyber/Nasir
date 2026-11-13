package com.ipvio.stream

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import coil.Coil

/**
 * Adaptation to the device's working memory. Many TV boxes and sticks have only 1 GB RAM:
 * there the image cache and player buffer are kept smaller so the app is not ended by the system.
 * Tiers: 0 = low (< 1.5 GB), 1 = medium (< 3 GB), 2 = high.
 */
object Mem {
    private var cachedTier = -1

    fun totalMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun tier(ctx: Context): Int {
        if (cachedTier >= 0) return cachedTier
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = totalMb(ctx)
        cachedTier = tierFor(mb, am?.isLowRamDevice == true)
        return cachedTier
    }

    fun tierFor(totalMb: Long, lowRamFlag: Boolean): Int = when {
        lowRamFlag || (totalMb in 1..1536) -> 0
        totalMb in 1..3072 -> 1
        else -> 2
    }

    /** Share of the working memory for the image cache (Coil). */
    fun imageCachePercent(tier: Int): Double = when (tier) { 0 -> 0.08; 1 -> 0.13; else -> 0.18 }

    /** Images decoded at the same time (Coil default: 4). Two on small boxes, so decoding leaves CPU cores for scrolling. */
    fun imageDecoders(tier: Int): Int = if (tier >= 2) 4 else 2

    /** Buffer sizes for the player: (min ms, max ms, start ms, rebuffer ms, byte limit). */
    fun bufferPlan(tier: Int): IntArray = when (tier) {
        // After a stall playback resumes only with a few seconds in the buffer (rebuffer ms); too little means stall, play 1 s, stall again
        0 -> intArrayOf(10_000, 25_000, 1_500, 5_000, 14 * 1024 * 1024)
        1 -> intArrayOf(20_000, 40_000, 2_000, 6_000, 28 * 1024 * 1024)
        else -> intArrayOf(30_000, 60_000, 2_500, 8_000, -1)
    }

    fun loadControl(ctx: Context): DefaultLoadControl {
        val p = BufferLogic.scale(bufferPlan(tier(ctx)), BufferLogic.level(AppSettings.bufferMode, AppSettings.bufferLearned))
        val b = DefaultLoadControl.Builder().setBufferDurationsMs(p[0], p[1], p[2], p[3])
        val bytes = BufferLogic.byteLimit(p[4], Runtime.getRuntime().maxMemory())
        if (bytes > 0) b.setTargetBufferBytes(bytes).setPrioritizeTimeOverSizeThresholds(false)
        return b.build()
    }

    /** True if the app's Java memory is used by more than [limit] (background work then stops instead of crashing). */
    fun heapTight(limit: Double = 0.75): Boolean {
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory() > r.maxMemory() * limit
    }

    /** On memory shortage: empty everything that can be reloaded. */
    fun trim(ctx: Context) {
        runCatching { Coil.imageLoader(ctx).memoryCache?.clear() }
        runCatching { MetaRepo.trim() }
        runCatching { Epg.trim(); EpgFull.trim(); CreditsRepo.trim() }
    }
}

/**
 * Remembers the last crash (only the kind and the top stack lines, no message texts with addresses),
 * so it can be shown in the diagnostics area.
 */
object CrashLog {
    private const val PREFS = "ipvio_crash"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val frames = e.stackTrace.filter { it.className.startsWith("com.ipvio") }.take(4)
                    .joinToString(" < ") { it.fileName + ":" + it.lineNumber }
                val kind = e.javaClass.simpleName + (if (e is OutOfMemoryError) " (Speicher voll)" else "")
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last", kind + (if (frames.isNotEmpty()) " · " + frames else ""))
                    .putLong("at", System.currentTimeMillis()).commit()
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): Pair<String, Long>? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("last", null) ?: return null
        return s to sp.getLong("at", 0L)
    }
}
