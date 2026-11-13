package com.ipvio.stream

enum class Kind { LIVE, MOVIE, SERIES }

data class Variant(val url: String, val label: String, val quality: String?, val lang: String?)

data class Episode(
    val season: Int, val num: Int, val title: String, val url: String,
    val variants: List<Variant> = emptyList(), val quality: String? = null
)

/**
 * Chooses the source of an episode by language, quality and source (user's preference).
 * Without a fixed quality [cap] decides (see [QualityLogic]): the best source the device handles well.
 */
fun pickVariant(ep: Episode, q: String?, lang: String?, label: String?, cap: Int = 4): Variant {
    var c = ep.variants.ifEmpty { listOf(Variant(ep.url, "", ep.quality, null)) }
    if (lang != null) c.filter { it.lang == lang }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (q != null) c.filter { it.quality == q }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (label != null) c.filter { it.label == label }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (q == null && c.size > 1) return c[QualityLogic.order(c.map { it.quality }, cap).first()]
    return c.first()
}

/** "Episode 1" … only once in memory instead of once per episode. */
object EpTitles {
    private val CACHE = arrayOfNulls<String>(2000)
    fun of(n: Int): String {
        if (n < 0 || n >= CACHE.size) return "Episode $n"
        return CACHE[n] ?: "Episode $n".also { CACHE[n] = it }
    }
}

/**
 * The episodes of a series, stored compactly (instead of a database – measured: same effect, without load time):
 * season/episode as numbers, the address only as its end ("12345.mkv") – the common beginning ("http://server/series/user/pass/")
 * is in memory once. An episode ([Episode]) is built only on access. For the rest of the app it is an ordinary list.
 * With large providers ~70 % of all entries are episodes: 581,000 episodes need ~12 MB instead of ~88 MB this way.
 */
class EpisodeTable private constructor(
    /** (season shl 16) or episode */
    private val se: IntArray,
    private val pre: Array<String>,
    /** Beginning per episode (index into [pre]); null = all [pre] 0 */
    private val preIdx: ByteArray?,
    /** Address ends one after another, [sufEnd] = end per episode */
    private val suf: String,
    private val sufEnd: IntArray,
    /** Quality per episode as a code (see [Q]) */
    private val q: ByteArray,
    /** Sources per episode, only for episodes with several sources (otherwise null) */
    private val vars: Array<List<Variant>?>?,
    /** Number of distinct seasons (for the cards, without building all episodes) */
    val seasonCount: Int
) : AbstractList<Episode>(), RandomAccess {
    override val size: Int get() = se.size

    override fun get(index: Int): Episode {
        val k = se[index]
        val n = k and 0xFFFF
        val start = if (index == 0) 0 else sufEnd[index - 1]
        val p = pre[preIdx?.get(index)?.toInt() ?: 0]
        return Episode(k ushr 16, n, EpTitles.of(n), p + suf.substring(start, sufEnd[index]), vars?.get(index) ?: emptyList(), Q[q[index].toInt()])
    }

    companion object {
        private val Q = arrayOf(null, "HD", "FHD", "4K")
        private fun qCode(s: String?): Int = Q.indexOf(s)

        /** Beginning of an address up to and including the last "/". */
        fun prefixOf(url: String): String = url.substring(0, url.lastIndexOf('/') + 1)

        /**
         * Compact form of an episode list. If something does not fit the scheme (unusual title, quality, number,
         * more than 120 distinct address beginnings), the list stays as it is – nothing is lost.
         */
        fun of(list: List<Episode>, pool: StringPool? = null): List<Episode> {
            if (list.isEmpty()) return emptyList()
            if (list is EpisodeTable) return list
            val n = list.size
            val se = IntArray(n)
            val q = ByteArray(n)
            val sufEnd = IntArray(n)
            val sb = StringBuilder(n * 12)
            val pres = ArrayList<String>(2)
            val preIdx = ByteArray(n)
            var vars: Array<List<Variant>?>? = null
            val seasons = HashSet<Int>()
            for (i in 0 until n) {
                val e = list[i]
                if (e.season !in 0..0xFFFF || e.num !in 0..0xFFFF || e.title != EpTitles.of(e.num)) return list
                val qc = qCode(e.quality)
                if (qc < 0) return list
                se[i] = (e.season shl 16) or e.num
                q[i] = qc.toByte()
                seasons.add(e.season)
                val p = prefixOf(e.url)
                var pi = pres.indexOf(p)
                if (pi < 0) {
                    if (pres.size >= 120) return list
                    pres.add(pool?.of(p) ?: p); pi = pres.lastIndex
                }
                preIdx[i] = pi.toByte()
                sb.append(e.url, p.length, e.url.length)
                sufEnd[i] = sb.length
                if (e.variants.isNotEmpty()) {
                    if (vars == null) vars = arrayOfNulls(n)
                    vars[i] = e.variants
                }
            }
            return EpisodeTable(se, pres.toTypedArray(), if (pres.size == 1) null else preIdx, sb.toString(), sufEnd, q, vars, seasons.size)
        }
    }
}

