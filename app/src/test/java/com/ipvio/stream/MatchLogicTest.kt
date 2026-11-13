package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB matching: the right hit instead of blindly the first (the "Game of Thrones" case). */
class MatchLogicTest {
    private fun c(id: Int, name: String, orig: String = name, year: String? = null, votes: Int = 0) =
        MatchLogic.Candidate(id, name, orig, year, votes)

    @Test fun suchtitelOhneSprachkennung() {
        assertEquals("Game of Thrones", MatchLogic.searchTitle("DE - Game of Thrones"))
        assertEquals("Game of Thrones", MatchLogic.searchTitle("|DE| Game of Thrones"))
        assertEquals("Game of Thrones", MatchLogic.searchTitle("[GER] Game of Thrones"))
        assertEquals("Haus des Geldes", MatchLogic.searchTitle("DE: Haus des Geldes"))
        assertEquals("Game of Thrones", MatchLogic.searchTitle("Game of Thrones"))
        assertEquals("The Office", MatchLogic.searchTitle("The Office"))          // "The" is not a language
        assertEquals("Up - Oben", MatchLogic.searchTitle("Up - Oben"))           // "Up" is not a language
        assertEquals("DE", MatchLogic.searchTitle("DE"))                          // never empty
    }

    @Test fun gleichnamigerTrefferGewinntGegenBeliebtenAbleger() {
        val res = listOf(
            c(250000, "A Knight of the Seven Kingdoms", votes = 900),
            c(1399, "Game of Thrones", votes = 25000),
            c(1400, "Game of Thrones: Conquest & Rebellion", votes = 40)
        )
        assertEquals(1399, MatchLogic.pick("DE - Game of Thrones", null, res)!!.id)
    }

    @Test fun ohneGleichnamigenBleibtDerErsteTreffer() {
        val res = listOf(c(1, "La casa de papel"), c(2, "Money Heist Korea"))
        assertEquals(1, MatchLogic.pick("Haus des Geldes", null, res)!!.id)
        assertNull(MatchLogic.pick("x", null, emptyList()))
    }

    @Test fun originalnameUndJahrZaehlen() {
        val res = listOf(c(10, "Der Pate", "The Godfather", "1972", 100), c(11, "Der Pate", "The Godfather", "1990", 5000))
        assertEquals(10, MatchLogic.pick("The Godfather", 1972, res)!!.id)
        assertEquals(11, MatchLogic.pick("The Godfather", null, res)!!.id)       // without year: more votes
        assertTrue(MatchLogic.sameTitle("Amélie", "Amelie"))
        assertTrue(MatchLogic.sameTitle("Fast & Furious", "Fast and Furious"))
    }

    @Test fun staffelnPassen() {
        assertFalse(MatchLogic.seasonsFit(8, 1))    // playlist has 8 seasons, hit only 1 → wrong hit
        assertTrue(MatchLogic.seasonsFit(8, 8))
        assertTrue(MatchLogic.seasonsFit(0, 1))     // unknown
        assertTrue(MatchLogic.seasonsFit(3, 0))
    }
}
