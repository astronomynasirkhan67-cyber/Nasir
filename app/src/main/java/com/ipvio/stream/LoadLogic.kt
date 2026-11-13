package com.ipvio.stream

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Rules for loading large playlists (without Android, testable):
 * memory estimate, language preselection, resuming an interrupted download and the remembered load job.
 */
object LoadLogic {
    /** Share of the Java memory the playlist may use at most (rest: UI, images, player, metadata). */
    const val SHARE = 0.40

    /**
     * Memory per byte of the list. Measured (283,000 titles, 230 MB): formerly 0.92, since episodes are stored compactly
     * ([EpisodeTable]) 0.61 – calculated with 0.65 (reserve).
     */
    const val FACTOR = 0.65

    /** How many bytes of playlist fit in memory (memory share [SHARE] divided by [FACTOR]). */
    fun budget(maxHeap: Long): Long = (maxHeap * SHARE / FACTOR).toLong()

    /**
     * Amount of data of the shown languages in bytes of the list (compared with [budget], which is already converted).
     */
    fun estimate(bytes: Map<String, Long>, hidden: Set<String>): Long =
        bytes.entries.sumOf { if (it.key in hidden) 0L else it.value }

    /** Does the selection fit? Without data (old caches) the answer is: yes. */
    fun fits(bytes: Map<String, Long>, hidden: Set<String>, budget: Long): Boolean =
        bytes.isEmpty() || estimate(bytes, hidden) <= budget

    /**
     * Preselection for the question on very large lists: the app's language, plus "no tag" if both fit together.
     * If the app language does not exist, the largest language that fits alone. Result = shown languages.
     */
    fun preselect(counts: Map<String, Int>, bytes: Map<String, Long>, appLang: String, budget: Long): Set<String> {
        val all = counts.keys
        fun fitsShown(s: Set<String>) = estimate(bytes, all - s) <= budget
        if (appLang in all) {
            val withUnknown = setOf(appLang, LangTagLogic.UNKNOWN).filter { it in all }.toSet()
            return if (withUnknown.size > 1 && fitsShown(withUnknown)) withUnknown else setOf(appLang)
        }
        val best = counts.entries.filter { it.key != LangTagLogic.UNKNOWN && fitsShown(setOf(it.key)) }.maxByOrNull { it.value }
        return if (best != null) setOf(best.key) else emptySet()
    }

    /** Device language (ISO, e.g. "de", "sr", "nb", "iw") → abbreviation of the language filters ([LangTagLogic]). */
    fun langCodeOf(system: String): String = when (val l = system.lowercase()) {
        "bs", "hr", "sr", "sl", "mk", "sh", "cnr" -> "yu"
        "no", "nn", "nb" -> "nb"
        "iw", "he" -> "he"
        "fil", "tl" -> "tl"
        "sk", "cs" -> "cs"
        else -> l
    }

    /**
     * Preselection of the language question: the device's language if the playlist has titles in it, otherwise English.
     * If neither exists, nothing is preselected. Result = shown languages.
     */
    fun systemPreselect(counts: Map<String, Int>, systemLang: String): Set<String> {
        val code = langCodeOf(systemLang)
        return when {
            (counts[code] ?: 0) > 0 -> setOf(code)
            (counts["en"] ?: 0) > 0 -> setOf("en")
            else -> emptySet()
        }
    }

    // ------------------------------------------------------------------ Resuming (HTTP range)

    const val APPEND = 0
    const val RESTART = 1
    const val ERROR = 2

    /** Strong validator for "If-Range": only a strong ETag (without W/) or Last-Modified. Without a validator no resuming. */
    fun validator(etag: String?, lastModified: String?): String? = when {
        !etag.isNullOrBlank() && !etag.startsWith("W/") -> etag
        !lastModified.isNullOrBlank() -> lastModified
        else -> null
    }

    /**
     * Headers to resume a download from [offset], or empty (then from the start).
     * "If-Range" ensures that changed content is sent completely anew instead of being assembled wrongly.
     */
    fun resumeHeaders(offset: Long, etag: String?, lastModified: String?): Map<String, String> {
        val v = validator(etag, lastModified) ?: return emptyMap()
        if (offset <= 0) return emptyMap()
        return mapOf("Range" to "bytes=$offset-", "If-Range" to v)
    }

    /** What to do with the response: append to the partial file, write from the start or error. */
    fun resumeAction(code: Int, contentRange: String?, offset: Long): Int = when {
        code == 206 && offset > 0 && rangeStart(contentRange) == offset -> APPEND
        code == 206 -> ERROR           // partial response that does not fit: better to retry than keep a broken list
        code in 200..299 -> RESTART
        else -> ERROR
    }

    /** Start byte from "Content-Range: bytes 1000-1999/5000", otherwise -1. */
    fun rangeStart(contentRange: String?): Long {
        val m = Regex("""bytes\s+(\d+)-""").find(contentRange ?: return -1) ?: return -1
        return m.groupValues[1].toLongOrNull() ?: -1
    }

    /** Total size from "Content-Range: bytes 1000-1999/5000", otherwise -1. */
    fun rangeTotal(contentRange: String?): Long =
        contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1

    /** Details about the partial file of an interrupted download. */
    data class PartInfo(val urlHash: Int, val etag: String?, val lastModified: String?, val charset: String) {
        fun encode(): String = kv("u" to urlHash.toString(), "e" to etag, "m" to lastModified, "c" to charset)

