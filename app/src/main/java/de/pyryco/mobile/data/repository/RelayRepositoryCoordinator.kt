package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ReplayCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 *
 * **Connect-time FCM push-token re-registration (#365).** Per live connection, once the fresh Noise
 * session reaches `Open`, it re-sends the stored push token via #359's
 * [RemoteConversationRepository.registerPushToken] so the daemon's wake target self-heals across app
 * restarts and drops (see [reregisterPushTokenOnOpen]). It threads the live [deviceName] into the repo
 * — closing #359's `device_name: ""` defer — and reads the token via [pushToken]. Both are defaulted
 * (`""` / `{ null }`), so the capability is dormant until `AppModule` wires the live values.
 *
 * **Two-part connection status (#392).** It also publishes [connectionStatus], the combined
 * `{relay, pyrycode}` model the Settings status line (#390) consumes. The relay leg is the
 * supervisor's [relayStatus] (socket-level); the pyrycode leg is *derived here* from the live pump's
 * lifecycle, reaching [PyrycodeLinkStatus.Connected] only once the Noise handshake completes
 * (pump `Open`) — never on bare socket-up, and not at all between connections. This is the layer that
 * owns the connection-scoped pump, so it is where the end-to-end-readiness signal is wired.
 *
 * @property deviceName the paired device's live name (`NoiseClientInfo.deviceName`, i.e. `Build.MODEL`),
 *   threaded into the repo as `register_push_token`'s `device_name`; `""` until AppModule supplies it.
 * @property pushToken a one-shot read of the persisted FCM token (`null` ⇒ no registration is sent).
 * @property relayStatus the relay-leg status (#391), fetched off the concrete supervisor like
 *   [connections]; zipped with the derived pyrycode leg into [connectionStatus].
 */
class RelayRepositoryCoordinator(
    private val connections: StateFlow<RelayTransport?>,
    private val relayStatus: StateFlow<RelayLinkStatus>,
    private val createPump: (RelayTransport) -> ManagedSessionPump,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceName: String = "",
    private val pushToken: suspend () -> String? = { null },
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)

    /**
     * The reconnect-spanning replay cursor (#412): the latest interactive structured-stream
     * [de.pyryco.mobile.data.network.Envelope.eventId] observed across all connections. It lives here,
     * not in the per-connection [RemoteConversationRepository] (rebuilt each reconnect), because it must
     * outlive connection churn and be readable at the next connection's `hello`-build — [teardownActive]
     * never touches it, so only a full [close] ends it. Threaded into each per-connection repo in
     * [onConnection] as the single recorder; #413 reads [ReplayCursor.latest] here to advertise the
     * resume point. `internal` (module-visible, read-only seam) so #413 and unit tests can read it
     * without a public API surface, mirroring [toPyrycodeLinkStatus]'s visibility.
     */
    internal val replayCursor: ReplayCursor = ReplayCursor()

    private val mutableRepository = MutableStateFlow<ConversationRepository?>(null)

    /** Observable mirror of the live pump (the pump half of [active]), or `null` between connections.
     *  Written in lock-step with [mutableRepository] inside the non-suspending [onConnection] /
     *  [teardownActive] critical section. Stays **private**: the pump is single-owner — only the
     *  *derived* readiness ([connectionStatus]) is exposed, never the pump reference. */
    private val activePumpFlow = MutableStateFlow<ManagedSessionPump?>(null)

    /** The pyrycode leg (#392): the live pump's [PumpState] mapped to [PyrycodeLinkStatus], tracking the
     *  current pump across reconnects ([flatMapLatest] cancels the prior pump's `state` collection). No
     *  pump ⇒ `null` ⇒ [PyrycodeLinkStatus.Down]. Private — no consumer wants the leg standalone. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pyrycodeStatus: Flow<PyrycodeLinkStatus> =
        activePumpFlow
            .flatMapLatest { pump -> pump?.state ?: flowOf(null) }
            .map { it.toPyrycodeLinkStatus() }

    /**
     * The live connection-scoped repository, or `null` until the Noise pump reaches [PumpState.Open]
     * (and `null` again between connections). Hot; consumed by the #352 facade.
     *
     * **Open-gated (#421 fix).** Exposing the repo at bare socket-up (when [mutableRepository] is set but
     * the pump is still `Handshaking`) made the conversation list never load. The facade (#352)
     * subscribes to [RemoteConversationRepository.observeConversations] the moment a non-null repo
     * appears, and that subscription fires a **one-shot** `list_conversations` via `pump.send` — which
     * returns false and is **dropped** while the pump is pre-`Open`, and is never re-issued after the
     * handshake completes. So the daemon never received the request, never replied with a `conversations`
     * snapshot, and the list screen spun on its loading state forever (the "New discussion" FAB, gated on
     * the first snapshot, never appeared). Gating the repo behind `Open` — mirroring [pyrycodeStatus] and
     * the connect-time push-token hook, which already await `Open` — means the facade subscribes, and the
     * list send fires, only once `pump.send` will succeed. The derivation is race-free: both inputs are
     * StateFlows mutated only on the single non-suspending [onConnection] / [teardownActive] path (plus
     * the pump's own `state`), so a teardown that nulls the inputs deterministically re-derives `null`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentRepository: StateFlow<ConversationRepository?> =
        combine(
            mutableRepository,
            activePumpFlow.flatMapLatest { pump -> pump?.state ?: flowOf(null) },
        ) { repo, pumpState -> if (pumpState is PumpState.Open) repo else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Observable mirror of the live concrete repository (the repo half of [active]), or `null` between
     *  connections. Written in lock-step with [mutableRepository] / [activePumpFlow] inside the
     *  non-suspending [onConnection] / [teardownActive] critical section. Stays **private**:
     *  [liveSessionEvents] (#385's typed events live on the concrete repo, not the interface) only needs
     *  this to re-subscribe across reconnects. */
    private val activeRemoteRepo = MutableStateFlow<RemoteConversationRepository?>(null)

    /** The decoded v2 structured live-session events (#385) for the current connection, surfaced off the
     *  connection-scoped concrete [RemoteConversationRepository] (#406). The events are not on the
     *  [ConversationRepository] interface, so — exactly as [pyrycodeStatus] reaches the concrete pump
     *  through [activePumpFlow] — this reaches the concrete repo through [activeRemoteRepo]. A cold
     *  `Flow` (events have no "current value", so no [stateIn]); [flatMapLatest] switches to the fresh
     *  repo's stream on each connection and cancels the prior, so the seam survives reconnection. Empty
     *  between connections. Kept generic (the full [LiveSessionEvent] stream, not an `isThinking`
     *  projection) so the tool-correlation (#387) and assistant-text (#337) consumers reuse it without
     *  re-plumbing this layer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveSessionEvents: Flow<LiveSessionEvent> =
        activeRemoteRepo.flatMapLatest { repo -> repo?.liveSessionEvents ?: emptyFlow() }

    /** The decoded v2 interactive **modal** lifecycle events (#437) for the current connection, surfaced
     *  off the connection-scoped concrete [RemoteConversationRepository] — a byte-for-byte mirror of the
     *  [liveSessionEvents] seam. Like it, modal events live on the concrete repo, not the
     *  [ConversationRepository] interface, so this reaches them through [activeRemoteRepo]. A cold `Flow`
     *  (events, `replay = 0`, no "current value" → no [stateIn]; folding "which modal is open" is the #445
     *  ViewModel projection's job); [flatMapLatest] switches to the fresh repo's stream on each connection
     *  and cancels the prior, so the seam survives reconnection. Empty between connections. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val modalEvents: Flow<ModalEvent> =
        activeRemoteRepo.flatMapLatest { repo -> repo?.modalEvents ?: emptyFlow() }

    /** The combined two-part status (#392) #390 consumes off this concrete singleton: the supervisor's
     *  relay leg zipped with the derived pyrycode leg. `Eagerly` so `.value` is correct at any glance;
     *  cancelled by [close] (which cancels [scope]). */
    val connectionStatus: StateFlow<ConnectionStatus> =
        combine(relayStatus, pyrycodeStatus) { relay, pyrycode -> ConnectionStatus(relay, pyrycode) }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus(relayStatus.value, PyrycodeLinkStatus.Down))

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
        activePumpFlow.value = pump
        // Retain the concrete repo: registerPushToken is not on the ConversationRepository interface, so
        // the hook must call it through this handle, not via the interface-typed currentRepository.
        // The capability supplier (#385) snapshots the live negotiated set lazily on each structured
        // envelope; `.value` is a non-suspending read, preserving this collector's cancellation
        // atomicity (:128). Once Open the set is connection-constant, and structured envelopes only
        // arrive post-Open, so every one sees the final negotiated capabilities.
        val repo =
            RemoteConversationRepository(
                pump,
                childScope,
                deviceName,
                negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() },
                replayCursor = replayCursor,
            )
        mutableRepository.value = repo
        activeRemoteRepo.value = repo
        // launch returns immediately; the suspending re-registration runs on the child scope, off the
        // non-suspending critical path of this collector (the :69 cancellation-atomicity invariant).
        childScope.launch { reregisterPushTokenOnOpen(pump, repo) }
    }

    /**
     * Connect-time FCM push-token re-registration (#365). Awaits the pump's first transition out of
     * [PumpState.Handshaking]; if it reached [PumpState.Open] and a token is stored, re-sends the
     * registration once via #359's sender. **Dormant** until a token is stored (Firebase #361 via
     * #364): a `null` token is a no-op. Fires **exactly once per connection** — each connection builds
     * a fresh pump + child scope + hook, and the state machine never revisits `Handshaking` (re-key
     * stays `Open`). Per the daemon's contract the server de-dupes the `(platform, token, device_name)`
     * triple, so a repeat is a cheap no-op that self-heals registry drift across restarts/drops.
     */
    private suspend fun reregisterPushTokenOnOpen(
        pump: ManagedSessionPump,
        repo: RemoteConversationRepository,
    ) {
        // first {} checks the current value too, so an already-Open pump fires without a missed edge.
        val terminal = pump.state.first { it is PumpState.Open || it is PumpState.Closed }
        if (terminal !is PumpState.Open) return // closed before Open → nothing to register
        val token = pushToken() ?: return // dormant until a token is stored
        try {
            repo.registerPushToken(token)
        } catch (e: CancellationException) {
            throw e // connection-drop cancellation must propagate, not be absorbed (structured concurrency)
        } catch (e: Exception) {
            // Swallowed (the token is never logged): the daemon re-registers on the next connect.
        }
    }

    /**
     * Tears down the active connection if any, returning [currentRepository] to `null`. Order matters:
     * cancel the child scope first (stops the repository's inbound collector), then close the pump
     * (wipes its keys + tears its own scope down — independently required, since the child scope does
     * not reach the pump's scope). Idempotent: a second call with no active connection is a no-op.
     */
    private fun teardownActive() {
        mutableRepository.value = null
        activeRemoteRepo.value = null
        activePumpFlow.value = null
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

    /**
     * Outbound modal **answer** passthrough (#451): reach the connection-scoped concrete
     * [RemoteConversationRepository.answerModal] through [activeRemoteRepo] — the **outbound mirror** of
     * the inbound [modalEvents] seam. Inbound is a `Flow` (a stream); an answer is a request/reply control
     * **call**, so this is a suspend method, not a flow.
     *
     * When no connection is active ([activeRemoteRepo] is `null`, between connections) it throws
     * [IllegalStateException]. When a connection exists but the pump is still pre-[PumpState.Open], the
     * concrete `answerModal` → `sendAndAwaitReply` → `pump.send` returns false → [IllegalStateException]
     * (the #438 precedent) — so this needs **only** the null-guard, not a redundant `Open` gate. A server
     * `error` propagates as [de.pyryco.mobile.data.network.RelayErrorException] unchanged. Adds **no log**:
     * the `modalId`/`optionId` may name a sensitive command/path (never-log contract).
     */
    suspend fun answerModal(
        modalId: String,
        optionId: String,
    ) {
        val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection")
        repo.answerModal(modalId, optionId)
    }

    /**
     * Outbound modal **cancel** passthrough (#451): the [answerModal] mirror for
     * [RemoteConversationRepository.cancelModal]. Same null-guard-only posture and never-log contract.
     */
    suspend fun cancelModal(modalId: String) {
        val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection")
        repo.cancelModal(modalId)
    }

    /**
     * Outbound `interrupt` passthrough (#458): the [cancelModal] mirror for
     * [RemoteConversationRepository.interrupt]. Same null-guard-only posture and never-log contract;
     * fire-and-forget (no reply awaited).
     */
    suspend fun interrupt() {
        val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection")
        repo.interrupt()
    }

    private class Connection(
        val pump: ManagedSessionPump,
        val scope: CoroutineScope,
    )
}

/**
 * Derives the pyrycode-leg readiness [PyrycodeLinkStatus] from the live pump's [PumpState] (or `null`
 * when no pump is live, i.e. between connections). [PyrycodeLinkStatus.Connected] is reached **only**
 * from [PumpState.Open] — the handshake-completion state — never on bare socket-up. Total over the
 * sealed [PumpState] plus the no-pump `null`. `Open.connId` and `Closed.cause` are both **discarded**,
 * holding the no-log contract: no relay/crypto-derived string flows into the status surface.
 */
internal fun PumpState?.toPyrycodeLinkStatus(): PyrycodeLinkStatus =
    when (this) {
        null -> PyrycodeLinkStatus.Down
        PumpState.Handshaking -> PyrycodeLinkStatus.Handshaking
        is PumpState.Open -> PyrycodeLinkStatus.Connected
        is PumpState.Closed -> PyrycodeLinkStatus.Down
    }
