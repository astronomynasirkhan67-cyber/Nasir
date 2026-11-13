package com.ipvio.stream

/**
 * Pure calculation logic for colors and scaling (without Android/Compose, hence testable on its own).
 * Colors are given as a 0xRRGGBB number.
 */
object ThemeMath {
    /** Relative luminance according to WCAG 2.x (0 = black, 1 = white). */
    fun luminance(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((rgb shr 16) and 0xFF) + 0.7152 * ch((rgb shr 8) and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    /** Contrast ratio of two colors (1 = equal, 21 = black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** HSV (h 0..360, s and v 0..1) to 0xRRGGBB. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        fun c(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /**
     * From a freely chosen hue two accent colors are formed that ALWAYS stay readable:
     * - Surface: white text sits on it, contrast at least 4.5 : 1.
     * - Highlight: lies as text or frame on the dark background, contrast at least 4.5 : 1.
     */
    fun accentFromHue(hue: Int, bg: Int = 0x07080C): Pair<Int, Int> {
        var v = 0.92f
        var fill = hsv(hue.toFloat(), 0.62f, v)
        while (contrast(0xFFFFFF, fill) < 4.5 && v > 0.2f) { v -= 0.02f; fill = hsv(hue.toFloat(), 0.62f, v) }
        // Highlight: first make it lighter if that is not enough (e.g. for blue), additionally desaturate.
        val hh = hue + 150f
        var w = 0.80f
        var hi = hsv(hh, 0.70f, w)
        while (contrast(hi, bg) < 4.5 && w < 1f) { w = (w + 0.02f).coerceAtMost(1f); hi = hsv(hh, 0.70f, w) }
        var sat = 0.70f
        while (contrast(hi, bg) < 4.5 && sat > 0f) { sat = (sat - 0.05f).coerceAtLeast(0f); hi = hsv(hh, sat, 1f) }
        return fill to hi
    }

    /** 0xRRGGBB -> floatArrayOf(h 0..360, s 0..1, v 0..1) */
    fun rgbToHsv(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    /**
     * From a freely chosen color the surface (white text on it, contrast >= 4.5) and
     * highlight (on a dark ground, contrast >= 4.5) are derived. The hue is preserved.
     */
    fun accentFromRgb(rgb: Int, bg: Int = 0x07080C): Pair<Int, Int> {
        val hsv = rgbToHsv(rgb)
        val h = hsv[0]
        val s = hsv[1]
        var v = hsv[2].coerceAtLeast(0.25f)
        var fill = hsv(h, s, v)
        while (contrast(0xFFFFFF, fill) < 4.5 && v > 0.05f) { v -= 0.02f; fill = hsv(h, s, v) }
        var w = maxOf(hsv[2], 0.80f)
        val hs = minOf(s, 0.75f)
        var hi = hsv(h, hs, w)
        while (contrast(hi, bg) < 4.5 && w < 1f) { w = (w + 0.02f).coerceAtMost(1f); hi = hsv(h, hs, w) }
        var sat = hs
        while (contrast(hi, bg) < 4.5 && sat > 0f) { sat = (sat - 0.05f).coerceAtLeast(0f); hi = hsv(h, sat, 1f) }
        return fill to hi
    }

    /** Dark text for very light accent colors (e.g. neon green): company color navy. */
    const val INK = 0x0B0F19

    /**
     * Text color on an accent surface: white as long as it is well readable (contrast >= 4), otherwise the better readable
     * of white and dark navy. So even very light colors like #39FF13 remain usable as a surface.
     */
    fun onAccent(fill: Int): Int {
        val w = contrast(0xFFFFFF, fill)
        if (w >= 4.0) return 0xFFFFFF
        return if (contrast(INK, fill) > w) INK else 0xFFFFFF
    }

    /** "#2F6FE0", "2f6fe0" or "26E" -> 0xRRGGBB, otherwise null. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6) return null
        return t.toIntOrNull(16)
    }

    fun hexOf(rgb: Int): String = "#" + String.format("%06X", rgb and 0xFFFFFF)

    /** Scaling on focusing depending on the animation level (0 = off, 1 = subtle, 2 = normal). */
    fun focusScale(target: Float, motion: Int): Float = when (motion) {
        0 -> 1f
        1 -> 1f + (target - 1f) * 0.4f
        else -> target
    }

    /**
     * Factor for the whole UI. Auto: devices with a very wide dp range (e.g. Fire TV with 1280 dp)
     * are brought to the design width of 960 dp so nothing looks too small.
     */
    fun uiFactor(mode: Int, screenWidthDp: Int, screenHeightDp: Int = 100_000): Float = when (mode) {
        1 -> 0.85f
        2 -> 1f
        3 -> 1.15f
        4 -> 1.3f
        // Phone in landscape (e.g. 780 x 360 dp): the design is 960 x 540 dp, so scale down so everything fits on the screen
        else -> if (screenWidthDp > 1000) (screenWidthDp / 960f).coerceIn(1f, 2f)
        else minOf(screenWidthDp / 960f, screenHeightDp / 540f, 1f).coerceIn(0.72f, 1f)
    }
}
