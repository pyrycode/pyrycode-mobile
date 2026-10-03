package de.pyryco.mobile.ui.conversations.components

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
