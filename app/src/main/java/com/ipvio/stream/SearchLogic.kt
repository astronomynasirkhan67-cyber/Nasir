package com.ipvio.stream

/** Search index: title lowercased once and without accents/special characters so "Amelie" also finds "Amélie". */
class SearchIndex(val all: List<Item>) {
    val keys: Array<String> = Array(all.size) { norm(all[it].title) }
    // No id map here any more: with very large playlists it cost memory for every title, and the search only needed it for
    // the few history entries (see SearchScreen, resolved in one pass there)

    fun find(q: String): Triple<List<Item>, List<Item>, List<Item>> {
        val n = norm(q)
        val m = ArrayList<Item>(); val s = ArrayList<Item>(); val l = ArrayList<Item>()
        if (n.isEmpty()) return Triple(m, s, l)
        for (i in all.indices) if (keys[i].contains(n)) {
            val it = all[i]
            when (it.kind) { Kind.MOVIE -> m.add(it); Kind.SERIES -> s.add(it); Kind.LIVE -> l.add(it) }
        }
        // Few hits: forgive typos (movies and series, not channels)
        if (m.size + s.size < FUZZY_BELOW && n.length >= 4) {
            val k = maxErrors(n.length)
            val hits = ArrayList<Pair<Int, Int>>()
            for (i in all.indices) {
                val kind = all[i].kind
                if (kind == Kind.LIVE) continue
                val key = keys[i]
                if (key.length < n.length - k || key.contains(n)) continue
                val d = approxDist(n, key)
                if (d <= k) hits.add(d to i)
            }
            hits.sortedWith(compareBy({ it.first }, { keys[it.second].length })).take(FUZZY_MAX).forEach { (_, i) ->
                val it = all[i]
                if (it.kind == Kind.MOVIE) m.add(it) else s.add(it)
            }
        }
        return Triple(m, s, l)
    }

    companion object {
        const val FUZZY_BELOW = 3
        const val FUZZY_MAX = 40

        /** Allowed typos per search length: 4–5 characters = 1, from 6 = 2. */
        fun maxErrors(len: Int): Int = if (len >= 6) 2 else 1

        /** Smallest distance (insert/delete/replace) between [p] and any place in [t] (Sellers algorithm). */
        fun approxDist(p: String, t: String): Int {
            val m = p.length
            if (m == 0) return 0
            val col = IntArray(m + 1) { it }
            var best = m
            for (c in t) {
                var diag = col[0]
                col[0] = 0
                for (i in 1..m) {
                    val up = col[i]
                    col[i] = minOf(col[i] + 1, col[i - 1] + 1, diag + (if (p[i - 1] == c) 0 else 1))
                    diag = up
                }
                if (col[m] < best) best = col[m]
            }
            return best
        }

        private val MARKS = Regex("""\p{Mn}+""")
        fun norm(t: String): String =
            java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "").replace("ß", "ss")
    }
}

/**
 * Word suggestions for the search: all words (3+ letters) of the titles, sorted, with how often they occur.
 * [suggest] returns the most common words that start with what is typed so far.
 */
class WordSuggest(items: List<Item>) {
    private val words: Array<String>
    private val counts: IntArray

    init {
        val m = HashMap<String, Int>()
        val sep = Regex("[^\\p{L}\\p{N}]+")
        for (it in items) for (w in it.title.lowercase(java.util.Locale.ROOT).split(sep)) {
            if (w.length >= 3 && !w.all { c -> c.isDigit() }) m[w] = (m[w] ?: 0) + 1
        }
        val keys = m.keys.sorted()
        words = keys.toTypedArray()
        counts = IntArray(keys.size) { m[keys[it]] ?: 0 }
    }

    /** Up to [n] words starting with [prefix] (at least 2 characters), most common first; the exact word itself is left out. */
    fun suggest(prefix: String, n: Int = 6): List<String> {
        val p = prefix.lowercase(java.util.Locale.ROOT)
        if (p.length < 2) return emptyList()
        var lo = 0; var hi = words.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (words[mid] < p) lo = mid + 1 else hi = mid }
        val hits = ArrayList<Int>()
        var i = lo
        while (i < words.size && words[i].startsWith(p)) { if (words[i] != p) hits.add(i); i++ }
        return hits.sortedByDescending { counts[it] }.take(n).map { words[it] }
    }

    companion object {
        /** Replaces the last word of [query] with [word] and adds a space. */
        fun apply(query: String, word: String): String {
            val cut = query.trimEnd().lastIndexOf(' ')
            val head = if (cut >= 0) query.substring(0, cut + 1) else ""
            return (head + word + " ").take(60)
        }

        /** The word being typed (after the last space), empty if the query ends with a space. */
        fun lastWord(query: String): String = if (query.endsWith(" ")) "" else query.substringAfterLast(' ')
    }
}
