package com.ipvio.stream

/**
 * New episodes for favorite series (round 15): compares the number of episodes with the last known state.
 * Without Android, testable. The first time (series is new in favorites) only the state is remembered, no notice.
 */
object NewEpisodeLogic {
    /** A favorite series: ID, title, episodes as (season, number). */
    class Show(val id: String, val title: String, val episodes: List<Pair<Int, Int>>)

    /** Notice: new episode(s) of a series; [season]/[num] = the latest episode, [added] = how many are new. */
    data class Notice(val id: String, val title: String, val season: Int, val num: Int, val added: Int)

    class Result(val known: Map<String, Int>, val notices: List<Notice>)

    fun detect(known: Map<String, Int>, shows: List<Show>): Result {
        val out = HashMap<String, Int>()
        val notices = ArrayList<Notice>()
        for (s in shows) {
            val n = s.episodes.size
            val old = known[s.id]
            if (n == 0) { if (old != null) out[s.id] = old; continue }   // not loaded yet: keep the stored count
            out[s.id] = n
            if (old != null && n > old) {
                val last = s.episodes.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
                notices.add(Notice(s.id, s.title, last.first, last.second, n - old))
            }
        }
        // Forget series that are no longer in favorites
        return Result(out, notices)
    }

    /** Text for the bell, e.g. "New episode · Season 3, Episode 5" or "3 new episodes · up to Season 3, Episode 8". */
    fun text(n: Notice): String =
        if (n.added <= 1) "Neue Folge · Staffel ${n.season}, Folge ${n.num}" else "${n.added} neue Folgen · bis Staffel ${n.season}, Folge ${n.num}"
}
