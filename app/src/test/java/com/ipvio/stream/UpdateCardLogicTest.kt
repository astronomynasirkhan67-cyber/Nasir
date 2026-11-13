package com.ipvio.stream

import com.ipvio.stream.UpdateCardLogic.Action
import com.ipvio.stream.UpdateCardLogic.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

class UpdateCardLogicTest {
    @Test fun show_onlyOnFirstStartWithoutPlaylist() {
        assertTrue(UpdateCardLogic.shouldShow(seen = false, hasPlaylist = false))
        assertFalse(UpdateCardLogic.shouldShow(seen = true, hasPlaylist = false))
        assertFalse(UpdateCardLogic.shouldShow(seen = false, hasPlaylist = true))
        assertFalse(UpdateCardLogic.shouldShow(seen = true, hasPlaylist = true))
    }

    @Test fun seenFlag_writtenOnFirstDecisionOnly() {
        assertTrue(UpdateCardLogic.shouldMarkSeen(false))
        assertFalse(UpdateCardLogic.shouldMarkSeen(true))
    }

    @Test fun leftFirstStart_whenPlaylistLoadingOrDemo() {
        assertFalse(UpdateCardLogic.leftFirstStart(hasPlaylist = false, loading = false, demo = false))
        assertTrue(UpdateCardLogic.leftFirstStart(hasPlaylist = true, loading = false, demo = false))
        assertTrue(UpdateCardLogic.leftFirstStart(hasPlaylist = false, loading = true, demo = false))
        assertTrue(UpdateCardLogic.leftFirstStart(hasPlaylist = false, loading = false, demo = true))
    }

    @Test fun start_isChecking() = assertEquals(Phase.CHECKING, UpdateCardLogic.START.phase)

    @Test fun result_nullMeansUpToDate() = assertEquals(UpdateCardLogic.State(Phase.UP_TO_DATE), UpdateCardLogic.onResult(null))

    @Test fun result_versionMeansAvailable() {
        val s = UpdateCardLogic.onResult("1.4")
        assertEquals(Phase.AVAILABLE, s.phase)
        assertEquals("1.4", s.version)
    }

    @Test fun failure_networkErrorsAreOffline() {
        assertEquals(Phase.OFFLINE, UpdateCardLogic.onFailure(UnknownHostException("api.github.com")).phase)
        assertEquals(Phase.OFFLINE, UpdateCardLogic.onFailure(SocketTimeoutException("timeout")).phase)
        assertEquals(Phase.OFFLINE, UpdateCardLogic.onFailure(IOException()).phase)
    }

    @Test fun failure_otherErrorsKeepTheirMessage() {
        val s = UpdateCardLogic.onFailure(IllegalStateException("Fehler 403"))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("Fehler 403", s.message)
    }

    @Test fun failure_blankMessageFallsBackToClassName() =
        assertEquals("IllegalStateException", UpdateCardLogic.onFailure(IllegalStateException("  ")).message)

    @Test fun failure_longMessageIsCut() =
        assertEquals(120, UpdateCardLogic.onFailure(IllegalStateException("x".repeat(500))).message.length)

    @Test fun cancellation_isIgnorable() {
        assertTrue(UpdateCardLogic.ignorable(CancellationException("left screen")))
        assertFalse(UpdateCardLogic.ignorable(IOException()))
        assertFalse(UpdateCardLogic.ignorable(IllegalStateException()))
    }

    @Test fun primaryButton_perPhase() {
        assertEquals(Action.NONE, UpdateCardLogic.primary(Phase.CHECKING))
        assertEquals(Action.NONE, UpdateCardLogic.primary(Phase.UP_TO_DATE))
        assertEquals(Action.SHOW_UPDATE, UpdateCardLogic.primary(Phase.AVAILABLE))
        assertEquals(Action.RETRY, UpdateCardLogic.primary(Phase.OFFLINE))
        assertEquals(Action.RETRY, UpdateCardLogic.primary(Phase.ERROR))
    }

    @Test fun texts_translateInEveryLanguage() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI, DictJ, DictK, DictL)
        val keys = listOf(
            "Auf Updates prüfen", "Suche nach Updates …", "Du hast die neueste Version ({0}).", "Update anzeigen",
            "Keine Verbindung. Prüfe das Netzwerk und versuche es erneut.", "Dieser Hinweis erscheint nur einmal."
        )
        assertEquals("Check for updates", I18nCore.translate(keys[0], 1))
        assertEquals("You have the latest version ({0}).", I18nCore.translate(keys[2], 1))
        for (lang in listOf(2, 4, 6, 7, 8)) for (k in keys) {
            assertTrue("missing $lang: $k", I18nCore.translate(k, lang) != k)
        }
    }
}
