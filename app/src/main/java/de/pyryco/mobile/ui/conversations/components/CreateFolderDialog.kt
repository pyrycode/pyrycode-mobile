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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
internal fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CreateFolderDialogInternal(
        initialValue = TextFieldValue(text = "", selection = TextRange.Zero),
        onCreate = onCreate,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun CreateFolderDialogInternal(
    initialValue: TextFieldValue,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember { mutableStateOf(initialValue) }
    val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
    val isCreateEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = "Create workspace",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                label = { Text("What should this workspace be called?") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = { if (isCreateEnabled) onCreate(trimmedName) },
                    ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(trimmedName) },
                enabled = isCreateEnabled,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Preview(name = "CreateFolderDialog — Empty (Light)", showBackground = true, widthDp = 412)
@Composable
private fun CreateFolderDialogEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        CreateFolderDialogInternal(
            initialValue = TextFieldValue(text = "", selection = TextRange.Zero),
            onCreate = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "CreateFolderDialog — Filled (Light)", showBackground = true, widthDp = 412)
@Composable
private fun CreateFolderDialogFilledPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        CreateFolderDialogInternal(
            initialValue =
                TextFieldValue(
                    text = "my-new-workspace",
                    selection = TextRange(0, "my-new-workspace".length),
                ),
            onCreate = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "CreateFolderDialog — Filled (Dark)",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CreateFolderDialogFilledDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        CreateFolderDialogInternal(
            initialValue =
                TextFieldValue(
                    text = "my-new-workspace",
                    selection = TextRange(0, "my-new-workspace".length),
                ),
            onCreate = {},
            onDismiss = {},
        )
    }
}
