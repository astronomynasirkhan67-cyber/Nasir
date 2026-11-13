package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

private class Cat(val icon: AIcon, val title: String, val sub: String)

private val CATS = listOf(
    Cat(AIcon.Gear, "Allgemein", "Start, Beenden, Autostart"),
    Cat(AIcon.Globe, "Sprache & Region", "Sprache, Uhrzeit, Zeitzone"),
    Cat(AIcon.Grid, "Ansicht", "Kartengröße, Seitenleiste, Raster"),
    Cat(AIcon.Sparkle, "Design und Farbe", "Farben, Schrift, Animationen"),
    Cat(AIcon.Lists, "Menü", "Reihenfolge und Einträge"),
    Cat(AIcon.Play, "Player", "Spulen, Intro, Qualität, defekte Links"),
    Cat(AIcon.Music, "Ton & Untertitel", "Sprachen, CC, Untertitel-Stil"),
    Cat(AIcon.Tag, "Genres & Kategorien", "Automatische Genres, Kategorien"),
    Cat(AIcon.Live, "Live-TV & Guide", "TV-Programm, Senderlisten, Erinnerungen"),
    Cat(AIcon.Download, "Playlists", "Verwalten, Sprachen, Aktualisieren"),
    Cat(AIcon.Person, "Profile", "Verlauf, Favoriten, Jugendschutz je Person"),
    Cat(AIcon.Star, "API-Schlüssel", "TMDB, IMDb, Rotten Tomatoes, Untertitel"),
    Cat(AIcon.Lock, "Jugendschutz", "PIN, Inhalte ab 18"),
    Cat(AIcon.Storage, "Speicher", "Cache, Verlauf, Fortsetzen"),
    Cat(AIcon.Pulse, "Diagnose", "Status und Fehlersuche"),
    Cat(AIcon.Download, "Listen importieren", "Letterboxd, MyAnimeList, Trakt"),
    Cat(AIcon.Live, "Boomer Mode", "Ganz einfach: große Kacheln zum Auswählen"),
    Cat(AIcon.Sliders, "Startseite anpassen", "Reihen und Widgets wählen und ordnen"),
    Cat(AIcon.Info, "Über IPVIO Stream", "Version, Quellen, Updates"),
    // Resets every settings tab at once (each tab 0..8 has its own reset for just that tab)
    Cat(AIcon.Warning, "Alles zurücksetzen", "Alle Einstellungen auf Standard"),
    // Donate is always the very last entry
    Cat(AIcon.HeartFilled, "Spende", "Unterstütze IPVIO Stream")
)

/** Open a settings page directly (e.g. profile circle at the top with only one profile: page "Profiles"). */
object SettingsNav {
    const val PROFILES = 10
    const val HOME = 17
    /** "Reset all settings" page; the donate page is always the last one (one after this). */
    const val RESET_ALL = 19
    var openPage by androidx.compose.runtime.mutableIntStateOf(-1)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsHub(onAdd: () -> Unit, reload: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(-1) }
    var last by remember { mutableIntStateOf(0) }
    LaunchedEffect(SettingsNav.openPage) {
        val p = SettingsNav.openPage
        if (p >= 0) { SettingsNav.openPage = -1; last = p; page = p }
    }
    val cardFocus = remember { List(CATS.size) { if (it == 0) ContentFocus.req else FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    BackHandler(enabled = page >= 0) { page = -1 }
    LaunchedEffect(page) {
        runCatching { if (page < 0) cardFocus[last].requestFocus() else backFocus.requestFocus() }
    }

    // No verticalScroll of its own: the settings tab sits in a LazyColumn that scrolls itself.
    Column(Modifier.padding(horizontal = DS.PageX, vertical = dpx(20)), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
        if (page < 0) {
            // Round 15: in the style of the home screen (tiles with an own color per area, calm font, white focus)
            Column(verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                Text("Einstellungen", color = Color.White, fontSize = spx(38), fontWeight = FontWeight.ExtraBold)
                Text("Alles rund um Aussehen, Wiedergabe und Konto", color = Tok.TextLow, fontSize = spx(16))
            }
            CATS.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                    row.forEachIndexed { c, cat ->
                        val idx = r * 3 + c
                        SettingsCard(cat, idx, Modifier.weight(1f).focusRequester(cardFocus[idx])) { last = idx; page = idx }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
                SettingsBack(Modifier.focusRequester(backFocus)) { page = -1 }
                Text(CATS[page].title, color = Color.White, fontSize = spx(36), fontWeight = FontWeight.ExtraBold)
            }
            // Below the last selectable element there are often still hints: Down at the end brings them into view
            val fm = LocalFocusManager.current
            val endReq = remember { BringIntoViewRequester() }
            val endScope = rememberCoroutineScope()
            Column(
                Modifier.onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        if (!fm.moveFocus(FocusDirection.Down)) endScope.launch { runCatching { endReq.bringIntoView() } }
                        true
                    } else false
                }.fillMaxWidth().clip(RoundedCornerShape(dpx(18))).background(Color(0x0DFFFFFF))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(dpx(18))).padding(dpx(26)),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            when (page) {
                0 -> GeneralPage(ctx)
                1 -> RegionPage(ctx)
                2 -> ViewPage(ctx)
                3 -> DesignPage(ctx)
                4 -> MenuPage(ctx)
                5 -> PlayerPage(ctx)
                6 -> AudioPage(ctx)
                7 -> FilterPage(ctx) { page = 9 }
                8 -> LivePage(ctx)
                9 -> PlaylistPage(ctx, onAdd, reload)
                10 -> ProfilesPage(ctx) { page = -1 }
                11 -> ApiKeysPage(ctx)
                12 -> ParentalPage(ctx) { page = -1 }
                13 -> StoragePage(ctx)
                14 -> DiagPage(ctx, reload)
                16 -> BoomerPage(ctx) { page = -1 }
                17 -> HomeLayoutPage(ctx)
                15 -> ListImportPage(ctx)
                SettingsNav.RESET_ALL -> ResetAllPage(ctx)
                SettingsNav.RESET_ALL + 1 -> DonateSection(ctx)
                else -> AboutPage(ctx)   // 18
            }
            // Every settings tab ends with a way back to the defaults. It resets only the settings of the tab that is open.
            if (SettingsResetLogic.hasReset(page)) {
                SectionTitle("Zurücksetzen")
                ConfirmPill("Diese Seite zurücksetzen") {
                    SettingsReset.page(ctx, page)
                    settingsToast(ctx, "Diese Seite wurde zurückgesetzt.")
                }
                Hint("Resets only the settings of this page to their defaults. All other pages stay unchanged. To reset everything at once, use Settings > Reset all.")
            }
            Spacer(Modifier.height(8.dp).bringIntoViewRequester(endReq))
            }
        }
    }
}

/** Color per area: same structure as the home screen tiles (gradient, icon in the area color, arrow). */
private fun catColors(i: Int): Triple<Color, Color, Color> {
    val h = (i * 360f / CATS.size + 18f) % 360f
    return Triple(Color.hsv(h, 0.62f, 0.34f), Color.hsv(h, 0.70f, 0.14f), Color.hsv(h, 0.45f, 1f))
}

@Composable
private fun SettingsCard(cat: Cat, idx: Int, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.04f) else 1f, label = "sc")
    val shape = RoundedCornerShape(dpx(14))
    val (c1, c2, ink) = catColors(idx)
    Row(
        modifier.height(dpx(96)).graphicsLayer { scaleX = s; scaleY = s }.clip(shape)
            .background(Brush.horizontalGradient(listOf(c1, c2)))
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else ink.copy(alpha = .22f), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(20)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(16))
    ) {
        Box(Modifier.size(dpx(48)).clip(CircleShape).background(ink.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            AppIcon(cat.icon, ink, size = dpx(28))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
            Text(cat.title, color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cat.sub, color = Tok.TextMid, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppIcon(AIcon.Right, Color.White, size = dpx(18))
    }
}

/** Back button of the sub pages: round button like in the header. */
@Composable
private fun SettingsBack(modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.size(dpx(52)).clip(CircleShape)
            .background(if (f) DS.FocusFill else Tok.Glass)
            .border(1.dp, if (f) DS.FocusFill else Color(0x26FFFFFF), CircleShape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(AIcon.Left, if (f) DS.FocusInk else Color.White, size = dpx(24)) }
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = Muted, fontSize = DS.TextSmall, lineHeight = 20.sp, modifier = Modifier.widthIn(max = 680.dp))
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

/** Choice chip with optional color dots. The chosen entry carries a check mark and a light border. */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, dots: List<Color> = emptyList(), modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, check: Boolean = true, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "chip")
    val shape = RoundedCornerShape(50)
    // Focus like all buttons: white surface, dark text. Selected (without focus): lighter surface, light border and check mark.
    Row(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.height(46.dp).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) DS.Overlay3 else Surf)
            .border(DS.Border, if (f) DS.FocusFill else if (selected) Color(0x99FFFFFF) else DS.Hairline, shape)
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocus?.invoke(it.isFocused) } }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dots.forEach { Box(Modifier.size(18.dp).clip(CircleShape).background(it).border(1.dp, if (f) DS.FocusInk.copy(alpha = .3f) else DS.Overlay3, CircleShape)) }
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = DS.TextBody, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (selected && check) AppIcon(AIcon.Check, if (f) DS.FocusInk else Accent2, size = 18.dp)
    }
}

/** Chip for the sub pages of "View" (ViewSettings.kt). */
@Composable
internal fun ViewChip(label: String, selected: Boolean, onFocus: (Boolean) -> Unit, onClick: () -> Unit) =
    ChoiceChip(label, selected, onFocus = onFocus, check = false, onClick = onClick)   // headings: no check mark (only real settings get one)

/** Round color swatch for the custom color. */
@Composable
private fun Swatch(rgb: Int, selected: Boolean, onFocus: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.18f) else 1f, label = "sw")
    val col = Color(0xFF000000.toInt() or rgb)
    Box(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(44.dp).clip(CircleShape).background(col)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else Color(0x44FFFFFF), CircleShape)
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocus?.invoke(it.isFocused) } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) AppIcon(AIcon.Check, if (ThemeMath.luminance(rgb) > 0.45) Color.Black else Color.White, size = 22.dp)
    }
}

/** Switch row as in standard apps: title, explanation and an on/off switch. OK toggles. */
@Composable
internal fun ToggleRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        Modifier.fillMaxWidth().widthIn(max = 760.dp).clip(shape)
            .background(if (f) RowFocus else Surf)
            .border(DS.Border, if (f) Accent2 else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (desc != null) Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Box(
            Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Accent1 else Color(0x44FFFFFF)).padding(3.dp)
        ) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(24.dp).clip(CircleShape).background(Color.White))
        }
        Text(if (checked) "An" else "Aus", color = if (checked) Accent2 else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
    }
}

