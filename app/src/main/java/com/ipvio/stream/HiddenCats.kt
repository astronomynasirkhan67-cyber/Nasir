package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/** Hidden Live categories (per profile). They disappear from the category list and can be brought back via "Hidden". */
object HiddenCats {
    val list = mutableStateListOf<String>()
    private var inited = false
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_hidecats")

    fun init(c: Context) {
        if (inited) return
        inited = true
        list.addAll((prefs(c).getString("k", "") ?: "").split("\n").filter { it.isNotBlank() })
    }

    private fun save(c: Context) = prefs(c).edit().putString("k", list.joinToString("\n")).apply()

    fun hide(c: Context, name: String) { init(c); if (name !in list) list.add(name); save(c) }
    fun show(c: Context, name: String) { init(c); list.remove(name); save(c) }
    fun showAll(c: Context) { init(c); list.clear(); save(c) }
}
