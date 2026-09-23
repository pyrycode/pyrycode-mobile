/**
 * The history-page reduction and merge (#645), plus the pure thread folds it shares with the live lane.
 *
 * [RemoteConversationRepository.requestHistory] returns a [HistoryPage] of [HistoryEntry] values,
 * newest-first, each carrying a durable per-conversation log id, the stored frame's wire `type`, its
 * still-undecoded `payload` and a `ts`. This file turns one such page into thread rows and puts them in
 * front of the rows a conversation's thread already holds.
 *
 * **One fold surface, reached two ways.** The `List<ThreadItem>` extensions below *are* the live lane's
 * folds: the repository's `appendMessages` / `applyToolUse` / `applyToolResult` / `applyToolDenied` /
 * `applyAssistantDelta` /
 * `finalizeAssistantTurn` are thin wrappers that call them inside a `MutableStateFlow.update`. A replayed
 * page therefore cannot drift from the live stream's shape, which is the property the ticket asks for
 * ("the same timeline mapping the live lane already runs"). The one deliberate difference is the clock:
 * a locally-assembled row's timestamp is a **parameter** here, so the live wrapper passes
 * `Clock.System.now()` while the reduction passes the entry's stored `ts` — a replayed tool row must not
 * be stamped with the moment it was replayed.
 *
 * **Nothing in this file logs anything, on any branch.** `type` and `payload` are replayed content —
 * operator-authored for a stored `send_message`, `claude`-authored for a stored assistant frame — and a
 * logged `conversation_id` is a cross-conversation correlation leak. Every drop is silent, matching every
 * `onInbound` arm.
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § *Conversation history (v2)*, sub-sections
 * *A history entry* and *Joining a page to the live stream*. Not restated here.
 *
 * The file reads in three parts: the shared thread folds, then the reduction, then the merge.
 */

package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.ToolDeniedPayloadDto
import de.pyryco.mobile.data.network.ToolProgressPayloadDto
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.UnrecognizedMessagePayloadDto
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.network.toDenial
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toMessage
import de.pyryco.mobile.data.network.toRow
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_ASSISTANT_DELTA
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MESSAGE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SEND_MESSAGE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_SESSION_TRANSITION
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_TOOL_DENIED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_TOOL_PROGRESS
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_TOOL_RESULT
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_TOOL_USE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_TURN_END
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_UNRECOGNIZED_MESSAGE
import kotlinx.datetime.Instant
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Index of the [ThreadItem.MessageItem] in this thread whose [Message.id] is [id] and [Message.role]
 * is [role], or -1 if none — the row guard shared by the four id+role folds (tool / assistant). The
 * `is ThreadItem.MessageItem` type-guard namespaces message rows from [ThreadItem.SessionBoundary]
 * rows, so a fold never mistakes a boundary for a message (and the `as` after a hit is always safe).
 */
internal fun List<ThreadItem>.indexOfMessage(
    id: String,
    role: Role,
): Int = indexOfFirst { it is ThreadItem.MessageItem && it.message.id == id && it.message.role == role }

/**
 * Append [message] as a [ThreadItem.MessageItem], deduping by `message_id`: a first-seen id is appended
 * at the end, a repeat id replaces the existing message row **in place** (position fixed at first
 * occurrence, last write wins). The `is ThreadItem.MessageItem` guard skips any interleaved
 * [ThreadItem.SessionBoundary] so a `message_id` never matches a boundary row.
 *
 * Dedup here is **id-only, role-agnostic** — deliberately not routed through [indexOfMessage] — because
 * that is what `appendMessages` has always done and routing it through the role-taking helper would
 * change semantics. It is also the key `ThreadScreen`'s `LazyColumn` uses for a message row.
 */
internal fun List<ThreadItem>.withMessage(message: Message): List<ThreadItem> {
    val index = indexOfFirst { it is ThreadItem.MessageItem && it.message.id == message.id }
    return if (index >= 0) {
        toMutableList().apply { this[index] = ThreadItem.MessageItem(message) }
    } else {
        this + ThreadItem.MessageItem(message)
    }
}

