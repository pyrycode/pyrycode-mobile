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
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
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

/**
 * The Edit host modal's target and the caller-owned flags that component requires (#744).
 *
 * Holds **display text and the target id only** — never the [de.pyryco.mobile.data.crypto.PairedServer]
 * it was read from. That record also carries the pairing token and the server static key; keeping it here
 * would put both credentials in a `StateFlow` that outlives the modal, for no gain: the two fields the
 * ticket allows are copied out at open time and the entry is dropped.
 *
 * [serverIdentity] and [relayAddress] are carried unclamped, deliberately. `parsePairingPayload` bounds
 * neither field's length — it checks the relay's scheme and host and tolerates a path — so both are
 * QR-authored and unbounded, and the clamp belongs where `EditHostModal` already applies it, at that
 * component's own boundary before layout and semantics. It keys its name buffer on the raw identity so
 * two hosts sharing a 128-character prefix cannot collapse onto one buffer; clamping here would defeat
 * that. Nothing outside the modal reads either field.
 *
 * [failed] and [unpairFailed] are flags rather than messages so the string resolves at the screen, which
 * keeps this view model free of `Context` and makes it impossible for an identity or a relay address to
 * reach the shell's live region. The name draft is absent for the same division: `EditHostModal` owns its
 * own buffer, so a failed save keeps what the operator typed with no view-model involvement — provided the
 * same state instance stays published, which is why the failure path copies rather than reopens.
 *
 * [confirmingUnpair] is a flag on the open editor rather than a second pending-target flow (#745): the
 * target is already here. [serverId] is the exact id the modal was opened for, and `remove` being id-exact
 * and a no-op on an unknown id protects the other hosts only if the id handed to it is the right one — a
 * second id would be a second source of truth for which host is being removed.
 *
 * [unpairFailed] is separate from [failed] rather than shared so the screen picks its string from an
 * explicit flag instead of inferring the failing operation from [confirmingUnpair].
 *
 * [saving] means "a write is in flight; block the rest", and covers the removal as well as the rename —
 * a fifth flag would say the same thing.
 */
