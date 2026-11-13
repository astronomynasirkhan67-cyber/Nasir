package com.ipvio.stream

import org.json.JSONObject
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Online subtitle search (like Kodi): OpenSubtitles.com and SubDL. Without Android, testable; the requests are in
 * [SubSearch]. Both services need the user's own key (Settings > API keys); no key is shipped with the app.
 *
 * Search order: TMDB ID (exact, also for episodes via season/episode), otherwise the cleaned title and year.
 */
object SubSearchLogic {
    const val OS = "OpenSubtitles"
    const val SD = "SubDL"
    const val OS_API = "https://api.opensubtitles.com/api/v1"
    const val SD_API = "https://api.subdl.com/api/v1/subtitles"
    const val SD_DL = "https://dl.subdl.com"
    /** OpenSubtitles requires an app name with version in the User-Agent. */
    const val UA = "IPVIO v1.2"

    /** What is searched: TMDB ID (0 = unknown) or title/year; [season]/[episode] > 0 for an episode. */
    data class Query(val title: String, val year: Int?, val tmdbId: Int, val season: Int, val episode: Int, val langs: List<String>) {
        val isEpisode get() = season > 0 && episode > 0
    }

    /** One hit. [ref] = OpenSubtitles file ID or SubDL download path (without key). */
    data class Hit(
        val provider: String, val ref: String, val lang: String, val release: String,
        val hi: Boolean, val downloads: Int, val format: String
    )

    /** API keys may contain letters, digits, "_" and "-" (SubDL: "subdl_…"), never characters that change an address. */
    fun cleanKey(s: String): String = s.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(128)

    private val YEAR = Regex("""\((19|20)\d{2}\)|\b(19|20)\d{2}\b""")
    private val SE = Regex("""(?i)\s*S\d{1,3}\s*E\d{1,4}.*$""")
    private val PREFIX = Regex("""^\s*[A-Z]{2,3}\s*[|:\-]\s+""")
    private val TAGS = Regex("""(?i)\[[^]]*]|\b(4k|uhd|fhd|hd|sd|1080p|720p|2160p|multi|sub|dubbed)\b""")

