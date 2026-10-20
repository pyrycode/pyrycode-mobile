package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextSummaryFormatTest {
    @Test
    fun tokensUseDesktopThousandsRoundingAndStripPointZero() {
        for ((value, expected) in listOf(
            0L to "0",
            999L to "999",
            1000L to "1k",
            12400L to "12.4k",
            12450L to "12.5k",
            200000L to "200k",
            -1L to "-1",
        )) {
            assertEquals(expected, formatContextTokens(value))
        }
    }

    @Test
    fun barSharesUseFloatingPointBeforeMultiplicationAndClamp() {
        assertEquals(6.2f, contextBarPercent(12400, 200000), .0001f)
        assertEquals(100f, contextBarPercent(Long.MAX_VALUE, Long.MAX_VALUE), 0f)
        assertEquals(100f, contextBarPercent(Long.MAX_VALUE, 1), 0f)
        assertEquals(0f, contextBarPercent(Long.MIN_VALUE, 1), 0f)
        assertEquals(0f, contextBarPercent(100, 0), 0f)
        assertEquals(0f, contextBarPercent(100, -1), 0f)
    }
}
