package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    NoticePill(
        text = label,
        isError = false,
        modifier = Modifier.testTag("thread_attention_pill"),
        onClick = { onOpen(state.target) },
        maxLines = 2,
        containerColor =
            if (waiting) MaterialTheme.colorScheme.attentionWaitingContainer else MaterialTheme.colorScheme.attentionFinishedContainer,
        contentColor = if (waiting) MaterialTheme.colorScheme.warning else MaterialTheme.colorScheme.success,
    )
}
