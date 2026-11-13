package com.ipvio.stream

import java.security.MessageDigest

/** Detection of adult content and PIN check (without Android, testable). */
object ParentalLogic {
    private val GROUP_RE = Regex(
        """(?i)(^|[^a-z0-9])(xxx|porn\w*|adult\w*|erotic\w*|erotik\w*|sex|nsfw|hentai|erwachsene\w*|fsk ?18|ab 18|for adults|18\s?\+|\+\s?18)($|[^a-z0-9])"""
    )
    private val TITLE_RE = Regex("""(?i)(^|[^a-z0-9])(xxx|porn\w*|hentai|nsfw|18\s?\+|\[adult\]|\(adult\))($|[^a-z0-9])""")

    fun isAdult(group: String, title: String): Boolean =
        GROUP_RE.containsMatchIn(group) || TITLE_RE.containsMatchIn(title)

    fun hash(pin: String, salt: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun validPin(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

    fun check(pin: String, salt: String, expected: String): Boolean = validPin(pin) && hash(pin, salt) == expected
}

/** Protection against guessing the PIN: after [maxFails] failed attempts, input is locked for [lockMs]. */
class PinGuard(private val maxFails: Int = 5, private val lockMs: Long = 60_000L) {
    private var fails = 0
    private var lockedUntil = 0L

    /** Remaining lock time in seconds (0 = input allowed). */
    fun waitSec(now: Long): Long = if (now >= lockedUntil) 0L else (lockedUntil - now + 999) / 1000

    /** Report the result of an attempt. Returns true if locked from now on. */
    fun record(ok: Boolean, now: Long): Boolean {
        if (ok) { fails = 0; lockedUntil = 0L; return false }
        fails++
        if (fails >= maxFails) { fails = 0; lockedUntil = now + lockMs; return true }
        return false
    }
}
