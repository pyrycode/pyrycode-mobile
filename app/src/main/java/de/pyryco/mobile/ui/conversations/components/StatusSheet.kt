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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.label
import de.pyryco.mobile.ui.settings.label
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
    yoloEnabled: Boolean,
    onYoloToggled: (Boolean) -> Unit,
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
            selectedModel = selectedModel,
            onModelSelected = onModelSelected,
            selectedEffort = selectedEffort,
            onEffortSelected = onEffortSelected,
            yoloEnabled = yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun StatusSheetContent(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
    yoloEnabled: Boolean,
    onYoloToggled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleRow(title = "Run configuration", onClose = onDismiss)
        SectionHeader(text = "Model")
        Column(modifier = Modifier.selectableGroup()) {
            Model.entries.forEach { model ->
                ModelRow(
                    model = model,
                    selected = model == selectedModel,
                    onClick = { onModelSelected(model) },
                )
            }
        }
        SectionHeader(text = "Effort")
        EffortChipRow(selectedEffort = selectedEffort, onEffortSelected = onEffortSelected)
        SectionHeader(text = "YOLO mode")
        YoloRow(enabled = yoloEnabled, onToggled = onYoloToggled)
        SectionHeader(text = "Context window")
        ContextWindowSection()
        Spacer(modifier = Modifier.height(24.dp))
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
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton,
                ).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = model.label(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = model.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Model.description(): String =
    when (this) {
        Model.OPUS_4_7 -> "best for complex work"
        Model.SONNET_4_6 -> "faster, cheaper"
        Model.HAIKU_4_5 -> "fastest"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortChipRow(
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Effort.entries.forEach { effort ->
            FilterChip(
                selected = effort == selectedEffort,
                onClick = { onEffortSelected(effort) },
                label = { Text(effort.label()) },
            )
        }
    }
}

@Composable
private fun YoloRow(
    enabled: Boolean,
    onToggled: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onToggled,
                ).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Auto-accept tool calls",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Claude runs commands without asking for confirmation. Use carefully.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
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

@Preview(name = "StatusSheet — Opus", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetOpusPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "StatusSheet — Sonnet", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetSonnetPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
                    selectedModel = Model.SONNET_4_6,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "StatusSheet — Haiku", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetHaikuPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
                    selectedModel = Model.HAIKU_4_5,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "StatusSheet — Effort low, YOLO off", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetEffortLowYoloOffPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.LOW,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "StatusSheet — Effort max, YOLO on", showBackground = true, widthDp = 412)
@Composable
private fun StatusSheetEffortMaxYoloOnPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.MAX,
                    onEffortSelected = {},
                    yoloEnabled = true,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }
    }
}
