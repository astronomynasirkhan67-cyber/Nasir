package com.ipvio.stream

/** Key of the favorites in the category selection (Live TV and player). */
const val FAV_CAT = "#FAV"

/** Own channel list: name and channels in own order (channel IDs as with the favorites). */
data class ChList(val id: Int, val name: String, val ids: List<String>)

/** Create, fill and sort own channel lists (without Android, testable). */
object ChannelListLogic {
    const val MAX_NAME = 40
    /** Key of a list in the category selection (next to favorites and "ALL"). */
    const val PREFIX = "L:"

    fun key(l: ChList) = PREFIX + l.id
    fun idOfKey(k: String): Int? = if (k.startsWith(PREFIX)) k.removePrefix(PREFIX).toIntOrNull() else null

    fun cleanName(n: String): String = n.replace(Regex("[\\t\\n\\r]"), " ").trim().take(MAX_NAME)

    /** New list; empty name → "Senderliste N". */
    fun create(lists: List<ChList>, name: String, first: String? = null): List<ChList> {
        val id = (lists.maxOfOrNull { it.id } ?: 0) + 1
        val n = cleanName(name).ifEmpty { "Senderliste $id" }
        return lists + ChList(id, n, listOfNotNull(first))
    }

    fun rename(lists: List<ChList>, id: Int, name: String): List<ChList> {
        val n = cleanName(name)
        if (n.isEmpty()) return lists
        return lists.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun delete(lists: List<ChList>, id: Int): List<ChList> = lists.filter { it.id != id }

    /** Append a channel at the end (no duplicate). */
    fun add(lists: List<ChList>, id: Int, ch: String): List<ChList> =
        lists.map { if (it.id == id && ch !in it.ids) it.copy(ids = it.ids + ch) else it }

    fun remove(lists: List<ChList>, id: Int, ch: String): List<ChList> =
        lists.map { if (it.id == id) it.copy(ids = it.ids - ch) else it }

    /** Move a channel by [delta] places (−1 = up). */
    fun move(lists: List<ChList>, id: Int, ch: String, delta: Int): List<ChList> = lists.map { l ->
        if (l.id != id) l else {
            val i = l.ids.indexOf(ch)
            val j = i + delta
            if (i < 0 || j !in l.ids.indices) l else l.copy(ids = l.ids.toMutableList().also { m -> m.removeAt(i); m.add(j, ch) })
        }
    }

    /** Channels of the list in its order; ones that no longer exist are skipped. */
    fun <T> resolve(l: ChList, byId: Map<String, T>): List<T> = l.ids.mapNotNull { byId[it] }

    /** Text form: one line per list "id TAB name TAB channel,channel,…". */
    fun encode(lists: List<ChList>): String = lists.joinToString("\n") { "${it.id}\t${cleanName(it.name)}\t${it.ids.joinToString(",")}" }

    fun decode(s: String?): List<ChList> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 3) return@mapNotNull null
            val id = p[0].toIntOrNull() ?: return@mapNotNull null
            ChList(id, p[1], p[2].split(',').filter { it.isNotBlank() })
        }
    }
}

/** Reminder for a show (times in seconds since 1970). */
data class Reminder(val chId: String, val chTitle: String, val title: String, val start: Long, val end: Long) {
    val key: String get() = chId + "@" + start
}

/** When to remind of which show (without Android, testable). */
object ReminderLogic {
    /** Lead times to choose from in minutes. */
    val LEADS = listOf(0, 2, 5, 10, 15)

    /** Due: lead time reached and the show has not ended yet. */
    fun due(list: List<Reminder>, nowSec: Long, leadSec: Long): List<Reminder> =
        list.filter { nowSec >= it.start - leadSec && nowSec < it.end }

    /** Old reminders (show is over) are dropped. */
    fun prune(list: List<Reminder>, nowSec: Long): List<Reminder> = list.filter { it.end > nowSec }

    /** Next alarm time (seconds) or null. */
    fun nextAlarm(list: List<Reminder>, nowSec: Long, leadSec: Long): Long? =
        list.filter { it.end > nowSec }.minOfOrNull { maxOf(it.start - leadSec, nowSec) }

    fun toggle(list: List<Reminder>, r: Reminder): List<Reminder> =
        if (list.any { it.key == r.key }) list.filter { it.key != r.key } else (list + r).sortedBy { it.start }

    /** Can one be reminded of this show? Only if it has not started yet. */
    fun canRemind(start: Long, nowSec: Long): Boolean = start > nowSec

    private fun esc(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    fun encode(list: List<Reminder>): String =
        list.joinToString("\n") { "${esc(it.chId)}\t${esc(it.chTitle)}\t${esc(it.title)}\t${it.start}\t${it.end}" }

    fun decode(s: String?): List<Reminder> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 5) return@mapNotNull null
            Reminder(p[0], p[1], p[2], p[3].toLongOrNull() ?: return@mapNotNull null, p[4].toLongOrNull() ?: return@mapNotNull null)
        }
    }
}
