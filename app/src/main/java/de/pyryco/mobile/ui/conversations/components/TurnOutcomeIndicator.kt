package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/** The most characters of one claude-authored token this row shows; the daemon's 256-byte bound is not a layout bound. */
private const val MAX_TOKEN_CHARS = 40

private const val ELLIPSIS = "…"

/** claude's own subtype for a clean stop; every other non-empty `outcome` is a report worth showing. */
private const val SUCCESS_OUTCOME = "success"

/** claude's `terminal_reason` for a clean stop, which says nothing a reader needs. */
private const val COMPLETED_TERMINAL_REASON = "completed"

private const val CANCELLED_STOP_REASON = "cancelled"

/** The daemon's documented `stop_reason` values, besides `cancelled`, that end a turn short of an answer. */
private val EARLY_STOP_REASONS = setOf("max_tokens", "max_turn_requests", "refusal")

/**
 * How a turn that did not finish cleanly ended (#805), ready to render: every string in it has already
 * crossed [turnOutcomeReport]'s sanitizer, so nothing downstream holds claude's raw text.
 *
 * [claudeReports] are claude's own tokens for the stop, and [apiErrorCategory] the API error claude says a
 * message in the turn reported. Both are claude's account, and the row attributes them to claude.
 */
data class TurnOutcomeReport(
    val kind: Kind,
    val claudeReports: List<String>,
    val apiErrorCategory: String?,
) {
    enum class Kind { Interrupted, Failed, StoppedEarly }
}

/**
 * The status report for [event], or `null` when the turn stopped cleanly.
 *
 * Each signal is read on its own and none is inferred from another: the daemon's `cancelled`, claude's
 * `is_error` flag (so `outcome = "success"` with `is_error = true` is a failure), a non-success `outcome`
 * (so `stop_reason = "end_turn"` with `outcome = "error_max_turns"` is shown, not resolved in favour of
 * either), and the daemon's early-stop reasons. `error_category` and `terminal_reason` never raise the
 * arm alone — a clean turn may carry a category from an API error it recovered from — but are shown once
 * it is raised.
 */
internal fun turnOutcomeReport(event: LiveSessionEvent.TurnEnd): TurnOutcomeReport? {
    val cancelled = event.stopReason == CANCELLED_STOP_REASON
    val earlyStop = event.stopReason in EARLY_STOP_REASONS
    val nonSuccessOutcome = event.outcome.isNotEmpty() && event.outcome != SUCCESS_OUTCOME
    if (!cancelled && !event.isError && !nonSuccessOutcome && !earlyStop) return null
    val kind =
        when {
            cancelled -> TurnOutcomeReport.Kind.Interrupted
            event.isError -> TurnOutcomeReport.Kind.Failed
            else -> TurnOutcomeReport.Kind.StoppedEarly
        }
    val reports =
        listOfNotNull(
            event.outcome.takeIf { it != SUCCESS_OUTCOME }?.let(::inertOutcomeToken),
            event.terminalReason.takeIf { it != COMPLETED_TERMINAL_REASON }?.let(::inertOutcomeToken),
            event.stopReason.takeIf { earlyStop }?.let(::inertOutcomeToken),
        ).distinct()
    return TurnOutcomeReport(kind, reports, inertOutcomeToken(event.errorCategory))
}

/**
 * One claude-authored token as inert display text, or `null` when nothing printable is left. ISO-control
 * characters (terminal escapes, line breaks) and Unicode format characters (the bidi overrides, which could
 * visually reorder the client-owned lead) become spaces; the result is cut to [MAX_TOKEN_CHARS] with an
 * ellipsis so a cut value never reads as complete.
 */
private fun inertOutcomeToken(raw: String): String? {
    val printable =
        raw
            .map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("")
            .trim()
    if (printable.isEmpty()) return null
    return if (printable.length > MAX_TOKEN_CHARS) printable.take(MAX_TOKEN_CHARS) + ELLIPSIS else printable
}

/**
 * Status-area arm for a turn that failed or was interrupted (#805), hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.turnOutcome], so the turn no longer ends
 * looking like a clean answer.
 *
 * The lead is client-owned; everything the agent said about the stop follows "<agent> reports", naming the
 * conversation's [agent] (#1113), so an account state such as `billing_error` never reads as the app's own
 * finding. Stateless and total: it emits
 * nothing for `null`, the sibling early-return idiom. The merged content description is the visible label.
 */
@Composable
fun TurnOutcomeIndicator(
    report: TurnOutcomeReport?,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    if (report == null) return
    val name = agentName(agent)
    val details =
        buildList {
            addAll(report.claudeReports)
            report.apiErrorCategory?.let { add(stringResource(R.string.thread_turn_outcome_api_error, it)) }
        }
    val label =
        buildString {
            append(
                stringResource(
                    when (report.kind) {
                        TurnOutcomeReport.Kind.Interrupted -> R.string.thread_turn_outcome_interrupted
                        TurnOutcomeReport.Kind.Failed -> R.string.thread_turn_outcome_failed
                        TurnOutcomeReport.Kind.StoppedEarly -> R.string.thread_turn_outcome_stopped
                    },
                ),
            )
            when {
                details.isNotEmpty() ->
                    append(stringResource(R.string.thread_turn_outcome_agent_reports, name, details.joinToString(", ")))
                report.kind == TurnOutcomeReport.Kind.Failed ->
                    append(stringResource(R.string.thread_turn_outcome_agent_reports_error, name))
            }
        }
    Row(modifier = modifier.fillMaxWidth()) {
        NoticePill(
            text = label,
            isError = true,
            contentDescription = label,
            shadowElevation = 0.dp,
            leadingIcon =
                if (report.kind == TurnOutcomeReport.Kind.Interrupted) {
                    Icons.Outlined.StopCircle
                } else {
                    Icons.Outlined.ErrorOutline
                },
            maxLines = 2,
        )
    }
}

@Preview(name = "TurnOutcomeIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun TurnOutcomeIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            TurnOutcomeIndicator(
                report = TurnOutcomeReport(TurnOutcomeReport.Kind.Failed, listOf("prompt_too_long"), "invalid_request"),
                agent = ConversationAgent.Claude,
            )
        }
    }
}

@Preview(
    name = "TurnOutcomeIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TurnOutcomeIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            TurnOutcomeIndicator(
                report = TurnOutcomeReport(TurnOutcomeReport.Kind.Interrupted, emptyList(), null),
                agent = ConversationAgent.Codex,
            )
        }
    }
}
