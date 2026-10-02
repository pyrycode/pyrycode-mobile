package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private const val CANCELLED_STOP_REASON = "cancelled"

/** claude's own subtype for a clean stop; every other non-empty `outcome` marks the turn as stopped. */
private const val SUCCESS_OUTCOME = "success"

private const val PROMPT_TOO_LONG = "prompt_too_long"

private const val BILLING_ERROR = "billing_error"

private const val AUTHENTICATION_FAILED = "authentication_failed"

/**
 * The recovery advice the status area offers after a stopped turn (#1357), desktop's `ComposerErrorSlotControl`
 * copy. Since #1356 the thread's stopped-turn row tells what happened; this says only what to do about it.
 */
enum class TurnRecoveryNotice { ContextTooLong, BillingError, AuthenticationFailed }

/**
 * The advice for [event], desktop's `latestTurnEnd` rule: a turn that was not cancelled and that `is_error`
 * or a non-empty, non-`success` `outcome` marks as stopped gets the context notice for `terminal_reason`
 * `prompt_too_long`, else the billing or sign-in notice for its `error_category`. Every other turn gets
 * `null`. The daemon's tokens are only compared, never shown, so nothing here needs sanitizing.
 */
internal fun turnRecoveryNotice(event: LiveSessionEvent.TurnEnd): TurnRecoveryNotice? {
    if (event.stopReason == CANCELLED_STOP_REASON) return null
    val stopped = event.isError || (event.outcome.isNotEmpty() && event.outcome != SUCCESS_OUTCOME)
    if (!stopped) return null
    return when {
        event.terminalReason == PROMPT_TOO_LONG -> TurnRecoveryNotice.ContextTooLong
        event.errorCategory == BILLING_ERROR -> TurnRecoveryNotice.BillingError
        event.errorCategory == AUTHENTICATION_FAILED -> TurnRecoveryNotice.AuthenticationFailed
        else -> null
    }
}

/**
 * Status-area arm for a stopped turn's recovery advice (#1357), hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.turnOutcome]. All copy is client-owned; the
 * billing and sign-in notices name the conversation's [agent] (#1113).
 *
 * The context notice carries a Compact pill that runs [onCompact]; a `null` [onCompact], the command being
 * absent from the published menu, leaves the pill with no click action. Stateless and total: it emits
 * nothing for `null`, the sibling early-return idiom.
 */
@Composable
fun TurnOutcomeIndicator(
    notice: TurnRecoveryNotice?,
    agent: ConversationAgent,
    onCompact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (notice == null) return
    val label =
        when (notice) {
            TurnRecoveryNotice.ContextTooLong -> stringResource(R.string.thread_recovery_context)
            TurnRecoveryNotice.BillingError -> stringResource(R.string.thread_recovery_billing, agentName(agent))
            TurnRecoveryNotice.AuthenticationFailed -> stringResource(R.string.thread_recovery_auth, agentName(agent))
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NoticePill(
            text = label,
            isError = true,
            modifier = Modifier.weight(1f, fill = false),
            shadowElevation = 0.dp,
            leadingIcon = Icons.Outlined.ErrorOutline,
            maxLines = 2,
        )
        if (notice == TurnRecoveryNotice.ContextTooLong) {
            // The band is 24dp, the pill's own height, as for the task pill beside it.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                NoticePill(
                    text = stringResource(R.string.thread_recovery_compact),
                    isError = false,
                    onClick = onCompact,
                    modifier = Modifier.sizeIn(minHeight = 24.dp),
                    shadowElevation = 0.dp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Preview(name = "TurnOutcomeIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun TurnOutcomeIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            TurnOutcomeIndicator(
                notice = TurnRecoveryNotice.ContextTooLong,
                agent = ConversationAgent.Claude,
                onCompact = {},
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
                notice = TurnRecoveryNotice.BillingError,
                agent = ConversationAgent.Codex,
                onCompact = null,
            )
        }
    }
}
