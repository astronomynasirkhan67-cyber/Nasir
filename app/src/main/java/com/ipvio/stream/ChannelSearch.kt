package com.ipvio.stream

/** Name search over live channels for the Live TV list (pure logic, no Android). */
object ChannelSearch {
    const val MAX_RESULTS = 300
    private val MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NONWORD = Regex("[^a-z0-9\\p{L}]+")

    /** Lower-case, strip accents and punctuation so "Das Erste HD" matches "das erste". */
    fun norm(t: String): String {
        normCache[t]?.let { return it }
        val r = normNow(t)
        if (normCache.size > 200_000) normCache.clear()
        normCache[t] = r
        return r
    }

    // Normalized channel names and categories: computed once instead of for every channel on every typed letter
    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun normNow(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(MARKS, "").replace("ß", "ss")
            .replace(NONWORD, " ").trim()

    /**
     * Channels whose name (or category) contains every word of [query]. Name matches come first
     * (starts with the query, then word start, then anywhere), category-only matches last.
     */
    fun filter(channels: List<Item>, query: String): List<Item> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Int, Item>>()
        for (c in channels) {
            val name = norm(c.title)
            val rank = when {
                words.all { it in name } -> if (name.startsWith(q)) 0 else if (name.contains(" $q")) 1 else 2
                words.all { it in name || it in norm(c.group) } -> 3
                else -> continue
            }
            scored.add(rank to c)
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.title.length })).take(MAX_RESULTS).map { it.second }
    }
}
