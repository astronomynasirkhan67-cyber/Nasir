package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round20Test {
    @Test fun versionNames() {
        assertEquals(listOf(1, 2, 3), UpdateLogic.parts("v1.2.3"))
        assertEquals(listOf(1, 1), UpdateLogic.parts("1.1-beta"))
        assertTrue(UpdateLogic.isNewerName("1.0", "v1.1"))
        assertTrue(UpdateLogic.isNewerName("1.9", "1.10"))
        assertFalse(UpdateLogic.isNewerName("1.1", "v1.1"))
        assertFalse(UpdateLogic.isNewerName("1.1.0", "v1.1"))
        assertFalse(UpdateLogic.isNewerName("1.2", "v1.1"))
        assertFalse(UpdateLogic.isNewerName("1.0", "latest"))
    }

    @Test fun picksApk() {
        val a = listOf("notes.txt" to "https://x/notes.txt", "app-release.apk" to "https://x/a.apk", "IPVIO-Stream.apk" to "https://x/i.apk")
        assertEquals("https://x/i.apk", UpdateLogic.pickApk(a))
        assertEquals("https://x/a.apk", UpdateLogic.pickApk(a.take(2)))
        assertNull(UpdateLogic.pickApk(a.take(1)))
    }

    @Test fun dueOncePerDay() {
        val h = 3_600_000L
        assertTrue(UpdateLogic.due(0L, 100 * h))
        assertFalse(UpdateLogic.due(100 * h, 110 * h))
        assertTrue(UpdateLogic.due(100 * h, 121 * h))
        assertTrue(UpdateLogic.due(200 * h, 100 * h))
    }
}

class MultiLogicTest {
    private fun movie(id: String, t: String, y: Int?) = Item(id, Kind.MOVIE, t, y, "http://h/movie/u/p/$id.mp4", null, "Filme")
    private fun live(id: String, t: String) = Item(id, Kind.LIVE, t, null, "http://h/live/u/p/$id.ts", null, "TV")
    private fun series(id: String, eps: List<Episode>) = Item(id, Kind.SERIES, "Dark", null, "", null, "Serien", eps)
    private fun ep(s: Int, n: Int, u: String) = Episode(s, n, EpTitles.of(n), u)

    @Test fun singleListUnchanged() {
        val a = listOf(movie("1", "Dune", 2021))
        val m = MultiLogic.merge(listOf(a), listOf("A"))
        assertTrue(m.items === a)
        assertTrue(m.shadow.isEmpty())
    }

    @Test fun mergesSourcesAndShadowsDuplicateMovies() {
        val a = listOf(movie("1", "Dune", 2021), live("10", "Das Erste"))
        val b = listOf(movie("2", "Dune", 2021), movie("3", "Heat", 1995), live("11", "Das Erste"), movie("1", "Dune", 2021))
        val m = MultiLogic.merge(listOf(a, b), listOf("A", "B"))
        assertEquals(5, m.items.size)   // duplicate ID "1" only once
        assertEquals(setOf("2"), m.shadow)
        assertEquals(4, m.visible().size)
        val heat = m.items.first { it.id == "3" }
        assertEquals(1, heat.src)
        assertTrue(m.inSource(m.items.first { it.id == "2" }, 1))
        assertFalse(m.inSource(m.items.first { it.id == "1" }, 1))
        assertEquals(2, m.items.count { it.kind == Kind.LIVE })
    }

    @Test fun mergesSeriesEpisodesAsVariants() {
        val a = listOf(series("s_dark", listOf(ep(1, 1, "http://a/series/u/p/1.mp4"), ep(1, 2, "http://a/series/u/p/2.mp4"))))
        val b = listOf(series("s_dark", listOf(ep(1, 2, "http://b/series/u/p/9.mp4"), ep(1, 3, "http://b/series/u/p/10.mp4"))))
        val m = MultiLogic.merge(listOf(a, b), listOf("A", "B"))
        assertEquals(1, m.items.size)
        val s = m.items[0]
        assertEquals(3, s.episodes.size)
        val e2 = s.episodes[1]
        assertEquals(2, e2.variants.size)
        assertEquals("B", e2.variants[1].label)
        assertEquals("http://a/series/u/p/2.mp4", e2.url)
        assertTrue(m.inSource(s, 0)); assertTrue(m.inSource(s, 1))
    }

    @Test fun orderAndKeys() {
        val o = MultiLogic.mergeOrder(listOf(mapOf("Filme" to 0, "Sport" to 1), mapOf("Sport" to 0, "Kids" to 1)))
        assertEquals(listOf("Filme", "Sport", "Kids"), o.entries.sortedBy { it.value }.map { it.key })
        assertEquals(setOf(3, -7), MultiLogic.decodeKeys(MultiLogic.encodeKeys(listOf(3, -7))))
        assertTrue(MultiLogic.decodeKeys(null).isEmpty())
    }
}

class TapLogicTest {
    @Test fun chunksAndTrim() {
        val c = TapLogic.CHUNK
        assertEquals(0L, TapLogic.keepFrom(c * 2, c * 3))
        assertEquals(c, TapLogic.keepFrom(c * 4 + 10, c * 3))
        assertEquals(2L, TapLogic.chunkOf(c * 2 + 5))
        assertEquals(5L, TapLogic.offsetIn(c * 2 + 5))
        assertEquals(376L, TapLogic.align(400))
    }

