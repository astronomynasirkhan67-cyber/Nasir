package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Online subtitle search: queries, addresses, parsing, ranking, files. Synthetic data, no network. */
class SubSearchLogicTest {
    private val movie = SubSearchLogic.Query("Example Movie", 2010, 27205, 0, 0, listOf("de", "en"))
    private val episode = SubSearchLogic.Query("Example Show", null, 1399, 1, 2, listOf("en"))

    @Test fun titleIsCleanedFromPlaylistNoise() {
        val q = SubSearchLogic.query("EN - Example Movie (2010) [4K]", 0, 0, 0, "", "de")
        assertEquals("Example Movie", q.title)
        assertEquals(2010, q.year)
        assertEquals(listOf("de", "en"), q.langs)
        val e = SubSearchLogic.query("Example Show S01E02", 1399, 1, 2, "fr", "en")
        assertEquals("Example Show", e.title)
        assertTrue(e.isEpisode)
        assertEquals(listOf("fr", "en"), e.langs)
    }

    @Test fun openSubtitlesParametersAreSorted() {
        assertEquals("${SubSearchLogic.OS_API}/subtitles?languages=de%2Cen&tmdb_id=27205", SubSearchLogic.osSearchUrl(movie))
        assertEquals(
            "${SubSearchLogic.OS_API}/subtitles?episode_number=2&languages=en&parent_tmdb_id=1399&season_number=1",
            SubSearchLogic.osSearchUrl(episode)
        )
        val byTitle = SubSearchLogic.osSearchUrl(movie.copy(tmdbId = 0))
        assertEquals("${SubSearchLogic.OS_API}/subtitles?languages=de%2Cen&query=example+movie&year=2010", byTitle)
    }

    @Test fun subdlAddressKeepsOnlySafeKeyCharacters() {
        val u = SubSearchLogic.sdSearchUrl(episode, "subdl_ab-C9&evil=1")
        assertTrue(u.startsWith(SubSearchLogic.SD_API + "?api_key=subdl_ab-C9evil1&tmdb_id=1399&type=tv&season_number=1&episode_number=2"))
        assertTrue(u.contains("&languages=EN"))
        assertTrue(SubSearchLogic.sdSearchUrl(movie.copy(tmdbId = 0), "k").contains("&film_name=Example+Movie&year=2010&type=movie"))
    }

    @Test fun openSubtitlesHitsSkipMachineTranslationAndMultiCd() {
        val body = """{"data":[
          {"attributes":{"language":"en","release":"Example.Movie.2010.1080p","hearing_impaired":false,"download_count":900,"files":[{"file_id":111,"file_name":"a.srt"}]}},
          {"attributes":{"language":"en","release":"MT","machine_translated":true,"files":[{"file_id":112}]}},
          {"attributes":{"language":"de","release":"2CD","files":[{"file_id":113},{"file_id":114}]}},
          {"attributes":{"language":"de","release":"","hearing_impaired":true,"download_count":5,"files":[{"file_id":115,"file_name":"b.srt"}]}}
        ]}"""
        val hits = SubSearchLogic.osHits(body)
        assertEquals(listOf("111", "115"), hits.map { it.ref })
        assertEquals("b.srt", hits[1].release)
        assertTrue(hits[1].hi)
        assertEquals(emptyList<SubSearchLogic.Hit>(), SubSearchLogic.osHits("not json"))
    }

    @Test fun subdlHitsDropKeyAndOtherEpisodes() {
        val body = """{"status":true,"subtitles":[
          {"release_name":"Show.S01E02","language":"EN","hi":false,"url":"/subtitle/1-2.zip?api_key=secret","episode":2,"full_season":false},
          {"release_name":"Show.S01E03","language":"EN","url":"/subtitle/1-3.zip","episode":3,"full_season":false},
          {"release_name":"Season pack","language":"EN","url":"/subtitle/1-9.zip","episode":null,"full_season":true},
          {"release_name":"Bad","language":"EN","url":"/../etc/passwd","episode":2}
        ]}"""
        val hits = SubSearchLogic.sdHits(body, episode)
        assertEquals(listOf("/subtitle/1-2.zip"), hits.map { it.ref })
        assertFalse(hits.single().ref.contains("secret"))
        assertEquals(emptyList<SubSearchLogic.Hit>(), SubSearchLogic.sdHits("""{"status":false}""", episode))
        assertNull(SubSearchLogic.sdPath("https://evil.tld/subtitle/1.zip"))
    }

    @Test fun rankingPrefersLanguageThenNotCcThenDownloads() {
        fun h(p: String, ref: String, lang: String, hi: Boolean, dl: Int) = SubSearchLogic.Hit(p, ref, lang, ref, hi, dl, "srt")
        val hits = listOf(
            h(SubSearchLogic.SD, "en1", "en", false, 0), h(SubSearchLogic.OS, "de-cc", "de", true, 999),
            h(SubSearchLogic.OS, "de-big", "de", false, 50), h(SubSearchLogic.OS, "de-small", "de", false, 5),
            h(SubSearchLogic.OS, "fr", "fr", false, 9999), h(SubSearchLogic.OS, "de-big", "de", false, 50),
        )
        val q = SubSearchLogic.Query("", null, 1, 0, 0, listOf("de", "en"))
        assertEquals(listOf("de-big", "de-small", "de-cc", "en1"), SubSearchLogic.rank(hits, q, false).map { it.ref })
        assertEquals("de-cc", SubSearchLogic.rank(hits, q, true).first().ref)
    }

