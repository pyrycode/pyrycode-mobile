package de.pyryco.mobile.ui.conversations.list

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.conversations.components.ConversationRow
import de.pyryco.mobile.ui.conversations.components.DiscussionPreviewRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed interface ChannelListEvent {
    data class RowTapped(
        val conversationId: String,
    ) : ChannelListEvent

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
                navigationIcon = {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pyry_logo),
                            contentDescription = stringResource(R.string.cd_pyrycode_logo),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
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
            is ChannelListUiState.Empty ->
                Column(bodyModifier.fillMaxSize()) {
                    RecentDiscussionsSection(
                        discussions = state.recentDiscussions,
                        totalCount = state.recentDiscussionsCount,
                        onSeeAllClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
                        onRowClick = { onEvent(ChannelListEvent.RowTapped(it)) },
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.channel_list_empty))
                    }
                }
            is ChannelListUiState.Error ->
                CenteredText(
                    "Couldn't load channels: ${state.message}",
                    bodyModifier,
                )
            is ChannelListUiState.Loaded ->
                Column(bodyModifier.fillMaxSize()) {
                    ChannelsSectionHeader()
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(items = state.channels, key = { it.id }) { channel ->
                            ConversationRow(
                                conversation = channel,
                                onClick = { onEvent(ChannelListEvent.RowTapped(channel.id)) },
                            )
                        }
                        item(key = "recent-discussions-section") {
                            RecentDiscussionsSection(
                                discussions = state.recentDiscussions,
                                totalCount = state.recentDiscussionsCount,
                                onSeeAllClick = { onEvent(ChannelListEvent.RecentDiscussionsTapped) },
                                onRowClick = { onEvent(ChannelListEvent.RowTapped(it)) },
                            )
                        }
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

@Composable
private fun ChannelsSectionHeader() {
    Text(
        text = stringResource(R.string.channels_section_header),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecentDiscussionsSection(
    discussions: List<Conversation>,
    totalCount: Int,
    onSeeAllClick: () -> Unit,
    onRowClick: (String) -> Unit,
) {
    if (discussions.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
        )
        Text(
            text = stringResource(R.string.recent_discussions_section_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        discussions.forEach { conversation ->
            DiscussionPreviewRow(
                conversation = conversation,
                onClick = { onRowClick(conversation.id) },
            )
        }
        SeeAllDiscussionsRow(totalCount = totalCount, onClick = onSeeAllClick)
    }
}

@Composable
private fun SeeAllDiscussionsRow(
    totalCount: Int,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.cd_see_all_discussions, totalCount)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.see_all_discussions_label, totalCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun previewChannels(now: Instant): List<Conversation> =
    listOf(
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
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-3",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = now - 3.days,
            isSleeping = true,
        ),
    )

private fun previewDiscussions(now: Instant): List<Conversation> =
    listOf(
        Conversation(
            id = "discussion-1",
            name = "What's the safest way…",
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-d1",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = now - 12.minutes,
        ),
        Conversation(
            id = "discussion-2",
            name = "Help me debug auth flow",
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-d2",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = now - 2.hours,
        ),
        Conversation(
            id = "discussion-3",
            name = "Quick regex for log parsing",
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "session-d3",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = now - 26.hours,
        ),
    )

@Preview(name = "Loaded — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenLoadedPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state =
                ChannelListUiState.Loaded(
                    channels = previewChannels(Clock.System.now()),
                    recentDiscussions = emptyList(),
                    recentDiscussionsCount = 0,
                ),
            onEvent = {},
        )
    }
}

@Preview(name = "Loaded + discussions — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenLoadedWithDiscussionsPreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state =
                ChannelListUiState.Loaded(
                    channels = previewChannels(now),
                    recentDiscussions = previewDiscussions(now),
                    recentDiscussionsCount = 8,
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
            state =
                ChannelListUiState.Empty(
                    recentDiscussions = emptyList(),
                    recentDiscussionsCount = 0,
                ),
            onEvent = {},
        )
    }
}

@Preview(name = "Empty + discussions — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenEmptyWithDiscussionsPreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state =
                ChannelListUiState.Empty(
                    recentDiscussions = previewDiscussions(now),
                    recentDiscussionsCount = 5,
                ),
            onEvent = {},
        )
    }
}

@Preview(
    name = "Loaded — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChannelListScreenLoadedDarkPreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = true) {
        ChannelListScreen(
            state =
                ChannelListUiState.Loaded(
                    channels = previewChannels(now),
                    recentDiscussions = previewDiscussions(now),
                    recentDiscussionsCount = 8,
                ),
            onEvent = {},
        )
    }
}

@Preview(
    name = "Empty — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChannelListScreenEmptyDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ChannelListScreen(
            state =
                ChannelListUiState.Empty(
                    recentDiscussions = emptyList(),
                    recentDiscussionsCount = 0,
                ),
            onEvent = {},
        )
    }
}
