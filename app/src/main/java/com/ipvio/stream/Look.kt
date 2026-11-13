package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** A color theme: name, surface (under white text) and highlight (on a dark ground). */
class AccentPreset(val name: String, val fill: Int, val highlight: Int)

/** Colors of a design (ground, surfaces, color 1, color 2, text on color 1). */
class LookPalette(val bg: Color, val surf: Color, val surf2: Color, val fill: Color, val high: Color, val onFill: Color)

/** Background theme: name, ground, surface, surface 2. */
class BgPreset(val name: String, val bg: Int, val surf: Int, val surf2: Int)

/**
 * Central design state. The colors in Ui.kt (Bg, Surf, Accent1 …) read from here,
 * so when switching the whole app changes immediately without any screen having to be adjusted.
 */
object Look {
    /** Default design: light green #4ADE80 (highlight #86EFAC) on navy #0B0F19. */
    const val BRAND_GREEN = 0x4ADE80
    const val BRAND_GREEN_HI = 0x86EFAC
    const val BRAND_NAVY = 0x0B0F19
    /** Default: IPVIO green on navy (round 13e: the design colors were removed again). */
    const val DEFAULT_ACCENT = 0
    const val DEFAULT_BG = 0

    val ACCENTS = listOf(
        AccentPreset("IPVIO", BRAND_GREEN, BRAND_GREEN_HI),
        AccentPreset("Violett & Türkis", 0x7C5CFF, 0x00D4C8),
        AccentPreset("Ozean", 0x2F6FE0, 0x5CD6FF),
        AccentPreset("Smaragd", 0x0A7F58, 0x4ADE80),
        AccentPreset("Sonnenuntergang", 0xD93F46, 0xFFB454),
        AccentPreset("Rosé", 0xC72F8F, 0xFF9ED2),
        AccentPreset("Gold", 0x9A6414, 0xFFD166),
        AccentPreset("Orange", 0xC2410C, 0xFFA64D),
        AccentPreset("Eisblau", 0x0E7490, 0x67E8F9),
        AccentPreset("Limette", 0x4D7C0F, 0xBEF264),
        AccentPreset("Purpur", 0x7E22CE, 0xE879F9),
        AccentPreset("Schiefer", 0x475569, 0xE2E8F0)
    )
    /** Color swatches for the custom color (2 rows of 10). */
    val SWATCHES = listOf(
        0xE53935, 0xF4511E, 0xFB8C00, 0xFFB300, 0xFDD835, 0x9CCC65, 0x4ADE80, 0x00C98D, 0x00BFA5, 0x26C6DA,
        0x29B6F6, 0x3D7BFF, 0x5C6BFF, 0x8E5CFF, 0xB44CF0, 0xE040C0, 0xFF4F8B, 0xFF6F61, 0xCFD8DC, 0x78909C
    )
    /** Index after the presets = custom color (freely chosen, see [custom]). */
    val CUSTOM get() = ACCENTS.size

    val BGS = listOf(
        BgPreset("IPVIO Navy", BRAND_NAVY, 0x151B2A, 0x1F2739),
        BgPreset("Mitternacht", 0x07080C, 0x12141C, 0x1B1E2A),
        BgPreset("OLED-Schwarz", 0x000000, 0x0B0C10, 0x15171E),
        BgPreset("Graphit", 0x121316, 0x1C1E24, 0x272A32),
        BgPreset("Tiefblau", 0x070B14, 0x101828, 0x1A2540),
        // New (1.2): backgrounds of the ready-made designs. Only appended at the end so saved numbers stay the same.
        BgPreset("Vampir", 0x282A36, 0x343746, 0x44475A),
        BgPreset("Eisfach", 0x2E3440, 0x3B4252, 0x4C566A),
        BgPreset("Tiefsee", 0x06161F, 0x0D2530, 0x143546),
        BgPreset("Waldboden", 0x0C1510, 0x16241B, 0x203428),
        BgPreset("Glut", 0x1A110C, 0x2A1B13, 0x3A2619)
    )

