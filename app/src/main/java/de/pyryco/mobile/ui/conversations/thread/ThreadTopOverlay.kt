package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeIndicator
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.conversations.components.boundMcpText
import de.pyryco.mobile.ui.conversations.components.usageLimitIsWarning
import de.pyryco.mobile.ui.conversations.components.usageLimitLabel

// Figma 541:2446: the pills stack 12dp apart.
private val OverlayPillGap = 12.dp

/**
 * Figma `533:1956`'s `Top overlay` (#1002): the thread's notices as a right-aligned stack of pills pinned
 * to the top of the message area, drawn over the messages so it takes no layout space. Notices live here
 * rather than in the status row, so they never hide what the running turn is doing.
 *
 * Top to bottom: other-conversation attention, the usage-limit report, a failed MCP server,
 * a pairing error or offline retry, then a session error. The attention pill has no X. The
 * report is a Default pill with an X only when [usageLimitIsWarning] says so, and it is left out once
 * [usageLimitDismissed]; any other reading is an Error pill that cannot be hidden. Pairing failure takes
 * precedence over the offline pill because a network retry cannot repair a rejected pairing. With none of
 * them, nothing is emitted. Session errors and stopped-turn recovery advice follow these persistent
 * notices; [transientError] follows all persistent notices and expires independently under the screen's queue.
 *
 * [mcpFailure] (#1345) is the Claude-authored name of a failed MCP server: an Error pill with no X whose tap
 * runs [onOpenMcpFailure]. It is never drawn beside the pairing or offline pill.
 */
@Composable
internal fun ThreadTopOverlay(
    usageLimit: UsageLimitReading?,
    usageLimitDismissed: Boolean,
    onDismissUsageLimit: () -> Unit,
    showRePair: Boolean,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: ConnectionState = ConnectionState.Connected,
    onRetryConnection: () -> Unit = {},
    mcpFailure: String? = null,
    onOpenMcpFailure: () -> Unit = {},
    sessionError: String? = null,
    agent: ConversationAgent = ConversationAgent.Claude,
    turnOutcome: TurnRecoveryNotice? = null,
    onCompact: (() -> Unit)? = null,
    transientError: String? = null,
    transientErrorOccurrence: Long = 0L,
    attentionPill: (@Composable () -> Unit)? = null,
) {
    val usage = usageLimit?.takeUnless { usageLimitDismissed }
    val showOffline = connectionState == ConnectionState.Offline && !showRePair
    val mcp = mcpFailure?.takeUnless { showRePair || showOffline }
    if (attentionPill == null &&
        usage == null &&
        mcp == null &&
        !showRePair &&
        !showOffline &&
        sessionError == null &&
        turnOutcome == null &&
        transientError == null
    ) {
        return
    }
    val viewConfiguration = LocalViewConfiguration.current
    val pillTouchConfiguration =
        remember(viewConfiguration) {
            object : ViewConfiguration by viewConfiguration {
                // Keep the dismiss X's expanded vertical target and the platform horizontal width.
                // The adjacent Re-pair action uses its own visible-height target below.
                override val minimumTouchTargetSize = DpSize(viewConfiguration.minimumTouchTargetSize.width, 36.dp)
            }
        }
    val pairingTouchConfiguration =
        remember(viewConfiguration) {
            object : ViewConfiguration by viewConfiguration {
                // Use the measured surface height vertically; native text height need not be 24dp.
                override val minimumTouchTargetSize = DpSize(viewConfiguration.minimumTouchTargetSize.width, 0.dp)
            }
        }
    // Keep the clickable error pill at the design's 24dp visible height. The default Material layout
    // minimum inserted an extra 12dp above and below it, shifting the visible stack out of position.
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
        LocalViewConfiguration provides pillTouchConfiguration,
    ) {
        val followingErrors: @Composable () -> Unit = {
            if (sessionError != null) {
                NoticePill(text = sessionErrorLabel(sessionError, agent), isError = true)
            }
            TurnOutcomeIndicator(
                notice = turnOutcome,
                agent = agent,
                onCompact = onCompact,
                followingNotice =
                    transientError?.let { text ->
                        { key(transientErrorOccurrence) { TransientErrorPill(text) } }
                    },
            )
        }
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(OverlayPillGap),
        ) {
            attentionPill?.invoke()
            if (usage != null) {
                val warning = usageLimitIsWarning(usage)
                NoticePill(
                    text = usageLimitLabel(usage),
                    isError = !warning,
                    onDismiss = if (warning) onDismissUsageLimit else null,
                )
            }
            if (mcp != null) {
                // Bounded, and at most two lines, so a long Claude-authored name cannot cover the thread.
                NoticePill(
                    text = stringResource(R.string.thread_mcp_server_failed, boundMcpText(mcp)),
                    isError = true,
                    onClick = onOpenMcpFailure,
                    maxLines = 2,
                )
            }
            if (showRePair) {
                // The label is a local resource, never daemon text.
                CompositionLocalProvider(LocalViewConfiguration provides pairingTouchConfiguration) {
                    NoticePill(text = stringResource(R.string.thread_re_pair), isError = true, onClick = onRePair)
                }
            } else if (showOffline) {
                val offlineLabel = stringResource(R.string.thread_connection_offline_retry)
                // Measure spacing from the visible pill, independently of Retry's 48dp touch box.
                // Draw the target over Offline but under following inert errors, so tapping an error
                // cannot trigger Retry. The parent encloses the full target for bottom-edge hits.
                Layout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        NoticePill(
                            text = offlineLabel,
                            isError = true,
                            contentDescription = "",
                            modifier = Modifier.semantics { hideFromAccessibility() },
                        )
                        Box(
                            Modifier
                                .testTag("offline_retry_target")
                                .semantics { contentDescription = offlineLabel }
                                .clickable(role = Role.Button, onClick = onRetryConnection),
                        )
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(OverlayPillGap),
                        ) { followingErrors() }
                    },
                ) { measurables, constraints ->
                    val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                    val pill = measurables[0].measure(childConstraints)
                    val target =
                        measurables[1].measure(
                            childConstraints.copy(
                                minWidth = maxOf(144.dp.roundToPx(), pill.width).coerceAtMost(constraints.maxWidth),
                                minHeight = maxOf(48.dp.roundToPx(), pill.height).coerceAtMost(constraints.maxHeight),
                            ),
                        )
                    val following = measurables[2].measure(childConstraints)
                    val followingTop = pill.height + OverlayPillGap.roundToPx()
                    val visibleHeight = if (following.height > 0) followingTop + following.height else pill.height
                    layout(constraints.maxWidth, maxOf(target.height, visibleHeight)) {
                        pill.placeRelative(constraints.maxWidth - pill.width, 0)
                        target.placeRelative(constraints.maxWidth - target.width, 0)
                        following.placeRelative(constraints.maxWidth - following.width, followingTop)
                    }
                }
            }
            if (!showOffline) followingErrors()
        }
    }
}

/** The wire code is an open, untrusted vocabulary. Only client-owned resources may reach the pill. */
@Composable
private fun sessionErrorLabel(
    code: String,
    agent: ConversationAgent,
): String =
    stringResource(
        when (code) {
            "session.blocked" ->
                if (agent == ConversationAgent.Codex) R.string.thread_session_blocked_codex else R.string.thread_session_blocked
            "session.child_crashing" ->
                if (agent ==
                    ConversationAgent.Codex
                ) {
                    R.string.thread_session_child_crashing_codex
                } else {
                    R.string.thread_session_child_crashing
                }
            else ->
                if (agent == ConversationAgent.Codex) R.string.thread_session_error_codex else R.string.thread_session_error
        },
    )
