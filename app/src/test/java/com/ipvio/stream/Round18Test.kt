package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/** Round 18: translations of the runtime texts (player hint, cinema hint, decades, update messages). */
class Round18Test {
    @Before fun setUp() { I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI) }
    private val EN = 1; private val TR = 2; private val IT = 8

    @Test fun playerTimelineHint() {
        assertEquals("Timeline  ·  Left/Right = skip 10 s  ·  Hold = keep skipping",
            I18nCore.translate("Zeitleiste  ·  Links/Rechts = 10 s springen  ·  Halten = weiter springen", EN))
    }
    @Test fun cinemaHint() {
        assertEquals("Down for details and playback", I18nCore.translate("Runter für Details und Abspielen", EN))
    }
    @Test fun decades() {
        assertEquals("2020s", I18nCore.translate("2020er", EN))
        assertEquals("2010s", I18nCore.translate("2010er", EN))
        assertEquals("2000s", I18nCore.translate("2000er", EN))
        assertEquals("1990s", I18nCore.translate("1990er", EN))
        assertEquals("before 1990", I18nCore.translate("vor 1990", EN))
        assertEquals("Anni 2020", I18nCore.translate("2020er", IT))
    }
    @Test fun updateMessages() {
        assertEquals("New: version 1.2", I18nCore.translate("Neu: Version 1.2", EN))
        assertEquals("Error 404", I18nCore.translate("Fehler 404", EN))
        assertEquals("Loading … 45 %", I18nCore.translate("Lade … 45 %", EN))
        assertEquals("Update file incomplete", I18nCore.translate("Update-Datei unvollständig", EN))
    }
    @Test fun playerMessages() {
        assertEquals("Connection stalled – trying again (1/2)", I18nCore.translate("Verbindung hängt – neuer Versuch (1/2)", EN))
        assertEquals("15 min", I18nCore.translate("15 min", EN))
        assertEquals("Source 2", I18nCore.translate("Quelle 2", EN))
        assertEquals("Top right", I18nCore.translate("Oben rechts", EN))
    }
    @Test fun aboutAndHints() {
        assertNotEquals("Zeigt die aktuelle Uhrzeit beim Abspielen in einer Ecke (nicht, solange die Steuerleiste offen ist).",
            I18nCore.translate("Zeigt die aktuelle Uhrzeit beim Abspielen in einer Ecke (nicht, solange die Steuerleiste offen ist).", TR))
    }
}
