package com.nasir.voicechat

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Msg(val fromUser: Boolean, val text: String)

class AppState {
    val messages = mutableStateListOf<Msg>()
    var status by mutableStateOf("Ready")
    // "en" = English, "ur" = Urdu
    var lang by mutableStateOf("en")
    var draft by mutableStateOf("")
    var responding by mutableStateOf(false)
    var handsFree by mutableStateOf(false)
    var note by mutableStateOf("")
    var showSetup by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var gemmaReady by mutableStateOf(false)
    var tick by mutableStateOf(0)
    // Report about the previous crash (shown once after a crash so we know the real cause).
    var crashInfo by mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) { main.post { block() } }
}
