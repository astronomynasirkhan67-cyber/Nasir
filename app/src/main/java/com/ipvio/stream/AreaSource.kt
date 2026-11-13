package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Which playlist is shown per area (only with several shown playlists): movies and series separately,
 * Live TV uses [LiveSource]. -1 = all playlists. Index = position in [PlaylistStore.sourceNames].
 */
object AreaSource {
    private var movies by mutableIntStateOf(-1)
    private var series by mutableIntStateOf(-1)

    /** Chosen source for the area [tab] (1 movies, 2 series, 3 Live TV); -1 = all or invalid. */
    fun get(tab: Int): Int {
        val k = when (tab) { 1 -> movies; 2 -> series; 3 -> LiveSource.k; else -> -1 }
        return if (k in PlaylistStore.sourceNames.indices && PlaylistStore.sourceNames.size > 1) k else -1
    }

    fun set(tab: Int, k: Int) {
        when (tab) { 1 -> movies = k; 2 -> series = k; 3 -> LiveSource.k = k }
    }

    /**
     * Only titles of source [k] for the area [tab] (1 movies, 2 series); other kinds stay unchanged.
     * Duplicate movies are replaced by the version of this source (like in the title list filter).
     */
    fun filter(items: List<Item>, tab: Int, k: Int): List<Item> {
        if (k < 0 || tab !in 1..2) return items
        val kind = if (tab == 1) Kind.MOVIE else Kind.SERIES
        val seen = HashSet<String>()
        return items.mapNotNull { i ->
            if (i.kind != kind) i
            else (if (PlaylistStore.inSource(i, k)) i else PlaylistStore.twin(i, k))?.takeIf { seen.add(it.id) }
        }
    }
}
