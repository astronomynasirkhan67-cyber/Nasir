package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/**
 * Loading screen when adding a playlist – deliberately kept calm:
 * dark background, the logo fades in gently and barely noticeably "breathes", below it a fine progress line.
 * If the server knows the size, the line shows the exact state, otherwise a soft light streak glides slowly through.
 * With "Motion: off" everything stays still. If it takes very long: quotes (from 5 min), sayings (from 10 min), hint (from 15 min).
 */
@Composable
fun PlaylistLoadingOverlay() {
    PlaylistStore.langAsk?.let { LangPicker(it); return }
    val moving = Look.motion > 0
    val t = rememberInfiniteTransition(label = "load")
    val breathe by t.animateFloat(0.78f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val sweep by t.animateFloat(-0.3f, 1.05f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "sweep")
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(if (moving) 700 else 0)) }

    val total = PlaylistStore.totalBytes
    val read = PlaylistStore.bytesRead
    val done = PlaylistStore.downloadDone
    val known = total > 0 || done
    val target = when {
        done -> 1f
        total > 0 -> (read.toFloat() / total).coerceIn(0f, 0.99f)
        else -> 0f
    }
    val frac by animateFloatAsState(target, tween(600), label = "frac")
    val line = Accent1.copy(alpha = 0.85f)
    val barW = 360.dp

    // An accidental Back does not cancel: only the second press within 3 s
    var lastBack by remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf(false) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 3000) PlaylistStore.cancel() else { lastBack = now; hint = true }
    }
    LaunchedEffect(lastBack) { if (lastBack > 0) { delay(3000); hint = false } }
    // Bring the focus here so the D-pad does not keep paging invisibly in the background
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { holder.requestFocus() } }

    // Load time for quotes, sayings and hint
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val started = PlaylistStore.loadStarted
    val elapsed = if (started > 0) now - started else 0L
    val fun1 = LoadFun.line(elapsed)
    var hintClosed by remember { mutableStateOf(false) }
    val showHint = LoadFun.stage(elapsed) >= 3 && !hintClosed && !PlaylistStore.askRequested

    Box(
        Modifier.fillMaxSize().background(
            // subtle vignette: center slightly lighter, edges darker – no colored glow
            Brush.radialGradient(listOf(lerp(Bg, Color.White, 0.035f), lerp(Bg, Color.Black, 0.45f)), radius = 1500f)
        ).focusRequester(holder).focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.alpha(enter.value).widthIn(max = 760.dp).padding(horizontal = DS.PageX),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BrandMark(Modifier.size(64.dp).alpha(if (moving) breathe else 1f))
            Spacer(Modifier.height(40.dp))
            Text("Playlist wird geladen", color = Color.White.copy(alpha = 0.92f), fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
            Spacer(Modifier.height(8.dp))
            Crossfade(PlaylistStore.phase, animationSpec = tween(if (moving) 450 else 0), label = "phase") { p ->
                Text(p, color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.height(28.dp))
            // thin progress line
            Box(Modifier.width(barW).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
                if (known) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(50)).background(line))
                } else {
                    val seg = barW * 0.28f
                    val x = if (moving) (barW + seg) * sweep - seg else (barW - seg) / 2
                    Box(
                        Modifier.offset(x = x).width(seg).fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, line, Color.Transparent)))
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.width(barW), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (read > 0) mb(read) else "", color = Muted.copy(alpha = 0.8f), fontSize = 13.sp)
                Text(if (known) ((frac * 100).toInt()).toString() + " %" else "", color = Muted.copy(alpha = 0.8f), fontSize = 13.sp)
            }
            // From 5 minutes: quotes, from 10 minutes: sayings (deliberately in English)
            if (fun1 != null) {
                Spacer(Modifier.height(30.dp))
                Crossfade(fun1, animationSpec = tween(if (moving) 900 else 0), label = "fun") { (text, author) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "“$text”", color = Color.White.copy(alpha = 0.78f), fontSize = 17.sp, fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center, lineHeight = 24.sp, raw = true
                        )
                        if (author != null) Text("— $author", color = Muted.copy(alpha = 0.8f), fontSize = 13.sp, raw = true, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (PlaylistStore.askRequested) {
                Spacer(Modifier.height(18.dp))
                Text("Die Sprachauswahl erscheint, sobald der Download fertig ist.", color = Accent2, fontSize = 14.sp)
            }
            if (showHint) {
                Spacer(Modifier.height(24.dp))
                SlowHint(onChoose = { PlaylistStore.requestLanguageChoice() }, onClose = { hintClosed = true; runCatching { holder.requestFocus() } })
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (hint) "Noch einmal Zurück drücken zum Abbrechen" else "Deine Angaben sind gespeichert – bei einer Unterbrechung geht es beim nächsten Start weiter.",
                color = if (hint) Accent2 else Muted.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
    }
}

/** Hint after 15 minutes: first English, below it in the app's language (if not English). */
@Composable
private fun SlowHint(onChoose: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    val shape = RoundedCornerShape(DS.RadiusL)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surf).border(DS.Border, Accent2.copy(alpha = 0.6f), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Das dauert gerade sehr lange.", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Sehr große Playlists brauchen lange. Mit der Sprachauswahl lädst du nur die Titel, die du wirklich brauchst.",
            color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 21.sp
        )
        if (Lang.code() != "en") Text(
            "Das dauert ungewöhnlich lange. Mit der Sprachauswahl lädst du nur die Titel, die du wirklich brauchst.",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
            FocusButton("Sprachen wählen", Modifier.focusRequester(first), primary = true) { onChoose() }
            FocusButton("Weiter warten") { onClose() }
        }
    }
}

