package com.ipvio.stream

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

object ContentFocus {
    val req = FocusRequester()
    var tab = -1
}

/**
 * Last focused element in the content (cards, buttons). Right from the sidebar goes back there
 * instead of to the element that happens to lie next to the chosen menu entry (round 14).
 */
object ContentMemory {
    var last: FocusRequester? = null
    fun restore(): Boolean = last?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
}

/** Remembers this element as last focused (see [ContentMemory]). */
@Composable
fun Modifier.trackFocus(): Modifier {
    val fr = remember { FocusRequester() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (ContentMemory.last === fr) ContentMemory.last = null } }
    return this.focusRequester(fr).onFocusChanged { if (it.isFocused) ContentMemory.last = fr }
}

/** Like [trackFocus], but uses the given requester so the element has only ONE focusRequester (two on one node break requestFocus). */
@Composable
fun Modifier.trackFocus(fr: FocusRequester): Modifier {
    androidx.compose.runtime.DisposableEffect(fr) { onDispose { if (ContentMemory.last === fr) ContentMemory.last = null } }
    return this.focusRequester(fr).onFocusChanged { if (it.isFocused) ContentMemory.last = fr }
}

object LastFocus {
    var pending: String? = null
}

/**
 * Episode of a series last played in the player. On coming back the detail page opens exactly at this season/episode and
 * highlights it so you can pick another right away (instead of landing on "Resume" again). Read once and deleted.
 */
object DetailReturn {
    private var id: String? = null
    private var season = 0
    private var ep = 0
    fun set(itemId: String, s: Int, e: Int) { if (s > 0 || e > 0) { id = itemId; season = s; ep = e } }
    fun take(itemId: String): Pair<Int, Int>? = if (id == itemId) { id = null; season to ep } else null
}

object HomeFlags {
    var heroDone = false
}

object NavState {
    var tab = 0
}

/**
 * App-wide navigation not bound to a screen:
 * - [homeSignal]: counts up when the app is opened again after the Home key (or app switch) → back to the home screen.
 * - [focusTab]: tab whose content should get the focus after a switch when the whole screen was replaced
 *   (e.g. Start → Search). The old screen's sidebar is already gone and can no longer do that.
 */
object AppNav {
    var homeSignal by androidx.compose.runtime.mutableIntStateOf(0)
    /** The View settings ask the movie area to open its categories screen (counts up). */
    var openCatsSignal by androidx.compose.runtime.mutableIntStateOf(0)
    /** Same for the "All movies" grid (consumed by the movie area). */
    var openBrowseSignal by androidx.compose.runtime.mutableIntStateOf(0)
    /** View settings preview: scroll the home screen to the first of these block keys ("a|b"); "" = nothing, "top" = to the top. */
    var homeScrollKey by androidx.compose.runtime.mutableStateOf("")
    /** Player open? Then the background refresh postpones reloading the playlist (no stutter in the middle of a movie). */
    @Volatile var playing = false
    /** Channel to be opened (tapping a reminder notification). */
    var openChannel by androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var leftByUser = false
    @Volatile var focusTab = -1
    /** Open the TV guide at the next start of the player (Live TV > "TV guide"). */
    @Volatile var openGuide = false
}

object RowStates {
    private val m = HashMap<String, LazyListState>()
    fun get(k: String): LazyListState = m.getOrPut(k) { LazyListState() }
}

@Composable
fun Modifier.restoreFocus(id: String): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (LastFocus.pending == id) {
            delay(150)
            runCatching { fr.requestFocus() }
            LastFocus.pending = null
        }
    }
    return this.focusRequester(fr)
}
