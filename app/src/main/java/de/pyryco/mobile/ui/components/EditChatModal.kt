package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

/** The device suites' handle for the name field, which the design draws without a built-in label. */
internal const val EDIT_CHAT_NAME_FIELD_TAG: String = "edit-chat-name"

// The frame's 8dp label/field gap and the 8dp gap above the archive action.
private val FieldLabelGap = 8.dp
private val ArchiveTopPadding = 8.dp

/**
 * The Edit chat frame, drawn through [MobileModal] and driven entirely by its caller — desktop's
 * `EditChatDialogView` on the phone.
 *
 * Presentation only. It performs no storage, connection or navigation work: it reports the trimmed
 * name through [onSubmit], the archive intent through [onArchiveRequested] and every dismissal route
 * through [onDismissRequest], and none of the three closes it — the caller removes it from composition.
 *
 * The two actions read different halves of one guard, as desktop's do. OK needs a non-blank name, an
 * available host and no write in flight ([loading]). Archive needs only the host and no write in flight:
 * putting a chat away has nothing to do with what its name field holds, and it takes no confirmation
 * step because an archived chat comes back through Archive's Restore.
 *
 * The typed name is keyed on [conversationId], never on [initialName], so a list update that changes
 * the caller's name mid-edit cannot erase what the operator typed; [error], [loading] and
 * [hostAvailable] are not keys either. Keep [error] generic: the shell announces it aloud.
 */
@Composable
internal fun EditChatModal(
    conversationId: String,
    initialName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onArchiveRequested: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    var fieldValue by remember(conversationId) {
        // The daemon wrote this name and sets no length limit, so it is clamped before layout. OK sends
        // the field back unedited, so the clamp must not end on half a surrogate pair.
        val boundedName =
            initialName.take(MAX_WORKSPACE_LABEL_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        mutableStateOf(TextFieldValue(text = boundedName, selection = TextRange(boundedName.length)))
    }
    val submissionEnabled = hostAvailable && fieldValue.text.isNotBlank()
    val submit = { onSubmit(fieldValue.text.trim()) }

    MobileModal(
        title = stringResource(R.string.edit_chat_title),
        onDismissRequest = onDismissRequest,
        onSubmit = submit,
        modifier = modifier,
        submissionEnabled = submissionEnabled,
        loading = loading,
        error = error,
    ) {
        ChatNameField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            onDone = { if (submissionEnabled && !loading) submit() },
        )
        ArchiveAction(
            enabled = hostAvailable && !loading,
            onClick = {
                logEditChatEvent("archive_requested")
                onArchiveRequested()
            },
        )
    }
}

/** The frame's "Input large": its own label above a filled field, as `EditHostModal` draws it. */
@Composable
private fun ChatNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
) {
    val label = stringResource(R.string.edit_chat_name_label)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldLabelGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag(EDIT_CHAT_NAME_FIELD_TAG),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.modalFieldText),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(MaterialTheme.colorScheme.modalFieldContainer, RoundedCornerShape(6.dp))
                            .padding(start = 16.dp, end = 56.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    innerTextField()
                }
            },
        )
    }
}

/** The frame's outlined `Archive chat` action, grown to the shell's touch floor. */
@Composable
private fun ArchiveAction(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = ArchiveTopPadding)) {
        OutlinedButton(
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            onClick = onClick,
            modifier = Modifier.minimumInteractiveComponentSize(),
            enabled = enabled,
            shape = RoundedCornerShape(6.dp),
            border =
                BorderStroke(
                    1.dp,
                    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.edit_chat_archive),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Content-free and debug-gated, in the shell's shape: the event name and nothing it was given. */
private fun logEditChatEvent(event: String) {
    if (BuildConfig.DEBUG) Log.d("EditChatModal", "event=$event")
}

@Preview(name = "Edit chat — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Edit chat — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditChatModalPreview() {
    PyrycodeMobileTheme {
        EditChatModal(
            conversationId = "preview",
            initialName = "Release notes",
            onDismissRequest = {},
            onSubmit = {},
            onArchiveRequested = {},
        )
    }
}
