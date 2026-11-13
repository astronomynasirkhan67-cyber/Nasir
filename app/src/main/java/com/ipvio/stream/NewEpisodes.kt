package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/**
 * Report new episodes for favorite series (bell at the top right). The state per series is remembered on the device;
 * it is checked when the playlist is loaded. Opening the series dismisses the notice.
 */
object NewEpisodes {
    val list = mutableStateListOf<NewEpisodeLogic.Notice>()
    private val known = HashMap<String, Int>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_newep")

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        (p.getString("known", "") ?: "").split("\n").forEach { l ->
            val t = l.split("\t"); if (t.size == 2) t[1].toIntOrNull()?.let { known[t[0]] = it }
        }
        (p.getString("list", "") ?: "").split("\n").forEach { l ->
            val t = l.split("\t")
            if (t.size == 5) list.add(NewEpisodeLogic.Notice(t[0], t[1], t[2].toIntOrNull() ?: 0, t[3].toIntOrNull() ?: 0, t[4].toIntOrNull() ?: 1))
        }
    }

    private fun save(c: Context) {
        prefs(c).edit()
            .putString("known", known.entries.joinToString("\n") { it.key + "\t" + it.value })
            .putString("list", list.joinToString("\n") { listOf(it.id, it.title.replace('\t', ' ').replace('\n', ' '), it.season, it.num, it.added).joinToString("\t") })
            .apply()
    }

    /** Check favorite series (after loading the playlist and when the favorites change). */
    fun check(c: Context, favSeries0: List<Item>) {
        init(c)
        // Series with a placeholder do not know their episodes yet: no comparison (otherwise false notices)
        val favSeries = favSeries0.filter { !XtreamLogic.isLazy(it) }
        if (favSeries.isEmpty()) return
        val r = NewEpisodeLogic.detect(known, favSeries.map { s -> NewEpisodeLogic.Show(s.id, s.title, s.episodes.map { it.season to it.num }) })
        val ids = favSeries.map { it.id }.toSet()
        known.putAll(r.known)
        for (n in r.notices) {
            val old = list.firstOrNull { it.id == n.id }
            if (old != null) list.remove(old)
            list.add(0, if (old != null) n.copy(added = n.added + old.added) else n)
        }
        // Remove notices for series that are no longer favorites
        list.removeAll { it.id !in ids }
        save(c)
    }

    /** Series opened: notice dismissed. */
    fun dismiss(c: Context, id: String) {
        init(c)
        if (list.removeAll { it.id == id }) save(c)
    }
}
