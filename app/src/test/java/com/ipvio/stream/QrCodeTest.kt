package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test fun groesseWaechstMitDemText() {
        assertEquals(21, QrCode.encode("A")!!.size)                                  // Version 1
        assertEquals(25, QrCode.encode("http://192.168.0.20:8080")!!.size)           // Version 2
        assertTrue(QrCode.encode("x".repeat(120))!!.size > 41)
    }

    @Test fun zuLangGibtNull() {
        assertNotNull(QrCode.encode("y".repeat(QrCode.maxBytes())))
        assertNull(QrCode.encode("y".repeat(QrCode.maxBytes() + 1)))
    }

    @Test fun eckenMuster() {
        val m = QrCode.encode("https://www.google.com/device")!!
        val n = m.size
        // Top-left finder square: 7×7 frame, white inside, dark center
        for (i in 0..6) { assertTrue(m[0][i]); assertTrue(m[6][i]); assertTrue(m[i][0]); assertTrue(m[i][6]) }
        assertTrue(!m[1][1] && m[3][3])
        assertTrue(m[0][n - 1] && m[n - 1][0])                                       // the other two corners
        assertTrue(m[n - 8][8])                                                      // fixed dark module
        for (i in 8..n - 9) assertEquals(i % 2 == 0, m[6][i])                        // timing pattern
    }

    @Test fun gleicherTextGleichesRaster() {
        val a = QrCode.encode("http://10.0.0.5:5050/transfer")!!
        val b = QrCode.encode("http://10.0.0.5:5050/transfer")!!
        for (y in a.indices) assertTrue(a[y].contentEquals(b[y]))
    }
}
