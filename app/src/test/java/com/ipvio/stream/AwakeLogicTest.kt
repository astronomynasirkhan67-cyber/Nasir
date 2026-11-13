package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hard requirement: the screen stays on while a stream plays and is released otherwise. */
class AwakeLogicTest {
    private fun need(pwr: Boolean, st: Int, err: Boolean = false, fg: Boolean = true) =
        AwakeLogic.playerNeedsScreen(pwr, st, err, fg)

    @Test fun laeuftOderLaedt_bleibtWach() {
        assertTrue(need(true, AwakeLogic.STATE_READY))
        assertTrue(need(true, AwakeLogic.STATE_BUFFERING))   // reloading and reconnecting
    }

    @Test fun pause_ende_fehler_hintergrund_geben_frei() {
        assertFalse(need(false, AwakeLogic.STATE_READY))      // pause / sleep timer
        assertFalse(need(true, AwakeLogic.STATE_ENDED))       // end
        assertFalse(need(true, AwakeLogic.STATE_IDLE))        // stopped / after error
        assertFalse(need(true, AwakeLogic.STATE_READY, err = true))
        assertFalse(need(true, AwakeLogic.STATE_READY, fg = false))  // app in background
    }

    @Test fun ablauf_mitAbbruchUndReconnect() {
        // Start -> playing -> network drop (error) -> reconnect (buffering) -> playing -> pause
        val steps = listOf(
            Triple(true, AwakeLogic.STATE_BUFFERING, false) to true,
            Triple(true, AwakeLogic.STATE_READY, false) to true,
            Triple(true, AwakeLogic.STATE_IDLE, true) to false,
            Triple(true, AwakeLogic.STATE_BUFFERING, false) to true,
            Triple(true, AwakeLogic.STATE_READY, false) to true,
            Triple(false, AwakeLogic.STATE_READY, false) to false
        )
        for ((s, want) in steps) assertEquals(want, need(s.first, s.second, s.third))
    }

    @Test fun mehrereGruende_keinerSchaltetDemAnderenAus() {
        val t = AwakeTags()
        assertTrue(t.set("player", true))
        assertTrue(t.set("playlist", true))
        assertTrue(t.set("playlist", false))   // playlist done, player still running
        assertFalse(t.set("player", false))
        assertFalse(t.active)
        assertFalse(t.set("player", false))    // releasing twice is harmless
    }
}
