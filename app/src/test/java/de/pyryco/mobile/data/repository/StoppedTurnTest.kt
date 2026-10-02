package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stopped-turn rule (#1356), case for case against desktop's `stoppedTurn.test.tsx`: whether a `turn_end`
 * leaves a row, the reason token it carries, and the inert report text every token crosses.
 */
class StoppedTurnTest {
    private val failed = LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", isError = true)

    @Test
    fun terminalReason_isPreferredOverTheOutcome() {
        for (terminal in listOf("max_turns", "budget_exhausted", "prompt_too_long", "api_error", "hook_stopped", "model_error", "future")) {
            val row = failed.copy(terminalReason = terminal, outcome = "error_max_turns").stoppedTurn(AT)
            assertEquals(terminal, ThreadItem.StoppedTurn(TURN, terminal, "", AT), row)
        }
    }

    @Test
    fun completedTerminalReason_fallsBackToTheOutcome() {
        val cases =
            listOf(
                "error_max_turns" to "max_turns",
                "error_max_budget_usd" to "budget_exhausted",
                "future" to "future",
                "" to "",
                "success" to "",
            )
        for ((outcome, reason) in cases) {
            val row = failed.copy(outcome = outcome, terminalReason = "completed").stoppedTurn(AT)
            assertEquals(outcome, reason, row?.reason)
        }
    }

    @Test
    fun aNonSuccessOutcome_addsARowWithoutIsError() {
        val row = LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", outcome = "error_max_turns").stoppedTurn(AT)

        assertEquals("max_turns", row?.reason)
    }

    @Test
    fun cancellation_cleanSuccess_andALegacyTurn_addNothing() {
        assertNull(LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn").stoppedTurn(AT))
        assertNull(LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", outcome = "success").stoppedTurn(AT))
        assertNull(failed.copy(stopReason = "cancelled", outcome = "error_max_turns").stoppedTurn(AT))
    }

    @Test
    fun earlyStopReasons_addNothingOnTheirOwn() {
        for (stopReason in listOf("max_tokens", "max_turn_requests", "refusal")) {
            assertNull(stopReason, LiveSessionEvent.TurnEnd(CONVERSATION, TURN, stopReason, outcome = "success").stoppedTurn(AT))
        }
    }

    @Test
    fun aCategory_readsAsAnApiError_andIsCarried() {
        val row = LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", outcome = "error_during_execution", errorCategory = "overloaded")

        assertEquals(ThreadItem.StoppedTurn(TURN, "api_error", "overloaded", AT), row.stoppedTurn(AT))
    }

    @Test
    fun aCategoryAloneOnACleanTurn_addsNothing() {
        assertNull(
            LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", outcome = "success", errorCategory = "overloaded").stoppedTurn(AT),
        )
    }

    @Test
    fun anOutcomeThatSanitizesToEmpty_addsNothingWithoutIsError() {
        assertNull(LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn", outcome = "\u0000\u202E\n").stoppedTurn(AT))
    }

    @Test
    fun hostileTokens_reachTheRowInert() {
        val row = failed.copy(terminalReason = "<img>\nfuture\u0000", errorCategory = "\u202Ebad\u2028").stoppedTurn(AT)

        assertEquals(ThreadItem.StoppedTurn(TURN, "<img>future", "bad", AT), row)
    }

    @Test
    fun anOversizedTerminalReason_isAbsent() {
        assertEquals("", failed.copy(terminalReason = "x".repeat(257)).stoppedTurn(AT)?.reason)
    }

    @Test
    fun reportText_removesControlFormatAndSeparatorCharacters() {
        // Line breaks, NUL, DEL, ESC, a bidi override and isolate, the zero-width joiner, the two
        // separators, and an astral format character (U+E0001 LANGUAGE TAG) all go; nothing replaces them.
        val hostile = "a\nb\r\u0000c\u007F\u001B\u202Ed\u2066e\u200Df\u2028g\u2029h\uDB40\uDC01i\u0085j"

        assertEquals("abcdefghij", stoppedReportText(hostile))
    }

    @Test
    fun reportText_keepsPrintableText_withoutCuttingIt() {
        val long = "error_" + "y".repeat(200)

        assertEquals(long, stoppedReportText(long))
        // A tab is a control character too, as in desktop's `\p{Cc}`.
        assertEquals("Ünïcödé 😀 tabgone", stoppedReportText("Ünïcödé 😀 tab\tgone"))
    }

    @Test
    fun reportText_measuresTheBoundInUtf8Bytes() {
        assertEquals("x".repeat(256), stoppedReportText("x".repeat(256)))
        assertEquals("", stoppedReportText("x".repeat(257)))
        // 85 three-byte characters are 255 bytes; one more ASCII byte is 256, two more are 257.
        val wide = "€".repeat(85)
        assertEquals("${wide}a", stoppedReportText("${wide}a"))
        assertEquals("", stoppedReportText("${wide}ab"))
        // A four-byte emoji counts four bytes, not its two UTF-16 units.
        assertEquals("", stoppedReportText("😀".repeat(64) + "a"))
        assertEquals("😀".repeat(64), stoppedReportText("😀".repeat(64)))
    }

    @Test
    fun reportText_countsALoneSurrogateAsThreeBytes_asTheEncoderDoes() {
        assertEquals("", stoppedReportText("\uD800" + "x".repeat(254)))
        assertEquals("\uD800" + "x".repeat(253), stoppedReportText("\uD800" + "x".repeat(253)))
    }

    private companion object {
        const val CONVERSATION = "c1"
        const val TURN = "turn-1"
        val AT: Instant = Instant.parse("2026-10-02T10:00:00Z")
    }
}
