package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice.AuthenticationFailed
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice.BillingError
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice.ContextTooLong
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [turnRecoveryNotice] (#1357): which `turn_end` frames raise one of the three recovery
 * notices, desktop's `latestTurnEnd` and `ComposerErrorSlotControl` rule, and which raise none.
 */
class TurnRecoveryNoticeTest {
    private data class Case(
        val name: String,
        val event: LiveSessionEvent.TurnEnd,
        val expected: TurnRecoveryNotice?,
    )

    private val cases =
        listOf(
            // The three notices.
            Case("context overflow", turnEnd(isError = true, outcome = "success", terminalReason = "prompt_too_long"), ContextTooLong),
            Case("billing", turnEnd(isError = true, errorCategory = "billing_error"), BillingError),
            Case("authentication", turnEnd(isError = true, errorCategory = "authentication_failed"), AuthenticationFailed),
            // Either failure signal raises it on its own.
            Case("non-success outcome alone", turnEnd(outcome = "error_during_execution", errorCategory = "billing_error"), BillingError),
            Case("is_error alone", turnEnd(isError = true, terminalReason = "prompt_too_long"), ContextTooLong),
            // The context notice wins over a category.
            Case(
                "context over category",
                turnEnd(isError = true, terminalReason = "prompt_too_long", errorCategory = "authentication_failed"),
                ContextTooLong,
            ),
            // A cancelled turn shows nothing, whatever it carries.
            Case("cancelled context", turnEnd("cancelled", isError = true, terminalReason = "prompt_too_long"), null),
            Case("cancelled billing", turnEnd("cancelled", outcome = "error_during_execution", errorCategory = "billing_error"), null),
            Case("cancelled plain", turnEnd("cancelled"), null),
            // A clean turn shows nothing, even with a stale category or terminal reason.
            Case("clean", turnEnd(), null),
            Case("clean success", turnEnd(outcome = "success", terminalReason = "completed"), null),
            Case("clean with stale category", turnEnd(outcome = "success", errorCategory = "billing_error"), null),
            Case("clean with terminal reason", turnEnd(terminalReason = "prompt_too_long"), null),
            // Every other failed or early-stopped turn shows nothing.
            Case("max turns", turnEnd(outcome = "error_max_turns", terminalReason = "max_turns"), null),
            Case("refusal", turnEnd("refusal"), null),
            Case("max tokens", turnEnd("max_tokens", isError = true), null),
            Case("other category", turnEnd(isError = true, errorCategory = "rate_limit"), null),
            Case("bare failure", turnEnd(isError = true), null),
            Case("near-miss token", turnEnd(isError = true, terminalReason = "prompt_too_long ", errorCategory = "Billing_error"), null),
        )

    @Test
    fun theRuleMapsEachTurnEndToItsNotice() {
        for (case in cases) {
            assertEquals(case.name, case.expected, turnRecoveryNotice(case.event))
        }
    }

    private fun turnEnd(
        stopReason: String = "end_turn",
        outcome: String = "",
        isError: Boolean = false,
        terminalReason: String = "",
        errorCategory: String = "",
    ) = LiveSessionEvent.TurnEnd("c1", "t1", stopReason, outcome, isError, terminalReason, errorCategory)
}
