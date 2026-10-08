package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem

/**
 * One rendered row of the thread (#782) — the unit [ThreadScreen]'s list walks, produced by
 * [foldQueuedRows] from the thread's items and the daemon's queued backlog.
 *
 * The two arms are one message's two states, not two kinds of content: a send the daemon parked draws
 * as [Queued] where the thread's items hold its echo — below the turn it waits behind, which the
 * repository's read sees to (#1558) — and the same row becomes [Delivered] when the next snapshot no
 * longer holds it.
 */
sealed interface ThreadRow {
    /** A row the daemon has run, or a row that is not a message at all (a boundary, an unrecognized frame). */
    data class Delivered(
        val item: ThreadItem,
        val agentBlockId: String? = null,
    ) : ThreadRow

    /** Launch-location affordance for a loaded, joined background Agent. */
    data class AgentStartMarker(
        val agentId: String,
        val description: String,
        val finished: Boolean,
    ) : ThreadRow

    /**
     * A message the daemon's last `queue_state` snapshot reported still waiting.
     *
     * @param queuedMessageId The backlog item's `queued_msg_id` — what `dropQueuedMessage` addresses.
     * @param text What the row renders: the correlated echo's own content when matched, the snapshot
     *   entry's text when not.
     * @param echoId The [de.pyryco.mobile.data.model.Message.id] of the echo this item correlated to,
     *   or `null` for a backlog item this device minted no echo for. **Used for the list key and
     *   equality only** — never rendered, never logged, never sent. Keying on it is sound where the
     *   snapshot's own `message_id` would not be (see [QueuedMessage.messageId]): it is read out of the
     *   thread's items, which `withMessage`'s upsert keeps unique, and [foldQueuedRows] lets at most
     *   one row claim a given echo.
     */
    data class Queued(
        val queuedMessageId: Long,
        val text: String,
        val echoId: String?,
    ) : ThreadRow

    /**
     * A run of adjacent tools or a joined Agent block, drawn as one "Using tools: N" header (#1635), produced by
     * [foldToolRuns]. Render-time only, like the queued fold.
     *
     * @param runId The run's first tool row's [Message.id] — its `tool_use_id`. New tool rows join a run
     *   at its end, so the id holds while the run grows and an expanded run stays expanded.
     * @param tools The run's tool messages, in thread order; the header counts them and reads their status.
     * @param expanded Whether the run's own rows follow the header.
     */
    data class ToolRun(
        val runId: String,
        val tools: List<Message>,
        val expanded: Boolean,
    ) : ThreadRow
}

/**
 * Join the thread's [items] against the daemon's last `queue_state` snapshot ([queued]) into one row
 * list — the fold that makes a message sent mid-turn draw **once** (#782).
 *
 * Two independent writers used to draw it. `RemoteConversationRepository.sendMessage` appends an
 * optimistic user echo for every send and cannot know whether the daemon ran the message or parked it;
 * the daemon parks it, pushes a `queue_state` snapshot, and the shipped foot-of-list backlog section
 * drew a second, near-identical row below the thread. This joins them on the correlation key #781
 * carried through ([QueuedMessage.messageId] ↔ the echo's [de.pyryco.mobile.data.model.Message.id],
 * which `sendMessage` stamps from the same minted value) and yields one list.
 *
 * **The fold is render-time, and that is an architectural line rather than a preference.** `queue_state`
 * is daemon state (SSOT pyrycode#720), not part of claude's turn stream, so it never folds into the
 * thread's message reducer and keeps its own projection. What changed is only that the view reads both.
 * It is also what makes a replacing snapshot free — every row is re-derived here on each render, so
 * there is no reconciliation state to hold and nothing to orphan when a snapshot (including the empty
 * one a reconnect clears to) replaces the backlog.
 *
 * Contract, in the order it matters — shared with `pyrycode-desktop`'s `foldQueuedRows.ts`, which
 * states the same five rules for the sibling client:
 *
 * 1. **Every item appears exactly once, at its own index, in order.** Nothing is reordered, dropped or
 *    duplicated; the fold only decides, per index, whether an item renders [ThreadRow.Delivered] or
 *    [ThreadRow.Queued]. That is what makes "one row per message, where it was sent" structural.
 * 2. **One backlog item correlates to at most one echo, and an echo to at most one item** — a greedy
 *    one-to-one assignment walking the snapshot in order against an index built once from [items],
 *    consuming each echo as it is claimed. Two equal texts under distinct ids therefore claim two
 *    distinct rows; a snapshot repeating an id claims first-come and leaves the second unmatched rather
 *    than double-marking a row. The index holds a *list* of positions per key even though
 *    `HistoryPageReducer.withMessage`'s upsert makes the id unique today: this defence must not depend
 *    on an invariant enforced in another file.
 * 3. **Only user-authored message rows are candidates** ([userEchoId]). This is the guard that stops a
 *    hostile `queue_state` putting the operator's own queued treatment, and its drop control, onto
 *    daemon-authored content — an assistant bubble, a tool row, an unrecognized-output row.
 * 4. **Only a non-empty id on both sides participates.** `""` correlates with nothing, matching
 *    [QueuedMessage.messageId]'s contract, and **text is never compared**. This is the first guard in
 *    this path, not a redundant second one: #781 put its empty-id rule in `dropQueuedMessage`, which
 *    this consumer does not reach.
 * 5. **A backlog item that claims no echo becomes its own row at the tail**, in snapshot order, after
 *    every thread row. A first-class state, not an error — `queue_state` reaches every paired device,
 *    so this phone sees ids it never minted (a message queued from the desktop, or a reconnect into a
 *    backlog it has no echo for). What such a row must never do is attach itself to somebody else's
 *    message, which rule 2 forbids.
 *
 * O(items + queued): one pass to index, one to assign.
 */
