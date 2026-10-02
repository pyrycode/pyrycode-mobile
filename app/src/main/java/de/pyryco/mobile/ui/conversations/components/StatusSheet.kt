package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.components.MobileDismissModal
import de.pyryco.mobile.ui.conversations.thread.ThreadEffortChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadReportedText
import de.pyryco.mobile.ui.conversations.thread.ThreadRunningModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * The Status sheet's run-configuration surface.
 *
 * **Since #807 this is a dumb renderer of pre-sanitized choices.** The `Model` / `Effort` device enums it
 * used to iterate were this phone's guesses at a vocabulary the daemon publishes per conversation, and a
 * value the server never published is refused server-side. The ViewModel now owns the #791 trust
 * boundary — every label arriving here has already been made inert — so this file imports nothing from
 * `data/` and never sees a raw `ModelMenuRow`. [ThreadModelChoice.value] is the write argument and is
 * never rendered; the labels reach `Text` and nothing else.
 * The radio selection and shared modal presentation follow Figma's current Run configuration frame.
 */
@Composable
fun StatusSheet(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    effortNote: String? = null,
    running: ThreadRunningModel = ThreadRunningModel(),
    contextPercent: Int? = null,
    permissionMode: String = "",
    permissionChoices: List<Pair<String, String>> = emptyList(),
    onPermissionSelected: (String) -> Unit = {},
    permissionPending: Boolean = false,
    modelSelectionNote: String? = null,
) {
    MobileDismissModal(
        title = "Run configuration",
        actionLabel = "Done",
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        StatusSheetContent(
            choices = choices,
            menuAvailable = menuAvailable,
            notListedModels = notListedModels,
            selectedModel = selectedModel,
            modelSelectionNote = modelSelectionNote,
            onModelSelected = onModelSelected,
            effortChoices = effortChoices,
            selectedEffort = selectedEffort,
            onEffortSelected = onEffortSelected,
            pending = pending,
            enabled = enabled,
            effortNote = effortNote,
            running = running,
            contextPercent = contextPercent,
            permissionMode = permissionMode,
            permissionChoices = permissionChoices,
            onPermissionSelected = onPermissionSelected,
            permissionPending = permissionPending,
        )
    }
}

@Composable
internal fun StatusSheetContent(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    effortNote: String? = null,
    running: ThreadRunningModel = ThreadRunningModel(),
    contextPercent: Int? = null,
    permissionMode: String = "",
    permissionChoices: List<Pair<String, String>> = emptyList(),
    onPermissionSelected: (String) -> Unit = {},
    permissionPending: Boolean = false,
    modelSelectionNote: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(text = sectionTitle("Model", pending))
            ModelSection(choices, menuAvailable, notListedModels, selectedModel, modelSelectionNote, onModelSelected, enabled && !pending)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(text = sectionTitle("Effort", pending))
            EffortRadioRows(effortChoices, selectedEffort, onEffortSelected, enabled && !pending)
            effortNote?.let { Caption(text = it) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader(text = stringResource(R.string.status_sheet_running_model))
            RunningModelSection(running = running)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader(text = "Context window")
            ContextWindowSection(contextPercent = contextPercent)
        }
        // The current Figma frame omits Permission; keep the production control after its designed sections.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(text = sectionTitle("Permission", permissionPending))
            PermissionSection(permissionMode, permissionChoices, enabled && !permissionPending, onPermissionSelected)
        }
    }
}

/** A pending write is visible as well as disabling: the two changeable sections say so in their headers,
 *  so "your tap has not been confirmed yet" never has to be inferred from greyed controls alone. */
private fun sectionTitle(
    base: String,
    pending: Boolean,
): String = if (pending) "$base · applying…" else base

/**
 * The published models, in the daemon's own order. Three readings, kept apart: no menu at all, a menu
 * that named nothing, and a menu with rows — the #601 honest-unavailable idiom rather than a spinner,
 * because absence of a frame is the wire's only "no list" signal and is a normal resting state.
 */
