package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Scale of the design (user's images, 1658 px wide). All measurements of the home screen, movies/series, sidebar and header
 * are given in "design pixels" and converted to the real screen width – so the app looks on every device
 * exactly like the design (same proportions), independent of the "Display size" setting.
 */
object Design {
    const val WIDTH = 1658f
    /** dp per design pixel; set in MainActivity from the screen width (1130 dp → 0.68). */
    var k by mutableFloatStateOf(0.68f)
}

/** Design pixels → dp. */
fun dpx(v: Number): Dp = (v.toFloat() * Design.k).dp

/** Design pixels → sp (font size). */
fun spx(v: Number): TextUnit = typeSize(v.toFloat()).sp

/**
 * Legibility from 3 m (round 17): small design sizes do not become arbitrarily small. Below about 19 design pixels the
 * text stays at least 13 sp (or 85% of the design value if smaller); large titles follow the design exactly.
 */
fun typeSize(v: Float): Float = maxOf(v * Design.k, minOf(v * 0.85f, 13f))
