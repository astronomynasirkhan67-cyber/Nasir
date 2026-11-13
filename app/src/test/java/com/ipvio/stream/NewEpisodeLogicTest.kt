package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodeLogicTest {
    private fun show(id: String, vararg e: Pair<Int, Int>) = NewEpisodeLogic.Show(id, "Serie $id", e.toList())

    @Test fun ersteMalNurMerken() {
        val r = NewEpisodeLogic.detect(emptyMap(), listOf(show("a", 1 to 1, 1 to 2)))
        assertTrue(r.notices.isEmpty())
        assertEquals(2, r.known["a"])
    }

    @Test fun neueFolgeWirdGemeldet() {
        val r = NewEpisodeLogic.detect(mapOf("a" to 2), listOf(show("a", 1 to 1, 1 to 2, 2 to 1)))
        assertEquals(1, r.notices.size)
        assertEquals(2, r.notices[0].season); assertEquals(1, r.notices[0].num); assertEquals(1, r.notices[0].added)
        assertEquals(3, r.known["a"])
        assertEquals("Neue Folge · Staffel 2, Folge 1", NewEpisodeLogic.text(r.notices[0]))
    }

    @Test fun mehrereNeueUndKeineAenderung() {
        val r = NewEpisodeLogic.detect(mapOf("a" to 1, "b" to 2), listOf(show("a", 1 to 1, 1 to 2, 1 to 3), show("b", 1 to 1, 1 to 2)))
        assertEquals(listOf("a"), r.notices.map { it.id })
        assertEquals("2 neue Folgen · bis Staffel 1, Folge 3", NewEpisodeLogic.text(r.notices[0]))
    }

    @Test fun nochNichtGeladenBehaeltStand() {
        val r = NewEpisodeLogic.detect(mapOf("a" to 5), listOf(show("a")))
        assertTrue(r.notices.isEmpty()); assertEquals(5, r.known["a"])
        // fewer episodes (provider cleans up): no alarm, the stored count drops
        val r2 = NewEpisodeLogic.detect(mapOf("a" to 5), listOf(show("a", 1 to 1)))
        assertTrue(r2.notices.isEmpty()); assertEquals(1, r2.known["a"])
    }
}
