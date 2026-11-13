package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class Round22Test {
    private fun name(title: String, ch: String = "") = SportLogic.catName(SportLogic.categoryOf(title, ch))

    @Test fun sportarten() {
        assertEquals("Fußball", name("Bundesliga live: Bayern – Dortmund"))
        assertEquals("American Football", name("NFL Sunday Night Football"))
        assertEquals("Basketball", name("NBA: Lakers vs Celtics"))
        assertEquals("Eishockey", name("NHL Highlights"))
        assertEquals("Tennis", name("Wimbledon Finale"))
        assertEquals("Motorsport", name("Formel 1: Großer Preis von Brasilien"))
        assertEquals("Kampfsport", name("UFC 300"))
        assertEquals("Sonstige", name("Livesport", "Sport 1"))
        // the channel name decides if the title gives nothing away
        assertEquals("Fußball", name("Live", "Sky Sport Bundesliga"))
        // the title takes precedence over the channel
        assertEquals("Basketball", name("NBA live", "Sky Sport Bundesliga"))
    }

    @Test fun zeitfenster() {
        val tz = TimeZone.getTimeZone("UTC")
        val now = 1_793_966_400L // 2026-11-06 12:00 UTC
        assertTrue(SportLogic.startsIn(0, now + 3 * 3600, now, tz))
        assertFalse(SportLogic.startsIn(0, now + 3 * 3600 + 1, now, tz))
        assertTrue(SportLogic.startsIn(1, now + 11 * 3600, now, tz))   // 23:00 today
        assertFalse(SportLogic.startsIn(1, now + 13 * 3600, now, tz))  // 01:00 tomorrow
        assertTrue(SportLogic.startsIn(2, now + 13 * 3600, now, tz))
        assertFalse(SportLogic.startsIn(2, now + 37 * 3600, now, tz))  // day after tomorrow
    }
}