        companion object {
            fun decode(s: String?): PartInfo? {
                val o = unkv(s) ?: return null
                val u = o["u"]?.toIntOrNull() ?: return null
                return PartInfo(u, o["e"], o["m"], o["c"] ?: "UTF-8")
            }
        }
    }

    // ------------------------------------------------------------------ remembered load job

    const val RUNNING = "run"
    const val FAILED = "fail"
    /** From this many interrupted attempts on, downloading is no longer continued automatically. */
    const val MAX_AUTO = 3

    /**
     * A playlist that is currently being added. Stored BEFORE the download:
     * if the app crashes, power fails or it is closed, nothing is lost on the next start.
     */
    data class Pending(val url: String, val state: String, val attempts: Int, val msg: String?) {
        /** Ended in the middle of loading last time (crash, power loss, app closed). */
        val interrupted: Boolean get() = state == RUNNING
        val autoResume: Boolean get() = interrupted && attempts < MAX_AUTO

        fun encode(): String = kv("url" to url, "s" to state, "a" to attempts.toString(), "m" to msg)

        companion object {
            fun decode(s: String?): Pending? {
                val o = unkv(s) ?: return null
                val url = o["url"]?.takeIf { it.isNotBlank() } ?: return null
                return Pending(url, o["s"] ?: RUNNING, o["a"]?.toIntOrNull() ?: 1, o["m"])
            }
        }
    }

    /** New attempt: started by hand counts anew from 1, continued automatically keeps counting. */
    fun started(prev: Pending?, url: String, resumed: Boolean): Pending =
        Pending(url, RUNNING, if (resumed && prev?.url == url) prev.attempts + 1 else 1, null)

    /** Simple text form "key=value" per line (values encoded); without org.json so it runs in JVM tests. */
    private fun kv(vararg p: Pair<String, String?>): String =
        p.filter { it.second != null }.joinToString("\n") { it.first + "=" + URLEncoder.encode(it.second, "UTF-8") }

    private fun unkv(s: String?): Map<String, String>? {
        if (s.isNullOrBlank()) return null
        return try {
            s.split('\n').mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to URLDecoder.decode(l.substring(i + 1), "UTF-8")
            }.toMap()
        } catch (e: Exception) { null }
    }

    /** Xtream access from a get.php address (for prefilling the form), otherwise null. */
    fun xtreamParts(url: String): Triple<String, String, String>? {
        val m = Regex("""^(https?://[^/?#]+)/get\.php\?(.*)$""").find(url.trim()) ?: return null
        val q = m.groupValues[2].split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }.toMap()
        val user = q["username"] ?: return null
        val pass = q["password"] ?: return null
        return Triple(m.groupValues[1], user, pass)
    }
}

/**
 * Entertainment during very long loading (user's wish, deliberately in English):
 * from 5 minutes quotes of famous people about patience, from 10 minutes sarcastic remarks, from 15 minutes the hint about the language selection.
 */
object LoadFun {
    const val QUOTES_AFTER = 5 * 60_000L
    const val SARCASM_AFTER = 10 * 60_000L
    const val HINT_AFTER = 15 * 60_000L
    /** How long a saying stays displayed. */
    const val ROTATE = 15_000L

    val QUOTES = listOf(
        "Rome wasn't built in a day." to "Proverb",
        "Adopt the pace of nature: her secret is patience." to "Ralph Waldo Emerson",
        "The two most powerful warriors are patience and time." to "Leo Tolstoy",
        "He that can have patience can have what he will." to "Benjamin Franklin",
        "It does not matter how slowly you go as long as you do not stop." to "Confucius",
        "Slow and steady wins the race." to "Aesop",
        "Patience and fortitude conquer all things." to "Ralph Waldo Emerson",
        "Have patience. All things are difficult before they become easy." to "Saadi",
        "All good things come to those who wait." to "Proverb"
    )

    val SARCASM = listOf(
        "Seriously, how many titles do they have?",
        "Still loading. Your provider apparently owns every movie ever made.",
        "At this point the playlist needs its own zip code.",
        "Somewhere a server is sweating. Probably yours.",
        "You could have watched a whole episode by now. Just saying.",
        "Your provider called. They want to know why you need all of this.",
        "This isn't a playlist anymore. It's a lifestyle.",
        "Grab a snack. Actually, grab two.",
        "Have you tried turning your patience off and on again?",
        "Counting titles… we ran out of fingers a while ago."
    )

    /** 0 = nothing, 1 = quotes, 2 = sarcasm, 3 = additionally the hint about the language selection. */
    fun stage(elapsedMs: Long): Int = when {
        elapsedMs >= HINT_AFTER -> 3
        elapsedMs >= SARCASM_AFTER -> 2
        elapsedMs >= QUOTES_AFTER -> 1
        else -> 0
    }

    /** The saying for this moment (text, author or null) – changes every [ROTATE] ms; null before 5 minutes. */
    fun line(elapsedMs: Long): Pair<String, String?>? = when (stage(elapsedMs)) {
        0 -> null
        1 -> QUOTES[(((elapsedMs - QUOTES_AFTER) / ROTATE) % QUOTES.size).toInt()]
        else -> SARCASM[(((elapsedMs - SARCASM_AFTER) / ROTATE) % SARCASM.size).toInt()] to null
    }
}
