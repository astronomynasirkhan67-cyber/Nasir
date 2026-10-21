package com.nasir.voicechat

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// Saves a crash report to a file, and after a crash shows the cause on the next launch.
// No PC / logcat needed: the app tells you why it closed (Java crash, native crash, low memory).
object CrashInfo {
    private const val FILE = "last_crash.txt"
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText(("Thread: " + t.name + "\n" + sw.toString()).take(4000))
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }

    fun consume(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) {
                sb.append("Java crash:\n").append(f.readText().take(1200))
                f.delete()
            }
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { exitReason(ctx)?.let { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(it) } } catch (_: Throwable) {}
        }
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReason(ctx: Context): String? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
        val prefs = ctx.getSharedPreferences("nk_prefs", Context.MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong("exit_seen", 0L)) return null
        prefs.edit().putLong("exit_seen", info.timestamp).apply()
        val name = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the app: out of memory"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (inside the AI or speech engine)"
            ApplicationExitInfo.REASON_CRASH -> "App crash"
            ApplicationExitInfo.REASON_ANR -> "App stopped responding"
            ApplicationExitInfo.REASON_SIGNALED -> "App was killed by the system"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Used too many resources"
            else -> return null
        }
        return "Last close reason: $name\n${info.description ?: ""}".trim()
    }
}
