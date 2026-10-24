package com.nasir.voicechat

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Msg(val fromUser: Boolean, val text: String, val fileUri: String? = null, val fileMime: String? = null)

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
    // id of the chosen chat model: "gemma4" or "qwen3"
    var modelId by mutableStateOf("gemma4")
    var tick by mutableStateOf(0)
    // Report about the previous crash (shown once after a crash so we know the real cause).
    var crashInfo by mutableStateOf("")
    // Question typed before choosing a picture (sent together with the picture).
    var imageQuestion by mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) { main.post { block() } }
}
