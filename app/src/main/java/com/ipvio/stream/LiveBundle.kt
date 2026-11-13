package com.ipvio.stream

/**
 * Bundles the quality variants of one live channel ("Das Erste 8K", "Das Erste 4K", "Das Erste FHD", "Das Erste HD", "Das Erste SD")
 * into a single list row. Pure logic, no Android.
 *
 * Two channels are the same channel only if their names are equal after removing the country prefix and quality words AND they have
 * the same language tag, so "RTL" is never merged with "Super RTL" and "DE: Sport1 HD" is never merged with "TR: Sport1".
 */
object LiveBundleLogic {
    /** Quality words and resolution tags that are not part of the channel name. */
    private val QUALITY = Regex("""(?i)(?<![\p{L}0-9])(8k|4k|uhd|fhd|full\s?hd|hevc|h\.?26[45]|raw|sd|hd|\d{3,4}\s?p|\d{2}\s?fps)(?![\p{L}0-9])""")
    /** Country / language prefix such as "DE:", "|DE|", "[DE]", "DE |" or "DE -" at the start. */
    private val PREFIX = Regex("""^\s*[\[(|]?\s*[A-Za-z]{2,4}\s*[\])|]\s*|^\s*[A-Za-z]{2,4}\s*[:]\s+""")
    private val SEPARATORS = Regex("""[^\p{L}0-9]+""")

    // Normalized names per title: computed once, so bundling another category (or the same one again) costs only map lookups
    private val baseCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Normalized channel name used as bundle key (without language). */
    fun baseName(title: String): String {
        baseCache[title]?.let { return it }
        val r = baseNameNow(title)
        if (baseCache.size > 200_000) baseCache.clear()
        baseCache[title] = r
        return r
    }

    private fun baseNameNow(title: String): String {
        // "DE - Das Erste" only for known language codes (a plain 3-letter prefix like "ZDF - neo" is part of the name)
        var t = LangTagLogic.stripDashTag(title)
        PREFIX.find(t)?.let { m -> if (m.range.last + 1 < t.length) t = t.substring(m.range.last + 1) }
        t = QUALITY.replace(t, " ")
        return t.lowercase().replace(SEPARATORS, " ").trim()
    }

    private val R8K = Regex("""(?<![\p{L}0-9])(8k|4320p?)(?![\p{L}0-9])""")
    private val R4K = Regex("""(?<![\p{L}0-9])(4k|uhd|2160p?)(?![\p{L}0-9])""")
    private val RFHD = Regex("""(?<![\p{L}0-9])(fhd|full\s?hd|1080p?)(?![\p{L}0-9])""")
    private val RHD = Regex("""(?<![\p{L}0-9])(hd|720p?)(?![\p{L}0-9])""")
    private val RSD = Regex("""(?<![\p{L}0-9])(sd|480p?|576p?)(?![\p{L}0-9])""")

    /** Higher = better picture. Unmarked channels count as between SD and HD. */
    fun rank(title: String, quality: String?): Int {
        val t = (title + " " + quality.orEmpty()).lowercase()
        return when {
            R8K.containsMatchIn(t) -> 8
            R4K.containsMatchIn(t) -> 7
            RFHD.containsMatchIn(t) -> 5
            RHD.containsMatchIn(t) -> 4
            RSD.containsMatchIn(t) -> 2
            else -> 3
        }
    }

    class Result(
        /** One item per channel: the best variant, in order of first appearance. */
        val list: List<Item>,
        /** Best variant id → all variants (best first), only for channels with more than one variant. */
        val variants: Map<String, List<Item>>
    )

    // Bundling a big list (all channels) takes noticeable time: results of the last lists are kept (same list object = same result)
    private val cache = ArrayList<Pair<List<Item>, Result>>()

    @Synchronized
    fun bundle(items: List<Item>): Result {
        cache.firstOrNull { it.first === items }?.let { return it.second }
        val r = bundleNow(items)
        if (items.size > 300) { cache.add(0, items to r); while (cache.size > 6) cache.removeAt(cache.size - 1) }
        return r
    }

    private fun bundleNow(items: List<Item>): Result {
        val groups = LinkedHashMap<String, ArrayList<Item>>()
        for (i in items) {
            val base = baseName(i.title)
            // Names that normalize to nothing stay alone
            val key = if (base.isEmpty()) "id:" + i.id else base + "|" + i.lang
            groups.getOrPut(key) { ArrayList() }.add(i)
        }
        val out = ArrayList<Item>(groups.size)
        val variants = HashMap<String, List<Item>>()
        for (g in groups.values) {
            if (g.size == 1) { out.add(g[0]); continue }
            val sorted = g.sortedByDescending { rank(it.title, it.quality) }
            out.add(sorted[0])
            variants[sorted[0].id] = sorted
        }
        return Result(out, variants)
    }
}
