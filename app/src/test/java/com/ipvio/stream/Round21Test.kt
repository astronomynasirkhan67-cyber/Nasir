package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 21: series with placeholder (episodes are only loaded on open). */
class Round21Test {
    private val acct = XtreamLogic.Acct("http://srv.tld:8080", "user", "pa ss")
    private val series = XtreamLogic.Series("4711", "Dark", "http://img/c.jpg", "Drama")

    private fun stubItem(url: String, labelA: String = ""): Item {
        val ep = Episode(1, 1, "Episode 1", url, emptyList(), null)
        return Item("s_dark", Kind.SERIES, "Dark", null, "", null, "Drama", listOf(ep))
    }

    @Test fun platzhalterZeileHatStaffelUndAdresse() {
        val e = XtreamLogic.lazyEntry(acct, series).lines()
        assertEquals(2, e.size)
        assertTrue(e[0].contains("Dark S01E01"))
        assertTrue(e[0].contains("tvg-logo=\"http://img/c.jpg\""))
        assertEquals("http://srv.tld:8080/series/user/pa%20ss/4711.xtser", e[1])
    }

    @Test fun adresseWirdZerlegt() {
        val (a, id) = XtreamLogic.parseLazy("http://srv.tld:8080/series/user/pa%20ss/4711.xtser")!!
        assertEquals("http://srv.tld:8080", a.base)
        assertEquals("user", a.user)
        assertEquals("pa ss", a.pass)
        assertEquals("4711", id)
        assertNull(XtreamLogic.parseLazy("http://srv.tld:8080/series/user/pass/4711.mkv"))
        assertNull(XtreamLogic.parseLazy("http://srv.tld:8080/movie/user/pass/4711.xtser"))
    }

    @Test fun serieMitPlatzhalterErkennen() {
        val s = stubItem("http://srv.tld/series/u/p/1.xtser")
        assertTrue(XtreamLogic.isLazy(s))
        assertEquals(1, XtreamLogic.lazyUrls(s).size)
        // real episodes are not a placeholder
        val real = s.copy(episodes = listOf(Episode(1, 1, "E1", "http://srv.tld/series/u/p/9.mkv"), Episode(1, 2, "E2", "http://srv.tld/series/u/p/10.mkv")))
        assertFalse(XtreamLogic.isLazy(real))
        // a single real episode is not one either
        assertFalse(XtreamLogic.isLazy(s.copy(episodes = listOf(Episode(1, 1, "E1", "http://srv.tld/series/u/p/9.mkv")))))
        // movies and channels never
        assertFalse(XtreamLogic.isLazy(s.copy(kind = Kind.MOVIE)))
    }

    @Test fun folgeInTeilen() {
        val f = mapOf("id" to "123", "episode_num" to "4", "season" to "2", "container_extension" to "mkv")
        val (se, n, url) = XtreamLogic.episodeParts(acct, f, null)!!
        assertEquals(2, se); assertEquals(4, n)
        assertEquals("http://srv.tld:8080/series/user/pa%20ss/123.mkv", url)
        // season from the key if the episode names none
        assertEquals(3, XtreamLogic.episodeParts(acct, mapOf("id" to "5", "episode_num" to "1"), 3)!!.first)
        // unusable
        assertNull(XtreamLogic.episodeParts(acct, mapOf("id" to "x", "episode_num" to "1"), 1))
        assertNull(XtreamLogic.episodeParts(acct, mapOf("id" to "5"), 1))
        assertNull(XtreamLogic.episodeParts(acct, mapOf("id" to "5", "episode_num" to "100000"), 1))
    }

    @Test fun folgeZeileUnveraendert() {
        val f = mapOf("id" to "123", "episode_num" to "4", "season" to "2", "container_extension" to "mkv")
        val line = XtreamLogic.episodeEntry(acct, series, f, null)!!
        assertTrue(line.contains(",Dark S02E04"))
        assertTrue(line.endsWith("/123.mkv"))
    }

    @Test fun mehrerePlaylistsPlatzhalterNieMitEchtenFolgenMischen() {
        val stub = stubItem("http://a.tld/series/u/p/1.xtser")
        val real = stub.copy(episodes = listOf(Episode(1, 1, "E1", "http://b.tld/series/u/p/9.mkv"), Episode(1, 2, "E2", "http://b.tld/series/u/p/10.mkv")))
        val m1 = MultiLogic.merge(listOf(listOf(stub), listOf(real)), listOf("A", "B")).items
        val s1 = m1.first { it.kind == Kind.SERIES }
        assertEquals(2, s1.episodes.size)
        assertFalse(XtreamLogic.isLazy(s1))
        assertTrue(s1.episodes.all { it.variants.isEmpty() && it.url.endsWith(".mkv") })
        val m2 = MultiLogic.merge(listOf(listOf(real), listOf(stub)), listOf("A", "B")).items
        assertEquals(2, m2.first { it.kind == Kind.SERIES }.episodes.size)
    }

    @Test fun zweiPlatzhalterBleibenPlatzhalterMitBeidenQuellen() {
        val a = stubItem("http://a.tld/series/u/p/1.xtser")
        val b = stubItem("http://b.tld/series/u/p/2.xtser")
        val m = MultiLogic.merge(listOf(listOf(a), listOf(b)), listOf("A", "B")).items.first { it.kind == Kind.SERIES }
        assertTrue(XtreamLogic.isLazy(m))
        val urls = XtreamLogic.lazyUrls(m)
        assertEquals(2, urls.size)
        assertEquals("", urls[0].second)
        assertEquals("B", urls[1].second)
        assertNotNull(XtreamLogic.parseLazy(urls[1].first))
    }
}
