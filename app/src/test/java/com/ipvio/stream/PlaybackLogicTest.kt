package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source matching the device, fallback, stutter, automatic subtitles, broken links. */
class PlaybackLogicTest {
    @Test fun geraeteGrenze() {
        assertEquals(4, QualityLogic.deviceMax(2160, true, 0))
        assertEquals(3, QualityLogic.deviceMax(2160, false, 0))      // 4K screen, but no 4K decoder
        assertEquals(3, QualityLogic.deviceMax(1080, true, 0))       // Full HD TV
        assertEquals(3, QualityLogic.deviceMax(2160, true, 3))       // learned: 4K stuttered
        assertEquals(2, QualityLogic.deviceMax(720, true, 0))
        assertEquals(3, QualityLogic.deviceMax(0, false, 0))         // unknown
        assertEquals(4, QualityLogic.cap(QualityLogic.HIGHEST, 2))
        assertEquals(2, QualityLogic.cap(QualityLogic.MAX_HD, 4))
        assertEquals(3, QualityLogic.cap(QualityLogic.AUTO, 3))
    }

    @Test fun reihenfolgeDerQuellen() {
        val qs = listOf("4K", "FHD", "HD", null)
        assertEquals(listOf(0, 1, 2, 3), QualityLogic.order(qs, 4))
        assertEquals(listOf(1, 2, 3, 0), QualityLogic.order(qs, 3))  // FHD device: 4K last
        assertEquals(listOf(2, 3, 1, 0), QualityLogic.order(qs, 2))
        assertEquals(listOf(0), QualityLogic.order(listOf("4K"), 2))  // only 4K: play anyway
    }

    @Test fun pickVariantBeachtetGeraet() {
        val ep = Episode(1, 1, "Episode 1", "u4", listOf(Variant("u4", "4K", "4K", "DE"), Variant("uf", "FHD", "FHD", "DE")), "4K")
        assertEquals("u4", pickVariant(ep, null, null, null).url)          // without a limit as before: best
        assertEquals("uf", pickVariant(ep, null, null, null, 3).url)       // Full HD device
        assertEquals("u4", pickVariant(ep, "4K", null, null, 3).url)       // a fixed choice takes precedence
    }

    @Test fun ausweichen() {
        val qs = listOf("4K", "FHD", "FHD", "HD")
        assertEquals(1, QualityLogic.fallback(qs, 0, setOf(0)))
        assertEquals(3, QualityLogic.fallback(qs, 1, setOf(0, 1)))                   // stutter: next smaller before equally good
        assertEquals(2, QualityLogic.fallback(qs, 1, setOf(0, 1), sameFirst = true))  // dead link: first another source of equal quality
        assertNull(QualityLogic.fallback(qs, 3, setOf(0, 1, 3)))                      // never larger than before
        assertNull(QualityLogic.fallback(listOf("FHD"), 0, setOf(0)))
    }

    @Test fun ruckeln() {
        var s = emptyList<Long>()
        s = StallLogic.add(s, 1_000); s = StallLogic.add(s, 30_000)
        assertFalse(StallLogic.tooMany(s, 30_000))
        s = StallLogic.add(s, 60_000)
        assertTrue(StallLogic.tooMany(s, 60_000))
        s = StallLogic.add(listOf(1_000L, 2_000L), 200_000)            // old pauses no longer count
        assertEquals(listOf(200_000L), s)
    }

