package com.ipvio.stream

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Phone and tablet support. Phones follow the device orientation by default; users can opt into
 * landscape mode in Settings > General. TV devices keep their existing behavior.
 */
object MobilePrefs {
    private const val PREFS = "ipvio_mobile"
    var landscape by mutableStateOf(false)
        private set

    /** Phone/tablet instead of TV (no Leanback, touch present). */
    fun isMobile(c: Context): Boolean {
        val pm = c.packageManager
        val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only") ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
        return !tv && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun apply(a: Activity) {
        landscape = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("landscape", false)
        if (!isMobile(a)) return
        a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun set(a: Activity, v: Boolean) {
        landscape = v
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("landscape", v).apply()
        apply(a)
    }
}
