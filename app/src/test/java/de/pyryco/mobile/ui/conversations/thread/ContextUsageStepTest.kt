package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1412: the footer's context reading steps at desktop #1062's 50 and 70 percent boundaries. */
class ContextUsageStepTest {
    @Test
    fun belowFifty_isNormal() {
        assertEquals(ContextUsageStep.Normal, contextUsageStep(0))
        assertEquals(ContextUsageStep.Normal, contextUsageStep(49))
    }

    @Test
    fun fiftyToSixtyNine_isWarning() {
        assertEquals(ContextUsageStep.Warning, contextUsageStep(50))
        assertEquals(ContextUsageStep.Warning, contextUsageStep(69))
    }

    @Test
    fun seventyAndAbove_isHigh() {
        assertEquals(ContextUsageStep.High, contextUsageStep(70))
        assertEquals(ContextUsageStep.High, contextUsageStep(100))
    }
}
