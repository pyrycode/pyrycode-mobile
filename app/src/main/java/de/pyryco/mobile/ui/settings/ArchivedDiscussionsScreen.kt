package de.pyryco.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.conversations.components.ArchiveRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // Single-line, exactly as Figma 18:2 draws it. The owning host rides below the bar
                // rather than on a second title line: M3's small `TopAppBar` is a fixed 64dp
                // container, which a two-line title overruns once the user's font scale grows.
                title = { Text(stringResource(R.string.archived_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(ArchivedDiscussionsEvent.BackTapped) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(modifier = Modifier.padding(inner)) {
            // Which host's archive this is (#715). Figma 18:2 draws one host and titles the bar
            // "Archived" alone; naming the owner is the ticket's own adaptation, rendered in the
            // subordinate treatment the Settings host rows use for the same job. It sits above the
            // tab row rather than inside the bar so that it grows with the font scale instead of
            // being clipped by the bar's fixed height, and outside the `when` below so the header
            // reads the same in Loading, Error and Loaded.
            //
            // Clamped here rather than where the label is resolved, so the bound holds for every
            // caller of this screen: the name comes from a scanned QR payload or locally-entered
            // host metadata. Since #752 `parsePairingPayload` bounds each scanned field at 512 UTF-8
            // bytes and rejects an over-long payload rather than truncating it, but that ceiling
            // defends the route argument and the saved-state `Bundle`, not this line of text — it is
            // four times what fits here, it is counted in bytes rather than characters, and it does
            // not reach a host paired before #752 or a locally-entered name. This clamp stands on
            // top of it. Drawn as plain text, never as a format argument.
            if (hostName.isNotBlank()) {
                Text(
                    text = hostName.take(MAX_WORKSPACE_LABEL_CHARS),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Horizontally flush with the rows below: `ArchiveRow` takes the same 16dp gutter.
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

@Composable
private fun LoadedBody(
    state: ArchivedDiscussionsUiState.Loaded,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == ArchiveTab.Channels,
                onClick = { onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Channels)) },
                text = {
                    Text(
                        stringResource(R.string.archived_tab_channels, state.channels.size),
                    )
                },
            )
            Tab(
                selected = state.selectedTab == ArchiveTab.Discussions,
                onClick = { onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Discussions)) },
                text = {
                    Text(
                        stringResource(R.string.archived_tab_discussions, state.discussions.size),
                    )
                },
            )
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
private fun CenteredText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

@Preview(name = "Archived — Discussions tab — Light", showBackground = true, widthDp = 412)
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
    PyrycodeMobileTheme(darkTheme = false) {
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

@Preview(name = "Archived — Channels tab — Light", showBackground = true, widthDp = 412)
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
    PyrycodeMobileTheme(darkTheme = false) {
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

@Preview(name = "Archived — Discussions tab empty — Light", showBackground = true, widthDp = 412)
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
    PyrycodeMobileTheme(darkTheme = false) {
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
