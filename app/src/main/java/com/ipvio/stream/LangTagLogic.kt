package com.ipvio.stream

/**
 * Detects the language of an entry from the provider's tags (without Android, testable).
 *
 * IPTV providers almost always mark the audio track in the category or in the name, e.g. "DE | Action",
 * "|TR| Filmler", "VOD - EN - Comedy", "Netflix [GER]", "TÜRK FILMLERI" or "DE: Das Erste HD".
 * That is the language of the audio track (dubbed into German), not the country of origin of the movie.
 * The real audio track can only be read when playing – for 100,000+ titles the tag is the only way.
 */
object LangTagLogic {
    const val UNKNOWN = "?"
    /** Own "language" for adult categories (Wishbox): they no longer land in a real language or in "untagged". */
    const val ADULT = "xxx"

    /**
     * Content mode of a playlist (see PlaylistMode) travels with the hidden languages as two pseudo codes. That way it is
     * applied while parsing and while reading the cache, and a changed mode makes the stored cache (which remembers its
     * hidden set) invalid, so the list is parsed again from the stored file.
     */
    const val NO_LIVE = "~live"
    const val NO_VOD = "~vod"
    val PSEUDO = setOf(NO_LIVE, NO_VOD)

    /** Is an entry of this language and kind left out? */
    fun hides(hidden: Set<String>, lang: String, kind: Kind): Boolean =
        lang in hidden || (if (kind == Kind.LIVE) NO_LIVE in hidden else NO_VOD in hidden)

    /** Abbreviation → language. Ambiguous abbreviations (IN, AL, NO, CA, SE, BE, MY) deliberately left out: "beIN Sports", "Al Jazeera" … */
    private val CODES = mapOf(
        "DE" to "de", "GER" to "de", "DEU" to "de", "AT" to "de", "CH" to "de",
        "EN" to "en", "ENG" to "en", "UK" to "en", "GB" to "en", "US" to "en", "USA" to "en",
        "TR" to "tr", "TUR" to "tr",
        "AR" to "ar", "ARA" to "ar", "ARB" to "ar",
        "FR" to "fr", "FRA" to "fr", "FRE" to "fr",
        "ES" to "es", "ESP" to "es", "SPA" to "es", "LAT" to "es", "MX" to "es",
        "IT" to "it", "ITA" to "it",
        "NL" to "nl", "NED" to "nl", "DUT" to "nl",
        "PL" to "pl", "POL" to "pl",
        "PT" to "pt", "POR" to "pt", "BR" to "pt",
        "RU" to "ru", "RUS" to "ru",
        "HIN" to "hi",
        "JP" to "ja", "JPN" to "ja", "JAP" to "ja",
        "KR" to "ko", "KOR" to "ko",
        "CN" to "zh", "CHN" to "zh", "ZH" to "zh",
        "ALB" to "sq",
        "EXYU" to "yu", "YU" to "yu", "HR" to "yu", "RS" to "yu", "SRB" to "yu", "BA" to "yu", "BIH" to "yu", "SI" to "yu", "MK" to "yu",
        "GR" to "el", "GRE" to "el",
        "RO" to "ro", "ROM" to "ro",
        "HU" to "hu", "HUN" to "hu",
        "CZ" to "cs", "SK" to "cs",
        "SWE" to "sv", "DK" to "da", "DAN" to "da", "NOR" to "nb", "FI" to "fi", "FIN" to "fi",
        "IR" to "fa", "PER" to "fa",
        "KURD" to "ku",
        "IL" to "he", "HEB" to "he",
        "UA" to "uk", "UKR" to "uk",
        "BG" to "bg", "BUL" to "bg",
        "AFG" to "fa", "PK" to "ur", "URD" to "ur",
        "VN" to "vi", "TH" to "th", "PH" to "tl"
    )

