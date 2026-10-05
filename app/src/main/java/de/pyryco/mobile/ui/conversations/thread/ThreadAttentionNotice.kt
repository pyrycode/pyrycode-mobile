package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.notifications.notificationTitle
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.theme.attentionFinishedContainer
import de.pyryco.mobile.ui.theme.attentionWaitingContainer
import de.pyryco.mobile.ui.theme.success
import de.pyryco.mobile.ui.theme.warning

/** Navigation only: a null target opens the list, while a single conversation retains its host. */
@Composable
internal fun ThreadAttentionNotice(
    state: ThreadAttention,
    onOpen: (HostConversationTarget?) -> Unit,
) {
    val waiting = state.waitingCount > 0
    val name = notificationTitle(state.name) ?: stringResource(R.string.app_name)
    val label =
        when {
            state.waitingCount > 1 -> stringResource(R.string.thread_attention_waiting_count, state.waitingCount)
            waiting -> stringResource(R.string.thread_attention_waiting, name)
            else -> stringResource(R.string.thread_attention_finished, name)
        }
    Box(
        modifier =
            Modifier
                // Add 24dp above the bodySmall pill's 24dp surface for a 48dp target. Report only
                // the visible height to the stack, placing the extra target into its top clearance.
                // Expanding upward keeps the usage dismiss target and the 12dp visible gap intact.
                .layout { measurable, constraints ->
                    val target = measurable.measure(constraints)
                    val clearance = 24.dp.roundToPx()
                    layout(target.width, target.height - clearance) { target.placeRelative(0, -clearance) }
                }.testTag("thread_attention_pill")
                .clickable(role = Role.Button) { onOpen(state.target) }
                .padding(top = 24.dp),
    ) {
        NoticePill(
            text = label,
            isError = false,
            modifier = Modifier.heightIn(min = 24.dp).testTag("thread_attention_surface"),
            maxLines = 2,
            // The outer 48dp button owns the label; a nested merging node would hide it from that target.
            mergeDescendants = false,
            containerColor =
                if (waiting) MaterialTheme.colorScheme.attentionWaitingContainer else MaterialTheme.colorScheme.attentionFinishedContainer,
            contentColor = if (waiting) MaterialTheme.colorScheme.warning else MaterialTheme.colorScheme.success,
        )
    }
}
