package com.ipvio.stream

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference

/**
 * Keeps the screen awake (FLAG_KEEP_SCREEN_ON on the activity's window) as long as at least one reason is active.
 * Used exclusively on the main thread (Compose effects, activity lifecycle).
 * The CPU and Wi-Fi lock during playback is handled by the player itself (WAKE_MODE_NETWORK).
 */
object ScreenAwake {
    private val tags = AwakeTags()
    private var window: WeakReference<Window>? = null

    /** true if the screen is currently kept awake. */
    var isOn by mutableStateOf(false)
        private set
    /** true if it should be kept awake but cannot be (no window, system error). */
    var unavailable by mutableStateOf(false)
        private set
    /** Reasons that are currently active (for diagnostics). */
    var reasons by mutableStateOf("")
        private set

    /** The last changes (on/off with time and reason) – visible in Settings → Diagnostics, for checking. */
    val history = ArrayDeque<Pair<Long, String>>()

    private fun note(on: Boolean) {
        history.addFirst(System.currentTimeMillis() to (if (on) "an (" + reasons + ")" else "aus"))
        while (history.size > 6) history.removeLast()
    }

    fun attach(a: Activity) { window = WeakReference(a.window); apply() }

    fun detach(a: Activity) {
        if (window?.get() === a.window) {
            runCatching { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            window = null
            isOn = false
        }
    }

    fun set(tag: String, on: Boolean) { tags.set(tag, on); apply() }

    /** Sets the window flag to match the state. Also call after returning from standby (in case the system dropped it). */
    fun apply() {
        reasons = tags.names().joinToString(", ")
        val want = tags.active
        val w = window?.get()
        if (w == null) { if (isOn) note(false); isOn = false; unavailable = want; return }
        try {
            if (want) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val now = want && (w.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            if (now != isOn) note(now)
            isOn = now
            unavailable = want && !isOn
        } catch (e: Exception) {
            isOn = false
            unavailable = want
        }
    }
}

/** Keeps the screen awake as long as [active] holds and the spot is displayed. It is released safely on leaving. */
@Composable
fun KeepScreenOn(tag: String, active: Boolean) {
    DisposableEffect(tag, active) {
        ScreenAwake.set(tag, active)
        onDispose { ScreenAwake.set(tag, false) }
    }
}
