package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ConversationCache
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

/**
 * App-owned cache: collection continues without subscribers until [dispose].
 *
 * A host's rows otherwise live only while its repository does, so the teardown the lifecycle driver
 * performs on background — and the process death that may follow — draws every saved host empty. With
 * a [ConversationCache] present (#796) each accepted live list is written to it and a new host entry
 * is seeded from it, so previously loaded rows survive both. The seed never touches
 * [HostConversationSnapshot.connectionStatus]: a restored list must not read as a connected one. The
 * demo path passes no cache and is unaffected.
 */
class HostConversationSource internal constructor(
    private val connections: StateFlow<List<HostConversationConnection>>,
    private val lookup: (String) -> ConversationRepository?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cache: ConversationCache? = null,
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
                cache?.let { store ->
                    scope.launch(entry.job) {
                        // A read never throws for an unreadable or absent document; it yields empty, and an
                        // empty result must not be read as "the daemon has no conversations". Only a live
                        // list may empty a host, so nothing is published when there is nothing cached.
                        val restored = store.readConversations(connection.serverId)
                        if (restored.isNotEmpty()) update(entry, restore = true) { it.withRows(restored) }
                    }
                }
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

    /** Reports whether the snapshot was actually transformed, so only an accepted list is cached. */
    @Synchronized
    private fun update(
        entry: Held,
        repository: ConversationRepository? = null,
        restore: Boolean = false,
        transform: (HostConversationSnapshot) -> HostConversationSnapshot,
    ): Boolean {
        val connection = entry.connection
        if (disposed ||
            held[connection.serverId] !== entry ||
            connections.value.none { it.serverId == connection.serverId && it.repositories === connection.repositories } ||
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

        /** Set once a live list is accepted; a cache restore landing afterwards must not replace it. */
        var live = false
    }

    companion object {
        const val DEMO_SERVER_ID = "demo"

        /** [cache] trails [dispatcher] so no existing positional call site moves; null disables both sides. */
        fun relay(
            registry: RelayConnectionRegistry,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            cache: ConversationCache? = null,
        ) = HostConversationSource(registry.hostConnections, { serverId ->
            registry.connectionFor(serverId)?.coordinator?.liveRepository()
        }, dispatcher, cache)

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
