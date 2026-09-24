package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.components.ChannelFormFields
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

/**
 * Save as channel on the phone (#957): [ChannelFormFields] in a [MobileModal], driven entirely by its caller.
 *
 * Presentation only. It reports the trimmed name and the verbatim prompt through [onSubmit] and every
 * dismissal route through [onDismissRequest]; neither closes it — the caller removes it from composition.
 * OK needs a non-blank name and a prompt within [SystemPromptLimit.MAX_BYTES] UTF-8 bytes. The prompt
 * always opens empty: this flow never reads the stored prompt, and a blank one is the caller's cue to
 * write nothing.
 *
 * Both typed values are keyed on [conversationId], never on [initialName], [loading] or [error], so a
 * failed write leaves them in place for OK to retry. They use `remember`, not `rememberSaveable`: the
 * prompt may hold a pasted credential and stays out of the saved-state Bundle. [nameEditable] is `false`
 * once the promote has been confirmed, when a retry writes only the prompt. Keep [error] generic: the
 * shell announces it aloud.
 */
@Composable
fun SaveAsChannelDialog(
    conversationId: String,
    initialName: String,
    onSubmit: (name: String, systemPrompt: String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    nameEditable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    var name by remember(conversationId) {
        // The daemon wrote this name and sets no length limit, so it is clamped before layout. OK sends
        // the field back unedited, so the clamp must not end on half a surrogate pair.
        val boundedName =
            initialName.take(MAX_WORKSPACE_LABEL_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        mutableStateOf(TextFieldValue(text = boundedName, selection = TextRange(0, boundedName.length)))
    }
    var systemPrompt by remember(conversationId) { mutableStateOf("") }

    MobileModal(
        title = stringResource(R.string.save_as_channel_dialog_title),
        onDismissRequest = onDismissRequest,
        onSubmit = { onSubmit(name.text.trim(), systemPrompt) },
        modifier = modifier,
        submissionEnabled = name.text.isNotBlank() && SystemPromptLimit.fits(systemPrompt),
        loading = loading,
        error = error,
    ) {
        ChannelFormFields(
            name = name,
            onNameChange = { name = it },
            systemPrompt = systemPrompt,
            onSystemPromptChange = { systemPrompt = it },
            nameEnabled = nameEditable,
        )
    }
}

@Preview(name = "Save as channel — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Save as channel — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SaveAsChannelDialogPreview() {
    PyrycodeMobileTheme {
        SaveAsChannelDialog(
            conversationId = "preview",
            initialName = "New channel",
            onSubmit = { _, _ -> },
            onDismissRequest = {},
        )
    }
}
