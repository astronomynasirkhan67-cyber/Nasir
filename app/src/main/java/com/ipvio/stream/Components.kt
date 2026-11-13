package com.ipvio.stream

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val palette = listOf(
    0xFF7C5CFF, 0xFF00B8AE, 0xFFE0566B, 0xFFE59A2F, 0xFF3F8EE8, 0xFFA855F7
).map { Color(it) }

fun colorFor(item: Item): Color = palette[(item.title.hashCode() and 0x7fffffff) % palette.size]

fun kindLabel(k: Kind) = when (k) { Kind.LIVE -> "LIVE"; Kind.MOVIE -> "FILM"; Kind.SERIES -> "SERIE" }

fun qualityOf(item: Item): String? = QualityStore.get(item.id) ?: item.quality

class MetaState(val meta: Meta?, val done: Boolean)

@Composable
fun rememberMetaState(item: Item): MetaState {
    val ctx = LocalContext.current
    var m by remember(item.id) { mutableStateOf(MetaRepo.cached(item)) }
    var done by remember(item.id) {
        mutableStateOf(m != null || !MetaRepo.enabled() || item.kind == Kind.LIVE || MetaRepo.missed(item))
    }
    LaunchedEffect(item.id) {
        if (m == null && !done) {
            // Short pause: cards that only fly past while scrolling start no TMDB search (the effect is cancelled)
            kotlinx.coroutines.delay(250)
            m = MetaRepo.basic(ctx, item); done = true
        }
        // The slim cache lacks the description: reload the full entry from the device, but only for cards that stay
        // on screen. Before, every card read its file and recomposed while a grid was scrolled.
        else m?.let { cur ->
            if (cur.overview == null && cur.tmdbId > 0) {
                kotlinx.coroutines.delay(700)
                MetaRepo.basic(ctx, item)?.let { m = it }
            }
        }
    }
    return MetaState(m, done)
}

@Composable
fun rememberMeta(item: Item): Meta? = rememberMetaState(item).meta

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, light: Boolean = false, icon: AIcon? = null, trailing: AIcon? = null, raw: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val pulse = rememberPulse()
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "pill")
    val shape = RoundedCornerShape(50)
    val c = buttonColors(f, primary = light)
    Box(
        modifier.graphicsLayer { val k = s * pulse.scale; scaleX = k; scaleY = k }
            .clip(shape)
            .background(c.bg)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = { pulse.pulse(); onClick() })
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        IconLabel(icon, text, c.text, 17.sp, FontWeight.SemiBold, iconSize = 18.dp, trailing = trailing, raw = raw)
    }
}

@Composable
fun SmallPill(text: String, modifier: Modifier = Modifier, icon: AIcon? = null, trailing: AIcon? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val pulse = rememberPulse()
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "small")
    val shape = RoundedCornerShape(50)
    val c = buttonColors(f, primary = false)
    Box(
        modifier.graphicsLayer { val k = s * pulse.scale; scaleX = k; scaleY = k }
            .clip(shape)
            .background(c.bg)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = { pulse.pulse(); onClick() })
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) { IconLabel(icon, text, c.text, DS.TextSmall, FontWeight.SemiBold, iconSize = 15.dp, trailing = trailing) }
}

