package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks every translation row: placeholders must be numbered uniquely and appear in every language. */
class DictConsistencyTest {
    private val ph = Regex("""\{#?(\d)\}""")

    @Test fun placeholders_eindeutig_und_in_allen_sprachen() {
        val problems = ArrayList<String>()
        for (block in listOf(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI, DictJ, DictK, DictL, DictM, DictNl1, DictNl2, DictNl3, DictNl4, DictNl5, DictNl6)) {
            for (line in block.lineSequence()) {
                if (line.isBlank() || line.startsWith("#") || !line.contains('¦')) continue
                val cols = line.split('¦')
                val de = ph.findAll(cols[0]).map { it.groupValues[1] }.toList()
                if (de.size != de.toSet().size) problems.add("doppelt: " + cols[0])
                for (c in cols.drop(1)) {
                    if (c.isBlank()) continue
                    val ci = ph.findAll(c).map { it.groupValues[1] }.toSet()
                    if (ci != de.toSet()) problems.add("fehlt: " + cols[0] + " | " + c)
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun niederlaendisch_deckt_alle_zeilen_ab() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI, DictJ, DictK, DictL, DictM)
        val nl = I18nCore.CODES.indexOf("nl")
        assertEquals(9, nl)
        I18nCore.loadColumn(nl, DictNl1, DictNl2, DictNl3, DictNl4, DictNl5)
        assertEquals("Playlist toevoegen", I18nCore.translate("Playlist hinzufügen", nl))
        assertEquals("8 afleveringen", I18nCore.translate("8 Folgen", nl))
        assertEquals("Nederlands", I18nCore.NATIVE[nl])
    }

    @Test fun zahlen_werden_richtig_eingesetzt() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI, DictJ, DictK, DictL, DictM)
        assertEquals("loading 5/10", I18nCore.translate("lädt 5/10", 1))
        assertEquals("8 episodes", I18nCore.translate("8 Folgen", 1))
        assertEquals("No connection: SocketException", I18nCore.translate("Keine Verbindung: SocketException", 1))
        assertEquals("Server not found (DNS)", I18nCore.translate("Server nicht gefunden (DNS)", 1))
    }
}