internal fun foldQueuedRows(
    items: List<ThreadItem>,
    queued: List<QueuedMessage>,
): List<ThreadRow> {
    // echo id -> the positions in `items` carrying it, in thread order. Built only from user rows with
    // a non-empty id, so rules 3 and 4 are satisfied by what goes IN rather than by a check at every
    // lookup. Removing from the front of each list is what makes the assignment first-come.
    val echoPositions = mutableMapOf<String, MutableList<Int>>()
    items.forEachIndexed { index, item ->
        val id = item.userEchoId() ?: return@forEachIndexed
        echoPositions.getOrPut(id) { mutableListOf() }.add(index)
    }

    val claimed = mutableMapOf<Int, ThreadRow.Queued>()
    val unmatched = mutableListOf<ThreadRow.Queued>()
    for (entry in queued) {
        val position = entry.messageId.takeIf { it.isNotEmpty() }?.let { echoPositions[it]?.removeFirstOrNull() }
        if (position == null) {
            // Carries the snapshot's own text and NO echo id: an id this device did not mint must never
            // look like one it did, or a later reader would treat somebody else's message as this
            // device's own.
            unmatched += ThreadRow.Queued(queuedMessageId = entry.id, text = entry.text, echoId = null)
        } else {
            // The echo's own content, not the snapshot's: the two are the same message, and the one this
            // device sent is the one it can vouch for.
            val echo = (items[position] as ThreadItem.MessageItem).message
            claimed[position] = ThreadRow.Queued(queuedMessageId = entry.id, text = echo.content, echoId = echo.id)
        }
    }

    return items.mapIndexedNotNull { index, item ->
        if (item is ThreadItem.BackgroundTaskLifecycle) null else claimed[index] ?: ThreadRow.Delivered(item)
    } + unmatched
}

/**
 * Fold each maximal run of two or more adjacent tool rows in [rows] into one [ThreadRow.ToolRun] (#1635),
 * the "Collapse assistant tool uses" setting's whole effect on the thread. Outside joined background
 * blocks, any other row ends a run and a lone tool row passes through as itself.
 *
 * A background Agent block's own root call is never part of that fold (#1827 follow-up): Figma
 * `795:7158`/`789:10437` keep "Agent", its title and its status visible no matter how many rows its
 * block later picks up, so the root always draws as itself, same as today. Its *other* rows — tool and
 * assistant children alike — still fold together below it, so prose does not split the block or escape
 * its visibility control, and a subagent's tool rows are tool rows, so they join the run they sit in. A
 * run whose id is in [expandedRuns] is followed by its own rows, unchanged, so they keep their keys,
 * their nesting depth and their #1577 flush join.
 *
 * Runs after [foldQueuedRows]: a queued row is never a tool row, so it ends a run like any other. O(rows).
 */
