package de.pyryco.mobile.ui.conversations.thread

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The client-owned counter formatting on a running task's meta line (#1044). */
@RunWith(AndroidJUnit4::class)
class BackgroundTaskProgressFormatTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun counters(
        toolUses: Long = 4,
        totalTokens: Long = 18_000,
        durationMs: Long = 161_000,
    ) = progressCounters(resources, toolUses, totalTokens, durationMs)

    private fun tools(count: Long) = counters(toolUses = count)[0]

    private fun tokens(count: Long) = counters(totalTokens = count)[1]

    private fun elapsed(ms: Long) = counters(durationMs = ms)[2]

    @Test
    fun segments_areToolsTokensElapsed_inOrder() {
        assertEquals(listOf("4 tools", "18k tokens", "2m 41s"), counters())
    }

    @Test
    fun counts_useTheSingular_atExactlyOne() {
        assertEquals("1 tool", tools(1))
        assertEquals("1 token", tokens(1))
        assertEquals("0 tools", tools(0))
        assertEquals("2 tools", tools(2))
    }

    @Test
    fun tokensUnderAThousand_printWhole() {
        assertEquals("840 tokens", tokens(840))
        assertEquals("999 tokens", tokens(999))
    }

    @Test
    fun tokensFromAThousand_roundHalfUpToThousands() {
        assertEquals("1k tokens", tokens(1000))
        assertEquals("1k tokens", tokens(1499))
        assertEquals("2k tokens", tokens(1500))
        assertEquals("42k tokens", tokens(41_500))
    }

    @Test
    fun hugeTokenCount_doesNotOverflow() {
        assertEquals("${Long.MAX_VALUE / 1000 + 1}k tokens", tokens(Long.MAX_VALUE))
    }

    @Test
    fun elapsedUnderAMinute_isSeconds() {
        assertEquals("0s", elapsed(0))
        assertEquals("0s", elapsed(999))
        assertEquals("41s", elapsed(41_000))
        assertEquals("59s", elapsed(59_999))
    }

    @Test
    fun elapsedUnderAnHour_isMinutesAndZeroPaddedSeconds() {
        assertEquals("1m 00s", elapsed(60_000))
        assertEquals("1m 05s", elapsed(65_000))
        assertEquals("2m 41s", elapsed(161_000))
        assertEquals("59m 59s", elapsed(3_599_000))
    }

    @Test
    fun elapsedFromAnHour_isHoursAndZeroPaddedMinutes_droppingSeconds() {
        assertEquals("1h 00m", elapsed(3_600_000))
        assertEquals("1h 03m", elapsed(3_839_000))
        assertEquals("27h 10m", elapsed(97_800_000))
    }

    @Test
    fun negativeReadings_printAsZero() {
        assertEquals(listOf("0 tools", "0 tokens", "0s"), counters(toolUses = -3, totalTokens = -840, durationMs = -41_000))
    }
}
