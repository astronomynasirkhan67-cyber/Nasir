package com.ipvio.stream

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Icons for the whole app. All are drawn (24×24 grid), not taken from a font:
 * no emojis, which look colorful, different or missing depending on the device.
 */
enum class AIcon {
    Play, Star, StarOutline, Check, Close, Plus, Warning, Bell, BellOff, Mic, Phone, Info,
    Grid, Categories, Lists, Refresh, External, Up, Down, Left, Right, Caret, Dot, Gear,
    Sparkle, Music, Tag, Live, Download, Person, Lock, Storage, Pulse, Backspace, History,
    Tomato, Rewind, FastForward, Catchup, Eye, EyeOff, Globe,
    // Round 14
    Edit, Heart, HeartFilled, Sliders, Layers
}

@Composable
fun AppIcon(icon: AIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) { drawAIcon(icon, tint) }
}

/** Icon + text in one row (for buttons, badges, menu entries). */
@Composable
fun IconLabel(
    icon: AIcon?, text: String, color: Color, fontSize: TextUnit, fontWeight: FontWeight? = null,
    iconSize: Dp = 18.dp, iconTint: Color = color, trailing: AIcon? = null, raw: Boolean = false, maxLines: Int = 1,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) AppIcon(icon, iconTint, size = iconSize)
        if (text.isNotEmpty()) Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, raw = raw,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (trailing != null) AppIcon(trailing, iconTint, size = iconSize)
    }
}

private fun DrawScope.l(c: Color, u: Float, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 2f) {
    drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = w * u, cap = StrokeCap.Round)
}

