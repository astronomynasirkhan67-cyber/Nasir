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
    var lang by mutableStateOf("en")
    var handsFree by mutableStateOf(false)
    var codeMode by mutableStateOf(false)
    var typed by mutableStateOf("")
    var note by mutableStateOf("")
    var showSetup by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var llmUrl by mutableStateOf("")
    var hfToken by mutableStateOf("")
    var tick by mutableStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    fun post(block: () -> Unit) { main.post { block() } }
}
