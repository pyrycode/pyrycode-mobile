package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [turnOutcomeReport] (#805): which `turn_end` frames raise the status arm, which kind
 * they raise, and that every claude-authored token reaching the report is inert, bounded text.
 */
class TurnOutcomeReportTest {
    // ---- clean stops raise nothing ----------------------------------------------------------------

    @Test
    fun cleanEndTurn_withNoStopShape_isNull() {
        assertNull(turnOutcomeReport(turnEnd()))
    }

    @Test
    fun cleanEndTurn_withClaudesSuccessShape_isNull() {
        assertNull(turnOutcomeReport(turnEnd(outcome = "success", terminalReason = "completed")))
    }

    // A clean turn may carry a category from an API error it recovered from; the category alone is not a
    // failed turn, and the protocol says it can be one turn stale.
    @Test
    fun errorCategoryAlone_isNull() {
        assertNull(turnOutcomeReport(turnEnd(outcome = "success", errorCategory = "rate_limit")))
    }

    @Test
    fun unrecognisedStopReason_alone_isNull() {
        assertNull(turnOutcomeReport(turnEnd(stopReason = "some_future_reason")))
    }

    // ---- failures and interruptions ---------------------------------------------------------------

    // The documented context-overflow case: `outcome` says success, `is_error` says otherwise.
    @Test
    fun successOutcome_withIsError_isAFailure() {
        val report = turnOutcomeReport(turnEnd(outcome = "success", isError = true, terminalReason = "prompt_too_long"))

        assertEquals(TurnOutcomeReport(Kind.Failed, listOf("prompt_too_long"), null), report)
    }

    @Test
    fun isErrorAlone_isAFailureWithNoDetail() {
        assertEquals(TurnOutcomeReport(Kind.Failed, emptyList(), null), turnOutcomeReport(turnEnd(isError = true)))
    }

    @Test
    fun cancelled_isAnInterruption() {
        assertEquals(
            TurnOutcomeReport(Kind.Interrupted, emptyList(), null),
            turnOutcomeReport(turnEnd(stopReason = "cancelled")),
        )
    }

    @Test
    fun cancelled_winsOverIsError_andKeepsClaudesDetail() {
        val report =
            turnOutcomeReport(
                turnEnd(stopReason = "cancelled", outcome = "error_during_execution", isError = true, terminalReason = "aborted_tools"),
            )

        assertEquals(TurnOutcomeReport(Kind.Interrupted, listOf("error_during_execution", "aborted_tools"), null), report)
    }

    // stop_reason and outcome disagree by design; neither is preferred.
    @Test
    fun endTurn_withErrorMaxTurnsOutcome_isShown() {
        val report = turnOutcomeReport(turnEnd(stopReason = "end_turn", outcome = "error_max_turns", terminalReason = "max_turns"))

        assertEquals(TurnOutcomeReport(Kind.StoppedEarly, listOf("error_max_turns", "max_turns"), null), report)
    }

    @Test
    fun unrecognisedNonSuccessOutcome_isShown() {
        assertEquals(
            TurnOutcomeReport(Kind.StoppedEarly, listOf("error_from_a_newer_claude"), null),
            turnOutcomeReport(turnEnd(outcome = "error_from_a_newer_claude")),
        )
    }

    @Test
    fun refusalStopReason_isAnEarlyStop_namingTheReason() {
        assertEquals(
            TurnOutcomeReport(Kind.StoppedEarly, listOf("refusal"), null),
            turnOutcomeReport(turnEnd(stopReason = "refusal")),
        )
    }

    @Test
    fun errorCategory_isCarriedOnceTheArmIsRaised() {
        val report = turnOutcomeReport(turnEnd(isError = true, errorCategory = "billing_error"))

        assertEquals(TurnOutcomeReport(Kind.Failed, emptyList(), "billing_error"), report)
    }

    // ---- inert text -------------------------------------------------------------------------------

    @Test
    fun controlAndFormatCharacters_becomeSpaces() {
        val report = turnOutcomeReport(turnEnd(isError = true, outcome = "a\u001b[31mb\nc‮d\r"))

        assertEquals(listOf("a [31mb c d"), report?.claudeReports)
    }

    @Test
    fun aTokenWithNothingPrintable_isDropped() {
        val report = turnOutcomeReport(turnEnd(isError = true, terminalReason = "\u001b\n‮ ", errorCategory = "\u0007"))

        assertEquals(TurnOutcomeReport(Kind.Failed, emptyList(), null), report)
    }

    @Test
    fun aLongToken_isCutWithAnEllipsis() {
        val report = turnOutcomeReport(turnEnd(isError = true, errorCategory = "x".repeat(41)))

        assertEquals("x".repeat(40) + "…", report?.apiErrorCategory)
    }

    @Test
    fun a40CharacterToken_isNotCut() {
        val report = turnOutcomeReport(turnEnd(isError = true, errorCategory = "x".repeat(40)))

        assertEquals("x".repeat(40), report?.apiErrorCategory)
    }

    @Test
    fun duplicateTokens_collapse() {
        val report = turnOutcomeReport(turnEnd(outcome = "error_max_turns", terminalReason = "error_max_turns"))

        assertEquals(listOf("error_max_turns"), report?.claudeReports)
    }

    private fun turnEnd(
        stopReason: String = "end_turn",
        outcome: String = "",
        isError: Boolean = false,
        terminalReason: String = "",
        errorCategory: String = "",
    ) = LiveSessionEvent.TurnEnd("c1", "t1", stopReason, outcome, isError, terminalReason, errorCategory)
}