@Composable
private fun ModelSection(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String?,
    selectionNote: String?,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
) {
    selectionNote?.let { UnavailableNote(text = it) }
    if (choices.isEmpty()) {
        UnavailableNote(
            text = if (menuAvailable) "This server published no selectable models." else "Model list unavailable",
        )
        return
    }
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        choices.forEach { choice ->
            ModelRow(
                choice = choice,
                selected = choice.value == selectedModel,
                enabled = enabled,
                onClick = { onModelSelected(choice.value) },
            )
        }
    }
    // Reported, never recomputed from choices.size — which is what lets this say "3 of 47" instead of
    // presenting a shortened menu as complete.
    if (notListedModels > 0) {
        Caption(text = "${choices.size} shown · $notListedModels not listed")
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun ModelRow(
    choice: ThreadModelChoice,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    onClick = onClick,
                    role = Role.RadioButton,
                ).heightIn(min = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected = selected, enabled = enabled)
        Spacer(modifier = Modifier.width(12.dp))
        // #807: claude's own label, already made inert by the ViewModel. #1497: 600:1694 draws the label alone on
        // one line, without the resolved-identifier helper line.
        Text(
            text = choice.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The selected row's own effort levels, in wire order. Empty is a positive statement that this model
 * exposes no effort control — never a cue to substitute the five `Effort` entries, which is exactly the
 * substitution #807 removes.
 */
@Composable
private fun EffortRadioRows(
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    enabled: Boolean,
) {
    if (effortChoices.isEmpty()) {
        UnavailableNote(text = "No effort levels published for this model.")
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        effortChoices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { effort ->
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .selectable(
                                    selected = effort.value == selectedEffort,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onEffortSelected(effort.value) },
                                ).heightIn(min = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioMark(selected = effort.value == selectedEffort, enabled = enabled)
                        Spacer(modifier = Modifier.width(12.dp))
                        // #1497: 600:1694 reads the published level capitalised; the write stays effort.value.
                        Text(
                            text = effort.label.replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Draw only the 20 dp visible circle; the selectable parent supplies the touch and radio semantics. */
@Composable
private fun RadioMark(
    selected: Boolean,
    enabled: Boolean,
) {
    val color = MaterialTheme.colorScheme.tertiary.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        modifier = Modifier.size(20.dp).border(BorderStroke(2.dp, color), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
private fun PermissionSection(
    selectedMode: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    if (selectedMode.isEmpty()) {
        UnavailableNote(text = "Permission mode unavailable")
        return
    }
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        choices.forEach { (value, label) ->
            val selected = value == selectedMode
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                            onSelected(value)
                        }.heightIn(min = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioMark(selected = selected, enabled = enabled)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

/**
 * The model claude announced and its build (#891), both already inert. Neither `Text` sets `maxLines`: the
 * inert bound caps the length, and an ellipsis would clip the very mark that says a value was cut.
 */
@Composable
private fun RunningModelSection(running: ThreadRunningModel) {
    val truncatedMark = stringResource(R.string.status_sheet_running_truncated)
    val model = running.model
    if (model == null) {
        UnavailableNote(text = stringResource(R.string.status_sheet_running_model_unavailable))
    } else {
        Text(
            text = withTruncationMark(model.text, model.truncated, truncatedMark),
            modifier = Modifier.fillMaxWidth().testTag(RUNNING_MODEL_TEST_TAG),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    running.build?.let { build ->
        Caption(
            text =
                withTruncationMark(
                    stringResource(R.string.status_sheet_running_build, build.text),
                    build.truncated,
                    truncatedMark,
                ),
        )
    }
}

/** [text] plus, when it was cut, the client-owned italic mark — text, so TalkBack reads it too. */
private fun withTruncationMark(
    text: String,
    truncated: Boolean,
    mark: String,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (truncated) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(mark) }
    }

/** Marks the running-model line for the rung-3 scenario (#891). A static tag; never daemon text. */
const val RUNNING_MODEL_TEST_TAG = "status_sheet_running_model"

/** The honest-unavailable line #601 established for the Context-window section, reused wherever the
 *  daemon published nothing to choose from. */
@Composable
private fun UnavailableNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun Caption(text: String) {
    Caption(text = AnnotatedString(text))
}

@Composable
private fun Caption(text: AnnotatedString) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

// The current modal design shows one reported reading and no helper copy. Never infer 0% from absence.
@Composable
private fun ContextWindowSection(contextPercent: Int?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text =
                if (contextPercent != null) {
                    stringResource(R.string.status_sheet_context_used, contextPercent)
                } else {
                    stringResource(R.string.status_sheet_context_unavailable)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// #807: the previews carry a menu shaped like one a daemon actually publishes — claude's own labels and
// per-row effort levels — rather than the three device enum entries they used to iterate.
private val PreviewOpus =
    ThreadModelChoice(
        value = "opus",
        label = "Opus 4.7",
        detail = "claude-opus-4-7",
        effortChoices = listOf("low", "medium", "high", "max").map { ThreadEffortChoice(it, it) },
    )

private val PreviewSonnet =
    ThreadModelChoice(
        value = "sonnet",
        label = "Sonnet 4.6",
        detail = "claude-sonnet-4-6",
        effortChoices = listOf("low", "high").map { ThreadEffortChoice(it, it) },
    )

private val PreviewHaiku =
    ThreadModelChoice(value = "haiku", label = "Haiku 4.5", detail = "claude-haiku-4-5", effortChoices = emptyList())

private val PreviewChoices = listOf(PreviewOpus, PreviewSonnet, PreviewHaiku)

private val PreviewRunning =
    ThreadRunningModel(
        model = ThreadReportedText("claude-opus-4-7", truncated = false),
        build = ThreadReportedText("2.1.3", truncated = false),
    )

@Composable
private fun PreviewSheet(
    choices: List<ThreadModelChoice> = PreviewChoices,
    menuAvailable: Boolean = true,
    notListedModels: Int = 0,
    selectedModel: String = PreviewOpus.value,
    selectedEffort: String = "high",
    pending: Boolean = false,
    enabled: Boolean = true,
    running: ThreadRunningModel = PreviewRunning,
    contextPercent: Int? = null,
    darkTheme: Boolean = false,
) {
    val selected = choices.firstOrNull { it.value == selectedModel }
    PyrycodeMobileTheme(darkTheme = darkTheme) {
        StatusSheet(
            choices = choices,
            menuAvailable = menuAvailable,
            notListedModels = notListedModels,
            selectedModel = selectedModel,
            onModelSelected = {},
            effortChoices = selected?.effortChoices.orEmpty(),
            selectedEffort = selectedEffort,
            onEffortSelected = {},
            pending = pending,
            enabled = enabled,
            onDismiss = {},
            running = running,
            contextPercent = contextPercent,
        )
    }
}

@Preview(name = "StatusSheet — published menu", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetPublishedPreview() = PreviewSheet()

@Preview(name = "StatusSheet — published menu, dark", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetPublishedDarkPreview() = PreviewSheet(darkTheme = true)

@Preview(name = "StatusSheet — row with no effort levels", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetNoEffortLevelsPreview() = PreviewSheet(selectedModel = PreviewHaiku.value)

@Preview(name = "StatusSheet — menu unavailable", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetMenuUnavailablePreview() = PreviewSheet(choices = emptyList(), menuAvailable = false)

@Preview(name = "StatusSheet — truncated menu", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetTruncatedMenuPreview() = PreviewSheet(notListedModels = 44)

@Preview(name = "StatusSheet — applying", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetPendingPreview() = PreviewSheet(pending = true)

@Preview(name = "StatusSheet — read-only session", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetReadOnlyPreview() = PreviewSheet(enabled = false, selectedModel = "")

@Preview(name = "StatusSheet — context usage reported", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetContextUsagePreview() = PreviewSheet(contextPercent = 84)

@Preview(name = "StatusSheet — running model not announced", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetRunningUnavailablePreview() = PreviewSheet(running = ThreadRunningModel())

@Preview(name = "StatusSheet — running model truncated, dark", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetRunningTruncatedDarkPreview() =
    PreviewSheet(
        running =
            ThreadRunningModel(
                model = ThreadReportedText("claude-opus-4-7-with-a-very-long-suffix", truncated = true),
                build = ThreadReportedText("2.1", truncated = true),
            ),
        darkTheme = true,
    )
