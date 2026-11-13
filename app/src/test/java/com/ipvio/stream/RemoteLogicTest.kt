package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogicTest {
    @Test fun json_text_wird_maskiert() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", RemoteLogic.q("a\"b\\c\nd"))
        assertEquals("\"\\u0001\"", RemoteLogic.q("\u0001"))
        assertEquals("\"Café – 4K\"", RemoteLogic.q("Café – 4K"))
    }

    @Test fun titel_und_folgen_als_json() {
        assertEquals("{\"id\":\"x1\",\"t\":\"Ein \\\"Film\\\"\",\"y\":2020,\"q\":\"4K\",\"g\":\"Kino\"}", RemoteLogic.item("x1", "Ein \"Film\"", 2020, "4K", "Kino"))
        assertEquals("{\"id\":\"x\",\"t\":\"T\",\"y\":0,\"q\":\"\",\"g\":\"\"}", RemoteLogic.item("x", "T", null, null, ""))
        assertEquals("[]", RemoteLogic.arr(emptyList()))
        assertEquals("[1,2]", RemoteLogic.arr(listOf("1", "2")))
        assertEquals("{\"s\":2,\"n\":5,\"t\":\"Titel\"}", RemoteLogic.episode(2, 5, "Titel"))
    }

    @Test fun zustand_und_uhr() {
        assertEquals("{\"a\":false,\"t\":\"\",\"pl\":false,\"pos\":0,\"dur\":0,\"live\":false}", RemoteLogic.state(false, "", false, 0, 0, false))
        assertEquals("1:05", RemoteLogic.clock(65))
        assertEquals("1:01:05", RemoteLogic.clock(3665))
        assertEquals("0:00", RemoteLogic.clock(-4))
        assertEquals(600, RemoteLogic.clampSkip(99999))
        assertEquals(-30, RemoteLogic.clampSkip(-30))
    }

    @Test fun seite_hat_alle_aufrufe_und_keine_ungeloesten_platzhalter() {
        for (li in listOf(0, 1, 2, 9)) {
            val p = RemoteLogic.page(li)
            assertTrue(p.startsWith("<!doctype html>"))
            for (a in listOf("'search'", "'cats'", "'channels'", "'eps'", "'play'", "'ctl'", "'state'")) assertTrue("$li $a", p.contains(a))
            assertFalse("Platzhalter offen", p.contains("\${"))
            // Never insert the provider's title as HTML
            assertFalse(p.contains("innerHTML=it") || p.contains("innerHTML=a"))
        }
        assertTrue(RemoteLogic.page(0).contains("Pause / Weiter"))
        assertTrue(RemoteLogic.page(1).contains("Pause / Play"))
        assertTrue(RemoteLogic.page(9).contains("lang=\"nl\""))
    }

    @Test fun page_sends_the_access_code_with_the_list_import() {
        for (li in listOf(0, 1, 9)) {
            assertTrue("$li", RemoteLogic.page(li).contains("'/listimport?name='+encodeURIComponent(x.name)+'&code='+encodeURIComponent(code)"))
        }
    }
}