    /** Query from the player's title ("EN - Inception (2010)", "Show S01E02"). [uiLang] = app language as fallback. */
    fun query(title: String, tmdbId: Int, season: Int, episode: Int, subLang: String, uiLang: String): Query {
        val year = YEAR.find(title)?.value?.filter(Char::isDigit)?.toIntOrNull()
        var t = SE.replace(title, "")
        t = PREFIX.replace(t, "")
        t = YEAR.replace(t, " ")
        t = TAGS.replace(t, " ").replace(Regex("""[._]+"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
        return Query(t, year, tmdbId, season, episode, langs(subLang, uiLang))
    }

    /** Wanted languages: subtitle setting, then app language, then English. ISO 639-1, lowercase, no duplicates. */
    fun langs(subLang: String, uiLang: String): List<String> =
        listOf(subLang, uiLang, "en").map { it.trim().lowercase().take(2) }.filter { it.length == 2 && it.all(Char::isLetter) }.distinct()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Search address of OpenSubtitles. The parameters must be sorted alphabetically and lowercase, otherwise the
     * service answers with a redirect (and drops the key).
     */
    fun osSearchUrl(q: Query): String {
        val p = sortedMapOf<String, String>()
        p["languages"] = q.langs.joinToString(",")
        if (q.tmdbId > 0) p[if (q.isEpisode) "parent_tmdb_id" else "tmdb_id"] = q.tmdbId.toString()
        else {
            p["query"] = words(q.title).joinToString(" ")
            if (q.year != null && !q.isEpisode) p["year"] = q.year.toString()
        }
        if (q.isEpisode) { p["season_number"] = q.season.toString(); p["episode_number"] = q.episode.toString() }
        return "$OS_API/subtitles?" + p.entries.joinToString("&") { it.key + "=" + enc(it.value) }
    }

    fun sdSearchUrl(q: Query, key: String): String {
        val sb = StringBuilder(SD_API).append("?api_key=").append(enc(cleanKey(key)))
        if (q.tmdbId > 0) sb.append("&tmdb_id=").append(q.tmdbId) else {
            sb.append("&film_name=").append(enc(q.title))
            if (q.year != null && !q.isEpisode) sb.append("&year=").append(q.year)
        }
        sb.append("&type=").append(if (q.isEpisode) "tv" else "movie")
        if (q.isEpisode) sb.append("&season_number=").append(q.season).append("&episode_number=").append(q.episode)
        sb.append("&languages=").append(enc(q.langs.joinToString(",") { it.uppercase() })).append("&subs_per_page=30")
        return sb.toString()
    }

    fun osHits(body: String): List<Hit> {
        val a = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until a.length()) {
            val at = a.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val files = at.optJSONArray("files") ?: continue
            if (files.length() != 1) continue // multi-CD subtitles do not fit one stream
            val f = files.optJSONObject(0) ?: continue
            val id = f.opt("file_id")?.toString()?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: continue
            val lang = at.optString("language").lowercase().take(5)
            if (at.optBoolean("machine_translated") || at.optBoolean("ai_translated")) continue
            out.add(Hit(OS, id, lang, at.optString("release").ifBlank { f.optString("file_name") }.trim(), at.optBoolean("hearing_impaired"), at.optInt("download_count"), "srt"))
        }
        return out
    }

    fun sdHits(body: String, q: Query): List<Hit> {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!o.optBoolean("status")) return emptyList()
        val a = o.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            val path = sdPath(s.optString("url")) ?: continue
            // Episode search: only the wanted episode, no whole-season packs
            if (q.isEpisode && (s.optBoolean("full_season") || s.optInt("episode", -1) != q.episode)) continue
            out.add(Hit(SD, path, s.optString("language").lowercase().take(5), s.optString("release_name").trim(), s.optBoolean("hi"), 0, "zip"))
        }
        return out
    }

    /** "/subtitle/123-456.zip?api_key=…" → "/subtitle/123-456.zip" (the key never goes into lists or caches). */
    fun sdPath(url: String): String? {
        val p = url.substringBefore('?').trim()
        return p.takeIf { Regex("""^/subtitle/[A-Za-z0-9._-]+\.zip$""").matches(it) }
    }

    /**
     * Only wanted languages, best first: language order, release name fits the title ([q]), not for the hard of
     * hearing unless [ccPref], more downloads, OpenSubtitles before SubDL.
     */
    fun rank(hits: List<Hit>, q: Query, ccPref: Boolean): List<Hit> {
        fun li(h: Hit) = q.langs.indexOf(h.lang.take(2))
        return hits.distinctBy { it.provider + it.ref }.filter { li(it) >= 0 }.sortedWith(
            compareBy<Hit>({ li(it) }, { if (fits(it.release, q)) 0 else 1 }, { if (FORCED.containsMatchIn(it.release)) 1 else 0 }, { if (it.hi == ccPref) 0 else 1 }, { -it.downloads }, { if (it.provider == OS) 0 else 1 })
        )
    }

    /** "Forced" tracks only cover foreign-language lines, so full subtitles come first. */
    private val FORCED = Regex("""(?i)forced""")

    private val EP_TAG = Regex("""(?i)\bS\d{1,3}\s*E\d{1,4}\b|\b\d{1,2}x\d{2}\b""")

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 2 }

    /**
     * Release name fits the search: no episode tag for a movie, and at least half of the title words occur (services
     * sometimes return other titles, e.g. an episode of another series for a movie). Unknown title or release = fits.
     */
    fun fits(release: String, q: Query): Boolean {
        if (!q.isEpisode && EP_TAG.containsMatchIn(release)) return false
        val t = words(q.title)
        if (t.isEmpty() || release.isBlank()) return true
        val r = words(release).toSet()
        return t.count { it in r } * 2 >= t.size
    }

    /** MIME type for Media3 from the file name. */
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/x-subrip"
    }

    private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")

    /**
     * Subtitle file from a ZIP (SubDL), at most [max] bytes. Packs hold a whole season or several languages, so the file
     * named after the episode ([season]/[episode] > 0) and the language [lang] wins; otherwise the largest. null = none inside.
     */
    fun fromZip(bytes: ByteArray, season: Int = 0, episode: Int = 0, lang: String = "", max: Int = 4_000_000): Pair<String, ByteArray>? {
        val ep = if (season > 0 && episode > 0) Regex("""(?i)(?:s0*$season\W?e0*$episode|\b0*${season}x0*$episode)(?!\d)""") else null
        val tags = langTags(lang)
        val all = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name.substringAfterLast('.', "").lowercase() !in SUB_EXT) continue
                all.add(name to (readUpTo(z, max) ?: continue))
            }
        }
        fun score(n: String): Int {
            val words = n.lowercase().substringBeforeLast('.').split(Regex("""[^a-z]+""")).toSet()
            return (if (ep?.containsMatchIn(n) == true) 4 else 0) + (if (words.any { it in tags }) 2 else if (words.any { it in OTHER_LANG && it !in tags }) -2 else 0)
        }
        return all.maxWithOrNull(compareBy<Pair<String, ByteArray>>({ score(it.first) }, { it.second.size }))
    }

    /** Words that mark a language in file names ("…-por.srt", "….English.srt"). */
    private val OTHER_LANG = setOf(
        "ara", "ar", "por", "pt", "spa", "es", "fre", "fra", "fr", "ger", "deu", "de", "ita", "it", "rus", "ru", "tur", "tr",
        "chi", "zho", "jpn", "kor", "pol", "dut", "nld", "swe", "nor", "dan", "fin", "gre", "ell", "heb", "hun", "cze", "ces",
        "rum", "ron", "bul", "hrv", "srp", "bos", "eng", "en", "english", "portuguese", "spanish", "french", "german", "italian", "arabic"
    )

    private fun langTags(code: String): Set<String> {
        val c = code.take(2).lowercase()
        if (c.length != 2) return emptySet()
        val l = java.util.Locale.forLanguageTag(c)
        return setOfNotNull(c, runCatching { l.isO3Language }.getOrNull(), l.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase().takeIf { it.isNotBlank() }) +
            (when (c) { "de" -> setOf("ger"); "fr" -> setOf("fre"); "nl" -> setOf("dut"); "zh" -> setOf("chi"); "cs" -> setOf("cze"); "ro" -> setOf("rum"); "el" -> setOf("gre"); else -> emptySet() })
    }

    /** Up to [max] bytes of the current entry; null if larger (readNBytes needs a newer Android). */
    private fun readUpTo(z: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = z.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
    }

    /** Text of a subtitle file in UTF-8 (many .srt files are Windows-1252 / Latin-1). */
    fun toUtf8(data: ByteArray): ByteArray {
        val bom = data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
        if (bom) return data.copyOfRange(3, data.size)
        val dec = Charsets.UTF_8.newDecoder()
        return if (runCatching { dec.decode(java.nio.ByteBuffer.wrap(data)) }.isSuccess) data
        else String(data, charset("windows-1252")).toByteArray(Charsets.UTF_8)
    }

    /** Label in the list: "English · Release name". */
    fun label(h: Hit): String = langName(h.lang) + " · " + h.release.ifBlank { h.provider }

    fun langName(code: String): String = java.util.Locale.forLanguageTag(code.take(2)).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code.uppercase() }
}
