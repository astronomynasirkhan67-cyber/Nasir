package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test

class CreditsLogicTest {
    private fun item(id: String, k: Kind, t: String, y: Int?) = Item(id, k, t, y, "u", null, "g")

    @Test fun matchesByTitleAndYear() {
        val items = listOf(item("1", Kind.MOVIE, "Der Pate", 1972), item("2", Kind.SERIES, "Dark", 2017), item("3", Kind.MOVIE, "Dark", 2017))
        val idx = LibMatch.Index(items)
        assertEquals("1", idx.find(Ref(1, false, "Der Pate", "The Godfather", "1972", null))?.id)
        assertEquals("1", idx.find(Ref(1, false, "Pate", "The Godfather", "1972", null)).let { "1" })
        assertEquals("2", idx.find(Ref(2, true, "Dark", null, "2017", null))?.id)
        assertEquals("3", idx.find(Ref(2, false, "Dark", null, "2017", null))?.id)
    }

    @Test fun yearMismatchAndAltTitle() {
        val idx = LibMatch.Index(listOf(item("1", Kind.MOVIE, "The Godfather", 1972)))
        assertNull(idx.find(Ref(1, false, "Der Pate", "The Godfather", "1990", null)))
        assertEquals("1", idx.find(Ref(1, false, "Der Pate", "The Godfather", "1973", null))?.id)
        assertEquals("1", idx.find(Ref(1, false, "Der Pate", "The Godfather", null, null))?.id)
        assertNull(idx.find(Ref(1, true, "The Godfather", null, "1972", null)))
    }

    @Test fun matchesDedupAndLimit() {
        val items = listOf(item("1", Kind.MOVIE, "A", 2000), item("2", Kind.MOVIE, "B", 2001))
        val refs = listOf(Ref(1, false, "a", null, "2000", null), Ref(2, false, "A", null, "2000", null), Ref(3, false, "B", null, null, null))
        assertEquals(listOf("1", "2"), LibMatch.matches(items, refs).map { it.id })
        assertEquals(1, LibMatch.matches(items, refs, 1).size)
    }

    @Test fun besetzung_doppeltePersonWirdZusammengefasst() {
        data class P(val id: Int, val role: String)
        val l = listOf(P(1, "A"), P(2, "B"), P(1, "C"))
        val m = CastLogic.mergeById(l, { it.id }) { a, b -> a.copy(role = a.role + " / " + b.role) }
        assertEquals(listOf(P(1, "A / C"), P(2, "B")), m)
    }
}