/** Dangerous action with confirmation: the first press asks, the second executes (the question expires after 4 s). */
@Composable
internal fun ConfirmPill(label: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
    PillButton(if (armed) "Wirklich? OK zum Bestätigen" else label, light = armed) {
        if (armed) { armed = false; onConfirm() } else armed = true
    }
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

/** Selection row in the same style as ToggleRow: title left, current value right, OK opens the list. */
@Composable
internal fun SelectRow(label: String, value: String, options: List<String>, selected: Int, active: Boolean = false, onPick: (Int) -> Unit) {
    var f by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Box {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).clip(shape)
                .background(if (f) RowFocus else Surf)
                .border(DS.Border, if (f) Accent2 else DS.Hairline, shape)
                .onFocusChanged { f = it.isFocused }
                .clickable { open = true }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(value, color = if (active) Accent2 else DS.TextSoft, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
            AppIcon(AIcon.Caret, Muted, size = 18.dp)
        }
        if (open) OptionPopup(options, selected, { open = false }) { onPick(it); open = false }
    }
}

/** Number pad for the PIN: arrow keys + OK or directly the digit keys of the remote. */
@Composable
internal fun PinPad(title: String, hint: String?, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    LaunchedEffect(pin) { if (pin.length == 4) { val p = pin; pin = ""; onSubmit(p) } }
    BackHandler { onCancel() }
    fun add(d: Char) { if (pin.length < 4) pin += d }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            val ne = e.nativeKeyEvent
            if (ne.action != android.view.KeyEvent.ACTION_DOWN) false
            else {
                val kc = ne.keyCode
                val d = when {
                    kc in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> '0' + (kc - android.view.KeyEvent.KEYCODE_0)
                    kc in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (kc - android.view.KeyEvent.KEYCODE_NUMPAD_0)
                    else -> null
                }
                if (d != null) { add(d); true } else false
            }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Hint(hint)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { i ->
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (i < pin.length) Accent2 else DS.Overlay3))
            }
            if (error != null) Text(error, color = DS.Danger, fontSize = 15.sp)
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "ESC")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    val ic = when (k) { "DEL" -> AIcon.Backspace; "ESC" -> AIcon.Close; else -> null }
                    SmallPill(if (ic != null) "" else k, Modifier.width(78.dp).then(if (k == "5") Modifier.focusRequester(start) else Modifier), icon = ic) {
                        when (k) {
                            "DEL" -> pin = pin.dropLast(1)
                            "ESC" -> onCancel()
                            else -> add(k[0])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionPage(ctx: Context) {
    val langOpts = listOf("Automatisch (Gerät)") + I18nCore.NATIVE
    val li = if (General.lang.isBlank()) 0 else I18nCore.CODES.indexOf(General.lang) + 1
    SectionTitle("Sprache")
    SelectRow("Sprache der App", if (li == 0) "Automatisch (Gerät)" else I18nCore.NATIVE[li - 1], langOpts, li) {
        General.setLang(ctx, if (it == 0) "" else I18nCore.CODES[it - 1])
    }
    Hint("Alle Menüs und Hinweise erscheinen in dieser Sprache. Beschreibungen, Besetzung und Titel-Infos kommen von TMDB und werden in derselben Sprache geladen, sobald du einen Titel öffnest. Namen von Sendern, Filmen und Kategorien stammen von deinem Anbieter und bleiben unverändert.")
    SectionTitle("Uhrzeit")
    val fmts = listOf("Automatisch (Gerät)", "24 Stunden (14:30)", "12 Stunden (2:30 PM)")
    SelectRow("Zeitformat", fmts[General.timeFmt], fmts, General.timeFmt) { General.setTimeFmt(ctx, it) }
    val zoneOpts = SettingsLogic.ZONES.map { if (it.id.isEmpty()) "Systemzeit (Gerät: " + java.util.TimeZone.getDefault().id + ")" else it.label }
    val zi = SettingsLogic.ZONES.indexOfFirst { it.id == General.tzId }.coerceAtLeast(0)
    SelectRow("Zeitzone", zoneOpts[zi], zoneOpts, zi) { General.setTz(ctx, SettingsLogic.ZONES[it].id) }
    Text("Uhrzeit jetzt: " + hhmm(System.currentTimeMillis() / 1000), color = Accent2, fontSize = 16.sp)
    Hint("Gilt für alle Uhrzeiten, zum Beispiel im TV-Guide und in der Live-TV-Infoleiste. „Systemzeit“ nutzt die Einstellung deines Geräts. Zeigt die App Uhrzeiten falsch an, steht dein Fernseher vermutlich auf UTC; wähle dann hier deine Zeitzone.")
}

@Composable
private fun ParentalPage(ctx: Context, onExit: () -> Unit) {
    // Steps of the PIN entry: 0 = none, 1 = unlock, 2 = new PIN, 3 = confirm new PIN, 4 = old PIN when removing
    var step by remember { mutableIntStateOf(if (Parental.pinSet && !Parental.unlocked) 1 else 0) }
    var first by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { Parental.unlocked = false } }
    // Counting via regex over all titles: in the background, not while drawing
    val src = PlaylistStore.channels
    val hideNow = Parental.hideAdult
    val hiddenCount by produceState(0, Parental.version, src, hideNow) {
        value = if (hideNow) 0 else withContext(Dispatchers.Default) { src.count { ParentalLogic.isAdult(it.group, it.title) } }
    }
    when (step) {
        1 -> PinPad("PIN eingeben", "Die Jugendschutz-Einstellungen sind mit einer PIN geschützt.", err,
            {
                if (Parental.verify(ctx, it)) { Parental.unlocked = true; err = null; step = 0 }
                else err = Parental.pinError()
            },
            { onExit() })
        2 -> PinPad("Neue PIN festlegen", "Wähle eine 4-stellige PIN.", err, { first = it; err = null; step = 3 }, { step = 0 })
        3 -> PinPad("PIN bestätigen", "Gib die PIN noch einmal ein.", err,
            { if (it == first) { Parental.setPin(ctx, it); Parental.unlocked = true; err = null; step = 0 } else { err = "Die PINs stimmen nicht überein"; step = 2 } },
            { step = 0 })
        else -> {
            ToggleRow(
                "Inhalte für Erwachsene ausblenden",
                "Blendet Kategorien und Titel mit Begriffen wie XXX, Adult oder 18+ in der ganzen App aus: Startseite, Suche, Listen und Live-TV.",
                Parental.hideAdult
            ) { Parental.setHide(ctx, it) }
            if (Parental.hideAdult) Text("Der Schutz ist aktiv.", color = Accent2, fontSize = 15.sp)
            else Text(if (hiddenCount > 0) "$hiddenCount Einträge in deiner Playlist würden ausgeblendet." else "Kein Erwachsenen-Inhalt erkannt.", color = Muted, fontSize = 15.sp)
            SectionTitle("PIN")
            Text(if (Parental.pinSet) "PIN ist gesetzt" else "Keine PIN gesetzt", color = if (Parental.pinSet) Accent2 else Muted, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (Parental.pinSet) "PIN ändern" else "PIN festlegen", light = !Parental.pinSet) { err = null; step = 2 }
                if (Parental.pinSet) ConfirmPill("PIN entfernen") { Parental.clearPin(ctx) }
            }
            Hint("Mit einer PIN lässt sich der Schutz nur nach Eingabe der PIN ändern. Die Erkennung arbeitet mit Stichwörtern in Kategorie- und Titelnamen und ist kein vollständiger Filter. Beim Zurücksetzen aller Einstellungen bleibt die PIN erhalten.")
        }
    }
}

@Composable
private fun GeneralPage(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    SectionTitle("Start")
    ToggleRow(
        "Beim Einschalten des Geräts öffnen",
        "IPVIO Stream startet automatisch, sobald der Fernseher oder die Box hochgefahren ist.",
        General.autoStart
    ) { on ->
        General.setAutoStart(ctx, on)
        if (on && !canStartFromBoot(ctx)) openOverlaySettings(ctx)
    }
    val granted = remember(tick, General.autoStart) { canStartFromBoot(ctx) }
    if (General.autoStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconLabel(
                if (granted) AIcon.Check else AIcon.Warning,
                if (granted) "Berechtigung erteilt" else "Berechtigung „Über anderen Apps einblenden“ fehlt",
                if (granted) Accent2 else Color(0xFFFFB454), 15.sp, iconSize = 16.dp, maxLines = 2
            )
            if (!granted) SmallPill("Einstellung öffnen") { openOverlaySettings(ctx) }
            SmallPill("Prüfen") { tick++; reportOverlayState(ctx) }
        }
        Hint("Ab Android 10 erlaubt das System den Autostart nur mit dieser Berechtigung. Manche Geräte (z. B. einzelne Fire-TV-Modelle) blockieren den Autostart trotzdem; das kann die App nicht ändern.")
    }
    val tabs = listOf("Start", "Filme", "Serien", "Live TV", "Suche")
    val shown = SettingsLogic.resolveStartTab(General.startTab, AppSettings.menuOrder.toList(), AppSettings.menuHidden.toList())
    SelectRow("Startseite", tabs[shown], tabs, shown) { General.setStartTab(ctx, it) }
    Hint("Diese Seite öffnet sich beim Start der App. Versteckte Menüpunkte sind nicht wählbar.")
    ToggleRow("Letzten Sender beim Start abspielen", "Wenn du zuletzt Live TV gesehen hast, läuft dieser Sender direkt nach dem Start.", General.resumeLive) { General.setResumeLive(ctx, it) }
    ToggleRow("Letzten Film / letzte Serie beim Start fortsetzen", "Wenn du zuletzt einen Film oder eine Serie gesehen hast, läuft sie nach dem Start an der gemerkten Stelle weiter (bei einer fertigen Folge mit der nächsten).", General.resumeVod) { General.setResumeVod(ctx, it) }

    SectionTitle("Bedienung")
    ToggleRow("Tastatur des Geräts in der Suche", "Aus: die eingebaute Tastatur der App. An: die Tastatur deines Geräts (z. B. Gboard) mit allen Sprachen, Sprachfunktion und Einfügen. Mit OK auf das Suchfeld wird sie geöffnet.", AppSettings.systemKeyboard) { AppSettings.setSystemKeyboard(ctx, it) }
    ToggleRow("Beenden bestätigen", "Auf der Startseite muss „Zurück“ zweimal gedrückt werden, damit die App nicht aus Versehen schließt.", General.confirmExit) { General.setConfirmExit(ctx, it) }
    // Phone/tablet: landscape (the UI is built for the TV)
    if (MobilePrefs.isMobile(ctx)) {
        val act = generateSequence<Context>(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        ToggleRow("Auf dem Handy im Querformat", "Die App ist für den Fernseher gebaut und wirkt quer am besten. Aus: dreht mit dem Gerät. Im Player: Tippen zeigt die Steuerung, Doppeltippen links/rechts spult, in der Mitte Pause.", MobilePrefs.landscape) {
            if (act != null) MobilePrefs.set(act, it)
        }
    }
}

