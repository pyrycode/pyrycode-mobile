package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant

// Figma 16:8 `Message area` (533:1956) and the shared `Message` component it fills with. `internal`
// rather than file-private where QueuedBacklog needs the same numbers — it used to keep its own copies
// precisely because these were unreachable, and the ticket asks the two to stay one family.
internal val MessageAreaRowSpacing = 16.dp // `Message area` gap-[16px]
internal val BubbleShape = RoundedCornerShape(6.dp) // `Message` rounded-[6px]
internal val BubbleHorizontalPadding = 20.dp // `Message` px-[20px]
internal val BubbleVerticalPadding = 16.dp // `Message` py-[16px]
internal val BubbleContentSpacing = 12.dp // `Message` gap-[12px] — body to meta row

// The frame's 412dp reference width carries a 20dp gutter on each edge, leaving the 372dp content area;
// each role container then insets its *opposite* edge by 100dp (`pr-[100px]` / `pl-[100px]`), which caps
// a bubble at 272dp there. The inset is the mechanism and 272dp is its value at the reference width, so
// there is no separate max-width constant to drift away from it. The gutter lives on the component
// because ThreadScreen's LazyColumn applies none and this ticket does not touch it.
internal val MessageContentGutter = 20.dp
internal val MessageRoleInset = 100.dp

// #896: the frame has no subagent grouping, so each nesting level steps a tool row in by the
// `Message area` gap it already uses between rows.
private val ToolNestingIndent = MessageAreaRowSpacing

// The bubble's own container, tagged because its *width* is the property under test and nothing else
// observes it: a body `Text` hugs its own content whether or not the container around it does, so the
// Surface is the only node that moves when the hug regresses.
internal const val MESSAGE_BUBBLE_TEST_TAG = "message-bubble"

private const val STREAMING_CARET_GLYPH = "▎"
private const val STREAMING_REVEAL_CHARS_PER_SECOND = 50
private const val STREAMING_REVEAL_STEP_CHARS = 1
private const val STREAMING_REVEAL_STEP_MS: Long = 1000L / STREAMING_REVEAL_CHARS_PER_SECOND
private const val STREAMING_CARET_BLINK_PERIOD_MS: Long = 500L

/**
 * [toolNestingDepth] (#896) is read only by a tool row: how many `Agent`/`Task` calls deep a subagent's
 * call sits. Each level indents the row's leading edge one [ToolNestingIndent] past the gutter.
 *
 * [attachmentStates], [onAttachmentShown] and [onRetryAttachment] (#984) are read only by the two bubble
 * roles, for the message's attachments: each attachment's state by id, the report that one is on screen,
 * and its retry control.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    toolNestingDepth: Int = 0,
    attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
    onAttachmentShown: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
) {
    val attachments: @Composable () -> Unit = {
        MessageAttachments(
            attachments = message.attachments,
            states = attachmentStates,
            onShown = onAttachmentShown,
            onRetry = onRetryAttachment,
        )
    }
    when (message.role) {
        Role.User -> UserMessageBubble(message, attachments, modifier)
        Role.Assistant -> AssistantMessage(message, attachments, modifier)
        // The gutter is applied here rather than inside ToolCallRow: moving it into the components left
        // the tool row as the one list kind still bleeding to the screen edge, which reads as a ragged
        // left edge next to the bubbles. The row's own layout belongs to #658, and this arm reaches it
        // without touching that file.
        Role.Tool ->
            message.toolCall?.let {
                ToolCallRow(
                    toolCall = it,
                    modifier =
                        modifier.padding(
                            start = MessageContentGutter + ToolNestingIndent * toolNestingDepth,
                            end = MessageContentGutter,
                        ),
                    subagentDepth = toolNestingDepth,
                )
            }
    }
}

/**
 * The design's `User message container` (Figma node `114:3559`): right-aligned, leading edge inset by
 * [MessageRoleInset], filled from the `primaryContainer` pair.
 *
 * Content stays unparsed plain [Text] — the user wrote it, it is not a markdown source.
 */
