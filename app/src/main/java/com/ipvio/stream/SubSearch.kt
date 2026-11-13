package com.ipvio.stream

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Online subtitles in the player (see [SubSearchLogic]): search both services, download the chosen file, keep it per
 * title and attach it to the running stream. A subtitle chosen once is attached automatically the next time.
 */
object SubSearch {
    private val client = Net.base.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private const val MAX_FILES = 200

    /** A downloaded subtitle file attached to a title. */
    data class Sub(val file: File, val lang: String, val label: String) {
        val mime get() = SubSearchLogic.mime(file.name)
    }

    /** Search result: hits (best first) or an error text (German, gets translated). */
    class Result(val hits: List<SubSearchLogic.Hit>, val error: String?)

    fun hasKeys() = Config.OS_KEY.isNotEmpty() || Config.SD_KEY.isNotEmpty()

    private fun osReq(url: String) = Request.Builder().url(url)
        .header("Api-Key", Config.OS_KEY).header("User-Agent", SubSearchLogic.UA).header("Accept", "application/json")

    private fun get(r: Request): Pair<Int, String> = client.newCall(r).execute().use { it.code to (it.body?.string().orEmpty()) }

    suspend fun search(q: SubSearchLogic.Query): Result = withContext(Dispatchers.IO) {
        if (!hasKeys()) return@withContext Result(emptyList(), "Kein Schlüssel für die Untertitel-Suche. Einstellungen > API-Schlüssel")
        if (q.tmdbId <= 0 && q.title.isBlank()) return@withContext Result(emptyList(), "Titel unbekannt")
        coroutineScope {
            val os = async {
                if (Config.OS_KEY.isEmpty()) null else runCatching {
                    val (code, body) = get(osReq(SubSearchLogic.osSearchUrl(q)).build())
                    if (code == 200) SubSearchLogic.osHits(body) to null else emptyList<SubSearchLogic.Hit>() to "$code"
                }.getOrElse { emptyList<SubSearchLogic.Hit>() to errName(it) }
            }
            val sd = async {
                if (Config.SD_KEY.isEmpty()) null else runCatching {
                    val (code, body) = get(Request.Builder().url(SubSearchLogic.sdSearchUrl(q, Config.SD_KEY)).build())
                    if (code == 200) SubSearchLogic.sdHits(body, q) to null else emptyList<SubSearchLogic.Hit>() to "$code"
                }.getOrElse { emptyList<SubSearchLogic.Hit>() to errName(it) }
            }
            val parts = listOfNotNull(os.await()?.let { SubSearchLogic.OS to it }, sd.await()?.let { SubSearchLogic.SD to it })
            val hits = SubSearchLogic.rank(parts.flatMap { it.second.first }, q, AppSettings.ccPref)
            val errs = parts.filter { it.second.second != null }.joinToString(", ") { it.first + " " + it.second.second }
            Result(hits, if (hits.isEmpty() && errs.isNotEmpty()) "Suche fehlgeschlagen: $errs" else null)
        }
    }

    private fun errName(e: Throwable) = when (e) {
        is java.net.UnknownHostException -> "DNS"
        is java.net.SocketTimeoutException -> "Timeout"
        else -> e.javaClass.simpleName
    }

    /** Downloads [h] and keeps it for title [itemId]. Error text (German) if that is not possible. */
    suspend fun download(c: Context, itemId: String, h: SubSearchLogic.Hit, season: Int = 0, episode: Int = 0): Pair<Sub?, String?> = withContext(Dispatchers.IO) {
        try {
            val (name, data) = when (h.provider) {
                SubSearchLogic.OS -> osFile(h) ?: return@withContext null to osError
                else -> sdFile(h, season, episode) ?: return@withContext null to "Download fehlgeschlagen"
            }
            val dir = File(c.filesDir, "subs").apply { mkdirs() }
            val ext = name.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
            val f = File(dir, "s" + Integer.toHexString(itemId.hashCode()) + "_" + h.lang.take(5).filter(Char::isLetterOrDigit) + "." + ext)
            f.writeBytes(SubSearchLogic.toUtf8(data))
            val sub = Sub(f, h.lang.take(2), SubSearchLogic.label(h))
            remember(c, itemId, sub)
            prune(dir)
            sub to null
        } catch (e: Exception) {
            null to "Download fehlgeschlagen: " + errName(e)
        }
    }

    @Volatile private var osError = ""

