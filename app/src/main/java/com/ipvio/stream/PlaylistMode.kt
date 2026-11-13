package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Content mode per playlist (Wishbox): Live TV + VOD, Live TV only, or VOD only. Saved per playlist address.
 * Applied when the shown list is built (PlaylistStore.rebuild), so a change takes effect at once without a new download.
 * Fewer titles in the shown list also means less work for search, home screen and genre detection.
 */
object PlaylistMode {
    const val ALL = 0
    const val LIVE_ONLY = 1
    const val VOD_ONLY = 2

    /** Labels in the order of the constants above. */
    val labels = listOf("Live TV + Filme & Serien", "Nur Live TV", "Nur Filme & Serien")

    /** Raised on every change (screens that show the mode redraw). */
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_plmode", Context.MODE_PRIVATE)
    private fun key(url: String) = "m_" + (PlaylistLogic.normalizeUrl(url) ?: url.trim()).hashCode()

    fun get(c: Context, url: String): Int = prefs(c).getInt(key(url), ALL).coerceIn(ALL, VOD_ONLY)

    fun set(c: Context, url: String, mode: Int) {
        prefs(c).edit().putInt(key(url), mode.coerceIn(ALL, VOD_ONLY)).apply()
        version++
    }

    /** Hidden languages of a playlist plus its content mode as pseudo codes (used while loading, see LangTagLogic.hides). */
    fun withMode(c: Context, url: String, hidden: Set<String>): Set<String> = when (get(c, url)) {
        LIVE_ONLY -> hidden + LangTagLogic.NO_VOD
        VOD_ONLY -> hidden + LangTagLogic.NO_LIVE
        else -> hidden
    }

    /** The items of one playlist as its mode allows (same list object when nothing is removed). */
    fun filter(c: Context, url: String, items: List<Item>): List<Item> = when (get(c, url)) {
        LIVE_ONLY -> items.filter { it.kind == Kind.LIVE }
        VOD_ONLY -> items.filter { it.kind != Kind.LIVE }
        else -> items
    }
}
