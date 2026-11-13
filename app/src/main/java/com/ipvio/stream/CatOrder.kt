package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * Sorting of provider categories per area ([LIVE], [MOVIE], [SERIES]) and per profile, see [CatOrderLogic].
 * Store "ipvio_catorder": "m_AREA" = mode, "o_AREA" = own order (one name per line).
 */
object CatOrder {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"

    private val modes = mutableStateMapOf<String, Int>()
    private val own = mutableStateMapOf<String, List<String>>()
    private var inited = false
    /** Changes with every new mode or order: key for remembered, sorted lists. */
    var version by mutableIntStateOf(0)
        private set
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_catorder")

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        for (a in listOf(LIVE, MOVIE, SERIES)) {
            modes[a] = p.getInt("m_$a", CatOrderLogic.PROVIDER).coerceIn(0, 2)
            own[a] = CatOrderLogic.decode(p.getString("o_$a", ""))
        }
    }

    fun mode(area: String): Int = modes[area] ?: CatOrderLogic.PROVIDER

    /** [names] in provider order → order to show. */
    fun sorted(area: String, names: List<String>): List<String> = CatOrderLogic.apply(names, mode(area), own[area].orEmpty())

    fun setMode(c: Context, area: String, m: Int) {
        init(c)
        modes[area] = m.coerceIn(0, 2)
        prefs(c).edit().putInt("m_$area", modes[area] ?: 0).apply()
        version++
    }

    /** Moves [name] by [delta] places within [names] (provider order); switches to the own order. */
    fun move(c: Context, area: String, names: List<String>, name: String, delta: Int) =
        save(c, area, CatOrderLogic.move(sorted(area, names), name, delta))

    fun toTop(c: Context, area: String, names: List<String>, name: String) =
        save(c, area, CatOrderLogic.toTop(sorted(area, names), name))

    private fun save(c: Context, area: String, order: List<String>) {
        init(c)
        // Keep places of categories that are not in the current list (e.g. other playlist shown at the moment)
        val inOrder = order.toHashSet()
        val keep = own[area].orEmpty().filter { it !in inOrder }
        own[area] = order + keep
        modes[area] = CatOrderLogic.OWN
        prefs(c).edit().putInt("m_$area", CatOrderLogic.OWN).putString("o_$area", CatOrderLogic.encode(own[area].orEmpty())).apply()
        version++
    }
}