internal fun foldToolRuns(
    rows: List<ThreadRow>,
    expandedRuns: Set<String>,
): List<ThreadRow> {
    val folded = ArrayList<ThreadRow>(rows.size)
    var start = 0
    while (start < rows.size) {
        val delivered = rows[start] as? ThreadRow.Delivered
        val block = delivered?.agentBlockId
        if (block != null && block == delivered.deliveredMessageId()) {
            folded += rows[start]
            start++
            continue
        }
        var end = start
        while (end < rows.size &&
            (rows[end].isToolRow() || block != null) &&
            rows[end] is ThreadRow.Delivered &&
            (rows[end] as ThreadRow.Delivered).agentBlockId == block
        ) {
            end++
        }
        val run = rows.subList(start, end)
        val tools = run.filter { it.isToolRow() }.map { ((it as ThreadRow.Delivered).item as ThreadItem.MessageItem).message }
        if (end - start >= 2 && tools.isNotEmpty()) {
            val runId = tools.first().id
            val expanded = runId in expandedRuns
            folded += ThreadRow.ToolRun(runId = runId, tools = tools, expanded = expanded)
            if (expanded) folded += run
            start = end
        } else {
            // Zero or one tool row, or a block remainder with no tool row at all: each row draws as itself.
            folded += rows[start]
            start++
        }
    }
    return folded
}

