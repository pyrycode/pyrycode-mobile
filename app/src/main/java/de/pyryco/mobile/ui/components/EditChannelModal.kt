package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.conversations.list.ChannelPromptReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

// The frame's 8dp gap above the archive action, and the shell's touch floor for it.
private val ArchiveTopPadding = 8.dp
private val ActionMinHeight = 48.dp

/**
 * Edit channel on the phone (#667): [ChannelFormFields] plus an outlined Archive channel action in a
 * [MobileModal], driven entirely by its caller — desktop's `EditChannelDialog`, opened from a Channels row's pen.
 *
 * Presentation only. It reports the trimmed name and the prompt through [onSubmit], the archive intent
 * through [onArchiveRequested] and every dismissal route through [onDismissRequest]; none closes it.
 *
 * The name opens with [initialName]. The prompt field stays disabled, with a static line under it, until
 * [prompt] is a [ChannelPromptReading.Read]; it then shows the stored prompt verbatim, and [onSubmit] carries
 * the field's text. Until then [onSubmit] carries `null`, so nothing the operator never saw can be written.
 * A `Differs` reading adds the next-session note. OK needs an available host, a non-blank name and a prompt
 * within [SystemPromptLimit.MAX_BYTES] UTF-8 bytes; Archive needs only the host and no write in flight.
 *
 * Both typed values are keyed on [conversationId] alone, so a failure, a reconnect or a late reading leaves
 * them in place. They use `remember`, not `rememberSaveable`: the prompt may hold a pasted credential and
 * stays out of the saved-state Bundle. Keep [error] generic: the shell announces it aloud.
 */
@Composable
internal fun EditChannelModal(
    conversationId: String,
    initialName: String,
    prompt: ChannelPromptReading,
    onSubmit: (name: String, systemPrompt: String?) -> Unit,
    onArchiveRequested: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    var name by remember(conversationId) {
        mutableStateOf(TextFieldValue(text = initialName, selection = TextRange(initialName.length)))
    }
    var typedPrompt by remember(conversationId) { mutableStateOf<String?>(null) }
    // Derived, not copied in by an effect: the field holds the stored prompt from the draw it arrives in,
    // and is null — disabled, and reported as null — until then. Only an enabled field can be typed into.
    val shownPrompt = typedPrompt ?: (prompt as? ChannelPromptReading.Read)?.let { it.prompt.orEmpty() }
    val note =
        when (prompt) {
            ChannelPromptReading.Reading -> stringResource(R.string.edit_channel_prompt_reading)
            ChannelPromptReading.Unavailable -> stringResource(R.string.edit_channel_prompt_unavailable)
            is ChannelPromptReading.Read ->
                if (prompt.status == SessionPromptStatus.Differs) stringResource(R.string.edit_channel_prompt_next_session) else null
        }

    MobileModal(
        title = stringResource(R.string.edit_channel_title),
        onDismissRequest = onDismissRequest,
        onSubmit = { onSubmit(name.text.trim(), shownPrompt) },
        modifier = modifier,
        submissionEnabled =
            hostAvailable && name.text.isNotBlank() && (shownPrompt == null || SystemPromptLimit.fits(shownPrompt)),
        loading = loading,
        error = error,
    ) {
        ChannelFormFields(
            name = name,
            onNameChange = { name = it },
            systemPrompt = shownPrompt.orEmpty(),
            onSystemPromptChange = { typedPrompt = it },
            promptEnabled = shownPrompt != null,
            promptNote = note,
        )
        ArchiveChannelAction(enabled = hostAvailable && !loading, onClick = onArchiveRequested)
    }
}

/** The frame's outlined `Archive channel` action, drawn as `EditChatModal`'s archive action is. */
@Composable
private fun ArchiveChannelAction(
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
                text = stringResource(R.string.edit_channel_archive),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Preview(name = "Edit channel — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Edit channel — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditChannelModalPreview() {
    PyrycodeMobileTheme {
        EditChannelModal(
            conversationId = "preview",
            initialName = "Release notes",
            prompt = ChannelPromptReading.Read("Answer in short paragraphs.", SessionPromptStatus.Differs),
            onSubmit = { _, _ -> },
            onArchiveRequested = {},
            onDismissRequest = {},
        )
    }
}

@Preview(name = "Edit channel, reading — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
private fun EditChannelModalReadingPreview() {
    PyrycodeMobileTheme {
        EditChannelModal(
            conversationId = "preview",
            initialName = "Release notes",
            prompt = ChannelPromptReading.Reading,
            onSubmit = { _, _ -> },
            onArchiveRequested = {},
            onDismissRequest = {},
        )
    }
}
