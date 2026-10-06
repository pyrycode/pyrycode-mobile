package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText

private val RenameLabelLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

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

    val submit = { if (isSaveEnabled) onSubmit(trimmedName) }
    MobileModal(
        title = stringResource(R.string.rename_dialog_title),
        onDismissRequest = onDismiss,
        onSubmit = submit,
        modifier = modifier,
        submissionEnabled = isSaveEnabled,
        cancelLabel = stringResource(R.string.rename_dialog_cancel),
        submitLabel = stringResource(R.string.rename_dialog_save),
    ) {
        // The field composes in the dialog window; a parent-side focus request cannot reach it.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        val label = stringResource(R.string.rename_dialog_field_label)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                // Figma 671:5664 draws this label in its full line box; the theme's default trims it to its
                // glyphs, which pulls the field below a couple of px closer than the frame (#1651).
                style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = RenameLabelLineBox),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            BasicTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.modalFieldText),
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = { submit() },
                    ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .background(MaterialTheme.colorScheme.modalFieldContainer, MaterialTheme.shapes.modalControl)
                                .padding(start = 16.dp, end = 56.dp, top = 16.dp, bottom = 16.dp),
                    ) {
                        innerTextField()
                    }
                },
            )
        }
    }
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
