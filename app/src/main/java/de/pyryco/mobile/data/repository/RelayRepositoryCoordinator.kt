package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalAction
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.batchFor
import de.pyryco.mobile.data.model.latestOutstanding
import de.pyryco.mobile.data.model.reconnected
import de.pyryco.mobile.data.model.reduce
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
 * — closing #359's `device_name: ""` defer — and observes the token via [pushTokens], so a rotation
 * (#361) re-registers on an already open connection. Both are defaulted (`""` / `flowOf(null)`), so the
 * capability is dormant until `AppModule` wires the live values.
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
 * @property pushTokens the persisted FCM token and its later rotations (`null` ⇒ no registration is sent).
 * @property relayStatus the relay-leg status (#391), fetched off the concrete supervisor like
 *   [connections]; zipped with the derived pyrycode leg into [connectionStatus].
 */
class RelayRepositoryCoordinator(
    private val connections: StateFlow<RelayTransport?>,
    private val relayStatus: StateFlow<RelayLinkStatus>,
    private val createPump: (RelayTransport) -> ManagedSessionPump,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceName: String = "",
    private val pushTokens: Flow<String?> = flowOf(null),
    now: () -> Instant = Clock.System::now,
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

    /**
     * Which background tasks this host has finished (#677). Lives here for [replayCursor]'s reason: each
     * connection gets a fresh repository, but the daemon re-sends its retained roster on every reconnect, so
     * the finished mark must outlive the connection or a finished task comes back as live on every return
     * to the foreground. [teardownActive] never touches it. Threaded into each repository in [onConnection].
     */
    internal val finishedBackgroundTasks: FinishedBackgroundTasks = FinishedBackgroundTasks()

    /**
     * The five readings this host pushes and the phone never asks for again (#1317), held for the host's
     * pairing as desktop does, so a return to the foreground does not blank them until the next turn ends.
     * Lives here for [replayCursor]'s reason, and [teardownActive] never touches it. Threaded into each
     * repository in [onConnection]; the thread reads it while disconnected. [close] drops it, which is the
     * pairing-scoped clear: registry reconcile closes the coordinator on unpair and re-pair. Built with [now]
     * so a held usage limit still lapses at its reset time.
     */
    internal val hostReadings: HostReadings = HostReadings(now)

    /**
     * The single connection-state source of truth: the pump + child scope + concrete repository of the
     * connection currently being served, or `null` between connections. **Every** connection-derived seam
     * ([currentRepository], [pyrycodeStatus], [liveSessionEvents], [modalEvents]/[hostModals],
     * [connectionStatus]) is a projection of this one [StateFlow], and the outbound passthroughs
     * ([answerModal]/[cancelModal]/[interrupt]) read its `.value` — so repo and pump-state can never be
     * paired across two different connections (the #493 fix; see [currentRepository]). Written only on the
     * single, non-suspending [onConnection] / [teardownActive] critical section (plus the idempotent
     * [close]); the pump/repo references stay **inside** it (single-owner) — only *derived* signals are
     * ever exposed, never the pump reference itself.
     */
    private val activeConnection = MutableStateFlow<Connection?>(null)

    /** The pyrycode leg (#392): the live pump's [PumpState] mapped to [PyrycodeLinkStatus], tracking the
     *  current pump across reconnects ([flatMapLatest] over [activeConnection] cancels the prior pump's
     *  `state` collection). No connection ⇒ `null` ⇒ [PyrycodeLinkStatus.Down]. Private — no consumer
     *  wants the leg standalone. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pyrycodeStatus: Flow<PyrycodeLinkStatus> =
        activeConnection
            .flatMapLatest { conn -> conn?.pump?.state ?: flowOf(null) }
            .map { it.toPyrycodeLinkStatus() }

    /**
     * The live connection-scoped repository, or `null` until the connection's Noise pump reaches
     * [PumpState.Open] (and `null` again between connections). Hot; consumed by the #352 facade.
     *
     * **Open-gated (#421 fix).** Exposing the repo at bare socket-up (repo set but the pump still
     * `Handshaking`) made the conversation list never load. The facade (#352) subscribes to
     * [RemoteConversationRepository.observeConversations] the moment a non-null repo appears, and that
     * subscription fires a **one-shot** `list_conversations` via `pump.send` — which returns false and is
     * **dropped** while the pump is pre-`Open`, and is never re-issued after the handshake completes. So the
     * daemon never received the request, never replied with a `conversations` snapshot, and the list screen
     * spun on its loading state forever (the "New discussion" FAB, gated on the first snapshot, never
     * appeared). Gating the repo behind `Open` — mirroring [pyrycodeStatus] and the connect-time push-token
     * hook, which already await `Open` — means the facade subscribes, and the list send fires, only once
     * `pump.send` will succeed.
     *
     * **Single-source derivation (#493 fix).** The gate derives repo *and* pump-state from the **same**
     * switched value: one [flatMapLatest] over [activeConnection], whose inner flow maps *that connection's
     * own* `pump.state` to the gated repo. The prior form `combine`d two independently-mutated `StateFlow`s
     * (a repo holder and a `flatMapLatest` over a *separate* pump holder); on a direct A→B reconnect the
     * direct repo emission raced ahead of the one-extra-hop pump-state switch, so `combine` transiently
     * paired the **new** connection's repo with the **old** pump's cached `Open` — re-exposing the repo
     * pre-`Open` and re-introducing #421. Deriving both from one switched [Connection] closes that window
     * structurally: the inner lambda closes over `conn`, so the mapped `conn.repo` and `conn.pump.state` are
     * always the *same* connection's; switching to connection B yields `connB.pump.state.value`
     * (`Handshaking`) → `null` first, and there is no path that pairs `connB.repo` with any pump but B's own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentRepository: StateFlow<ConversationRepository?> =
        activeConnection
            .flatMapLatest { conn ->
                conn?.pump?.state?.map { if (it is PumpState.Open) conn.repo else null } ?: flowOf(null)
            }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The decoded v2 structured live-session events (#385) for the current connection, surfaced off the
     *  connection-scoped concrete [RemoteConversationRepository] (#406). The events are not on the
     *  [ConversationRepository] interface, so this reaches the concrete repo through [activeConnection]'s
     *  [Connection.repo]. A cold `Flow` (events have no "current value", so no [stateIn]); [flatMapLatest]
     *  switches to the fresh repo's stream on each connection and cancels the prior, so the seam survives
     *  reconnection. Empty between connections. Kept generic (the full [LiveSessionEvent] stream, not an
     *  `isThinking` projection) so the tool-correlation (#387) and assistant-text (#337) consumers reuse it
     *  without re-plumbing this layer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveSessionEvents: Flow<LiveSessionEvent> =
        activeConnection.flatMapLatest { conn -> conn?.repo?.liveSessionEvents ?: emptyFlow() }

    /** The decoded v2 interactive **modal** lifecycle events (#437) for the current connection, surfaced
     *  off the connection-scoped concrete [RemoteConversationRepository] through [activeConnection]'s
     *  [Connection.repo], like [liveSessionEvents]. Each connection's stream starts with one `null`, the
     *  reconnect marker (#1337): [flatMapLatest] emits it as the first value of the new connection's inner
     *  flow, after the prior connection's collection is cancelled and before any of the new connection's
     *  frames. Empty between connections, so a teardown folds nothing. **Private** (#492): its sole consumer
     *  is [hostModals]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val modalEvents: Flow<ModalEvent?> =
        activeConnection.flatMapLatest { conn ->
            conn?.repo?.modalEvents?.onStart<ModalEvent?> { emit(null) } ?: emptyFlow()
        }

    /**
     * Every prompt this host holds (#1337): each outstanding prompt across all its conversations, and the ones
     * dismissed on this connection, folded **once at this process-scoped layer** from [modalEvents] via
     * [HostModalState.reduce]. A thread reads its own conversation's prompt through [HostModalState.scopedTo].
     *
     * **Cleared on every new connection.** The reconnect marker resets the fold to empty before the new
     * connection's first modal frame, so a prompt answered on desktop or timed out while the phone was away
     * does not linger: the daemon re-sends every still-outstanding `modal_shown` with its original `modal_id`
     * on each (re)connection (protocol-mobile § Reconcile on (re)connect), and those re-sends are the only way
     * a prompt returns. A teardown alone clears nothing, so prompts held while disconnected stay until the
     * next connection is published; the deterministic [answerModal]/[cancelModal] null-guard keeps a tap on
     * one from reaching a dead connection. Desktop #415/#510/#1140.
     *
     * Rejection notices (#1340) are the exception: [HostModalState.reconnected] keeps them, so only the user's
     * dismissal or the end of the pairing clears one.
     *
     * **Hoisted from [ThreadViewModel] (#492)** so a `modal_shown` that arrives before any thread screen
     * subscribes is held: one collector, launched eagerly in [scope], folds every frame whether or not anyone
     * observes. Since #1340 it is a [MutableStateFlow] so [recordModalAction] can fold this phone's own actions
     * synchronously beside it; both write through `update`, so neither loses the other's change. Adds **no
     * log**: the modal fields may name a sensitive command/path.
     */
    private val hostModalState = MutableStateFlow(HostModalState())
    val hostModals: StateFlow<HostModalState> = hostModalState.asStateFlow()

    init {
        scope.launch {
            modalEvents.collect { event ->
                hostModalState.update { state -> if (event == null) state.reconnected() else state.reduce(event) }
            }
        }
    }

    /**
     * Folds one of this phone's own prompt actions into [hostModals] at once (#1340): an answer or cancel
     * closing its prompt before the daemon replies, a refused answer, or the user dismissing that refusal.
     * Non-suspending, so the thread's next synchronous read already sees it. No log: actions carry modal ids.
     */
    fun recordModalAction(action: ModalAction) {
        hostModalState.update { it.reduce(action) }
    }

    /**
     * The single-value view of [hostModals] the conversation-list attention readers still take (#1337, until
     * #1338 switches them to the whole list): the most recently shown outstanding prompt, else
     * [ModalUiState.Hidden]. Threads never read it; they scope [hostModals] instead.
     */
    val currentModal: StateFlow<ModalUiState> =
        hostModals
            .map { it.latestOutstanding }
            .stateIn(scope, SharingStarted.Eagerly, ModalUiState.Hidden)

    /**
     * Every clarification batch outstanding on this host (#822), across all its conversations. A per-
     * conversation surface reads through [observeQuestionBatch] instead, so one conversation's question is
     * never shown in another's thread.
     *
     * Switched to the active connection's [RemoteConversationRepository.questionBatches] and started
     * [SharingStarted.Eagerly] for [hostModals]' reason (#492): a batch that arrives before any thread
     * screen subscribes is held. Unlike [hostModals], which keeps its prompts through a teardown, it is **not** retained:
     * [teardownActive] nulls [activeConnection] (empty) and the next connection's repository starts empty,
     * so the old connection's batches are gone before the new one's first frame folds, and the daemon's
     * connect-time reconcile rebuilds only the batches still outstanding. No log: the batch strings are
     * claude-authored.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val questionBatches: StateFlow<List<QuestionBatch>> =
        activeConnection
            .flatMapLatest { conn -> conn?.repo?.questionBatches ?: flowOf(emptyList()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The batch outstanding for [conversationId] on this host, or null (#822); see [batchFor]. */
    fun observeQuestionBatch(conversationId: String): Flow<QuestionBatch?> =
        questionBatches.map { it.batchFor(conversationId) }.distinctUntilChanged()

    /**
     * The background tasks each conversation holds on this host (#677), keyed by conversation id. A
     * per-conversation surface reads through [observeBackgroundTasks] or [observeLiveBackgroundTaskCount].
     *
     * The [questionBatches] shape: switched to the active connection's
     * [RemoteConversationRepository.backgroundTasks] and started [SharingStarted.Eagerly], so frames that
     * arrive before any screen subscribes are held. Empty between connections, and each connection rebuilds
     * it from its own frames; only [finishedBackgroundTasks] carries over. No log: the task strings are
     * claude-authored command lines.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>> =
        activeConnection
            .flatMapLatest { conn -> conn?.repo?.backgroundTasks ?: flowOf(emptyMap()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The background tasks [conversationId] holds on this host, or null when nothing has been reported (#677). */
    fun observeBackgroundTasks(conversationId: String): Flow<BackgroundTaskRoster?> =
        backgroundTasks.map { it[conversationId] }.distinctUntilChanged()

    /** [BackgroundTaskRoster.liveCount] for [conversationId], or 0 when nothing has been reported (#677). */
    fun observeLiveBackgroundTaskCount(conversationId: String): Flow<Int> =
        observeBackgroundTasks(conversationId).map { it?.liveCount ?: 0 }.distinctUntilChanged()

    /** The combined two-part status (#392) #390 consumes off this concrete singleton: the supervisor's
     *  relay leg zipped with the derived pyrycode leg. `Eagerly` so `.value` is correct at any glance;
     *  cancelled by [close] (which cancels [scope]). */
    val connectionStatus: StateFlow<ConnectionStatus> =
        combine(relayStatus, pyrycodeStatus) { relay, pyrycode -> ConnectionStatus(relay, pyrycode) }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionStatus(relayStatus.value, PyrycodeLinkStatus.Down))

    private val started = AtomicBoolean(false)

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
    @Synchronized
    private fun onConnection(transport: RelayTransport?) {
        teardownActive()
        if (transport == null) return
        val childScope = CoroutineScope(SupervisorJob(job) + dispatcher)
        val pump = createPump(transport).also { it.start() }
        // Build the concrete repo up front: registerPushToken / liveSessionEvents / modalEvents are not on
        // the ConversationRepository interface, so those seams reach them through the retained [Connection],
        // not via the interface-typed currentRepository. The capability supplier (#385) snapshots the live
        // negotiated set lazily on each structured envelope; `.value` is a non-suspending read, preserving
        // this collector's cancellation atomicity (the :233 invariant). Once Open the set is
        // connection-constant, and structured envelopes only arrive post-Open, so every one sees the final
        // negotiated capabilities.
        val repo =
            RemoteConversationRepository(
                pump,
                childScope,
                deviceName,
                negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() },
                replayCursor = replayCursor,
                finishedBackgroundTasks = finishedBackgroundTasks,
                hostReadings = hostReadings,
            )
        // Publish the whole connection as ONE object: currentRepository now derives repo and pump-state
        // from this single switched value, closing the #493 cross-StateFlow race (see [currentRepository]).
        activeConnection.value = Connection(pump, childScope, repo, transport)
        // launch returns immediately; the suspending re-registration runs on the child scope, off the
        // non-suspending critical path of this collector (the :233 cancellation-atomicity invariant).
        childScope.launch { reregisterPushTokenOnOpen(pump, repo) }
    }

    /**
     * Connect-time FCM push-token re-registration (#365), kept current while open (#361). Awaits the
     * pump's first transition out of [PumpState.Handshaking]; if it reached [PumpState.Open], sends the
     * stored token and then every rotated one via #359's sender. **Dormant** while no token is stored: a
     * `null` is skipped. Runs on the connection's child scope, so a drop ends it and the next connection
     * sends the then-current token once — each connection builds a fresh pump + child scope + hook, and
     * the state machine never revisits `Handshaking` (re-key stays `Open`). Per the daemon's contract the
     * server de-dupes the `(platform, token, device_name)` triple, so a repeat is a cheap no-op that
     * self-heals registry drift across restarts/drops.
     */
    private suspend fun reregisterPushTokenOnOpen(
        pump: ManagedSessionPump,
        repo: RemoteConversationRepository,
    ) {
        // first {} checks the current value too, so an already-Open pump fires without a missed edge.
        val terminal = pump.state.first { it is PumpState.Open || it is PumpState.Closed }
        if (terminal !is PumpState.Open) return // closed before Open → nothing to register
        pushTokens.filterNotNull().distinctUntilChanged().collect { token ->
            try {
                repo.registerPushToken(token)
            } catch (e: CancellationException) {
                throw e // connection-drop cancellation must propagate, not be absorbed (structured concurrency)
            } catch (e: Exception) {
                // Swallowed (the token is never logged): the daemon re-registers on the next connect.
            }
        }
    }

    /**
     * Tears down the active connection if any, returning [currentRepository] to `null`. Order matters:
     * null [activeConnection] first so every derived seam re-derives its empty projection, then cancel the
     * child scope (stops the repository's inbound collector), then close the pump (wipes its keys + tears
     * its own scope down — independently required, since the child scope does not reach the pump's scope).
     * Idempotent: a second call with no active connection is a no-op.
     */
    @Synchronized
    private fun teardownActive() {
        val current = activeConnection.value ?: return
        activeConnection.value = null
        current.repo.endDebugBundle()
        current.scope.cancel()
        current.pump.close()
    }

    /** Tears down the active connection (wiping pump keys), drops the held [hostReadings] and cancels the
     *  coordinator scope, ending the collector. Idempotent. */
    @Synchronized
    fun close() {
        teardownActive()
        hostReadings.close()
        scope.cancel()
    }

    /** Reads current authenticated availability without the asynchronous [currentRepository] cache. */
    @Synchronized
    internal fun liveRepository(): ConversationRepository? {
        val current = activeConnection.value
        if (!job.isActive || current == null || current.transport !== connections.value || current.pump.state.value !is PumpState.Open) {
            return null
        }
        return current.repo
    }

    /** Request on this host's current authenticated connection; never wait, redirect or replay. */
    @Synchronized
    fun requestDebugBundle(): DebugBundleTransfer {
        val current = activeConnection.value
        if (!job.isActive || current == null || current.transport !== connections.value || current.pump.state.value !is PumpState.Open) {
            return DebugBundleTransfer.rejected(DebugBundleStatus.UNAVAILABLE)
        }
        return current.repo.requestDebugBundle()
    }

    /**
     * Outbound modal **answer** passthrough (#451): reach the connection-scoped concrete
     * [RemoteConversationRepository.answerModal] through [activeConnection]'s [Connection.repo] — the
     * **outbound mirror** of the inbound [modalEvents] seam. Inbound is a `Flow` (a stream); an answer is a
     * request/reply control **call**, so this is a suspend method, not a flow.
     *
     * When no connection is active ([activeConnection] is `null`, between connections) it throws
     * [IllegalStateException]. When a connection exists but the pump is still pre-[PumpState.Open], the
     * concrete `answerModal` → [ConversationCommands.answerModal] → [RelayRequests.sendAndAwaitReply] → `pump.send` returns false → [IllegalStateException]
     * (the #438 precedent) — so this needs **only** the null-guard, not a redundant `Open` gate. A server
     * `error` propagates as [de.pyryco.mobile.data.network.RelayErrorException] unchanged. Adds **no log**:
     * the `modalId`/`optionId` may name a sensitive command/path (never-log contract). [alwaysAllow] (#818)
     * passes through unchanged.
     */
    suspend fun answerModal(
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean = false,
    ) {
        val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
        repo.answerModal(modalId, optionId, alwaysAllow)
    }

    /**
     * Outbound modal **cancel** passthrough (#451): the [answerModal] mirror for
     * [RemoteConversationRepository.cancelModal]. Same null-guard-only posture and never-log contract.
     */
    suspend fun cancelModal(modalId: String) {
        val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
        repo.cancelModal(modalId)
    }

    /**
     * Outbound `interrupt` passthrough (#458): the [cancelModal] mirror for
     * [RemoteConversationRepository.interrupt]. Same null-guard-only posture and never-log contract;
     * fire-and-forget (no reply awaited).
     */
    suspend fun interrupt(conversationId: String) {
        val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
        repo.interrupt(conversationId)
    }

    /**
     * Outbound `question_answer` passthrough (#825) to this host's current connection's
     * [RemoteConversationRepository.answerQuestionBatch], which validates against the batches that
     * connection holds (the state [questionBatches] projects). Fire-and-forget, never clears the batch,
     * and adds no log. Throws [IllegalStateException] when no connection is active.
     */
    suspend fun answerQuestionBatch(
        questionBatchId: String,
        answers: List<QuestionAnswer>,
    ) {
        val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
        repo.answerQuestionBatch(questionBatchId, answers)
    }

    /** Outbound `question_refused` passthrough (#825): the [answerQuestionBatch] mirror. */
    suspend fun refuseQuestionBatch(questionBatchId: String) {
        val repo = activeConnection.value?.repo ?: throw IllegalStateException("no active connection")
        repo.refuseQuestionBatch(questionBatchId)
    }

    /** A draft belongs to its source/request, never a later connection that rebuilds the same nonce. Null answers refuse. */
    internal suspend fun submitQuestionBatch(
        source: RemoteConversationRepository,
        batch: QuestionBatch,
        answers: List<QuestionAnswer>?,
    ) {
        synchronized(this) {
            check(liveRepository() === source) { "question source retired" }
            check(source.questionBatches.value.firstOrNull { it.conversationId == batch.conversationId } === batch) {
                "question request retired"
            }
        }
        // These fire-and-forget calls do not suspend; retain the validated source rather than selecting another repository.
        if (answers ==
            null
        ) {
            source.refuseQuestionBatch(batch.questionBatchId)
        } else {
            source.answerQuestionBatch(batch.questionBatchId, answers)
        }
    }

    private class Connection(
        val pump: ManagedSessionPump,
        val scope: CoroutineScope,
        val repo: RemoteConversationRepository,
        val transport: RelayTransport,
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