internal fun canStartFromBoot(ctx: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(ctx)

/** Opens the overlay permission screen; falls back to the app details and the general settings (Fire TV has no overlay screen). */
internal fun openOverlaySettings(ctx: Context) {
    val pkg = android.net.Uri.parse("package:" + ctx.packageName)
    val intents = listOf(
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
    )
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) { /* try the next fallback */ }
    }
    android.widget.Toast.makeText(ctx, Lang.tr("Dieses Gerät bietet die Einstellung nicht an. Autostart funktioniert evtl. trotzdem."), android.widget.Toast.LENGTH_LONG).show()
}

/** Shows the result of the permission check so the "Prüfen" button gives visible feedback. */
internal fun reportOverlayState(ctx: Context) {
    val msg = if (canStartFromBoot(ctx)) "Berechtigung erteilt. Der Autostart ist bereit."
    else "Berechtigung fehlt noch. Bitte „Einstellung öffnen“ wählen und IPVIO Stream erlauben."
    android.widget.Toast.makeText(ctx, Lang.tr(msg), android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun StoragePage(ctx: Context) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    // Sizes and deleting run in the background: the metadata folder can contain tens of thousands of files
    var sizes by remember { mutableStateOf(List(General.AREAS.size) { 0L }) }
    LaunchedEffect(tick) { sizes = withContext(Dispatchers.IO) { General.AREAS.map { General.areaSize(ctx, it) } } }
    SectionTitle("Zwischenspeicher")
    General.AREAS.forEachIndexed { i, a ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(420.dp)) {
                Text(a.name + "  ·  " + SettingsLogic.fmtBytes(sizes[i]), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(a.note, color = Muted, fontSize = 13.sp)
            }
            ConfirmPill("Leeren") { scope.launch { withContext(Dispatchers.IO) { General.clearArea(ctx, a) }; tick++ } }
        }
    }
    Text("Gesamt: " + SettingsLogic.fmtBytes(sizes.sum()), color = Accent2, fontSize = 15.sp)
    Hint("Geleerte Daten lädt die App bei Bedarf neu. Titel-Infos und Bilder erscheinen danach kurz später. Der Playlist-Zwischenspeicher macht den Start schnell; ohne ihn wartet die App auf den Download.")
    SectionTitle("Verlauf")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfirmPill("Suchverlauf löschen") { AppSettings.clearSearch(ctx) }
        ConfirmPill("Zuletzt geschaut löschen") { General.clearHistory(ctx) }
        ConfirmPill("Weiterschauen löschen") { General.clearResume(ctx) }
    }
    Hint("Meine Liste und Favoriten bleiben immer erhalten.")
    SectionTitle("Sicherung")
    var backupMsg by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Sicherung erstellen") {
            scope.launch {
                val f = withContext(Dispatchers.IO) { Backup.export(ctx) }
                backupMsg = if (f != null) "Gespeichert: " + f.absolutePath else "Sicherung fehlgeschlagen."
            }
        }
        ConfirmPill("Sicherung wiederherstellen") {
            scope.launch {
                val n = withContext(Dispatchers.IO) { Backup.restore(ctx) }
                if (n >= 0) Profiles.restart(ctx) else backupMsg = "Keine gültige Sicherung gefunden."
            }
        }
    }
    // Share: save the backup to a freely chosen place (Google Drive, Downloads, USB …) or load it from there
    val saveAs = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val txt = Backup.exportText(ctx, false) ?: return@runCatching false
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(txt.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            backupMsg = if (ok) "Sicherung gespeichert." else "Sicherung fehlgeschlagen."
        }
    }
    // Same, but with playlists and logins: for the second device (contains passwords, keep the file private)
    val saveAsFull = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val txt = Backup.exportText(ctx, true) ?: return@runCatching false
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(txt.toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            backupMsg = if (ok) "Sicherung gespeichert." else "Sicherung fehlgeschlagen."
        }
    }
    val loadFrom = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching -1
                    if (bytes.size > 4 * 1024 * 1024) -1 else Backup.restoreText(ctx, String(bytes, Charsets.UTF_8))
                }.getOrDefault(-1)
            }
            if (n >= 0) Profiles.restart(ctx) else backupMsg = "Keine gültige Sicherung gefunden."
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Sicherung mit Playlists speichern …") {
            try { saveAsFull.launch("ipvio_backup_full.json") }
            catch (e: Exception) { backupMsg = "Auf diesem Gerät gibt es keine Dateiauswahl. Nutze die Übertragung im WLAN." }
        }
        PillButton("Sicherung speichern unter …") {
            try { saveAs.launch("ipvio_backup.json") }
            catch (e: Exception) { backupMsg = "Auf diesem Gerät gibt es keine Dateiauswahl. Nutze „Sicherung erstellen“." }
        }
        ConfirmPill("Sicherung aus Datei laden …") {
            try { loadFrom.launch(arrayOf("*/*")) }
            catch (e: Exception) { backupMsg = "Auf diesem Gerät gibt es keine Dateiauswahl." }
        }
    }
    backupMsg?.let { Text(it, color = Accent2, fontSize = 14.sp, modifier = Modifier.widthIn(max = 680.dp)) }
    Hint("„Speichern unter“ lässt dich den Ort wählen, zum Beispiel Google Drive, Downloads oder einen USB-Stick – so nimmst du die Sicherung auf ein anderes Gerät mit.")
    Hint("Gesichert werden Einstellungen, Favoriten, Verlauf, Weiterschauen und Profile. Playlists und Zugangsdaten sind nur in „Sicherung mit Playlists“ enthalten (die Datei enthält Passwörter, gib sie nicht weiter). Beim Wiederherstellen startet die App neu. Auf einem neuen Gerät geht das gleich beim Hinzufügen der ersten Playlist.")
    TransferSection(ctx)
}

@Composable
private fun MenuPage(ctx: Context) {
    Hint("Verschiebe Einträge mit den Pfeil-Knöpfen oder blende sie aus. Start und Einstellungen bleiben immer sichtbar.")
    AppSettings.menuOrder.forEach { id ->
        // key(id): the row (and the focus on its arrow) moves together with the entry, so the cursor follows it
        androidx.compose.runtime.key(id) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(MENU_LABELS[id], color = if (id in AppSettings.menuHidden) Muted else Color.White,
                    fontSize = 17.sp, modifier = Modifier.width(190.dp))
                SmallPill("", icon = AIcon.Up) { AppSettings.moveMenu(ctx, id, -1) }
                SmallPill("", icon = AIcon.Down) { AppSettings.moveMenu(ctx, id, 1) }
                if (id != 0 && id != 5) { val hidden = id in AppSettings.menuHidden; SmallPill(if (hidden) "versteckt" else "sichtbar", icon = if (hidden) AIcon.EyeOff else AIcon.Eye) { AppSettings.toggleMenu(ctx, id) } }
            }
        }
    }
    PillButton("Standard-Reihenfolge wiederherstellen") { AppSettings.setMenuDefault(ctx) }
}

