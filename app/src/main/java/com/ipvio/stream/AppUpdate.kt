package com.ipvio.stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * [sha256] = lower-case hex of the APK from update.json, empty if the source gave none (GitHub API, custom address).
 * [minSupportedCode] and [mandatory] come from update.json (T-015).
 */
data class UpdateInfo(
    val code: Long, val name: String, val apkUrl: String, val notes: String,
    val sha256: String = "", val minSupportedCode: Long = 0L, val mandatory: Boolean = false
)

/**
 * Update from within the app.
 * Default: `update.json`, a file attached to the latest release on GitHub (PRIMARY_URL). If it is missing or unreadable,
 * the GitHub API of the latest release is used (DEFAULT_URL). The app checks once a day, shows
 * "Update available" in the bell at the top right and installs on a button press. Android always asks once
 * (for apps outside the Play Store it does not work entirely without confirmation).
 *
 * A custom address (Settings > About) still works: either a GitHub API address
 * (https://api.github.com/repos/NAME/REPO/releases/latest) or a small JSON file
 * {"versionCode": 2, "versionName": "1.1", "apk": "ipvio.apk", "sha256": "(optional)", "notes": "What's new"}.
 *
 * Before the installer opens (T-015): SHA-256 of the download (if the source gave one), package name, versionCode and
 * signer certificate of the APK are checked (see [UpdateVerify]). The signer check is what protects against a replaced
 * file; the hash protects against a damaged download.
 *
 * So that history, favorites and settings are preserved on an update, every new APK must be signed with the same
 * key (see docs/RELEASE.md). Otherwise Android rejects the installation.
 */
object AppUpdate {
    /** Built-in fallback address: the GitHub API of the latest release of the project. */
    const val DEFAULT_URL = "https://api.github.com/repos/Emrodev/IPVIO-Stream/releases/latest"

    /** Built-in primary address: `update.json` attached to the latest release (T-015). */
    const val PRIMARY_URL = "https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/update.json"

    /** Found update (for the bell), null = none known. */
    var available by mutableStateOf<UpdateInfo?>(null)
        private set

    /** "Später" tapped in the start dialog: stays hidden until the app is started again. */
    var promptDismissed by mutableStateOf(false)

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_upd", Context.MODE_PRIVATE)
    fun url(c: Context) = prefs(c).getString("url", "").orEmpty().ifBlank { DEFAULT_URL }
    fun setUrl(c: Context, v: String) = prefs(c).edit().putString("url", v.trim()).apply()

