package com.ipvio.stream

/**
 * Matching a playlist title with TMDB hits (without Android, testable).
 *
 * Formerly the first hit was always taken. But TMDB also sorts by popularity: for "Game of Thrones" a popular
 * spin-off with a single season can be at the front – then the series shows wrong images and nothing at all from season 2.
 * Now a hit with exactly the same name wins; if there is none, the first TMDB hit remains.
 */
object MatchLogic {
    /** A search hit from TMDB (series or movie). */
    data class Candidate(val id: Int, val name: String?, val original: String?, val year: String?, val votes: Int)

    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Comparison form: lowercase, without accents and punctuation, "&" = "and". */
    fun norm(t: String?): String {
        if (t.isNullOrBlank()) return ""
        val s = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
            .replace("&", " and ").replace("ß", "ss")
        return s.replace(NON_ALNUM, " ").trim()
    }

    /** Same title (name or original name)? */
    fun sameTitle(title: String, name: String?, original: String? = null): Boolean {
        val n = norm(searchTitle(title))
        return n.isNotEmpty() && (n == norm(name) || n == norm(original))
    }

    // Language/country tag at the start: "DE - ", "DE: ", "|DE| ", "[DE] ", "(GER) ", "DE | "
    private val LEAD = Regex("""^\s*[\[(|]?\s*([A-Za-z]{2,4})\s*(?:[\])|]\s*[-–|:]?|\s*[-–|:])\s*""")

    /**
     * Title for the TMDB search: without the provider's language tag at the start (also "DE - ", which stays in the displayed title).
     * "DE - Game of Thrones" otherwise found other series at TMDB.
     */
    fun searchTitle(title: String): String {
        var t = title.trim()
        repeat(2) {
            val m = LEAD.find(t) ?: return@repeat
            if (LangTagLogic.detect(m.groupValues[1]) == null) return@repeat
            val rest = t.substring(m.range.last + 1).trim()
            if (rest.isNotEmpty()) t = rest
        }
        return t
    }

    /**
     * Best hit: exactly the same name (for movies with a matching year preferred), among several equal ones the one with the most
     * votes. Without a hit of the same name the first TMDB hit (as before).
     */
    fun pick(title: String, year: Int?, results: List<Candidate>): Candidate? {
        if (results.isEmpty()) return null
        val exact = results.filter { sameTitle(title, it.name, it.original) }
        if (exact.isEmpty()) return results.first()
        val y = year?.toString()
        val sameYear = if (y != null) exact.filter { it.year == y } else emptyList()
        return (sameYear.ifEmpty { exact }).maxByOrNull { it.votes }
    }

    /**
     * Does a stored hit still fit? No if the playlist has more seasons than the series at TMDB
     * (typical for a wrong hit), or if neither name nor original name matches the title and there is a better one.
     */
    fun seasonsFit(playlistMaxSeason: Int, tmdbSeasons: Int): Boolean =
        playlistMaxSeason <= 0 || tmdbSeasons <= 0 || playlistMaxSeason <= tmdbSeasons
}
