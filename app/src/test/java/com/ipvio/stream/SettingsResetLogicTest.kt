package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResetLogicTest {
    private val pages = SettingsResetLogic.RESETTABLE.toList()

    @Test fun resettablePages_areTheNineSettingsTabs() {
        assertEquals((0..8).toList(), pages)
        assertTrue(SettingsResetLogic.hasReset(SettingsResetLogic.DESIGN))
        // Playlists, profiles, about, reset-all and donate have no per-tab reset
        assertFalse(SettingsResetLogic.hasReset(9))
        assertFalse(SettingsResetLogic.hasReset(18))
        assertFalse(SettingsResetLogic.hasReset(19))
        assertFalse(SettingsResetLogic.hasReset(-1))
    }

    @Test fun noKey_belongsToTwoTabs() {
        // The bug: resetting one tab also reset other tabs. Every key must have exactly one owner per file.
        for (keysOf in listOf(SettingsResetLogic::appSettingsKeys, SettingsResetLogic::generalKeys)) {
            val seen = HashMap<String, Int>()
            for (p in pages) for (k in keysOf(p)) {
                val other = seen.put(k, p)
                assertTrue("key $k is owned by tabs $other and $p", other == null)
            }
        }
    }

    @Test fun genresReset_doesNotTouchDesign() {
        val design = SettingsResetLogic.appSettingsKeys(SettingsResetLogic.DESIGN) + SettingsResetLogic.generalKeys(SettingsResetLogic.DESIGN)
        val genres = SettingsResetLogic.appSettingsKeys(SettingsResetLogic.GENRES) + SettingsResetLogic.generalKeys(SettingsResetLogic.GENRES)
        assertTrue(design.isNotEmpty())
        assertTrue(genres.intersect(design).isEmpty())
    }

    @Test fun designReset_coversColorsFontsAndScreenFit() {
        val app = SettingsResetLogic.appSettingsKeys(SettingsResetLogic.DESIGN)
        val general = SettingsResetLogic.generalKeys(SettingsResetLogic.DESIGN)
        assertTrue(app.containsAll(listOf("look_accent", "look_custom", "look_custom2", "look_bg", "look_motion", "look_scale")))
        // "font" is the old font key; init() falls back to it while "font2" is missing
        assertTrue(general.containsAll(listOf("font2", "font", "text_scale", "show_clock", "fit_x", "fit_y")))
    }

    @Test fun userData_isNeverInATabReset() {
        val all = pages.flatMap { SettingsResetLogic.appSettingsKeys(it) + SettingsResetLogic.generalKeys(it) }
        // search history, the one-time migration versions and the parental control must survive a tab reset
        assertFalse(all.any { it == "search_hist" || it.endsWith("_ver") || it.startsWith("parental_") })
    }

    @Test fun unknownPage_hasNoKeys() {
        assertTrue(SettingsResetLogic.appSettingsKeys(9).isEmpty())
        assertTrue(SettingsResetLogic.generalKeys(-1).isEmpty())
    }
}
