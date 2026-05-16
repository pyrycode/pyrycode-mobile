package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock

private val MessageRowVerticalSpacing = 12.dp
private val UserBubbleMaxWidth = 320.dp
private val UserBubbleShape =
    RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = 6.dp,
        bottomStart = 20.dp,
    )
private val BubbleHorizontalPadding = 14.dp
private val BubbleVerticalPadding = 12.dp

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.User -> UserMessageBubble(message.content, modifier)
        Role.Assistant -> AssistantMessage(message.content, modifier)
        // Tool-role rendering lands in #131; ThreadScreen will route it before reaching here.
        Role.Tool -> Unit
    }
}

@Composable
private fun UserMessageBubble(
    content: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = MessageRowVerticalSpacing),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = UserBubbleMaxWidth),
            shape = UserBubbleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = content,
                modifier =
                    Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    content: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = MessageRowVerticalSpacing),
    ) {
        Text(
            text = content,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun previewMessage(
    role: Role,
    content: String,
): Message =
    Message(
        id = "preview-${role.name}",
        sessionId = "preview-session",
        role = role,
        content = content,
        timestamp = Clock.System.now(),
        isStreaming = false,
    )

@Composable
private fun MessageBubblePreviewSequence() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        MessageBubble(
            previewMessage(
                role = Role.User,
                content = "Can you help me think through the schema migration plan?",
            ),
        )
        MessageBubble(
            previewMessage(
                role = Role.Assistant,
                content = "Sure — let me read the existing schema first.",
            ),
        )
        MessageBubble(
            previewMessage(
                role = Role.User,
                content = "Quick follow-up: what about edge cases?",
            ),
        )
        MessageBubble(
            previewMessage(
                role = Role.Assistant,
                content =
                    "Good catch. There are three classes of edge case here, the most important " +
                        "being null user_ids in the legacy table — let me walk through each.",
            ),
        )
    }
}

@Preview(name = "MessageBubble — Light", showBackground = true, widthDp = 412)
@Composable
private fun MessageBubbleLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            MessageBubblePreviewSequence()
        }
    }
}

@Preview(
    name = "MessageBubble — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessageBubbleDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            MessageBubblePreviewSequence()
        }
    }
}
