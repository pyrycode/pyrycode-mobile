package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.thread.WorkspaceChoice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun SaveAsChannelDialog(
    initialName: String,
    onSubmit: (name: String, workspace: WorkspaceChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SaveAsChannelDialogInternal(
        initialValue =
            TextFieldValue(
                text = initialName,
                selection = TextRange(0, initialName.length),
            ),
        initialWorkspace = WorkspaceChoice.DEDICATED,
        onSubmit = onSubmit,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun SaveAsChannelDialogInternal(
    initialValue: TextFieldValue,
    initialWorkspace: WorkspaceChoice,
    onSubmit: (name: String, workspace: WorkspaceChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(initialValue) }
    var selectedWorkspace by remember { mutableStateOf(initialWorkspace) }
    val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
    val isSaveEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.save_as_channel_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            // Placement is load-bearing: the field composes in the dialog window's own
            // sub-composition, so a requestFocus() driven from the parent composition returns
            // cleanly but never lands (#589 — measured 0 focused nodes). Keep the effect here,
            // beside the field it targets.
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.save_as_channel_dialog_field_label)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (isSaveEnabled) onSubmit(trimmedName, selectedWorkspace)
                            },
                        ),
                )
                WorkspaceRadios(
                    selected = selectedWorkspace,
                    onSelectedChange = { selectedWorkspace = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(trimmedName, selectedWorkspace) },
                enabled = isSaveEnabled,
            ) {
                Text(stringResource(R.string.save_as_channel_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.save_as_channel_dialog_cancel))
            }
        },
    )
}

@Composable
private fun WorkspaceRadios(
    selected: WorkspaceChoice,
    onSelectedChange: (WorkspaceChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == WorkspaceChoice.DEDICATED,
                        onClick = { onSelectedChange(WorkspaceChoice.DEDICATED) },
                        role = Role.RadioButton,
                    ).padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected == WorkspaceChoice.DEDICATED,
                onClick = null,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.save_as_channel_dialog_workspace_dedicated),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "~/pyry-workspace/channels/<auto-slug>/",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == WorkspaceChoice.SCRATCH,
                        onClick = { onSelectedChange(WorkspaceChoice.SCRATCH) },
                        role = Role.RadioButton,
                    ).padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected == WorkspaceChoice.SCRATCH,
                onClick = null,
            )
            Text(
                text = stringResource(R.string.save_as_channel_dialog_workspace_scratch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Preview(name = "SaveAsChannelDialog — Default (Light)", showBackground = true, widthDp = 412)
@Composable
private fun SaveAsChannelDialogDefaultLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        SaveAsChannelDialogInternal(
            initialValue =
                TextFieldValue(
                    text = "New channel",
                    selection = TextRange(0, "New channel".length),
                ),
            initialWorkspace = WorkspaceChoice.DEDICATED,
            onSubmit = { _, _ -> },
            onDismiss = {},
        )
    }
}

@Preview(name = "SaveAsChannelDialog — Scratch selected (Light)", showBackground = true, widthDp = 412)
@Composable
private fun SaveAsChannelDialogScratchLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        SaveAsChannelDialogInternal(
            initialValue =
                TextFieldValue(
                    text = "New channel",
                    selection = TextRange(0, "New channel".length),
                ),
            initialWorkspace = WorkspaceChoice.SCRATCH,
            onSubmit = { _, _ -> },
            onDismiss = {},
        )
    }
}

@Preview(
    name = "SaveAsChannelDialog — Default (Dark)",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SaveAsChannelDialogDefaultDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        SaveAsChannelDialogInternal(
            initialValue =
                TextFieldValue(
                    text = "New channel",
                    selection = TextRange(0, "New channel".length),
                ),
            initialWorkspace = WorkspaceChoice.DEDICATED,
            onSubmit = { _, _ -> },
            onDismiss = {},
        )
    }
}
