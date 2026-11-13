package com.ipvio.stream

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * T-015: checks a downloaded APK before the system installer opens, so the user gets a clear text instead of Android's
 * "App not installed". The rules themselves are pure and tested in [UpdateLogic]; this file only reads the APK.
 *
 * Checks, in this order: the file is a readable APK; it belongs to this app (package name); its versionCode is higher than
 * the installed one (and equals the announced one, if there is one); its signer certificate matches the installed app.
 * Android still has the last word at install time. **Unverified on a device:** reading signatures of an APK file on API below 28
 * (`GET_SIGNATURES` with `getPackageArchiveInfo`).
 */
object UpdateVerify {
    private class Signers(val current: Set<String>, val history: Set<String>)

    /** Returns null if the file may be installed, otherwise a short German text for the user. */
    fun check(c: Context, apk: File, info: UpdateInfo): String? {
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return "Die Datei ist keine gültige App-Datei."
        if (archive.packageName != c.packageName) return "Die Datei gehört zu einer anderen App."
        when (UpdateLogic.versionProblem(AppUpdate.currentCode(c), codeOf(archive), info.code)) {
            UpdateLogic.VersionProblem.CODE_MISMATCH -> return "Die Versionsnummer passt nicht zur Update-Datei."
            UpdateLogic.VersionProblem.NOT_NEWER -> return "Die Datei ist nicht neuer als die installierte Version."
            null -> {}
        }
        val installed = runCatching { pm.getPackageInfo(c.packageName, flags) }.getOrNull()
        val have = signersOf(installed)
        val got = signersOf(archive)
        if (have.current.isEmpty() || got.current.isEmpty()) return "Die Signatur konnte nicht geprüft werden."
        if (!UpdateLogic.sameSigners(have.current, got.current, got.history)) {
            return "Die Signatur passt nicht zur installierten App. Android würde das Update ablehnen. " +
                "Erstelle ein Backup, installiere die App neu und stelle das Backup wieder her."
        }
        return null
    }

    private fun codeOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()

    private fun digest(s: Signature): String = UpdateLogic.hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private fun signersOf(pi: PackageInfo?): Signers {
        if (pi == null) return Signers(emptySet(), emptySet())
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val si = pi.signingInfo ?: return@runCatching Signers(emptySet(), emptySet())
                val current = si.apkContentsSigners.orEmpty().map(::digest).toSet()
                // The history (key rotation) exists only for a single signer.
                val history = if (si.hasMultipleSigners()) emptySet() else si.signingCertificateHistory.orEmpty().map(::digest).toSet()
                Signers(current, history)
            } else {
                @Suppress("DEPRECATION")
                Signers(pi.signatures.orEmpty().map(::digest).toSet(), emptySet())
            }
        }.getOrDefault(Signers(emptySet(), emptySet()))
    }
}
