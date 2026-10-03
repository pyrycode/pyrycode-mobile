package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.NoticePill
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
 * Top to bottom: the usage-limit report, a failed MCP server, then a pairing error or offline retry. The
 * report is a Default pill with an X only when [usageLimitIsWarning] says so, and it is left out once
 * [usageLimitDismissed]; any other reading is an Error pill that cannot be hidden. Pairing failure takes
 * precedence over the offline pill because a network retry cannot repair a rejected pairing. With none of
 * them, nothing is emitted.
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
) {
    val usage = usageLimit?.takeUnless { usageLimitDismissed }
    val showOffline = connectionState == ConnectionState.Offline && !showRePair
    val mcp = mcpFailure?.takeUnless { showRePair || showOffline }
    if (usage == null && mcp == null && !showRePair && !showOffline) return
    val viewConfiguration = LocalViewConfiguration.current
    val pillTouchConfiguration =
        remember(viewConfiguration) {
            object : ViewConfiguration by viewConfiguration {
                // The 24dp pills sit 12dp apart. Two 48dp vertical touch targets overlap in that stack.
                // Keep 48dp horizontally, and expand each pill to the largest non-overlapping height.
                override val minimumTouchTargetSize = DpSize(viewConfiguration.minimumTouchTargetSize.width, 36.dp)
            }
        }
    // Keep the clickable error pill at the design's 24dp visible height. The default Material layout
    // minimum inserted an extra 12dp above and below it, shifting the visible stack out of position.
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
        LocalViewConfiguration provides pillTouchConfiguration,
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(OverlayPillGap),
        ) {
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
                NoticePill(text = stringResource(R.string.thread_re_pair), isError = true, onClick = onRePair)
            } else if (showOffline) {
                // The visible 24dp pill keeps its 12dp gap below usage. Its 48dp target extends downward,
                // away from the usage pill's dismiss target. Figma 627:4910 (#1499): the drawn pill hugs its
                // label at the box's top-right; the wider box is touch area only.
                Box(
                    modifier =
                        Modifier
                            .height(48.dp)
                            .width(144.dp)
                            .testTag("offline_retry_target")
                            .clickable(role = Role.Button, onClick = onRetryConnection),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    NoticePill(
                        text = stringResource(R.string.thread_connection_offline_retry),
                        isError = true,
                    )
                }
            }
        }
    }
}
