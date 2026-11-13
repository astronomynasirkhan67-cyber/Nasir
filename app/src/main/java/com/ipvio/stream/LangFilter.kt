package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Language filter per profile: hides content in languages you do not need (e.g. Turkish, Arabic categories).
 * Works everywhere – home, movies, series, Live TV, search, TV program. Hidden languages are not even loaded
 * into working memory (see PlaylistStore); the language of each entry is in [Item.lang].
 */
object LangFilter {
    private const val PREFS = "ipvio_lang"

    /** Hidden languages (abbreviation from [LangTagLogic], "?" = no tag). */
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Changes with every switch. */
    var version by mutableIntStateOf(0)
        private set
    /**
     * Deliberately chose "all languages" (language question skipped or "Show all"): then large playlists are not asked
     * again on every load.
     */
    var allChosen by mutableStateOf(false)
        private set

    fun init(c: Context) {
        val p = Profiles.sp(c, PREFS)
        hidden = LangTagLogic.decode(p.getString("hidden", ""))
        allChosen = p.getBoolean("all", false)
        version++
    }

    /** Stores the selection. The playlist must be parsed again afterwards ([PlaylistStore.reapplyLanguages]). Empty = deliberately all. */
    fun set(c: Context, h: Set<String>) {
        hidden = h
        allChosen = h.isEmpty()
        Profiles.sp(c, PREFS).edit().putString("hidden", LangTagLogic.encode(h)).putBoolean("all", allChosen).apply()
        version++
    }

    /** Reset the language selection without choosing "all" (the next large playlist asks again). */
    fun reset(c: Context) {
        hidden = emptySet()
        allChosen = false
        Profiles.sp(c, PREFS).edit().putString("hidden", "").putBoolean("all", false).apply()
        version++
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /**
     * Stores the selection and shortly afterwards parses the playlist again from the cache.
     * Several quick clicks are merged; if a load is running, it is applied afterwards.
     * Keeps running independently of the settings page, even if you leave it immediately.
     */
    fun setAndApply(c: Context, h: Set<String>) {
        set(c, h)
        val app = c.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(600)
            repeat(60) {
                if (PlaylistStore.savedUrl(app) == null) return@launch
                if (PlaylistStore.reapplyLanguages(app)) return@launch
                delay(500)
            }
        }
    }
}
