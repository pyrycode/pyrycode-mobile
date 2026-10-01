package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.host.HostEditorController
import de.pyryco.mobile.ui.host.HostEditorState
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import de.pyryco.mobile.ui.workspace.isWorkspaceLabelTooLong
import de.pyryco.mobile.ui.workspace.workspaceDisplayName
import de.pyryco.mobile.ui.workspace.workspaceLabelFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** [host]'s non-Idle attention states by conversation id (#877); read a row through [attentionFor]. */
    val attention: Map<String, ConversationAttention> = emptyMap(),
) {
    /** The one attention state of [host]'s row [conversationId]: every row has one, Idle by default. */
    fun attentionFor(conversationId: String): ConversationAttention = attention[conversationId] ?: ConversationAttention.Idle
}

data class HostChannelListState(
    val hosts: List<HostChannelListEntry> = emptyList(),
    /** The host whose Add workspace modal is open, or null when none is (#904). */
    val addWorkspace: AddWorkspaceState? = null,
    /** [addWorkspace]'s own host's recent folders, daemon-authored; empty while closed (#904). */
    val addWorkspaceRecent: List<String> = emptyList(),
    /**
     * The folded nodes — **collapsed**, never expanded, so the empty initial set is "everything open"
     * and a host or workspace that arrives later needs no reconciliation to draw expanded.
     */
    val collapsed: Set<TreeFoldKey> = emptySet(),
    /** The conversation most recently opened from this list, highlighted when the list comes back. */
    val selected: HostConversationTarget? = null,
    /** The host whose Edit host modal is open, or null when none is (#744). */
    val hostEditor: HostEditorState? = null,
    /** The chat whose Edit chat modal is open, or null when none is (#827). */
    val chatEditor: ChatEditorState? = null,
    /** The workspace whose Edit workspace modal is open, or null when none is (#905). */
    val workspaceEditor: WorkspaceEditorState? = null,
    /** The host whose Channels-section Create channel modal is open, or null when none is. */
    val createChannel: CreateChannelState? = null,
    /** The host whose Chats-section create request is in flight or has failed. */
    val createChat: CreateChatState? = null,
    /** The channel whose Edit channel modal is open, or null when none is (#667). */
    val channelEditor: ChannelEditorState? = null,
) {
    /**
     * Whether [serverId]'s own session is up, read from the same snapshot its rows are drawn from (#827).
     *
     * Derived rather than stored on a modal's state. Since #1336 it also decides whether the tree draws the
     * host's section plus and row pens, and the view model closes the host's create and edit modals by the
     * same rule. Both legs are compared with `==` rather than an exhaustive `when`, so a relay state added
     * later reads as not connected instead of needing a classification here.
     */
    fun isHostConnected(serverId: String): Boolean = hosts.any { it.host.serverId == serverId && it.host.connectionStatus.isLive() }
}

private fun ConnectionStatus.isLive(): Boolean = relay == RelayLinkStatus.Connected && pyrycode == PyrycodeLinkStatus.Connected

/** [editor]'s own reading, or [ChannelPromptReading.Reading] while the latest one is another channel's. */
private fun channelPromptFor(
    editor: ChannelEditorState,
    reading: Pair<HostConversationTarget, ChannelPromptReading>?,
): ChannelPromptReading =
    reading?.takeIf { it.first == HostConversationTarget(editor.serverId, editor.conversationId) }?.second
        ?: ChannelPromptReading.Reading

/**
 * A daemon-authored name clamped before layout, as `EditChatModal` clamps its own. The clamp must not end on
 * half a surrogate pair, since an untouched field is compared against it.
 */