@Composable
private fun PlayerPage(ctx: Context) {
    SectionTitle("Wiedergabe")
    val steps = listOf(5, 10, 15, 30, 60)
    val hides = listOf(3, 4, 5, 6, 8, 10, 15)
    SelectRow("Sprung links/rechts", "${AppSettings.seekStep} s", steps.map { "$it s" }, steps.indexOf(AppSettings.seekStep).coerceAtLeast(0)) {
        AppSettings.setPlayer(ctx, AppSettings.skipIntro, AppSettings.showInfo, steps[it])
    }
    val asp = General.ASPECT_NAMES
    SelectRow("Standard-Bildformat", asp[General.defaultAspect], asp, General.defaultAspect) { General.setAspect(ctx, it) }
    SelectRow("Steuerleiste ausblenden nach", "${AppSettings.hideSec} s", hides.map { "$it s" }, hides.indexOf(AppSettings.hideSec).coerceAtLeast(0)) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, hides[it], AppSettings.showEndTime, AppSettings.autoSkipIntro)
    }
    ToggleRow("Stream-Info am Start", "Zeigt kurz Auflösung, Ton und Format beim Abspielen von Filmen und Serien.", AppSettings.showInfo) {
        AppSettings.setPlayer(ctx, AppSettings.skipIntro, it, AppSettings.seekStep)
    }
    ToggleRow("Endzeit anzeigen", "Zeigt, um wie viel Uhr der Film voraussichtlich endet.", AppSettings.showEndTime) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, it, AppSettings.autoSkipIntro)
    }
    SectionTitle("Intro und nächste Folge")
    ToggleRow("Intro überspringen anbieten", "Zeigt bei Serien einen Knopf „Intro überspringen“, wenn Intro-Zeiten bekannt sind.", AppSettings.skipIntro) {
        AppSettings.setPlayer(ctx, it, AppSettings.showInfo, AppSettings.seekStep)
    }
    ToggleRow("Intro automatisch überspringen", "Überspringt bekannte Intros ohne Rückfrage.", AppSettings.autoSkipIntro) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, AppSettings.showEndTime, it)
    }
    ToggleRow("Nächste Folge automatisch", "Startet die nächste Folge nach dem Abspann von selbst.", AppSettings.autoNext) {
        AppSettings.setPlayerExtra(ctx, it, AppSettings.hideSec, AppSettings.showEndTime, AppSettings.autoSkipIntro)
    }
    ToggleRow("Abspann automatisch überspringen", "Startet die nächste Folge, sobald der Abspann beginnt. Geht nur, wenn für die Serie Abspann-Zeiten bekannt sind (TheIntroDB); sonst bleibt es beim Hinweis in den letzten 30 Sekunden.", AppSettings.autoSkipOutro) {
        AppSettings.setAutoOutro(ctx, it)
    }
    SectionTitle("Uhr im Bild")
    if (AppSettings.clockOn) { Text("Vorschau", color = Muted, fontSize = 14.sp); ClockSample() }
    ToggleRow("Uhrzeit im Bild", "Zeigt die aktuelle Uhrzeit beim Abspielen in einer Ecke (nicht, solange die Steuerleiste offen ist).", AppSettings.clockOn) {
        AppSettings.setClock(ctx, it, AppSettings.clockPos, AppSettings.clockOpacity, AppSettings.clockSize)
    }
    if (AppSettings.clockOn) {
        val poss = listOf("Oben rechts", "Oben links", "Unten rechts", "Unten links")
        SelectRow("Uhrzeit: Ort", poss[AppSettings.clockPos], poss, AppSettings.clockPos) {
            AppSettings.setClock(ctx, true, it, AppSettings.clockOpacity, AppSettings.clockSize)
        }
        val ops = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
        SelectRow("Uhrzeit: Schrift-Deckkraft", "${AppSettings.clockOpacity} %", ops.map { "$it %" }, ops.indexOf(AppSettings.clockOpacity).let { if (it < 0) 5 else it }) {
            AppSettings.setClock(ctx, true, AppSettings.clockPos, ops[it], AppSettings.clockSize)
        }
        val sizes = listOf("Klein", "Mittel", "Groß")
        SelectRow("Uhrzeit: Größe", sizes[AppSettings.clockSize], sizes, AppSettings.clockSize) {
            AppSettings.setClock(ctx, true, AppSettings.clockPos, AppSettings.clockOpacity, it)
        }
        ToggleRow("Uhrzeit: Hintergrund", "Dunkler Kasten hinter der Uhr. Aus = nur die Zahlen.", AppSettings.clockBg) {
            AppSettings.setClockBg(ctx, it, AppSettings.clockBgOpacity)
        }
        if (AppSettings.clockBg) {
            val bops = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
            SelectRow("Uhrzeit: Hintergrund-Deckkraft", "${AppSettings.clockBgOpacity} %", bops.map { "$it %" }, bops.indexOf(AppSettings.clockBgOpacity).let { if (it < 0) 3 else it }) {
                AppSettings.setClockBg(ctx, true, bops[it])
            }
        }
        Hint("Die Uhr folgt der Zeitzone und dem 12/24-Stunden-Format aus den Einstellungen. Im Player geht es auch über Einstellungen > Uhrzeit.")
    }
    SectionTitle("Netzwerk und Bildrate")
    val bufNames = listOf("Automatisch", "Normal", "Groß", "Sehr groß")
    SelectRow("Puffer (bei langsamem Internet)", bufNames[AppSettings.bufferMode], bufNames, AppSettings.bufferMode) { AppSettings.setBufferMode(ctx, it) }
    Hint("Ein größerer Puffer lädt mehr Film im Voraus und verhindert Ruckeln bei schwacher Leitung; dafür dauert der Start etwas länger und es wird mehr Speicher gebraucht. „Automatisch“ beginnt normal und vergrößert den Puffer von selbst, wenn es mehrfach ruckelt (aktuell Stufe ${AppSettings.bufferLearned}). Gilt ab dem nächsten gestarteten Titel.")
    ToggleRow("Bildrate anpassen", "Passt die Bildwiederholrate des Fernsehers an den Film an (z. B. 24 Bilder), wenn der Fernseher das ohne Bildaussetzer kann. Bei Fire TV zusätzlich in den Geräteeinstellungen „Originale Bildrate anpassen“ prüfen.", AppSettings.autoFps) {
        AppSettings.setAutoFps(ctx, it)
    }
    Hint("Intro-Zeiten stammen aus der Community-Datenbank TheIntroDB und gibt es nicht für jede Serie. Die Stream-Info erscheint nur bei Filmen und Serien.")
    SectionTitle("Zusatzbild")
    DualPrefs.init(ctx)
    SelectRow("Anordnung", DualPrefs.ARRANGE_NAMES[DualPrefs.split], DualPrefs.ARRANGE_NAMES, DualPrefs.split) { DualPrefs.set(ctx, split = it) }
    SelectRow("Fenstergröße", DualPrefs.SIZE_NAMES[DualPrefs.size], DualPrefs.SIZE_NAMES, DualPrefs.size) { DualPrefs.set(ctx, size = it) }
    SelectRow("Position", DualPrefs.CORNER_NAMES[DualPrefs.corner], DualPrefs.CORNER_NAMES, DualPrefs.corner) { DualPrefs.set(ctx, corner = it) }
    Hint("Gilt für Bild-in-Bild. Deine Auswahl wird gespeichert.")
    SourceSettingsSection(ctx)
    DeadLinksSection(ctx)
}

@Composable
private fun DesignPage(ctx: Context) {
    fun save(accent: Int = Look.accentIdx, custom: Int = Look.custom, bg: Int = Look.bgIdx, motion: Int = Look.motion, scale: Int = Look.uiScale, custom2: Int = Look.custom2) =
        AppSettings.setLook(ctx, accent, custom, bg, motion, scale, custom2)
    fun col(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    // Show the preview at the top right (fixed, does not scroll along); show highlighted colors only there
    DisposableEffect(Unit) {
        DesignPreview.clear(); DesignPreview.visible = true
        onDispose { DesignPreview.visible = false; DesignPreview.clear() }
    }
    // When moving on, "focus gone" may arrive only after "focus there" of the next entry: clear only the own preview
    fun hover(on: Boolean, label: String, set: () -> Unit) {
        if (on) { DesignPreview.clear(); set(); DesignPreview.label = label }
        else if (DesignPreview.label == label) DesignPreview.clear()
    }

    // Space stays on the right for the preview
    Column(Modifier.padding(end = DesignPreview.WIDTH + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SectionTitle("Fertige Designs")
    Look.DESIGNS.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { d ->
                val a = Look.ACCENTS[d.accent.coerceIn(0, Look.ACCENTS.lastIndex)]
                val on = Look.accentIdx == d.accent && Look.bgIdx == d.bg
                ChoiceChip(d.name, on, listOf(col(a.fill), col(a.highlight)),
                    onFocus = { hover(it, Lang.tr(d.name)) { DesignPreview.accent = d.accent; DesignPreview.bg = d.bg } }) { save(accent = d.accent, bg = d.bg) }
            }
        }
    }
    Hint("Ein Klick stellt Farbe und Hintergrund passend ein. Darunter kannst du alles weiter von Hand ändern.")
    SectionTitle("Farbthema")
    val ids = Look.ACCENTS.indices.toList() + Look.CUSTOM
    ids.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                if (i == Look.CUSTOM) {
                    val c2 = if (Look.custom2 >= 0) Look.custom2 else Look.autoHighRgb()
                    ChoiceChip("Eigene Farben", Look.accentIdx == i, listOf(col(Look.custom), col(c2)),
                        onFocus = { hover(it, Lang.tr("Eigene Farben")) { DesignPreview.accent = i } }) { save(accent = i) }
                } else {
                    val p = Look.ACCENTS[i]
                    ChoiceChip(p.name, Look.accentIdx == i, listOf(col(p.fill), col(p.highlight)),
                        onFocus = { hover(it, Lang.tr(p.name)) { DesignPreview.accent = i } }) { save(accent = i) }
                }
            }
        }
    }

    if (Look.accentIdx == Look.CUSTOM) {
        SectionTitle("Eigene Farben")
        // Both colors freely selectable: first select the color above, then swatch or hex code
        var slot by remember { mutableIntStateOf(0) }
        val c2 = if (Look.custom2 >= 0) Look.custom2 else Look.autoHighRgb()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceChip("Farbe 1 · Flächen", slot == 0, listOf(col(Look.custom))) { slot = 0 }
            ChoiceChip(if (Look.custom2 >= 0) "Farbe 2 · Hervorhebung" else "Farbe 2 · automatisch", slot == 1, listOf(col(c2))) { slot = 1 }
        }
        val current = if (slot == 0) Look.custom else c2
        fun pick(rgb: Int) { if (slot == 0) save(custom = rgb) else save(custom2 = rgb) }
        Look.SWATCHES.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Swatch(c, c == current, onFocus = { on ->
                        hover(on, ThemeMath.hexOf(c)) { if (slot == 0) DesignPreview.custom = c else DesignPreview.custom2 = c }
                    }) { pick(c) }
                }
            }
        }
        var hex by remember(slot, current) { mutableStateOf(ThemeMath.hexOf(current)) }
        val parsed = ThemeMath.parseHex(hex)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(240.dp)) { Field(hex, { hex = it.take(7) }, "#RRGGBB") }
            PillButton(if (parsed != null) "Übernehmen" else "Ungültig", light = parsed != null) { parsed?.let { pick(it) } }
            if (slot == 1 && Look.custom2 >= 0) PillButton("Automatisch") { save(custom2 = -1) }
        }
        if (Look.custom2 >= 0 && ThemeMath.contrast(Look.custom2, Look.BGS[Look.bgIdx.coerceIn(0, Look.BGS.lastIndex)].bg) < 3.0) {
            Text("Farbe 2 ist auf diesem Hintergrund schwer lesbar.", color = Color(0xFFFFB454), fontSize = 15.sp)
        }
        Hint("Farbe 1 färbt Knöpfe, Balken und markierte Flächen – die Schrift darauf wird automatisch hell oder dunkel, damit sie lesbar bleibt. Farbe 2 färbt Hervorhebungen, Rahmen und farbige Schrift. „Automatisch“ berechnet Farbe 2 passend zu Farbe 1.")
    }

    SectionTitle("Hintergrund")
    Look.BGS.indices.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val b = Look.BGS[i]
                ChoiceChip(b.name, Look.bgIdx == i, listOf(Color(0xFF000000.toInt() or b.surf2)),
                    onFocus = { hover(it, Lang.tr(b.name)) { DesignPreview.bg = i } }) { save(bg = i) }
            }
        }
    }

    // Round 14: choice as fields (like the colors) – highlighting shows the effect in the preview, OK adopts it
    SectionTitle("Animationen")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Look.MOTION.forEachIndexed { i, n ->
            ChoiceChip(n, Look.motion == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.motion = i } }) { save(motion = i) }
        }
    }
    SectionTitle("Anzeigegröße")
    Look.SCALES.indices.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val n = Look.SCALES[i]
                ChoiceChip(n, Look.uiScale == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.scale = i } }) { save(scale = i) }
            }
        }
    }
    val cfg = LocalConfiguration.current
    val factor = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp, cfg.screenHeightDp)
    Hint("Oberfläche aktuell ×" + String.format(java.util.Locale.US, "%.2f", factor) + " (Bildschirm " + cfg.screenWidthDp + " dp breit).")
    Hint("Standard ist 85 %: mehr Inhalt, Elemente bleiben gut lesbar. „Automatisch“ vergrößert die App auf sehr breiten Bildschirmen.")
    Hint("Animationen „Aus“ schaltet die Vergrößerung beim Fokus ab, „Dezent“ macht sie schwächer. Das schont schwache Geräte.")

    SectionTitle("Bildschirm anpassen")
    var calibrate by remember { mutableStateOf(false) }
    PillButton("Bildschirmrand einstellen", light = true, icon = AIcon.Sliders) { calibrate = true }
    Hint("Zeigt einen Rahmen mit vier Ecken am Fernseher: Stelle den Rand ein, bis alle vier Ecken sichtbar sind. Hilft, wenn dein Fernseher Menü oder Texte am Rand abschneidet.")
    if (calibrate) FitCalibration { calibrate = false }
    ToggleRow("Uhr anzeigen", null, General.showClock) { General.setShowClock(ctx, it) }
    SectionTitle("Schriftart")
    General.FONTS.indices.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val n = General.FONTS[i]
                ChoiceChip(n, General.fontIdx == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.font = i } }) { General.setFont(ctx, i) }
            }
        }
    }
    Hint("Outfit (Standard) und Inter sind in die App eingebaut, die übrigen Schriften stammen von deinem Gerät.")
    SectionTitle("Schriftgröße")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        General.TEXT_SCALES.forEachIndexed { i, n ->
            ChoiceChip(n, General.textScaleIdx == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.textScale = i } }) { General.setTextScale(ctx, i) }
        }
    }
    Hint("Fünf klare Systemschriften, die auf jedem Gerät vorhanden sind. Die Schriftgröße wirkt zusätzlich zur Anzeigegröße.")
    }
}

