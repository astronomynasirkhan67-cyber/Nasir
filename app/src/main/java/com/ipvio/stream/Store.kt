package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object Langs {
    val AUDIO = listOf(
        "" to "Automatisch", "de" to "Deutsch", "en" to "Englisch", "ja" to "Japanisch", "ko" to "Koreanisch",
        "fr" to "Französisch", "es" to "Spanisch", "it" to "Italienisch", "tr" to "Türkisch", "hr" to "Kroatisch", "sr" to "Serbisch",
        "bs" to "Bosnisch", "pt" to "Portugiesisch", "pl" to "Polnisch", "ru" to "Russisch", "ar" to "Arabisch", "nl" to "Niederländisch"
    )
    /** Second audio language: used when the first is not available. */
    val AUDIO2 = listOf("" to "Keine") + AUDIO.drop(1)
    /** Subtitle language; empty = same as the app. */
    val SUBS = listOf("" to "Wie die App-Sprache") + AUDIO.drop(1)
    val SUB_MODES = listOf("Aus", "Immer an", "Nur bei fremder Tonspur")
}

object SubStyle {
    val SIZES = listOf("Klein" to 0.7f, "Normal" to 1f, "Groß" to 1.3f, "Sehr groß" to 1.7f, "Riesig" to 2.1f)
    val COLORS = listOf("Weiß" to 0xFFFFFFFFL, "Gelb" to 0xFFFFEB3BL, "Grün" to 0xFF69F0AEL, "Cyan" to 0xFF18FFFFL, "Pink" to 0xFFFF80ABL)
    val BGS = listOf("Keiner" to 0x00000000L, "Halbtransparent" to 0x99000000L, "Schwarz" to 0xFF000000L)
    val EDGES = listOf("Kein", "Schatten", "Kontur")
}

val MENU_LABELS = listOf("Home", "Filme", "Serien", "Live TV", "Suche", "Einstellungen", "EPG", "Favoriten", "Meine Liste", "Verlauf", "Sport")

object AppSettings {
    var ipvioFilter by mutableStateOf(false)
    var gridCols by mutableIntStateOf(4)
    var gridRows by mutableIntStateOf(3)
    var cardW by mutableIntStateOf(150)
    var subSize by mutableIntStateOf(1)
    var subColor by mutableIntStateOf(0)
    var subBg by mutableIntStateOf(1)
    var subEdge by mutableIntStateOf(2)
    var audioLang by mutableStateOf("")
    /** Second audio language (fallback when the first is not available). */
    var audioLang2 by mutableStateOf("")
    /** Subtitle language ("" = same as the app). Whether they are on is controlled by [subMode]. */
    var subLang by mutableStateOf("")
    /** [TrackLogic.SUB_OFF], [TrackLogic.SUB_ALWAYS] or [TrackLogic.SUB_FOREIGN]. */
    var subMode by mutableIntStateOf(TrackLogic.SUB_OFF)
    /** Prefer subtitles for the hard of hearing (CC/SDH). */
    var ccPref by mutableStateOf(false)
    var skipIntro by mutableStateOf(true)
    var showInfo by mutableStateOf(true)
    /** Search with the device's own keyboard (e.g. Gboard: other languages, voice input) instead of the built-in one. */
    var systemKeyboard by mutableStateOf(false)
    var seekStep by mutableIntStateOf(10)
    var autoNext by mutableStateOf(true)
    var hideSec by mutableIntStateOf(4)
    var showEndTime by mutableStateOf(true)
    /** Time zone for program times: 0 = Germany (Berlin), 1 = time zone of the device. Many TVs are set to UTC. */
    var tzMode by mutableIntStateOf(0)
    var autoSkipIntro by mutableStateOf(false)
    /** Start the next episode right when the credits begin (only if credits times are known). */
    var autoSkipOutro by mutableStateOf(false)
    /** Clock on screen: on/off, corner (0 top right, 1 top left, 2 bottom right, 3 bottom left), opacity in %, size (0 small, 1 medium, 2 large). */
    var clockOn by mutableStateOf(false)
    /** Match the frame rate to the movie (ExoPlayer: only if the switch is possible seamlessly). */
    var autoFps by mutableStateOf(true)
    /** Clock: background box on/off and its opacity in % (the opacity of the text is clockOpacity). */
    var clockBg by mutableStateOf(true)
    var clockBgOpacity by mutableIntStateOf(40)
    /** Buffer: 0 Automatic (learns on stutter), 1 Normal, 2 Large, 3 Very large. [bufferLearned] = level 0..2 learned by the automatic mode. */
    var bufferMode by mutableIntStateOf(0)
    var bufferLearned by mutableIntStateOf(0)
    var clockPos by mutableIntStateOf(0)
    var clockOpacity by mutableIntStateOf(70)
    var clockSize by mutableIntStateOf(1)
    val menuOrder = mutableStateListOf(*MenuLogic.DEFAULT_ORDER.toTypedArray())
    val menuHidden = mutableStateListOf(*MenuLogic.parseHidden(MenuLogic.DEFAULT_HIDDEN).toTypedArray())
    val searchHistory = mutableStateListOf<String>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_settings")

