package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.assistantBubbleContainer
import de.pyryco.mobile.ui.theme.userBubbleContainer
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
private val UserParagraphBreak = Regex("\\r?\\n[\\t ]*\\r?\\n")
private const val STREAMING_REVEAL_CHARS_PER_SECOND = 50
private const val STREAMING_REVEAL_STEP_CHARS = 1
private const val STREAMING_REVEAL_STEP_MS: Long = 1000L / STREAMING_REVEAL_CHARS_PER_SECOND
private const val STREAMING_CARET_BLINK_PERIOD_MS: Long = 500L

/**
 * [toolNestingDepth] (#896) is read only by a tool row: how many `Agent`/`Task` calls deep a subagent's
 * call sits. Each level indents the row's leading edge one [ToolNestingIndent] past the gutter.
 * [joinsNextToolRow] (#1577) is also read only by a tool row: the thread's next row is a tool row too.
 *
 * [attachmentStates], [onAttachmentShown] and [onRetryAttachment] (#984) are read only by the two bubble
 * roles, for the message's attachments: each attachment's state by id, the report that one is on screen,
 * and its retry control. [onOpenAttachment] and [onSaveAttachment] (#985) are a ready attachment's tap and
 * long-press; [onRequestAttachment] (#1329) is the tap or long-press of a file not fetched yet.
 *
 * [onOpenMarkdownLink] (#1050) is read only by an assistant reply, streaming or finished: a tapped link to a
 * workspace markdown note hands over its path. `null` leaves such a link inert, as it was before.
 *
 * [metaRowVisible] and [onToggleMetaRow] (#1621) are read only by the two bubble roles. The thread hides
 * the meta row until the bubble is tapped and owns which message shows it; the defaults keep the row
 * drawn and the bubble inert, as every other host had it. A non-null [onToggleMetaRow] is the bubble's
 * tap and its screen-reader click.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    toolNestingDepth: Int = 0,
    joinsNextToolRow: Boolean = false,
    attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
    onAttachmentShown: (MessageAttachment) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    onOpenAttachment: (AttachmentTarget) -> Unit = {},
    onSaveAttachment: (AttachmentTarget) -> Unit = {},
    onRequestAttachment: (MessageAttachment, AttachmentAction) -> Unit = { _, _ -> },
    onOpenMarkdownLink: ((String) -> Unit)? = null,
    metaRowVisible: Boolean = true,
    onToggleMetaRow: (() -> Unit)? = null,
) {
    val metaRow = MetaRowControl(metaRowVisible, onToggleMetaRow)
    val attachments: @Composable () -> Unit = {
        MessageAttachments(
            attachments = message.attachments,
            states = attachmentStates,
            onShown = onAttachmentShown,
            onRetry = onRetryAttachment,
            onOpen = onOpenAttachment,
            onSave = onSaveAttachment,
            onRequest = onRequestAttachment,
        )
    }
    when (message.role) {
        Role.User -> UserMessageBubble(message, attachments, metaRow, modifier)
        Role.Assistant -> AssistantMessage(message, attachments, onOpenMarkdownLink, metaRow, modifier)
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
                    joinsNextToolRow = joinsNextToolRow,
                )
            }
    }
}

/**
 * The design's `User message container` (Figma node `114:3559`): right-aligned, leading edge inset by
 * [MessageRoleInset], using the theme's user bubble fill and `onPrimaryContainer` content.
 *
 * Content stays unparsed plain [Text] — the user wrote it, it is not a markdown source.
 */
