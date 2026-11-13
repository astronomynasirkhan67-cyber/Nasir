package com.ipvio.stream

/**
 * Rules for when the screen must stay awake (without Android, testable).
 * Mandatory: while a stream plays or reloads, the screen must never turn off.
 * On pause, end, error and in the background it is released – no permanent lock when idle.
 */
object AwakeLogic {
    // Same values as androidx.media3.common.Player.STATE_*
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /**
     * true while playback runs or is loading (also after a reconnect).
     * playWhenReady = false means pause or sleep timer expired.
     */
    fun playerNeedsScreen(playWhenReady: Boolean, state: Int, hasError: Boolean, foreground: Boolean): Boolean =
        foreground && playWhenReady && !hasError && (state == STATE_BUFFERING || state == STATE_READY)
}

/**
 * Several places can keep the screen awake (player, loading the playlist).
 * The switch stays on as long as at least one of them needs it; no place turns off another's light.
 */
class AwakeTags {
    private val tags = LinkedHashSet<String>()

    /** Sets or removes a reason and returns whether the screen must stay awake afterwards. */
    fun set(tag: String, on: Boolean): Boolean {
        if (on) tags.add(tag) else tags.remove(tag)
        return tags.isNotEmpty()
    }

    val active: Boolean get() = tags.isNotEmpty()
    fun names(): List<String> = tags.toList()
}
