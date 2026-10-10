package de.pyryco.mobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ReadMarkDecimalTest {
    @Test
    fun canonicalDecimalPreservesTheEntireUnsignedRange() {
        val boundaries = listOf(0uL, 1uL, 9uL, 10uL, Long.MAX_VALUE.toULong(), Long.MAX_VALUE.toULong() + 1u, ULong.MAX_VALUE)
        val random = Random(2050)
        (boundaries + List(10000) { random.nextLong().toULong() }).forEach { value ->
            assertEquals(value, parseReadMarkDecimal(value.toString()))
        }
    }

    @Test
    fun rejectsNoncanonicalAndOverflowingTokens() {
        listOf(
            "",
            "00",
            "01",
            "-1",
            "+1",
            "1.0",
            "1e2",
            " 1",
            "1 ",
            "null",
            "true",
            "１２",
            "18446744073709551616",
            "99999999999999999999",
            "100000000000000000000",
        ).forEach {
            assertNull(it, parseReadMarkDecimal(it))
        }
    }
}