@Composable
private fun DiagPage(ctx: Context, reload: () -> Unit) {
    val cfg = LocalConfiguration.current
    val items = PlaylistStore.channels
    val counts = remember(items) { Triple(items.count { it.kind == Kind.LIVE }, items.count { it.kind == Kind.MOVIE }, items.count { it.kind == Kind.SERIES }) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val dens = ctx.resources.displayMetrics.density
    val err = Diag.lastError?.let { it + "  (" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Diag.lastErrorAt)) + ")" } ?: "keiner seit dem Start"
    val lines = listOf(
        "Version" to version,
        "Installiert" to runCatching { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime)) }.getOrDefault("?"),
        "Sprache" to (Lang.code() + " · Index " + Lang.index),
        "Bildschirm" to "${cfg.screenWidthDp} × ${cfg.screenHeightDp} dp · Dichte ${"%.2f".format(java.util.Locale.US, dens)}",
        "Playlist" to (PlaylistStore.lists.getOrNull(PlaylistStore.active)?.name ?: "keine"),
        "Inhalte" to "${counts.first} Sender · ${counts.second} Filme · ${counts.third} Serien",
        "Ladezustand" to (if (PlaylistStore.loading) "lädt …" else PlaylistStore.error ?: "bereit"),
        "Programm (EPG)" to "${Xmltv.status} · ${Xmltv.channelCount} Sender",
        "Metadaten" to (if (MetaRepo.enabled()) (if (Classifier.running) "lädt ${Classifier.progress}/${Classifier.total}" else "aktiv") else "kein TMDB-Schlüssel"),
        "Speicher" to (Mem.totalMb(ctx).toString() + " MB · " + listOf("wenig", "mittel", "viel")[Mem.tier(ctx)]),
        "Letzter Fehler" to err,
        "Fehler seit Start" to Diag.errorCount.toString(),
        "Bildschirm wach" to (if (ScreenAwake.isOn) Lang.tr("an") + " (" + ScreenAwake.reasons + ")" else Lang.tr("aus")) +
            ScreenAwake.history.joinToString("") {
                "\n" + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it.first)) + "  " +
                    (if (it.second == "aus") Lang.tr("aus") else Lang.tr("an") + it.second.removePrefix("an"))
            },
        "Weiterschauen" to (ResumeLog.also { it.init(ctx) }.lines.take(12).joinToString("\n").ifBlank { "noch nichts gemerkt" }),
        "Letzter Absturz" to (CrashLog.last(ctx)?.let { it.first + "  (" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.second)) + ")" } ?: "keiner")
    )
    lines.forEach { (k, v) ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(k, color = Muted, fontSize = 16.sp, modifier = Modifier.width(170.dp))
            Text(v, color = Color.White, fontSize = 16.sp, modifier = Modifier.widthIn(max = 640.dp))
        }
    }
    // Hint before the buttons: text below the last button never scrolls the list into view (nothing below is selectable)
    Hint("Der Bericht enthält keine Adressen, Benutzernamen oder Passwörter. Er wird nur in die Zwischenablage kopiert und nirgendwohin gesendet.")
    Hint("Auf dem Fernseher lässt sich die Zwischenablage oft nicht nutzen: Unter Einstellungen > Speicher > „Übertragung im WLAN starten“ kannst du den Bericht am Handy öffnen (Seite „Letzten Absturzbericht anzeigen“) und dort kopieren.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Playlist neu laden", light = true, icon = AIcon.Refresh) { reload() }
        PillButton("Bericht kopieren") {
            val text = lines.joinToString("\n") { it.first + ": " + it.second }
            runCatching {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("IPVIO Diagnose", text))
            }
        }
    }
}

@Composable
private fun AudioPage(ctx: Context) {
    TrackSettingsSection(ctx)
    // Round 14: subtitle style with live preview. Highlighting shows the effect immediately in the picture above, OK adopts it.
    Text("Untertitel-Stil", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    var pSize by remember { mutableStateOf<Int?>(null) }
    var pColor by remember { mutableStateOf<Int?>(null) }
    var pBg by remember { mutableStateOf<Int?>(null) }
    var pEdge by remember { mutableStateOf<Int?>(null) }
    SubtitlePreview(pSize ?: AppSettings.subSize, pColor ?: AppSettings.subColor, pBg ?: AppSettings.subBg, pEdge ?: AppSettings.subEdge)
    fun setSub(size: Int = AppSettings.subSize, color: Int = AppSettings.subColor, bg: Int = AppSettings.subBg, edge: Int = AppSettings.subEdge) =
        AppSettings.setSub(ctx, size, color, bg, edge)
    SubChoiceRow("Größe", SubStyle.SIZES.map { it.first }, AppSettings.subSize, { i -> pSize = i }) { setSub(size = it) }
    SubChoiceRow("Farbe", SubStyle.COLORS.map { it.first }, AppSettings.subColor, { i -> pColor = i }) { setSub(color = it) }
    SubChoiceRow("Hintergrund", SubStyle.BGS.map { it.first }, AppSettings.subBg, { i -> pBg = i }) { setSub(bg = it) }
    SubChoiceRow("Rand", SubStyle.EDGES, AppSettings.subEdge, { i -> pEdge = i }) { setSub(edge = it) }
    Hint("Rand: „Schatten“ legt einen weichen dunklen Schatten unter die Schrift, „Kontur“ zeichnet einen schwarzen Rand rundherum (besser lesbar auf hellem Bild), „Kein“ lässt die Schrift pur.")
}

/** A row of choice fields; [onProbe] gets the highlighted entry (null = none highlighted). */
@Composable
private fun SubChoiceRow(title: String, names: List<String>, selected: Int, onProbe: (Int?) -> Unit, onPick: (Int) -> Unit) {
    Text(title, color = Muted, fontSize = 15.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, n ->
            ChoiceChip(n, selected == i, onFocus = { on -> if (on) onProbe(i) else onProbe(null) }) { onPick(i) }
        }
    }
}

/** Small "TV picture" with a subtitle line in the chosen style. */
@Composable
private fun SubtitlePreview(size: Int, color: Int, bg: Int, edge: Int) {
    val shape = RoundedCornerShape(DS.RadiusM)
    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF8FA3B8), Color(0xFF3B4A5C), Color(0xFFB89B72))))
            .border(1.dp, DS.Overlay3, shape),
        contentAlignment = Alignment.BottomCenter
    ) {
        AppIcon(AIcon.Play, Color(0x33FFFFFF), Modifier.align(Alignment.Center), 46.dp)
        // Shadow = dark soft shadow to the lower right; outline = black rim all around (drawn as offset copies), so both look different
        val shadow = if (edge == 1) androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(4f, 4f), 6f) else null
        val txt = "So sehen deine Untertitel aus"
        val fs = (20 * SubStyle.SIZES[size.coerceIn(0, SubStyle.SIZES.lastIndex)].second).sp
        Box(
            Modifier.padding(bottom = 16.dp).background(Color(SubStyle.BGS[bg.coerceIn(0, SubStyle.BGS.lastIndex)].second.toInt()))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            if (edge == 2) for ((dx, dy) in OUTLINE_OFFSETS) Text(txt, color = Color.Black, fontSize = fs, modifier = Modifier.offset(dx.dp, dy.dp))
            Text(
                txt, color = Color(SubStyle.COLORS[color.coerceIn(0, SubStyle.COLORS.lastIndex)].second.toInt()), fontSize = fs,
                style = androidx.compose.ui.text.TextStyle(shadow = shadow)
            )
        }
    }
}

private val OUTLINE_OFFSETS = listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f, 1f to 1f, -1f to -1f, 1f to -1f, -1f to 1f)

@Composable
private fun FilterPage(ctx: Context, onLanguages: () -> Unit) {
    ToggleRow("Automatische Genres", "Aus = Kategorien deines Anbieters (Standard). Umschalten geht auch direkt in der Ansicht „Kategorien“.", AppSettings.ipvioFilter) { AppSettings.set(ctx, it) }
    val all = PlaylistStore.channels
    val vod = remember(all) { all.count { it.kind != Kind.LIVE } }
    if (!Classifier.supported(vod)) Hint("Deine Bibliothek ist für automatische Genres zu groß (über 60.000 Filme und Serien). Es werden die Kategorien deines Anbieters verwendet. Blende unter „Playlists“ > „Sprachen der Inhalte“ Sprachen aus, die du nicht brauchst – dann passt es wieder.")
    Hint("Automatische Genres sortieren Titel nach Genre (Anime = Animation aus Japan). Beim ersten Mal dauert das einige Minuten, danach ist alles gespeichert. Aus = die Kategorien deines Anbieters in der Reihenfolge deines Anbieters. In der Ansicht „Kategorien“ kannst du jederzeit zwischen beiden wechseln.")
    if (!MetaRepo.enabled()) Text("Automatische Genres brauchen einen TMDB-Schlüssel (Einstellungen > API-Schlüssel).", color = DS.Danger, fontSize = 15.sp)
    if (Classifier.running) Text("${Classifier.phase} werden geladen … ${Classifier.progress} / ${Classifier.total}", color = Accent2, fontSize = 15.sp)
    SectionTitle("Sprachen der Inhalte")
    Hint("Welche Sprachen geladen werden, stellst du unter „Playlists“ ein – das bestimmt, was aus der Playlist geladen wird.")
    PillButton("Sprachen der Inhalte öffnen", icon = AIcon.Globe) { onLanguages() }
    IgnoredGroupsSection(ctx)
    CategoryFilterSection(ctx)
}