    fun init(c: Context) {
        val p = prefs(c)
        // Default: provider categories; automatic genres only if chosen (setting or view "Categories")
        ipvioFilter = p.getBoolean("ipvio_filter", false)
        gridCols = p.getInt("grid_cols", 6).coerceIn(3, 10)
        gridRows = p.getInt("grid_rows", 3).coerceIn(2, 8)
        cardW = p.getInt("card_w", 150).coerceIn(110, 220)
        subSize = p.getInt("sub_size", 1).coerceIn(0, SubStyle.SIZES.lastIndex)
        subColor = p.getInt("sub_color", 0).coerceIn(0, SubStyle.COLORS.lastIndex)
        subBg = p.getInt("sub_bg", 1).coerceIn(0, SubStyle.BGS.lastIndex)
        subEdge = p.getInt("sub_edge", 2).coerceIn(0, SubStyle.EDGES.lastIndex)
        audioLang = p.getString("audio_lang", "") ?: ""
        audioLang2 = p.getString("audio_lang2", "") ?: ""
        subLang = p.getString("sub_lang", "") ?: ""
        // Before: subtitle language empty = off. Derive from that without a new setting.
        subMode = if (p.contains("sub_mode")) p.getInt("sub_mode", 0).coerceIn(0, 2) else if (subLang.isEmpty()) TrackLogic.SUB_OFF else TrackLogic.SUB_ALWAYS
        ccPref = p.getBoolean("cc_pref", false)
        skipIntro = p.getBoolean("skip_intro", true)
        showInfo = p.getBoolean("show_info", true)
        systemKeyboard = p.getBoolean("sys_kb", false)
        seekStep = p.getInt("seek_step", 10).coerceIn(5, 120)
        autoNext = p.getBoolean("auto_next", true)
        hideSec = p.getInt("hide_sec", 4).coerceIn(3, 15)
        showEndTime = p.getBoolean("show_end", true)
        tzMode = p.getInt("tz_mode", 0).coerceIn(0, 1)
        autoSkipIntro = p.getBoolean("auto_skip", false)
        autoSkipOutro = p.getBoolean("auto_outro", false)
        clockOn = p.getBoolean("clock_on", false)
        autoFps = p.getBoolean("auto_fps", true)
        clockBg = p.getBoolean("clock_bg", true)
        clockBgOpacity = p.getInt("clock_bg_op", 40).coerceIn(10, 100)
        bufferMode = p.getInt("buf_mode", 0).coerceIn(0, 3)
        bufferLearned = p.getInt("buf_learned", 0).coerceIn(0, 2)
        clockPos = p.getInt("clock_pos", 0).coerceIn(0, 3)
        clockOpacity = p.getInt("clock_opacity", 70).coerceIn(20, 100)
        clockSize = p.getInt("clock_size", 1).coerceIn(0, 2)
        menuOrder.clear(); menuOrder.addAll(MenuLogic.parseOrder(p.getString("menu_order", null)))
        menuHidden.clear(); menuHidden.addAll(MenuLogic.parseHidden(p.getString("menu_hidden", MenuLogic.DEFAULT_HIDDEN)))
        // One-time (round 14): remove "EPG" from the sidebar (it only opened the TV guide, which exists in Live TV)
        if (p.getInt("menu_ver", 1) < 2) {
            if (MenuLogic.EPG !in menuHidden) menuHidden.add(MenuLogic.EPG)
            p.edit().putInt("menu_ver", 2).putString("menu_hidden", menuHidden.joinToString(",")).apply()
        }
        searchHistory.clear()
        searchHistory.addAll((p.getString("search_hist", "") ?: "").split("\n").filter { it.isNotBlank() })
        if (p.getInt("look_ver", 1) < 2) {
            // One-time: set company colors (IPVIO green on navy) as the new default design. A custom color is kept.
            p.edit().putInt("look_accent", 0).putInt("look_bg", 0).putInt("look_ver", 2).commit()
        }
        if (p.getInt("look_ver", 1) < 4) {
            // One-time: replace earlier default greens (#39FF13, #22C55E) as custom color with the current one.
            val e = p.edit().putInt("look_ver", 4)
            val old = setOf(0x39FF13, 0x22C55E)
            if ((p.getInt("look_custom", Look.BRAND_GREEN) and 0xFFFFFF) in old) e.putInt("look_custom", Look.BRAND_GREEN)
            val c2 = p.getInt("look_custom2", -1)
            if (c2 >= 0 && (c2 and 0xFFFFFF) == 0x39FF13) e.putInt("look_custom2", Look.BRAND_GREEN_HI)
            e.commit()
        }
        if (p.getInt("look_ver", 1) < 5) {
            // One-time (round 13): colors of the design (orange on black). New preset at position 12 –
            // the custom color therefore moves from 12 to 13 (it is kept). Going back is possible under Settings > Design.
            val e = p.edit().putInt("look_ver", 5)
            if (p.getInt("look_accent", 0) == 12) e.putInt("look_custom_was", 1)
            e.putInt("look_accent", 12).putInt("look_bg", 5).commit()
        }
        if (p.getInt("look_ver", 1) < 6) {
            // One-time (round 13e): back to the default colors (IPVIO green on navy). The design presets no longer exist,
            // the custom color is at position 12 again. Whoever had the custom color before gets it back.
            val e = p.edit().putInt("look_ver", 6)
            val acc = p.getInt("look_accent", 0)
            val newAcc = when {
                acc == 13 -> Look.CUSTOM
                acc == 12 -> if (p.getInt("look_custom_was", 0) == 1) Look.CUSTOM else Look.DEFAULT_ACCENT
                else -> acc
            }
            val bg = p.getInt("look_bg", 0)
            e.putInt("look_accent", newAcc).putInt("look_bg", if (bg >= 5) Look.DEFAULT_BG else bg).remove("look_custom_was").commit()
        }
        Look.accentIdx = p.getInt("look_accent", Look.DEFAULT_ACCENT).coerceIn(0, Look.ACCENTS.size)
        Look.custom = p.getInt("look_custom", Look.BRAND_GREEN) and 0xFFFFFF
        Look.custom2 = p.getInt("look_custom2", -1).let { if (it < 0) -1 else it and 0xFFFFFF }
        Look.bgIdx = p.getInt("look_bg", Look.DEFAULT_BG).coerceIn(0, Look.BGS.lastIndex)
        Look.motion = p.getInt("look_motion", 2).coerceIn(0, 2)
        Look.uiScale = p.getInt("look_scale", Look.DEFAULT_SCALE).coerceIn(0, Look.SCALES.lastIndex)
    }

