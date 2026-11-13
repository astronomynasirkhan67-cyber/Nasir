package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * API keys for the optional film information services. No key is shipped with the app: every user enters their own
 * (Settings > API keys). Developers can additionally put keys into "secrets.properties" (not in Git); those are only
 * used as a fallback when nothing was entered in the app.
 *
 *   TMDB   https://www.themoviedb.org/settings/api  – posters, descriptions, cast, trailers, automatic genres
 *   OMDb   https://www.omdbapi.com/apikey.aspx      – IMDb and Rotten Tomatoes ratings
 *
 *   OpenSubtitles https://www.opensubtitles.com/consumers – online subtitle search in the player
 *   SubDL         https://subdl.com/panel/api              – online subtitle search in the player
 *
 * Intro/credits times come from TheIntroDB, which needs no key for reading.
 * Without keys the app works normally and only hides the extra information.
 */
object Config {
    @Volatile var TMDB_KEY: String = clean(BuildConfig.TMDB_KEY)
        private set
    @Volatile var OMDB_KEY: String = clean(BuildConfig.OMDB_KEY)
        private set
    /** true if the key was entered in the app (not from secrets.properties). */
    var tmdbOwn by androidx.compose.runtime.mutableStateOf(false)
        private set
    var omdbOwn by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var inited = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_keys", Context.MODE_PRIVATE)

    /** Letters and digits only (a key must never smuggle special characters into addresses). */
    fun clean(s: String): String = s.trim().filter { it.isLetterOrDigit() }.take(64)

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = prefs(c)
        val t = clean(p.getString("tmdb", "").orEmpty())
        val o = clean(p.getString("omdb", "").orEmpty())
        if (t.isNotEmpty()) { TMDB_KEY = t; tmdbOwn = true }
        if (o.isNotEmpty()) { OMDB_KEY = o; omdbOwn = true }
    }

    /** Stores the entered keys; empty = remove key (then secrets.properties applies again if present). */
    fun save(c: Context, tmdb: String, omdb: String) {
        val t = clean(tmdb); val o = clean(omdb)
        prefs(c).edit().putString("tmdb", t).putString("omdb", o).apply()
        TMDB_KEY = t.ifEmpty { clean(BuildConfig.TMDB_KEY) }; tmdbOwn = t.isNotEmpty()
        OMDB_KEY = o.ifEmpty { clean(BuildConfig.OMDB_KEY) }; omdbOwn = o.isNotEmpty()
        inited = true
    }

    /** Keys for the online subtitle search (see [SubSearch]); empty = that service is not asked. */
    @Volatile var OS_KEY: String = ""
        private set
    @Volatile var SD_KEY: String = ""
        private set

    @Synchronized
    fun initSubs(c: Context) {
        val p = prefs(c)
        OS_KEY = SubSearchLogic.cleanKey(p.getString("opensubtitles", "").orEmpty())
        SD_KEY = SubSearchLogic.cleanKey(p.getString("subdl", "").orEmpty())
    }

    fun saveSubs(c: Context, os: String, sd: String) {
        OS_KEY = SubSearchLogic.cleanKey(os); SD_KEY = SubSearchLogic.cleanKey(sd)
        prefs(c).edit().putString("opensubtitles", OS_KEY).putString("subdl", SD_KEY).apply()
    }

    /** Entered key (for the input field); keys from secrets.properties are not shown. */
    fun ownTmdb(c: Context): String = clean(prefs(c).getString("tmdb", "").orEmpty())
    fun ownOmdb(c: Context): String = clean(prefs(c).getString("omdb", "").orEmpty())
}

/** Checks a key with a small request to the service. */
object KeyCheck {
    /** null = OK, otherwise error text (German, gets translated). */
    suspend fun tmdb(key: String): String? = check("https://api.themoviedb.org/3/configuration?api_key=" + Config.clean(key)) { code, body ->
        if (code == 200) null else if (code == 401) "Schlüssel ungültig" else "Fehler $code"
    }

    suspend fun omdb(key: String): String? = check("https://www.omdbapi.com/?apikey=" + Config.clean(key) + "&i=tt0111161") { code, body ->
        if (code == 200 && body.contains("\"Response\":\"True\"")) null
        else if (code == 401 || body.contains("Invalid API key")) "Schlüssel ungültig" else "Fehler $code"
    }

    suspend fun openSubtitles(key: String): String? = SubSearch.checkOs(key)
    suspend fun subdl(key: String): String? = SubSearch.checkSd(key)

    private suspend fun check(url: String, eval: (Int, String) -> String?): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Net.base.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r -> eval(r.code, r.body?.string().orEmpty()) }
            } catch (e: java.net.UnknownHostException) {
                "Server nicht gefunden (DNS)"
            } catch (e: javax.net.ssl.SSLException) {
                "Sichere Verbindung fehlgeschlagen. Datum und Uhrzeit des Geräts prüfen"
            } catch (e: java.net.SocketTimeoutException) {
                "Zeitüberschreitung, Server antwortet nicht"
            } catch (e: Exception) {
                "Keine Verbindung: " + e.javaClass.simpleName
            }
        }
}
