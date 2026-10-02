package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ReadPosition
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.UUID

/** Host identity surrounds unchanged, host-local conversation records. Contains no pairing secrets. */
data class HostConversationSnapshot(
    val serverId: String,
    val displayName: String?,
    val connectionStatus: ConnectionStatus,
    val channels: List<Conversation> = emptyList(),
    val chats: List<Conversation> = emptyList(),
)

/**
 * One thing on one host that may deserve an alert (#685): a turn the attention fold counted for the first
 * time, or a prompt newly outstanding. [key] is the turn id, or `modal:<modalId>` / `batch:<batchId>`.
 * Every field is daemon-authored except [serverId], so each is an identity only, never text to show.
 */
data class AttentionAlert(
    val serverId: String,
    val conversationId: String,
    val kind: Kind,
    val key: String,
) {
    enum class Kind { TurnCompleted, Prompt }
}

/**
 * Internal presentation and stream descriptor; repository-stream identity is the bundle generation.
 *
 * The last three are the host's own attention sources (#877): its coordinator's live events, every
 * permission prompt it holds (#1338) and its outstanding question batches. Defaulted inert for the demo host.
 */
internal data class HostConversationConnection(
    val serverId: String,
    val displayName: String?,
    val repositories: StateFlow<ConversationRepository?>,
    val status: StateFlow<ConnectionStatus>,
    val liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
    val modals: StateFlow<HostModalState> = MutableStateFlow(HostModalState()),
    val questionBatches: StateFlow<List<QuestionBatch>> = MutableStateFlow(emptyList()),
)

/**
 * App-owned cache: collection continues without subscribers until [dispose].
 *
 * A host's rows otherwise live only while its repository does, so the teardown the lifecycle driver
 * performs on background — and the process death that may follow — draws every saved host empty. With
 * a [ConversationCache] present (#796) each accepted live list is written to it and a new host entry
 * is seeded from it, so previously loaded rows survive both. The seed never touches
 * [HostConversationSnapshot.connectionStatus]: a restored list must not read as a connected one. The
 * demo path passes no cache and is unaffected.
 *
 * [retry] redials exactly one host by its `serverId` (#840); the relay path routes it to the registry's
 * own per-host retry, which keeps its foreground and identity refusals.
 *
 * [attention] holds one [ConversationAttention] per conversation per host (#877), folded from each host's
 * own sources and keyed by host first, because two hosts can hold the same conversation id. It is a
 * separate flow from [snapshots] so an attention change never re-projects a host's rows. Read positions
 * are restored from and written to [cache] per host, so unpairing a host drops them.
 */