    @Test fun budget() {
        val mb = 1024L * 1024
        assertEquals(512 * mb, TapLogic.budget(512, 4000 * mb))
        assertEquals(0L, TapLogic.budget(512, 420 * mb))
        assertEquals(600 * mb, TapLogic.budget(2048, 1000 * mb))
    }

    @Test fun timeIndex() {
        val t = TapLogic.TimeIndex()
        t.add(0, 0); t.add(1000, 1000); t.add(2000, 3000)
        assertEquals(500L, t.byteAt(500))
        assertEquals(2000L, t.byteAt(1500))
        assertEquals(3000L, t.byteAt(9000))
        assertEquals(1500L, t.msAt(2000))
        t.add(1500, 5000)   // backwards in time: ignored
        assertEquals(3, t.size())
        t.trimBefore(2000)
        assertEquals(1000L, t.firstMs())
    }

    @Test fun texts() {
        assertEquals("1:05", TapLogic.behindText(65_000))
        assertEquals("1:00:01", TapLogic.behindText(3_601_000))
        assertTrue(TapLogic.tappable("http://h/live/u/p/1.ts"))
        assertTrue(TapLogic.tappable("http://h/u/p/1"))
        assertFalse(TapLogic.tappable("http://h/live/u/p/1.m3u8"))
        assertFalse(TapLogic.tappable("file:///x.ts"))
        val n = TapLogic.fileName("Das Erste", "Tatort: Der/Fall", 0L, java.util.TimeZone.getTimeZone("UTC"))
        assertEquals("Das Erste - Tatort_ Der_Fall 1970-01-01 0000.ts", n)
    }
}

class SportLogicTest {
    private fun ch(t: String, g: String, tvg: String? = null) = Item(t, Kind.LIVE, t, null, "http://h/live/u/p/$t.ts", null, g, tvgId = tvg)

    @Test fun detectsSportChannels() {
        assertTrue(SportLogic.isSportChannel(ch("Sky Sport 1", "DE | Sky")))
        assertTrue(SportLogic.isSportChannel(ch("Eurosport 2", "DE")))
        assertTrue(SportLogic.isSportChannel(ch("DAZN 1", "DE")))
        assertTrue(SportLogic.isSportChannel(ch("Kanal X", "DE | Sport")))
        assertTrue(SportLogic.isSportChannel(ch("beIN SPORTS 3", "TR")))
        assertFalse(SportLogic.isSportChannel(ch("Transport TV", "DE")))
        assertFalse(SportLogic.isSportChannel(ch("Das Erste", "DE | Vollprogramm")))
    }

    @Test fun eventsAndSlots() {
        assertTrue(SportLogic.isSportEvent("Fußball: Bundesliga, 12. Spieltag"))
        assertTrue(SportLogic.isSportEvent("Formel 1: Großer Preis von Brasilien"))
        assertFalse(SportLogic.isSportEvent("Tagesschau"))
        val a = ch("Das Erste", "DE", "ard"); val b = ch("Sky Sport 1", "DE", "sky1")
        val now = 10_000L
        val progs = mapOf(
            "ard" to listOf(EpgEntry("Tagesschau", "", 9000, 10_500), EpgEntry("Sportschau", "", 10_500, 12_000)),
            "sky1" to listOf(EpgEntry("Bundesliga live", "", 9500, 11_000))
        )
        val (n, s) = SportLogic.slots(listOf(a, b), { progs[it.tvgId].orEmpty() }, now)
        assertEquals(listOf("Bundesliga live"), n.map { it.prog.title })
        assertEquals(listOf("Sportschau"), s.map { it.prog.title })
    }
}

