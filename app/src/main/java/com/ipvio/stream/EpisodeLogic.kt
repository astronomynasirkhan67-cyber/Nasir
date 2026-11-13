package com.ipvio.stream

/**
 * Split long seasons (e.g. One Piece: over 1,000 episodes, all in "Season 1" at the provider) into sections,
 * so nobody has to scroll to episode 1000. If TMDB knows the real seasons, they are used as sections
 * (with names like "Alabasta") and the episode info comes from the right TMDB season. Otherwise equally sized blocks of at most 30 episodes.
 * Without Android, testable.
 */
object EpisodeLogic {
    /** From this many episodes in a season it is split. */
    const val LONG = 60
    /** Maximum size of a section when TMDB does not help (evenly distributed: 62 episodes = 21 + 21 + 20). */
    const val BLOCK = 30
    /** Larger TMDB seasons are additionally split into blocks. */
    const val MAX_GROUP = 100

    /** A season according to TMDB: number, count of episodes, name (or null). Season 0 = specials, not used. */
    data class TmdbSeason(val num: Int, val count: Int, val name: String?)

    /**
     * Section of a playlist season: episodes [from]..[to] (index in the season's episode list, inclusive).
     * Info comes from TMDB season [tmdbSeason] (-1 = none, e.g. newer than TMDB), TMDB episode number = number − [offset].
     */
    data class Group(
        val from: Int, val to: Int, val firstNum: Int, val lastNum: Int,
        val tmdbSeason: Int, val offset: Int, val name: String?
    ) {
        val size: Int get() = to - from + 1
        operator fun contains(index: Int) = index in from..to
    }

    private val GENERIC = Regex("""^\s*(season|staffel|temporada|saison|sezon|sezona|stagione|serie)\s*\d+\s*$""", RegexOption.IGNORE_CASE)

    /** TMDB name only if it says more than "Season 3". */
    fun niceName(n: String?): String? = n?.trim()?.takeIf { it.isNotEmpty() && !GENERIC.matches(it) }

    private fun real(tmdb: List<TmdbSeason>) = tmdb.filter { it.num >= 1 && it.count > 0 }.sortedBy { it.num }

    /** First (continuous) episode number of each TMDB season: season 1 → 1, season 2 → 1 + episodes of season 1 … */
    fun starts(tmdb: List<TmdbSeason>): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        var n = 1
        for (s in real(tmdb)) { out[s.num] = n; n += s.count }
        return out
    }

    /**
     * Does the provider count continuously (everything in one season, numbers far beyond TMDB's season 1)?
     * Only then are the episodes distributed over the TMDB seasons.
     */
    fun absolute(eps: List<Episode>, providerSeasons: Int, tmdb: List<TmdbSeason>): Boolean {
        val r = real(tmdb)
        if (providerSeasons != 1 || r.size < 2 || eps.isEmpty()) return false
        val first = r.first().count
        return eps.maxOf { it.num } > first + 2
    }

    /**
     * Sections of a season. [eps] = episodes of this season, sorted by number.
     * Short seasons: one section (the list looks as before).
     */
    fun groups(eps: List<Episode>, season: Int, providerSeasons: Int, tmdb: List<TmdbSeason>): List<Group> {
        if (eps.isEmpty()) return emptyList()
        val st = starts(tmdb)
        if (absolute(eps, providerSeasons, tmdb)) {
            val r = real(tmdb)
            val out = ArrayList<Group>()
            // TMDB season per episode (null = newer than TMDB knows), equal ones in a row become one section
            fun seasonOf(n: Int): TmdbSeason? = r.firstOrNull { s -> val a = st.getValue(s.num); n >= a && n < a + s.count }
            var i = 0
            while (i < eps.size) {
                val s = seasonOf(eps[i].num)
                var j = i
                while (j + 1 < eps.size && seasonOf(eps[j + 1].num)?.num == s?.num) j++
                val off = if (s != null) st.getValue(s.num) - 1 else 0
                out.addAll(split(eps, i, j, s?.num ?: -1, off, niceName(s?.name)))
                i = j + 1
            }
            return out
        }
        // Provider with own seasons but continuous numbering (season 2 starts with episode 62)
        val offset = st[season]?.let { a ->
            val cnt = real(tmdb).first { it.num == season }.count
            if (a > 1 && eps.first().num == a && eps.last().num <= a + cnt + 1) a - 1 else 0
        } ?: 0
        if (eps.size <= LONG) return listOf(Group(0, eps.lastIndex, eps.first().num, eps.last().num, season, offset, null))
        return split(eps, 0, eps.lastIndex, season, offset, null, BLOCK)
    }

    private fun split(eps: List<Episode>, from: Int, to: Int, tmdbSeason: Int, offset: Int, name: String?, size: Int = MAX_GROUP): List<Group> {
        val n = to - from + 1
        if (n <= size) return listOf(Group(from, to, eps[from].num, eps[to].num, tmdbSeason, offset, name))
        val out = ArrayList<Group>()
        // Distribute evenly instead of 50 + remainder: shorter ways to the wanted episode, no tiny last section
        val count = (n + BLOCK - 1) / BLOCK
        val per = (n + count - 1) / count
        var a = from
        while (a <= to) {
            val b = minOf(a + per - 1, to)
            out.add(Group(a, b, eps[a].num, eps[b].num, tmdbSeason, offset, name))
            a = b + 1
        }
        return out
    }

    /** Index of the section that contains the episode at position [index] (otherwise 0). */
    fun groupOf(groups: List<Group>, index: Int): Int = groups.indexOfFirst { index in it }.coerceAtLeast(0)

    /** Position of the episode with number [num]; if it does not exist, the nearest. -1 for an empty list. */
    fun indexOfNum(eps: List<Episode>, num: Int): Int {
        if (eps.isEmpty()) return -1
        val exact = eps.indexOfFirst { it.num == num }
        if (exact >= 0) return exact
        return eps.indices.minByOrNull { kotlin.math.abs(eps[it].num - num) } ?: 0
    }

    /**
     * Target of "Go to episode [n]". If the number exists in the current season, it applies there. Otherwise in the first other
     * season with this number. If it exists nowhere (e.g. 100 for a series with 25 episodes per season), it counts as a
     * total number across all seasons (season 1 episode 1 = 1). Result: season and episode number, null if there are not that many episodes.
     */
    fun locate(all: List<Episode>, currentSeason: Int, n: Int): Pair<Int, Int>? {
        if (n < 1 || all.isEmpty()) return null
        all.firstOrNull { it.season == currentSeason && it.num == n }?.let { return it.season to it.num }
        all.filter { it.num == n }.minByOrNull { it.season }?.let { return it.season to it.num }
        val sorted = all.sortedWith(compareBy({ it.season }, { it.num }))
        return sorted.getOrNull(n - 1)?.let { it.season to it.num }
    }

    /** Label of a section: "1–61", for a TMDB season with a name "1–61 · East Blue". */
    fun label(g: Group): String = (if (g.firstNum == g.lastNum) "${g.firstNum}" else "${g.firstNum}–${g.lastNum}") +
        (g.name?.let { " · $it" } ?: "")

    /** Episode number under which TMDB lists the episode in [Group.tmdbSeason]. */
    fun tmdbNum(g: Group, num: Int): Int = num - g.offset
}

