package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * Create channel on the phone (#958): [ChannelFormFields] in a [MobileModal], driven entirely by its caller —
 * desktop's `CreateChannelDialog`, opened from a host's Channels-section plus.
 *
 * Presentation only. It reports the trimmed name and the verbatim prompt through [onSubmit] and every
 * dismissal route through [onDismissRequest]; neither closes it — the caller removes it from composition.
 * OK needs an available host, a non-blank name and a prompt within [SystemPromptLimit.MAX_BYTES] UTF-8
 * bytes. Both fields open empty; the form focuses the name. A blank prompt is the caller's cue to write none.
 *
 * Both typed values are keyed on the target ([serverId], [cwd]), never on [loading] or [error], so a failed
 * write leaves them in place for OK to retry. They use `remember`, not `rememberSaveable`: the prompt may
 * hold a pasted credential and stays out of the saved-state Bundle. [nameEditable] is `false` once the
 * create has been confirmed, when a retry writes only the prompt. Keep [error] generic: the shell
 * announces it aloud.
 */
@Composable
internal fun CreateChannelModal(
    serverId: String,
    cwd: String?,
    onSubmit: (name: String, systemPrompt: String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    nameEditable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    var name by remember(serverId, cwd) { mutableStateOf(TextFieldValue()) }
    var systemPrompt by remember(serverId, cwd) { mutableStateOf("") }

    MobileModal(
        title = stringResource(R.string.create_channel_title),
        onDismissRequest = onDismissRequest,
        onSubmit = { onSubmit(name.text.trim(), systemPrompt) },
        modifier = modifier,
        submissionEnabled = hostAvailable && name.text.isNotBlank() && SystemPromptLimit.fits(systemPrompt),
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

@Preview(name = "Create channel — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Create channel — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CreateChannelModalPreview() {
    PyrycodeMobileTheme {
        CreateChannelModal(
            serverId = "preview",
            cwd = "/preview",
            onSubmit = { _, _ -> },
            onDismissRequest = {},
        )
    }
}
