package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionState
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

    /** Full teardown → idle [ConnectionState.Connected] (app background) — an intentional disconnect. */
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
 * in this ticket calls them, so the bound instance sits dormant at [ConnectionState.Connected] (banner
 * hidden) until #302 drives the first `connect()`.
 *
 * `Connected` here means **socket-open** (transport `Up`), not Noise-session-open. Gating on #309's
 * handshake-completion signal would couple this layer to #309 (which has no blocker relationship) and
 * is out of scope; a future ticket may refine `Connected` to mean end-to-end readiness if wanted.
 *
 * Emits no logs: the outward signal is the typed [RelayLinkStatus] relay leg (and its derived 4-case
 * [ConnectionState]) — no strings beyond `secondsRemaining`, and `DaemonAbsent` is a static object
 * carrying no relay-supplied text. `PairedServer`, the relay URL, the transport, and `Down`'s
 * code/reason/cause are never logged; the 4404 branch reads `Down.code` only to compare it, never to
 * log it.
 */
class RelayConnectionSupervisor(
    private val transportFactory: RelayTransportFactory,
    private val pairedServerStore: PairedServerStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) : ConnectionStateSource,
    RelayConnectionController {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = MutableStateFlow<RelayLinkStatus>(RelayLinkStatus.Connected)

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

    /** Stops the loop, tears down the live socket, and goes idle (back to [ConnectionState.Connected]
     *  — an intentional disconnect, not an error, so the banner stays hidden). */
    @Synchronized
    override fun close() {
        loopJob?.cancel()
        loopJob = null
        liveConnection.value?.close()
        liveConnection.value = null
        state.value = RelayLinkStatus.Connected
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
            // (unpaired, or an undecryptable record) idles at Connected so the banner stays hidden and
            // ends the loop — a later connect()/retry() starts a fresh loop that re-reads the store.
            val paired = pairedServerStore.load()
            if (paired == null) {
                state.value = RelayLinkStatus.Connected
                return
            }
            state.value = RelayLinkStatus.Connecting
            val transport = transportFactory.create(paired)
            var sawUp = false
            var daemonAbsent = false
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
                            // registered) branches to DaemonAbsent; every other code — incl. 4401
                            // auth-reject and a null dial failure — stays on the uniform retry path
                            // (4401's halt/re-pair branch is a future ticket). Read-only: the code is
                            // compared against the constant, never logged (no-log contract).
                            daemonAbsent = event.code == RELAY_NO_DAEMON_CLOSE
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
            backoff(attempt, daemonAbsent)
        }
    }

    private suspend fun backoff(
        attempt: Int,
        daemonAbsent: Boolean,
    ) {
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

    /** Waits up to [ms], returning early when [retry] collapses the wait.
     *  @return true if a retry arrived (reconnect now), false on full elapse. */
    private suspend fun collapsibleWait(ms: Long): Boolean = withTimeoutOrNull(ms) { retrySignal.receive() } != null

    private companion object {
        const val CAP_SECONDS = 30
        const val STABLE_THRESHOLD_MS = 60_000L

        /** The relay's "no server" WS close: relay reachable, but no daemon registered behind it. */
        const val RELAY_NO_DAEMON_CLOSE = 4404
    }
}

/**
 * Derives the legacy single-signal [ConnectionState] from the relay leg [RelayLinkStatus]: identity for
 * the four shared cases; [RelayLinkStatus.DaemonAbsent] collapses to [ConnectionState.Offline] (the
 * relay is up but unusable end-to-end) until #392's combined banner gives DaemonAbsent its own copy.
 */
internal fun RelayLinkStatus.toConnectionState(): ConnectionState =
    when (this) {
        RelayLinkStatus.Connected -> ConnectionState.Connected
        RelayLinkStatus.Connecting -> ConnectionState.Connecting
        is RelayLinkStatus.Reconnecting -> ConnectionState.Reconnecting(secondsRemaining)
        RelayLinkStatus.DaemonAbsent -> ConnectionState.Offline
        RelayLinkStatus.Offline -> ConnectionState.Offline
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
