package com.ipvio.stream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** General app settings: start, quit, refresh. Own file so they are handled separately on reset. */
object General {
    private const val PREFS = "ipvio_general"
    /** Refresh mode when the user never picked one: 2 = daily (see [refreshMode]). */
    const val DEFAULT_REFRESH_MODE = 2
    var autoStart by mutableStateOf(false)
    var startTab by mutableIntStateOf(0)
    var resumeLive by mutableStateOf(false)
    /** Resume the last watched movie / series at start at the remembered position. */
    var resumeVod by mutableStateOf(false)
    var confirmExit by mutableStateOf(true)
    /** Live TV: show the quality variants of one channel (8K/4K/FHD/HD/SD) as a single row. */
    var bundleLive by mutableStateOf(true)
    /** Playlist refresh: 0 = at every start, 1 = every X hours, 2 = daily, 3 = weekly, 4 = by hand only */
    var refreshMode by mutableIntStateOf(DEFAULT_REFRESH_MODE)
    var refreshInterval by mutableIntStateOf(12)
    var refreshHour by mutableIntStateOf(4)
    var refreshMinute by mutableIntStateOf(0)
    var refreshDow by mutableIntStateOf(java.util.Calendar.MONDAY)
    var lastRefresh by mutableStateOf(0L)
    /** Language: "" = device, otherwise code from Lang.CODES */
    var lang by mutableStateOf("")
    /** Time zone: "" = system time of the device */
    var tzId by mutableStateOf("")
    /** Time format: 0 = system, 1 = 24 hours, 2 = 12 hours */
    var timeFmt by mutableIntStateOf(0)
    var sys24 by mutableStateOf(true)
    var fontIdx by mutableIntStateOf(0)
    var textScaleIdx by mutableIntStateOf(1)
    /** Default aspect ratio in the player (index in [ASPECT_NAMES]). */
    var defaultAspect by mutableIntStateOf(0)
    /**
     * Aspect ratios (round 14). 0–2 differ only if the picture has a different aspect ratio than the TV
     * (with 16:9 channels there was therefore no visible difference). 3–5 always change the picture visibly.
     */
    val ASPECT_NAMES = listOf("Einpassen", "Bildschirm füllen", "Strecken", "Zoom 115 %", "Zoom 133 %", "4:3")
    /** Card size in the rows ("View" button at the top): 0 = small, 1 = normal, 2 = large. */
    var cardSize by mutableIntStateOf(2)
    /** Titles per row in rows, grid and categories ("View" button): 0 = automatic by card size. */
    var perRow by mutableIntStateOf(0)
    /** Titles per row in the cinema view (separate from the grid, 0 = automatic) */
    var kinoPerRow by mutableIntStateOf(0)
    /** Preview when highlighting a title in the rows: 0 = info on the poster (card stays, nothing shifts), 1 = wide scene image (old). */
    var previewStyle by mutableIntStateOf(0)
    /** Images of the movie/series cards: 0 = cover (portrait), 1 = scene image (landscape). */
    var cardArt by mutableIntStateOf(0)
    /** Switch time of the start banner in seconds, 0 = do not switch automatically */
    var heroSec by mutableIntStateOf(9)
    /** Clock next to the profile icon (default: on). */
    var showClock by mutableStateOf(true)
    /** Sports: show real results (unofficial ESPN source). When off, the sports area uses only playlist and TV guide. */
    var sportLive by mutableStateOf(true)
    /** Adjust screen (1.2): margin in percent left/right and top/bottom so nothing is cut off at the TV edge. */
    var fitX by mutableIntStateOf(0)
    var fitY by mutableIntStateOf(0)
    val FIT_STEPS = listOf(0, 1, 2, 3, 4, 5, 6, 8, 10)

    fun plan() = UpdatePlan(refreshMode, refreshInterval, refreshHour, refreshMinute, refreshDow)
    fun zone(): java.util.TimeZone = SettingsLogic.zoneOf(tzId)