class StalkerLogicTest {
    @Test fun sourceAndMac() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerLogic.normMac("00-1a-79-ab-cd-ef"))
        assertEquals("00:1A:79:AB:CD:EF", StalkerLogic.normMac("001A79ABCDEF"))
        assertNull(StalkerLogic.normMac("00:1A:79:AB:CD"))
        val s = StalkerLogic.source("portal.tld:8080/c/", "00:1a:79:ab:cd:ef")!!
        assertEquals("http://portal.tld:8080/c/", s.portal)
        val enc = s.encode()
        assertTrue(StalkerLogic.isSource(enc))
        assertEquals(s, StalkerLogic.parse(enc))
        assertEquals(enc, PlaylistLogic.normalizeUrl(" $enc "))
        assertNull(StalkerLogic.source("", "00:1A:79:AB:CD:EF"))
    }

    @Test fun endpointsAndLinks() {
        assertEquals("http://p:8080/portal.php", StalkerLogic.endpoints("http://p:8080/c/").first())
        assertEquals("http://p/stalker_portal/server/load.php", StalkerLogic.endpoints("http://p/stalker_portal/c/").first())
        assertEquals("http://p/x/load.php", StalkerLogic.endpoints("http://p/x/load.php").single())
        assertEquals("http://real/ch/1?token=x", StalkerLogic.stripCmd("ffmpeg http://real/ch/1?token=x"))
        assertNull(StalkerLogic.stripCmd("ffmpeg "))
        assertEquals("http://p:8080/misc/logos/320/a.png", StalkerLogic.logoUrl("http://p:8080/c/", "a.png"))
        assertEquals("http://p:8080/img/a.png", StalkerLogic.logoUrl("http://p:8080/c/", "/img/a.png"))
    }

    @Test fun itemUrlsRoundTrip() {
        val u = StalkerLogic.itemUrl(42, "live", "ffrt http://localhost/ch/12345_")
        assertTrue(u.startsWith("stalker://42/live/") && u.endsWith(".ts"))
        val r = StalkerLogic.itemRef(u)!!
        assertEquals(42, r.key); assertEquals("itv", r.type); assertEquals("ffrt http://localhost/ch/12345_", r.cmd)
        assertEquals("vod", StalkerLogic.itemRef(StalkerLogic.itemUrl(-7, "movie", "x"))!!.type)
        assertNull(StalkerLogic.itemRef("http://x"))
    }

    @Test fun entriesParse() {
        val s = StalkerLogic.source("http://p/c/", "00:1A:79:00:00:01")!!
        val text = "#EXTM3U\n" + StalkerLogic.liveEntry(s, mapOf("cmd" to "ffrt http://localhost/ch/1", "name" to "Sky Sport, HD", "tv_genre_id" to "3", "logo" to "1.png"), mapOf("3" to "Sport"))!! +
            "\n" + StalkerLogic.movieEntry(s, mapOf("cmd" to "/media/1.mpg", "name" to "Dune", "year" to "2021"), "Sci-Fi")!!
        val p = M3uParser.parseStream(java.io.StringReader(text))
        assertEquals(Kind.LIVE, p.items[0].kind)
        assertEquals("Sport", p.items[0].group)
        val m = p.items.first { it.kind == Kind.MOVIE }
        assertEquals(2021, m.year)
        assertTrue(StalkerLogic.isItem(m.url))
    }
}

class PhoneTransferTest {
    private fun req(port: Int, head: String, body: ByteArray = ByteArray(0)): String {
        java.net.Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write((head + "\r\nHost: x\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun uploadBackupRestoreCrash() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "pi_" + System.nanoTime())
        var got: PhoneInput? = null
        var savedText: String? = null
        var restored: String? = null
        val pi = PhoneImport { got = it }
        pi.uploadDir = dir
        pi.saveUpload = { name, f -> savedText = f.readText(); "file:///x/imports/$name" }
        pi.backup = { pl -> "{\"v\":1,\"pl\":$pl}" }
        pi.restore = { t -> restored = t; true }
        pi.crash = { "IllegalStateException\n  at PlayerScreen.kt:782" }
        assertTrue(pi.start())
        try {
            val page = req(pi.port, "GET / HTTP/1.1")
            assertTrue(page.contains("/upload") && page.contains("/transfer") && page.contains("/crash"))
            assertTrue(req(pi.port, "POST /upload?code=0000&name=a.m3u HTTP/1.1", "#EXTM3U".toByteArray()).startsWith("HTTP/1.1 403"))
            assertTrue(req(pi.port, "POST /upload?code=${pi.code}&name=a.m3u HTTP/1.1", "#EXTM3U\n".toByteArray()).startsWith("HTTP/1.1 200"))
            assertEquals("#EXTM3U\n", savedText)
            assertEquals("file:///x/imports/a.m3u", got?.url)
            val b = req(pi.port, "GET /backup?code=${pi.code}&pl=1 HTTP/1.1")
            assertTrue(b.contains("attachment") && b.endsWith("{\"v\":1,\"pl\":true}"))
            assertTrue(req(pi.port, "POST /restore?code=${pi.code} HTTP/1.1", "{\"v\":1}".toByteArray()).startsWith("HTTP/1.1 200"))
            assertEquals("{\"v\":1}", restored)
            assertTrue(req(pi.port, "GET /crash HTTP/1.1").contains("name=\"code\""))
            assertTrue(req(pi.port, "GET /crash?code=${pi.code} HTTP/1.1").contains("PlayerScreen.kt:782"))
        } finally { pi.stop(); dir.deleteRecursively() }
    }

    @Test fun plainPageWithoutExtras() {
        val p = PhoneImportLogic.page(0)
        assertFalse(p.contains("/upload"))
        assertTrue(PhoneImportLogic.transferPage(1).contains("Include playlists"))
        assertTrue(PhoneImportLogic.crashPage(0, "<x>").contains("&lt;x&gt;"))
    }
}

class ImportUrlTest {
    @Test fun onlyAppImports() {
        val ok = "file:///data/user/0/com.ipvio.stream/files/imports/Liste.m3u"
        assertEquals(ok, PlaylistLogic.normalizeUrl(ok))
        assertNull(PlaylistLogic.normalizeUrl("file:///data/user/0/com.ipvio.stream/files/imports/../x.m3u"))
        assertNull(PlaylistLogic.normalizeUrl("file:///sdcard/x.m3u"))
    }
}
