package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {
    private val now = System.currentTimeMillis() / 1000
    private fun e(start: Long, end: Long, title: String = "x") = EpgEntry(title, "", start, end)

    @Test fun fmtTime_unterEineStunde() = assertEquals("02:05", fmtTime(125_000))
    @Test fun fmtTime_mitStunden() = assertEquals("1:02:05", fmtTime(3_725_000))
    @Test fun fmtTime_negativWirdNull() = assertEquals("00:00", fmtTime(-5_000))
    @Test fun fmtShort_sprung() = assertEquals("2:40", fmtShort(160_000))
    @Test fun fmtDelta_vor() = assertEquals("+0:30", fmtDelta(30_000))
    @Test fun fmtDelta_zurueck() = assertEquals("−0:30", fmtDelta(-30_000))

    @Test fun endClock_doppeltesTempoHalbiertDieRestzeit() = assertEquals(1800L, endClockSec(0L, 3_600_000L, 2f))

    @Test fun quality_stufen() {
        assertEquals("4K", qualityName(2160))
        assertEquals("2K", qualityName(1440))
        assertEquals("FHD", qualityName(1080))
        assertEquals("HD", qualityName(720))
        assertEquals("SD", qualityName(719))
    }

    @Test fun quality_breitbild4kWirdAlsVierKErkannt() {
        // 3840x1600: height alone would be "2K", but from the width (16:9) it works out to 2160 -> 4K
        val w = 3840
        val h = 1600
        assertEquals("4K", qualityName(maxOf(h, w * 9 / 16)))
    }

    @Test fun cleanEpg_ueberlappungWirdGekuerzt() {
        val out = cleanEpg(listOf(e(now - 3600, now - 1200, "A"), e(now - 1800, now, "B")))
        assertEquals(2, out.size)
        assertEquals(now - 1800, out[0].end)
        assertEquals("B", out[1].title)
    }

    @Test fun cleanEpg_enthalteneSendungWirdEntfernt() {
        val out = cleanEpg(listOf(e(now - 3600, now, "A"), e(now - 3000, now - 2400, "B")))
        assertEquals(1, out.size)
        assertEquals("A", out[0].title)
    }

    @Test fun cleanEpg_fastGleicherStartErsetzt() {
        val out = cleanEpg(listOf(e(now - 3600, now - 1800, "A"), e(now - 3570, now, "B")))
        assertEquals(1, out.size)
        assertEquals("B", out[0].title)
    }

    @Test fun cleanEpg_kaputteZeitenFliegenRaus() {
        val out = cleanEpg(listOf(e(now, now - 10, "falschherum"), e(now - 100_000, now, "zuLang"), e(now - 600, now, "ok")))
        assertEquals(1, out.size)
        assertEquals("ok", out[0].title)
    }

    @Test fun turbo_stufen() {
        assertEquals(0, turboLevel(0)); assertEquals(0, turboLevel(499))
        assertEquals(1, turboLevel(500)); assertEquals(2, turboLevel(1200)); assertEquals(3, turboLevel(2200)); assertEquals(4, turboLevel(5000))
    }

    @Test fun turbo_sprungweite() {
        assertEquals(10_000L, turboStepMs(0, 10_000L, 7_200_000L))
        assertEquals(120_000L, turboStepMs(1, 10_000L, 7_200_000L))       // 1/60 of 2 h
        assertEquals(480_000L, turboStepMs(3, 10_000L, 7_200_000L))       // 1/15 of 2 h
        assertEquals(900_000L, turboStepMs(4, 10_000L, 7_200_000L))       // 1/8 of 2 h
        assertTrue(turboStepMs(1, 10_000L, 60_000L) >= 20_000L)            // never smaller than a multiple of the base
        assertTrue(turboStepMs(2, 10_000L, 0L) > 0)                        // unknown length
    }

    @Test fun xmltvTime_mitZone() {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
        for (t in listOf("20261019132500 +0200", "20260101000000 +0000", "20240229235959 -0500", "20251231120000 +0530", "19991231235959 +0100")) {
            assertEquals(t, f.parse(t)!!.time / 1000, xmltvTime(t))
        }
    }

    @Test fun xmltvTime_ohneZoneIstUtc() {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        assertEquals(f.parse("20261019132500")!!.time / 1000, xmltvTime("20261019132500"))
        assertEquals(0L, xmltvTime("19700101000000"))
    }

    @Test fun xmltvTime_kaputtesGibtNull() {
        assertEquals(null, xmltvTime(null)); assertEquals(null, xmltvTime("abc")); assertEquals(null, xmltvTime("20261399000000"))
    }
}
