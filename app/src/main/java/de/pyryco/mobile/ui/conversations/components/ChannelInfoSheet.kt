package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.components.PROMPT_MIN_LINES
import de.pyryco.mobile.ui.components.PromptWellHeight
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl

internal data class ChannelInfoUiModel(
    val conversationName: String,
    val workspacePath: String,
    val createdLabel: String,
    val lastActivityLabel: String,
    val sessionCount: Int,
    val messageCount: Int,
    val memorySearch: MemorySearchReport,
    val channelId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelInfoSheet(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Gated on the thread state's "mutations supported" signal (#507): false in relay mode, where the
    // Actions are unavailable. Defaulted for previews/tests only — production threads the real value.
    mutationsSupported: Boolean = true,
    // #1342: the System prompt section's state and controls. `null` omits the section — a preview/test
    // seam; the thread host always passes a state while the sheet is open.
    systemPrompt: SystemPromptEditorState? = null,
    onSystemPromptChange: (String) -> Unit = {},
    onSystemPromptSave: () -> Unit = {},
    onSystemPromptClear: () -> Unit = {},
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(50),
                            ),
                )
            }
        },
    ) {
        ChannelInfoSheetContent(
            model = model,
            onRename = onRename,
            onArchive = onArchive,
            onDelete = onDelete,
            onInstallMemoryPlugin = onInstallMemoryPlugin,
            onDismiss = onDismiss,
            mutationsSupported = mutationsSupported,
            systemPrompt = systemPrompt,
            onSystemPromptChange = onSystemPromptChange,
            onSystemPromptSave = onSystemPromptSave,
            onSystemPromptClear = onSystemPromptClear,
        )
    }
}

