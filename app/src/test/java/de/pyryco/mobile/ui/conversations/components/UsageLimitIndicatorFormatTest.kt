package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reset clause behind the usage pill (#804, #1519). claude's `resets_at` is range-checked before any date
 * exists, and a usable one renders in a fixed `HH:MM` / `DD.MM.YYYY` shape rather than the locale's.
 */
class UsageLimitIndicatorFormatTest {
    private val now = Instant.parse("2026-09-23T10:00:00Z")
    private val utc = TimeZone.UTC

    private fun reset(resetsAt: Long): UsageLimitReset? = formatUsageLimitReset(resetsAt, now, utc)

    @Test
    fun `zero means no reset reported, not the epoch`() {
        assertNull(reset(0L))
    }

    @Test
    fun `negative and past instants decline`() {
        assertNull(reset(-1L))
        assertNull(reset(now.epochSeconds - 60))
        assertNull(reset(now.epochSeconds))
    }

    @Test
    fun `absurd far-future instants decline rather than format`() {
        assertNull(reset(1_200_000_000_000L)) // ~year 40000
        assertNull(reset(Long.MAX_VALUE))
        assertNull(reset(now.epochSeconds + 32L * 24 * 60 * 60))
    }

    @Test
    fun `a reset later today renders the time only`() {
        assertEquals(UsageLimitReset(date = null, time = "15:30"), reset(Instant.parse("2026-09-23T15:30:00Z").epochSeconds))
    }

    @Test
    fun `a reset on another day renders date and time, zero-padded`() {
        assertEquals(UsageLimitReset(date = "29.09.2026", time = "08:30"), reset(Instant.parse("2026-09-29T08:30:00Z").epochSeconds))
        assertEquals(UsageLimitReset(date = "01.10.2026", time = "00:05"), reset(Instant.parse("2026-10-01T00:05:00Z").epochSeconds))
    }

    @Test
    fun `the day is the local calendar date, not a 24-hour difference`() {
        val helsinki = TimeZone.of("Europe/Helsinki")
        val lateEvening = Instant.parse("2026-09-23T20:30:00Z") // 23:30 local
        // Ten minutes later is past local midnight, so it carries its date.
        assertEquals(
            UsageLimitReset(date = "24.09.2026", time = "00:10"),
            formatUsageLimitReset(Instant.parse("2026-09-23T21:10:00Z").epochSeconds, lateEvening, helsinki),
        )
        // Nearly a day later in UTC terms, but still the same local date as a 00:10 reading.
        assertEquals(
            UsageLimitReset(date = null, time = "23:50"),
            formatUsageLimitReset(Instant.parse("2026-09-24T20:50:00Z").epochSeconds, Instant.parse("2026-09-23T21:10:00Z"), helsinki),
        )
    }

    @Test
    fun `a finite utilization in range rounds to the nearest whole percent`() {
        assertEquals(94, usageLimitSpentPercent(0.94))
        assertEquals(0, usageLimitSpentPercent(0.0))
        assertEquals(100, usageLimitSpentPercent(1.0))
        assertEquals(50, usageLimitSpentPercent(0.499)) // rounds up, not truncates
    }

    @Test
    fun `an absent utilization shows no percent, never zero`() {
        assertNull(usageLimitSpentPercent(null))
    }

    @Test
    fun `an out-of-range or non-finite utilization declines rather than clamping`() {
        assertNull(usageLimitSpentPercent(-0.5))
        assertNull(usageLimitSpentPercent(1.5))
        assertNull(usageLimitSpentPercent(Double.NaN))
        assertNull(usageLimitSpentPercent(Double.POSITIVE_INFINITY))
        assertNull(usageLimitSpentPercent(Double.NEGATIVE_INFINITY))
    }

    private fun reading(
        status: String,
        utilization: Double?,
    ): UsageLimitReading = UsageLimitReading(status, "seven_day", 0L, utilization, null)

    @Test
    fun `an other-than-rejected reading at exactly 75 percent stays hidden`() {
        assertFalse(usageLimitShouldShow(reading("allowed_warning", 0.75)))
    }

    @Test
    fun `an other-than-rejected reading just above 75 percent shows`() {
        assertTrue(usageLimitShouldShow(reading("allowed_warning", 0.76)))
    }

    @Test
    fun `a null utilization shows, since it cannot prove the window is comfortable`() {
        assertTrue(usageLimitShouldShow(reading("allowed_warning", null)))
    }

    @Test
    fun `a rejected reading always shows, regardless of utilization`() {
        assertTrue(usageLimitShouldShow(reading("rejected", 0.5)))
    }

    @Test
    fun `an out-of-range or non-finite utilization shows rather than clamping`() {
        assertTrue(usageLimitShouldShow(reading("allowed_warning", -0.1)))
        assertTrue(usageLimitShouldShow(reading("allowed_warning", 1.5)))
        assertTrue(usageLimitShouldShow(reading("allowed_warning", Double.NaN)))
    }
}
