package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AddPlaylistScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Last entered playlist that has not finished loading (crash, power loss, abort): prefill
    val pend = remember { PlaylistStore.pending(ctx) }
    val pendX = remember { pend?.let { LoadLogic.xtreamParts(it.url) } }
    var xtream by remember { mutableStateOf(pendX != null) }
    // Round 20: Stalker portal (portal address + MAC address)
    val pendS = remember { pend?.url?.let { StalkerLogic.parse(it) } }
    var stalker by remember { mutableStateOf(pendS != null) }
    var portal by remember { mutableStateOf(pendS?.portal ?: "") }
    var mac by remember { mutableStateOf(pendS?.mac ?: "00:1A:79:") }
    // Optional: some providers also want serial number and device IDs (as in TiviMate / STB Emu)
    var sn by remember { mutableStateOf(pendS?.sn ?: "") }
    var devId by remember { mutableStateOf(pendS?.id1 ?: "") }
    var devId2 by remember { mutableStateOf(pendS?.id2 ?: "") }
    var url by remember { mutableStateOf(if (pend != null && pendX == null && pendS == null) pend.url else "") }
    var host by remember { mutableStateOf(pendX?.first ?: "") }
    var user by remember { mutableStateOf(pendX?.second ?: "") }
    var pass by remember { mutableStateOf(pendX?.third ?: "") }
    var showPass by remember { mutableStateOf(false) }
    // Backup servers (optional): after an interrupted entry, restore the ones already saved
    val backups = remember { backupList(pend?.url?.let { PlaylistStore.backups(ctx, it) } ?: emptyList()) }
    // Send from the phone: local mini server as long as this screen is open
    val ip = remember { PhoneImport.localIp() }
    val receiver = remember {
        PhoneImport { inp ->
            Handler(Looper.getMainLooper()).post {
                val u = if (inp.isXtream) PlaylistStore.xtreamUrl(inp.host, inp.user, inp.pass) else inp.url
                stalker = false
                if (inp.isXtream) { xtream = true; host = inp.host; user = inp.user; pass = inp.pass } else { xtream = false; url = inp.url }
                if (u == null) PlaylistStore.error = "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                else scope.launch { if (PlaylistStore.load(ctx, u, true)) onDone() }
            }
        }
    }
    var serving by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // Round 20: upload a playlist file from the phone, read the crash report on the phone
        receiver.uploadDir = ImportFiles.tmpDir(ctx)
        receiver.saveUpload = { name, f -> ImportFiles.save(ctx, name, f) }
        receiver.crash = { ImportFiles.crashText(ctx) }
        // Full transfer from another device (settings, history, playlists with logins): also possible from the phone page
        receiver.restore = { t ->
            val ok = Backup.restoreText(ctx, t) >= 0
            if (ok) Handler(Looper.getMainLooper()).postDelayed({ Profiles.restart(ctx) }, 1500)
            ok
        }
        receiver.onEpg = { u ->
            Handler(Looper.getMainLooper()).post {
                Xmltv.setCustom(ctx, u)
                if (PlaylistStore.channels.isNotEmpty()) Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels)
            }
        }
        receiver.onList = { name, bytes ->
            Handler(Looper.getMainLooper()).post { ListImportApply.run(ctx, name, bytes) }
        }
        serving = receiver.start()
        onDispose { receiver.stop() }
    }
    val canCancel = PlaylistStore.lists.isNotEmpty() || PlaylistStore.channels.isNotEmpty()
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImportFiles.fromUri(ctx, uri) }
            if (u == null) PlaylistStore.error = "Das ist keine gültige M3U-Datei."
            else if (PlaylistStore.load(ctx, u, true)) onDone()
        }
    }
    // Backup of another device (with playlists and logins): load the file, the app restarts with everything in place
    val pickBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching -1
                    if (bytes.size > 4 * 1024 * 1024) -1 else Backup.restoreText(ctx, String(bytes, Charsets.UTF_8))
                }.getOrDefault(-1)
            }
            if (n >= 0) Profiles.restart(ctx) else PlaylistStore.error = "Keine gültige Sicherung gefunden."
        }
    }
    BackHandler(enabled = canCancel) { PlaylistStore.clearPending(ctx); onDone() }

    // Interrupted in the middle of loading last time: continue automatically without entering anything again
    LaunchedEffect(Unit) {
        val p = pend ?: return@LaunchedEffect
        when {
            p.autoResume && !PlaylistStore.loading -> { if (PlaylistStore.load(ctx, p.url, true, resumed = true)) onDone() }
            p.interrupted -> PlaylistStore.error = "Das Laden wurde mehrmals unterbrochen. Deine Angaben sind gespeichert – drücke „Hinzufügen“, um es erneut zu versuchen."
            p.msg != null -> PlaylistStore.error = p.msg
        }
    }

    if (PlaylistStore.loading) { PlaylistLoadingOverlay(); return }

    // Start focus on the M3U/Xtream choice (not on an input field: that would open the on-screen keyboard immediately)
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { startFocus.requestFocus() } }

    // Phone (narrow or portrait): single column, without the phone card, smaller margins
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = cfg.screenWidthDp < 900 || cfg.screenHeightDp > cfg.screenWidthDp
    // Everything should be visible at a glance: the screen is drawn a little smaller (TV 15 %, phone 8 %)
    // and scrolls without animation (the app-wide soft scrolling made this screen trail behind the cursor).
    val d0 = androidx.compose.ui.platform.LocalDensity.current
    val dense = remember(d0.density, d0.fontScale, compact) { androidx.compose.ui.unit.Density(d0.density * (if (compact) 0.92f else 0.85f), d0.fontScale) }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides dense,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides InstantSpec
    ) {
    Row(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(lerp(Bg, Accent1, 0.14f), Bg), radius = 1400f))
            .padding(horizontal = if (compact) 10.dp else 28.dp, vertical = if (compact) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: the form
        Column(
            // Padding in the scroll area: the focused button is enlarged and must not be cut off
            Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(if (compact) 6.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(Modifier.size(36.dp))
                Text("IPVIO", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Playlist hinzufügen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp))
            }
            // Many new users ask which link to enter: say clearly that the app brings no content of its own
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x14FFFFFF)).border(1.dp, Surf2, RoundedCornerShape(DS.RadiusM)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Wichtig zu wissen", color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("IPVIO Stream ist nur ein Abspielgerät. Die App liefert selbst keine Sender, Filme, Serien und keine Playlist. Den Link oder die Zugangsdaten bekommst du von deinem IPTV-Anbieter oder aus deiner eigenen Quelle. Wir können dir keinen Link nennen.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            // The padding sits inside the scrolling row: an enlarged (focused) button must not be cut off at its edge
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton("M3U-Link", Modifier.focusRequester(startFocus), primary = !xtream && !stalker, compact = true) { xtream = false; stalker = false }
                FocusButton("Xtream-Login", primary = xtream && !stalker, compact = true) { xtream = true; stalker = false }
                FocusButton("Stalker-Portal", primary = stalker, compact = true) { stalker = true; xtream = false }
            }
            if (stalker) {
                Field(portal, { portal = it }, "Portal-Adresse (z. B. http://portal.tld:8080/c/)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(mac, { mac = it }, "MAC-Adresse", Modifier.weight(1f))
                    Field(sn, { sn = it }, "Seriennummer (optional)", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(devId, { devId = it }, "Device ID (optional)", Modifier.weight(1f))
                    Field(devId2, { devId2 = it }, "Device ID 2 (optional)", Modifier.weight(1f))
                }
                Text("Seriennummer und Device IDs nur eintragen, wenn dein Anbieter sie verlangt (wie bei TiviMate oder STB Emu). Leer = die App erzeugt eigene Werte.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            } else if (!xtream) {
                Field(url, { url = it }, "http://…/playlist.m3u")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusButton("Aus Zwischenablage einfügen", compact = true) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.text?.let { url = it.toString() }
                    }
                    // Round 20: M3U file from the device (USB stick, Downloads …); on devices without a file picker via the phone page
                    FocusButton("Datei vom Gerät", compact = true) {
                        try { pickFile.launch(arrayOf("*/*")) }
                        catch (e: Exception) { PlaylistStore.error = "Auf diesem Gerät gibt es keine Dateiauswahl. Lade die Datei über die Handy-Seite rechts hoch." }
                    }
                }
            } else {
                Field(host, { host = it }, "Server (z. B. http://server.tld:8080)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(user, { user = it }, "Benutzername", Modifier.weight(1f))
                    Field(pass, { pass = it }, "Passwort", Modifier.weight(1f), password = !showPass)
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    FocusButton(if (showPass) "Passwort verbergen" else "Passwort anzeigen", compact = true) { showPass = !showPass }
                }
                BackupFields(backups)
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    FocusButton("+ Backup-Server", compact = true) { backups.add("") }
                }
                Text(BACKUP_HINT, color = Muted, fontSize = 15.sp)
            }
            PlaylistStore.error?.let { Text(it, color = DS.Danger, fontSize = 14.sp, lineHeight = 18.sp) }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton(if (PlaylistStore.loading) "Lade…" else "Hinzufügen", primary = true, compact = true) {
                    if (PlaylistStore.loading) return@FocusButton
                    val finalUrl = if (stalker) StalkerLogic.source(portal, mac, sn, devId, devId2)?.encode() else if (xtream) PlaylistStore.xtreamUrl(host, user, pass) else url
                    if (finalUrl == null) PlaylistStore.error = if (stalker) "Angaben unvollständig: Portal-Adresse und MAC-Adresse (z. B. 00:1A:79:12:34:56) nötig." else "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                    else if (xtream && !stalker && !backupsValid(backups)) PlaylistStore.error = BACKUP_INVALID
                    else {
                        if (xtream && !stalker) PlaylistStore.setBackups(ctx, finalUrl, backups.toList())
                        scope.launch { if (PlaylistStore.load(ctx, finalUrl, true)) onDone() }
                    }
                }
                if (canCancel) FocusButton("Abbrechen", compact = true) { PlaylistStore.clearPending(ctx); onDone() }
                // Nothing to add yet (first start): demo mode with sample entries and neutral placeholders, so the UI can be explored.
                // Settings then offer "Playlist hinzufügen" to leave it.
                else FocusButton("Weiter ohne Playlist", compact = true) { PlaylistStore.clearPending(ctx); PlaylistStore.error = null; DemoMode.enter(); onDone() }
            }
            // Second device? Take everything over in one step
            Text("Schon ein anderes Gerät mit IPVIO Stream? Spiele die Sicherung ein: Playlists, Zugangsdaten, Verlauf, Favoriten und Einstellungen sind danach sofort da.", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                FocusButton("Sicherung von anderem Gerät einspielen", compact = true) {
                    try { pickBackup.launch(arrayOf("*/*")) }
                    catch (e: Exception) { PlaylistStore.error = "Auf diesem Gerät gibt es keine Dateiauswahl. Spiele die Sicherung über die Handy-Seite rechts ein." }
                }
            }
        }
        // Right: instructions for input on the phone
        if (!compact) PhoneCard(Modifier.weight(1f), if (serving && ip != null) "http://" + ip + ":" + receiver.port else null, receiver.code)
    }
    }
}

