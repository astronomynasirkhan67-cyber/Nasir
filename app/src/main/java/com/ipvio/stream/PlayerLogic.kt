package com.ipvio.stream

import java.util.Locale

/**
 * Pure helper functions of the player (without Compose) so they can be tested.
 * All names are deliberately "player"-specific so nothing collides with other files.
 */

/** 3725000 ms -> "01:02:05" or "02:05" */
fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Short form for jumps: 30000 -> "0:30", 160000 -> "2:40" */
fun fmtShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** "+2:40" or "−0:30" */
fun fmtDelta(ms: Long): String = (if (ms >= 0) "+" else "−") + fmtShort(kotlin.math.abs(ms))

/** Fast seeking: level 0..4 depending on how long a direction key is held. */
fun turboLevel(holdMs: Long): Int = when {
    holdMs < 500 -> 0
    holdMs < 1200 -> 1
    holdMs < 2200 -> 2
    holdMs < 3500 -> 3
    else -> 4
}

/** Jump size per step: level 0 = configured size, higher levels = fraction of the total length (e.g. 1/15). */
fun turboStepMs(level: Int, stepMs: Long, durMs: Long): Long {
    if (level <= 0) return stepMs
    val d = if (durMs > 0) durMs else 3_600_000L
    val frac = when (level) { 1 -> d / 60; 2 -> d / 30; 3 -> d / 15; else -> d / 8 }
    return maxOf(stepMs * (level + 1), frac)
}

/** Clock time (seconds since 1970) at which the movie ends at the current speed. */
fun endClockSec(nowMs: Long, remainMs: Long, speed: Float): Long {
    val sp = if (speed > 0f) speed else 1f
    return (nowMs + (remainMs.coerceAtLeast(0) / sp).toLong()) / 1000
}

fun qualityName(h: Int): String = when {
    h >= 2160 -> "4K"
    h >= 1440 -> "2K"
    h >= 1080 -> "FHD"
    h >= 720 -> "HD"
    else -> "SD"
}

fun rankQ(q: String): Int = when (q) { "4K" -> 4; "2K" -> 3; "FHD" -> 2; "HD" -> 1; else -> 0 }

fun codecName(mime: String?): String = when {
    mime == null -> ""
    mime.contains("avc") -> "H.264"
    mime.contains("hevc") -> "HEVC"
    mime.contains("av01") -> "AV1"
    mime.contains("vp9") -> "VP9"
    mime.contains("mpeg2") -> "MPEG-2"
    mime.contains("mp4a") -> "AAC"
    mime.contains("eac3") -> "E-AC-3"
    mime.contains("ac-3") -> "AC-3"
    else -> mime.substringAfter('/')
}

fun channelLabel(n: Int): String = when (n) {
    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
    else -> if (n > 0) "$n Kanäle" else ""
}

fun langLabel(code: String?): String? =
    code?.takeIf { it != "und" && it.isNotBlank() }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(PlayerLocale.locale).replaceFirstChar { c -> c.uppercase() } }

/** Language for display names (e.g. audio track languages); set by the language settings. */
object PlayerLocale {
    @Volatile var locale: Locale = Locale.GERMAN
}

/**
 * Cleans show data from the provider: sorts, discards broken times and makes sure
 * that two shows never overlap (the earlier one is shortened).
 */
/**
 * XMLTV time ("20261019132500 +0200" or without zone = UTC) -> seconds since 1970.
 * Pure calculation without SimpleDateFormat: several times faster with hundreds of thousands of shows.
 */
fun xmltvTime(s: String?): Long? {
    if (s == null) return null
    val t = s.trim()
    if (t.length < 14) return null
    for (i in 0 until 14) if (t[i] !in '0'..'9') return null
    val y = t.substring(0, 4).toInt()
    val mo = t.substring(4, 6).toInt()
    val d = t.substring(6, 8).toInt()
    val h = t.substring(8, 10).toInt()
    val mi = t.substring(10, 12).toInt()
    val se = t.substring(12, 14).toInt()
    if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || se > 60) return null
    var off = 0
    val rest = t.substring(14).trim()
    if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
        val oh = rest.substring(1, 3).toIntOrNull() ?: return null
        val om = rest.substring(3, 5).toIntOrNull() ?: return null
        off = (if (rest[0] == '-') -1 else 1) * (oh * 3600 + om * 60)
    }
    // Days since 1970 (proleptic Gregorian calendar)
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097L + doe - 719468L
    return days * 86400L + h * 3600L + mi * 60L + se - off
}

