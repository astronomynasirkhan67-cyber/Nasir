package com.ipvio.stream

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** All player icons. They are drawn on a 24×24 grid and scale cleanly to any size. */
enum class PIcon {
    Play, Pause, Replay, Forward, PrevEp, NextEp, AudioSubs, Speed, Episodes, Settings,
    Channels, Guide, StarOn, StarOff, Aspect, External, Check, Chevron, Close
}

@Composable
fun PlayerIcon(icon: PIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) { drawPIcon(icon, tint) }
}

private fun DrawScope.ln(c: Color, u: Float, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 2f) {
    drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w * u, cap = StrokeCap.Round)
}

private fun DrawScope.strokeShape(c: Color, u: Float, w: Float = 2f, build: Path.(Float) -> Unit) {
    val p = Path()
    p.build(u)
    drawPath(p, c, style = Stroke(width = w * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Filled shape with slightly rounded corners (fill + thin round outline). */
private fun DrawScope.fillShape(c: Color, u: Float, round: Float = 1.4f, build: Path.(Float) -> Unit) {
    val p = Path()
    p.build(u)
    drawPath(p, c)
    drawPath(p, c, style = Stroke(width = round * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun starPath(u: Float): Path {
    val p = Path()
    val cx = 12f
    val cy = 12.8f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 9.2f else 4.0f
        val a = -PI / 2 + i * PI / 5
        val x = (cx + r * cos(a)).toFloat() * u
        val y = (cy + r * sin(a)).toFloat() * u
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

private fun DrawScope.drawPIcon(icon: PIcon, c: Color) {
    val u = size.minDimension / 24f
    val mid = Offset(size.width / 2f, size.height / 2f)
    when (icon) {
        PIcon.Play -> fillShape(c, u, 2f) { k ->
            moveTo(9f * k, 6.5f * k); lineTo(17.5f * k, 12f * k); lineTo(9f * k, 17.5f * k); close()
        }

        PIcon.Pause -> {
            drawRoundRect(c, Offset(6.5f * u, 5f * u), Size(3.8f * u, 14f * u), CornerRadius(1.4f * u))
            drawRoundRect(c, Offset(13.7f * u, 5f * u), Size(3.8f * u, 14f * u), CornerRadius(1.4f * u))
        }

        PIcon.Replay, PIcon.Forward -> {
            val body: DrawScope.() -> Unit = {
                drawArc(
                    c, 270f, 300f, false, Offset(4f * u, 5.5f * u), Size(16f * u, 16f * u),
                    style = Stroke(width = 2f * u, cap = StrokeCap.Round)
                )
                fillShape(c, u, 1.2f) { k ->
                    moveTo(9.2f * k, 5.5f * k); lineTo(13f * k, 2.8f * k); lineTo(13f * k, 8.2f * k); close()
                }
            }
            if (icon == PIcon.Forward) scale(-1f, 1f, mid) { body() } else body()
        }

        PIcon.PrevEp, PIcon.NextEp -> {
            val body: DrawScope.() -> Unit = {
                drawRoundRect(c, Offset(5.5f * u, 5.5f * u), Size(2.2f * u, 13f * u), CornerRadius(1.1f * u))
                fillShape(c, u, 1.4f) { k ->
                    moveTo(18.5f * k, 6.5f * k); lineTo(10f * k, 12f * k); lineTo(18.5f * k, 17.5f * k); close()
                }
            }
            if (icon == PIcon.NextEp) scale(-1f, 1f, mid) { body() } else body()
        }

        PIcon.AudioSubs -> {
            drawRoundRect(c, Offset(3f * u, 5f * u), Size(18f * u, 14f * u), CornerRadius(3f * u), style = Stroke(width = 2f * u))
            ln(c, u, 7f, 10.4f, 11f, 10.4f, 1.8f)
            ln(c, u, 13f, 10.4f, 17f, 10.4f, 1.8f)
            ln(c, u, 7f, 14.2f, 9.8f, 14.2f, 1.8f)
            ln(c, u, 11.8f, 14.2f, 17f, 14.2f, 1.8f)
        }

        PIcon.Speed -> {
            drawArc(
                c, 180f, 180f, false, Offset(3.5f * u, 5.5f * u), Size(17f * u, 17f * u),
                style = Stroke(width = 2f * u, cap = StrokeCap.Round)
            )
            ln(c, u, 3.5f, 14f, 6f, 14f)
            ln(c, u, 18f, 14f, 20.5f, 14f)
            ln(c, u, 12f, 14f, 16.2f, 9.8f)
            drawCircle(c, radius = 1.7f * u, center = Offset(12f * u, 14f * u))
        }

        PIcon.Episodes -> {
            ln(c, u, 7f, 4.8f, 17f, 4.8f, 1.8f)
            drawRoundRect(c, Offset(4f * u, 8.2f * u), Size(16f * u, 11.8f * u), CornerRadius(2.6f * u), style = Stroke(width = 2f * u))
            fillShape(c, u, 1f) { k ->
                moveTo(10.6f * k, 11.4f * k); lineTo(14.8f * k, 14.1f * k); lineTo(10.6f * k, 16.8f * k); close()
            }
        }

        PIcon.Settings -> {
            val ys = floatArrayOf(6.5f, 12f, 17.5f)
            val ks = floatArrayOf(15f, 8.5f, 14f)
            for (i in 0..2) {
                val y = ys[i]
                val k = ks[i]
                ln(c, u, 4f, y, k - 3.6f, y)
                ln(c, u, k + 3.6f, y, 20f, y)
                drawCircle(c, radius = 2.3f * u, center = Offset(k * u, y * u), style = Stroke(width = 1.9f * u))
            }
        }

        PIcon.Channels -> {
            for (y in floatArrayOf(7f, 12f, 17f)) {
                drawCircle(c, radius = 1.5f * u, center = Offset(5f * u, y * u))
                ln(c, u, 9f, y, 20f, y)
            }
        }

        PIcon.Guide -> {
            drawRoundRect(c, Offset(3.5f * u, 4.5f * u), Size(17f * u, 15f * u), CornerRadius(2.6f * u), style = Stroke(width = 2f * u))
            ln(c, u, 3.5f, 9.6f, 20.5f, 9.6f, 1.8f)
            ln(c, u, 7f, 13f, 11f, 13f, 1.8f)
            ln(c, u, 13f, 13f, 17f, 13f, 1.8f)
            ln(c, u, 7f, 16.2f, 9.6f, 16.2f, 1.8f)
            ln(c, u, 11.6f, 16.2f, 17f, 16.2f, 1.8f)
        }

        PIcon.StarOn -> {
            val p = starPath(u)
            drawPath(p, c)
            drawPath(p, c, style = Stroke(width = 1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        PIcon.StarOff -> drawPath(
            starPath(u), c,
            style = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        PIcon.Aspect -> {
            strokeShape(c, u, 2f) { k -> moveTo(4f * k, 9f * k); lineTo(4f * k, 4f * k); lineTo(9f * k, 4f * k) }
            strokeShape(c, u, 2f) { k -> moveTo(15f * k, 4f * k); lineTo(20f * k, 4f * k); lineTo(20f * k, 9f * k) }
            strokeShape(c, u, 2f) { k -> moveTo(20f * k, 15f * k); lineTo(20f * k, 20f * k); lineTo(15f * k, 20f * k) }
            strokeShape(c, u, 2f) { k -> moveTo(9f * k, 20f * k); lineTo(4f * k, 20f * k); lineTo(4f * k, 15f * k) }
            drawRoundRect(c, Offset(8.6f * u, 9.2f * u), Size(6.8f * u, 5.6f * u), CornerRadius(1.2f * u), style = Stroke(width = 1.8f * u))
        }

        PIcon.External -> {
            strokeShape(c, u, 2f) { k ->
                moveTo(10f * k, 5f * k); lineTo(7f * k, 5f * k); quadraticTo(4f * k, 5f * k, 4f * k, 8f * k)
                lineTo(4f * k, 17f * k); quadraticTo(4f * k, 20f * k, 7f * k, 20f * k)
                lineTo(16f * k, 20f * k); quadraticTo(19f * k, 20f * k, 19f * k, 17f * k); lineTo(19f * k, 14f * k)
            }
            ln(c, u, 12.5f, 11.5f, 19.5f, 4.5f)
            strokeShape(c, u, 2f) { k -> moveTo(14.5f * k, 4.5f * k); lineTo(19.5f * k, 4.5f * k); lineTo(19.5f * k, 9.5f * k) }
        }

        PIcon.Check -> strokeShape(c, u, 2.4f) { k ->
            moveTo(5f * k, 12.5f * k); lineTo(10f * k, 17.5f * k); lineTo(19f * k, 7f * k)
        }

        PIcon.Chevron -> strokeShape(c, u, 2.4f) { k ->
            moveTo(9f * k, 5.5f * k); lineTo(15.5f * k, 12f * k); lineTo(9f * k, 18.5f * k)
        }

        PIcon.Close -> {
            ln(c, u, 6f, 6f, 18f, 18f, 2.4f)
            ln(c, u, 18f, 6f, 6f, 18f, 2.4f)
        }
    }
}
