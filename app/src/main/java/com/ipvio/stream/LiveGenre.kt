package com.ipvio.stream

/**
 * Channels by genre (1.2): "Sports", "Movies", "Documentaries", "Kids", "News", "Music".
 * Detected from the playlist's category and channel name – not perfect, but no effort for you.
 */
object LiveGenre {
    class Genre(val key: String, val label: String, val re: Regex?)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val ALL = listOf(
        Genre("GEN:sport", "Sport", null),   // sport is detected by SportLogic
        Genre("GEN:movies", "Filme", rx("""\b(films?|movies?|cinema\w*|kino|cine\w*|sinema\w*|filmovi|premiere|cinemax|hbo|serien|series|serije|dizi)\b""")),
        Genre("GEN:doku", "Dokumentationen", rx("""documentar\w*|\bdoku\w*|discovery|nat\s?geo|national\s?geographic|\bhistory\b|animal\s?planet|\bdmax\b|nautilus|explore|\bscience\b|dokumentar\w*""")),
        Genre("GEN:kids", "Kinder", rx("""\bkids?\b|kinder|children|cartoon\w*|\bnick\w*|disney|junior|\bbaby\w*|toon\w*|boomerang|ragazzi|çocuk|djeca|dječji|\bkika\b|jetix|\bpogo\b""")),
        Genre("GEN:news", "Nachrichten", rx("""\bnews\b|nachrichten|\bn-?tv\b|\bcnn\b|euronews|al\s?jazeera|tagesschau|\bwelt\b|haber|vijesti|noticias|\bnotizie\b|\binfo(rmation)?\b|\brtl\s?aktuell\b""")),
        Genre("GEN:music", "Musik", rx("""\bmusic\w*|musik|\bmtv\b|\bvh1\b|\bviva\b|\bklubbing\b|\bradio\b|\bmuzik\w*"""))
    )

    fun byKey(k: String): Genre? = ALL.firstOrNull { it.key == k }

    fun isGenreKey(k: String) = k.startsWith("GEN:")

    fun matches(g: Genre, i: Item): Boolean =
        if (g.re == null) SportLogic.isSportChannel(i) else g.re.containsMatchIn(i.group) || g.re.containsMatchIn(i.title)

    /** Channels per genre; empty genres are missing. */
    fun split(live: List<Item>): LinkedHashMap<String, List<Item>> {
        // The same list is asked again and again (every recomposition, every tab): remember the last result
        synchronized(this) { last.firstOrNull { it.first === live }?.let { return it.second } }
        val r = compute(live)
        // Three results: a filtered source in Live TV no longer evicts the prepared result of all channels
        synchronized(this) { last.add(0, live to r); while (last.size > 3) last.removeAt(last.size - 1) }
        return r
    }

    private val last = ArrayList<Pair<List<Item>, LinkedHashMap<String, List<Item>>>>()

    private fun compute(live: List<Item>): LinkedHashMap<String, List<Item>> {
        val out = LinkedHashMap<String, List<Item>>()
        for (g in ALL) {
            val l = live.filter { matches(g, it) }
            if (l.isNotEmpty()) out[g.key] = l
        }
        return out
    }
}
