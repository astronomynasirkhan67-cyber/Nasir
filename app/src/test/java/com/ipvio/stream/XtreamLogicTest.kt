package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamLogicTest {
    private val a = XtreamLogic.Acct("http://srv.tld:8080", "user", "pa ss&1")

    @Test fun parsesGetUrl() {
        val x = XtreamLogic.fromUrl("http://srv.tld:8080/get.php?username=user&password=pa%20ss%261&type=m3u_plus&output=ts")
        assertNotNull(x)
        assertEquals("http://srv.tld:8080", x!!.base)
        assertEquals("user", x.user)
        assertEquals("pa ss&1", x.pass)
        assertNull(XtreamLogic.fromUrl("http://srv.tld/list.m3u"))
        assertNull(XtreamLogic.fromUrl("http://srv.tld/get.php?username=&password=x"))
        assertEquals("http://h/sub", XtreamLogic.fromUrl("http://h/sub/get.php?password=p&username=u")!!.base)
    }

    @Test fun buildsUrls() {
        assertEquals("http://srv.tld:8080/player_api.php?username=user&password=pa+ss%261&action=get_live_streams", XtreamLogic.api(a, "get_live_streams"))
        assertEquals("http://srv.tld:8080/live/user/pa%20ss%261/12.ts", XtreamLogic.liveUrl(a, "12"))
        assertEquals("http://srv.tld:8080/movie/user/pa%20ss%261/7.mkv", XtreamLogic.movieUrl(a, "7", "mkv"))
        assertEquals("http://srv.tld:8080/series/user/pa%20ss%261/9.mp4", XtreamLogic.episodeUrl(a, "9", "../x"))
        val b = XtreamLogic.Acct("http://h", "u", "p")
        assertEquals("http://h/live/u/p/1.ts", XtreamLogic.liveUrl(b, "1"))
    }

    @Test fun liveEntryWithCatchup() {
        val e = XtreamLogic.liveEntry(a, mapOf("stream_id" to "5", "name" to "Sport, HD", "epg_channel_id" to "sp.de",
            "stream_icon" to "http://i/x.png", "category_id" to "3", "tv_archive" to "1", "tv_archive_duration" to "7"), mapOf("3" to "DE | Sport"))!!
        assertTrue(e.startsWith("#EXTINF:-1 tvg-id=\"sp.de\" tvg-logo=\"http://i/x.png\" group-title=\"DE | Sport\" catchup=\"xc\" catchup-days=\"7\","))
        assertTrue(e.contains(",Sport‚ HD\n"))
        assertTrue(e.endsWith("/live/user/pa%20ss%261/5.ts"))
        assertNull(XtreamLogic.liveEntry(a, mapOf("name" to "x"), emptyMap()))
    }

    @Test fun movieEntryAddsYear() {
        val e = XtreamLogic.movieEntry(a, mapOf("stream_id" to "8", "name" to "Dune", "year" to "2021", "container_extension" to "mkv"), emptyMap())!!
        assertTrue(e.contains("group-title=\"Sonstige\",Dune (2021)\n"))
        val f = XtreamLogic.movieEntry(a, mapOf("stream_id" to "8", "name" to "Dune (2021)", "releaseDate" to "2021-09-15"), emptyMap())!!
        assertTrue(f.contains(",Dune (2021)\n"))
    }

    @Test fun episodeEntry() {
        val s = XtreamLogic.seriesOf(mapOf("series_id" to "44", "name" to "Dark", "cover" to "http://c/d.jpg", "category_id" to "2"), mapOf("2" to "Serien"))!!
        val e = XtreamLogic.episodeEntry(a, s, mapOf("id" to "900", "episode_num" to "3", "container_extension" to "mp4"), 2)!!
        assertTrue(e.contains(",Dark S02E03\n"))
        assertNull(XtreamLogic.episodeEntry(a, s, mapOf("id" to "900"), 1))
    }

    @Test fun generatedListParsesLikeGetPhp() {
        val s = XtreamLogic.seriesOf(mapOf("series_id" to "1", "name" to "Dark", "category_id" to "2"), mapOf("2" to "Serien"))!!
        val text = listOf(
            XtreamLogic.header(a),
            XtreamLogic.liveEntry(a, mapOf("stream_id" to "1", "name" to "Das Erste", "category_id" to "1"), mapOf("1" to "DE | TV"))!!,
            XtreamLogic.movieEntry(a, mapOf("stream_id" to "2", "name" to "Dune", "year" to "2021"), mapOf())!!,
            XtreamLogic.episodeEntry(a, s, mapOf("id" to "10", "episode_num" to "1", "season" to "1"), null)!!,
            XtreamLogic.episodeEntry(a, s, mapOf("id" to "11", "episode_num" to "2", "season" to "1"), null)!!
        ).joinToString("\n")
        val p = M3uParser.parseStream(java.io.StringReader(text))
        assertEquals(3, p.items.size)
        val live = p.items.first { it.kind == Kind.LIVE }
        assertEquals("Das Erste", live.title)
        val movie = p.items.first { it.kind == Kind.MOVIE }
        assertEquals(2021, movie.year)
        val ser = p.items.first { it.kind == Kind.SERIES }
        assertEquals("Dark", ser.title)
        assertEquals(2, ser.episodes.size)
        assertTrue(p.epgUrls.first().contains("/xmltv.php?username=user"))
    }

    @Test fun authAndM3uCheck() {
        assertEquals(false, XtreamLogic.authOk("0", null))
        assertEquals(true, XtreamLogic.authOk("1", "Active"))
        assertEquals(false, XtreamLogic.authOk("1", "Expired"))
        assertNull(XtreamLogic.authOk(null, null))
        assertTrue(XtreamLogic.looksLikeM3u("﻿#EXTM3U x"))
        assertFalse(XtreamLogic.looksLikeM3u("<html>884</html>"))
    }
}
