package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class Person(val id: Int, val name: String, val role: String?, val photo: String?)
data class Credits(val cast: List<Person>, val directors: List<Person>, val similar: List<Ref>)
/** [from] = title from which the person was opened (always included on their page, round 14). */
data class PersonRef(val id: Int, val name: String, val photo: String?, val from: String? = null)
data class PersonInfo(
    val id: Int, val name: String, val bio: String?, val photo: String?,
    val born: String?, val place: String?, val dept: String?, val titles: List<Ref>
)

private fun JSONObject.s(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }

/** Cast, director, similar titles and person pages via TMDB (with the user's own TMDB key). */
object CreditsRepo {
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val TTL = 14L * 24 * 3600 * 1000
    private val client = Net.base
    private val mem = ConcurrentHashMap<String, Credits>()
    private val persons = ConcurrentHashMap<String, PersonInfo>()
    var lastError by mutableStateOf<String?>(null)

    /** Clear on memory shortage (everything is also available as a file). */
    fun trim() { mem.clear(); persons.clear() }

    /** A person only once (several roles merged) – duplicate IDs made the cast row crash. */
    private fun dedupe(list: List<Person>): List<Person> =
        CastLogic.mergeById(list, { it.id }) { a, b ->
            val roles = listOfNotNull(a.role, b.role).distinct()
            a.copy(role = if (roles.isEmpty()) null else roles.joinToString(" / "), photo = a.photo ?: b.photo)
        }