@Composable
internal fun ChannelInfoSheetContent(
    model: ChannelInfoUiModel,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onInstallMemoryPlugin: () -> Unit,
    onDismiss: () -> Unit,
    mutationsSupported: Boolean = true,
    systemPrompt: SystemPromptEditorState? = null,
    onSystemPromptChange: (String) -> Unit = {},
    onSystemPromptSave: () -> Unit = {},
    onSystemPromptClear: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        TitleRow(title = model.conversationName, onClose = onDismiss)

        SectionHeader(text = "About")
        AboutRow(label = "Folder", value = model.workspacePath, valueIsPath = true)
        AboutRow(label = "Created", value = model.createdLabel)
        AboutRow(label = "Last activity", value = model.lastActivityLabel)
        AboutRow(label = "Total sessions", value = model.sessionCount.toString())
        AboutRow(label = "Total messages", value = model.messageCount.toString())

        SectionHeader(text = "Memory")
        MemoryRow(report = model.memorySearch, onInstall = onInstallMemoryPlugin)

        // Desktop shows this for every conversation, so it is not behind mutationsSupported.
        if (systemPrompt != null) {
            SectionHeader(text = SYSTEM_PROMPT_HEADER)
            SystemPromptSection(
                state = systemPrompt,
                onChange = onSystemPromptChange,
                onSave = onSystemPromptSave,
                onClear = onSystemPromptClear,
            )
        }

        if (mutationsSupported) {
            SectionHeader(text = "Actions")
            ActionsGrid(
                onRename = onRename,
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
            modifier = if (valueIsPath) Modifier else Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f).padding(start = if (valueIsPath) 16.dp else 8.dp),
            style =
                if (valueIsPath) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (valueIsPath) 1 else 2,
            overflow = if (valueIsPath) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MemoryRow(
    report: MemorySearchReport,
    onInstall: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f
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
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (report.shouldOfferMemoryInstall()) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                if (compact) {
                    Text(
                        text = "None",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compact) {
                        Text(
                            text = "None",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        Text(text = "Install", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else if (report.providers.isEmpty()) {
            Text(
                text =
                    if (report.availability == MemorySearchAvailability.Unknown) "Status unknown" else "Memory search unavailable",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                report.providers.forEach { provider ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = provider.displayName.take(120).ifBlank { "Unnamed provider" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = providerStatus(provider),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private fun providerStatus(provider: MemorySearchProvider): String =
    when {
        !provider.installed -> "Not installed"
        !provider.enabled -> "Disabled"
        provider.availability == MemorySearchAvailability.Available -> "Memory search available"
        provider.availability == MemorySearchAvailability.Unavailable -> "Memory search unavailable"
        else -> "Status unknown"
    }

@Composable
private fun ActionsGrid(
    onRename: () -> Unit,
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
            ActionCell(label = "Archive", onClick = onArchive, modifier = Modifier.weight(1f))
        }
        ActionCell(label = "Delete", onClick = onDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ActionCell(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The device suites' handle for the System prompt box. */
internal const val CHANNEL_INFO_PROMPT_FIELD_TAG: String = "channel-info-prompt"

// Desktop's SystemPromptSection copy (#1342), verbatim. No daemon string reaches any of these.
private const val SYSTEM_PROMPT_HEADER = "System prompt"
private const val SYSTEM_PROMPT_LOADING = "Reading the stored prompt from the daemon"
private const val SYSTEM_PROMPT_UNAVAILABLE = "Couldn't read the stored system prompt."
private const val SYSTEM_PROMPT_HINT =
    "This prompt is stored on the channel, so two channels on one repository can behave differently."
private const val SYSTEM_PROMPT_FIELD_LABEL = "System prompt for this channel"
private const val SYSTEM_PROMPT_OVER_LIMIT = "Over the ${SystemPromptLimit.MAX_BYTES}-byte limit. Shorten it before saving."
private const val SYSTEM_PROMPT_SAVING = "Saving"
private const val SYSTEM_PROMPT_SAVED = "Saved. A running session keeps the prompt it started with until Reset session."
private const val SYSTEM_PROMPT_DIFFERS =
    "The running session was started with a different prompt. Reset session applies the saved one."

/** Desktop's `WRITE_REJECTED`, one line per refusal the phone can classify. */
internal fun SystemPromptRefusal.line(): String =
    when (this) {
        SystemPromptRefusal.Malformed -> "Not saved: the daemon refused the request."
        SystemPromptRefusal.NotFound -> "Not saved: the daemon has no record of this channel."
        SystemPromptRefusal.Unclassified -> "Not saved: the daemon refused the write."
    }

/** The write line under the box: in flight, acknowledged, refused, or nothing yet. */
internal fun SystemPromptEditorState.Loaded.writeLine(): String? =
    when {
        saving -> SYSTEM_PROMPT_SAVING
        saved -> SYSTEM_PROMPT_SAVED
        saveFailed -> (refusal ?: SystemPromptRefusal.Unclassified).line()
        else -> null
    }

/**
 * The System prompt section (#1342), desktop's `SystemPromptSectionView`: a status line until the reading
 * arrives, then the box, its byte count and the Save and Clear controls. The prompt reaches only the text
 * field; it is never logged, keyed on, or put into a description.
 */
@Composable
private fun SystemPromptSection(
    state: SystemPromptEditorState,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val lineStyle = MaterialTheme.typography.bodySmall
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    when (state) {
        SystemPromptEditorState.Loading -> Text(SYSTEM_PROMPT_LOADING, lineModifier, lineColor, style = lineStyle)
        SystemPromptEditorState.Unavailable -> Text(SYSTEM_PROMPT_UNAVAILABLE, lineModifier, lineColor, style = lineStyle)
        is SystemPromptEditorState.Loaded -> {
            Text(SYSTEM_PROMPT_HINT, lineModifier, lineColor, style = lineStyle)
            BasicTextField(
                value = state.draft,
                onValueChange = onChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics {
                            contentDescription = SYSTEM_PROMPT_FIELD_LABEL
                            if (state.overLimit) error(SYSTEM_PROMPT_OVER_LIMIT)
                        }.testTag(CHANNEL_INFO_PROMPT_FIELD_TAG),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                minLines = PROMPT_MIN_LINES,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    // Edit channel's prompt well (ChannelFormFields), on the sheet's own container token.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = PromptWellHeight)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.modalControl)
                                .padding(16.dp),
                    ) {
                        innerTextField()
                    }
                },
            )
            Text(
                text = "${state.draftBytes} / ${SystemPromptLimit.MAX_BYTES} bytes",
                modifier = lineModifier,
                color = if (state.overLimit) MaterialTheme.colorScheme.error else lineColor,
                style = lineStyle,
            )
            if (state.overLimit) {
                Text(SYSTEM_PROMPT_OVER_LIMIT, lineModifier, MaterialTheme.colorScheme.error, style = lineStyle)
            }
            if (state.appliedStatus == SessionPromptStatus.Differs) {
                Text(SYSTEM_PROMPT_DIFFERS, lineModifier, lineColor, style = lineStyle)
            }
            state.writeLine()?.let { Text(it, lineModifier, lineColor, style = lineStyle) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionCell(label = "Save", onClick = onSave, modifier = Modifier.weight(1f), enabled = state.canSave)
                ActionCell(label = "Clear", onClick = onClear, modifier = Modifier.weight(1f), enabled = state.canClear)
            }
        }
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
        memorySearch = MemorySearchReport(MemorySearchAvailability.Absent, emptyList()),
        channelId = "ch_a8f3c2d1e9b7",
    )

private val SAMPLE_PROMPT =
    SystemPromptEditorState.Loaded(
        confirmed = "Answer in short paragraphs.",
        appliedStatus = SessionPromptStatus.Differs,
        draft = "Answer in short paragraphs.",
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
                    onArchive = {},
                    onDelete = {},
                    onInstallMemoryPlugin = {},
                    onDismiss = {},
                    systemPrompt = SAMPLE_PROMPT,
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
                    onArchive = {},
                    onDelete = {},
                    onInstallMemoryPlugin = {},
                    onDismiss = {},
                    systemPrompt = SAMPLE_PROMPT,
                )
            }
        }
    }
}