@Composable
private fun UserMessageBubble(
    message: Message,
    attachments: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    MessageContainer(
        message = message,
        alignment = Alignment.End,
        bubbleColor = MaterialTheme.colorScheme.primaryContainer,
        bubbleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        attachments = attachments,
        modifier = modifier,
    ) {
        if (message.hasNoBody()) return@MessageContainer
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The design's `Assistant message container` (Figma node `114:3558`): left-aligned, trailing edge inset
 * by [MessageRoleInset], filled from the `secondaryContainer` pair.
 *
 * The body keeps both renderers it has had since #184 — the progressive-reveal [StreamingAssistantBody]
 * while `isStreaming`, the static [MarkdownText] once finalized. Neither is wrapped in its own
 * `LocalContentColor` provider any more: the enclosing [Surface] supplies the ambient, which is the
 * contract `MarkdownText` documents for a host surface.
 *
 * The #128 divergence closes here. That ticket's acceptance criteria overrode the Figma frame to ship a
 * flat, unboxed assistant body, and the note deferred reconciliation to a downstream design call; #644
 * *is* that call, and it resolves in favour of the design.
 */
@Composable
private fun AssistantMessage(
    message: Message,
    attachments: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    MessageContainer(
        message = message,
        alignment = Alignment.Start,
        bubbleColor = MaterialTheme.colorScheme.secondaryContainer,
        bubbleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        attachments = attachments,
        modifier = modifier,
    ) {
        if (message.isStreaming) {
            // The one arm that keeps filling, deliberately. `caretVisible` toggles the rendered string
            // by one glyph twice a second, so a shrink-wrapping streaming bubble would oscillate in
            // width at 2Hz for the whole turn — worst on exactly the short replies the hug exists for.
            // Filling holds the width steady while deltas land, and the bubble settles onto its content
            // at `turn_end`: one snap rather than continuous jitter.
            StreamingAssistantBody(
                content = message.content,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (!message.hasNoBody()) {
            // No `fillMaxWidth()`. It sets minWidth = maxWidth, which pinned every finalized assistant
            // bubble to the full lane and made 272dp a fixed width rather than the maximum the design
            // specifies — the frame's short assistant instance (`I533:1956;132:4539`) is 205dp. Without
            // it `MarkdownText`'s `Column` wraps its widest child, while `CodeBlock` carries its own
            // `fillMaxWidth()`, so a fenced block still spans the bubble and only prose hugs.
            MarkdownText(markdown = message.content)
        }
    }
}

/**
 * The shared `Message` component both roles render through, plus the role container that positions it.
 *
 * The only per-role inputs are [alignment] and the two colours; the geometry — gutter, [MessageRoleInset]
 * on the opposite edge, 6dp corners, 20/16 inner padding, 12dp between body and meta row — is identical,
 * which is what makes the two bubbles read as one family.
 *
 * The meta row is handed [Message.content] directly, never anything read back out of [body], so an
 * assistant bubble copies its markdown source rather than the parsed render.
 *
 * [attachments] fills the design's `Slot` between the body and the meta row (#984), and only when the
 * message has any: a text-only bubble lays out exactly as before.
 */
@Composable
private fun MessageContainer(
    message: Message,
    alignment: Alignment.Horizontal,
    bubbleColor: Color,
    bubbleContentColor: Color,
    modifier: Modifier = Modifier,
    attachments: @Composable () -> Unit = {},
    body: @Composable () -> Unit,
) {
    val isUserSide = alignment == Alignment.End
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = MessageContentGutter + if (isUserSide) MessageRoleInset else 0.dp,
                    end = MessageContentGutter + if (isUserSide) 0.dp else MessageRoleInset,
                    bottom = MessageAreaRowSpacing,
                ),
        // One child, so the zero spacing carries nothing; this is how a Row takes an
        // Alignment.Horizontal parameter rather than a hardcoded Arrangement.Start / .End.
        horizontalArrangement = Arrangement.spacedBy(0.dp, alignment),
    ) {
        Surface(
            modifier = Modifier.testTag(MESSAGE_BUBBLE_TEST_TAG),
            shape = BubbleShape,
            color = bubbleColor,
            contentColor = bubbleContentColor,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(BubbleContentSpacing),
                // The design puts `items-start` on the `Message` column for *both* roles — a short
                // user body is left-aligned inside its bubble — and `justify-end` on the user's meta
                // row alone. So the column aligns Start and the meta row overrides for its own side.
                horizontalAlignment = Alignment.Start,
            ) {
                body()
                if (message.attachments.isNotEmpty()) attachments()
                MessageMetaRow(
                    timestamp = message.timestamp,
                    copyText = message.content,
                    modifier = Modifier.align(alignment),
                )
            }
        }
    }
}

/**
 * A message that carries attachments and no text has no body (#984): drawing an empty text block would
 * leave a blank line above the attachments. A text-only message always keeps its body.
 */
private fun Message.hasNoBody(): Boolean = attachments.isNotEmpty() && content.isBlank()

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

// Pinned rather than Clock.System.now() so the meta row renders a stable, reviewable timestamp — the
// design's own sample moment, which a de-DE preview host reproduces as `13.01.2026 - 13:55`.
private val PreviewTimestamp = Instant.parse("2026-01-13T12:55:00Z")

private fun previewMessage(
    role: Role,
    content: String,
    isStreaming: Boolean = false,
): Message =
    Message(
        id = "preview-${role.name}",
        sessionId = "preview-session",
        role = role,
        content = content,
        timestamp = PreviewTimestamp,
        isStreaming = isStreaming,
    )

// No horizontal padding: the component owns the design's 20dp gutter now, so the preview viewport is
// the frame's full 412dp reference width and the bubbles land on the real geometry.
@Composable
private fun MessageBubblePreviewSequence() {
    Column {
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
        // Short enough that the meta row, not the body, sets the bubble's width. Every other message in
        // this sequence wraps and so reaches the lane maximum, which is what made the hug invisible
        // under review: 272dp is the maximum, and a brief reply must sit well inside it.
        MessageBubble(previewMessage(role = Role.Assistant, content = "On it."))
        // #896: an agent call, its subagent's call one level in, and that subagent's own subagent two in.
        PreviewToolNesting.forEachIndexed { depth, toolCall ->
            MessageBubble(
                message = previewMessage(role = Role.Tool, content = "").copy(toolCall = toolCall),
                toolNestingDepth = depth,
            )
        }
    }
}

private val PreviewToolNesting =
    listOf(
        ToolCall(toolName = "Agent", input = "Survey the schema", output = ""),
        ToolCall(toolName = "Task", input = "Check the legacy table", output = ""),
        ToolCall(toolName = "Grep", input = "user_id", output = "", inputFields = mapOf("pattern" to "user_id")),
    )

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

// Narrow-width check, the peer of SessionBoundaryDelimiterNarrowPreview: at 320dp the 20dp gutters and
// the 100dp role inset leave a 180dp bubble, so the meta row's timestamp-plus-glyph is the widest thing
// in it and sets the bubble's floor. Kept reviewable because that is the width at which the design's
// generous insets bite hardest.
@Preview(name = "MessageBubble — Narrow", showBackground = true, widthDp = 320)
@Composable
private fun MessageBubbleNarrowPreview() {
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

- [x] Task list item, done
- [ ] Task list item, not done
- Plain item in the same list, keeping its bullet

Struck text: ~~two tildes~~ and ~one tilde~, beside a path that keeps both of its: ~/src ~/out.

| Construct | Alignment | Count |
|:----------|:---------:|------:|
| Table | centre | 1 |
| Task list | centre | 3 |
| Strikethrough | centre | 2 |

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

// Each bubble carries its own bottom spacing, so the column adds none.
@Composable
private fun MessageBubbleMarkdownPreviewBody() {
    Column {
        // The half-revealed streaming snapshot, pinned so the caret lands deterministically where the
        // produceState-driven path would render at an unpredictable position — now routed through the
        // real assistant container, so the preview shows the caret *inside* the new bubble.
        MessageContainer(
            message = previewMessage(Role.Assistant, MARKDOWN_PREVIEW_FIXTURE, isStreaming = true),
            alignment = Alignment.Start,
            bubbleColor = MaterialTheme.colorScheme.secondaryContainer,
            bubbleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            StreamingAssistantBodyView(
                revealedText = MARKDOWN_PREVIEW_FIXTURE.take(MARKDOWN_PREVIEW_FIXTURE.length / 2),
                caretVisible = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
