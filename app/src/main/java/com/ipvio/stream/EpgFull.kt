package com.ipvio.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EpgFull {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(300)
    fun trim() = cache.clear()

    /**
     * Program of a channel: from 1 h back, for channels with archive (catchup) as far back as the archive reaches.
     * First cache and loaded XMLTV (without network), then Xtream.
     */
    suspend fun get(item: Item): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        cache[item.id]?.let { if (nowMs - it.first < 600_000) return it.second }
        // The full program table can hold days of shows: parsing and Base64 decoding run off the main thread, whoever calls this
        return withContext(Dispatchers.Default) { getFresh(item, nowMs, nowSec) }
    }

    /** Cached program if still fresh, without lookup or network (cheap enough for composition). */
    fun peek(item: Item): List<EpgEntry>? {
        val c = cache[item.id] ?: return null
        return if (System.currentTimeMillis() - c.first < 600_000) c.second else null
    }

    private suspend fun getFresh(item: Item, nowMs: Long, nowSec: Long): List<EpgEntry> {
        val days = Catchup.of(item)?.days ?: 0
        val from = if (days > 0) nowSec - days * 86400L else nowSec - 3600
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > from }
        // With archive, XMLTV is only enough if it also contains past shows
        val localOk = local.size >= 3 && local.any { it.end > nowSec } && (days == 0 || local.first().start <= nowSec - 6 * 3600)
        if (localOk) return local
        val url = Epg.xtreamApi(item, "get_simple_data_table")
        if (url != null) {
            val body = EpgNet.fetch(url)
            if (body != null) {
                val out = Epg.parseListings(body) { _, e -> e > from }
                if (out.isNotEmpty()) { cache[item.id] = nowMs to out; return out }
            }
        }
        if (local.isNotEmpty()) return local
        return Epg.now(item)
    }

    /** Past shows in a channel's archive, newest first. */
    suspend fun archive(item: Item): List<EpgEntry> {
        val info = Catchup.of(item) ?: return emptyList()
        return CatchupLogic.archived(info, get(item), System.currentTimeMillis() / 1000)
    }
}
