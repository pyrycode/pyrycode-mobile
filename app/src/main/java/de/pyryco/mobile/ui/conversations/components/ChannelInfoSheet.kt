package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

internal data class ChannelInfoUiModel(
    val conversationName: String,
    val workspacePath: String,
    val createdLabel: String,
    val lastActivityLabel: String,
    val sessionCount: Int,
    val messageCount: Int,
    val memoryPlugins: List<String>,
    val channelId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelInfoSheet(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Gated on the thread state's "mutations supported" signal (#507): false in relay mode, where the
    // Actions are unavailable. Defaulted for previews/tests only — production threads the real value.
    mutationsSupported: Boolean = true,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        ChannelInfoSheetContent(
            model = model,
            onRename = onRename,
            onChangeWorkspace = onChangeWorkspace,
            onArchive = onArchive,
            onDelete = onDelete,
            onInstallMemoryPlugin = onInstallMemoryPlugin,
            onDismiss = onDismiss,
            mutationsSupported = mutationsSupported,
        )
    }
}

@Composable
internal fun ChannelInfoSheetContent(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    mutationsSupported: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleRow(title = model.conversationName, onClose = onDismiss)

        SectionHeader(text = "About")
        AboutRow(label = "Workspace", value = model.workspacePath, valueIsPath = true)
        AboutRow(label = "Created", value = model.createdLabel)
        AboutRow(label = "Last activity", value = model.lastActivityLabel)
        AboutRow(label = "Total sessions", value = model.sessionCount.toString())
        AboutRow(label = "Total messages", value = model.messageCount.toString())

        SectionHeader(text = "Memory")
        MemoryRow(plugins = model.memoryPlugins, onInstall = onInstallMemoryPlugin)

        if (mutationsSupported) {
            SectionHeader(text = "Actions")
            ActionsGrid(
                onRename = onRename,
                onChangeWorkspace = onChangeWorkspace,
                onArchive = onArchive,
                onDelete = onDelete,
            )
        }

        Footer(channelId = model.channelId)
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
private fun AboutRow(
    label: String,
    value: String,
    valueIsPath: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (valueIsPath) {
            Text(
                text = value,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                textAlign = TextAlign.End,
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun MemoryRow(
    plugins: List<String>,
    onInstall: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Memory plugins",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (plugins.isEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "None",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Install",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                plugins.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsGrid(
    onRename: () -> Unit,
    onChangeWorkspace: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionCell(label = "Rename", onClick = onRename, modifier = Modifier.weight(1f))
            ActionCell(label = "Change workspace", onClick = onChangeWorkspace, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionCell(label = "Archive", onClick = onArchive, modifier = Modifier.weight(1f))
            ActionCell(label = "Delete", onClick = onDelete, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ActionCell(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Footer(channelId: String) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(channelId))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                ),
    ) {
        Text(
            text = "Channel ID: $channelId",
            modifier = Modifier.alpha(0.5f),
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SAMPLE_MODEL =
    ChannelInfoUiModel(
        conversationName = "kitchenclaw refactor",
        workspacePath = "~/Workspace/Projects/KitchenClaw",
        createdLabel = "3 weeks ago",
        lastActivityLabel = "2 hours ago",
        sessionCount = 12,
        messageCount = 347,
        memoryPlugins = emptyList(),
        channelId = "ch_a8f3c2d1e9b7",
    )

@Preview(name = "ChannelInfoSheet — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelInfoSheetPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                ChannelInfoSheetContent(
                    model = SAMPLE_MODEL,
                    onRename = {},
                    onChangeWorkspace = {},
                    onArchive = {},
                    onDelete = {},
                    onInstallMemoryPlugin = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(
    name = "ChannelInfoSheet — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChannelInfoSheetDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                ChannelInfoSheetContent(
                    model = SAMPLE_MODEL,
                    onRename = {},
                    onChangeWorkspace = {},
                    onArchive = {},
                    onDelete = {},
                    onInstallMemoryPlugin = {},
                    onDismiss = {},
                )
            }
        }
    }
}
