package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun RenameDialog(
    initialName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RenameDialogInternal(
        initialName = initialName,
        initialValue =
            TextFieldValue(
                text = initialName,
                selection = TextRange(0, initialName.length),
            ),
        onSubmit = onSubmit,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun RenameDialogInternal(
    initialName: String,
    initialValue: TextFieldValue,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(initialValue) }
    val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
    val isSaveEnabled by remember(initialName) {
        derivedStateOf { trimmedName.isNotEmpty() && trimmedName != initialName }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.rename_dialog_title),
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
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                label = { Text(stringResource(R.string.rename_dialog_field_label)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = { if (isSaveEnabled) onSubmit(trimmedName) },
                    ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(trimmedName) },
                enabled = isSaveEnabled,
            ) {
                Text(stringResource(R.string.rename_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rename_dialog_cancel))
            }
        },
    )
}

@Preview(name = "RenameDialog — Pre-filled (Light)", showBackground = true, widthDp = 412)
@Composable
private fun RenameDialogPrefilledLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        RenameDialogInternal(
            initialName = "kitchenclaw refactor",
            initialValue =
                TextFieldValue(
                    text = "kitchenclaw refactor",
                    selection = TextRange(0, "kitchenclaw refactor".length),
                ),
            onSubmit = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "RenameDialog — Pre-filled (Dark)",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RenameDialogPrefilledDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        RenameDialogInternal(
            initialName = "kitchenclaw refactor",
            initialValue =
                TextFieldValue(
                    text = "kitchenclaw refactor",
                    selection = TextRange(0, "kitchenclaw refactor".length),
                ),
            onSubmit = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "RenameDialog — Edited (Light)", showBackground = true, widthDp = 412)
@Composable
private fun RenameDialogEditedLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        RenameDialogInternal(
            initialName = "kitchenclaw refactor",
            initialValue =
                TextFieldValue(
                    text = "kitchenclaw rewrite",
                    selection = TextRange("kitchenclaw rewrite".length),
                ),
            onSubmit = {},
            onDismiss = {},
        )
    }
}
