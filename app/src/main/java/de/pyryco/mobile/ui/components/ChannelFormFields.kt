package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText

/** The device suites' handles for the two fields, which the design draws without built-in labels. */
internal const val CHANNEL_NAME_FIELD_TAG: String = "channel-form-name"
internal const val CHANNEL_PROMPT_FIELD_TAG: String = "channel-form-prompt"

// The frame's 8dp label/field gap and 12dp gap between the two blocks.
private val FieldLabelGap = 8.dp
private val FieldGap = 12.dp

// The prompt well opens tall enough to read as a paragraph box; the shell scrolls beyond that.
private const val PROMPT_MIN_LINES = 4
private val NameWellHeight = 52.dp
private val PromptWellHeight = 112.dp
private val WellInset = 16.dp
private val NameTrailingInset = 56.dp

/**
 * A channel's name and optional system prompt as one form, drawn inside a [MobileModal] — desktop's
 * Save as channel content on the phone, and the Create channel modal's body later.
 *
 * Stateless: the caller owns both values and every callback. The name field takes focus when the form
 * first composes; the request lives here because the form composes inside the modal's own dialog window,
 * where a request made from the parent composition never lands (#589). The prompt is never trimmed here
 * or anywhere after: it is sent verbatim. A prompt over [SystemPromptLimit.MAX_BYTES] UTF-8 bytes is
 * marked with a static message; the caller disables its submit for the same reason. [promptNote] is a
 * caller's static line drawn in the same place when the prompt is within the limit — Edit channel's
 * reading and next-session notes (#667) — and [promptEnabled] locks the prompt while there is nothing to edit.
 */
@Composable
internal fun ChannelFormFields(
    name: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    nameEnabled: Boolean = true,
    promptEnabled: Boolean = true,
    promptNote: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val promptOverLimit = !SystemPromptLimit.fits(systemPrompt)
    val nameLabel = stringResource(R.string.channel_form_name_label)
    val promptLabel = stringResource(R.string.channel_form_prompt_label)
    val promptError = stringResource(R.string.channel_form_prompt_too_long)
    val fieldText = MaterialTheme.colorScheme.modalFieldText
    val cursor = SolidColor(MaterialTheme.colorScheme.primary)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldGap),
    ) {
        LabelledField(label = nameLabel) {
            BasicTextField(
                value = name,
                onValueChange = onNameChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = nameLabel }
                        .testTag(CHANNEL_NAME_FIELD_TAG),
                enabled = nameEnabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (nameEnabled) fieldText else fieldText.copy(alpha = 0.38f)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                cursorBrush = cursor,
                decorationBox = { innerTextField ->
                    FieldWell(minHeight = NameWellHeight, trailingInset = NameTrailingInset, content = innerTextField)
                },
            )
        }
        LabelledField(label = promptLabel) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = promptLabel
                                if (promptOverLimit) error(promptError)
                            }.testTag(CHANNEL_PROMPT_FIELD_TAG),
                    enabled = promptEnabled,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = if (promptEnabled) fieldText else fieldText.copy(alpha = 0.38f),
                        ),
                    minLines = PROMPT_MIN_LINES,
                    cursorBrush = cursor,
                    decorationBox = { innerTextField ->
                        FieldWell(minHeight = PromptWellHeight, trailingInset = WellInset, content = innerTextField)
                    },
                )
                when {
                    promptOverLimit ->
                        Text(
                            promptError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    promptNote != null -> Text(promptNote, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The frame's "Input large": its own label above a filled field, as `EditChatModal` draws it. */
@Composable
private fun LabelledField(
    label: String,
    field: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldLabelGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        field()
    }
}

/** A plain filled well with the form instance's exact insets and no Material field minimums. */
@Composable
private fun FieldWell(
    minHeight: Dp,
    trailingInset: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(MaterialTheme.colorScheme.modalFieldContainer, RoundedCornerShape(6.dp))
                .padding(start = WellInset, end = trailingInset, top = WellInset, bottom = WellInset),
    ) {
        content()
    }
}

@Preview(name = "Channel form — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Channel form — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChannelFormFieldsPreview() {
    PyrycodeMobileTheme {
        var name by remember { mutableStateOf(TextFieldValue("Release notes")) }
        var prompt by remember { mutableStateOf("Answer in short paragraphs.") }
        MobileModal(title = "Save as channel", onDismissRequest = {}, onSubmit = {}) {
            ChannelFormFields(
                name = name,
                onNameChange = { name = it },
                systemPrompt = prompt,
                onSystemPromptChange = { prompt = it },
            )
        }
    }
}