/**
 * Open a tool-call row for a `tool_use` (#387): append a `Running` [Role.Tool] [Message] carrying the
 * tool name + input, keyed by [LiveSessionEvent.ToolUse.toolUseId]. **Idempotent on a repeat id:** if a
 * [Role.Tool] row with this id already exists (possibly already completed by an earlier `tool_result`),
 * it is left untouched — a duplicate never adds a second row nor resets a finished one to `Running`.
 * The `&& role == Role.Tool` match namespaces tool rows so a `toolUseId` can never clobber a real
 * `message_id` row. [timestamp] is the row clock (see this file's header); the tool name / input,
 * the input fields and the parent id (#810) are carried **verbatim** — never trimmed, parsed, or logged.
 */
internal fun List<ThreadItem>.withToolUse(
    event: LiveSessionEvent.ToolUse,
    timestamp: Instant,
): List<ThreadItem> =
    if (indexOfMessage(event.toolUseId, Role.Tool) >= 0) {
        this
    } else {
        this +
            ThreadItem.MessageItem(
                Message(
                    id = event.toolUseId,
                    sessionId = "",
                    role = Role.Tool,
                    content = event.name,
                    timestamp = timestamp,
                    isStreaming = false,
                    toolCall =
                        ToolCall(
                            toolName = event.name,
                            input = event.inputSummary,
                            output = "",
                            status = ToolCallStatus.Running,
                            inputFields = event.input,
                            parentToolUseId = event.parentToolUseId,
                        ),
                ),
            )
    }

/**
 * Complete a tool-call row for a `tool_result` (#387): update the matching [Role.Tool] row in place —
 * position and `timestamp` preserved — attaching the output and flipping the status to
 * [ToolCallStatus.Failed] when [LiveSessionEvent.ToolResult.isError], else [ToolCallStatus.Done].
 * **If no matching row exists, no-op:** a result with no prior use — including one arriving before its
 * use — is dropped, leaving no orphan half-row. A duplicate re-applies the same in-place update
 * (idempotent / last-write-wins, one row). The result summary is carried **verbatim**.
 *
 * The result's `parent_tool_use_id` (#810) replaces the row's when non-empty and leaves the use's in
 * place when empty. A conforming daemon sends the same value on both frames; this only matters across a
 * mid-stream daemon change where one frame lacks the key, and then it never un-nests a row.
 *
 * A row already [ToolCallStatus.Denied] (#811) keeps that status and its denial: claude writes a
 * `tool_result` after refusing a call, and that result must not turn a blocked call into a failed one.
 *
 * Closing the call clears its elapsed reading (#812); see [withToolProgress].
 */
internal fun List<ThreadItem>.withToolResult(event: LiveSessionEvent.ToolResult): List<ThreadItem> {
    val index = indexOfMessage(event.toolUseId, Role.Tool)
    if (index < 0) return this
    val row = (this[index] as ThreadItem.MessageItem).message
    val updated =
        row.copy(
            toolCall =
                row.toolCall?.let { call ->
                    call.copy(
                        output = event.resultSummary,
                        status =
                            when {
                                call.status == ToolCallStatus.Denied -> ToolCallStatus.Denied
                                event.isError -> ToolCallStatus.Failed
                                else -> ToolCallStatus.Done
                            },
                        parentToolUseId = event.parentToolUseId.ifEmpty { call.parentToolUseId },
                        elapsedSeconds = null,
                    )
                },
        )
    return toMutableList().apply { this[index] = ThreadItem.MessageItem(updated) }
}

/**
 * Mark the [Role.Tool] row [toolUseId] names as [ToolCallStatus.Denied] for a `tool_denied` (#811), in
 * place, attaching [denial]. The denial wins over any prior status, because the daemon's result-line
 * recovery can report a denial after the call's `tool_result` shipped. Output, input, parent and position
 * are untouched, and a repeat denial is last-write-wins. **No matching row, no-op:** a denial never adds
 * a row. A denial closes the call, so it clears the elapsed reading (#812).
 */
