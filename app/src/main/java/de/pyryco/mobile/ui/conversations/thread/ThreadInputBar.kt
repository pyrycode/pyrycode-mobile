package de.pyryco.mobile.ui.conversations.thread

import android.net.Uri
import android.view.inputmethod.InputContentInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.composerFieldContainer

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
 * [sending] comes from the chat's pending attachments (#933): while they send, the button stays disabled so a
 * second tap cannot resend them. Pending attachments still need text to send (#1328), as on desktop.
 *
 * [enabled] is false while the host is not connected (#1319): Send and Stop both grey out, and the field
 * stays editable so the draft can still be written.
 *
 * [onImagesReceived], when set, takes the image content URIs a paste or a keyboard image insert offers the
 * field (#934); the rest of the clip, text included, still goes into the field.
 *
 * [onAnchorChanged] reports the field's window bounds with the left edge moved in to where the typed text
 * starts, which the screen uses to place the slash-command suggestions above the field (#885).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onInterrupt: () -> Unit = {},
    onAnchorChanged: (Rect) -> Unit = {},
    sending: Boolean = false,
    onImagesReceived: ((List<Uri>, InputContentInfo?) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val textInset = with(LocalDensity.current) { FieldLeadingInset.toPx() }
    // One button, two jobs (#643) — the placement desktop's #678 settled, replacing the standalone
    // foot-of-list interrupt control. Text present wins over the in-flight turn deliberately: sending
    // while the agent is busy is a shipped path (the daemon queues it and QueuedBacklog renders it,
    // #461), so a stop variant that pre-empted a typed message would remove the only tap that reaches
    // it. Stop therefore owns the button exactly when the composer's text is empty — the state anyone
    // reaching for stop is in. Pending attachments do not count: they cannot send without text (#1328).
    val stopping = isBusy && text.isBlank()
    val buttonEnabled = enabled && (stopping || (!sending && text.isNotBlank()))
    // #885: the field keeps its own cursor, and text replaced from outside (a slash-command completion, a
    // cleared send) puts the cursor at the end. The draft returns asynchronously, so [text] can still be the
    // value from before the field's own latest edit ([textAtLastEdit]); that is not an outside change, and
    // the field keeps its own value. #934 moved this onto a TextFieldState, the only text field that can
    // receive pasted content: user edits reach the draft through the input transformation, which a
    // programmatic set never runs, so an outside change cannot echo back as an edit.
    // Plain `remember`, never `rememberTextFieldState`: that one is saveable, and its saver writes the text and
    // the whole undo history into the saved-state Bundle. The draft is heap-only (#789).
    val fieldState = remember { TextFieldState(text, TextRange(text.length)) }
    var textAtLastEdit by remember { mutableStateOf<String?>(null) }
    // The text either path last put in the field: a user edit reported through the transformation, or an
    // outside change set here. A field text that is neither came from undo or redo, which bypass input
    // transformations, and still has to reach the draft.
    var accountedText by remember { mutableStateOf(text) }
    val currentText by rememberUpdatedState(text)
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val reportEdits =
        remember {
            InputTransformation {
                val edited = toString()
                accountedText = edited
                if (edited != currentText) {
                    textAtLastEdit = currentText
                    currentOnTextChange(edited)
                }
            }
        }
    LaunchedEffect(text) {
        val shown = fieldState.text.toString()
        if (shown == text) {
            // Once the draft has caught up, the pre-edit text means nothing: a send that clears back to it
            // is an outside change like any other.
            textAtLastEdit = null
        } else if (text != textAtLastEdit) {
            accountedText = text
            fieldState.setTextAndPlaceCursorAtEnd(text)
        }
    }
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() }.collect { shown ->
            if (shown != accountedText) {
                accountedText = shown
                textAtLastEdit = currentText
                currentOnTextChange(shown)
            }
        }
    }
    val ownPackage = LocalContext.current.packageName
    val currentOnImagesReceived by rememberUpdatedState(onImagesReceived)
    // #934: a paste or a keyboard image insert offers the field a clip. Image content URIs from another
    // app go to the attachment path; everything else is left for the field, so text still pastes as text.
    val imageReceiver =
        remember(ownPackage) {
            ReceiveContentListener { content ->
                val receive = currentOnImagesReceived ?: return@ReceiveContentListener content
                val description = content.clipMetadata.clipDescription
                val images = mutableListOf<Uri>()
                val rest =
                    content.consume { item ->
                        val image = isPastedImageItem(item, description, ownPackage)
                        if (image) images += item.uri
                        image
                    }
                if (images.isNotEmpty()) {
                    // Compose requests the keyboard grant and places its owner in this extra.
                    val input =
                        content.platformTransferableContent
                            ?.extras
                            ?.getParcelable("EXTRA_INPUT_CONTENT_INFO", InputContentInfo::class.java)
                    receive(images, input)
                }
                rest
            }
        }
    Surface(
        shape = FieldCorner,
        color = MaterialTheme.colorScheme.composerFieldContainer,
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
                state = fieldState,
                // The design's `Text area` py-12: the 48dp button sets the single-line height, this
                // keeps wrapped text off the container's edge as the field grows.
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = FieldTextVerticalInset)
                        .then(if (onImagesReceived != null) Modifier.contentReceiver(imageReceiver) else Modifier),
                inputTransformation = reportEdits,
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                decorator = { innerTextField ->
                    Box {
                        if (fieldState.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.thread_input_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
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
                enabled = buttonEnabled,
                modifier = Modifier.size(ButtonTouchSize),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    ),
            ) {
                Icon(
                    // Figma defines the Send path. Its component has no Stop variant, so the
                    // existing filled-circle Stop icon retains that action's distinct meaning.
                    painter =
                        if (stopping) rememberVectorPainter(Icons.Filled.StopCircle) else painterResource(R.drawable.ic_composer_send),
                    // The two descriptions both suites pin: "Send message" is the e2e thread-arrival
                    // marker, "Stop the running turn" is what ScriptedThreadRenderTest drives.
                    contentDescription =
                        stringResource(
                            if (stopping) R.string.cd_thread_interrupt else R.string.cd_send_message,
                        ),
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