    /** Automatic check at start (default: on). */
    fun auto(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    /** T-014: the first-start update card was already decided (shown or skipped). New key, existing keys are untouched. */
    fun firstCardSeen(c: Context) = prefs(c).getBoolean("first_card_seen", false)
    fun markFirstCardSeen(c: Context) = prefs(c).edit().putBoolean("first_card_seen", true).apply()

    fun currentCode(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(0L)

    fun currentName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

    /** Returns null if the installed version is current. */
    suspend fun check(c: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val u = url(c)
            if (u.isBlank()) error("Keine Update-Adresse eingetragen")
            // Built-in source: update.json first, the GitHub API if that fails for any reason (no asset yet, bad file, no network).
            // Custom address: only that address, as before.
            val res = if (u == DEFAULT_URL) {
                try {
                    fetch(c, PRIMARY_URL, strict = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fetch(c, DEFAULT_URL, strict = false)
                }
            } else fetch(c, u, strict = false)
            prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
            available = res
            remember(c, res)
            res
        }
    }

    /** One request to [u]; returns null if the installed version is current. [strict] = the file must carry a SHA-256. */
    private fun fetch(c: Context, u: String, strict: Boolean): UpdateInfo? {
        val req = Request.Builder().url(u).header("User-Agent", "IPVIO Stream")
            .header("Accept", if (u == DEFAULT_URL) "application/vnd.github+json" else "application/json, application/vnd.github+json").build()
        return Net.base.newCall(req).execute().use { r ->
            if (r.code == 404) error("Noch keine Veröffentlichung gefunden")
            if (!r.isSuccessful) error("Fehler ${r.code}")
            // An update description is tiny; a huge answer is not one.
            val j = JSONObject(r.peekBody(512 * 1024L).string())
            if (j.has("tag_name")) fromGithub(c, j) else fromFile(c, u, j, strict)
        }
    }

    private fun fromGithub(c: Context, j: JSONObject): UpdateInfo? {
        val tag = j.optString("tag_name")
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        if (!UpdateLogic.isNewerName(currentName(c), tag)) return null
        val a = j.optJSONArray("assets")
        val assets = ArrayList<Pair<String, String>>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            assets.add(o.optString("name") to o.optString("browser_download_url"))
        }
        val apk = UpdateLogic.pickApk(assets) ?: return null
        val notes = j.optString("body").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(4).joinToString(" · ")
        return UpdateInfo(0L, tag.trimStart('v', 'V'), apk, notes.take(300))
    }

    private fun fromFile(c: Context, u: String, j: JSONObject, strict: Boolean): UpdateInfo? {
        val m = UpdateLogic.parseManifest(j, strict)
        if (!UpdateLogic.isNewer(currentCode(c), m.code)) return null
        val apkUrl = UpdateLogic.resolve(u, m.apk)
        if (strict && !apkUrl.startsWith("https://")) error("Update-Datei: Download-Adresse nicht sicher")
        return UpdateInfo(m.code, m.name, apkUrl, m.notes, m.sha256.orEmpty(), m.minSupportedCode, m.mandatory)
    }

    /** At start: check silently at most once a day. Errors (no network, GitHub unreachable) stay invisible. */
    suspend fun autoCheck(c: Context) {
        if (!auto(c)) return
        if (!UpdateLogic.due(prefs(c).getLong("last", 0L), System.currentTimeMillis())) {
            // Remember a known update so the bell keeps showing it after a restart
            restore(c); return
        }
        check(c)
    }

    /**
     * At every app start (not only once a day): ask for a new version so the start dialog can offer it right away.
     * At most every 10 minutes, so quick restarts do not use up GitHub's request limit. Errors stay invisible.
     */
    suspend fun startCheck(c: Context) {
        if (!auto(c)) return
        val now = System.currentTimeMillis()
        val last = prefs(c).getLong("last_start", 0L)
        if (now >= last && now - last < 10 * 60_000L) { restore(c); return }
        prefs(c).edit().putLong("last_start", now).apply()
        check(c)
    }

    private fun remember(c: Context, u: UpdateInfo?) {
        val e = prefs(c).edit()
        // T-015: h, s, m are extra fields inside the same JSON value (old entries simply lack them); the key itself is unchanged.
        if (u == null) e.remove("known") else e.putString(
            "known",
            JSONObject().put("c", u.code).put("n", u.name).put("a", u.apkUrl).put("t", u.notes)
                .put("h", u.sha256).put("s", u.minSupportedCode).put("m", u.mandatory).toString()
        )
        e.apply()
    }

    private fun restore(c: Context) {
        val s = prefs(c).getString("known", null) ?: return
        val o = runCatching { JSONObject(s) }.getOrNull() ?: return
        val u = UpdateInfo(
            o.optLong("c"), o.optString("n"), o.optString("a"), o.optString("t"),
            UpdateLogic.normalizeSha(o.optString("h")).orEmpty(), o.optLong("s"), o.optBoolean("m")
        )
        // Installed in the meantime? Then do not show it any more
        val newer = if (u.code > 0) UpdateLogic.isNewer(currentCode(c), u.code) else UpdateLogic.isNewerName(currentName(c), u.name)
        if (newer) available = u else remember(c, null)
    }

    /** Download and open the installer; [status] gets progress and result as text. */
    suspend fun downloadAndInstall(c: Context, u: UpdateInfo, status: (String) -> Unit) {
        download(c, u) { p -> status(if (p >= 0) "Lade … $p %" else "Lade …") }
            .onSuccess { f ->
                // T-015: the installer opens only for a file that passed the checks (package, version, signer).
                val problem = withContext(Dispatchers.IO) { UpdateVerify.check(c, f, u) }
                if (problem != null) {
                    f.delete()
                    status(problem)
                } else status(if (install(c, f)) "Installation gestartet" else "Erlaube der App einmal „Apps installieren“ und tippe dann erneut.")
            }
            .onFailure { status(it.message ?: "Fehler") }
    }

    /** Is this update one the user may not postpone (T-015)? */
    fun isRequired(c: Context, u: UpdateInfo): Boolean = UpdateLogic.isRequired(currentCode(c), u.minSupportedCode, u.mandatory)

    /** Downloads the APK into the cache; [onProgress] delivers percent (0..100) or -1 for unknown size. */
    suspend fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "ipvio-update.apk")
            val client = Net.withTimeouts(15, 60)
            client.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "IPVIO Stream").build()).execute().use { r ->
                if (!r.isSuccessful) error("Fehler ${r.code}")
                val body = r.body ?: error("Leere Antwort")
                val total = body.contentLength()
                var done = 0L
                var last = -2
                val md = java.security.MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); md.update(buf, 0, n); done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
                if (total > 0 && done != total) { out.delete(); error("Download unvollständig. Versuche es erneut.") }
                if (info.sha256.isNotEmpty() && !UpdateLogic.sameHash(info.sha256, UpdateLogic.hex(md.digest()))) {
                    out.delete()
                    error("Prüfsumme stimmt nicht. Die Datei ist beschädigt oder verändert. Versuche es erneut.")
                }
            }
            out
        }
    }

    /** Opens the system installer. If the "Apps from unknown sources" permission is missing, its setting opens instead. */
    fun install(c: Context, apk: File): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", apk)
        return runCatching {
            c.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }
}