    /** Whole words → language. */
    private val WORDS = mapOf(
        "GERMAN" to "de", "GERMANY" to "de", "DEUTSCH" to "de", "DEUTSCHE" to "de", "DEUTSCHLAND" to "de", "ALLEMAGNE" to "de",
        "ENGLISH" to "en",
        "TURK" to "tr", "TURKISH" to "tr", "TURKIYE" to "tr", "TÜRK" to "tr", "TÜRKIYE" to "tr", "TÜRKİYE" to "tr", "TÜRKÇE" to "tr",
        "ARABIC" to "ar", "ARAB" to "ar",
        "FRANCE" to "fr", "FRENCH" to "fr", "FRANÇAIS" to "fr", "FRANCAIS" to "fr",
        "SPAIN" to "es", "SPANISH" to "es", "ESPAÑA" to "es", "ESPANA" to "es", "LATINO" to "es", "ESPAÑOL" to "es",
        "ITALIA" to "it", "ITALY" to "it", "ITALIAN" to "it", "ITALIANO" to "it",
        "POLSKA" to "pl", "POLAND" to "pl", "POLISH" to "pl",
        "PORTUGAL" to "pt", "BRASIL" to "pt", "BRAZIL" to "pt",
        "RUSSIA" to "ru", "RUSSIAN" to "ru",
        "INDIA" to "hi", "HINDI" to "hi", "INDIAN" to "hi",
        "JAPAN" to "ja", "JAPANESE" to "ja",
        "KOREA" to "ko", "KOREAN" to "ko",
        "CHINA" to "zh", "CHINESE" to "zh",
        "NETHERLANDS" to "nl", "NEDERLAND" to "nl", "DUTCH" to "nl",
        "ALBANIA" to "sq", "SHQIP" to "sq", "SHQIPTARE" to "sq",
        "BALKAN" to "yu",
        "GREECE" to "el", "GREEK" to "el",
        "ROMANIA" to "ro", "ROMANIAN" to "ro",
        "KURDISH" to "ku", "KURDI" to "ku", "KURDISTAN" to "ku",
        "PERSIAN" to "fa", "IRAN" to "fa", "AFGHAN" to "fa",
        "UKRAINE" to "uk", "BULGARIA" to "bg", "HUNGARY" to "hu",
        "SWEDEN" to "sv", "DENMARK" to "da", "NORWAY" to "nb", "FINLAND" to "fi",
        "PAKISTAN" to "ur", "VIETNAM" to "vi", "THAILAND" to "th", "PHILIPPINES" to "tl"
    )

    /** Display names in the respective language itself, so everyone recognizes their language immediately. */
    val NAMES = linkedMapOf(
        "de" to "Deutsch", "en" to "English", "tr" to "Türkçe", "ar" to "العربية", "fr" to "Français",
        "es" to "Español", "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski", "pt" to "Português",
        "ru" to "Русский", "hi" to "हिन्दी", "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "sq" to "Shqip",
        "yu" to "Ex-YU / Balkan", "el" to "Ελληνικά", "ro" to "Română", "hu" to "Magyar", "cs" to "Čeština",
        "sv" to "Svenska", "da" to "Dansk", "nb" to "Norsk", "fi" to "Suomi", "fa" to "فارسی", "ku" to "Kurdî",
        "he" to "עברית", "uk" to "Українська", "bg" to "Български", "ur" to "اردو", "vi" to "Tiếng Việt",
        "th" to "ไทย", "tl" to "Filipino", ADULT to "18+"
    )

    fun name(code: String): String = NAMES[code] ?: code.uppercase()

    private val SPLIT = Regex("""[^\p{L}0-9]+""")

    /** Language from a text or null. Checks the first two words, the last word and known language words. */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val t = text.uppercase().split(SPLIT).filter { it.isNotEmpty() }
        if (t.isEmpty()) return null
        for (w in t.take(2)) CODES[w]?.let { return it }
        if (t.size > 1) CODES[t.last()]?.let { return it }
        for (w in t) WORDS[w]?.let { return it }
        return null
    }

    // Tag at the start ("DE | Title", "[DE] Title", "DE: Title", "DE - Title") or at the end ("Title [DE]", "Title (GER)")
    private val TAG_START = Regex("""^\W*([A-Za-z]{2,4})\s*([|:\]]|[-–]\s)""")
    private val TAG_END = Regex("""[\[(|]\s*([A-Za-z]{2,4})\s*[\])|]?\s*$""")
    /** Abbreviations that are also English words ("IT – Welcome to Derry", "Us - …"): valid only with | : or brackets. */
    private val WORDLIKE = setOf("IT", "US", "AT", "UK", "DE", "ES")

    private val DASH_TAG = Regex("""^\s*([A-Za-z]{2,4})\s*[-–]\s+""")

    /** Removes a language tag with a dash at the start ("EN - Title" → "Title") so title and TMDB search are clean. */
    fun stripDashTag(name: String): String {
        val m = DASH_TAG.find(name) ?: return name
        val code = m.groupValues[1].uppercase()
        return if (code in CODES && code !in WORDLIKE) name.substring(m.range.last + 1) else name
    }

    /** Language from an explicit tag in the (uncleaned) movie or series title, otherwise null. */
    fun detectTagged(name: String): String? {
        TAG_START.find(name)?.let { m ->
            val code = m.groupValues[1].uppercase()
            val dash = m.groupValues[2].trim().let { it == "-" || it == "–" }
            if (!(dash && code in WORDLIKE)) CODES[code]?.let { return it }
        }
        TAG_END.find(name)?.let { m -> CODES[m.groupValues[1].uppercase()]?.let { return it } }
        return null
    }

    /**
     * Language of an entry: first from the category (reliable), for live channels otherwise from the channel name,
     * for movies and series only from an explicit tag in the playlist's original name ("DE | …", "… [GER]").
     */
    fun of(group: String, title: String, live: Boolean): String =
        if (ParentalLogic.isAdult(group, "")) ADULT
        else detect(group) ?: (if (live) detect(title) else detectTagged(title)) ?: UNKNOWN

    /** Store hidden languages as text and read them again. */
    fun encode(hidden: Set<String>): String = hidden.sorted().joinToString(",")
    fun decode(s: String?): Set<String> = (s ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