    /** A ready-made design: name, color theme (index into [ACCENTS]) and background (index into [BGS]). */
    class ReadyDesign(val name: String, val accent: Int, val bg: Int)

    val DESIGNS = listOf(
        ReadyDesign("Nachteule", 0, 0),
        ReadyDesign("Vampir-Disco", 10, 5),
        ReadyDesign("Eisbär im Eisfach", 8, 6),
        ReadyDesign("Tiefsee-Tauchgang", 2, 7),
        ReadyDesign("Waldschrat", 3, 8),
        ReadyDesign("Mitternachtssnack", 6, 2),
        ReadyDesign("Sonnenbrand", 4, 9)
    )

    /** Default: 85 % (index 1) – shows more content, elements stay easy to read. */
    const val DEFAULT_SCALE = 1

    val MOTION = listOf("Aus", "Dezent", "Normal")
    val SCALES = listOf("Automatisch", "85 %", "100 %", "115 %", "130 %")

    var accentIdx by mutableIntStateOf(DEFAULT_ACCENT)
    /** Custom color 1 (surfaces, buttons, bars). */
    var custom by mutableIntStateOf(BRAND_GREEN)
    /** Custom color 2 (highlight, text and frame on the background); -1 = automatic from color 1. */
    var custom2 by mutableIntStateOf(-1)
    var bgIdx by mutableIntStateOf(DEFAULT_BG)
    var motion by mutableIntStateOf(2)
    var uiScale by mutableIntStateOf(DEFAULT_SCALE)

    private fun bgRgb(): Int = BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg

    /** Color 1: preset or exactly the chosen custom color (the text on it adapts, see onAccent). */
    fun fillRgb(): Int = if (accentIdx in ACCENTS.indices) ACCENTS[accentIdx].fill else custom

    /** Color 2 automatic: derived from color 1 so that it is well readable on the background. */
    fun autoHighRgb(): Int = ThemeMath.accentFromRgb(custom, bgRgb()).second

    /** Color 2: preset, the chosen custom color 2 or automatic. */
    fun highRgb(): Int = when {
        accentIdx in ACCENTS.indices -> ACCENTS[accentIdx].highlight
        custom2 >= 0 -> custom2
        else -> autoHighRgb()
    }

    private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    val bg: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg)
    val surf: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf)
    val surf2: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf2)
    val accent1: Color get() = c(fillRgb())
    val accent2: Color get() = c(highRgb())
    /** Text on color-1 surfaces (white or navy, whichever is readable). */
    val onAccent: Color get() = c(ThemeMath.onAccent(fillRgb()))

    /** Colors of a design (even one only chosen tentatively) – for the preview in the settings, changes nothing in the app. */
    fun palette(accent: Int = accentIdx, cust: Int = custom, cust2: Int = custom2, bgI: Int = bgIdx): LookPalette {
        val b = BGS[bgI.coerceIn(0, BGS.lastIndex)]
        val fill = if (accent in ACCENTS.indices) ACCENTS[accent].fill else cust
        val high = when {
            accent in ACCENTS.indices -> ACCENTS[accent].highlight
            cust2 >= 0 -> cust2
            else -> ThemeMath.accentFromRgb(cust, b.bg).second
        }
        return LookPalette(c(b.bg), c(b.surf), c(b.surf2), c(fill), c(high), c(ThemeMath.onAccent(fill)))
    }

    fun reset() { accentIdx = DEFAULT_ACCENT; custom = BRAND_GREEN; custom2 = -1; bgIdx = DEFAULT_BG; motion = 2; uiScale = DEFAULT_SCALE }
}

/** Focus enlargement, depending on the chosen animation level. */
fun focusScale(target: Float): Float = ThemeMath.focusScale(target, Look.motion)
