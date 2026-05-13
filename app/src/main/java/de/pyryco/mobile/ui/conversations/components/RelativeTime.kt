package de.pyryco.mobile.ui.conversations.components

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val sameYearFormat =
    LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        dayOfMonth()
    }

private val crossYearFormat =
    LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        dayOfMonth()
        chars(", ")
        year()
    }

internal fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val delta = now - instant
    if (delta.isNegative() || delta < 1.minutes) return "just now"
    if (delta < 1.hours) return "${delta.inWholeMinutes}m ago"
    if (delta < 24.hours) return "${delta.inWholeHours}h ago"
    if (delta < 48.hours) return "Yesterday"
    if (delta < 7.days) return "${delta.inWholeDays}d ago"

    val instantDate = instant.toLocalDateTime(timeZone).date
    val nowDate = now.toLocalDateTime(timeZone).date
    return if (instantDate.year == nowDate.year) {
        sameYearFormat.format(instantDate)
    } else {
        crossYearFormat.format(instantDate)
    }
}