internal fun List<ThreadItem>.withToolDenied(
    toolUseId: String,
    denial: ToolDenial,
): List<ThreadItem> {
    val index = indexOfMessage(toolUseId, Role.Tool)
    if (index < 0) return this
    val row = (this[index] as ThreadItem.MessageItem).message
    val updated =
        row.copy(toolCall = row.toolCall?.copy(status = ToolCallStatus.Denied, denial = denial, elapsedSeconds = null))
    return toMutableList().apply { this[index] = ThreadItem.MessageItem(updated) }
}

/**
 * Retain claude's latest elapsed-seconds reading (`tool_progress`, #812) on the open [Role.Tool] row
 * [ToolProgressPayloadDto.toolUseId] names, in place. The reading is copied **verbatim** — zero, negative
 * and backwards values included — and a repeat of the held reading returns this list unchanged. **No
 * matching row, or a row no longer [ToolCallStatus.Running], no-op:** a heartbeat never adds a row, and a
 * late one neither reopens a closed call nor touches its outcome. Only the routing key is read beside the
 * value; `turn_id` joins nothing, as for the other tool folds.
 */
internal fun List<ThreadItem>.withToolProgress(progress: ToolProgressPayloadDto): List<ThreadItem> {
    val index = indexOfMessage(progress.toolUseId, Role.Tool)
    if (index < 0) return this
    val row = (this[index] as ThreadItem.MessageItem).message
    val call = row.toolCall ?: return this
    if (call.status != ToolCallStatus.Running || call.elapsedSeconds == progress.elapsedSeconds) return this
    val updated = row.copy(toolCall = call.copy(elapsedSeconds = progress.elapsedSeconds))
    return toMutableList().apply { this[index] = ThreadItem.MessageItem(updated) }
}

/**
 * Fold one `assistant_delta` into the conversation's streaming assistant row (#337). The first delta of
 * a turn opens a [Role.Assistant] [Message] keyed by [LiveSessionEvent.AssistantDelta.turnId] with
 * [Message.isStreaming] `= true`; each later delta for that turn **appends** its text in place, keeping
 * the row's id and position. The `&& role == Role.Assistant` match namespaces this row so a `turnId` can
 * never clobber a `message_id` or `toolUseId` row.
 *
 * **Arrival-order concatenation, by design** — see `applyAssistantDelta`'s KDoc for why that is correct
 * on the live lane. It holds for a page too, and for a different reason worth stating: a page is
 * reduced strictly oldest-first, so the log's own append order *is* the concatenation order. The delta
 * text is carried **verbatim** — never trimmed, parsed, or logged.
 */
internal fun List<ThreadItem>.withAssistantDelta(
    event: LiveSessionEvent.AssistantDelta,
    timestamp: Instant,
): List<ThreadItem> {
    val index = indexOfMessage(event.turnId, Role.Assistant)
    return if (index >= 0) {
        val row = (this[index] as ThreadItem.MessageItem).message
        toMutableList().apply { this[index] = ThreadItem.MessageItem(row.copy(content = row.content + event.text)) }
    } else {
        this +
            ThreadItem.MessageItem(
                Message(
                    id = event.turnId,
                    sessionId = "",
                    role = Role.Assistant,
                    content = event.text,
                    timestamp = timestamp,
                    isStreaming = true,
                ),
            )
    }
}

/**
 * Finalize the streaming assistant row on `turn_end` (#337): flip the matching [Role.Assistant] row
 * (keyed by [LiveSessionEvent.TurnEnd.turnId]) to [Message.isStreaming] `= false` in place, so the
 * thread renders the completed reply as static markdown rather than the streaming caret view. **No-op
 * when no streaming assistant row exists for the turn** — a tool-only or empty turn carries no assistant
 * text — and a duplicate re-applies the same flip (idempotent). `turn_end` carries no final text, so
 * nothing is appended here.
 */
internal fun List<ThreadItem>.withFinalizedTurn(event: LiveSessionEvent.TurnEnd): List<ThreadItem> {
    val index = indexOfMessage(event.turnId, Role.Assistant)
    if (index < 0) return this
    val row = (this[index] as ThreadItem.MessageItem).message
    if (!row.isStreaming) return this
    return toMutableList().apply { this[index] = ThreadItem.MessageItem(row.copy(isStreaming = false)) }
}

