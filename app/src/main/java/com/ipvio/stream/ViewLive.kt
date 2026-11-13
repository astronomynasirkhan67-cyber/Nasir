package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/**
 * Live preview of the "View" settings: the real app stays visible (no dimming) and the settings sit in a slim bar at the
 * bottom (or top). One click on a choice applies it at once and the choice is clearly marked, so the home screen, the lists
 * and the sidebar change in front of your eyes with your own titles.
 */
object ViewLive {
    var active by mutableStateOf(false)
    /** Selected sub page of the View settings (0 cards, 1 sidebar, 2 grid, 3 home, 4 categories). Shared with the settings page. */
    var tab by mutableIntStateOf(0)
    /** Bar at the top instead of the bottom. */
    var atTop by mutableStateOf(false)
    /** Card that is shown highlighted (expanded preview) on the home screen while the cards are edited. */
    var demoId by mutableStateOf<String?>(null)
}

private val LIVE_TABS = listOf("Karten", "Seitenleiste", "Raster", "Startseite", "Kategorien")

/** Small choice chip: the chosen one is filled and has a check mark, the highlighted one is white. */
@Composable
private fun LChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(32.dp).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) Accent1 else Color(0x26FFFFFF))
            .border(if (f) 2.dp else 1.dp, if (f) Color.White else if (selected) Accent1 else Color(0x33FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (selected) AppIcon(AIcon.Check, if (f) DS.FocusInk else OnAccent, size = 14.dp)
        Text(label, color = if (f) DS.FocusInk else if (selected) OnAccent else Color.White, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** One setting: small label and its choices in a row. */
@Composable
private fun LRow(label: String, choices: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, maxLines = 1)
        choices.forEachIndexed { i, c -> LChip(c, selected == i) { onPick(i) } }
    }
}

/** A number with minus and plus. */
@Composable
private fun LStep(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, maxLines = 1)
        LChip("−", false, onMinus)
        Text(value, color = Accent2, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp), maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, raw = true)
        LChip("+", false, onPlus)
    }
}

