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
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.reduceOrderedHistoryPage
import de.pyryco.mobile.notifications.completionReply
import de.pyryco.mobile.notifications.notificationPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

/** Host identity surrounds unchanged, host-local conversation records. Contains no pairing secrets. */
data class HostConversationSnapshot(
    val serverId: String,
    val displayName: String?,
    val connectionStatus: ConnectionStatus,
    val channels: List<Conversation> = emptyList(),
    val chats: List<Conversation> = emptyList(),
    /** Only an accepted live list establishes absence; startup and cache restoration do not. */
    val rowsLoaded: Boolean = false,
)

/**
 * One thing on one host that may deserve an alert (#685): a turn the attention fold counted for the first
 * time, or a prompt newly outstanding. [key] is the turn id, or `modal:<modalId>` / `batch:<batchId>`.
 * Identity fields never supply display text. The optional [preview] supplies ephemeral untrusted content.
 */
data class AttentionAlert(
    val serverId: String,
    val conversationId: String,
    val kind: Kind,
    val key: String,
    /** Present only for a history-backed completion; never inferred from the conversation's latest. */
    val historyEntryId: ULong? = null,
    /** Ephemeral untrusted display content; evaluated only by the notifier after its gates. */
    val preview: (suspend () -> String?)? = null,
    /** Rejects a host/repository generation retired while enrichment was pending. */
    val isCurrent: () -> Boolean = { true },
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
    private val readMarkState = MutableStateFlow<Map<String, Map<String, ConversationReadMarks>>>(emptyMap())

    /** Confirmed live facts, independent of attention precedence; never inferred from local positions. */
    internal val readMarks = readMarkState.asStateFlow()
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

    /** Fresh notification proof from the current repository; a replacement never inherits old support. */
    @Synchronized
    internal fun currentReadMarks(
        serverId: String,
        conversationId: String,
    ): ConversationReadMarks? {
        if (disposed) return null
        val entry = held[serverId] ?: return null
        if (!isCurrent(entry)) return null
        val repository = entry.connection.repositories.value
        return when {
            repository is RemoteConversationRepository -> repository.currentReadMarks(conversationId)
            repository == null || entry.attentionRepository === repository -> entry.attention.readMarks[conversationId]
            else -> null
        }
    }

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
     * The operator opened [conversationId] on [serverId]: clear its legacy local unread position.
     * A modern conversation stays unread until its confirmed shared mark covers its newest known entry.
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
                        val rows =
                            when (repository) {
                                is RemoteConversationRepository -> repository.observeConversationSnapshots(ConversationFilter.All)
                                null -> null
                                else -> repository.observeConversations(ConversationFilter.All).map { it to true }
                            }
                        rows
                            ?.catch { RelayLog.d { "event=host_snapshot_list_failed" } }
                            ?.collect { (rows, loaded) ->
                                // Only a list the guards accepted is cached, so a superseded generation
                                // cannot reach disk after being rejected for the snapshot. What is stored is
                                // the daemon's list verbatim, archived rows included: the document mirrors
                                // what was reported and `withRows` filters both sides of it identically.
                                if (update(entry, repository, rowsLoaded = loaded) { it.withRows(rows) }) {
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
                        alert(
                            entry,
                            event.conversationId,
                            AttentionAlert.Kind.TurnCompleted,
                            event.turnId,
                            event.historyEntryId,
                            preview = completionPreview(entry, event),
                        )
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
                if (repository != null) {
                    repository.observeHostReadMarks().collect { marks -> updateReadMarks(entry, repository, marks) }
                }
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
                        updateAttention(entry, repository) {
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
                        val modal = modals.outstanding.firstOrNull { "modal:${it.modalId}" == key }
                        val prompt =
                            if (modal?.modalClass == "permission") {
                                notificationPreview(modal.prompt)?.let { "${modal.title}\n\n${modal.prompt}" }
                            } else if (key.startsWith("batch:")) {
                                batches
                                    .firstOrNull { "batch:${it.questionBatchId}" == key }
                                    ?.questions
                                    ?.firstOrNull()
                                    ?.question
                                    ?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            }
                        alert(entry, conversationId, AttentionAlert.Kind.Prompt, key, preview = { prompt })
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
    private fun updateReadMarks(
        entry: Held,
        repository: ConversationRepository,
        marks: Map<String, ConversationReadMarks>,
    ) {
        updateAttention(entry, repository) { attention = attention.withReadMarks(marks) }
        RelayLog.d { "event=conversation_attention_read_facts" }
    }

    @Synchronized
    private fun updateAttention(
        entry: Held,
        repository: ConversationRepository? = null,
        change: Held.() -> Unit,
    ) {
        if (!isCurrent(entry)) return
        val current = entry.connection.repositories.value
        if (repository != null && current !== repository) return
        // Establish the generation in the same critical section as every consumer, including events
        // that beat the repository collectors. Offline retains facts; a new daemon starts without them.
        if (entry.attentionRepository !== current) {
            entry.attentionRepository = current
            if (current != null) entry.attention = entry.attention.withReadMarks(emptyMap())
        }
        entry.change()
        if (entry.positions.value != null) entry.positions.value = entry.attention.positions
        entry.resolved = entry.attention.resolve(entry.modals, entry.batches)
        publish()
    }

    private fun alert(
        entry: Held,
        conversationId: String,
        kind: AttentionAlert.Kind,
        key: String,
        historyEntryId: ULong? = null,
        preview: (suspend () -> String?)? = null,
    ) {
        val repository = entry.connection.repositories.value
        alertEvents.tryEmit(
            AttentionAlert(entry.connection.serverId, conversationId, kind, key, historyEntryId, preview) {
                previewIsCurrent(entry, repository)
            },
        )
    }

    @Synchronized
    private fun previewIsCurrent(
        entry: Held,
        repository: ConversationRepository?,
    ): Boolean = isCurrent(entry) && entry.connection.repositories.value === repository

    /** Work remains owned by the host, even while the notifier waits independently of other alerts. */
    private fun completionPreview(
        entry: Held,
        event: LiveSessionEvent.TurnEnd,
    ): suspend () -> String? {
        val repository = entry.connection.repositories.value
        return {
            if (!previewIsCurrent(entry, repository)) throw CancellationException("retired attention source")
            val pending =
                scope.async(entry.job) {
                    coroutineScope {
                        val lookup =
                            async {
                                withTimeoutOrNull(3_000) {
                                    repository?.let { readCompletionPreview(it, event) }
                                }.also { if (it == null) RelayLog.d { "event=attention_preview outcome=fallback" } }
                            }
                        val retirement =
                            launch {
                                entry.connection.repositories.first { entry.connection.repositories.value !== repository }
                                lookup.cancel()
                            }
                        try {
                            lookup.await().also {
                                if (!previewIsCurrent(entry, repository)) throw CancellationException("retired attention source")
                            }
                        } finally {
                            retirement.cancel()
                        }
                    }
                }
            try {
                pending.await()
            } finally {
                pending.cancel()
            }
        }
    }

    /** Read-only evidence: neither the local snapshot nor this one newest page is merged or persisted. */
    private suspend fun readCompletionPreview(
        repository: ConversationRepository,
        event: LiveSessionEvent.TurnEnd,
    ): String? =
        try {
            // The projection can silently drop malformed deltas/tool seams; even a settled prefix
            // starting at zero cannot certify the tail. Only raw evidence through the end can do so.
            val page =
                if (repository is RemoteConversationRepository) {
                    repository.requestAttentionHistory(event.conversationId)
                } else {
                    repository.requestHistory(event.conversationId)
                }
            val entries = page.entries
            val attributed =
                entries.all { item ->
                    val id = (item.payload as? JsonObject)?.get("conversation_id") as? JsonPrimitive
                    id?.isString == true && id.content == event.conversationId
                }
            val completed =
                entries.any { item ->
                    item.type == "turn_end" &&
                        (event.historyEntryId == null || item.unsignedId == event.historyEntryId) &&
                        runCatching {
                            MobileJson.decodeFromJsonElement<TurnEndPayloadDto>(item.payload).let {
                                it.conversationId == event.conversationId && it.turnId == event.turnId
                            }
                        }.getOrDefault(false)
                }
            val continuous =
                entries.zipWithNext().all { (newer, older) ->
                    newer.unsignedId > older.unsignedId && newer.unsignedId - older.unsignedId == 1uL
                }
            if (!attributed || !completed || !continuous) {
                null
            } else {
                val reduced = reduceOrderedHistoryPage(entries, interactive = true)
                // Every supported row producer/update must be represented or explicitly understood.
                // A null fact means the forgiving reducer dropped evidence; do not trust its rows.
                val rowTypes =
                    setOf(
                        "message",
                        "send_message",
                        "assistant_delta",
                        "turn_end",
                        "tool_use",
                        "tool_result",
                        "tool_denied",
                        "tool_progress",
                        "session_transition",
                        "unrecognized_message",
                        "banner",
                        "compacting",
                        "compaction_boundary",
                        "model_refusal_fallback",
                        "model_refusal_no_fallback",
                        "background_task_started",
                        "background_task_updated",
                    )
                val retained = entries.filter { it.type in rowTypes }.all { reduced.readFacts[it.unsignedId] != null }
                if (retained) completionReply(reduced.rows, event.turnId) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RelayLog.d { "event=attention_preview outcome=unavailable" }
            null
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
        rowsLoaded: Boolean = true,
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
        entry.snapshot = transform(entry.snapshot).let { if (repository != null) it.copy(rowsLoaded = rowsLoaded) else it }
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
        readMarkState.value =
            connections.value.mapNotNull { host -> held[host.serverId]?.let { host.serverId to it.attention.readMarks } }.toMap()
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        held.clear()
        state.value = emptyList()
        attentionState.value = emptyMap()
        readMarkState.value = emptyMap()
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
        var attentionRepository: ConversationRepository? = null
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
