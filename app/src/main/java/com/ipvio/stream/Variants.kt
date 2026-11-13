package com.ipvio.stream

import android.content.Context

object VariantPref {
    private fun prefs(c: Context) = Profiles.sp(c, "ipvio_variants")

    fun get(c: Context, id: String): Triple<String?, String?, String?> {
        val s = prefs(c).getString(id, "||") ?: "||"
        val p = s.split("|")
        return Triple(p.getOrNull(0)?.ifBlank { null }, p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2)?.ifBlank { null })
    }

    fun set(c: Context, id: String, q: String?, lang: String?, label: String?) {
        prefs(c).edit().putString(id, (q ?: "") + "|" + (lang ?: "") + "|" + (label ?: "")).apply()
    }
}
