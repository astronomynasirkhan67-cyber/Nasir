package com.ipvio.stream

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.wrapContentSize
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.requiredWidth

/*
 * Building blocks of the home screen and of Movies/Series after the user's design (picture 1 and 2).
 * All measures in design pixels (dpx/spx, see Design): same proportions as in the design on every screen.
 */

/** Side margin of the rows (design: cards from x 252, i.e. 22–24 px after the sidebar). */
val RowPadX: Dp get() = dpx(24)

/** Card colors of the design draft. */
private val CardBg = Color(0xFF141418)
private val CardLine = Tok.Line
private val SoftInk = Tok.TextLow

/** Card widths: rows fill the width with equally wide cards (channels/landscape 7, poster 9, continue watching 4). */
class CardGrid(val land: Dp, val poster: Dp, val chan: Dp, val gap: Dp, val wide: Dp, val posterGap: Dp)

/** Scrolls the list of the current tab fully to the top (so the header with the focus becomes visible). */
val LocalScrollTop = compositionLocalOf<(suspend () -> Unit)?> { null }

val LocalCardGrid = compositionLocalOf { CardGrid(183.dp, 141.dp, 183.dp, 16.dp, 330.dp, 12.dp) }

fun cardGrid(contentW: Dp, size: Int = General.cardSize, perRow: Int = 0): CardGrid {
    val gap = dpx(16)
    val pgap = dpx(14)
    fun col(n: Int, g: Dp) = (contentW - g * (n - 1)) / n
    // "View" button: small = more cards per row, large = fewer
    val land = intArrayOf(8, 6, 5)[size.coerceIn(0, 2)]
    val poster = if (perRow > 0) perRow else intArrayOf(10, 8, 6)[size.coerceIn(0, 2)]
    val wide = intArrayOf(5, 4, 3)[size.coerceIn(0, 2)]
    return CardGrid(col(land, gap), col(poster, pgap), col(land, gap), gap, col(wide, gap), pgap)
}

/** Landscape image from TMDB in a suitable size for cards (w780 instead of w1280). */
fun cardBackdrop(url: String): String = url.replace("/t/p/w1280/", "/t/p/w780/")

/** "2016 | Abenteuer | 1h 47min | ★ 7.6" as in the design draft. */
@Composable
fun HeroMetaLine(item: Item, m: Meta?, dots: Boolean = false) {
    val parts = ArrayList<Pair<String, Boolean>>()   // text, raw (do not translate)
    // Image 1: „2016 | Abenteuer | 1h 47min | ★ 7.6“; image 2: „Drama · 2023 · 2 Staffeln“
    if (dots) m?.genres?.firstOrNull()?.let { parts.add(it to true) }
    (m?.year ?: item.year?.toString())?.let { parts.add(it to true) }
    if (!dots) m?.genres?.firstOrNull()?.let { parts.add(it to true) }
    if (item.kind == Kind.SERIES) parts.add(seriesCountText(item) to false)
    else m?.runtime?.takeIf { it > 0 }?.let { parts.add((if (it >= 60) "${it / 60}h ${it % 60}min" else "$it min") to true) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        parts.forEachIndexed { i, (p, raw) ->
            if (i > 0) {
                if (dots) Box(Modifier.size(dpx(4)).clip(CircleShape).background(Color(0x99FFFFFF)))
                else Box(Modifier.width(1.dp).height(dpx(16)).background(Color(0x66FFFFFF)))
            }
            Text(p, color = Color(0xFFD4D4DA), fontSize = spx(if (dots) 15 else 16), raw = raw, maxLines = 1)
        }
        if (!dots && m != null && (m.imdbRating != null || m.rtRating != null || m.rating != null)) {
            if (parts.isNotEmpty()) Box(Modifier.width(1.dp).height(dpx(16)).background(Color(0x66FFFFFF)))
            ScoreRow(m, 17)
        }
    }
}

/** "3 seasons · 64 episodes" (big picture and expanded card). */
fun seriesCountText(item: Item): String {
    if (XtreamLogic.isLazy(item)) return ""
    val e = item.episodes.size
    return if (e > 0) Lang.tr(seasonsText(item)) + " · " + Lang.tr(if (e == 1) "1 Episode" else "$e Episoden") else Lang.tr(seasonsText(item))
}

/**
 * Ratings: IMDb (yellow) and Rotten Tomatoes (tomato + percent) if available; otherwise the TMDB rating with a star.
 * [sp] = font size in design pixels.
 */
