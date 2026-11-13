package com.ipvio.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long OK must be held to count as a long press. Own timer instead of the system long press
 * (`KeyEvent.isLongPress`), which comes only with the first key repeat (about 500 ms, longer with some
 * accessibility settings) and felt too slow on Fire TV remotes.
 */
const val LONG_OK_MS = 350L

/**
 * Long-OK detection shared by [favKeys] and [menuKeys]. Returns true when the event was consumed.
 * The action runs on release: if it opens a menu, that menu would otherwise receive the release as a click on its first entry.
 */
private class LongOk {
    var done by mutableStateOf(false)
    var timer: Job? = null
}

@Composable
private fun Modifier.longOkKeys(onMenu: () -> Unit, onLong: () -> Unit): Modifier {
    val st = remember { LongOk() }
    val scope = rememberCoroutineScope()
    return this.onPreviewKeyEvent { e ->
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onMenu(); true }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyDown -> {
                if (e.nativeKeyEvent.repeatCount == 0) {
                    // New press: also clears a state left over when the release went to another element
                    st.done = false; st.timer?.cancel()
                    st.timer = scope.launch { delay(LONG_OK_MS); st.done = true }
                    false   // a short press stays a normal click
                } else if (st.done || e.nativeKeyEvent.isLongPress) { st.done = true; true } else false
            }
            e.key == Key.DirectionCenter && e.type == KeyEventType.KeyUp -> {
                st.timer?.cancel(); st.timer = null
                if (st.done) { st.done = false; onLong(); true } else false
            }
            else -> false
        }
    }
}

/** Menu key or a long OK runs [onFav] (on release). */
@Composable
fun Modifier.favKeys(onFav: () -> Unit): Modifier = longOkKeys(onFav, onFav)

/**
 * Menu key opens a menu ([onMenu]), OK held long does [onLong] (on release).
 * Many remotes (Fire TV) have no menu key: then a long OK opens the same menu.
 */
@Composable
fun Modifier.menuKeys(onMenu: () -> Unit, onLong: () -> Unit): Modifier = longOkKeys(onMenu, onLong)