// ---- The reduction -----------------------------------------------------------------------------

/**
 * Reduce one history page's [entries] to the thread rows they describe (#645), **oldest-first**.
 *
 * [entries] arrive in the wire's newest-first order and are reversed here, then folded through the
 * shared folds above starting from an empty list — so a `tool_use` and its `tool_result` in one page
 * become **one** completed row, and a turn's `assistant_delta` run plus its `turn_end` become one
 * finalized row, exactly as they would have live.
 *
 * **What is deliberately NOT reducible.** The transient live state — the stall set, the live-event
 * stream the thinking indicator reads, and the modal state fed by `modal_shown` / `modal_dismissed` —
 * lives outside the thread projection, and a stored `turn_state` / `stall` / `queue_state` / `api_retry`
 * / `compacting` / modal frame must never be replayed into any of it. That is not enforced by a check:
 * this function returns [ThreadItem]s and holds no reference to any of those holders, so those types
 * simply have no arm and land in the `else`. A stored state frame **cannot** reopen an old permission
 * prompt or restart a finished status indicator.
 *
 * **Routing is the caller's, never an entry's.** The result carries no conversation identity at all, so
 * a page structurally cannot write into a conversation the client did not ask about — an entry payload's
 * own `conversation_id` is never read for routing.
 *
 * **Forward compatibility.** Nothing re-validates a stored [HistoryEntry.type], so an unrecognised one
 * must survive the reduction rather than fail it: it falls to the `else` and costs one entry. A payload
 * that cannot be decoded (or whose mapper rejects an unknown `reason` / `site`) costs one entry the same
 * way — never the page. This is deliberately *unlike* the `message_chunk` arm's whole-batch drop, which
 * is a property of one envelope decoded as a unit; here each entry's payload is an independent second
 * decode behind an already-successful page decode.
 *
 * [interactive] mirrors the live lane's `CAPABILITY_INTERACTIVE` gate arm-for-arm: the six structured
 * types are gated exactly as their live twins are and `message` is ungated exactly as its twin is. The
 * daemon's `request_history` handler carries no such gate, so without this the client's fail-closed
 * posture would have a hole the live lane does not have.
 */
internal fun reduceHistoryPage(
    entries: List<HistoryEntry>,
    interactive: Boolean,
): List<ThreadItem> = entries.asReversed().fold(emptyList()) { rows, entry -> rows.withHistoryEntry(entry, interactive) }

/**
 * Fold one [entry] into [this] accumulator, or return it unchanged when the entry produces no row.
 *
 * The whole body is one `try`/`catch (IllegalArgumentException)`
 * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), the drop idiom every
 * `onInbound` decode arm uses — **nothing is logged** on the drop branch or any other.
 */
