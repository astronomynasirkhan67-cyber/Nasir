package com.ipvio.stream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-015: update.json parsing, hash, version and signer rules (pure, no Android). */
class UpdateLogicTest {
    private val sha = "a".repeat(32) + "0123456789abcdef" + "B".repeat(16)

    private fun json(vararg extra: String): JSONObject {
        val base = """"versionCode": 5, "versionName": "1.4", "apk": "IPVIO-Stream.apk""""
        return JSONObject("{" + (listOf(base) + extra.toList()).joinToString(",") + "}")
    }

    private fun failsWith(msg: String, block: () -> Unit) {
        try { block(); fail("expected: $msg") } catch (e: IllegalStateException) { assertEquals(msg, e.message) }
    }

    @Test fun manifest_vollstaendig() {
        val m = UpdateLogic.parseManifest(
            json(""""sha256": "$sha"""", """"minSupportedCode": 3""", """"mandatory": true""", """"notes": "What is new""""), strict = true
        )
        assertEquals(5L, m.code); assertEquals("1.4", m.name); assertEquals("IPVIO-Stream.apk", m.apk)
        assertEquals(sha.lowercase(), m.sha256); assertEquals(3L, m.minSupportedCode)
        assertTrue(m.mandatory); assertEquals("What is new", m.notes)
    }

    @Test fun manifest_standardwerte_ohne_sha_wenn_nicht_strikt() {
        val m = UpdateLogic.parseManifest(json(), strict = false)
        assertNull(m.sha256); assertEquals(0L, m.minSupportedCode); assertFalse(m.mandatory); assertEquals("", m.notes)
    }

    @Test fun manifest_strikt_verlangt_sha() {
        failsWith("Update-Datei unvollständig") { UpdateLogic.parseManifest(json(), strict = true) }
    }

    @Test fun manifest_sha_muss_wohlgeformt_sein_auch_wenn_nicht_strikt() {
        failsWith("Update-Datei: ungültige Prüfsumme") { UpdateLogic.parseManifest(json(""""sha256": "abc123""""), strict = false) }
    }

    @Test fun manifest_pflichtfelder() {
        failsWith("Update-Datei unvollständig") { UpdateLogic.parseManifest(JSONObject("""{"apk":"a.apk"}"""), strict = false) }
        failsWith("Update-Datei unvollständig") { UpdateLogic.parseManifest(JSONObject("""{"versionCode":2}"""), strict = false) }
        failsWith("Update-Datei unvollständig") { UpdateLogic.parseManifest(JSONObject("""{"versionCode":0,"apk":"a.apk"}"""), strict = false) }
        failsWith("Update-Datei unvollständig") { UpdateLogic.parseManifest(JSONObject("""{"versionCode":2,"apk":null}"""), strict = false) }
    }

    @Test fun manifest_versionName_fehlt_dann_code() {
        assertEquals("7", UpdateLogic.parseManifest(JSONObject("""{"versionCode":7,"apk":"a.apk"}"""), strict = false).name)
    }

    @Test fun manifest_negative_minSupported_wird_null() {
        assertEquals(0L, UpdateLogic.parseManifest(json(""""minSupportedCode": -4"""), strict = false).minSupportedCode)
    }

    @Test fun sha_normalisieren() {
        assertEquals(sha.lowercase(), UpdateLogic.normalizeSha(sha))
        assertEquals(sha.lowercase(), UpdateLogic.normalizeSha("  " + sha.chunked(4).joinToString(" ") + "\n"))
        assertNull(UpdateLogic.normalizeSha(null))
        assertNull(UpdateLogic.normalizeSha(""))
        assertNull(UpdateLogic.normalizeSha("a".repeat(63)))
        assertNull(UpdateLogic.normalizeSha("a".repeat(65)))
        assertNull(UpdateLogic.normalizeSha("g".repeat(64)))
    }

    @Test fun hex_ist_klein_und_zweistellig() {
        assertEquals("000fff10", UpdateLogic.hex(byteArrayOf(0, 15, -1, 16)))
    }

    @Test fun sha_vergleich() {
        assertTrue(UpdateLogic.sameHash(sha, sha.lowercase()))
        assertTrue(UpdateLogic.sameHash(sha.uppercase(), sha.lowercase()))
        assertFalse(UpdateLogic.sameHash(sha, "0".repeat(64)))
        assertFalse(UpdateLogic.sameHash(null, sha))
        assertFalse(UpdateLogic.sameHash("", ""))
        assertFalse(UpdateLogic.sameHash("zz", "zz"))
    }

    @Test fun sha_von_bekanntem_wert() {
        // SHA-256 of the empty input
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateLogic.hex(d))
    }

    @Test fun pflicht_update() {
        assertTrue(UpdateLogic.isRequired(4, 0, true))
        assertTrue(UpdateLogic.isRequired(2, 3, false))
        assertFalse(UpdateLogic.isRequired(3, 3, false))
        assertFalse(UpdateLogic.isRequired(2, 0, false))
    }

    @Test fun apk_version() {
        assertNull(UpdateLogic.versionProblem(4, 5, 5))
        assertNull(UpdateLogic.versionProblem(4, 5, 0))
        assertEquals(UpdateLogic.VersionProblem.NOT_NEWER, UpdateLogic.versionProblem(5, 5, 5))
        assertEquals(UpdateLogic.VersionProblem.NOT_NEWER, UpdateLogic.versionProblem(6, 5, 0))
        assertEquals(UpdateLogic.VersionProblem.CODE_MISMATCH, UpdateLogic.versionProblem(4, 6, 5))
    }

    @Test fun signer() {
        val a = setOf("aa"); val b = setOf("bb")
        assertTrue(UpdateLogic.sameSigners(a, a, emptySet()))
        assertFalse(UpdateLogic.sameSigners(a, b, emptySet()))
        // key rotation: the new APK lists the old certificate in its history
        assertTrue(UpdateLogic.sameSigners(a, b, setOf("aa", "bb")))
        assertFalse(UpdateLogic.sameSigners(a, b, setOf("cc", "bb")))
        // several signers must all match
        assertTrue(UpdateLogic.sameSigners(setOf("aa", "bb"), setOf("bb", "aa"), emptySet()))
        assertFalse(UpdateLogic.sameSigners(setOf("aa", "bb"), setOf("aa"), emptySet()))
        // nothing readable never matches
        assertFalse(UpdateLogic.sameSigners(emptySet(), emptySet(), emptySet()))
        assertFalse(UpdateLogic.sameSigners(a, emptySet(), a))
        assertFalse(UpdateLogic.sameSigners(emptySet(), a, a))
    }

    @Test fun apk_adresse_relativ_zur_release_url() {
        val base = "https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/update.json"
        assertEquals("https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/IPVIO-Stream.apk", UpdateLogic.resolve(base, "IPVIO-Stream.apk"))
    }
}
