package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.BackfillSincePayloadDto
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.ConversationsPayload
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MessageChunkPayloadDto
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModalDismissedPayloadDto
import de.pyryco.mobile.data.network.ModalShownPayloadDto
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.ReplayCursor
import de.pyryco.mobile.data.network.RequestContextUsagePayloadDto
import de.pyryco.mobile.data.network.RequestHistoryPayloadDto
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.TurnStatePayloadDto
import de.pyryco.mobile.data.network.WorkspaceUpdatedPayloadDto
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toHistoryPage
import de.pyryco.mobile.data.network.toMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
 * [Envelope.type] into the list held by [ConversationListProjection]. [observeConversations] is a cold flow
 * that fans out from that projection, so N concurrent collectors share the one inbound consumer.
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
    /**
     * The wall clock the usage-limit expiry reads (#802) — a **supplier**, not a value, for
     * [negotiatedCapabilities]' reason: the expiry is evaluated every time a collector reads
     * [observeUsageLimit], long after construction, so a captured instant would freeze it.
     *
     * [kotlinx.datetime.Instant] rather than a bare seconds `Long` is the type-level defence against
     * the unit hazard this comparison carries: a caller supplying milliseconds would hand over a value
     * a thousand times larger than any real `resets_at`, expiring every reading the instant it landed,
     * with no type error and a symptom ("nothing ever shows") identical to the daemon having sent
     * nothing. `Instant.epochSeconds` is the only route to a number here, so the unit cannot be got
     * wrong. **Defaulted** so every existing construction (tests, the coordinator, the scripted
     * harness) compiles unchanged; only a test supplies its own.
     *
     * Used only to build the default [hostReadings] (#1317). A caller that supplies [hostReadings], as the
     * coordinator does, supplies the clock with it, and this one is unused.
     */
    now: () -> Instant = Clock.System::now,
    /**
     * Which background tasks this host has finished (#677), the one piece of background-task state that
     * outlives a connection. [RelayRepositoryCoordinator] owns the host-lifetime instance and threads it into
     * each repository, the [replayCursor] shape. **Defaulted to a throwaway instance** so existing
     * constructions compile unchanged.
     */
    private val finishedBackgroundTasks: FinishedBackgroundTasks = FinishedBackgroundTasks(),
    /**
     * The five readings the host pushes (#1317): announced model, session facts, context usage, usage limit and
     * slash-command menu. Only context usage is also asked for, by [requestContextUsage] (#1410). [RelayRepositoryCoordinator] owns the instance
     * for the host's pairing and threads it into each repository, the [finishedBackgroundTasks] shape, so a
     * reconnect starts from the held readings rather than nothing. Every arm still applies, replaces and
     * clears through it as before. Since #1320 it also holds the model menus and the last successful
     * settings reply. **Defaulted to a throwaway instance** on this repository's [now], so existing
     * constructions compile unchanged and keep connection-scoped readings.
     */
    hostReadings: HostReadings = HostReadings(now),
    /**
     * The process-wide message trail (#1564), kept in release builds: [sendMessage]'s sent, acknowledged and
     * failed lines and the thread's queued, delivered and dropped ones. [RelayRepositoryCoordinator] threads
     * the `AppModule` instance in. **Defaulted to a file-less instance** so existing constructions compile.
     */
    messageTrail: MessageTrail = MessageTrail(),
    /**
     * This connection's `conn_id` as [de.pyryco.mobile.data.network.RelayLog.redactConnId]'s 8-hex token, for
     * the trail's sent line (#1564). A supplier for [negotiatedCapabilities]' reason: the repository is built
     * before `Open`. Never the full `conn_id`.
     */
    connToken: () -> String? = { null },
) : ConversationRepository,
    ThreadSnapshotSource {
    /**
     * The conversation list and the last-message previews (#913): the list projection, the most-recent
     * message per conversation, and every write that folds into either. [onInbound] hands it the
     * `conversations` snapshot, and the mutations and the `message` / `conversation_updated` /
     * `workspace_updated` / `session_transition` arms fold into it once they have decoded their frame.
     */
    private val conversationListProjection = ConversationListProjection()

    /**
     * The per-conversation status readings, one small projection per wire event: stall (#395), queue
     * (#460), API retry (#593), compaction (#596), usage limit (#802), thinking progress (#801), reset
     * phase (#871), and the announced model and session facts (#890). Each
     * owns its state, its decoder and its read. [onInbound] hands each its own envelope type behind the
     * `interactive` gate, and the clears one event causes in another stay in the arm that causes them.
     * The usage limit, announced model and session facts come from [HostReadings], held for the host's
     * pairing (#1317); the rest are this connection's own.
     */
    private val stallProjection = StallProjection()
    private val sessionErrorProjection = SessionErrorProjection()
    private val queueProjection = QueueProjection()
    private val apiRetryProjection = ApiRetryProjection()
    private val compactingProjection = CompactingProjection()
    private val usageLimitProjection = hostReadings.usageLimit
    private val thinkingProgressProjection = ThinkingProgressProjection()
    private val resettingProjection = ResettingProjection()

    /** The turn phase of every conversation on this connection (#1313); see [TurnPhaseProjection]. */
    private val turnPhaseProjection = TurnPhaseProjection()
    private val announcedModelProjection = hostReadings.announcedModel
    private val sessionFactsProjection = hostReadings.sessionFacts

    /**
     * The context-usage reading of every conversation (#945), held for the host's pairing (#1317). [onInbound]
     * hands it `context_usage` behind the `interactive` gate and the `session_transition` clear. It sends
     * nothing; the ask is this connection's [requestContextUsage] (#1410).
     */
    private val contextUsageProjection = hostReadings.contextUsage

    /**
     * The MCP server reading of every conversation on **this connection** (#1343): the held report, the five
     * request flags, the three request verbs and their refusal correlation. Deliberately not in [HostReadings]: a
     * reconnect starts from nothing and the surface asks again. [onInbound] hands it `mcp_status` behind the
     * `interactive` gate and the refusal half of `error`. Its ids come from [relayRequests]' one counter, through a
     * lambda for the reason [modelMenuProjection] gives.
     */
    private val mcpStatusProjection =
        McpStatusProjection(
            send = pump::send,
            negotiatedCapabilities = negotiatedCapabilities,
            nextRequestId = { relayRequests.nextRequestId() },
        )

    /**
     * The thread of every conversation (#912): the thread store, the minted-id ledger and the pending drops,
     * with every write that folds a thread row. [onInbound] hands it the thread frames behind the
     * `interactive` gate, and [sendMessage], [dropQueuedMessage] and [requestHistory] record into it.
     */
    private val threadProjection = ThreadProjection(messageTrail)

    /**
     * The files the daemon offered in each conversation on this connection (#898), each also appended to
     * [threadProjection] as a row the first time it arrives (#983). Declared after it for that reason.
     */
    private val attachmentOfferProjection = AttachmentOfferProjection(threadProjection)

    /**
     * The model menu of every conversation (#913): the retained menus, the one-shot `request_model_list`
     * ask and its refusal correlation, the menus held in [HostReadings] (#1320) and the asks this
     * connection's own. [onInbound] hands it `model_list` behind the `interactive` gate and
     * the refusal half of `error`; [observeModelMenu] reads it. Its ask takes its envelope id from this
     * repository's one counter in [relayRequests] — read through a lambda, because [relayRequests] is
     * declared below and a bound reference would capture it before it is initialised.
     */
    private val modelMenuProjection =
        ModelMenuProjection(
            send = pump::send,
            negotiatedCapabilities = negotiatedCapabilities,
            nextRequestId = { relayRequests.nextRequestId() },
            readings = hostReadings,
        )

    /**
     * The slash-command menu of every conversation (#882), held for the host's pairing (#1317). [onInbound]
     * hands it `slash_command_list` behind the `interactive` gate; [observeSlashCommandMenu] reads it. It
     * sends nothing: the frame has no verb.
     */
    private val slashCommandMenuProjection = hostReadings.slashCommandMenu

    /**
     * The request↔reply plumbing of this connection (#914): the one envelope-id counter every request takes
     * its id from, the reply waiters, the correlated await and the teardown sweep. [onInbound] completes or
     * fails waiters through it, and the [init] collector's `finally` sweeps it last.
     */
    private val relayRequests = RelayRequests(send = pump::send)

    /**
     * The conversation commands (#914): create, promote, rename, archive, unarchive, delete, new session,
     * interrupt, push-token registration and the modal answer and cancel. Each public command below hands
     * off to it in one line.
     */
    private val conversationCommands =
        ConversationCommands(
            requests = relayRequests,
            send = pump::send,
            conversationList = conversationListProjection,
            threadProjection = threadProjection,
            deviceName = deviceName,
        )

    /**
     * The message and transfer commands (#915): sending, attachment uploads, the screen snapshot, dropping a
     * queued message, and the debug bundle, with the upload and debug bundle transfer state. [onInbound]
     * offers each frame to its two transfers first, and the [init] collector's `finally` ends both before the
     * pending-request sweep. Each public command below hands off to it in one line.
     */
    private val messageCommands =
        MessageCommands(
            requests = relayRequests,
            send = pump::send,
            conversationList = conversationListProjection,
            threadProjection = threadProjection,
            queueProjection = queueProjection,
            trail = messageTrail,
            connToken = connToken,
        )

    /**
     * The attachment retrievals of this connection (#899): one `request_attachment` at a time, answered by chunks
     * or an `error` naming it. [onInbound] offers each frame to it after the upload, and the [init] collector's
     * `finally` ends it beside the upload.
     */
    private val attachmentRetrievals =
        AttachmentRetrievals(
            nextRequestId = { relayRequests.nextRequestId() },
            send = pump::send,
        )

    /**
     * The session settings and system prompt commands (#916): the settings read, its refresh trigger and
     * the per-conversation revision map behind it, the settings write, and the system prompt read and
     * write. [onInbound]'s `session_transition` arm bumps its revision; each public command below hands
     * off to it in one line.
     */
    private val sessionSettingsCommands =
        SessionSettingsCommands(
            requests = relayRequests,
            negotiatedCapabilities = negotiatedCapabilities,
            conversationList = conversationListProjection,
            readings = hostReadings,
        )

    /**
     * The workspace commands (#916): recent workspaces, changing a conversation's workspace, creating a
     * workspace folder, and renaming and archiving a workspace. [onInbound]'s `workspace_updated` arm
     * takes its static malformed-reply error from it; each public command below hands off to it in one line.
     */
    private val workspaceCommands =
        WorkspaceCommands(
            requests = relayRequests,
            conversationList = conversationListProjection,
            conversationCommands = conversationCommands,
        )

    /** One attempt per connection (#683); see [MessageCommands.requestDebugBundle]. */
    internal fun requestDebugBundle(): DebugBundleTransfer = messageCommands.requestDebugBundle()

    /** Fails a running debug bundle and refuses later ones; see [MessageCommands.endDebugBundle]. */
    internal fun endDebugBundle() = messageCommands.endDebugBundle()

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

    /**
     * Live refusal frames and session transitions keyed by conversation id (#1360), the source of
     * [observeLiveRefusalEvents]. The [mutableLiveSessionEvents] posture: events, not state, so nothing is
     * replayed, and [BufferOverflow.DROP_OLDEST] keeps [MutableSharedFlow.tryEmit] from ever stalling the
     * inbound collector. A dropped event can only leave a switch-back offer unarmed or stale; a write still
     * needs the user's tap.
     */
    private val liveRefusalEvents =
        MutableSharedFlow<Pair<String, LiveRefusalEvent>>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * The clarification batches outstanding on **this connection** (#822), held by [QuestionBatchProjection]
     * (#913), which folds `question_shown` / `question_dismissed` and sends the answers and refusals. A new
     * connection builds a new repository, so this starts empty and the reconcile rebuilds it. On the concrete
     * repository only, like [modalEvents].
     */
    private val questionBatchProjection =
        QuestionBatchProjection(
            send = pump::send,
            nextRequestId = { relayRequests.nextRequestId() },
        )
    val questionBatches: StateFlow<List<QuestionBatch>> = questionBatchProjection.batches

    /** Independent stop-refusal ledger; IDs come from the same counter as ordinary reply waiters. */
    private val backgroundTaskStops = BackgroundTaskStops(relayRequests, pump::send, negotiatedCapabilities)

    /**
     * The background tasks each conversation holds on **this connection** (#677), keyed by conversation id; a
     * missing key means nothing has been reported. Folded by [BackgroundTaskProjection] from the three
     * `background_task_*` frames; only the finished marks in [finishedBackgroundTasks] predate this
     * connection. On the concrete repository only, like [questionBatches].
     */
    private val backgroundTaskProjection =
        BackgroundTaskProjection(
            finishedBackgroundTasks,
            onTaskFinished = backgroundTaskStops::taskFinished,
            onRosterReported = backgroundTaskStops::rosterReported,
        )
    val backgroundTasks: StateFlow<Map<String, BackgroundTaskRoster>> = backgroundTaskProjection.rosters

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
                endBackgroundTaskStops()
                sessionErrorProjection.reset()
                endDebugBundle()
                messageCommands.endAttachmentUploads()
                attachmentRetrievals.end()
                relayRequests.failAllPending()
            }
        }
    }

    private fun onInbound(envelope: Envelope) {
        if (messageCommands.routeDebugBundle(envelope)) return
        if (messageCommands.routeAttachmentUpload(envelope)) return
        if (attachmentRetrievals.route(envelope)) return
        recordReplayCursor(envelope)
        when (envelope.type) {
            TYPE_CONVERSATIONS ->
                // A full-list snapshot, reply or unsolicited push: see [ConversationListProjection.applySnapshot].
                conversationListProjection.applySnapshot(envelope)
            TYPE_MESSAGE -> {
                // A live (or send_message-echo) `message` envelope. Decode + map through the single
                // #317 boundary; a malformed payload (missing field / unmappable role →
                // SerializationException, bad envelope ts → IllegalArgumentException via Instant.parse;
                // the former is a subtype of the latter) is dropped so the single inbound consumer
                // survives. `sessionId = ""` — the payload carries none and the last-message preview
                // never reads it (list-tier placeholder, as #312 uses for currentSessionId). Drop
                // silently: message content may be sensitive, so nothing here logs the payload.
                val (conversationId, message, sentNow) =
                    try {
                        val dto = MobileJson.decodeFromJsonElement<MessagePayloadDto>(envelope.payload)
                        val message = dto.toMessage(envelope, sessionId = "")
                        Triple(
                            dto.conversationId,
                            if (message.role == Role.User) {
                                message.copy(attachments = storedAttachmentReferences(dto.attachmentIds))
                            } else {
                                message
                            },
                            dto.sentNow,
                        )
                    } catch (e: IllegalArgumentException) {
                        return
                    }
                // Keep the most-recent by timestamp (the strictly-greater fold below). Only a user
                // message is a thread row (#1351), as on desktop: the v2 path mints `message` for the
                // operator's delivered turn alone, and assistant output arrives as structured events.
                // A held id — the phone's own confirmed send among them — is kept, not replaced.
                conversationListProjection.recordLastMessage(conversationId, message)
                if (message.role == Role.User) threadProjection.appendLiveMessage(conversationId, message, sentNow)
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
                threadProjection.appendMessages(rows)
            }
            TYPE_CONVERSATION_UPDATED -> {
                // TWO kinds of producer, and the protocol requires a client to accept both (#721): a
                // *correlated* reply (promote / rename / archive / unarchive / change_workspace /
                // set_system_prompt), and an *unsolicited push* (`pyry channel new` on the host, or the
                // one-shot auto-naming of a never-named conversation) that carries no `in_reply_to`.
                // Both fold here by the payload's own `id`, which is what makes a rename made on another
                // client reach this phone's live list (and, since #720, carries that row's
                // `workspace_label` with it) instead of waiting for the next snapshot.
                // A correlated reply folds here too, before its waiter completes (#996). The waiter's
                // own upsert runs on the caller's scope, and a thread popped straight after Save
                // cancels that scope between this completion and the caller's resumption, so the
                // reply had no other writer and the list kept the old name. The caller's later upsert
                // writes the same row again, which the projection's conflation absorbs.
                // Decode-or-drop guards the fold only: a malformed payload mutates nothing, still reaches
                // its waiter verbatim (whose own decode then throws), and the single inbound consumer
                // survives. Drop silently — the record carries the conversation's name and cwd, so
                // nothing here logs the payload.
                val record =
                    try {
                        MobileJson.decodeFromJsonElement<ConversationResponseDto>(envelope.payload)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                record?.let(conversationListProjection::upsertConversation)
                relayRequests.waiter(envelope.inReplyTo)?.complete(envelope.payload)
            }
            TYPE_WORKSPACE_UPDATED -> {
                // A workspace-label notification (#721). Like `conversation_updated` it has two kinds of
                // producer — the correlated reply to `rename_workspace` and the unsolicited push the
                // daemon fans to every *other* interactive-capable conn — but unlike it the record is
                // applied **unconditionally**, whether or not `in_reply_to` is set (AC #1): the payload
                // is identical either way. A matching [renameWorkspace] waiter (#663) is completed only
                // **after** the apply, so the caller resumes onto rows that already carry the label.
                // Deliberately NOT capability-gated: the gate would break the
                // correlated half and buys nothing, since a daemon ignoring the negotiated set could
                // drive the same label change through an ungated `conversations` snapshot.
                // Decode-or-drop is the single failure surface (a missing/ill-typed `path` →
                // SerializationException ⊂ IllegalArgumentException), so a malformed frame leaves the
                // projection intact and the lone inbound collector alive for the next valid one (AC #3).
                // A malformed *correlated* one also fails its waiter with a static error, rather than
                // leaving the rename suspended until teardown; the decode exception is not forwarded,
                // since its message can quote the payload.
                // Drop silently: `path` is a filesystem location on the daemon's host and `label` is
                // operator-authored text — neither reaches a log on any branch, matching the daemon,
                // which records only a conn id and an event name for this verb.
                val waiter = relayRequests.waiter(envelope.inReplyTo)
                val decoded =
                    try {
                        MobileJson.decodeFromJsonElement<WorkspaceUpdatedPayloadDto>(envelope.payload)
                    } catch (e: IllegalArgumentException) {
                        waiter?.completeExceptionally(workspaceCommands.malformedWorkspaceReply())
                        return
                    }
                conversationListProjection.applyWorkspaceLabel(decoded.path, decoded.label)
                waiter?.complete(envelope.payload)
            }
            TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_DELETED,
            TYPE_SCREEN_SNAPSHOT, TYPE_SESSION_SETTINGS_UPDATED, TYPE_WORKSPACE_FOLDER_CREATED,
            TYPE_RECENT_WORKSPACES_LIST, TYPE_HISTORY_PAGE, TYPE_SESSION_SETTINGS, TYPE_SYSTEM_PROMPT, TYPE_HOST_SYSTEM_PROMPT,
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
                // harmless; and `complete` is idempotent so a duplicate reply is harmless. A `system_prompt`
                // (#823) is the same shape for [requestSystemPrompt]: no conversation_id, so it is routed
                // by `in_reply_to` alone, and without this entry its waiter would never complete.
                // `conversation_created` stays here deliberately: unlike `conversation_updated` (which
                // #721 moved to its own arm above) it is a correlated reply only — the create-on-host
                // push is a `conversation_updated`, not a `conversation_created`.
                relayRequests.waiter(envelope.inReplyTo)?.complete(envelope.payload)
            TYPE_ERROR ->
                // Failure reply to a correlated request (#346): unblock the waiter exceptionally with
                // the mapped domain error. `mapError` never throws (a malformed payload yields a
                // fallback exception), so the lone collector survives; `completeExceptionally` is
                // idempotent and a no-op when no entry matches.
                //
                // Since #792 the arm also carries the refusal half of `request_model_list`, whose ask
                // registers in [ModelMenuProjection]'s ask ledger and never in [RelayRequests.pendingRequests]. The two maps are disjoint
                // by construction, so an id resolves in at most one and the two lookups cannot consume
                // each other's reply; an `inReplyTo` matching neither (a stale, duplicated or
                // unsolicited error) is a no-op in both.
                envelope.inReplyTo?.let { id ->
                    relayRequests.waiter(id)?.completeExceptionally(relayRequests.mapError(envelope.payload))
                    modelMenuProjection.applyRefusal(id, envelope.payload)
                    // The MCP asks (#1343) register in their own ledger, disjoint by the same one counter.
                    mcpStatusProjection.applyRefusal(id, envelope.payload)
                    backgroundTaskStops.applyRefusal(id, envelope.payload)
                }
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
                        stallProjection.clear(event.conversationId)
                        // Hold the conversation's turn phase (#1313) for every conversation, open or not, so
                        // a thread opened mid-turn reads it at once. Only `turn_state` and `turn_end` move it.
                        turnPhaseProjection.apply(event)
                        if (event is LiveSessionEvent.TurnState && event.phase != LiveSessionEvent.TurnState.Phase.Idle) {
                            sessionErrorProjection.clear(event.conversationId)
                        }
                        // Fold the structured turn into the same thread store ([ThreadProjection]) the live
                        // `message` arm writes, so every row interleaves by arrival order (AC #4): a
                        // `tool_use`/`tool_result` pair into one evolving tool row (#387), and the
                        // `assistant_delta` stream into one streaming assistant row that `turn_end`
                        // finalizes (#337). `turn_state` folds no row — the thinking indicator reads the
                        // held phase above (#1313) — and every event is surfaced on the live-event stream
                        // below regardless of whether it also folds a row.
                        when (event) {
                            is LiveSessionEvent.AssistantDelta -> threadProjection.applyAssistantDelta(event)
                            is LiveSessionEvent.ToolUse -> threadProjection.applyToolUse(event)
                            is LiveSessionEvent.ToolResult -> threadProjection.applyToolResult(event)
                            is LiveSessionEvent.TurnEnd -> {
                                threadProjection.finalizeAssistantTurn(event)
                                // First of the two clears for #801's thinking-progress reading, which has
                                // no falling edge of its own: the turn whose reasoning it described has
                                // ended, so a retained reading would report the depth of a finished think.
                                // This belongs HERE, inside the TurnEnd branch — NOT beside the stall
                                // clear above, which runs for every live event: hoisted there it would
                                // wipe the reading on each `assistant_delta` and `tool_use` while still
                                // passing a turn-end test. Routed by the event's own conversation id, so
                                // one conversation's turn ending cannot clear another's reading, and a
                                // removal of an absent key is an inert no-op.
                                thinkingProgressProjection.clear(event.conversationId)
                            }
                            else -> Unit
                        }
                        mutableLiveSessionEvents.tryEmit(event)
                    }
                }
            }
            TYPE_TOOL_DENIED -> {
                // A refused tool call (#811): see [ThreadProjection.applyToolDenied]. Same `interactive` gate as the
                // structured-stream arm above, but no live event — it folds into the thread store only.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyToolDenied(envelope)
                }
            }
            TYPE_TOOL_PROGRESS -> {
                // claude's elapsed reading for an open call (#812): see [ThreadProjection.applyToolProgress]. The same gate and
                // thread-store-only shape as `tool_denied`; it clears no stall and emits no live event.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyToolProgress(envelope)
                }
            }
            TYPE_SESSION_ERROR -> {
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    sessionErrorProjection.apply(envelope)
                }
            }
            TYPE_STALL -> {
                // Stall onset (#395): see [StallProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    stallProjection.apply(envelope)
                }
            }
            TYPE_QUEUE_STATE -> {
                // Queued-backlog snapshot (#460): see [QueueProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    queueProjection.apply(envelope)
                    // A snapshot is the daemon's only confirmation of a drop (#859). Settling a
                    // conversation whose backlog did not change is a no-op, so settle every one pending.
                    threadProjection.settleDrops(queueProjection)
                    // After the drops, so a dropped echo is already gone: this device's queued echoes read
                    // below the running turn, and a drained one settles at the end of the thread (#1558). Only
                    // one first queued while a turn was open waits behind it (#1636).
                    threadProjection.settleQueuedEchoes(queueProjection, turnPhaseProjection::isOpen)
                }
            }
            TYPE_MODEL_LIST -> {
                // The per-conversation model menu (#791), and since #792 the answer to this client's own
                // ask: see [ModelMenuProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    modelMenuProjection.apply(envelope)
                }
            }
            TYPE_SLASH_COMMAND_LIST -> {
                // The per-conversation slash-command menu (#882), behind the same `interactive` gate as
                // `model_list`: see [SlashCommandMenuProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    slashCommandMenuProjection.apply(envelope)
                }
            }
            TYPE_API_RETRY -> {
                // API-retry status (#593): see [ApiRetryProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    apiRetryProjection.apply(envelope)
                }
            }
            TYPE_COMPACTING -> {
                // Context-compaction status (#596): see [CompactingProjection.apply]. The same frame folds
                // the thread's compaction divider (#1358): see [ThreadProjection.applyCompacting].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    compactingProjection.apply(envelope)
                    threadProjection.applyCompacting(envelope)
                }
            }
            TYPE_RATE_LIMITED -> {
                // What claude said about its usage-limit window (#802): see [UsageLimitProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    usageLimitProjection.apply(envelope)
                }
            }
            TYPE_THINKING_PROGRESS -> {
                // How far this conversation's reasoning has got (#801): see [ThinkingProgressProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    thinkingProgressProjection.apply(envelope)
                }
            }
            TYPE_RESETTING -> {
                // Where this conversation's Reset is (#871): see [ResettingProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    resettingProjection.apply(envelope)
                }
            }
            TYPE_MODEL_ANNOUNCED -> {
                // The model claude announced for this conversation's turn (#890): see [AnnouncedModelProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    announcedModelProjection.apply(envelope)
                }
            }
            TYPE_SESSION_FACTS -> {
                // What claude reported about its own run (#890): see [SessionFactsProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    sessionFactsProjection.apply(envelope)
                }
            }
            TYPE_CONTEXT_USAGE -> {
                // How full this conversation's context window is, pushed after a turn or answering the phone's own
                // ask (#945): see [ContextUsageProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    contextUsageProjection.apply(envelope)
                }
            }
            TYPE_MCP_STATUS -> {
                // A conversation's MCP server report, pushed or answering one of this client's MCP requests
                // (#1343): see [McpStatusProjection.apply].
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    mcpStatusProjection.apply(envelope)
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
                        threadProjection.appendSessionBoundary(conversationId, boundary)
                        conversationListProjection.updateCurrentSessionId(conversationId, boundary.newSessionId)
                        // Third write since #590: the session this conversation's settings describe has
                        // been replaced, so every reading of it is stale. Bump the trigger rather than
                        // reading here — the read belongs on a collector's coroutine, and a conversation
                        // nobody is watching must not send a frame. Routed by the same decoded
                        // conversation_id as its two siblings, so a transition cannot invalidate another
                        // conversation's reading.
                        sessionSettingsCommands.bumpSettingsRevision(conversationId)
                        // Fourth write since #801, and the second of the two clears for the
                        // thinking-progress reading: the session whose reasoning it described has been
                        // replaced, so the reading describes a think that can no longer be running.
                        // Routed by the same decoded conversation_id as its three siblings, so a
                        // transition cannot clear another conversation's reading; removing an absent key
                        // is an inert no-op.
                        thinkingProgressProjection.clear(conversationId)
                        // Fifth write since #871: a reset ends in a session transition, and the daemon emits
                        // `restarting` before it respawns claude, so no rising edge can follow this one. Routed
                        // by the same decoded conversation_id, so it cannot clear another conversation's reading.
                        resettingProjection.clear(conversationId)
                        // Sixth and seventh writes since #890: both readings describe the replaced session's
                        // run, and the new one reports its own on its first turn. Same routing, same no-op on
                        // an absent key.
                        announcedModelProjection.clear(conversationId)
                        sessionFactsProjection.clear(conversationId)
                        // Eighth write since #945: the context reading described the replaced session, so it is
                        // dropped until the new session's first turn ends. Same routing.
                        contextUsageProjection.onSessionTransition(conversationId)
                        // Ninth since #1360: the replaced session's switch-back offer is over. Emitted on the same
                        // flow as the refusals, so the two keep their wire order.
                        liveRefusalEvents.tryEmit(conversationId to LiveRefusalEvent.SessionReplaced)
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
                // the stall state in NEITHER direction (an unparseable message is not turn forward
                // progress, and clearing here would be a hostile-daemon lever for suppressing the stall
                // indicator) nor any other conversation status. Drop silently — nothing here logs any
                // payload field: `raw`/`message_type` are the most untrusted strings the thread holds, and a
                // logged conversation_id is a cross-conversation correlation leak.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyUnrecognizedMessage(envelope)
                }
            }
            TYPE_BANNER -> {
                // Text claude printed about the session (#873, pyrycode#2256). Same `interactive` gate as
                // its thread-row siblings above (fail-closed, defence in depth). Decode-or-drop: a malformed
                // payload or ts yields null → drop one envelope, the lone collector survives. Routes strictly
                // by the payload's conversation_id. Exactly ONE write — appendBanner folds the row — and
                // inert toward every neighbour: no liveSessionEvents emission, no turn opened, closed or
                // altered, no stall or other status touched. That holds for `stops_turn: true` too: it is a
                // report, and acting on it would hand claude a self-service turn abort. Nothing here logs
                // any payload field: `text` is claude-authored prose.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyBanner(envelope)
                }
            }
            TYPE_COMPACTION_BOUNDARY -> {
                // A finished compaction (#874, pyrycode#2237). Same `interactive` gate as its thread-row
                // siblings (fail-closed). Decode-or-drop: a malformed payload or ts yields null → drop one
                // envelope, the lone collector survives. Routes strictly by the payload's conversation_id.
                // Exactly ONE write — withCompactionBoundary fills the pending divider or appends one — and inert toward every
                // neighbour: `compacting` alone drives the status indicator, so this arm clears no compacting
                // state, emits no liveSessionEvents, and opens, closes or alters no turn. Nothing logged.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyCompactionBoundary(envelope)
                }
            }
            TYPE_MODEL_REFUSAL_FALLBACK, TYPE_MODEL_REFUSAL_NO_FALLBACK -> {
                // claude refused a turn on one model, and retried on another or did not (#875,
                // pyrycode#2265/#2266). Same `interactive` gate as its thread-row siblings (fail-closed).
                // Decode-or-drop by envelope type: a malformed payload or ts drops one envelope, the lone
                // collector survives. Routes strictly by the payload's conversation_id. Exactly ONE write —
                // the refusal row — and inert toward every neighbour: no liveSessionEvents emission, no turn
                // opened, closed or altered, no status touched, and no model state, which `model_announced`
                // alone owns. Nothing here logs any payload field: all of them but the id are claude's.
                // #1360: the one other write is the live signal, carrying the decoded refusal with its `scope`.
                // Only this live arm emits it; the history lane never does.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    threadProjection.applyModelRefusal(envelope)?.let { liveRefusalEvents.tryEmit(it) }
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
            TYPE_QUESTION_SHOWN, TYPE_QUESTION_DISMISSED -> {
                // A v2 clarification batch (#822). Same `interactive` gate as the modal arm above; not a
                // modal, so it never reaches modalEvents. Held per connection, not a thread row, and it
                // clears no stall. Drop silently: the claude-authored strings and the nonce are never logged.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    questionBatchProjection.apply(envelope)
                }
            }
            TYPE_ATTACHMENT_OFFERED ->
                // A file claude produced (#898): see [AttachmentOfferProjection.apply]. Deliberately NOT behind
                // the `interactive` gate: the daemon delivers it to every attached client, outside the
                // interactive family, like the upload leg's `attachment_stored`. The daemon routes nothing, so
                // the projection filters on the payload's conversation_id. A first offer of an id is also a
                // thread row (#983); no stall touched.
                attachmentOfferProjection.apply(envelope)
            TYPE_BACKGROUND_TASK_STARTED,
            TYPE_BACKGROUND_TASK_UPDATED,
            TYPE_BACKGROUND_TASK_ROSTER,
            TYPE_BACKGROUND_TASK_PROGRESS,
            -> {
                // Background work claude left running past its turn (#677, progress #1042): see
                // [BackgroundTaskProjection.apply].
                // The panel remains replacing state; scalar frames also retain invisible lifecycle positions.
                // No stall is cleared and command lines/summaries are never logged.
                if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
                    backgroundTaskProjection.apply(envelope)
                    threadProjection.applyBackgroundTaskLifecycle(envelope)
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
                TYPE_TURN_STATE -> {
                    // A coerced routing primitive cannot provide a trustworthy error-clearing edge.
                    val payload = envelope.payload as? JsonObject
                    if ((payload?.get("conversation_id") as? JsonPrimitive)?.isString == true &&
                        (payload["state"] as? JsonPrimitive)?.isString == true
                    ) {
                        MobileJson.decodeFromJsonElement<TurnStatePayloadDto>(payload).toEvent()
                    } else {
                        null
                    }
                }
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
     * Decode one v2 `session_transition` envelope (#336) to its routing [conversationId] and the mapped
     * [ThreadItem.SessionBoundary], or **null** when it cannot be folded. Decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson] and maps via `toBoundary()`. The whole
     * body is one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed payload
     * — a missing/wrong-typed required field or an unparseable `occurred_at` — yields `null`, dropping the
     * one envelope while the lone inbound collector survives (AC #5). An **unrecognized `reason`** is a
     * distinct path: `toBoundary()` returns `null` (no throw), so the one envelope drops the same way
     * (AC #3). Mirrors the [StallProjection] / [QueueProjection] decoders' drop idiom — **nothing here logs the payload**
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
     * Decode one v2 modal envelope (#437) to its typed [ModalEvent], or **null** when it cannot be
     * surfaced. Selects the DTO by [Envelope.type], decodes the untrusted [Envelope.payload] through the
     * single configured [MobileJson], and maps via `toEvent()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed / partially-decodable payload (missing or wrong-typed
     * required field, AC #3) yields `null`, dropping the one envelope while the lone inbound collector
     * survives. Both mappers are **total** — `class`/`source`/`outcome` are carried verbatim, so there is
     * no "unrecognized value" drop (AC #3). Mirrors the [StallProjection] decoder's and [decodeLiveSessionEvent]'s drop idiom
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

    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
        flow {
            // Request on every subscription: redundant requests are absorbed by StateFlow conflation,
            // and re-subscribing (e.g. on lifecycle resume) naturally re-issues. A pre-Open send returns
            // false and is dropped — the coordinator (#302) wires this against an Open pump.
            pump.send(listConversationsRequest())
            emitAll(conversationListProjection.observe(filter))
        }

    private fun listConversationsRequest(): Envelope =
        Envelope(
            id = relayRequests.nextRequestId(),
            type = TYPE_LIST_CONVERSATIONS,
            ts = Clock.System.now().toString(),
            payload = JsonObject(emptyMap()),
        )

    /** Recently-used workspace folders (#565), failing closed to empty; see [WorkspaceCommands.recentWorkspaces]. */
    override fun recentWorkspaces(): Flow<List<String>> = workspaceCommands.recentWorkspaces()

    /**
     * One backward step of [conversationId]'s history walk over v2 `request_history` (#623, server
     * pyrycode#2113/#2116). The [rename] shape — encode → [RelayRequests.sendAndAwaitReply] → typed-decode — and,
     * since #645, **one** state fold: the decoded page goes through [ThreadProjection.mergeHistoryPage] into
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
     * **retryable** code (`history.unavailable`) from the three permanent ones. [RelayRequests.mapError] needs no
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
                id = relayRequests.nextRequestId(),
                type = TYPE_REQUEST_HISTORY,
                ts = Clock.System.now().toString(),
                payload =
                    MobileJson.encodeToJsonElement(
                        RequestHistoryPayloadDto(conversationId = conversationId, cursor = cursor, limit = limit),
                    ),
            )
        // Throws on a server `error` / not-Open session before the decode below. The reply is the
        // {entries,cursor,at_start} page; a malformed one throws here and mutates nothing.
        val reply = relayRequests.sendAndAwaitReply(request)
        val page = MobileJson.decodeFromJsonElement<HistoryPagePayloadDto>(reply).toHistoryPage()
        threadProjection.mergeHistoryPage(conversationId, page, CAPABILITY_INTERACTIVE in negotiatedCapabilities())
        return page
    }

    /** The run configuration of a conversation's session (#590); see [SessionSettingsCommands.observeSessionSettings]. */
    override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
        sessionSettingsCommands.observeSessionSettings(conversationId)

    /** Invalidate a conversation's settings reading (#590); see [SessionSettingsCommands.refreshSessionSettings]. */
    override fun refreshSessionSettings(conversationId: String) = sessionSettingsCommands.refreshSessionSettings(conversationId)

    /**
     * Send one `request_context_usage` naming [conversationId] (#1410), the `askForModelMenu` posture in
     * [ModelMenuProjection]: an empty id and a connection without `interactive` send nothing, and a send the
     * transport refuses or throws on is dropped, never retried. No waiter is registered. The answer is a
     * `context_usage` the [TYPE_CONTEXT_USAGE] arm applies by its own `conversation_id`, and a refusal is an
     * `error` whose `in_reply_to` matches nothing, so it leaves the reading as it was. Never logs: the id is a
     * cross-conversation correlation key.
     */
    override fun requestContextUsage(conversationId: String) {
        if (conversationId.isEmpty()) return
        if (CAPABILITY_INTERACTIVE !in negotiatedCapabilities()) return
        val request =
            Envelope(
                id = relayRequests.nextRequestId(),
                type = TYPE_REQUEST_CONTEXT_USAGE,
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(RequestContextUsagePayloadDto(conversationId = conversationId)),
            )
        try {
            pump.send(request)
        } catch (e: Exception) {
            // Absorbed: the next open or reconnect asks again.
        }
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
            id = relayRequests.nextRequestId(),
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
        observeThreadSnapshot(conversationId).map { it.rows }.distinctUntilChanged()

    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
        flow {
            pump.send(backfillSinceRequest(conversationId))
            emitAll(threadProjection.observeSnapshot(conversationId))
        }

    override fun observeThreadRowCounts(): Flow<Map<String, Int>> = threadProjection.observeRowCounts()

    override fun observeLastMessage(conversationId: String): Flow<Message?> = conversationListProjection.observeLastMessage(conversationId)

    override fun observeStall(conversationId: String): Flow<Boolean> = stallProjection.observe(conversationId)

    override fun observeSessionError(conversationId: String): Flow<String?> = sessionErrorProjection.observe(conversationId)

    override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = queueProjection.observe(conversationId)

    override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = apiRetryProjection.observe(conversationId)

    override fun observeCompacting(conversationId: String): Flow<Boolean> = compactingProjection.observe(conversationId)

    override fun observeTurnPhase(conversationId: String): Flow<LiveSessionEvent.TurnState.Phase> =
        turnPhaseProjection.observe(conversationId)

    override fun observeResetting(conversationId: String): Flow<ResetStatus?> = resettingProjection.observe(conversationId)

    override fun observeBusyConversations(): Flow<Set<String>> =
        combine(
            stallProjection.observeIds(),
            apiRetryProjection.observeIds(),
            compactingProjection.observeIds(),
            resettingProjection.observeIds(),
        ) { stalled, retrying, compacting, resetting -> stalled + retrying + compacting + resetting }.distinctUntilChanged()

    override fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = announcedModelProjection.observe(conversationId)

    override fun observeLiveRefusalEvents(conversationId: String): Flow<LiveRefusalEvent> =
        liveRefusalEvents.filter { it.first == conversationId }.map { it.second }

    override fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = sessionFactsProjection.observe(conversationId)

    override fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = contextUsageProjection.observe(conversationId)

    override fun observeMcpStatus(conversationId: String): Flow<McpStatus> = mcpStatusProjection.observe(conversationId)

    override fun requestMcpStatus(conversationId: String) = mcpStatusProjection.requestStatus(conversationId)

    override fun reconnectMcpServer(
        conversationId: String,
        serverName: String,
    ) = mcpStatusProjection.reconnect(conversationId, serverName)

    override fun toggleMcpServer(
        conversationId: String,
        serverName: String,
        enabled: Boolean,
    ) = mcpStatusProjection.toggle(conversationId, serverName, enabled)

    override fun endMcpReconnectWait(conversationId: String) = mcpStatusProjection.endReconnectWait(conversationId)

    override fun endMcpToggleWait(conversationId: String) = mcpStatusProjection.endToggleWait(conversationId)

    override fun observeAttachmentOffers(conversationId: String): Flow<List<AttachmentOffer>> =
        attachmentOfferProjection.observe(conversationId)

    override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = usageLimitProjection.observe(conversationId)

    override fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> =
        thinkingProgressProjection.observe(conversationId)

    override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> = modelMenuProjection.observe(conversationId)

    override fun observeSlashCommandMenu(conversationId: String): Flow<SlashCommandMenu?> =
        slashCommandMenuProjection.observe(conversationId)

    /** Create an unpromoted discussion (#347); see [ConversationCommands.createDiscussion]. */
    override suspend fun createDiscussion(workspace: String?): Conversation = conversationCommands.createDiscussion(workspace)

    /** Create a named, promoted channel (#956); see [ConversationCommands.createChannel]. */
    override suspend fun createChannel(
        name: String,
        workspace: String?,
    ): Conversation = conversationCommands.createChannel(name, workspace)

    /** Promote a conversation into a named channel (#348); see [ConversationCommands.promote]. */
    override suspend fun promote(
        conversationId: String,
        name: String,
        workspace: String?,
    ): Conversation = conversationCommands.promote(conversationId, name, workspace)

    /** Post [text] to [conversationId] over v2 `send_message` (#346); see [MessageCommands.sendMessage]. */
    override suspend fun sendMessage(
        conversationId: String,
        text: String,
    ): Message {
        sessionErrorProjection.clear(conversationId)
        return messageCommands.sendMessage(conversationId, text)
    }

    /** The same send naming [attachments] (#830, #983); see [MessageCommands.sendMessage]. */
    override suspend fun sendMessage(
        conversationId: String,
        text: String,
        attachments: List<MessageAttachment>,
    ): Message {
        sessionErrorProjection.clear(conversationId)
        return messageCommands.sendMessage(conversationId, text, attachments)
    }

    /** Upload [bytes] as `attachment_chunk` frames (#829); see [MessageCommands.uploadAttachment]. */
    override suspend fun uploadAttachment(
        conversationId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
    ): AttachmentUploadResult = messageCommands.uploadAttachment(conversationId, bytes, filename, mimeType, onProgress)

    /** Fetch one stored file over `request_attachment` (#899); see [AttachmentRetrievals.fetch]. */
    override suspend fun fetchAttachment(
        conversationId: String,
        attachmentId: String,
    ): AttachmentFetchResult = attachmentRetrievals.fetch(conversationId, attachmentId)

    /** Read one workspace file live over `read_workspace_file` (#1049); see [AttachmentRetrievals.readWorkspaceFile]. */
    override suspend fun readWorkspaceFile(
        conversationId: String,
        path: String,
    ): AttachmentFetchResult = attachmentRetrievals.readWorkspaceFile(conversationId, path)

    /** Request the rendered claude screen (#375); see [MessageCommands.requestScreenSnapshot]. */
    override suspend fun requestScreenSnapshot(conversationId: String): String = messageCommands.requestScreenSnapshot(conversationId)

    /** Deliver a queued message now through this host connection. */
    override suspend fun sendQueuedNow(
        conversationId: String,
        queuedMessageId: Long,
    ): Unit = messageCommands.sendQueuedNow(conversationId, queuedMessageId)

    /** Drop a queued message over fire-and-forget `dequeue_message` (#466); see [MessageCommands.dropQueuedMessage]. */
    override suspend fun dropQueuedMessage(
        conversationId: String,
        queuedMessageId: Long,
    ): Unit = messageCommands.dropQueuedMessage(conversationId, queuedMessageId)

    /** Register the phone's FCM push token (#359); see [ConversationCommands.registerPushToken]. */
    suspend fun registerPushToken(token: String): Unit = conversationCommands.registerPushToken(token)

    /** Answer the surfaced modal (#438); see [ConversationCommands.answerModal]. */
    suspend fun answerModal(
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean = false,
    ): Unit = conversationCommands.answerModal(modalId, optionId, alwaysAllow)

    /** Cancel the surfaced modal (#438); see [ConversationCommands.cancelModal]. */
    suspend fun cancelModal(modalId: String): Unit = conversationCommands.cancelModal(modalId)

    /** Current connection's detection capability, disabled permanently on collector termination. */
    val supportsBackgroundTaskStop: Boolean get() = backgroundTaskStops.supported

    /** Returns after send, without claiming task completion. No task or turn projection is changed. */
    suspend fun stopBackgroundTask(
        conversationId: String,
        taskId: String,
    ): Result<Unit> = backgroundTaskStops.stop(conversationId, taskId)

    /** Subscribe before invoking a stop; emits only originating opaque task keys for this conversation. */
    fun observeBackgroundTaskStopRefusals(conversationId: String): Flow<String> = backgroundTaskStops.observeRefusals(conversationId)

    internal fun endBackgroundTaskStops() = backgroundTaskStops.end()

    /** Stop the named conversation over fire-and-forget `interrupt`; see [ConversationCommands.interrupt]. */
    suspend fun interrupt(conversationId: String): Unit = conversationCommands.interrupt(conversationId)

    /**
     * Answer the held clarification batch [questionBatchId] (#825) over v2 `question_answer`
     * (protocol-mobile.md, Question v2). Fire-and-forget like [interrupt]: the daemon replies with
     * neither `ack` nor `error` and silently drops an answer it cannot resolve, so everything checkable
     * is checked here first and nothing is awaited. The only resolution signal is the inbound
     * `question_dismissed` — this method never clears the batch from [questionBatches].
     *
     * Entries go out in batch order; [QuestionAnswer.values] are sent verbatim and never compared with
     * the offered labels. Throws [IllegalStateException] when this connection holds no such batch (never
     * shown, dismissed, or dropped by the reconnect that built this repository) or the pump refuses the
     * frame, and [IllegalArgumentException] when [answers] do not cover every question exactly once.
     * Messages are static: the nonce and the operator's values never reach an exception or a log.
     */
    suspend fun answerQuestionBatch(
        questionBatchId: String,
        answers: List<QuestionAnswer>,
    ): Unit = questionBatchProjection.answer(questionBatchId, answers)

    /**
     * Decline the held clarification batch [questionBatchId] (#825) over v2 `question_refused`: the
     * batch id and a token, nothing else. Same fire-and-forget, no-clear and failure posture as
     * [answerQuestionBatch].
     */
    suspend fun refuseQuestionBatch(questionBatchId: String): Unit = questionBatchProjection.refuse(questionBatchId)

    /**
     * `true`: every mutation method below now has a v2 wire message on both ends — mobile
     * `rename` / `archive` / `unarchive` / `delete` / `startNewSession` / `changeWorkspace` /
     * `setSessionSettings` / `createWorkspaceFolder` (#530-#536, #549, #560, #564, #565) and the
     * matching daemon handlers (pyrycode #820-#826). The `false` in #507 predated those wires; the
     * throwing stubs it referred to are gone, so the mutation affordances are now reachable in relay mode.
     */
    override val mutationsSupported: Boolean = true

    /** Archive over `archive_conversation` (#549); see [ConversationCommands.archive]. */
    override suspend fun archive(conversationId: String): Unit = conversationCommands.archive(conversationId)

    /** Restore over `unarchive_conversation` (#549); see [ConversationCommands.unarchive]. */
    override suspend fun unarchive(conversationId: String): Unit = conversationCommands.unarchive(conversationId)

    /** Set or clear the mute flag over `set_conversation_muted` (#1000); see [ConversationCommands.setMuted]. */
    override suspend fun setMuted(
        conversationId: String,
        muted: Boolean,
    ): Unit = conversationCommands.setMuted(conversationId, muted)

    /** Permanently delete a conversation (#532); see [ConversationCommands.delete]. */
    override suspend fun delete(conversationId: String): Unit = conversationCommands.delete(conversationId)

    /** Rename a conversation (#530); see [ConversationCommands.rename]. */
    override suspend fun rename(
        conversationId: String,
        name: String,
    ): Conversation = conversationCommands.rename(conversationId, name)

    /** Apply a run-configuration change over `set_session_settings` (#543); see [SessionSettingsCommands.setSessionSettings]. */
    override suspend fun setSessionSettings(
        sessionId: String,
        model: String?,
        effort: String?,
        yolo: Boolean?,
        permissionMode: String?,
    ): Unit = sessionSettingsCommands.setSessionSettings(sessionId, model, effort, yolo, permissionMode)

    /** Read/write this connection's host settings, with no conversation state mutation. */
    override suspend fun requestHostSystemPrompt(): Result<HostSystemPromptReading> = sessionSettingsCommands.requestHostSystemPrompt()

    override suspend fun setHostSystemPrompt(systemPrompt: String): Result<HostSystemPromptReading> =
        sessionSettingsCommands.setHostSystemPrompt(systemPrompt)

    /** Read a conversation's stored system prompt (#823); see [SessionSettingsCommands.requestSystemPrompt]. */
    override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading =
        sessionSettingsCommands.requestSystemPrompt(conversationId)

    /** Set or clear a conversation's system prompt (#823); see [SessionSettingsCommands.setSystemPrompt]. */
    override suspend fun setSystemPrompt(
        conversationId: String,
        systemPrompt: String?,
    ): Unit = sessionSettingsCommands.setSystemPrompt(conversationId, systemPrompt)

    /** Send fire-and-forget `new_session` for a conversation (#539); see [ConversationCommands.startNewSession]. */
    override suspend fun startNewSession(
        conversationId: String,
        workspace: String?,
    ): Session = conversationCommands.startNewSession(conversationId, workspace)

    /** Change a conversation's workspace over `change_workspace` (#560); see [WorkspaceCommands.changeWorkspace]. */
    override suspend fun changeWorkspace(
        conversationId: String,
        workspace: String,
    ): Session = workspaceCommands.changeWorkspace(conversationId, workspace)

    /** Create a workspace folder under the fixed client root (#564); see [WorkspaceCommands.createWorkspaceFolder]. */
    override suspend fun createWorkspaceFolder(name: String): String = workspaceCommands.createWorkspaceFolder(name)

    /** Set or clear a workspace's label over `rename_workspace` (#663); see [WorkspaceCommands.renameWorkspace]. */
    override suspend fun renameWorkspace(
        path: String,
        label: String?,
    ): Unit = workspaceCommands.renameWorkspace(path, label)

    /** Archive every active row at a workspace path (#663); see [WorkspaceCommands.archiveWorkspace]. */
    override suspend fun archiveWorkspace(path: String): Unit = workspaceCommands.archiveWorkspace(path)

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

        /** Request: set or clear a conversation's mute flag (#1000, pyrycode#2572). Reply is `conversation_updated`, also pushed uncorrelated. */
        const val TYPE_SET_CONVERSATION_MUTED = "set_conversation_muted"

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

        /** Request: the system prompt one conversation stores (#823). Reply is [TYPE_SYSTEM_PROMPT]. */
        const val TYPE_REQUEST_SYSTEM_PROMPT = "request_system_prompt"

        /**
         * Correlated reply for [TYPE_REQUEST_SYSTEM_PROMPT] (#823): the stored prompt and the running
         * session's verdict against it. Carries no conversation id, and is **never an error frame**.
         */
        const val TYPE_SYSTEM_PROMPT = "system_prompt"

        /** Correlated read and durable-write reply; never a conversation push. */
        const val TYPE_HOST_SYSTEM_PROMPT = "host_system_prompt"

        /** Request: set or clear a conversation's system prompt (#823). Acked by [TYPE_CONVERSATION_UPDATED]. */
        const val TYPE_SET_SYSTEM_PROMPT = "set_system_prompt"

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

        /** Request: set or clear one workspace's label (#663). Reply is a correlated [TYPE_WORKSPACE_UPDATED]. */
        const val TYPE_RENAME_WORKSPACE = "rename_workspace"

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

        /** Thread-store event: claude refused a tool call `{…, tool_use_id, tool_name, message, …}` (#811). */
        const val TYPE_TOOL_DENIED = "tool_denied"

        /** Thread-store event: claude's elapsed reading for an open call `{…, tool_use_id, elapsed_seconds}` (#812). */
        const val TYPE_TOOL_PROGRESS = "tool_progress"

        /** Structured-stream event: end of a turn `{…, turn_id, stop_reason}` (#385, #607). */
        const val TYPE_TURN_END = "turn_end"

        /**
         * Capability-gated control event: a remote-head stall `{conversation_id}` (#395, #638/#639) —
         * onset-only, no clearing edge on the wire (recovery is inferred from forward progress).
         */
        const val TYPE_STALL = "stall"

        /** Unsolicited conversation-scoped failure; carries no replay event id or retry instruction. */
        const val TYPE_SESSION_ERROR = "session_error"

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
         * on every (re)connect (with none); the payload is identical on both paths. Since #792 it also
         * arrives as the correlated answer to a [TYPE_REQUEST_MODEL_LIST] — **this same frame
         * unchanged**, carrying an `in_reply_to` and, like the reconcile burst's, no `event_id`.
         */
        const val TYPE_MODEL_LIST = "model_list"

        /**
         * Capability-gated inventory (#882): the slash commands claude will accept for one conversation, a
         * full snapshot that replaces that conversation's menu. Arrives on the live interactive lane (with an
         * `event_id`) and as a per-conversation snapshot on every (re)connect (with none). Declares no
         * inbound verb.
         */
        const val TYPE_SLASH_COMMAND_LIST = "slash_command_list"

        /**
         * Request: one conversation's model menu, on demand (#792, daemon pyrycode#2125). The third and
         * last way a client gets a menu and the only one it can trigger itself — it covers the
         * conversation created *after* the phone connected, which crosses neither unsolicited delivery
         * edge. Payload is the single `conversation_id` key; the reply is a [TYPE_MODEL_LIST] correlated
         * by `in_reply_to`, or an `error` carrying [ERROR_CONVERSATION_NOT_FOUND] or
         * [ERROR_MODEL_LIST_UNAVAILABLE]. Interactive-gated: a conn without it is answered with nothing
         * at all, so the sender does not send one.
         */
        const val TYPE_REQUEST_MODEL_LIST = "request_model_list"

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
         * `false` finished). It drives the status indicator, and its falling edge also draws a thread
         * compaction divider, failed or unreported (#1358). The upstream detector streams no progress, so
         * the payload carries no counter, percent, or ETA. Unlike [TYPE_STALL] this **has** a clearing edge on the
         * wire, so the state is cleared explicitly rather than inferred from forward progress.
         */
        const val TYPE_COMPACTING = "compacting"

        /**
         * Capability-gated status event: what claude said about its usage-limit window
         * `{conversation_id, status, limit_type, resets_at, utilization, truncated_fields}` (#802,
         * pyrycode#1405/#1410). **A frame is not proof the turn was blocked** — the one measured
         * non-benign status was seen on an account whose turns all ran normally — so this reports what
         * claude said, not that the user is rate limited. `status` carries the clearing edge when it
         * holds the benign value, and that clear names a *different* `limit_type` than the warning it
         * clears, so it pairs by `conversation_id` and never by limit. Unlike [TYPE_COMPACTING]'s
         * clearing edge this one is **session-scoped**, so a warning raised before a `/clear` or a
         * session eviction is never followed by one — hence the read-time expiry on
         * [observeUsageLimit]. Conversation-scoped: no `turn_id`, and receiving one neither opens nor
         * closes a turn.
         */
        const val TYPE_RATE_LIMITED = "rate_limited"

        /**
         * Capability-gated status event: how far a conversation's current reasoning has got
         * `{conversation_id, estimated_tokens, estimated_tokens_delta}` (#801, pyrycode#1386) — claude's
         * only mid-turn proof of life on the stream-json surface. Unlike [TYPE_COMPACTING] and
         * [TYPE_API_RETRY] it carries **no edge at all**: it is a reading, with no `active` flag and no
         * falling edge, so its clears live on the [TYPE_SESSION_TRANSITION] and `turn_end` arms rather
         * than on this one. Rate-bounded and **not monotonic** (it restarts at every inference-request
         * boundary), and **absence proves nothing** — the PTY surface emits none at all.
         */
        const val TYPE_THINKING_PROGRESS = "thinking_progress"

        /**
         * Capability-gated status event: where a conversation's Reset is `{conversation_id, active, phase,
         * handoff}` (#871, pyrycode#2478) — pyrycode `docs/protocol-mobile.md` § `resetting`. Unlike
         * [TYPE_COMPACTING]'s strict edge pair, one reset sends **two** rising edges (`wrapping_up`, then
         * `restarting`) before one falling edge, so a second rising edge replaces the reading rather than
         * starting another reset. `phase` and `handoff` are closed sets while `active` is true; nothing on
         * the frame is claude-authored. Opens, closes and alters no turn.
         */
        const val TYPE_RESETTING = "resetting"

        /**
         * Capability-gated status event: the model claude announced for a turn `{conversation_id, model,
         * truncated}` (#890, pyrycode#1638) — pyrycode `docs/protocol-mobile.md` § `model_announced`. Sent once
         * per turn and not deduplicated, so the latest one wins. Not the saved per-session override. `model`
         * is claude-authored. Opens, closes and alters no turn.
         */
        const val TYPE_MODEL_ANNOUNCED = "model_announced"

        /**
         * Capability-gated status event: claude's own build and claimed permission posture
         * `{conversation_id, claude_code_version, permission_mode, truncated_fields}` (#890, pyrycode#2254) —
         * pyrycode `docs/protocol-mobile.md` § `session_facts`. The sibling of [TYPE_MODEL_ANNOUNCED] from the
         * same `system/init` line; both strings are claude-authored. Opens, closes and alters no turn.
         */
        const val TYPE_SESSION_FACTS = "session_facts"

        /**
         * Capability-gated status event: how full a conversation's context window is, `{conversation_id, model,
         * total_tokens, max_tokens, percentage, <inventories>, as_of?}` (#945, pyrycode#2371) — pyrycode
         * `docs/protocol-mobile.md` § `context_usage`. Pushed after every completed turn, and also the correlated
         * answer to [TYPE_REQUEST_CONTEXT_USAGE]. Only the scalars are decoded. Opens, closes and alters no turn.
         */
        const val TYPE_CONTEXT_USAGE = "context_usage"

        /**
         * Capability-gated status event: one conversation's MCP server report, `{conversation_id, servers,
         * dropped_servers}` (#1343, pyrycode#2375) — pyrycode `docs/protocol-mobile.md` § `mcp_status`. Pushed once
         * per eligible child, and also the correlated answer to [TYPE_MCP_STATUS_REQUEST], and to an accepted
         * [TYPE_MCP_RECONNECT] or [TYPE_MCP_TOGGLE]. Opens, closes and alters no turn.
         */
        const val TYPE_MCP_STATUS = "mcp_status"

        /**
         * Phone → daemon: ask for one conversation's current [TYPE_MCP_STATUS] (#1343, pyrycode#2381). Refused with
         * `protocol.malformed`, [ERROR_CONVERSATION_NOT_FOUND] or [ERROR_MCP_STATUS_UNAVAILABLE]. Interactive-gated.
         */
        const val TYPE_MCP_STATUS_REQUEST = "mcp_status_request"

        /**
         * Phone → daemon: reconnect one MCP server on the conversation's live child (#1343, pyrycode#2420). Accepted
         * answers with a correlated [TYPE_MCP_STATUS]; every refusal is [ERROR_MCP_ACTUATION_REFUSED].
         */
        const val TYPE_MCP_RECONNECT = "mcp_reconnect"

        /** Phone → daemon: turn one MCP server on or off (#1343, pyrycode#2420). Answered like [TYPE_MCP_RECONNECT]. */
        const val TYPE_MCP_TOGGLE = "mcp_toggle"

        /**
         * Phone → daemon: ask for a fresh [TYPE_CONTEXT_USAGE] reading of one conversation (#945, pyrycode#2431).
         * Payload is the single `conversation_id` key; the reply is a [TYPE_CONTEXT_USAGE] correlated by
         * `in_reply_to`, or an `error` carrying [ERROR_CONVERSATION_NOT_FOUND] or [ERROR_CONTEXT_USAGE_UNAVAILABLE].
         * Interactive-gated: a conn without it is answered with nothing at all. Sent by [requestContextUsage] when a
         * thread opens and when its host returns (#1410); since pyrycode#2563 a mid-turn ask no longer holds up the
         * connection's later frames.
         */
        const val TYPE_REQUEST_CONTEXT_USAGE = "request_context_usage"

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
         * Capability-gated thread event: text claude printed about the session
         * `{conversation_id, level, text, truncated, stops_turn}` (#873, pyrycode#2256) — folds a
         * [ThreadItem.Banner] into the conversation thread (keyed by `conversation_id`) in arrival order.
         * Conversation-scoped with no `turn_id`; `level` is an open set; `text` is claude's, capped at
         * 4 KiB daemon-side and not sanitized; `stops_turn` is a report nothing here acts on.
         */
        const val TYPE_BANNER = "banner"

        /**
         * Capability-gated thread event: a finished compaction `{conversation_id, trigger, pre_tokens,
         * post_tokens}` (#874, pyrycode#2237) — folds a [ThreadItem.CompactionBoundary] divider into the
         * conversation thread in arrival order. Conversation-scoped with no `turn_id`, may arrive with no
         * [TYPE_COMPACTING] edge before it, and drives no status indicator; `trigger` is an open set and each
         * count is an integer or `null`, neither clamped nor ordered.
         */
        const val TYPE_COMPACTION_BOUNDARY = "compaction_boundary"

        /**
         * Capability-gated thread event: claude refused a turn on one model and retried it on another
         * `{conversation_id, original_model, fallback_model, scope, refusal_category, banner, truncated_fields,
         * dropped_fields}` (#875, pyrycode#2265) — folds a [ThreadItem.ModelRefusal] into the conversation
         * thread in arrival order. Conversation-scoped with no `turn_id`; every value but the id is claude's,
         * bounded and unsanitized; `scope` and `refusal_category` are open and drive nothing.
         */
        const val TYPE_MODEL_REFUSAL_FALLBACK = "model_refusal_fallback"

        /**
         * Capability-gated thread event: the no-retry sibling of [TYPE_MODEL_REFUSAL_FALLBACK]
         * `{conversation_id, original_model, refusal_category, banner, truncated_fields, dropped_fields}`
         * (#875, pyrycode#2266), told apart by this envelope type alone.
         */
        const val TYPE_MODEL_REFUSAL_NO_FALLBACK = "model_refusal_no_fallback"

        /**
         * Outbound queue control: the phone's request to drop a not-yet-drained message
         * `{conversation_id, queued_msg_id}` from a conversation's backlog (#466, pyrycode#723, ADR
         * 025) — the outbound peer of [TYPE_QUEUE_STATE]. The daemon never replies (#859); the backlog
         * updates via the next `queue_state`, which is also the drop's only confirmation.
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
         * Capability-gated clarification batch `{conversation_id, question_batch_id, questions}` (#822,
         * pyrycode § Question (v2)) — claude's whole `AskUserQuestion` call in one frame.
         */
        const val TYPE_QUESTION_SHOWN = "question_shown"

        /** Capability-gated retirement of a question batch `{question_batch_id, outcome, source}` (#822). */
        const val TYPE_QUESTION_DISMISSED = "question_dismissed"

        /**
         * Capability-gated background task claude started past its turn
         * `{conversation_id, task_id, tool_call_id, description, task_type, truncated_fields}` (#677).
         */
        const val TYPE_BACKGROUND_TASK_STARTED = "background_task_started"

        /**
         * Capability-gated change to a background task
         * `{conversation_id, task_id, patch, status, summary, truncated_fields}` (#677); `status != ""` ends it.
         */
        const val TYPE_BACKGROUND_TASK_UPDATED = "background_task_updated"

        /** Capability-gated snapshot of a conversation's background tasks `{conversation_id, tasks, dropped_tasks}` (#677). */
        const val TYPE_BACKGROUND_TASK_ROSTER = "background_task_roster"

        /**
         * Capability-gated current activity of a running background task `{conversation_id, task_id, description,
         * subagent_type, last_tool_name, total_tokens, tool_uses, duration_ms, truncated_fields}` (#1042).
         */
        const val TYPE_BACKGROUND_TASK_PROGRESS = "background_task_progress"

        /** Outbound answer to a held question batch `{question_batch_id, answer_token, answers}` (#825); no reply. */
        const val TYPE_QUESTION_ANSWER = "question_answer"

        /** Outbound refusal of a held question batch `{question_batch_id, answer_token}` (#825); no reply. */
        const val TYPE_QUESTION_REFUSED = "question_refused"

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
        const val TYPE_ATTACHMENT_CHUNK = "attachment_chunk"

        /**
         * Outbound push naming a file claude produced (#898): `{conversation_id, attachment_id, filename}`, no
         * bytes, delivered to every attached client and live-only. See [AttachmentOfferProjection].
         */
        const val TYPE_ATTACHMENT_OFFERED = "attachment_offered"

        /** Server `error.code` for an unknown conversation → [IllegalArgumentException] (#346, AC #3). */
        const val ERROR_CONVERSATION_NOT_FOUND = "conversation.not_found"

        /**
         * Server `error.code` refusing a [TYPE_REQUEST_MODEL_LIST] because the daemon **does** host the
         * named conversation but has no vocabulary to answer with yet (#792, daemon pyrycode#2125) —
         * the one **retryable** refusal on that verb, and the only one [ModelMenuProjection]'s `onModelListRefusal` releases a
         * one-shot for. Its sibling `conversation.not_found` is terminal for that id, which is why the
         * two are branched on rather than merged.
         */
        const val ERROR_MODEL_LIST_UNAVAILABLE = "model_list.unavailable"

        /**
         * Server `error.code` refusing a [TYPE_REQUEST_CONTEXT_USAGE] because the daemon hosts the conversation but
         * has neither a fresh nor a remembered reading (#945, pyrycode#2431/#2461). Retryable after a backoff, but
         * the phone does not retry [requestContextUsage] (#1410): the reading stays as it was until the next frame,
         * so nothing branches on this code and [ContextUsageProjection] handles no refusal at all.
         */
        const val ERROR_CONTEXT_USAGE_UNAVAILABLE = "context_usage.unavailable"

        /**
         * Server `error.code` refusing a [TYPE_MCP_STATUS_REQUEST] for a hosted conversation with no live eligible
         * child or no usable child reply (#1343). The only status-ask refusal that marks the reading unavailable.
         */
        const val ERROR_MCP_STATUS_UNAVAILABLE = "mcp_status.unavailable"

        /**
         * The single merged `error.code` for every [TYPE_MCP_RECONNECT] or [TYPE_MCP_TOGGLE] refusal (#1343). The
         * phone settles any correlated refusal of those verbs the same way, so nothing branches on this code; it
         * documents the contract.
         */
        const val ERROR_MCP_ACTUATION_REFUSED = "mcp_actuation.refused"

        /** Client-side synthetic code for an undecodable `error` payload (#346 fallback, never hangs). */
        const val ERROR_MALFORMED_REPLY = "error.malformed_reply"

        /**
         * Static, payload-free message for the [IllegalStateException] [RelayRequests.failAllPending] fails every
         * in-flight request with on teardown (#488). Carries no request content, `modalId`, or ids —
         * the never-log contract holds by construction.
         */
        const val PENDING_REQUEST_TORN_DOWN = "connection torn down before reply"

        /** Static refusals for the system-prompt verbs (#823): never the value, its length or the id. */
        const val SYSTEM_PROMPT_READ_NOT_INTERACTIVE = "request_system_prompt not sent: interactive not negotiated"
        const val SYSTEM_PROMPT_TOO_LONG = "set_system_prompt not sent: value exceeds the byte limit"

        /** Static failure for a `rename_workspace` reply that is malformed or does not confirm the path (#663). */
        const val WORKSPACE_REPLY_MALFORMED = "workspace_updated reply did not confirm the workspace"

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