    private fun osFile(h: SubSearchLogic.Hit): Pair<String, ByteArray>? {
        val body = """{"file_id":${h.ref.toLong()}}""".toRequestBody("application/json".toMediaType())
        val (code, text) = get(osReq(SubSearchLogic.OS_API + "/download").post(body).build())
        val o = runCatching { JSONObject(text) }.getOrNull()
        val link = o?.optString("link")?.takeIf { code == 200 && it.startsWith("https://") }
        if (link == null) {
            // e.g. 406: daily download limit reached ("message" says until when)
            osError = if (code == 406) "OpenSubtitles: Tageslimit erreicht" else "OpenSubtitles: Fehler $code"
            return null
        }
        client.newCall(Request.Builder().url(link).header("User-Agent", SubSearchLogic.UA).build()).execute().use { r ->
            if (!r.isSuccessful) { osError = "OpenSubtitles: Fehler ${r.code}"; return null }
            val bytes = r.body?.bytes() ?: return null
            return o.optString("file_name").ifBlank { "sub.srt" } to bytes
        }
    }

    private fun sdFile(h: SubSearchLogic.Hit, season: Int, episode: Int): Pair<String, ByteArray>? {
        val path = SubSearchLogic.sdPath(h.ref) ?: return null
        client.newCall(Request.Builder().url(SubSearchLogic.SD_DL + path).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return SubSearchLogic.fromZip(r.body?.bytes() ?: return null, season, episode, h.lang)
        }
    }

    // ------------------------------------------------------------------ Per title

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_subs", Context.MODE_PRIVATE)

    private fun remember(c: Context, itemId: String, s: Sub) {
        prefs(c).edit().putString(itemId, listOf(s.file.name, s.lang, s.label.replace('\t', ' ')).joinToString("\t")).apply()
    }

    /** Subtitle chosen earlier for [itemId] (file still there), otherwise null. */
    fun saved(c: Context, itemId: String): Sub? {
        val p = prefs(c).getString(itemId, null)?.split('\t') ?: return null
        if (p.size != 3 || p[0].contains('/')) return null
        val f = File(File(c.filesDir, "subs"), p[0])
        return if (f.isFile) Sub(f, p[1], p[2]) else null
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    // ------------------------------------------------------------------ Player

    fun config(s: Sub): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s.file))
            .setMimeType(s.mime).setLanguage(s.lang).setLabel(s.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    /** Media item for [url] with the subtitle chosen earlier for [itemId]. */
    fun item(c: Context, url: String, itemId: String?): MediaItem {
        val s = itemId?.let { saved(c, it) } ?: return MediaItem.fromUri(url)
        return MediaItem.Builder().setUri(url).setSubtitleConfigurations(listOf(config(s))).build()
    }

    /** Attach [s] to the running stream at the current position and switch it on. */
    fun attach(player: Player, s: Sub): Boolean {
        val cur = player.currentMediaItem ?: return false
        if (cur.localConfiguration == null) return false
        val others = cur.localConfiguration!!.subtitleConfigurations.filter { it.uri != Uri.fromFile(s.file) }
        val item = cur.buildUpon().setSubtitleConfigurations(others + config(s)).build()
        val pos = player.currentPosition
        val play = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(s.lang).build()
        player.setMediaItem(item, pos)
        player.prepare()
        player.playWhenReady = play
        return true
    }

    // ------------------------------------------------------------------ Key check (Settings)

    suspend fun checkOs(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Searching works with any key; a download of a file that does not exist tells whether the key is known
            val r = Request.Builder().url(SubSearchLogic.OS_API + "/download")
                .header("Api-Key", SubSearchLogic.cleanKey(key)).header("User-Agent", SubSearchLogic.UA).header("Accept", "application/json")
                .post("""{"file_id":1}""".toRequestBody("application/json".toMediaType())).build()
            val (code, body) = get(r)
            if (code == 200 || body.contains("Invalid file_id", true)) null else if (code in setOf(401, 403, 503)) "Schlüssel ungültig" else "Fehler $code"
        }.getOrElse { "Keine Verbindung: " + errName(it) }
    }

    suspend fun checkSd(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val q = SubSearchLogic.Query("", null, 27205, 0, 0, listOf("en"))
            val (code, body) = get(Request.Builder().url(SubSearchLogic.sdSearchUrl(q, key)).build())
            if (code == 200 && body.contains("\"status\":true")) null
            else if (body.contains("api_key", true) || code == 401 || code == 403) "Schlüssel ungültig" else "Fehler $code"
        }.getOrElse { "Keine Verbindung: " + errName(it) }
    }
}