class HostConversationSource internal constructor(
    private val connections: StateFlow<List<HostConversationConnection>>,
    private val lookup: (String) -> ConversationRepository?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cache: ConversationCache? = null,
    private val retry: (String) -> Unit = {},
    private val viewing: ConversationViewing = ConversationViewing(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val held = mutableMapOf<String, Held>()
    private val state = MutableStateFlow<List<HostConversationSnapshot>>(emptyList())
    val snapshots = state.asStateFlow()
    private val attentionState = MutableStateFlow<Map<String, Map<String, ConversationAttention>>>(emptyMap())

    /** Non-Idle states only, by `serverId` then conversation id; a conversation missing from it is Idle. */
    val attention = attentionState.asStateFlow()
    private val alertEvents = MutableSharedFlow<AttentionAlert>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Candidate alerts (#685), emitted once per newly counted turn, whether or not the conversation is
     * viewed, and once per prompt key new since the host's previous prompt set. Hot and not replayed:
     * a re-shown prompt emits again after a reconnect, so a consumer that must alert once dedupes.
     */
    val alerts = alertEvents.asSharedFlow()
    private var disposed = false

    init {
        scope.launch { connections.collect { reconcile(it) } }
    }

    /** Current availability only; an operation may still lose its connection after this lookup. */
    @Synchronized
    fun repositoryFor(serverId: String): ConversationRepository? = if (disposed) null else lookup(serverId)

    /**
     * Retries one host's connection, and only that host's. Snapshots are untouched, so the host's rows,
     * cached or live, stay drawn while it redials.
     *
     * [retry] runs outside this monitor: on the relay path it takes the registry's lock, and holding both
     * would add a lock order this class has never had.
     */
    fun retryHost(serverId: String) {
        if (synchronized(this) { disposed }) return
        retry(serverId)
    }

    /**
     * The operator opened [conversationId] on [serverId]: its unread and failed states clear on that host
     * only. A host that is not held yet has nothing to clear.
     */
    @Synchronized
    fun markOpened(
        serverId: String,
        conversationId: String,
    ) {
        val entry = held[serverId] ?: return
        updateAttention(entry) { attention = attention.opened(conversationId) }
        RelayLog.d { "event=conversation_attention_opened" }
    }

    @Synchronized
    private fun reconcile(current: List<HostConversationConnection>) {
        if (disposed) return
        val retained = current.associateBy { it.serverId }
        held.entries.removeAll { (id, entry) ->
            (retained[id]?.repositories !== entry.connection.repositories).also { removed ->
                if (removed) entry.job.cancel()
            }
        }
        current.forEach { connection ->
            val existing = held[connection.serverId]
            if (existing != null) {
                existing.snapshot = existing.snapshot.copy(displayName = connection.displayName)
            } else {
                val entry = Held(connection, SupervisorJob(scope.coroutineContext[Job]))
                held[connection.serverId] = entry
                scope.launch(entry.job) {
                    connection.status.collect { status -> update(entry) { it.copy(connectionStatus = status) } }
                }
                cache?.let { store ->
                    scope.launch(entry.job) {
                        // A read never throws for an unreadable or absent document; it yields empty, and an
                        // empty result must not be read as "the daemon has no conversations". Only a live
                        // list may empty a host, so nothing is published when there is nothing cached.
                        val restored = store.readConversations(connection.serverId)
                        if (restored.isNotEmpty()) update(entry, restore = true) { it.withRows(restored) }
                    }
                }
                launchAttention(entry)
                scope.launch(entry.job) {
                    connection.repositories.collectLatest { repository ->
                        repository
                            ?.observeConversations(ConversationFilter.All)
                            ?.catch { RelayLog.d { "event=host_snapshot_list_failed" } }
                            ?.collect { rows ->
                                // Only a list the guards accepted is cached, so a superseded generation
                                // cannot reach disk after being rejected for the snapshot. What is stored is
                                // the daemon's list verbatim, archived rows included: the document mirrors
                                // what was reported and `withRows` filters both sides of it identically.
                                if (update(entry, repository) { it.withRows(rows) }) {
                                    cache
                                        ?.writeConversations(connection.serverId, rows)
                                        ?.onFailure { RelayLog.d { "event=host_snapshot_cache_write_failed" } }
                                }
                            }
                    }
                }
            }
        }
        publish()
        RelayLog.d { "event=host_snapshots_reconciled count=${held.size}" }
    }

    /**
     * The host's attention collectors (#877), all under the entry's job so a replaced bundle stops them.
     * Events and new thread rows fold with the viewing state read under the same monitor; a repository going away is the host
     * losing its connection, which ends every running turn and busy conversation on it.
     */
    private fun launchAttention(entry: Held) {
        val connection = entry.connection
        scope.launch(entry.job) {
            connection.liveSessionEvents.collect { event ->
                updateAttention(entry) {
                    val before = attention.counted[event.conversationId]
                    attention = attention.onEvent(event, viewing.isViewing(connection.serverId, event.conversationId))
                    // The fold appends a turn id to `counted` only when it counts that turn for the first time.
                    if (event is LiveSessionEvent.TurnEnd && attention.counted[event.conversationId] != before) {
                        alert(connection.serverId, event.conversationId, AttentionAlert.Kind.TurnCompleted, event.turnId)
                    }
                }
            }
        }
        scope.launch(entry.job) {
            connection.repositories.collect { repository ->
                if (repository == null) updateAttention(entry) { attention = attention.disconnected() }
            }
        }
        scope.launch(entry.job) {
            connection.repositories.collectLatest { repository ->
                // Each connection's repository starts its thread store empty, so its baseline is zero rows:
                // rows a replay delivered before this first read are new (#1361).
                var seen = emptyMap<String, Int>()
                repository?.observeThreadRowCounts()?.collect { counts ->
                    val grown = counts.filter { (id, count) -> count > (seen[id] ?: 0) }.keys
                    seen = counts
                    if (grown.isNotEmpty()) {
                        updateAttention(entry) {
                            attention =
                                grown.fold(attention) { state, id ->
                                    state.rowsAdded(id, viewing.isViewing(connection.serverId, id), UUID.randomUUID().toString())
                                }
                        }
                    }
                }
            }
        }
        scope.launch(entry.job) {
            // Each connection's repository holds its own busy edges (#1452); a null one observes nothing, and
            // the collector above clears the busy set it left.
            connection.repositories.collectLatest { repository ->
                repository?.observeBusyConversations()?.collect { ids -> updateAttention(entry) { attention = attention.withBusy(ids) } }
            }
        }
        scope.launch(entry.job) {
            // Opening is idempotent, so every viewed conversation of this host is re-opened on each change.
            viewing.viewed.collect { viewed ->
                val opened = viewed.filter { it.first == connection.serverId }.map { it.second }
                if (opened.isNotEmpty()) updateAttention(entry) { attention = opened.fold(attention) { state, id -> state.opened(id) } }
            }
        }
        scope.launch(entry.job) {
            combine(connection.modals, connection.questionBatches, ::Pair).collect { (modals, batches) ->
                updateAttention(entry) {
                    this.modals = modals.outstanding
                    this.batches = batches
                    val current = promptKeys(modals.outstanding, batches)
                    current.filterKeys { it !in prompts }.forEach { (key, conversationId) ->
                        alert(connection.serverId, conversationId, AttentionAlert.Kind.Prompt, key)
                    }
                    prompts = current.keys
                }
            }
        }
        val store = cache ?: return
        scope.launch(entry.job) {
            // Written only after the restore landed, so an empty map never overwrites stored positions.
            var written = store.readReadPositions(connection.serverId)
            updateAttention(entry) {
                attention = attention.restored(written)
                positions.value = attention.positions
            }
            entry.positions.filterNotNull().collect { positions ->
                if (positions != written) {
                    written = positions
                    store
                        .writeReadPositions(connection.serverId, positions)
                        .onFailure { RelayLog.d { "event=conversation_attention_write_failed" } }
                }
            }
        }
    }

    @Synchronized
    private fun updateAttention(
        entry: Held,
        change: Held.() -> Unit,
    ) {
        if (!isCurrent(entry)) return
        entry.change()
        if (entry.positions.value != null) entry.positions.value = entry.attention.positions
        entry.resolved = entry.attention.resolve(entry.modals, entry.batches)
        publish()
    }

    private fun alert(
        serverId: String,
        conversationId: String,
        kind: AttentionAlert.Kind,
        key: String,
    ) {
        alertEvents.tryEmit(AttentionAlert(serverId, conversationId, kind, key))
    }

    /** Each outstanding prompt's key and its conversation; a blank-conversation prompt belongs to none. */
    private fun promptKeys(
        modals: List<ModalUiState.Open>,
        batches: List<QuestionBatch>,
    ): Map<String, String> =
        batches.filter { it.conversationId.isNotBlank() }.associate { "batch:${it.questionBatchId}" to it.conversationId } +
            modals.filter { it.conversationId.isNotBlank() }.associate { "modal:${it.modalId}" to it.conversationId }

    /** Whether [entry] is still its host's live generation; a retired one may neither publish nor persist. */
    private fun isCurrent(entry: Held): Boolean {
        val connection = entry.connection
        return !disposed &&
            held[connection.serverId] === entry &&
            connections.value.any { it.serverId == connection.serverId && it.repositories === connection.repositories }
    }

    /** Reports whether the snapshot was actually transformed, so only an accepted list is cached. */
    @Synchronized
    private fun update(
        entry: Held,
        repository: ConversationRepository? = null,
        restore: Boolean = false,
        transform: (HostConversationSnapshot) -> HostConversationSnapshot,
    ): Boolean {
        val connection = entry.connection
        if (!isCurrent(entry) ||
            (repository != null && connection.repositories.value !== repository) ||
            // A slow restore that finishes after the daemon's list landed must not undo it. The cache
            // read suspends outside this monitor, so reading and setting `live` under it is atomic
            // against the live path.
            (restore && entry.live)
        ) {
            return false
        }
        if (repository != null) entry.live = true
        entry.snapshot = transform(entry.snapshot)
        publish()
        return true
    }

    /** The one promoted/archived split: a restored host is filtered by exactly the live rule. */
    private fun HostConversationSnapshot.withRows(rows: List<Conversation>) =
        copy(
            channels = rows.filter { it.isPromoted && !it.archived },
            chats = rows.filter { !it.isPromoted && !it.archived },
        )

    private fun publish() {
        state.value = connections.value.mapNotNull { held[it.serverId]?.snapshot }
        attentionState.value = connections.value.mapNotNull { host -> held[host.serverId]?.let { host.serverId to it.resolved } }.toMap()
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        held.clear()
        state.value = emptyList()
        attentionState.value = emptyMap()
        RelayLog.d { "event=host_snapshots_disposed" }
    }

    private class Held(
        val connection: HostConversationConnection,
        val job: Job,
    ) {
        var snapshot = HostConversationSnapshot(connection.serverId, connection.displayName, connection.status.value)

        /** Set once a live list is accepted; a cache restore landing afterwards must not replace it. */
        var live = false

        var attention = HostAttentionState()
        var modals: List<ModalUiState.Open> = emptyList()
        var batches: List<QuestionBatch> = emptyList()
        var resolved: Map<String, ConversationAttention> = emptyMap()

        /** The prompt keys already alerted for this generation, so an unchanged prompt emits once (#685). */
        var prompts: Set<String> = emptySet()

        /** The positions to persist; null until the stored ones were restored, and never set without a cache. */
        val positions = MutableStateFlow<Map<String, ReadPosition>?>(null)
    }

    companion object {
        const val DEMO_SERVER_ID = "demo"

        /** [cache] trails [dispatcher] so no existing positional call site moves; null disables both sides. */
        fun relay(
            registry: RelayConnectionRegistry,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            cache: ConversationCache? = null,
            viewing: ConversationViewing = ConversationViewing(),
        ) = HostConversationSource(
            registry.hostConnections,
            { serverId -> registry.connectionFor(serverId)?.coordinator?.liveRepository() },
            dispatcher,
            cache,
            // The thread banner's pairing: a bundle replaced between the two calls fails retryHost's
            // identity check and is refused rather than redialled.
            retry = { serverId -> registry.connectionFor(serverId)?.let { registry.retryHost(serverId, it) } },
            viewing = viewing,
        )

        fun demo(
            repository: ConversationRepository,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            viewing: ConversationViewing = ConversationViewing(),
        ): HostConversationSource {
            val demo =
                HostConversationConnection(
                    DEMO_SERVER_ID,
                    "Demo",
                    MutableStateFlow(repository),
                    MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                )
            return HostConversationSource(
                MutableStateFlow(listOf(demo)),
                { if (it == DEMO_SERVER_ID) repository else null },
                dispatcher,
                viewing = viewing,
            )
        }
    }
}
