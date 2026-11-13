package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Series with a placeholder (round 21): when loading an Xtream playlist via the API, each series has only one
 * entry in the list. The app fetches its episodes here when the series is opened or played – one request instead of
 * tens of thousands while loading. The result is remembered for the session.
 */
object LazySeries {
    private class Hit(val item: Item, val at: Long)

    private val done = ConcurrentHashMap<String, Hit>()
    private const val MAX_AGE = 30 * 60_000L

    fun isStub(i: Item): Boolean = XtreamLogic.isLazy(i) || StalkerLogic.isLazy(i)

    /** The series with its real episodes if already loaded; otherwise the series itself. */
    fun full(i: Item): Item {
        if (!isStub(i)) return i
        val h = done[i.id] ?: return i
        return if (System.currentTimeMillis() - h.at < MAX_AGE) h.item else i
    }

    fun loaded(i: Item): Boolean = !isStub(i) || full(i) !== i

    /** Forget everything (new playlist loaded). */
    fun clear() = done.clear()

    /** Load episodes. null = the provider does not respond (network, login). */
    suspend fun load(@Suppress("UNUSED_PARAMETER") ctx: Context, i: Item): Item? {
        val have = full(i)
        if (have !== i) return have
        // Stalker portal: seasons and episodes via the portal's API
        val sUrls = StalkerLogic.lazyUrls(i)
        if (sUrls.isNotEmpty()) return withContext(Dispatchers.IO) {
            var eps: List<Episode>? = null
            for ((url, label) in sUrls) {
                val l = StalkerApi.episodeList(url) ?: continue
                eps = if (eps == null) l else MultiLogic.mergeEpisodes(eps, l, label.ifBlank { "2" })
            }
            val e = eps ?: return@withContext null
            if (e.isEmpty()) return@withContext null
            val r = i.copy(episodes = e)
            done[i.id] = Hit(r, System.currentTimeMillis())
            r
        }
        val urls = XtreamLogic.lazyUrls(i)
        if (urls.isEmpty()) return i
        return withContext(Dispatchers.IO) {
            val api = PlaylistStore.xtreamApi()
            var eps: List<Episode>? = null
            for ((url, label) in urls) {
                val (acct, sid) = XtreamLogic.parseLazy(url) ?: continue
                val l = api.episodeList(acct, sid) ?: continue
                eps = if (eps == null) l else MultiLogic.mergeEpisodes(eps, l, label.ifBlank { "2" })
            }
            val e = eps ?: return@withContext null
            if (e.isEmpty()) return@withContext null
            val r = i.copy(episodes = e)
            done[i.id] = Hit(r, System.currentTimeMillis())
            r
        }
    }
}
