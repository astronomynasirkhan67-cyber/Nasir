package com.ipvio.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object Trending {
    private val client = Net.base
    private var cache: Set<String>? = null

    fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    suspend fun titles(): Set<String> {
        cache?.let { return it }
        if (!MetaRepo.enabled()) return emptySet()
        val out = HashSet<String>()
        withContext(Dispatchers.IO) {
            for (type in listOf("movie", "tv")) for (page in 1..3) {
                try {
                    val url = "https://api.themoviedb.org/3/trending/$type/week?api_key=${Config.TMDB_KEY}&language=de-DE&page=$page"
                    val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string() else null
                    } ?: continue
                    val a = JSONObject(body).optJSONArray("results") ?: continue
                    for (x in 0 until a.length()) {
                        val o = a.getJSONObject(x)
                        for (k in listOf("title", "name", "original_title", "original_name")) {
                            val t = o.optString(k)
                            if (t.isNotBlank()) out.add(norm(t))
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        if (out.isNotEmpty()) cache = out
        return out
    }
}
