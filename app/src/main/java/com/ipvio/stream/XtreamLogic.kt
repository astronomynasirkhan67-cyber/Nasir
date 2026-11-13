package com.ipvio.stream

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Xtream access via the provider's API (player_api.php) instead of via the finished list (get.php).
 * Without Android, testable.
 *
 * Some providers switch get.php off, limit it or answer with their own error codes (e.g. 884) while the
 * API works (this is how TiviMate works too). The app builds a list from the API's answers
 * in the same format as get.php (type=m3u_plus, output=ts). So parsing, language filter, cache and
 * title IDs stay the same.
 */
object XtreamLogic {
    /** Login with plain-text user and password; [base] = address before "/get.php" or "/player_api.php". */
    data class Acct(val base: String, val user: String, val pass: String)

    private val GET = Regex("""^(https?://[^?#]+?)/(?:get|player_api)\.php\?(.*)$""", RegexOption.IGNORE_CASE)
    private val SAFE = Regex("""^[A-Za-z0-9._~-]+$""")
    private val EXT = Regex("""^[A-Za-z0-9]{1,5}$""")
    private val YEAR_IN_NAME = Regex("""[\(\[](?:19|20)\d{2}[\)\]]""")
    private val YEAR = Regex("""^(?:19|20)\d{2}""")

    /** Login from a get.php address, otherwise null. */
    fun fromUrl(url: String): Acct? {
        val m = GET.find(url.trim()) ?: return null
        val q = HashMap<String, String>()
        for (p in m.groupValues[2].split('&')) {
            val i = p.indexOf('=')
            if (i <= 0) continue
            q[p.substring(0, i).lowercase()] = runCatching { URLDecoder.decode(p.substring(i + 1), "UTF-8") }.getOrDefault(p.substring(i + 1))
        }
        val u = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pw = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return Acct(m.groupValues[1].trimEnd('/'), u, pw)
    }

    private fun qEnc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Part of a path: as usual with providers unchanged, only special characters are encoded. */
    fun pathPart(s: String): String = if (SAFE.matches(s)) s else URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun api(a: Acct, action: String? = null, extra: String = ""): String =
        "${a.base}/player_api.php?username=${qEnc(a.user)}&password=${qEnc(a.pass)}" + (if (action != null) "&action=$action" else "") + extra

    private fun ext(s: String?, def: String): String = s?.trim()?.trimStart('.')?.takeIf { EXT.matches(it) } ?: def

    fun liveUrl(a: Acct, id: String): String = "${a.base}/live/${pathPart(a.user)}/${pathPart(a.pass)}/$id.ts"
    fun movieUrl(a: Acct, id: String, ext: String?): String = "${a.base}/movie/${pathPart(a.user)}/${pathPart(a.pass)}/$id.${ext(ext, "mp4")}"
    fun episodeUrl(a: Acct, id: String, ext: String?): String = "${a.base}/series/${pathPart(a.user)}/${pathPart(a.pass)}/$id.${ext(ext, "mp4")}"

    /** Value for an attribute of the #EXTINF line (without quotes and line breaks). */
    fun attr(s: String?): String = (s ?: "").replace('"', '\'').replace('\r', ' ').replace('\n', ' ').trim()

    /**
     * Name at the end of the #EXTINF line. Parsing takes everything after the last comma; a comma in the name is therefore
     * replaced by the similar-looking "‚" ("Tiger‚ Dragon" instead of just "Dragon").
     */
    fun name(s: String?): String = (s ?: "").replace(',', '‚').replace('\r', ' ').replace('\n', ' ').trim()

    private fun idOf(f: Map<String, String>, key: String): String? = f[key]?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun group(f: Map<String, String>, cats: Map<String, String>): String =
        f["category_id"]?.let { cats[it.trim()] }?.takeIf { it.isNotBlank() } ?: "Sonstige"

    /** Header line of the generated list (with the provider's EPG address). */
    fun header(a: Acct): String = "#EXTM3U url-tvg=\"${a.base}/xmltv.php?username=${qEnc(a.user)}&password=${qEnc(a.pass)}\""

