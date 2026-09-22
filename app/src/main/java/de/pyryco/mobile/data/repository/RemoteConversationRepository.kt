package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.ApiRetryPayloadDto
import de.pyryco.mobile.data.network.ArchiveConversationPayloadDto
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.BackfillSincePayloadDto
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.ChangeWorkspacePayloadDto
import de.pyryco.mobile.data.network.CompactingPayloadDto
import de.pyryco.mobile.data.network.ConversationDeletedPayloadDto
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.CreateConversationPayloadDto
import de.pyryco.mobile.data.network.CreateWorkspaceFolderPayloadDto
import de.pyryco.mobile.data.network.DeleteConversationPayloadDto
import de.pyryco.mobile.data.network.DequeueMessagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MessageChunkPayloadDto
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModalAnswerPayloadDto
import de.pyryco.mobile.data.network.ModalCancelPayloadDto
import de.pyryco.mobile.data.network.ModalDismissedPayloadDto
import de.pyryco.mobile.data.network.ModalShownPayloadDto
import de.pyryco.mobile.data.network.ModelListPayloadDto
import de.pyryco.mobile.data.network.PromoteConversationPayloadDto
import de.pyryco.mobile.data.network.QueueStatePayloadDto
import de.pyryco.mobile.data.network.RecentWorkspacesListPayloadDto
import de.pyryco.mobile.data.network.RegisterPushTokenPayloadDto
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RenameConversationPayloadDto
import de.pyryco.mobile.data.network.ReplayCursor
import de.pyryco.mobile.data.network.RequestHistoryPayloadDto
import de.pyryco.mobile.data.network.RequestSessionSettingsPayloadDto
import de.pyryco.mobile.data.network.RequestSnapshotPayloadDto
import de.pyryco.mobile.data.network.ScreenSnapshotPayloadDto
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.SessionSettingsUpdatedPayloadDto
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.network.StallPayloadDto
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.TurnStatePayloadDto
import de.pyryco.mobile.data.network.UnrecognizedMessagePayloadDto
import de.pyryco.mobile.data.network.WorkspaceFolderCreatedPayloadDto
import de.pyryco.mobile.data.network.WorkspaceUpdatedPayloadDto
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.network.toConversation
import de.pyryco.mobile.data.network.toConversations
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toHistoryPage
import de.pyryco.mobile.data.network.toMenu
import de.pyryco.mobile.data.network.toMessage
import de.pyryco.mobile.data.network.toQueue
import de.pyryco.mobile.data.network.toRow
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.network.toStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mobile Protocol v2 conversation-list read path (#312). Drives a `list_conversations` request
 * and projects inbound `conversations` snapshots — decoded and mapped by the #316
 * [ConversationsPayload] layer — into the filtered, sorted domain [Conversation] list the UI reads.
 *
 * Consumes the Noise session pump (#309) over the portable [SessionPump] surface: inbound
 * `Flow<Envelope>` + non-throwing [SessionPump.send]. It does **not** reach below the pump to the
 * frame transport or the Noise session, and it does **not** re-implement wire↔domain mapping — the
 * #316 mapper is the single decode-and-validate boundary, and this slice runs **behind** the
 * already-authenticated Noise channel.
 *
 * `pump.inbound` is hot and **single-consumer**, so the repository runs exactly one long-lived
 * collector (launched in [init] on the connection-scoped [scope]) that demultiplexes by
 * [Envelope.type] into a single [projection] `StateFlow`. [observeConversations] is a cold flow that
 * fans out from that projection, so N concurrent collectors share the one inbound consumer.
 *
 * Every interface method other than [observeConversations] is a not-yet-implemented stub that the
 * sibling slices replace in this same class: thread reads (#313), last-message (#329), mutations
 * (#314), and the methods still awaiting a documented v2 wire message (`archive` / `unarchive` /
 * `changeWorkspace`). [delete] and [createWorkspaceFolder] keep their interface
 * defaults — intentionally outside this slice's surface.
 */
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,
    /**
     * The connection-level device name sent as `register_push_token`'s `device_name` (#359) — the
     * same value [de.pyryco.mobile.data.network.NoiseClientInfo.deviceName] puts in the `hello`
     * payload. It is a connection constant (not a per-call argument), so it is threaded in as a
     * constructor param. **Defaulted to `""`** so the one production construction site
     * ([RelayRepositoryCoordinator.onConnection]) and the existing tests compile unchanged; the live
     * value is wired by the Firebase sibling that adds the live caller (a `deviceName` param on
     * [RelayRepositoryCoordinator] supplied from `NoiseClientInfo` in `AppModule`). The default is
     * never exercised in production today — [registerPushToken] has no live caller yet.
     */
    private val deviceName: String = "",
    /**
     * Snapshots the connection's negotiated capability set (#385/#401), read lazily **per structured
     * envelope** to gate [liveSessionEvents] on `interactive` (AC #2). A **supplier**, not a value:
     * the repository is built while the pump is still `Handshaking`, but structured envelopes only
     * arrive after `Open`, so the gate must read the set at arrival time — by then the negotiated set
     * is connection-constant. [RelayRepositoryCoordinator.onConnection] wires it from the live
     * [de.pyryco.mobile.data.network.PumpState.Open.capabilities]. **Defaulted to `{ emptySet() }`**
     * ("gate closed") so existing two-/three-arg constructions (tests, pre-wiring) compile and surface
     * no structured events until the supplier is wired.
     */
    private val negotiatedCapabilities: () -> Set<String> = { emptySet() },
    /**
     * The reconnect-spanning replay cursor (#412): each interactive structured frame's durable
     * [Envelope.eventId] is folded into this high-water mark in [recordReplayCursor]. It is **not**
     * owned by this repository (rebuilt every reconnect) — [RelayRepositoryCoordinator] holds the
     * process-scoped instance and threads the same one into each per-connection repo, so the mark
     * survives connection churn and is readable at the next `hello`-build (#413). **Defaulted to a
     * throwaway instance** so existing constructions (tests, pre-wiring) compile unchanged; only the
     * coordinator-wired instance outlives the connection.
     */
    private val replayCursor: ReplayCursor = ReplayCursor(),
) : ConversationRepository {
    /**
     * The demuxed list projection: `null` until the first `conversations` snapshot loads, then the
     * latest full-list snapshot. The primary read source; [observeConversations] derives every cold
     * read from it. Written by the single [init] inbound collector (the authoritative full-replace on
     * each `conversations` snapshot), by [createDiscussion]'s confirmed insert (#347), **and** by
     * [promote]'s confirmed upsert (#348) — both mutations fold a [Conversation] in via
     * [upsertConversation], an atomic [MutableStateFlow.update] CAS upsert (dedup by id) run only after
     * the correlated reply (`conversation_created` / `conversation_updated`) lands, so the writers
     * retry-merge rather than clobber. Two further inbound writers land here since #721: an
     * **unsolicited** `conversation_updated` folds through that same [upsertConversation], and a
     * `workspace_updated` relabels every row sharing its path via [applyWorkspaceLabel]. [promote]
     * additionally **reads** [projection]`.value` (a
     * lock-free snapshot) to resolve the conversation's existing cwd when its `workspace` argument is
     * null. `StateFlow` conflation means a value-equal result does not re-emit (e.g. a redundant reply
     * to a second collector's request, or the authoritative snapshot that later re-includes a
     * just-folded conversation).
     */
    private val projection = MutableStateFlow<List<Conversation>?>(null)

    /**
     * `conversationId -> most-recent` [Message] seen on this connection's live `message` stream
     * (#329). Written by the single [init] inbound collector **and** by [sendMessage]'s confirmed
     * insert (#346) — two writers, but every write goes through the atomic [MutableStateFlow.update]
     * fold below, so concurrent updates retry-merge correctly. [observeLastMessage] fans out from it.
     * Connection-scoped in-memory state — lost on process death and re-derived from the live stream
     * on reconnect (the cold-start gap is the #313 backfill hand-off). The fold is
     * strictly-greater-by-timestamp, mirroring the fake's `maxByOrNull { it.timestamp }`.
     */
    private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())

    /**
     * `conversationId -> ordered thread rows` ([ThreadItem.MessageItem] + [ThreadItem.SessionBoundary])
     * for the conversation — backfilled history (`message_chunk`) plus live `message`s and structured
     * turns, deduped by `message_id`, interleaved in wire/arrival order with `session_transition`
     * boundaries (#313, #336). Written by the single [init] inbound collector **and** by [sendMessage]'s
     * confirmed insert (#346) — two writers, but every write goes through the atomic
     * [appendMessages] / [appendSessionBoundary] / [MutableStateFlow.update] fold, so concurrent updates
     * retry-merge correctly. [observeMessages] fans out from it. Message rows are order-preserving: first
     * insertion fixes a message's position, a repeat `message_id` updates it in place (the dedup rule);
     * boundaries pure-append in arrival order (they carry no id, so no dedup). The thread is
     * complete-on-first-emission once backfill arrives and live rows append after.
     */
    private val threadByConversation = MutableStateFlow<Map<String, List<ThreadItem>>>(emptyMap())

    /**
     * The set of conversation ids currently in a stall (#395) — membership = stalled. Written **only**
     * from the single [init] inbound collector: a `stall` envelope adds its id (onset), and any
     * successfully-decoded forward-progress [LiveSessionEvent] removes its conversation (clearing —
     * the wire carries no clearing edge, so recovery is inferred from forward progress). Single writer
     * on the one collector coroutine, so onset and clearing never race; the atomic
     * [MutableStateFlow.update] matches the sibling projections' memory-visibility posture.
     * [observeStall] fans out from it. Connection-scoped in-memory state — a fresh repository per
     * connection (#351) starts empty, so a stall never survives a reconnect (it is re-derived from the
     * live stream). A stall is a transient "right now" condition, not durable state.
     */
    private val stalledConversations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * `conversationId -> ordered queued-message backlog` (#460) — the messages the daemon has queued
     * while claude is busy, in wire/FIFO order. Written **only** from the single [init] inbound
     * collector: each `queue_state` envelope is a full snapshot that **replaces** that conversation's
     * entry (the wire form of `msgqueue.Snapshot`), leaving every other conversation untouched. Single
     * writer on the one collector coroutine, so snapshots never race; the atomic [MutableStateFlow.update]
     * matches the sibling projections' memory-visibility posture. [observeQueue] fans out from it.
     * Connection-scoped in-memory state — a fresh repository per connection (#351) starts empty, so a
     * backlog never survives a reconnect (it is re-derived from the next live `queue_state`). The backlog
     * is a transient "right now" condition, not durable state.
     */
    private val queuedByConversation = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())

    /**
     * `conversationId -> the model menu this connection heard for it` (#791) — the identifiers, labels,
     * per-row effort levels and auto-mode support the daemon published. Written **only** from the single
     * [init] inbound collector: each `model_list` frame is a full snapshot that **replaces** that
     * conversation's entry, leaving every other conversation untouched. Single writer on the one
     * collector coroutine, so snapshots never race; the atomic [MutableStateFlow.update] matches the
     * sibling projections' memory-visibility posture. [observeModelMenu] fans out from it.
     *
     * **Nothing ever removes a key, and no connection edge clears the map.** Absence of a frame is the
     * wire's only "no list" signal, so a blanket clear would manufacture an unavailable reading the
     * daemon never stated. Connection-scoped in-memory state — a fresh repository per connection (#351)
     * starts empty, which is the only reset this state has, and is also where "per host" comes from: the
     * published vocabulary varies by machine and account rather than by conversation.
     *
     * Unlike [queuedByConversation] this is **not** a transient "right now" condition — a published
     * vocabulary is a standing fact about the host for as long as the connection lives.
     */
    private val modelMenusByConversation = MutableStateFlow<Map<String, ModelMenu>>(emptyMap())

    /**
     * `conversationId -> the message ids this device minted and echoed into the thread` (#781) — the
     * ledger that makes a queued item's [QueuedMessage.messageId] safe to act on. Written by
     * [sendMessage] after its ack (beside the confirmed insert) and consumed by [dropQueuedMessage]
     * after its ack; both writes are atomic [MutableStateFlow.update]s, the two-writer posture
     * [appendMessages] already relies on. Observed by nothing, so it publishes no flow.
     *
     * **This exists because the thread projection is not a valid correlation store.** `queue_state`
     * fans out to every interactive connection, so items routinely carry ids another paired device
     * minted; the thread meanwhile also holds rows folded from history pages (#623/#778), which can
     * carry those same foreign ids. `message_id` is client-chosen with uniqueness enforced **nowhere**,
     * so matching a queued item against the projection alone would let a colliding id delete a row this
     * phone never sent — which `docs/protocol-mobile.md` § Queue (v2) forbids in as many words ("a
     * client merges an item only against echoes it minted itself"). Membership here *is* that rule.
     *
     * An id is **removed when consumed**, which is also what makes the legal duplicate-`message_id`
     * case behave: dropping a second item carrying an already-spent id finds nothing and removes
     * nothing, instead of taking an unrelated row with it. Connection-scoped and in-memory like every
     * sibling projection (#351) — it holds one minted id per successful send, minus every consumed
     * drop, and dies with the connection.
     */
    private val mintedMessageIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /**
     * `conversationId -> current API-retry status` (#593) — whether claude is stuck retrying an API
     * error, and at which attempt. Written **only** from the single [init] inbound collector: each
     * `api_retry` envelope **replaces** that conversation's entry (a rising edge with the current
     * counter, a re-fired rising edge with the climbed one, or [ApiRetryStatus.NotRetrying] on the
     * falling edge), leaving every other conversation untouched. Single writer on the one collector
     * coroutine, so the rising and falling edges never race; the write is a pure replace, not a
     * read-modify-write, so there is no check-then-mutate window even in principle, and the atomic
     * [MutableStateFlow.update] matches the sibling projections' memory-visibility posture.
     * [observeApiRetry] fans out from it. A falling edge **stores** [ApiRetryStatus.NotRetrying]
     * rather than removing the key — observationally identical to an absent key, since the observer
     * defaults an absent one the same way. Connection-scoped in-memory state — a fresh repository per
     * connection (#351) starts empty, so a retry state never survives a reconnect (it is re-derived
     * from the live stream). A retry is a transient "right now" condition, not durable state.
     */
    private val apiRetryByConversation = MutableStateFlow<Map<String, ApiRetryStatus>>(emptyMap())

    /**
     * The set of conversation ids claude is currently auto-compacting (#596) — membership = compacting.
     * Written **only** from the single [init] inbound collector: a `compacting` envelope's rising edge
     * adds its id and its falling edge removes it. Single writer on the one collector coroutine, so the
     * rising and falling edges never race; the write is a genuine **read-modify-write** on the set
     * (`it + id` / `it - id`), unlike [apiRetryByConversation]'s pure replace, so the atomic
     * [MutableStateFlow.update] is load-bearing rather than stylistic — a
     * `.value = compactingConversations.value + id` formulation would open a real check-then-mutate
     * window. [observeCompacting] fans out from it. Connection-scoped in-memory state — a fresh
     * repository per connection (#351) starts empty, so a compaction state never survives a reconnect
     * (it is re-derived from the live stream). Compaction is a transient "right now" condition, not
     * durable state.
     *
     * The one place [stalledConversations]' shape does not transfer: removal here is driven by an
     * **explicit wire falling edge**, not inferred from the next forward-progress event.
     */
    private val compactingConversations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * `conversationId -> settings-read ordinal` (#590) — the **refresh trigger** for
     * [observeSessionSettings], deliberately not a cache of the readings themselves. A bump means "the
     * reading you hold is stale, read again"; the value is meaningless beyond being different from the
     * last one, and is never compared across connections (a new connection is a new repository with a
     * fresh map).
     *
     * Two writers, unlike the single-collector projections above: the [init] inbound collector bumps on
     * a `session_transition`, and any caller thread bumps through [refreshSessionSettings] once its
     * settings write has settled. The increment is a genuine **read-modify-write**, so running it inside
     * the atomic [MutableStateFlow.update] is load-bearing rather than stylistic — the
     * [compactingConversations] posture, not [apiRetryByConversation]'s pure replace. Even the
     * degenerate collapse is safe: two bumps folding into one still trigger a read that observes the
     * newest state, because the read asks the daemon rather than replaying a stored edge.
     *
     * An absent key reads as ordinal `0`, so a first collector needs no seeding and a conversation
     * nobody has opened costs nothing.
     */
    private val settingsRevision = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val requestId = AtomicLong(0)
    private var debugBundle: DebugBundleTransfer? = null
    private var bundleInboundEnded = false

    /** One attempt per connection: uncorrelated chunk/done frames cannot safely feed a retry. */
    @Synchronized
    internal fun requestDebugBundle(): DebugBundleTransfer {
        if (bundleInboundEnded) return DebugBundleTransfer.rejected(DebugBundleStatus.UNAVAILABLE)
        debugBundle?.let {
            return DebugBundleTransfer.rejected(
                if (it.state.value.status == DebugBundleStatus.RECEIVING) DebugBundleStatus.BUSY else DebugBundleStatus.RECONNECT_REQUIRED,
            )
        }
        val request = Envelope(requestId.incrementAndGet(), "request_debug_bundle", Clock.System.now().toString())
        val transfer = DebugBundleTransfer(request.id).also { debugBundle = it }
        val sent =
            try {
                pump.send(request)
            } catch (_: Exception) {
                false
            }
        if (!sent) transfer.fail(DebugBundleStatus.SEND_FAILED)
        return transfer
    }

    @Synchronized
    internal fun endDebugBundle() {
        bundleInboundEnded = true
        debugBundle?.fail(DebugBundleStatus.DISCONNECTED)
    }

    @Synchronized
    private fun routeDebugBundle(envelope: Envelope): Boolean = debugBundle?.accept(envelope) == true

    /**
     * Source of the client-owned [ThreadItem.UnrecognizedMessage.id] (#609). The `unrecognized_message`
     * wire frame carries neither a message id nor a `turn_id`, yet the thread's `LazyColumn` keys on
     * `"unrecognized:<id>"` — so a duplicate crashes the thread, and the id cannot be derived from the
     * payload or the arrival instant (two byte-identical frames stamped in the same instant would
     * collide, which is exactly the repeat case the fold must keep distinguishable).
     *
     * A per-repository monotonic counter is sufficient *structurally*, not incidentally: ids are unique
     * within this instance (hence within any one thread), the repository is connection-scoped (#351) so
     * each connection gets a fresh instance, and [StableConversationRepository]'s `flatMapLatest` drops
     * the previous connection's projection outright on reconnect — no reader ever observes rows from two
     * instances merged, so a restarted counter cannot collide with a prior connection's ids.
     *
     * The value carries **no wire data** — no `conversation_id`, no payload hash — keeping [QueuedMessage.id]'s
     * posture: a monotonic ordinal, not a secret, never compared against anything attacker-controlled
     * ([AtomicLong], deliberately not `SecureRandom`). [AtomicLong] mirrors [requestId] for consistency
     * rather than because concurrency demands it; the inbound demux is a single collector.
     *
     * **Ordinals may be skipped** — a frame that decodes structurally but is dropped by the unknown-`site`
     * mapper still consumed its `incrementAndGet()`. That is intentional: the invariant is *uniqueness*,
     * not density.
     */
    private val unrecognizedRowId = AtomicLong(0)

    /**
     * Request *envelope* id (`requestId.incrementAndGet()`, **not** the payload `message_id`) ->
     * the deferred awaiting that request's correlated reply (#346). A request registers its deferred
     * here before sending; the single [init] collector completes it on the matching `ack` (success)
     * or `error` (failure) by [Envelope.inReplyTo]; the awaiting caller removes its own entry in a
     * `finally`. Touched from the collector coroutine and arbitrary caller coroutines, so
     * [ConcurrentHashMap] — the same `java.util.concurrent` posture as [requestId]. This is the
     * shared request↔reply correlation primitive #347/#348 reuse.
     */
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    /**
     * Hot stream of decoded v2 structured live-session events (#385) — the typed `turn_state` /
     * `assistant_delta` / `tool_use` / `tool_result` / `turn_end` family, surfaced off the single
     * [init] inbound collector (no second subscription). A `SharedFlow`, not a `StateFlow`: these are
     * *events*, not current-value state, so `replay = 0` (late subscribers get no history — holding
     * "latest" is a consumer projection concern). A bounded [extraBufferCapacity] with
     * [BufferOverflow.DROP_OLDEST] makes [MutableSharedFlow.tryEmit] **infallible and non-blocking**:
     * the load-bearing invariant is that a slow live-event consumer must never back-pressure the
     * shared inbound collector and stall the connection's `conversations`/`message`/`ack` processing.
     * Delivery is therefore best-effort under extreme backpressure (a flooding daemon cannot grow
     * memory here); a consumer needing lossless accumulation owns its own buffering (#337).
     *
     * Exposed on the **concrete** repository only — deliberately **not** on the [ConversationRepository]
     * interface, mirroring [registerPushToken] (#359): adding it to the interface would force the fake
     * + facade to plumb a flow this decode slice does not use. Facade/coordinator reachability for the
     * UI consumers (#386/#387/#337) is downstream consumer-slice work.
     */
    private val mutableLiveSessionEvents =
        MutableSharedFlow<LiveSessionEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val liveSessionEvents: SharedFlow<LiveSessionEvent> = mutableLiveSessionEvents.asSharedFlow()

    /**
     * Hot stream of decoded v2 interactive **modal** lifecycle events (#437) — the typed `modal_shown` /
     * `modal_dismissed` family, surfaced off the same single [init] inbound collector (no second
     * subscription). A verbatim sibling of [mutableLiveSessionEvents]: `replay = 0` (these are *events*,
     * not held state — "which modal is currently open" is the #439 consumer's projection), a bounded
     * [extraBufferCapacity] + [BufferOverflow.DROP_OLDEST] make [MutableSharedFlow.tryEmit] **infallible
     * and non-blocking** so a slow modal consumer can never back-pressure the shared inbound collector and
     * stall the connection's `conversations`/`message`/`ack` processing. Modals are inherently low-rate
     * (one outstanding at a time, user-driven), so the bound is never realistically hit.
     *
     * A **separate** flow from [liveSessionEvents], deliberately not a sixth [LiveSessionEvent]: modal
     * payloads carry **no `conversation_id`** ([ModalEvent] keys on `modalId`), whereas every
     * [LiveSessionEvent] subtype mandates `conversationId` and the structured arm routes on it. Exposed on
     * the **concrete** repository only — **not** on the [ConversationRepository] interface — the exact
     * [liveSessionEvents] / [registerPushToken] (#359) posture; facade/coordinator reachability for the
     * render consumer (#439) is downstream consumer-slice work.
     */
    private val mutableModalEvents =
        MutableSharedFlow<ModalEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val modalEvents: SharedFlow<ModalEvent> = mutableModalEvents.asSharedFlow()

    init {
        // The single consumer of the hot, single-consumer inbound stream. Cancelled by its owner
        // (#279/#302) when the connection ends; the pump completing `inbound` on teardown also ends it.
        // On any collector-termination mode (scope cancel — the primary teardown trigger — inbound
        // completing, or inbound throwing) the `finally` sweeps still-registered pending requests so
        // an awaiting caller fails fast instead of hanging forever (#488).
        scope.launch {
            try {
                pump.inbound.collect { envelope -> onInbound(envelope) }
            } finally {
                endDebugBundle()
                failAllPending()
            }
        }
    }

    private fun onInbound(envelope: Envelope) {
        if (routeDebugBundle(envelope)) return
        recordReplayCursor(envelope)
        when (envelope.type) {
            TYPE_CONVERSATIONS -> {
                // The reply to our request AND any unsolicited change push arrive as a full-list
                // `conversations` snapshot, so re-emission needs no in_reply_to correlation (AC #3).
                // Decode is the single failure surface: a malformed payload (missing required field
                // → SerializationException, bad timestamp → IllegalArgumentException; the former is a
                // subtype of the latter) is dropped so the single inbound consumer survives, rather
                // than letting an uncaught throw freeze every future update for the connection.
                val decoded =
                    try {
                        MobileJson.decodeFromJsonElement<ConversationsPayload>(envelope.payload)
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                projection.value = decoded.toConversations()
            }
            TYPE_MESSAGE -> {
                // A live (or send_message-echo) `message` envelope. Decode + map through the single
                // #317 boundary; a malformed payload (missing field / unmappable role →
                // SerializationException, bad envelope ts → IllegalArgumentException via Instant.parse;
                // the former is a subtype of the latter) is dropped so the single inbound consumer
                // survives. `sessionId = ""` — the payload carries none and the last-message preview
                // never reads it (list-tier placeholder, as #312 uses for currentSessionId). Drop
                // silently: message content may be sensitive, so nothing here logs the payload.
                val (conversationId, message) =
                    try {
                        val dto = MobileJson.decodeFromJsonElement<MessagePayloadDto>(envelope.payload)
                        dto.conversationId to dto.toMessage(envelope, sessionId = "")
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                // Keep the most-recent by timestamp (the strictly-greater fold below). The live
                // message is also a thread row (#313): append it to the conversation thread in
                // arrival order, deduped by message_id. The thread is a distinct projection from
                // the last-message preview.
                recordLastMessage(conversationId, message)
                appendMessages(listOf(conversationId to message))
            }
            TYPE_MESSAGE_CHUNK -> {
                // The `backfill_since` response (#313): a batch of finished messages, each carrying
                // the chunk's one envelope `ts`. Decode + map the whole chunk in one try/catch — a
                // single bad row (unmappable role / missing field → SerializationException, bad ts →
                // IllegalArgumentException via Instant.parse; the former is a subtype) drops the
                // entire chunk so the single inbound consumer survives. Drop silently: message
                // content may be sensitive. Each row self-routes via its own `conversation_id`.
                val rows =
                    try {
                        val chunk = MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(envelope.payload)
                        chunk.messages.map { dto -> dto.conversationId to dto.toMessage(envelope, sessionId = "") }
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                appendMessages(rows)
            }
            TYPE_CONVERSATION_UPDATED -> {
                // TWO kinds of producer, and the protocol requires a client to accept both (#721).
                // A *correlated* reply (promote / rename / archive / unarchive / change_workspace /
                // set_system_prompt) goes to its waiter verbatim, exactly as the shared arm below does
                // — the awaiting mutation decodes and upserts its own typed return, so folding here too
                // would be a redundant second write to the same row.
                // An *unsolicited push* (`pyry channel new` on the host, or the one-shot auto-naming of
                // a never-named conversation) carries no `in_reply_to` at all — and a duplicate reply
                // arriving after its waiter deregistered matches no pending entry — so correlating on
                // the type alone would drop it. Both fold by the payload's own `id`, which is what
                // makes a rename made on another client reach this phone's live list (and, since #720,
                // carries that row's `workspace_label` with it) instead of waiting for the next
                // snapshot. Decode-or-drop precedes the fold, so a malformed push mutates nothing and
                // the single inbound consumer survives; drop silently — the record carries the
                // conversation's name and cwd, so nothing here logs the payload.
                val waiter = envelope.inReplyTo?.let { id -> pendingRequests[id] }
                if (waiter != null) {
                    waiter.complete(envelope.payload)
                } else {
                    val conversation =
                        try {
                            MobileJson.decodeFromJsonElement<ConversationResponseDto>(envelope.payload).toConversation()
                        } catch (e: IllegalArgumentException) {
                            return
                        }
                    upsertConversation(conversation)
                }
            }
            TYPE_WORKSPACE_UPDATED -> {
                // A workspace-label notification (#721). Like `conversation_updated` it has two kinds of
                // producer — the correlated reply to `rename_workspace` and the unsolicited push the
                // daemon fans to every *other* interactive-capable conn — but unlike it the record is
                // applied **unconditionally**, whether or not `in_reply_to` is set (AC #1): the payload
                // is identical either way and nothing in this repository sends `rename_workspace` yet,
                // so completing a waiter here would be plumbing for a request that has no sender (#663
                // adds both together). Deliberately NOT capability-gated: the gate would break the
                // correlated half and buys nothing, since a daemon ignoring the negotiated set could
                // drive the same label change through an ungated `conversations` snapshot.
                // Decode-or-drop is the single failure surface (a missing/ill-typed `path` →
                // SerializationException ⊂ IllegalArgumentException), so a malformed frame leaves the
                // projection intact and the lone inbound collector alive for the next valid one (AC #3).
                // Drop silently: `path` is a filesystem location on the daemon's host and `label` is
                // operator-authored text — neither reaches a log on any branch, matching the daemon,
                // which records only a conn id and an event name for this verb.
                val decoded =
                    try {
                        MobileJson.decodeFromJsonElement<WorkspaceUpdatedPayloadDto>(envelope.payload)
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                applyWorkspaceLabel(decoded.path, decoded.label)
            }
            TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_DELETED,
            TYPE_SCREEN_SNAPSHOT, TYPE_SESSION_SETTINGS_UPDATED, TYPE_WORKSPACE_FOLDER_CREATED,
            TYPE_RECENT_WORKSPACES_LIST, TYPE_HISTORY_PAGE, TYPE_SESSION_SETTINGS,
            ->
                // Success reply to a correlated request, handed verbatim to the waiter. An `ack`
                // (#346) carries the empty `{}` the bare-ack waiter ignores; a `conversation_created`
                // (#347) carries the bare conversation object [createDiscussion] decodes for its typed
                // return; a
                // `screen_snapshot` (#375) carries the rendered-screen payload [requestScreenSnapshot]
                // decodes for its `text`; a `session_settings_updated` (#543) carries the bare
                // `{session_id}` ack the [setSessionSettings] waiter decodes for reply-shape validation;
                // a `conversation_deleted` (#532) carries the bare `{id}` ack the [delete] waiter decodes
                // for reply-shape validation; a `workspace_folder_created` (#564) carries the bare
                // `{path}` the [createWorkspaceFolder] waiter decodes for its return (the created
                // folder's canonical path); a `recent_workspaces_list` (#565) carries the
                // `{workspaces:[…]}` the [recentWorkspaces] waiter decodes for its path list. An
                // `inReplyTo` matching no pending entry (or null) is a
                // no-op: `list_conversations` / `backfill_since` draw no reply here; `screen_snapshot` /
                // `session_settings_updated` / `conversation_deleted` / `workspace_folder_created` /
                // `recent_workspaces_list` are always correlated replies (the daemon never broadcasts
                // them); a `history_page` (#623) carries the `{entries,cursor,at_start}` the
                // [requestHistory] waiter decodes for its page; a `session_settings` (#590) carries the
                // run configuration the [observeSessionSettings] read decodes. That last one is where the
                // demux does load-bearing safety work: the reply carries NO conversation_id of its own, so
                // a stale, duplicate or unsolicited one has no pending entry to complete and therefore no
                // slot to land in — a reading can only ever be routed by the id its caller asked with. So
                // an unmatched one is
                // harmless; and `complete` is idempotent so a duplicate reply is harmless.
                // `conversation_created` stays here deliberately: unlike `conversation_updated` (which
                // #721 moved to its own arm above) it is a correlated reply only — the create-on-host
                // push is a `conversation_updated`, not a `conversation_created`.
                envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
            TYPE_ERROR ->
                // Failure reply to a correlated request (#346): unblock the waiter exceptionally with
                // the mapped domain error. `mapError` never throws (a malformed payload yields a
                // fallback exception), so the lone collector survives; `completeExceptionally` is
                // idempotent and a no-op when no entry matches.
                envelope.inReplyTo?.let { id -> pendingRequests[id]?.completeExceptionally(mapError(envelope.payload)) }
            TYPE_TURN_STATE, TYPE_ASSISTANT_DELTA, TYPE_TOOL_USE, TYPE_TOOL_RESULT, TYPE_TURN_END -> {
                // A v2 structured live-session envelope (#385). AC #2: gate on the negotiated
                // capability — without `interactive` we never decode and never surface it (a buggy/
                // hostile daemon ignoring the negotiated set cannot push structured events to a
                // non-interactive phone). Decoding is best-effort: a malformed envelope or an
                // unrecognized turn_state yields null and is dropped (AC #3/#4); tryEmit is
                // non-blocking (DROP_OLDEST) so the shared inbound collector is never stalled.
                // Drop silently: the payloads carry message/tool content — nothing here logs them.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeLiveSessionEvent(envelope)?.let { event ->
                        // Forward progress ends any active stall for the conversation (#395, AC #2):
                        // the quiet-while-not-idle condition no longer holds. A removal of an absent id
                        // is a no-op, so clearing rides every live event harmlessly. Symmetric with the
                        // onset arm below — both are inside the same `interactive` gate.
                        stalledConversations.update { it - event.conversationId }
                        // Fold the structured turn into the same `threadByConversation` the live
                        // `message` arm writes, so every row interleaves by arrival order (AC #4): a
                        // `tool_use`/`tool_result` pair into one evolving tool row (#387), and the
                        // `assistant_delta` stream into one streaming assistant row that `turn_end`
                        // finalizes (#337). `turn_state` stays a stream-only signal — the thinking
                        // indicator reads it off the live-event stream below (#406) — and every event
                        // is surfaced on that stream regardless of whether it also folds a row.
                        when (event) {
                            is LiveSessionEvent.AssistantDelta -> applyAssistantDelta(event)
                            is LiveSessionEvent.ToolUse -> applyToolUse(event)
                            is LiveSessionEvent.ToolResult -> applyToolResult(event)
                            is LiveSessionEvent.TurnEnd -> finalizeAssistantTurn(event)
                            else -> Unit
                        }
                        mutableLiveSessionEvents.tryEmit(event)
                    }
                }
            }
            TYPE_STALL -> {
                // Stall onset (#395). Same `interactive` gate as the live-session arm: a non-interactive
                // phone never decodes a spurious `stall` from a buggy/hostile daemon that ignored the
                // server-side fan-out gate (fail-closed, defence in depth). A malformed payload decodes
                // to null and is dropped so the single inbound consumer survives (AC #3). The wire is
                // onset-only ({conversation_id}, no clearing edge); re-receipt for an already-stalled
                // conversation is an idempotent Set add. Drop silently — nothing here logs the payload.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeStall(envelope)?.let { conversationId ->
                        stalledConversations.update { it + conversationId }
                    }
                }
            }
            TYPE_QUEUE_STATE -> {
                // Queued-backlog snapshot (#460). Same `interactive` gate as the live-session / `stall`
                // siblings: a non-interactive phone never decodes a spurious `queue_state` from a buggy/
                // hostile daemon that ignored the server-side fan-out gate (fail-closed, defence in depth).
                // Each snapshot is the authoritative current backlog (msgqueue.Snapshot), so it FULLY
                // REPLACES this conversation's entry and leaves every other conversation untouched (AC #3);
                // wire array order is preserved verbatim (AC #1). A malformed payload decodes to null and
                // is dropped so the single inbound consumer survives (AC #4). Unlike the live-session arm
                // this folds no thread row and does NOT clear a stall (a backlog is "waiting", not forward
                // progress). Drop silently — queued `text` is user content; nothing here logs the payload.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeQueueState(envelope)?.let { (conversationId, queue) ->
                        queuedByConversation.update { it + (conversationId to queue) }
                    }
                }
            }
            TYPE_MODEL_LIST -> {
                // The per-conversation model menu (#791). Same `interactive` gate as the live-session /
                // `stall` / `queue_state` siblings: a non-interactive phone never decodes a spurious
                // `model_list` from a buggy/hostile daemon that ignored the server-side fan-out gate
                // (fail-closed, defence in depth). Each frame is snapshot-shaped full state, so it FULLY
                // REPLACES this conversation's entry and leaves every other conversation untouched — no
                // merge, no append, and re-applying the reconnect burst is safe by construction.
                //
                // Routing is the payload's own conversation_id and NOTHING else. Never the envelope id,
                // which every frame in the reconcile burst repeats, and never burst position: the daemon
                // walks its registry in an order that is not a contract. An id no collector observes
                // simply sits unread in the map.
                //
                // Nothing is cleared here or on a connection edge — absence of a frame is the wire's only
                // "no list" signal, so a clear would manufacture an unavailable reading the daemon never
                // stated. A malformed payload decodes to null and is dropped so the single inbound
                // consumer survives, leaving the previously retained menu standing. Like the `queue_state`
                // sibling and unlike the live-session arm, this folds no thread row and does NOT clear a
                // stall — a menu is not turn forward progress. Drop silently: every row string is
                // claude-authored text that crossed the subprocess trust boundary, and a logged
                // conversation_id is a cross-conversation correlation leak.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeModelList(envelope)?.let { (conversationId, menu) ->
                        modelMenusByConversation.update { it + (conversationId to menu) }
                    }
                }
            }
            TYPE_API_RETRY -> {
                // API-retry status (#593). Same `interactive` gate as the live-session / `stall` /
                // `queue_state` siblings: a non-interactive phone never decodes a spurious `api_retry`
                // from a buggy/hostile daemon that ignored the server-side fan-out gate (fail-closed,
                // defence in depth). One unconditional replace of this conversation's entry, leaving
                // every other conversation untouched (AC #3) — deliberately no branch on `active` here:
                // `toStatus()` is the sole owner of the edge semantics (including discarding the stale
                // counter the falling edge carries), and a second `if` would encode the same rule twice.
                // A malformed payload decodes to null and is dropped so the single inbound consumer
                // survives (AC #3). Like the `queue_state` sibling and unlike the live-session arm, this
                // folds no thread row and does NOT clear a stall — a retry is claude stuck, not turn
                // forward progress (AC #4). Drop silently — nothing here logs the payload (the counter is
                // screen-derived data crossing the tui-driver substrate seal).
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeApiRetry(envelope)?.let { (conversationId, status) ->
                        apiRetryByConversation.update { it + (conversationId to status) }
                    }
                }
            }
            TYPE_COMPACTING -> {
                // Context-compaction status (#596). Same `interactive` gate as the live-session /
                // `stall` / `queue_state` / `api_retry` siblings: a non-interactive phone never decodes a
                // spurious `compacting` from a buggy/hostile daemon that ignored the server-side fan-out
                // gate (fail-closed, defence in depth). One membership transition for this conversation,
                // leaving every other conversation untouched (AC #3) — and here the `if (active)` belongs
                // in the arm rather than a mapper: unlike `api_retry` there is no mapper to own the edge
                // semantics (the two wire fields already are the domain shape), so the membership
                // transition *is* the edge, expressed exactly once. `it - conversationId` on an absent id
                // is a no-op, so a falling edge with no prior rising edge is harmlessly inert, and a
                // repeated rising edge is an idempotent Set add. A malformed payload decodes to null and
                // is dropped so the single inbound consumer survives (AC #5). Like the `queue_state` /
                // `api_retry` siblings and unlike the live-session arm, this folds no thread row and does
                // NOT clear a stall — compaction is claude busy elsewhere, not turn forward progress, and
                // clearing a stall here would let a daemon suppress the phone's stall indicator by
                // emitting `compacting` frames. Drop silently — nothing here logs the payload (a logged
                // conversation_id is a cross-conversation correlation leak).
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeCompacting(envelope)?.let { (conversationId, active) ->
                        compactingConversations.update { if (active) it + conversationId else it - conversationId }
                    }
                }
            }
            TYPE_SESSION_TRANSITION -> {
                // A session boundary (#336, pyrycode#656/#657/#740). Same `interactive` gate as the
                // live-session / `stall` / `queue_state` siblings: a non-interactive phone never decodes a
                // spurious `session_transition` from a buggy/hostile daemon that ignored the server-side
                // fan-out gate (fail-closed, defence in depth — the client mirror of the producer's
                // server-side drop of unbindable transitions). Decode-or-drop (AC #3/#5): a malformed
                // payload or an unknown reason yields null → drop one envelope, the lone collector
                // survives. Routes strictly by the payload's conversation_id, so the boundary structurally
                // cannot cross-route into another thread (AC #1) — an id no collector observes simply sits
                // unread in the map. Two sibling writes on a decoded boundary: appendSessionBoundary folds
                // a thread-boundary row, and updateCurrentSessionId (#578) folds new_session_id into the
                // projection Conversation.currentSessionId so session-scoped frames target the live session
                // instead of the empty id the v2 `conversations` summary defaults. Unlike the structured-
                // stream arm neither surfaces on liveSessionEvents (a boundary is not a streaming event) nor
                // clears a stall (a session transition is not turn forward-progress). Drop silently —
                // conversation_id / session ids / workspace_cwd are sensitive; nothing here logs the payload.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeSessionTransition(envelope)?.let { (conversationId, boundary) ->
                        appendSessionBoundary(conversationId, boundary)
                        updateCurrentSessionId(conversationId, boundary.newSessionId)
                        // Third write since #590: the session this conversation's settings describe has
                        // been replaced, so every reading of it is stale. Bump the trigger rather than
                        // reading here — the read belongs on a collector's coroutine, and a conversation
                        // nobody is watching must not send a frame. Routed by the same decoded
                        // conversation_id as its two siblings, so a transition cannot invalidate another
                        // conversation's reading.
                        bumpSettingsRevision(conversationId)
                    }
                }
            }
            TYPE_UNRECOGNIZED_MESSAGE -> {
                // A claude message the daemon's stream-json parser could not map (#609, pyrycode#1074).
                // Same `interactive` gate as the live-session / `stall` / `queue_state` / `api_retry` /
                // `compacting` / `session_transition` siblings: a non-interactive phone never decodes a
                // spurious `unrecognized_message` from a buggy/hostile daemon that ignored the server-side
                // fan-out gate (fail-closed, defence in depth). Decode-or-drop (AC #4): a malformed payload
                // or a `site` outside the closed set yields null → drop one envelope, the lone collector
                // survives. Routes strictly by the payload's conversation_id, so the row structurally
                // cannot cross-route into another thread (AC #1) — an id no collector observes simply sits
                // unread in the map. Exactly ONE write, unlike the session_transition twin directly above:
                // appendUnrecognizedMessage folds the thread row and that is all — there is deliberately no
                // updateCurrentSessionId sibling here, because the frame carries no session identity (a
                // reader who knows that arm will look for a second write; there isn't one). Inert toward
                // every neighbour (AC #3): it does not tryEmit on liveSessionEvents (a diagnostic row is not
                // a streaming event), does not open, close, or alter a turn (the frame carries no turn_id —
                // the daemon could not attribute one honestly, and opening a turn would wedge the
                // conversation since no turn end follows a message nobody could parse), and touches
                // stalledConversations in NEITHER direction (an unparseable message is not turn forward
                // progress, and clearing here would be a hostile-daemon lever for suppressing the stall
                // indicator) nor any other conversation status. Drop silently — nothing here logs any
                // payload field: `raw`/`message_type` are the most untrusted strings the thread holds, and a
                // logged conversation_id is a cross-conversation correlation leak.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeUnrecognizedMessage(envelope)?.let { (conversationId, row) ->
                        appendUnrecognizedMessage(conversationId, row)
                    }
                }
            }
            TYPE_MODAL_SHOWN, TYPE_MODAL_DISMISSED -> {
                // A v2 modal lifecycle envelope (#437). Same `interactive` gate as the structured-stream,
                // `stall`, and `resync` siblings — a non-interactive phone never decodes a spurious modal
                // from a buggy/hostile daemon that ignored the negotiated set (fail-closed, defence in
                // depth). Decode-or-drop only: unlike the TYPE_TURN_STATE arm this does NOT fold a thread
                // row (modals are not rows and carry no conversation_id) and does NOT clear a stall (a
                // `modal_shown` means claude is *waiting* for input — not turn forward-progress). A
                // malformed payload decodes to null and is dropped so the single inbound consumer survives
                // (AC #3); tryEmit is non-blocking (DROP_OLDEST) so the shared collector is never stalled.
                // Drop silently — title/prompt/option-label are operator content; nothing here logs them.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    decodeModalEvent(envelope)?.let { mutableModalEvents.tryEmit(it) }
                }
            }
            TYPE_RESYNC -> {
                // Replay resync (#417): the daemon's signal that the advertised `last_event_id` aged out
                // of its bounded ring (pyrycode#646/#647), so gap-free in-ring replay is impossible. Same
                // `interactive` gate as the live-session, `stall`, and recordReplayCursor arms — a
                // non-interactive phone never advertised a cursor, so a spurious `resync` from a buggy/
                // hostile daemon is ignored (fail-closed, defence in depth). The reset is unconditional on
                // the type match: the cursor is process-global (not per-conversation), so a malformed/
                // absent conversation_id still clears it — the safety action (avoid mis-resuming; the next
                // reconnect then advertises a fresh position, #416) must not depend on payload shape. The
                // gap surface is conditional on a decodable conversation_id (it routes the ReplayGap).
                // recordReplayCursor ran first (above) but a `resync` carries no event_id, so it recorded
                // nothing — no record-then-reset conflict. Drop silently — nothing here logs the payload.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    replayCursor.reset()
                    resyncConversationId(envelope)?.let { id ->
                        mutableLiveSessionEvents.tryEmit(LiveSessionEvent.ReplayGap(id))
                    }
                }
            }
            // Any other type is a no-op here: single-row conversation deltas (#318 → #314) extend
            // this `when` in their own slice. `backfill_done` ({delivered}) needs no action — the
            // `message_chunk` already delivered the full history; the count is informational only.
            else -> Unit
        }
    }

    /**
     * Record the envelope-level replay cursor (#412), the **first** action in [onInbound] — before the
     * `when` demux. Envelope-level and type-agnostic: it reads [Envelope.eventId] directly, so a frame
     * with a valid `event_id` but a malformed structured *payload* still advances the cursor (the
     * durable event occurred; the cursor marks position, not decodability). Gated on the negotiated
     * `interactive` capability, symmetric with the structured-stream and `stall` arms: a buggy/hostile
     * authenticated daemon that ignored the negotiated set cannot advance a cursor a non-interactive
     * phone will never advertise (#413's advertise is itself `interactive`-gated) — defence in depth.
     * A non-interactive frame carries no `event_id`, so [Envelope.eventId] is `null` and nothing is
     * recorded (AC #3). [ReplayCursor.record] is throw-free (set-membership + a pure max-fold), so this
     * never kills the single inbound collector; it is a pure side-write with no feedback into delivery.
     */
    private fun recordReplayCursor(envelope: Envelope) {
        if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
            envelope.eventId?.let { replayCursor.record(it) }
        }
    }

    /**
     * Decode one v2 structured live-session envelope (#385) to its typed [LiveSessionEvent], or
     * **null** when it cannot be surfaced. Selects the DTO by [Envelope.type], decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson], and maps via `toEvent()`. The
     * whole body is wrapped in one `try`/`catch (IllegalArgumentException)`:
     * [kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException], so a malformed /
     * partially-decodable payload (missing or wrong-typed field, AC #4) yields `null`. A `turn_state`
     * whose `state` is unrecognized also yields `null` (its mapper returns null, AC #3). Both drop the
     * one envelope; the lone inbound collector survives and the next envelope is processed normally.
     * Mirrors the `TYPE_MESSAGE` arm's drop idiom — **nothing here logs the payload** (the text / tool
     * summaries may be sensitive).
     */
    private fun decodeLiveSessionEvent(envelope: Envelope): LiveSessionEvent? =
        try {
            when (envelope.type) {
                TYPE_TURN_STATE -> MobileJson.decodeFromJsonElement<TurnStatePayloadDto>(envelope.payload).toEvent()
                TYPE_ASSISTANT_DELTA -> MobileJson.decodeFromJsonElement<AssistantDeltaPayloadDto>(envelope.payload).toEvent()
                TYPE_TOOL_USE -> MobileJson.decodeFromJsonElement<ToolUsePayloadDto>(envelope.payload).toEvent()
                TYPE_TOOL_RESULT -> MobileJson.decodeFromJsonElement<ToolResultPayloadDto>(envelope.payload).toEvent()
                TYPE_TURN_END -> MobileJson.decodeFromJsonElement<TurnEndPayloadDto>(envelope.payload).toEvent()
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `stall` envelope (#395) to its conversation id, or **null** when it cannot be
     * read. Decodes the untrusted [Envelope.payload] through the single configured [MobileJson]; the
     * whole body is one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed
     * payload — a missing or wrong-typed `conversation_id` (AC #3) — yields `null`, dropping the one
     * envelope while the lone inbound collector survives. Mirrors [decodeLiveSessionEvent]'s drop
     * idiom — **nothing here logs the payload** (uniform with every other `onInbound` arm).
     */
    private fun decodeStall(envelope: Envelope): String? =
        try {
            MobileJson.decodeFromJsonElement<StallPayloadDto>(envelope.payload).conversationId
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `queue_state` envelope (#460) to its conversation id and ordered backlog, or
     * **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through the single
     * configured [MobileJson] and maps via `toQueue()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing/wrong-typed `conversation_id`, a
     * bad item (`queued_msg_id` as a string, missing `text`), or an unparseable item `ts` — yields
     * `null`, dropping the one envelope while the lone inbound collector survives (AC #4). Mirrors
     * [decodeStall]'s drop idiom — **nothing here logs the payload** (queued `text` is user content).
     */
    private fun decodeQueueState(envelope: Envelope): Pair<String, List<QueuedMessage>>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<QueueStatePayloadDto>(envelope.payload)
            dto.conversationId to dto.toQueue()
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `model_list` envelope (#791) to its routing conversation id and retained
     * [ModelMenu], or **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through
     * the single configured [MobileJson] and maps via `toMenu()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing/wrong-typed `conversation_id` or
     * `dropped_models`, a `models` or `effort_levels` that is explicitly `null` (both are always arrays
     * on the wire), or a row missing one of its required strings — yields `null`, dropping the one
     * envelope while the lone inbound collector survives. There is no partial menu: a bad row fails the
     * whole frame, and the caller's previously retained menu stands because nothing was written.
     *
     * Because `toMenu()` is **total**, structural malformation is the only null path — there is no
     * unrecognized *value* to reject, unlike [decodeLiveSessionEvent]. Returning a [Pair] of the routing
     * id and the already-mapped domain value keeps the untrusted wire DTO from escaping this boundary,
     * matching every sibling decoder. Mirrors [decodeStall] / [decodeQueueState]'s drop idiom —
     * **nothing here logs the payload**, which is mandatory rather than stylistic: the rows are
     * claude-authored text the daemon does not sanitize, and the caught throwable (kotlinx-serialization
     * can quote the offending input in its message) is discarded rather than surfaced.
     */
    private fun decodeModelList(envelope: Envelope): Pair<String, ModelMenu>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ModelListPayloadDto>(envelope.payload)
            dto.conversationId to dto.toMenu()
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `api_retry` envelope (#593) to its conversation id and mapped [ApiRetryStatus], or
     * **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through the single
     * configured [MobileJson] and maps via `toStatus()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing field, or one whose JSON shape
     * cannot be read as its declared type — yields `null`, dropping the one envelope while the lone
     * inbound collector survives (AC #3). Because `toStatus()` is **total**, structural malformation is the only
     * null path: an undocumented counter shape maps to [ApiRetryStatus.AttemptUnknown] rather than
     * discarding a real retry onset. Mirrors [decodeStall] / [decodeQueueState]'s drop idiom —
     * **nothing here logs the payload** (uniform with every other `onInbound` arm).
     */
    private fun decodeApiRetry(envelope: Envelope): Pair<String, ApiRetryStatus>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ApiRetryPayloadDto>(envelope.payload)
            dto.conversationId to dto.toStatus()
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `compacting` envelope (#596) to its conversation id and edge bool, or **null** when
     * it cannot be read. Decodes the untrusted [Envelope.payload] through the single configured
     * [MobileJson]; returning a [Pair] of already-trusted primitives rather than the DTO keeps the
     * untrusted wire type from escaping the boundary, matching every sibling decoder. The whole body is
     * one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed
     * payload — a missing field, or one whose JSON shape cannot be read as its declared type — yields
     * `null`, dropping the one envelope while the lone inbound collector survives (AC #5). Structural
     * malformation is the **only** null path: there is no unrecognized *value* to reject (`active` is a
     * bool), so unlike [decodeLiveSessionEvent] no mapper drop exists here. Mirrors [decodeStall] /
     * [decodeApiRetry]'s drop idiom — **nothing here logs the payload** (uniform with every other
     * `onInbound` arm).
     */
    private fun decodeCompacting(envelope: Envelope): Pair<String, Boolean>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<CompactingPayloadDto>(envelope.payload)
            dto.conversationId to dto.active
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `session_transition` envelope (#336) to its routing [conversationId] and the mapped
     * [ThreadItem.SessionBoundary], or **null** when it cannot be folded. Decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson] and maps via `toBoundary()`. The whole
     * body is one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed payload
     * — a missing/wrong-typed required field or an unparseable `occurred_at` — yields `null`, dropping the
     * one envelope while the lone inbound collector survives (AC #5). An **unrecognized `reason`** is a
     * distinct path: `toBoundary()` returns `null` (no throw), so the one envelope drops the same way
     * (AC #3). Mirrors [decodeStall] / [decodeQueueState]'s drop idiom — **nothing here logs the payload**
     * (conversation_id / session ids / workspace_cwd are sensitive; a logged or mis-routed boundary is a
     * cross-conversation leak).
     */
    private fun decodeSessionTransition(envelope: Envelope): Pair<String, ThreadItem.SessionBoundary>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(envelope.payload)
            dto.toBoundary()?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `unrecognized_message` envelope (#609) to its routing [conversationId] and the mapped
     * [ThreadItem.UnrecognizedMessage], or **null** when it cannot be folded. Decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson] and maps via `toRow()`. The whole body
     * is one `try`/`catch (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing or wrong-typed required field —
     * yields `null`, dropping the one envelope while the lone inbound collector survives (AC #4). A
     * **`site` outside the closed set** is a distinct path: `toRow()` returns `null` (no throw), so the one
     * envelope drops the same way. Mirrors [decodeSessionTransition]'s drop idiom.
     *
     * This is the sole boundary at which the untrusted payload becomes a typed value, and the DTO never
     * escapes it — callers hold only the domain type. The two **client-owned** fields are stamped here,
     * not in the mapper: the row id from [unrecognizedRowId] (see its KDoc for why a monotonic counter is
     * structurally sufficient) and the arrival instant from [Clock.System.now], the established
     * locally-assembled-row clock ([applyToolUse]) — the wire carries no timestamp. The `"unrecognized-"`
     * prefix is for debuggability only; it is **not** load-bearing for collision-avoidance, since ids are
     * compared only within their own [ThreadItem] type and `ThreadScreen` namespaces each type's key.
     *
     * **Nothing here logs any payload field** — `raw` and `message_type` are unbounded model-adjacent JSON
     * (the most untrusted strings the thread holds) and a logged conversation_id is a cross-conversation
     * correlation leak.
     */
    private fun decodeUnrecognizedMessage(envelope: Envelope): Pair<String, ThreadItem.UnrecognizedMessage>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<UnrecognizedMessagePayloadDto>(envelope.payload)
            dto
                .toRow(id = "unrecognized-${unrecognizedRowId.incrementAndGet()}", occurredAt = Clock.System.now())
                ?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 modal envelope (#437) to its typed [ModalEvent], or **null** when it cannot be
     * surfaced. Selects the DTO by [Envelope.type], decodes the untrusted [Envelope.payload] through the
     * single configured [MobileJson], and maps via `toEvent()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed / partially-decodable payload (missing or wrong-typed
     * required field, AC #3) yields `null`, dropping the one envelope while the lone inbound collector
     * survives. Both mappers are **total** — `class`/`source`/`outcome` are carried verbatim, so there is
     * no "unrecognized value" drop (AC #3). Mirrors [decodeStall] / [decodeLiveSessionEvent]'s drop idiom
     * — **nothing here logs the payload** (title/prompt/option-label are operator content).
     */
    private fun decodeModalEvent(envelope: Envelope): ModalEvent? =
        try {
            when (envelope.type) {
                TYPE_MODAL_SHOWN -> MobileJson.decodeFromJsonElement<ModalShownPayloadDto>(envelope.payload).toEvent()
                TYPE_MODAL_DISMISSED -> MobileJson.decodeFromJsonElement<ModalDismissedPayloadDto>(envelope.payload).toEvent()
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Read the inline `conversation_id` of a `resync` marker (#417) as a JSON string, or **null** when
     * it is absent / not a string / the payload is not a JSON object. Pure structural access off
     * [Envelope.payload] — no `decodeFromJsonElement`, no DTO (mirrors the server's payload-less
     * inline-struct precedent) — so it cannot throw and cannot kill the single inbound collector. Used
     * only to tag the surfaced [LiveSessionEvent.ReplayGap] for routing; the id is never trusted beyond
     * that (the cursor reset is process-global and does not read it). Nothing here logs the payload.
     */
    private fun resyncConversationId(envelope: Envelope): String? =
        (envelope.payload as? JsonObject)
            ?.get("conversation_id")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content

    /**
     * Map a server `error` reply payload (#346) to the domain exception the awaiting suspend throws.
     * Decodes [ErrorPayload] through [MobileJson]; `conversation.not_found` becomes the
     * [IllegalArgumentException] the [ConversationRepository] contract pins for an unknown
     * conversation (AC #3, mirroring the fake's type), and every other code becomes a
     * [RelayErrorException] carrying the structured `code`/`retryable`/`message` (AC #4). A
     * malformed/undecodable payload yields a fallback [RelayErrorException] so the waiter is always
     * unblocked rather than left hanging. Never logs the payload (message content stays off the log).
     */
    private fun mapError(payload: JsonElement): Throwable {
        val error =
            try {
                MobileJson.decodeFromJsonElement<ErrorPayload>(payload)
            } catch (e: IllegalArgumentException) {
                return RelayErrorException(code = ERROR_MALFORMED_REPLY, retryable = false, message = "Malformed error reply")
            }
        return if (error.code == ERROR_CONVERSATION_NOT_FOUND) {
            IllegalArgumentException("Unknown conversation: ${error.message}")
        } else {
            RelayErrorException(code = error.code, retryable = error.retryable, message = error.message)
        }
    }

    /**
     * Most-recent-by-timestamp fold for [conversationId]'s last-message preview ([lastMessages]).
     * Atomic check-then-replace: replace the stored entry **iff** [message]'s timestamp is strictly
     * greater, so out-of-order older arrivals and re-delivered duplicates are no-ops (no re-emit).
     * Called by both the live `message` collector arm and [sendMessage]'s confirmed insert.
     */
    private fun recordLastMessage(
        conversationId: String,
        message: Message,
    ) {
        lastMessages.update { current ->
            val existing = current[conversationId]
            if (existing != null && message.timestamp <= existing.timestamp) {
                current
            } else {
                current + (conversationId to message)
            }
        }
    }

    /**
     * Register a deferred for [request]'s reply, send the request, and await the correlated
     * `ack`/`error` (#346) — the reusable request↔reply primitive #347/#348 inherit. Registers
     * **before** sending (no lost-reply race), throws [IllegalStateException] without awaiting if the
     * pump is not `Open` ([SessionPump.send] returns `false`, AC #4), and removes the entry in a
     * `finally` covering success, error, and caller cancellation. Returns the reply payload (the
     * empty `{}` for an `ack`); rethrows the collector's exceptional completion on an `error`.
     */
    private suspend fun sendAndAwaitReply(request: Envelope): JsonElement {
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[request.id] = deferred
        return try {
            check(pump.send(request)) { "${request.type} not sent: session not connected" }
            deferred.await()
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    /**
     * Teardown sweep (#488): complete every still-registered [pendingRequests] deferred exceptionally
     * and remove it, so an awaiting [sendAndAwaitReply] caller (a tapped permission answer, a sent
     * message, a promote, …) throws **promptly** instead of suspending forever once the connection
     * tears down. Runs in the [init] collector's `finally`, on scope cancellation (the primary trigger,
     * [RelayRepositoryCoordinator.teardownActive]) or `pump.inbound` completing.
     *
     * Fails with a plain [IllegalStateException] — the exact type [sendAndAwaitReply]'s not-connected
     * `check(...)` already surfaces, so every caller handles teardown-mid-await through the one failure
     * mode it already has. Deliberately **not** a [kotlinx.coroutines.CancellationException] (which
     * `extends IllegalStateException` on the JVM): completing with cancellation would make `await()`
     * read as the caller's own scope dying, losing the surfaceable error. **Non-suspending**
     * ([CompletableDeferred.completeExceptionally] returns `Boolean`), so it runs to completion even
     * inside the cancelling collector coroutine, and idempotent against the caller's own
     * `finally { remove }` (completing an already-removed deferred is a no-op). Emits **no log**: the
     * swept reply payloads are discarded and the message is a static literal (never-log contract).
     */
    private fun failAllPending() {
        val cause = IllegalStateException(PENDING_REQUEST_TORN_DOWN)
        val iterator = pendingRequests.values.iterator()
        while (iterator.hasNext()) {
            iterator.next().completeExceptionally(cause)
            iterator.remove()
        }
    }

    /**
     * Append [rows] (`conversationId -> Message`) into [threadByConversation] as [ThreadItem.MessageItem]
     * rows in one atomic [MutableStateFlow.update], preserving order and deduping by `message_id` — the
     * per-row fold is [withMessage], which #645 lifted out of this class so the history reduction runs
     * the **same** fold rather than a second copy of it (see `HistoryPageReducer`). Batching a whole
     * chunk into one update avoids emitting an intermediate list per row. No-op on an empty batch so a
     * malformed/empty chunk never re-emits.
     */
    private fun appendMessages(rows: List<Pair<String, Message>>) {
        if (rows.isEmpty()) return
        threadByConversation.update { current ->
            val updated = current.toMutableMap()
            for ((conversationId, message) in rows) {
                updated[conversationId] = updated[conversationId].orEmpty().withMessage(message)
            }
            updated
        }
    }

    /**
     * Append [boundary] to [conversationId]'s thread in one atomic [MutableStateFlow.update] (#336): a
     * pure end-append in arrival order, **no dedup** — a `session_transition` carries no row id and the
     * repository is connection-scoped (#351), so within a connection arrival order is correct (the same
     * posture as [applyAssistantDelta]'s arrival-order concatenation; cross-reconnect replay dedup is a
     * #402 concern, deferred). Routes strictly into [conversationId]'s slice, so a boundary can only ever
     * surface in `observeMessages(conversationId)` — never cross-routed (AC #1). The session ids /
     * `workspaceCwd` are carried inside the typed [boundary] and never logged here (Security review).
     */
    private fun appendSessionBoundary(
        conversationId: String,
        boundary: ThreadItem.SessionBoundary,
    ) {
        threadByConversation.update { it + (conversationId to (it[conversationId].orEmpty() + boundary)) }
    }

    /**
     * Append [row] to [conversationId]'s thread in one atomic [MutableStateFlow.update] (#609): a pure
     * end-append in arrival order into the same [threadByConversation] the live `message` and `tool_use`
     * arms write, so the row interleaves with everything else in the thread (AC #1). Routes strictly into
     * [conversationId]'s slice, so it can only ever surface in `observeMessages(conversationId)` — never
     * cross-routed. The untrusted `raw` / `messageType` ride inside the typed [row] and are never logged
     * here (Security review).
     *
     * **No dedup, and deliberately a separate function from [appendSessionBoundary] rather than a shared
     * `appendThreadItem`.** The two share an implementation but not a contract, and the difference is
     * exactly the rationale: [appendSessionBoundary] does not dedup because there is *nothing to dedup on*
     * (the wire carries no row id), whereas this one does not dedup because **dedup would destroy the
     * signal** — how often this frame fires is the number that tells someone to go fix something, so
     * merging repeats hides it. The refusal is the point, not an oversight; the daemon does no dedup on the
     * wire either. A shared helper would have to carry both rationales in one KDoc, and a later change to
     * one contract would silently change the other.
     *
     * This cuts against the two nearest folds — [appendMessages] dedups by `message_id` and [applyToolUse]
     * is idempotent on a repeat id. [appendSessionBoundary]'s pure end-append is the one followed here.
     */
    private fun appendUnrecognizedMessage(
        conversationId: String,
        row: ThreadItem.UnrecognizedMessage,
    ) {
        threadByConversation.update { it + (conversationId to (it[conversationId].orEmpty() + row)) }
    }

    /**
     * Fold a `session_transition`'s [newSessionId] into the list [projection] entry for [conversationId]
     * (#578) — the sibling write to [appendSessionBoundary], resolving the live session identity the v2
     * `conversations` summary omits (so a session-scoped frame like `set_session_settings` targets the
     * real session instead of the defaulted empty id). Field-updates an **existing** entry only: a
     * [List.map] over the current list, so an unknown [conversationId] yields an element-equal list
     * ([StateFlow] conflation ⇒ no re-emit, **no phantom conversation** — unlike [appendSessionBoundary]
     * the list projection must not gain a phantom entry, so the `else it` identity branch *is* the
     * absent-conversation guard), and a `null` (pre-first-snapshot) projection stays `null`. Written
     * **verbatim for every reason** (`clear` carries the freshly-rotated-to id; `idle_evict` carries the
     * evicted id unchanged ⇒ element-equal no-op in the common case), mirroring [appendSessionBoundary]'s
     * copy-through posture. The atomic [MutableStateFlow.update] CAS retry-merges against a concurrent
     * authoritative `conversations` snapshot rather than clobbering it, as [upsertConversation] does.
     * [newSessionId] is sensitive and is never logged (Security review).
     */
    private fun updateCurrentSessionId(
        conversationId: String,
        newSessionId: String,
    ) {
        projection.update { current ->
            current?.map { if (it.id == conversationId) it.copy(currentSessionId = newSessionId) else it }
        }
    }

    /**
     * Open a live tool-call row for a `tool_use` (#387): append a `Running` [Role.Tool] [Message]
     * carrying the tool name + input, keyed by [LiveSessionEvent.ToolUse.toolUseId] (the correlation
     * handle and the row's [Message.id]). One atomic [MutableStateFlow.update] into the same
     * [threadByConversation] the live `message` arm writes, so the row interleaves by **arrival
     * order** with messages (AC #4). **Idempotent on a repeat id:** if a [Role.Tool] row with this id
     * already exists (possibly already completed by an earlier `tool_result`), it is left untouched —
     * a duplicate `tool_use` never adds a second row nor resets a finished one to `Running` (AC #3).
     * The `&& role == Role.Tool` match namespaces tool rows so a `toolUseId` can never clobber a real
     * `message_id` row. The fields the UI ignores ([Message.content] = the tool name, a non-empty
     * fallback; [Message.timestamp] = [Clock.System.now], the established locally-assembled-row clock —
     * thread order is arrival order, never a timestamp sort) mirror [sendMessage]'s posture. The tool
     * name/input/output are carried **verbatim** — never trimmed, parsed, or logged (Security review).
     *
     * The row logic itself moved to [withToolUse] in #645, as did the three sibling folds below, so the
     * history reduction runs these exact folds instead of a second copy — see `HistoryPageReducer`. Each
     * method here is now just the projection write: read this conversation's slice, fold, put it back.
     * The clock is the one thing the two lanes differ on, which is why it is a parameter there.
     */
    private fun applyToolUse(event: LiveSessionEvent.ToolUse) {
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withToolUse(event, Clock.System.now()))
        }
    }

    /**
     * Complete a live tool-call row for a `tool_result` (#387): update the matching [Role.Tool] row in
     * place — position and `timestamp` preserved — attaching the output and flipping the status to
     * [ToolCallStatus.Failed] when [LiveSessionEvent.ToolResult.isError], else [ToolCallStatus.Done]
     * (AC #2). One atomic [MutableStateFlow.update]. **If no matching row exists, no-op:** a
     * `tool_result` with no prior `tool_use` — including a result arriving before its use
     * (out-of-order) — is dropped, leaving no orphan half-row (AC #3). A duplicate `tool_result`
     * re-applies the same in-place update (idempotent / last-write-wins, one row). The result summary
     * is carried **verbatim** — never trimmed, parsed, or logged (Security review).
     */
    private fun applyToolResult(event: LiveSessionEvent.ToolResult) {
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withToolResult(event))
        }
    }

    /**
     * Fold one `assistant_delta` into the conversation's live streaming assistant row (#337). The
     * first delta of a turn opens a [Role.Assistant] [Message] keyed by
     * [LiveSessionEvent.AssistantDelta.turnId] with [Message.isStreaming] `= true`; each later delta
     * for that turn **appends** its text in place, keeping the row's id and position. One atomic
     * [MutableStateFlow.update] into the same [threadByConversation] the live `message` and tool
     * arms write, so the assistant text interleaves by **arrival order** with messages and tool rows
     * (AC #4). The `&& role == Role.Assistant` match namespaces this row so a `turnId` can never
     * clobber a `message_id` or `toolUseId` row.
     *
     * **Arrival-order concatenation, by design.** The wire delivers a turn's deltas in
     * [LiveSessionEvent.AssistantDelta.seq] order over the single ordered inbound stream, and a fresh
     * repository is built per connection (#351), so within a connection arrival order *is* seq order
     * and concatenation is correct. Cross-reconnect replay de-dup ([LiveSessionEvent.AssistantDelta.seq]
     * as the idempotency key) is a #402 concern, deferred until the reconnect/replay path lands. The
     * delta text is carried **verbatim** — never trimmed, parsed, or logged (it may be sensitive).
     *
     * An interactive phone receives **only** the structured stream, never a whole-turn `message` for
     * the same turn (the server's fan-out is capability-exclusive: pyrycode `interactive_turn_v2` vs
     * `assistant_turn_v2`), so this folded row is the canonical assistant reply — there is no `message`
     * echo to de-dup against.
     */
    private fun applyAssistantDelta(event: LiveSessionEvent.AssistantDelta) {
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withAssistantDelta(event, Clock.System.now()))
        }
    }

    /**
     * Finalize the live streaming assistant row on `turn_end` (#337): flip the matching
     * [Role.Assistant] row (keyed by [LiveSessionEvent.TurnEnd.turnId]) to [Message.isStreaming]
     * `= false` in place, so the thread renders the completed reply as static markdown rather than the
     * streaming caret view. One atomic [MutableStateFlow.update]. **No-op when no streaming assistant
     * row exists for the turn** — a tool-only or empty turn carries no assistant text (AC #3), and a
     * duplicate `turn_end` re-applies the same flip (idempotent). `turn_end` carries no final text, so
     * nothing is appended here; [LiveSessionEvent.TurnEnd.stopReason] is not consumed by this slice
     * (turn-outcome mapping is a later consumer concern).
     */
    private fun finalizeAssistantTurn(event: LiveSessionEvent.TurnEnd) {
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withFinalizedTurn(event))
        }
    }

    /**
     * Confirmed-insert [conversation] into the list [projection] (#347): an atomic
     * [MutableStateFlow.update] CAS upsert — replace the entry with the same `id` in place, else
     * append — so a concurrent authoritative `conversations` snapshot retry-merges rather than being
     * lost, and a re-delivered create is idempotent. Folding into the `null` (pre-first-snapshot)
     * projection yields a single-element list, which [observeConversations] then emits (AC #2).
     */
    private fun upsertConversation(conversation: Conversation) {
        projection.update { current ->
            val existing = current.orEmpty()
            val index = existing.indexOfFirst { it.id == conversation.id }
            if (index >= 0) {
                existing.toMutableList().apply { this[index] = conversation }
            } else {
                existing + conversation
            }
        }
    }

    /**
     * Apply a `workspace_updated` notification (#721) to the list [projection]: every conversation whose
     * [Conversation.cwd] equals [path] takes [label] — a string replacing the stored display name, a
     * `null` clearing it. A workspace is a **folder**, and N conversations may share one, so this is a
     * fan-out over the whole projection rather than a keyed upsert; archived rows are included, because
     * archiving a conversation does not un-name its folder.
     *
     * Direct sibling of [updateCurrentSessionId], and it inherits that shape's three properties:
     * [path] is matched by **exact string equality** — no trim, no normalization, no filesystem access
     * (the protocol compares the path as bytes, so two paths differing by a trailing separator are
     * distinct workspaces and normalizing would merge workspaces the daemon keeps apart); the `else it`
     * identity branch means a path matching no row is a genuine no-op, since `map` then returns an
     * element-equal list and [StateFlow] conflation suppresses re-emission — **no phantom conversation**,
     * as the frame carries a path and not a conversation; and a `null` (pre-first-snapshot) projection
     * stays `null`, a push that arrives before the first snapshot having no rows to label.
     *
     * The atomic [MutableStateFlow.update] CAS is load-bearing rather than stylistic: [projection] is
     * written from caller coroutines too (the correlated mutations, via [upsertConversation]), so a
     * `.value = …` read-modify-write would open a real check-then-mutate window against a concurrent
     * snapshot or upsert.
     *
     * [path] and [label] are never logged (Security review): the path is a filesystem location on the
     * daemon's host and the label is operator-authored text, and the daemon keeps both out of its own
     * records for this verb. [label] is stored **verbatim** — never trimmed or truncated, and a blank is
     * not folded to `null`; the daemon's 128-byte bound is a size limit and not a safety property, and
     * safe rendering of this opaque text belongs to the consuming slices (#722, #641).
     */
    private fun applyWorkspaceLabel(
        path: String,
        label: String?,
    ) {
        projection.update { current ->
            current?.map { if (it.cwd == path) it.copy(workspaceLabel = label) else it }
        }
    }

    /**
     * Remove [conversationId] from **all three** read projections after a confirmed `delete` (#532) —
     * the contrast to [upsertConversation]. The [ConversationRepository.delete] contract's
     * post-condition spans all three streams, and the fake achieves it by removing its *unified* record
     * ([FakeConversationRepository]'s `state - conversationId` empties list, messages, and last-message
     * at once); the remote holds three *separate* `StateFlow`s read independently by [observeMessages] /
     * [observeLastMessage], so a list-only removal would leave those streams emitting a hard-deleted
     * conversation's rows. Clearing all three is *completing* the delete, not scope creep. Idempotent by
     * construction: `List.filterNot` returns an element-equal list when the id is absent, and
     * `Map - missingKey` an equals-identical map, so [StateFlow] conflation makes deleting an
     * already-absent id re-emit nothing on any of the three.
     */
    private fun removeConversation(conversationId: String) {
        projection.update { current -> current?.filterNot { it.id == conversationId } }
        threadByConversation.update { it - conversationId }
        lastMessages.update { it - conversationId }
    }

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        flow {
            // Request on every subscription: redundant requests are absorbed by StateFlow conflation,
            // and re-subscribing (e.g. on lifecycle resume) naturally re-issues. A pre-Open send returns
            // false and is dropped — the coordinator (#302) wires this against an Open pump.
            pump.send(listConversationsRequest())
            emitAll(projection.filterNotNull().map { project(it, filter) })
        }

    /** Apply the [ConversationFilter] then order most-recently-used first — mirrors the fake exactly. */
    private fun project(
        conversations: List<Conversation>,
        filter: ConversationFilter,
    ): List<Conversation> =
        conversations
            .filter { conversation ->
                when (filter) {
                    ConversationFilter.All -> true
                    ConversationFilter.Channels -> conversation.isPromoted && !conversation.archived
                    ConversationFilter.Discussions -> !conversation.isPromoted && !conversation.archived
                    ConversationFilter.Archived -> conversation.archived
                }
            }.sortedByDescending { it.lastUsedAt }

    private fun listConversationsRequest(): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_LIST_CONVERSATIONS,
            ts = Clock.System.now().toString(),
            payload = JsonObject(emptyMap()),
        )

    /**
     * Recently-used workspace folders (#565), a **one-shot request/reply** read verb modeled on
     * [observeConversations]'s `list_conversations` but **without** a push projection — #888 is one-shot
     * daemon-side (no live re-emit on change exists to subscribe to). Each collection issues one
     * `recent_workspaces` request (empty `{}` payload) and awaits the correlated `recent_workspaces_list`
     * reply via [sendAndAwaitReply], so a fresh picker open re-fetches (cold, per-collector; no caching,
     * no cross-collection dedup, no projection touched).
     *
     * Emits the reply's paths in **wire order** — ordering and dedup are daemon-authoritative (#888), so
     * the client does **not** re-sort — after excluding the two "no bound workspace" sentinels the
     * interface contract mandates: the empty string `""` (via [String.isNotBlank], a safe superset the
     * daemon already trims) and [DEFAULT_SCRATCH_CWD] (load-bearing — #888 folds distinct `Cwd` values
     * and does **not** strip scratch).
     *
     * Fails **closed to empty**: `.catch { emit(emptyList()) }` degrades every non-cancellation throwable
     * to one empty emission (AC #4) — the not-`Open` [IllegalStateException] from [sendAndAwaitReply]'s
     * `check`, a server [RelayErrorException] (this verb names no conversation, so there is **no**
     * `not_found` path), a teardown-mid-await [IllegalStateException] (#488 `failAllPending`), and a
     * malformed-reply decode exception. [kotlinx.coroutines.flow.catch] is cancellation-transparent — it
     * does not swallow the [kotlinx.coroutines.CancellationException] a lifecycle-STOP / sheet-dismiss
     * raises — so a cancelled collect stops cleanly with no spurious empty emit.
     */
    override fun recentWorkspaces(): Flow<List<String>> =
        flow {
            val reply = sendAndAwaitReply(recentWorkspacesRequest())
            val list = MobileJson.decodeFromJsonElement<RecentWorkspacesListPayloadDto>(reply)
            emit(list.workspaces.map { it.path }.filter { it.isNotBlank() && it != DEFAULT_SCRATCH_CWD })
        }.catch { emit(emptyList()) }

    private fun recentWorkspacesRequest(): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_RECENT_WORKSPACES,
            ts = Clock.System.now().toString(),
            payload = JsonObject(emptyMap()),
        )

    /**
     * One backward step of [conversationId]'s history walk over v2 `request_history` (#623, server
     * pyrycode#2113/#2116). The [rename] shape — encode → [sendAndAwaitReply] → typed-decode — and,
     * since #645, **one** state fold: the decoded page goes through [mergeHistoryPage] into
     * [conversationId]'s thread before it is returned. Still nothing is cached, and the page itself is
     * returned unchanged for the walking caller's `cursor` / `atStart`.
     *
     * [cursor] and [limit] are forwarded **verbatim** (as [rename] forwards the dialog's name). The
     * cursor is opaque — never parsed, rebuilt or validated here — and the daemon re-validates both:
     * a cursor that does not decode, was minted for another conversation, or names a position no
     * longer in the log is one merged `history.invalid_cursor`, and a negative limit is
     * `history.invalid_page_size`.
     *
     * **Deliberately no `.catch {}`**, unlike its one-shot sibling [recentWorkspaces], which fails
     * closed to empty for a picker: every failure must reach the caller so a walk can tell the one
     * **retryable** code (`history.unavailable`) from the three permanent ones. [mapError] needs no
     * new mapping — it is already generic over unrecognised codes, and it already turns the daemon's
     * `conversation.not_found` into the [IllegalArgumentException] the [ConversationRepository]
     * contract pins for an unknown conversation. So: [IllegalStateException] when the session is not
     * connected or tears down mid-await (#488), [IllegalArgumentException] for an unknown
     * conversation, [de.pyryco.mobile.data.network.RelayErrorException] carrying `code`/`retryable`
     * for every `history.*` code, and the #318 decode exception for a malformed page. Each fails
     * **only this ask** — the failure lands on this caller's deferred, the shared inbound collector
     * never sees it, and no other conversation's projection changes.
     *
     * Emits no log on any branch, like the rest of this class: the cursor and the entries' `type` /
     * `payload` are replayed content and never reach Logcat.
     */
    override suspend fun requestHistory(
        conversationId: String,
        cursor: String,
        limit: Int,
    ): HistoryPage {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_REQUEST_HISTORY,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RequestHistoryPayloadDto(conversationId = conversationId, cursor = cursor, limit = limit),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the
        // {entries,cursor,at_start} page; a malformed one throws here and mutates nothing.
        val reply = sendAndAwaitReply(request)
        val page = MobileJson.decodeFromJsonElement<HistoryPagePayloadDto>(reply).toHistoryPage()
        mergeHistoryPage(conversationId, page)
        return page
    }

    /**
     * Fold one decoded [page] into [conversationId]'s thread (#645) — the write #623's [requestHistory]
     * KDoc promised this ticket would add, and the **only** projection this verb touches. The page is
     * still returned to the caller unchanged: a walking caller needs `cursor` / `atStart` to decide
     * whether to ask again, and #646 owns that decision.
     *
     * The reduction and the merge both run **inside** the [MutableStateFlow.update] lambda, and that is
     * load-bearing rather than stylistic: reading the current thread, merging and assigning are one
     * check-then-act, so hoisting them out would silently lose a concurrent live append every time the
     * CAS retried. The cost of re-running a pure reduction on a retry is the right trade.
     *
     * Routes **strictly into [conversationId]'s slice** — the conversation the client asked about — and
     * never reads an entry payload's own `conversation_id`, so a page structurally cannot write into
     * another conversation's thread. [reduceHistoryPage] returns rows carrying no conversation identity
     * at all, which is what makes that a property of the types rather than of a check.
     *
     * Gated on the negotiated `interactive` capability exactly as the live arms are, arm for arm: the six
     * structured types reduce only when it was negotiated, `message` / `send_message` always do. The
     * daemon's `request_history` handler has no such gate, so this is the client's fail-closed half.
     *
     * Emits no log on any branch, like the rest of this class.
     */
    private fun mergeHistoryPage(
        conversationId: String,
        page: HistoryPage,
    ) {
        if (page.entries.isEmpty()) return
        val interactive = CAPABILITY_INTERACTIVE in negotiatedCapabilities()
        threadByConversation.update { current ->
            val existing = current[conversationId].orEmpty()
            current + (conversationId to existing.mergeHistoryRows(reduceHistoryPage(page.entries, interactive)))
        }
    }

    /**
     * The run configuration of [conversationId]'s session over v2 `request_session_settings` (#590,
     * daemon pyrycode#1610/#2449/#2510). A cold per-collector read that re-issues on every trigger and
     * folds **nothing** — no projection on this class holds a [SessionSettings], so there is no stale
     * value to invalidate and no slot for a late reply to land in.
     *
     * Reads the conversation's own [settingsRevision] slice, so a bump for **another** conversation does
     * not re-read this one; [distinctUntilChanged] means a value-identical re-emission does not either.
     * [flatMapLatest] is what makes a superseded read harmless: a new trigger **cancels** the in-flight
     * one before starting the next, so a reply that arrives late has no collector to reach and its
     * deferred is already deregistered by [sendAndAwaitReply]'s `finally`.
     *
     * The [onStart] `null` is not cosmetic. It resets the reading to *unavailable* at the head of every
     * subscription, which is what keeps a host handoff clean: the facade re-subscribes on the new
     * connection, and without it a consumer would keep rendering the **previous host's** values until
     * the new read landed (AC #1).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
        settingsRevision
            .map { it[conversationId] ?: 0L }
            .distinctUntilChanged()
            .flatMapLatest { sessionSettingsRead(conversationId) }
            .onStart { emit(null) }

    /**
     * One settings read as a single-emission flow (#590). Fails **closed to `null`** rather than to the
     * caller, because the consumer of a footer reading has nothing to retry with — the next trigger
     * re-reads from scratch — and AC #4 requires a failed read to leave the reading unavailable rather
     * than fall back to anything.
     *
     * [kotlinx.coroutines.flow.catch] rather than a `try`/`catch`: it converts an upstream failure
     * without swallowing the **collector's own cancellation**, which a `runCatching` around a suspend
     * call would. Scoped to this inner flow rather than applied to [observeSessionSettings] as a whole,
     * because a terminal `catch` there would end the outer flow and the conversation would never read
     * again after one failure.
     *
     * **The caught throwable is discarded and never logged, and that is load-bearing.** Only the
     * `effective_effort` message is ours and content-free; a structural decode failure is authored by
     * kotlinx-serialization, whose message can quote the offending input. Dropping it is what keeps AC
     * #2's "logs no payload content" true — a later `catch { Log.w(TAG, it) }` would leak daemon payload
     * content to Logcat in one line.
     *
     * **Gated fail-closed on `interactive`**, like the live-stream arms: the daemon leaves a conn that
     * did not negotiate it fully inert on this verb — no reply, not even a signal that the conversation
     * exists — so an ungated send would suspend until teardown. Not sending is also what keeps "the read
     * sends `request_session_settings` and nothing else" true in the degenerate case.
     */
    private fun sessionSettingsRead(conversationId: String): Flow<SessionSettings?> =
        flow {
            emit(
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) readSessionSettings(conversationId) else null,
            )
        }.catch { emit(null) }

    /**
     * Send one `request_session_settings` and decode its correlated `session_settings` reply (#590) —
     * the [requestHistory] body minus the projection fold. The reply is routed **by the id this call
     * asked with**: it carries no `conversation_id` of its own, so a reading structurally cannot
     * cross-route into another conversation, the property [mergeHistoryPage] relies on one level up.
     *
     * Throws rather than returning null — [sessionSettingsRead] owns the conversion — so every failure
     * mode stays distinguishable at this seam: [IllegalStateException] when the pump is not `Open` or
     * tears down mid-await (#488), [RelayErrorException] for a server `error` (this verb publishes no
     * reject codes of its own — it always answers — so one can only be transport-level), and a
     * [kotlinx.serialization.SerializationException] for a malformed reply. The daemon's all-zero reply
     * is a **successful** read of "nothing resolved", not a failure.
     *
     * Emits no log on any branch, like the rest of this class: the session id, the model and the effort
     * strings never reach Logcat.
     */
    private suspend fun readSessionSettings(conversationId: String): SessionSettings {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_REQUEST_SESSION_SETTINGS,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestSessionSettingsPayloadDto(conversationId = conversationId)),
            )
        return sendAndAwaitReply(request).toSessionSettings()
    }

    /**
     * Invalidate [conversationId]'s settings reading (#590) — the caller-driven trigger, for the moment
     * a settings write has settled. Sends nothing itself: it bumps [settingsRevision], and a collector
     * (if one is listening) issues the read on its own coroutine. Non-suspending and non-throwing, so a
     * caller with no live connection drops it rather than handling a failure it cannot act on.
     */
    override fun refreshSessionSettings(conversationId: String) = bumpSettingsRevision(conversationId)

    /** Atomic read-modify-write of one conversation's settings-read ordinal; see [settingsRevision]. */
    private fun bumpSettingsRevision(conversationId: String) {
        settingsRevision.update { current -> current + (conversationId to (current[conversationId] ?: 0L) + 1L) }
    }

    /**
     * The `backfill_since` request for [conversationId]'s full thread (#313). Wire shape per server
     * SSOT `internal/protocol/messaging.go` `BackfillSincePayload` (#272): full history is requested
     * from the Unix epoch ([BACKFILL_ALL_HISTORY_SINCE]) with an advisory cap
     * ([BACKFILL_MAX_MESSAGES]). The server replies with `message_chunk` (+ `backfill_done`)
     * correlated via `inReplyTo`; the chunk self-routes by its rows' `conversation_id`, so this
     * slice does not track the request id against the conversation.
     */
    private fun backfillSinceRequest(conversationId: String): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_BACKFILL_SINCE,
            ts = Clock.System.now().toString(),
            payload =
                MobileJson.encodeToJsonElement(
                    BackfillSincePayloadDto(
                        sinceTs = BACKFILL_ALL_HISTORY_SINCE,
                        conversationId = conversationId,
                        maxMessages = BACKFILL_MAX_MESSAGES,
                    ),
                ),
        )

    // ---- Stubs: each later slice replaces the methods it owns -----------------------------------

    /**
     * Full thread for [conversationId] (#313): historical messages backfilled ahead of the live
     * `message` stream, merged into one chronological [ThreadItem.MessageItem] list, deduped by
     * `message_id`, in wire/arrival order. Cold, mirroring [observeConversations]: every
     * subscription issues a `backfill_since` request (re-delivered history is absorbed by the
     * `message_id` dedup, so no idempotency guard is needed); a pre-Open send returns false and is
     * dropped — the live stream still fills the thread and the next subscription re-backfills.
     */
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
        flow {
            pump.send(backfillSinceRequest(conversationId))
            emitAll(threadProjection(conversationId))
        }

    /**
     * Cold per-conversation thread view: the ordered [ThreadItem] list ([ThreadItem.MessageItem] rows
     * deduped by `message_id` + interleaved [ThreadItem.SessionBoundary] rows) for [conversationId].
     * The store already holds [ThreadItem]s, so this is a plain per-conversation slice — no row wrap.
     * [distinctUntilChanged] means a change to **another** conversation's slot does not re-emit this
     * flow (AC #3). A `StateFlow` always has a value, so a fresh collector receives the current thread
     * (empty until backfill/live arrives) on subscription.
     */
    private fun threadProjection(conversationId: String): Flow<List<ThreadItem>> =
        threadByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()

    /**
     * Most-recent live [Message] for [conversationId] (#329), a pure cold projection of the shared
     * [lastMessages] `StateFlow`. Issues no request — rides the live `message` stream. A `StateFlow`
     * always has a current value, so every collector (including a `flatMapLatest` re-subscription)
     * receives the current most-recent (or `null` when the conversation is absent) on subscription
     * and re-emits only on change; the one inbound consumer fans out to unlimited collectors.
     */
    override fun observeLastMessage(conversationId: String): Flow<Message?> = lastMessages.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Whether [conversationId] is currently stalled (#395), a pure cold projection of the shared
     * [stalledConversations] `StateFlow` (membership = stalled). Issues no request — rides the live
     * interactive stream (onset on a `stall` envelope, clearing on the next forward-progress event).
     * [distinctUntilChanged] means a stall change to **another** conversation does not re-emit this
     * flow. A `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
     * re-subscription through the facade) receives the current state (`false` until a stall lands) on
     * subscription; the one inbound consumer fans out to unlimited collectors.
     */
    override fun observeStall(conversationId: String): Flow<Boolean> =
        stalledConversations.map { conversationId in it }.distinctUntilChanged()

    /**
     * Ordered queued-message backlog for [conversationId] (#460), a pure cold projection of the shared
     * [queuedByConversation] `StateFlow`. Issues no request — rides the live `queue_state` snapshots.
     * `orEmpty()` gives empty-until-first-snapshot (AC #2). [distinctUntilChanged] means a `queue_state`
     * for **another** conversation, or a value-identical re-snapshot, does not re-emit this flow (AC #3).
     * A `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
     * re-subscription through the facade) receives the current backlog (empty until one lands) on
     * subscription; the one inbound consumer fans out to unlimited collectors.
     */
    override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
        queuedByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()

    /**
     * Current API-retry status for [conversationId] (#593), a pure cold projection of the shared
     * [apiRetryByConversation] `StateFlow`. Issues no request — rides the live `api_retry` edges. The
     * [ApiRetryStatus.NotRetrying] default gives not-retrying-until-the-first-frame (AC #1) and makes an
     * absent key indistinguishable from a stored falling edge. [distinctUntilChanged] suppresses only
     * value-*identical* re-emissions, so an `api_retry` for **another** conversation does not re-emit
     * this flow, while a **climbed counter is a different [ApiRetryStatus.Attempt] value and does reach
     * the collector as a new emission** (AC #2) — precisely what a membership `Set` could not do, since
     * `true` → `true` would collapse the climb. A `StateFlow` always has a current value, so every
     * collector (including a `flatMapLatest` re-subscription through the facade) receives the current
     * status on subscription; the one inbound consumer fans out to unlimited collectors.
     */
    override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> =
        apiRetryByConversation.map { it[conversationId] ?: ApiRetryStatus.NotRetrying }.distinctUntilChanged()

    /**
     * Whether claude is currently auto-compacting [conversationId]'s context (#596), a pure cold
     * projection of the shared [compactingConversations] `StateFlow` (membership = compacting). Issues no
     * request — rides the live `compacting` edges. Membership over an empty set gives
     * not-compacting-until-the-first-frame with no default needed: "absent" and "not compacting" are the
     * same thing by construction, tidier than [observeApiRetry]'s stored falling edge.
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `compacting` for
     * **another** conversation does not re-emit this flow and a repeated rising edge is genuinely nothing
     * new — #593's no-dedup hazard does not transfer here, because that one existed only to let a
     * climbing counter through and a bool has no intermediate values to collapse. A `StateFlow` always
     * has a current value, so every collector (including a `flatMapLatest` re-subscription through the
     * facade) receives the current state (`false` until a compaction lands) on subscription; the one
     * inbound consumer fans out to unlimited collectors.
     */
    override fun observeCompacting(conversationId: String): Flow<Boolean> =
        compactingConversations.map { conversationId in it }.distinctUntilChanged()

    /**
     * The model menu this connection heard for [conversationId] (#791), a pure cold projection of the
     * shared [modelMenusByConversation] `StateFlow`. Issues no request — rides the unasked `model_list`
     * frames (the on-demand ask is #792). An absent key is `null`, which is **unavailable**: a normal,
     * permanent resting state, never an error, never the `Model` / `Effort` device enums and — because
     * the lookup is by the caller's own id — never another conversation's rows.
     *
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `model_list` for
     * **another** conversation does not re-emit this flow, and the reconnect burst's re-send of an
     * unchanged menu costs a consumer nothing. A genuinely different menu is a different [ModelMenu]
     * value and does reach the collector — the [observeApiRetry] property, which a membership `Set`
     * could not provide. A `StateFlow` always has a current value, so every collector (including a
     * `flatMapLatest` re-subscription through the facade) receives the current reading (`null` until a
     * frame lands) on subscription; the one inbound consumer fans out to unlimited collectors.
     */
    override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> =
        modelMenusByConversation.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Create an unpromoted discussion over v2 `create_conversation` (#347). Encodes the request
     * ([CreateConversationPayloadDto]: `is_promoted=false`, optional `cwd`), sends it, and awaits its
     * correlated `conversation_created` reply — the **typed** bare-conversation payload (contrast
     * [sendMessage]'s empty `ack`, which it reconstructs from input). Decodes the reply through the
     * #318 [ConversationResponseDto] boundary, so a malformed reply throws before any state mutation,
     * then **confirmed-inserts** the returned [Conversation] into [projection] — only after the reply
     * decodes — so [observeConversations] re-emits with it (AC #2). The returned `cwd` is the
     * **server-assigned** value from the reply (a null [workspace] requests a scratch cwd the server
     * picks), never the input (AC #1).
     *
     * Throws [RelayErrorException] for a server `error`, [IllegalStateException] when the session is
     * not connected, and the #318 decode exception ([kotlinx.serialization.SerializationException] /
     * [IllegalArgumentException]) for a malformed reply — none of which mutate [projection] (AC #3).
     */
    override suspend fun createDiscussion(workspace: String?): Conversation {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_CREATE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(CreateConversationPayloadDto(cwd = workspace)),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed insert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = sendAndAwaitReply(request)
        val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
        upsertConversation(conversation)
        return conversation
    }

    /**
     * Promote an existing (scratch) conversation into a named, persistent channel over v2
     * `promote_conversation` (#348). Resolves the required wire `cwd` from [workspace] or — when null
     * ("promote in place") — the conversation's existing cwd in [projection], encodes the request
     * ([PromoteConversationPayloadDto], all three fields required), sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload (contrast [sendMessage]'s
     * empty `ack`). Decodes the reply through the #318 [ConversationResponseDto] boundary, so a
     * malformed reply throws before any state mutation, then **confirmed-upserts** the returned
     * [Conversation] into [projection] — only after the reply decodes — so [observeConversations]
     * re-emits with it now in the Channels tier (AC #2). The returned `name`/`cwd`/`isPromoted` are the
     * **server-authoritative** reply values (AC #1), never the request's resolved cwd.
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`,
     * [IllegalStateException] when the session is not connected, and the #318 decode exception
     * ([kotlinx.serialization.SerializationException] / [IllegalArgumentException]) for a malformed
     * reply — none of which mutate [projection] (AC #3).
     */
    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation {
        // Null workspace ("promote in place") resolves to the conversation's existing cwd from the read
        // projection — the remote analog of the fake's `workspace ?: record.conversation.cwd`. The
        // `?: ""` fallback is only reachable when the conversation is absent from the projection (not
        // reachable from the shipped UI, which only promotes a visible, hence loaded, conversation).
        val cwd = workspace ?: projection.value?.firstOrNull { it.id == conversationId }?.cwd ?: ""
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_PROMOTE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        PromoteConversationPayloadDto(conversationId = conversationId, name = name, cwd = cwd),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = sendAndAwaitReply(request)
        val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
        upsertConversation(conversation)
        return conversation
    }

    /**
     * Post [text] to [conversationId] over v2 `send_message` (#346). Mints a client-side
     * `message_id`, sends the request, and awaits its correlated reply: an empty `ack` (success) or
     * an `error` (failure). Mirrors [FakeConversationRepository.sendMessage]'s observable contract —
     * returns a `role=User` [Message] reconstructed from the input. There is no server `message` echo
     * to the sender, so the sender's thread updates only via the **confirmed insert** below, run
     * **only after** the `ack`: both read-path projections re-emit, never on a failure path.
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`, and
     * [IllegalStateException] when the session is not connected — none of which mutate a projection.
     */
    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message {
        val messageId = UUID.randomUUID().toString()
        val sentAt = Clock.System.now()
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_SEND_MESSAGE,
                ts = sentAt.toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SendMessagePayloadDto(conversationId = conversationId, messageId = messageId, text = text),
                    ),
            )
        // Throws on a server `error` / not-Open session; the confirmed insert below is unreachable
        // on any failure path. The empty `{}` ack payload carries nothing to map.
        sendAndAwaitReply(request)
        val message =
            Message(
                id = messageId,
                // The v2 wire carries no session_id (boundaries are #336); list/thread tiers use the
                // "" placeholder, never a resolved currentSessionId — matching the inbound mappers.
                sessionId = "",
                role = Role.User,
                content = text,
                timestamp = sentAt,
                isStreaming = false,
            )
        recordLastMessage(conversationId, message)
        appendMessages(listOf(conversationId to message))
        // Record the echo as ours (#781) — only an id in this ledger may later be correlated with a
        // queued item and removed. Recorded after the ack, so a failed send leaves no phantom claim.
        mintedMessageIds.update { it + (conversationId to (it[conversationId].orEmpty() + messageId)) }
        return message
    }

    /**
     * Request the current claude screen for [conversationId] over v2 `request_snapshot` (#375) and
     * return the correlated `screen_snapshot` reply's rendered [ScreenSnapshotPayloadDto.text] — the
     * always-available, parser-independent snapshot floor (pyrycode#618). A pure read: it mutates no
     * projection.
     *
     * Encodes the request ([RequestSnapshotPayloadDto]: `{conversation_id}`), sends it, and awaits the
     * correlated reply through the shared single inbound collector + [sendAndAwaitReply] (the #346
     * primitive — no second pump subscription), then decodes the reply through the #374
     * [ScreenSnapshotPayloadDto] boundary and returns its [text][ScreenSnapshotPayloadDto.text]
     * **verbatim** — never parsed, trimmed, or sanitized; `ts` is never read; nothing here is logged
     * (the snapshot text may be sensitive screen content).
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`,
     * mirroring the fake's type), [RelayErrorException] for any other server `error`, and
     * [IllegalStateException] when the session is not connected. A malformed reply throws the #374
     * decode exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException])
     * caller-side, **after** [sendAndAwaitReply] returns, so it never threatens the single inbound
     * collector.
     */
    override suspend fun requestScreenSnapshot(conversationId: String): String {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_REQUEST_SNAPSHOT,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = conversationId)),
            )
        // Throws on a server `error` / not-Open session; the decode below is unreachable on failure.
        val reply = sendAndAwaitReply(request)
        return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text
    }

    /**
     * Drop a not-yet-drained message from [conversationId]'s queued backlog over v2 `dequeue_message`
     * (#466, ADR 025): send `{conversation_id, queued_msg_id}` and await its correlated reply — an
     * empty `ack` (the daemon removed the message) or an `error` (failure). [queuedMessageId] is the
     * `QueuedMessage.id` the caller received from [observeQueue] (#460), echoed **verbatim** as the
     * wire `queued_msg_id` (a `uint64` → [Long] JSON number, not a String — the pyrycode#720 trap);
     * the daemon validates the `(conversation_id, queued_msg_id)` pair against its own per-conversation
     * queue and stale-id rejects a mismatch — this method neither re-derives nor trusts it.
     *
     * The backlog entry still leaves only on the next `queue_state` (#467's non-optimistic ruling, which
     * the daemon owns), but a confirmed drop **also removes this device's own undelivered echo** for the
     * message (#781) — the thread row [sendMessage] posted after its ack, which the daemon never authored
     * and which otherwise stays behind reading as a message claude received. The correlation key is the
     * item's `message_id` (pyrycode#2092), resolved from this connection's own snapshot rather than
     * carried down from the UI: `queued_msg_id` already addresses the row, so no caller above needs to
     * learn a second id. Never logs the payload or either id.
     *
     * Three properties of the sequence are load-bearing:
     *  - **Resolved before the send.** A successful drop provokes a fresh `queue_state` that removes the
     *    item, so reading the snapshot afterwards races the inbound collector and usually finds nothing.
     *    Reading it early is safe because `queued_msg_id` is a per-conversation counter that is never
     *    recycled — item *N* in a stale snapshot is still the same item *N*.
     *  - **Removed only on the ack.** A throw skips the removal entirely, so a failed drop leaves the
     *    entry and the echo in place: the daemon never heard it, the message will still run, and the
     *    echo is still true. Showing a message the operator typed is optimism; hiding one the daemon
     *    still holds would be a claim about the daemon.
     *  - **Removed only against [mintedMessageIds].** An item carrying `""`, one whose id this device
     *    never minted (another paired device's real queued message), or a `queuedMessageId` absent from
     *    the snapshot all correlate with nothing: the send still goes and no thread row is touched.
     *    Text is never compared.
     *
     * Deliberately **not** driven by a backlog diff: a backlog also shrinks when the daemon *drains* it,
     * so a diff-driven removal would delete the echo of every message that ran normally — a worse lie
     * than the one being fixed.
     *
     * Throws [IllegalArgumentException] for an unknown conversation (server `conversation.not_found`),
     * [RelayErrorException] for any other server `error` (a stale / already-drained id surfaces
     * generically here), and [IllegalStateException] when the session is not connected
     * ([SessionPump.send] returns `false`) — none of them mutates any state.
     */
    override suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_DEQUEUE_MESSAGE,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        DequeueMessagePayloadDto(conversationId = conversationId, queuedMsgId = queuedMessageId),
                    ),
            )
        // Resolved before the send: a successful drop replaces the snapshot this reads from. "" when the
        // id matches no current item — a correlation this connection cannot make, not an error.
        val echoId =
            queuedByConversation.value[conversationId]
                .orEmpty()
                .firstOrNull { it.id == queuedMessageId }
                ?.messageId
                .orEmpty()
        // Throws on a server `error` / not-Open session; the empty `{}` ack carries nothing to map, so
        // the returned reply is ignored — but reaching the next line IS the drop's confirmation.
        sendAndAwaitReply(request)
        removeOwnEcho(conversationId, echoId)
    }

    /**
     * Remove the one thread row **this device** minted under [messageId] from [conversationId]'s thread
     * (#781), and spend the id so it can never match twice. A no-op unless [messageId] is non-empty and
     * held in [mintedMessageIds] — the multi-device rule made mechanical: a queued item's id is
     * client-chosen and unique nowhere, so only an id this connection minted may remove a row.
     *
     * The removal is a `filterNot` over the existing list inside one atomic [MutableStateFlow.update],
     * so every other row keeps its position and a concurrent append retry-merges rather than being lost
     * (the [appendMessages] posture). Matches on [ThreadItem.MessageItem] and the message's own id
     * only — never on `text`, which is neither unique nor a key.
     */
    private fun removeOwnEcho(
        conversationId: String,
        messageId: String,
    ) {
        if (messageId.isEmpty()) return
        if (messageId !in mintedMessageIds.value[conversationId].orEmpty()) return
        mintedMessageIds.update { it + (conversationId to (it[conversationId].orEmpty() - messageId)) }
        threadByConversation.update { current ->
            val rows = current[conversationId] ?: return@update current
            current + (conversationId to rows.filterNot { it is ThreadItem.MessageItem && it.message.id == messageId })
        }
    }

    /**
     * Register the phone's FCM push [token] with the paired daemon over v2 `register_push_token`
     * (#359) — so the daemon knows where to send a wake notification when the phone is backgrounded.
     * Encodes the request ([RegisterPushTokenPayloadDto]: `platform="fcm"`, the [token], and the
     * connection-level [deviceName]), sends it, and awaits its correlated reply: an empty `ack`
     * (success) or an `error` (failure). A pure request/reply with **no** projection side effect —
     * unlike the conversation mutations, this registers a token and produces no domain object, so the
     * success signal is simply "the call returned without throwing".
     *
     * The server dedupes the `(platform, token, device_name)` triple, so this does no client-side
     * dedupe — it just sends. **Dormant** until the Firebase sibling provides a real token and a live
     * caller; this slice only exposes the capability. Never logs the [token] or the request payload.
     *
     * Throws [RelayErrorException] for a server `error` (carrying the structured `code`/`retryable`,
     * e.g. `server.binary_busy` retryable / `auth.invalid_token` not), and [IllegalStateException]
     * when the session is not connected ([SessionPump.send] returns `false`) — neither mutates any
     * state (there is nothing to mutate).
     */
    suspend fun registerPushToken(token: String) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_REGISTER_PUSH_TOKEN,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RegisterPushTokenPayloadDto(platform = PLATFORM_FCM, token = token, deviceName = deviceName),
                    ),
            )
        // Throws on a server `error` / not-Open session. The empty `{}` ack payload carries nothing
        // to map and no projection is mutated, so the returned reply is ignored.
        sendAndAwaitReply(request)
    }

    /**
     * Answer the surfaced permission/choice modal (#437) over v2 `modal_answer` (#438): send
     * `{modal_id, option_id, answer_token}` and await its correlated reply — an empty `ack` (the
     * daemon received and will process the answer) or an `error` (failure). [modalId] and [optionId]
     * are the opaque tokens the caller (#439, via the #437 decode) hands in — echoed **verbatim**,
     * never parsed or validated; the daemon validates [modalId] against its own outstanding modal
     * (first-answer-wins; a stale id is rejected) and maps [optionId] against its own option list.
     * The [answerToken] is minted here ([answerToken] helper) as a deterministic idempotency key.
     *
     * A pure request/reply control call with **no** projection side effect — success is simply "the
     * call returned without throwing". The modal's eventual *resolution* arrives asynchronously as the
     * inbound `modal_dismissed` event on [modalEvents] (#437); this method does **not** await it.
     * Never logs the payload (the modal may name a sensitive command/path).
     *
     * Throws [RelayErrorException] for a server `error` (carrying `code`/`retryable` — incl. the
     * ungranted-device reject pyrycode#702/#703, whose read-only degrade is #440's concern, not
     * caught here), and [IllegalStateException] when the session is not connected ([SessionPump.send]
     * returns `false`) — neither mutates any state (there is none).
     */
    suspend fun answerModal(
        modalId: String,
        optionId: String,
    ) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_MODAL_ANSWER,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        ModalAnswerPayloadDto(
                            modalId = modalId,
                            optionId = optionId,
                            answerToken = answerToken(modalId, optionId),
                        ),
                    ),
            )
        // Throws on a server `error` / not-Open session; the empty `{}` ack is ignored.
        sendAndAwaitReply(request)
    }

    /**
     * Cancel the surfaced permission/choice modal (#437) over v2 `modal_cancel` (#438): send
     * `{modal_id}` and await its correlated `ack`/`error`. [modalId] is echoed **verbatim**, never
     * parsed; the daemon validates it against its own outstanding modal (a stale id — e.g. a re-cancel
     * of an already-resolved modal — is rejected). Cancel carries no idempotency token (pyrycode#701).
     * Same request/reply, no-projection, never-log posture as [answerModal].
     *
     * Throws [RelayErrorException] for a server `error` and [IllegalStateException] when the session
     * is not connected ([SessionPump.send] returns `false`).
     */
    suspend fun cancelModal(modalId: String) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_MODAL_CANCEL,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(ModalCancelPayloadDto(modalId = modalId)),
            )
        // Throws on a server `error` / not-Open session; the empty `{}` ack is ignored.
        sendAndAwaitReply(request)
    }

    /**
     * Stop the named conversation over v2 `interrupt` (protocol-mobile.md, Interrupt v2).
     * Fire-and-forget: the daemon sends no ack, so use [SessionPump.send], not [sendAndAwaitReply].
     * The daemon validates the conversation lookup key and enforces the interactive capability.
     * No local turn state changes; the existing inbound turn events remain authoritative.
     *
     * Throws [IllegalStateException] when the session is not connected so the caller can swallow it.
     */
    suspend fun interrupt(conversationId: String) {
        check(pump.send(interruptRequest(conversationId))) { "$TYPE_INTERRUPT not sent: session not connected" }
    }

    /** Explicitly targeted, fire-and-forget control frame — see [interrupt]. */
    private fun interruptRequest(conversationId: String): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_INTERRUPT,
            ts = Clock.System.now().toString(),
            payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
        )

    /**
     * Mint the `answer_token` for a `modal_answer`: a deterministic, collision-free encoding of the
     * answer's identity `(modalId, optionId)` (pyrycode#701 — uniqueness + stability matter, secrecy
     * does not). A **pure** function: no stored state, no random, no clock — purity is what gives the
     * two AC#2 properties by construction:
     *
     *  - **Stable across a retry of the same logical answer:** the same `(modalId, optionId)` always
     *    yields the same token, so a resend carries the identical token and the daemon dedups it to a
     *    no-op (no remembered state, no cache lifetime).
     *  - **Unique per distinct answer:** distinct pairs always yield distinct tokens. Both ids
     *    participate — two different modals each answered with an option id `"allow"` are distinct
     *    answers and must carry distinct tokens.
     *
     * The modal id is **length-prefixed** so the opaque ids (which may contain the `:` separator)
     * cannot alias: `("a:b","c")` → `"3:a:b:c"` and `("a","b:c")` → `"1:a:b:c"` are distinct. The
     * daemon treats the token as an opaque comparison key, so this conforming encoding is wire-correct.
     */
    private fun answerToken(
        modalId: String,
        optionId: String,
    ): String = "${modalId.length}:$modalId:$optionId"

    /**
     * `true`: every mutation method below now has a v2 wire message on both ends — mobile
     * `rename` / `archive` / `unarchive` / `delete` / `startNewSession` / `changeWorkspace` /
     * `setSessionSettings` / `createWorkspaceFolder` (#530-#536, #549, #560, #564, #565) and the
     * matching daemon handlers (pyrycode #820-#826). The `false` in #507 predated those wires; the
     * throwing stubs it referred to are gone, so the mutation affordances are now reachable in relay mode.
     */
    override val mutationsSupported: Boolean = true

    override suspend fun archive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_ARCHIVE_CONVERSATION)

    override suspend fun unarchive(conversationId: String): Unit = sendArchiveToggle(conversationId, TYPE_UNARCHIVE_CONVERSATION)

    /**
     * Archive or restore [conversationId] over v2 [type] (`archive_conversation` /
     * `unarchive_conversation`, #549, server pyrycode#881) — the shared body both toggle overrides
     * delegate to (mirroring the server's one parameterized handler registered under both verbs). Encodes
     * the id-only [ArchiveConversationPayloadDto] request, sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload now carrying `is_archived`
     * (pyrycode#881). Decodes the reply through the #318 [ConversationResponseDto] boundary, so a
     * malformed reply throws before any state mutation, then **confirmed-upserts** the returned
     * [Conversation] into [projection] — only after the reply decodes — so [observeConversations]
     * re-emits with the conversation in its new tier (leaving/entering [ConversationFilter.Archived]).
     *
     * A direct analogue of [rename] (encode → [sendAndAwaitReply] → typed-decode → fold) minus the
     * return value: the [ConversationRepository] contract returns [Unit], so the decoded conversation is
     * folded but not returned. Idempotent: pyrycode#881 replies `conversation_updated` with the unchanged
     * state on a re-archive/re-unarchive, and [upsertConversation] replaces the entry with an equal value
     * (a benign re-emit). Throws [IllegalArgumentException] for an unknown conversation (server
     * `conversation.not_found`, mirroring the fake's type), [RelayErrorException] for any other server
     * `error`, [IllegalStateException] when the session is not connected, and the #318 decode exception
     * for a malformed reply — none of which mutate [projection] (AC #3, #4). Adds no logging (the id and
     * reply stay off the log, the `security-sensitive` discipline).
     */
    private suspend fun sendArchiveToggle(
        conversationId: String,
        type: String,
    ) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = type,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(ArchiveConversationPayloadDto(conversationId = conversationId)),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = sendAndAwaitReply(request)
        val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
        upsertConversation(conversation)
    }

    /**
     * Permanently delete [conversationId] over v2 `delete_conversation` (#532, server pyrycode#822 /
     * PR #884). Encodes the id-only [DeleteConversationPayloadDto] request, sends it, and awaits its
     * correlated `conversation_deleted` ack. Unlike [rename] / [sendArchiveToggle] (whose reply is a
     * bare `conversation_updated` folded via [upsertConversation]), delete's reply is a dedicated
     * `{id}` ack — the record is gone, so there is nothing to upsert. The ack is decoded through the
     * [ConversationDeletedPayloadDto] boundary **only** to validate the reply shape (#318 posture — a
     * malformed ack throws here, before any removal); the decoded value is **discarded** (the repo
     * removes the id it *sent*, not the id the reply echoes, so a lying relay cannot redirect the
     * removal). On a well-formed ack it **removes** [conversationId] from all three read projections
     * ([removeConversation]) so [observeConversations] re-emits without it, [observeMessages] →
     * `emptyList()`, and [observeLastMessage] → `null` — the faithful mirror of the fake's whole-record
     * removal.
     *
     * **`conversation.not_found` converges as success**, the deliberate divergence from [rename] /
     * [sendArchiveToggle]: the [ConversationRepository.delete] contract is *tolerant* of unknown ids
     * (converges on the post-condition, not [IllegalArgumentException]), so an already-gone id is
     * removed locally and returns normally. [mapError] maps `conversation.not_found` — and nothing
     * else — to [IllegalArgumentException], so the tight `catch` below captures exactly that case; it
     * is scoped to [sendAndAwaitReply] alone, so a malformed-ack decode [IllegalArgumentException]
     * still propagates (never mis-read as already-gone, so a bad ack removes nothing).
     *
     * Throws [IllegalStateException] when the session is not connected (or on teardown mid-await),
     * [RelayErrorException] for any other server `error`, and the #318 decode exception for a malformed
     * ack — none of which mutate a projection (AC #2, #3). Adds no logging (the id and reply stay off
     * the log, the `security-sensitive` discipline; [RelayErrorException.message] is server-supplied).
     */
    override suspend fun delete(conversationId: String) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_DELETE_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(DeleteConversationPayloadDto(conversationId = conversationId)),
            )
        val reply =
            try {
                sendAndAwaitReply(request)
            } catch (alreadyGone: IllegalArgumentException) {
                // mapError maps conversation.not_found → IAE and nothing else, so this is exactly the
                // not-found case. Delete's post-condition is "absent", so already-gone is success (AC #4):
                // converge locally and return. The catch is scoped to the await only — the decode below
                // is NOT inside it, so a malformed-ack IAE cannot be mis-read as already-gone.
                removeConversation(conversationId)
                return
            }
        // #318 boundary: a malformed ack throws here (SerializationException ⊂ IllegalArgumentException),
        // BEFORE removeConversation, so a bad ack mutates nothing (AC #2). Shape-validated, then discarded.
        MobileJson.decodeFromJsonElement<ConversationDeletedPayloadDto>(reply)
        removeConversation(conversationId)
    }

    /**
     * Rename an existing conversation (channel or discussion) to [name] over v2 `rename_conversation`
     * (#530, server #820). Encodes the request ([RenameConversationPayloadDto]: `{conversation_id, name}`,
     * both required — no `cwd`, contrast [promote]), sends it, and awaits its correlated
     * `conversation_updated` reply — the **typed** bare-conversation payload. Decodes the reply through
     * the #318 [ConversationResponseDto] boundary, so a malformed reply throws before any state
     * mutation, then **confirmed-upserts** the returned [Conversation] into [projection] — only after
     * the reply decodes — so [observeConversations] re-emits with the new name (and the thread top bar,
     * derived from the same projection). The returned `name` is the **server-authoritative** reply
     * value, not the request's.
     *
     * [name] is forwarded **verbatim** — the `RenameDialog` is the sole trim authority; the daemon
     * re-validates and rejects empty/whitespace titles server-side (`protocol.malformed`), surfaced
     * here as an ordinary [RelayErrorException]. Throws [IllegalArgumentException] for an unknown
     * conversation (server `conversation.not_found`, mirroring the fake's type), [RelayErrorException]
     * for any other server `error`, [IllegalStateException] when the session is not connected, and the
     * #318 decode exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException])
     * for a malformed reply — none of which mutate [projection] (AC #3).
     */
    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_RENAME_CONVERSATION,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RenameConversationPayloadDto(conversationId = conversationId, name = name),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = sendAndAwaitReply(request)
        val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
        upsertConversation(conversation)
        return conversation
    }

    /**
     * Apply the operator's run-configuration change — [model] / [effort] / [yolo] — to the running
     * session [sessionId] over v2 `set_session_settings` (#543, server #844/#845). A direct analogue of
     * [rename] (encode → [sendAndAwaitReply] → typed-decode) **minus the state fold**: session settings
     * are ViewModel state (#544), not a projection in this repo, so there is nothing to upsert.
     *
     * Encodes [SetSessionSettingsPayloadDto] under the presence contract — a `null` field is **omitted**
     * ([MobileJson]'s `explicitNulls = false`), meaning "leave unchanged"; a non-null value (including
     * `false` / `""`) is always sent — then awaits the correlated `session_settings_updated` ack. The
     * ack carries only `{session_id}` (an echo of the input, not the applied settings), so it is decoded
     * through the [SessionSettingsUpdatedPayloadDto] boundary **only** to validate the reply shape
     * (#318 posture — a malformed ack throws here); the decoded value is discarded. Returns [Unit] —
     * there is nothing to return, and no projection is touched.
     *
     * [sessionId] / [model] / [effort] / [yolo] are forwarded **verbatim** (as [rename] forwards the
     * dialog's name); the daemon re-validates `model` / `effort` server-side and gates on the
     * interactive capability. Throws [IllegalStateException] when the session is not connected, and —
     * unlike the conversation-scoped verbs — has **no** [IllegalArgumentException] path: the
     * unhosted-session code is `session.not_found` (not `conversation.not_found`), so every server
     * `error` maps to a [RelayErrorException] carrying its `code` (`session.not_found` /
     * `protocol.malformed` / `server.binary_offline`). A malformed ack throws the #318 decode exception.
     * None of these mutate any projection.
     */
    override suspend fun setSessionSettings(
        sessionId: String,
        model: String?,
        effort: String?,
        yolo: Boolean?,
    ) {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_SET_SESSION_SETTINGS,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        SetSessionSettingsPayloadDto(sessionId = sessionId, model = model, effort = effort, yolo = yolo),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the bare
        // {session_id} ack; decode validates its shape (a malformed ack throws) — the result is discarded.
        val reply = sendAndAwaitReply(request)
        MobileJson.decodeFromJsonElement<SessionSettingsUpdatedPayloadDto>(reply)
    }

    /**
     * Send v2 `new_session` for the viewed conversation, explicitly naming its id so another
     * device's activity cannot redirect the reset through the daemon's follow-active cursor.
     * See the upstream protocol's New session (v2) contract. This is fire-and-forget via
     * [SessionPump.send], never [sendAndAwaitReply]; session changes arrive through inbound events.
     * The daemon validates the id and enforces the interactive capability.
     *
     * [workspace] is not part of this control frame. Throws [IllegalStateException] when
     * [SessionPump.send] returns false, so the caller can surface the failure.
     *
     * The interface forces a [Session] return, but a fire-and-forget frame yields no session identity —
     * the real one arrives later via the out-of-scope `session_transition` marker (#336 fold). So the
     * returned placeholder's identity fields (`id`, `claudeSessionUuid`) are **explicitly unassigned**
     * (empty strings, not a fabricated-to-look-real UUID); it is never persisted, never enters
     * [projection], and the #540 consumer discards it.
     */
    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session {
        check(pump.send(newSessionFrame(conversationId))) { "$TYPE_NEW_SESSION not sent: session not connected" }
        return Session(
            id = "",
            conversationId = conversationId,
            claudeSessionUuid = "",
            startedAt = Clock.System.now(),
            endedAt = null,
        )
    }

    /** Explicitly targeted, fire-and-forget control frame — see [startNewSession]. */
    private fun newSessionFrame(conversationId: String): Envelope =
        Envelope(
            id = requestId.incrementAndGet(),
            type = TYPE_NEW_SESSION,
            ts = Clock.System.now().toString(),
            payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversationId))),
        )

    /**
     * Change conversation [conversationId]'s workspace to [workspace] over v2 `change_workspace`
     * (#560, server #823). "Workspace" **is** the conversation's `cwd` — there is no separate
     * workspace-id concept. A line-for-line mirror of [rename] with a `cwd` payload
     * ([ChangeWorkspacePayloadDto]: `{conversation_id, cwd}`, both required) instead of `{…, name}`:
     * encodes the request, sends it, and awaits its correlated `conversation_updated` reply — the same
     * reply reuse rename relies on. Decodes the reply through the #318 [ConversationResponseDto]
     * boundary, so a malformed reply throws before any state mutation, then **confirmed-upserts** the
     * returned [Conversation] into [projection] — only after the reply decodes — so the new `cwd`
     * becomes visible on the workspace chip / list (AC #2). The folded `cwd` is the
     * **server-authoritative** reply value (the daemon's resolved realpath), not the request's.
     *
     * [workspace] is an **untrusted** path forwarded **verbatim** — no client-side validation,
     * canonicalisation, or `$HOME` check, and the phone never touches the filesystem with it:
     * confinement is the daemon's job (fail-closed, stores the resolved realpath), which re-validates
     * and rejects out-of-`$HOME` / empty paths server-side (`protocol.malformed`), surfaced here as an
     * ordinary [RelayErrorException]. Throws [IllegalArgumentException] for an unknown conversation
     * (server `conversation.not_found`, as [rename]), [RelayErrorException] for any other server
     * `error`, [IllegalStateException] when the session is not connected, and the #318 decode exception
     * for a malformed reply — none of which mutate [projection] (AC #3).
     *
     * `change_workspace` performs **no session transition** — it updates the recorded `cwd` only; the
     * new folder takes effect on the conversation's next fresh session spawn (#823 Out-of-Scope, AC
     * #4). So there is no `session_transition` (#336) and no session-boundary delimiter. The interface
     * forces a [Session] return, but there is no session identity to return: the returned placeholder's
     * identity fields (`id`, `claudeSessionUuid`) are **explicitly unassigned** (empty strings, not a
     * fabricated UUID — the [startNewSession] precedent); it is never persisted, never enters
     * [projection], and the #560 `onWorkspacePicked` caller discards it.
     */
    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session {
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_CHANGE_WORKSPACE,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        ChangeWorkspacePayloadDto(conversationId = conversationId, cwd = workspace),
                    ),
            )
        // Throws on a server `error` / not-Open session; the decode + confirmed upsert below are
        // unreachable on any failure path. The reply is the bare conversation object (#318 decodes it).
        val reply = sendAndAwaitReply(request)
        val conversation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(reply).toConversation()
        upsertConversation(conversation)
        // Vestigial: change_workspace has no session transition (AC #4), so no session identity.
        return Session(
            id = "",
            conversationId = conversationId,
            claudeSessionUuid = "",
            startedAt = Clock.System.now(),
            endedAt = null,
        )
    }

    /**
     * Create a new workspace folder named [name] under the fixed client root over v2
     * `create_workspace_folder` (#564, server #887), returning the daemon's canonical created path.
     * The leanest write-verb: it names no conversation, carries no `conversation_id`, touches **no**
     * projection, and — unlike [rename] / [changeWorkspace] — its return value (the created path) is
     * the sole effect (it flows to the Workspace Picker's `onPicked` and becomes the selected
     * workspace). A direct analogue of [rename] (encode → [sendAndAwaitReply] → typed-decode) **minus
     * the state fold**, plus a client-side blank-name guard.
     *
     * The interface passes only [name]; the wire request carries a **parent path and a name**
     * ([CreateWorkspaceFolderPayloadDto]). This sends `parent = `[WORKSPACE_FOLDER_PARENT]` (the fixed
     * `~/pyry-workspace` root, tilde-anchored so the daemon resolves it against **its** `$HOME` — a
     * relative `pyry-workspace` would resolve against the daemon's process cwd) and `name = name.trim()`.
     * Both are **untrusted** path components forwarded verbatim — no client-side validation,
     * canonicalisation, or `$HOME` check, and the phone never touches the filesystem with them:
     * confinement is the daemon's job (fail-closed, symlink-resolved, before `MkdirAll`), which also
     * rejects a `name` that is not a clean single element (empty / absolute / separator / `..`),
     * surfaced here as an ordinary [RelayErrorException]. The returned `path` is the
     * **server-authoritative** canonical realpath, not a client-derived join.
     *
     * Throws [IllegalArgumentException] for a blank/whitespace-only [name] (checked **before** any
     * send — no request reaches the wire, mirroring the fake's contract), [IllegalStateException] when
     * the session is not connected, [RelayErrorException] for any server `error` (create has **no**
     * `conversation.not_found` path — every reject is `protocol.malformed`), and the #318 decode
     * exception ([kotlinx.serialization.SerializationException] / [IllegalArgumentException]) for a
     * malformed reply. No projection is folded on any path — a failure leaves no partial state.
     */
    override suspend fun createWorkspaceFolder(name: String): String {
        require(name.isNotBlank()) { "name must not be blank" }
        val request =
            Envelope(
                id = requestId.incrementAndGet(),
                type = TYPE_CREATE_WORKSPACE_FOLDER,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        CreateWorkspaceFolderPayloadDto(parent = WORKSPACE_FOLDER_PARENT, name = name.trim()),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the bare
        // {path} object (#318 decodes it); a malformed reply throws here. No state is folded.
        val reply = sendAndAwaitReply(request)
        return MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(reply).path
    }

    /**
     * Widened from `private` to `internal` by #645 so [reduceHistoryPage] can dispatch a stored
     * [HistoryEntry.type] against the **same** wire-string declarations this class demuxes live frames
     * on, rather than re-spelling eight protocol strings in a second place. The constants are protocol
     * vocabulary, not state — nothing here becomes writable by widening it.
     */
    internal companion object {
        /** Request: list the conversations (payload `{}` per protocol). */
        const val TYPE_LIST_CONVERSATIONS = "list_conversations"

        /** Response/push: a full-list `{conversations:[…]}` snapshot — also unsolicited on change. */
        const val TYPE_CONVERSATIONS = "conversations"

        /** Request: list recently-used workspace folders (#565, #888). Empty `{}` payload; reply is [TYPE_RECENT_WORKSPACES_LIST]. */
        const val TYPE_RECENT_WORKSPACES = "recent_workspaces"

        /**
         * Correlated reply for [TYPE_RECENT_WORKSPACES] (#565, #888): the distinct recent-workspace
         * paths, most-recent-first, daemon-ordered (the client does not re-sort or re-dedup). A **new**
         * reply type — not a reused `conversations`/`conversation_updated` — so it must be registered in
         * the [onInbound] success-reply arm, or its pending deferred hangs (the delete/create-family hazard).
         */
        const val TYPE_RECENT_WORKSPACES_LIST = "recent_workspaces_list"

        /** Request: one backward page of a conversation's stored history (#623, pyrycode#2113). Reply is [TYPE_HISTORY_PAGE]. */
        const val TYPE_REQUEST_HISTORY = "request_history"

        /**
         * Correlated reply for [TYPE_REQUEST_HISTORY] (#623, pyrycode#2116): one page of entries,
         * newest-first, with the next cursor and `at_start`. A **new** reply type, so it must be
         * registered in the [onInbound] success-reply arm or its pending deferred hangs (the
         * delete/create-family hazard [TYPE_RECENT_WORKSPACES_LIST] records).
         */
        const val TYPE_HISTORY_PAGE = "history_page"

        /**
         * Live/echo single-`message` payload (#317) — feeds both the last-message preview (#329) and
         * the conversation thread (#313). The batched backfill response is [TYPE_MESSAGE_CHUNK].
         */
        const val TYPE_MESSAGE = "message"

        /** Request: full thread backfill for one conversation (#313, #272 `BackfillSincePayload`). */
        const val TYPE_BACKFILL_SINCE = "backfill_since"

        /** Response: a batch of finished `message` rows answering a `backfill_since` (#313, #272). */
        const val TYPE_MESSAGE_CHUNK = "message_chunk"

        /** Request: post a user message to a conversation (#346, #272 `SendMessagePayload`). */
        const val TYPE_SEND_MESSAGE = "send_message"

        /** Request: register the phone's push token (#359, #275 `RegisterPushTokenPayload`). */
        const val TYPE_REGISTER_PUSH_TOKEN = "register_push_token"

        /** The Android push platform value for `register_push_token.platform` (#359, AC #2). */
        const val PLATFORM_FCM = "fcm"

        /** Request: create a new (unpromoted) conversation (#347, #274 `CreateConversationPayload`). */
        const val TYPE_CREATE_CONVERSATION = "create_conversation"

        /** Correlated success reply carrying the bare created conversation object (#347, #274). */
        const val TYPE_CONVERSATION_CREATED = "conversation_created"

        /** Request: promote an existing conversation to a named channel (#348, #274 `PromoteConversationPayload`). */
        const val TYPE_PROMOTE_CONVERSATION = "promote_conversation"

        /** Request: rename an existing conversation (#530, #820 `RenameConversationPayload`). Reply is `conversation_updated`. */
        const val TYPE_RENAME_CONVERSATION = "rename_conversation"

        /** Request: archive an existing conversation (#549, #881 `ArchiveConversationPayload`). Reply is `conversation_updated`. */
        const val TYPE_ARCHIVE_CONVERSATION = "archive_conversation"

        /** Request: restore an archived conversation (#549, #881, shares `ArchiveConversationPayload`). Reply is `conversation_updated`. */
        const val TYPE_UNARCHIVE_CONVERSATION = "unarchive_conversation"

        /** Request: permanently delete an existing conversation (#532, #822 `DeleteConversationPayload`). */
        const val TYPE_DELETE_CONVERSATION = "delete_conversation"

        /** Request: change a conversation's workspace `cwd` (#560, #823 `ChangeWorkspacePayload`). Reply is `conversation_updated`. */
        const val TYPE_CHANGE_WORKSPACE = "change_workspace"

        /** Request: create a new workspace folder (#564, #887 `CreateWorkspaceFolderPayload`). Reply is [TYPE_WORKSPACE_FOLDER_CREATED]. */
        const val TYPE_CREATE_WORKSPACE_FOLDER = "create_workspace_folder"

        /** Correlated reply for [TYPE_CREATE_WORKSPACE_FOLDER] carrying the created folder's canonical path (#564, #887). */
        const val TYPE_WORKSPACE_FOLDER_CREATED = "workspace_folder_created"

        /**
         * The fixed client parent root for [TYPE_CREATE_WORKSPACE_FOLDER] (#564). Tilde-anchored so the
         * daemon resolves it against **its** `$HOME` (a relative `pyry-workspace` would resolve against
         * the daemon's process cwd); matches the daemon's golden fixture and the Figma trigger row
         * ("…under pyry-workspace").
         */
        const val WORKSPACE_FOLDER_PARENT = "~/pyry-workspace"

        /** Correlated ack for [TYPE_DELETE_CONVERSATION] carrying only `{id}` (#532, #822). */
        const val TYPE_CONVERSATION_DELETED = "conversation_deleted"

        /** Request: apply model/effort/YOLO to a running session (#543, #844 `SetSessionSettingsPayload`). */
        const val TYPE_SET_SESSION_SETTINGS = "set_session_settings"

        /** Correlated ack for [TYPE_SET_SESSION_SETTINGS] carrying only `{session_id}` (#543, #844). */
        const val TYPE_SESSION_SETTINGS_UPDATED = "session_settings_updated"

        /** Request: the run configuration of one conversation's session (#590). Reply is [TYPE_SESSION_SETTINGS]. */
        const val TYPE_REQUEST_SESSION_SETTINGS = "request_session_settings"

        /**
         * Correlated reply for [TYPE_REQUEST_SESSION_SETTINGS] (#590): the resolved session's id, stored
         * model and effort, claude's applied effort when available, the current child's confirmed
         * permission posture when available, and the context-window reading. **Never an error frame** —
         * an unhosted, unbound, unnamed or unwired conversation is answered with the all-zero reply.
         */
        const val TYPE_SESSION_SETTINGS = "session_settings"

        /**
         * Correlated success reply carrying the bare promoted conversation object (#348, #274) — **and**
         * the server's unsolicited broadcast on a host-side create or auto-name, which carries no
         * `in_reply_to` at all. Both kinds are handled, on correlation and by the payload's own `id`
         * respectively (#721, daemon #2156/#2159/#2210).
         */
        const val TYPE_CONVERSATION_UPDATED = "conversation_updated"

        /**
         * Workspace-label notification (#721, daemon #2209): `{path, label}`, keyed by **workspace, not
         * conversation**. Arrives both as the correlated reply to `rename_workspace` (whose request side
         * is #663's) and as the unsolicited push the daemon fans to every *other* interactive-capable
         * conn; both are applied. Live-only — it carries no `event_id`, so there is no replay and a
         * client disconnected during a rename reads the label off its next [TYPE_CONVERSATIONS] snapshot.
         */
        const val TYPE_WORKSPACE_UPDATED = "workspace_updated"

        /** Request: one-shot text snapshot of the current claude screen (#375, #617 `RequestSnapshot`). */
        const val TYPE_REQUEST_SNAPSHOT = "request_snapshot"

        /** Correlated success reply carrying the rendered screen text (#375, #617 `ScreenSnapshot`). */
        const val TYPE_SCREEN_SNAPSHOT = "screen_snapshot"

        /** Structured-stream event: coarse turn lifecycle `{conversation_id, state}` (#385, #607). */
        const val TYPE_TURN_STATE = "turn_state"

        /** Structured-stream event: incremental assistant text `{…, seq, text}` (#385, #607). */
        const val TYPE_ASSISTANT_DELTA = "assistant_delta"

        /** Structured-stream event: a tool invocation `{…, tool_use_id, name, input_summary}` (#385, #607). */
        const val TYPE_TOOL_USE = "tool_use"

        /** Structured-stream event: a tool result `{…, tool_use_id, is_error, result_summary}` (#385, #607). */
        const val TYPE_TOOL_RESULT = "tool_result"

        /** Structured-stream event: end of a turn `{…, turn_id, stop_reason}` (#385, #607). */
        const val TYPE_TURN_END = "turn_end"

        /**
         * Capability-gated control event: a remote-head stall `{conversation_id}` (#395, #638/#639) —
         * onset-only, no clearing edge on the wire (recovery is inferred from forward progress).
         */
        const val TYPE_STALL = "stall"

        /**
         * Capability-gated snapshot event: a conversation's queued-message backlog
         * `{conversation_id, queued:[{queued_msg_id, text, ts}]}` (#460, pyrycode#705/#720) — the full
         * current backlog (`msgqueue.Snapshot`) in FIFO order, each snapshot replacing the prior one.
         */
        const val TYPE_QUEUE_STATE = "queue_state"

        /**
         * Capability-gated snapshot event: a conversation's published model menu
         * `{conversation_id, models:[{resolved_model, value, display_name, effort_levels,
         * supports_auto_mode, truncated_fields}], dropped_models}` (#791) — the vocabulary claude
         * reported for its `initialize` ask, each frame replacing the prior menu for that conversation.
         * Arrives both on the live interactive lane (with an `event_id`) and as a per-conversation burst
         * on every (re)connect (with none); the payload is identical on both paths.
         */
        const val TYPE_MODEL_LIST = "model_list"

        /**
         * Capability-gated status event: claude is retrying an API error
         * `{conversation_id, active, current, total}` (#593, pyrycode#1074) — `active` is the edge
         * (`true` onset / `false` recovered), `current`/`total` mirror claude's on-screen `attempt N/M`
         * counter (both `0` when it did not parse). The rising edge re-fires on a counter climb; the
         * falling edge carries the last-known counter, which a client ignores.
         */
        const val TYPE_API_RETRY = "api_retry"

        /**
         * Capability-gated status event: claude is auto-compacting a conversation's context
         * `{conversation_id, active}` (#596, pyrycode#1074) — `active` is the edge (`true` onset /
         * `false` finished). Banner-only: the upstream detector streams no progress, so the payload
         * carries no counter, percent, or ETA. Unlike [TYPE_STALL] this **has** a clearing edge on the
         * wire, so the state is cleared explicitly rather than inferred from forward progress.
         */
        const val TYPE_COMPACTING = "compacting"

        /**
         * Capability-gated thread event: a session transition `{conversation_id, previous_session_id,
         * new_session_id, reason, occurred_at, workspace_cwd}` (#336, pyrycode#656/#657/#740) — folds a
         * [ThreadItem.SessionBoundary] into the conversation thread (keyed by `conversation_id`) in
         * arrival order at a `/clear` / idle-evict / workspace-change transition. `reason` ∈ {`clear`,
         * `idle_evict`, `workspace_change`}; `workspace_cwd` is non-null only for `workspace_change`.
         */
        const val TYPE_SESSION_TRANSITION = "session_transition"

        /**
         * Capability-gated thread event: a claude message the daemon's stream-json parser could not map
         * `{conversation_id, site, message_type, raw, truncated}` (#609, pyrycode#1074) — folds a
         * [ThreadItem.UnrecognizedMessage] into the conversation thread (keyed by `conversation_id`) in
         * arrival order. `site` ∈ {`line_type`, `assistant_block`, `user_block`, `undecodable`};
         * `message_type` is empty on `undecodable`; `raw` is the offending JSON as a **string**, capped
         * daemon-side at 16 KiB. Unlike its `stall` / `api_retry` / `compacting` neighbours this reports a
         * gap in **our own** mapping rather than what claude is doing, and unlike every turn-stream event
         * it carries **no `turn_id`** and drives no turn lifecycle. Repeats are never coalesced — on the
         * wire or here — because the firing frequency *is* the signal.
         */
        const val TYPE_UNRECOGNIZED_MESSAGE = "unrecognized_message"

        /**
         * Outbound queue control: the phone's request to drop a not-yet-drained message
         * `{conversation_id, queued_msg_id}` from a conversation's backlog (#466, pyrycode#723, ADR
         * 025) — the outbound peer of [TYPE_QUEUE_STATE]. Success is an empty `ack`; the backlog
         * updates via the next `queue_state`, not a reply.
         */
        const val TYPE_DEQUEUE_MESSAGE = "dequeue_message"

        /**
         * Capability-gated modal event: a surfaced permission/choice modal
         * `{modal_id, class, title, prompt, options, default_option_id}` (#437, pyrycode#701) — no
         * `conversation_id`; `modal_id` is the sole correlation key.
         */
        const val TYPE_MODAL_SHOWN = "modal_shown"

        /**
         * Capability-gated modal event: a resolved modal `{modal_id, outcome, source}` (#437,
         * pyrycode#701) — `source` ∈ {`remote`, `local`, `timeout`}. The outbound `modal_answer`/
         * `modal_cancel` constants belong to the answer-send slice (#438).
         */
        const val TYPE_MODAL_DISMISSED = "modal_dismissed"

        /**
         * Capability-gated outbound modal control: the phone's answer
         * `{modal_id, option_id, answer_token}` to a surfaced modal (#438, pyrycode#701). The
         * `answer_token` is a client-minted idempotency key (uniqueness + stability matter, secrecy
         * does not), letting the daemon collapse a replayed/reordered answer to a no-op. Authorization
         * is `modal_id` validity plus the per-device answer gate (pyrycode#702), never this token.
         */
        const val TYPE_MODAL_ANSWER = "modal_answer"

        /**
         * Capability-gated outbound modal control: the phone's cancellation `{modal_id}` of a surfaced
         * modal (#438, pyrycode#701) — carries no idempotency token; a re-cancel of an already-resolved
         * modal is a stale-`modal_id` reject the daemon handles.
         */
        const val TYPE_MODAL_CANCEL = "modal_cancel"

        /**
         * Outbound control: the phone's bare `interrupt` (#458, pyrycode#707) — the wire half of Esc.
         * No payload, no reply, interactive-gated server-side, permission-gate-exempt; replay-safe.
         */
        const val TYPE_INTERRUPT = "interrupt"

        /**
         * Outbound control: the phone's bare `new_session` (#539, pyrycode#831) — the wire half of
         * "New session" (/clear). No payload, no reply, interactive-gated server-side; replay-safe.
         */
        const val TYPE_NEW_SESSION = "new_session"

        /**
         * Capability-gated control marker: a replay resync `{conversation_id}`, no `event_id` (#417,
         * pyrycode#646/#647) — the daemon's signal that the advertised `last_event_id` aged out of its
         * bounded ring, so gap-free in-ring replay is impossible. The phone resets its replay cursor and
         * surfaces the gap; full reload via `backfill_since` is deferred (no daemon handler yet).
         */
        const val TYPE_RESYNC = "resync"

        /** Correlated success reply (empty `{}`) to a request, matched on `in_reply_to` (#346). */
        const val TYPE_ACK = "ack"

        /** Correlated failure reply (`{code, message, retryable}`) to a request (#346, #272). */
        const val TYPE_ERROR = "error"

        /** Server `error.code` for an unknown conversation → [IllegalArgumentException] (#346, AC #3). */
        const val ERROR_CONVERSATION_NOT_FOUND = "conversation.not_found"

        /** Client-side synthetic code for an undecodable `error` payload (#346 fallback, never hangs). */
        const val ERROR_MALFORMED_REPLY = "error.malformed_reply"

        /**
         * Static, payload-free message for the [IllegalStateException] [failAllPending] fails every
         * in-flight request with on teardown (#488). Carries no request content, `modalId`, or ids —
         * the never-log contract holds by construction.
         */
        const val PENDING_REQUEST_TORN_DOWN = "connection torn down before reply"

        /**
         * RFC-3339 epoch cursor for "all history on first load" — `backfill_since.since_ts` is a
         * required `time.Time` on the wire (#272), so full history is the epoch, not an absent field.
         */
        const val BACKFILL_ALL_HISTORY_SINCE = "1970-01-01T00:00:00Z"

        /**
         * Advisory cap on the backfilled-message count (`backfill_since.max_messages`, #272). Sized
         * to cover a full thread without truncation; the server chunks the response and may deliver
         * fewer. The backfill dispatcher is not yet live, so this value is not yet exercised against
         * real server behaviour.
         */
        const val BACKFILL_MAX_MESSAGES = 10_000
    }
}