@Composable
private fun UserMessageBubble(
    message: Message,
    attachments: @Composable () -> Unit,
    metaRow: MetaRowControl,
    modifier: Modifier = Modifier,
) {
    MessageContainer(
        message = message,
        alignment = Alignment.End,
        bubbleColor = MaterialTheme.colorScheme.userBubbleContainer,
        bubbleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        attachments = attachments,
        metaRow = metaRow,
        modifier = modifier,
    ) {
        if (message.hasNoBody()) return@MessageContainer
        message.content.split(UserParagraphBreak).forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The design's `Assistant message container` (Figma node `114:3558`): left-aligned, trailing edge inset
 * by [MessageRoleInset], using the theme's assistant bubble fill and `onSecondaryContainer` content.
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
    onOpenMarkdownLink: ((String) -> Unit)?,
    metaRow: MetaRowControl,
    modifier: Modifier = Modifier,
) {
    MessageContainer(
        message = message,
        alignment = Alignment.Start,
        bubbleColor = MaterialTheme.colorScheme.assistantBubbleContainer,
        bubbleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        attachments = attachments,
        metaRow = metaRow,
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
                onOpenMarkdownLink = onOpenMarkdownLink,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (!message.hasNoBody()) {
            // No `fillMaxWidth()`. It sets minWidth = maxWidth, which pinned every finalized assistant
            // bubble to the full lane and made 272dp a fixed width rather than the maximum the design
            // specifies — the frame's short assistant instance (`I533:1956;132:4539`) is 205dp. Without
            // it `MarkdownText`'s `Column` wraps its widest child, while `CodeBlock` carries its own
            // `fillMaxWidth()`, so a fenced block still spans the bubble and only prose hugs.
            MarkdownText(markdown = message.content, onOpenMarkdownPath = onOpenMarkdownLink)
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
 * [attachments] fills the design's `Slot` above the body (#984, moved above it by #1513 so the attachment
 * the text talks about is in view first), and only when the message has any: a text-only bubble lays out
 * exactly as before.
 *
 * [metaRow] (#1621) says whether the meta row is drawn and what a tap on the bubble does. The tap is a
 * `pointerInput` detector rather than `clickable`: `clickable` merges every descendant into one semantics
 * node, which would read a whole reply as one TalkBack stop. A nested target that handles its own tap — a
 * link span, an attachment, the code block's copy — consumes the down event first, so it never toggles
 * the row. While the row is hidden the bubble itself carries the timestamp and a copy action for a screen
 * reader.
 */
@Composable
private fun MessageContainer(
    message: Message,
    alignment: Alignment.Horizontal,
    bubbleColor: Color,
    bubbleContentColor: Color,
    modifier: Modifier = Modifier,
    attachments: @Composable () -> Unit = {},
    metaRow: MetaRowControl = MetaRowControl(),
    body: @Composable () -> Unit,
) {
    val isUserSide = alignment == Alignment.End
    val onToggle = metaRow.onToggle
    val toggleLabel =
        stringResource(if (metaRow.visible) R.string.thread_message_hide_details else R.string.thread_message_show_details)
    val sentDescription = stringResource(R.string.cd_thread_message_sent, rememberFormattedTimestamp(message.timestamp))
    val copyLabel = stringResource(R.string.cd_thread_copy_message)
    val clipboard = LocalClipboardManager.current
    // Keyed on Unit with the latest lambda read at tap time, so a host passing a fresh lambda each
    // recomposition does not restart the gesture detector.
    val currentOnToggle by rememberUpdatedState(onToggle)
    val tap =
        if (onToggle == null) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) { detectTapGestures(onTap = { currentOnToggle?.invoke() }) }
        }
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
            modifier =
                Modifier
                    .shadow(4.dp, BubbleShape)
                    .testTag(MESSAGE_BUBBLE_TEST_TAG)
                    .then(tap)
                    .semantics {
                        if (onToggle != null) {
                            onClick(label = toggleLabel) {
                                onToggle()
                                true
                            }
                        }
                        if (!metaRow.visible) {
                            contentDescription = sentDescription
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(copyLabel) {
                                        clipboard.setBoundedText(message.content)
                                        true
                                    },
                                )
                        }
                    },
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
                if (message.attachments.isNotEmpty()) attachments()
                body()
                if (metaRow.visible) {
                    MessageMetaRow(
                        timestamp = message.timestamp,
                        copyText = message.content,
                        modifier = Modifier.align(alignment),
                    )
                }
            }
        }
    }
}

/** Whether a bubble draws its meta row, and the bubble's tap that toggles it (#1621); `null` is no tap. */
private data class MetaRowControl(
    val visible: Boolean = true,
    val onToggle: (() -> Unit)? = null,
)

/**
 * A message that carries attachments and no text has no body (#984): drawing an empty text block would
 * leave a blank line below the attachments. A text-only message always keeps its body.
 */
private fun Message.hasNoBody(): Boolean = attachments.isNotEmpty() && content.isBlank()

@Composable
private fun StreamingAssistantBody(
    content: String,
    onOpenMarkdownLink: ((String) -> Unit)?,
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
        onOpenMarkdownLink = onOpenMarkdownLink,
        modifier = modifier,
    )
}

@Composable
private fun StreamingAssistantBodyView(
    revealedText: String,
    caretVisible: Boolean,
    onOpenMarkdownLink: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val displayText = if (caretVisible) revealedText + STREAMING_CARET_GLYPH else revealedText
    MarkdownText(markdown = displayText, modifier = modifier, onOpenMarkdownPath = onOpenMarkdownLink)
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
                onOpenMarkdownLink = null,
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
