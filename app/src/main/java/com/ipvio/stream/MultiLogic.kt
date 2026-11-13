package com.ipvio.stream

/**
 * Several playlists at once (round 20). Without Android, testable.
 *
 * - Channels: all stay (each source has its own addresses); in Live TV you can filter by source.
 * - Movies: the same title + year from a later source is hidden in "All" ([Merged.shadow]),
 *   but is still reachable via the source filter.
 * - Series: the same series from several sources becomes ONE series; the same episode from the second source becomes a
 *   further source of the episode (like several qualities) with the playlist name in the label. The series ID stays,
 *   so Continue watching, My list and history are preserved.
 * - Same ID twice (e.g. the same playlist twice): only the first entry counts.
 */
object MultiLogic {
    class Merged(
        val items: List<Item>,
        /** IDs not shown in "All" (duplicate movies of later sources). */
        val shadow: Set<String>,
        /** Series from several sources: ID → bit mask of the sources (bit k = source k). */
        val seriesSrc: Map<String, Int>
    ) {
        /** Everything "All" shows. */
        fun visible(): List<Item> = if (shadow.isEmpty()) items else items.filter { it.id !in shadow }

        /** Does the title belong to source [k]? */
        fun inSource(i: Item, k: Int): Boolean {
            if (i.src == k) return true
            val m = seriesSrc[i.id] ?: return false
            return k in 0..30 && (m shr k) and 1 == 1
        }
    }

    fun merge(lists: List<List<Item>>, names: List<String>, pool: StringPool? = null): Merged {
        if (lists.size <= 1) return Merged(lists.firstOrNull() ?: emptyList(), emptySet(), emptyMap())
        val out = ArrayList<Item>(lists.sumOf { it.size })
        val ids = HashSet<String>(out.size * 2)
        val seriesAt = HashMap<String, Int>()
        val movieKeys = HashSet<String>()
        val shadow = HashSet<String>()
        val seriesSrc = HashMap<String, Int>()
        for ((k, l) in lists.withIndex()) {
            val name = names.getOrElse(k) { "Playlist ${k + 1}" }
            val kMovies = HashSet<String>()
            for (it0 in l) {
                when (it0.kind) {
                    Kind.SERIES -> {
                        val at = seriesAt[it0.id]
                        if (at == null) {
                            if (!ids.add(it0.id)) continue
                            seriesAt[it0.id] = out.size
                            out.add(if (k == 0) it0 else it0.copy(src = k))
                        } else {
                            val a = out[at]
                            // Never mix placeholders (episodes only on opening) as an "episode" with real episodes: then the source with real episodes counts
                            val la = XtreamLogic.isLazy(a) || StalkerLogic.isLazy(a)
                            val lb = XtreamLogic.isLazy(it0) || StalkerLogic.isLazy(it0)
                            out[at] = a.copy(episodes = when {
                                la && !lb -> it0.episodes
                                !la && lb -> a.episodes
                                else -> mergeEpisodes(a.episodes, it0.episodes, name, pool)
                            })
                            seriesSrc[a.id] = (seriesSrc[a.id] ?: (1 shl a.src.coerceIn(0, 30))) or (1 shl k.coerceIn(0, 30))
                        }
                    }
                    else -> {
                        if (!ids.add(it0.id)) continue
                        val it = if (k == 0) it0 else it0.copy(src = k)
                        if (it.kind == Kind.MOVIE) {
                            // Duplicates within the same source stay visible as before; only duplicates of later sources
                            if (k > 0 && it.mk in movieKeys) shadow.add(it.id)
                            kMovies.add(it.mk)
                        }
                        out.add(it)
                    }
                }
            }
            movieKeys.addAll(kMovies)
        }
        out.trimToSize()
        return Merged(out, shadow, seriesSrc)
    }

    /** Merge the episodes of two sources: same season/episode → one episode with several sources. */
    fun mergeEpisodes(a: List<Episode>, b: List<Episode>, nameB: String, pool: StringPool? = null): List<Episode> {
        if (b.isEmpty()) return a
        val byKey = LinkedHashMap<Long, MutableList<Variant>>()
        val first = HashMap<Long, Episode>()
        fun keyOf(e: Episode) = (e.season.toLong() shl 32) or (e.num.toLong() and 0xFFFFFFFFL)
        for (e in a) {
            val key = keyOf(e)
            first.putIfAbsent(key, e)
            byKey.getOrPut(key) { ArrayList() }.addAll(e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) })
        }
        for (e in b) {
            val key = keyOf(e)
            first.putIfAbsent(key, e)
            val vs = e.variants.ifEmpty { listOf(Variant(e.url, "", e.quality, null)) }
            byKey.getOrPut(key) { ArrayList() }.addAll(vs.map { v ->
                val l = if (v.label.isBlank()) nameB else "$nameB · ${v.label}"
                Variant(v.url, pool?.of(l) ?: l, v.quality, v.lang)
            })
        }
        val out = ArrayList<Episode>(byKey.size)
        for ((key, vs) in byKey) {
            val e = first[key]!!
            if (vs.size == 1) out.add(Episode(e.season, e.num, e.title, vs[0].url, emptyList(), vs[0].quality))
            else out.add(Episode(e.season, e.num, e.title, vs[0].url, vs, vs[0].quality))
        }
        out.sortWith(compareBy({ it.season }, { it.num }))
        return EpisodeTable.of(out, pool)
    }

    /** Category order: first that of the main playlist, then new names of the further ones in their order. */
    fun mergeOrder(orders: List<Map<String, Int>>): Map<String, Int> {
        if (orders.size <= 1) return orders.firstOrNull() ?: emptyMap()
        val out = LinkedHashMap<String, Int>()
        var base = 0
        for (o in orders) {
            for ((k, v) in o.entries.sortedBy { it.value }) if (!out.containsKey(k)) out[k] = base + v
            base += (o.values.maxOrNull() ?: 0) + 1
        }
        return out
    }

    /** Saved selection "show at the same time" as keys (hash of the address – the address itself contains login data). */
    fun encodeKeys(keys: Collection<Int>): String = keys.sorted().joinToString(",")
    fun decodeKeys(s: String?): Set<Int> = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
}
