package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Small error log for the diagnostics area. Deliberately stores ONLY short messages:
 * no addresses, no user names, no passwords.
 */
object Diag {
    var lastError by mutableStateOf<String?>(null)
    var lastErrorAt by mutableLongStateOf(0L)
    var errorCount by mutableLongStateOf(0L)

    fun report(msg: String) {
        lastError = msg.take(160)
        lastErrorAt = System.currentTimeMillis()
        errorCount++
    }
}
