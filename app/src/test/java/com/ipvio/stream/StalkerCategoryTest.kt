package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stalker categories: page count, stored state for loading on demand, covers and play addresses. Synthetic data. */
class StalkerCategoryTest {
    @Test fun catRefRoundTrips() {
        val refs = listOf(
            StalkerLogic.CatRef("vod", "12", "Action", 4200, 15),
            StalkerLogic.CatRef("series", "7", "Kids & Family", -1, 0),
        )
        assertEquals(refs, StalkerLogic.decodeCats(StalkerLogic.encodeCats(refs)))
    }

    @Test fun catRefNameCannotBreakTheLine() {
        val r = StalkerLogic.CatRef("vod", "1", "A\tB\nC", 10, 1)
        val back = StalkerLogic.decodeCats(StalkerLogic.encodeCats(listOf(r))).single()
        assertEquals("A B C", back.name)
    }

    @Test fun catRefRejectsGarbage() {
        assertNull(StalkerLogic.CatRef.decode(""))
        assertNull(StalkerLogic.CatRef.decode("itv\t1\t10\t1\tNews"))
        assertNull(StalkerLogic.CatRef.decode("vod\t1\tx\t1\tNews"))
        assertNull(StalkerLogic.CatRef.decode("vod\t1\t10\t-2\tNews"))
        assertNull(StalkerLogic.CatRef.decode("vod\t\t10\t1\tNews"))
        assertEquals(emptyList<StalkerLogic.CatRef>(), StalkerLogic.decodeCats(null))
    }

    @Test fun pageCountFromTotals() {
        assertEquals(3, StalkerLogic.pageCount(30, 14, 1))
        assertEquals(1, StalkerLogic.pageCount(14, 14, 1))
        assertEquals(5, StalkerLogic.pageCount(0, 14, 5))
        assertEquals(5, StalkerLogic.pageCount(30, 0, 5))
    }

    @Test fun movieCoverPathIsResolved() {
        val src = StalkerLogic.source("http://portal.tld:8080/c/", "00:1A:79:12:34:56")!!
        val line = StalkerLogic.movieEntry(src, mapOf("cmd" to "/media/1.mpg", "name" to "Example", "screenshot_uri" to "/stalker_portal/screenshots/1/2.jpg"), "Drama")!!
        assertEquals(true, line.contains("tvg-logo=\"http://portal.tld:8080/stalker_portal/screenshots/1/2.jpg\""))
        val abs = StalkerLogic.movieEntry(src, mapOf("cmd" to "/media/1.mpg", "name" to "Example", "screenshot_uri" to "https://img.tld/p.jpg"), "Drama")!!
        assertEquals(true, abs.contains("tvg-logo=\"https://img.tld/p.jpg\""))
    }

    @Test fun stalkerChannelInMoviesGenreStaysLive() {
        val src = StalkerLogic.source("http://portal.tld:8080/c/", "00:1A:79:12:34:56")!!
        val line = StalkerLogic.liveEntry(src, mapOf("cmd" to "ffrt http://x/ch/1", "name" to "Movie Channel", "tv_genre_id" to "3"), mapOf("3" to "ENGLISH | PREMIUM MOVIES"))!!
        val item = M3uParser.parse("#EXTM3U\n$line\n").single()
        assertEquals(Kind.LIVE, item.kind)
    }

    @Test fun movieWithFilesIsLookedUpAtPlayTime() {
        val src = StalkerLogic.source("http://portal.tld:8080/c/", "00:1A:79:12:34:56")!!
        val f = mapOf("id" to "77", "cmd" to "/media/77.mpg", "name" to "Example", "has_files" to "1")
        val entry = StalkerLogic.movieEntry(src, f, "Drama")!!
        val ref = StalkerLogic.itemRef(entry.lines()[1])!!
        assertEquals("mfile", ref.type)
        assertEquals("77", ref.cmd)
        assertEquals(Kind.MOVIE, M3uParser.parse("#EXTM3U\n$entry\n").single().kind)
        assertEquals("vod", StalkerLogic.itemRef(StalkerLogic.movieEntry(src, f - "has_files", "Drama")!!.lines()[1])!!.type)
        assertEquals(false, StalkerLogic.needsFileLookup(f + ("cmd" to "/media/file_9.mpg"), "/media/file_9.mpg"))
    }

