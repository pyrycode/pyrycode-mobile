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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.delay
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

private const val STREAMING_CARET_GLYPH = "▎"
private const val STREAMING_REVEAL_CHARS_PER_SECOND = 50
private const val STREAMING_REVEAL_STEP_CHARS = 1
private const val STREAMING_REVEAL_STEP_MS: Long = 1000L / STREAMING_REVEAL_CHARS_PER_SECOND
private const val STREAMING_CARET_BLINK_PERIOD_MS: Long = 500L

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.User -> UserMessageBubble(message.content, modifier)
        Role.Assistant -> AssistantMessage(message, modifier)
        Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }
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
    message: Message,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = MessageRowVerticalSpacing),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            if (message.isStreaming) {
                StreamingAssistantBody(
                    content = message.content,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                MarkdownText(
                    markdown = message.content,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StreamingAssistantBody(
    content: String,
    modifier: Modifier = Modifier,
) {
    val revealedLength by produceState(initialValue = 0, key1 = content) {
        while (value < content.length) {
            delay(STREAMING_REVEAL_STEP_MS)
            value = (value + STREAMING_REVEAL_STEP_CHARS).coerceAtMost(content.length)
        }
    }
    val caretVisible by produceState(initialValue = true, key1 = Unit) {
        while (true) {
            delay(STREAMING_CARET_BLINK_PERIOD_MS)
            value = !value
        }
    }
    StreamingAssistantBodyView(
        revealedText = content.take(revealedLength),
        caretVisible = caretVisible,
        modifier = modifier,
    )
}

@Composable
private fun StreamingAssistantBodyView(
    revealedText: String,
    caretVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayText = if (caretVisible) revealedText + STREAMING_CARET_GLYPH else revealedText
    MarkdownText(markdown = displayText, modifier = modifier)
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

private const val MARKDOWN_PREVIEW_FIXTURE = """
# Heading 1
## Heading 2
### Heading 3

Plain paragraph with **bold**, *italic*, `inline code`, and a [link](https://pyryco.de).

- Unordered list item 1
- Unordered list item 2

1. Ordered list item 1
2. Ordered list item 2

> Blockquote — single line of quoted text.

Kotlin:

```kotlin
// migrate legacy orders into the modern shape
fun migrate(legacy: List<LegacyOrder>, batchSize: Int = 100, dryRun: Boolean = false): List<Order> =
    legacy.map { it.toModern() }
```

JSON:

```json
{
  "name": "pyrycode-mobile",
  "version": 1,
  "tags": ["android", "compose"]
}
```

Bash:

```bash
# bootstrap the dev environment
./gradlew assembleDebug
echo "Build complete"
```

Markdown:

```markdown
# Heading
- bullet
**bold** and *italic*
```
"""

@Composable
private fun MessageBubbleMarkdownPreviewBody() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(MessageRowVerticalSpacing),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            ) {
                StreamingAssistantBodyView(
                    revealedText =
                        MARKDOWN_PREVIEW_FIXTURE.take(MARKDOWN_PREVIEW_FIXTURE.length / 2),
                    caretVisible = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        MessageBubble(previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE))
    }
}

@Preview(
    name = "MessageBubble — Markdown · Light",
    showBackground = true,
    widthDp = 412,
)
@Composable
private fun MessageBubbleMarkdownLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            MessageBubbleMarkdownPreviewBody()
        }
    }
}

@Preview(
    name = "MessageBubble — Markdown · Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MessageBubbleMarkdownDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            MessageBubbleMarkdownPreviewBody()
        }
    }
}