    @Test fun untertitelAutomatik() {
        assertEquals("de", TrackLogic.norm("ger"))
        assertEquals("de", TrackLogic.norm("de-DE"))
        assertEquals("ja", TrackLogic.norm("jpn"))
        assertNull(TrackLogic.norm("und"))
        assertNull(TrackLogic.norm(null))
        // Japanese audio, German set → subtitles on; German audio → off; unknown → off
        assertTrue(TrackLogic.subsWanted(TrackLogic.SUB_FOREIGN, "jpn", "de", "de"))
        assertFalse(TrackLogic.subsWanted(TrackLogic.SUB_FOREIGN, "ger", "de", ""))
        assertFalse(TrackLogic.subsWanted(TrackLogic.SUB_FOREIGN, null, "de", "de"))
        assertFalse(TrackLogic.subsWanted(TrackLogic.SUB_FOREIGN, "en", "de", "en"))   // English chosen as audio language
        assertTrue(TrackLogic.subsWanted(TrackLogic.SUB_ALWAYS, "de", "de", "de"))
        assertFalse(TrackLogic.subsWanted(TrackLogic.SUB_OFF, "ja", "de", "de"))
        assertEquals(listOf("ja", "de", "en"), TrackLogic.audioPrefs("de", "en", "jpn"))
        assertEquals(listOf("de"), TrackLogic.audioPrefs("de", "de", null))
        assertEquals("ja" to "de", TrackLogic.decode(TrackLogic.encode("ja", "de")))
        assertEquals("ja" to null, TrackLogic.decode(TrackLogic.encode("ja", null)))
        assertNull(TrackLogic.decode("kaputt"))
    }

    @Test fun defekteLinks() {
        assertTrue(DeadLinkLogic.dead(1, true))
        assertFalse(DeadLinkLogic.dead(1, false))
        assertTrue(DeadLinkLogic.dead(2, false))
        assertTrue(DeadLinkLogic.counts(404, false, true))
        assertTrue(DeadLinkLogic.counts(0, false, true))                // e.g. broken format
        assertFalse(DeadLinkLogic.counts(403, false, true))             // access – affects all titles
        assertFalse(DeadLinkLogic.counts(503, false, true))
        assertFalse(DeadLinkLogic.counts(0, true, true))                // decoder: the device, not the link
        assertFalse(DeadLinkLogic.counts(404, false, false))            // never without internet
        assertTrue(DeadLinkLogic.permanent(410))
        assertEquals(Triple(2, 99L, true), DeadLinkLogic.decode(DeadLinkLogic.encode(2, 99L, true)))
        assertNull(DeadLinkLogic.decode("x"))
    }
}

/** Skip times from TheIntroDB: only plausible sections (round 13e). */
class SegLogicTest {
    private val dur = 3_300_000L   // 55-minute episode

    @org.junit.Test fun introUndRueckblick() {
        org.junit.Assert.assertEquals(60_000L to 150_000L, SegLogic.clean(SegLogic.INTRO, 60_000, 150_000, dur))
        org.junit.Assert.assertEquals(0L to 90_000L, SegLogic.clean(SegLogic.RECAP, null, 90_000, dur))
        // no end: no button (otherwise until the end of the episode)
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.RECAP, 0, null, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.INTRO, 10_000, null, dur))
        // too long, too short, in the middle of the episode
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.INTRO, 0, 600_000, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.INTRO, 0, 2_000, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.INTRO, 2_000_000, 2_060_000, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.RECAP, 1_500_000, 1_560_000, dur))
        // beyond the end of the episode (data for a different cut)
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.INTRO, 3_350_000, 3_400_000, dur))
    }

    @org.junit.Test fun abspann() {
        org.junit.Assert.assertEquals(3_100_000L to dur, SegLogic.clean(SegLogic.CREDITS, 3_100_000, null, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.CREDITS, null, dur, dur))
        org.junit.Assert.assertNull(SegLogic.clean(SegLogic.CREDITS, 600_000, 700_000, dur))
    }

    @Test fun spracheAusBezeichnungWennKuerzelFehlt() {
        assertEquals("de", TrackLogic.guess("und", "Deutsch 5.1"))
        assertEquals("de", TrackLogic.guess(null, "German AC3"))
        assertEquals("ja", TrackLogic.guess("", "Japanese"))
        assertEquals("de", TrackLogic.guess("ger", "irgendwas"))
        assertEquals(null, TrackLogic.guess("und", "Track 2"))
    }
}
