package com.ipvio.stream

import java.net.URLEncoder

/** Own error message when loading the playlist (text is meant for the user and never contains the address). */
class PlaylistError(msg: String) : Exception(msg)

/** Validation of playlist inputs and understandable error texts (without Android, testable). */
object PlaylistLogic {
    /** First element per key, order is kept (e.g. the same movie in HD and 4K only once in the rows). */
    fun <T> firstPerKey(list: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>(list.size * 2)
        val out = ArrayList<T>(list.size)
        for (x in list) if (seen.add(key(x))) out.add(x)
        return out
    }

    private val HTTP_URL = Regex("""^https?://[^\s/?#:@]+(:\d{1,5})?([/?#]\S*)?$""", RegexOption.IGNORE_CASE)
    private const val MAX_LEN = 4096

    /** Cleaned address or null if it is not a valid http(s) address. Login data in the address is allowed. */
    fun normalizeUrl(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty() || u.length > MAX_LEN) return null
        // Stalker portal (portal address + MAC), see StalkerLogic
        if (StalkerLogic.isSource(u)) return StalkerLogic.parse(u)?.encode()
        // Uploaded playlist file in the app folder
        if (u.startsWith("file:///")) return u.takeIf { it.contains("/files/imports/") && it.endsWith(".m3u", true) && !it.contains("..") }
        val noCred = u.replaceFirst(Regex("""^(https?://)[^/@\s]*@""", RegexOption.IGNORE_CASE), "$1")
        return if (HTTP_URL.matches(noCred)) u else null
    }

    /** Xtream address from server, user and password (special characters are encoded) or null if details are missing. */
    fun xtreamUrl(host: String, user: String, pass: String): String? {
        var h = host.trim().trimEnd('/')
        val u = user.trim()
        val p = pass.trim()
        if (h.isEmpty() || u.isEmpty() || p.isEmpty()) return null
        if (!h.startsWith("http://", true) && !h.startsWith("https://", true)) h = "http://$h"
        if (normalizeUrl(h) == null) return null
        return "$h/get.php?username=${enc(u)}&password=${enc(p)}&type=m3u_plus&output=ts"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Text for an HTTP error response when loading the playlist. */
    fun httpError(code: Int): String = when (code) {
        401, 403 -> "Zugang abgelehnt ($code). Benutzername, Passwort oder Abo prüfen."
        404 -> "Playlist nicht gefunden (404). Adresse prüfen."
        429, 458, 509 -> "Der Anbieter begrenzt gerade die Verbindungen ($code). Später erneut versuchen."
        512 -> "Der Server des Anbieters lehnt die Verbindung ab (512). Meist ist die Server-Adresse nicht mehr aktuell oder gesperrt. Beim Anbieter nach der aktuellen Adresse fragen."
        in 500..599 -> "Der Anbieter hat gerade ein Problem ($code). Später erneut versuchen."
        else -> "Server antwortet mit Fehler $code."
    }

    /** Error text for the user. Never passes on the message of third-party libraries (it can contain address and password). */
    const val NO_SPACE = "Zu wenig freier Speicherplatz auf dem Gerät. Bitte Platz schaffen (z. B. unbenutzte Apps löschen) und erneut versuchen."

    /** Storage full (Android reports this as an IOException with "ENOSPC" or "No space left"). */
    fun noSpace(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    /** Is a retry worthwhile? Yes on a dropped connection or timeout, no on a wrong address, certificate, full storage. */
    fun retryable(e: Throwable): Boolean = when (e) {
        is PlaylistError -> false
        is java.net.UnknownHostException -> false
        is javax.net.ssl.SSLException -> false
        is java.net.ConnectException -> false
        else -> e is java.io.IOException && !noSpace(e)
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is PlaylistError -> e.message ?: "Laden fehlgeschlagen."
        is java.net.UnknownHostException -> "Server nicht gefunden. Adresse und Internetverbindung prüfen."
        is java.net.SocketTimeoutException -> "Zeitüberschreitung: Der Server antwortet nicht."
        is java.net.ConnectException -> "Keine Verbindung zum Server."
        is javax.net.ssl.SSLPeerUnverifiedException -> "Das Zertifikat des Anbieters passt nicht zur Server-Adresse. Beim Anbieter nach einer aktuellen Adresse fragen."
        is javax.net.ssl.SSLException -> "Sichere Verbindung fehlgeschlagen (Zertifikat)."
        is IllegalArgumentException -> "Ungültige Adresse."
        is OutOfMemoryError -> "Die Playlist ist zu groß für den Speicher dieses Geräts."
        is java.io.IOException -> if (noSpace(e)) NO_SPACE else "Verbindung unterbrochen. Bitte erneut versuchen."
        else -> "Laden fehlgeschlagen (" + e.javaClass.simpleName + ")."
    }
}

/**
 * Display title from the playlist: resolve the providers' HTML characters ("Asterix &amp; Obelix", "Christie&#039;s")
 * and remove a language tag with a vertical bar at the start ("|EN| Title", "EN| Title", "[EN] Title").
 * Without Android, testable. Fast for titles without "&" or "|"/"[" (almost all).
 */
object TitleText {
    /** Short version for the big picture at the top: at most [max] characters, shortened at a word boundary, with "…". */
    fun short(s: String, max: Int = 250): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        if (t.length <= max) return t
        val cut = t.substring(0, max - 1)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > max / 2) cut.substring(0, sp) else cut).trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    private val ENT = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")
    private val NAMED = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ccedil" to "ç", "ntilde" to "ñ"
    )
    private val LEAD = Regex("""^\s*(?:\|\s*[A-Za-z]{2,3}\s*\||[A-Za-z]{2,3}\s*\||\[\s*[A-Za-z]{2,3}\s*])\s*""")

    /** Resolve HTML characters (also double-encoded ones like "&amp;#039;"). Unknown ones stay. */
    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        var t = s
        repeat(2) {
            if (t.indexOf('&') < 0) return t
            t = ENT.replace(t) { m ->
                val k = m.groupValues[1]
                when {
                    k.startsWith("#x") || k.startsWith("#X") -> k.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                    k.startsWith("#") -> k.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
                    else -> NAMED[k] ?: m.value
                }
            }
        }
        return t
    }

    private fun cp(c: Int): String? = if (c in 32..0x10FFFF && c !in 0xD800..0xDFFF) String(Character.toChars(c)) else null

    /** Title for movies and series: resolve HTML characters, remove the tag "|EN|" at the start. */
    fun title(s: String): String {
        val d = decode(s)
        if (d.indexOf('|') < 0 && d.indexOf('[') < 0) return d
        val m = LEAD.find(d) ?: return d
        val rest = d.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) d else rest
    }
}
