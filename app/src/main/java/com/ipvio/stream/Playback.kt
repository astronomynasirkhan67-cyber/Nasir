package com.ipvio.stream

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What this device can do (determined once): largest screen resolution and whether a hardware decoder handles 4K (HEVC).
 * Basis for "Quality automatic": a weak device does not get a 4K source it cannot play smoothly.
 */
object DeviceCaps {
    @Volatile private var done = false
    var displayHeight = 0
        private set
    var hevc4k = false
        private set

    /** Starts the check on a background thread: the codec query takes noticeable time on weak boxes and must not delay the first screen. */
    fun initAsync(c: Context) {
        if (done) return
        val app = c.applicationContext
        Thread({ runCatching { init(app) } }, "device-caps").start()
    }

    /** Blocking; waits for a running [initAsync]. */
    @Synchronized
    fun init(c: Context) {
        if (done) return
        displayHeight = runCatching {
            @Suppress("DEPRECATION")
            val d = (c.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val modes = d.supportedModes.map { maxOf(it.physicalHeight, it.physicalWidth * 9 / 16) }
            (modes + maxOf(d.mode.physicalHeight, d.mode.physicalWidth * 9 / 16)).maxOrNull() ?: 0
        }.getOrDefault(0)
        hevc4k = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                if (ci.isEncoder) return@any false
                val type = ci.supportedTypes.firstOrNull { it.equals("video/hevc", true) } ?: return@any false
                val hw = if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
                else !ci.name.startsWith("OMX.google.", true) && !ci.name.startsWith("c2.android.", true)
                hw && ci.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(3840, 2160) == true
            }
        }.getOrDefault(false)
        done = true
    }

    /** Short description for the settings. */
    fun summary(): String {
        val disp = when {
            displayHeight >= 2000 -> "4K-Bildschirm"
            displayHeight >= 1000 -> "Full-HD-Bildschirm"
            displayHeight > 0 -> "HD-Bildschirm"
            else -> "Bildschirm unbekannt"
        }
        return disp + " · 4K-Decoder: " + (if (hevc4k) "ja" else "nein")
    }

    /** Does the device currently have internet? (Without a network an error does not count against the link.) */
    fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork != null
    }.getOrDefault(true)
}

/** Settings for source/quality and what the app has learned about this device (applies to all profiles). */
object PlaybackPrefs {
    /** [QualityLogic.AUTO] … [QualityLogic.MAX_HD] */
    var quality by mutableIntStateOf(QualityLogic.AUTO)
        private set
    /** On stutter/error switch automatically to another (smaller) source. */
    var autoFallback by mutableStateOf(true)
        private set
    /** Learned limit: 3 = at most Full HD (4K stuttered here several times), 0 = none. */
    var learnedCap by mutableIntStateOf(0)
        private set
    private var fails4k = 0
    @Volatile private var inited = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_playback", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (inited) return
        inited = true
        DeviceCaps.initAsync(c)
        val p = prefs(c)
        quality = p.getInt("quality", QualityLogic.AUTO).coerceIn(0, 3)
        autoFallback = p.getBoolean("fallback", true)
        learnedCap = p.getInt("learned_cap", 0)
        fails4k = p.getInt("fails_4k", 0)
    }

    /** Highest rank the automatic mode currently chooses. */
    fun cap(c: Context): Int {
        init(c)
        DeviceCaps.init(c)
        return QualityLogic.cap(quality, QualityLogic.deviceMax(DeviceCaps.displayHeight, DeviceCaps.hevc4k, learnedCap))
    }

    fun setQuality(c: Context, v: Int) { quality = v.coerceIn(0, 3); prefs(c).edit().putInt("quality", quality).apply() }
    fun setFallback(c: Context, v: Boolean) { autoFallback = v; prefs(c).edit().putBoolean("fallback", v).apply() }

    /** 4K had to give way here (stutter/decoder). After two times the automatic mode plays at most Full HD. */
    fun note4kTrouble(c: Context) {
        init(c)
        fails4k++
        val e = prefs(c).edit().putInt("fails_4k", fails4k)
        if (fails4k >= 2 && learnedCap == 0) { learnedCap = 3; e.putInt("learned_cap", 3) }
        e.apply()
    }

    fun resetLearned(c: Context) {
        fails4k = 0; learnedCap = 0
        prefs(c).edit().putInt("fails_4k", 0).putInt("learned_cap", 0).apply()
    }
}

/** Remembered audio/subtitle choice per title (series, movie, channel) – the next episode starts right straight away. */
object TrackMemory {
    private const val MAX = 300
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_tracks")

