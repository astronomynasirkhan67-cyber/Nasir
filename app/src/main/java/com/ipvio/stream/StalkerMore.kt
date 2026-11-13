package com.ipvio.stream

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Stalker portal, titles on demand (like IPTVnator): while loading, [StalkerApi.write] fetches only the first page of each
 * movie/series category. When the user opens a category the next portion comes ([StalkerApi.loadChunk]); further
 * portions only when they scroll near the end of the list ([wantMore]). Only one request runs at a time.
 *
 * The state is kept in memory for the running session only: titles loaded on demand are not in the playlist cache,
 * so after a restart loading starts again after the first page.
 */
object StalkerMore {
    /** This close to the end of the list (in titles) the next portion is fetched. */
    const val AHEAD = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    /** The view currently open; only it is extended. */
    @Volatile private var view: BrowseState? = null
    /** Counts every open/leave; work of an old round no longer changes anything. */
    @Volatile private var gen = 0
    /** State per playlist: categories with further titles (key = playlist address, then type + ID). */
    private val refs = HashMap<String, MutableMap<String, StalkerLogic.CatRef>>()
    /** Time of the last failure per category: do not ask again too soon. */
    private val failedAt = HashMap<String, Long>()
    private const val RETRY_AFTER_MS = 120_000L

    /** Titles loaded on demand per category (oldest first), for the preview in the category list. */
    val added = mutableStateMapOf<String, List<Item>>()

    /** New list loaded: forget the stored state. */
    fun reset() { synchronized(lock) { refs.clear(); failedAt.clear() }; Snapshot.withMutableSnapshot { added.clear() } }

    /** Titles of a category including those loaded on demand (oldest first). */
    fun withAdded(group: String, loaded: List<Item>): List<Item> {
        val more = added[group] ?: return loaded
        val have = loaded.mapTo(HashSet()) { it.id }
        return more.filter { it.id !in have } + loaded
    }

    /** Categories named [group] that fit a view showing [kinds] (movie and series areas can share a name). */
    private fun refsFor(c: Context, u: String, group: String, kinds: Set<Kind>): List<StalkerLogic.CatRef> =
        synchronized(lock) { StalkerLogic.catsFor(refsOf(c, u).values, group, Kind.MOVIE in kinds, Kind.SERIES in kinds) }

    /** Number of titles according to the portal if the category is not fully loaded yet; otherwise null. */
    fun total(c: Context, group: String, kinds: Set<Kind>): Int? {
        val u = PlaylistStore.shownUrl ?: return null
        val t = refsFor(c, u, group, kinds).map { it.total }
        return if (t.isEmpty() || t.any { it <= 0 }) null else t.sum()
    }

    /**
     * Category selected in the list (preview): fetch the next portion once so the preview shows more than the first
     * page. Cancels a running request for another category.
     */
    fun prefetch(c: Context, group: String, kinds: Set<Kind>) {
        if (added.containsKey(group)) return
        val ctx = c.applicationContext
        app = ctx
        synchronized(lock) {
            job?.cancel(); view = null
            val myGen = ++gen
            job = scope.launch {
                try { next(ctx, group, kinds, null, myGen) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    Log.w("StalkerMore", "prefetch failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun refKey(r: StalkerLogic.CatRef) = r.type + "\t" + r.id

    private fun refsOf(c: Context, u: String): MutableMap<String, StalkerLogic.CatRef> = synchronized(lock) {
        refs.getOrPut(u) {
            val l = StalkerLogic.decodeCats(runCatching { PlaylistStore.stalkerMoreFile(c, u).takeIf { it.exists() }?.readText() }.getOrNull())
            LinkedHashMap<String, StalkerLogic.CatRef>().also { m -> l.forEach { m[refKey(it)] = it } }
        }
    }

    /** Category of the view, only if all titles come from the same category (otherwise: search, genre, "All" …). */
    private fun groupOf(req: BrowseReq): String? {
        val g = req.items.firstOrNull()?.group ?: return null
        return if (req.items.all { it.group == g }) g else null
    }

    @Volatile private var app: Context? = null

    /** View [state] opened: fetch the next portion of its category right away. */
    fun attach(c: Context, state: BrowseState) {
        app = c.applicationContext
        synchronized(lock) { job?.cancel(); job = null; view = state; gen++ }
        wantMore(state)
    }

    /** View left: cancel the running request. */
    fun detach() { synchronized(lock) { job?.cancel(); job = null; view = null; gen++ } }

    /** User is near the end of the list: fetch the next portion (nothing if one is already on its way or everything is there). */
    fun wantMore(state: BrowseState) {
        val c = app ?: return
        synchronized(lock) {
            if (view !== state || job?.isActive == true) return
            val myGen = gen
            val group = groupOf(state.req) ?: return
            val kinds = state.req.items.mapTo(HashSet()) { it.kind }
            job = scope.launch {
                try {
                    next(c, group, kinds, state, myGen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w("StalkerMore", "load failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun next(c: Context, group: String, kinds: Set<Kind>, state: BrowseState?, myGen: Int) {
        val u = PlaylistStore.shownUrl ?: return
        val src = StalkerLogic.parse(u) ?: return
        val now = System.currentTimeMillis()
        val ref = synchronized(lock) {
            refsFor(c, u, group, kinds).firstOrNull { now - (failedAt[refKey(it)] ?: 0L) > RETRY_AFTER_MS }
        } ?: return
        val ctx = coroutineContext
        fun live() = ctx.isActive && myGen == gen
        val ch = StalkerApi.loadChunk(src, ref) { if (!live()) throw CancellationException("left") }
        if (!live()) return
        val parsed = if (ch.m3u.isBlank()) emptyList() else M3uParser.parse("#EXTM3U\n" + ch.m3u)
        val fresh = PlaylistStore.appendItems(c, u, parsed)
        val mine = fresh.filter { it.group == group && it.kind in kinds }
        synchronized(lock) {
            val m = refsOf(c, u)
            when {
                ch.done -> m.remove(refKey(ch.ref))
                else -> m[refKey(ch.ref)] = ch.ref
            }
            if (!ch.ok) failedAt[refKey(ch.ref)] = System.currentTimeMillis()
        }
        val sameGroup = fresh.filter { it.group == group }
        Snapshot.withMutableSnapshot {
            added[group] = sameGroup + (added[group] ?: emptyList())
            if (state != null && mine.isNotEmpty()) state.req = state.req.copy(items = mine + state.req.items)
        }
        // Portion brought nothing visible (e.g. only series in the movie view): try the next one right away
        if (state != null && mine.isEmpty() && ch.ok && !ch.done) synchronized(lock) { job = null }.also { wantMore(state) }
    }
}
