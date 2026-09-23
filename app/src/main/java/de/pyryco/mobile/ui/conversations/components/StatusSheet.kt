package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.conversations.thread.ThreadEffortChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        StatusSheetContent(
            choices = choices,
            menuAvailable = menuAvailable,
            notListedModels = notListedModels,
            selectedModel = selectedModel,
            onModelSelected = onModelSelected,
            effortChoices = effortChoices,
            selectedEffort = selectedEffort,
            onEffortSelected = onEffortSelected,
            pending = pending,
            enabled = enabled,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun StatusSheetContent(
    choices: List<ThreadModelChoice>,
    menuAvailable: Boolean,
    notListedModels: Int,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    pending: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    // The menu length is the daemon's, and its producer cap is not a wire constant — so the body scrolls
    // rather than clipping the Context-window section below a long Model section. #650 moved the
    // permission control to the composer footer, where it reads the daemon's confirmed mode. The
    // SettingsScreen / MobileModal idiom; ModalBottomSheet handles the nested scroll.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
    ) {
        TitleRow(title = "Run configuration", onClose = onDismiss)
        SectionHeader(text = sectionTitle("Model", pending))
        ModelSection(
            choices = choices,
            menuAvailable = menuAvailable,
            notListedModels = notListedModels,
            selectedModel = selectedModel,
            onModelSelected = onModelSelected,
            enabled = enabled && !pending,
        )
        SectionHeader(text = sectionTitle("Effort", pending))
        EffortChipRow(
            effortChoices = effortChoices,
            selectedEffort = selectedEffort,
            onEffortSelected = onEffortSelected,
            enabled = enabled && !pending,
        )
        SectionHeader(text = "Context window")
        ContextWindowSection()
        Spacer(modifier = Modifier.height(24.dp))
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
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    enabled: Boolean,
) {
    if (choices.isEmpty()) {
        UnavailableNote(
            text = if (menuAvailable) "This server published no models." else "Model list unavailable",
        )
        return
    }
    Column(modifier = Modifier.selectableGroup()) {
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
private fun TitleRow(
    title: String,
    onClose: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                ).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            // #807: claude's own label, already made inert by the ViewModel. maxLines guards the layout
            // against a label the daemon bounded but did not shape.
            Text(
                text = choice.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Replaces #254's private `Model.description()` mapping table, whose three Figma-derived
            // strings described three device enum entries that no longer drive this sheet. The row's own
            // `resolvedModel` is the honest equivalent: what this family currently resolves to.
            if (choice.detail.isNotEmpty()) {
                Text(
                    text = choice.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The selected row's own effort levels, in wire order. Empty is a positive statement that this model
 * exposes no effort control — never a cue to substitute the five `Effort` entries, which is exactly the
 * substitution #807 removes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortChipRow(
    effortChoices: List<ThreadEffortChoice>,
    selectedEffort: String,
    onEffortSelected: (String) -> Unit,
    enabled: Boolean,
) {
    if (effortChoices.isEmpty()) {
        UnavailableNote(text = "No effort levels published for this model.")
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        effortChoices.forEach { effort ->
            FilterChip(
                selected = effort.value == selectedEffort,
                enabled = enabled,
                onClick = { onEffortSelected(effort.value) },
                label = {
                    Text(text = effort.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

/** The honest-unavailable line #601 established for the Context-window section, reused wherever the
 *  daemon published nothing to choose from. */
@Composable
private fun UnavailableNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Deliberate divergence from Figma node 20:151, which draws a populated figure and a
// severity-coloured bar. The daemon does not serve mobile a real context figure yet (#591), so a
// number here would always be a stub. Wording matches desktop; layout and typography are unchanged.
@Composable
private fun ContextWindowSection() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Context usage unavailable",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                "When full, oldest messages get dropped from claude's view " +
                    "(delimiter still shows; old messages stay in your scroll).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun PreviewSheet(
    choices: List<ThreadModelChoice> = PreviewChoices,
    menuAvailable: Boolean = true,
    notListedModels: Int = 0,
    selectedModel: String = PreviewOpus.value,
    selectedEffort: String = "high",
    pending: Boolean = false,
    enabled: Boolean = true,
    darkTheme: Boolean = false,
) {
    val selected = choices.firstOrNull { it.value == selectedModel }
    PyrycodeMobileTheme(darkTheme = darkTheme) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
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
                )
            }
        }
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