    private fun httpGet(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) { lastError = null; r.body?.string() } else { lastError = "TMDB antwortet mit Fehler ${r.code}"; null }
        }
    } catch (e: Exception) {
        lastError = "Keine Verbindung zu TMDB (${e.javaClass.simpleName})"
        null
    }

    private fun dir(c: Context) = File(c.filesDir, "credits1").also { it.mkdirs() }

    private fun refOf(o: JSONObject, tv: Boolean): Ref? {
        val id = o.optInt("id", -1)
        val t = o.s(if (tv) "name" else "title") ?: return null
        if (id < 0) return null
        return Ref(
            id, tv, t, o.s(if (tv) "original_name" else "original_title")?.takeIf { it != t },
            (o.s(if (tv) "first_air_date" else "release_date"))?.take(4), o.s("poster_path")?.let { IMG + "w342" + it }
        )
    }

    private fun toJson(c: Credits): JSONObject {
        fun pj(p: Person) = JSONObject().put("i", p.id).put("n", p.name).put("r", p.role).put("p", p.photo)
        fun rj(r: Ref) = JSONObject().put("i", r.tmdbId).put("tv", r.isTv).put("t", r.title).put("a", r.alt).put("y", r.year).put("p", r.poster)
        return JSONObject().put("ts", System.currentTimeMillis())
            .put("cast", JSONArray().also { a -> c.cast.forEach { a.put(pj(it)) } })
            .put("dir", JSONArray().also { a -> c.directors.forEach { a.put(pj(it)) } })
            .put("sim", JSONArray().also { a -> c.similar.forEach { a.put(rj(it)) } })
    }

    private fun fromJson(j: JSONObject): Credits {
        fun pl(k: String): List<Person> = j.optJSONArray(k)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Person(it.getInt("i"), it.getString("n"), it.s("r"), it.s("p")) }
        } ?: emptyList()
        val sim = j.optJSONArray("sim")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Ref(it.getInt("i"), it.getBoolean("tv"), it.getString("t"), it.s("a"), it.s("y"), it.s("p")) }
        } ?: emptyList()
        return Credits(dedupe(pl("cast")), dedupe(pl("dir")), sim.distinctBy { it.tmdbId })
    }

    suspend fun credits(ctx: Context, m: Meta): Credits? {
        if (m.tmdbId <= 0 || !MetaRepo.enabled()) return null
        val type = if (m.isTv) "tv" else "movie"
        val k = "$type${m.tmdbId}_${Lang.code()}"
        mem[k]?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = File(dir(ctx), k)
            try {
                if (f.exists()) {
                    val j = JSONObject(f.readText())
                    if (System.currentTimeMillis() - j.optLong("ts") < TTL) return@withContext fromJson(j).also { mem[k] = it }
                }
            } catch (e: Exception) {
            }
            val body = httpGet(
                "https://api.themoviedb.org/3/$type/${m.tmdbId}?api_key=${Config.TMDB_KEY}&language=${Lang.tmdb()}" +
                    "&append_to_response=credits,recommendations,similar"
            ) ?: return@withContext null
            try {
                val o = JSONObject(body)
                val cr = o.optJSONObject("credits")
                val cast = ArrayList<Person>()
                cr?.optJSONArray("cast")?.let { a ->
                    for (x in 0 until minOf(a.length(), 18)) {
                        val p = a.getJSONObject(x)
                        cast.add(Person(p.getInt("id"), p.optString("name"), p.s("character"), p.s("profile_path")?.let { IMG + "w185" + it }))
                    }
                }
                val dirs = ArrayList<Person>()
                if (m.isTv) o.optJSONArray("created_by")?.let { a ->
                    for (x in 0 until a.length()) {
                        val p = a.getJSONObject(x)
                        dirs.add(Person(p.getInt("id"), p.optString("name"), "Regie", p.s("profile_path")?.let { IMG + "w185" + it }))
                    }
                }
                cr?.optJSONArray("crew")?.let { a ->
                    for (x in 0 until a.length()) {
                        val p = a.getJSONObject(x)
                        if (p.optString("job") == "Director" && dirs.none { it.id == p.getInt("id") })
                            dirs.add(Person(p.getInt("id"), p.optString("name"), "Regie", p.s("profile_path")?.let { IMG + "w185" + it }))
                    }
                }
                val sim = ArrayList<Ref>()
                for (key in listOf("recommendations", "similar")) o.optJSONObject(key)?.optJSONArray("results")?.let { a ->
                    for (x in 0 until a.length()) {
                        val r = refOf(a.getJSONObject(x), m.isTv) ?: continue
                        if (r.tmdbId != m.tmdbId && sim.none { it.tmdbId == r.tmdbId }) sim.add(r)
                    }
                }
                val c = Credits(dedupe(cast), dedupe(dirs).take(4), sim)
                mem[k] = c
                try { f.writeText(toJson(c).toString()) } catch (e: Exception) { }
                c
            } catch (e: Exception) {
                lastError = "Besetzung nicht lesbar (${e.javaClass.simpleName})"
                null
            }
        }
    }

    suspend fun person(id: Int): PersonInfo? {
        val pk = id.toString() + Lang.code()
        persons[pk]?.let { return it }
        return withContext(Dispatchers.IO) {
            val base = "https://api.themoviedb.org/3/person/$id?api_key=${Config.TMDB_KEY}&append_to_response=combined_credits"
            val body = httpGet("$base&language=${Lang.tmdb()}") ?: return@withContext null
            try {
                val o = JSONObject(body)
                var bio = o.s("biography")
                if (bio == null) bio = httpGet("$base&language=en-US")?.let { JSONObject(it).s("biography") }
                val dept = o.s("known_for_department")
                data class Cand(val ref: Ref, val pop: Double)
                val cands = ArrayList<Cand>()
                val cc = o.optJSONObject("combined_credits")
                fun scan(arr: JSONArray?, crew: Boolean) {
                    if (arr == null) return
                    for (x in 0 until arr.length()) {
                        val e = arr.getJSONObject(x)
                        val mt = e.optString("media_type")
                        if (mt != "movie" && mt != "tv") continue
                        if (crew && e.optString("job") != "Director") continue
                        if (!crew && e.optString("character").contains("self", true)) continue
                        val g = e.optJSONArray("genre_ids")
                        var bad = false
                        if (g != null) for (y in 0 until g.length()) if (g.getInt(y) == 10767 || g.getInt(y) == 10763 || g.getInt(y) == 10764) bad = true
                        if (bad) continue
                        val r = refOf(e, mt == "tv") ?: continue
                        cands.add(Cand(r, e.optDouble("popularity", 0.0)))
                    }
                }
                if (dept == "Directing") { scan(cc?.optJSONArray("crew"), true); scan(cc?.optJSONArray("cast"), false) }
                else { scan(cc?.optJSONArray("cast"), false); scan(cc?.optJSONArray("crew"), true) }
                val titles = cands.distinctBy { it.ref.tmdbId.toString() + it.ref.isTv }.sortedByDescending { it.pop }.map { it.ref }.take(120)
                val bornRaw = o.s("birthday")
                val born = bornRaw?.let { b -> b.split("-").takeIf { it.size == 3 }?.let { "${it[2]}.${it[1]}.${it[0]}" } ?: b }
                val info = PersonInfo(id, o.optString("name"), bio, o.s("profile_path")?.let { IMG + "w342" + it }, born, o.s("place_of_birth"), dept, titles)
                persons[pk] = info
                info
            } catch (e: Exception) {
                null
            }
        }
    }
}
