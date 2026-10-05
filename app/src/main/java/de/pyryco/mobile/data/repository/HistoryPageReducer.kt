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

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.AssistantDeltaPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import de.pyryco.mobile.data.network.BannerPayloadDto
import de.pyryco.mobile.data.network.CompactingPayloadDto
import de.pyryco.mobile.data.network.CompactionBoundaryPayloadDto
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.MessagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModelRefusalFallbackPayloadDto
import de.pyryco.mobile.data.network.ModelRefusalNoFallbackPayloadDto
import de.pyryco.mobile.data.network.SendMessagePayloadDto
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.ToolDeniedPayloadDto
import de.pyryco.mobile.data.network.ToolProgressPayloadDto
import de.pyryco.mobile.data.network.ToolResultPayloadDto
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import de.pyryco.mobile.data.network.TurnEndPayloadDto
import de.pyryco.mobile.data.network.UnrecognizedMessagePayloadDto
import de.pyryco.mobile.data.network.failed
import de.pyryco.mobile.data.network.isAttachmentIdShape
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.network.toDenial
import de.pyryco.mobile.data.network.toEvent
import de.pyryco.mobile.data.network.toMessage
import de.pyryco.mobile.data.network.toRow
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_ASSISTANT_DELTA
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_BACKGROUND_TASK_STARTED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_BACKGROUND_TASK_UPDATED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_BANNER
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_COMPACTING
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_COMPACTION_BOUNDARY
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MESSAGE
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MODEL_REFUSAL_FALLBACK
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_MODEL_REFUSAL_NO_FALLBACK
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
 * is [role], or -1 if none — the row guard shared by the id+role tool folds. The
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
 * The check is on the id alone, whatever row holds it (#1350): a `toolUseId` that a message or an
 * assistant segment already carries adds no row either, because the thread's list key ignores the role
 * and two rows with one key crash it. [timestamp] is the row clock (see this file's header); the tool
 * name / input, the input fields and the parent id (#810) are carried **verbatim** — never trimmed,
 * parsed, or logged.
 */
internal fun List<ThreadItem>.withToolUse(
    event: LiveSessionEvent.ToolUse,
    timestamp: Instant,
): List<ThreadItem> =
    if (any { it is ThreadItem.MessageItem && it.message.id == event.toolUseId }) {
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
 * use — is dropped, leaving no orphan half-row. The result summary and its count
 * ([LiveSessionEvent.ToolResult.resultDetail], #1316) are carried **verbatim**.
 *
 * **The first result wins** (#1316, desktop's `fillResult`): only a [ToolCallStatus.Running] row, or a
 * [ToolCallStatus.Denied] row with no result yet ([ToolCall.resultDetail] `null`), takes one. A later
 * frame returns this list unchanged, as does a result for a resolved row restored from the disk cache.
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
    val call = row.toolCall ?: return this
    val open = call.status == ToolCallStatus.Running || (call.status == ToolCallStatus.Denied && call.resultDetail == null)
    if (!open) return this
    val updated =
        row.copy(
            toolCall =
                call.copy(
                    output = event.resultSummary,
                    resultDetail = event.resultDetail,
                    status =
                        when {
                            call.status == ToolCallStatus.Denied -> ToolCallStatus.Denied
                            event.isError -> ToolCallStatus.Failed
                            else -> ToolCallStatus.Done
                        },
                    parentToolUseId = event.parentToolUseId.ifEmpty { call.parentToolUseId },
                    elapsedSeconds = null,
                ),
        )
    return toMutableList().apply { this[index] = ThreadItem.MessageItem(updated) }
}

/**
 * Mark the [Role.Tool] row [toolUseId] names as [ToolCallStatus.Denied] for a `tool_denied` (#811), in
 * place, attaching [denial]. The denial wins over any prior status, because the daemon's result-line
 * recovery can report a denial after the call's `tool_result` shipped. Output, input, parent and position
 * are untouched. **The first denial wins** (#1316): a row already holding a denial returns this list
 * unchanged. **No matching row, no-op:** a denial never adds a row. A denial closes the call, so it clears
 * the elapsed reading (#812).
 */
internal fun List<ThreadItem>.withToolDenied(
    toolUseId: String,
    denial: ToolDenial,
): List<ThreadItem> {
    val index = indexOfMessage(toolUseId, Role.Tool)
    if (index < 0) return this
    val row = (this[index] as ThreadItem.MessageItem).message
    val call = row.toolCall ?: return this
    if (call.denial != null) return this
    val updated = row.copy(toolCall = call.copy(status = ToolCallStatus.Denied, denial = denial, elapsedSeconds = null))
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
 * Fold one `assistant_delta` into the thread's assistant reply segments (#337, #1350), as desktop's
 * `appendDelta` does. A delta extends the **last** row when that row is a segment of the same turn;
 * anything else — a tool row, a user message, a boundary, or no row — makes it open a new segment at the
 * end, so text claude writes after a tool call draws below that call. Either way the segment streams.
 *
 * A new segment is keyed by [segmentKey]: the bare `turnId` for the one opened at `seq` 0, the turn's
 * first, and `"<turnId>#<seq>"` for a later one, so live and a replayed page derive the same key from
 * the same delta. Two guards keep that key unique in the thread, which the `LazyColumn` requires:
 *
 *  - a delta whose `seq` is not above the highest one any segment of its turn already holds changes
 *    nothing, so a repeated first delta after a tool row cannot mint the first segment's key again;
 *  - a delta whose new key any message row already carries (a daemon is free to choose a `turn_id` that
 *    spells another turn's `"<turnId>#<seq>"`, a tool id or a message id) changes nothing either. That
 *    costs text and never crashes the thread.
 *
 * **Arrival-order concatenation, by design** — see `applyAssistantDelta`'s KDoc for why that is correct
 * on the live lane. It holds for a page too, and for a different reason worth stating: a page is
 * reduced strictly oldest-first, so the log's own append order *is* the concatenation order. The delta
 * text is carried **verbatim** — never trimmed, parsed, or logged.
 *
 * [passOver] (#1558) names user rows the delta looks past when it picks the last row: the live lane's own
 * echoes parked behind a turn (#1636), which read below the running turn, so a reply the echo was typed
 * into stays one segment.
 * A page passes nothing.
 */
internal fun List<ThreadItem>.withAssistantDelta(
    event: LiveSessionEvent.AssistantDelta,
    timestamp: Instant,
    passOver: Set<String> = emptySet(),
): List<ThreadItem> {
    if (event.seq <= highestSeqOf(event.turnId)) return this
    val delta = SegmentDelta(event.seq, event.text.length)
    val anchor =
        indexOfLast { row ->
            row !is ThreadItem.BackgroundTaskLifecycle &&
                !(row is ThreadItem.MessageItem && row.message.role == Role.User && row.message.id in passOver)
        }
    val last = (getOrNull(anchor) as? ThreadItem.MessageItem)?.message
    val segment = last?.segment
    if (last != null && last.role == Role.Assistant && segment?.turnId == event.turnId) {
        val extended =
            last.copy(
                content = last.content + event.text,
                isStreaming = true,
                segment = segment.copy(deltas = segment.deltas + delta),
            )
        return toMutableList().apply { this[anchor] = ThreadItem.MessageItem(extended) }
    }
    val key = segmentKey(event.turnId, event.seq)
    if (any { it is ThreadItem.MessageItem && it.message.id == key }) return this
    return this +
        ThreadItem.MessageItem(
            Message(
                id = key,
                sessionId = "",
                role = Role.Assistant,
                content = event.text,
                timestamp = timestamp,
                isStreaming = true,
                segment = AssistantSegment(event.turnId, listOf(delta)),
            ),
        )
}

/**
 * The id of the segment of [turnId] that the delta numbered [openingSeq] opens (#1350): the bare turn id
 * for `seq` 0 — every turn's text starts there, so this is its first segment, and a row cached before
 * segments existed is keyed the same way — else `"<turnId>#<openingSeq>"`.
 */
internal fun segmentKey(
    turnId: String,
    openingSeq: Int,
): String = if (openingSeq == 0) turnId else "$turnId#$openingSeq"

/** The highest `seq` any assistant segment of [turnId] in this thread holds, or -1 when none does (#1350). */
private fun List<ThreadItem>.highestSeqOf(turnId: String): Int =
    maxOfOrNull { row ->
        val segment = (row as? ThreadItem.MessageItem)?.message?.segment
        if (segment?.turnId == turnId) segment.lastSeq else -1
    } ?: -1

/**
 * Finalize a turn's assistant text on `turn_end` (#337): flip **every** streaming segment of the turn
 * (#1350) — and a row keyed by the bare turn id with no segment record — to [Message.isStreaming]
 * `= false` in place, so the thread renders the completed reply as static markdown rather than the
 * streaming caret view. **No-op when no streaming row of the turn exists** — a tool-only or empty turn
 * carries no assistant text — and a duplicate changes nothing. `turn_end` carries no final text.
 *
 * A turn that did not end cleanly then leaves a [ThreadItem.StoppedTurn] stamped [occurredAt] at the end,
 * after the turn's last row (#1356), unless the thread already holds one for that turn ([holdsStoppedTurn]).
 * Both lanes come through here, so a live row and a history row of one turn are built by one rule.
 */
internal fun List<ThreadItem>.withFinalizedTurn(
    event: LiveSessionEvent.TurnEnd,
    occurredAt: Instant,
): List<ThreadItem> {
    val settled = withSettledTurns(setOf(event.turnId))
    val stopped = event.stoppedTurn(occurredAt) ?: return settled
    return if (settled.holdsStoppedTurn(stopped.turnId)) settled else settled + stopped
}

/**
 * [withFinalizedTurn] for every turn in [turnIds] in one pass (#1419): the projection settles each row of a
 * turn whose `turn_end` it has already seen when that row enters the thread late. Returns this very list
 * when no streaming row of those turns exists.
 */
internal fun List<ThreadItem>.withSettledTurns(turnIds: Set<String>): List<ThreadItem> {
    fun ofTurn(message: Message): Boolean =
        message.role == Role.Assistant &&
            message.isStreaming &&
            (message.segment?.turnId?.let { it in turnIds } ?: (message.id in turnIds))
    if (turnIds.isEmpty() || none { it is ThreadItem.MessageItem && ofTurn(it.message) }) return this
    return map { row ->
        if (row is ThreadItem.MessageItem && ofTurn(row.message)) ThreadItem.MessageItem(row.message.copy(isStreaming = false)) else row
    }
}

/**
 * This thread with every streaming message row but the last one settled (#1350): a segment stops
 * streaming the moment any row follows it, whichever lane or merge put that row there, so only the
 * thread's newest segment can draw as in progress. Returns this very list when nothing changes.
 */
internal fun List<ThreadItem>.withOnlyLastRowStreaming(): List<ThreadItem> {
    val lastVisible = indexOfLast { it !is ThreadItem.BackgroundTaskLifecycle }
    val stale = withIndex().any { (index, row) -> index != lastVisible && row is ThreadItem.MessageItem && row.message.isStreaming }
    if (!stale) return this
    return mapIndexed { index, row ->
        if (index != lastVisible && row is ThreadItem.MessageItem && row.message.isStreaming) {
            ThreadItem.MessageItem(row.message.copy(isStreaming = false))
        } else {
            row
        }
    }
}

/**
 * Where one conversation's compaction fold stands (#1358), desktop's `compacting` / `pendingCompaction`:
 * whether a compaction is open, and the `occurredAt` of the divider its falling edge drew that a later
 * `compaction_boundary` fills in. The live lane keeps one per conversation; a history reduction keeps one
 * for the page.
 */
internal data class CompactionFold(
    val compacting: Boolean = false,
    val pending: Instant? = null,
)

/**
 * Fold one `compacting` edge (#1358), desktop's `compacting` arm. An edge that repeats the fold's state
 * changes nothing, so a falling edge with no rising edge before it draws nothing. A rising edge opens the
 * compaction and forgets any pending divider. A falling edge appends a divider stamped with its own `ts`,
 * unless the thread already holds one there ([holdsCompactionBoundary]), and leaves it pending unless
 * [failed]. The divider carries no counts: those arrive on the boundary frame.
 */
internal fun List<ThreadItem>.withCompactingEdge(
    fold: CompactionFold,
    active: Boolean,
    failed: Boolean,
    occurredAt: Instant,
): Pair<List<ThreadItem>, CompactionFold> {
    if (active == fold.compacting) return this to fold
    if (active) return this to CompactionFold(compacting = true)
    val row = ThreadItem.CompactionBoundary(preTokens = null, postTokens = null, manual = false, occurredAt = occurredAt, failed = failed)
    val rows = if (holdsCompactionBoundary(row)) this else this + row
    return rows to CompactionFold(compacting = false, pending = if (failed) null else occurredAt)
}

/**
 * Fold one `compaction_boundary` (#874, #1358), desktop's `compactionBoundary` arm. It replaces the pending
 * divider **in place**, so the row keeps the edge divider's position and takes the boundary's `ts`, counts
 * and trigger. With no pending divider it appends, unless the thread already holds its `ts`. When the
 * thread already holds the boundary's `ts` while a divider is pending, a history page brought this
 * compaction's filled-in row first, so the pending one is removed rather than given a second row with that
 * key. The fold leaves with nothing pending.
 */
internal fun List<ThreadItem>.withCompactionBoundary(
    fold: CompactionFold,
    row: ThreadItem.CompactionBoundary,
): Pair<List<ThreadItem>, CompactionFold> {
    val next = fold.copy(pending = null)
    val pending = fold.pending?.let { at -> indexOfFirst { it is ThreadItem.CompactionBoundary && it.occurredAt == at } } ?: -1
    val heldElsewhere =
        withIndex().any { (index, item) ->
            index != pending &&
                item is ThreadItem.CompactionBoundary &&
                item.occurredAt == row.occurredAt
        }
    val rows =
        when {
            heldElsewhere && pending >= 0 -> filterIndexed { index, _ -> index != pending }
            heldElsewhere -> this
            pending >= 0 -> toMutableList().apply { this[pending] = row }
            else -> this + row
        }
    return rows to next
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
 * prompt or restart a finished status indicator. The one exception is `compacting`, and only toward the
 * thread (#1358): its edges fold the same compaction divider the live lane draws ([withCompactionEntry]),
 * through a page-local [CompactionFold], never the status indicator's state.
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
 * [interactive] mirrors the live lane's `CAPABILITY_INTERACTIVE` gate arm-for-arm: the structured
 * types are gated exactly as their live twins are and `message` is ungated exactly as its twin is. The
 * daemon's `request_history` handler carries no such gate, so without this the client's fail-closed
 * posture would have a hole the live lane does not have.
 */
internal fun reduceHistoryPage(
    entries: List<HistoryEntry>,
    interactive: Boolean,
): List<ThreadItem> {
    var compaction = CompactionFold()
    return entries.asReversed().fold(emptyList()) { rows, entry ->
        if (interactive && (entry.type == TYPE_COMPACTING || entry.type == TYPE_COMPACTION_BOUNDARY)) {
            rows.withCompactionEntry(entry, compaction).let { (next, fold) ->
                compaction = fold
                next
            }
        } else {
            rows.withHistoryEntry(entry, interactive)
        }
    }
}

/**
 * Fold one stored `compacting` or `compaction_boundary` [entry] through the live lane's folds (#874, #1358),
 * stamped with the entry's own `ts`, which the daemon also handed the live envelope, so a divider on both
 * lanes joins on one identity. A malformed entry costs only itself and leaves [fold] as it was. Logs nothing.
 */
private fun List<ThreadItem>.withCompactionEntry(
    entry: HistoryEntry,
    fold: CompactionFold,
): Pair<List<ThreadItem>, CompactionFold> =
    try {
        if (entry.type == TYPE_COMPACTING) {
            MobileJson.decodeFromJsonElement<CompactingPayloadDto>(entry.payload).let { dto ->
                withCompactingEdge(fold, dto.active, dto.failed(), entry.timestamp)
            }
        } else {
            withCompactionBoundary(
                fold,
                MobileJson.decodeFromJsonElement<CompactionBoundaryPayloadDto>(entry.payload).toRow(entry.timestamp),
            )
        }
    } catch (e: IllegalArgumentException) {
        this to fold
    }

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
            // A user turn the daemon logged itself, the peer's among them, names its files as a send does
            // (#1020). The ids mean something only on a user turn, so an assistant row never takes them.
            TYPE_MESSAGE ->
                MobileJson.decodeFromJsonElement<MessagePayloadDto>(entry.payload).let { dto ->
                    val message = dto.toMessage(timestamp = entry.timestamp, sessionId = "")
                    withMessage(
                        if (message.role == Role.User) {
                            message.copy(attachments = storedAttachmentReferences(dto.attachmentIds))
                        } else {
                            message
                        },
                    )
                }
            // A stored inbound `send_message` — the operator's own turn, which the live lane never
            // echoes back (the ack carries nothing), so the log is its only retention. `role` is not a
            // wire field on this payload: the sender is the operator by construction. Its `attachment_ids`
            // become references with no hints (#983): the wire names no file for them.
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
                            attachments = storedAttachmentReferences(dto.attachmentIds),
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
                        is LiveSessionEvent.TurnEnd -> withFinalizedTurn(event, entry.timestamp)
                        else -> this
                    }
                }
            // Invisible lifecycle evidence follows the same interactive gate as its live twin.
            TYPE_BACKGROUND_TASK_STARTED ->
                if (!interactive) {
                    this
                } else {
                    withBackgroundTaskStarted(
                        MobileJson.decodeFromJsonElement<BackgroundTaskStartedPayloadDto>(entry.payload),
                        entry.timestamp,
                    )
                }
            TYPE_BACKGROUND_TASK_UPDATED ->
                if (!interactive) {
                    this
                } else {
                    withBackgroundTaskUpdated(
                        MobileJson.decodeFromJsonElement<BackgroundTaskUpdatedPayloadDto>(entry.payload),
                        entry.timestamp,
                    )
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
            // Stamped with the entry's own ts, which the daemon also handed the live envelope, so a banner
            // on both lanes joins on one identity (#873). stops_turn is decoded and never acted on.
            TYPE_BANNER ->
                if (!interactive) {
                    this
                } else {
                    MobileJson
                        .decodeFromJsonElement<BannerPayloadDto>(entry.payload)
                        .toRow(occurredAt = entry.timestamp)
                        .let { row -> if (holdsBanner(row)) this else this + row }
                }
            // `compaction_boundary` and `compacting` fold in reduceHistoryPage (#1358), which carries their
            // fold across the page; without `interactive` both land in the `else` below.
            // Stamped with the entry's own ts, as the banner arm above, and decoded by the stored type, which
            // is the only thing that tells the two frames apart, so a refusal on both lanes joins once (#875).
            TYPE_MODEL_REFUSAL_FALLBACK ->
                if (!interactive) {
                    this
                } else {
                    MobileJson
                        .decodeFromJsonElement<ModelRefusalFallbackPayloadDto>(entry.payload)
                        .toRow(occurredAt = entry.timestamp)
                        .let { row -> if (holdsModelRefusal(row)) this else this + row }
                }
            TYPE_MODEL_REFUSAL_NO_FALLBACK ->
                if (!interactive) {
                    this
                } else {
                    MobileJson
                        .decodeFromJsonElement<ModelRefusalNoFallbackPayloadDto>(entry.payload)
                        .toRow(occurredAt = entry.timestamp)
                        .let { row -> if (holdsModelRefusal(row)) this else this + row }
                }
            // Every other stored type — the state frames, the modal pair, the control verbs, and any
            // type a future daemon invents. See this function's KDoc: no row, no failure.
            else -> this
        }
    } catch (e: IllegalArgumentException) {
        this
    }

/**
 * The references a stored user turn names, a `send_message` (#983) or a user `message` (#1020), in wire order: every id that is not the published
 * lowercase-UUIDv4 shape is dropped and the rest kept, a repeat keeps its first position, and at most
 * [MessageAttachmentIds.MAX] survive, the bound the daemon enforced on the send. A replayed entry is not
 * re-validated by the daemon, so this is the only check between it and the thread. The live `message`
 * arm (#1351) runs the same check on a pushed user turn.
 */
internal fun storedAttachmentReferences(ids: List<String>?): List<MessageAttachment> =
    ids
        .orEmpty()
        .filter(::isAttachmentIdShape)
        .distinct()
        .take(MessageAttachmentIds.MAX)
        .map { MessageAttachment(it) }

/**
 * The `turn_id` of every `turn_end` among [entries] (#1419), behind the same [interactive] gate the
 * reduction applies to it. A malformed entry costs only itself, as in [withHistoryEntry].
 */
internal fun endedTurnIds(
    entries: List<HistoryEntry>,
    interactive: Boolean,
): Set<String> {
    if (!interactive) return emptySet()
    return entries.mapNotNullTo(HashSet()) { entry ->
        if (entry.type != TYPE_TURN_END) return@mapNotNullTo null
        try {
            (decodeLiveEvent(entry) as? LiveSessionEvent.TurnEnd)?.turnId
        } catch (e: IllegalArgumentException) {
            null
        }
    }
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
 * **One join key per row kind** — and each is the key `ThreadScreen`'s `LazyColumn` keys that row
 * kind on, because a row the merge admits is a row the renderer must be able to key uniquely:
 *
 *  - a [ThreadItem.MessageItem] joins on `message_id` alone. One key serves all three sources: a
 *    `message_id` (so a stored `send_message` collapses into the local echo of that same send, which
 *    minted the id client-side before it could appear in either lane), a `tool_use_id`, and a `turn_id`.
 *    Two sends of identical text carry different ids and stay two rows.
 *  - a [ThreadItem.SessionBoundary] joins on its `(previousSessionId, newSessionId, occurredAt)` identity
 *    — see [holdsBoundary] for why that is neither the pair alone nor structural equality.
 *  - a [ThreadItem.UnrecognizedMessage] joins on its [historyRowId]-derived id.
 *  - a [ThreadItem.Banner] joins on its `ts` — see [holdsBanner].
 *  - a [ThreadItem.CompactionBoundary] joins on its `ts` — see [holdsCompactionBoundary].
 *  - a [ThreadItem.ModelRefusal] joins on its frame type and `ts` — see [holdsModelRefusal].
 *  - a [ThreadItem.StoppedTurn] joins on its `turn_id` — see [holdsStoppedTurn].
 *
 * A duplicate is **skipped, not merged in place.** The only overlap a walk can produce is the narrow
 * ask-versus-answer race the protocol names, and in that window the live lane owns the newer state and
 * will finish the row itself; updating in place would also break the existing rows' relative order. The
 * one exception is an attachment reference's missing hints, which a skipped twin fills without moving
 * anything (#983, see [withAttachmentHintsFrom]).
 * Lifecycle twins also fill missing launch hints in place. Fresh lifecycle evidence uses the page's
 * preceding retained neighbour as an anchor, since ordinary backfill can already hold both sides of a
 * launch or finish without holding the marker itself. Existing rows keep their relative order.
 *
 * Never joins a [HistoryEntry.id] to an `event_id` — they are different sequences that both look like
 * small integers, and neither appears here at all.
 *
 * **Assistant text is deduped by `(turnId, seq)`, not by id alone** (#1350). Where this thread holds
 * segments of a turn, a page's segment of that turn keeps only the deltas below the lowest `seq` the
 * thread holds ([olderThan]): the two lanes can split one turn into different segments, because the local
 * echo of a send lands at the ack while the daemon logs the message at delivery, so the ids need not
 * match. A segment cut by the seam is then joined back ([withJoinedSegments]): a page cut by count or
 * bytes can end inside an assistant segment the thread's first row continues, and the newest page can
 * hold the start of a segment the live lane joined halfway through.
 */
internal fun List<ThreadItem>.mergeHistoryRows(rows: List<ThreadItem>): List<ThreadItem> {
    if (rows.isEmpty()) return this
    val heads = segmentHeads()
    val fresh = rows.filterNot { alreadyHolds(it) }.mapNotNull { it.olderThan(heads) }
    val kept = withAttachmentHintsFrom(rows).withBackgroundTaskHintsFrom(rows)
    if (fresh.isEmpty()) return kept.withBackgroundTaskLaunches()
    val ordinary = fresh.filterNot { it is ThreadItem.BackgroundTaskLifecycle }
    return (ordinary + kept)
        .withHistoryLifecyclePositions(rows, fresh.filterIsInstance<ThreadItem.BackgroundTaskLifecycle>())
        .withoutSegmentsOfWholeTurns()
        .withJoinedSegments()
        .withBackgroundTaskLaunches()
}

/** Insert fresh evidence beside its retained page neighbours; held markers never move on replay. */
private fun List<ThreadItem>.withHistoryLifecyclePositions(
    page: List<ThreadItem>,
    fresh: List<ThreadItem.BackgroundTaskLifecycle>,
): List<ThreadItem> {
    if (fresh.isEmpty()) return this
    val pending = fresh.mapTo(mutableSetOf()) { it.joinIdentity() }
    val heldAt = HashMap<Any, Int>(size)
    forEachIndexed { index, row -> heldAt.putIfAbsent(row.joinIdentity(), index) }
    // Slot i precedes row i. Leading evidence waits for the page's first retained neighbour.
    val slots = mutableMapOf<Int, MutableList<ThreadItem>>()
    val leading = mutableListOf<ThreadItem>()
    var slot: Int? = null
    for (row in page) {
        val identity = row.joinIdentity()
        if (row is ThreadItem.BackgroundTaskLifecycle && pending.remove(identity)) {
            val after = slot
            if (after == null) leading += row else slots.getOrPut(after) { mutableListOf() } += row
        } else {
            heldAt[identity]?.let { index ->
                if (leading.isNotEmpty()) {
                    slots.getOrPut(index) { mutableListOf() }.addAll(leading)
                    leading.clear()
                }
                slot = index + 1
            }
        }
    }
    if (leading.isNotEmpty()) slots.getOrPut(0) { mutableListOf() }.addAll(leading)
    return buildList {
        this@withHistoryLifecyclePositions.forEachIndexed { index, row ->
            slots[index]?.let(::addAll)
            add(row)
        }
        slots[this@withHistoryLifecyclePositions.size]?.let(::addAll)
    }
}

/**
 * Put [cached] — the thread cache's rows — under this live thread (#797), keeping each row only the cache
 * holds beside its neighbours (#983).
 *
 * The join and the hint fill are [mergeHistoryRows]'s. What differs is where a cache-only row goes: after
 * the live copy of the nearest row above it in [cached] that this thread holds, or in front of everything
 * when there is no such row — the older rows a reconnect's newest page does not reach, and every row when
 * the page does not overlap the cache at all. An attachment offer needs this: the daemon never replays it,
 * so after a reconnect or a restart it is a cache-only row in the middle of a page the live side re-delivers,
 * and a prepend would move the file above the message that asked for it, then write that order back.
 *
 * The history walk keeps [mergeHistoryRows]: its skip-and-prepend is the deliberate answer to the
 * ask-versus-answer race described there.
 *
 * Assistant text is deduped by `(turnId, seq)` as in [mergeHistoryRows] (#1350): a cached segment of a turn
 * this thread holds keeps only its deltas below the thread's lowest `seq` for that turn, and goes no lower
 * than right above the thread's first segment of it. The cache holds the turn in live order and a
 * reconnect's newest page holds it in log order, and where a send's echo sat between them, the cached
 * neighbour would otherwise put the older text below newer text. That cached head is then joined to the
 * segment it continues ([withJoinedSegments]). A cached row from before segments existed holds its whole
 * turn and keeps it, and the thread's segments of that turn give way ([withoutSegmentsOfWholeTurns]).
 */
internal fun List<ThreadItem>.mergeCachedRows(cached: List<ThreadItem>): List<ThreadItem> {
    if (cached.isEmpty()) return this
    val kept = withAttachmentHintsFrom(cached).withBackgroundTaskHintsFrom(cached)
    val heads = segmentHeads()
    // Built once so the merge stays linear in the cached base, which holds a thread's whole history (#1353).
    val heldAt = HashMap<Any, Int>(size)
    forEachIndexed { index, row -> heldAt.putIfAbsent(row.joinIdentity(), index) }
    // Slot i is in front of this thread's row i; slot size is after its last row.
    val slots = mutableMapOf<Int, MutableList<ThreadItem>>()
    var slot = 0
    for (row in cached) {
        val held = heldAt[row.joinIdentity()] ?: -1
        if (held >= 0) {
            slot = held + 1
            continue
        }
        val older = row.olderThan(heads) ?: continue
        val ceiling = (older as? ThreadItem.MessageItem)?.message?.segment?.let { heads[it.turnId]?.index } ?: slot
        slots.getOrPut(minOf(slot, ceiling)) { mutableListOf() } += older
    }
    if (slots.isEmpty()) return kept.withBackgroundTaskLaunches()
    return buildList {
        kept.forEachIndexed { index, row ->
            slots[index]?.let(::addAll)
            add(row)
        }
        slots[kept.size]?.let(::addAll)
    }.withoutSegmentsOfWholeTurns().withJoinedSegments().withBackgroundTaskLaunches()
}

/** Where a turn's text starts in a thread (#1350): the lowest `seq` its segments hold, and its first segment's row index. */
private class SegmentHead(
    val lowestSeq: Int,
    val index: Int,
)

/** The [SegmentHead] of each turn this thread holds assistant segments of, by turn id (#1350). */
private fun List<ThreadItem>.segmentHeads(): Map<String, SegmentHead> {
    val heads = HashMap<String, SegmentHead>()
    forEachIndexed { index, row ->
        val segment = (row as? ThreadItem.MessageItem)?.message?.segment ?: return@forEachIndexed
        val head = heads[segment.turnId]
        heads[segment.turnId] = SegmentHead(minOf(head?.lowestSeq ?: Int.MAX_VALUE, segment.firstSeq), head?.index ?: index)
    }
    return heads
}

/**
 * This row as it may join a thread with [heads] (#1350): an assistant segment of a turn the thread holds keeps
 * only its deltas below the thread's lowest `seq` for that turn, and is `null` when none are. Every other row
 * is returned unchanged.
 *
 * Where the thread holds a turn's text, it holds it from that `seq` on: a page or the cache adds only the
 * older text in front of it. Deltas are recorded in increasing `seq`, so what is kept is a prefix of the
 * record and of the content. Its id, that of its opening delta, stays. The cut is clamped to the content,
 * so a record that does not match its text cuts short rather than throwing.
 */
private fun ThreadItem.olderThan(heads: Map<String, SegmentHead>): ThreadItem? {
    val message = (this as? ThreadItem.MessageItem)?.message ?: return this
    val segment = message.segment ?: return this
    val below = heads[segment.turnId]?.lowestSeq ?: return this
    val deltas = segment.deltas.takeWhile { it.seq < below }
    if (deltas.size == segment.deltas.size) return this
    if (deltas.isEmpty()) return null
    val cut = deltas.sumOf { it.length }.coerceIn(0, message.content.length)
    return ThreadItem.MessageItem(message.copy(content = message.content.substring(0, cut), segment = segment.copy(deltas = deltas)))
}

/**
 * This thread without the segments of any turn a whole-turn row also holds (#1350), or this very list when
 * there are none. A whole-turn row is an assistant row with no segment record, keyed by its turn's id: one
 * cached before segments existed, which holds every delta of its ended turn, so a segment of that turn
 * would draw its text twice. It keeps its place and its text, as drawn before #1350, and only removes rows.
 */
private fun List<ThreadItem>.withoutSegmentsOfWholeTurns(): List<ThreadItem> {
    val whole =
        mapNotNullTo(HashSet()) { row ->
            (row as? ThreadItem.MessageItem)?.message?.takeIf { it.role == Role.Assistant && it.segment == null }?.id
        }
    if (whole.isEmpty()) return this
    val kept =
        filterNot { row ->
            (row as? ThreadItem.MessageItem)
                ?.message
                ?.segment
                ?.turnId
                ?.let { it in whole } == true
        }
    return if (kept.size == size) this else kept
}

/**
 * This thread with each pair of adjacent assistant segments of one turn joined into one row (#1350), or
 * this very list when no pair joins.
 *
 * A fold never leaves two such rows side by side: a delta extends the last row when it is its turn's
 * segment. So a merged list that has them holds **one segment cut by a seam** — a page boundary, the
 * newest page meeting the live lane, or the cache meeting either — whose later half opened under the
 * first `seq` it saw. See [joinSegments] for the rule. Joining only ever removes the later row and keeps
 * the earlier row's id, already unique in the list, so it cannot repeat a list key.
 */
private fun List<ThreadItem>.withJoinedSegments(): List<ThreadItem> {
    var joined = false
    val out = ArrayList<ThreadItem>(size)
    var lastVisible = -1
    for (row in this) {
        if (row is ThreadItem.BackgroundTaskLifecycle) {
            out += row
            continue
        }
        val older = (out.getOrNull(lastVisible) as? ThreadItem.MessageItem)?.message
        val newer = (row as? ThreadItem.MessageItem)?.message
        val merged = if (older != null && newer != null) joinSegments(older, newer) else null
        if (merged != null) {
            out[lastVisible] = ThreadItem.MessageItem(merged)
            joined = true
        } else {
            out += row
            lastVisible = out.lastIndex
        }
    }
    return if (joined) out else this
}

/**
 * [older] and [newer], two adjacent rows, as one assistant segment (#1350), or `null` when they are not
 * halves of one: both must be assistant rows recording segments of one turn, and [newer] must have opened
 * after [older] did. A row with no record — a `message` frame's, or one cached before segments existed —
 * never joins.
 *
 * The joined row keeps [older]'s id, time and session, since [older] saw the segment's earlier opener.
 * It takes [older]'s deltas and then only [newer]'s deltas past [older]'s last `seq`, so a delta both
 * halves hold — an entry the protocol delivers on both lanes when it lands between ask and answer — is
 * counted once. It streams as [newer] does: the later half knows whether the turn has ended.
 *
 * The cut is located from [newer]'s own record and clamped to its content, so a record that does not
 * match its text cuts short rather than throwing.
 */
private fun joinSegments(
    older: Message,
    newer: Message,
): Message? {
    val first = older.segment ?: return null
    val second = newer.segment ?: return null
    if (older.role != Role.Assistant || newer.role != Role.Assistant || first.turnId != second.turnId) return null
    if (second.firstSeq <= first.firstSeq) return null
    val skipped = second.deltas.takeWhile { it.seq <= first.lastSeq }
    val cut = skipped.sumOf { it.length }.coerceIn(0, newer.content.length)
    return older.copy(
        content = older.content + newer.content.substring(cut),
        isStreaming = newer.isStreaming,
        segment = first.copy(deltas = first.deltas + second.deltas.drop(skipped.size)),
    )
}

/**
 * This thread with each attachment reference's missing hints filled from a skipped twin (#983), or this very
 * list when there is nothing to fill. A twin is a message in [rows] with the same `message_id`; a hint is
 * taken only from its reference with the same attachment id, and only where this thread's hint is `null`, so
 * a known name is never replaced. Rows keep their positions.
 *
 * The merge runs in both directions, which is why this is needed: a history page merged into a thread
 * holding the local echo finds nothing to fill, but the cache's rows merged under a reconnected live
 * thread — whose copy of a sent message came back from history without names — give those names back.
 */
private fun List<ThreadItem>.withAttachmentHintsFrom(rows: List<ThreadItem>): List<ThreadItem> {
    val twins =
        rows
            .filterIsInstance<ThreadItem.MessageItem>()
            .filter { it.message.attachments.isNotEmpty() }
            .associate { it.message.id to it.message.attachments }
    if (twins.isEmpty()) return this
    var changed = false
    val filled =
        map { row ->
            val twin = (row as? ThreadItem.MessageItem)?.let { twins[it.message.id] } ?: return@map row
            val attachments = row.message.attachments.map { it.withHintsFrom(twin) }
            if (attachments == row.message.attachments) {
                row
            } else {
                changed = true
                ThreadItem.MessageItem(row.message.copy(attachments = attachments))
            }
        }
    return if (changed) filled else this
}

private fun MessageAttachment.withHintsFrom(twin: List<MessageAttachment>): MessageAttachment {
    if (displayName != null && mimeType != null) return this
    val source = twin.firstOrNull { it.attachmentId == attachmentId } ?: return this
    return copy(displayName = displayName ?: source.displayName, mimeType = mimeType ?: source.mimeType)
}

private fun List<ThreadItem>.alreadyHolds(row: ThreadItem): Boolean =
    when (row) {
        is ThreadItem.BackgroundTaskLifecycle -> any { it is ThreadItem.BackgroundTaskLifecycle && it.samePosition(row) }
        is ThreadItem.MessageItem -> any { it is ThreadItem.MessageItem && it.message.id == row.message.id }
        is ThreadItem.SessionBoundary -> holdsBoundary(row)
        is ThreadItem.UnrecognizedMessage -> holdsUnrecognized(row.id)
        is ThreadItem.Banner -> holdsBanner(row)
        is ThreadItem.CompactionBoundary -> holdsCompactionBoundary(row)
        is ThreadItem.ModelRefusal -> holdsModelRefusal(row)
        is ThreadItem.StoppedTurn -> holdsStoppedTurn(row.turnId)
    }

/**
 * The identity [alreadyHolds] joins this row on, as a hashable key (#1353): two rows hold each other exactly
 * when their identities are equal. The kind leads each key, so rows of different kinds never collide.
 */
private fun ThreadItem.joinIdentity(): Any =
    when (this) {
        is ThreadItem.BackgroundTaskLifecycle -> listOf("background-task", taskId, terminal != null)
        is ThreadItem.MessageItem -> listOf("message", message.id)
        is ThreadItem.SessionBoundary -> listOf("boundary", previousSessionId, newSessionId, occurredAt)
        is ThreadItem.UnrecognizedMessage -> listOf("unrecognized", id)
        is ThreadItem.Banner -> listOf("banner", occurredAt)
        is ThreadItem.CompactionBoundary -> listOf("compaction", occurredAt)
        is ThreadItem.ModelRefusal -> listOf("refusal", fallbackModel != null, occurredAt)
        is ThreadItem.StoppedTurn -> listOf("stopped", turnId)
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

/**
 * Whether this thread already holds a banner stamped [banner]'s `ts` (#873) — the protocol's `(type, ts)`
 * join key, with the type implied by [ThreadItem.Banner].
 *
 * **One identity, three readers:** this history merge, the live lane's `appendBanner`, and the list key
 * `ThreadRow.listKey` gives a banner row, so two banners this predicate lets into one thread never share a
 * key. Two *different* banners on one instant lose the second — the fail-safe direction against a hostile
 * daemon, a missing notice rather than a crashed thread; the daemon's single emit path cannot produce one.
 */
internal fun List<ThreadItem>.holdsBanner(banner: ThreadItem.Banner): Boolean =
    any { it is ThreadItem.Banner && it.occurredAt == banner.occurredAt }

/**
 * Whether this thread already holds a compaction boundary stamped [boundary]'s `ts` (#874) — the protocol's
 * `(type, ts)` join key, with the type implied by [ThreadItem.CompactionBoundary].
 *
 * **One identity, three readers:** this history merge, the live and history compaction folds
 * (`withCompactingEdge`, `withCompactionBoundary`), and the
 * list key `ThreadRow.listKey` gives the divider, so two dividers this predicate lets into one thread never
 * share a key. Two *different* boundaries on one instant lose the second — the fail-safe direction, a missing
 * divider rather than a crashed thread; the daemon stamps one `ts` per compaction.
 */
internal fun List<ThreadItem>.holdsCompactionBoundary(boundary: ThreadItem.CompactionBoundary): Boolean =
    any { it is ThreadItem.CompactionBoundary && it.occurredAt == boundary.occurredAt }

/**
 * Whether this thread already holds a refusal of [refusal]'s frame type stamped with its `ts` (#875) — the
 * protocol's `(type, ts)` join key, the type being whether a fallback model is present.
 *
 * **One identity, three readers:** this history merge, the live lane's `appendModelRefusal`, and the list key
 * `ThreadRow.listKey` gives the row, so two refusals this predicate lets into one thread never share a key.
 * Two *different* refusals of one type on one instant lose the second — the fail-safe direction, a missing
 * row rather than a crashed thread; the daemon stamps one `ts` per refusal.
 */
internal fun List<ThreadItem>.holdsModelRefusal(refusal: ThreadItem.ModelRefusal): Boolean =
    any {
        it is ThreadItem.ModelRefusal &&
            (it.fallbackModel != null) == (refusal.fallbackModel != null) &&
            it.occurredAt == refusal.occurredAt
    }

/**
 * Whether this thread already holds the stopped-turn row of [turnId] (#1356): one turn ends once, so its id
 * is the row's identity whichever lane or instant brought it.
 *
 * **One identity, three readers:** this history merge, [withFinalizedTurn] on both lanes, and the list key
 * `ThreadRow.listKey` gives the row, so two stopped rows this predicate lets into one thread never share a
 * key. A replayed `turn_end` whose fields differ loses to the row already held — the fail-safe direction.
 */
internal fun List<ThreadItem>.holdsStoppedTurn(turnId: String): Boolean = any { it is ThreadItem.StoppedTurn && it.turnId == turnId }