/** Number of seasons of a series – fast with the compact list (cards show this on every draw). */
fun seasonCount(item: Item): Int = (item.episodes as? EpisodeTable)?.seasonCount ?: item.episodes.map { it.season }.distinct().size

data class Item(
    val id: String,
    val kind: Kind,
    val title: String,
    val year: Int?,
    val url: String,
    val logo: String?,
    val group: String,
    val episodes: List<Episode> = emptyList(),
    val quality: String? = null,
    val tvgId: String? = null,
    /** Language according to the provider's tag (abbreviation from [LangTagLogic], "?" = no tag). */
    val lang: String = LangTagLogic.UNKNOWN,
    /** Source with several playlists shown at once: 0 = main playlist, 1 … = further ones (see [MultiLogic]). */
    val src: Int = 0
) {
    // Key for the metadata cache: computed once per title instead of anew on every sort comparison.
    // Own field instead of "by lazy": saves two helper objects per title with 100,000+ titles.
    private var mkCache: String? = null
    val mk: String get() = mkCache ?: (kind.name + "|" + title.lowercase() + "|" + (year ?: 0)).also { mkCache = it }
}

/**
 * Result of parsing: entries (without hidden languages) plus the EPG addresses named in the head of the list.
 * [counts] = titles per language in the whole list, [bytes] = amount of data per language (for the memory estimate).
 */
class ParsedPlaylist(
    val items: List<Item>, val epgUrls: List<String>,
    val counts: Map<String, Int> = emptyMap(), val bytes: Map<String, Long> = emptyMap(),
    /** Channels with archive (catchup) according to the playlist: channel ID → details. */
    val catchup: Map<String, CatchupInfo> = emptyMap()
)

/** Result of the quick scan pass (without creating titles): titles and amount of data per language. */
class PlaylistScan(val counts: Map<String, Int>, val bytes: Map<String, Long>, val entries: Int)

/** Thrown when parsing should be aborted (memory tight, user cancels). */
class ParseStop(msg: String) : RuntimeException(msg)

/**
 * Stores equal texts only once in memory (categories, labels, languages).
 * Large playlists repeat the same category tens of thousands of times – without a pool every repeat would be its own text.
 */
class StringPool {
    private val map = HashMap<String, String>()
    fun of(s: String): String = map.getOrPut(s) { s }
    fun ofOrNull(s: String?): String? = if (s == null) null else of(s)
}

object M3uParser {
    /** Language of an entry; the category is examined only once per name. */
    private class LangOf(private val pool: StringPool) {
        private val byGroup = HashMap<String, String>()
        private val vodByGroup = HashMap<String, Boolean>()
        fun of(group: String, name: String, live: Boolean): String {
            val g = byGroup.getOrPut(group) { LangTagLogic.detect(group) ?: "" }
            if (g.isNotEmpty()) return g
            return pool.of(LangTagLogic.of("", name, live))
        }
        /** Does the category name look like films ("VOD", "Kino" …)? Checked once per category, not once per entry. */
        fun vod(group: String): Boolean = vodByGroup.getOrPut(group) { VOD_GROUP.containsMatchIn(group) }
    }

