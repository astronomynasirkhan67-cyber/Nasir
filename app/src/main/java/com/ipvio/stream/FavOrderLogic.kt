package com.ipvio.stream

/**
 * Manual order of favorites. The stored list ([Library.favs]) mixes live channels, movies and series; every screen shows
 * only a part of it (one section, one source). Moving an entry inside such a part must not disturb the entries that
 * are not on screen. Pure Kotlin, no Android, testable.
 */
object FavOrderLogic {
    /**
     * [all] = the whole stored list, [shown] = the IDs on screen (a subset of [all], in the order of [all]).
     * Moves [id] by [delta] places inside [shown] (negative = towards the front) and returns the new full list.
     * Entries of [all] that are not in [shown] keep their exact positions. Anything unexpected (unknown ID, no move
     * possible) returns [all] unchanged.
     */
    fun move(all: List<String>, shown: List<String>, id: String, delta: Int): List<String> {
        val base = all.distinct()
        val stored = base.toHashSet()
        val view = shown.distinct().filter { it in stored }
        val moved = CatOrderLogic.move(view, id, delta)
        if (moved == view) return all
        val inView = view.toHashSet()
        val slots = base.indices.filter { base[it] in inView }
        if (slots.size != moved.size) return all
        val out = base.toMutableList()
        slots.forEachIndexed { k, pos -> out[pos] = moved[k] }
        return out
    }

    /** Moves [id] to the first place inside [shown]. */
    fun toTop(all: List<String>, shown: List<String>, id: String): List<String> = move(all, shown, id, -shown.size)
}
