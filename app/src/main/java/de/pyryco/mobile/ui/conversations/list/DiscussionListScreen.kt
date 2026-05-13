package de.pyryco.mobile.ui.conversations.list

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.ui.conversations.components.ConversationRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data object BackTapped : DiscussionListEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionListScreen(
    state: DiscussionListUiState,
    onEvent: (DiscussionListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discussion_list_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(DiscussionListEvent.BackTapped) }) {
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
            DiscussionListUiState.Loading -> CenteredText("Loading…", bodyModifier)
            DiscussionListUiState.Empty -> CenteredText(
                stringResource(R.string.discussion_list_empty),
                bodyModifier,
            )
            is DiscussionListUiState.Error -> CenteredText(
                "Couldn't load discussions: ${state.message}",
                bodyModifier,
            )
            is DiscussionListUiState.Loaded -> LazyColumn(modifier = bodyModifier.fillMaxSize()) {
                items(items = state.discussions, key = { it.id }) { discussion ->
                    ConversationRow(
                        conversation = discussion,
                        lastMessage = null,
                        onClick = { onEvent(DiscussionListEvent.RowTapped(discussion.id)) },
                        modifier = Modifier.alpha(0.65f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)
@Composable
private fun DiscussionListScreenLoadedPreview() {
    val now: Instant = Clock.System.now()
    val previewDiscussions = listOf(
        Conversation(
            id = "disc-1",
            name = null,
            cwd = DefaultScratchCwd,
            currentSessionId = "session-1",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = now - 7.minutes,
        ),
        Conversation(
            id = "disc-2",
            name = "ad-hoc kotlin question",
            cwd = DefaultScratchCwd,
            currentSessionId = "session-2",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = now - 2.hours,
            isSleeping = true,
        ),
    )
    PyrycodeMobileTheme(darkTheme = false) {
        DiscussionListScreen(
            state = DiscussionListUiState.Loaded(previewDiscussions),
            onEvent = {},
        )
    }
}

@Preview(name = "Discussion row vs channel row", showBackground = true, widthDp = 412)
@Composable
private fun DiscussionRowVsChannelRowPreview() {
    val now: Instant = Clock.System.now()
    val channel = Conversation(
        id = "channel-1",
        name = "pyrycode-mobile",
        cwd = "~/Workspace/Projects/pyrycode-mobile",
        currentSessionId = "session-c",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = now - 12.minutes,
    )
    val discussion = Conversation(
        id = "disc-1",
        name = null,
        cwd = DefaultScratchCwd,
        currentSessionId = "session-d",
        sessionHistory = emptyList(),
        isPromoted = false,
        lastUsedAt = now - 12.minutes,
    )
    PyrycodeMobileTheme(darkTheme = false) {
        Column {
            ConversationRow(
                conversation = channel,
                lastMessage = null,
                onClick = {},
            )
            ConversationRow(
                conversation = discussion,
                lastMessage = null,
                onClick = {},
                modifier = Modifier.alpha(0.65f),
            )
        }
    }
}
