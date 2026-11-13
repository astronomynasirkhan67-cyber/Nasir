package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SettingsLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(utc); c.clear(); c.set(y, mo - 1, d, h, mi, 0); return c.timeInMillis
    }

    @Test fun startTab() {
        val order = listOf(0, 4, 1, 2, 3, 5)
        assertEquals(3, SettingsLogic.resolveStartTab(3, order, emptyList()))
        assertEquals(0, SettingsLogic.resolveStartTab(3, order, listOf(3)))
        assertEquals(0, SettingsLogic.resolveStartTab(5, order, emptyList()))
        assertEquals(0, SettingsLogic.resolveStartTab(2, listOf(0, 1, 5), emptyList()))
    }

    @Test fun everyStartAndManual() {
        val now = at(2026, 10, 20, 9, 0)
        assertTrue(SettingsLogic.refreshDue(UpdatePlan(0), now, now, utc))
        assertFalse(SettingsLogic.refreshDue(UpdatePlan(4), 0, now, utc))
    }

    @Test fun interval() {
        val now = at(2026, 10, 20, 9, 0)
        val p = UpdatePlan(1, intervalH = 6)
        assertFalse(SettingsLogic.refreshDue(p, now - 5 * 3_600_000L, now, utc))
        assertTrue(SettingsLogic.refreshDue(p, now - 6 * 3_600_000L, now, utc))
        assertTrue(SettingsLogic.refreshDue(p, 0, now, utc))
        assertTrue(SettingsLogic.refreshDue(p, now + 1000, now, utc))
    }

    @Test fun daily() {
        val p = UpdatePlan(2, hour = 4, minute = 30)
        // 20.10. 09:00 -> last slot was 20.10. 04:30
        val now = at(2026, 10, 20, 9, 0)
        assertEquals(at(2026, 10, 20, 4, 30), SettingsLogic.lastScheduled(p, now, utc))
        assertTrue(SettingsLogic.refreshDue(p, at(2026, 10, 19, 20, 0), now, utc))
        assertFalse(SettingsLogic.refreshDue(p, at(2026, 10, 20, 5, 0), now, utc))
        // before today's slot, yesterday's applies
        val early = at(2026, 10, 20, 3, 0)
        assertEquals(at(2026, 10, 19, 4, 30), SettingsLogic.lastScheduled(p, early, utc))
        assertFalse(SettingsLogic.refreshDue(p, at(2026, 10, 19, 5, 0), early, utc))
    }

    @Test fun weekly() {
        // 20.10.2026 is a Tuesday. Plan: Mondays 04:00 -> last slot 19.10. 04:00
        val p = UpdatePlan(3, hour = 4, minute = 0, dow = Calendar.MONDAY)
        val now = at(2026, 10, 20, 9, 0)
        assertEquals(at(2026, 10, 19, 4, 0), SettingsLogic.lastScheduled(p, now, utc))
        assertTrue(SettingsLogic.refreshDue(p, at(2026, 10, 18, 12, 0), now, utc))
        assertFalse(SettingsLogic.refreshDue(p, at(2026, 10, 19, 5, 0), now, utc))
        // Tuesday plan, but it is Tuesday 03:00 -> last week
        val q = UpdatePlan(3, hour = 4, minute = 0, dow = Calendar.TUESDAY)
        assertEquals(at(2026, 10, 13, 4, 0), SettingsLogic.lastScheduled(q, at(2026, 10, 20, 3, 0), utc))
        assertEquals(at(2026, 10, 20, 4, 0), SettingsLogic.lastScheduled(q, at(2026, 10, 20, 4, 0), utc))
    }

    @Test fun nextDue() {
        val now = at(2026, 10, 20, 9, 0)
        assertEquals(at(2026, 10, 21, 4, 30), SettingsLogic.nextDue(UpdatePlan(2, hour = 4, minute = 30), 0, now, utc))
        assertEquals(at(2026, 10, 26, 4, 0), SettingsLogic.nextDue(UpdatePlan(3, hour = 4, dow = Calendar.MONDAY), 0, now, utc))
        assertEquals(now + 6 * 3_600_000L, SettingsLogic.nextDue(UpdatePlan(1, intervalH = 6), now, now, utc))
        assertNull(SettingsLogic.nextDue(UpdatePlan(4), 0, now, utc))
        assertNull(SettingsLogic.nextDue(UpdatePlan(0), 0, now, utc))
    }

    @Test fun bytes() {
        assertEquals("512 B", SettingsLogic.fmtBytes(512))
        assertEquals("2 KB", SettingsLogic.fmtBytes(2048))
        assertEquals("1,5 MB", SettingsLogic.fmtBytes(1572864))
    }

    @Test fun zonesAndPatterns() {
        for (z in SettingsLogic.ZONES.drop(1)) assertEquals(z.id, SettingsLogic.zoneOf(z.id).id)
        assertEquals(TimeZone.getDefault().id, SettingsLogic.zoneOf("").id)
        assertEquals(TimeZone.getDefault().id, SettingsLogic.zoneOf("Mars/Olympus").id)
        assertEquals("HH:mm", SettingsLogic.timePattern(0, true))
        assertEquals("h:mm a", SettingsLogic.timePattern(0, false))
        assertEquals("HH:mm", SettingsLogic.timePattern(1, false))
        assertEquals("h:mm a", SettingsLogic.timePattern(2, true))
        assertTrue(SettingsLogic.isMidnight(at(2026, 10, 20, 0, 0) / 1000, utc))
        assertFalse(SettingsLogic.isMidnight(at(2026, 10, 20, 12, 0) / 1000, utc))
        assertFalse(SettingsLogic.isMidnight(at(2026, 10, 20, 0, 5) / 1000, utc))
    }
}
