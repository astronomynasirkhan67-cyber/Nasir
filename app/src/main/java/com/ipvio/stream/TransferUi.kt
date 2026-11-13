package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Storage: transfer to another device (round 20).
 * Wi-Fi: small page for the phone (download/upload backup, crash report). Google Drive: optional.
 */
@Composable
internal fun TransferSection(ctx: Context) {
    SectionTitle("Auf ein anderes Gerät übertragen")
    Hint("Verlauf, Weiterschauen, Favoriten, Listen und Einstellungen mitnehmen – z. B. vom Fire TV auf das Handy. Auf Wunsch auch die Playlists mit Zugangsdaten.")
    var serving by remember { mutableStateOf(false) }
    val ip = remember { PhoneImport.localIp() }
    val server = remember {
        PhoneImport { }.apply {
            backup = { pl -> Backup.exportText(ctx, pl) }
            restore = { t ->
                val ok = Backup.restoreText(ctx, t) >= 0
                // Restart so all settings apply (after answering the phone)
                if (ok) Handler(Looper.getMainLooper()).postDelayed({ Profiles.restart(ctx) }, 1500)
                ok
            }
            crash = { ImportFiles.crashText(ctx) }
        }
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }
    if (!serving) PillButton("Übertragung im WLAN starten", icon = AIcon.Phone) { serving = server.start() }
    else {
        Column(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x14FFFFFF)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ip == null) Text("Das Gerät ist nicht mit einem WLAN verbunden.", color = Color(0xFFFFB86B), fontSize = 16.sp)
            else {
                Text("Am Handy (im selben WLAN) öffnen:", color = Muted, fontSize = 15.sp)
                val addr = "http://$ip:${server.port}/transfer"
                Text(addr, color = Accent2, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                QrWithLabel(addr)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", color = Muted, fontSize = 16.sp)
                    Text(server.code, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                Text("Altes Gerät: Sicherung herunterladen. Neues Gerät: hier ebenfalls starten und die Datei einspielen. Danach startet die App neu.", color = Muted, fontSize = 14.sp)
            }
            PillButton("Übertragung beenden") { server.stop(); serving = false }
        }
    }
}

/** QR code (black on white, with a border – so phone cameras also read it on a dark screen). */
@Composable
internal fun QrImage(text: String, size: Dp = 160.dp) {
    val m = remember(text) { QrCode.encode(text) } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val quiet = 2
        val cell = this.size.minDimension / (m.size + quiet * 2)
        for (y in m.indices) for (x in m[y].indices) if (m[y][x])
            drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell * 1.02f, cell * 1.02f))
    }
}

/** QR code with a short hint next to it. */
@Composable
internal fun QrWithLabel(text: String, size: Dp = 160.dp, scanLabel: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(text, size)
        if (scanLabel) Text("Oder mit der Handy-Kamera scannen", color = Muted, fontSize = 15.sp)
        else Text("Mit der Handy-Kamera scannen", color = Muted, fontSize = 15.sp)
    }
}
