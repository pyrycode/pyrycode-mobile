package de.pyryco.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.conversations.components.ArchiveRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
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
            }
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
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
        val bodyModifier = Modifier.padding(inner)
        when (state) {
            ArchivedDiscussionsUiState.Loading -> CenteredText("Loading…", bodyModifier)
            is ArchivedDiscussionsUiState.Error ->
                CenteredText(
                    "Couldn't load archived discussions: ${state.message}",
                    bodyModifier,
                )
            is ArchivedDiscussionsUiState.Loaded ->
                LoadedBody(
                    state = state,
                    onEvent = onEvent,
                    modifier = bodyModifier,
                )
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