fun cleanEpg(src: List<EpgEntry>): List<EpgEntry> {
    val sorted = src.filter { it.end > it.start && epgSane(it.start, it.end) }.sortedBy { it.start }
    val out = ArrayList<EpgEntry>()
    for (e in sorted) {
        val last = out.lastOrNull()
        if (last != null && e.start < last.end) {
            if (e.end <= last.end) continue
            if (e.start - last.start < 60) { out[out.lastIndex] = e; continue }
            out[out.lastIndex] = last.copy(end = e.start)
        }
        out.add(e)
    }
    return out
}

/** Checks whether a show has plausible times (protection against broken EPG data). */
fun epgSane(start: Long, end: Long): Boolean {
    if (end <= start) return false
    if (end - start > 12 * 3600L) return false
    val now = System.currentTimeMillis() / 1000
    if (start > now + 7 * 86400L) return false
    if (end > now + 8 * 86400L) return false
    return true
}

fun speedLabel(s: Float): String = when (s) {
    0.5f -> "0,5×"
    0.75f -> "0,75×"
    1f -> "1× (Normal)"
    1.25f -> "1,25×"
    1.5f -> "1,5×"
    else -> "2×"
}


/** Responses where a retry brings nothing (login, subscription or address are wrong). */
fun isPermanentHttp(status: Int): Boolean = status == 401 || status == 403 || status == 404 || status == 410 || status == 451

/** Understandable error message with a solution hint. Never shows the address of the stream. */
fun playbackErrorText(code: Int, status: Int, live: Boolean, tls: Boolean = false): String = when {
    tls -> "Das Zertifikat des Anbieters passt nicht zur Server-Adresse. Beim Anbieter nach einer aktuellen Adresse fragen."
    status == 401 || status == 403 -> "Zugang abgelehnt ($status). Benutzername, Passwort oder Abo beim Anbieter prüfen."
    status == 404 || status == 410 -> if (live) "Dieser Sender ist beim Anbieter nicht mehr vorhanden." else "Dieser Titel ist beim Anbieter nicht mehr vorhanden."
    status == 429 || status == 458 || status == 509 -> "Maximale Verbindungen des Anbieters erreicht. Anderes Gerät oder Profil beenden und erneut versuchen."
    status == 512 -> "Der Server des Anbieters lehnt die Verbindung ab (512). Meist ist die Server-Adresse nicht mehr aktuell oder gesperrt."
    status in 500..599 -> "Der Anbieter hat gerade ein Problem ($status). Später erneut versuchen."
    status >= 400 -> "Der Anbieter lehnt die Wiedergabe ab ($status)."
    code == PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED ||
        code == PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Anbieter. Internet und Netzwerk prüfen."
    code == PlaybackErrorCodes.DECODER_INIT_FAILED ||
        code == PlaybackErrorCodes.DECODING_FAILED ||
        code == PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED -> "Dieses Videoformat kann das Gerät nicht abspielen."
    code == PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED ||
        code == PlaybackErrorCodes.PARSING_MANIFEST_MALFORMED ||
        code == PlaybackErrorCodes.PARSING_CONTAINER_UNSUPPORTED -> "Der Stream ist fehlerhaft oder wird nicht unterstützt."
    else -> "Wiedergabe nicht möglich."
}

/**
 * Mirror of the Media3 PlaybackException error codes used by playbackErrorText, so this file needs no Media3.
 * Media3ContractTest compares every value with PlaybackException.ERROR_CODE_*.
 */
internal object PlaybackErrorCodes {
    const val IO_NETWORK_CONNECTION_FAILED = 2001
    const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    const val PARSING_CONTAINER_MALFORMED = 3001
    const val PARSING_MANIFEST_MALFORMED = 3002
    const val PARSING_CONTAINER_UNSUPPORTED = 3003
    const val DECODER_INIT_FAILED = 4001
    const val DECODING_FAILED = 4003
    const val DECODING_FORMAT_UNSUPPORTED = 4005
}

/** Retry rules for loading streams (pure logic; IptvLoadErrorPolicy in PlayerNet.kt delegates here). */
object IptvRetryRules {
    const val RETRIES = 6

    /** HTTP answers where another attempt is pointless: client errors except "timeout" (408) and "too many requests" (429). */
    fun isFinalStatus(code: Int): Boolean = code in 400..499 && code != 408 && code != 429
}

object PlayerLogic {
    /** Hint when a stream has a format the player cannot open (instead of a crash). */
    const val MSG_FORMAT = "Dieses Stream-Format wird nicht unterstützt."
}