    @Test fun releasesOfOtherTitlesGoLast() {
        val q = SubSearchLogic.Query("Example: Who Is It?", 2026, 0, 0, 0, listOf("en"))
        fun h(rel: String, dl: Int) = SubSearchLogic.Hit(SubSearchLogic.OS, rel, "en", rel, false, dl, "srt")
        val ranked = SubSearchLogic.rank(listOf(h("Other.Show.S01E13.WEBRip", 9000), h("Example.Who.Is.It.2026.1080p.WEB", 10), h("Totally.Different.2019", 500)), q, false)
        assertEquals("Example.Who.Is.It.2026.1080p.WEB", ranked.first().ref)
        assertFalse(SubSearchLogic.fits("Other.Show.S01E13", q))
        assertTrue(SubSearchLogic.fits("Show.S01E13", q.copy(title = "Show", season = 1, episode = 13)))
        assertTrue(SubSearchLogic.osSearchUrl(q).contains("query=example+who+is+it"))
    }

    @Test fun forcedTracksGoBelowFullSubtitles() {
        val q = SubSearchLogic.Query("Example", 2026, 0, 0, 0, listOf("en"))
        fun h(rel: String, dl: Int) = SubSearchLogic.Hit(SubSearchLogic.OS, rel, "en", rel, false, dl, "srt")
        val ranked = SubSearchLogic.rank(listOf(h("Example.2026.WEB-GRPforced", 900), h("Example.2026.WEB.Forced", 800), h("Example.2026.WEB-GRP", 5)), q, false)
        assertEquals("Example.2026.WEB-GRP", ranked.first().ref)
    }

    @Test fun zipGivesLargestSubtitleAndTextBecomesUtf8() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("readme.txt")); z.write("ignore".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/small.srt")); z.write("1\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/main.srt")); z.write("1\n00:00:01,000 --> 00:00:02,000\nHallo\n".toByteArray()); z.closeEntry()
        }
        val (name, data) = SubSearchLogic.fromZip(bos.toByteArray())!!
        assertEquals("main.srt", name)
        assertTrue(String(data).contains("Hallo"))
        assertNull(SubSearchLogic.fromZip(ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()))

        val pack = ByteArrayOutputStream()
        ZipOutputStream(pack).use { z ->
            z.putNextEntry(ZipEntry("Show.S01E01.srt")); z.write(ByteArray(900)); z.closeEntry()
            z.putNextEntry(ZipEntry("Show.S01E02.srt")); z.write(ByteArray(100)); z.closeEntry()
            z.putNextEntry(ZipEntry("Show.S01E12.srt")); z.write(ByteArray(950)); z.closeEntry()
        }
        assertEquals("Show.S01E02.srt", SubSearchLogic.fromZip(pack.toByteArray(), 1, 2)!!.first)
        assertEquals("Show.S01E01.srt", SubSearchLogic.fromZip(pack.toByteArray(), 1, 1)!!.first)
        assertEquals("Show.S01E12.srt", SubSearchLogic.fromZip(pack.toByteArray())!!.first)
        val langs = ByteArrayOutputStream()
        ZipOutputStream(langs).use { z ->
            z.putNextEntry(ZipEntry("Show - S01E01 - Pilot-por.srt")); z.write(ByteArray(900)); z.closeEntry()
            z.putNextEntry(ZipEntry("Show - S01E01 - Pilot-eng.srt")); z.write(ByteArray(500)); z.closeEntry()
            z.putNextEntry(ZipEntry("Show - S01E02 - Next-eng.srt")); z.write(ByteArray(990)); z.closeEntry()
        }
        assertEquals("Show - S01E01 - Pilot-eng.srt", SubSearchLogic.fromZip(langs.toByteArray(), 1, 1, "en")!!.first)
        assertEquals("Show - S01E01 - Pilot-por.srt", SubSearchLogic.fromZip(langs.toByteArray(), 1, 1, "pt")!!.first)

        val latin1 = "Größe".toByteArray(charset("windows-1252"))
        assertEquals("Größe", String(SubSearchLogic.toUtf8(latin1), Charsets.UTF_8))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok".toByteArray()
        assertEquals("ok", String(SubSearchLogic.toUtf8(bom), Charsets.UTF_8))
        assertEquals("text/vtt", SubSearchLogic.mime("a.VTT"))
        assertEquals("application/x-subrip", SubSearchLogic.mime("a.srt"))
    }

    @Test fun keysKeepOnlySafeCharacters() {
        assertEquals("subdl_Ab-9", SubSearchLogic.cleanKey(" subdl_Ab-9/?&# "))
    }
}