    // Episode up to 4 digits: anime like One Piece have over 1,000 episodes ("S01 E1050" used to become episode 105)
    private val SE = Regex("""^(.*?)[\s._-]*[Ss](\d{1,2})[\s._-]*[Ee](\d{1,4})""")
    private val YEAR = Regex("""[\(\[]((?:19|20)\d{2})[\)\]]""")
    private val PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[|:]\s*""")
    private val LANG = Regex("""^\s*([A-Za-z]{2,3})\s*[|:]""")
    private val BRACKETS = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val TAGS = Regex("""\b(4K|UHD|FHD|HD|HEVC|H265|1080p|720p|MULTI|GERMAN|DUAL)\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s{2,}""")
    private val VOD_GROUP = Regex("""film|movie|vod|kino|cinema""", RegexOption.IGNORE_CASE)
    /**
     * Address ends in .mp4, .mkv or .avi (case does not matter), optionally followed by a query ("?…").
     * Same result as the regular expression `\.(mp4|mkv|avi)(\?.*)?$`, without scanning the whole address with it.
     */
    internal fun isVideoFile(url: String): Boolean {
        var i = url.indexOf('.')
        while (i >= 0) {
            val end = i + 4
            if (end <= url.length && (end == url.length || url[end] == '?') &&
                (url.regionMatches(i + 1, "mp4", 0, 3, ignoreCase = true) ||
                 url.regionMatches(i + 1, "mkv", 0, 3, ignoreCase = true) ||
                 url.regionMatches(i + 1, "avi", 0, 3, ignoreCase = true))) return true
            i = url.indexOf('.', i + 1)
        }
        return false
    }
    private val Q4K = Regex("""\b(4K|UHD|2160p?)\b""", RegexOption.IGNORE_CASE)
    private val QFHD = Regex("""\b(FHD|1080[pi]?)\b""", RegexOption.IGNORE_CASE)
    private val QHD = Regex("""\b(HD|720p?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Cheap necessary condition for a quality word ("4K", "UHD", "2160", "FHD", "1080", "HD", "720"): most names have none,
     * so the three regular expressions only run when this passes (measured: ~25 % of the parse time on large lists).
     */
    internal fun mayHaveQuality(s: String): Boolean {
        val n = s.length
        var i = 0
        while (i < n) {
            when (s[i]) {
                '4' -> if (i + 1 < n && (s[i + 1] == 'k' || s[i + 1] == 'K')) return true
                '2' -> if (s.startsWith("2160", i)) return true
                '7' -> if (s.startsWith("720", i)) return true
                '1' -> if (s.startsWith("1080", i)) return true
                'U', 'u' -> if (i + 2 < n && (s[i + 1] == 'h' || s[i + 1] == 'H') && (s[i + 2] == 'd' || s[i + 2] == 'D')) return true
                'H', 'h' -> if (i + 1 < n && (s[i + 1] == 'd' || s[i + 1] == 'D')) return true
            }
            i++
        }
        return false
    }

    private fun quality(name: String, group: String): String? {
        if (!mayHaveQuality(name) && !mayHaveQuality(group)) return null
        val s = "$name $group"
        return when {
            Q4K.containsMatchIn(s) -> "4K"
            QFHD.containsMatchIn(s) -> "FHD"
            QHD.containsMatchIn(s) -> "HD"
            else -> null
        }
    }

    private fun rank(q: String?) = when (q) { "4K" -> 4; "FHD" -> 3; "HD" -> 2; else -> 1 }

    /**
     * Value of `key="…"` in an #EXTINF line (the first occurrence, as the regular expression `key="([^"]*)"` would find it).
     * Plain text search instead of a regular expression: it runs 3 times for every entry.
     */
    private fun attr(line: String, key: String): String? {
        val tag = key + "=\""
        val at = line.indexOf(tag)
        if (at < 0) return null
        val from = at + tag.length
        val end = line.indexOf('"', from)
        return if (end < 0) null else line.substring(from, end)
    }

    private fun epgFromHeader(first: String?): List<String> {
        if (first == null) return emptyList()
        return listOf("url-tvg", "x-tvg-url").mapNotNull { attr(first, it) }
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.startsWith("http") }
    }

