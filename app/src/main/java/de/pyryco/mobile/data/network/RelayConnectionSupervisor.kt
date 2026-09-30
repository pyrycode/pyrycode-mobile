package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.repository.ConnectionStateSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The narrow lifecycle-control seam the process-lifecycle driver (#302) drives. Two methods, no data
 * parameters — a deliberately minimal surface so the driver (and a future FCM push-wake caller) can
 * only start or stop the supervision loop, never inject relay- or push-controlled data through it.
 *
 * Implemented by [RelayConnectionSupervisor]; declared here in the portable `data/network` package so
 * the platform `lifecycle` package depends inward on this contract, never the reverse.
 */
interface RelayConnectionController {
    /** Idempotent supervision-loop start (app foreground / push-wake). */
    fun connect()

    /** Full teardown → idle [RelayLinkStatus.Idle] (app background) — an intentional disconnect; the
     *  derived banner state stays [ConnectionState.Connected] (hidden). */
    fun close()
}

/**
 * The Phase 4 reconnect-policy layer on top of the single-connection relay WS transport (#306). It
 * owns the loop that re-dials on drop with capped-exponential backoff and maps live socket state onto
 * the existing [ConnectionStateSource] surface, so the connection banner reflects real connectivity.
 *
 * It drives a fresh [RelayTransport] per dial (via [RelayTransportFactory]) and observes only that
 * transport's `events` (`Up` / `Down`) — never its single-consumer `inbound` frame stream, which
 * belongs to the sibling Noise session pump (#309). The live instance is published on
 * [currentConnection] so a layer-up coordinator (#302 / future wiring) can hand the same connection's
 * `inbound` to a fresh pump per connection; coordinating the two over one instance lives there, not
 * here.
 *
 * `connect()` / `close()` are the lifecycle seam for #302 (process-lifecycle close/reconnect); nothing
 * in this ticket calls them, so the bound instance sits dormant at [RelayLinkStatus.Idle] — which
 * derives to [ConnectionState.Connected] (banner hidden) — until #302 drives the first `connect()`.
 *
 * `Connected` here means **socket-open** (transport `Up`), not Noise-session-open. Gating on #309's
 * handshake-completion signal would couple this layer to #309 (which has no blocker relationship) and
 * is out of scope; a future ticket may refine `Connected` to mean end-to-end readiness if wanted.
 *
 * Emits no logs: the outward signal is the typed [RelayLinkStatus] relay leg (and its derived 4-case
 * [ConnectionState]) — no strings beyond `secondsRemaining`, `DaemonAbsent` / `PairingRejected`
 * are static objects carrying no relay-supplied text, and `UpdateRequired`'s minimum comes only from the
 * sealed daemon error, validated in [recordClientMinimum]. `PairedServer`, the relay URL, the transport,
 * the minimum, and `Down`'s code/reason/cause are never logged; the 4404 / 4401 / 4426 / 4412 branches
 * read `Down.code` only to compare it, never to log it, and no branch reads the close reason.
 */
class RelayConnectionSupervisor(
    private val transportFactory: RelayTransportFactory,
    private val pairedServerStore: PairedServerStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) : ConnectionStateSource,
    RelayConnectionController {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = MutableStateFlow<RelayLinkStatus>(RelayLinkStatus.Idle)

    // CONFLATED: a burst of retry() calls collapses to one pending wake-up.
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    private val liveConnection = MutableStateFlow<RelayTransport?>(null)

    /** The live transport instance (set on `Up`, cleared on `Down` / [close]) or `null`. The seam
     *  the #302/future coordinator uses to hand this connection's `inbound` to a fresh #309 pump. */
    val currentConnection: StateFlow<RelayTransport?> = liveConnection.asStateFlow()

    /** The relay-leg status (#391): the single hot source of truth, branching the relay's `4404`
     *  close into [RelayLinkStatus.DaemonAbsent]. #392 zips this leg with the pyrycode-session leg
     *  into the combined model; it is fetched off the concrete supervisor like [currentConnection]. */
    val relayStatus: StateFlow<RelayLinkStatus> = state.asStateFlow()

    private var loopJob: Job? = null

    // The current dial's transport and the app minimum its sealed `client.update_required` error named
    // (#1008). The error is decrypted by the pump while the `4412` close arrives here, in either order, so
    // the minimum is latched per dial and read at the halt. Guarded by [dialLock], as is every write of
    // [RelayLinkStatus.UpdateRequired] to [state].
    private val dialLock = Any()
    private var dialTransport: RelayTransport? = null
    private var dialMinimum: String? = null

    // Legacy single-signal surface (#197 banner): the 4-case [ConnectionState] derived per-collector
    // from the relay leg, so every existing consumer is untouched (DaemonAbsent -> nearest, Offline).
    override fun observe(): Flow<ConnectionState> = state.map { it.toConnectionState() }

    /** Starts the supervision loop. Idempotent — a repeated call (e.g. an over-eager #302) cannot
     *  spawn a second loop, which would mean two concurrent dials on one transport surface. */
    @Synchronized
    override fun connect() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    /** Stops the loop, tears down the live socket, and goes [RelayLinkStatus.Idle] — an intentional
     *  disconnect, not an error, so the derived banner state stays [ConnectionState.Connected] (hidden)
     *  while the Settings relay leg reads honestly not-connected. */
    @Synchronized
    override fun close() {
        loopJob?.cancel()
        loopJob = null
        liveConnection.value?.close()
        liveConnection.value = null
        state.value = RelayLinkStatus.Idle
    }

    /** Forces an immediate reconnect, collapsing any pending backoff wait. Starts the loop if idle
     *  (a tap-to-retry arriving while unpaired re-checks paired state and stays idle). Non-blocking;
     *  never throws — failures surface only as [ConnectionState] transitions. */
    override suspend fun retry() {
        connect()
        retrySignal.trySend(Unit)
    }

    private suspend fun CoroutineScope.runLoop() {
        var attempt = 0 // consecutive failures since the last ≥60 s-stable connection
        while (isActive) {
            // Re-read the paired record every dial (#489): a re-pair to another server is picked up on
            // the next dial rather than dialing the record captured once at loop start. A null read
            // (unpaired, or an undecryptable record) goes Idle — dialing nothing, so the banner stays
            // hidden (idle derives to Connected) yet Settings reads not-connected — and ends the loop; a
            // later connect()/retry() starts a fresh loop that re-reads the store.
            val paired = pairedServerStore.load()
            if (paired == null) {
                state.value = RelayLinkStatus.Idle
                return
            }
            state.value = RelayLinkStatus.Connecting
            val transport = transportFactory.create(paired)
            synchronized(dialLock) {
                dialTransport = transport
                dialMinimum = null
            }
            var sawUp = false
            var daemonAbsent = false
            var pairingRejected = false
            var updateRequired = false
            val stableReached = AtomicBoolean(false)
            var stabilityTimer: Job? = null
            try {
                transport.connect()
                transport.events.collect { event ->
                    when (event) {
                        TransportEvent.Up -> {
                            sawUp = true
                            liveConnection.value = transport
                            state.value = RelayLinkStatus.Connected
                            stabilityTimer =
                                launch {
                                    delay(STABLE_THRESHOLD_MS)
                                    stableReached.set(true)
                                }
                        }
                        is TransportEvent.Down -> {
                            // #308 seam: a 4404 "no server" close (relay reachable, no daemon
                            // registered) branches to DaemonAbsent; a 4401 invalid-token or 4426
                            // handshake-failed close is a rejected pairing that halts the redial
                            // (#841), and a 4412 app-too-old close halts it too (#1008); every other
                            // code and a null dial failure stay on the uniform retry path. Read-only:
                            // the code is compared against the constants, never logged (no-log contract).
                            daemonAbsent = event.code == RELAY_NO_DAEMON_CLOSE
                            pairingRejected =
                                event.code == RELAY_TOKEN_REJECTED_CLOSE ||
                                event.code == HANDSHAKE_FAILED_CLOSE
                            updateRequired = event.code == CLIENT_UPDATE_REQUIRED_CLOSE
                        }
                    }
                    // events completes after the single terminal Down (#306), ending collect.
                }
            } finally {
                // Runs on a normal Down and on cancellation (close()): always release the socket,
                // including a transport cancelled mid-dial before it ever reached Up.
                stabilityTimer?.cancel() // cancel before reading the flag (no late write)
                // Compare-and-clear on identity (#496): a cancelled loop's finally runs OUTSIDE the
                // @Synchronized close()/connect() lock, so it must clear liveConnection only when it
                // still holds *this* loop's transport — never a value a newer loop already published.
                liveConnection.update { current -> if (current === transport) null else current }
                transport.close()
            }
            if (sawUp && stableReached.get()) attempt = 0
            attempt += 1
            when {
                pairingRejected -> haltUntilRetry { RelayLinkStatus.PairingRejected }
                updateRequired -> haltUntilRetry { RelayLinkStatus.UpdateRequired(dialMinimum) }
                else -> backoff(attempt, daemonAbsent)
            }
        }
    }

    private suspend fun backoff(
        attempt: Int,
        daemonAbsent: Boolean,
    ) {
        drainStaleRetrySignals()
        val base = backoffBaseSeconds(attempt)
        val intervalMs = jitteredBackoffMs(attempt, random)
        if (daemonAbsent) {
            // Relay reachable, no daemon: a steady, distinct DaemonAbsent (no per-second countdown) on
            // the SAME backoff schedule as any other drop, so the leg flips off the instant a daemon
            // registers and the next dial succeeds. collapsibleWait keeps tap-to-retry working here.
            state.value = RelayLinkStatus.DaemonAbsent
            collapsibleWait(intervalMs)
            return
        }
        if (base < CAP_SECONDS) {
            // Sub-cap: a per-second Reconnecting countdown, each second collapsible by retry().
            var remainingMs = intervalMs
            while (remainingMs > 0) {
                state.value = RelayLinkStatus.Reconnecting(ceil(remainingMs / 1000.0).toInt())
                val step = minOf(1000L, remainingMs)
                if (collapsibleWait(step)) return // retry collapsed the wait -> reconnect now
                remainingMs -= step
            }
        } else {
            // At the cap: sustained unavailability -> Offline ("tap to retry"); keep redialling.
            state.value = RelayLinkStatus.Offline
            collapsibleWait(intervalMs)
        }
    }

    /** A rejected pairing (#841) or app build (#1008): a redial cannot succeed, so publish [halted] and
     *  wait with no timeout until [retry] asks for one more dial. [close] cancels the wait; the next
     *  foreground [connect] then starts a fresh loop. Consumes no jitter, so later backoffs keep their
     *  schedule. [halted] is read under [dialLock] so a minimum recorded concurrently is never lost. */
    private suspend fun haltUntilRetry(halted: () -> RelayLinkStatus) {
        drainStaleRetrySignals()
        synchronized(dialLock) { state.value = halted() }
        retrySignal.receive()
    }

    /**
     * Records the app minimum [transport]'s host named in its sealed `client.update_required` error
     * (#1008), for the halt that host's `4412` close causes. A value that is not three bounded decimal
     * parts is dropped, as is one from a transport that is no longer the current dial. When the halt
     * already happened, the state gains the minimum; this never halts by itself. No log.
     */
    internal fun recordClientMinimum(
        transport: RelayTransport,
        minClientVersion: String,
    ) {
        val valid = validMinClientVersion(minClientVersion) ?: return
        synchronized(dialLock) {
            if (transport !== dialTransport) return
            dialMinimum = valid
            if (state.value is RelayLinkStatus.UpdateRequired) state.value = RelayLinkStatus.UpdateRequired(valid)
        }
    }

    /** Drains any stale retry signal buffered while no wait was in progress (#498): a retry() issued
     *  during a healthy connection (loop inside events.collect) leaves a Unit in the CONFLATED
     *  retrySignal with no receiver. Called once per drop, before the first wait, so it cannot
     *  pre-collapse this drop's wait; a retry() arriving *during* the wait lands after it. */
    private fun drainStaleRetrySignals() {
        while (retrySignal.tryReceive().isSuccess) { /* discard a stale pre-drop signal */ }
    }

    /** Waits up to [ms], returning early when [retry] collapses the wait.
     *  @return true if a retry arrived (reconnect now), false on full elapse. */
    private suspend fun collapsibleWait(ms: Long): Boolean = withTimeoutOrNull(ms) { retrySignal.receive() } != null

    private companion object {
        const val CAP_SECONDS = 30
        const val STABLE_THRESHOLD_MS = 60_000L

        /** The relay's "no server" WS close: relay reachable, but no daemon registered behind it. */
        const val RELAY_NO_DAEMON_CLOSE = 4404

        /** The daemon's close for an invalid, expired or revoked device token. */
        const val RELAY_TOKEN_REJECTED_CLOSE = 4401

        /** The daemon's close for a failed handshake: the saved server static key is stale. */
        const val HANDSHAKE_FAILED_CLOSE = 4426

        /** The daemon's close for an app build below its configured minimum (pyrycode#2576). */
        const val CLIENT_UPDATE_REQUIRED_CLOSE = 4412
    }
}

/**
 * Derives the legacy single-signal [ConnectionState] from the relay leg [RelayLinkStatus]: identity for
 * the four shared cases; [RelayLinkStatus.Idle] (deliberately not dialing) maps to
 * [ConnectionState.Connected] so the banner stays hidden while idle; [RelayLinkStatus.DaemonAbsent]
 * collapses to [ConnectionState.Offline] (the relay is up but unusable end-to-end) until #392's combined
 * banner gives DaemonAbsent its own copy.
 */
internal fun RelayLinkStatus.toConnectionState(): ConnectionState =
    when (this) {
        RelayLinkStatus.Connected -> ConnectionState.Connected
        RelayLinkStatus.Idle -> ConnectionState.Connected
        RelayLinkStatus.Connecting -> ConnectionState.Connecting
        is RelayLinkStatus.Reconnecting -> ConnectionState.Reconnecting(secondsRemaining)
        RelayLinkStatus.DaemonAbsent -> ConnectionState.Offline
        RelayLinkStatus.PairingRejected -> ConnectionState.Offline
        is RelayLinkStatus.UpdateRequired -> ConnectionState.Offline
        RelayLinkStatus.Offline -> ConnectionState.Offline
    }

/**
 * The thread's single-signal [ConnectionState] from both legs (#1318): [ConnectionState.Connected] only
 * once the socket is up **and** the Noise handshake has finished, matching desktop's `handshake-complete`.
 * A relay leg up with the pyrycode leg not yet connected reads [ConnectionState.Connecting]; every other
 * relay value keeps [RelayLinkStatus.toConnectionState], so Idle stays Connected and the Reconnecting
 * countdown survives.
 */
internal fun ConnectionStatus.toConnectionState(): ConnectionState =
    when {
        relay != RelayLinkStatus.Connected -> relay.toConnectionState()
        pyrycode == PyrycodeLinkStatus.Connected -> ConnectionState.Connected
        else -> ConnectionState.Connecting
    }

/** Backoff base seconds for [attempt] (1-based): 1, 2, 4, 8, 16, then 30 (cap) from attempt 6. */
internal fun backoffBaseSeconds(attempt: Int): Int = if (attempt >= 6) 30 else (1 shl (attempt - 1))

/** Backoff interval for [attempt] with ±20% jitter — exactly one [Random.nextDouble] per interval,
 *  so a seeded [random] replays deterministically under the test virtual clock. */
internal fun jitteredBackoffMs(
    attempt: Int,
    random: Random,
): Long {
    val base = backoffBaseSeconds(attempt)
    val factor = 0.8 + random.nextDouble() * 0.4 // [0.8, 1.2)
    return (base * 1000L * factor).toLong()
}