/**
 * Categories of movies, series and Live TV (Wishbox): hide single categories, or switch all off and only the wanted ones
 * back on (as in TiviMate). Uses the "ignore" store, so hidden categories disappear everywhere without reloading.
 * Each type is collapsed at first: a playlist can have hundreds of categories.
 */
@Composable
private fun CategoryFilterSection(ctx: Context) {
    IgnoredGroups.init(ctx)
    val ver = IgnoredGroups.version
    val all = PlaylistStore.channels
    val groups by produceState<Map<Kind, List<Pair<String, Int>>>>(emptyMap(), all.size, PlaylistStore.langCounts) {
        value = withContext(Dispatchers.Default) {
            val raw = PlaylistStore.unfilteredChannels()
            val out = LinkedHashMap<Kind, List<Pair<String, Int>>>()
            for (k in listOf(Kind.MOVIE, Kind.SERIES, Kind.LIVE)) {
                val m = HashMap<String, Int>()
                for (i in raw) if (i.kind == k && i.group.isNotBlank()) m[i.group] = (m[i.group] ?: 0) + 1
                out[k] = m.entries.sortedBy { it.key.lowercase() }.map { it.key to it.value }
            }
            out
        }
    }
    if (groups.values.all { it.isEmpty() }) return
    SectionTitle("Kategorien ein- und ausblenden")
    Hint("Blende einzelne Kategorien aus oder schalte alle aus und nur die gewünschten wieder ein. Ausgeblendete Kategorien verschwinden sofort überall, ohne die Playlist neu zu laden.")
    val names = mapOf(Kind.MOVIE to "Filme", Kind.SERIES to "Serien", Kind.LIVE to "Live-TV")
    var open by remember { mutableStateOf<Kind?>(null) }
    for ((kind, list) in groups) {
        if (list.isEmpty()) continue
        val keys = remember(list) { list.map { IgnoredGroups.groupKey(kind, it.first) } }
        val off = remember(keys, ver) { keys.count { IgnoredGroups.has(it) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                Lang.tr(names.getValue(kind)) + ": " + (list.size - off) + " / " + list.size,
                icon = if (open == kind) AIcon.Up else AIcon.Down, raw = true
            ) { open = if (open == kind) null else kind }
            if (open == kind) {
                PillButton("Alle aus") { IgnoredGroups.setMany(ctx, keys, true) }
                PillButton("Alle ein") { IgnoredGroups.setMany(ctx, keys, false) }
            }
        }
        if (open == kind) for ((g, n) in list) {
            val key = IgnoredGroups.groupKey(kind, g)
            ToggleRow(g, n.toString() + " " + Lang.tr("Titel"), !IgnoredGroups.has(key)) { on -> IgnoredGroups.set(ctx, key, !on) }
        }
    }
    if (ver < 0) Spacer(Modifier.height(0.dp))
}

/**
 * "Ignore": bundle titles by the provider's tag ("TR", "EN", "AR" …) and switch whole bundles on or off.
 * Switched-off bundles disappear everywhere, the playlist does not have to be reloaded for that. Complements the language filter.
 */
@Composable
private fun IgnoredGroupsSection(ctx: Context) {
    IgnoredGroups.init(ctx)
    val ver = IgnoredGroups.version
    val all = PlaylistStore.channels
    // Counting over the whole (unfiltered) list in the background
    val stats by produceState<Map<Kind, List<Pair<String, Int>>>>(emptyMap(), all.size, PlaylistStore.langCounts) {
        value = withContext(Dispatchers.Default) {
            val raw = PlaylistStore.unfilteredChannels()
            val out = LinkedHashMap<Kind, List<Pair<String, Int>>>()
            for (k in listOf(Kind.MOVIE, Kind.SERIES, Kind.LIVE)) {
                val m = HashMap<String, Int>()
                for (i in raw) if (i.kind == k) { val t = IgnoredGroups.tagOf(i); m[t] = (m[t] ?: 0) + 1 }
                out[k] = m.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ it.key.isEmpty() }, { -it.value })).take(40).map { it.key to it.value }
            }
            out
        }
    }
    SectionTitle("Gruppen ignorieren")
    Hint("Titel und Sender sind nach der Kennung deines Anbieters gebündelt (z. B. „TR |“, „[EN]“, „AR:“). Schalte Bündel aus, die du nicht brauchst: Sie verschwinden sofort überall, ohne die Playlist neu zu laden. Ausgeschaltet heißt ignoriert. Hilft dort, wo der Sprachfilter eine Kennung nicht erkennt.")
    if (stats.isEmpty() || stats.values.all { it.isEmpty() }) { Hint("Noch keine Playlist geladen."); return }
    if (!IgnoredGroups.isEmpty) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Alle wieder einschalten", icon = AIcon.Check) { IgnoredGroups.clear(ctx) }
            Text(Lang.tr("Ignoriert") + ": " + IgnoredGroups.size, color = Muted, fontSize = 14.sp, raw = true)
        }
    }
    val names = mapOf(Kind.MOVIE to "Filme", Kind.SERIES to "Serien", Kind.LIVE to "Live-TV")
    for ((kind, list) in stats) {
        if (list.isEmpty()) continue
        SectionTitle(names.getValue(kind))
        for ((tag, n) in list) {
            val key = IgnoredGroups.bundleKey(kind, tag)
            ToggleRow(
                if (tag.isEmpty()) "Ohne Kennung" else tag,
                n.toString() + " Titel",
                !IgnoredGroups.has(key)
            ) { on -> IgnoredGroups.set(ctx, key, !on) }
        }
    }
    if (ver < 0) Spacer(Modifier.height(0.dp))
}

/**
 * Language filter: languages of the playlist with counts. Selection first as a draft (tick several languages on/off),
 * loading happens only with "Apply" – otherwise a new parse would start after every tick.
 */
@Composable
private fun LanguageFilterSection(ctx: Context) {
    val counts = PlaylistStore.langCounts
    val list = remember(counts) {
        counts.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ it.key == LangTagLogic.UNKNOWN }, { -it.value })).map { it.key to it.value }
    }
    val hidden = LangFilter.hidden
    var draft by remember(hidden) { mutableStateOf(hidden) }
    var warn by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    val changed = draft != hidden
    fun edit(h: Set<String>) { draft = h; warn = false }
    fun apply() {
        // Never hide everything: the playlist would be empty and you could hardly get back
        if (list.isNotEmpty() && list.all { it.first in draft }) { warn = true; big = false; return }
        warn = false
        // Very large selection: allowed, but with a notice (if memory gets too tight, the old selection stays active)
        big = !PlaylistStore.fits(draft)
        LangFilter.setAndApply(ctx, draft)
    }
    SectionTitle("Sprachen der Inhalte")
    Hint("Blende Sprachen aus, die du nicht brauchst – sie verschwinden dann überall: Startseite, Filme, Serien, Live-TV und Suche. Erkannt wird die Kennzeichnung deines Anbieters (z. B. „DE |“, „[TR]“, „GERMAN“). Das ist bei IPTV-Anbietern die Sprache der Tonspur, nicht das Herkunftsland des Films.")
    if (list.isEmpty()) { Hint("Noch keine Playlist geladen."); return }
    Hint("Ausgeblendete Sprachen werden gar nicht erst geladen. Das spart viel Arbeitsspeicher und macht große Playlists schneller.")
    if (PlaylistStore.loading) {
        val t = PlaylistStore.totalBytes
        val pct = if (t > 0) "  " + (PlaylistStore.bytesRead * 100 / t).coerceIn(0, 100) + " %" else ""
        Text(Lang.tr("Sprachauswahl wird angewendet …") + pct, color = Accent2, fontSize = 15.sp, raw = true)
    } else if (PlaylistStore.error == PlaylistStore.MSG_TOO_BIG) Text(PlaylistStore.MSG_TOO_BIG, color = DS.Danger, fontSize = 15.sp)
    if (big) Text("Diese Auswahl ist sehr groß für dieses Gerät – das Laden kann lange dauern.", color = DS.Gold, fontSize = 15.sp)
    val mine = Lang.code()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Nur meine Sprache") { edit(list.map { it.first }.filter { it != mine && it != LangTagLogic.UNKNOWN }.toSet()) }
        PillButton("Alle an") { edit(emptySet()) }
        PillButton("Alle aus") { edit(list.map { it.first }.toSet()) }
    }
    @Composable
    fun ApplyRow() {
        if (warn) Text("Mindestens eine Sprache muss sichtbar bleiben.", color = DS.Danger, fontSize = 15.sp)
        if (changed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Übernehmen und laden", light = true, icon = AIcon.Check) { apply() }
            PillButton("Verwerfen") { edit(hidden) }
            val n = list.count { it.first !in draft }
            Text("$n von ${list.size} Sprachen gewählt", color = Muted, fontSize = 14.sp)
        }
    }
    ApplyRow()
    for ((code, n) in list) {
        ToggleRow(
            if (code == LangTagLogic.UNKNOWN) "Ohne Kennung" else LangTagLogic.name(code),
            n.toString() + " Titel",
            code !in draft
        ) { edit(if (code in draft) draft - code else draft + code) }
    }
    // After a long list: Apply also at the bottom so you do not have to scroll back
    if (list.size > 4) ApplyRow()
}

@Composable
private fun LivePage(ctx: Context) {
    var url by remember { mutableStateOf(Xmltv.customUrl(ctx)) }
    val all = PlaylistStore.channels
    val chCount = Xmltv.channelCount
    val counts by produceState(0 to 0, all, chCount) {
        value = withContext(Dispatchers.Default) { val live = all.filter { it.kind == Kind.LIVE }; live.size to live.count { Epg.supported(it) } }
    }
    val xtream = counts.second
    Text("TV-Programm (EPG)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Programm bei $xtream von ${counts.first} Sendern möglich", color = Muted, fontSize = 15.sp)
    Text("XMLTV: ${Xmltv.status}", color = Accent2, fontSize = 15.sp)
    ToggleRow("Sender-Qualitäten bündeln", "Derselbe Sender in 8K, 4K, FHD, HD und SD erscheint als eine Zeile. OK startet die beste Qualität, die anderen wählst du im Menü des Senders (OK gedrückt halten).", General.bundleLive) { General.setBundleLive(ctx, it) }
    Hint("Die App sucht die Programmdaten automatisch (Xtream-Schnittstelle oder XMLTV-Adresse aus der Playlist). Wenn dein Anbieter eine eigene XMLTV-Adresse nennt, trage sie hier ein.")
    Field(url, { url = it }, "https://…/xmltv.php?username=…&password=…")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Speichern & laden", light = true) {
            Xmltv.setCustom(ctx, url)
            Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels)
        }
        PillButton("Neu laden") { Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels) }
    }
    Spacer(Modifier.height(4.dp))
    Text("Favoriten: ${Library.favs.size} Sender · ${CatFavs.list.size} Kategorien", color = Color.White, fontSize = 17.sp)
    Hint("Sender als Favorit: auf dem Sender OK gedrückt halten > „Zu Favoriten“. Kategorie als Favorit: auf der Kategorie OK gedrückt halten.")
    ChannelListsSection(ctx)
    CatchupSection()
    LiveBufferSection(ctx)
    ReminderSettingsSection(ctx)
}

