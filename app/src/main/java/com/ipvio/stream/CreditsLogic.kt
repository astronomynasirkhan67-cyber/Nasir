package com.ipvio.stream


/** Rules for cast lists (without Android, testable). */
object CastLogic {
    /** Merges entries with the same ID (order of first occurrence is kept). */
    fun <T> mergeById(list: List<T>, id: (T) -> Int, merge: (T, T) -> T): List<T> {
        val out = LinkedHashMap<Int, T>()
        for (x in list) { val k = id(x); val prev = out[k]; out[k] = if (prev == null) x else merge(prev, x) }
        return out.values.toList()
    }
}

/** Reference to a title at TMDB (recommendation or filmography). [alt] is the original title. */
data class Ref(val tmdbId: Int, val isTv: Boolean, val title: String, val alt: String?, val year: String?, val poster: String?)

/** Maps TMDB titles to entries of the own playlist (title normalized, year ±1, movie/series separate). */
object LibMatch {
    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    class Index(items: List<Item>) {
        private val map = HashMap<String, ArrayList<Item>>()

        init {
            for (i in items) {
                if (i.kind == Kind.LIVE) continue
                map.getOrPut(key(i.kind == Kind.SERIES, norm(i.title))) { ArrayList(1) }.add(i)
            }
        }

        private fun key(tv: Boolean, n: String) = (if (tv) "s|" else "m|") + n

        fun find(r: Ref): Item? {
            for (t in listOfNotNull(r.title, r.alt)) {
                val n = norm(t)
                if (n.isEmpty()) continue
                val c = map[key(r.isTv, n)] ?: continue
                val y = r.year?.toIntOrNull()
                val hit = if (y == null) c.first() else c.firstOrNull { it.year == null || Math.abs(it.year - y) <= 1 }
                if (hit != null) return hit
            }
            return null
        }
    }

    private var lastList: List<Item>? = null
    private var lastIndex: Index? = null

    /** Index of the whole playlist, built once per loaded list. */
    @Synchronized
    fun indexFor(items: List<Item>): Index {
        val li = lastIndex
        if (li != null && lastList === items) return li
        return Index(items).also { lastList = items; lastIndex = it }
    }

    /** Call when reloading the playlist: otherwise the old list and its index would stay in memory. */
    @Synchronized
    fun reset() { lastList = null; lastIndex = null }

    /** All hits in the playlist, without duplicates, in the order of the references. */
    fun matches(items: List<Item>, refs: List<Ref>, limit: Int = 60): List<Item> {
        val idx = indexFor(items)
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (r in refs) {
            val h = idx.find(r) ?: continue
            if (seen.add(h.id)) out.add(h)
            if (out.size >= limit) break
        }
        return out
    }
}