@Composable
fun ScoreRow(m: Meta, sp: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(sp * 0.6f))) {
        m.imdbRating?.let {
            Text(raw = true, text = "IMDb $it", color = Color.Black, fontSize = spx(sp * 0.9f), fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(dpx(5))).background(Color(0xFFF5C518)).padding(horizontal = dpx(sp * 0.5f), vertical = dpx(sp * 0.12f)))
        }
        m.rtRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(sp * 0.3f))) {
                AppIcon(AIcon.Tomato, Color(0xFFFA320A), size = dpx(sp * 1.1f))
                Text(raw = true, text = it, color = Color.White, fontSize = spx(sp), fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        if (m.imdbRating == null && m.rtRating == null) m.rating?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(sp * 0.3f))) {
                AppIcon(AIcon.Star, Accent1, size = dpx(sp * 1.1f))
                Text(raw = true, text = String.format("%.1f", r), color = Accent1, fontSize = spx(sp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Meta with IMDb/RT rating: fetches the detailed data (once, then remembered on the device). */
@Composable
fun rememberFullMeta(item: Item, base: Meta?, enabled: Boolean = true): Meta? {
    val ctx = LocalContext.current
    val v by produceState(base, item.id, base?.tmdbId, enabled) {
        value = base
        if (enabled && item.kind != Kind.LIVE && (base != null || !MetaRepo.enabled() || MetaRepo.missed(item)) && MetaRepo.usable(item)) MetaRepo.extended(ctx, item)?.let { value = it }
    }
    return v
}

/**
 * Big button in the top picture (round 14): when not selected all are equally calm (dark glass, "Watch now" with a colored icon),
 * only the selected button is filled (white, dark text). Before, "Watch now" was always colored and looked selected.
 */
@Composable
fun HeroButton(text: String, icon: AIcon, primary: Boolean, modifier: Modifier = Modifier, h: Int = 50, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val pulse = rememberPulse()
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "herobtn")
    val shape = RoundedCornerShape(50)
    val ink = if (f) DS.FocusInk else Color.White
    Row(
        modifier.graphicsLayer { val k = s * pulse.scale; scaleX = k; scaleY = k }.height(dpx(h)).clip(shape)
            .background(if (f) DS.FocusFill else Color(0x33FFFFFF))
            .border(1.dp, if (f) DS.FocusFill else Color(0x40FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = { pulse.pulse(); onClick() })
            .padding(horizontal = dpx(h * 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(h * 0.28f))
    ) {
        AppIcon(icon, if (f) DS.FocusInk else if (primary) Accent1 else Color.White, size = dpx(h * 0.4f))
        Text(text, color = ink, fontSize = spx(h * 0.34f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Uniform button of the detail page (seasons, director, read more …): same look and height as the main buttons. */
@Composable
fun DetailPill(text: String, modifier: Modifier = Modifier, light: Boolean = false, icon: AIcon? = null, trailing: AIcon? = null, h: Int = 44, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val pulse = rememberPulse()
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "detpill")
    val shape = RoundedCornerShape(50)
    val ink = if (f) DS.FocusInk else Color.White
    Row(
        modifier.graphicsLayer { val k = s * pulse.scale; scaleX = k; scaleY = k }.height(dpx(h)).clip(shape)
            .background(if (f) DS.FocusFill else if (light) Color(0x66FFFFFF) else Color(0x33FFFFFF))
            .border(1.dp, if (f) DS.FocusFill else if (light) Color(0xB3FFFFFF) else Color(0x40FFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = { pulse.pulse(); onClick() })
            .padding(horizontal = dpx(h * 0.6f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(h * 0.28f))
    ) {
        if (icon != null) AppIcon(icon, ink, size = dpx(h * 0.4f))
        Text(text, color = ink, fontSize = spx(h * 0.34f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (trailing != null) AppIcon(trailing, ink, size = dpx(h * 0.4f))
    }
}

/** Head of a row: title left, "Show all →" right (selectable; Down = first card). */
@Composable
fun RowTitle(
    title: String, linkReq: FocusRequester?, firstCard: FocusRequester?, raw: Boolean = false,
    /** First row of the screen: Up = profile (far top right), Left = last button at the top of the picture. */
    upReq: FocusRequester? = null, leftReq: FocusRequester? = null,
    /** State of the card row below: if the first card is shifted/not composed, scroll to the start first */
    rowState: LazyListState? = null,
    onAll: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().padding(start = dpx(29), end = dpx(28), top = dpx(28)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = spx(25), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), raw = raw)
        if (onAll != null) {
            var f by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(50)
            val scope = rememberCoroutineScope()
            val scrollTop = LocalScrollTop.current
            val bring = remember { BringIntoViewRequester() }
            val dens = androidx.compose.ui.platform.LocalDensity.current
            val fm = androidx.compose.ui.platform.LocalFocusManager.current
            Row(
                (if (linkReq != null) Modifier.focusRequester(linkReq) else Modifier)
                    .bringIntoViewRequester(bring)
                    .clip(shape).background(if (f) DS.FocusFill else Color.Transparent)
                    .onFocusChanged {
                        f = it.isFocused
                        // Bring the row with some space above into view so the highlight never gets stuck at the edge
                        if (it.isFocused) scope.launch {
                            val px = with(dens) { dpx(60).toPx() }
                            bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -px, px * 3f, px))
                        }
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) false
                        else if (firstCard != null && ev.key == Key.DirectionDown) {
                            // The row is shifted under the highlight while paging: then the first card is not composed and the
                            // focus request fails. This used to leave you stuck on "Show all" ("Down" had no effect).
                            if (runCatching { firstCard.requestFocus() }.isFailure) scope.launch {
                                rowState?.scrollToItem(0)
                                repeat(3) { withFrameNanos { } }
                                runCatching { firstCard.requestFocus() }
                            }
                            true
                        }
                        else if (upReq != null && ev.key == Key.DirectionUp) {
                            // Header (profile, settings) is at the top of the list: scroll up first, then focus
                            scope.launch {
                                runCatching { scrollTop?.invoke() }
                                withFrameNanos { }
                                runCatching { upReq.requestFocus() }
                            }
                            true
                        }
                        else if (leftReq != null && ev.key == Key.DirectionLeft) runCatching { leftReq.requestFocus() }.getOrDefault(false)
                        else if (upReq == null && ev.key == Key.DirectionUp) {
                            // Default "Up" can fail when the row above is not composed yet (screen stays, focus stuck):
                            // scroll that row into view first, then move on
                            if (!fm.moveFocus(FocusDirection.Up)) scope.launch {
                                val px = with(dens) { dpx(60).toPx() }
                                bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -px * 5f, px * 3f, px))
                                repeat(2) { withFrameNanos { } }
                                fm.moveFocus(FocusDirection.Up)
                            }
                            true
                        }
                        else false
                    }
                    .trackFocus()
                    .clickable(onClick = onAll)
                    .padding(horizontal = dpx(10), vertical = dpx(4)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))
            ) {
                Text("Alle anzeigen", color = if (f) DS.FocusInk else SoftInk, fontSize = spx(16))
                AppIcon(AIcon.Right, if (f) DS.FocusInk else SoftInk, size = dpx(15))
            }
        }
    }
}

/** A quick-access tile: gradient, icon, title, subtitle, arrow and an own motif on the right (no movie cover). */
class QuickTile(
    val title: String, val sub: String, val icon: Int, val c1: Color, val c2: Color, val iconColor: Color,
    /** Round 15: real image from the own library (scene image of a top title) instead of the drawn shape */
    val image: String? = null,
    /** Live TV: channel logos (favorites, recently watched) */
    val logos: List<String> = emptyList(),
    val onClick: () -> Unit
)

/** Five equally wide tiles across the whole row. */
@Composable
fun QuickTiles(tiles: List<QuickTile>) {
    Row(
        Modifier.fillMaxWidth().padding(start = RowPadX + dpx(2), end = RowPadX, top = dpx(18))
            .onFocusChanged { if (it.hasFocus) Billboard.cardFocus = false },
        horizontalArrangement = Arrangement.spacedBy(dpx(16))
    ) {
        tiles.forEach { t -> QuickTileCard(t, Modifier.weight(1f)) }
    }
}

@Composable
private fun QuickTileCard(t: QuickTile, modifier: Modifier) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "qt")
    val shape = RoundedCornerShape(dpx(12))
    Box(
        modifier.height(dpx(88)).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(Brush.horizontalGradient(listOf(t.c1, t.c2)))
            .border(if (f) 2.dp else 1.dp, if (f) Color.White else t.iconColor.copy(alpha = .22f), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = t.onClick)
    ) {
        // Motif on the right (drawn, matching the area), softly blended into the gradient
        if (t.image != null) AsyncImage(cardBackdrop(t.image), null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.72f), contentScale = ContentScale.Crop)
        else if (t.logos.isNotEmpty()) Row(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = dpx(46)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
        ) {
            t.logos.take(3).forEach { u ->
                Box(Modifier.size(dpx(52)).clip(CircleShape).background(Color(0x33FFFFFF)).padding(dpx(8)), contentAlignment = Alignment.Center) {
                    AsyncImage(u, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
        else Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)) { tileArt(t.icon, t.iconColor) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to t.c1, 0.38f to t.c1.copy(alpha = .85f), 0.7f to t.c1.copy(alpha = .15f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0x55000000))))
        Row(
            Modifier.fillMaxSize().padding(start = dpx(22), end = dpx(20)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dpx(16))
        ) {
            MenuIcon(t.icon, t.iconColor, Modifier.size(dpx(34)))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(t.sub, color = Tok.TextMid, fontSize = spx(14), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppIcon(AIcon.Right, Color.White, size = dpx(18))
        }
    }
}

/** Motifs of the tiles: large, cropped shapes in the tile color (Live TV, Movies, Series, Favorites, My list). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.tileArt(id: Int, c: Color) {
    val w = size.width; val h = size.height
    val line = Stroke(width = h * 0.045f, cap = StrokeCap.Round)
    val soft = c.copy(alpha = .22f); val mid = c.copy(alpha = .45f); val strong = c.copy(alpha = .7f)
    when (id) {
        3 -> { // Live TV: screen with broadcast waves
            drawRoundRect(soft, Offset(w * .38f, h * .22f), Size(w * .5f, h * .62f), CornerRadius(h * .08f))
            drawRoundRect(strong, Offset(w * .38f, h * .22f), Size(w * .5f, h * .62f), CornerRadius(h * .08f), style = line)
            for (k in 1..3) drawArc(mid, -60f, 120f, false, Offset(w * .2f - k * h * .16f, h * .5f - k * h * .16f - h * .06f),
                Size(k * h * .32f + h * .12f, k * h * .32f + h * .12f), style = line)
            drawCircle(strong, h * .06f, Offset(w * .63f, h * .53f))
        }
        1 -> { // Movies: film reel and film strip
            val cx = w * .72f; val cy = h * .5f; val r = h * .46f
            drawCircle(soft, r, Offset(cx, cy)); drawCircle(strong, r, Offset(cx, cy), style = line)
            for (k in 0 until 6) {
                val a = Math.toRadians(k * 60.0)
                drawCircle(mid, r * .2f, Offset(cx + (r * .55f * Math.cos(a)).toFloat(), cy + (r * .55f * Math.sin(a)).toFloat()))
            }
            drawRoundRect(mid, Offset(w * .2f, h * .62f), Size(w * .34f, h * .26f), CornerRadius(h * .04f), style = line)
            for (k in 0 until 5) drawRect(mid, Offset(w * .23f + k * w * .065f, h * .68f), Size(w * .03f, h * .05f))
        }
        2 -> { // Series: staggered screens with play symbol
            for (k in 2 downTo 0) {
                val o = k * h * .14f
                drawRoundRect(if (k == 0) soft else c.copy(alpha = .12f), Offset(w * .36f + o, h * .2f - o * .4f), Size(w * .46f, h * .6f), CornerRadius(h * .07f))
                drawRoundRect(if (k == 0) strong else mid, Offset(w * .36f + o, h * .2f - o * .4f), Size(w * .46f, h * .6f), CornerRadius(h * .07f), style = line)
            }
            val p = Path().apply { moveTo(w * .54f, h * .36f); lineTo(w * .54f, h * .64f); lineTo(w * .54f + h * .26f, h * .5f); close() }
            drawPath(p, strong)
        }
        7 -> { // Favorites: large heart, small hearts
            fun heart(cx: Float, cy: Float, r: Float, col: Color, fill: Boolean) {
                val p = Path().apply {
                    moveTo(cx, cy + r * .9f)
                    cubicTo(cx - r * 1.3f, cy + r * .1f, cx - r * 1.1f, cy - r * 1f, cx, cy - r * .45f)
                    cubicTo(cx + r * 1.1f, cy - r * 1f, cx + r * 1.3f, cy + r * .1f, cx, cy + r * .9f)
                    close()
                }
                if (fill) drawPath(p, col) else drawPath(p, col, style = line)
            }
            heart(w * .7f, h * .52f, h * .5f, soft, true); heart(w * .7f, h * .52f, h * .5f, strong, false)
            heart(w * .36f, h * .3f, h * .16f, mid, true); heart(w * .45f, h * .78f, h * .11f, mid, true)
        }
        8 -> { // My List: bookmark stack
            for (k in 0..2) {
                val x = w * .44f + k * h * .3f; val top = h * .14f + k * h * .05f
                val p = Path().apply { moveTo(x, top); lineTo(x + h * .34f, top); lineTo(x + h * .34f, h * .92f); lineTo(x + h * .17f, h * .74f); lineTo(x, h * .92f); close() }
                drawPath(p, if (k == 2) soft else c.copy(alpha = .12f)); drawPath(p, if (k == 2) strong else mid, style = line)
            }
        }
        else -> drawCircle(soft, h * .45f, Offset(w * .7f, h * .5f))
    }
}

/** Channel card (design): logo, name, running show, start/end time and progress. */
@Composable
fun ChannelNowCard(item: Item, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "chnow")
    val border by animateColorAsState(if (f) Color.White else CardLine, label = "chb")
    val shape = RoundedCornerShape(dpx(10))
    val epg by produceState(emptyList<EpgEntry>(), item.id) { value = Epg.now(item) }
    val nowE = Epg.current(epg)
    Column(
        modifier.height(dpx(110)).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(if (f) Color(0xFF1E1E24) else CardBg)
            .border(if (f) 2.dp else 1.dp, border, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .favKeys { Library.toggleFav(ctx, item.id) }
            .clickable(onClick = onClick)
            .padding(start = dpx(14), end = dpx(14), top = dpx(12), bottom = dpx(10))
    ) {
        // Logo left, all texts left-aligned to its right: channel, what is on now, start and end time
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
            Box(Modifier.size(dpx(58)), contentAlignment = Alignment.Center) {
                if (item.logo != null) AsyncImage(item.logo, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(raw = true, text = item.title.take(3).uppercase(), color = Color.White, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
                if (Library.isFav(item.id)) AppIcon(AIcon.Star, DS.Gold, Modifier.align(Alignment.TopEnd), dpx(14))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(2))) {
                Text(raw = true, text = item.title, color = Color.White, fontSize = spx(15), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nowE != null) {
                    Text(raw = true, text = nowE.title, color = Color(0xFFD0D0D6), fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(raw = true, text = hhmm(nowE.start) + " – " + hhmm(nowE.end), color = Color(0xFFA0A0A8), fontSize = spx(13), maxLines = 1)
                } else Text("Live", color = Color(0xFFA0A0A8), fontSize = spx(13), maxLines = 1)
            }
        }
        Spacer(Modifier.height(dpx(8)))
        val frac = if (nowE == null) 0f else ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(dpx(3)).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
        }
    }
}

/** Row with channel cards (home "Live TV"). */
@Composable
fun ChannelNowRow(title: String, list: List<Item>, onOpen: (Item) -> Unit, onAll: () -> Unit) {
    CardRow(title, list, CARD_POSTER, onOpen, onAll, RowStates.get("${NavState.tab}|chnow"))
}

/** Poster without text (design "Top 10 · Trending"). Without an image: title on a colored surface. */
@Composable
fun PosterTile(item: Item, modifier: Modifier, reorder: List<Item>? = null, onClick: () -> Unit) {
    val st = rememberMetaState(item)
    // Favorites: menu key / long OK opens the move menu ([reorder] = the favorites of this section)
    var favMenu by remember { mutableStateOf(false) }
    val reorderMod = if (reorder != null) Modifier.followFav(item.id).menuKeys({ favMenu = true }, { favMenu = true }) else Modifier
    val m = st.meta
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "ptile")
    val shape = RoundedCornerShape(dpx(8))
    val rowF = LocalRowFocus.current
    val dimA by animateFloatAsState(if (rowF && !f) 0.5f else 1f, tween(if (Look.motion == 0) 0 else 320), label = "dim")
    val img: String? = when {
        item.kind == Kind.LIVE -> item.logo
        m?.poster != null -> cardPoster(m.poster)
        st.done -> item.logo
        else -> null
    }
    Box(
        modifier.then(reorderMod).aspectRatio(2f / 3f).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
            .border(1.dp, if (f) Color(0x40FFFFFF) else CardLine, shape)
            .billboardFocus(item) { f = it }
            .trackFocus()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (favMenu && reorder != null) FavReorderMenu(item, reorder) { favMenu = false }
        if (img == null && st.done) ArtPlaceholder(item, Modifier.fillMaxSize())
        if (img != null) AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = if (item.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop)
        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, Modifier.align(Alignment.TopStart).padding(dpx(8)), dpx(18))
        // 17c: darkening as a simple surface on top (cheaper than transparency of the whole card)
        Box(Modifier.fillMaxSize().drawBehind { val d = 1f - dimA; if (d > 0.01f) drawRect(Color.Black.copy(alpha = d)) })
    }
}

