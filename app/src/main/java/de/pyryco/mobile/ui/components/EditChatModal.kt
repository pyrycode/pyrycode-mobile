package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

/** The device suites' handle for the name field, which the design draws without a built-in label. */
internal const val EDIT_CHAT_NAME_FIELD_TAG: String = "edit-chat-name"

// The frame's 8dp label/field gap and the 8dp gap above the archive action.
private val FieldLabelGap = 8.dp
private val ArchiveTopPadding = 8.dp

// The shell's touch floor for its own actions, applied to this component's one action.
private val ActionMinHeight = 48.dp

// The frame's `on-primary` 41% fill turns white in the light scheme; `EditHostModal`'s
// `FIELD_FILL_ALPHA` records why the shell's content colour at a low alpha replaces it.
private const val FIELD_FILL_ALPHA = 0.12f

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
        // The daemon wrote this name and sets no length limit, so it is clamped before layout.
        val boundedName = initialName.take(MAX_WORKSPACE_LABEL_CHARS)
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldLabelGap),
    ) {
        Text(
            text = stringResource(R.string.edit_chat_name_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        val fill = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = FIELD_FILL_ALPHA)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(EDIT_CHAT_NAME_FIELD_TAG),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            // The design draws a plain filled well with no underline.
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = fill,
                    unfocusedContainerColor = fill,
                    disabledContainerColor = fill,
                    focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
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
            onClick = onClick,
            modifier = Modifier.heightIn(min = ActionMinHeight),
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
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
