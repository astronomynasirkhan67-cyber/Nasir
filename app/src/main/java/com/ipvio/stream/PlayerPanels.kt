package com.ipvio.stream

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/** Simple selection list for menus (speed, aspect ratio, quality ...). */
@Composable
fun PChoiceList(options: List<String>, selected: Int, focus: FocusRequester, onPick: (Int) -> Unit) {
    val st = rememberLazyListState(maxOf(selected - 3, 0))
    LazyColumn(state = st, verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
        itemsIndexed(options) { i, o ->
            PPanelRow(o, i == selected, if (i == maxOf(selected, 0)) Modifier.focusRequester(focus) else Modifier) { onPick(i) }
        }
    }
}

/**
 * Audio & subtitles in one menu with two columns.
 * Right key in the audio column switches to the subtitles, Left goes back or closes the menu.
 */
@Composable
fun BoxScope.TracksPanel(
    player: Player, ver: Int, onChanged: () -> Unit,
    audioFocus: FocusRequester, subsFocus: FocusRequester,
    onClose: () -> Unit, onStyle: () -> Unit, onSearch: (() -> Unit)? = null
) {
    val audio = remember(ver) { collectTracks(player, C.TRACK_TYPE_AUDIO) }
    val subs = remember(ver) { collectTracks(player, C.TRACK_TYPE_TEXT) }
    val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val subsSel = if (subsOff) 0 else (subs.indexOfFirst { it.selected } + 1).coerceAtLeast(0)
    val audioState = rememberLazyListState(0)
    val subsState = rememberLazyListState(maxOf(subsSel - 3, 0))

    // Focus always starts at the first item of the audio column; if there is no audio choice, at the first item of the subtitles
    LaunchedEffect(Unit) {
        delay(200)
        repeat(8) {
            if (runCatching { (if (audio.isNotEmpty()) audioFocus else subsFocus).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(70)
        }
    }

    PPanelFrame(680.dp, "Audio & Untertitel", "Links schließt das Menü  ·  Rechts wechselt zu den Untertiteln", onEdgeDown = onClose) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionLeft -> { onClose(); true }
                        Key.DirectionRight -> { runCatching { subsFocus.requestFocus() }; true }
                        else -> false
                    }
                }
            ) {
                PColumnHeader("Audio")
                if (audio.isEmpty()) {
                    Text(
                        "Keine Auswahl verfügbar", color = Muted, fontSize = 15.sp,
                        modifier = Modifier.focusRequester(audioFocus).focusable().padding(16.dp)
                    )
                } else {
                    LazyColumn(state = audioState, verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        itemsIndexed(audio) { i, t ->
                            PPanelRow(t.label, t.selected, if (i == 0) Modifier.focusRequester(audioFocus) else Modifier) {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                                onChanged()
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else when (e.key) {
                        Key.DirectionLeft -> { runCatching { audioFocus.requestFocus() }; true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                }
            ) {
                PColumnHeader("Untertitel")
                LazyColumn(state = subsState, verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                    item {
                        PPanelRow("Aus", subsOff, if (subsSel == 0) Modifier.focusRequester(subsFocus) else Modifier) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                            onChanged()
                        }
                    }
                    itemsIndexed(subs) { i, t ->
                        PPanelRow(t.label, t.selected && !subsOff, if (subsSel == i + 1) Modifier.focusRequester(subsFocus) else Modifier) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                            onChanged()
                        }
                    }
                    if (subs.isEmpty()) {
                        item {
                            Text(
                                "Dieser Stream enthält keine Untertitel.", color = Muted, fontSize = 14.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (onSearch != null) {
            PPanelRow(
                "Untertitel online suchen", false,
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onClose(); true }
                    else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onSearch(); true }
                    else false
                },
                chevron = true, tinted = true, onClick = onSearch
            )
            Spacer(Modifier.height(PT.RowGap))
        }
        PPanelRow(
            "Aussehen der Untertitel", false,
            Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onClose(); true }
                else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onStyle(); true }
                else false
            },
            chevron = true, tinted = true, onClick = onStyle
        )
    }
}

private class EpRow(val header: String?, val ep: Episode?)

/**
 * Episode list of the series. The running episode is highlighted and focused on opening.
 * Long seasons (One Piece & Co.) are split into blocks of 50 episodes; Right / fast-forward jumps 50 further, rewind back.
 */
@Composable
fun BoxScope.EpisodesPanel(
    episodes: List<Episode>, curSeason: Int, curNum: Int,
    focus: FocusRequester, onClose: () -> Unit, onPick: (Episode) -> Unit
) {
    val rows = remember(episodes) {
        val out = ArrayList<EpRow>()
        val bySeason = episodes.groupBy { it.season }
        var s = -1
        var k = 0
        for (e in episodes) {
            if (e.season != s) { s = e.season; k = 0; out.add(EpRow("Staffel $s", null)) }
            val list = bySeason.getValue(s)
            if (list.size > EpisodeLogic.LONG && k % EpisodeLogic.BLOCK == 0) {
                out.add(EpRow("Folgen ${e.num}–${list[minOf(k + EpisodeLogic.BLOCK - 1, list.lastIndex)].num}", null))
            }
            out.add(EpRow(null, e))
            k++
        }
        out
    }
    val long = episodes.size > EpisodeLogic.LONG
    // first episode after each heading = jump targets
    val starts = remember(rows) { rows.indices.filter { rows[it].ep != null && (it == 0 || rows[it - 1].ep == null) } }
    val curIdx = rows.indexOfFirst { r -> r.ep != null && r.ep.season == curSeason && r.ep.num == curNum }
    val focusIdx = if (curIdx >= 0) curIdx else rows.indexOfFirst { it.ep != null }
    val st = rememberLazyListState(maxOf(focusIdx - 2, 0))
    val reqs = remember(rows) { HashMap<Int, FocusRequester>() }
    val scope = rememberCoroutineScope()
    fun jumpTo(target: Int) {
        scope.launch {
            st.scrollToItem((target - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { reqs.getOrPut(target) { FocusRequester() }.requestFocus() }
        }
    }
    PSimplePanel(520.dp, "Folgen", if (long) "Rechts / Vorspulen = 50 weiter · Zurückspulen = zurück · Links schließt" else "Links schließt das Menü", onClose) {
        LazyColumn(state = st, verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
            itemsIndexed(rows) { i, r ->
                val ep = r.ep
                if (ep == null) PColumnHeader(r.header ?: "")
                else {
                    val req = if (i == focusIdx) focus else reqs.getOrPut(i) { FocusRequester() }
                    if (i == focusIdx) reqs[i] = focus
                    PPanelRow(
                        "Folge ${ep.num}", i == curIdx,
                        Modifier.focusRequester(req).then(if (!long) Modifier else Modifier.onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                Key.DirectionRight, Key.MediaFastForward, Key.ChannelDown, Key.PageDown -> {
                                    starts.firstOrNull { it > i }?.let { jumpTo(it) }; true
                                }
                                Key.MediaRewind, Key.ChannelUp, Key.PageUp -> {
                                    val here = starts.lastOrNull { it <= i } ?: i
                                    (if (here < i) here else starts.lastOrNull { it < i })?.let { jumpTo(it) }; true
                                }
                                else -> false
                            }
                        }),
                        trailing = if (i == curIdx) "läuft" else null
                    ) { onPick(ep) }
                }
            }
        }
    }
}
