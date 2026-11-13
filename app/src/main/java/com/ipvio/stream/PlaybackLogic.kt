package com.ipvio.stream

/**
 * Rules around playback (without Android, testable):
 * which source with several qualities, when to fall back to a smaller one, subtitle automation, broken links.
 */
object QualityLogic {
    /** Setting "Quality with several sources". */
    const val AUTO = 0
    const val HIGHEST = 1
    const val MAX_FHD = 2
    const val MAX_HD = 3

    /** Rank of a source: 4K = 4, FHD = 3, HD = 2, otherwise (SD/unknown) = 1. */
    fun rank(q: String?): Int = when (q) { "4K" -> 4; "FHD", "2K" -> 3; "HD" -> 2; else -> 1 }

    /**
     * What this device handles well: 4K only with a 4K screen AND a hardware decoder for 4K (HEVC).
     * [learnedCap] = limit the app has learned because 4K stuttered here several times (0 = none).
     */
    fun deviceMax(displayHeight: Int, hevc4k: Boolean, learnedCap: Int): Int {
        val disp = when {
            displayHeight <= 0 -> 3            // unknown: Full HD
            displayHeight >= 2000 -> 4
            displayHeight >= 1000 -> 3
            displayHeight >= 700 -> 2
            else -> 1
        }
        var m = if (disp >= 4 && !hevc4k) 3 else disp
        if (learnedCap in 1..3) m = minOf(m, learnedCap)
        return m
    }

    /** Highest rank the automatic mode chooses. */
    fun cap(pref: Int, deviceMax: Int): Int = when (pref) {
        HIGHEST -> 4
        MAX_FHD -> 3
        MAX_HD -> 2
        else -> deviceMax
    }

    /**
     * Order of the sources: first the best up to the limit, then descending; sources above the limit last
     * (the smallest of them first) – if only 4K exists, 4K is played anyway.
     */
    fun order(qs: List<String?>, cap: Int): List<Int> =
        qs.indices.sortedWith(compareBy<Int>({ if (rank(qs[it]) <= cap) 0 else 1 }, { if (rank(qs[it]) <= cap) -rank(qs[it]) else rank(qs[it]) }))

    /**
     * Fallback source: not yet tried, never a larger quality.
     * Stutter ([sameFirst] = false): the next smaller quality, otherwise an equally good one.
     * Error ([sameFirst] = true, e.g. dead link): first another equally good source, otherwise the next smaller.
     * null = none left.
     */
    fun fallback(qs: List<String?>, current: Int, tried: Set<Int>, sameFirst: Boolean = false): Int? {
        val cr = rank(qs.getOrNull(current))
        val open = qs.indices.filter { it !in tried && it != current }
        val lower = open.filter { rank(qs[it]) < cr }.maxByOrNull { rank(qs[it]) }
        val same = open.firstOrNull { rank(qs[it]) == cr }
        return if (sameFirst) same ?: lower else lower ?: same
    }
}

/** Detect stutter: several rebuffering pauses shortly after one another (not triggered by seeking). */
object StallLogic {
    const val WINDOW = 120_000L
    const val COUNT = 3

    /** New list of pause times (only those within the time window). */
    fun add(stalls: List<Long>, now: Long): List<Long> = stalls.filter { now - it < WINDOW } + now

    /** Fall back to a smaller source? */
    fun tooMany(stalls: List<Long>, now: Long): Boolean = stalls.count { now - it < WINDOW } >= COUNT
}

/** Subtitle automation and language codes. */
object TrackLogic {
    const val SUB_OFF = 0
    const val SUB_ALWAYS = 1
    /** Only if the audio track is not in a language the person understands. */
    const val SUB_FOREIGN = 2

    private val ISO3 = mapOf(
        "ger" to "de", "deu" to "de", "eng" to "en", "tur" to "tr", "hrv" to "hr", "scr" to "hr", "srp" to "sr", "scc" to "sr",
        "bos" to "bs", "fre" to "fr", "fra" to "fr", "spa" to "es", "ita" to "it", "jpn" to "ja", "kor" to "ko", "por" to "pt",
        "rus" to "ru", "pol" to "pl", "ara" to "ar", "dut" to "nl", "nld" to "nl", "chi" to "zh", "zho" to "zh", "hin" to "hi",
        "gre" to "el", "ell" to "el", "alb" to "sq", "sqi" to "sq", "rum" to "ro", "ron" to "ro", "hun" to "hu", "cze" to "cs", "ces" to "cs"
    )

    /** Normalize language codes: "ger", "de-DE", "DE" → "de". Unknown/"und" → null. */
    fun norm(code: String?): String? {
        val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (c.isEmpty() || c == "und" || c == "mul" || c == "zxx") return null
        return ISO3[c] ?: c.takeIf { it.length == 2 } ?: c
    }