private fun List<ThreadItem>.withHistoryEntry(
    entry: HistoryEntry,
    interactive: Boolean,
): List<ThreadItem> =
    try {
        when (entry.type) {
            TYPE_MESSAGE ->
                withMessage(
                    MobileJson
                        .decodeFromJsonElement<MessagePayloadDto>(entry.payload)
                        .toMessage(timestamp = entry.timestamp, sessionId = ""),
                )
            // A stored inbound `send_message` — the operator's own turn, which the live lane never
            // echoes back (the ack carries nothing), so the log is its only retention. `role` is not a
            // wire field on this payload: the sender is the operator by construction.
            TYPE_SEND_MESSAGE ->
                MobileJson.decodeFromJsonElement<SendMessagePayloadDto>(entry.payload).let { dto ->
                    withMessage(
                        Message(
                            id = dto.messageId,
                            sessionId = "",
                            role = Role.User,
                            content = dto.text,
                            timestamp = entry.timestamp,
                            isStreaming = false,
                        ),
                    )
                }
            // The four turn-scoped structured types share one decode-then-dispatch, mirroring the
            // repository's own `decodeLiveSessionEvent` arm. The `when` over the sealed event type
            // replaces four casts; a `turn_state` cannot reach here (it has no arm above).
            TYPE_TOOL_USE, TYPE_TOOL_RESULT, TYPE_ASSISTANT_DELTA, TYPE_TURN_END ->
                if (!interactive) {
                    this
                } else {
                    when (val event = decodeLiveEvent(entry)) {
                        is LiveSessionEvent.ToolUse -> withToolUse(event, entry.timestamp)
                        is LiveSessionEvent.ToolResult -> withToolResult(event)
                        is LiveSessionEvent.AssistantDelta -> withAssistantDelta(event, entry.timestamp)
                        is LiveSessionEvent.TurnEnd -> withFinalizedTurn(event)
                        else -> this
                    }
                }
            // Not a live event (#811), so it has its own arm rather than a `decodeLiveEvent` case.
            TYPE_TOOL_DENIED ->
                if (!interactive) {
                    this
                } else {
                    MobileJson.decodeFromJsonElement<ToolDeniedPayloadDto>(entry.payload).let { dto ->
                        withToolDenied(dto.toolUseId, dto.toDenial())
                    }
                }
            // Not a live event either (#812); the daemon stores every heartbeat, so a page replays them.
            TYPE_TOOL_PROGRESS ->
                if (!interactive) {
                    this
                } else {
                    withToolProgress(MobileJson.decodeFromJsonElement<ToolProgressPayloadDto>(entry.payload))
                }
            TYPE_SESSION_TRANSITION ->
                if (!interactive) {
                    this
                } else {
                    MobileJson
                        .decodeFromJsonElement<SessionTransitionPayloadDto>(entry.payload)
                        .toBoundary()
                        ?.let { boundary -> if (holdsBoundary(boundary)) this else this + boundary }
                        ?: this
                }
            TYPE_UNRECOGNIZED_MESSAGE ->
                if (!interactive) {
                    this
                } else {
                    MobileJson
                        .decodeFromJsonElement<UnrecognizedMessagePayloadDto>(entry.payload)
                        .toRow(id = historyRowId(entry.id), occurredAt = entry.timestamp)
                        ?.let { row -> if (holdsUnrecognized(row.id)) this else this + row }
                        ?: this
                }
            // Every other stored type — the state frames, the modal pair, the control verbs, and any
            // type a future daemon invents. See this function's KDoc: no row, no failure.
            else -> this
        }
    } catch (e: IllegalArgumentException) {
        this
    }

/**
 * Decode one turn-scoped structured entry to its typed [LiveSessionEvent] through the **same** per-type
 * DTO + `toEvent()` arms the live lane uses. Throws on a malformed payload; the single `try` in
 * [withHistoryEntry] owns the drop, exactly as `onInbound`'s arms own theirs.
 */
private fun decodeLiveEvent(entry: HistoryEntry): LiveSessionEvent? =
    when (entry.type) {
        TYPE_TOOL_USE -> MobileJson.decodeFromJsonElement<ToolUsePayloadDto>(entry.payload).toEvent()
        TYPE_TOOL_RESULT -> MobileJson.decodeFromJsonElement<ToolResultPayloadDto>(entry.payload).toEvent()
        TYPE_ASSISTANT_DELTA -> MobileJson.decodeFromJsonElement<AssistantDeltaPayloadDto>(entry.payload).toEvent()
        TYPE_TURN_END -> MobileJson.decodeFromJsonElement<TurnEndPayloadDto>(entry.payload).toEvent()
        else -> null
    }

/**
 * The **client-owned** id a replayed [ThreadItem.UnrecognizedMessage] row takes, derived from the entry's
 * durable per-conversation log id. Derived rather than freshly minted because the thread's `LazyColumn`
 * keys on this value and a re-reduction of an overlapping page must produce the *same* id — a fresh UUID
 * would defeat the overlap dedup and a duplicate would crash the list. The `"history-"` prefix keeps this
 * namespace disjoint from the live lane's `"unrecognized-<counter>"`, which is minted from a per-process
 * counter and would otherwise collide by coincidence.
 */
private fun historyRowId(entryId: Long): String = "history-$entryId"

