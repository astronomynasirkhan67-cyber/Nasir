package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ipvio.stream.UpdateCardLogic.Action
import com.ipvio.stream.UpdateCardLogic.Phase

/**
 * First-start card (T-014): checks for an app update once and shows the result at the bottom of the screen.
 * Rules live in [UpdateCardLogic]; this file only draws them.
 *
 * - Shown once: the decision is stored in the new key `first_card_seen` of `ipvio_upd`, and only on a start without a playlist.
 * - Closes by itself when a playlist is added (loading starts) or the demo mode is entered (T-025).
 * - Non-blocking: no focus request and no dialog, so the add-playlist screen keeps its focus. The buttons are normal
 *   focusable elements (OK on the remote works) that the D-pad reaches by moving down.
 * - While the card is up, the automatic start dialog of [UpdatePromptHost] is held back (`promptDismissed`), so it cannot
 *   take the focus. "Update anzeigen" opens that dialog on purpose.
 */
@Composable
fun UpdateCardHost() {
    val ctx = LocalContext.current
    val show = rememberSaveable {
        val seen = AppUpdate.firstCardSeen(ctx)
        val s = UpdateCardLogic.shouldShow(seen, PlaylistStore.savedUrl(ctx) != null)
        if (UpdateCardLogic.shouldMarkSeen(seen)) AppUpdate.markFirstCardSeen(ctx)
        s
    }
    var closed by rememberSaveable { mutableStateOf(false) }
    // T-025: once the user has moved on from the first-start screen the card closes by itself and stays closed.
    val left = UpdateCardLogic.leftFirstStart(PlaylistStore.lists.isNotEmpty(), PlaylistStore.loading, DemoMode.active)
    LaunchedEffect(left) { if (left) closed = true }
    if (!show || closed || left) return
    var state by remember { mutableStateOf(UpdateCardLogic.START) }
    var run by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf(AppUpdate.auto(ctx)) }

    LaunchedEffect(run) {
        state = UpdateCardLogic.START
        AppUpdate.promptDismissed = true
        val r = AppUpdate.check(ctx)
        val err = r.exceptionOrNull()
        if (err != null && UpdateCardLogic.ignorable(err)) return@LaunchedEffect
        state = if (err != null) UpdateCardLogic.onFailure(err) else UpdateCardLogic.onResult(r.getOrNull()?.name)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 40.dp, end = 40.dp, bottom = 28.dp)
                .widthIn(max = 880.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Tok.GlassStrong).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Auf Updates prüfen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("Dieser Hinweis erscheint nur einmal.", color = Muted, fontSize = 13.sp)
            }
            when (state.phase) {
                Phase.CHECKING -> Text("Suche nach Updates …", color = Tok.TextMid, fontSize = 17.sp)
                Phase.UP_TO_DATE -> Text(
                    Lang.tr("Du hast die neueste Version ({0}).").replace("{0}", AppUpdate.currentName(ctx)),
                    color = Accent2, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, raw = true
                )
                Phase.AVAILABLE -> Text(
                    Lang.tr("Neue Version {0} (installiert: {1})").replace("{0}", state.version).replace("{1}", AppUpdate.currentName(ctx)),
                    color = Accent2, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, raw = true
                )
                Phase.OFFLINE -> Text("Keine Verbindung. Prüfe das Netzwerk und versuche es erneut.", color = Tok.TextMid, fontSize = 17.sp)
                Phase.ERROR -> Text(state.message, color = Tok.TextMid, fontSize = 17.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ToggleRow("Automatisch nach Updates suchen", null, auto) { auto = it; AppUpdate.setAuto(ctx, it) }
                }
                when (UpdateCardLogic.primary(state.phase)) {
                    Action.SHOW_UPDATE -> PillButton("Update anzeigen", light = true) { AppUpdate.promptDismissed = false }
                    Action.RETRY -> PillButton("Erneut versuchen", light = true) { run++ }
                    Action.NONE -> {}
                }
                PillButton("Schließen") { closed = true }
            }
        }
    }
}