    private val LABEL_LANG = mapOf(
        "deutsch" to "de", "german" to "de", "ger" to "de", "deu" to "de", "de" to "de",
        "english" to "en", "englisch" to "en", "eng" to "en", "en" to "en",
        "japanese" to "ja", "japanisch" to "ja", "jpn" to "ja", "jap" to "ja", "ja" to "ja",
        "french" to "fr", "französisch" to "fr", "franzosisch" to "fr", "fra" to "fr", "fre" to "fr", "fr" to "fr",
        "spanish" to "es", "spanisch" to "es", "spa" to "es", "es" to "es",
        "italian" to "it", "italienisch" to "it", "ita" to "it", "it" to "it",
        "turkish" to "tr", "türkisch" to "tr", "turkisch" to "tr", "tur" to "tr", "tr" to "tr",
        "korean" to "ko", "koreanisch" to "ko", "kor" to "ko", "ko" to "ko",
        "russian" to "ru", "russisch" to "ru", "rus" to "ru", "ru" to "ru",
        "croatian" to "hr", "kroatisch" to "hr", "hrv" to "hr", "hr" to "hr",
        "serbian" to "sr", "serbisch" to "sr", "srp" to "sr", "sr" to "sr",
        "bosnian" to "bs", "bosnisch" to "bs", "bos" to "bs", "bs" to "bs",
        "polish" to "pl", "polnisch" to "pl", "pol" to "pl", "pl" to "pl",
        "arabic" to "ar", "arabisch" to "ar", "ara" to "ar", "ar" to "ar"
    )

    /**
     * Language of a track: from the language code, for "und"/empty (common with IPTV files) from the label
     * („Deutsch 5.1“, „German AC3“, „DE“). Unknown → null.
     */
    fun guess(language: String?, label: String?): String? {
        norm(language)?.let { return it }
        val l = label?.lowercase() ?: return null
        return l.split(Regex("[^a-zäöüß]+")).firstNotNullOfOrNull { LABEL_LANG[it] }
    }

    /** Preferred audio languages: remembered choice for this title, then first and second language from the settings. */
    fun audioPrefs(primary: String, secondary: String, remembered: String?): List<String> =
        listOfNotNull(norm(remembered), norm(primary), norm(secondary)).distinct()

    /** Should subtitles be on? [audio] = language of the chosen audio track. */
    fun subsWanted(mode: Int, audio: String?, subLang: String, primaryAudio: String): Boolean = when (mode) {
        SUB_OFF -> false
        SUB_ALWAYS -> true
        else -> {
            val a = norm(audio)
            // Language unknown: better show nothing
            a != null && a !in setOfNotNull(norm(subLang), norm(primaryAudio))
        }
    }

    /** Remembered track choice per title: "audio|subtitle", subtitle "-" = off. */
    fun encode(audio: String?, text: String?): String = (audio ?: "") + "|" + (text ?: "-")

    fun decode(s: String?): Pair<String?, String?>? {
        if (s.isNullOrBlank() || '|' !in s) return null
        val a = s.substringBefore('|').ifBlank { null }
        val t = s.substringAfter('|').let { if (it == "-") null else it.ifBlank { null } }
        return a to t
    }
}

/** Broken links: when an address counts as broken. */
object DeadLinkLogic {
    /** Broken: immediately on "no longer exists" (404/410), otherwise after two failed attempts on different days/times. */
    fun dead(fails: Int, permanent: Boolean): Boolean = permanent || fails >= 2

    /**
     * Does this error count against the address? Not for access/subscription/connection limit/server problem (affects all titles),
     * not for decoder problems (the device, not the link) and not without internet.
     */
    fun counts(status: Int, decoderProblem: Boolean, online: Boolean): Boolean = when {
        !online || decoderProblem -> false
        status == 401 || status == 403 || status == 429 || status == 458 || status == 509 -> false
        status in 500..599 -> false
        else -> true
    }

    fun permanent(status: Int): Boolean = status == 404 || status == 410

    /** „Fehlversuche|Zeit|dauerhaft“ */
    fun encode(fails: Int, ts: Long, perm: Boolean) = "$fails|$ts|${if (perm) 1 else 0}"

    fun decode(s: String?): Triple<Int, Long, Boolean>? {
        val p = s?.split('|') ?: return null
        if (p.size != 3) return null
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toLongOrNull() ?: 0L, p[2] == "1")
    }
}

/**
 * Plausibility of the skip times from TheIntroDB (user-contributed data, partly wrong or incomplete).
 * Returns the cleaned section (start, end in ms) or null if it does not fit.
 * [start]/[end] = null means: missing in the data. [dur] = length of the episode (0 = unknown).
 */
object SegLogic {
    const val INTRO = 0
    const val RECAP = 1
    const val CREDITS = 2

    fun clean(kind: Int, start: Long?, end: Long?, dur: Long): Pair<Long, Long>? {
        val d = if (dur > 0) dur else Long.MAX_VALUE / 4
        when (kind) {
            INTRO, RECAP -> {
                // Intro/recap need an end (otherwise the button would reach to the end of the episode)
                val e = end ?: return null
                val s = start ?: 0L
                val len = e - s
                if (len < 5_000 || len > (if (kind == INTRO) 180_000 else 240_000)) return null
                if (e > d + 5_000) return null
                // Intro at most in the first half, recap in the first third of the episode
                if (dur > 0 && s > dur * (if (kind == INTRO) 0.5 else 0.35)) return null
                return s to minOf(e, d)
            }
            CREDITS -> {
                val s = start ?: return null
                val e = end ?: d
                if (e - s < 5_000) return null
                // Credits only in the last quarter of the episode
                if (dur > 0 && (s < dur * 0.75 || s >= dur)) return null
                return s to minOf(e, d)
            }
        }
        return null
    }
}
