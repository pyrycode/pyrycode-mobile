package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.SessionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1411: the one context-percentage computation both the footer and the Status sheet read. */
class ContextPercentTest {
    @Test
    fun aReading_isTotalOverMax_notItsReportedPercentage() {
        assertEquals(25, contextPercent(usage(total = 50_000, max = 200_000, percentage = 84), null))
    }

    @Test
    fun aReading_winsOverTheSettingsPair() {
        assertEquals(25, contextPercent(usage(total = 50_000, max = 200_000), settings(used = 150_000, window = 200_000)))
    }

    @Test
    fun aReadingWithAZeroWindow_isUnavailable_evenWithUsableSettings() {
        assertNull(contextPercent(usage(total = 50_000, max = 0), settings(used = 150_000, window = 200_000)))
    }

    @Test
    fun withoutAReading_theSettingsPairIsUsed() {
        assertEquals(75, contextPercent(null, settings(used = 150_000, window = 200_000)))
    }

    @Test
    fun withNeitherSource_itIsUnavailable() {
        assertNull(contextPercent(null, null))
    }

    @Test
    fun aSettingsZeroWindow_isUnavailable() {
        assertNull(contextPercent(null, settings(used = 0, window = 0)))
    }

    @Test
    fun aNegativeWindow_isUnavailable() {
        assertNull(contextPercent(usage(total = 10, max = -1), null))
    }

    @Test
    fun itRoundsToTheNearestWholePercent_halfUp() {
        assertEquals(1, contextPercent(usage(total = 1, max = 200), null)) // 0.5%
        assertEquals(0, contextPercent(usage(total = 49, max = 10_000), null)) // 0.49%
        assertEquals(85, contextPercent(usage(total = 169, max = 200), null)) // 84.5%
        assertEquals(84, contextPercent(usage(total = 1_689, max = 2_000), null)) // 84.45%
    }

    @Test
    fun itClampsToZeroThroughHundred() {
        assertEquals(100, contextPercent(usage(total = 250_000, max = 200_000), null))
        assertEquals(100, contextPercent(usage(total = 199_999, max = 200_000), null)) // 99.9995%
        assertEquals(0, contextPercent(null, settings(used = -5, window = 200_000)))
        assertEquals(0, contextPercent(null, settings(used = 0, window = 200_000)))
    }

    @Test
    fun hugeTotals_doNotOverflow() {
        assertEquals(50, contextPercent(usage(total = Long.MAX_VALUE / 2, max = Long.MAX_VALUE), null))
    }

    private fun usage(
        total: Long,
        max: Long,
        percentage: Int = 0,
    ) = ContextUsage(totalTokens = total, maxTokens = max, percentage = percentage, asOf = null)

    private fun settings(
        used: Long,
        window: Long,
    ) = SessionSettings(
        sessionId = "sess-a",
        model = "",
        effort = "",
        effectiveEffort = EffectiveEffort.Unavailable,
        permissionMode = "",
        yolo = false,
        usedTokens = used,
        windowTokens = window,
    )
}
