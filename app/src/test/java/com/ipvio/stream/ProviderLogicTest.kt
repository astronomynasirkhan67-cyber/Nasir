package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Film information from the provider (Xtream player_api). */
class ProviderLogicTest {
    @Test fun movieRef_erkennt_xtream_adresse() {
        val r = ProviderLogic.movieRef("http://srv.example:8080/movie/user1/pw1/12345.mkv")!!
        assertEquals("http://srv.example:8080", r.first.host)
        assertEquals("user1", r.first.user)
        assertEquals("pw1", r.first.pass)
        assertEquals("12345", r.second)
        assertNull(ProviderLogic.movieRef("http://srv.example/live/user1/pw1/12345.ts"))
        assertNull(ProviderLogic.movieRef("http://srv.example/film.mp4"))
    }

    @Test fun supports_nach_art() {
        val mv = Item("1", Kind.MOVIE, "A", 2020, "http://h/movie/u/p/9.mp4", null, "g")
        val other = Item("2", Kind.MOVIE, "B", 2020, "http://h/file.mp4", null, "g")
        val live = Item("3", Kind.LIVE, "C", null, "http://h/u/p/5", null, "g")
        val ep = Episode(1, 1, "E", "http://h/series/u/p/77.mkv")
        val se = Item("4", Kind.SERIES, "D", null, "", null, "g", listOf(ep))
        assertTrue(ProviderLogic.supports(mv))
        assertFalse(ProviderLogic.supports(other))
        assertFalse(ProviderLogic.supports(live))
        assertTrue(ProviderLogic.supports(se))
        assertEquals("u", ProviderLogic.seriesAcct(se)!!.user)
    }

    @Test fun apiUrl_gebaut() {
        val a = ProviderLogic.Acct("http://h:80", "u", "p")
        assertEquals("http://h:80/player_api.php?username=u&password=p&action=get_vod_info&vod_id=5",
            ProviderLogic.apiUrl(a, "get_vod_info", "&vod_id=5"))
    }

    @Test fun rating_zehnerSkala() {
        assertEquals(7.5, ProviderLogic.ratingOf("7.5", "3.75")!!, 0.001)
        assertEquals(7.0, ProviderLogic.ratingOf("0", "3.5")!!, 0.001)
        assertEquals(6.8, ProviderLogic.ratingOf("6,8", null)!!, 0.001)
        assertNull(ProviderLogic.ratingOf("", "0"))
        assertNull(ProviderLogic.ratingOf("N/A", null))
        assertEquals(10.0, ProviderLogic.ratingOf("55", null)!!, 0.001)
    }

    @Test fun laufzeit() {
        assertEquals(120, ProviderLogic.minutes("7200", null, null))
        assertEquals(105, ProviderLogic.minutes(null, "01:45:00", null))
        assertEquals(45, ProviderLogic.minutes("0", "", "45"))
        assertNull(ProviderLogic.minutes("", "", ""))
    }

    @Test fun genres_trailer_jahr() {
        assertEquals(listOf("Action", "Drama", "Krimi"), ProviderLogic.genres("Action, Drama / Krimi"))
        assertEquals(emptyList<String>(), ProviderLogic.genres(" "))
        assertEquals("dQw4w9WgXcQ", ProviderLogic.youtubeId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", ProviderLogic.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(ProviderLogic.youtubeId("kein trailer"))
        assertEquals("2019", ProviderLogic.year("2019-05-17"))
        assertNull(ProviderLogic.year("unbekannt"))
    }

    @Test fun titelVergleich() {
        assertTrue(ProviderLogic.sameName("DE - Game of Thrones", "Game of Thrones"))
        assertTrue(ProviderLogic.sameName("Die Hard", "die hard"))
        assertFalse(ProviderLogic.sameName("Die Hard", "Die Hard 2"))
        assertFalse(ProviderLogic.sameName("X", null))
    }

    @Test fun infoOf_baut_Felder() {
        val f = mapOf(
            "plot" to "Eine Handlung.", "movie_image" to "https://img.example/p.jpg", "rating" to "8.1",
            "genre" to "Thriller", "releasedate" to "2021-03-04", "duration_secs" to "5400", "youtube_trailer" to "dQw4w9WgXcQ"
        )
        val i = ProviderLogic.infoOf(f, "https://img.example/b.jpg", listOf("movie_image", "cover_big", "cover"))
        assertEquals("Eine Handlung.", i.plot)
        assertEquals("https://img.example/p.jpg", i.cover)
        assertEquals("https://img.example/b.jpg", i.backdrop)
        assertEquals(8.1, i.rating!!, 0.001)
        assertEquals("2021", i.year)
        assertEquals(90, i.runtime)
        assertEquals("dQw4w9WgXcQ", i.trailer)
        assertTrue(ProviderLogic.infoOf(emptyMap(), null, listOf("cover")).isEmpty())
    }
}