@Composable
fun Badge(text: String, bg: Color = Color(0x26FFFFFF), fg: Color = Color.White, icon: AIcon? = null, iconTint: Color = fg) {
    if (icon == null) Text(
        text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)
    ) else Row(
        Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(icon, iconTint, size = 14.dp)
        if (text.isNotEmpty()) Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Rating as a badge: star symbol + number. */
@Composable
fun RatingBadge(r: Double, bg: Color = DS.Scrim) = Badge(String.format("%.1f", r), bg, DS.Gold, AIcon.Star)

@Composable
fun QualityBadge(q: String) {
    val gold = q == "4K"
    Badge(q, if (gold) Color(0xFFFFB300) else Color(0xCC000000), if (gold) Color.Black else Color.White)
}

@Composable
fun SectionHeader(title: String) {
    Row(Modifier.padding(start = DS.PageX, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Accent1))
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Subtitle of a series card, e.g. "5 Staffeln · 62 Folgen" (German key text). */
fun seriesInfo(item: Item): String {
    // Series with a placeholder: seasons and episodes are not known yet (loaded on opening)
    if (XtreamLogic.isLazy(item) || StalkerLogic.isLazy(item)) return ""
    val sc = seasonCount(item)
    val ec = item.episodes.size
    val a = if (sc == 1) "1 Staffel" else "$sc Staffeln"
    return if (ec <= 0) a else a + " · " + (if (ec == 1) "1 Folge" else "$ec Folgen")
}

/** For cards the smaller TMDB image size is enough (w342 instead of w500): less data, faster, less memory. */
fun cardPoster(url: String): String = url.replace("/t/p/w500/", "/t/p/w342/")

@Composable
fun PosterCard(
    item: Item, onClick: () -> Unit, modifier: Modifier = Modifier,
    progress: Float? = null, subtitle: String? = null,
    fixedPosterHeight: Dp? = null, onFocus: () -> Unit = {}, titleLines: Int = 1
) {
    val st = rememberMetaState(item)
    val m = st.meta
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "poster")
    val border by animateColorAsState(if (f) Accent2 else Color.Transparent, label = "pborder")
    val shape = RoundedCornerShape(DS.RadiusM)
    val live = item.kind == Kind.LIVE
    val img: String? = when {
        live -> item.logo
        m?.poster != null -> cardPoster(m.poster)
        st.done -> item.logo
        else -> null
    }
    val posterMod = if (fixedPosterHeight != null) Modifier.fillMaxWidth().height(fixedPosterHeight)
    else Modifier.fillMaxWidth().aspectRatio(2f / 3f)
    Column(
        modifier.graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            // The space below the subtitle belongs to the card: when scrolling to the focused card, the
            // enlarged card (focus) also stays fully in view instead of being cut off at the bottom
            .padding(bottom = 14.dp)
    ) {
        Box(
            posterMod.focusGlow(f, shape).clip(shape)
                .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
                .border(DS.FocusBorder, border, shape),
            contentAlignment = Alignment.Center
        ) {
            // Round 17: placeholder (gradient, initials, icon, title) below the image, stays visible if it is missing or does not load
            if ((!live && (st.done || img != null)) || (live && img == null && st.done)) ArtPlaceholder(item, Modifier.fillMaxSize())
            if (img != null) ArtImage(
                img, item.title,
                if (live) Modifier.fillMaxSize().padding(20.dp) else Modifier.fillMaxSize(),
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop
            )
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                // Broken (on playback "no longer available" or failed several times): warning icon instead of artwork
                if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) Badge("Gestört", DS.Scrim, DS.Gold, AIcon.Warning) else Badge(kindLabel(item.kind), DS.Scrim)
            }
            qualityOf(item)?.let { Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { QualityBadge(it) } }
            m?.rating?.let {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    RatingBadge(it)
                }
            }
            if (Library.isWatch(item.id)) Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { Badge("Liste", DS.Scrim, Accent2, AIcon.Check) }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent2))
                }
            }
        }
        Text(raw = true, text = item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = titleLines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val sub = subtitle ?: when (item.kind) {
            Kind.SERIES -> seriesInfo(item)
            Kind.MOVIE -> m?.year ?: item.year?.toString() ?: "Film"
            Kind.LIVE -> item.group
        }
        Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LiveCard(item: Item, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "live")
    val border by animateColorAsState(if (f) Accent2 else Color.Transparent, label = "lborder")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.graphicsLayer { scaleX = s; scaleY = s }
            .focusGlow(f, shape)
            .clip(shape).background(Surf)
            .border(DS.FocusBorder, border, shape)
            .onFocusChanged { f = it.isFocused }
            .favKeys { Library.toggleFav(ctx, item.id) }
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .8f), Surf2))),
            contentAlignment = Alignment.Center
        ) {
            if (item.logo != null) ArtImage(item.logo, item.title, Modifier.fillMaxSize().padding(20.dp), contentScale = ContentScale.Fit)
            else Text(raw = true, text = item.title.take(2).uppercase(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(DS.RadiusXs))
                    .background(Color(0xFFFF3B5C)).padding(horizontal = 7.dp, vertical = 2.dp))
            if (Library.isFav(item.id)) AppIcon(AIcon.Star, DS.Gold, Modifier.align(Alignment.TopEnd).padding(10.dp), 20.dp)
            else qualityOf(item)?.let { Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { QualityBadge(it) } }
            if (DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, Modifier.align(Alignment.BottomEnd).padding(8.dp), 20.dp)
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(raw = true, text = item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = item.group, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
