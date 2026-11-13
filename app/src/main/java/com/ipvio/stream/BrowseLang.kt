package com.ipvio.stream

import android.content.Context

/**
 * Language filter of the browse screens (the "Kategorien" filter bar), saved per profile and per content type, so movies can be
 * Spanish only while live TV keeps showing every language. Display filter only: nothing is dropped while loading.
 */
object BrowseLangPrefs {
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_browselang")

    /** Saved language code for this type (movies, series, live TV), null = all languages. */
    fun get(c: Context, kind: Kind?): String? = prefs(c).getString(kind?.name ?: "ALL", null)?.takeIf { it.isNotEmpty() }

    fun set(c: Context, kind: Kind?, code: String?) {
        prefs(c).edit().putString(kind?.name ?: "ALL", code ?: "").apply()
    }

    /** Language codes present in [items], most frequent first; "?" (no tag) last. */
    fun available(items: List<Item>): List<String> {
        val counts = HashMap<String, Int>()
        for (i in items) counts[i.lang] = (counts[i.lang] ?: 0) + 1
        return counts.entries.filter { it.key != LangTagLogic.UNKNOWN }.sortedByDescending { it.value }.map { it.key } +
            (if (counts.containsKey(LangTagLogic.UNKNOWN)) listOf(LangTagLogic.UNKNOWN) else emptyList())
    }
}
