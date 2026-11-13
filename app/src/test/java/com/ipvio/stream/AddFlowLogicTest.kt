package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test

class AddFlowLogicTest {
    @Test fun cancelAfterResumeReloadsWhenNothingWasShown() {
        assertTrue(AddFlowLogic.shouldReloadAfterAddClosed(true, false, true, false, false))
    }

    @Test fun cancelDoesNotReloadWhenListWasShownButIsEmptyByMode() {
        assertFalse(AddFlowLogic.shouldReloadAfterAddClosed(true, false, true, false, true))
    }

    @Test fun noReloadWhileLoadingOrStillAddingOrListPresent() {
        assertFalse(AddFlowLogic.shouldReloadAfterAddClosed(true, false, true, true, false))
        assertFalse(AddFlowLogic.shouldReloadAfterAddClosed(true, true, true, false, false))
        assertFalse(AddFlowLogic.shouldReloadAfterAddClosed(true, false, false, false, false))
        assertFalse(AddFlowLogic.shouldReloadAfterAddClosed(false, false, true, false, false))
    }

    @Test fun settingsPageStaysOnlyOnSettingsTabWithPlaylists() {
        assertTrue(AddFlowLogic.keepsSettingsPage(MenuLogic.SETTINGS, true))
        assertFalse(AddFlowLogic.keepsSettingsPage(MenuLogic.SETTINGS, false))
        assertFalse(AddFlowLogic.keepsSettingsPage(MenuLogic.HOME, true))
    }
}