    /** Saves color theme, background, animation level and display size. */
    fun setLook(c: Context, accent: Int, custom: Int, bg: Int, motion: Int, scale: Int, custom2: Int = Look.custom2) {
        Look.accentIdx = accent.coerceIn(0, Look.ACCENTS.size)
        Look.custom = custom and 0xFFFFFF
        Look.custom2 = if (custom2 < 0) -1 else custom2 and 0xFFFFFF
        Look.bgIdx = bg.coerceIn(0, Look.BGS.lastIndex)
        Look.motion = motion.coerceIn(0, 2)
        Look.uiScale = scale.coerceIn(0, Look.SCALES.lastIndex)
        prefs(c).edit().putInt("look_accent", Look.accentIdx).putInt("look_custom", Look.custom).putInt("look_custom2", Look.custom2).putInt("look_bg", Look.bgIdx).putInt("look_ver", 6)
            .putInt("look_motion", Look.motion).putInt("look_scale", Look.uiScale).apply()
    }

    fun setTz(c: Context, mode: Int) {
        tzMode = mode.coerceIn(0, 1)
        prefs(c).edit().putInt("tz_mode", tzMode).apply()
    }

    fun setMenuDefault(c: Context) {
        menuOrder.clear(); menuOrder.addAll(MenuLogic.DEFAULT_ORDER)
        menuHidden.clear(); menuHidden.addAll(MenuLogic.parseHidden(MenuLogic.DEFAULT_HIDDEN))
        saveMenu(c)
    }

