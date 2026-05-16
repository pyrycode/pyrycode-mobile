package de.pyryco.mobile.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.conversations.components.ConversationRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedDiscussionsScreen(
    state: ArchivedDiscussionsUiState,
    onEvent: (ArchivedDiscussionsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archived_discussions_title)) },
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
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        when (state) {
            ArchivedDiscussionsUiState.Loading -> CenteredText("Loading…", bodyModifier)
            ArchivedDiscussionsUiState.Empty ->
                CenteredText(
                    stringResource(R.string.archived_discussions_empty),
                    bodyModifier,
                )
            is ArchivedDiscussionsUiState.Error ->
                CenteredText(
                    "Couldn't load archived discussions: ${state.message}",
                    bodyModifier,
                )
            is ArchivedDiscussionsUiState.Loaded ->
                LazyColumn(modifier = bodyModifier.fillMaxSize()) {
                    items(items = state.discussions, key = { it.id }) { discussion ->
                        ArchivedDiscussionRow(
                            discussion = discussion,
                            onRestore = {
                                onEvent(ArchivedDiscussionsEvent.RestoreRequested(discussion.id))
                            },
                        )
                    }
                }
        }
    }
}

@Composable
private fun ArchivedDiscussionRow(
    discussion: Conversation,
    onRestore: () -> Unit,
    menuInitiallyExpanded: Boolean = false,
) {
    var menuExpanded by remember(discussion.id) { mutableStateOf(menuInitiallyExpanded) }
    Box {
        ConversationRow(
            conversation = discussion,
            onClick = {},
            modifier = Modifier.alpha(0.65f),
            onLongClick = { menuExpanded = true },
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restore_action)) },
                onClick = {
                    menuExpanded = false
                    onRestore()
                },
            )
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

@Preview(name = "Archived — Loaded — Light", showBackground = true, widthDp = 412)
@Composable
private fun ArchivedDiscussionsScreenLoadedPreview() {
    val archived =
        listOf(
            Conversation(
                id = "seed-discussion-archived",
                name = null,
                cwd = DEFAULT_SCRATCH_CWD,
                currentSessionId = "session-archived",
                sessionHistory = emptyList(),
                isPromoted = false,
                lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
                archived = true,
            ),
        )
    PyrycodeMobileTheme(darkTheme = false) {
        ArchivedDiscussionsScreen(
            state = ArchivedDiscussionsUiState.Loaded(archived),
            onEvent = {},
        )
    }
}

@Preview(name = "Archived — Empty — Light", showBackground = true, widthDp = 412)
@Composable
private fun ArchivedDiscussionsScreenEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ArchivedDiscussionsScreen(
            state = ArchivedDiscussionsUiState.Empty,
            onEvent = {},
        )
    }
}

@Preview(name = "Archived — Long-press menu", showBackground = true, widthDp = 412)
@Composable
private fun ArchivedDiscussionsRowMenuPreview() {
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
        ArchivedDiscussionRow(
            discussion = discussion,
            onRestore = {},
            menuInitiallyExpanded = true,
        )
    }
}
