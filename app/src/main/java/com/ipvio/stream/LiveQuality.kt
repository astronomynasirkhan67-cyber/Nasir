package com.ipvio.stream

import android.content.Context

/** Last seen picture size and frame rate per live channel (learned while watching, per profile) – appears in the channel list. */
object LiveQuality {
    private val map = HashMap<String, String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_livequal")

    fun init(c: Context) {
        if (inited) return
        inited = true
        for ((k, v) in prefs(c).all) if (v is String) map[k] = v
    }

    /** e.g. "FHD · 50 fps" or just "HD". */
    fun get(id: String): String? {
        val v = map[id] ?: return null
        val i = v.indexOf('|')
        if (i < 0) return v
        val fps = v.substring(i + 1).toIntOrNull() ?: 0
        return v.substring(0, i) + (if (fps > 0) " · $fps fps" else "")
    }

    fun put(c: Context, id: String, label: String, fps: Int) {
        init(c)
        val oldFps = map[id]?.substringAfter('|', "")?.toIntOrNull() ?: 0
        val v = label + "|" + (if (fps > 0) fps else oldFps)
        if (map[id] == v) return
        map[id] = v
        prefs(c).edit().putString(id, v).apply()
    }
}