    /**
     * Resets ALL settings to the defaults (view, menu, player, audio, design).
     * Playlists, favorites, history and saved positions are left untouched.
     */
    fun resetAll(c: Context) {
        val hist = searchHistory.toList()
        prefs(c).edit().clear().apply()
        init(c)
        searchHistory.clear(); searchHistory.addAll(hist)
        if (hist.isNotEmpty()) prefs(c).edit().putString("search_hist", hist.joinToString("\n")).apply()
    }

    /**
     * Resets only the given preference keys to their defaults: removes them and reloads every value from storage
     * (the defaults are the ones in [init]). All other settings keep their saved value.
     */
    fun resetKeys(c: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        val e = prefs(c).edit()
        keys.forEach { e.remove(it) }
        e.apply()
        init(c)
    }

    fun set(c: Context, v: Boolean) {
        ipvioFilter = v
        prefs(c).edit().putBoolean("ipvio_filter", v).apply()
    }

    fun setSystemKeyboard(c: Context, on: Boolean) {
        systemKeyboard = on
        prefs(c).edit().putBoolean("sys_kb", on).apply()
    }

    fun setPlayer(c: Context, skip: Boolean, info: Boolean, step: Int) {
        skipIntro = skip; showInfo = info; seekStep = step
        prefs(c).edit().putBoolean("skip_intro", skip).putBoolean("show_info", info).putInt("seek_step", step).apply()
    }

    fun setPlayerExtra(c: Context, autoNext: Boolean, hideSec: Int, endTime: Boolean, autoSkip: Boolean) {
        this.autoNext = autoNext
        this.hideSec = hideSec.coerceIn(3, 15)
        showEndTime = endTime
        autoSkipIntro = autoSkip
        prefs(c).edit().putBoolean("auto_next", autoNext).putInt("hide_sec", this.hideSec)
            .putBoolean("show_end", endTime).putBoolean("auto_skip", autoSkip).apply()
    }

    fun setAutoOutro(c: Context, on: Boolean) {
        autoSkipOutro = on
        prefs(c).edit().putBoolean("auto_outro", on).apply()
    }

    fun setClockBg(c: Context, on: Boolean, opacity: Int) {
        clockBg = on; clockBgOpacity = opacity.coerceIn(10, 100)
        prefs(c).edit().putBoolean("clock_bg", on).putInt("clock_bg_op", clockBgOpacity).apply()
    }

    /** Changing the mode resets the learned level. */
    fun setBufferMode(c: Context, mode: Int) {
        bufferMode = mode.coerceIn(0, 3); bufferLearned = 0
        prefs(c).edit().putInt("buf_mode", bufferMode).putInt("buf_learned", 0).apply()
    }

    /** The automatic mode noticed stutter: next playback with a larger buffer. */
    fun learnBuffer(c: Context) {
        val n = BufferLogic.bump(bufferMode, bufferLearned)
        if (n == bufferLearned) return
        bufferLearned = n
        prefs(c).edit().putInt("buf_learned", n).apply()
    }

    fun setAutoFps(c: Context, on: Boolean) {
        autoFps = on
        prefs(c).edit().putBoolean("auto_fps", on).apply()
    }

    fun setClock(c: Context, on: Boolean, pos: Int, opacity: Int, size: Int) {
        clockOn = on; clockPos = pos.coerceIn(0, 3); clockOpacity = opacity.coerceIn(20, 100); clockSize = size.coerceIn(0, 2)
        prefs(c).edit().putBoolean("clock_on", on).putInt("clock_pos", clockPos).putInt("clock_opacity", clockOpacity).putInt("clock_size", clockSize).apply()
    }

    fun setGrid(c: Context, cols: Int, rows: Int) {
        gridCols = cols.coerceIn(3, 10)
        gridRows = rows.coerceIn(2, 8)
        prefs(c).edit().putInt("grid_cols", gridCols).putInt("grid_rows", gridRows).apply()
    }