    fun get(c: Context, id: String): Pair<String?, String?>? =
        TrackLogic.decode(prefs(c).getString(id, null)?.substringAfter('#'))

    fun set(c: Context, id: String, audio: String?, text: String?) {
        val p = prefs(c)
        val e = p.edit().putString(id, System.currentTimeMillis().toString() + "#" + TrackLogic.encode(audio, text))
        val all = p.all
        if (all.size > MAX) all.entries.sortedBy { (it.value as? String)?.substringBefore('#')?.toLongOrNull() ?: 0L }
            .take(all.size - MAX).forEach { e.remove(it.key) }
        e.apply()
    }
}

/**
 * Broken links: addresses that reported "no longer available" when playing or failed several times.
 * The app does NOT check thousands of addresses by itself (the provider would treat that as abuse) – it only remembers
 * what went wrong during playback. If a link plays again, it is released immediately.
 */
object DeadLinks {
    private const val MAX = 3000
    private val map = HashMap<Int, Triple<Int, Long, Boolean>>()
    @Volatile private var inited = false
    /** Changes only when toggling "hide" and when clearing – not with every new error (otherwise the whole list would be recomputed). */
    var version by mutableIntStateOf(0)
        private set
    var hide by mutableStateOf(false)
        private set
    var count by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_dead", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        hide = p.getBoolean("_hide", false)
        for ((k, v) in p.all) {
            if (k.startsWith("_")) continue
            val d = DeadLinkLogic.decode(v as? String) ?: continue
            k.toIntOrNull()?.let { map[it] = d }
        }
        count = map.values.count { DeadLinkLogic.dead(it.first, it.third) }
        version++
    }

    @Synchronized
    fun isDead(url: String): Boolean {
        if (url.isEmpty()) return false
        val d = map[url.hashCode()] ?: return false
        return DeadLinkLogic.dead(d.first, d.third)
    }

    @Synchronized
    fun fail(c: Context, url: String, permanent: Boolean) {
        init(c)
        val k = url.hashCode()
        val old = map[k]
        val n = Triple((old?.first ?: 0) + 1, System.currentTimeMillis(), permanent || old?.third == true)
        map[k] = n
        val e = prefs(c).edit().putString(k.toString(), DeadLinkLogic.encode(n.first, n.second, n.third))
        if (map.size > MAX) map.entries.sortedBy { it.value.second }.take(map.size - MAX).toList().forEach { map.remove(it.key); e.remove(it.key.toString()) }
        e.apply()
        count = map.values.count { DeadLinkLogic.dead(it.first, it.third) }
    }

    @Synchronized
    fun ok(c: Context, url: String) {
        init(c)
        if (map.remove(url.hashCode()) != null) {
            prefs(c).edit().remove(url.hashCode().toString()).apply()
            count = map.values.count { DeadLinkLogic.dead(it.first, it.third) }
        }
    }

    fun setHide(c: Context, v: Boolean) {
        init(c)
        hide = v
        prefs(c).edit().putBoolean("_hide", v).apply()
        version++
    }

    @Synchronized
    fun clear(c: Context) {
        map.clear()
        prefs(c).edit().clear().putBoolean("_hide", hide).apply()
        count = 0
        version++
    }

    /** List without broken movies and channels (series stay: there it concerns single episodes). */
    @Synchronized
    fun filter(list: List<Item>): List<Item> =
        if (map.isEmpty()) list else list.filterNot { it.kind != Kind.SERIES && isDead(it.url) }
}

/**
 * Several sources of the same movie (e.g. "Matrix" in 4K, FHD and HD as separate entries of the provider):
 * same kind, same cleaned title and same year. The language stays the same – the app never switches the language by itself.
 */
object Sources {
    fun alternatives(item: Item, all: List<Item>): List<Item> {
        if (item.kind != Kind.MOVIE) return listOf(item)
        val k = item.mk
        val same = all.filter { it.kind == Kind.MOVIE && it.lang == item.lang && it.mk == k }
        return if (same.isEmpty()) listOf(item) else (listOf(item) + same.filter { it.id != item.id })
    }

    fun asVariants(list: List<Item>): List<Variant> {
        // Make labels unique (same quality in the same folder: "HD · Movies (2)") so they can be selected
        val seen = HashMap<String, Int>()
        return list.map {
            val base = listOfNotNull(it.quality, it.group.take(18)).joinToString(" · ").ifBlank { "Quelle" }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            Variant(it.url, if (n == 1) base else "$base ($n)", it.quality, it.lang)
        }
    }
}
