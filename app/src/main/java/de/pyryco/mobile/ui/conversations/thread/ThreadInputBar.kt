package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

// Figma 16:8's `Input large` (347:6635): a 6dp-cornered container 52dp tall holding the message text
// inset 16dp from the leading edge, and — overlapping its trailing edge 4dp in — a 48dp button drawn
// as a 28dp filled circle glyph.
private val FieldCorner = RoundedCornerShape(6.dp)
private val FieldMinHeight = 52.dp
private val FieldLeadingInset = 16.dp
private val FieldTrailingInset = 4.dp
private val FieldTextVerticalInset = 12.dp
private val ButtonTouchSize = 48.dp
private val ButtonGlyphSize = 28.dp

/**
 * The composer's input field (#643) — Figma `16:8`'s `Input large`, mounted by [ThreadScreen] as the
 * middle band of the `Input area` between the status area above it and the model/effort footer below.
 *
 * The surrounding surface, the 20dp content gutter and `Modifier.imePadding()` belong to that
 * composer column, not here: the whole input area lifts above the keyboard as one unit while the
 * header and the message list stay stationary. The design draws no rule above the input area.
 *
 * Stateless. A self-owning overload holding its own `rememberSaveable` text used to sit beside this one
 * and was retired in #789: its state died with the thread destination, so a draft was lost on
 * navigation and the next chat opened in that slot inherited whatever the composition held. The text
 * now lives in [ComposerDraftStore], keyed per host and conversation, and reaches here through
 * [ThreadScreen]'s `draft` / `onDraftChange`.
 *
 * [hasAttachments] and [sending] come from the chat's pending attachments (#933): attachments alone are
 * enough to send, and while [sending] the button stays disabled so a second tap cannot resend them.
 *
 * [onAnchorChanged] reports the field's window bounds with the left edge moved in to where the typed text
 * starts, which the screen uses to place the slash-command suggestions above the field (#885).
 */
@Composable
fun ThreadInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onInterrupt: () -> Unit = {},
    onAnchorChanged: (Rect) -> Unit = {},
    hasAttachments: Boolean = false,
    sending: Boolean = false,
) {
    val textInset = with(LocalDensity.current) { FieldLeadingInset.toPx() }
    // One button, two jobs (#643) — the placement desktop's #678 settled, replacing the standalone
    // foot-of-list interrupt control. Text present wins over the in-flight turn deliberately: sending
    // while the agent is busy is a shipped path (the daemon queues it and QueuedBacklog renders it,
    // #461), so a stop variant that pre-empted a typed message would remove the only tap that reaches
    // it. Stop therefore owns the button exactly when the composer is empty — the state anyone
    // reaching for stop is in. Pending attachments (#933) are something to send, so they count as not empty.
    val stopping = isBusy && text.isBlank() && !hasAttachments
    // #885: the field keeps its own cursor, and text replaced from outside (a slash-command completion, a
    // cleared send) puts the cursor at the end. A String-valued field would keep the old offset, so an
    // argument typed after picking `/model` from `/mo` would land inside the name. The draft returns
    // asynchronously, so [text] can still be the value from before the field's own latest edit
    // ([textAtLastEdit]); that is not an outside change, and the field keeps showing its own value.
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var textAtLastEdit by remember { mutableStateOf<String?>(null) }
    val shownValue =
        if (fieldValue.text == text || text == textAtLastEdit) {
            fieldValue
        } else {
            TextFieldValue(text, TextRange(text.length))
        }
    // Once the draft has caught up, the pre-edit text means nothing: a send that clears back to it is an
    // outside change like any other.
    LaunchedEffect(text) {
        if (text == fieldValue.text) textAtLastEdit = null
    }
    Surface(
        shape = FieldCorner,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = FieldMinHeight)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onAnchorChanged(bounds.copy(left = bounds.left + textInset))
                },
    ) {
        Row(
            modifier = Modifier.padding(start = FieldLeadingInset, end = FieldTrailingInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = shownValue,
                onValueChange = { value ->
                    fieldValue = value
                    if (value.text != text) {
                        textAtLastEdit = text
                        onTextChange(value.text)
                    }
                },
                // The design's `Text area` py-12: the 48dp button sets the single-line height, this
                // keeps wrapped text off the container's edge as the field grows.
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = FieldTextVerticalInset),
                textStyle =
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.thread_input_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(
                onClick = if (stopping) onInterrupt else onSend,
                enabled = stopping || (!sending && (text.isNotBlank() || hasAttachments)),
                modifier = Modifier.size(ButtonTouchSize),
            ) {
                Icon(
                    // Same filled-circle silhouette in both states, per the design's
                    // `Message input button`, so the two actions read as one control.
                    imageVector = if (stopping) Icons.Filled.StopCircle else Icons.Filled.ArrowCircleUp,
                    // The two descriptions both suites pin: "Send message" is the e2e thread-arrival
                    // marker, "Stop the running turn" is what ScriptedThreadRenderTest drives.
                    contentDescription =
                        stringResource(
                            if (stopping) R.string.cd_thread_interrupt else R.string.cd_send_message,
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(ButtonGlyphSize),
                )
            }
        }
    }
}

@Preview(name = "InputBar — Light, Empty", showBackground = true, widthDp = 372)
@Composable
private fun ThreadInputBarLightEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadInputBar(text = "", onTextChange = {}, onSend = {})
    }
}

@Preview(name = "InputBar — Light, Filled", showBackground = true, widthDp = 372)
@Composable
private fun ThreadInputBarLightFilledPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadInputBar(text = "Drafting a reply…", onTextChange = {}, onSend = {})
    }
}

@Preview(name = "InputBar — Dark, Empty", showBackground = true, widthDp = 372)
@Composable
private fun ThreadInputBarDarkEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadInputBar(text = "", onTextChange = {}, onSend = {})
    }
}

@Preview(name = "InputBar — Dark, Filled", showBackground = true, widthDp = 372)
@Composable
private fun ThreadInputBarDarkFilledPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadInputBar(text = "Drafting a reply…", onTextChange = {}, onSend = {})
    }
}

@Preview(name = "InputBar — Dark, Stop variant", showBackground = true, widthDp = 372)
@Composable
private fun ThreadInputBarDarkStopPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadInputBar(text = "", onTextChange = {}, onSend = {}, isBusy = true, onInterrupt = {})
    }
}