// ---- The merge ---------------------------------------------------------------------------------

/**
 * Put [rows] — one page's reduction — in front of this thread, skipping every row the thread already
 * holds (#645).
 *
 * **A prepend, never a re-sort.** The thread is in *arrival* order by deliberate choice (see
 * `applyToolUse`'s KDoc), so sorting the merged list by timestamp would reorder live rows that are
 * currently correct. A page is older than the live lane, so putting its rows in front is both the right
 * order and the one that leaves every existing row's relative position untouched.
 *
 * **Three join keys, one per row kind** — and each is the key `ThreadScreen`'s `LazyColumn` keys that row
 * kind on, because a row the merge admits is a row the renderer must be able to key uniquely:
 *
 *  - a [ThreadItem.MessageItem] joins on `message_id` alone. One key serves all three sources: a
 *    `message_id` (so a stored `send_message` collapses into the local echo of that same send, which
 *    minted the id client-side before it could appear in either lane), a `tool_use_id`, and a `turn_id`.
 *    Two sends of identical text carry different ids and stay two rows.
 *  - a [ThreadItem.SessionBoundary] joins on its `(previousSessionId, newSessionId, occurredAt)` identity
 *    — see [holdsBoundary] for why that is neither the pair alone nor structural equality.
 *  - a [ThreadItem.UnrecognizedMessage] joins on its [historyRowId]-derived id.
 *
 * A duplicate is **skipped, not merged in place.** The only overlap a walk can produce is the narrow
 * ask-versus-answer race the protocol names, and in that window the live lane owns the newer state and
 * will finish the row itself; updating in place would also break the existing rows' relative order.
 *
 * Never joins a [HistoryEntry.id] to an `event_id` — they are different sequences that both look like
 * small integers, and neither appears here at all.
 */
internal fun List<ThreadItem>.mergeHistoryRows(rows: List<ThreadItem>): List<ThreadItem> {
    if (rows.isEmpty()) return this
    val fresh = rows.filterNot { alreadyHolds(it) }
    return if (fresh.isEmpty()) this else fresh + this
}

private fun List<ThreadItem>.alreadyHolds(row: ThreadItem): Boolean =
    when (row) {
        is ThreadItem.MessageItem -> any { it is ThreadItem.MessageItem && it.message.id == row.message.id }
        is ThreadItem.SessionBoundary -> holdsBoundary(row)
        is ThreadItem.UnrecognizedMessage -> holdsUnrecognized(row.id)
    }

/**
 * Whether this thread already holds [boundary] — a boundary with the same
 * `(previousSessionId, newSessionId, occurredAt)` identity (#775).
 *
 * **One identity, three readers:** this history merge, the live lane's `appendSessionBoundary`, and the
 * list key `ThreadRow.listKey` gives a boundary row. The key encodes exactly these three fields, so any
 * two boundaries this predicate lets into one thread key distinctly, and the `LazyColumn`, which throws on
 * a duplicate key, cannot be handed two rows with one key.
 *
 * **Not the pair alone.** An honest daemon repeats a pair: `idle_evict` mirrors the evicted id into both
 * fields and a reactivated session keeps its id, so a session evicted twice sends `A->A` twice. Those are
 * two real delimiters with two instants, and #645's pair-only check dropped the older one.
 *
 * **Not structural equality either.** Two frames equal on the triple but differing in `reason` or
 * `workspaceCwd` would key alike, so the second is dropped — the fail-safe direction against a hostile
 * daemon: a missing delimiter, never a crashed thread. A stored entry and its live frame are the same
 * bytes (the daemon marshals one payload for both), so an overlap still collapses.
 */
internal fun List<ThreadItem>.holdsBoundary(boundary: ThreadItem.SessionBoundary): Boolean =
    any {
        it is ThreadItem.SessionBoundary &&
            it.previousSessionId == boundary.previousSessionId &&
            it.newSessionId == boundary.newSessionId &&
            it.occurredAt == boundary.occurredAt
    }

private fun List<ThreadItem>.holdsUnrecognized(id: String): Boolean = any { it is ThreadItem.UnrecognizedMessage && it.id == id }