private fun DrawScope.poly(c: Color, u: Float, w: Float, closed: Boolean, vararg pts: Float) {
    val p = Path()
    p.moveTo(pts[0] * u, pts[1] * u)
    var i = 2
    while (i < pts.size) { p.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
    if (closed) p.close()
    drawPath(p, c, style = Stroke(width = w * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.fillPoly(c: Color, u: Float, vararg pts: Float) {
    val p = Path()
    p.moveTo(pts[0] * u, pts[1] * u)
    var i = 2
    while (i < pts.size) { p.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
    p.close()
    drawPath(p, c)
    drawPath(p, c, style = Stroke(width = 1.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.ring(c: Color, u: Float, cx: Float, cy: Float, r: Float, w: Float = 2f) {
    drawCircle(c, radius = r * u, center = Offset(cx * u, cy * u), style = Stroke(width = w * u))
}

private fun DrawScope.arc(c: Color, u: Float, cx: Float, cy: Float, r: Float, start: Float, sweep: Float, w: Float = 2f) {
    drawArc(c, start, sweep, false, Offset((cx - r) * u, (cy - r) * u), Size(2 * r * u, 2 * r * u),
        style = Stroke(width = w * u, cap = StrokeCap.Round))
}

private fun star(u: Float): Path {
    val p = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 9.2f else 4.0f
        val a = -PI / 2 + i * PI / 5
        val x = (12f + r * cos(a)).toFloat() * u
        val y = (12.8f + r * sin(a)).toFloat() * u
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** Bell (for reminders). */
private fun DrawScope.bell(c: Color, u: Float) {
    val p = Path()
    p.moveTo(5f * u, 17f * u)
    p.lineTo(6.5f * u, 14.5f * u)
    p.lineTo(6.5f * u, 10.5f * u)
    p.cubicTo(6.5f * u, 7f * u, 9f * u, 4.5f * u, 12f * u, 4.5f * u)
    p.cubicTo(15f * u, 4.5f * u, 17.5f * u, 7f * u, 17.5f * u, 10.5f * u)
    p.lineTo(17.5f * u, 14.5f * u)
    p.lineTo(19f * u, 17f * u)
    p.close()
    drawPath(p, c, style = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    arc(c, u, 12f, 18.6f, 2.2f, 0f, 180f, 2f)
    l(c, u, 12f, 2.8f, 12f, 4.5f)
}

/** Clock with a counterclockwise arrow (history). */
private fun DrawScope.clockBack(c: Color, u: Float) {
    arc(c, u, 12.5f, 12.5f, 8f, 200f, 300f)
    fillPoly(c, u, 2.6f, 8.6f, 6.8f, 8.2f, 4.2f, 12.6f)
    l(c, u, 12.5f, 8f, 12.5f, 12.8f)
    l(c, u, 12.5f, 12.8f, 15.6f, 14.8f)
}

private fun DrawScope.eye(c: Color, u: Float) {
    val p = Path()
    p.moveTo(2.5f * u, 12f * u)
    p.quadraticTo(12f * u, 2.5f * u, 21.5f * u, 12f * u)
    p.quadraticTo(12f * u, 21.5f * u, 2.5f * u, 12f * u)
    p.close()
    drawPath(p, c, style = Stroke(width = 2f * u, join = StrokeJoin.Round))
    ring(c, u, 12f, 12f, 3f)
}

private fun DrawScope.drawAIcon(icon: AIcon, c: Color) {
    val u = size.minDimension / 24f
    when (icon) {
        AIcon.Play -> fillPoly(c, u, 8f, 5.5f, 18.5f, 12f, 8f, 18.5f)
        AIcon.Star -> { val p = star(u); drawPath(p, c); drawPath(p, c, style = Stroke(width = 1.4f * u, join = StrokeJoin.Round)) }
        AIcon.StarOutline -> drawPath(star(u), c, style = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        AIcon.Check -> poly(c, u, 2.6f, false, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
        AIcon.Close -> { l(c, u, 6.5f, 6.5f, 17.5f, 17.5f, 2.4f); l(c, u, 17.5f, 6.5f, 6.5f, 17.5f, 2.4f) }
        AIcon.Plus -> { l(c, u, 12f, 5f, 12f, 19f, 2.4f); l(c, u, 5f, 12f, 19f, 12f, 2.4f) }
        AIcon.Warning -> {
            poly(c, u, 2f, true, 12f, 3.5f, 21.5f, 20f, 2.5f, 20f)
            l(c, u, 12f, 9.5f, 12f, 14f, 2.2f)
            drawCircle(c, radius = 1.3f * u, center = Offset(12f * u, 17f * u))
        }
        AIcon.Bell -> bell(c, u)
        AIcon.BellOff -> { bell(c, u); l(c, u, 4f, 3.5f, 20f, 20.5f, 2.2f) }
        AIcon.Mic -> {
            drawRoundRect(c, Offset(9f * u, 3f * u), Size(6f * u, 11f * u), CornerRadius(3f * u), style = Stroke(width = 2f * u))
            arc(c, u, 12f, 11f, 6f, 0f, 180f)
            l(c, u, 12f, 17f, 12f, 20.5f)
            l(c, u, 8.5f, 20.5f, 15.5f, 20.5f)
        }
        AIcon.Phone -> {
            drawRoundRect(c, Offset(7f * u, 2.5f * u), Size(10f * u, 19f * u), CornerRadius(2.4f * u), style = Stroke(width = 2f * u))
            l(c, u, 10.5f, 18f, 13.5f, 18f, 1.8f)
        }
        AIcon.Info -> {
            ring(c, u, 12f, 12f, 9f)
            l(c, u, 12f, 11f, 12f, 16.5f, 2.2f)
            drawCircle(c, radius = 1.3f * u, center = Offset(12f * u, 7.8f * u))
        }
        AIcon.Grid -> for (x in floatArrayOf(4f, 13.5f)) for (y in floatArrayOf(4f, 13.5f)) {
            drawRoundRect(c, Offset(x * u, y * u), Size(6.5f * u, 6.5f * u), CornerRadius(1.6f * u), style = Stroke(width = 2f * u))
        }
        AIcon.Categories -> {
            drawRoundRect(c, Offset(3.5f * u, 4f * u), Size(7f * u, 7f * u), CornerRadius(1.6f * u), style = Stroke(width = 2f * u))
            ring(c, u, 17f, 7.5f, 3.6f)
            drawRoundRect(c, Offset(3.5f * u, 13.5f * u), Size(7f * u, 7f * u), CornerRadius(1.6f * u), style = Stroke(width = 2f * u))
            poly(c, u, 2f, true, 17f, 13.5f, 20.8f, 20.5f, 13.2f, 20.5f)
        }
        AIcon.Lists -> for (y in floatArrayOf(6.5f, 12f, 17.5f)) {
            drawCircle(c, radius = 1.4f * u, center = Offset(4.8f * u, y * u))
            l(c, u, 9f, y, 20f, y)
        }
        AIcon.Refresh -> {
            arc(c, u, 12f, 12.5f, 7.5f, -60f, 300f)
            fillPoly(c, u, 15.2f, 2.8f, 19.8f, 6.4f, 14.8f, 8.4f)
        }
        AIcon.External -> {
            poly(c, u, 2f, false, 10f, 5f, 5f, 5f, 5f, 19f, 19f, 19f, 19f, 14f)
            l(c, u, 12.5f, 11.5f, 19.5f, 4.5f)
            poly(c, u, 2f, false, 14.5f, 4.5f, 19.5f, 4.5f, 19.5f, 9.5f)
        }
        AIcon.Up -> poly(c, u, 2.4f, false, 5.5f, 15f, 12f, 8.5f, 18.5f, 15f)
        AIcon.Down -> poly(c, u, 2.4f, false, 5.5f, 9f, 12f, 15.5f, 18.5f, 9f)
        AIcon.Left -> poly(c, u, 2.4f, false, 15f, 5.5f, 8.5f, 12f, 15f, 18.5f)
        AIcon.Right -> poly(c, u, 2.4f, false, 9f, 5.5f, 15.5f, 12f, 9f, 18.5f)
        AIcon.Caret -> fillPoly(c, u, 7f, 9.5f, 17f, 9.5f, 12f, 15.5f)
        AIcon.Dot -> drawCircle(c, radius = 4.5f * u, center = Offset(12f * u, 12f * u))
        AIcon.Gear -> {
            for (i in 0 until 8) {
                val a = i * PI / 4 + PI / 8
                val x1 = (12f + 6f * cos(a)).toFloat(); val y1 = (12f + 6f * sin(a)).toFloat()
                val x2 = (12f + 9.2f * cos(a)).toFloat(); val y2 = (12f + 9.2f * sin(a)).toFloat()
                drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = 3.6f * u, cap = StrokeCap.Butt)
            }
            ring(c, u, 12f, 12f, 6.4f, 2.6f)
            ring(c, u, 12f, 12f, 2.2f, 2f)
        }
        AIcon.Sparkle -> {
            fillPoly(c, u, 10f, 3f, 12f, 9f, 18f, 11f, 12f, 13f, 10f, 19f, 8f, 13f, 2f, 11f, 8f, 9f)
            fillPoly(c, u, 18f, 14.5f, 19f, 17f, 21.5f, 18f, 19f, 19f, 18f, 21.5f, 17f, 19f, 14.5f, 18f, 17f, 17f)
        }
        AIcon.Music -> {
            l(c, u, 9f, 17f, 9f, 5f)
            l(c, u, 19f, 15f, 19f, 3.5f)
            l(c, u, 9f, 5f, 19f, 3.5f, 2.6f)
            drawCircle(c, radius = 2.6f * u, center = Offset(6.6f * u, 17.4f * u))
            drawCircle(c, radius = 2.6f * u, center = Offset(16.6f * u, 15.4f * u))
        }
        AIcon.Tag -> {
            poly(c, u, 2f, true, 3.5f, 4.5f, 11.5f, 4.5f, 20f, 13f, 13f, 20f, 3.5f, 11.5f)
            drawCircle(c, radius = 1.6f * u, center = Offset(7.8f * u, 8.8f * u))
        }
        AIcon.Live -> {
            drawCircle(c, radius = 2.4f * u, center = Offset(12f * u, 12f * u))
            arc(c, u, 12f, 12f, 5.5f, -40f, 80f); arc(c, u, 12f, 12f, 5.5f, 140f, 80f)
            arc(c, u, 12f, 12f, 9f, -40f, 80f); arc(c, u, 12f, 12f, 9f, 140f, 80f)
        }
        AIcon.Download -> {
            l(c, u, 12f, 3.5f, 12f, 14.5f)
            poly(c, u, 2f, false, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
            poly(c, u, 2f, false, 4.5f, 15.5f, 4.5f, 20f, 19.5f, 20f, 19.5f, 15.5f)
        }
        AIcon.Person -> {
            ring(c, u, 12f, 8f, 4f)
            arc(c, u, 12f, 21f, 7.5f, 180f, 180f)
        }
        AIcon.Lock -> {
            drawRoundRect(c, Offset(5f * u, 10.5f * u), Size(14f * u, 10f * u), CornerRadius(2.2f * u), style = Stroke(width = 2f * u))
            arc(c, u, 12f, 10.5f, 4.5f, 180f, 180f)
            l(c, u, 7.5f, 10.5f, 7.5f, 9.5f); l(c, u, 16.5f, 10.5f, 16.5f, 9.5f)
            drawCircle(c, radius = 1.4f * u, center = Offset(12f * u, 15.5f * u))
        }
        AIcon.Storage -> {
            drawOval(c, Offset(4.5f * u, 3.5f * u), Size(15f * u, 5f * u), style = Stroke(width = 2f * u))
            l(c, u, 4.5f, 6f, 4.5f, 18f); l(c, u, 19.5f, 6f, 19.5f, 18f)
            drawArc(c, 0f, 180f, false, Offset(4.5f * u, 9.5f * u), Size(15f * u, 5f * u), style = Stroke(width = 2f * u))
            drawArc(c, 0f, 180f, false, Offset(4.5f * u, 15.5f * u), Size(15f * u, 5f * u), style = Stroke(width = 2f * u))
        }
        AIcon.Pulse -> poly(c, u, 2.2f, false, 2.5f, 12.5f, 7f, 12.5f, 9.5f, 6f, 13.5f, 18.5f, 16f, 12.5f, 21.5f, 12.5f)
        AIcon.Backspace -> {
            poly(c, u, 2f, true, 8f, 5.5f, 20.5f, 5.5f, 20.5f, 18.5f, 8f, 18.5f, 2.5f, 12f)
            l(c, u, 11.5f, 9f, 17f, 15f); l(c, u, 17f, 9f, 11.5f, 15f)
        }
        AIcon.History -> clockBack(c, u)
        AIcon.Catchup -> {
            arc(c, u, 12.5f, 12.5f, 8f, 200f, 300f)
            fillPoly(c, u, 2.6f, 8.6f, 6.8f, 8.2f, 4.2f, 12.6f)
            fillPoly(c, u, 10.5f, 9f, 16f, 12.5f, 10.5f, 16f)
        }
        AIcon.Tomato -> {
            drawCircle(c, radius = 8f * u, center = Offset(12f * u, 13.5f * u))
            fillPoly(Color(0xFF2E9E4F), u, 12f, 3f, 14f, 6.5f, 18f, 5.5f, 15f, 8.5f, 9f, 8.5f, 6f, 5.5f, 10f, 6.5f)
        }
        AIcon.Rewind -> { fillPoly(c, u, 12f, 6f, 12f, 18f, 3f, 12f); fillPoly(c, u, 21f, 6f, 21f, 18f, 12f, 12f) }
        AIcon.FastForward -> { fillPoly(c, u, 3f, 6f, 3f, 18f, 12f, 12f); fillPoly(c, u, 12f, 6f, 12f, 18f, 21f, 12f) }
        AIcon.Eye -> eye(c, u)
        AIcon.EyeOff -> { eye(c, u); l(c, u, 4f, 4f, 20f, 20f, 2.2f) }
        AIcon.Globe -> {
            ring(c, u, 12f, 12f, 9f)
            drawOval(c, Offset(8f * u, 3f * u), Size(8f * u, 18f * u), style = Stroke(width = 1.8f * u))
            l(c, u, 3.5f, 9f, 20.5f, 9f, 1.8f)
            l(c, u, 3.5f, 15f, 20.5f, 15f, 1.8f)
        }
        // Pencil (rename)
        AIcon.Edit -> {
            poly(c, u, 2f, true, 15.5f, 4.5f, 19.5f, 8.5f, 9f, 19f, 4.5f, 19.5f, 5f, 15f)
            l(c, u, 13.5f, 6.5f, 17.5f, 10.5f)
        }
        // Heart (favorites for movies and series)
        AIcon.Heart, AIcon.HeartFilled -> {
            val p = Path()
            p.moveTo(12f * u, 19.5f * u)
            p.cubicTo(5f * u, 14.5f * u, 3f * u, 11.5f * u, 3f * u, 8.6f * u)
            p.cubicTo(3f * u, 6f * u, 5f * u, 4f * u, 7.5f * u, 4f * u)
            p.cubicTo(9.4f * u, 4f * u, 11f * u, 5.1f * u, 12f * u, 6.8f * u)
            p.cubicTo(13f * u, 5.1f * u, 14.6f * u, 4f * u, 16.5f * u, 4f * u)
            p.cubicTo(19f * u, 4f * u, 21f * u, 6f * u, 21f * u, 8.6f * u)
            p.cubicTo(21f * u, 11.5f * u, 19f * u, 14.5f * u, 12f * u, 19.5f * u)
            p.close()
            if (icon == AIcon.HeartFilled) drawPath(p, c)
            drawPath(p, c, style = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Sliders (adjust view)
        AIcon.Sliders -> {
            l(c, u, 4f, 7f, 20f, 7f); l(c, u, 4f, 17f, 20f, 17f)
            drawCircle(c, 2.6f * u, Offset(9f * u, 7f * u)); drawCircle(c, 2.6f * u, Offset(15f * u, 17f * u))
        }
        // Stacked layers (several sources)
        AIcon.Layers -> {
            poly(c, u, 2f, true, 12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f)
            poly(c, u, 2f, false, 3f, 13f, 12f, 18f, 21f, 13f)
        }
    }
}
