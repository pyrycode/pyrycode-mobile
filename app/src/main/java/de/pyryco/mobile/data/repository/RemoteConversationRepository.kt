package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.BackfillSincePayloadDto
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.CreateConversationPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MessageChunkPayloadDto
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PromoteConversationPayloadDto
import de.pyryco.mobile.data.network.RegisterPushTokenPayloadDto
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.ReplayCursor
import de.pyryco.mobile.data.network.RequestSnapshotPayloadDto
import de.pyryco.mobile.data.network.ScreenSnapshotPayloadDto
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.StallPayloadDto
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.TurnStatePayloadDto
import de.pyryco.mobile.data.network.toConversation
import de.pyryco.mobile.data.network.toConversations
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * (#314), and the methods with no documented v2 wire message (`archive` / `unarchive` / `rename` /
 * `startNewSession` / `changeWorkspace`). [delete], [recentWorkspaces], and [createWorkspaceFolder]
 * keep their interface defaults — intentionally outside this slice's surface.
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
     * retry-merge rather than clobber. [promote] additionally **reads** [projection]`.value` (a
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
     * `conversationId -> ordered, message_id-deduped thread` of every [Message] seen for the
     * conversation — backfilled history (`message_chunk`) plus live `message`s, in wire/arrival
     * order (#313). Written by the single [init] inbound collector **and** by [sendMessage]'s
     * confirmed insert (#346) — two writers, but every write goes through the atomic
     * [appendMessages] / [MutableStateFlow.update] fold, so concurrent updates retry-merge correctly.
     * [observeMessages] fans out from it. Each value is order-preserving: first insertion fixes a
     * message's position, a repeat `message_id` updates it in place (the dedup rule), so the thread
     * is complete-on-first-emission once backfill arrives and live messages append after.
     */
    private val messagesByConversation = MutableStateFlow<Map<String, List<Message>>>(emptyMap())

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

    private val requestId = AtomicLong(0)

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

    init {
        // The single consumer of the hot, single-consumer inbound stream. Cancelled by its owner
        // (#279/#302) when the connection ends; the pump completing `inbound` on teardown also ends it.
        scope.launch {
            pump.inbound.collect { envelope -> onInbound(envelope) }
        }
    }

    private fun onInbound(envelope: Envelope) {
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
            TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED, TYPE_SCREEN_SNAPSHOT ->
                // Success reply to a correlated request, handed verbatim to the waiter. An `ack`
                // (#346) carries the empty `{}` the bare-ack waiter ignores; a `conversation_created`
                // (#347) / `conversation_updated` (#348) carries the bare conversation object the
                // mutation ([createDiscussion] / [promote]) decodes for its typed return; a
                // `screen_snapshot` (#375) carries the rendered-screen payload [requestScreenSnapshot]
                // decodes for its `text`. An `inReplyTo` matching no pending entry (or null) is a
                // no-op: `list_conversations` / `backfill_since` draw no reply here; `screen_snapshot`
                // is always a correlated reply (no unsolicited push), so an unmatched one is harmless;
                // `conversation_updated` is also the server's unsolicited broadcast on change (no
                // `inReplyTo`), which must stay a harmless no-op (the authoritative `conversations`
                // snapshot drives an unsolicited list refresh, not this delta); and `complete` is
                // idempotent so a duplicate reply is harmless.
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
                        // Correlate the tool-call pair into a single evolving thread row (#387): a
                        // `tool_use` opens a running row, its matching `tool_result` updates it in
                        // place. Folds into the same `messagesByConversation` the live `message` arm
                        // writes, so tool rows interleave by arrival order (AC #4). The other event
                        // types are surfaced only on the live-event stream below.
                        when (event) {
                            is LiveSessionEvent.ToolUse -> applyToolUse(event)
                            is LiveSessionEvent.ToolResult -> applyToolResult(event)
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
     * Append [rows] (`conversationId -> Message`) into [messagesByConversation] in one atomic
     * [MutableStateFlow.update], preserving order and deduping by `message_id`: a first-seen id is
     * appended at the end, a repeat id replaces the existing row **in place** (position fixed at
     * first occurrence, last write wins). Batching a whole chunk into one update avoids emitting an
     * intermediate list per row. No-op on an empty batch so a malformed/empty chunk never re-emits.
     */
    private fun appendMessages(rows: List<Pair<String, Message>>) {
        if (rows.isEmpty()) return
        messagesByConversation.update { current ->
            val updated = current.toMutableMap()
            for ((conversationId, message) in rows) {
                val existing = updated[conversationId].orEmpty()
                val index = existing.indexOfFirst { it.id == message.id }
                updated[conversationId] =
                    if (index >= 0) {
                        existing.toMutableList().apply { this[index] = message }
                    } else {
                        existing + message
                    }
            }
            updated
        }
    }

    /**
     * Open a live tool-call row for a `tool_use` (#387): append a `Running` [Role.Tool] [Message]
     * carrying the tool name + input, keyed by [LiveSessionEvent.ToolUse.toolUseId] (the correlation
     * handle and the row's [Message.id]). One atomic [MutableStateFlow.update] into the same
     * [messagesByConversation] the live `message` arm writes, so the row interleaves by **arrival
     * order** with messages (AC #4). **Idempotent on a repeat id:** if a [Role.Tool] row with this id
     * already exists (possibly already completed by an earlier `tool_result`), it is left untouched —
     * a duplicate `tool_use` never adds a second row nor resets a finished one to `Running` (AC #3).
     * The `&& role == Role.Tool` match namespaces tool rows so a `toolUseId` can never clobber a real
     * `message_id` row. The fields the UI ignores ([Message.content] = the tool name, a non-empty
     * fallback; [Message.timestamp] = [Clock.System.now], the established locally-assembled-row clock —
     * thread order is arrival order, never a timestamp sort) mirror [sendMessage]'s posture. The tool
     * name/input/output are carried **verbatim** — never trimmed, parsed, or logged (Security review).
     */
    private fun applyToolUse(event: LiveSessionEvent.ToolUse) {
        messagesByConversation.update { current ->
            val existing = current[event.conversationId].orEmpty()
            if (existing.any { it.id == event.toolUseId && it.role == Role.Tool }) {
                current
            } else {
                val row =
                    Message(
                        id = event.toolUseId,
                        sessionId = "",
                        role = Role.Tool,
                        content = event.name,
                        timestamp = Clock.System.now(),
                        isStreaming = false,
                        toolCall =
                            ToolCall(
                                toolName = event.name,
                                input = event.inputSummary,
                                output = "",
                                status = ToolCallStatus.Running,
                            ),
                    )
                current + (event.conversationId to (existing + row))
            }
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
        messagesByConversation.update { current ->
            val existing = current[event.conversationId].orEmpty()
            val index = existing.indexOfFirst { it.id == event.toolUseId && it.role == Role.Tool }
            if (index < 0) {
                current
            } else {
                val row = existing[index]
                val updated =
                    row.copy(
                        toolCall =
                            row.toolCall?.copy(
                                output = event.resultSummary,
                                status = if (event.isError) ToolCallStatus.Failed else ToolCallStatus.Done,
                            ),
                    )
                current + (event.conversationId to existing.toMutableList().apply { this[index] = updated })
            }
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
     * Cold per-conversation thread view: the ordered, deduped [ThreadItem.MessageItem] list for
     * [conversationId]. [distinctUntilChanged] means a change to **another** conversation's slot
     * does not re-emit this flow (AC #3). A `StateFlow` always has a value, so a fresh collector
     * receives the current thread (empty until backfill/live arrives) on subscription.
     */
    private fun threadProjection(conversationId: String): Flow<List<ThreadItem>> =
        messagesByConversation
            .map { byConversation ->
                byConversation[conversationId].orEmpty().map { message -> ThreadItem.MessageItem(message) }
            }.distinctUntilChanged()

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

    override suspend fun archive(conversationId: String): Unit =
        throw UnsupportedOperationException("archive: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun unarchive(conversationId: String): Unit =
        throw UnsupportedOperationException("unarchive: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = throw UnsupportedOperationException("rename: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = throw UnsupportedOperationException("startNewSession: no v2 wire message defined (follow-up specs the wire contract)")

    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = throw UnsupportedOperationException("changeWorkspace: no v2 wire message defined (follow-up specs the wire contract)")

    private companion object {
        /** Request: list the conversations (payload `{}` per protocol). */
        const val TYPE_LIST_CONVERSATIONS = "list_conversations"

        /** Response/push: a full-list `{conversations:[…]}` snapshot — also unsolicited on change. */
        const val TYPE_CONVERSATIONS = "conversations"

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

        /**
         * Correlated success reply carrying the bare promoted conversation object (#348, #274) — also
         * the server's unsolicited broadcast to all phones on a conversation change.
         */
        const val TYPE_CONVERSATION_UPDATED = "conversation_updated"

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

        /** Correlated success reply (empty `{}`) to a request, matched on `in_reply_to` (#346). */
        const val TYPE_ACK = "ack"

        /** Correlated failure reply (`{code, message, retryable}`) to a request (#346, #272). */
        const val TYPE_ERROR = "error"

        /** Server `error.code` for an unknown conversation → [IllegalArgumentException] (#346, AC #3). */
        const val ERROR_CONVERSATION_NOT_FOUND = "conversation.not_found"

        /** Client-side synthetic code for an undecodable `error` payload (#346 fallback, never hangs). */
        const val ERROR_MALFORMED_REPLY = "error.malformed_reply"

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
