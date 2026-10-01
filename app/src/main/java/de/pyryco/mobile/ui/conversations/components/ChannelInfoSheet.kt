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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = value.text ?: "Not reported",
                modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
                style = MaterialTheme.typography.bodyMedium,
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
        memorySearch = MemorySearchReport(MemorySearchAvailability.Absent, emptyList()),
        channelId = "ch_a8f3c2d1e9b7",
        sessionFacts = SessionFacts("2.1.143", "acceptEdits", null),
        sessionCostUsd = 0.42,
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
                )
            }
        }
    }
}
