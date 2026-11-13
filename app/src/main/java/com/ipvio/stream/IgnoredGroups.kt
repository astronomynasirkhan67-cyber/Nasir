package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * "Ignore": titles and channels you do not need disappear everywhere (home, movies, series, Live TV, search),
 * without reloading the playlist. Grouping is by the provider's tag – "TR |", "[EN]", "DE:" – first from the
 * category name, otherwise from the title. This complements the language filter for cases where its detection is not enough.
 * Keys: bundle = "t:KIND|TAG" (KIND = LIVE, MOVIE, SERIES), single category = "g:KIND|CATEGORY".
 */
object IgnoredGroups {
    private const val PREFS = "ipvio_ignored"
    private var keys: Set<String> = emptySet()
    /** Changes with every switch (trigger to rebuild the shown list). */
    var version by mutableIntStateOf(0)
        private set

    val isEmpty: Boolean get() = keys.isEmpty()
    val size: Int get() = keys.size
    fun has(key: String) = key in keys

    fun init(c: Context) {
        val s = (Profiles.sp(c, PREFS).getString("k", "") ?: "").split("\n").filter { it.isNotBlank() }.toSet()
        if (s != keys) { keys = s; version++ }
    }

    private fun save(c: Context, s: Set<String>) {
        keys = s
        Profiles.sp(c, PREFS).edit().putString("k", s.joinToString("\n")).apply()
        version++
    }

    fun set(c: Context, key: String, ignored: Boolean) = save(c, if (ignored) keys + key else keys - key)
    fun setMany(c: Context, add: Collection<String>, ignored: Boolean) = save(c, if (ignored) keys + add else keys - add.toSet())
    fun clear(c: Context) = save(c, emptySet())

    private val START = Regex("""^\W*([A-Za-z]{2,5})\s*(?:[|:\]\)]|[-–]\s)""")
    private val END = Regex("""[\[(|]\s*([A-Za-z]{2,4})\s*[\])|]?\s*$""")

    /** Tag like "TR" or "EN" from a text (start, otherwise end) or null. */
    fun tagIn(s: String): String? =
        START.find(s)?.groupValues?.get(1)?.uppercase() ?: END.find(s)?.groupValues?.get(1)?.uppercase()

    /** Tag of an entry: category first, otherwise title. Empty = no tag. */
    fun tagOf(i: Item): String = tagIn(i.group) ?: tagIn(i.title) ?: ""

    fun bundleKey(kind: Kind, tag: String) = "t:" + kind.name + "|" + tag
    fun groupKey(kind: Kind, group: String) = "g:" + kind.name + "|" + group

    fun isIgnored(i: Item): Boolean {
        if (keys.isEmpty()) return false
        if (("g:" + i.kind.name + "|" + i.group) in keys) return true
        val t = tagOf(i)
        return t.isNotEmpty() && ("t:" + i.kind.name + "|" + t) in keys
    }

    fun filter(l: List<Item>): List<Item> = if (keys.isEmpty()) l else l.filter { !isIgnored(it) }
}
