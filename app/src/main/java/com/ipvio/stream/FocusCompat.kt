package com.ipvio.stream

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester

// The newer onEnter/onExit callbacks cannot redirect focus to a given FocusRequester, so these two
// helpers keep the established enter/exit behaviour in one place and hide the deprecation warning.

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
fun FocusProperties.enterTo(target: (FocusDirection) -> FocusRequester) {
    enter = target
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
fun FocusProperties.exitTo(target: (FocusDirection) -> FocusRequester) {
    exit = target
}