    /** A channel from get_live_streams as two lines (#EXTINF + address), null = unusable. */
    fun liveEntry(a: Acct, f: Map<String, String>, cats: Map<String, String>): String? {
        val id = idOf(f, "stream_id") ?: return null
        val n = name(f["name"]).ifEmpty { return null }
        val sb = StringBuilder(160).append("#EXTINF:-1")
        attr(f["epg_channel_id"]).takeIf { it.isNotEmpty() }?.let { sb.append(" tvg-id=\"").append(it).append('"') }
        attr(f["stream_icon"]).takeIf { it.startsWith("http") }?.let { sb.append(" tvg-logo=\"").append(it).append('"') }
        sb.append(" group-title=\"").append(attr(group(f, cats))).append('"')
        if (f["tv_archive"]?.trim() == "1") {
            val d = f["tv_archive_duration"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            sb.append(" catchup=\"xc\"")
            if (d != null) sb.append(" catchup-days=\"").append(d).append('"')
        }
        return sb.append(',').append(n).append('\n').append(liveUrl(a, id)).toString()
    }

    /** A movie from get_vod_streams; if the year is missing in the name, it is added from "year" or "releaseDate". */
    fun movieEntry(a: Acct, f: Map<String, String>, cats: Map<String, String>): String? {
        val id = idOf(f, "stream_id") ?: return null
        var n = name(f["name"]).ifEmpty { return null }
        if (!YEAR_IN_NAME.containsMatchIn(n)) {
            val y = (f["year"] ?: f["releaseDate"] ?: f["release_date"])?.trim()?.let { YEAR.find(it)?.value }
            if (y != null) n = "$n ($y)"
        }
        val sb = StringBuilder(160).append("#EXTINF:-1")
        attr(f["stream_icon"]).takeIf { it.startsWith("http") }?.let { sb.append(" tvg-logo=\"").append(it).append('"') }
        sb.append(" group-title=\"").append(attr(group(f, cats))).append('"')
        return sb.append(',').append(n).append('\n').append(movieUrl(a, id, f["container_extension"])).toString()
    }

    /** A series from get_series (only what is needed for the episode list). */
    data class Series(val id: String, val name: String, val cover: String?, val group: String)

    fun seriesOf(f: Map<String, String>, cats: Map<String, String>): Series? {
        val id = idOf(f, "series_id") ?: return null
        val n = name(f["name"]).ifEmpty { return null }
        return Series(id, n, attr(f["cover"]).takeIf { it.startsWith("http") }, group(f, cats))
    }

    /** Ending of the placeholder address of a series whose episodes are only loaded on opening. */
    const val LAZY_EXT = "xtser"

    /**
     * Placeholder for a series: one entry instead of all episodes. Parsing sees a series with one episode (S01E01);
     * the app fetches the real episodes only when the series is opened (see [LazySeries]). With 50,000 series this saves
     * 50,000 requests when loading the playlist.
     */
    fun lazyEntry(a: Acct, s: Series): String {
        val sb = StringBuilder(160).append("#EXTINF:-1")
        s.cover?.let { sb.append(" tvg-logo=\"").append(it).append('"') }
        sb.append(" group-title=\"").append(attr(s.group)).append('"')
        sb.append(',').append(s.name).append(" S01E01")
        return sb.append('\n').append("${a.base}/series/${pathPart(a.user)}/${pathPart(a.pass)}/${s.id}.$LAZY_EXT").toString()
    }

    private val LAZY_URL = Regex("""^(https?://.+?)/series/([^/]+)/([^/]+)/(\d+)\.$LAZY_EXT$""")

    /** Login and series ID from a placeholder address; null = none. User and password come back unencoded. */
    fun parseLazy(url: String): Pair<Acct, String>? {
        val m = LAZY_URL.find(url) ?: return null
        val g = m.groupValues
        fun dec(x: String) = runCatching { URLDecoder.decode(x.replace("+", "%2B"), "UTF-8") }.getOrDefault(x)
        return Acct(g[1], dec(g[2]), dec(g[3])) to g[4]
    }

    /**
     * Placeholder addresses of a series (one per source with several playlists, label = name of the source, empty for the first).
     * Empty if the series has real episodes.
     */
    fun lazyUrls(i: Item): List<Pair<String, String>> {
        if (i.kind != Kind.SERIES || i.episodes.size != 1) return emptyList()
        val e = i.episodes[0]
        val vs = e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) }
        if (vs.any { parseLazy(it.url) == null }) return emptyList()
        return vs.map { it.url to it.label }
    }

    fun isLazy(i: Item): Boolean = lazyUrls(i).isNotEmpty()

    /** Season, episode and address of an episode from get_series_info; null = unusable. [seasonKey] = season from the key of the response. */
    fun episodeParts(a: Acct, f: Map<String, String>, seasonKey: Int?): Triple<Int, Int, String>? {
        val id = idOf(f, "id") ?: return null
        val season = f["season"]?.trim()?.toIntOrNull() ?: seasonKey ?: 1
        val num = f["episode_num"]?.trim()?.toIntOrNull() ?: return null
        if (season !in 0..99 || num !in 0..9999) return null
        return Triple(season, num, episodeUrl(a, id, f["container_extension"]))
    }

    /** An episode from get_series_info; [seasonKey] = season from the key of the response in case the episode names none. */
    fun episodeEntry(a: Acct, s: Series, f: Map<String, String>, seasonKey: Int?): String? {
        val id = idOf(f, "id") ?: return null
        val season = f["season"]?.trim()?.toIntOrNull() ?: seasonKey ?: 1
        val num = f["episode_num"]?.trim()?.toIntOrNull() ?: return null
        if (season !in 0..99 || num !in 0..9999) return null
        val sb = StringBuilder(160).append("#EXTINF:-1")
        s.cover?.let { sb.append(" tvg-logo=\"").append(it).append('"') }
        sb.append(" group-title=\"").append(attr(s.group)).append('"')
        sb.append(',').append(s.name).append(" S").append(season.toString().padStart(2, '0'))
            .append('E').append(num.toString().padStart(2, '0'))
        return sb.append('\n').append(episodeUrl(a, id, f["container_extension"])).toString()
    }

    /** Response without action: login valid? null = unknown (response not readable), otherwise true/false. */
    fun authOk(auth: String?, status: String?): Boolean? {
        val a = auth?.trim()
        if (a == "0") return false
        if (status != null && status.trim().lowercase() in setOf("disabled", "banned", "expired")) return false
        if (a == "1") return true
        return null
    }

    /** Does the beginning of a downloaded file look like an M3U list? */
    fun looksLikeM3u(head: String): Boolean {
        val t = head.trimStart('﻿', ' ', '\r', '\n', '\t')
        return t.startsWith("#EXTM3U") || t.startsWith("#EXTINF")
    }
}