/**
 * Continue watching: when an episode/movie counts as watched, and the progress per episode in compact text form.
 */
object ResumeLogic {
    /** Watched: from 92 % or in the last 90 seconds (credits), provided at least half has played. */
    fun finished(posMs: Long, durMs: Long): Boolean =
        durMs > 0 && (posMs >= durMs * 0.92 || (durMs - posMs <= 90_000 && posMs >= durMs / 2))

    /** Only from 30 seconds on is a resume point worthwhile. */
    const val MIN_POS = 30_000L

    /**
     * Should the state be remembered? From 30 seconds or when watched to the end always. Shorter only if there is already an entry for
     * the same series and it concerns ANOTHER episode: whoever moves on from episode 40 to 41 and 42 should land at
     * 42 when coming back, not at 40. A short peek into the same episode changes nothing.
     */
    fun shouldSave(hasOld: Boolean, oldSeason: Int, oldEp: Int, season: Int, ep: Int, posMs: Long, finished: Boolean): Boolean = when {
        finished -> true
        posMs > MIN_POS -> true
        !hasOld -> false
        (season > 0 || ep > 0) && (oldSeason != season || oldEp != ep) -> true
        else -> false
    }

    /** Progress of an episode in seconds. */
    data class EpPos(val pos: Int, val dur: Int) {
        val done: Boolean get() = finished(pos * 1000L, dur * 1000L)
        val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    fun key(season: Int, ep: Int): Long = (season.toLong() shl 32) or (ep.toLong() and 0xFFFFFFFFL)

    /** "s:e:pos:dur;…" (seconds). */
    fun encode(m: Map<Long, EpPos>): String = m.entries.joinToString(";") { (k, v) ->
        "${(k shr 32).toInt()}:${k.toInt()}:${v.pos}:${v.dur}"
    }

    fun decode(s: String?): LinkedHashMap<Long, EpPos> {
        val out = LinkedHashMap<Long, EpPos>()
        if (s.isNullOrBlank()) return out
        for (part in s.split(';')) {
            val p = part.split(':')
            if (p.size != 4) continue
            val se = p[0].toIntOrNull() ?: continue
            val e = p[1].toIntOrNull() ?: continue
            out[key(se, e)] = EpPos(p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0)
        }
        return out
    }

    /** Season and episode from "Title S1E3" (old entries without their own fields). */
    private val SE = Regex("""[Ss](\d{1,3})\s*[Ee](\d{1,4})\s*$""")

    fun seasonEp(title: String): Pair<Int, Int>? =
        SE.find(title)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() }
}
