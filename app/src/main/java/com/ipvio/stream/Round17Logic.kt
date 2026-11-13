package com.ipvio.stream

import org.json.JSONObject

// Pure rules from addendum 17 (without Android so it can be tested)

/** Pure formatting of the technical info (tested). */
object TechText {
    fun kbps(bps: Int): String = if (bps <= 0) "?" else if (bps >= 1_000_000) "%.1f Mbit/s".format(bps / 1_000_000.0) else "${bps / 1000} kbit/s"
    fun fps(f: Float): String = if (f <= 0f) "?" else if (Math.abs(f - Math.round(f)) < 0.01f) "${Math.round(f)}" else "%.3f".format(f)
    fun secs(ms: Long): String = "%.1f s".format(ms.coerceAtLeast(0L) / 1000.0)
}


/** Pure rules of the app update (tested). */
object UpdateLogic {
    fun isNewer(current: Long, remote: Long): Boolean = remote > current

    /** Numbers of a version string: "v1.2.3" → [1, 2, 3], "1.1-beta" → [1, 1]. */
    fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-', '_', ' ').map { it.takeWhile(Char::isDigit) }.takeWhile { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }

    /** Is [remote] (e.g. tag "v1.2" of a GitHub release) newer than the installed version [current] ("1.1")? */
    fun isNewerName(current: String, remote: String): Boolean {
        val a = parts(current); val b = parts(remote)
        if (b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /** APK of a GitHub release: (name, download address); prefers "IPVIO-Stream.apk", otherwise the first APK. */
    fun pickApk(assets: List<Pair<String, String>>): String? {
        val apks = assets.filter { it.first.endsWith(".apk", ignoreCase = true) && it.second.startsWith("https://") }
        return (apks.firstOrNull { it.first.equals("IPVIO-Stream.apk", true) } ?: apks.firstOrNull { it.first.contains("ipvio", true) } ?: apks.firstOrNull())?.second
    }

    /** Check automatically once a day. */
    fun due(lastCheck: Long, now: Long): Boolean = now - lastCheck >= 20 * 3_600_000L || now < lastCheck

    /** The APK address may be given relative to the update address. */
    fun resolve(base: String, apk: String): String =
        if (apk.startsWith("http://") || apk.startsWith("https://")) apk
        else base.substringBeforeLast('/', base) + "/" + apk.trimStart('/')

    // ---- T-015: update.json, hash, version and signer checks (pure, tested in UpdateLogicTest) ----

    /** Parsed `update.json`. [sha256] is lower-case hex or null when the file has none. */
    data class Manifest(
        val code: Long, val name: String, val apk: String, val sha256: String?,
        val minSupportedCode: Long, val mandatory: Boolean, val notes: String
    )

    private fun str(j: JSONObject, key: String): String = if (j.isNull(key)) "" else j.optString(key).trim()

    /**
     * Reads an `update.json`. [strict] (the built-in source) requires a valid `sha256`; for a custom address it is optional,
     * but if present it must be well-formed. Throws [IllegalStateException] with a short German message (shown to the user).
     */
    fun parseManifest(j: JSONObject, strict: Boolean): Manifest {
        val code = j.optLong("versionCode", 0L)
        val apk = str(j, "apk")
        if (code <= 0L || apk.isEmpty()) error("Update-Datei unvollständig")
        val raw = str(j, "sha256")
        val sha = normalizeSha(raw)
        if (raw.isNotEmpty() && sha == null) error("Update-Datei: ungültige Prüfsumme")
        if (strict && sha == null) error("Update-Datei unvollständig")
        val name = str(j, "versionName").ifEmpty { code.toString() }
        return Manifest(code, name, apk, sha, j.optLong("minSupportedCode", 0L).coerceAtLeast(0L), j.optBoolean("mandatory", false), str(j, "notes").take(300))
    }

    /** SHA-256 as 64 lower-case hex characters; spaces are ignored (older certutil prints them). Null if malformed. */
    fun normalizeSha(s: String?): String? {
        val t = s.orEmpty().filterNot { it.isWhitespace() }.lowercase()
        return if (t.length == 64 && t.all { it in '0'..'9' || it in 'a'..'f' }) t else null
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** [expected] comes from update.json (any case, may be null), [actualHex] from the download. */
    fun sameHash(expected: String?, actualHex: String): Boolean {
        val e = normalizeSha(expected) ?: return false
        return e == normalizeSha(actualHex)
    }

    /** An update the user cannot postpone: flagged `mandatory`, or the installed version is below `minSupportedCode`. */
    fun isRequired(installedCode: Long, minSupportedCode: Long, mandatory: Boolean): Boolean =
        mandatory || (minSupportedCode > 0L && installedCode < minSupportedCode)

    enum class VersionProblem { NOT_NEWER, CODE_MISMATCH }

    /** Version check of the downloaded APK itself. [expected] = versionCode announced by update.json (0 = unknown). */
    fun versionProblem(installed: Long, apk: Long, expected: Long): VersionProblem? = when {
        expected > 0L && apk != expected -> VersionProblem.CODE_MISMATCH
        apk <= installed -> VersionProblem.NOT_NEWER
        else -> null
    }

    /**
     * Do the signer certificates (SHA-256 hex) of the new APK match the installed app? Equal sets, or (key rotation, API 28+)
     * the installed signers appear in the new APK's signing history. Empty sets never match.
     */
    fun sameSigners(installed: Set<String>, apkCurrent: Set<String>, apkHistory: Set<String>): Boolean {
        if (installed.isEmpty() || apkCurrent.isEmpty()) return false
        return installed == apkCurrent || installed.all { it in apkHistory }
    }
}


/** Buffer levels for slow internet (addendum 18). Level 0 = Normal, 1 = Large, 2 = Very large. */
object BufferLogic {
    /** Effective level: mode 0 (Automatic) uses the learned level, 1 Normal, 2 Large, 3 Very large. */
    fun level(mode: Int, learned: Int): Int = when (mode) { 0 -> learned.coerceIn(0, 2); 1 -> 0; 2 -> 1; else -> 2 }

    /** Only the automatic mode learns; one level per call, at most 2. */
    fun bump(mode: Int, learned: Int): Int = if (mode == 0) (learned + 1).coerceAtMost(2) else learned

    /**
     * Byte limit of the buffer. The time targets (20-60 s) only work if the byte limit leaves room for them: a 4K movie
     * with 40 Mbit/s is 5 MB per second, so 14 MB hold barely 3 s and the player stalls on every small network hiccup.
     * So the limit grows with the Java heap (12 %, at most 64 MB) but never drops below the planned value. -1 = no limit of our own.
     */
    fun byteLimit(planned: Int, maxHeapBytes: Long): Int {
        if (planned <= 0) return planned
        val share = minOf(maxHeapBytes / 100 * 12, 64L * 1024 * 1024)
        return maxOf(planned.toLong(), share).toInt()
    }

    /** [base] = (min ms, max ms, start ms, rebuffer ms, byte limit or -1). */
    fun scale(base: IntArray, level: Int): IntArray {
        if (level <= 0) return base
        val m = level + 1
        val bytes = if (base[4] > 0) minOf(base[4].toLong() * m, 64L * 1024 * 1024).toInt() else base[4]
        return intArrayOf(base[0] * m, base[1] * m, base[2] + 2000 * level, base[3] + 4000 * level, bytes)
    }
}
