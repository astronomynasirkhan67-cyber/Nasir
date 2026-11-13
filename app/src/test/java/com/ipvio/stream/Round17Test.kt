package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Addendum 17: subtitle offset, technical-info texts, update rules, history in the menu. */
class Round17Test {
    // T224
    @Test fun technik_texte() {
        assertEquals("24", TechText.fps(24f))
        assertEquals("23.976", TechText.fps(23.976f).replace(',', '.'))
        assertEquals("?", TechText.fps(0f))
        assertEquals("8 kbit/s".replace("8", "800"), TechText.kbps(800_000))
        assertTrue(TechText.kbps(4_500_000).startsWith("4"))
    }

    // T225
    @Test fun update_neuerNurWennHoeher() {
        assertTrue(UpdateLogic.isNewer(1, 2))
        assertFalse(UpdateLogic.isNewer(2, 2))
        assertFalse(UpdateLogic.isNewer(3, 2))
    }

    // T226
    @Test fun update_relativeAdresse() {
        assertEquals("https://x.de/app/ipvio.apk", UpdateLogic.resolve("https://x.de/app/update.json", "ipvio.apk"))
        assertEquals("https://y.de/a.apk", UpdateLogic.resolve("https://x.de/app/update.json", "https://y.de/a.apk"))
    }

    // T227
    @Test fun menue_verlaufVorhanden() {
        assertEquals(11, MenuLogic.COUNT)
        assertTrue(MenuLogic.VERLAUF in MenuLogic.parseOrder(null))
        assertEquals(MenuLogic.COUNT, MenuLogic.parseOrder("0,1,2,3,4,5").size)
        assertFalse(MenuLogic.VERLAUF in MenuLogic.parseHidden(MenuLogic.DEFAULT_HIDDEN))
    }

    // T237
    @Test fun suche_tippfehler() {
        assertEquals(0, SearchIndex.approxDist("monster", "monster"))
        assertEquals(1, SearchIndex.approxDist("monster", "monstr"))
        assertEquals(0, SearchIndex.approxDist("monster", "the monsters of x"))
        assertTrue(SearchIndex.approxDist("breaking bd", "breaking bad") <= 1)
        assertTrue(SearchIndex.approxDist("zzzz", "monster") > 1)
        assertEquals(1, SearchIndex.maxErrors(5))
        assertEquals(2, SearchIndex.maxErrors(8))
    }

    // T238
    @Test fun puffer_stufen() {
        val base = intArrayOf(10_000, 25_000, 1_500, 3_000, 14 * 1024 * 1024)
        assertEquals(base.toList(), BufferLogic.scale(base, 0).toList())
        val g = BufferLogic.scale(base, 1)
        assertEquals(20_000, g[0]); assertEquals(50_000, g[1]); assertEquals(3_500, g[2]); assertEquals(7_000, g[3])
        val x = BufferLogic.scale(intArrayOf(30_000, 60_000, 2_500, 5_000, -1), 2)
        assertEquals(-1, x[4]); assertEquals(90_000, x[0])
        assertEquals(0, BufferLogic.level(1, 2)); assertEquals(2, BufferLogic.level(3, 0)); assertEquals(1, BufferLogic.level(0, 1))
        assertEquals(1, BufferLogic.bump(0, 0)); assertEquals(2, BufferLogic.bump(0, 2)); assertEquals(0, BufferLogic.bump(2, 0))
    }
}
