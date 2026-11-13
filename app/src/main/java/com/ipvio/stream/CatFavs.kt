package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

object CatFavs {
    val list = mutableStateListOf<String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_catfavs")

    fun init(c: Context) {
        if (inited) return
        inited = true
        list.addAll((prefs(c).getString("c", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    fun toggle(c: Context, name: String) {
        init(c)
        if (!list.remove(name)) list.add(0, name)
        prefs(c).edit().putString("c", list.joinToString("\n")).apply()
    }
}
