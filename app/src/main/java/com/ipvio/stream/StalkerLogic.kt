package com.ipvio.stream

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Stalker/Ministra portals (portal address + MAC address), round 20, extended in round 22 (series, more robust login).
 * Without Android, testable.
 *
 * A login is stored as "stalker:" + portal address + "#mac=" + MAC (encrypted like all logins).
 * The app builds a list in M3U format from it. Because portals hand out the play addresses only on request and often
 * valid only briefly, the list contains an own address per channel/movie "stalker://SOURCE/live|movie/CMD";
 * the player fetches the real address at start ([StalkerApi.resolve]).
 *
 * Series appear with a placeholder in the list ("stalker://SOURCE/series/DETAILS.ser", name "… S01E01"). Their seasons
 * and episodes are fetched by [LazySeries] only when the series is opened ([StalkerApi.episodeList]), just like with Xtream.
 */
object StalkerLogic {
    /**
     * [sn] = serial number, [id1] = device ID, [id2] = device ID 2 (as in TiviMate / STB Emu). Optional: some providers
     * identify the box by them in addition to the MAC. Empty = the app derives its own values from the MAC (as before).
     */
    data class Source(val portal: String, val mac: String, val sn: String = "", val id1: String = "", val id2: String = "") {
        /** Key of the source in the play addresses (hash of the stored details, without the details themselves). */
        val key: Int get() = encode().trim().hashCode()
        /** Without the optional values exactly as before (so existing logins keep their key and their cache). */
        fun encode(): String = "stalker:" + portal + "#mac=" + mac +
            (if (sn.isNotEmpty()) "#sn=$sn" else "") + (if (id1.isNotEmpty()) "#id1=$id1" else "") + (if (id2.isNotEmpty()) "#id2=$id2" else "")
    }