/** Scrolls to a focused element at once (no animation, small margin) instead of the app-wide soft scrolling. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val InstantSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = 40f
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f && trailing > containerSize -> 0f
            leading < 0f -> leading - peek
            else -> trailing - containerSize + peek
        }
    }
}

/** Card with instructions: enter the playlist conveniently on the phone instead of with the remote. */
@Composable
private fun PhoneCard(modifier: Modifier, address: String?, code: String) {
    val shape = RoundedCornerShape(DS.RadiusXl)
    var bigQr by remember { mutableStateOf(false) }
    // The card scrolls (the focused QR code brings the rest into view), so nothing is cut off on small screens
    Column(
        modifier.fillMaxHeight().clip(shape).background(Surf).border(2.dp, Surf2, shape).verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Einfacher mit dem Handy", color = Accent2, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Mit der Fernbedienung zu tippen ist mühsam. Schick die Zugangsdaten lieber vom Handy:",
            color = Muted, fontSize = 15.sp, lineHeight = 21.sp
        )
        if (address == null) {
            Text(
                "Die Übertragung vom Handy ist gerade nicht verfügbar. Das Gerät muss mit dem WLAN verbunden sein.",
                color = Color(0xFFFFB86B), fontSize = 17.sp, lineHeight = 24.sp
            )
        } else {
            Step(1, "Handy mit demselben WLAN verbinden wie dieses Gerät.")
            Step(2, "Im Browser des Handys diese Adresse öffnen:")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(address, color = Accent2, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                var qf by remember { mutableStateOf(false) }
                Column(
                    Modifier.clip(RoundedCornerShape(DS.RadiusM)).border(if (qf) 3.dp else 0.dp, Color.White, RoundedCornerShape(DS.RadiusM))
                        .onFocusChanged { qf = it.isFocused }.clickable { bigQr = true }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QrImage(address, 110.dp)
                    Text("OK = groß", color = Muted, fontSize = 12.sp)
                }
            }
            Step(3, "Den Code von hier eingeben:")
            Row(
                Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Code", color = Muted, fontSize = 18.sp)
                Text(code, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
            }
            Step(4, "Zugangsdaten eintragen oder eine M3U-Datei hochladen und senden. Die Playlist wird danach automatisch geladen.")
        }
    }
    if (bigQr && address != null) Dialog(onDismissRequest = { bigQr = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val close = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); runCatching { close.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { bigQr = false }.focusRequester(close).focusable(), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                QrImage(address, 440.dp)
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Mit der Handy-Kamera scannen", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Handy und dieses Gerät müssen im selben WLAN sein. Halte die Kamera auf das Bild. Es klappt auch aus einiger Entfernung.", color = Muted, fontSize = 18.sp, lineHeight = 26.sp)
                    Text("Oder Adresse im Browser eintippen:", color = Muted, fontSize = 16.sp)
                    Text(address, color = Accent2, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                    Text("Code: $code", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                    Text("Zurück oder OK schließt das Bild.", color = Muted, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Accent1),
            contentAlignment = Alignment.Center
        ) {
            Text(n.toString(), color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color.White, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Explanation under the backup fields (Add playlist and Settings > Playlists). */
internal const val BACKUP_HINT = "Fällt der Server aus, wechselt die App von selbst zu einem Backup-Server. Anbieter nennen sie oft als weitere Server- oder DNS-Adressen; Benutzername und Passwort bleiben gleich."
internal const val BACKUP_INVALID = "Backup-Server ungültig: Adresse wie http://server.tld:8080 eingeben."

/** Empty fields are allowed; filled ones must be a valid server address. */
internal fun backupsValid(b: List<String>): Boolean = b.all { it.isBlank() || HostFailoverLogic.normalize(it) != null }

/** Editable list of backup servers, with at least one (empty) field. */
internal fun backupList(saved: List<String>): SnapshotStateList<String> =
    mutableStateListOf<String>().apply { addAll(saved); if (isEmpty()) add("") }

/** One field per backup server; the "+" button adds more, empty fields are ignored when saving. */
@Composable
internal fun BackupFields(list: SnapshotStateList<String>) {
    for (i in list.indices) {
        Field(list[i], { list[i] = it }, "Backup-Server ${i + 1} (optional)")
        HostHealthLine(list[i])
    }
}

/** "Reachable" or "Not reachable since …" under a server address; nothing while the app has never used it. */
@Composable
internal fun HostHealthLine(addr: String) {
    HostFailover.healthVersion   // recompose when the state changes
    val h = HostFailoverLogic.normalize(addr)?.let { HostFailover.health(it) } ?: return
    // Symbols are drawn (AppIcons), the dictionary texts contain none (I18nCoreTest.noEmojisInDictionaries)
    val color = if (h.ok) Accent2 else DS.Danger
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(if (h.ok) AIcon.Check else AIcon.Close, color, size = 16.dp)
        if (h.ok) Text("Erreichbar", color = color, fontSize = 14.sp)
        else {
            val t = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Lang.locale()).format(java.util.Date(h.at))
            Text("Nicht erreichbar seit $t", color = color, fontSize = 14.sp)
        }
    }
}