private fun boundedName(name: String): String =
    name.take(MAX_WORKSPACE_LABEL_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

/**
 * The Edit chat modal's target and flags (#827), shaped like [HostEditorState]: ids and display text only.
 *
 * [initialName] is the chat's own name as its host's snapshot held it at open time — empty for a chat
 * with no name — and is carried unclamped: `EditChatModal` clamps it at its own boundary, surrogate-safe.
 * [saving], [failed] and [archiveFailed] are flags so the failure string resolves on screen and no daemon
 * message can reach the shell's live region. [saving] covers either write in flight — a rename or an
 * archive (#828) — since the modal's one loading flag gates both actions; [failed] is the rename's
 * failure and [archiveFailed] the archive's, and each write clears both as it starts. The typed name is
 * the modal's own buffer, keyed on [conversationId].
 */
data class ChatEditorState(
    val serverId: String,
    val conversationId: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val archiveFailed: Boolean = false,
)

/**
 * The Edit workspace modal's target and flags (#905), shaped like [ChatEditorState].
 *
 * The target is the ([serverId], [cwd]) pair a workspace row is keyed on — never its shown name, which a
 * second workspace can share. [initialName] is the name the row showed at open time, daemon-authored and
 * carried unclamped: `EditWorkspaceModal` clamps it at its own boundary. [confirmingArchive] swaps the
 * modal's content for the archive prompt in place, as `HostEditorState.confirmingUnpair` does. [saving]
 * covers either write in flight; [failed] is the rename's failure and [archiveFailed] the archive's, and
 * both are flags so the string resolves on screen and no daemon message reaches the shell's live region.
 */
data class WorkspaceEditorState(
    val serverId: String,
    val cwd: String,
    val initialName: String,
    val confirmingArchive: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val archiveFailed: Boolean = false,
)

/**
 * The Add workspace modal's target and flags (#904), shaped like [ChatEditorState].
 *
 * [selected] is the folder OK will start a chat in: a recent folder the operator picked, or one just
 * created on [serverId]. [busy] covers either write in flight — a folder creation or a chat start —
 * since the modal's one loading flag gates both; [createFailed] and [startFailed] are flags so the
 * failure string resolves on screen and no daemon message can reach the shell's live region.
 */
data class AddWorkspaceState(
    val serverId: String,
    val selected: String? = null,
    val busy: Boolean = false,
    val createFailed: Boolean = false,
    val startFailed: Boolean = false,
)

/**
 * The Create channel modal's target and flags (#958), shaped like [AddWorkspaceState].
 *
 * The target is [serverId]; null [cwd] asks the daemon to use its default folder. The optional `cwd`
 * remains part of the modal identity for callers that supply a folder. [createdConversationId] is set once the daemon confirmed the create, so a retry after a failed
 * prompt write addresses that conversation and never creates a second channel. [saving] covers either
 * write in flight; [createFailed] and [promptFailed] are flags so the failure string resolves on screen and
 * no daemon message can reach the shell's live region. The typed name and prompt are the modal's own
 * buffers and never live here.
 */
data class CreateChannelState(
    val serverId: String,
    val cwd: String? = null,
    val saving: Boolean = false,
    val createdConversationId: String? = null,
    val createFailed: Boolean = false,
    val promptFailed: Boolean = false,
)

/** State for a direct Chats-section create request. */
data class CreateChatState(
    val serverId: String,
    val requestId: Long,
    val saving: Boolean = true,
    val failed: Boolean = false,
)

/**
 * The Edit channel modal's stored-prompt reading (#667).
 *
 * [Unavailable] is a failed read — or a reply over [SystemPromptLimit.MAX_BYTES], which is never put in the
 * field — and is not the same statement as "no prompt stored": that one is a [Read] whose prompt is `null`.
 * Neither [Reading] nor [Unavailable] can lead to a prompt write.
 */
sealed interface ChannelPromptReading {
    data object Reading : ChannelPromptReading

    data object Unavailable : ChannelPromptReading

    /**
     * [prompt] keeps `null` (none stored) and `""` apart and is held verbatim: it is untrusted
     * operator-authored text. [toString] is overridden because the generated one would print it, and it
     * may hold a pasted credential.
     */
    data class Read(
        val prompt: String?,
        val status: SessionPromptStatus,
    ) : ChannelPromptReading {
        override fun toString(): String = "Read(prompt=${if (prompt == null) "absent" else "<redacted>"}, status=$status)"
    }
}

/**
 * The Edit channel modal's target and flags (#667), shaped like [ChatEditorState].
 *
 * [savedName] is the channel's name as its own host's snapshot held it at open time, clamped for layout,
 * and then the name the daemon confirmed: OK renames only when the trimmed field differs from it, so a
 * retry after a failed prompt write never renames twice. [prompt] is published by the view model from the
 * target's own reading. [saving] covers any write in flight; [failed] is a rename or prompt write's
 * failure and [archiveFailed] the archive's, both flags so the string resolves on screen and no daemon
 * message reaches the shell's live region. The typed name and prompt are the modal's own buffers.
 * [savedMuted] follows [savedName]'s pattern for the host's mute flag (#1021): the snapshot's value at open
 * time, then the value the daemon confirmed, so a retry never repeats a confirmed mute write.
 */
data class ChannelEditorState(
    val serverId: String,
    val conversationId: String,
    val savedName: String,
    val prompt: ChannelPromptReading = ChannelPromptReading.Reading,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val archiveFailed: Boolean = false,
    val savedMuted: Boolean = false,
)

/** The tree's foldable node kinds. */
enum class ConversationTreeSection {
    Host,
    Channels,
    Chats,
}

/**
 * One foldable node identified by its host and kind. Display names and folders never affect folding.
 */
data class TreeFoldKey(
    val section: ConversationTreeSection,
    val serverId: String,
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
    private val addWorkspace = MutableStateFlow<AddWorkspaceState?>(null)

    /**
     * The open Add workspace modal's host's recent folders, tagged with the host they were fetched for.
     *
     * Keyed on the server id alone, so a selection or a flag change never re-fetches but a close and
     * reopen does. The repository is re-resolved on every snapshot, so a host that connects after the
     * modal opened still fills it; a disconnect keeps the last list. The tag is what [hostState] checks
     * before publishing, so no emission can pair one host's modal with another host's folders.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val addWorkspaceRecent: Flow<Pair<String?, List<String>>> =
        addWorkspace
            .map { it?.serverId }
            .distinctUntilChanged()
            .flatMapLatest { serverId ->
                if (serverId == null) {
                    flowOf(null to emptyList())
                } else {
                    hostSource.snapshots
                        .map { hostSource.repositoryFor(serverId) }
                        .distinctUntilChanged()
                        .filterNotNull()
                        .flatMapLatest { it.recentWorkspaces() }
                        .map { it.take(MAX_ADD_WORKSPACE_RECENTS) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            RelayLog.d { "event=add_workspace_recents_failed" }
                            emit(emptyList())
                        }.onStart { emit(emptyList()) }
                        .map { serverId to it }
                }
            }

    // Fold and selection are the screen's, but they live here so they survive recomposition, LazyColumn
    // recycling, an incoming snapshot and the thread round trip (#731). The editor's machine is held
    // here for the same reason (#744), one instance per owner so no two screens share an open editor.
    private val collapsedKeys = MutableStateFlow<Set<TreeFoldKey>>(emptySet())
    private val lastOpenedTarget = MutableStateFlow<HostConversationTarget?>(null)
    private val hostEditor = HostEditorController(viewModelScope, pairedServers, appPreferences)
    private val chatEditor = MutableStateFlow<ChatEditorState?>(null)
    private val workspaceEditor = MutableStateFlow<WorkspaceEditorState?>(null)
    private val createChannel = MutableStateFlow<CreateChannelState?>(null)
    private val createChat = MutableStateFlow<CreateChatState?>(null)
    private var nextCreateChatRequestId = 0L
    private val pendingNewChatModels = mutableSetOf<HostConversationTarget>()
    private val tappedPendingNewChats = mutableSetOf<HostConversationTarget>()

    // #667: the editor's own prompt stays at its default here; the reading lives apart, tagged with the
    // channel it was read for, so a read landing mid-write never breaks that write's `compareAndSet` and no
    // emission can pair one channel's modal with another channel's prompt.
    private val channelEditor = MutableStateFlow<ChannelEditorState?>(null)
    private val channelPrompt = MutableStateFlow<Pair<HostConversationTarget, ChannelPromptReading>?>(null)
    private var channelPromptRead: Job? = null

    private val publishedChannelEditor: Flow<ChannelEditorState?> =
        combine(channelEditor, channelPrompt) { editor, reading ->
            editor?.copy(prompt = channelPromptFor(editor, reading))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val hostState: StateFlow<HostChannelListState> =
        combine(
            // #877: attention joins the projected entries here, outside the per-snapshot projection, so a
            // turn starting or ending never re-subscribes a host's previews.
            combine(
                hostSource.snapshots
                    .flatMapLatest { hosts ->
                        RelayLog.d { "event=host_channel_list_projected count=${hosts.size}" }
                        if (hosts.isEmpty()) flowOf(emptyList()) else combine(hosts.map(::observeHostEntry)) { it.toList() }
                    },
                hostSource.attention,
            ) { entries, attention -> entries.map { it.copy(attention = attention[it.host.serverId].orEmpty()) } },
            // The list's creation modals, grouped because five flows is the typed `combine`'s limit.
            combine(addWorkspace, addWorkspaceRecent, combine(createChannel, createChat, ::Pair), ::Triple),
            collapsedKeys,
            lastOpenedTarget,
            // Grouped first: five flows is the typed `combine`'s limit.
            combine(combine(hostEditor.state, chatEditor, ::Pair), workspaceEditor, publishedChannelEditor, ::Triple),
        ) { hosts, (adding, recent, creatingModals), collapsed, selected, (editorAndChat, workspace, channel) ->
            val (editor, chat) = editorAndChat
            val (creating, creatingChat) = creatingModals
            HostChannelListState(
                hosts = hosts,
                addWorkspace = adding,
                // Only the open modal's own host's list, never one fetched for a previous target.
                addWorkspaceRecent = if (adding != null && recent.first == adding.serverId) recent.second else emptyList(),
                collapsed = collapsed,
                selected = selected,
                hostEditor = editor,
                chatEditor = chat,
                workspaceEditor = workspace,
                createChannel = creating,
                createChat = creatingChat,
                channelEditor = channel,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HostChannelListState())

    init {
        // #1336, desktop's rule: a host that stops being connected closes its create and edit modals and
        // clears its failed Chats create. A write already in flight may finish; its terminal
        // `compareAndSet` against the cleared state is a no-op, so nothing is resurrected.
        viewModelScope.launch {
            hostSource.snapshots.collect { hosts ->
                val live = hosts.filter { it.connectionStatus.isLive() }.mapTo(HashSet()) { it.serverId }
                createChannel.clearUnless("create_channel") { it.serverId in live }
                createChat.clearUnless("create_chat") { it.serverId in live }
                chatEditor.clearUnless("chat_editor") { it.serverId in live }
                if (channelEditor.clearUnless("channel_editor") { it.serverId in live }) channelPromptRead?.cancel()
            }
        }
    }

    /** Atomically sets this modal's state to null unless [keep] holds; true when it closed one. */
    private fun <T : Any> MutableStateFlow<T?>.clearUnless(
        name: String,
        keep: (T) -> Boolean,
    ): Boolean {
        val before = getAndUpdate { state -> state?.takeIf(keep) }
        val cleared = before != null && !keep(before)
        if (cleared) RelayLog.d { "event=${name}_closed code=disconnected" }
        return cleared
    }

    /** [serverId]'s snapshot is connected, by the same rule as [HostChannelListState.isHostConnected]. */
    private fun isHostLive(serverId: String): Boolean =
        hostSource.snapshots.value.any { it.serverId == serverId && it.connectionStatus.isLive() }

    /**
     * Folds or unfolds one host or fixed section row.
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
        if (target in pendingNewChatModels) {
            tappedPendingNewChats += target
            RelayLog.d { "event=new_chat_open_deferred" }
            return
        }
        // Recorded before the send so the row highlights on the tap, not a dispatch later.
        lastOpenedTarget.value = target
        // The list's open path clears that host's unread and failed marks at the tap (#877); the thread's
        // viewing handle keeps it read while it is open.
        hostSource.markOpened(target.serverId, target.conversationId)
        viewModelScope.launch { hostNavigationChannel.send(target) }
    }

    private suspend fun prepareNewChatModel(
        repository: ConversationRepository,
        conversation: Conversation,
        serverId: String,
    ): HostConversationTarget {
        val target = HostConversationTarget(serverId, conversation.id)
        pendingNewChatModels += target
        var settled = false
        try {
            applyRememberedModel(repository, conversation)
            settled = true
        } finally {
            pendingNewChatModels -= target
            if (!settled) tappedPendingNewChats -= target
        }
        return target
    }

    /** A newly created chat alone may inherit the phone's last acknowledged model choice. */
    private suspend fun applyRememberedModel(
        repository: ConversationRepository,
        conversation: Conversation,
    ) {
        val remembered =
            try {
                appPreferences.rememberedModel.first()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=new_chat_model outcome=preference_unavailable" }
                return
            }
        if (remembered.isNullOrEmpty() || remembered == "default") return
        val menu =
            try {
                withTimeoutOrNull(5_000) {
                    repository.observeModelMenu(conversation.id).filterNotNull().first()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        if (menu == null) {
            RelayLog.d { "event=new_chat_model outcome=menu_unavailable" }
            return
        }
        val offered =
            menu.rows.any { row ->
                row.agent == conversation.agent && row.value == remembered && "value" !in row.truncatedFields.orEmpty()
            }
        if (!offered) {
            RelayLog.d { "event=new_chat_model outcome=not_applicable" }
            return
        }
        // A relay create reply contains no session id. Read the new conversation's
        // authoritative settings before the thread opens so the first send uses this model.
        val sessionId =
            try {
                withTimeoutOrNull(5_000) {
                    repository
                        .observeSessionSettings(conversation.id)
                        .filterNotNull()
                        .first()
                        .sessionId
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        if (sessionId.isNullOrEmpty()) {
            RelayLog.d { "event=new_chat_model outcome=settings_unavailable" }
            return
        }
        try {
            repository.setSessionSettings(sessionId, model = remembered)
            RelayLog.d { "event=new_chat_model outcome=applied" }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RelayLog.d { "event=new_chat_model outcome=write_failed" }
        }
    }

    /** Create on the clicked host immediately, using its daemon default folder. */
    fun createChat(serverId: String) {
        val host = hostSource.snapshots.value.firstOrNull { it.serverId == serverId }
        if (host == null) {
            RelayLog.d { "event=create_chat_rejected code=unknown_host" }
            return
        }
        if (!isHostLive(serverId)) {
            RelayLog.d { "event=create_chat_rejected code=disconnected" }
            return
        }
        if (createChat.value?.saving == true) return
        val state = CreateChatState(serverId, requestId = ++nextCreateChatRequestId)
        createChat.value = state
        val live = hostSource.repositoryFor(serverId)
        if (live == null) {
            createChat.compareAndSet(state, state.copy(saving = false, failed = true))
            RelayLog.d { "event=create_chat_failed code=unavailable" }
            return
        }
        viewModelScope.launch {
            val conversation =
                try {
                    RelayLog.d { "event=create_chat_started" }
                    live.createDiscussion(null)
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        createChat.compareAndSet(state, null)
                        throw error
                    }
                    createChat.compareAndSet(state, state.copy(saving = false, failed = true))
                    RelayLog.d { "event=create_chat_failed code=request" }
                    return@launch
                }
            val target = prepareNewChatModel(live, conversation, serverId)
            createChat.compareAndSet(state, null)
            tappedPendingNewChats -= target
            lastOpenedTarget.value = target
            hostNavigationChannel.send(target)
            RelayLog.d { "event=create_chat_created" }
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

    /**
     * Opens the retained Add workspace modal on an explicitly named host (#904) — never the selected host. An id
     * the list does not hold opens nothing.
     */
    fun openAddWorkspace(serverId: String) {
        if (hostSource.snapshots.value.none { it.serverId == serverId }) {
            RelayLog.d { "event=add_workspace_open_rejected code=unknown_host" }
            return
        }
        addWorkspace.value = AddWorkspaceState(serverId)
        RelayLog.d { "event=add_workspace_opened" }
    }

    /**
     * Selects the folder OK will start a chat in, clearing a shown failure. Refused while a write is in
     * flight: the write's terminal `compareAndSet` expects the state it published.
     */
    fun selectAddWorkspaceFolder(path: String) {
        val state = addWorkspace.value ?: return
        if (state.busy) return
        addWorkspace.value = state.copy(selected = path, createFailed = false, startFailed = false)
    }

    /**
     * Creates a folder on the modal's own host and selects it; it starts nothing (#904).
     *
     * The repository is resolved from the modal's `serverId` at the press, as [submitChatName] does, and
     * both terminal transitions are `compareAndSet` against the state published before the write, so a
     * result landing after a dismissal or a reopen cannot touch the modal. A failure keeps the selection
     * and publishes a flag, never the exception's message.
     */
    fun createAddWorkspaceFolder(name: String) {
        val state = addWorkspace.value ?: return
        if (state.busy) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val live = hostSource.repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=add_workspace_create_rejected code=unavailable" }
            addWorkspace.value = state.copy(createFailed = true, startFailed = false)
            return
        }
        val pending = state.copy(busy = true, createFailed = false, startFailed = false)
        addWorkspace.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=add_workspace_create_started" }
            val path =
                try {
                    live.createWorkspaceFolder(trimmed)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Never log the name, the path or the server's message; the UI gets one static string.
                    RelayLog.d { "event=add_workspace_create_failed" }
                    addWorkspace.compareAndSet(pending, pending.copy(busy = false, createFailed = true))
                    return@launch
                }
            addWorkspace.compareAndSet(pending, pending.copy(busy = false, selected = path))
            RelayLog.d { "event=add_workspace_created" }
        }
    }

    /**
     * OK: starts an unpromoted chat in exactly the selected folder on the modal's own host, then closes
     * the modal and opens the chat's thread (#904).
     *
     * Resolution, the `busy` guard and the terminal transitions follow [createAddWorkspaceFolder]. The
     * thread opens only when the modal is still the one that pressed OK: a chat started before a Cancel
     * stays in the list, unopened.
     */
    fun submitAddWorkspace() {
        val state = addWorkspace.value ?: return
        if (state.busy) return
        val workspace = state.selected ?: return
        val live = hostSource.repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=add_workspace_start_rejected code=unavailable" }
            addWorkspace.value = state.copy(createFailed = false, startFailed = true)
            return
        }
        val pending = state.copy(busy = true, createFailed = false, startFailed = false)
        addWorkspace.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=add_workspace_start_started" }
            val conversation =
                try {
                    live.createDiscussion(workspace)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=add_workspace_start_failed" }
                    addWorkspace.compareAndSet(pending, pending.copy(busy = false, startFailed = true))
                    return@launch
                }
            val target = prepareNewChatModel(live, conversation, state.serverId)
            val tapped = tappedPendingNewChats.remove(target)
            if (!addWorkspace.compareAndSet(pending, null) && !tapped) {
                RelayLog.d { "event=add_workspace_started code=dismissed" }
                return@launch
            }
            lastOpenedTarget.value = target
            hostNavigationChannel.send(target)
            RelayLog.d { "event=add_workspace_started" }
        }
    }

    /** Cancel, Close and Back: close the Add workspace modal and send nothing. */
    fun dismissAddWorkspace() {
        addWorkspace.value = null
        RelayLog.d { "event=add_workspace_dismissed" }
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

    /** Fires after an unpair here leaves no saved host (#1323). */
    val lastHostUnpaired: Flow<Unit> = hostEditor.lastHostUnpaired

    fun dismissHostEditor() = hostEditor.dismiss()

    /**
     * Opens the Edit chat modal on a Chats row's own host and conversation (#827).
     *
     * The name is read from that host's own snapshot, never from row text or another host's list: the
     * conversation id is host-local, so a second host may hold a different chat under the same id. Only a
     * chat qualifies — a channel's editor is [openChannelEditor]. The selection and navigation are left alone: the pen
     * edits the row, it does not open it.
     */
    fun openChatEditor(target: HostConversationTarget) {
        val chat =
            hostSource.snapshots.value
                .firstOrNull { it.serverId == target.serverId }
                ?.chats
                ?.firstOrNull { it.id == target.conversationId }
        if (chat == null) {
            RelayLog.d { "event=chat_editor_open_rejected code=unknown_chat" }
            return
        }
        if (!isHostLive(target.serverId)) {
            RelayLog.d { "event=chat_editor_open_rejected code=disconnected" }
            return
        }
        chatEditor.value =
            ChatEditorState(
                serverId = target.serverId,
                conversationId = target.conversationId,
                initialName = chat.name?.takeIf { it.isNotBlank() }.orEmpty(),
            )
        RelayLog.d { "event=chat_editor_opened" }
    }

    /**
     * Renames the open editor's chat on the editor's own host, then closes the modal.
     *
     * The repository is resolved from the editor's `serverId` at the press — never from the selected host —
     * and a host with no open session fails the press rather than queueing it. The trim is this method's own
     * so the contract holds for any caller; a blank name is ignored, as the daemon would reject it.
     *
     * Both terminal transitions are `compareAndSet` against the state published before the write, so a
     * rename finishing after a dismissal cannot resurrect a closed modal or overwrite a newer one. A
     * failure publishes a flag, never the exception's message.
     */
    fun submitChatName(name: String) {
        val target = chatEditor.value ?: return
        if (target.saving) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!isHostLive(target.serverId)) {
            RelayLog.d { "event=chat_rename_rejected code=disconnected" }
            chatEditor.compareAndSet(target, null)
            return
        }
        val live = hostSource.repositoryFor(target.serverId)
        if (live == null) {
            RelayLog.d { "event=chat_rename_rejected code=unavailable" }
            chatEditor.value = target.copy(failed = true, archiveFailed = false)
            return
        }
        val pending = target.copy(saving = true, failed = false, archiveFailed = false)
        chatEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=chat_rename_started" }
            try {
                live.rename(target.conversationId, trimmed)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the name, the ids or the server's message; the UI gets one static string.
                RelayLog.d { "event=chat_rename_failed" }
                chatEditor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                return@launch
            }
            // The row picks the new name up from the host's own conversation stream; nothing is patched here.
            chatEditor.compareAndSet(pending, null)
            RelayLog.d { "event=chat_renamed" }
        }
    }

    /**
     * Archives the open editor's chat on the editor's own host, then closes the modal (#828).
     *
     * Whatever the name field holds is irrelevant, so this takes no name, and it asks no confirmation:
     * the host's Archive screen restores the chat. The host and the terminal transitions follow
     * [submitChatName] exactly — the repository is resolved from the editor's `serverId` at the press,
     * and a result landing after a dismissal or a reopen cannot touch the modal. The chat leaves the
     * list through the host's own conversation stream; nothing is patched here.
     */
    fun archiveChat() {
        val target = chatEditor.value ?: return
        if (target.saving) return
        if (!isHostLive(target.serverId)) {
            RelayLog.d { "event=chat_archive_rejected code=disconnected" }
            chatEditor.compareAndSet(target, null)
            return
        }
        val live = hostSource.repositoryFor(target.serverId)
        if (live == null) {
            RelayLog.d { "event=chat_archive_rejected code=unavailable" }
            chatEditor.value = target.copy(failed = false, archiveFailed = true)
            return
        }
        val pending = target.copy(saving = true, failed = false, archiveFailed = false)
        chatEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=chat_archive_started" }
            try {
                live.archive(target.conversationId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the ids or the server's message; the UI gets one static string.
                RelayLog.d { "event=chat_archive_failed" }
                chatEditor.compareAndSet(pending, pending.copy(saving = false, archiveFailed = true))
                return@launch
            }
            chatEditor.compareAndSet(pending, null)
            RelayLog.d { "event=chat_archived" }
        }
    }

    /** Cancel, Close and Back: close the Edit chat modal and send nothing. */
    fun dismissChatEditor() {
        chatEditor.value = null
        RelayLog.d { "event=chat_editor_dismissed" }
    }

    /**
     * Opens the Edit workspace modal on a workspace row's own host and exact `cwd` (#905), in either section.
     *
     * The shown name is read from that host's own snapshot — the first channel or chat at [cwd], through
     * the same display rule the row used — never from row text or another host. A host the list does not
     * hold, or a path that host holds no active row at, opens nothing. Selection and navigation are left
     * alone: the pencil edits the row, it does not open anything.
     */
    fun openWorkspaceEditor(
        serverId: String,
        cwd: String,
    ) {
        val row =
            hostSource.snapshots.value
                .firstOrNull { it.serverId == serverId }
                ?.let { host -> (host.channels + host.chats).firstOrNull { it.cwd == cwd } }
        if (row == null) {
            RelayLog.d { "event=workspace_editor_open_rejected code=unknown_workspace" }
            return
        }
        workspaceEditor.value = WorkspaceEditorState(serverId, cwd, workspaceDisplayName(cwd, row.workspaceLabel))
        RelayLog.d { "event=workspace_editor_opened" }
    }

    /**
     * Sets or clears the open editor's workspace label on the editor's own host, then closes the modal.
     *
     * The label rule is applied here, so it holds for any caller: [workspaceLabelFor] against the folder's
     * own name, and a label the daemon would refuse for its size is not sent at all. The repository is
     * resolved from the editor's `serverId` at the press and the terminal transitions are `compareAndSet`,
     * exactly as [submitChatName]; a failure publishes a flag, never the exception's message.
     */
    fun submitWorkspaceName(name: String) {
        val target = workspaceEditor.value ?: return
        if (target.saving || target.confirmingArchive) return
        val label = workspaceLabelFor(name, folderName = workspaceDisplayName(target.cwd, label = null))
        if (isWorkspaceLabelTooLong(label)) {
            RelayLog.d { "event=workspace_rename_rejected code=too_long" }
            return
        }
        val live = hostSource.repositoryFor(target.serverId)
        if (live == null) {
            RelayLog.d { "event=workspace_rename_rejected code=unavailable" }
            workspaceEditor.value = target.copy(failed = true, archiveFailed = false)
            return
        }
        val pending = target.copy(saving = true, failed = false, archiveFailed = false)
        workspaceEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=workspace_rename_started cleared=${label == null}" }
            try {
                live.renameWorkspace(target.cwd, label)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the label, the path, the id or the server's message; the UI gets one static string.
                RelayLog.d { "event=workspace_rename_failed" }
                workspaceEditor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                return@launch
            }
            // The rows pick the label up from the host's own conversation stream; nothing is patched here.
            workspaceEditor.compareAndSet(pending, null)
            RelayLog.d { "event=workspace_renamed" }
        }
    }

    /** The modal's Archive workspace: swap to the confirmation in place, sending nothing. */
    fun requestWorkspaceArchive() {
        val target = workspaceEditor.value ?: return
        if (target.saving) return
        workspaceEditor.value = target.copy(confirmingArchive = true, failed = false, archiveFailed = false)
        RelayLog.d { "event=workspace_archive_requested" }
    }

    /** Backs out of the confirmation to the editor without writing — never closing it. */
    fun declineWorkspaceArchive() {
        val target = workspaceEditor.value ?: return
        // A Cancel tap mid-archive must not defeat that archive's own close.
        if (target.saving) return
        workspaceEditor.value = target.copy(confirmingArchive = false, archiveFailed = false)
        RelayLog.d { "event=workspace_archive_declined" }
    }

    /**
     * Archives every active row at the editor's exact `cwd` on the editor's own host, then closes (#905).
     *
     * Only from the confirmation. Host resolution and the terminal transitions follow [submitWorkspaceName].
     * A failure keeps the confirmation up with its flag, so OK retries; `archiveWorkspace` leaves the rows
     * it did confirm archived, so a retry archives only those still active.
     */
    fun confirmWorkspaceArchive() {
        val target = workspaceEditor.value ?: return
        if (target.saving || !target.confirmingArchive) return
        val live = hostSource.repositoryFor(target.serverId)
        if (live == null) {
            RelayLog.d { "event=workspace_archive_rejected code=unavailable" }
            workspaceEditor.value = target.copy(failed = false, archiveFailed = true)
            return
        }
        val pending = target.copy(saving = true, failed = false, archiveFailed = false)
        workspaceEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=workspace_archive_started" }
            try {
                live.archiveWorkspace(target.cwd)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=workspace_archive_failed" }
                workspaceEditor.compareAndSet(pending, pending.copy(saving = false, archiveFailed = true))
                return@launch
            }
            workspaceEditor.compareAndSet(pending, null)
            RelayLog.d { "event=workspace_archived" }
        }
    }

    /** Cancel, Close and Back from the editor: close the Edit workspace modal and send nothing. */
    fun dismissWorkspaceEditor() {
        workspaceEditor.value = null
        RelayLog.d { "event=workspace_editor_dismissed" }
    }

    /** Opens Create channel for this host even when its Channels section is empty. */
    fun openCreateChannel(serverId: String) {
        if (hostSource.snapshots.value.none { it.serverId == serverId }) {
            RelayLog.d { "event=create_channel_open_rejected code=unknown_host" }
            return
        }
        if (!isHostLive(serverId)) {
            RelayLog.d { "event=create_channel_open_rejected code=disconnected" }
            return
        }
        createChannel.value = CreateChannelState(serverId)
        RelayLog.d { "event=create_channel_opened" }
    }

    /**
     * OK: creates a channel named [name] in the daemon host's default folder, writes a
     * non-blank [systemPrompt] verbatim to the **created** conversation, then closes the modal and opens
     * the channel (#958).
     *
     * One create per channel: once the daemon confirmed it, the created id is published and a retry after
     * a failed prompt write sends only the prompt. The repository is resolved from the modal's `serverId`
     * at the press, as [submitAddWorkspace] does, and every terminal transition is a `compareAndSet`
     * against the state published before the write, so a result landing after a dismissal or a reopen
     * cannot touch the modal or open anything. The chain itself is not cancelled by a dismissal: the
     * operator already pressed OK. The trim and the byte limit are applied here, so they hold for any caller.
     */
    fun submitCreateChannel(
        name: String,
        systemPrompt: String,
    ) {
        val state = createChannel.value ?: return
        if (state.saving) return
        val createdId = state.createdConversationId
        val trimmed = name.trim()
        if ((createdId == null && trimmed.isEmpty()) || !SystemPromptLimit.fits(systemPrompt)) {
            RelayLog.d { "event=create_channel_rejected code=invalid" }
            return
        }
        if (!isHostLive(state.serverId)) {
            RelayLog.d { "event=create_channel_rejected code=disconnected" }
            createChannel.compareAndSet(state, null)
            return
        }
        val live = hostSource.repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=create_channel_rejected code=unavailable" }
            createChannel.value =
                if (createdId == null) state.copy(createFailed = true) else state.copy(promptFailed = true)
            return
        }
        val pending = state.copy(saving = true, createFailed = false, promptFailed = false)
        createChannel.value = pending
        viewModelScope.launch {
            val conversationId =
                createdId ?: try {
                    RelayLog.d { "event=create_channel_started" }
                    live.createChannel(trimmed, state.cwd).id
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Never log the name, the path or the server's message; the UI gets one static string.
                    RelayLog.d { "event=create_channel_failed" }
                    createChannel.compareAndSet(pending, pending.copy(saving = false, createFailed = true))
                    return@launch
                }
            var current = pending
            if (systemPrompt.isNotBlank()) {
                val created = pending.copy(createdConversationId = conversationId)
                // Inert after a dismissal; the prompt is still written, since OK was pressed.
                createChannel.compareAndSet(pending, created)
                current = created
                try {
                    live.setSystemPrompt(conversationId, systemPrompt)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=create_channel_prompt_failed" }
                    createChannel.compareAndSet(created, created.copy(saving = false, promptFailed = true))
                    return@launch
                }
            }
            if (!createChannel.compareAndSet(current, null)) {
                RelayLog.d { "event=create_channel_created code=dismissed" }
                return@launch
            }
            val target = HostConversationTarget(state.serverId, conversationId)
            lastOpenedTarget.value = target
            hostNavigationChannel.send(target)
            RelayLog.d { "event=create_channel_created" }
        }
    }

    /** Cancel, Close and Back: close the Create channel modal and send nothing. */
    fun dismissCreateChannel() {
        createChannel.value = null
        RelayLog.d { "event=create_channel_dismissed" }
    }

    /**
     * Opens the Edit channel modal on a Channels row's own host and conversation (#667), then reads that
     * channel's stored prompt once.
     *
     * The name is read from that host's own snapshot, never from row text or another host: conversation ids
     * are host-local. Only a channel qualifies — a chat's editor is [openChatEditor]. The read waits for the
     * host's repository, so a modal opened while its host is down fills once it connects; it calls nothing
     * that starts or resets a session. Selection and navigation are left alone.
     */
    fun openChannelEditor(target: HostConversationTarget) {
        val channel =
            hostSource.snapshots.value
                .firstOrNull { it.serverId == target.serverId }
                ?.channels
                ?.firstOrNull { it.id == target.conversationId }
        if (channel == null) {
            RelayLog.d { "event=channel_editor_open_rejected code=unknown_channel" }
            return
        }
        if (!isHostLive(target.serverId)) {
            RelayLog.d { "event=channel_editor_open_rejected code=disconnected" }
            return
        }
        channelPromptRead?.cancel()
        channelPrompt.value = target to ChannelPromptReading.Reading
        channelEditor.value =
            ChannelEditorState(
                serverId = target.serverId,
                conversationId = target.conversationId,
                savedName = boundedName(channel.name?.takeIf { it.isNotBlank() }.orEmpty()),
                savedMuted = channel.muted,
            )
        RelayLog.d { "event=channel_editor_opened" }
        channelPromptRead =
            viewModelScope.launch {
                val live =
                    hostSource.snapshots
                        .map { hostSource.repositoryFor(target.serverId) }
                        .filterNotNull()
                        .first()
                channelPrompt.value = target to readChannelPrompt(live, target.conversationId)
            }
    }

    /**
     * OK: renames the open editor's channel when the trimmed [name] differs from its saved name, then writes
     * [muted] when it differs from the saved flag (#1021), then writes [systemPrompt] verbatim when it differs
     * from the stored prompt that was read, then closes (#667). [muted] `null` writes no mute.
     *
     * [systemPrompt] is `null` when the modal never showed a stored prompt, and it is ignored unless this
     * editor's reading arrived: a prompt the operator never saw can never be overwritten. An absent stored
     * prompt reads as an empty box, as `SystemPromptEditorState.Loaded.changed` does. A confirmed rename is
     * recorded as the saved name and a confirmed mute as the saved flag; the prompt, whose confirmation is not
     * recorded, goes last. So a retry sends only the writes the host has not confirmed.
     *
     * The repository is resolved from the editor's `serverId` at the press — a reconnect replaces it — and
     * every terminal transition is a `compareAndSet`, as [submitChatName]. The chain is not cancelled by a
     * dismissal: the operator already pressed OK.
     */
    fun submitChannelEdit(
        name: String,
        systemPrompt: String?,
        muted: Boolean? = null,
    ) {
        val state = channelEditor.value ?: return
        if (state.saving) return
        val trimmed = name.trim()
        val read = channelPromptFor(state, channelPrompt.value) as? ChannelPromptReading.Read
        val draft = systemPrompt?.takeIf { read != null }
        if (trimmed.isEmpty() || (draft != null && !SystemPromptLimit.fits(draft))) {
            RelayLog.d { "event=channel_edit_rejected code=invalid" }
            return
        }
        if (!isHostLive(state.serverId)) {
            RelayLog.d { "event=channel_edit_rejected code=disconnected" }
            closeChannelEditor(state)
            return
        }
        val live = hostSource.repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=channel_edit_rejected code=unavailable" }
            channelEditor.value = state.copy(failed = true, archiveFailed = false)
            return
        }
        val renameTo = trimmed.takeIf { it != state.savedName.trim() }
        val muteTo = muted?.takeIf { it != state.savedMuted }
        val promptToWrite = draft?.takeIf { it != read?.prompt.orEmpty() }
        val pending = state.copy(saving = true, failed = false, archiveFailed = false)
        channelEditor.value = pending
        viewModelScope.launch {
            var current = pending
            if (renameTo != null) {
                try {
                    live.rename(state.conversationId, renameTo)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Never log the name, the prompt, the ids or the server's message.
                    RelayLog.d { "event=channel_rename_failed" }
                    channelEditor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                    return@launch
                }
                current = pending.copy(savedName = renameTo)
                channelEditor.compareAndSet(pending, current)
            }
            if (muteTo != null) {
                val before = current
                try {
                    live.setMuted(state.conversationId, muteTo)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=channel_mute_write_failed" }
                    channelEditor.compareAndSet(before, before.copy(saving = false, failed = true))
                    return@launch
                }
                current = before.copy(savedMuted = muteTo)
                channelEditor.compareAndSet(before, current)
            }
            if (promptToWrite != null) {
                try {
                    live.setSystemPrompt(state.conversationId, promptToWrite)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    RelayLog.d { "event=channel_prompt_write_failed" }
                    channelEditor.compareAndSet(current, current.copy(saving = false, failed = true))
                    return@launch
                }
            }
            // The row picks the new name and flag up from the host's own conversation stream; nothing is patched here.
            channelEditor.compareAndSet(current, null)
            RelayLog.d {
                "event=channel_edited renamed=${renameTo != null} muted=${muteTo != null} prompt=${promptToWrite != null}"
            }
        }
    }

    /**
     * Archives the open editor's channel on the editor's own host, then closes the modal (#667).
     *
     * [archiveChat]'s shape: no confirmation, since Archive restores it, and nothing the fields hold — a
     * blank name or an unread prompt — stands in the way. The channel leaves Channels through the host's own
     * conversation stream.
     */
    fun archiveChannel() {
        val state = channelEditor.value ?: return
        if (state.saving) return
        if (!isHostLive(state.serverId)) {
            RelayLog.d { "event=channel_archive_rejected code=disconnected" }
            closeChannelEditor(state)
            return
        }
        val live = hostSource.repositoryFor(state.serverId)
        if (live == null) {
            RelayLog.d { "event=channel_archive_rejected code=unavailable" }
            channelEditor.value = state.copy(failed = false, archiveFailed = true)
            return
        }
        val pending = state.copy(saving = true, failed = false, archiveFailed = false)
        channelEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=channel_archive_started" }
            try {
                live.archive(state.conversationId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                RelayLog.d { "event=channel_archive_failed" }
                channelEditor.compareAndSet(pending, pending.copy(saving = false, archiveFailed = true))
                return@launch
            }
            channelEditor.compareAndSet(pending, null)
            RelayLog.d { "event=channel_archived" }
        }
    }

    /** Closes [state]'s Edit channel modal, and stops its prompt read, unless another one replaced it. */
    private fun closeChannelEditor(state: ChannelEditorState) {
        if (channelEditor.compareAndSet(state, null)) channelPromptRead?.cancel()
    }

    /** Cancel, Close and Back: close the Edit channel modal, stop its prompt read and send nothing. */
    fun dismissChannelEditor() {
        channelEditor.value = null
        channelPromptRead?.cancel()
        RelayLog.d { "event=channel_editor_dismissed" }
    }

    /**
     * One stored-prompt read. A reply over the byte limit is [ChannelPromptReading.Unavailable]: the decoder
     * bounds nothing, and a prompt the field cannot hold must never be rendered or written back.
     */
    private suspend fun readChannelPrompt(
        live: ConversationRepository,
        conversationId: String,
    ): ChannelPromptReading =
        try {
            val reading = live.requestSystemPrompt(conversationId)
            val prompt = reading.systemPrompt
            if (prompt != null && !SystemPromptLimit.fits(prompt)) {
                RelayLog.d { "event=channel_prompt_read_failed code=oversize" }
                ChannelPromptReading.Unavailable
            } else {
                RelayLog.d { "event=channel_prompt_read" }
                ChannelPromptReading.Read(prompt, reading.sessionPromptStatus)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            RelayLog.d { "event=channel_prompt_read_failed" }
            ChannelPromptReading.Unavailable
        }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val RECENT_DISCUSSIONS_LIMIT = 3

        // The modal's content column is not lazy, so a hostile reply's list is bounded here.
        const val MAX_ADD_WORKSPACE_RECENTS = 50
    }
}
