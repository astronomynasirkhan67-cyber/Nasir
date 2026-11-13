package com.ipvio.stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup of personal data (settings, favorites, history, profiles) into a file.
 * Playlists and login data are deliberately not included: they stay on the device.
 */
object Backup {
    private val NAMES = listOf(
        "ipvio_general", "ipvio_settings", "ipvio_lib", "ipvio_quality", "ipvio_hist", "ipvio_watch",
        "ipvio_catfavs", "ipvio_variants", "ipvio_guide", "ipvio_browse", "ipvio_cat", "ipvio_lang",
        "ipvio_chlists", "ipvio_tracks", "ipvio_eprog",
        // Round 20: live buffer/recordings, sidebar, phone, updates
        "ipvio_livetv", "ipvio_side", "ipvio_mobile", "ipvio_upd",
        // 1.2: sports (teams, notifications, leagues, channel correction), hidden/ignored groups, learned live quality
        "ipvio_sportfavs", "ipvio_sportalerts", "ipvio_sportleagues", "ipvio_sporthub", "ipvio_sportfix", "ipvio_hidecats", "ipvio_browselang", "ipvio_ignored", "ipvio_livequal",
        // Home block builder and Boomer mode
        "ipvio_homelayout", "ipvio_boomerlayout", "ipvio_boomer",
        // T-006: dual view, last live category, new episodes, imported lists, resume log (profile-scoped like the others)
        "ipvio_dual", "ipvio_live", "ipvio_newep", "ipvio_importedlists", "ipvio_resumelog",
        // 1.4: own order of categories (Live TV, movies, series)
        "ipvio_catorder"
    )

    /** A real backup never gets bigger; larger files are not even read. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    fun file(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "ipvio_backup.json")

    private fun fileNames(c: Context): List<String> {
        Profiles.init(c)
        return listOf(Profiles.META) + Profiles.list.flatMap { p -> NAMES.map { Profiles.scopedName(it, p.id) } }
    }

    /** Writes the backup and returns the file (or null on error). */
    fun export(c: Context): File? = runCatching {
        val f = file(c)
        f.parentFile?.mkdirs()
        f.writeText(exportText(c, false) ?: return null)
        f
    }.getOrNull()

    /**
     * Backup as text. [withPlaylists] = also the playlists with login data (only for transfer to another
     * own device on the Wi-Fi or into the own Google Drive; never in the file on the device).
     */
    fun exportText(c: Context, withPlaylists: Boolean): String? = runCatching {
        val root = JSONObject().put("v", 1).put("at", System.currentTimeMillis())
        val files = JSONObject()
        for (n in fileNames(c)) {
            val all = c.applicationContext.getSharedPreferences(n, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = JSONObject()
            for ((k, v) in all) {
                when (v) {
                    is String -> o.put(k, JSONArray().put("s").put(v))
                    is Boolean -> o.put(k, JSONArray().put("b").put(v))
                    is Int -> o.put(k, JSONArray().put("i").put(v))
                    is Long -> o.put(k, JSONArray().put("l").put(v))
                    is Float -> o.put(k, JSONArray().put("f").put(v.toDouble()))
                    is Set<*> -> o.put(k, JSONArray().put("t").put(JSONArray(v.map { it.toString() })))
                }
            }
            files.put(n, o)
        }
        root.put("files", files)
        if (withPlaylists) root.put("playlists", PlaylistStore.exportLists(c))
        root.toString()
    }.getOrNull()

    /** Reads the backup back. Returns the number of restored files, -1 on error. */
    fun restore(c: Context): Int = runCatching {
        val f = file(c)
        if (!f.exists() || f.length() > MAX_BYTES) return -1
        restoreText(c, f.readText())
    }.getOrDefault(-1)

    /** Restore a backup from text (Wi-Fi, Google Drive). Playlists in it are added, not replaced. */
    fun restoreText(c: Context, text: String): Int = runCatching {
        if (text.length > MAX_BYTES) return -1
        val root = JSONObject(text)
        if (root.optInt("v", 0) != 1) return -1
        val files = root.getJSONObject("files")
        var count = 0
        for (n in files.keys()) {
            // Allow known files only
            if (n != Profiles.META && NAMES.none { n == it || n.startsWith(it + "_p") }) continue
            val o = files.getJSONObject(n)
            val e = c.applicationContext.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                when (a.getString(0)) {
                    "s" -> e.putString(k, a.getString(1))
                    "b" -> e.putBoolean(k, a.getBoolean(1))
                    "i" -> e.putInt(k, a.getInt(1))
                    "l" -> e.putLong(k, a.getLong(1))
                    "f" -> e.putFloat(k, a.getDouble(1).toFloat())
                    "t" -> { val arr = a.getJSONArray(1); e.putStringSet(k, (0 until arr.length()).map { arr.getString(it) }.toSet()) }
                }
            }
            e.commit()
            count++
        }
        root.optJSONArray("playlists")?.let { PlaylistStore.importLists(c, it) }
        count
    }.getOrDefault(-1)
}
