package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Resets settings to their defaults, either for one settings tab or for all of them.
 * Only preferences are reset. Playlists, favorites, history, saved positions, API keys, the parental PIN,
 * the custom XMLTV address and the reminder list stay as they are.
 * Which keys belong to which tab is defined in [SettingsResetLogic].
 */
object SettingsReset {
    private const val DEFAULT_REMINDER_LEAD_MIN = 5

    /** Resets only the settings of one tab (tab ids: see [SettingsResetLogic]). Other tabs are not touched. */
    fun page(ctx: Context, page: Int) {
        if (!SettingsResetLogic.hasReset(page)) return
        AppSettings.resetKeys(ctx, SettingsResetLogic.appSettingsKeys(page))
        General.resetKeys(ctx, SettingsResetLogic.generalKeys(page))
        // Settings of this tab that live in their own stores
        when (page) {
            SettingsResetLogic.GENERAL -> resetPhoneOrientation(ctx)
            SettingsResetLogic.VIEW -> {
                SidebarPrefs.set(ctx, SidebarPrefs.LEFT, false)
                BrowsePrefs.set(ctx, true)
                CatPrefs.set(ctx, true)
            }
            SettingsResetLogic.PLAYER -> {
                DualPrefs.init(ctx)
                DualPrefs.set(ctx, split = 0, size = 1, corner = 0)
                PlaybackPrefs.init(ctx)
                PlaybackPrefs.setQuality(ctx, QualityLogic.AUTO)
                PlaybackPrefs.setFallback(ctx, true)
                DeadLinks.setHide(ctx, false)
            }
            SettingsResetLogic.GENRES -> {
                IgnoredGroups.init(ctx)
                IgnoredGroups.clear(ctx)
            }
            SettingsResetLogic.LIVE -> {
                LiveSettings.init(ctx)
                LiveSettings.setTimeshift(ctx, false)
                LiveSettings.setBufferMb(ctx, 512)
                LiveSettings.setRecDir(ctx, 0)
                LiveSettings.setRecPad(ctx, 3)
                Reminders.setLead(ctx, DEFAULT_REMINDER_LEAD_MIN)
            }
        }
    }

    /**
     * Resets every settings tab. Runs the reset of each tab first, then clears what is left in the two
     * settings files (for example the playlist refresh plan). The parental PIN and the search history are kept.
     */
    fun all(ctx: Context) {
        for (p in SettingsResetLogic.RESETTABLE) page(ctx, p)
        AppSettings.resetAll(ctx)
        General.resetAll(ctx)
    }

    /** Phones and tablets: back to the default (landscape). Does nothing on TV devices. */
    private fun resetPhoneOrientation(ctx: Context) {
        val activity = generateSequence<Context>(ctx) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull() ?: return
        if (MobilePrefs.isMobile(activity)) MobilePrefs.set(activity, true)
    }
}