/** The chosen card look with one of your own titles: normal card (size, picture type) and the card as it looks when highlighted. */
@Composable
private fun CardPreview() {
    val pick = remember(PlaylistStore.channels) { PlaylistStore.channels.firstOrNull { it.kind != Kind.LIVE && !it.logo.isNullOrBlank() } }
    val meta = remember(pick) { pick?.let { MetaRepo.cached(it) } }
    val poster: Any? = meta?.poster ?: pick?.logo
    val back: Any? = meta?.backdrop ?: pick?.logo
    val wide = General.previewStyle == 1
    val land = General.cardArt == 1
    val size = intArrayOf(86, 100, 116)[General.cardSize.coerceIn(0, 2)].dp
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("So sieht eine Karte aus: normal und markiert", color = Muted, fontSize = 12.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.height(size).width(if (land) size * 16 / 9 else size * 2 / 3).clip(shape).background(Color(0x26FFFFFF))) {
                coil.compose.AsyncImage(if (land) back else poster, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Box(Modifier.height(size + 14.dp).width(if (wide) (size + 14.dp) * 16 / 9 else (size + 14.dp) * 2 / 3).clip(shape)
                .border(2.dp, Color.White, shape).background(Color(0x26FFFFFF))) {
                coil.compose.AsyncImage(if (wide) back else poster, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF2050507))))
                Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(raw = true, text = pick?.title ?: "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Box(Modifier.height(3.dp).width(54.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x99FFFFFF)))
                    Box(Modifier.height(3.dp).width(40.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveControls(ctx: Context) {
    CatPrefs.init(ctx); BrowsePrefs.init(ctx)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (ViewLive.tab) {
            0 -> {
                LRow("Größe", listOf("Klein", "Normal", "Groß"), General.cardSize) { General.setCardView(ctx, it, General.cardArt) }
                LRow("Bild", listOf("Cover", "Szenenbild"), General.cardArt) { General.setCardView(ctx, General.cardSize, it) }
                LRow("Beim Markieren", listOf("Infos auf dem Poster", "Breites Szenenbild"), General.previewStyle) { General.setPreviewStyle(ctx, it) }
            }
            1 -> {
                LRow("Position", SidebarPrefs.POS_NAMES, SidebarPrefs.pos) { SidebarPrefs.set(ctx, pos = it) }
                LRow("Texte", listOf("Immer zeigen", "Nur Symbole"), if (SidebarPrefs.labels) 0 else 1) { SidebarPrefs.set(ctx, labels = it == 0) }
            }
            2 -> {
                LRow("Ansicht", listOf("Raster", "Kino"), if (BrowsePrefs.detail) 1 else 0) { BrowsePrefs.set(ctx, it == 1) }
                // Titles per row: grid and cinema each have their own value (the same setting as the pill in "All movies")
                val kino = BrowsePrefs.detail
                val opts = if (kino) listOf(0, 5, 6, 7, 8, 10, 12) else listOf(0, 4, 5, 6, 8, 10, 12)
                val cur = if (kino) General.kinoPerRow else General.perRow
                fun set(v: Int) { if (kino) General.setKinoPerRow(ctx, v) else General.setPerRow(ctx, v) }
                fun step(d: Int) = opts[((opts.indexOf(cur).let { if (it < 0) 0 else it }) + d).coerceIn(0, opts.lastIndex)]
                LStep(if (kino) "Titel pro Zeile (Kino)" else "Titel pro Zeile", if (cur == 0) "Auto" else cur.toString(), { set(step(-1)) }, { set(step(1)) })
            }
            3 -> {
                val opts = listOf(0, 6, 9, 15)
                LRow("Banner wechselt", opts.map { if (it == 0) "nie" else "alle $it s" }, opts.indexOf(General.heroSec).coerceAtLeast(0)) { General.setHeroSec(ctx, opts[it]) }
                LStep("Kartenbreite", AppSettings.cardW.toString(), { AppSettings.setCardW(ctx, AppSettings.cardW - 10) }, { AppSettings.setCardW(ctx, AppSettings.cardW + 10) })
            }
            else -> LRow("Ansicht", listOf("Liste mit Vorschau", "Kacheln"), if (CatPrefs.listView) 0 else 1) { CatPrefs.set(ctx, it == 0) }
        }
    }
}

/**
 * The settings bar: slim, full width, at the bottom or top. It is a dialog window so the remote control only moves inside
 * the bar; the app behind is not dimmed and follows the area you edit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewLivePanel(ctx: Context, goTab: (Int) -> Unit, closeCats: () -> Unit, onDone: () -> Unit) {
    // The app behind follows the area you are editing: it opens the screen where the change is visible
    LaunchedEffect(ViewLive.tab) {
        closeCats()
        ViewLive.demoId = null
        BrowsePrefs.forceGrid = false
        when (ViewLive.tab) {
            2 -> { goTab(1); delay(500); AppNav.openBrowseSignal++ }   // "All movies" in the chosen view
            4 -> { goTab(1); delay(500); AppNav.openCatsSignal++ }      // the real categories screen
            0 -> { goTab(0); delay(400); AppNav.homeScrollKey = "newm|topm|because|wl|trend|hist" } // a row of movie cards
            3 -> { goTab(0); delay(400); AppNav.homeScrollKey = "resume|favch|trend|wl|because|newm" }   // rows where the card width shows
            else -> { goTab(0); delay(400); AppNav.homeScrollKey = "top" }
        }
    }
    DisposableEffect(Unit) { onDispose { ViewLive.demoId = null } }
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        // No dimming: the app behind must look exactly as it will
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        val fm = LocalFocusManager.current
        LaunchedEffect(Unit) { delay(250); runCatching { fm.moveFocus(FocusDirection.Next) } }
        Box(Modifier.fillMaxSize(), contentAlignment = if (ViewLive.atTop) Alignment.TopCenter else Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xEE0A0C14)).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Live-Vorschau", color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp, top = 6.dp))
                    LIVE_TABS.forEachIndexed { i, l -> LChip(l, ViewLive.tab == i) { ViewLive.tab = i } }
                    Spacer(Modifier.width(12.dp))
                    LChip(if (ViewLive.atTop) "Leiste nach unten" else "Leiste nach oben", false) { ViewLive.atTop = !ViewLive.atTop }
                    LChip("Fertig", true) { onDone() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { LiveControls(ctx) }
                    if (ViewLive.tab == 0) CardPreview()
                }
            }
        }
    }
}