    /** Serial number / device ID as typed: only letters and digits (no separators, nothing that could break the stored text). */
    fun cleanId(raw: String): String = raw.trim().filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }.take(64)

    private val MAC = Regex("""^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$""")

    /** MAC in the usual form "00:1A:79:AB:CD:EF" or null if invalid. */
    fun normMac(raw: String): String? {
        val t = raw.trim().uppercase().replace('-', ':')
        if (MAC.matches(t)) return t
        val hex = t.filter { it.isDigit() || it in 'A'..'F' }
        return if (hex.length == 12 && t.all { it.isDigit() || it in 'A'..'F' || it == ':' || it == ' ' }) hex.chunked(2).joinToString(":") else null
    }

    /** Login from the inputs; null = portal address or MAC invalid. */
    fun source(portalRaw: String, macRaw: String, snRaw: String = "", id1Raw: String = "", id2Raw: String = ""): Source? {
        var p = portalRaw.trim()
        if (p.isEmpty()) return null
        if (!p.startsWith("http://", true) && !p.startsWith("https://", true)) p = "http://$p"
        if (PlaylistLogic.normalizeUrl(p) == null) return null
        val m = normMac(macRaw) ?: return null
        return Source(p, m, cleanId(snRaw), cleanId(id1Raw), cleanId(id2Raw))
    }

    fun isSource(url: String): Boolean = url.trim().startsWith("stalker:", true) && !url.trim().startsWith("stalker://", true)

    fun parse(url: String): Source? {
        val u = url.trim()
        if (!isSource(u)) return null
        val body = u.substring("stalker:".length)
        val i = body.lastIndexOf("#mac=")
        if (i <= 0) return null
        // After the MAC optionally "#sn=…", "#id1=…", "#id2=…"
        val rest = body.substring(i + 5).split('#')
        var sn = ""; var d1 = ""; var d2 = ""
        for (p in rest.drop(1)) {
            val kv = p.split('=', limit = 2)
            if (kv.size != 2) continue
            when (kv[0]) { "sn" -> sn = kv[1]; "id1" -> d1 = kv[1]; "id2" -> d2 = kv[1] }
        }
        return source(body.substring(0, i), rest[0], sn, d1, d2)
    }

    private val C_TAIL = Regex("""/c(/[^/]*)?$""", RegexOption.IGNORE_CASE)

    /** Possible API addresses of a portal, to be tried in this order. */
    fun endpoints(portal: String): List<String> {
        val p = portal.trim().trimEnd('/')
        // "…/c" and "…/c/index.html" are the box's web page, the API lies next to it
        val base = C_TAIL.replace(p, "").trimEnd('/')
        val out = LinkedHashSet<String>()
        if (p.endsWith(".php", true)) { out.add(p); return out.toList() }
        if (base.endsWith("/stalker_portal", true)) out.add("$base/server/load.php")
        out.add("$base/portal.php")
        out.add("$base/server/load.php")
        out.add("$base/stalker_portal/server/load.php")
        out.add("$base/c/portal.php")
        return out.toList()
    }

    /** Address of the logos (portals often give only the file name). */
    fun logoUrl(portal: String, logo: String?): String? {
        val l = logo?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        val p = portal.trim().trimEnd('/')
        val base = C_TAIL.replace(p, "").trimEnd('/')
        return if (l.startsWith("/")) Regex("""^(https?://[^/]+)""").find(base)?.value?.plus(l) else "$base/misc/logos/320/$l"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun api(endpoint: String, type: String, action: String, extra: String = ""): String =
        "$endpoint?type=$type&action=$action$extra&JsHttpRequest=1-xml"

    // Requesting a play address ("create_link"): see StalkerLink

    /** "ffmpeg http://…" / "ffrt http://…" / "auto http://…" → bare address. */
    fun stripCmd(cmd: String?): String? {
        val c = cmd?.trim() ?: return null
        val i = c.indexOf("http")
        val u = if (i >= 0) c.substring(i).trim() else return null
        return u.substringBefore(' ').takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /**
     * Cookie like on a MAG box: the MAC unchanged with colons (the device sends it that way too). Some portals
     * do not read the cookie URL-decoded and do not recognize an encoded MAC ("00%3A1A…").
     */
    fun cookie(mac: String, tz: String = "Europe/Berlin"): String = "mac=$mac; stb_lang=en; timezone=$tz"

    const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    const val X_UA = "Model: MAG250; Link: WiFi"
    private const val VER = "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.1; " +
        "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"

    private fun hash(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    /** Device identifiers some portals expect when fetching the profile (derived from the MAC, always the same). */
    fun profileExtra(mac: String): String = profileExtra(Source("", mac))

    /** Same, but values the user entered (serial number, device ID, device ID 2) take the place of the derived ones. */
    fun profileExtra(src: Source): String {
        val sn = src.sn.ifEmpty { hash("MD5", src.mac).take(13) }
        val dev = src.id1.ifEmpty { hash("SHA-256", src.mac) }
        val dev2 = src.id2.ifEmpty { dev }
        // As a MAG250 sends it (TiviMate / STB Emu do the same); some portals only accept the token for playback after this
        return "&hd=1&ver=${enc(VER)}&num_banks=2&sn=${enc(sn)}&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi" +
            "&device_id=${enc(dev)}&device_id2=${enc(dev2)}&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0" +
            "&api_signature=262"
    }

    // ------------------------------------------------------------------ Play addresses in the list

    /** Separator in the stored details (never occurs in portal data). */
    private const val SEP = "\u0001"

    private fun hex(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    private fun unhex(h: String): String? {
        if (h.length % 2 != 0) return null
        val b = ByteArray(h.length / 2)
        for (i in b.indices) b[i] = (h.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null).toByte()
        return String(b, Charsets.UTF_8)
    }

    /**
     * "stalker://KEY/live/HEX.ts" or "…/movie/HEX.mp4" – the extension helps with classification (live/movie).
     * [series] = episode number (episodes of a season share the season's command).
     */
    fun itemUrl(key: Int, kind: String, cmd: String, series: String? = null): String =
        "stalker://$key/$kind/" + hex(if (series != null) cmd + SEP + series else cmd) + (if (kind == "movie" || kind == "mfile" || kind == "efile") ".mp4" else ".ts")

    /**
     * [type] = "itv", "vod", "mfile" (movie whose file is looked up at play time; [cmd] is then the movie ID) or
     * "efile" (episode of a season; [cmd] also says where it sits, see [StalkerLink.Play.episode]).
     */
    class ItemRef(val key: Int, val type: String, val cmd: String, val series: String? = null)

    fun itemRef(url: String): ItemRef? {
        if (!url.startsWith("stalker://")) return null
        val rest = url.removePrefix("stalker://").split('/')
        if (rest.size != 3) return null
        val key = rest[0].toIntOrNull() ?: return null
        val type = when (rest[1]) { "live" -> "itv"; "movie" -> "vod"; "mfile" -> "mfile"; "efile" -> "efile"; else -> return null }
        val raw = unhex(rest[2].substringBefore('.')) ?: return null
        val p = raw.split(SEP, limit = 2)
        return ItemRef(key, type, p[0], p.getOrNull(1))
    }

    fun isItem(url: String): Boolean = url.startsWith("stalker://") && !url.contains("/series/")

    // ------------------------------------------------------------------ Series (placeholder, episodes only on opening)

    /** A series of the portal: [id], [cat] (category), [mode] "s" = own series area, "v" = series in the movie area. */
    class SeriesRef(val key: Int, val id: String, val cat: String, val mode: String)

    fun seriesUrl(key: Int, id: String, cat: String, mode: String): String =
        "stalker://$key/series/" + hex(listOf(id, cat, mode).joinToString(SEP)) + ".ser"

    fun seriesRef(url: String): SeriesRef? {
        if (!url.startsWith("stalker://")) return null
        val rest = url.removePrefix("stalker://").split('/')
        if (rest.size != 3 || rest[1] != "series") return null
        val key = rest[0].toIntOrNull() ?: return null
        val p = unhex(rest[2].substringBefore('.'))?.split(SEP) ?: return null
        if (p.size != 3 || p[0].isEmpty()) return null
        return SeriesRef(key, p[0], p[1], p[2])
    }

    /** Placeholder addresses of a series (one per source with several playlists, label = name of the source). Empty = real episodes. */
    fun lazyUrls(i: Item): List<Pair<String, String>> {
        if (i.kind != Kind.SERIES || i.episodes.size != 1) return emptyList()
        val e = i.episodes[0]
        val vs = e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) }
        if (vs.any { seriesRef(it.url) == null }) return emptyList()
        return vs.map { it.url to it.label }
    }

    fun isLazy(i: Item): Boolean = lazyUrls(i).isNotEmpty()

    private val SEASON_NAME = Regex("""(?i)(season|staffel|sezon|saison|temporada|stagione|sezona|s)\s*\.?\s*0*(\d{1,3})\b""")
    private val EPISODE_NAME = Regex("""(?i)(episode|folge|ep|e|bölüm|épisode|episodio|epizoda|aflevering)\s*\.?\s*0*(\d{1,4})\b""")
    private val EPISODE_AFTER = Regex("""(?i)\b0*(\d{1,4})\s*\.?\s*(episode|folge|bölüm|épisode|episodio|epizoda|aflevering)""")

    /** Season number: from fields of the portal, otherwise from the name ("Staffel 2", "Season 2", "S02"), otherwise [fallback]. */
    fun seasonNumber(f: Map<String, String>, fallback: Int): Int {
        for (k in listOf("season_number", "season_num", "season")) f[k]?.trim()?.toIntOrNull()?.takeIf { it in 0..999 }?.let { return it }
        val m = SEASON_NAME.find(f["name"] ?: f["title"] ?: "")
        return m?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 0..999 } ?: fallback
    }

    /** Episode number: from fields of the portal, otherwise from the name ("Episode 5", "E05", "5. Folge"), otherwise [fallback]. */
    fun episodeNumber(f: Map<String, String>, fallback: Int): Int {
        for (k in listOf("series_number", "episode_number", "episode_num", "episode")) f[k]?.trim()?.toIntOrNull()?.takeIf { it in 1..9999 }?.let { return it }
        val n = f["name"] ?: f["title"] ?: ""
        EPISODE_NAME.find(n)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..9999 }?.let { return it }
        EPISODE_AFTER.find(n)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..9999 }?.let { return it }
        return fallback
    }

    // ------------------------------------------------------------------ Entries of the list

    private fun attr(s: String?) = (s ?: "").replace('"', '\'').replace('\n', ' ').trim()
    /** Group name of a category in the list (as written into "group-title"). */
    fun groupName(category: String): String = attr(category)
    private fun name(s: String?) = (s ?: "").replace(',', '‚').replace('\n', ' ').trim()

    /** A channel as two M3U lines. */
    fun liveEntry(src: Source, f: Map<String, String>, genres: Map<String, String>): String? {
        val cmd = f["cmd"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = name(f["name"]).ifEmpty { return null }
        val sb = StringBuilder("#EXTINF:-1")
        attr(f["xmltv_id"]).takeIf { it.isNotEmpty() }?.let { sb.append(" tvg-id=\"").append(it).append('"') }
        logoUrl(src.portal, f["logo"])?.let { sb.append(" tvg-logo=\"").append(attr(it)).append('"') }
        sb.append(" group-title=\"").append(attr(f["tv_genre_id"]?.let { genres[it] } ?: "Sonstige")).append('"')
        return sb.append(',').append(n).append('\n').append(itemUrl(src.key, "live", cmd)).toString()
    }

    /** A movie as two M3U lines. */
    fun movieEntry(src: Source, f: Map<String, String>, category: String): String? {
        val cmd = f["cmd"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var n = name(f["name"]).ifEmpty { return null }
        val y = f["year"]?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        if (y != null && !n.contains("($y)")) n = "$n ($y)"
        val sb = StringBuilder("#EXTINF:-1")
        // Portals often send the cover as a path without a server ("/stalker_portal/screenshots/…")
        cover(src, f)?.let { sb.append(" tvg-logo=\"").append(attr(it)).append('"') }
        sb.append(" group-title=\"").append(attr(category.ifBlank { "Filme" })).append('"')
        // Movies with their own files ("has_files", cmd "/media/<id>.mpg"): the file is looked up at play time
        val url = if (needsFileLookup(f, cmd)) itemUrl(src.key, "mfile", f["id"]!!.trim()) else itemUrl(src.key, "movie", cmd)
        return sb.append(',').append(n).append('\n').append(url).toString()
    }

    /** Cover of a movie/series; relative paths ("/stalker_portal/screenshots/…") are resolved against the portal. */
    private fun cover(src: Source, f: Map<String, String>): String? =
        listOf("screenshot_uri", "cover_big", "cover", "logo").firstNotNullOfOrNull { k ->
            f[k]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { if (it.startsWith("http")) it else logoUrl(src.portal, it) }
        }

    /**
     * Movie whose play command only comes from its file ID: Ministra video club with "has_files" and cmd "/media/<id>.mpg".
     * create_link with the movie's own cmd returns no address there; it needs "/media/file_<file ID>.mpg".
     */
    fun needsFileLookup(f: Map<String, String>, cmd: String): Boolean =
        f.containsKey("has_files") && !f["id"].isNullOrBlank() && !cmd.contains("://") && cmd.contains("/media/") && !cmd.contains("/media/file_")

    /** A series as a placeholder (two M3U lines); the name ends in "S01E01" so parsing recognizes it as a series. */
    fun seriesEntry(src: Source, f: Map<String, String>, catId: String, catName: String, mode: String): String? {
        val id = (f["id"] ?: f["series_id"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = name(f["name"] ?: f["title"]).ifEmpty { return null }
        val sb = StringBuilder("#EXTINF:-1")
        cover(src, f)?.let { sb.append(" tvg-logo=\"").append(attr(it)).append('"') }
        sb.append(" group-title=\"").append(attr(catName.ifBlank { "Serien" })).append('"')
        return sb.append(',').append(n).append(" S01E01").append('\n').append(seriesUrl(src.key, id, catId, mode)).toString()
    }

    // ------------------------------------------------------------------ Categories on demand

    /**
     * A movie/series category of which only the newest pages were fetched while loading. The rest comes when the
     * user opens the category (see [StalkerMore]). [pages] = pages loaded so far, [total] = entries according to the
     * portal (-1 = unknown).
     */
    data class CatRef(val type: String, val id: String, val name: String, val total: Int, val pages: Int) {
        fun encode(): String = listOf(type, id, total.toString(), pages.toString(), name.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")

        companion object {
            fun decode(line: String): CatRef? {
                val p = line.split('\t')
                if (p.size != 5 || (p[0] != "vod" && p[0] != "series") || p[1].isEmpty() || p[4].isBlank()) return null
                val total = p[2].toIntOrNull() ?: return null
                val pages = p[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return CatRef(p[0], p[1], p[4], total, pages)
            }
        }
    }

    fun encodeCats(l: List<CatRef>): String = l.joinToString("") { it.encode() + "\n" }
    fun decodeCats(text: String?): List<CatRef> = text.orEmpty().lineSequence().mapNotNull { CatRef.decode(it) }.toList()

    /**
     * Categories named [group] that belong to a view showing [movies] and/or [series]. Movie and series areas can use the
     * same name ("ENGLISH"); a movie view uses the movie area ("vod"), a series view the series area and, if the portal
     * has none of that name, the movie area (portals also file series there).
     */
    fun catsFor(refs: Collection<CatRef>, group: String, movies: Boolean, series: Boolean): List<CatRef> {
        val named = refs.filter { it.name == group }
        if (movies) return named.filter { it.type == "vod" }
        if (!series) return emptyList()
        return named.filter { it.type == "series" }.ifEmpty { named.filter { it.type == "vod" } }
    }

    /** Page count from total and page size; [fallback] if the portal sends no numbers. */
    fun pageCount(total: Int, perPage: Int, fallback: Int): Int =
        if (total <= 0 || perPage <= 0) fallback else (total + perPage - 1) / perPage
}