/**
 * Very large playlist: first explain and ask (all languages or select), then the selection.
 * "Load everything" loads the whole playlist in both steps. The device's language is preselected, otherwise English.
 */
@Composable
private fun LangPicker(ask: PlaylistStore.LangAsk) {
    var step by remember(ask) { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(Bg, Color.White, 0.035f), lerp(Bg, Color.Black, 0.45f)), radius = 1500f)),
        contentAlignment = if (step == 0) Alignment.Center else Alignment.TopCenter
    ) {
        if (step == 0) LangIntro(ask, onSelect = { step = 1 })
        else LangSelect(ask, onBack = { step = 0 })
    }
}

/** Step 1: explanation – why the app asks, and the choice between "Select languages" and "Load everything". */
@Composable
private fun LangIntro(ask: PlaylistStore.LangAsk, onSelect: () -> Unit) {
    val nf = remember { NumberFormat.getIntegerInstance(Lang.locale()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    // Back: first a hint, the second time cancel (details stay saved)
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 3000) ask.choose(null) else lastBack = now
    }
    val backHint = System.currentTimeMillis() - lastBack < 3000
    LaunchedEffect(lastBack) { if (lastBack > 0) { delay(3000); lastBack = 0L } }
    Column(
        Modifier.widthIn(max = 760.dp).padding(horizontal = DS.PageX),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BrandMark(Modifier.size(52.dp))
        Text("Welche Inhalte möchtest du sehen?", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "Diese Playlist ist sehr groß: " + nf.format(ask.titles) + " Titel in " + ask.counts.size + " Sprachen.",
            color = Accent2, fontSize = 18.sp
        )
        Text(
            "Wähle die Sprachen, die du sprichst – dann zeigt dir die App nur Filme, Serien und Sender in diesen Sprachen. Das Laden geht deutlich schneller und braucht weniger Speicher. Ändern kannst du das jederzeit unter Einstellungen > Sprachen.",
            color = DS.TextSoft, fontSize = 17.sp, lineHeight = 25.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            FocusButton("Sprachen auswählen", Modifier.focusRequester(first), primary = true) { onSelect() }
            FocusButton("Alles laden") { ask.chooseAll() }
        }
        Text(
            "„Alles laden“ lädt alle " + nf.format(ask.titles) + " Titel. Das kann einige Minuten dauern.",
            color = Muted, fontSize = 14.sp
        )
        Text(
            if (backHint) "Noch einmal Zurück drücken zum Abbrechen" else "",
            color = Accent2, fontSize = 13.sp
        )
    }
}

/** Step 2: languages as a clear grid (largest first, "No tag" last), buttons at the top. */
@Composable
private fun LangSelect(ask: PlaylistStore.LangAsk, onBack: () -> Unit) {
    val nf = remember { NumberFormat.getIntegerInstance(Lang.locale()) }
    val list = remember(ask) {
        ask.counts.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ it.key == LangTagLogic.UNKNOWN }, { -it.value })).map { it.key to it.value }
    }
    var shown by remember(ask) { mutableStateOf(ask.preselect) }
    val fits = ask.fits(shown)
    val go = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { go.requestFocus() } }
    BackHandler { onBack() }
    Column(
        Modifier.widthIn(max = 1000.dp).fillMaxHeight().padding(horizontal = DS.PageX, vertical = DS.PageY),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sprachen auswählen", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Markiere die Sprachen, deren Titel du sehen möchtest. Erkannt wird die Kennzeichnung deines Anbieters (z. B. „DE |“ oder „[TR]“) – bei IPTV ist das die Sprache der Tonspur, nicht das Herkunftsland.",
            color = Muted, fontSize = 15.sp, lineHeight = 21.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusButton("Laden", Modifier.focusRequester(go), primary = true) { if (shown.isNotEmpty()) ask.choose(shown) }
            FocusButton("Alles laden") { ask.chooseAll() }
            Column {
                Text(
                    if (shown.isEmpty()) "Bitte mindestens eine Sprache wählen." else "Gewählt: " + nf.format(ask.titlesOf(shown)) + " Titel",
                    color = if (shown.isEmpty()) DS.Danger else Color.White, fontSize = 15.sp
                )
                if (shown.isNotEmpty()) Text(
                    if (fits) "Passt in den Speicher dieses Geräts." else "Sehr groß für dieses Gerät – das Laden kann lange dauern.",
                    color = if (fits) Accent2 else DS.Gold, fontSize = 13.sp
                )
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.weight(1f),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.first }) { (code, n) ->
                LangTile(code, nf.format(n) + " Titel", code in shown) { shown = if (code in shown) shown - code else shown + code }
            }
        }
    }
}

/** One language: name in the language itself (so everyone recognizes theirs), abbreviation and number of titles. */
@Composable
private fun LangTile(code: String, count: String, checked: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    val unknown = code == LangTagLogic.UNKNOWN
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(shape)
            .background(if (f) RowFocus else if (checked) lerp(Surf, Accent1, 0.18f) else Surf)
            .border(if (checked || f) DS.Border else 1.dp, if (f) Accent2 else if (checked) Accent1 else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(if (checked) Accent1 else DS.Overlay2),
            contentAlignment = Alignment.Center
        ) { if (checked) AppIcon(AIcon.Check, OnAccent, size = 18.dp) }
        Column(Modifier.weight(1f)) {
            if (unknown) Text("Ohne Kennung", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            else Text(LangTagLogic.name(code), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, raw = true)
            Text(count, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (!unknown) Text(code.uppercase(), color = Muted.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, raw = true)
    }
}

private fun mb(b: Long): String =
    if (b < 1_048_576) String.format(Locale.getDefault(), "%d KB", b / 1024)
    else String.format(Locale.getDefault(), "%.1f MB", b / 1_048_576.0)
