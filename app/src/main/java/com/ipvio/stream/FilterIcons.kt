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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small line icons for the filter bars (drawn, not font characters – look the same on every device). */
enum class FIcon { Sort, Rating, Year, Genre, Country, View, Grid, Reset }

@Composable
fun FilterIcon(icon: FIcon, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        when (icon) {
            FIcon.Sort -> {
                drawLine(color, o(4f, 6f), o(20f, 6f), 2f * u, StrokeCap.Round)
                drawLine(color, o(4f, 12f), o(15f, 12f), 2f * u, StrokeCap.Round)
                drawLine(color, o(4f, 18f), o(10f, 18f), 2f * u, StrokeCap.Round)
            }
            FIcon.Rating -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 12f + (r * Math.cos(a)).toFloat()
                    val y = 12.8f + (r * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x * u, y * u) else p.lineTo(x * u, y * u)
                }
                p.close()
                drawPath(p, color, style = st)
            }
            FIcon.Year -> {
                drawRoundRect(color, o(3.5f, 5.5f), Size(17f * u, 15f * u), CornerRadius(3f * u), style = st)
                drawLine(color, o(3.5f, 10.5f), o(20.5f, 10.5f), 2f * u, StrokeCap.Round)
                drawLine(color, o(8f, 3f), o(8f, 7f), 2f * u, StrokeCap.Round)
                drawLine(color, o(16f, 3f), o(16f, 7f), 2f * u, StrokeCap.Round)
            }
            FIcon.Genre -> {
                // Clapperboard: frame + slanted stripe at the top
                drawRoundRect(color, o(3.5f, 9.5f), Size(17f * u, 11f * u), CornerRadius(2.5f * u), style = st)
                drawLine(color, o(3.5f, 9.5f), o(5.5f, 4.5f), 2f * u, StrokeCap.Round)
                drawLine(color, o(5.5f, 4.5f), o(20.5f, 4.5f), 2f * u, StrokeCap.Round)
                drawLine(color, o(10f, 9.5f), o(12f, 4.5f), 2f * u, StrokeCap.Round)
                drawLine(color, o(15.5f, 9.5f), o(17.5f, 4.5f), 2f * u, StrokeCap.Round)
            }
            FIcon.Country -> {
                drawCircle(color, 9f * u, o(12f, 12f), style = st)
                drawLine(color, o(3f, 12f), o(21f, 12f), 2f * u, StrokeCap.Round)
                drawOval(color, o(8f, 3f), Size(8f * u, 18f * u), style = st)
            }
            FIcon.View -> {
                drawRoundRect(color, o(3.5f, 4f), Size(17f * u, 9.5f * u), CornerRadius(2.5f * u), style = st)
                drawRoundRect(color, o(3.5f, 16.5f), Size(4f * u, 3.5f * u), CornerRadius(1f * u), style = st)
                drawRoundRect(color, o(10f, 16.5f), Size(4f * u, 3.5f * u), CornerRadius(1f * u), style = st)
                drawRoundRect(color, o(16.5f, 16.5f), Size(4f * u, 3.5f * u), CornerRadius(1f * u), style = st)
            }
            FIcon.Grid -> {
                for (cx in listOf(3.5f, 13.5f)) for (cy in listOf(3.5f, 13.5f))
                    drawRoundRect(color, o(cx, cy), Size(7f * u, 7f * u), CornerRadius(2f * u), style = st)
            }
            FIcon.Reset -> {
                drawArc(color, 40f, 290f, false, o(4.5f, 4.5f), Size(15f * u, 15f * u), style = st)
                val p = Path()
                p.moveTo(o(17.5f, 2.5f).x, o(17.5f, 2.5f).y)
                p.lineTo(o(18.8f, 7.2f).x, o(18.8f, 7.2f).y)
                p.lineTo(o(14f, 7.8f).x, o(14f, 7.8f).y)
                drawPath(p, color, style = st)
            }
        }
    }
}
