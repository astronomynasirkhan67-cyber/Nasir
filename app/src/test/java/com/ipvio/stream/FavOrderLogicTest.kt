package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class FavOrderLogicTest {
    // l = live channel, m = movie, s = series; the stored list mixes all of them (newest first)
    private val all = listOf("l1", "m1", "l2", "s1", "m2", "l3", "m3")
    private val movies = listOf("m1", "m2", "m3")

    @Test fun moveDown_keepsOtherKindsInPlace() =
        assertEquals(listOf("l1", "m2", "l2", "s1", "m1", "l3", "m3"), FavOrderLogic.move(all, movies, "m1", 1))

    @Test fun moveUp_keepsOtherKindsInPlace() =
        assertEquals(listOf("l1", "m1", "l2", "s1", "m3", "l3", "m2"), FavOrderLogic.move(all, movies, "m3", -1))

    @Test fun toTop_movesToFirstSlotOfTheView() =
        assertEquals(listOf("l1", "m3", "l2", "s1", "m1", "l3", "m2"), FavOrderLogic.toTop(all, movies, "m3"))

    @Test fun edges_andUnknownId_changeNothing() {
        assertEquals(all, FavOrderLogic.move(all, movies, "m1", -1))
        assertEquals(all, FavOrderLogic.move(all, movies, "m3", 1))
        assertEquals(all, FavOrderLogic.move(all, movies, "x9", 1))
        assertEquals(all, FavOrderLogic.toTop(all, movies, "m1"))
    }

    @Test fun shownIdsThatAreNotStored_areIgnored() =
        assertEquals(listOf("l1", "m2", "l2", "s1", "m1", "l3", "m3"), FavOrderLogic.move(all, listOf("m1", "ghost", "m2", "m3"), "m1", 1))

    @Test fun otherPlaylistsEntries_notOnScreen_stayWhereTheyAre() {
        // "m2" belongs to a playlist that is not loaded: not shown, must keep index 4
        val shown = listOf("m1", "m3")
        val out = FavOrderLogic.move(all, shown, "m1", 1)
        assertEquals(listOf("l1", "m3", "l2", "s1", "m2", "l3", "m1"), out)
        assertEquals("m2", out[4])
    }

    @Test fun moveOverSeveralPlaces_isClamped() =
        assertEquals(listOf("l1", "m2", "l2", "s1", "m3", "l3", "m1"), FavOrderLogic.move(all, movies, "m1", 50))

    @Test fun result_isAPermutation() {
        val out = FavOrderLogic.move(all, movies, "m2", -1)
        assertEquals(all.sorted(), out.sorted())
    }

    @Test fun duplicatesInStoredList_areDroppedWhenSomethingMoves() =
        assertEquals(listOf("a", "c", "b"), FavOrderLogic.move(listOf("a", "b", "b", "c"), listOf("b", "c"), "b", 1))
}
