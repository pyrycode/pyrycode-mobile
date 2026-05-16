package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role as MessageRole
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Composable
fun DiscussionPreviewRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName =
        conversation.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.untitled_discussion)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (lastMessage != null) {
            Text(
                text = lastMessage.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatRelativeTime(conversation.lastUsedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(name = "Discussion preview — Light", showBackground = true, widthDp = 412)
@Composable
private fun DiscussionPreviewRowScratchLightPreview() {
    val lastUsedAt = Clock.System.now() - 12.minutes
    PyrycodeMobileTheme(darkTheme = false) {
        DiscussionPreviewRow(
            conversation =
                Conversation(
                    id = "preview-d1",
                    name = "What's the safest way…",
                    cwd = DEFAULT_SCRATCH_CWD,
                    currentSessionId = "session-1",
                    sessionHistory = emptyList(),
                    isPromoted = false,
                    lastUsedAt = lastUsedAt,
                ),
            lastMessage =
                Message(
                    id = "msg-d1",
                    sessionId = "session-1",
                    role = MessageRole.Assistant,
                    content =
                        "I'd start by checking if your nullable timestamp comparison is using " +
                            "the right operator. In TypeScript…",
                    timestamp = lastUsedAt,
                    isStreaming = false,
                ),
            onClick = {},
        )
    }
}

@Preview(
    name = "Discussion preview — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiscussionPreviewRowScratchDarkPreview() {
    val lastUsedAt = Clock.System.now() - 2.hours
    PyrycodeMobileTheme(darkTheme = true) {
        DiscussionPreviewRow(
            conversation =
                Conversation(
                    id = "preview-d2",
                    name = "Help me debug auth flow",
                    cwd = DEFAULT_SCRATCH_CWD,
                    currentSessionId = "session-2",
                    sessionHistory = emptyList(),
                    isPromoted = false,
                    lastUsedAt = lastUsedAt,
                ),
            lastMessage =
                Message(
                    id = "msg-d2",
                    sessionId = "session-2",
                    role = MessageRole.Assistant,
                    content =
                        "The token refresh is happening but the new token isn't being stored. " +
                            "Let me trace through the request flow…",
                    timestamp = lastUsedAt,
                    isStreaming = false,
                ),
            onClick = {},
        )
    }
}

@Preview(name = "Discussion preview — No message", showBackground = true, widthDp = 412)
@Composable
private fun DiscussionPreviewRowNoMessageLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        DiscussionPreviewRow(
            conversation =
                Conversation(
                    id = "preview-d3",
                    name = "Quick regex for log parsing",
                    cwd = DEFAULT_SCRATCH_CWD,
                    currentSessionId = "session-3",
                    sessionHistory = emptyList(),
                    isPromoted = false,
                    lastUsedAt = Clock.System.now() - 5.hours,
                ),
            lastMessage = null,
            onClick = {},
        )
    }
}
