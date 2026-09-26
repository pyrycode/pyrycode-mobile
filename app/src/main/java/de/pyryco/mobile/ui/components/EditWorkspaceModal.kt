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
import androidx.compose.material3.ButtonDefaults
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
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_BYTES
import de.pyryco.mobile.ui.workspace.clampWorkspaceText
import de.pyryco.mobile.ui.workspace.isWorkspaceLabelTooLong
import de.pyryco.mobile.ui.workspace.workspaceLabelByteCount
import de.pyryco.mobile.ui.workspace.workspaceLabelFor

/** The device suites' handle for the name field, which the design draws without a built-in label. */
internal const val EDIT_WORKSPACE_NAME_FIELD_TAG: String = "edit-workspace-name"

// The frame's 8dp label/field gap and the 8dp gap above the archive action.
private val FieldLabelGap = 8.dp
private val ArchiveTopPadding = 8.dp

// The shell's touch floor for its own actions, applied to this component's one action.
private val ActionMinHeight = 48.dp

/**
 * The Edit workspace frame, drawn through [MobileModal] and driven entirely by its caller — desktop's
 * `EditWorkspaceDialogView` on the phone (#905).
 *
 * Presentation only. It reports the trimmed name through [onSubmit], the archive intent through
 * [onArchiveRequested], the confirmation's answer through [onArchiveConfirmed] / [onArchiveDeclined] and
 * every dismissal from the editor through [onDismissRequest]; none of them closes it.
 *
 * [serverId] and [cwd] are the buffer's keys and nothing else: never drawn, logged or reported, so the
 * path stays out of this component's output. [folderName] is the workspace's name with no label, which
 * the label rule compares against; [initialName] is the row's shown name, daemon-authored and clamped
 * here before it reaches layout or the prompt.
 *
 * OK needs an available host and a label the daemon would accept: a blank name is allowed, since it
 * clears the label. The field reports the trimmed name's size in UTF-8 bytes, the daemon's unit.
 *
 * While [confirmingArchive] the content is replaced **in place** by one prompt naming the workspace, and
 * the shell's own footer decides it — OK is [onArchiveConfirmed], and Cancel, the close glyph and Back
 * are [onArchiveDeclined] — for the reasons `EditHostModal`'s unpair confirmation records. The buffer is
 * not keyed on that flag, so declining comes back to exactly what the operator typed. Keep [error]
 * generic: the shell announces it aloud.
 */
@Composable
internal fun EditWorkspaceModal(
    serverId: String,
    cwd: String,
    initialName: String,
    folderName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onArchiveRequested: () -> Unit,
    onArchiveConfirmed: () -> Unit,
    onArchiveDeclined: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    confirmingArchive: Boolean = false,
) {
    // The label rule recognises this same cut of the folder's own name, so an untouched OK still clears.
    val boundedName = clampWorkspaceText(initialName)
    var fieldValue by remember(serverId, cwd) {
        mutableStateOf(TextFieldValue(text = boundedName, selection = TextRange(boundedName.length)))
    }
    val tooLong = isWorkspaceLabelTooLong(workspaceLabelFor(fieldValue.text, folderName))
    val submit = { onSubmit(fieldValue.text.trim()) }

    MobileModal(
        title = stringResource(if (confirmingArchive) R.string.edit_workspace_archive_confirm_title else R.string.edit_workspace_title),
        onDismissRequest = if (confirmingArchive) onArchiveDeclined else onDismissRequest,
        onSubmit = if (confirmingArchive) onArchiveConfirmed else submit,
        modifier = modifier,
        submissionEnabled = hostAvailable && (confirmingArchive || !tooLong),
        loading = loading,
        error = error,
    ) {
        if (confirmingArchive) {
            Text(
                text = stringResource(R.string.edit_workspace_archive_confirm_body, boundedName),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            WorkspaceNameField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                byteCount = workspaceLabelByteCount(fieldValue.text.trim()),
                tooLong = tooLong,
                onDone = { if (hostAvailable && !tooLong && !loading) submit() },
            )
            ArchiveAction(
                enabled = hostAvailable && !loading,
                onClick = {
                    logEditWorkspaceEvent("archive_requested")
                    onArchiveRequested()
                },
            )
        }
    }
}

/** The frame's "Input large", plus the length line the frame does not draw. */
@Composable
private fun WorkspaceNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    byteCount: Int,
    tooLong: Boolean,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldLabelGap),
    ) {
        Text(
            text = stringResource(R.string.edit_workspace_name_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        val fill = MaterialTheme.colorScheme.modalFieldContainer
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(EDIT_WORKSPACE_NAME_FIELD_TAG),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            isError = tooLong,
            supportingText = { Text(stringResource(R.string.edit_workspace_name_length, byteCount, MAX_WORKSPACE_LABEL_BYTES)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            // The design draws a plain filled well with no underline.
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = fill,
                    unfocusedContainerColor = fill,
                    disabledContainerColor = fill,
                    errorContainerColor = fill,
                    focusedTextColor = MaterialTheme.colorScheme.modalFieldText,
                    unfocusedTextColor = MaterialTheme.colorScheme.modalFieldText,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
        )
    }
}

/** The frame's outlined `Archive workspace` action, grown to the shell's touch floor. */
@Composable
private fun ArchiveAction(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = ArchiveTopPadding)) {
        OutlinedButton(
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
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
                text = stringResource(R.string.edit_workspace_archive),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Content-free and debug-gated, in the shell's shape: the event name and nothing it was given. */
private fun logEditWorkspaceEvent(event: String) {
    if (BuildConfig.DEBUG) Log.d("EditWorkspaceModal", "event=$event")
}

@Composable
private fun PreviewModal(confirmingArchive: Boolean) {
    PyrycodeMobileTheme {
        EditWorkspaceModal(
            serverId = "preview",
            cwd = "~/Workspace/Second Brain",
            initialName = "Second Brain",
            folderName = "Second Brain",
            onDismissRequest = {},
            onSubmit = {},
            onArchiveRequested = {},
            onArchiveConfirmed = {},
            onArchiveDeclined = {},
            confirmingArchive = confirmingArchive,
        )
    }
}

@Preview(name = "Edit workspace — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Edit workspace — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditWorkspaceModalPreview() {
    PreviewModal(confirmingArchive = false)
}

@Preview(name = "Archive confirmation — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(
    name = "Archive confirmation — Dark",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EditWorkspaceModalArchiveConfirmationPreview() {
    PreviewModal(confirmingArchive = true)
}
