package de.pyryco.mobile.ui.conversations.components

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * `formatShortDateTime` (#644) — the meta row's timestamp.
 *
 * Assertions pin *composition and order*, never a formatted literal: each expected half is computed in
 * the test through the same `DateTimeFormatter.ofLocalized*` API the production formatter uses, mirroring
 * why `SessionBoundaryDelimiterTest` asserts label prefixes rather than times. A test that hardcoded
 * `13.01.2026 - 13:55` would pass on a de-DE JVM and redden everywhere else, and would still pass if the
 * production code grew the hardcoded pattern the ticket forbids.
 */
class MessageMetaRowFormatTest {
    private val instant = Instant.parse("2026-01-13T12:55:00Z")
    private val zone = TimeZone.of("Europe/Berlin")

    private fun expectedDate(locale: Locale): String =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.toLocalDateTime(zone).date.toJavaLocalDate())

    private fun expectedTime(locale: Locale): String =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.toLocalDateTime(zone).time.toJavaLocalTime())

    @Test
    fun joinsLocalizedShortDateAndShortTimeInThatOrder() {
        val locale = Locale.GERMANY

        val formatted = formatShortDateTime(instant, zone, locale)

        val date = expectedDate(locale)
        val time = expectedTime(locale)
        assertTrue("expected '$date' in '$formatted'", formatted.contains(date))
        assertTrue("expected '$time' in '$formatted'", formatted.contains(time))
        assertTrue(
            "date must precede time in '$formatted' (date=$date, time=$time)",
            formatted.indexOf(date) < formatted.indexOf(time),
        )
    }

    @Test
    fun joinsTheTwoHalvesWithTheDesignsSeparator() {
        val locale = Locale.GERMANY

        val formatted = formatShortDateTime(instant, zone, locale)

        assertEquals("${expectedDate(locale)} - ${expectedTime(locale)}", formatted)
    }

    @Test
    fun followsTheSuppliedLocaleRatherThanAFixedPattern() {
        val german = formatShortDateTime(instant, zone, Locale.GERMANY)
        val us = formatShortDateTime(instant, zone, Locale.US)

        // Two locales with different short-date and short-time conventions must not agree. A hardcoded
        // pattern — the thing the ticket forbids — would make these identical.
        assertNotEquals(german, us)
        assertTrue("expected a US short date in '$us'", us.contains(expectedDate(Locale.US)))
        assertTrue("expected a US short time in '$us'", us.contains(expectedTime(Locale.US)))
    }

    @Test
    fun followsTheSuppliedTimeZone() {
        val berlin = formatShortDateTime(instant, TimeZone.of("Europe/Berlin"), Locale.GERMANY)
        val tokyo = formatShortDateTime(instant, TimeZone.of("Asia/Tokyo"), Locale.GERMANY)

        // 12:55Z is the 13th in Berlin (UTC+1) and the 13th at 21:55 in Tokyo (UTC+9) — same date,
        // different time, so the zone must reach both halves of the formatter.
        assertNotEquals(berlin, tokyo)
    }

    @Test
    fun neverEmitsTheFigmaSampleLiteralForAnUnrelatedLocale() {
        // The design's sample string is `13.01.2026 - 13:55`; the instant under test is 13:55 local in
        // Berlin, so a de-DE render legitimately reproduces it. Any other locale must not.
        assertNotEquals(FIGMA_SAMPLE, formatShortDateTime(instant, zone, Locale.US))
        assertNotEquals(FIGMA_SAMPLE, formatShortDateTime(instant, zone, Locale.JAPAN))
    }

    private companion object {
        const val FIGMA_SAMPLE = "13.01.2026 - 13:55"
    }
}
