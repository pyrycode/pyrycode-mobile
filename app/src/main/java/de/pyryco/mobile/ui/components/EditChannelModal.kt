package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import de.pyryco.mobile.ui.theme.modalControl

// The frame's 8dp gap above the archive action, and the shell's touch floor for it.
private val ArchiveTopPadding = 8.dp
private val ActionMinHeight = 48.dp

// The desktop frame's 12dp between the checkbox and its label (#1021).
private val CheckboxLabelGap = 12.dp

/**
 * Edit channel on the phone (#667): [ChannelFormFields] plus an outlined Archive channel action in a
 * [MobileModal], driven entirely by its caller — desktop's `EditChannelDialog`, opened from a Channels row's pen.
 *
 * Presentation only. It reports the trimmed name and the prompt through [onSubmit], the archive intent
 * through [onArchiveRequested] and every dismissal route through [onDismissRequest]; none closes it.
 * [onSubmit] also carries the Mute notifications checkbox (#1021), which opens at [initialMuted] — the host's
 * stored flag — and is reported as it stands; the caller decides whether that is a change.
 *
 * The name opens with [initialName]. The prompt field stays disabled, with a static line under it, until
 * [prompt] is a [ChannelPromptReading.Read]; it then shows the stored prompt verbatim, and [onSubmit] carries
 * the field's text. Until then [onSubmit] carries `null`, so nothing the operator never saw can be written.
 * A `Differs` reading adds the next-session note. OK needs an available host, a non-blank name and a prompt
 * within [SystemPromptLimit.MAX_BYTES] UTF-8 bytes; Archive needs only the host and no write in flight.
 *
 * The typed values and the checkbox are keyed on [conversationId] alone, so a failure, a reconnect or a late reading leaves
 * them in place. They use `remember`, not `rememberSaveable`: the prompt may hold a pasted credential and
 * stays out of the saved-state Bundle. Keep [error] generic: the shell announces it aloud.
 */
@Composable
internal fun EditChannelModal(
    conversationId: String,
    initialName: String,
    prompt: ChannelPromptReading,
    initialMuted: Boolean,
    onSubmit: (name: String, systemPrompt: String?, muted: Boolean) -> Unit,
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
    var muted by remember(conversationId) { mutableStateOf(initialMuted) }
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
        onSubmit = { onSubmit(name.text.trim(), shownPrompt, muted) },
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
        MuteNotificationsRow(checked = muted, onCheckedChange = { muted = it })
        ArchiveChannelAction(enabled = hostAvailable && !loading, onClick = onArchiveRequested)
    }
}

/**
 * The desktop frame's "Checkbox with label" (`500-2120`): a tertiary box, then the label in label-medium
 * SemiBold. No mobile frame draws it, so it takes the permission modal's checkbox row: the whole row toggles
 * with the checkbox role, at the shell's touch floor.
 */
@Composable
private fun MuteNotificationsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ActionMinHeight)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CheckboxLabelGap),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.tertiary,
                    uncheckedColor = MaterialTheme.colorScheme.tertiary,
                    checkmarkColor = MaterialTheme.colorScheme.onTertiary,
                ),
        )
        Text(
            text = stringResource(R.string.edit_channel_mute),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
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
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            onClick = onClick,
            modifier = Modifier.minimumInteractiveComponentSize(),
            enabled = enabled,
            shape = MaterialTheme.shapes.modalControl,
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
            initialMuted = true,
            onSubmit = { _, _, _ -> },
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
            initialMuted = false,
            onSubmit = { _, _, _ -> },
            onArchiveRequested = {},
            onDismissRequest = {},
        )
    }
}