    fun epgUrls(text: String): List<String> =
        epgFromHeader(text.lineSequence().firstOrNull { it.startsWith("#EXTM3U") })

    private fun clean(raw: String): String {
        var t = LangTagLogic.stripDashTag(raw.replace(PREFIX, ""))
        if (t.indexOf('(') >= 0 || t.indexOf('[') >= 0) t = t.replace(BRACKETS, "")
        t = t.replace(TAGS, "")
        t = t.replace(SPACES, " ").trim().trim('-', '|', ':', '.', ' ')
        return if (t.isBlank()) raw.trim() else t
    }

    private fun isSeparator(n: String): Boolean {
        val t = n.trim()
        return t.isEmpty() || t.startsWith("***") || t.startsWith("---") || t.startsWith("===") ||
            t.startsWith("###") || t.startsWith("___")
    }

    fun parse(text: String): List<Item> = parseLines(text.lineSequence()).items

    /** Reads line by line from a Reader. This way the whole list never has to sit in memory as a single String. */
    fun parseStream(reader: java.io.Reader, hidden: Set<String> = emptySet(), check: (() -> Unit)? = null): ParsedPlaylist =
        LinePump(reader).use { parseLines(it.lines(), hidden, check) }

    /** Quick pass without creating titles: how many titles and how much data per language (for the language selection). */
    fun scanStream(reader: java.io.Reader, check: (() -> Unit)? = null): PlaylistScan =
        LinePump(reader).use { pump ->
            val lines = pump.lines()
            val st = Stats()
            val langOf = LangOf(StringPool())
            var n = 0
            entries(lines, check) { e -> n++; classify(e, st, langOf) }
            PlaylistScan(st.counts(), st.bytes, n)
        }

    /** An entry of the list: the #EXTINF line is only split further on demand (logo, tvg-id). */
    private class Entry(val info: String, val name: String, val group: String, val url: String)

    /** Counts titles and amount of data per language; series count once per language (not every episode). */
    private class Stats {
        val titles = HashMap<String, Int>()
        val bytes = HashMap<String, Long>()
        val series = HashSet<String>()
        fun add(lang: String, size: Int, seriesKey: String?) {
            bytes[lang] = (bytes[lang] ?: 0L) + size
            if (seriesKey == null || series.add(lang + "\u0001" + seriesKey)) titles[lang] = (titles[lang] ?: 0) + 1
        }
        fun counts(): Map<String, Int> = titles
    }

    /** What is determined when parsing an entry: kind, language, season/episode. */
    private class Kinded(val kind: Kind, val lang: String, val se: MatchResult?)

    /** Goes through the lines and reports each entry individually – nothing is collected. */
    private inline fun entries(lines: Sequence<String>, noinline check: (() -> Unit)?, onHeader: (String) -> Unit = {}, onEntry: (Entry) -> Unit) {
        var info: String? = null
        var name: String? = null
        var group = "Sonstige"
        var header = false
        var k = 0
        for (line in lines) {
            val l = line.trim()
            if (!header && l.startsWith("#EXTM3U")) { header = true; onHeader(l) }
            if (l.startsWith("#EXTINF")) {
                info = l
                name = l.substringAfterLast(',').trim()
                group = attr(l, "group-title")?.ifBlank { null } ?: "Sonstige"
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val n = name
                val inf = info
                if (n != null && inf != null) {
                    onEntry(Entry(inf, n, group, l))
                    if (++k % 2000 == 0) check?.invoke()
                }
                name = null; info = null
            }
        }
    }

    /** The episode pattern needs an "S" directly followed by a digit; names without it skip the regular expression. */
    internal fun mayBeEpisode(name: String): Boolean {
        var i = name.indexOf('S')
        var j = name.indexOf('s')
        while (i >= 0 || j >= 0) {
            val k = if (i < 0) j else if (j < 0) i else minOf(i, j)
            if (k + 1 < name.length && name[k + 1] in '0'..'9') return true
            if (k == i) i = name.indexOf('S', k + 1) else j = name.indexOf('s', k + 1)
        }
        return false
    }