    /** Play address from a create_link answer that only carries "cmd" (see [StalkerLink.address]). */
    private fun play(endpoint: String, cmd: String, sent: String = "") =
        StalkerLink.address(endpoint, "00:1A:79:12:34:56", mapOf("cmd" to cmd), "vod", sent)

    @Test fun relativePlayUrlUsesPortalInstallation() {
        assertEquals("http://p.tld/x.m3u8", play("http://p.tld/stalker_portal/server/load.php", "ffmpeg http://p.tld/x.m3u8"))
        assertEquals("http://p.tld:81/stalker_portal/media/vod/i.m3u8", play("http://p.tld:81/stalker_portal/server/load.php", "/media/vod/i.m3u8"))
        assertEquals("http://p.tld/media/1.mp4", play("http://p.tld/portal.php", "ffmpeg /media/1.mp4"))
        assertNull(play("http://p.tld/portal.php", ""))
    }

    @Test fun echoedCommandIsNoPlayUrl() {
        val ep = "http://p.tld/stalker_portal/server/load.php"
        assertNull(play(ep, "ffmpeg /media/file_9.mpg", "/media/file_9.mpg"))
        assertNull(play(ep, "/media/77.mpg", "ffmpeg /media/77.mpg"))
        assertEquals("http://p.tld/stalker_portal/media/vod/9.m3u8", play(ep, "/media/vod/9.m3u8", "/media/file_9.mpg"))
        assertEquals("http://p.tld/x.mp4", play(ep, "ffmpeg http://p.tld/x.mp4", "ffmpeg http://p.tld/x.mp4"))
    }

    @Test fun fileLookupNeverPlaysAnotherMovie() {
        // Proper file list: the entry carrying its own file command wins
        assertEquals("/media/file_5.mpg", StalkerLink.fileCmd(listOf(mapOf("id" to "1"), mapOf("id" to "5", "cmd" to "ffmpeg /media/file_5.mpg")), 2))
        // Short list without commands: first entry ID
        assertEquals("/media/file_42.mpg", StalkerLink.fileCmd(listOf(mapOf("id" to "42")), 1))
        // Portal ignored movie_id and sent a category (many entries / large total): no guess
        assertNull(StalkerLink.fileCmd(listOf(mapOf("id" to "42")), 4200))
        assertNull(StalkerLink.fileCmd((1..21).map { mapOf("id" to "$it") }, 21))
        assertNull(StalkerLink.fileCmd(emptyList(), 0))
        assertNull(StalkerLink.fileCmd(listOf(mapOf("id" to "0")), 1))
    }

    @Test fun categoryAreasWithTheSameNameStaySeparate() {
        val vod = StalkerLogic.CatRef("vod", "1", "ENGLISH", 4200, 1)
        val ser = StalkerLogic.CatRef("series", "9", "ENGLISH", 300, 1)
        val other = StalkerLogic.CatRef("vod", "2", "KIDS", 50, 1)
        val all = listOf(vod, ser, other)
        assertEquals(listOf(vod), StalkerLogic.catsFor(all, "ENGLISH", movies = true, series = false))
        assertEquals(listOf(ser), StalkerLogic.catsFor(all, "ENGLISH", movies = false, series = true))
        // Series view without a series area of that name: series filed in the movie area
        assertEquals(listOf(other), StalkerLogic.catsFor(all, "KIDS", movies = false, series = true))
        assertEquals(emptyList<StalkerLogic.CatRef>(), StalkerLogic.catsFor(all, "ENGLISH", movies = false, series = false))
    }
}
