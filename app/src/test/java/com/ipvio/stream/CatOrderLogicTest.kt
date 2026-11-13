package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class CatOrderLogicTest {
    private val provider = listOf("DE News", "de Sport", "AT Kids", "Movies")

    @Test fun provider_keepsOrder() =
        assertEquals(provider, CatOrderLogic.apply(provider, CatOrderLogic.PROVIDER, listOf("Movies")))

    @Test fun az_ignoresCase() =
        assertEquals(listOf("AT Kids", "DE News", "de Sport", "Movies"), CatOrderLogic.apply(provider, CatOrderLogic.AZ, emptyList()))

    @Test fun own_placedFirst_newOnesKeepProviderOrder() =
        assertEquals(
            listOf("Movies", "AT Kids", "DE News", "de Sport"),
            CatOrderLogic.apply(provider, CatOrderLogic.OWN, listOf("Movies", "Gone", "AT Kids"))
        )

    @Test fun own_ignoresDuplicates() =
        assertEquals(listOf("Movies", "DE News", "de Sport", "AT Kids"), CatOrderLogic.apply(provider, CatOrderLogic.OWN, listOf("Movies", "Movies")))

    @Test fun move_upDownAndEdges() {
        assertEquals(listOf("de Sport", "DE News", "AT Kids", "Movies"), CatOrderLogic.move(provider, "de Sport", -1))
        assertEquals(listOf("DE News", "AT Kids", "de Sport", "Movies"), CatOrderLogic.move(provider, "de Sport", 1))
        assertEquals(provider, CatOrderLogic.move(provider, "DE News", -1))
        assertEquals(provider, CatOrderLogic.move(provider, "Movies", 1))
        assertEquals(provider, CatOrderLogic.move(provider, "Unknown", 1))
    }

    @Test fun toTop_movesToFirstPlace() =
        assertEquals(listOf("Movies", "DE News", "de Sport", "AT Kids"), CatOrderLogic.toTop(provider, "Movies"))

    @Test fun encodeDecode_roundTrip() {
        assertEquals(provider, CatOrderLogic.decode(CatOrderLogic.encode(provider)))
        assertEquals(emptyList<String>(), CatOrderLogic.decode(null))
        assertEquals(emptyList<String>(), CatOrderLogic.decode(""))
    }
}