    fun setCardW(c: Context, w: Int) {
        cardW = w.coerceIn(110, 220)
        prefs(c).edit().putInt("card_w", cardW).apply()
    }

    fun setSub(c: Context, size: Int, color: Int, bg: Int, edge: Int) {
        subSize = size; subColor = color; subBg = bg; subEdge = edge
        prefs(c).edit().putInt("sub_size", size).putInt("sub_color", color)
            .putInt("sub_bg", bg).putInt("sub_edge", edge).apply()
    }

    fun setLangs(c: Context, audio: String, sub: String) {
        audioLang = audio; subLang = sub
        prefs(c).edit().putString("audio_lang", audio).putString("sub_lang", sub).apply()
    }

    fun setTrackExtra(c: Context, audio2: String, mode: Int, cc: Boolean) {
        audioLang2 = audio2; subMode = mode.coerceIn(0, 2); ccPref = cc
        prefs(c).edit().putString("audio_lang2", audio2).putInt("sub_mode", subMode).putBoolean("cc_pref", cc).apply()
    }

    /** Subtitle language that applies: the configured one or the app's. */
    fun subLangEffective(): String = subLang.ifEmpty { Lang.code() }

    private fun saveMenu(c: Context) {
        prefs(c).edit().putString("menu_order", menuOrder.joinToString(","))
            .putString("menu_hidden", menuHidden.joinToString(",")).apply()
    }

    fun moveMenu(c: Context, id: Int, delta: Int) {
        val moved = MenuLogic.move(menuOrder.toList(), id, delta)
        if (moved == menuOrder.toList()) return
        menuOrder.clear(); menuOrder.addAll(moved)
        saveMenu(c)
    }

    fun toggleMenu(c: Context, id: Int) {
        val next = MenuLogic.toggle(menuHidden.toList(), id)
        if (next == menuHidden.toList()) return
        menuHidden.clear(); menuHidden.addAll(next)
        saveMenu(c)
    }

    fun addSearch(c: Context, q: String) {
        val t = q.trim()
        if (t.length < 2) return
        searchHistory.removeAll { it.equals(t, true) }
        searchHistory.add(0, t)
        while (searchHistory.size > 10) searchHistory.removeAt(searchHistory.lastIndex)
        prefs(c).edit().putString("search_hist", searchHistory.joinToString("\n")).apply()
    }

    /** Removes one entry from the search history. */
    fun removeSearch(c: Context, q: String) {
        searchHistory.removeAll { it.equals(q, true) }
        prefs(c).edit().putString("search_hist", searchHistory.joinToString("\n")).apply()
    }

    fun clearSearch(c: Context) {
        searchHistory.clear()
        prefs(c).edit().remove("search_hist").apply()
    }
}

object Library {
    val favs = mutableStateListOf<String>()
    val watch = mutableStateListOf<String>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_lib")

