package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.ui.components.MobileGateModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * The clarification batch held for the open conversation (#661), drawn in the hardened gate so Back,
 * outside taps and the missing close glyph never answer or refuse. Every question shows in batch order
 * in the shell's one scrolling column (desktop's tabs and Previous/Next are not used on the phone).
 *
 * Stateless: [state] carries the picks and [onEvent] reports every edit and action to the ViewModel.
 * Headers, question text, labels and descriptions are claude-authored and render only as plain,
 * wrapping [Text]. Rows are addressed by index; no claude text becomes a key or a tag.
 */
@Composable
internal fun QuestionBatchModal(
    state: QuestionModalState,
    onEvent: (QuestionModalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    MobileGateModal(
        title = stringResource(R.string.question_modal_title),
        modifier = modifier,
        cancelLabel = stringResource(R.string.modal_cancel),
        onCancel = { onEvent(QuestionModalEvent.Cancel) },
        submitLabel = stringResource(R.string.question_continue),
        onSubmit = { onEvent(QuestionModalEvent.Continue) },
        submissionEnabled = state.canContinue,
        sending = state.locked,
        error = if (state.phase == QuestionSendPhase.Failed) stringResource(R.string.question_send_failed) else null,
    ) {
        state.batch.questions.forEachIndexed { index, question ->
            QuestionBlock(
                index = index,
                question = question,
                selection = state.selections[index],
                enabled = !state.locked,
                onEvent = onEvent,
            )
        }
    }
}

/** Figma `347:6913`: a tertiary header line above a bordered card holding the question and its rows. */
@Composable
private fun QuestionBlock(
    index: Int,
    question: Question,
    selection: QuestionSelection,
    enabled: Boolean,
    onEvent: (QuestionModalEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = question.header,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).then(if (question.multiSelect) Modifier else Modifier.selectableGroup()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = question.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                question.options.forEachIndexed { optionIndex, option ->
                    ChoiceRow(
                        selected = optionIndex in selection.optionIndices,
                        multiSelect = question.multiSelect,
                        enabled = enabled,
                        onClick = { onEvent(QuestionModalEvent.OptionToggled(index, optionIndex)) },
                    ) {
                        Column {
                            Text(option.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(option.description, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                ChoiceRow(
                    selected = selection.otherTicked,
                    multiSelect = question.multiSelect,
                    enabled = enabled,
                    onClick = { onEvent(QuestionModalEvent.OtherToggled(index)) },
                ) {
                    Text(
                        stringResource(R.string.question_other),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                OutlinedTextField(
                    value = selection.otherText,
                    onValueChange = { onEvent(QuestionModalEvent.OtherTextChanged(index, it)) },
                    modifier = Modifier.fillMaxWidth().testTag("question_other_$index"),
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.question_other_placeholder)) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.small,
                )
            }
        }
    }
}

/** One option or Other row: radio semantics on a single-choice question, checkbox on a multiple-choice. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    val interaction =
        if (multiSelect) {
            Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        } else {
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(interaction),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tertiary = MaterialTheme.colorScheme.tertiary
        if (multiSelect) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = tertiary, uncheckedColor = tertiary),
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = tertiary, unselectedColor = tertiary),
            )
        }
        Column(Modifier.weight(1f)) { label() }
    }
}

@Preview(name = "Question modal — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Question modal — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QuestionBatchModalPreview() {
    val options = listOf(QuestionOption("Kotlin", "The JVM language"), QuestionOption("Rust", "A systems language"))
    val batch =
        QuestionBatch(
            conversationId = "preview",
            questionBatchId = "preview",
            questions =
                listOf(
                    Question("Which language should you learn next?", "Language", options, multiSelect = false),
                    Question("Which targets matter?", "Targets", options, multiSelect = true),
                ),
        )
    PyrycodeMobileTheme {
        QuestionBatchModal(
            state =
                QuestionModalState(
                    batch,
                    listOf(QuestionSelection(setOf(0)), QuestionSelection(otherTicked = true, otherText = "Web")),
                ),
            onEvent = {},
        )
    }
}
