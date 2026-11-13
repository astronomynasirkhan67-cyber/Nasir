package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Demo mode: lets a new user look around the UI without connecting anything ("Continue without playlist").
 *
 * - Holds in memory only; nothing is written to SharedPreferences or to files.
 * - Shows generated sample entries (no stream addresses, no artwork) instead of a playlist.
 * - Artwork is replaced by neutral blocks ([ArtImage]) and no network request is made for metadata ([MetaRepo.enabled]).
 * - Ends as soon as the user starts adding a playlist, or as soon as a real playlist exists.
 */
object DemoMode {
    private var requested by mutableStateOf(false)

    /** True while the sample content is shown. A saved playlist always wins over the demo. */
    val active: Boolean get() = requested && PlaylistStore.lists.isEmpty()

    fun enter() {
        requested = true
        PlaylistStore.showDemo(items())
    }

    fun exit() {
        if (!requested) return
        requested = false
        PlaylistStore.clearDemo()
    }

    /** Sample entries: some live channels, movies and series with neutral names. */
    fun items(): List<Item> {
        val live = (1..12).map { n ->
            Item("demo:live$n", Kind.LIVE, "Sample Channel $n", null, "", null, "Sample Live")
        }
        val movies = (1..24).map { n ->
            Item("demo:movie$n", Kind.MOVIE, "Sample Movie $n", 2000 + n, "", null, "Sample Movies")
        }
        val series = (1..12).map { n ->
            val episodes = (1..2).flatMap { s -> (1..3).map { e -> Episode(s, e, EpTitles.of(e), "") } }
            Item("demo:series$n", Kind.SERIES, "Sample Series $n", null, "", null, "Sample Series", episodes)
        }
        return live + movies + series
    }
}

/**
 * Artwork from the network, or in demo mode a plain neutral block (no image is requested at all).
 * Same parameters as the former direct [AsyncImage] calls.
 */
@Composable
fun ArtImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center
) {
    if (DemoMode.active) Box(modifier.background(Surf2))
    else AsyncImage(model, contentDescription, modifier, contentScale = contentScale, alignment = alignment)
}
