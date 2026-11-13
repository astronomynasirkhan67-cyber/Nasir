package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

/** State of the "Phone search" display (button at the bottom of the sidebar, card with QR code above everything). */
object RemoteUi {
    var show by mutableStateOf(false)
}

/**
 * Card with a large QR code. The server keeps running when the card is closed (the phone stays connected);
 * „Beenden“ switches it off.
 */
@Composable
fun RemoteOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val ip = remember { PhoneImport.localIp() }
    val server = remember { RemoteHub.start(ctx) }
    fun close() { RemoteUi.show = false }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xFF0F1730)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Mit dem Handy suchen", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            if (ip == null) Text("Das Gerät ist nicht mit einem WLAN verbunden.", color = Color(0xFFFFB86B), fontSize = 17.sp)
            else if (server == null) Text("Der Server konnte nicht gestartet werden.", color = Color(0xFFFFB86B), fontSize = 17.sp)
            else {
                val addr = "http://$ip:${server.port}/r?code=${server.code}"
                QrImage(addr, 280.dp)
                Text("Mit der Handy-Kamera scannen", color = Muted, fontSize = 16.sp)
                Text("http://$ip:${server.port}/r", color = Accent2, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", color = Muted, fontSize = 16.sp)
                    Text(server.code, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                Text(
                    "Suche und starte Filme, Serien und Sender am Handy. Handy und Fernseher müssen im selben WLAN sein.",
                    color = Muted, fontSize = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Schließen", Modifier.focusRequester(focus), light = true) { close() }
                if (server != null) PillButton("Beenden") { RemoteHub.stop(); close() }
            }
        }
    }
}
