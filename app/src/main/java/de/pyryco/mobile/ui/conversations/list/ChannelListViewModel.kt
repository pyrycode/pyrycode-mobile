package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.conversations.launchGuardedRepoCall
import de.pyryco.mobile.ui.host.HostEditorController
import de.pyryco.mobile.ui.host.HostEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /**
     * The folded nodes — **collapsed**, never expanded, so the empty initial set is "everything open"
     * and a host or workspace that arrives later needs no reconciliation to draw expanded.
     */
    val collapsed: Set<TreeFoldKey> = emptySet(),
    /** The conversation most recently opened from this list, highlighted when the list comes back. */
    val selected: HostConversationTarget? = null,
    /** The host whose Edit host modal is open, or null when none is (#744). */
    val hostEditor: HostEditorState? = null,
)

/** The tree's two tiers. The same host draws a row in each, and the two fold independently. */
enum class ConversationTreeSection {
    Channels,
    Chats,
}

/**
 * One foldable node: a host row when [cwd] is null, one of that host's workspace rows otherwise.
 *
 * The identity is the ([section], [serverId], [cwd]) triple — [HostWorkspaceGroup]'s own key plus the
 * section, because the design draws each host in both sections and folding one must not fold the other.
 * `cwd` is compared exactly, as the projection produced it. No display name is ever part of a key: a
 * rename must fold and unfold nothing.
 */
data class TreeFoldKey(
    val section: ConversationTreeSection,
    val serverId: String,
    val cwd: String? = null,
)

data class HostConversationTarget(
    val serverId: String,
    val conversationId: String,
)

/**
 * The conversation tree's view model.
 *
 * Every action it exposes is host-qualified: the flat `ChannelListUiState` projection, its events and its
 * `ChannelListNavigation` channel went with the floating action button that was their only consumer
 * (#738), and with them the selected-host adapter the button's two paths resolved through. The repository
 * left the constructor at the same time — it was read only by that projection.
 *
 * [pairedServers] is the Edit host modal's read and write (#744) and the only store this view model
 * touches. Since #751 it is handed straight to [HostEditorController], the shared machine Settings
 * drives too; nothing here reads it directly.
 */
class ChannelListViewModel(
    private val appPreferences: AppPreferences,
    private val hostSource: HostConversationSource,
    private val pairedServers: PairedServerCollectionStore,
) : ViewModel() {
    private val pendingHostWorkspacePicker = MutableStateFlow<String?>(null)

    // Fold and selection are the screen's, but they live here so they survive recomposition, LazyColumn
    // recycling, an incoming snapshot and the thread round trip (#731). The editor's machine is held
    // here for the same reason (#744), one instance per owner so no two screens share an open editor.
    private val collapsedKeys = MutableStateFlow<Set<TreeFoldKey>>(emptySet())
    private val lastOpenedTarget = MutableStateFlow<HostConversationTarget?>(null)
    private val hostEditor = HostEditorController(viewModelScope, pairedServers, appPreferences)

    @OptIn(ExperimentalCoroutinesApi::class)
    val hostState: StateFlow<HostChannelListState> =
        combine(
            hostSource.snapshots
                .flatMapLatest { hosts ->
                    RelayLog.d { "event=host_channel_list_projected count=${hosts.size}" }
                    if (hosts.isEmpty()) flowOf(emptyList()) else combine(hosts.map(::observeHostEntry)) { it.toList() }
                },
            pendingHostWorkspacePicker,
            collapsedKeys,
            lastOpenedTarget,
            hostEditor.state,
        ) { hosts, pickerTarget, collapsed, selected, editor ->
            HostChannelListState(hosts, pickerTarget, collapsed, selected, editor)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HostChannelListState())

    /**
     * Folds or unfolds one host or workspace row.
     *
     * The collapsed set is never pruned against an incoming snapshot: a host that momentarily disappears
     * during a reconnect must come back folded exactly as the operator left it.
     */
    fun onFoldToggled(key: TreeFoldKey) {
        // A read-modify-write, so an atomic update rather than a read-then-assign. The node ends up
        // expanded exactly when it was collapsed before.
        val expanded = key in collapsedKeys.getAndUpdate { if (key in it) it - key else it + key }
        RelayLog.d { "event=tree_fold_toggled expanded=$expanded" }
    }

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

    // Carries the row's own host, which is why it outlived the bare-id channel it was separated from.
    private val hostNavigationChannel = Channel<HostConversationTarget>(Channel.BUFFERED)
    val hostNavigationEvents: Flow<HostConversationTarget> = hostNavigationChannel.receiveAsFlow()

    fun onHostRowTapped(target: HostConversationTarget) {
        // Recorded before the send so the row highlights on the tap, not a dispatch later.
        lastOpenedTarget.value = target
        viewModelScope.launch { hostNavigationChannel.send(target) }
    }

    fun createHostDiscussion(serverId: String) {
        launchGuardedRepoCall {
            val workspace = appPreferences.defaultWorkspace(serverId).first()
            sendHostDiscussion(serverId, workspace)
        }
    }

    /**
     * A disconnected host row's plug control (#840): redial the row's own host and no other. The
     * registry refuses while backgrounded or after removal; the fold, selection and rows are untouched.
     */
    fun reconnectHost(serverId: String) {
        RelayLog.d { "event=tree_host_reconnect_tapped" }
        hostSource.retryHost(serverId)
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

    /**
     * The Edit host modal's six transitions, all delegating to the shared machine (#751).
     *
     * Kept as this view model's own methods rather than exposing the controller: the screen's event
     * dispatch names them, and the move is meant to change no behaviour this list already proves.
     */
    fun openHostEditor(serverId: String) = hostEditor.open(serverId)

    fun submitHostName(name: String) = hostEditor.submitName(name)

    fun requestHostUnpair() = hostEditor.requestUnpair()

    fun declineHostUnpair() = hostEditor.declineUnpair()

    fun confirmHostUnpair() = hostEditor.confirmUnpair()

    fun dismissHostEditor() = hostEditor.dismiss()

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
        // Created from this list, so opened from it: the new row takes the highlight (#731).
        lastOpenedTarget.value = HostConversationTarget(serverId, conversation.id)
        hostNavigationChannel.send(HostConversationTarget(serverId, conversation.id))
        RelayLog.d { "event=host_chat_created" }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val RECENT_DISCUSSIONS_LIMIT = 3
    }
}
