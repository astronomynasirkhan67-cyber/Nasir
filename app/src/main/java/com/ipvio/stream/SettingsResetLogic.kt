package com.ipvio.stream

/**
 * Which stored preference keys belong to which settings tab (tab ids = position in the settings list).
 *
 * A tab reset removes only the keys of its own tab, so resetting one tab can never change another tab
 * (before, every tab ran the same global reset and also wiped the design).
 * Pure Kotlin without Android imports, so it can be unit tested.
 *
 * Only preferences are listed here. User data (playlists, favorites, history, API keys, parental PIN,
 * the custom XMLTV address, reminders) is never reset by a tab reset.
 */
object SettingsResetLogic {
    const val GENERAL = 0
    const val REGION = 1
    const val VIEW = 2
    const val DESIGN = 3
    const val MENU = 4
    const val PLAYER = 5
    const val AUDIO = 6
    const val GENRES = 7
    const val LIVE = 8

    /** Tabs that end with their own "reset this page" button. */
    val RESETTABLE: IntRange = GENERAL..LIVE

    fun hasReset(page: Int): Boolean = page in RESETTABLE

    /** Keys in the preference file `ipvio_settings` (object `AppSettings`), per tab. */
    private val appSettingsKeyMap: Map<Int, Set<String>> = mapOf(
        GENERAL to setOf("sys_kb"),
        VIEW to setOf("grid_cols", "grid_rows", "card_w"),
        DESIGN to setOf("look_accent", "look_custom", "look_custom2", "look_bg", "look_motion", "look_scale"),
        MENU to setOf("menu_order", "menu_hidden"),
        PLAYER to setOf(
            "seek_step", "skip_intro", "show_info", "auto_next", "hide_sec", "show_end", "auto_skip", "auto_outro",
            "clock_on", "clock_pos", "clock_opacity", "clock_size", "clock_bg", "clock_bg_op",
            "buf_mode", "buf_learned", "auto_fps"
        ),
        AUDIO to setOf(
            "audio_lang", "audio_lang2", "sub_lang", "sub_mode", "cc_pref",
            "sub_size", "sub_color", "sub_bg", "sub_edge"
        ),
        GENRES to setOf("ipvio_filter")
    )

    /** Keys in the preference file `ipvio_general` (object `General`), per tab. */
    private val generalKeyMap: Map<Int, Set<String>> = mapOf(
        GENERAL to setOf("auto_start", "start_tab", "resume_live", "resume_vod", "confirm_exit"),
        REGION to setOf("lang", "tz_id", "time_fmt"),
        VIEW to setOf("card_size", "card_art", "preview_style", "hero_sec", "per_row", "kino_per_row"),
        // "font" is the key of the old font list; init() reads it as long as "font2" is missing
        DESIGN to setOf("font2", "font", "text_scale", "show_clock", "fit_x", "fit_y"),
        PLAYER to setOf("aspect"),
        LIVE to setOf("bundle_live")
    )

    /** Keys of `ipvio_settings` that the reset of [page] removes. */
    fun appSettingsKeys(page: Int): Set<String> = appSettingsKeyMap[page] ?: emptySet()

    /** Keys of `ipvio_general` that the reset of [page] removes. */
    fun generalKeys(page: Int): Set<String> = generalKeyMap[page] ?: emptySet()
}
