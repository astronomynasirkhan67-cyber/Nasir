package com.ipvio.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

/** Width of the display so headers can leave room on the right. */
val MetaBadgeWidth = 250.dp

/**
 * Subtle progress indicator at the top right while genres and countries are detected in the background.
 * Kept calm: dark capsule, small progress ring, two lines of text. Fades in and out softly.
 */
@Composable
fun MetaBadge(modifier: Modifier = Modifier) {
    AnimatedVisibility(Classifier.running && Classifier.total > 0, modifier, enter = fadeIn(tween(400)), exit = fadeOut(tween(600))) {
        val total = Classifier.total.coerceAtLeast(1)
        val done = Classifier.progress.coerceIn(0, total)
        val frac by animateFloatAsState(done.toFloat() / total, tween(500), label = "meta")
        val nf = NumberFormat.getIntegerInstance()
        val a1 = Accent1
        val shape = RoundedCornerShape(DS.RadiusM)
        Row(
            Modifier.width(MetaBadgeWidth).clip(shape)
                .background(Surf.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Canvas(Modifier.size(22.dp)) {
                val w = 2.5.dp.toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                drawArc(Color.White.copy(alpha = 0.10f), 0f, 360f, false, tl, sz, style = Stroke(w))
                drawArc(a1, -90f, 360f * frac, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (Classifier.phase == "Länder") "Länder werden ergänzt" else "Genres werden erkannt",
                    color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1
                )
                Text(nf.format(done) + " / " + nf.format(total) + " · " + (frac * 100).toInt() + " %", color = Muted, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
