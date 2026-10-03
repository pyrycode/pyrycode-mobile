package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.BannerPayloadDto
import de.pyryco.mobile.data.network.CompactingPayloadDto
import de.pyryco.mobile.data.network.CompactionBoundaryPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModelRefusalFallbackPayloadDto
import de.pyryco.mobile.data.network.ModelRefusalNoFallbackPayloadDto
import de.pyryco.mobile.data.network.ToolDeniedPayloadDto
import de.pyryco.mobile.data.network.ToolProgressPayloadDto
import de.pyryco.mobile.data.network.UnrecognizedMessagePayloadDto
import de.pyryco.mobile.data.network.failed
import de.pyryco.mobile.data.network.toDenial
import de.pyryco.mobile.data.network.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * The thread of every conversation on one connection (#912): the thread store, the ledger of message ids
 * this device minted, the drops awaiting confirmation, and every write that folds a row into the thread,
 * split out of [RemoteConversationRepository] the way the status events were (#819). The repository keeps
 * the routing: its `onInbound` arms hand each thread frame here behind the negotiated `interactive` gate,
 * and its commands ([RemoteConversationRepository.sendMessage],
 * [RemoteConversationRepository.dropQueuedMessage], [RemoteConversationRepository.requestHistory]) record
 * into it. This class decodes only the frames whose sole effect is a thread row; a frame that also feeds
 * anything else is decoded by the repository and handed over already typed.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs, except the
 * queued, delivered and dropped states of this device's own sends to [trail] (#1564), keyed on the minted
 * ledger so a daemon frame naming another device's id records nothing.
 */
internal class ThreadProjection(
    private val trail: MessageTrail = MessageTrail(),
) {
    /**
     * `conversationId -> ordered thread rows` ([ThreadItem.MessageItem] + [ThreadItem.SessionBoundary])
     * for the conversation — backfilled history (`message_chunk`) plus live user `message`s and structured
     * turns, deduped by `message_id`, interleaved in wire/arrival order with `session_transition`
     * boundaries (#313, #336). Written by the repository's single inbound collector **and** by [RemoteConversationRepository.sendMessage]'s
     * confirmed insert (#346) — two writers, but every write goes through the atomic
     * [appendMessages] / [appendSessionBoundary] / [MutableStateFlow.update] fold, so concurrent updates
     * retry-merge correctly. [RemoteConversationRepository.observeMessages] fans out from it through [observe]. Message rows are order-preserving: first
     * insertion fixes a message's position; through [appendMessages] a repeat `message_id` updates it in
     * place (the dedup rule), while [appendLiveMessage] keeps the held row unchanged (#1351);
     * boundaries append in arrival order, skipping one the thread already holds ([holdsBoundary]). The thread is
     * complete-on-first-emission once backfill arrives and live rows append after.
     */
    private val threadByConversation = MutableStateFlow<Map<String, List<ThreadItem>>>(emptyMap())

    /**
     * `conversationId -> the turn ids whose turn_end this conversation has seen` on either lane (#1419).
     * A `turn_end` can reach the client before the rows it ends: the newest history page can hold only
     * the `turn_end`, or a live one can land before the page with the turn's deltas. Any row of an ended
     * turn that enters the thread later is settled against this set ([withSettledTurns]).
     *
     * Every writer records here **before** it updates the thread. A history merge runs on the caller's
     * coroutine while a live `turn_end` runs on the inbound collector, so the two can race, and a
     * finalize whose flip finds none of the turn's rows writes nothing: a [MutableStateFlow.update] to an
     * equal value never makes the merge's compare-and-set fail. So after its merge commits,
     * [mergeHistoryPage] runs [settleEndedTurns], which reads this set afresh. Either the finalize
     * recorded before that read, and the pass settles the rows, or its thread update starts after the
     * merge committed, and its own flip finds them. A live delta needs no such pass: its update always
     * changes the thread, so a racing merge either retries and sees the record or is seen by the delta.
     * Grow-only, since an ended turn never streams again. Connection-scoped and in-memory like
     * [mintedMessageIds], and dropped with the thread by [remove].
     */
    private val endedTurns = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /**
     * `conversationId -> where its compaction fold stands` (#1358): whether a compaction is open, and the
     * divider its falling edge drew that the next `compaction_boundary` fills in ([CompactionFold]). Written
     * only by the inbound collector, through [foldCompaction], and dropped by [remove]. Connection-scoped
     * like the thread: a fresh connection starts with no compaction open, as desktop does.
     */
    private val compactionFolds = MutableStateFlow<Map<String, CompactionFold>>(emptyMap())

    /**
     * `conversationId -> the message ids this device minted and echoed into the thread` (#781) — the
     * ledger that makes a queued item's [QueuedMessage.messageId] safe to act on. Written by
     * [RemoteConversationRepository.sendMessage] through [recordMinted] after its ack (beside the confirmed insert) and consumed when a drop settles
     * ([settleDrops], #859); both writes are atomic [MutableStateFlow.update]s, the two-writer posture
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
     * sibling projection (#351) — it holds one minted id per issued send, minus every consumed
     * drop, and dies with the connection. Recorded when the send is issued (#1355), so a refused send's id
     * stays too; no queued item will ever carry it, so it never removes a row.
     */
    private val mintedMessageIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /**
     * `conversationId -> (queued_msg_id -> echo message id)` for every drop this device sent and the
     * daemon has not yet confirmed (#859). The daemon never replies to `dequeue_message`; its only
     * confirmation is the next `queue_state` for the conversation lacking the item, which [settleDrops]
     * reads. Keyed on the `queued_msg_id` this device asked to drop, so an item that leaves the backlog
     * without a request from here (a drain, another device's drop) has no entry and keeps its echo.
     *
     * Two writers — [RemoteConversationRepository.dropQueuedMessage] and the inbound collector through [settleDrops] — both through
     * atomic [MutableStateFlow.update]s. Connection-scoped and in-memory like [mintedMessageIds]: a drop
     * whose confirmation never arrives before the connection closes leaves its echo, the safe side.
     */
    private val pendingDrops = MutableStateFlow<Map<String, Map<Long, String>>>(emptyMap())

    /**
     * `conversationId -> where this device's queued echoes stand` (#1558). A message sent while a turn runs
     * is drawn at tap time (#1355), but the daemon delivers it only after that turn ends, so [observe] reads
     * each [OwnEchoQueue.queued] echo below every other row, and its delivery moves it to the end of the
     * store once ([moveOwnEchoToEnd]) — on the drain's `queue_state` or the pushed `message`, whichever
     * arrives first.
     *
     * Written only by the inbound collector, through [settleQueuedEchoes] and [appendLiveMessage].
     * Connection-scoped and in-memory like [mintedMessageIds], and observed only through [observe].
     */
    private val ownEchoQueues = MutableStateFlow<Map<String, OwnEchoQueue>>(emptyMap())

    /**
     * Source of the client-owned [ThreadItem.UnrecognizedMessage.id] (#609). The `unrecognized_message`
     * wire frame carries neither a message id nor a `turn_id`, yet the thread's `LazyColumn` keys on
     * `"unrecognized:<id>"` — so a duplicate crashes the thread, and the id cannot be derived from the
     * payload or the arrival instant (two byte-identical frames stamped in the same instant would
     * collide, which is exactly the repeat case the fold must keep distinguishable).
     *
     * A per-projection monotonic counter is sufficient *structurally*, not incidentally: ids are unique
     * within this instance (hence within any one thread), the repository that owns this projection is
     * connection-scoped (#351) so each connection gets a fresh instance, and [StableConversationRepository]'s `flatMapLatest` drops
     * the previous connection's projection outright on reconnect — no reader ever observes rows from two
     * instances merged, so a restarted counter cannot collide with a prior connection's ids.
     *
     * The value carries **no wire data** — no `conversation_id`, no payload hash — keeping [QueuedMessage.id]'s
     * posture: a monotonic ordinal, not a secret, never compared against anything attacker-controlled
     * ([AtomicLong], deliberately not `SecureRandom`). [AtomicLong] mirrors the repository's request-id counter for consistency
     * rather than because concurrency demands it; the inbound demux is a single collector.
     *
     * **Ordinals may be skipped** — a frame that decodes structurally but is dropped by the unknown-`site`
     * mapper still consumed its `incrementAndGet()`. That is intentional: the invariant is *uniqueness*,
     * not density.
     */
    private val unrecognizedRowId = AtomicLong(0)

    /** Apply one `unrecognized_message` envelope (#609): decode it, then fold its row. A malformed one is dropped. */
    fun applyUnrecognizedMessage(envelope: Envelope) {
        decodeUnrecognizedMessage(envelope)?.let { (conversationId, row) -> appendUnrecognizedMessage(conversationId, row) }
    }

    /** Apply one `banner` envelope (#873): decode it, then fold its row. A malformed one is dropped. */
    fun applyBanner(envelope: Envelope) {
        decodeBanner(envelope)?.let { (conversationId, row) -> appendBanner(conversationId, row) }
    }

    /**
     * Apply one `compaction_boundary` envelope (#874): decode it, then fold its divider through
     * [withCompactionBoundary], which fills in the divider a falling edge left pending (#1358). A malformed
     * one is dropped.
     */
    fun applyCompactionBoundary(envelope: Envelope) {
        decodeCompactionBoundary(envelope)?.let { (conversationId, row) ->
            foldCompaction(conversationId) { rows, fold -> rows.withCompactionBoundary(fold, row) }
        }
    }

    /**
     * Apply one `compacting` envelope to the thread (#1358): a falling edge draws a divider through
     * [withCompactingEdge], stamped with the envelope's `ts`. A malformed payload or `ts` drops the frame for
     * the thread only.
     *
     * Unlike this class's other frames, this one also feeds [CompactingProjection], which decodes it on its
     * own: its `apply(envelope)` is kept as it is, so the status indicator is untouched by this fold. Only the
     * failure boolean leaves the decode; the claude-authored outcome strings never do.
     */
    fun applyCompacting(envelope: Envelope) {
        decodeCompactingEdge(envelope)?.let { (conversationId, edge) ->
            foldCompaction(conversationId) { rows, fold -> rows.withCompactingEdge(fold, edge.active, edge.failed, edge.occurredAt) }
        }
    }

    /**
     * Apply one `model_refusal_fallback` or `model_refusal_no_fallback` envelope (#875): decode it by its
     * type, then fold its row. A malformed one is dropped. Returns the routing conversation id and the
     * decoded refusal with its `scope` (#1360), for the caller's live signal, or `null` when dropped.
     */
    fun applyModelRefusal(envelope: Envelope): Pair<String, LiveRefusalEvent.Refused>? =
        decodeModelRefusal(envelope)?.also { (conversationId, refused) -> appendModelRefusal(conversationId, refused.refusal) }

    /**
     * Append [rows] (`conversationId -> Message`) into [threadByConversation] as [ThreadItem.MessageItem]
     * rows in one atomic [MutableStateFlow.update], preserving order and deduping by `message_id` — the
     * per-row fold is [withMessage], which #645 lifted out of the repository so the history reduction runs
     * the **same** fold rather than a second copy of it (see `HistoryPageReducer`). Batching a whole
     * chunk into one update avoids emitting an intermediate list per row. No-op on an empty batch so a
     * malformed/empty chunk never re-emits.
     */
    fun appendMessages(rows: List<Pair<String, Message>>) {
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
     * Append one live `message` [message] to [conversationId]'s thread (#1351), **keeping a row the thread
     * already holds** under the same `message_id` rather than replacing it the way [appendMessages] does.
     * The daemon pushes the operator's delivered message to every conn, the sender's included, and the
     * sender's confirmed row carries the attachment names and send time that the pushed copy lacks. The
     * identity is [withMessage]'s, id-only and role-agnostic. A held id returns the map unchanged, so
     * nothing re-emits.
     *
     * The one exception is position (#1558): the push of an echo still [OwnEchoQueue.queued] is its delivery,
     * so the held echo, unchanged, first moves to the end of the thread, after the turn it waited behind.
     */
    fun appendLiveMessage(
        conversationId: String,
        message: Message,
    ) {
        if (message.id in mintedMessageIds.value[conversationId].orEmpty()) trail.delivered(message.id)
        val echoes = ownEchoQueues.value[conversationId]
        if (echoes != null && message.id in echoes.queued) {
            if (message.id in echoes.parked) moveOwnEchoToEnd(conversationId, message.id)
            ownEchoQueues.update { all ->
                val current = all[conversationId] ?: return@update all
                all +
                    (
                        conversationId to
                            OwnEchoQueue(current.queued - message.id, current.delivered + message.id, current.behindTurn - message.id)
                    )
            }
        }
        threadByConversation.update { current ->
            val thread = current[conversationId].orEmpty()
            val held = thread.any { it is ThreadItem.MessageItem && it.message.id == message.id }
            if (held) current else current + (conversationId to (thread + ThreadItem.MessageItem(message)))
        }
    }

    /**
     * Append [boundary] to [conversationId]'s thread in one atomic [MutableStateFlow.update] (#336): an
     * end-append in arrival order that **skips a boundary the thread already holds** (#775). A
     * `session_transition` carries no row id, so the identity is `(previousSessionId, newSessionId,
     * occurredAt)` — the same [holdsBoundary] the history merge uses, and the fields the thread's list key
     * reads, so a held duplicate cannot reach the `LazyColumn` as a second row with the first one's key.
     * The pair alone would be wrong: a session idle-evicted twice sends `A->A` twice, and both are real.
     * A skipped repeat returns the map unchanged, so nothing re-emits.
     *
     * The repository is connection-scoped (#351), so within a connection arrival order is correct (the
     * same posture as [applyAssistantDelta]'s arrival-order concatenation; cross-reconnect replay is a
     * #402 concern, deferred). Routes strictly into [conversationId]'s slice, so a boundary can only ever
     * surface in `observeMessages(conversationId)` — never cross-routed (AC #1). The session ids /
     * `workspaceCwd` are carried inside the typed [boundary] and never logged here (Security review).
     */
    fun appendSessionBoundary(
        conversationId: String,
        boundary: ThreadItem.SessionBoundary,
    ) {
        threadByConversation.update { current ->
            val thread = current[conversationId].orEmpty()
            if (thread.holdsBoundary(boundary)) current else current + (conversationId to (thread + boundary))
        }
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
     * `appendThreadItem`.** The two differ in contract, and the difference is exactly the rationale:
     * [appendSessionBoundary] skips a repeat because a boundary *has* an identity (its session pair and
     * instant, #775) and a second row with it would collide on the list key, whereas this one does not
     * dedup because **dedup would destroy the signal** — how often this frame fires is the number that
     * tells someone to go fix something, so merging repeats hides it, and each row brings its own
     * client-stamped id so repeats never collide. The refusal is the point, not an oversight; the daemon
     * does no dedup on the wire either. A shared helper would have to carry both rationales in one KDoc,
     * and a later change to one contract would silently change the other.
     *
     * This cuts against the two nearest folds — [appendMessages] dedups by `message_id` and [applyToolUse]
     * is idempotent on a repeat id. A pure end-append is the one followed here.
     */
    private fun appendUnrecognizedMessage(
        conversationId: String,
        row: ThreadItem.UnrecognizedMessage,
    ) {
        threadByConversation.update { it + (conversationId to (it[conversationId].orEmpty() + row)) }
    }

    /**
     * End-append a [ThreadItem.Banner] to [conversationId]'s thread (#873), **unless the thread already
     * holds one with its `ts`** ([holdsBanner]). Unlike [appendUnrecognizedMessage] this dedups, because a
     * banner *has* an identity: the daemon stamps one `ts` per event and hands it to both lanes, so a
     * repeat is the same banner arriving twice — a replay, or a history page that raced the live lane —
     * not a second report. The check runs inside the one atomic [MutableStateFlow.update], so a
     * concurrent merge cannot slip a twin in between check and write.
     */
    private fun appendBanner(
        conversationId: String,
        row: ThreadItem.Banner,
    ) {
        threadByConversation.update { threads ->
            val thread = threads[conversationId].orEmpty()
            if (thread.holdsBanner(row)) threads else threads + (conversationId to (thread + row))
        }
    }

    /**
     * Run one compaction [step] on [conversationId]'s thread and its [compactionFolds] entry (#874, #1358).
     * The step runs inside the one atomic [MutableStateFlow.update], so a concurrent merge cannot slip a twin
     * divider in between check and write, and an unchanged thread writes nothing. The next fold depends on
     * the previous fold and the frame only, never on the thread, so a retried update yields the same fold;
     * it is stored after the update by the single inbound collector, the only writer.
     */
    private fun foldCompaction(
        conversationId: String,
        step: (List<ThreadItem>, CompactionFold) -> Pair<List<ThreadItem>, CompactionFold>,
    ) {
        val fold = compactionFolds.value[conversationId] ?: CompactionFold()
        var next = fold
        threadByConversation.update { threads ->
            val thread = threads[conversationId].orEmpty()
            val (rows, stepped) = step(thread, fold)
            next = stepped
            if (rows === thread) threads else threads + (conversationId to rows)
        }
        if (next != fold) compactionFolds.update { it + (conversationId to next) }
    }

    /**
     * End-append a [ThreadItem.ModelRefusal] to [conversationId]'s thread (#875), **unless the thread already
     * holds one of its type and `ts`** ([holdsModelRefusal]) — the [appendBanner] shape, for the same reason:
     * the daemon stamps one `ts` per refusal and hands it to both lanes, so a repeat is the same refusal
     * arriving twice. The check runs inside the one atomic [MutableStateFlow.update].
     */
    private fun appendModelRefusal(
        conversationId: String,
        row: ThreadItem.ModelRefusal,
    ) {
        threadByConversation.update { threads ->
            val thread = threads[conversationId].orEmpty()
            if (thread.holdsModelRefusal(row)) threads else threads + (conversationId to (thread + row))
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
     * thread order is arrival order, never a timestamp sort) mirror [RemoteConversationRepository.sendMessage]'s posture. The tool
     * name/input/output are carried **verbatim** — never trimmed, parsed, or logged (Security review).
     *
     * The row logic itself moved to [withToolUse] in #645, as did the three sibling folds below, so the
     * history reduction runs these exact folds instead of a second copy — see `HistoryPageReducer`. Each
     * method here is now just the projection write: read this conversation's slice, fold, put it back.
     * The clock is the one thing the two lanes differ on, which is why it is a parameter there.
     */
    fun applyToolUse(event: LiveSessionEvent.ToolUse) {
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
    fun applyToolResult(event: LiveSessionEvent.ToolResult) {
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withToolResult(event))
        }
    }

    /**
     * Mark a live tool-call row denied for a `tool_denied` (#811) through [withToolDenied], writing only
     * the frame's own conversation. A malformed payload drops this one envelope and the collector lives
     * on; the caught exception is discarded because its message can quote claude's prose. A conversation
     * with no retained rows is left without an entry rather than given an empty one, since a denial can
     * never add a row. Nothing here logs.
     */
    fun applyToolDenied(envelope: Envelope) {
        val dto =
            try {
                MobileJson.decodeFromJsonElement<ToolDeniedPayloadDto>(envelope.payload)
            } catch (e: IllegalArgumentException) {
                return
            }
        threadByConversation.update { current ->
            val rows = current[dto.conversationId] ?: return@update current
            current + (dto.conversationId to rows.withToolDenied(dto.toolUseId, dto.toDenial()))
        }
    }

    /**
     * Retain claude's elapsed reading on a live tool row for a `tool_progress` (#812) through
     * [withToolProgress], writing only the frame's own conversation. The drop and the no-slice rule are
     * [applyToolDenied]'s: a malformed payload costs this one envelope, and a conversation with no retained
     * rows gets no entry, since a heartbeat can never add a row. Nothing here logs.
     */
    fun applyToolProgress(envelope: Envelope) {
        val dto =
            try {
                MobileJson.decodeFromJsonElement<ToolProgressPayloadDto>(envelope.payload)
            } catch (e: IllegalArgumentException) {
                return
            }
        threadByConversation.update { current ->
            val rows = current[dto.conversationId] ?: return@update current
            current + (dto.conversationId to rows.withToolProgress(dto))
        }
    }

    /**
     * Fold one `assistant_delta` into the conversation's assistant reply segments (#337, #1350). A
     * delta extends the last row when it is a segment of the same turn and otherwise opens a new
     * streaming [Role.Assistant] segment at the end, so text after a tool call or a user message draws
     * below it; [withAssistantDelta] has the segment key and the guards that keep it unique. One atomic
     * [MutableStateFlow.update] into the same [threadByConversation] the live `message` and tool
     * arms write, so the assistant text interleaves by **arrival order** with messages and tool rows
     * (AC #4).
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
    fun applyAssistantDelta(event: LiveSessionEvent.AssistantDelta) {
        val queuedEchoes = ownEchoQueues.value[event.conversationId]?.parked.orEmpty()
        threadByConversation.update { current ->
            val rows = current[event.conversationId].orEmpty().withAssistantDelta(event, Clock.System.now(), passOver = queuedEchoes)
            val ended = event.turnId in endedTurns.value[event.conversationId].orEmpty()
            current + (event.conversationId to if (ended) rows.withSettledTurns(setOf(event.turnId)) else rows)
        }
    }

    /**
     * Finalize the turn's assistant text on `turn_end` (#337): flip every streaming segment of
     * [LiveSessionEvent.TurnEnd.turnId] (#1350) to [Message.isStreaming] `= false` in place, so the thread renders the completed reply as static markdown rather than the
     * streaming caret view. One atomic [MutableStateFlow.update]. **No-op when no streaming assistant
     * row exists for the turn** — a tool-only or empty turn carries no assistant text (AC #3), and a
     * duplicate `turn_end` re-applies the same flip (idempotent). `turn_end` carries no final text; a turn
     * that did not end cleanly leaves a [ThreadItem.StoppedTurn] stamped with its arrival instant, once
     * (#1356, see [withFinalizedTurn]). The turn is first recorded in [endedTurns], so a row of it that a
     * later merge brings in lands settled too (#1419).
     */
    fun finalizeAssistantTurn(event: LiveSessionEvent.TurnEnd) {
        recordEnded(event.conversationId, setOf(event.turnId))
        threadByConversation.update { current ->
            current + (event.conversationId to current[event.conversationId].orEmpty().withFinalizedTurn(event, Clock.System.now()))
        }
    }

    /**
     * Record [messageId] as an echo this device minted into [conversationId]'s thread (#781), called by
     * [RemoteConversationRepository.sendMessage] as it draws the echo, before the ack (#1355) — only an id
     * in [mintedMessageIds] may later be correlated with a queued item and removed.
     */
    fun recordMinted(
        conversationId: String,
        messageId: String,
    ) {
        mintedMessageIds.update { it + (conversationId to (it[conversationId].orEmpty() + messageId)) }
    }

    /** Record a drop of [queuedMessageId] whose echo is [echoId] before it is sent (#859); see [pendingDrops]. */
    fun recordDrop(
        conversationId: String,
        queuedMessageId: Long,
        echoId: String,
    ) {
        pendingDrops.update { it + (conversationId to (it[conversationId].orEmpty() + (queuedMessageId to echoId))) }
    }

    /** Withdraw a drop recorded by [recordDrop] whose send failed, so the daemon never heard it (#859). */
    fun withdrawDrop(
        conversationId: String,
        queuedMessageId: Long,
    ) {
        pendingDrops.update { it + (conversationId to (it[conversationId].orEmpty() - queuedMessageId)) }
    }

    /**
     * Settle the pending drops of every conversation against [queue]'s current snapshot (#859), after a
     * `queue_state` has been applied to it. A snapshot is the daemon's only confirmation of a drop, and
     * settling a conversation whose backlog did not change is a no-op, so every pending one is settled.
     */
    fun settleDrops(queue: QueueProjection) {
        pendingDrops.value.keys.forEach { settleDrops(it, queue) }
    }

    /**
     * Settle every drop pending in [conversationId] whose `queued_msg_id` the current snapshot no longer
     * holds (#859): claim the entries in one atomic [MutableStateFlow.update], so each settles at most
     * once, then remove each claimed entry's echo through [removeOwnEcho]. Entries still in the snapshot
     * stay pending.
     */
    private fun settleDrops(
        conversationId: String,
        queue: QueueProjection,
    ) {
        val live = queue.current(conversationId).mapTo(HashSet()) { it.id }
        var settled: Collection<String> = emptyList()
        pendingDrops.update { all ->
            val pending = all[conversationId] ?: return@update all
            val (gone, waiting) = pending.entries.partition { it.key !in live }
            settled = gone.map { it.value }
            if (waiting.isEmpty()) all - conversationId else all + (conversationId to waiting.associate { it.key to it.value })
        }
        settled.forEach { removeOwnEcho(conversationId, it) }
    }

    /**
     * Track this device's queued echoes against [queue]'s current snapshots (#1558), after a `queue_state`
     * has been applied and [settleDrops] has run. In each conversation this device minted into, an echo the
     * snapshot holds is [OwnEchoQueue.queued], and one that has left the snapshot is delivered: it moves to
     * the end of the thread before it stops reading as queued, so no read shows it at its tap-time slot. A
     * dropped echo has already been removed and its id spent, so the move finds nothing.
     *
     * Only an echo first reported while [turnOpen] held for its conversation waits behind a turn (#1636): it
     * alone reads last and moves when it drains. One first reported while idle keeps its tap-time slot.
     */
    fun settleQueuedEchoes(
        queue: QueueProjection,
        turnOpen: (conversationId: String) -> Boolean,
    ) {
        (mintedMessageIds.value.keys + ownEchoQueues.value.keys).forEach { settleQueuedEchoes(it, queue, turnOpen(it)) }
    }

    private fun settleQueuedEchoes(
        conversationId: String,
        queue: QueueProjection,
        turnOpen: Boolean,
    ) {
        val inSnapshot = queue.current(conversationId).mapTo(HashSet()) { it.messageId }
        val echoes = ownEchoQueues.value[conversationId] ?: OwnEchoQueue()
        val drained = echoes.queued - inSnapshot
        drained.filter { it in echoes.behindTurn }.forEach { moveOwnEchoToEnd(conversationId, it) }
        val delivered = echoes.delivered + drained
        val minted = mintedMessageIds.value[conversationId].orEmpty()
        val queued = inSnapshot.intersect(minted) - delivered
        val behindTurn = (echoes.behindTurn intersect queued) + if (turnOpen) queued - echoes.queued else emptySet()
        val next = OwnEchoQueue(queued, delivered, behindTurn)
        // A dropped echo has already left the ledger, so only a drained one still in it was delivered.
        drained.filter { it in minted }.forEach(trail::delivered)
        next.queued.forEach(trail::queued)
        if (next != echoes) ownEchoQueues.update { it + (conversationId to next) }
    }

    /**
     * Move the row this device minted under [messageId] to the end of [conversationId]'s thread, unchanged
     * (#1558). A no-op unless the id is in [mintedMessageIds] and the first message row carrying it is a
     * user row, so a daemon frame naming somebody else's id, or a non-user row's, never moves anything.
     */
    private fun moveOwnEchoToEnd(
        conversationId: String,
        messageId: String,
    ) {
        if (messageId !in mintedMessageIds.value[conversationId].orEmpty()) return
        threadByConversation.update { current ->
            val rows = current[conversationId] ?: return@update current
            val index = rows.indexOfFirst { it is ThreadItem.MessageItem && it.message.id == messageId }
            val row = rows.getOrNull(index) as? ThreadItem.MessageItem ?: return@update current
            if (row.message.role != Role.User || index == rows.lastIndex) return@update current
            current + (conversationId to (rows.filterIndexed { i, _ -> i != index } + row))
        }
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
        trail.dropped(messageId)
        mintedMessageIds.update { it + (conversationId to (it[conversationId].orEmpty() - messageId)) }
        threadByConversation.update { current ->
            val rows = current[conversationId] ?: return@update current
            current + (conversationId to rows.filterNot { it is ThreadItem.MessageItem && it.message.id == messageId })
        }
    }

    /**
     * Fold one decoded [page] into [conversationId]'s thread (#645) — the write #623's
     * [RemoteConversationRepository.requestHistory] KDoc promised this ticket would add, and the **only** projection this verb touches. The page is
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
     * structured types reduce only when it was negotiated ([interactive], which the repository reads from
     * the negotiated set), `message` / `send_message` always do. The daemon's `request_history` handler has
     * no such gate, so this is the client's fail-closed half.
     *
     * The page's `turn_end`s are recorded in [endedTurns] first, and the merged slice then settles every
     * turn the conversation has seen end (#1419): a page can bring the rows of a turn whose `turn_end` came
     * earlier, on the live lane or on a newer page.
     *
     * Emits no log on any branch, like the rest of this class.
     */
    fun mergeHistoryPage(
        conversationId: String,
        page: HistoryPage,
        interactive: Boolean,
    ) {
        if (page.entries.isEmpty()) return
        recordEnded(conversationId, endedTurnIds(page.entries, interactive))
        threadByConversation.update { current ->
            val existing = current[conversationId].orEmpty()
            val merged = existing.mergeHistoryRows(reduceHistoryPage(page.entries, interactive))
            current + (conversationId to merged.withSettledTurns(endedTurns.value[conversationId].orEmpty()))
        }
        settleEndedTurns(conversationId)
    }

    /**
     * Settle every row of [conversationId]'s thread whose turn has ended, after a merge has committed
     * (#1419). The merge's own settle can miss a live `turn_end` recorded while it ran, and that
     * finalize's thread update cannot make the merge retry when it changes nothing; this pass closes the
     * window (see [endedTurns]). Writes nothing when nothing changes.
     */
    private fun settleEndedTurns(conversationId: String) {
        threadByConversation.update { current ->
            val rows = current[conversationId] ?: return@update current
            val settled = rows.withSettledTurns(endedTurns.value[conversationId].orEmpty())
            if (settled === rows) current else current + (conversationId to settled)
        }
    }

    /** Add [turnIds] to [conversationId]'s [endedTurns]; an empty set writes nothing (#1419). */
    private fun recordEnded(
        conversationId: String,
        turnIds: Set<String>,
    ) {
        if (turnIds.isEmpty()) return
        endedTurns.update { it + (conversationId to (it[conversationId].orEmpty() + turnIds)) }
    }

    /**
     * Drop [conversationId]'s thread after a confirmed `delete` (#532), the thread third of
     * [ConversationCommands]' `removeConversation`, along with the turns it has seen end (#1419). Removing
     * an absent id re-emits nothing.
     */
    fun remove(conversationId: String) {
        endedTurns.update { it - conversationId }
        compactionFolds.update { it - conversationId }
        ownEchoQueues.update { it - conversationId }
        threadByConversation.update { it - conversationId }
    }

    /**
     * Cold per-conversation thread view: the ordered [ThreadItem] list ([ThreadItem.MessageItem] rows
     * deduped by `message_id` + interleaved [ThreadItem.SessionBoundary] rows) for [conversationId].
     * The store already holds [ThreadItem]s, so this is a plain per-conversation slice — no row wrap.
     * [distinctUntilChanged] means a change to **another** conversation's slot does not re-emit this
     * flow (AC #3). A `StateFlow` always has a value, so a fresh collector receives the current thread
     * (empty until backfill/live arrives) on subscription.
     *
     * Every row but the last is read settled ([withOnlyLastRowStreaming], #1350): an assistant segment
     * stops streaming once any row follows it, whichever write appended that row. This is the one read of
     * the store, so no reader sees an earlier segment still streaming.
     *
     * This device's queued echoes read last (#1558), in thread order, below every row of the turn they wait
     * behind, and the last-row rule runs over the rows without them, so the running reply keeps streaming.
     */
    fun observe(conversationId: String): Flow<List<ThreadItem>> =
        combine(threadByConversation, ownEchoQueues) { threads, echoes ->
            threads[conversationId].orEmpty().withQueuedEchoesLast(echoes[conversationId]?.parked.orEmpty())
        }.distinctUntilChanged()

    /** This thread as [observe] reads it: [queued] user rows last, the rest through [withOnlyLastRowStreaming]. */
    private fun List<ThreadItem>.withQueuedEchoesLast(queued: Set<String>): List<ThreadItem> {
        if (queued.isEmpty()) return withOnlyLastRowStreaming()
        val (parked, rest) = partition { it is ThreadItem.MessageItem && it.message.role == Role.User && it.message.id in queued }
        return if (parked.isEmpty()) withOnlyLastRowStreaming() else rest.withOnlyLastRowStreaming() + parked
    }

    /**
     * Every conversation's row count (#1361): the size of its thread, so an appended row of any kind raises
     * it and an in-place update (a delta into the same bubble, a tool result) does not. The projection starts
     * empty per connection, so a collector's baseline for it is zero rows.
     */
    fun observeRowCounts(): Flow<Map<String, Int>> =
        threadByConversation
            .map { threads ->
                threads.mapValues { it.value.size }
            }.distinctUntilChanged()

    /**
     * Decode one v2 `unrecognized_message` envelope (#609) to its routing [conversationId] and the mapped
     * [ThreadItem.UnrecognizedMessage], or **null** when it cannot be folded. Decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson] and maps via `toRow()`. The whole body
     * is one `try`/`catch (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing or wrong-typed required field —
     * yields `null`, dropping the one envelope while the lone inbound collector survives (AC #4). A
     * **`site` outside the closed set** is a distinct path: `toRow()` returns `null` (no throw), so the one
     * envelope drops the same way. Mirrors [RemoteConversationRepository]'s `decodeSessionTransition` drop idiom.
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
     * Decode one v2 `banner` envelope (#873) to its routing conversation id and the mapped
     * [ThreadItem.Banner], or **null** when it cannot be folded. The row's identity is the envelope's
     * `ts`, the protocol's join key against a history page, so a malformed `ts` drops the frame exactly as
     * a malformed payload does: both throw inside the one `try`. Mirrors [decodeUnrecognizedMessage]'s drop
     * idiom, and like it logs nothing.
     */
    private fun decodeBanner(envelope: Envelope): Pair<String, ThreadItem.Banner>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<BannerPayloadDto>(envelope.payload)
            dto.conversationId to dto.toRow(occurredAt = Instant.parse(envelope.ts))
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 `compaction_boundary` envelope (#874) to its routing conversation id and the mapped
     * [ThreadItem.CompactionBoundary], or **null** when it cannot be folded. The row's identity is the
     * envelope's `ts`, so a malformed `ts` drops the frame exactly as a malformed payload does. Mirrors
     * [decodeBanner], and like it logs nothing.
     */
    private fun decodeCompactionBoundary(envelope: Envelope): Pair<String, ThreadItem.CompactionBoundary>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<CompactionBoundaryPayloadDto>(envelope.payload)
            dto.conversationId to dto.toRow(occurredAt = Instant.parse(envelope.ts))
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * One conversation's own queued echoes (#1558). [queued] is the minted ids its latest snapshot holds;
     * [delivered] is the ids already moved to the end, which a later snapshot repeating one (a legal
     * duplicate `message_id`) never queues again.
     *
     * [behindTurn] is the [queued] ids first reported while a turn was open (#1636), and only those, the
     * [parked] ones, read last and move on delivery. One queued while idle waits behind nothing: the daemon
     * delivers it at once, and its confirmation can arrive after its own reply began, so moving it then would
     * put it below the reply's start and split the reply around it.
     */
    private data class OwnEchoQueue(
        val queued: Set<String> = emptySet(),
        val delivered: Set<String> = emptySet(),
        val behindTurn: Set<String> = emptySet(),
    ) {
        val parked: Set<String> get() = queued intersect behindTurn
    }

    /** One `compacting` edge as the thread fold reads it (#1358): the outcome strings reduced to [failed]. */
    private class CompactingEdge(
        val active: Boolean,
        val failed: Boolean,
        val occurredAt: Instant,
    )

    /**
     * Decode one `compacting` envelope (#1358) to its routing conversation id and its [CompactingEdge], or
     * **null** when it cannot be folded. A malformed `ts` drops the frame as a malformed payload does. Mirrors
     * [decodeCompactionBoundary], and like it logs nothing.
     */
    private fun decodeCompactingEdge(envelope: Envelope): Pair<String, CompactingEdge>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<CompactingPayloadDto>(envelope.payload)
            dto.conversationId to CompactingEdge(dto.active, dto.failed(), Instant.parse(envelope.ts))
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Decode one v2 model refusal envelope (#875) to its routing conversation id and the mapped
     * [ThreadItem.ModelRefusal], or **null** when it cannot be folded. The DTO is chosen by [Envelope.type],
     * which is the only thing that tells the two frames apart. The row's identity is the envelope's `ts`, so a
     * malformed `ts` drops the frame exactly as a malformed payload does. Mirrors [decodeBanner], and like it
     * logs nothing: every field but the conversation id is claude-authored. The fallback frame's `scope`
     * rides beside the row (#1360); the row itself still drops it.
     */
    private fun decodeModelRefusal(envelope: Envelope): Pair<String, LiveRefusalEvent.Refused>? =
        try {
            val occurredAt = Instant.parse(envelope.ts)
            when (envelope.type) {
                RemoteConversationRepository.TYPE_MODEL_REFUSAL_FALLBACK ->
                    MobileJson
                        .decodeFromJsonElement<ModelRefusalFallbackPayloadDto>(envelope.payload)
                        .let { it.conversationId to LiveRefusalEvent.Refused(it.toRow(occurredAt), it.scope) }
                RemoteConversationRepository.TYPE_MODEL_REFUSAL_NO_FALLBACK ->
                    MobileJson
                        .decodeFromJsonElement<ModelRefusalNoFallbackPayloadDto>(envelope.payload)
                        .let { it.conversationId to LiveRefusalEvent.Refused(it.toRow(occurredAt), scope = null) }
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            null
        }
}
