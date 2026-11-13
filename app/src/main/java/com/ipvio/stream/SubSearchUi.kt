package com.ipvio.stream

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Most hits shown; the best come first ([SubSearchLogic.rank]). */
private const val MAX_HITS = 40

/**
 * "Search subtitles online" in the player (like Kodi): asks OpenSubtitles and SubDL, OK on a hit downloads it and
 * attaches it to the running stream. [key] = title (or episode) the subtitle is remembered for.
 */
@Composable
fun BoxScope.SubSearchPanel(
    q: SubSearchLogic.Query, key: String, player: Player, focus: FocusRequester, hint: String,
    onClose: () -> Unit, onAttached: (SubSearch.Sub) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(q) { mutableStateOf<SubSearch.Result?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(q) { result = SubSearch.search(q) }
    // Focus on the first row as soon as there is something to show
    LaunchedEffect(result) {
        repeat(8) {
            delay(80)
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    PSimplePanel(640.dp, "Untertitel online suchen", hint, onClose) {
        val what = if (q.tmdbId > 0) q.title.ifBlank { "TMDB " + q.tmdbId } else q.title
        Text(
            what + (if (q.isEpisode) " · S${q.season}E${q.episode}" else q.year?.let { " ($it)" } ?: ""),
            color = PT.T2, fontSize = 15.sp, maxLines = 1, raw = true, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        val r = result
        when {
            r == null -> Text("Suche …", color = Muted, fontSize = 16.sp, modifier = Modifier.focusRequester(focus).focusable().padding(16.dp))
            r.hits.isEmpty() -> Text(
                r.error ?: "Keine Untertitel gefunden", color = Muted, fontSize = 16.sp,
                modifier = Modifier.focusRequester(focus).focusable().padding(16.dp)
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                itemsIndexed(r.hits.take(MAX_HITS)) { i, h ->
                    val tag = (if (h.hi) "CC · " else "") + h.provider
                    PPanelRow(
                        SubSearchLogic.label(h), false, if (i == 0) Modifier.focusRequester(focus) else Modifier,
                        trailing = if (loading == h.provider + h.ref) Lang.tr("lädt …") else tag, raw = true
                    ) {
                        if (loading != null) return@PPanelRow
                        loading = h.provider + h.ref; error = null
                        scope.launch {
                            val (sub, err) = SubSearch.download(ctx, key, h, q.season, q.episode)
                            loading = null
                            if (sub != null && SubSearch.attach(player, sub)) onAttached(sub)
                            else error = err ?: "Untertitel konnte nicht geladen werden"
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = PT.Gold, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) }
    }
}
