package com.ipvio.stream

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class PhoneImportTest {
    @Test fun formWirdZerlegtUndDekodiert() {
        val f = PhoneImportLogic.parseForm("code=1234&url=http%3A%2F%2Fa.tld%2Fx.m3u%3Fu%3D1%26p%3D2")
        assertEquals("1234", f["code"])
        assertEquals("http://a.tld/x.m3u?u=1&p=2", f["url"])
    }

    @Test fun eingabePruefung() {
        assertNotNull(PhoneImportLogic.toInput(mapOf("url" to " http://a/b.m3u ")))
        assertNull(PhoneImportLogic.toInput(mapOf("url" to "ftp://a")))
        assertTrue(PhoneImportLogic.toInput(mapOf("host" to "h", "user" to "u", "pass" to "p"))!!.isXtream)
        assertNull(PhoneImportLogic.toInput(mapOf("host" to "h", "user" to "u")))
        assertNull(PhoneImportLogic.toInput(emptyMap()))
    }

    @Test fun codeHatVierStellen() {
        repeat(50) { assertEquals(4, PhoneImportLogic.newCode(java.util.Random(it.toLong())).length) }
    }

    private fun post(port: Int, body: String): String {
        Socket("127.0.0.1", port).use { s ->
            val b = body.toByteArray()
            s.getOutputStream().write(("POST /send HTTP/1.1\r\nHost: x\r\nContent-Length: ${b.size}\r\n\r\n").toByteArray() + b)
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun serverNimmtNurMitRichtigemCodeAn() {
        var got: PhoneInput? = null
        val pi = PhoneImport { got = it }
        assertTrue(pi.start())
        try {
            assertTrue(post(pi.port, "code=0000&url=http%3A%2F%2Fa%2Fb.m3u").startsWith("HTTP/1.1 403"))
            assertNull(got)
            assertTrue(post(pi.port, "code=${pi.code}&url=http%3A%2F%2Fa%2Fb.m3u").startsWith("HTTP/1.1 200"))
            assertEquals("http://a/b.m3u", got?.url)
        } finally { pi.stop() }
    }

    @Test fun sperreNachFuenfFehlversuchen() {
        val pi = PhoneImport { }
        assertTrue(pi.start())
        try {
            repeat(5) { post(pi.port, "code=x&url=http%3A%2F%2Fa") }
            assertTrue(post(pi.port, "code=${pi.code}&url=http%3A%2F%2Fa").startsWith("HTTP/1.1 429"))
        } finally { pi.stop() }
    }

    /** Sends one request and returns the raw response; [declaredLength] overrides Content-Length (to test limits without sending the data). */
    private fun rawRequest(port: Int, requestLine: String, body: ByteArray, declaredLength: Long = body.size.toLong()): String {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(("$requestLine HTTP/1.1\r\nHost: x\r\nContent-Length: $declaredLength\r\n\r\n").toByteArray() + body)
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Server like the phone search: [PhoneImport.api] is set, which must not bypass the code any more. */
    private fun listServer(received: MutableList<Pair<String, ByteArray>>): PhoneImport {
        val pi = PhoneImport { }
        pi.onList = { name, bytes -> received.add(name to bytes) }
        pi.api = { _, _ -> null }
        return pi
    }

    @Test fun listImportWithoutCodeIsRejectedEvenWhenPhoneSearchIsActive() {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val pi = listServer(received)
        assertTrue(pi.start())
        try {
            assertTrue(rawRequest(pi.port, "POST /listimport?name=a.csv", "Name,Year\nX,2000".toByteArray()).startsWith("HTTP/1.1 403"))
            assertTrue(rawRequest(pi.port, "POST /listimport?name=a.csv&code=0000x", "Name,Year\nX,2000".toByteArray()).startsWith("HTTP/1.1 403"))
            assertTrue(received.isEmpty())
        } finally { pi.stop() }
    }

    @Test fun listImportWithCodeDeliversExactlyTheSentBytes() {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val pi = listServer(received)
        assertTrue(pi.start())
        try {
            // larger than one read chunk (64 KiB) so that the chunked reading is covered
            val data = ByteArray(200_000) { (it % 251).toByte() }
            assertTrue(rawRequest(pi.port, "POST /listimport?name=w.csv&code=${pi.code}", data).startsWith("HTTP/1.1 200"))
            assertEquals(1, received.size)
            assertEquals("w.csv", received[0].first)
            assertArrayEquals(data, received[0].second)
        } finally { pi.stop() }
    }

    @Test fun listImportRejectsTooLargeUploadWithoutReadingIt() {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val pi = listServer(received)
        assertTrue(pi.start())
        try {
            val r = rawRequest(pi.port, "POST /listimport?name=big.json&code=${pi.code}", ByteArray(0), PhoneImport.MAX_LIST_UPLOAD + 1)
            assertTrue(r.startsWith("HTTP/1.1 413"))
            assertTrue(received.isEmpty())
        } finally { pi.stop() }
    }

    @Test fun listImportRejectsEmptyBody() {
        val received = mutableListOf<Pair<String, ByteArray>>()
        val pi = listServer(received)
        assertTrue(pi.start())
        try {
            assertTrue(rawRequest(pi.port, "POST /listimport?name=e.csv&code=${pi.code}", ByteArray(0)).startsWith("HTTP/1.1 400"))
            assertTrue(received.isEmpty())
        } finally { pi.stop() }
    }

    @Test fun handySeiteInDerSpracheDesHandys() {
        assertEquals(0, PhoneImportLogic.langIndex("de-AT,de;q=0.9,en;q=0.8"))
        assertEquals(2, PhoneImportLogic.langIndex("tr-TR,tr;q=0.9"))
        assertEquals(5, PhoneImportLogic.langIndex("sr-Latn-RS"))
        assertEquals(9, PhoneImportLogic.langIndex("ja-JP,nl;q=0.5"))
        assertEquals(1, PhoneImportLogic.langIndex(""))
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        assertTrue(PhoneImportLogic.page(2).contains("Televizyona gönder"))
        assertTrue(PhoneImportLogic.page(0).contains("An den Fernseher senden"))
        assertTrue(PhoneImportLogic.page(1).contains("Send to TV"))
        assertTrue(PhoneImportLogic.message(7, "ok").contains("Envoyé"))
    }
}