/** Landscape card (design picture 2): image, title below, genre, seasons or year and ★ rating. */
@Composable
fun LandscapeCard(item: Item, modifier: Modifier, reorder: List<Item>? = null, onClick: () -> Unit) {
    val st = rememberMetaState(item)
    // Favorites: menu key / long OK opens the move menu ([reorder] = the favorites of this section)
    var favMenu by remember { mutableStateOf(false) }
    val reorderMod = if (reorder != null) Modifier.followFav(item.id).menuKeys({ favMenu = true }, { favMenu = true }) else Modifier
    val m = st.meta
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "land")
    val shape = RoundedCornerShape(dpx(8))
    val rowF = LocalRowFocus.current
    val dimA by animateFloatAsState(if (rowF && !f) 0.5f else 1f, tween(if (Look.motion == 0) 0 else 320), label = "dim")
    val img = m?.backdrop?.let { cardBackdrop(it) } ?: m?.poster?.let { cardPoster(it) } ?: item.logo
    Box(
        modifier.then(reorderMod).aspectRatio(1.3f).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
            .border(1.dp, if (f) Color(0x40FFFFFF) else CardLine, shape)
            .billboardFocus(item) { f = it }
            .trackFocus()
            .clickable(onClick = onClick)
    ) {
        if (favMenu && reorder != null) FavReorderMenu(item, reorder) { favMenu = false }
        if (img == null) ArtPlaceholder(item, Modifier.fillMaxSize(), showTitle = false)
        else AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xF5070709))))
        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, Modifier.align(Alignment.TopStart).padding(dpx(8)), dpx(18))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = dpx(12), end = dpx(12), bottom = dpx(10))) {
            Text(raw = true, text = item.title, color = Color.White, fontSize = spx(16), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            m?.genres?.firstOrNull()?.let { Text(raw = true, text = it, color = SoftInk, fontSize = spx(13), maxLines = 1) }
            Row(Modifier.padding(top = dpx(4)), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.kind == Kind.SERIES) seasonsText(item) else (m?.year ?: item.year?.toString() ?: "Film"),
                    color = SoftInk, fontSize = spx(12), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    raw = item.kind != Kind.SERIES
                )
                m?.rating?.let {
                    AppIcon(AIcon.Star, Accent1, size = dpx(14))
                    Spacer(Modifier.width(dpx(5)))
                    Text(raw = true, text = String.format("%.1f", it), color = Color.White, fontSize = spx(13), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // 17c: darkening as a simple surface on top (cheaper than transparency of the whole card)
        Box(Modifier.fillMaxSize().drawBehind { val d = 1f - dimA; if (d > 0.01f) drawRect(Color.Black.copy(alpha = d)) })
    }
}

/** "2 Staffeln" (landscape cards have little room). */
fun seasonsText(item: Item): String { val n = seasonCount(item); return if (n == 1) "1 Staffel" else "$n Staffeln" }

const val CARD_POSTER = 0
const val CARD_LAND = 1
/** Top 10 row: cover with a huge rank number in front (round 17b) */
const val CARD_TOP10 = 2

/** Row with title and "Show all →": channel, landscape or poster cards in equally wide columns. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CardRow(
    title: String, list: List<Item>, style: Int, onOpen: (Item) -> Unit, onAll: (() -> Unit)?,
    state: LazyListState, linkReq: FocusRequester = remember { FocusRequester() }, firstCard: FocusRequester = remember { FocusRequester() },
    raw: Boolean = false, upReq: FocusRequester? = null, leftReq: FocusRequester? = null
) {
    val g = LocalCardGrid.current
    val live = list.firstOrNull()?.kind == Kind.LIVE
    // Highlight stays in the middle (round 15/16): the row slides through under the highlight
    var cur by remember { mutableIntStateOf(-1) }
    CenterFollow(state, cur)
    val shownList = remember(list) { list.take(60) }   // same list object across recompositions
    Column {
        RowTitle(title, linkReq, firstCard, raw = raw, upReq = upReq, leftReq = leftReq, rowState = state, onAll = onAll)
        // Enough space above/below for the enlargement on highlight (otherwise the cover is cropped)
        // If the row has focus, the other
        // cards step back (LocalRowFocus); sideways the calm scrolling (SmoothSpec) applies, not the holding of the home screen.
        var rowFocused by remember { mutableStateOf(false) }
        Box(Modifier.onFocusChanged { rowFocused = it.hasFocus }) {
        CompositionLocalProvider(LocalRowFocus provides rowFocused, androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SmoothSpec) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = RowPadX, end = RowPadX, top = dpx(30), bottom = dpx(30)),
            horizontalArrangement = Arrangement.spacedBy(if (live || style == CARD_LAND) g.gap else g.posterGap)
        ) {
            itemsIndexed(shownList, contentType = { _, x -> if (x.kind == Kind.LIVE) 0 else style }) { i, it ->
                val fr = if (i == 0) Modifier.focusRequester(firstCard) else Modifier
                // Up on a card highlights "Show all" of the same row (otherwise hard to reach)
                val track = Modifier.onPreviewKeyEvent { ev ->
                    if (onAll != null && ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) runCatching { linkReq.requestFocus() }.getOrDefault(false)
                    else false
                }.onFocusChanged { st -> if (st.hasFocus) cur = i }
                when {
                    it.kind == Kind.LIVE -> ChannelNowCard(it, track.width(g.chan).then(fr)) { onOpen(it) }
                    style == CARD_LAND -> LandscapeCard(it, track.width(g.land).restoreFocus(it.id).then(fr)) { onOpen(it) }
                    // 17c: on the home screen no preview expands (the info is already shown large at the top), Top 10 without numbers
                    else -> RowPosterCard(it, g.poster, track.restoreFocus(it.id).then(fr), overlay = 0, expand = false) { onOpen(it) }
                }
            }
        }
        }
        }
    }
}

/**
 * Keeps the selected entry of a row in the middle of the screen: the row slides through under the highlight instead of
 * the highlight wandering to the edge. A controller (40 % of the remaining distance per frame) also follows the card that is just expanding.
 * At the start and end of the row it naturally cannot go further than the edge.
 */
@Composable
fun CenterFollow(state: LazyListState, cur: Int) {
    LaunchedEffect(cur) {
        if (cur < 0) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = state.layoutInfo
            info.visibleItemsInfo.firstOrNull { x -> x.index == cur }?.let { x -> x.offset + x.size / 2f - info.viewportSize.width / 2f }
        }.collectLatest { d ->
            if (d != null && kotlin.math.abs(d) > 1.5f) state.scrollBy(d * 0.4f)
        }
    }
}

