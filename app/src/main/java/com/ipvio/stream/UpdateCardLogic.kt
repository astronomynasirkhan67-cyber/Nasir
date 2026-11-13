package com.ipvio.stream

import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Pure rules of the first-start update check card (T-014). No Android imports, so it can be unit tested.
 *
 * The card appears once, on the very first start without a playlist, runs `AppUpdate.check()` and shows the outcome:
 * checking / up to date / update available / error with "Retry". `UpdateCardHost` (UI) only maps these states to widgets.
 */
object UpdateCardLogic {
    enum class Phase { CHECKING, UP_TO_DATE, AVAILABLE, OFFLINE, ERROR }

    /** What the primary button of the card does in a given phase. */
    enum class Action { NONE, SHOW_UPDATE, RETRY }

    /** [version] is set for AVAILABLE, [message] for ERROR. */
    data class State(val phase: Phase, val version: String = "", val message: String = "")

    val START = State(Phase.CHECKING)

    private const val MAX_MESSAGE = 120

    /** Show only if the card was never decided before and there is no playlist yet (a real first start). */
    fun shouldShow(seen: Boolean, hasPlaylist: Boolean): Boolean = !seen && !hasPlaylist

    /** The "seen" flag is written on the first decision, whether the card is shown or skipped (existing installs). */
    fun shouldMarkSeen(seen: Boolean): Boolean = !seen

    /**
     * The card belongs to the first-start screen only (T-025). It goes away by itself as soon as the user has moved on:
     * a playlist is saved, a playlist is being loaded, or the demo mode ("Continue without playlist") is on.
     */
    fun leftFirstStart(hasPlaylist: Boolean, loading: Boolean, demo: Boolean): Boolean = hasPlaylist || loading || demo

    /** [foundVersion] = name of the new version, null = the installed version is current. */
    fun onResult(foundVersion: String?): State =
        if (foundVersion == null) State(Phase.UP_TO_DATE) else State(Phase.AVAILABLE, version = foundVersion)

    /** Network problems (no DNS, timeout, refused, TLS) are OFFLINE; everything else shows its short message. */
    fun onFailure(t: Throwable): State =
        if (t is IOException) State(Phase.OFFLINE)
        else State(Phase.ERROR, message = t.message.orEmpty().trim().ifEmpty { t.javaClass.simpleName }.take(MAX_MESSAGE))

    /** A cancelled check (the card left the screen) is not an error and must not change the state. */
    fun ignorable(t: Throwable): Boolean = t is CancellationException

    fun primary(phase: Phase): Action = when (phase) {
        Phase.AVAILABLE -> Action.SHOW_UPDATE
        Phase.OFFLINE, Phase.ERROR -> Action.RETRY
        Phase.CHECKING, Phase.UP_TO_DATE -> Action.NONE
    }
}
