package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1412: the footer's context reading steps at 70 and 85 percent boundaries. */
class ContextUsageStepTest {
    @Test
    fun belowSeventy_isNormal() {
        assertEquals(ContextUsageStep.Normal, contextUsageStep(0))
        assertEquals(ContextUsageStep.Normal, contextUsageStep(69))
    }

    @Test
    fun seventyToEightyFour_isWarning() {
        assertEquals(ContextUsageStep.Warning, contextUsageStep(70))
        assertEquals(ContextUsageStep.Warning, contextUsageStep(84))
    }

    @Test
    fun eightyFiveAndAbove_isHigh() {
        assertEquals(ContextUsageStep.High, contextUsageStep(85))
        assertEquals(ContextUsageStep.High, contextUsageStep(100))
    }
}