    fun init(c: Context) {
        if (favs.isEmpty()) favs.addAll((prefs(c).getString("favs", "") ?: "").split("\n").filter { it.isNotBlank() })
        if (watch.isEmpty()) watch.addAll((prefs(c).getString("watch", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    fun isFav(id: String) = favs.contains(id)
    fun isWatch(id: String) = watch.contains(id)

    fun toggleFav(c: Context, id: String) {
        if (!favs.remove(id)) favs.add(0, id)
        prefs(c).edit().putString("favs", favs.joinToString("\n")).apply()
    }

    /** Moves favorite [id] by [delta] places inside [shown] (the favorites on screen); all other entries keep their place. */
    fun moveFav(c: Context, shown: List<String>, id: String, delta: Int) = setFavs(c, FavOrderLogic.move(favs.toList(), shown, id, delta))

    /** Moves favorite [id] to the first place inside [shown]. */
    fun favToTop(c: Context, shown: List<String>, id: String) = setFavs(c, FavOrderLogic.toTop(favs.toList(), shown, id))

    private fun setFavs(c: Context, list: List<String>) {
        if (list == favs.toList()) return
        favs.clear(); favs.addAll(list)
        prefs(c).edit().putString("favs", favs.joinToString("\n")).apply()
    }

    fun toggleWatch(c: Context, id: String) {
        if (!watch.remove(id)) watch.add(0, id)
        prefs(c).edit().putString("watch", watch.joinToString("\n")).apply()
    }
}

object QualityStore {
    private val map = HashMap<String, String>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_quality")

    fun init(c: Context) {
        if (map.isNotEmpty()) return
        for ((k, v) in prefs(c).all) if (v is String) map[k] = v
    }

    fun get(id: String): String? = map[id]

    fun put(c: Context, id: String, label: String) {
        if (map[id] == label) return
        map[id] = label
        prefs(c).edit().putString(id, label).apply()
    }
}

object History {
    val ids = mutableStateListOf<String>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_hist")

    fun init(c: Context) {
        if (ids.isNotEmpty()) return
        ids.addAll((prefs(c).getString("ids", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    fun add(c: Context, id: String) {
        ids.remove(id)
        ids.add(0, id)
        while (ids.size > 80) ids.removeAt(ids.lastIndex)
        prefs(c).edit().putString("ids", ids.joinToString("\n")).apply()
    }

    /** Removes a single title from "Recently watched". */
    fun remove(c: Context, id: String) {
        if (!ids.remove(id)) return
        prefs(c).edit().putString("ids", ids.joinToString("\n")).apply()
    }
}

/**
 * Resume point. For series: season/episode ([season]/[ep]); [next] = the previous episode has been watched to the end,
 * the next one is offered (position 0).
 */
data class Entry(
    val itemId: String, val title: String, val url: String, val pos: Long, val dur: Long, val ts: Long,
    val season: Int = 0, val ep: Int = 0, val next: Boolean = false
)

/**
 * Small log for troubleshooting "Continue watching" (Settings > Diagnostics): what was remembered, skipped and resumed.
 * Contains only titles and times, no addresses.
 */
object ResumeLog {
    val lines = mutableStateListOf<String>()
    private var loaded = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_resumelog")
    fun init(c: Context) {
        if (loaded) return
        loaded = true
        prefs(c).getString("l", null)?.split('\n')?.filter { it.isNotBlank() }?.let { lines.addAll(it) }
    }
    fun add(c: Context, msg: String) {
        init(c)
        val t = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.add(0, "$t  $msg")
        while (lines.size > 25) lines.removeAt(lines.lastIndex)
        prefs(c).edit().putString("l", lines.joinToString("\n")).apply()
    }
}

object WatchStore {
    val entries = mutableStateListOf<Entry>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_watch")

    fun init(c: Context) {
        if (entries.isNotEmpty()) return
        val s = prefs(c).getString("e", null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                entries.add(Entry(o.getString("i"), o.getString("t"), o.getString("u"), o.getLong("p"), o.getLong("d"), o.getLong("ts"),
                    o.optInt("s"), o.optInt("e"), o.optBoolean("n")))
            }
        } catch (e: Exception) {
        }
    }

    /**
     * Remembers the state. Watched to the end: for series [next] (the next episode) is offered, otherwise the entry disappears.
     * Under 30 seconds nothing changes – a short peek does not delete an old resume point.
     */
    fun save(c: Context, e: Entry, next: Entry? = null) {
        val fin = ResumeLogic.finished(e.pos, e.dur)
        val old = entries.firstOrNull { it.itemId == e.itemId }
        val what = e.title.take(40) + " #" + e.itemId.takeLast(4) + " " + (e.pos / 1000) + "s/" + (e.dur / 1000) + "s"
        if (!ResumeLogic.shouldSave(old != null, old?.season ?: 0, old?.ep ?: 0, e.season, e.ep, e.pos, fin)) {
            if (e.pos > 5000) ResumeLog.add(c, "übergangen: $what")
            return
        }
        if (e.pos > 5000 && (fin || old == null || old.season != e.season || old.ep != e.ep || e.pos - old.pos > 60_000))
            ResumeLog.add(c, (if (fin) "fertig: " else "gemerkt: ") + what + (if (fin && next != null) "  → als Nächstes S" + next.season + "E" + next.ep else ""))
        entries.removeAll { it.itemId == e.itemId }
        if (!fin) entries.add(0, e)
        else if (next != null) entries.add(0, next.copy(pos = 0L, dur = 0L, next = true))
        while (entries.size > 30) entries.removeAt(entries.lastIndex)
        persist(c)
    }

    /** Remove from "Continue watching" (menu key on the card). */
    fun remove(c: Context, itemId: String) {
        if (entries.removeAll { it.itemId == itemId }) persist(c)
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        for (x in entries) a.put(JSONObject().put("i", x.itemId).put("t", x.title).put("u", x.url)
            .put("p", x.pos).put("d", x.dur).put("ts", x.ts).put("s", x.season).put("e", x.ep).put("n", x.next))
        prefs(c).edit().putString("e", a.toString()).apply()
    }

    /**
     * What "Resume" plays: title and a CURRENT address from the loaded playlist
     * (providers change addresses on refresh; the stored one would then be dead).
     */
    fun target(c: Context, item0: Item, e: Entry): Pair<String, String> {
        // Series with a placeholder: if the episodes are already loaded (series opened), Resume uses them
        val item = LazySeries.full(item0)
        if (item.kind != Kind.SERIES) return e.title to item.url.ifBlank { e.url }
        var s = e.season; var n = e.ep
        if (s == 0 && n == 0) ResumeLogic.seasonEp(e.title)?.let { s = it.first; n = it.second }
        val ep = item.episodes.firstOrNull { it.season == s && it.num == n }
        ResumeLog.add(c, "Fortsetzen #" + item.id.takeLast(4) + ": Eintrag S" + e.season + "E" + e.ep + (if (e.next) " (nächste)" else "") + " bei " + (e.pos / 1000) + "s → " + (if (ep != null) "spielt S" + s + "E" + n else "Folge nicht gefunden, gespeicherte Adresse"))
        if (ep == null) return e.title to e.url
        val pref = VariantPref.get(c, item.id)
        return (item.title + " S" + s + "E" + n) to pickVariant(ep, pref.first, pref.second, pref.third, PlaybackPrefs.cap(c)).url
    }
}

/**
 * Progress per episode (for bars and "watched" in the episode list). One compact line per series;
 * the 150 most recently watched series are remembered.
 */
object EpProgress {
    private const val MAX_SERIES = 400
    private val cache = HashMap<String, LinkedHashMap<Long, ResumeLogic.EpPos>>()
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_eprog")

    @Synchronized
    fun of(c: Context, seriesId: String): Map<Long, ResumeLogic.EpPos> = load(c, seriesId)

    private fun load(c: Context, id: String): LinkedHashMap<Long, ResumeLogic.EpPos> =
        cache.getOrPut(id) { ResumeLogic.decode(prefs(c).getString(id, null)?.substringAfter('|')) }

    @Synchronized
    fun put(c: Context, seriesId: String, season: Int, ep: Int, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        val m = load(c, seriesId)
        val v = ResumeLogic.EpPos((posMs / 1000).toInt(), (durMs / 1000).toInt())
        val k = ResumeLogic.key(season, ep)
        val old = m[k]
        // Once seen stays seen (peeking again does not reset the check mark)
        if (old != null && old.done && !v.done) return
        if (old == v) return
        m[k] = v
        store(c, seriesId, m)
    }

    /** Length (seconds) of the placeholder progress that marks an episode as watched by hand. */
    private const val MARK_SECONDS = 60

    /**
     * Marks episodes ([keys] = [ResumeLogic.key]) as watched by hand, or resets them to "not watched".
     * A movie uses the key (0, 0).
     */
    @Synchronized
    fun mark(c: Context, seriesId: String, keys: Collection<Long>, seen: Boolean) {
        val m = load(c, seriesId)
        for (k in keys) {
            if (seen) m[k] = ResumeLogic.EpPos(MARK_SECONDS, MARK_SECONDS) else m.remove(k)
        }
        if (m.isEmpty()) {
            cache.remove(seriesId)
            prefs(c).edit().remove(seriesId).apply()
        } else store(c, seriesId, m)
    }

    private fun store(c: Context, seriesId: String, m: Map<Long, ResumeLogic.EpPos>) {
        val p = prefs(c)
        val e = p.edit().putString(seriesId, System.currentTimeMillis().toString() + "|" + ResumeLogic.encode(m))
        if (p.all.size > MAX_SERIES) {
            // forget the oldest series
            p.all.entries.filter { it.key != seriesId }
                .sortedBy { (it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L }
                .take(p.all.size - MAX_SERIES).forEach { e.remove(it.key); cache.remove(it.key) }
        }
        e.apply()
    }

    @Synchronized
    fun clear(c: Context) {
        cache.clear()
        prefs(c).edit().clear().apply()
    }
}

object Genres {
    private val NAMES = mapOf(
        28 to "Action", 12 to "Abenteuer", 35 to "Komödie", 80 to "Krimi", 99 to "Dokumentation",
        18 to "Drama", 10751 to "Familie", 14 to "Fantasy", 36 to "Historie", 27 to "Horror",
        10402 to "Musik", 9648 to "Mystery", 10749 to "Romantik", 878 to "Sci-Fi", 53 to "Thriller",
        10752 to "Krieg", 37 to "Western", 10759 to "Action", 10762 to "Kinder", 10763 to "Nachrichten",
        10764 to "Reality", 10765 to "Sci-Fi", 10766 to "Soap", 10767 to "Talk", 10768 to "Krieg"
    )

    fun categoriesOf(m: Meta): List<String> {
        val out = ArrayList<String>()
        val animation = 16 in m.genreIds
        if (animation && m.lang == "ja") out.add("Anime")
        else if (animation) out.add("Animation")
        for (g in m.genreIds) {
            if (g == 16) continue
            val n = NAMES[g] ?: continue
            if (n !in out) out.add(n)
        }
        return out
    }
}

object Classifier {
    /**
     * Limit for automatic genres: with very large libraries (over 60,000 movies and series – counted after the language filter)
     * querying all titles at TMDB would take hours and fill the working memory.
     * Then the app uses the provider's categories – without matching, fast and stable.
     */
    const val MAX_TITLES = 60_000
    fun supported(n: Int) = n <= MAX_TITLES

    /** True if the heap is more than 70 % full: then the matching is paused instead of crashing. */
    private fun lowMemory(): Boolean = Mem.heapTight(0.70)
    var progress by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var version by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var phase by mutableStateOf("Metadaten")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * Starts the detection for a (new) list. A running pass is first ended cleanly,
     * so that after a filter change the old list never keeps running or the new one is skipped.
     * Runs on a background thread, not on the main thread.
     */
    fun restart(ctx: Context, all: List<Item>) {
        val app = ctx.applicationContext
        val old = job
        job = scope.launch {
            old?.cancelAndJoin()
            if (!MetaRepo.enabled() || all.isEmpty()) return@launch
            run(app, all.filter { it.kind == Kind.SERIES } + all.filter { it.kind == Kind.MOVIE })
        }
    }

    private suspend fun run(ctx: Context, items: List<Item>) {
        if (!MetaRepo.enabled() || !supported(items.size)) return
        running = true
        try {
            // Load stored results in one go (instead of one file per title): only really new titles are queried
            MetaRepo.loadIndex(ctx)
            phase = "Metadaten"
            val todo = items.filter { MetaRepo.cached(it) == null && !MetaRepo.missed(it) }
            total = items.size
            progress = items.size - todo.size
            if (todo.isNotEmpty()) version++   // nothing new: no home rebuild
            var n = 0
            for (chunk in todo.chunked(12)) {
                if (lowMemory()) { MetaRepo.trim(); if (lowMemory()) { version++; return } }
                coroutineScope { chunk.map { async { MetaRepo.basic(ctx, it) } }.awaitAll() }
                progress += chunk.size
                n++
                if (n % 8 == 0) version++
                // Gentle on weak boxes: short pause between batches, and none at all while something plays
                kotlinx.coroutines.delay(150)
                while (AppNav.playing) kotlinx.coroutines.delay(2000)
                if (n % 200 == 0) MetaRepo.saveIndex(ctx)
            }
            if (todo.isNotEmpty()) { version++; MetaRepo.saveIndex(ctx) }
            phase = "Länder"
            val need = items.filter { MetaRepo.cached(it)?.countries?.isEmpty() == true }
            total = need.size
            progress = 0
            n = 0
            for (chunk in need.chunked(12)) {
                if (lowMemory()) { MetaRepo.trim(); if (lowMemory()) { version++; return } }
                coroutineScope { chunk.map { async { MetaRepo.fillCountry(ctx, it) } }.awaitAll() }
                progress += chunk.size
                n++
                if (n % 8 == 0) version++
                kotlinx.coroutines.delay(150)
                while (AppNav.playing) kotlinx.coroutines.delay(2000)
                if (n % 100 == 0) MetaRepo.saveIndex(ctx)
            }
            if (need.isNotEmpty()) version++
        } finally {
            running = false
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { MetaRepo.saveIndex(ctx) }
        }
    }
}