@Composable
private fun PlaylistPage(ctx: Context, onAdd: () -> Unit, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    // Editing the backup servers of an Xtream playlist
    var editingB by remember { mutableIntStateOf(-1) }
    var backups by remember { mutableStateOf(backupList(emptyList())) }
    var backupErr by remember { mutableStateOf<String?>(null) }
    PlaylistStore.savedUrl(ctx)
    if (Secrets.plainFallback) Hint("Dieses Gerät hat keinen sicheren Schlüsselspeicher: Playlist-Adressen mit Zugangsdaten werden unverschlüsselt gespeichert.")
    Text("Meine Playlists (${PlaylistStore.lists.size})", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (PlaylistStore.lists.size > 1) Hint("Die Hauptliste ist die Playlist, mit der die App arbeitet. „Zusätzlich anzeigen“ blendet eine weitere Playlist dazu ein; sie ersetzt die Hauptliste nicht.")
    PlaylistStore.lists.forEachIndexed { i, pl ->
        val isActive = i == PlaylistStore.active
        val shown = PlaylistStore.isShown(i)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(if (isActive) DS.Overlay2 else Color(0x12FFFFFF)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(pl.name + if (isActive) "  ·  " + Lang.tr("Hauptliste") else if (shown) "  ·  " + Lang.tr("zusätzlich angezeigt") else "", color = if (isActive || shown) Accent2 else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true)
                Text(PlaylistStore.hostOf(pl.url), color = Muted, fontSize = 13.sp, maxLines = 1)
                val nb = if (editingB == i) 0 else PlaylistStore.backups(ctx, pl.url).size
                if (nb > 0) Text("Backup-Server: $nb", color = Muted, fontSize = 13.sp, maxLines = 1)
                val down = PlaylistStore.downServers(ctx, pl.url)
                if (down.isNotEmpty()) Text("Nicht erreichbar: " + down.joinToString(", "), color = Color(0xFFFF8A80), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!isActive) SmallPill(if (shown) "Nicht mehr zusätzlich anzeigen" else "Zusätzlich anzeigen") {
                PlaylistStore.setShown(ctx, i, !shown)
                scope.launch { PlaylistStore.applyMulti(ctx) }
            }
            if (!isActive) SmallPill("Zur Hauptliste machen") {
                PlaylistStore.activate(ctx, i)
                scope.launch { PlaylistStore.load(ctx, pl.url, false) }
            }
            // Content mode (Wishbox): Live TV + VOD / Live TV only / VOD only, cycles on OK
            val mode = remember(PlaylistMode.version, pl.url) { PlaylistMode.get(ctx, pl.url) }
            SmallPill(PlaylistMode.labels[mode]) {
                PlaylistMode.set(ctx, pl.url, (mode + 1) % PlaylistMode.labels.size)
                // Parsed again from the stored list without network (the mode is applied while loading, saves memory)
                scope.launch { if (!PlaylistStore.reapplyLanguages(ctx)) PlaylistStore.applyMode(ctx) }
            }
            SmallPill("Umbenennen") { editing = i; name = pl.name }
            if (XtreamLogic.fromUrl(pl.url) != null) SmallPill("Backup-Server") {
                val b = PlaylistStore.backups(ctx, pl.url)
                editingB = i; backups = backupList(b); backupErr = null
            }
            SmallPill("Löschen") {
                val wasActive = isActive
                editingB = -1
                PlaylistStore.remove(ctx, i)
                if (PlaylistStore.lists.isEmpty()) onAdd()
                else if (wasActive) {
                    val u = PlaylistStore.lists[PlaylistStore.active].url
                    scope.launch { PlaylistStore.load(ctx, u, false) }
                }
            }
        }
    }
    if (PlaylistStore.lists.size > 1) Hint("„Zusätzlich anzeigen“ zeigt eine Playlist zusätzlich zur aktiven: Filme und Serien erscheinen zusammen (doppelte Filme nur einmal, gleiche Serien als eine Serie mit mehreren Quellen). In Filme und Serien filterst du über „Quelle“, im Live TV wechselst du die Quelle mit dem Knopf oben. Jede weitere Playlist braucht zusätzlich Speicher.")
    PlaylistStore.extraError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
    if (editing >= 0) {
        Field(name, { name = it }, "Name der Playlist")
        PillButton("Name speichern", light = true) { PlaylistStore.rename(ctx, editing, name); editing = -1 }
    }
    if (editingB in PlaylistStore.lists.indices) {
        XtreamLogic.fromUrl(PlaylistStore.lists[editingB].url)?.base?.let { main ->
            Text("Hauptserver: $main", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HostHealthLine(main)
        }
        BackupFields(backups)
        PillButton("+ Backup-Server") { backups.add("") }
        Hint(BACKUP_HINT)
        backupErr?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp) }
        PillButton("Backup-Server speichern", light = true) {
            if (!backupsValid(backups)) backupErr = BACKUP_INVALID
            else { PlaylistStore.setBackups(ctx, PlaylistStore.lists[editingB].url, backups.toList()); editingB = -1 }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("+ Weitere Playlist hinzufügen", light = true) { onAdd() }
        PillButton("Aktive Playlist neu laden", icon = AIcon.Refresh) { reload() }
    }
    LanguageFilterSection(ctx)
    SectionTitle("Automatisch aktualisieren")
    val modes = listOf("Bei jedem Start", "Alle paar Stunden", "Täglich", "Wöchentlich", "Nur von Hand")
    SelectRow("Playlist aktualisieren", modes[General.refreshMode], modes, General.refreshMode) { General.setPlan(ctx, mode = it) }
    if (General.refreshMode == 1) {
        val ivs = listOf(3, 6, 12, 24, 48)
        SelectRow("Abstand", "alle ${General.refreshInterval} Stunden", ivs.map { "alle $it Stunden" }, ivs.indexOf(General.refreshInterval).coerceAtLeast(0)) {
            General.setPlan(ctx, interval = ivs[it])
        }
    }
    if (General.refreshMode == 2 || General.refreshMode == 3) {
        if (General.refreshMode == 3) {
            val names = java.text.DateFormatSymbols(Lang.locale()).weekdays
            val dows = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
                java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
            SelectRow("Wochentag", names[General.refreshDow], dows.map { names[it] }, dows.indexOf(General.refreshDow).coerceAtLeast(0)) {
                General.setPlan(ctx, dow = dows[it])
            }
        }
        val hours = (0..23).map { String.format("%02d", it) }
        SelectRow("Stunde", hours[General.refreshHour], hours, General.refreshHour) { General.setPlan(ctx, hour = it) }
        val mins = listOf(0, 15, 30, 45)
        SelectRow("Minute", String.format("%02d", General.refreshMinute), mins.map { String.format("%02d", it) }, mins.indexOf(General.refreshMinute).coerceAtLeast(0)) {
            General.setPlan(ctx, minute = mins[it])
        }
    }
    val nowMs = System.currentTimeMillis()
    Text(
        "Zuletzt aktualisiert: " + (if (General.lastRefresh > 0) dayLabel(General.lastRefresh / 1000) + " " + hhmm(General.lastRefresh / 1000) else "noch nie"),
        color = Muted, fontSize = 14.sp
    )
    SettingsLogic.nextDue(General.plan(), General.lastRefresh, nowMs, General.zone())?.let {
        Text("Nächste Aktualisierung: " + dayLabel(it / 1000) + " " + hhmm(it / 1000), color = Accent2, fontSize = 14.sp)
    }
    Hint("„Bei jedem Start“ lädt die Playlist bei jedem Öffnen der App. Die geplanten Varianten laufen auch im Hintergrund, solange das Gerät eingeschaltet und online ist, und holen verpasste Termine beim nächsten Start nach. „Nur von Hand“ nutzt immer den gespeicherten Stand; Neu laden geht jederzeit mit dem Knopf oben. Die Uhrzeit richtet sich nach deiner Zeitzone unter „Sprache & Region“.")
    Hint("Läuft gerade ein Film oder Sender, wartet die Aktualisierung, bis du fertig bist – so ruckelt nichts.")
    Hint("Zugangsdaten werden nur auf diesem Gerät gespeichert und hier nicht angezeigt. Es ist immer eine Playlist aktiv. Mit „Zur Hauptliste machen“ wechselst du die Liste.")
}

@Composable
private fun ProfilesPage(ctx: Context, onExit: () -> Unit) {
    var editing by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    Profiles.init(ctx)
    // Locked profile (parental control with PIN): switching and editing profiles only after PIN entry
    var unlocked by remember { mutableStateOf(!Parental.profileLocked(ctx, Profiles.active)) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    if (!unlocked) {
        PinPad(
            "PIN eingeben", "Dieses Profil ist durch den Jugendschutz gesperrt. Profile wechseln und bearbeiten geht nur mit der PIN.", pinErr,
            { if (Parental.verifyFor(ctx, Profiles.active, it)) { unlocked = true; pinErr = null } else pinErr = Parental.pinError() },
            { onExit() }
        )
        return
    }
    Text("Aktiv: " + Profiles.activeName(), color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Hint("Jedes Profil hat eigenen Verlauf, eigene Favoriten, Ansicht, Player-Einstellungen und eigenen Jugendschutz. Playlist, Sprache und Aktualisierung sind für alle gleich.")
    Profiles.list.toList().forEach { p ->
        val isActive = p.id == Profiles.active
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(if (isActive) DS.Overlay2 else Color(0x12FFFFFF)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.foundation.text.BasicText(
                Profiles.displayName(p) + if (isActive) "  ·  " + Lang.tr("aktiv") else "",
                modifier = Modifier.weight(1f),
                style = androidx.compose.ui.text.TextStyle(color = if (isActive) Accent2 else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!isActive) SmallPill("Wechseln") { Profiles.switchTo(ctx, p.id) }
            SmallPill("Umbenennen") { editing = p.id; name = p.name }
            if (!isActive && p.id != 0) ConfirmPill("Löschen") { Profiles.remove(ctx, p.id) }
        }
    }
    if (editing >= 0) {
        Field(name, { name = it }, "Name des Profils")
        PillButton("Name speichern", light = true) { Profiles.rename(ctx, editing, name); editing = -1 }
    }
    if (Profiles.list.size < Profiles.MAX) PillButton("Profil hinzufügen") { Profiles.add(ctx) }
    ToggleRow("Beim Start fragen: Wer schaut?", "Zeigt beim Öffnen der App die Profilauswahl, sobald es mehr als ein Profil gibt.", Profiles.askAtStart) { Profiles.setAsk(ctx, it) }
    Hint("Beim Wechseln startet die App kurz neu.")
}

/**
 * Own API keys (open source: no key is built in). Without keys the app works, but shows
 * only what the playlist delivers.
 */
@Composable
private fun ApiKeysPage(ctx: Context) {
    val scope = rememberCoroutineScope()
    var tmdb by remember { mutableStateOf(Config.ownTmdb(ctx)) }
    var omdb by remember { mutableStateOf(Config.ownOmdb(ctx)) }
    var tmdbStatus by remember { mutableStateOf("") }
    var omdbStatus by remember { mutableStateOf("") }
    var osKey by remember { mutableStateOf(Config.OS_KEY) }
    var sdKey by remember { mutableStateOf(Config.SD_KEY) }
    var osStatus by remember { mutableStateOf("") }
    var sdStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Hint("Die App enthält keine eigenen Schlüssel. Mit deinen eigenen, kostenlosen Schlüsseln zeigt sie Poster, Beschreibungen, Besetzung, Trailer, automatische Genres sowie IMDb- und Rotten-Tomatoes-Wertungen. Ohne Schlüssel übernimmt sie Beschreibung, Cover und Bewertung von deinem Anbieter (bei Xtream-Zugängen).")

    SectionTitle("TMDB (Film-Infos)")
    Field(tmdb, { tmdb = it; tmdbStatus = "" }, "TMDB API-Schlüssel (v3)")
    Hint("Kostenlos unter themoviedb.org: Konto anlegen, anmelden, dann Einstellungen > API > API-Schlüssel anfordern und den 32-stelligen „API Key“ eintragen. Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert.")
    if (tmdbStatus.isNotBlank()) Text(tmdbStatus, color = if (tmdbStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)

    SectionTitle("OMDb (IMDb- und Rotten-Tomatoes-Wertung)")
    Field(omdb, { omdb = it; omdbStatus = "" }, "OMDb API-Schlüssel")
    Hint("Kostenlos unter omdbapi.com > API Key (FREE). Danach den Aktivierungslink in der E-Mail anklicken, sonst funktioniert der Schlüssel nicht. Geht auch ohne TMDB-Schlüssel.")
    if (omdbStatus.isNotBlank()) Text(omdbStatus, color = if (omdbStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)

    SectionTitle("Untertitel online suchen")
    Field(osKey, { osKey = it; osStatus = "" }, "OpenSubtitles API-Schlüssel")
    Hint("Kostenlos unter opensubtitles.com: Konto anlegen, dann unter „API consumers“ eine App anlegen und den API-Schlüssel eintragen. Ohne Anmeldung sind nur wenige Downloads pro Tag möglich.")
    if (osStatus.isNotBlank()) Text(osStatus, color = if (osStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)
    Field(sdKey, { sdKey = it; sdStatus = "" }, "SubDL API-Schlüssel")
    Hint("Kostenlos unter subdl.com > Konto > API. Im Player unter „Audio & Untertitel“ > „Untertitel online suchen“ werden beide Dienste gefragt.")
    if (sdStatus.isNotBlank()) Text(sdStatus, color = if (sdStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)

    Hint("Die Schlüssel bleiben nur auf diesem Gerät. Nach dem Eintragen die App einmal neu starten, damit alle Bereiche die Infos laden. Intro- und Abspann-Zeiten kommen von TheIntroDB und brauchen keinen Schlüssel.")
    // Buttons last: text below the last focusable element was cut off at the bottom of the page
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(if (busy) "Bitte warten …" else "Speichern und prüfen", light = true, icon = AIcon.Check) {
            if (busy) return@PillButton
            busy = true
            Config.save(ctx, tmdb, omdb)
            Config.saveSubs(ctx, osKey, sdKey)
            tmdb = Config.ownTmdb(ctx); omdb = Config.ownOmdb(ctx); osKey = Config.OS_KEY; sdKey = Config.SD_KEY
            scope.launch {
                tmdbStatus = if (tmdb.isEmpty()) "" else KeyCheck.tmdb(tmdb) ?: "Schlüssel gültig"
                omdbStatus = if (omdb.isEmpty()) "" else KeyCheck.omdb(omdb) ?: "Schlüssel gültig"
                osStatus = if (osKey.isEmpty()) "" else KeyCheck.openSubtitles(osKey) ?: "Schlüssel gültig"
                sdStatus = if (sdKey.isEmpty()) "" else KeyCheck.subdl(sdKey) ?: "Schlüssel gültig"
                busy = false
            }
        }
        if (tmdb.isNotEmpty() || omdb.isNotEmpty() || osKey.isNotEmpty() || sdKey.isNotEmpty()) ConfirmPill("Schlüssel entfernen") {
            tmdb = ""; omdb = ""; tmdbStatus = ""; omdbStatus = ""; Config.save(ctx, "", "")
            osKey = ""; sdKey = ""; osStatus = ""; sdStatus = ""; Config.saveSubs(ctx, "", "")
        }
    }
}

@Composable
private fun AboutPage(ctx: Context) {
    Text("IPVIO Stream · Version " + appVersion(ctx), color = Color.White, fontSize = 18.sp)
    Hint("IPVIO Stream ist ein Player und liefert selbst keine Inhalte. Nutze nur Quellen, für die du eine Berechtigung hast.")
    Hint("Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert. Wertungen: OMDb. Intro-Zeiten: TheIntroDB.")
    Hint("IPVIO Stream ist freie Software unter der GNU General Public License v3 (GPL-3.0). Verwendete Bibliotheken: AndroidX/Jetpack Compose, Media3 (Apache 2.0), OkHttp, Coil (Apache 2.0), Jellyfin FFmpeg-Decoder (GPL-3.0), Schriften Outfit und Inter (SIL Open Font License).")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Systemeinstellungen der App") {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                )
            }
        }
    }
    UpdateSection(ctx)
}

private fun settingsToast(ctx: Context, message: String) {
    android.widget.Toast.makeText(ctx, Lang.tr(message), android.widget.Toast.LENGTH_SHORT).show()
}

/** "Reset all settings": its own settings tab. The reset only runs after the confirmation dialog. */
@Composable
private fun ResetAllPage(ctx: Context) {
    var ask by remember { mutableStateOf(false) }
    Hint("Resets all settings to their defaults: view, design, menu, player, sound, genres, Live TV and more. Playlists, favorites, history, resume positions and the parental-control PIN are kept. Clear the cache under Storage.")
    PillButton("Alle Einstellungen zurücksetzen", light = true, icon = AIcon.Warning) { ask = true }
    Hint("Reset just one page? Every settings page has its own reset button at the bottom.")
    if (ask) ResetAllDialog(onCancel = { ask = false }) {
        ask = false
        SettingsReset.all(ctx)
        settingsToast(ctx, "Alle Einstellungen wurden zurückgesetzt.")
    }
}

/** Confirmation before everything is reset. Focus starts on "Abbrechen", so a stray OK press cannot reset anything. */
@Composable
private fun ResetAllDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { cancelFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(DS.RadiusL)
        Column(
            Modifier.width(560.dp).clip(shape).background(Surf).border(1.dp, DS.Overlay3, shape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Alle Einstellungen zurücksetzen?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Alle Einstellungen gehen auf die Standardwerte zurück. Playlists, Favoriten, Verlauf und Fortsetzen-Positionen bleiben erhalten. Das lässt sich nicht rückgängig machen.",
                color = Muted, fontSize = DS.TextSmall, lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Abbrechen", Modifier.focusRequester(cancelFocus), light = true) { onCancel() }
                PillButton("Zurücksetzen") { onConfirm() }
            }
        }
    }
}

/** App update from within the app: the default is the latest release on GitHub. */
@Composable
private fun UpdateSection(ctx: Context) {
    SectionTitle("App-Aktualisierung")
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(AppUpdate.auto(ctx)) }
    val info = AppUpdate.available
    val scope = rememberCoroutineScope()
    Text("Installiert: Version " + AppUpdate.currentName(ctx), color = Muted, fontSize = 16.sp)
    ToggleRow("Automatisch nach Updates suchen", "Einmal am Tag. Ein neues Update erscheint in der Glocke oben rechts.", auto) { auto = it; AppUpdate.setAuto(ctx, it) }
    Hint("Die Schlüssel bleiben nur auf diesem Gerät. Nach dem Eintragen die App einmal neu starten, damit alle Bereiche die Infos laden. Intro- und Abspann-Zeiten kommen von TheIntroDB und brauchen keinen Schlüssel.")
    // Buttons last: text below the last focusable element was cut off at the bottom of the page
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(if (busy) "Bitte warten …" else "Jetzt nach Update suchen") {
            if (busy) return@PillButton
            busy = true; status = "Suche …"
            scope.launch {
                AppUpdate.check(ctx)
                    .onSuccess { r -> status = if (r == null) "Du hast die neueste Version." else "Neu: Version ${r.name}" + (if (r.notes.isNotBlank()) " – " + r.notes else "") }
                    .onFailure { status = it.message ?: "Fehler" }
                busy = false
            }
        }
        info?.let { u ->
            PillButton("Herunterladen und installieren") {
                if (busy) return@PillButton
                busy = true
                scope.launch { AppUpdate.downloadAndInstall(ctx, u) { status = it }; busy = false }
            }
        }
    }
    if (status.isNotBlank()) Text(status, color = Muted, fontSize = 16.sp)
    Hint("Updates kommen von der Projektseite auf GitHub. Deine Playlists, Favoriten, der Verlauf und die Einstellungen bleiben bei einem Update erhalten. Android fragt vor der Installation einmal nach.")
    var url by remember { mutableStateOf(AppUpdate.url(ctx).takeIf { it != AppUpdate.DEFAULT_URL }.orEmpty()) }
    Field(url, { url = it; AppUpdate.setUrl(ctx, it) }, "Eigene Update-Adresse (leer = GitHub)")
}

