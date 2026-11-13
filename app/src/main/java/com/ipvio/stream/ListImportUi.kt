package com.ipvio.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings > Import list: bring a Letterboxd, MyAnimeList or Trakt export into the app.
 * Two ways: pick the file on this device, or upload it from the phone over the Wi-Fi (small web page, same as the transfer page).
 */
@Composable
internal fun ListImportPage(ctx: Context) {
    Hint("Hole deine Listen von Letterboxd, MyAnimeList oder Trakt in die App. Die Titel werden mit deiner Playlist abgeglichen: Merkliste und Gesehen werden übernommen.")
    Hint("Letterboxd: ZIP oder CSV · MyAnimeList: XML · Trakt: JSON")
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment ?: "list"
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null && bytes.size <= 16 * 1024 * 1024) ListImportApply.run(ctx, name, bytes)
    }
    var noPicker by remember { mutableStateOf(false) }
    PillButton("Datei vom Gerät wählen", light = true, icon = AIcon.Download) {
        try { pick.launch(arrayOf("*/*")) } catch (e: Exception) { noPicker = true }
    }
    if (noPicker) Text("Auf diesem Gerät gibt es keine Dateiauswahl. Nutze den Import im WLAN.", color = Color(0xFFFFB86B), fontSize = 15.sp)

    var serving by remember { mutableStateOf(false) }
    val ip = remember { PhoneImport.localIp() }
    val server = remember {
        PhoneImport { }.apply {
            uploadDir = ImportFiles.tmpDir(ctx)
            onList = { name, bytes -> Handler(Looper.getMainLooper()).post { ListImportApply.run(ctx, name, bytes) } }
        }
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }
    if (!serving) PillButton("Import im WLAN starten", icon = AIcon.Phone) { serving = server.start() }
    else {
        Column(
            Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x14FFFFFF)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ip == null) Text("Das Gerät ist nicht mit einem WLAN verbunden.", color = Color(0xFFFFB86B), fontSize = 16.sp)
            else {
                Text("Am Handy (im selben WLAN) öffnen:", color = Muted, fontSize = 15.sp)
                val addr = "http://$ip:${server.port}/"
                Text(addr, color = Accent2, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                QrWithLabel(addr)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", color = Muted, fontSize = 16.sp)
                    Text(server.code, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                Text("Am Handy „Liste importieren“ wählen, den Code eingeben und die Exportdatei hochladen.", color = Muted, fontSize = 14.sp)
            }
            PillButton("Import beenden") { server.stop(); serving = false }
        }
    }
}
