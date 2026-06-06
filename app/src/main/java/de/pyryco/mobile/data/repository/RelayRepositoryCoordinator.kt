package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.RelayTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stands up the per-connection chain that turns a live relay socket into a working remote
 * conversation repository (#351). It observes the supervisor's [connections] (its
 * `currentConnection: StateFlow<RelayTransport?>`, set on socket `Up`, cleared to `null` on
 * `Down`/`close` by #307) and, for each live transport, starts a fresh Noise [ManagedSessionPump]
 * over it and constructs a [RemoteConversationRepository] (#312/#313/#329) against that pump on a
 * connection-scoped child scope. The live repository — or `null` between connections — is published
 * on [currentRepository], the seam the stable-reference facade (#352) consumes so ViewModels never
 * re-resolve across connection churn.
 *
 * **Single-use pump per connection.** A `Noise_IK` session's ephemeral keys and AEAD nonce counter
 * are per-handshake, so a reconnect must build a *fresh* pump over the new transport — never reuse
 * one across connections (reusing nonces under one key breaks AEAD confidentiality). Each live
 * transport therefore gets a brand-new pump, child scope, and repository; nothing (projection state,
 * in-flight requests, the pump) is shared with the previous connection.
 *
 * **Key-wipe invariant.** Every transition that drops a pump reference first calls [ManagedSessionPump.close]
 * — the load-bearing security call: it wipes the session transport ciphers and the device-static copy.
 * Cancelling the child scope stops the repository's inbound collector but does **not** reach the pump's
 * own scope; `close()` is what tears the pump down. There are exactly three such transitions and all
 * three close the pump: a new connection replacing an old one, a `null` emission, and [close].
 *
 * It never collects [RelayTransport.inbound]/`events` — those are single-consumer streams owned by
 * the pump and the supervisor respectively; the coordinator only moves the transport reference into
 * [createPump]. It emits **no logs**: the only outward signal is [currentRepository].
 */
class RelayRepositoryCoordinator(
    private val connections: StateFlow<RelayTransport?>,
    private val createPump: (RelayTransport) -> ManagedSessionPump,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)

    private val mutableRepository = MutableStateFlow<ConversationRepository?>(null)

    /** The live connection-scoped repository, or `null` between connections. Hot; consumed by #352. */
    val currentRepository: StateFlow<ConversationRepository?> = mutableRepository.asStateFlow()

    private val started = AtomicBoolean(false)

    /** The pump + child scope of the connection currently being served; `null` between connections.
     *  Written only by the single, non-suspending [onConnection] collector (and the idempotent [close]). */
    private var active: Connection? = null

    /**
     * Launches the single [connections] collector. Idempotent — a repeated call (e.g. an over-eager
     * double registration) cannot spawn a second collector, which would mean two pumps per connection.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { connections.collect { onConnection(it) } }
    }

    /**
     * Handles one [connections] emission. **Non-suspending by design:** cooperative cancellation only
     * acts at a suspension point, so keeping the whole build/teardown body free of suspension means a
     * single emission is handled atomically w.r.t. cancellation — there is no window in which a racing
     * [close] could cancel the collector between [ManagedSessionPump.start] and retaining the pump,
     * which would leak a started-but-unclosed pump. Do not introduce a suspension here.
     */
    private fun onConnection(transport: RelayTransport?) {
        teardownActive()
        if (transport == null) return
        val childScope = CoroutineScope(SupervisorJob(job) + dispatcher)
        val pump = createPump(transport).also { it.start() }
        active = Connection(pump, childScope)
        mutableRepository.value = RemoteConversationRepository(pump, childScope)
    }

    /**
     * Tears down the active connection if any, returning [currentRepository] to `null`. Order matters:
     * cancel the child scope first (stops the repository's inbound collector), then close the pump
     * (wipes its keys + tears its own scope down — independently required, since the child scope does
     * not reach the pump's scope). Idempotent: a second call with no active connection is a no-op.
     */
    private fun teardownActive() {
        mutableRepository.value = null
        val current = active ?: return
        active = null
        current.scope.cancel()
        current.pump.close()
    }

    /** Tears down the active connection (wiping pump keys) and cancels the coordinator scope, ending
     *  the collector. Idempotent. */
    fun close() {
        teardownActive()
        scope.cancel()
    }

    private class Connection(
        val pump: ManagedSessionPump,
        val scope: CoroutineScope,
    )
}
