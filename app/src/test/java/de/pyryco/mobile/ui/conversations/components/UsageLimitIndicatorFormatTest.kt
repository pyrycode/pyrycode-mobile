package de.pyryco.mobile.ui.conversations.components

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The render-or-decline helpers behind `UsageLimitIndicator` (#804). Each untrusted field of the
 * `rate_limited` reading is either rendered as the client's own inert text or declined to `null` —
 * never clamped, never defaulted to zero, never formatted as a date without a range check.
 */
class UsageLimitIndicatorFormatTest {
    // ---- status: claude's open string as an opaque, inert label ----------------------------------

    @Test
    fun `status passes through verbatim`() {
        assertEquals("allowed_warning", usageLimitStatusLabel("allowed_warning", truncatedFields = null))
    }

    @Test
    fun `status strips control and bidi-override characters`() {
        // U+202E (RIGHT-TO-LEFT OVERRIDE) would visually reorder the client-owned clauses after it.
        assertEquals("a b c", usageLimitStatusLabel("a\nb‮c", truncatedFields = null))
    }

    @Test
    fun `status longer than the display cap is cut with an ellipsis`() {
        val label = usageLimitStatusLabel("x".repeat(100), truncatedFields = null)
        assertEquals("x".repeat(40) + "…", label)
    }

    @Test
    fun `status the daemon reports as truncated carries an ellipsis`() {
        assertEquals("rejec…", usageLimitStatusLabel("rejec", truncatedFields = listOf("status")))
    }

    @Test
    fun `truncation of another field does not mark the status`() {
        assertEquals("rejected", usageLimitStatusLabel("rejected", truncatedFields = listOf("limit_type")))
    }

    @Test
    fun `status with nothing printable declines`() {
        assertNull(usageLimitStatusLabel("", truncatedFields = null))
        assertNull(usageLimitStatusLabel(" \n‮ ", truncatedFields = null))
    }

    // ---- utilization: absent is not zero, out of range drives nothing ---------------------------

    @Test
    fun `absent utilization renders no spent figure`() {
        assertNull(usageLimitSpentPercent(null))
    }

    @Test
    fun `in-range utilization renders a percent`() {
        assertEquals(94, usageLimitSpentPercent(0.94))
        assertEquals(0, usageLimitSpentPercent(0.0))
        assertEquals(100, usageLimitSpentPercent(1.0))
    }

    @Test
    fun `out-of-range or non-finite utilization declines`() {
        assertNull(usageLimitSpentPercent(-0.1))
        assertNull(usageLimitSpentPercent(1.5))
        assertNull(usageLimitSpentPercent(94.0))
        assertNull(usageLimitSpentPercent(Double.NaN))
        assertNull(usageLimitSpentPercent(Double.POSITIVE_INFINITY))
    }

    // ---- resets_at: claude's number, range-checked before any date exists -----------------------

    private val now = Instant.parse("2026-09-23T10:00:00Z")
    private val utc = TimeZone.UTC

    private fun reset(resetsAt: Long): String? = formatUsageLimitReset(resetsAt, now, utc, Locale.GERMANY)

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
        assertEquals("15:30", reset(Instant.parse("2026-09-23T15:30:00Z").epochSeconds))
    }

    @Test
    fun `a reset on another day renders date and time`() {
        assertEquals("29.09.26, 08:30", reset(Instant.parse("2026-09-29T08:30:00Z").epochSeconds))
    }
}
