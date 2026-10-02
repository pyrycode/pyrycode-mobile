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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.McpStatus
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.components.PROMPT_MIN_LINES
import de.pyryco.mobile.ui.components.PromptWellHeight
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl
import java.util.Locale

internal data class ChannelInfoUiModel(
    val conversationName: String,
    val workspacePath: String,
    val createdLabel: String,
    val lastActivityLabel: String,
    val sessionCount: Int,
    val messageCount: Int,
    val memorySearch: MemorySearchReport,
    val channelId: String,
    // #1346: the Session section's readings. The facts and the cost are Claude's claims, shown as inert
    // text and never driving a control.
    val agent: ConversationAgent = ConversationAgent.Claude,
    val sessionFacts: SessionFacts? = null,
    val sessionCostUsd: Double? = null,
    // #1344: the MCP server reading, or `null` when the session reports it cannot answer — which hides the section.
    val mcpServers: McpStatus? = null,
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
    // #1344: a row's Reconnect and its switch, with the row's Claude-authored server name and, for the switch, the
    // state asked for. Defaulted for previews/tests only — production wires both.
    onMcpReconnect: (String) -> Unit = {},
    onMcpToggle: (String, Boolean) -> Unit = { _, _ -> },
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
            onMcpReconnect = onMcpReconnect,
            onMcpToggle = onMcpToggle,
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
    onMcpReconnect: (String) -> Unit = {},
    onMcpToggle: (String, Boolean) -> Unit = { _, _ -> },
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        TitleRow(title = model.conversationName, onClose = onDismiss)

        SectionHeader(text = "About")
        AboutRow(label = "Folder", value = model.workspacePath, valueIsPath = true)
        AboutRow(label = "Created", value = model.createdLabel)
        AboutRow(label = "Last activity", value = model.lastActivityLabel)
        AboutRow(label = "Total sessions", value = model.sessionCount.toString())
        AboutRow(label = "Total messages", value = model.messageCount.toString())

        SessionSection(model)

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

        model.mcpServers?.let { mcp ->
            SectionHeader(text = "MCP servers")
            McpServersSection(status = mcp, onReconnect = onMcpReconnect, onToggle = onMcpToggle)
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
                .padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(lineHeightStyle = FrameLineBox),
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

/**
 * Keeps each line's full box, as the frames `668:5355` and `668:5460` draw it (#1488). Compose's default trim
 * drops the leading above the first line and below the last, which shrank the 40 px About rows to 35 px.
 */
private val FrameLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = FrameLineBox),
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
            style = MaterialTheme.typography.bodyLarge.copy(lineHeightStyle = FrameLineBox),
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
                        lineHeightStyle = FrameLineBox,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = FrameLineBox)
                },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (valueIsPath) 1 else 2,
            overflow = if (valueIsPath) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The Session section (#1346), desktop's: the agent's version and the permission mode Claude reports, then
 * Claude's cost estimate when there is one. Both facts are claims only; nothing here reads them as a setting.
 */
@Composable
private fun SessionSection(model: ChannelInfoUiModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val facts = model.sessionFacts
        val flagged = facts?.truncatedFields.orEmpty()
        SectionHeader(text = "Session")
        SessionRow(
            label = if (model.agent == ConversationAgent.Codex) "Codex version" else "Claude version",
            value = reportedSessionValue(facts?.claudeCodeVersion, CLAUDE_CODE_VERSION_FIELD in flagged),
            valueTag = CHANNEL_INFO_AGENT_VERSION_TAG,
        )
        SessionRow(
            label = "Reported permission mode",
            value = reportedSessionValue(facts?.permissionMode, PERMISSION_MODE_FIELD in flagged),
        )
        model.sessionCostUsd?.let { cost ->
            SessionRow(
                label = "Cost (Claude's estimate)",
                value = ReportedSessionValue(formatSessionCost(cost), truncated = false),
                valueTag = CHANNEL_INFO_SESSION_COST_TAG,
            )
        }
    }
}

/** The [AboutRow] layout, with "Not reported" for an absent value and a "Truncated" line under a cut one. */
@Composable
private fun SessionRow(
    label: String,
    value: ReportedSessionValue,
    valueTag: String? = null,
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
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeightStyle = FrameLineBox),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = value.text ?: "Not reported",
                modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = FrameLineBox),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            if (value.truncated) {
                Text(
                    text = "Truncated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One Session value as shown (#1346): [text] `null` reads "Not reported"; [truncated] tags it. */
internal data class ReportedSessionValue(
    val text: String?,
    val truncated: Boolean,
)

/**
 * A Claude-reported session value made inert (#1346): control and Unicode format code points (bidi
 * overrides among them) become spaces and the ends are trimmed, then the text is cut at
 * [MAX_SESSION_VALUE_CODE_POINTS] code points, never inside a surrogate pair. Nothing printable left reads
 * as not reported. A cut made here or one the daemon reported ([flaggedTruncated]) marks it truncated. The
 * result reaches `Text` only.
 */
internal fun reportedSessionValue(
    raw: String?,
    flaggedTruncated: Boolean,
): ReportedSessionValue {
    val printable =
        buildString {
            raw.orEmpty().codePoints().forEach { cp ->
                if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt()) {
                    append(' ')
                } else {
                    appendCodePoint(cp)
                }
            }
        }.trim()
    if (printable.isEmpty()) return ReportedSessionValue(null, flaggedTruncated)
    val cut = printable.codePointCount(0, printable.length) > MAX_SESSION_VALUE_CODE_POINTS
    val shown = if (cut) printable.substring(0, printable.offsetByCodePoints(0, MAX_SESSION_VALUE_CODE_POINTS)) else printable
    return ReportedSessionValue(shown, cut || flaggedTruncated)
}

/** `$0.42 est.`, rounded to cents (desktop's `formatSessionCost`). */
internal fun formatSessionCost(usd: Double): String = "$" + String.format(Locale.ROOT, "%.2f", usd) + " est."

/** The device suites' handles for the Session section's version and cost values (#1346). */
internal const val CHANNEL_INFO_AGENT_VERSION_TAG: String = "channel-info-agent-version"
internal const val CHANNEL_INFO_SESSION_COST_TAG: String = "channel-info-session-cost"

private const val MAX_SESSION_VALUE_CODE_POINTS = 256

/** The `session_facts.truncated_fields` entries (pyrycode `docs/protocol-mobile.md`). */
private const val CLAUDE_CODE_VERSION_FIELD = "claude_code_version"
private const val PERMISSION_MODE_FIELD = "permission_mode"

@Composable
private fun MemoryRow(
    report: MemorySearchReport,
    onInstall: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale >= 1.3f
    // #1488: beside the label, Install's 48 dp touch target alone fills the frame's 52 px row.
    val inlineInstall = report.shouldOfferMemoryInstall() && !compact
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (inlineInstall) 2.dp else 8.dp),
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
            modifier = Modifier.alpha(0.55f),
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
        sessionFacts = SessionFacts("2.1.143", "acceptEdits", null),
        sessionCostUsd = 0.42,
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