    /**
     * Fonts (round 17): Outfit (default, built in, geometric and cinematic), Inter (built in, very readable at
     * a distance) and the system fonts of the device. Both built-in fonts: SIL Open Font License (OFL_*.txt in the project).
     */
    val FONTS = listOf("Outfit", "Inter", "System", "Kompakt", "Serif", "Leicht", "Monospace")
    private val FONT_FAMILIES = listOf(null, null, null, "sans-serif-condensed", "serif", "sans-serif-light", "monospace")
    private val familyCache = arrayOfNulls<FontFamily>(7)
    private val OUTFIT by lazy {
        FontFamily(Font(R.font.outfit_regular, FontWeight.Normal), Font(R.font.outfit_bold, FontWeight.Bold))
    }
    private val INTER by lazy {
        FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold),
            Font(R.font.inter_extrabold, FontWeight.ExtraBold)
        )
    }
    val TEXT_SCALES = listOf("Klein", "Normal", "Groß", "Sehr groß")
    private val TEXT_FACTORS = floatArrayOf(0.9f, 1f, 1.15f, 1.3f)
    fun textFactor(i: Int = textScaleIdx): Float = TEXT_FACTORS[i.coerceIn(0, TEXT_FACTORS.lastIndex)]

    /** Font family of the choice. These are system fonts every Android device brings along. */
    fun fontFamily(idx: Int = fontIdx): FontFamily? {
        val i = idx.coerceIn(0, FONTS.lastIndex)
        if (i == 0) return OUTFIT
        if (i == 1) return INTER
        val name = FONT_FAMILIES[i] ?: return null
        return familyCache[i] ?: FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
                .map { Font(DeviceFontFamilyName(name), it) }
        ).also { familyCache[i] = it }
    }

    /** Once per process: do not repeat start actions (play last channel) on every return. */
    @Volatile var startupHandled = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        val p = prefs(c)
        autoStart = p.getBoolean("auto_start", false)
        startTab = p.getInt("start_tab", 0).coerceIn(0, 4)
        resumeLive = p.getBoolean("resume_live", false)
        resumeVod = p.getBoolean("resume_vod", false)
        confirmExit = p.getBoolean("confirm_exit", true)
        bundleLive = p.getBoolean("bundle_live", true)
        // Users who never chose a mode get "daily": the first start still loads the list once (lastRefresh = 0),
        // later starts use the cache. Before 1.4 the default was "at every start", which re-downloaded the whole line each time.
        // One-time switch for existing installs: "at every start" (0, the old default) becomes "daily" (maintainer decision,
        // 12 Nov 2026). Runs once per profile; a later choice of "at every start" is kept.
        if (!p.getBoolean("refresh_daily_v2", false)) {
            val e = p.edit().putBoolean("refresh_daily_v2", true)
            if (p.getInt("refresh_mode", DEFAULT_REFRESH_MODE) == 0) e.putInt("refresh_mode", DEFAULT_REFRESH_MODE)
            e.apply()
        }
        refreshMode = p.getInt("refresh_mode", DEFAULT_REFRESH_MODE).coerceIn(0, 4)
        refreshInterval = p.getInt("refresh_interval", 12).coerceIn(1, 168)
        refreshHour = p.getInt("refresh_hour", 4).coerceIn(0, 23)
        refreshMinute = p.getInt("refresh_minute", 0).coerceIn(0, 59)
        refreshDow = p.getInt("refresh_dow", java.util.Calendar.MONDAY).coerceIn(1, 7)
        lastRefresh = p.getLong("last_refresh", 0L)
        lang = p.getString("lang", "") ?: ""
        tzId = p.getString("tz_id", "") ?: ""
        timeFmt = p.getInt("time_fmt", 0).coerceIn(0, 2)
        sys24 = android.text.format.DateFormat.is24HourFormat(c)
        // New font list (round 17): own key. Whoever had chosen another system font than "Default" before keeps it.
        fontIdx = if (p.contains("font2")) p.getInt("font2", 0).coerceIn(0, FONTS.lastIndex)
            else p.getInt("font", 0).let { old -> if (old in 1..4) old + 2 else 0 }
        textScaleIdx = p.getInt("text_scale", 1).coerceIn(0, TEXT_SCALES.lastIndex)
        defaultAspect = p.getInt("aspect", 0).coerceIn(0, ASPECT_NAMES.lastIndex)
        heroSec = p.getInt("hero_sec", 9).coerceIn(0, 60)
        showClock = p.getBoolean("show_clock", true)
        sportLive = p.getBoolean("sport_live", true)
        fitX = p.getInt("fit_x", 0).coerceIn(0, 10)
        fitY = p.getInt("fit_y", 0).coerceIn(0, 10)
        cardSize = p.getInt("card_size", 2).coerceIn(0, 2)
        perRow = p.getInt("per_row", 0).let { if (it == 0) 0 else it.coerceIn(3, 14) }
        kinoPerRow = p.getInt("kino_per_row", 0).let { if (it == 0) 0 else it.coerceIn(3, 14) }
        cardArt = p.getInt("card_art", 0).coerceIn(0, 1)
        previewStyle = p.getInt("preview_style", 0).coerceIn(0, 1)
        Parental.init(c)
        LangFilter.init(c)
        PlayerLocale.locale = Lang.locale()
    }

    fun setAutoStart(c: Context, v: Boolean) { autoStart = v; prefs(c).edit().putBoolean("auto_start", v).apply() }
    fun setStartTab(c: Context, v: Int) { startTab = v.coerceIn(0, 4); prefs(c).edit().putInt("start_tab", startTab).apply() }
    fun setResumeLive(c: Context, v: Boolean) { resumeLive = v; prefs(c).edit().putBoolean("resume_live", v).apply() }
    fun setResumeVod(c: Context, v: Boolean) { resumeVod = v; prefs(c).edit().putBoolean("resume_vod", v).apply() }
    fun setBundleLive(c: Context, v: Boolean) { bundleLive = v; prefs(c).edit().putBoolean("bundle_live", v).apply() }
    fun setConfirmExit(c: Context, v: Boolean) { confirmExit = v; prefs(c).edit().putBoolean("confirm_exit", v).apply() }
    fun setPlan(c: Context, mode: Int = refreshMode, interval: Int = refreshInterval, hour: Int = refreshHour, minute: Int = refreshMinute, dow: Int = refreshDow) {
        refreshMode = mode.coerceIn(0, 4); refreshInterval = interval.coerceIn(1, 168)
        refreshHour = hour.coerceIn(0, 23); refreshMinute = minute.coerceIn(0, 59); refreshDow = dow.coerceIn(1, 7)
        prefs(c).edit().putInt("refresh_mode", refreshMode).putInt("refresh_interval", refreshInterval).putInt("refresh_hour", refreshHour)
            .putInt("refresh_minute", refreshMinute).putInt("refresh_dow", refreshDow).apply()
        Updater.apply(c)
    }
    fun setLang(c: Context, v: String) { lang = v; prefs(c).edit().putString("lang", v).apply(); PlayerLocale.locale = Lang.locale() }
    fun setTz(c: Context, id: String) { tzId = id; prefs(c).edit().putString("tz_id", id).apply() }
    fun setTimeFmt(c: Context, v: Int) { timeFmt = v.coerceIn(0, 2); prefs(c).edit().putInt("time_fmt", timeFmt).apply() }
    fun setFont(c: Context, v: Int) { fontIdx = v.coerceIn(0, FONTS.lastIndex); prefs(c).edit().putInt("font2", fontIdx).apply() }
    fun setTextScale(c: Context, v: Int) { textScaleIdx = v.coerceIn(0, TEXT_SCALES.lastIndex); prefs(c).edit().putInt("text_scale", textScaleIdx).apply() }
    fun setAspect(c: Context, v: Int) { defaultAspect = v.coerceIn(0, ASPECT_NAMES.lastIndex); prefs(c).edit().putInt("aspect", defaultAspect).apply() }
    fun setKinoPerRow(c: Context, v: Int) { kinoPerRow = if (v == 0) 0 else v.coerceIn(3, 14); prefs(c).edit().putInt("kino_per_row", kinoPerRow).apply() }
    fun setPerRow(c: Context, v: Int) { perRow = if (v == 0) 0 else v.coerceIn(3, 14); prefs(c).edit().putInt("per_row", perRow).apply() }
    fun setCardView(c: Context, size: Int, art: Int) {
        cardSize = size.coerceIn(0, 2); cardArt = art.coerceIn(0, 1)
        prefs(c).edit().putInt("card_size", cardSize).putInt("card_art", cardArt).apply()
    }
    fun setPreviewStyle(c: Context, v: Int) { previewStyle = v.coerceIn(0, 1); prefs(c).edit().putInt("preview_style", previewStyle).apply() }
    fun setSportLive(c: Context, v: Boolean) { sportLive = v; prefs(c).edit().putBoolean("sport_live", v).apply() }
    fun setShowClock(c: Context, v: Boolean) { showClock = v; prefs(c).edit().putBoolean("show_clock", v).apply() }
    fun setFit(c: Context, x: Int = fitX, y: Int = fitY) {
        fitX = x.coerceIn(0, 10); fitY = y.coerceIn(0, 10)
        prefs(c).edit().putInt("fit_x", fitX).putInt("fit_y", fitY).apply()
    }
    fun setHeroSec(c: Context, v: Int) { heroSec = v.coerceIn(0, 60); prefs(c).edit().putInt("hero_sec", heroSec).apply() }
    fun markRefreshed(c: Context) { lastRefresh = System.currentTimeMillis(); prefs(c).edit().putLong("last_refresh", lastRefresh).apply() }

    fun resetAll(c: Context) {
        // Parental control (PIN) survives a reset, otherwise it could be bypassed with one click
        val keep = prefs(c).all.filterKeys { it.startsWith("parental_") }
        val e = prefs(c).edit().clear()
        for ((k, v) in keep) when (v) { is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v) }
        e.apply(); init(c); Updater.apply(c)
    }

    /**
     * Resets only the given preference keys to their defaults: removes them and reloads every value from storage
     * (the defaults are the ones in [init]). All other settings, including the parental control, keep their saved value.
     */
    fun resetKeys(c: Context, keys: Set<String>) {
        if (keys.isEmpty()) return
        val e = prefs(c).edit()
        keys.forEach { e.remove(it) }
        e.apply()
        init(c)
    }

    /** Asked by the BootReceiver after system start. */
    fun autoStartEnabled(c: Context): Boolean = prefs(c).getBoolean("auto_start", false)

    // ---- Storage ----
    fun sizeOf(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    class Area(val name: String, val note: String, val files: (Context) -> List<File>)

    val AREAS = listOf(
        Area("Titel-Infos", "Cover, Beschreibungen, Bewertungen") { listOf(File(it.filesDir, "meta3")) },
        Area("Besetzung", "Schauspieler, Regie, ähnliche Titel") { listOf(File(it.filesDir, "credits1")) },
        Area("Playlist-Zwischenspeicher", "Schneller Start ohne Wartezeit") { c -> c.filesDir.listFiles { f -> f.name.startsWith("pl_") }?.toList() ?: emptyList() },
        Area("Bilder", "Zwischengespeicherte Cover und Hintergründe") { listOf(File(it.cacheDir, "image_cache")) }
    )

    /** One-time: delete files of earlier versions (old IMDb data set, about 20 MB). Runs in the background. */
    fun cleanupLegacy(c: Context) {
        val p = prefs(c)
        if (p.getInt("cleanup_v", 0) >= 1) return
        listOf("imdb_ratings.bin", "imdb_ratings.bin.tmp").forEach { runCatching { File(c.filesDir, it).delete() } }
        p.edit().putInt("cleanup_v", 1).apply()
    }

    fun areaSize(c: Context, a: Area): Long = a.files(c).sumOf { sizeOf(it) }
    fun clearArea(c: Context, a: Area) { a.files(c).forEach { runCatching { it.deleteRecursively() } } }

    fun clearHistory(c: Context) {
        History.ids.clear()
        Profiles.sp(c, "ipvio_hist").edit().clear().apply()
    }

    fun clearResume(c: Context) {
        WatchStore.entries.clear()
        Profiles.sp(c, "ipvio_watch").edit().clear().apply()
        EpProgress.clear(c)
    }
}

/** Starts the app after the device is switched on if the user has allowed it in the settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        // Reminders for shows: set the alarm again after the restart
        runCatching { Reminders.schedule(c) }
        if (!General.autoStartEnabled(c) && !Boomer.isOn(c)) return
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}
