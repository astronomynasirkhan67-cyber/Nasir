package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown when the app is started and a newer version is available: one button downloads and installs it,
 * so nobody has to look for the file on GitHub first. "Später" hides it until the next app start.
 * It lies above every screen, also the "add playlist" screen – exactly where someone with a playlist problem is stuck.
 */
@Composable
fun UpdatePromptHost() {
    val ctx = LocalContext.current
    val upd = AppUpdate.available
    if (upd == null || AppUpdate.promptDismissed) return
    val scope = rememberCoroutineScope()
    var status by remember(upd) { mutableStateOf("") }
    var busy by remember(upd) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(upd) { delay(150); runCatching { focus.requestFocus() } }
    Dialog(
        onDismissRequest = { if (!busy) AppUpdate.promptDismissed = true },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Tok.GlassStrong).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Update verfügbar", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    Lang.tr("Neue Version {0} (installiert: {1})").replace("{0}", upd.name).replace("{1}", AppUpdate.currentName(ctx)),
                    color = Tok.TextMid, fontSize = 18.sp, raw = true
                )
                if (upd.notes.isNotBlank()) Text(upd.notes, color = Muted, fontSize = 16.sp, lineHeight = 22.sp, raw = true)
                // T-015: update.json can mark an update as required. Back still closes the dialog (no lock-out when offline).
                val required = AppUpdate.isRequired(ctx, upd)
                if (required) Text("Dieses Update ist erforderlich.", color = Accent2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Deine Playlists und Einstellungen bleiben erhalten. Android fragt vor der Installation einmal nach.", color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
                if (status.isNotBlank()) Text(status, color = Accent2, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(if (busy) "Bitte warten …" else "Jetzt aktualisieren", light = true, modifier = Modifier.focusRequester(focus)) {
                        if (busy) return@PillButton
                        busy = true
                        scope.launch {
                            AppUpdate.downloadAndInstall(ctx, upd) { status = it }
                            busy = false
                        }
                    }
                    if (!required) PillButton("Später") { if (!busy) AppUpdate.promptDismissed = true }
                }
            }
        }
    }
}