data class HostEditorState(
    val serverId: String,
    val serverIdentity: String,
    val relayAddress: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val confirmingUnpair: Boolean = false,
    val unpairFailed: Boolean = false,
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
 * touches. It reads exactly two of a record's four fields, `serverId` and `relayUrl`, and never the
 * pairing token or the server static key.
 */
class ChannelListViewModel(
    private val appPreferences: AppPreferences,
    private val hostSource: HostConversationSource,
    private val pairedServers: PairedServerCollectionStore,
) : ViewModel() {
    private val pendingHostWorkspacePicker = MutableStateFlow<String?>(null)

    // Fold and selection are the screen's, but they live here so they survive recomposition, LazyColumn
    // recycling, an incoming snapshot and the thread round trip (#731). The editor's target and its two
    // flags join them for the same reason (#744).
    private val collapsedKeys = MutableStateFlow<Set<TreeFoldKey>>(emptySet())
    private val lastOpenedTarget = MutableStateFlow<HostConversationTarget?>(null)
    private val hostEditor = MutableStateFlow<HostEditorState?>(null)

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
            hostEditor,
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

    // Read and written only from a tap dispatch on the main dispatcher, so it needs no synchronisation.
    private var editorOpenJob: Job? = null

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
     * Opens the Edit host modal on [serverId]'s own stored record (#744).
     *
     * Only the most recently tapped pencil may publish, which is what the cancellation buys: two rows'
     * controls tapped while the first `loadById` is still decrypting race on the assignment otherwise,
     * and the slower read wins — the modal would then show, and rename, a host the operator did not tap
     * last. The row's own `serverId` on the event cannot close that; both events carry a correct id and
     * the defect is in which reply lands. A cancelled launch cannot publish at all, rather than being
     * asked to check whether it still should.
     */
    fun openHostEditor(serverId: String) {
        editorOpenJob?.cancel()
        editorOpenJob =
            viewModelScope.launch {
                val entry =
                    try {
                        pairedServers.loadById(serverId)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // `list` re-raises an unclassified failure, and this launch is in viewModelScope:
                        // an escaping throw would reach the default handler and kill the process.
                        RelayLog.d { "event=host_editor_open_failed" }
                        return@launch
                    }
                if (entry == null) {
                    // No record to read: the design's two identity rows cannot be drawn from an absent one.
                    RelayLog.d { "event=host_editor_open_rejected code=unknown_host" }
                    return@launch
                }
                hostEditor.value =
                    HostEditorState(
                        serverId = serverId,
                        serverIdentity = entry.record.serverId,
                        relayAddress = entry.record.relayUrl,
                        // Blank reads as unnamed, exactly as the row reads it: an empty field, never the
                        // id and never the list's placeholder text.
                        initialName = entry.displayName?.takeIf { it.isNotBlank() }.orEmpty(),
                    )
                RelayLog.d { "event=host_editor_opened" }
            }
    }

    /**
     * Saves the entered name as the open host's local display name, clearing it when blank.
     *
     * The trim is this method's own rather than trusted from the component, so its contract holds for any
     * caller. The clamp is the same [MAX_WORKSPACE_LABEL_CHARS] every surface that renders a host name
     * already applies, moved to the write: the name is operator-authored, but a single-line field still
     * accepts an arbitrary paste, and this value is stored inside the encrypted pairing blob that
     * `KeystorePairedServerStore.list` decrypts and parses on every revision bump and every registry
     * reconcile — so an oversized one is a recurring cost on the same read path that loads credentials.
     * Bytes past the bound were never renderable.
     *
     * Both terminal transitions are `compareAndSet` against the state published before the call, so a save
     * that completes after a dismissal cannot resurrect a closed modal or overwrite a newer one.
     */
    fun submitHostName(name: String) {
        val target = hostEditor.value ?: return
        if (target.saving) return
        val pending = target.copy(saving = true, failed = false)
        hostEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=host_name_save_started" }
            try {
                pairedServers.setDisplayName(target.serverId, name.trim().take(MAX_WORKSPACE_LABEL_CHARS).ifBlank { null })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the name or the store's message; the UI gets one static string.
                RelayLog.d { "event=host_name_save_failed" }
                hostEditor.compareAndSet(pending, pending.copy(saving = false, failed = true))
                return@launch
            }
            // An id no longer stored is a silent no-op in the store, and the host is already gone from the
            // tree, so closing is the right outcome for it too.
            hostEditor.compareAndSet(pending, null)
            RelayLog.d { "event=host_name_saved" }
        }
    }

    /**
     * Arms the unpair confirmation on the open editor (#745).
     *
     * Guarded on [HostEditorState.saving] like every other transition here: the shell disables its OK
     * while loading but leaves the content live, so the `Unpair host` action can still be tapped during a
     * rename. Publishing a confirmation step under that rename's pending state would make its
     * `compareAndSet` fail and strand the modal on a step the store never took.
     */
    fun requestHostUnpair() {
        val target = hostEditor.value ?: return
        if (target.saving) return
        hostEditor.value = target.copy(confirmingUnpair = true, failed = false, unpairFailed = false)
        RelayLog.d { "event=host_unpair_requested" }
    }

    /** Backs out of the confirmation without writing, leaving the editor open — never closing it. */
    fun declineHostUnpair() {
        val target = hostEditor.value ?: return
        // Same guard, same reason: a Cancel tap mid-removal must not defeat that removal's own close.
        if (target.saving) return
        hostEditor.value = target.copy(confirmingUnpair = false, unpairFailed = false)
        RelayLog.d { "event=host_unpair_declined" }
    }

    /**
     * Removes the confirmed host's pairing, then its cached default workspace, then closes the editor.
     *
     * The order is the requirement: a failed store write has to leave the pairing intact, so the
     * host-owned workspace preference is not cleared until the removal has reported success, and a cleared
     * cache is never evidence the host is gone. The connection close needs no call of its own — this is
     * the shared observable store, whose revision bump `RelayConnectionRegistry` reconciles by closing
     * exactly the removed id's bundle.
     *
     * A failed workspace clear is deliberately not surfaced: the pairing is already gone and the
     * connection already closing, so reporting a failure would claim the host is still paired when it is
     * not. It leaves one inert preference keyed by an id nothing is paired to any more.
     *
     * Both terminal transitions are `compareAndSet` against the state published before the call, so a
     * removal completing after a dismissal cannot resurrect a closed modal or overwrite a newer one.
     */
    fun confirmHostUnpair() {
        val target = hostEditor.value ?: return
        if (target.saving || !target.confirmingUnpair) return
        val pending = target.copy(saving = true, failed = false, unpairFailed = false)
        hostEditor.value = pending
        viewModelScope.launch {
            RelayLog.d { "event=host_unpair_started" }
            try {
                pairedServers.remove(target.serverId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never log the id or the store's message; the UI gets one static string.
                RelayLog.d { "event=host_unpair_failed" }
                hostEditor.compareAndSet(pending, pending.copy(saving = false, unpairFailed = true))
                return@launch
            }
            // Only now: the pairing is gone, so clearing this host's own cached workspace cannot strand
            // a host that is still paired without one.
            appPreferences.removeDefaultWorkspace(target.serverId)
            hostEditor.compareAndSet(pending, null)
            RelayLog.d { "event=host_unpaired" }
        }
    }

    /**
     * Cancel, Close and Back all land here, and none of the three writes anything.
     *
     * Unguarded, unlike the three above: it publishes `null`, which is the state a completing write lands
     * on anyway, so there is no pending transition for it to strand.
     */
    fun dismissHostEditor() {
        hostEditor.value = null
        RelayLog.d { "event=host_editor_dismissed" }
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