    /** Kind and language of an entry; counts it in [st]. null = separator, skipped. */
    private fun classify(e: Entry, st: Stats, langOf: LangOf): Kinded? {
        if (isSeparator(e.name)) return null
        val m = if (mayBeEpisode(e.name)) SE.find(e.name) else null
        val isSeriesUrl = e.url.contains("/series/")
        val isMovie = e.url.contains("/movie/") || isVideoFile(e.url) || langOf.vod(e.group)
        // Stalker channels are always live, even in groups like "ENGLISH | PREMIUM MOVIES" (movie channels)
        val stalkerLive = e.url.startsWith("stalker://") && e.url.contains("/live/")
        val kind = if (stalkerLive) Kind.LIVE else if (m != null || isSeriesUrl) Kind.SERIES else if (isMovie) Kind.MOVIE else Kind.LIVE
        val lang = langOf.of(e.group, e.name, kind == Kind.LIVE)
        val countKey = if (kind == Kind.SERIES) (m?.groupValues?.get(1) ?: e.name).trim().lowercase() else null
        st.add(lang, e.info.length + e.url.length + 2, countKey)
        return Kinded(kind, lang, m)
    }

    /** Episode of a series when merging at the end (only for one series at a time in memory). */
    private class EpRaw(val season: Int, val num: Int, val url: String, val label: String, val q: String?, val lang: String?)

    /**
     * Episodes of a series while parsing – without an object per episode: numbers in fields, address ends as bytes
     * one after another, beginnings/labels/languages as references to shared texts. Saves a good 30 MB at the peak when parsing large lists
     * (580,000 episodes).
     */
    private class SeriesAcc(val title: String, val logo: String?, val group: String, val q: String?, val lang: String) {
        var n = 0
        var ss = IntArray(4)
        var nn = IntArray(4)
        var pre = arrayOfNulls<String>(4)
        var lab = arrayOfNulls<String>(4)
        var qq = arrayOfNulls<String>(4)
        var vl = arrayOfNulls<String>(4)
        var sufEnd = IntArray(4)
        var suf = ByteArray(32)
        var sufLen = 0
        var done = false

        fun add(season: Int, num: Int, p: String, url: String, label: String, q: String?, lang: String?) {
            if (n == ss.size) {
                val c = n * 2
                ss = ss.copyOf(c); nn = nn.copyOf(c); pre = pre.copyOf(c); lab = lab.copyOf(c); qq = qq.copyOf(c); vl = vl.copyOf(c); sufEnd = sufEnd.copyOf(c)
            }
            val b = url.substring(p.length).toByteArray(Charsets.UTF_8)
            if (sufLen + b.size > suf.size) suf = suf.copyOf(maxOf(suf.size * 2, sufLen + b.size))
            System.arraycopy(b, 0, suf, sufLen, b.size)
            sufLen += b.size
            ss[n] = season; nn[n] = num
            pre[n] = p; lab[n] = label; qq[n] = q; vl[n] = lang
            sufEnd[n] = sufLen
            n++
        }

        /** All episodes as a simple list (only briefly, when merging this one series). */
        fun raw(): ArrayList<EpRaw> {
            val out = ArrayList<EpRaw>(n)
            for (i in 0 until n) {
                val start = if (i == 0) 0 else sufEnd[i - 1]
                val url = pre[i] + String(suf, start, sufEnd[i] - start, Charsets.UTF_8)
                out.add(EpRaw(ss[i], nn[i], url, lab[i]!!, qq[i], vl[i]))
            }
            return out
        }

        fun free() { done = true; n = 0; ss = IntArray(0); nn = IntArray(0); pre = arrayOfNulls(0); lab = arrayOfNulls(0); qq = arrayOfNulls(0); vl = arrayOfNulls(0); sufEnd = IntArray(0); suf = ByteArray(0) }
    }

    private fun epTitle(n: Int): String = EpTitles.of(n)