/**
 * Poster in a row (round 14): if it stays selected briefly, it softly expands into a large scene image and shows
 * title, year, genre, runtime or seasons, rating, quality (HD/4K) and the beginning of the description.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RowPosterCard(item: Item, baseW: Dp, modifier: Modifier, overlay: Int = 0, aspect: Float = 1.5f, expand: Boolean = true, onClick: () -> Unit) {
    val st = rememberMetaState(item)
    val m = st.meta
    var f by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    // overlay 3 = automatic: the middle of the row grows to both sides, left edge to the right, right edge to the left.
    // Only the layout coordinates are remembered (no state, no position math per scroll frame); the position in the
    // window is computed once, at the moment the card expands.
    var mode by remember { mutableIntStateOf(3) }
    val place = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val viewW = androidx.compose.ui.platform.LocalView.current
    // Even with animation "Off" the preview expands (then without motion, see snap below)
    // Preview style (View > Preview on highlight): info on the poster (default) or wide scene image
    val wide = General.previewStyle == 1
    val canOpen = expand && (if (wide) m?.backdrop != null else m != null)
    // View settings live preview: one card shows its highlighted state so the preview style can be judged on a real title
    val demo = ViewLive.active && ViewLive.tab == 0 && ViewLive.demoId == item.id
    val fx = f || demo
    val geo = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(ViewLive.active, ViewLive.tab, ViewLive.demoId, canOpen) {
        if (ViewLive.active && ViewLive.tab == 0 && ViewLive.demoId == null && canOpen) {
            kotlinx.coroutines.delay(1_000)
            val vh = viewW.height.coerceAtLeast(1)
            if (ViewLive.demoId == null && geo[1] > vh * 0.04f && geo[1] < vh * 0.62f && geo[0] in 0.05f..0.45f) ViewLive.demoId = item.id
        }
    }
    LaunchedEffect(fx, canOpen) {
        if (fx && canOpen) {
            // Expand only after a longer dwell (look at the cover in peace); nothing happens while paging through
            kotlinx.coroutines.delay(if (wide) 1500 else 1300)
            if (overlay == 3) {
                val c = place[0]?.takeIf { it.isAttached }
                val x = if (c != null) (c.positionInRoot().x + c.size.width / 2f) / viewW.width.coerceAtLeast(1) else 0.5f
                mode = if (x < 0.36f) 1 else if (x > 0.64f) 2 else 3
            }
            open = true
        } else open = false
    }
    val h = baseW * aspect
    // A single progress value 0..1 controls width, scene image and text: even and without stutter
    val p by animateFloatAsState(
        if (open) 1f else 0f,
        if (Look.motion == 0) snap() else tween(if (Look.motion == 1) 420 else 650, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "pp"
    )
    val w = if (wide) baseW + (h * (16f / 9f) - baseW) * p else baseW
    val s by animateFloatAsState(if (fx && (!open || !wide)) focusScale(if (wide) 1.05f else 1.12f) else 1f, label = "ps")
    // 17b: in the selected row the other cards step back
    val rowF = LocalRowFocus.current
    val dimA by animateFloatAsState(if (rowF && !f) 0.5f else 1f, tween(if (Look.motion == 0) 0 else 320), label = "dim")
    val shape = RoundedCornerShape(dpx(10))
    // The expanded scene image draws its own frame
    // Image only. The focus sits on the poster slot (bottom), not on the wide card: otherwise Right/Left skips
    // the covered neighbor (bug "jumps 2 titles").
    val visual: @Composable (Modifier) -> Unit = { mod ->
    Box(
        mod.graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
            .border(1.dp, if (f) Color(0x40FFFFFF) else CardLine, shape)
    ) {
        // Poster at the bottom, the scene image fades in above it with the progress (no crossfade switch)
        val img: String? = when {
            m?.poster != null -> cardPoster(m.poster)
            st.done -> item.logo
            else -> null
        }
        // Placeholder lies under the image: if the image is missing or does not load, it stays visible
        if (img == null && st.done) ArtPlaceholder(item, Modifier.fillMaxSize())
        if (img != null) AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (wide && p > 0.01f && m?.backdrop != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }) {
                AsyncImage(cardBackdrop(m.backdrop), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.1f to Color.Transparent, 0.75f to Color(0xE6050507), 1f to Color(0xF7050507))))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xB3050507), 0.7f to Color.Transparent)))
            }
            // Fade in the text only when the card is almost open
            val ta = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (ta > 0f) PreviewInfo(item, m, open, Modifier.align(Alignment.BottomStart).padding(dpx(18)).graphicsLayer { alpha = ta })
        }
        // New style: the poster stays where it is. A dark gradient fades in from below, on it title, rating, genre and beginning
        // of the description (fade-in only, no size change: nothing shifts or covers the neighbors).
        if (!wide && p > 0.01f && m != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 0.72f to Color(0xB3050507), 1f to Color(0xF2050507))))
            val ta = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (ta > 0f) PosterInfo(item, m, open, baseW, Modifier.align(Alignment.BottomStart).padding(dpx(12))
                .graphicsLayer { alpha = ta; translationY = (1f - ta) * 14.dp.toPx() })
        }
        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, Modifier.align(Alignment.TopStart).padding(dpx(8)), dpx(18))
        // New episode for a favorite series (disappears as soon as the series is opened)
        if (item.kind == Kind.SERIES && NewEpisodes.list.any { it.id == item.id }) Box(Modifier.align(Alignment.TopStart).padding(dpx(8))) { Badge("Neue Folge", DS.Scrim, Accent2, AIcon.Sparkle) }
        // 17c: darkening as a simple surface on top (cheaper than transparency of the whole card)
        Box(Modifier.fillMaxSize().drawBehind { val d = 1f - dimA; if (d > 0.01f) drawRect(Color.Black.copy(alpha = d)) })
    }
    }
    // Position only for the View settings live preview (otherwise this ran for every card on every scroll frame)
    val focusMod = (if (ViewLive.active) Modifier.onGloballyPositioned { c -> geo[0] = (c.positionInRoot().x + c.size.width / 2f) / viewW.width.coerceAtLeast(1); geo[1] = c.positionInRoot().y } else Modifier)
        .billboardFocus(item) { f = it }.trackFocus().clickable(onClick = onClick)
    if (overlay == 0) Box(modifier.width(w).height(h).then(focusMod)) { visual(Modifier.fillMaxSize()) }
    else {
        // In the layout the card always takes only its own space (nothing shifts) and expands over it: middle to both
        // sides, left columns to the right, right ones to the left. (requiredWidth always centers, hence wrapContentSize.)
        val eff = if (overlay == 3) mode else overlay
        Box(
            modifier.width(baseW).height(h).zIndex(if (fx || p > 0.01f) 1f else 0f)
                .onGloballyPositioned { c -> place[0] = c }
                .then(focusMod)
        ) {
            Box(Modifier.wrapContentSize(align = if (eff == 2) Alignment.CenterEnd else if (eff == 1) Alignment.CenterStart else Alignment.Center, unbounded = true)) {
                visual(Modifier.width(w).height(h))
            }
        }
    }
}

/** Info on the poster (preview style "Info on the poster"): only title, ratings, year and genre, font follows the card width. */
@Composable
private fun PosterInfo(item: Item, base: Meta, load: Boolean, baseW: Dp, modifier: Modifier) {
    val m = rememberFullMeta(item, base, load) ?: base
    val k = (baseW.value / 150f).coerceIn(0.85f, 1.6f)
    val soft = Tok.TextMid
    Column(modifier, verticalArrangement = Arrangement.spacedBy((4f * k).dp)) {
        Text(raw = true, text = item.title, color = Color.White, fontSize = (16f * k).sp, lineHeight = (19f * k).sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8f * k).dp)) {
            ScoreRow(m, (11f * k).toInt().coerceAtLeast(10))
            (m.year ?: item.year?.toString())?.let { Text(raw = true, text = it, color = soft, fontSize = (12f * k).sp, maxLines = 1) }
        }
        m.genres.take(2).takeIf { it.isNotEmpty() }?.let { Text(raw = true, text = it.joinToString(" · "), color = Tok.TextLow, fontSize = (12f * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Info in the expanded card (round 15: larger font, IMDb and Rotten Tomatoes rating, seasons and episodes). */
@Composable
private fun PreviewInfo(item: Item, base: Meta, load: Boolean, modifier: Modifier) {
    val m = rememberFullMeta(item, base, load) ?: base
    val soft = Tok.TextMid
    Column(modifier, verticalArrangement = Arrangement.spacedBy(dpx(8))) {
        Text(raw = true, text = item.title, color = Color.White, fontSize = spx(28), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
            ScoreRow(m, 18)
            (m.year ?: item.year?.toString())?.let { Text(raw = true, text = it, color = soft, fontSize = spx(19), fontWeight = FontWeight.Medium) }
            if (item.kind == Kind.SERIES) Text(seriesCountText(item), color = soft, fontSize = spx(19), maxLines = 1)
            else m.runtime?.takeIf { it > 0 }?.let { Text(raw = true, text = if (it >= 60) "${it / 60}h ${it % 60}min" else "$it min", color = soft, fontSize = spx(19)) }
            (item.quality ?: QualityStore.get(item.id))?.let { q -> PreviewQualityBadge(q) }
        }
        m.genres.take(3).takeIf { it.isNotEmpty() }?.let { Text(raw = true, text = it.joinToString(" · "), color = Tok.TextLow, fontSize = spx(17), maxLines = 1) }
        m.overview?.let { Text(raw = true, text = it, color = Tok.TextMid, fontSize = spx(18), lineHeight = spx(25), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

/** Small badge "4K", "FHD", "HD" (outline, no fill). */
@Composable
fun PreviewQualityBadge(q: String) {
    val t = q.uppercase().let { if (it.contains("2160") || it.contains("UHD")) "4K" else it }.take(6)
    Text(raw = true, text = t, color = Color.White, fontSize = spx(16), fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(dpx(4))).padding(horizontal = dpx(6), vertical = dpx(1)))
}

/** Cards as a grid (favorites, My list): rows of equally wide cards. */
@Composable
fun CardGridRows(list: List<Item>, style: Int, firstReq: FocusRequester?, onOpen: (Item) -> Unit) {
    // Note: composes every card at once. For lists that can grow (favorites, my list, history) use [cardGridItems].
    val g = LocalCardGrid.current
    val live = list.firstOrNull()?.kind == Kind.LIVE
    // same column count as cardGrid ("Ansicht" button)
    val sz = General.cardSize.coerceIn(0, 2)
    val perRow = if (style == CARD_LAND || live) intArrayOf(8, 6, 5)[sz] else intArrayOf(10, 8, 6)[sz]
    val gap = if (style == CARD_LAND || live) g.gap else g.posterGap
    Column(Modifier.padding(start = RowPadX, end = RowPadX, top = dpx(12)), verticalArrangement = Arrangement.spacedBy(gap)) {
        list.chunked(perRow).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { c, it ->
                    val fr = if (r == 0 && c == 0 && firstReq != null) Modifier.focusRequester(firstReq) else Modifier
                    when {
                        it.kind == Kind.LIVE -> ChannelNowCard(it, Modifier.width(g.chan).then(fr)) { onOpen(it) }
                        style == CARD_LAND -> LandscapeCard(it, Modifier.width(g.land).restoreFocus(it.id).then(fr)) { onOpen(it) }
                        else -> PosterTile(it, Modifier.width(g.poster).restoreFocus(it.id).then(fr)) { onOpen(it) }
                    }
                }
            }
        }
    }
}

/**
 * Grid of cards as lazy rows of a LazyColumn: only the rows on screen are composed. [CardGridRows] composed every card of
 * the list at once inside one list item (hundreds of cards for a long history or list: slow and memory hungry).
 */
fun LazyListScope.cardGridItems(key: String, list: List<Item>, style: Int, firstReq: FocusRequester?, onOpen: (Item) -> Unit, reorder: List<Item>? = null) {
    val live = list.firstOrNull()?.kind == Kind.LIVE
    val sz = General.cardSize.coerceIn(0, 2)
    val perRow = if (style == CARD_LAND || live) intArrayOf(8, 6, 5)[sz] else intArrayOf(10, 8, 6)[sz]
    val rows = (list.size + perRow - 1) / perRow
    items(rows, key = { "$key#$it" }, contentType = { "cardrow$style" }) { r ->
        CardGridRow(list.subList(r * perRow, minOf(list.size, (r + 1) * perRow)), r == 0, style, live, if (r == 0) firstReq else null, onOpen, reorder)
    }
}

@Composable
private fun CardGridRow(row: List<Item>, first: Boolean, style: Int, live: Boolean, firstReq: FocusRequester?, onOpen: (Item) -> Unit, reorder: List<Item>? = null) {
    val g = LocalCardGrid.current
    val gap = if (style == CARD_LAND || live) g.gap else g.posterGap
    Row(Modifier.padding(start = RowPadX, end = RowPadX, top = if (first) dpx(12) else gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
        row.forEachIndexed { c, it ->
            val fr = if (c == 0 && firstReq != null) Modifier.focusRequester(firstReq) else Modifier
            when {
                it.kind == Kind.LIVE -> ChannelNowCard(it, Modifier.width(g.chan).then(fr)) { onOpen(it) }
                style == CARD_LAND -> LandscapeCard(it, Modifier.width(g.land).restoreFocus(it.id).then(fr), reorder) { onOpen(it) }
                else -> PosterTile(it, Modifier.width(g.poster).restoreFocus(it.id).then(fr), reorder) { onOpen(it) }
            }
        }
    }
}

/**
 * Favorites > Channels (round 15): wide card with logo, channel name, NOW marker, title and description of the running show,
 * time and progress. Before, only the channel name was shown.
 */
@Composable
fun ChannelDetailCard(item: Item, modifier: Modifier, reorder: List<Item>? = null, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    // Favorites tab: menu key / long OK opens the move menu instead of toggling the favorite at once
    var favMenu by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.04f) else 1f, label = "chd")
    val shape = RoundedCornerShape(dpx(12))
    val epg by produceState<List<EpgEntry>?>(null, item.id) { value = Epg.now(item) }
    val nowE = epg?.let { Epg.current(it) }
    Column(
        modifier.then(if (reorder != null) Modifier.followFav(item.id) else Modifier).height(dpx(172)).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape)
            .background(if (f) Tok.CardFocus else CardBg)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else CardLine, shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .then(if (reorder != null) Modifier.menuKeys({ favMenu = true }, { favMenu = true }) else Modifier.favKeys { Library.toggleFav(ctx, item.id) })
            .clickable(onClick = onClick)
            .padding(dpx(16))
    ) {
        if (favMenu && reorder != null) FavReorderMenu(item, reorder) { favMenu = false }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
            Box(Modifier.size(dpx(78)).clip(RoundedCornerShape(dpx(10))).background(Color(0x1FFFFFFF)).padding(dpx(8)), contentAlignment = Alignment.Center) {
                if (item.logo != null) AsyncImage(item.logo, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(raw = true, text = item.title.take(3).uppercase(), color = Color.White, fontSize = spx(22), fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                    Text(raw = true, text = item.title, color = Tok.TextLow, fontSize = spx(15), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (nowE != null) Text("JETZT", color = OnAccent, fontSize = spx(11), fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(dpx(4))).background(Accent1).padding(horizontal = dpx(6), vertical = dpx(1)))
                }
                Text(
                    raw = true, text = nowE?.title ?: if (epg == null) "" else "Kein Programm verfügbar",
                    color = Color.White, fontSize = spx(21), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (nowE != null) Text(raw = true, text = hhmm(nowE.start) + " – " + hhmm(nowE.end) + "  ·  " + ((nowE.end - nowE.start) / 60) + " Min", color = Tok.TextLow, fontSize = spx(14), maxLines = 1)
                if (nowE != null && nowE.desc.isNotBlank()) Text(raw = true, text = nowE.desc, color = Tok.TextMid, fontSize = spx(15), lineHeight = spx(20), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val frac = if (nowE == null) 0f else ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.padding(top = dpx(10)).fillMaxWidth().height(dpx(4)).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
        }
    }
}

/** Favorites > Channels: three wide cards per row (two for "Large"). */
@Composable
fun ChannelDetailRows(list: List<Item>, firstReq: FocusRequester?, onOpen: (Item) -> Unit, reorder: List<Item>? = null) {
    val perRow = intArrayOf(4, 3, 2)[General.cardSize.coerceIn(0, 2)]
    Column(Modifier.padding(start = RowPadX, end = RowPadX, top = dpx(12)), verticalArrangement = Arrangement.spacedBy(dpx(16))) {
        list.chunked(perRow).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                row.forEachIndexed { c, it ->
                    ChannelDetailCard(it, Modifier.weight(1f).then(if (r == 0 && c == 0 && firstReq != null) Modifier.focusRequester(firstReq) else Modifier), reorder) { onOpen(it) }
                }
                // fill missing slots so all cards stay equally wide
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
