package de.pyryco.mobile.di

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.DebugBundleStatus
import de.pyryco.mobile.data.repository.DebugBundleTransfer
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Owns every saved host; selection changes only the temporary single-host compatibility view. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectionRegistry(
    private val store: ObservablePairedServerStore,
    private val factory: RelayConnectionFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RelayConnectionController,
    ConnectionStateSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val entries = mutableMapOf<String, Pair<PairedServer, RelayConnectionBundle>>()
    private var foreground = false
    private var disposed = false
    private val selection = MutableStateFlow<RelayConnectionBundle?>(null)
    val selected = selection.asStateFlow()
    private val hosts = MutableStateFlow<List<HostConversationConnection>>(emptyList())
    internal val hostConnections = hosts.asStateFlow()

    val currentRepository: StateFlow<ConversationRepository?> = project(null) { it.coordinator.currentRepository }
    val connectionStatus: StateFlow<ConnectionStatus> = project(IDLE) { it.coordinator.connectionStatus }
    val liveSessionEvents = selection.flatMapLatest { it?.coordinator?.liveSessionEvents ?: emptyFlow() }

    // Read through the same selection as outbound actions. A stateIn cache can still hold A's
    // repository after selection switches to B, sending a one-shot to the wrong host at that edge.
    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private fun <T> project(
        empty: T,
        source: (RelayConnectionBundle) -> StateFlow<T>,
    ): StateFlow<T> =
        object : StateFlow<T> {
            override val value: T get() = selection.value?.let { source(it).value } ?: empty
            override val replayCache: List<T> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<T>): Nothing {
                selection
                    .flatMapLatest { it?.let(source) ?: flowOf(empty) }
                    .distinctUntilChanged()
                    .collect(collector)
                awaitCancellation()
            }
        }

    init {
        scope.launch {
            // One collector serializes reads; a mutation during a read leaves a newer revision queued.
            store.revision.collect { reconcile(store.list()) }
        }
    }

    @Synchronized
    fun connectionFor(serverId: String): RelayConnectionBundle? = entries[serverId]?.second

    /** Observe only the saved credentials, including replacement keys/tokens after re-pairing. */
    internal fun pairingStatus(record: PairedServer): Flow<ConnectionStatus?> =
        hosts.flatMapLatest {
            val bundle = synchronized(this) { entries[record.serverId]?.takeIf { it.first == record }?.second }
            flow {
                if (bundle == null) {
                    emit(null)
                } else {
                    var initial = true
                    bundle.coordinator.connectionStatus.collect { status ->
                        val retry = initial && (status.relay == RelayLinkStatus.DaemonAbsent || status.relay == RelayLinkStatus.Offline)
                        initial = false
                        if (retry) {
                            // Subscribe before retry; the previous attempt's terminal status is stale.
                            retryHost(record.serverId, bundle)
                            emit(null)
                        } else {
                            emit(status)
                        }
                    }
                }
            }
        }

    /** Exact host routing shares the removal lock; selection never participates. */
    @Synchronized
    fun requestDebugBundle(serverId: String): DebugBundleTransfer =
        connectionFor(serverId)?.coordinator?.requestDebugBundle()
            ?: DebugBundleTransfer.rejected(DebugBundleStatus.UNAVAILABLE)

    @Synchronized
    private fun reconcile(saved: List<PairedServerEntry>) {
        if (disposed) return
        val records = saved.associate { it.record.serverId to it.record }
        val removed = entries.filter { (id, held) -> records[id] != held.first }
        removed.forEach { (id, held) ->
            held.second.close()
            entries.remove(id)
        }
        saved.forEach { (record) ->
            if (record.serverId !in entries) {
                val bundle = factory.create(record)
                entries[record.serverId] = record to bundle
                if (foreground) bundle.supervisor.connect()
            }
        }
        hosts.value =
            saved.mapNotNull { (record, displayName) ->
                entries[record.serverId]?.second?.coordinator?.let { coordinator ->
                    coordinator.hostConversationConnection(record.serverId, displayName)
                }
            }
        selection.value =
            saved
                .lastOrNull()
                ?.record
                ?.serverId
                ?.let { entries[it]?.second }
        RelayLog.d { "event=relay_registry_reconciled count=${entries.size}" }
    }

    @Synchronized
    override fun connect() {
        if (disposed || foreground) return
        foreground = true
        entries.values.forEach { it.second.supervisor.connect() }
        RelayLog.d { "event=relay_registry_foreground count=${entries.size}" }
    }

    /** Resumable background teardown: coordinators, modal accumulators and cursors survive. */
    @Synchronized
    override fun close() {
        foreground = false
        entries.values.forEach { it.second.supervisor.close() }
        RelayLog.d { "event=relay_registry_background count=${entries.size}" }
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        foreground = false
        selection.value = null
        hosts.value = emptyList()
        entries.values.forEach { it.second.close() }
        entries.clear()
        scope.cancel()
        RelayLog.d { "event=relay_registry_disposed" }
    }

    override fun observe(): Flow<ConnectionState> =
        selection.flatMapLatest { it?.supervisor?.observe() ?: flowOf(ConnectionState.Connected) }

    override suspend fun retry() {
        // UI retry must not reopen a backgrounded or removed host.
        synchronized(this) {
            if (!foreground || disposed) return
            val bundle = selection.value ?: return
            // The supervisor's retry is nonblocking. Run it before releasing the lifecycle lock,
            // so a queued retry cannot land after close() and reopen a background socket.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                bundle.supervisor.retry()
            }
        }
    }

    /** Destination retries retain both exact identity and lifecycle ownership until retry is sent. */
    @Synchronized
    internal fun retryHost(
        serverId: String,
        expectedBundle: RelayConnectionBundle,
    ) {
        if (!foreground || disposed || entries[serverId]?.second !== expectedBundle) {
            RelayLog.d { "event=host_retry_rejected code=unavailable" }
            return
        }
        // Like compatibility retry, the supervisor's nonblocking call completes under this lock.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { expectedBundle.supervisor.retry() }
        RelayLog.d { "event=host_retry_requested" }
    }

    private fun active() = selection.value?.coordinator ?: error("no active connection")

    suspend fun answerModal(
        modalId: String,
        optionId: String,
    ) = active().answerModal(modalId, optionId)

    suspend fun cancelModal(modalId: String) = active().cancelModal(modalId)

    suspend fun interrupt(conversationId: String) = active().interrupt(conversationId)

    private companion object {
        val IDLE = ConnectionStatus(RelayLinkStatus.Idle, PyrycodeLinkStatus.Down)
    }
}

/** The registry's exact-host descriptor; events and synchronous repository reads share one owner. */
internal fun RelayRepositoryCoordinator.hostConversationConnection(
    serverId: String,
    displayName: String?,
): HostConversationConnection =
    HostConversationConnection(serverId, displayName, currentRepository, connectionStatus, liveSessionEvents, hostModals, questionBatches)
