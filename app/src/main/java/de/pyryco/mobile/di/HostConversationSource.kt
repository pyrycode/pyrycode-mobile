package de.pyryco.mobile.di

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Host identity surrounds unchanged, host-local conversation records. Contains no pairing secrets. */
data class HostConversationSnapshot(
    val serverId: String,
    val displayName: String?,
    val connectionStatus: ConnectionStatus,
    val channels: List<Conversation> = emptyList(),
    val chats: List<Conversation> = emptyList(),
)

/** Internal presentation and stream descriptor; repository-stream identity is the bundle generation. */
internal data class HostConversationConnection(
    val serverId: String,
    val displayName: String?,
    val repositories: StateFlow<ConversationRepository?>,
    val status: StateFlow<ConnectionStatus>,
)

/** App-owned cache: collection continues without subscribers until [dispose]. */
class HostConversationSource internal constructor(
    private val connections: StateFlow<List<HostConversationConnection>>,
    private val lookup: (String) -> ConversationRepository?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val held = mutableMapOf<String, Held>()
    private val state = MutableStateFlow<List<HostConversationSnapshot>>(emptyList())
    val snapshots = state.asStateFlow()
    private var disposed = false

    init {
        scope.launch { connections.collect { reconcile(it) } }
    }

    /** Current availability only; an operation may still lose its connection after this lookup. */
    @Synchronized
    fun repositoryFor(serverId: String): ConversationRepository? = if (disposed) null else lookup(serverId)

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
                scope.launch(entry.job) {
                    connection.repositories.collectLatest { repository ->
                        repository
                            ?.observeConversations(ConversationFilter.All)
                            ?.catch { RelayLog.d { "event=host_snapshot_list_failed" } }
                            ?.collect { rows ->
                                update(entry, repository) {
                                    it.copy(
                                        channels = rows.filter { row -> row.isPromoted && !row.archived },
                                        chats = rows.filter { row -> !row.isPromoted && !row.archived },
                                    )
                                }
                            }
                    }
                }
            }
        }
        publish()
        RelayLog.d { "event=host_snapshots_reconciled count=${held.size}" }
    }

    @Synchronized
    private fun update(
        entry: Held,
        repository: ConversationRepository? = null,
        transform: (HostConversationSnapshot) -> HostConversationSnapshot,
    ) {
        val connection = entry.connection
        if (disposed ||
            held[connection.serverId] !== entry ||
            connections.value.none { it.serverId == connection.serverId && it.repositories === connection.repositories } ||
            (repository != null && connection.repositories.value !== repository)
        ) {
            return
        }
        entry.snapshot = transform(entry.snapshot)
        publish()
    }

    private fun publish() {
        state.value = connections.value.mapNotNull { held[it.serverId]?.snapshot }
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        held.clear()
        state.value = emptyList()
        RelayLog.d { "event=host_snapshots_disposed" }
    }

    private class Held(
        val connection: HostConversationConnection,
        val job: Job,
    ) {
        var snapshot = HostConversationSnapshot(connection.serverId, connection.displayName, connection.status.value)
    }

    companion object {
        const val DEMO_SERVER_ID = "demo"

        fun relay(
            registry: RelayConnectionRegistry,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ) = HostConversationSource(registry.hostConnections, { serverId ->
            registry.connectionFor(serverId)?.let { bundle ->
                if (bundle.supervisor.currentConnection.value == null) null else bundle.coordinator.currentRepository.value
            }
        }, dispatcher)

        fun demo(
            repository: ConversationRepository,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): HostConversationSource {
            val demo =
                HostConversationConnection(
                    DEMO_SERVER_ID,
                    "Demo",
                    MutableStateFlow(repository),
                    MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                )
            return HostConversationSource(MutableStateFlow(listOf(demo)), { if (it == DEMO_SERVER_ID) repository else null }, dispatcher)
        }
    }
}
