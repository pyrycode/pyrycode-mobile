package de.pyryco.mobile.ui.conversations.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.ui.conversations.components.ConversationRow
import de.pyryco.mobile.ui.conversations.components.RecentDiscussionsPill
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
    data object RecentDiscussionsTapped : ChannelListEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onEvent(ChannelListEvent.SettingsTapped) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty) {
                FloatingActionButton(
                    onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_new_discussion),
                    )
                }
            }
        },
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        when (state) {
            ChannelListUiState.Loading -> CenteredText("Loading…", bodyModifier)
            is ChannelListUiState.Empty -> Column(bodyModifier.fillMaxSize()) {
                RecentDiscussionsPill(
                    count = state.recentDiscussionsCount,
                    onClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.channel_list_empty))
                }
            }
            is ChannelListUiState.Error -> CenteredText(
                "Couldn't load channels: ${state.message}",
                bodyModifier,
            )
            is ChannelListUiState.Loaded -> Column(bodyModifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items = state.channels, key = { it.id }) { channel ->
                        ConversationRow(
                            conversation = channel,
                            lastMessage = null,
                            onClick = { onEvent(ChannelListEvent.RowTapped(channel.id)) },
                        )
                    }
                }
                RecentDiscussionsPill(
                    count = state.recentDiscussionsCount,
                    onClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
                )
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

private fun previewChannels(now: Instant): List<Conversation> = listOf(
    Conversation(
        id = "channel-1",
        name = "pyrycode-mobile",
        cwd = "~/Workspace/Projects/pyrycode-mobile",
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = now - 12.minutes,
    ),
    Conversation(
        id = "channel-2",
        name = "pyrycode",
        cwd = "~/Workspace/Projects/pyrycode",
        currentSessionId = "session-2",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = now - 4.hours,
    ),
    Conversation(
        id = "channel-3",
        name = "scratch ideas",
        cwd = DefaultScratchCwd,
        currentSessionId = "session-3",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = now - 3.days,
        isSleeping = true,
    ),
)

@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenLoadedPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state = ChannelListUiState.Loaded(
                channels = previewChannels(Clock.System.now()),
                recentDiscussionsCount = 0,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Loaded + pill — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenLoadedWithPillPreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state = ChannelListUiState.Loaded(
                channels = listOf(previewChannels(now).first()),
                recentDiscussionsCount = 1,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Empty — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state = ChannelListUiState.Empty(recentDiscussionsCount = 0),
            onEvent = {},
        )
    }
}

@Preview(name = "Empty + pill — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenEmptyWithPillPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state = ChannelListUiState.Empty(recentDiscussionsCount = 5),
            onEvent = {},
        )
    }
}