    /**
     * Reads the list in one pass. Hidden languages ([hidden]) are only counted but not created –
     * so a list with 800,000 entries needs only as much memory as the chosen languages.
     * [check] is called every 2,000 entries (progress, abort, memory guard).
     */
    fun parseLines(lines: Sequence<String>, hidden: Set<String> = emptySet(), check: (() -> Unit)? = null): ParsedPlaylist {
        val pool = StringPool()
        val langOf = LangOf(pool)
        val st = Stats()
        val out = ArrayList<Item>()
        val series = LinkedHashMap<String, SeriesAcc>()
        var header: String? = null
        var headCu: CatchupInfo? = null
        val catchup = HashMap<String, CatchupInfo>()

        entries(lines, check, onHeader = { header = it; headCu = CatchupLogic.fromInfo(it, "") }) { e ->
            val kd = classify(e, st, langOf) ?: return@entries
            if (LangTagLogic.hides(hidden, kd.lang, kd.kind)) return@entries
            val group = pool.of(e.group)
            val q = quality(e.name, e.group)
            when (kd.kind) {
                Kind.SERIES -> {
                    val m = kd.se
                    // Key (= series ID) as before from the cleaned name, so Resume/My list are preserved
                    val cleaned = clean(m?.groupValues?.get(1) ?: e.name)
                    val key = cleaned.lowercase()
                    val title = TitleText.title(cleaned)
                    val acc = series.getOrPut(key) {
                        SeriesAcc(title, attr(e.info, "tvg-logo")?.ifBlank { null }, group, q, kd.lang)
                    }
                    if (acc.done) return@entries
                    val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                    val num = m?.groupValues?.get(3)?.toIntOrNull() ?: (acc.n + 1)
                    val vl = pool.ofOrNull(LANG.find(e.name)?.groupValues?.get(1)?.uppercase())
                    val label = pool.of(listOfNotNull(vl, q, group.take(18)).joinToString(" · "))
                    acc.add(season, num, pool.of(EpisodeTable.prefixOf(e.url)), e.url, label, q, vl)
                }
                Kind.MOVIE -> {
                    val year = YEAR.find(e.name)?.groupValues?.get(1)?.toIntOrNull()
                    out.add(Item(e.url.hashCode().toString(), Kind.MOVIE, TitleText.title(clean(e.name)), year, e.url,
                        attr(e.info, "tvg-logo")?.ifBlank { null }, group, quality = q, lang = kd.lang))
                }
                Kind.LIVE -> {
                    val id = e.url.hashCode().toString()
                    out.add(Item(id, Kind.LIVE, TitleText.decode(e.name), null, e.url,
                        attr(e.info, "tvg-logo")?.ifBlank { null }, group, quality = q,
                        tvgId = attr(e.info, "tvg-id")?.ifBlank { null }, lang = kd.lang))
                    CatchupLogic.fromInfo(e.info, e.url, headCu)?.let { catchup[id] = it }
                }
            }
        }

        for ((key, acc) in series) {
            if (acc.done) continue
            val raw = acc.raw()
            acc.free()   // release immediately: episodes are rebuilt series by series
            raw.sortWith(compareBy({ it.season }, { it.num }))
            val eps = ArrayList<Episode>()
            var i = 0
            while (i < raw.size) {
                var j = i + 1
                while (j < raw.size && raw[j].season == raw[i].season && raw[j].num == raw[i].num) j++
                if (j - i == 1) {
                    val r = raw[i]
                    // Only one source: no list of its own (saves a lot of memory with 100,000+ episodes; pickVariant then takes url/quality of the episode)
                    eps.add(Episode(r.season, r.num, epTitle(r.num), r.url, emptyList(), r.q))
                } else {
                    val vars = raw.subList(i, j).sortedByDescending { rank(it.q) }.map { Variant(it.url, it.label, it.q, it.lang) }
                    eps.add(Episode(raw[i].season, raw[i].num, epTitle(raw[i].num), vars[0].url, vars, vars[0].quality))
                }
                i = j
            }
            out.add(Item("s_$key", Kind.SERIES, acc.title, null, "", acc.logo, acc.group, EpisodeTable.of(eps, pool), acc.q, lang = acc.lang))
        }
        out.trimToSize()
        return ParsedPlaylist(out, epgFromHeader(header), st.counts(), st.bytes, catchup)
    }
}
