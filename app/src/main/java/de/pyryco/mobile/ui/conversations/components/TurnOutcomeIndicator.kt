package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
 * The recovery advice the top overlay offers after a stopped turn (#1357), desktop's `ComposerErrorSlotControl`
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
 * Top-overlay pill for a stopped turn's recovery advice (#1357), hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.turnOutcome]. All copy is client-owned; the
 * billing and sign-in notices name the conversation's [agent] (#1113).
 *
 * The whole context pill runs [onCompact]; a `null` [onCompact], the command being
 * absent from the published menu, leaves the pill with no click action. Stateless and total: it emits
 * no recovery for `null`. [followingNotice] stays 12dp below the visible pill and intercepts taps
 * on its inert surface without inheriting Compact's action.
 */
@Composable
fun TurnOutcomeIndicator(
    notice: TurnRecoveryNotice?,
    agent: ConversationAgent,
    onCompact: (() -> Unit)?,
    modifier: Modifier = Modifier,
    followingNotice: (@Composable () -> Unit)? = null,
) {
    if (notice == null) {
        followingNotice?.invoke()
        return
    }
    val label =
        when (notice) {
            TurnRecoveryNotice.ContextTooLong -> stringResource(R.string.thread_recovery_context)
            TurnRecoveryNotice.BillingError -> stringResource(R.string.thread_recovery_billing, agentName(agent))
            TurnRecoveryNotice.AuthenticationFailed -> stringResource(R.string.thread_recovery_auth, agentName(agent))
        }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        val action = onCompact.takeIf { notice == TurnRecoveryNotice.ContextTooLong }
        // Measure the visible surface before expanding Compact's target. A following inert notice
        // keeps the 12dp visible gap and owns its pixels where the 48dp target extends beneath it.
        Layout(
            modifier = modifier,
            content = {
                Box(
                    modifier = if (action != null) Modifier.clickable(role = Role.Button, onClick = action) else Modifier,
                    contentAlignment = Alignment.TopEnd,
                ) {
                    // API 35 hugs this label at 176dp, versus Figma 685:3992's 172dp Roboto metrics.
                    NoticePill(
                        text = label,
                        isError = true,
                        mergeDescendants = action == null,
                        modifier = Modifier.sizeIn(minHeight = 24.dp),
                    )
                }
                if (followingNotice != null) {
                    // This is a touch barrier, not an action or accessible button.
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { followingNotice() }
                }
            },
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val visibleHeight = measurables[0].minIntrinsicHeight(constraints.maxWidth)
            val target =
                measurables[0].measure(
                    childConstraints.copy(
                        minHeight = if (action != null) maxOf(48.dp.roundToPx(), visibleHeight).coerceAtMost(constraints.maxHeight) else 0,
                    ),
                )
            val following = measurables.getOrNull(1)?.measure(childConstraints)
            val followingTop = visibleHeight + 12.dp.roundToPx()
            val width = maxOf(target.width, following?.width ?: 0)
            val height = maxOf(target.height, following?.let { followingTop + it.height } ?: 0)
            layout(width, height) {
                target.placeRelative(width - target.width, 0)
                following?.placeRelative(width - following.width, followingTop)
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
