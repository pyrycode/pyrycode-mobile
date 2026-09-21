package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState

    data class Empty(
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,
    ) : ChannelListUiState

    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,
    ) : ChannelListUiState

    data class Error(
        val message: String,
    ) : ChannelListUiState
}

sealed interface ChannelListNavigation {
    data class ToThread(
        val conversationId: String,
    ) : ChannelListNavigation
}

/** Rows, preview keys and workspace groups are local to [host]; never flatten them across hosts. */
data class HostChannelListEntry(
    val host: HostConversationSnapshot,
    val recentChats: List<Conversation>,
    val chatCount: Int,
    val recentChatLastMessages: Map<String, Message> = emptyMap(),
    /** Every active channel of [host], grouped by exact `cwd` — not the recent slice. */
    val channelGroups: List<HostWorkspaceGroup> = emptyList(),
    /** Every active chat of [host], grouped by exact `cwd` — not the recent slice. */
    val chatGroups: List<HostWorkspaceGroup> = emptyList(),
)

data class HostChannelListState(
    val hosts: List<HostChannelListEntry> = emptyList(),
    val workspacePickerServerId: String? = null,
)

data class HostConversationTarget(
    val serverId: String,
    val conversationId: String,
)

class ChannelListViewModel(
    private val repository: ConversationRepository,
    private val appPreferences: AppPreferences,
    private val hostSource: HostConversationSource,
) : ViewModel() {
    private val pendingWorkspacePicker = MutableStateFlow(false)
    private val pendingHostWorkspacePicker = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val hostState: StateFlow<HostChannelListState> =
        hostSource.snapshots
            .flatMapLatest { hosts ->
                RelayLog.d { "event=host_channel_list_projected count=${hosts.size}" }
                if (hosts.isEmpty()) flowOf(emptyList()) else combine(hosts.map(::observeHostEntry)) { it.toList() }
            }.combine(pendingHostWorkspacePicker) { hosts, target ->
                HostChannelListState(hosts, target)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HostChannelListState())

    private fun observeHostEntry(host: HostConversationSnapshot): Flow<HostChannelListEntry> {
        val recent = host.chats.take(RECENT_DISCUSSIONS_LIMIT)
        // Grouped once per snapshot emission; the preview combine below carries them through its
        // copy, so a preview never recomputes a group.
        val entry =
            HostChannelListEntry(
                host = host,
                recentChats = recent,
                chatCount = host.chats.size,
                channelGroups = groupConversationsByWorkspace(host.serverId, host.channels),
                chatGroups = groupConversationsByWorkspace(host.serverId, host.chats),
            )
        val live = hostSource.repositoryFor(host.serverId)
        if (live == null || recent.isEmpty()) return flowOf(entry)
        return combine(
            recent.map { conversation ->
                flow { emitAll(live.observeLastMessage(conversation.id)) }
                    .onStart { emit(null) }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        RelayLog.d { "event=host_chat_preview_failed" }
                        emit(null)
                    }.map { conversation.id to it }
            },
        ) { previews ->
            entry.copy(recentChatLastMessages = previews.mapNotNull { (id, message) -> message?.let { id to it } }.toMap())
        }
    }

    // Separate from the legacy channel: its collector can only navigate with a bare id.
    private val hostNavigationChannel = Channel<HostConversationTarget>(Channel.BUFFERED)
    val hostNavigationEvents: Flow<HostConversationTarget> = hostNavigationChannel.receiveAsFlow()

    fun onHostRowTapped(target: HostConversationTarget) {
        viewModelScope.launch { hostNavigationChannel.send(target) }
    }

    fun createHostDiscussion(serverId: String) {
        launchGuardedRepoCall {
            val workspace = appPreferences.defaultWorkspace(serverId).first()
            sendHostDiscussion(serverId, workspace)
        }
    }

    fun openHostWorkspacePicker(serverId: String) {
        pendingHostWorkspacePicker.value = serverId
        RelayLog.d { "event=host_workspace_picker_opened" }
    }

    fun pickHostWorkspace(workspace: String) {
        val serverId = pendingHostWorkspacePicker.value ?: return
        pendingHostWorkspacePicker.value = null
        launchGuardedRepoCall { sendHostDiscussion(serverId, workspace) }
    }

    fun dismissHostWorkspacePicker() {
        pendingHostWorkspacePicker.value = null
        RelayLog.d { "event=host_workspace_picker_dismissed" }
    }

    private suspend fun sendHostDiscussion(
        serverId: String,
        workspace: String,
    ) {
        val live = hostSource.repositoryFor(serverId)
        if (live == null) {
            RelayLog.d { "event=host_chat_create_rejected code=unavailable" }
            return
        }
        RelayLog.d { "event=host_chat_create_started" }
        val conversation =
            try {
                live.createDiscussion(workspace)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=host_chat_create_failed" }
                throw error
            }
        hostNavigationChannel.send(HostConversationTarget(serverId, conversation.id))
        RelayLog.d { "event=host_chat_created" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChannelListUiState> =
        run {
            val channelsFlow = repository.observeConversations(ConversationFilter.Channels)
            val discussionsFlow = repository.observeConversations(ConversationFilter.Discussions)
            val recentIdsFlow =
                discussionsFlow
                    .map { it.take(RECENT_DISCUSSIONS_LIMIT).map(Conversation::id) }
                    .distinctUntilChanged()
            val lastMessagesFlow: Flow<Map<String, Message>> =
                recentIdsFlow.flatMapLatest { ids ->
                    if (ids.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        combine(
                            ids.map { id ->
                                repository.observeLastMessage(id).map { msg -> id to msg }
                            },
                        ) { pairs ->
                            pairs
                                .mapNotNull { (id, msg) -> msg?.let { id to it } }
                                .toMap()
                        }
                    }
                }
            combine(
                channelsFlow,
                discussionsFlow,
                lastMessagesFlow,
                pendingWorkspacePicker,
            ) { channels, discussions, lastMessages, pickerVisible ->
                val recent = discussions.take(RECENT_DISCUSSIONS_LIMIT)
                val count = discussions.size
                if (channels.isEmpty()) {
                    ChannelListUiState.Empty(
                        recentDiscussions = recent,
                        recentDiscussionsCount = count,
                        recentDiscussionLastMessages = lastMessages,
                        workspacePickerVisible = pickerVisible,
                    )
                } else {
                    ChannelListUiState.Loaded(
                        channels = channels,
                        recentDiscussions = recent,
                        recentDiscussionsCount = count,
                        recentDiscussionLastMessages = lastMessages,
                        workspacePickerVisible = pickerVisible,
                    )
                }
            }.catch { e ->
                val raw = e.message
                emit(
                    ChannelListUiState.Error(
                        if (raw.isNullOrBlank()) "Failed to load channels." else raw,
                    ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ChannelListUiState.Loading,
            )
        }

    private val navigationChannel = Channel<ChannelListNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ChannelListNavigation> = navigationChannel.receiveAsFlow()

    fun onEvent(event: ChannelListEvent) {
        when (event) {
            ChannelListEvent.CreateDiscussionTapped ->
                launchGuardedRepoCall {
                    val workspace = appPreferences.defaultWorkspace.first()
                    val conversation = repository.createDiscussion(workspace = workspace)
                    navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))
                }
            ChannelListEvent.LongPressFab ->
                pendingWorkspacePicker.value = true
            is ChannelListEvent.WorkspacePicked -> {
                pendingWorkspacePicker.value = false
                launchGuardedRepoCall {
                    val conversation = repository.createDiscussion(workspace = event.workspace)
                    navigationChannel.send(ChannelListNavigation.ToThread(conversation.id))
                }
            }
            ChannelListEvent.WorkspacePickerDismissed ->
                pendingWorkspacePicker.value = false
            is ChannelListEvent.RowTapped,
            ChannelListEvent.SettingsTapped,
            ChannelListEvent.RecentDiscussionsTapped,
            -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val RECENT_DISCUSSIONS_LIMIT = 3
    }
}
