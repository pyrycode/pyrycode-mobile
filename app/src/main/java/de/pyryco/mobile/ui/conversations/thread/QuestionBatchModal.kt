package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.ui.components.MobileGateModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText

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
        title =
            stringResource(
                when (state.agent) {
                    ConversationAgent.Claude -> R.string.question_modal_title
                    ConversationAgent.Codex -> R.string.question_modal_title_codex
                },
            ),
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

/** Figma `347:6697`: a tertiary header line above a bordered card holding the question and its rows. */
@Composable
private fun QuestionBlock(
    index: Int,
    question: Question,
    selection: QuestionSelection,
    enabled: Boolean,
    onEvent: (QuestionModalEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Image(
                painter = painterResource(R.drawable.ic_question_glyph),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.size(width = 14.dp, height = 16.dp).testTag("question_header_glyph_$index"),
            )
            Text(
                text = question.header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.modalControl,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).then(if (question.multiSelect) Modifier else Modifier.selectableGroup()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = question.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEachIndexed { optionIndex, option ->
                        ChoiceRow(
                            selected = optionIndex in selection.optionIndices,
                            multiSelect = question.multiSelect,
                            enabled = enabled,
                            controlTag = "question_control_${index}_$optionIndex",
                            onClick = { onEvent(QuestionModalEvent.OptionToggled(index, optionIndex)) },
                        ) {
                            Column(Modifier.padding(top = 2.dp)) {
                                Text(option.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(option.description, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    ChoiceRow(
                        selected = selection.otherTicked,
                        multiSelect = question.multiSelect,
                        enabled = enabled,
                        controlTag = "question_control_${index}_other",
                        other = true,
                        onClick = { onEvent(QuestionModalEvent.OtherToggled(index)) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.question_other),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            val placeholder = stringResource(R.string.question_other_placeholder)
                            BasicTextField(
                                value = selection.otherText,
                                onValueChange = { onEvent(QuestionModalEvent.OtherTextChanged(index, it)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("question_other_$index"),
                                enabled = enabled,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.modalFieldText),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { field ->
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 32.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.modalFieldContainer,
                                                        MaterialTheme.shapes.modalControl,
                                                    ).padding(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            if (selection.otherText.isEmpty()) {
                                                Text(
                                                    placeholder,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.inversePrimary,
                                                )
                                            }
                                            field()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
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
    controlTag: String,
    onClick: () -> Unit,
    other: Boolean = false,
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
        verticalAlignment = Alignment.Top,
    ) {
        val tertiary = MaterialTheme.colorScheme.tertiary
        val shape = if (multiSelect) MaterialTheme.shapes.extraSmall else CircleShape
        Box(
            modifier =
                Modifier
                    .padding(top = if (other) 8.dp else 0.dp)
                    .size(20.dp)
                    .border(2.dp, tertiary, shape)
                    .testTag(controlTag),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                if (multiSelect) {
                    Image(
                        painter = painterResource(R.drawable.ic_question_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tertiary),
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Box(Modifier.size(10.dp).background(tertiary, CircleShape))
                }
            }
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
