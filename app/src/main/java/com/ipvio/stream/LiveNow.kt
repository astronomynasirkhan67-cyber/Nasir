package com.ipvio.stream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * Round 17b – Live TV in "Cinematic" style (design image 3): background in the color of the highlighted channel with a large,
 * faint logo; on the right, large, what is on now (LIVE, title, description, timeline) and what comes next.
 */

/** Background of the live page: color and logo of the highlighted channel, softly cross-faded. */
@Composable
fun LiveBackdrop(ch: Item?, modifier: Modifier = Modifier) {
    // Offscreen: the background (color, two radial gradients, logo, two gradients) is drawn once into a layer and only
    // copied on each frame while the channel list scrolls, instead of filling the full screen six times per frame
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.background(Bg)) {
        // Own cross-fade instead of Crossfade: Crossfade fades each child with a default layer, which renders every child
        // into an extra full-screen offscreen buffer on each frame of the 700 ms fade. ModulateAlpha applies the alpha to
        // the draw calls directly (no extra buffer). Timing and easing are the same as before.
        val layers = remember { mutableStateListOf<BackdropFade>().apply { if (ch != null) add(BackdropFade(ch, Animatable(1f))) } }
        LaunchedEffect(ch) {
            if (ch == layers.lastOrNull()?.item && ch != null) return@LaunchedEffect
            if (ch == null && layers.isEmpty()) return@LaunchedEffect
            val fresh = ch?.let { BackdropFade(it, Animatable(0f)) }
            if (fresh != null) layers.add(fresh)
            val spec = tween<Float>(if (Look.motion == 0) 0 else 700)
            coroutineScope {
                for (l in layers.toList()) launch { l.alpha.animateTo(if (l === fresh) 1f else 0f, spec) }
            }
            layers.removeAll { it !== fresh }
        }
        for (l in layers) key(l) {
            BackdropTint(l.item, Modifier.fillMaxSize().graphicsLayer { alpha = l.alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha })
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = .9f), 0.55f to Bg.copy(alpha = .35f), 1f to Bg.copy(alpha = .15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg.copy(alpha = .9f))))
    }
}

/** One channel in the backdrop cross-fade (identity matters: the same channel may fade in again while it still fades out). */
private class BackdropFade(val item: Item, val alpha: Animatable<Float, AnimationVector1D>)

/** Color glow and faint logo of one channel (the part of the backdrop that cross-fades). */
@Composable
private fun BackdropTint(c: Item, modifier: Modifier) {
    Box(modifier) {
        val col = colorFor(c)
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), radius = 1400f, center = androidx.compose.ui.geometry.Offset(1500f, 380f))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(col, Color.White, 0.2f).copy(alpha = 0.22f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(1900f, 900f))))
        if (c.logo != null) AsyncImage(
            c.logo, null,
            Modifier.align(Alignment.TopEnd).padding(top = dpx(60), end = dpx(80)).size(dpx(360)).graphicsLayer { alpha = 0.12f },
            contentScale = ContentScale.Fit
        )
    }
}

/** What is on the highlighted channel: large, with timeline and the next shows. */
@Composable
fun LiveNowPanel(ch: Item?, modifier: Modifier = Modifier) {
    if (ch == null) return
    Crossfade(ch, animationSpec = tween(if (Look.motion == 0) 0 else 400), label = "liveNow", modifier = modifier) { c ->
        // null = still loading (cached data is shown right away); parsing off the main thread
        val loaded by produceState(Epg.peek(c), c.id) { if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Epg.now(c) } }
        val epg = loaded ?: emptyList()
        val nowE = Epg.current(epg)
        val next = if (nowE != null) epg.filter { it.start >= nowE.end }.take(2) else epg.take(2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                LiveBadge()
                Text(raw = true, text = c.title + (nowE?.let { "  ·  " + hhmm(it.start) + " – " + hhmm(it.end) } ?: ""),
                    color = Tok.TextMid, fontSize = spx(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(raw = true, text = nowE?.title ?: c.title, color = Tok.TextHi, fontSize = spx(68), lineHeight = spx(70),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            nowE?.desc?.takeIf { it.isNotBlank() }?.let {
                Text(raw = true, text = it, color = Tok.TextMid, fontSize = spx(21), lineHeight = spx(30), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                    Text(raw = true, text = hhmm(nowE.start), color = Muted, fontSize = spx(17))
                    Box(Modifier.weight(1f).height(dpx(6)).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Accent1))
                    }
                    Text(raw = true, text = hhmm(nowE.end), color = Muted, fontSize = spx(17))
                }
            }
            if (next.isNotEmpty()) Row(Modifier.padding(top = dpx(12)), horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                next.forEachIndexed { i, e ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(dpx(18))).background(Color(0x12FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(dpx(18))).padding(dpx(18)),
                        verticalArrangement = Arrangement.spacedBy(dpx(6))
                    ) {
                        Text((if (i == 0) Lang.tr("Danach") + " · " else "") + hhmm(e.start), raw = true,
                            color = if (i == 0) Accent2 else Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                        Text(raw = true, text = e.title, color = Tok.TextHi, fontSize = spx(21), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (loaded != null && nowE == null && epg.isEmpty()) Text("Für diesen Sender gibt es kein Programm.", color = Muted, fontSize = spx(18))
        }
    }
}

/** Red "LIVE" with a pulsing dot (calm without animations). */
@Composable
fun LiveBadge() {
    // The dot pulses in small steps (10 per second) instead of on every frame. The continuous animation made the whole
    // Live TV screen redraw 60 times per second, also while the channel list scrolls (expensive on Fire TV sticks).
    var a by remember { mutableFloatStateOf(1f) }
    if (Look.motion != 0) LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            kotlinx.coroutines.delay(100)
            i = (i + 1) % 16
            a = 1f - 0.7f * (if (i <= 8) i / 8f else (16 - i) / 8f)
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(dpx(8))).background(Color(0xFFE11D48)).padding(horizontal = dpx(12), vertical = dpx(4)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(9)).graphicsLayer { alpha = if (Look.motion == 0) 1f else a }.clip(RoundedCornerShape(50)).background(Color.White))
        Text(raw = true, text = "LIVE", color = Color.White, fontSize = spx(15), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}
