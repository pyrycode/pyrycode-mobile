package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.datetime.Instant

/** The largest report token, in UTF-8 bytes, that is shown; a longer one is treated as absent (desktop's bound). */
private const val MAX_REPORT_BYTES = 256

private const val CANCELLED_STOP_REASON = "cancelled"
private const val SUCCESS_OUTCOME = "success"
private const val COMPLETED_TERMINAL_REASON = "completed"

/** The character types desktop's `stoppedReportText` removes: `\p{Cc}`, `\p{Cf}`, `\p{Zl}` and `\p{Zp}`. */
private val REMOVED_TYPES =
    setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )

/**
 * One agent-authored report token as inert display text (#1356), copied from desktop's `stoppedReportText`:
 * empty when [value] is over [MAX_REPORT_BYTES] UTF-8 bytes, else [value] with every control, format (the
 * bidi overrides and isolates among them), line-separator and paragraph-separator code point removed —
 * removed, not replaced, and never cut. Idempotent.
 */
internal fun stoppedReportText(value: String): String {
    if (utf8Length(value) > MAX_REPORT_BYTES) return ""
    val kept = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.getType(codePoint) !in REMOVED_TYPES) kept.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return kept.toString()
}

/** [value]'s UTF-8 length, counting a lone surrogate as the three bytes of the U+FFFD an encoder writes for it. */
private fun utf8Length(value: String): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        bytes +=
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        index += Character.charCount(codePoint)
    }
    return bytes
}

/**
 * The stopped-turn row this `turn_end` leaves in the thread (#1356), or `null` when the turn ended cleanly —
 * desktop's `stoppedTurnText` rule, which reads only the agent's own result fields: no row for a cancelled
 * turn, nor when [LiveSessionEvent.TurnEnd.isError] is false and the outcome is empty or `success`. The
 * daemon's early-stop `stop_reason`s add no row on their own.
 *
 * The reason is the terminal reason unless it is empty or `completed`; else the two budget outcomes map to
 * `max_turns` and `budget_exhausted`; else a reported category reads as `api_error`; else the outcome, with
 * `success` (an `is_error` turn) left empty.
 */
internal fun LiveSessionEvent.TurnEnd.stoppedTurn(occurredAt: Instant): ThreadItem.StoppedTurn? {
    if (stopReason == CANCELLED_STOP_REASON) return null
    val outcome = stoppedReportText(outcome)
    if (!isError && (outcome.isEmpty() || outcome == SUCCESS_OUTCOME)) return null
    val terminal = stoppedReportText(terminalReason)
    val category = stoppedReportText(errorCategory)
    val reason =
        when {
            terminal.isNotEmpty() && terminal != COMPLETED_TERMINAL_REASON -> terminal
            outcome == "error_max_turns" -> "max_turns"
            outcome == "error_max_budget_usd" -> "budget_exhausted"
            category.isNotEmpty() -> "api_error"
            outcome == SUCCESS_OUTCOME -> ""
            else -> outcome
        }
    return ThreadItem.StoppedTurn(turnId, reason, category, occurredAt)
}
