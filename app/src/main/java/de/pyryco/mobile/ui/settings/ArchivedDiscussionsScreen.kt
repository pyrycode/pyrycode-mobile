package de.pyryco.mobile.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.conversations.components.ArchiveRow
import de.pyryco.mobile.ui.theme.LocalStaticDarkPalette
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant

@Composable
fun ArchivedDiscussionsScreen(
    state: ArchivedDiscussionsUiState,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
    effects: Flow<ArchivedDiscussionsEffect> = emptyFlow(),
    hostName: String = "",
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(effects, snackbarHostState) {
        effects.collect { effect ->
            when (effect) {
                is ArchivedDiscussionsEffect.RestoreSucceeded ->
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.restored_snackbar, effect.displayName),
                    )
                ArchivedDiscussionsEffect.RestoreFailed ->
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.restore_failed),
                    )
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val staticDark = LocalStaticDarkPalette.current
    val glow = scheme.primary.copy(alpha = 0.12f).compositeOver(scheme.onPrimary)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(scheme.background)
                .drawBehind {
                    if (staticDark) {
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    0f to glow,
                                    0.45f to scheme.onPrimary.copy(alpha = 0.4f),
                                    0.8f to scheme.onPrimary.copy(alpha = 0f),
                                    center = Offset(size.width * 0.48f, size.height * 0.3f),
                                    radius = size.height * 0.56f,
                                ),
                        )
                    }
                },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(start = 4.dp, end = 16.dp)
                            .testTag("archive_header"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = { onEvent(ArchivedDiscussionsEvent.BackTapped) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = scheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(R.string.archived_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { inner ->
            Column(modifier = Modifier.padding(inner)) {
                // The selected host is an intentional adaptation: Figma 18:2 has no owner label.
                // Keep it outside the fixed-height header so enlarged text can grow vertically.
                if (hostName.isNotBlank()) {
                    Text(
                        text = hostName.take(MAX_WORKSPACE_LABEL_CHARS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                when (state) {
                    ArchivedDiscussionsUiState.Loading -> CenteredText("Loading…", Modifier)
                    is ArchivedDiscussionsUiState.Error ->
                        CenteredText(
                            "Couldn't load archived discussions: ${state.message}",
                            Modifier,
                        )
                    is ArchivedDiscussionsUiState.Loaded ->
                        LoadedBody(
                            state = state,
                            onEvent = onEvent,
                        )
                }
            }
        }
    }
}

@Composable
private fun LoadedBody(
    state: ArchivedDiscussionsUiState.Loaded,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxWidth().testTag("archive_tabs").drawBehind {
                    drawLine(outline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f))
                },
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                ArchiveTabLabel(
                    text = stringResource(R.string.archived_tab_channels, state.channels.size),
                    selected = state.selectedTab == ArchiveTab.Channels,
                    onClick = { onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Channels)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                ArchiveTabLabel(
                    text = stringResource(R.string.archived_tab_discussions, state.discussions.size),
                    selected = state.selectedTab == ArchiveTab.Discussions,
                    onClick = { onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Discussions)) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(
                            if (state.selectedTab == ArchiveTab.Channels) {
                                "archive_selected_indicator"
                            } else {
                                "archive_unselected_indicator"
                            },
                        ).background(
                            if (state.selectedTab == ArchiveTab.Channels) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(
                            if (state.selectedTab == ArchiveTab.Discussions) {
                                "archive_selected_indicator"
                            } else {
                                "archive_unselected_indicator"
                            },
                        ).background(
                            if (state.selectedTab ==
                                ArchiveTab.Discussions
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
        }
        val items =
            when (state.selectedTab) {
                ArchiveTab.Channels -> state.channels
                ArchiveTab.Discussions -> state.discussions
            }
        if (items.isEmpty()) {
            val emptyText =
                when (state.selectedTab) {
                    ArchiveTab.Channels -> stringResource(R.string.archived_empty_channels)
                    ArchiveTab.Discussions -> stringResource(R.string.archived_empty_discussions)
                }
            CenteredText(emptyText, Modifier)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            ) {
                items(items = items, key = { it.id }) { conversation ->
                    val displayName = conversation.displayName()
                    ArchiveRow(
                        conversation = conversation,
                        displayName = displayName,
                        onRestore = {
                            onEvent(
                                ArchivedDiscussionsEvent.RestoreRequested(
                                    conversation.id,
                                    displayName,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveTabLabel(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.heightIn(min = 46.dp).semantics { this.selected = selected }.clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenteredText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

@Preview(
    name = "Archived — Discussions tab — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchivedScreenDiscussionsPreview() {
    val channel =
        Conversation(
            id = "seed-channel-archived",
            name = "old-project-experiments",
            cwd = "~/Workspace/old-project",
            currentSessionId = "session-archived-channel",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
    val discussion =
        Conversation(
            id = "seed-discussion-archived",
            name = null,
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-archived",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
    PyrycodeMobileTheme(darkTheme = true) {
        ArchivedDiscussionsScreen(
            state =
                ArchivedDiscussionsUiState.Loaded(
                    channels = listOf(channel),
                    discussions = listOf(discussion),
                    selectedTab = ArchiveTab.Discussions,
                ),
            onEvent = {},
            // The ordinary owner line: a name short enough to draw whole (#715).
            hostName = "studio-mini",
        )
    }
}

@Preview(
    name = "Archived — Channels tab — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchivedScreenChannelsPreview() {
    val channel =
        Conversation(
            id = "seed-channel-archived",
            name = "old-project-experiments",
            cwd = "~/Workspace/old-project",
            currentSessionId = "session-archived-channel",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
    val discussion =
        Conversation(
            id = "seed-discussion-archived",
            name = null,
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-archived",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
    PyrycodeMobileTheme(darkTheme = true) {
        ArchivedDiscussionsScreen(
            state =
                ArchivedDiscussionsUiState.Loaded(
                    channels = listOf(channel),
                    discussions = listOf(discussion),
                    selectedTab = ArchiveTab.Channels,
                ),
            onEvent = {},
            // The overflow case (#715): a host name the owner never has to have typed — display
            // names arrive from a scanned payload — drawn on one line with an ellipsis, and bounded
            // at MAX_WORKSPACE_LABEL_CHARS long before any of it reaches the layout.
            hostName = "workstation-in-the-attic-behind-the-boiler-and-down-the-hall-past-the-window",
        )
    }
}

@Preview(
    name = "Archived — Discussions tab empty — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchivedScreenDiscussionsEmptyPreview() {
    val channel =
        Conversation(
            id = "seed-channel-archived",
            name = "old-project-experiments",
            cwd = "~/Workspace/old-project",
            currentSessionId = "session-archived-channel",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
    PyrycodeMobileTheme(darkTheme = true) {
        ArchivedDiscussionsScreen(
            state =
                ArchivedDiscussionsUiState.Loaded(
                    channels = listOf(channel),
                    discussions = emptyList(),
                    selectedTab = ArchiveTab.Discussions,
                ),
            onEvent = {},
        )
    }
}

@Preview(
    name = "Archived — Channels tab empty — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchivedScreenChannelsEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ArchivedDiscussionsScreen(
            state = ArchivedDiscussionsUiState.Loaded(emptyList(), emptyList(), ArchiveTab.Channels),
            onEvent = {},
            hostName = "studio-mini",
        )
    }
}
