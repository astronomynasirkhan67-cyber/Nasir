package com.ipvio.stream

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Backup servers for Xtream logins. No Android dependencies, unit-tested.
 *
 * A group = main server + any number of backup servers of the same provider (same username and password, only a
 * different address). Every URL the app builds for a playlist (playlist, EPG, streams, catch-up) starts with the main
 * server's address; when it fails, only that prefix is swapped for a backup server's.
 */
object HostFailoverLogic {
    private val BASE = Regex("""^https?://[^\s/?#]+(?:/[^\s?#]*)?$""", RegexOption.IGNORE_CASE)

    /** Input as in the server field ("server.tld:8080" becomes "http://server.tld:8080"); null = invalid or empty. */
    fun normalize(s: String): String? {
        var h = s.trim().trimEnd('/')
        if (h.isEmpty()) return null
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        return if (BASE.matches(h)) h else null
    }

    /** Index of the address in [bases] that [url] starts with (the longest if several match), otherwise -1. */
    fun baseIndex(url: String, bases: List<String>): Int {
        var best = -1
        for ((i, b) in bases.withIndex()) {
            if (!url.startsWith(b, ignoreCase = true)) continue
            if (url.length != b.length && url[b.length] !in "/?#") continue
            if (best < 0 || b.length > bases[best].length) best = i
        }
        return best
    }

    /**
     * URLs to try, in order: first on the last working server ([active]), then on the others.
     * If [url] belongs to no group, only [url] itself.
     */
    fun candidates(url: String, bases: List<String>, active: Int): List<String> {
        val i = baseIndex(url, bases)
        if (i < 0 || bases.size < 2) return listOf(url)
        val rest = url.substring(bases[i].length)
        val first = active.coerceIn(0, bases.lastIndex)
        return (listOf(first) + bases.indices.filter { it != first }).map { bases[it] + rest }
    }

    /** Main server + valid, distinct backup servers in the order entered; empty entries are dropped. */
    fun group(primary: String, backups: List<String>): List<String> {
        val out = arrayListOf(primary.trimEnd('/'))
        for (b in backups) {
            val n = normalize(b) ?: continue
            if (out.none { it.equals(n, ignoreCase = true) }) out.add(n)
        }
        return out
    }

    /**
     * The group [url] belongs to, or null when failover must not apply.
     *
     * [groups] has one entry per Xtream playlist, `[main, backup1, …]`, including playlists without backups (size 1).
     * The request carries the credentials of one playlist, so it must only ever be retried on servers entered for
     * that playlist:
     * - A URL on the main server of a playlist belongs to that playlist. If that playlist has no backups, there is no
     *   failover, even when the same address is entered as a backup for another playlist.
     * - A URL that only matches a backup server (e.g. after a failover, or a stream URL from a playlist loaded through
     *   a backup) belongs to that group, but only if every matching group has the same main server. Otherwise it is
     *   ambiguous and gets no failover.
     */
    fun pickGroup(url: String, groups: List<List<String>>): List<String>? {
        val asMain = groups.filter { it.isNotEmpty() && baseIndex(url, it.subList(0, 1)) == 0 }
        if (asMain.isNotEmpty()) return asMain.firstOrNull { it.size > 1 }
        val asBackup = groups.filter { it.size > 1 && baseIndex(url, it) > 0 }
        if (asBackup.isEmpty() || asBackup.map { it[0].lowercase() }.distinct().size > 1) return null
        return asBackup[0]
    }
}

/**
 * Runtime part: remembers per group the server that answered last (only until the app restarts; after that the
 * main server is tried first again). Hooks in at two places: [Interceptor] in the shared HTTP client ([Net]) for
 * playlist, API, EPG and catch-up, and [DataSourceWrapper] in the player for streams.
 *
 * Also keeps each server's state (reachable / not reachable since …) so the UI can show dead addresses.
 * [PlaylistStore] saves it through [onHealthChange], so it survives a restart.
 */
object HostFailover {
    @Volatile private var groups: List<List<String>> = emptyList()
    private val active = ConcurrentHashMap<String, Int>()

    /**
     * Replace the groups (from [PlaylistStore]): one entry per Xtream playlist, including playlists without backups,
     * so their requests are never sent to servers entered for another playlist (see [HostFailoverLogic.pickGroup]).
     */
    fun setGroups(g: List<List<String>>) { groups = g.filter { it.isNotEmpty() } }