/** A row that draws a tool call: a [Role.Tool] message carrying one. #1577's flush join and [foldToolRuns] share it. */
internal fun ThreadRow?.isToolRow(): Boolean {
    val message = ((this as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message ?: return false
    return message.role == Role.Tool && message.toolCall != null
}

/**
 * The row's `LazyColumn` key, at its [chronologicalIndex] in the folded list. Lives beside the fold
 * rather than inside the screen so it is unit-testable, and so the two halves of the uniqueness
 * argument below sit next to each other.
 *
 * A matched [ThreadRow.Queued] takes **the same key its [ThreadRow.Delivered] form carries**, which is
 * what leaves the row in place across delivery instead of recreating it at a new identity.
 *
 * Key uniqueness, which the list depends on — a duplicate key throws and takes the thread down:
 * - The namespaces are distinct string literals, so no arm can collide with another.
 * - `msg:` keys are unique because `withMessage` upserts by id, and because [foldQueuedRows] lets at
 *   most one row claim a given echo (rule 2) and never emits the claimed item a second time.
 * - `boundary:` keys encode exactly the `(previousSessionId, newSessionId, occurredAt)` identity both
 *   boundary writers dedup on (`holdsBoundary`, #775), so no two boundaries the thread holds share a key.
 *   The two ids are daemon-supplied and length-prefixed, so an id containing a separator cannot make two
 *   different triples spell the same key; `occurredAt` comes last and needs no prefix.
 * - An **unmatched** row keys on its position, deliberately **not** on `queued_msg_id`: that value is
 *   daemon-supplied and nothing on this client checks it for uniqueness, so a snapshot repeating one
 *   would mint two identical keys. Position is unique by construction.
 * - A [ThreadRow.ToolRun] (#1635) keys on `tool-run:` and its first row's id. That id is a `msg:` id, unique
 *   as above, and two runs never share a first row. A collapsed run's rows are not emitted and an expanded
 *   run's rows are emitted once, under their own `msg:` keys, so folding adds no duplicate.
 */
internal fun ThreadRow.listKey(chronologicalIndex: Int): String =
    when (this) {
        is ThreadRow.Delivered -> item.listKey()
        is ThreadRow.Queued -> echoId?.let { "msg:$it" } ?: "queued-row:$chronologicalIndex"
        is ThreadRow.ToolRun -> "tool-run:$runId"
        is ThreadRow.AgentStartMarker -> "agent-start:$agentId"
    }

/** Composition reuse follows rendered kind, independently of the row's key and changing content. */
internal fun ThreadRow.contentType(): String =
    when (this) {
        is ThreadRow.Delivered ->
            when (item) {
                is ThreadItem.MessageItem -> if (item.message.role == Role.Tool) "tool" else "message"
                is ThreadItem.SessionBoundary -> "session-boundary"
                is ThreadItem.UnrecognizedMessage -> "unrecognized"
                is ThreadItem.Banner -> "banner"
                is ThreadItem.CompactionBoundary -> "compaction-boundary"
                is ThreadItem.ModelRefusal -> "model-refusal"
                is ThreadItem.BackgroundTaskLifecycle -> "background-task-lifecycle"
                is ThreadItem.StoppedTurn -> "stopped-turn"
            }
        is ThreadRow.Queued -> "queued"
        is ThreadRow.ToolRun -> "tool-run"
        is ThreadRow.AgentStartMarker -> "agent-start"
    }

private fun ThreadItem.listKey(): String =
    when (this) {
        is ThreadItem.MessageItem -> "msg:${message.id}"
        is ThreadItem.SessionBoundary ->
            "boundary:${previousSessionId.length}:$previousSessionId${newSessionId.length}:$newSessionId@$occurredAt"
        // The frame carries neither a message id nor a turn_id, so the row brings its own
        // client-stamped identity (#608): a position key would shift under render()'s
        // synthetic-message append/drop, and a payload key would collide on two identical frames
        // stamped in the same instant.
        is ThreadItem.UnrecognizedMessage -> "unrecognized:$id"
        // The daemon's per-event ts, which both thread writers dedup a banner on (`holdsBanner`, #873).
        is ThreadItem.Banner -> "banner:$occurredAt"
        // The daemon's per-compaction ts, which both thread writers dedup on (`holdsCompactionBoundary`, #874).
        is ThreadItem.CompactionBoundary -> "compaction:$occurredAt"
        // The frame type and the daemon's per-refusal ts, which both thread writers dedup on (`holdsModelRefusal`, #875).
        is ThreadItem.ModelRefusal -> if (fallbackModel != null) "refusal:fallback:$occurredAt" else "refusal:no-fallback:$occurredAt"
        is ThreadItem.BackgroundTaskLifecycle -> "background-task:${terminal != null}:$taskId"
        // The turn id, which both thread writers dedup a stopped row on (`holdsStoppedTurn`, #1356). It is the
        // key's whole tail, so no separator inside it can make two ids spell one key.
        is ThreadItem.StoppedTurn -> "stopped:$turnId"
    }

/**
 * How many `Agent`/`Task` calls deep each subagent tool row sits (#896), keyed by the row's
 * [de.pyryco.mobile.data.model.Message.id] — its own `tool_use_id`. Holds only rows nested at least one
 * level; a row absent from the map renders at top level.
 *
 * `parentToolUseId` is a grouping hint, not a capability (`protocol-mobile.md` § `tool_use`), so every
 * way it can fail degrades to top level rather than to an error:
 * - `""` is the main thread, and a row restored from the disk cache carries `""` because the cache does
 *   not persist the field.
 * - A parent that names no *tool row* loaded in the thread matches nothing — an older page not yet
 *   fetched, or an id that belongs to some other kind of row.
 * - A chain that loops back on itself stops at the first row the walk reaches twice, which counts as
 *   top level. The daemon never sends one; this only guarantees the walk ends.
 *
 * Matching does not depend on list order. Each row's depth is memoised as the walk passes it, so the
 * whole derivation is O(tool rows) however deep the nesting goes.
 */
internal fun toolNestingDepths(items: List<ThreadItem>): Map<String, Int> {
    val parentOf = mutableMapOf<String, String>()
    for (item in items) {
        val message = (item as? ThreadItem.MessageItem)?.message ?: continue
        val toolCall = message.toolCall ?: continue
        if (message.role == Role.Tool && message.id.isNotEmpty()) parentOf[message.id] = toolCall.parentToolUseId
    }

    val depthOf = mutableMapOf<String, Int>()
    for (start in parentOf.keys) {
        // Walk up until a row whose depth is known, a top-level row, or a row already on this path.
        val path = mutableListOf<String>()
        val onPath = mutableSetOf<String>()
        var current = start
        while (current !in depthOf) {
            path += current
            onPath += current
            val parent = parentOf.getValue(current)
            if (parent.isEmpty() || parent !in parentOf || parent in onPath) break
            current = parent
        }
        // A walk that stopped on a known row continues from its depth; one that stopped on a top-level
        // row starts that row at 0. Each earlier row on the path then sits one level below the next.
        var base = depthOf[current] ?: -1
        for (id in path.asReversed()) {
            base += 1
            depthOf[id] = base
        }
    }
    return depthOf.filterValues { it > 0 }
}

/**
 * This row's correlation candidacy: the echo id a backlog item may claim, or `null` when the row is not
 * a candidate at all. Rules 3 and 4 of [foldQueuedRows], in one place — a row that is not a
 * user-authored message, or carries no id, correlates with nothing.
 */
private fun ThreadItem.userEchoId(): String? =
    (this as? ThreadItem.MessageItem)
        ?.message
        ?.takeIf { it.role == Role.User && it.id.isNotEmpty() }
        ?.id

/** Tool outlines join only inside the same background block or ordinary run. */
internal fun ThreadRow.joinsToolRow(next: ThreadRow?): Boolean =
    isToolRow() && next.isToolRow() && (this as ThreadRow.Delivered).agentBlockId == (next as ThreadRow.Delivered).agentBlockId

/** This row's own [Message.id], for telling a background block's root row apart from its children in [foldToolRuns]. */
private fun ThreadRow.Delivered.deliveredMessageId(): String? = (item as? ThreadItem.MessageItem)?.message?.id
