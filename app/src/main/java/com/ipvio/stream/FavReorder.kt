package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Focus follows a favorite that was just moved: after the move the entry sits in another slot, and the focus would
 * stay on the old slot (now another entry). Only valid for a short time so a stale request can never steal the focus later.
 */
object FavFocus {
    var id: String? by mutableStateOf(null)
        private set
    private var at = 0L

    fun follow(itemId: String) { at = System.currentTimeMillis(); id = itemId }

    fun isFresh() = System.currentTimeMillis() - at < FRESH_MS

    fun consume(itemId: String) { if (id == itemId) id = null }

    private const val FRESH_MS = 2000L
}

/** Gives the focus to the favorite that was just moved (see [FavFocus]). Reads no state while composing. */
@Composable
fun Modifier.followFav(itemId: String): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(itemId) {
        snapshotFlow { FavFocus.id }.collect { target ->
            if (target == itemId && FavFocus.isFresh()) {
                // wait until the menu is closed and the new order is laid out
                delay(200)
                runCatching { fr.requestFocus() }
                FavFocus.consume(itemId)
            }
        }
    }
    return this.focusRequester(fr)
}

/**
 * Move entries of [item] inside [shown] (the favorites currently on screen, in display order). Only the moves that are
 * possible are offered. Labels reuse the existing category-sorting texts.
 */
fun favMoveActions(ctx: Context, item: Item, shown: List<Item>): List<Triple<String, AIcon?, () -> Unit>> {
    val ids = shown.map { it.id }
    val i = ids.indexOf(item.id)
    if (i < 0) return emptyList()
    val out = ArrayList<Triple<String, AIcon?, () -> Unit>>()
    if (i > 0) out.add(Triple("Nach oben verschieben", AIcon.Up) { FavFocus.follow(item.id); Library.moveFav(ctx, ids, item.id, -1) })
    if (i < ids.lastIndex) out.add(Triple("Nach unten verschieben", AIcon.Down) { FavFocus.follow(item.id); Library.moveFav(ctx, ids, item.id, 1) })
    if (i > 1) out.add(Triple("An den Anfang verschieben", AIcon.Up) { FavFocus.follow(item.id); Library.favToTop(ctx, ids, item.id) })
    return out
}

/**
 * Menu of a favorite in the Favorites tab (menu key or long OK): move it, or remove it from the favorites.
 * [shown] = the list this entry is part of (live channels, movies or series).
 */
@Composable
fun FavReorderMenu(item: Item, shown: List<Item>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val acts = ArrayList<Triple<String, AIcon?, () -> Unit>>()
    acts.addAll(favMoveActions(ctx, item, shown))
    acts.add(Triple("Aus Favoriten entfernen", AIcon.Star) { Library.toggleFav(ctx, item.id) })
    OptionPopup(acts.map { it.first }, -1, onClose, acts.map { it.second }) { i -> onClose(); acts[i].third() }
}