    private fun groupOf(url: String): List<String>? = HostFailoverLogic.pickGroup(url, groups)

    fun candidates(url: String): List<String> {
        val g = groupOf(url) ?: return listOf(url)
        return HostFailoverLogic.candidates(url, g, active[g[0].lowercase()] ?: 0)
    }

    fun markWorking(url: String) {
        val g = groupOf(url) ?: return
        val i = HostFailoverLogic.baseIndex(url, g)
        if (i >= 0) active[g[0].lowercase()] = i
        setHealth(url, true)
    }

    fun markFailed(url: String) = setHealth(url, false)

    /** State of a server: [ok] = answered last time; [at] = since when this state holds. */
    class Health(val ok: Boolean, val at: Long)
    private val health = ConcurrentHashMap<String, Health>()

    /** Incremented on every change so the UI recomposes. */
    var healthVersion by mutableIntStateOf(0)
        private set

    /** Called on every change reachable ↔ not reachable (not on every request), for saving. */
    @Volatile var onHealthChange: ((Map<String, Health>) -> Unit)? = null

    fun health(base: String): Health? = health[base.trimEnd('/').lowercase()]

    /** Load the saved state at start. */
    fun loadHealth(m: Map<String, Health>) { health.putAll(m); healthVersion++ }

    private fun setHealth(url: String, ok: Boolean) {
        val g = groupOf(url) ?: return
        val i = HostFailoverLogic.baseIndex(url, g)
        if (i < 0) return
        val k = g[i].lowercase()
        // Only a change counts: during an outage the first failure time stays ("not reachable since …")
        if (health[k]?.ok == ok) return
        health[k] = Health(ok, System.currentTimeMillis())
        healthVersion++
        onHealthChange?.invoke(HashMap(health))
    }

    /** For all OkHttp requests: on a connection error or 5xx, try the backup servers in order. */
    object Interceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val req = chain.request()
            // Drop addresses OkHttp cannot parse up front, so a 5xx on the last usable server is returned as is
            // instead of being closed and replaced by an exception.
            val urls = candidates(req.url.toString()).mapNotNull { u -> u.toHttpUrlOrNull()?.let { u to it } }
            if (urls.size < 2) return chain.proceed(req)
            var last: IOException? = null
            for ((i, pair) in urls.withIndex()) {
                val (u, target) = pair
                try {
                    val resp = chain.proceed(req.newBuilder().url(target).build())
                    if (resp.code >= 500) {
                        markFailed(u)
                        if (i < urls.lastIndex) { resp.close(); continue }
                    } else markWorking(u)
                    return resp
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e
                    markFailed(u)
                    last = e
                }
            }
            throw last ?: IOException("No server reachable")
        }
    }

    /** Server does not answer (connection, timeout) or reports a server error (5xx)? 4xx = server is alive. */
    private fun serverDown(e: IOException): Boolean = when (e) {
        is HttpDataSource.InvalidResponseCodeException -> e.responseCode >= 500
        is HttpDataSource.HttpDataSourceException -> true
        else -> false
    }

    /**
     * The load was cancelled (ExoPlayer interrupts the loader thread on zapping, seeking or release), as opposed to
     * a server timeout. Then no other server is tried and no server is marked as down.
     */
    private fun cancelled(e: IOException): Boolean =
        Thread.currentThread().isInterrupted ||
            generateSequence<Throwable>(e) { it.cause }.take(8).any { it is InterruptedIOException && it !is SocketTimeoutException }

    /** For the player: opens streams on the last working server and moves on when it fails. */
    class DataSourceWrapper(private val inner: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val urls = candidates(dataSpec.uri.toString())
            if (urls.size < 2) return inner.open(dataSpec)
            for ((i, u) in urls.withIndex()) {
                try {
                    val n = inner.open(dataSpec.withUri(Uri.parse(u)))
                    markWorking(u)
                    return n
                } catch (e: IOException) {
                    if (!serverDown(e) || cancelled(e)) throw e
                    markFailed(u)
                    if (i == urls.lastIndex) throw e
                    runCatching { inner.close() }
                }
            }
            return inner.open(dataSpec)   // unreachable: the loop always ends with return or throw
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)
        override fun getUri(): Uri? = inner.uri
        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders
        override fun close() = inner.close()

        class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = DataSourceWrapper(inner.createDataSource())
        }
    }
}
