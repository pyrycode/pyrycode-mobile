package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.repository.ThreadItem

/**
 * One rendered row of the thread (#782) — the unit [ThreadScreen]'s list walks, produced by
 * the supplied presentation from the thread's items and the daemon's queued backlog.
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
        val identityKey: String? = null,
        val summaryText: String? = null,
        val displayAgent: de.pyryco.mobile.data.model.ConversationAgent? = null,
    ) : ThreadRow

    /** Launch-location affordance for a loaded, joined background Agent. */
    data class AgentStartMarker(
        val agentId: String,
        val description: String,
        val finished: Boolean,
        val finishAnchor: String? = null,
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
     *   snapshot's own `message_id` would not be (see the supplied identity): it is read out of the
     *   thread's items, which `withMessage`'s upsert keeps unique, and the supplied presentation lets at most
     *   one row claim a given echo.
     * @param occurrence Zero-based occurrence of this queued id in the snapshot, counting matched
     *   entries too. Together with the id it identifies an unmatched row independently of thread position.
     */
    data class Queued(
        val queuedMessageId: Long,
        val text: String,
        val echoId: String?,
        val occurrence: Int = 0,
        val identityKey: String? = null,
        val target: de.pyryco.mobile.data.repository.AuthoritativeQueueTarget? = null,
        val status: String = "queued",
        val authoritative: Boolean = false,
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
 * Runs after the supplied presentation: a queued row is never a tool row, so it ends a run like any other. O(rows).
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
 * The row's `LazyColumn` key. [chronologicalIndex] is retained for existing callers but does not
 * participate in identity. Lives beside the fold
 * rather than inside the screen so it is unit-testable, and so the two halves of the uniqueness
 * argument below sit next to each other.
 *
 * A matched [ThreadRow.Queued] takes **the same key its [ThreadRow.Delivered] form carries**, which is
 * what leaves the row in place across delivery instead of recreating it at a new identity.
 *
 * Key uniqueness, which the list depends on — a duplicate key throws and takes the thread down:
 * - Distinct string-literal namespaces separate unrelated identities. Message keys are shared only
 *   by mutually exclusive representatives of the same message.
 * - `msg:` keys are unique because `withMessage` upserts by id, and because the supplied presentation lets at
 *   most one row claim a given echo (rule 2) and never emits the claimed item a second time.
 * - `boundary:` keys encode exactly the `(previousSessionId, newSessionId, occurredAt)` identity both
 *   boundary writers dedup on (`holdsBoundary`, #775), so no two boundaries the thread holds share a key.
 *   The two ids are daemon-supplied and length-prefixed, so an id containing a separator cannot make two
 *   different triples spell the same key; `occurredAt` comes last and needs no prefix.
 * - An **unmatched** row keys on `(queued_msg_id, occurrence)`, where the supplied presentation counts every
 *   occurrence of that id in the snapshot. Repeated ids remain distinct without depending on delivered
 *   rows, history or display folds. Neither field contains separators: both are decimal numbers.
 * - A collapsed [ThreadRow.ToolRun] adopts its first tool's `msg:` key; that tool is consumed by the
 *   fold, so it cannot also be emitted. A lone or marker-exposed tool has the same key. An expanded
 *   header instead uses `tool-run:` with that id, leaving its re-exposed children their `msg:` keys.
 *   Two runs never share a first tool, so both collapsed representatives and expanded headers are unique.
 */
@Suppress("UNUSED_PARAMETER") // Preserve the indexed call shape while identity no longer depends on position.
internal fun ThreadRow.listKey(chronologicalIndex: Int): String =
    when (this) {
        is ThreadRow.Delivered -> identityKey ?: item.listKey()
        is ThreadRow.Queued -> identityKey ?: echoId?.let { "msg:$it" } ?: "queued-row:$queuedMessageId:$occurrence"
        is ThreadRow.ToolRun -> if (expanded) "tool-run:$runId" else "msg:$runId"
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
 * [de.pyryco.mobile.data.model.Message.id] — its renderer key. Parent chains join ordinary wire ids.
 * Holds only rows nested at least one
 * level; a row absent from the map renders at top level.
 *
 * `parentToolUseId` is a grouping hint, not a capability (`protocol-mobile.md` § `tool_use`), so every
 * way it can fail degrades to top level rather than to an error:
 * - `""` is the main thread. A row restored from a cache document written before #2221 also carries `""`,
 *   because the cache did not persist the field until then.
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
    val rendererIds = mutableMapOf<String, String>()
    for (item in items) {
        val message = (item as? ThreadItem.MessageItem)?.message ?: continue
        val toolCall = message.toolCall ?: continue
        val id = message.ordinaryId ?: continue
        if (message.role == Role.Tool && id.isNotEmpty()) {
            parentOf[id] = toolCall.parentToolUseId
            rendererIds[id] = message.id
        }
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
    return depthOf.filterValues { it > 0 }.mapKeys { (id, _) -> rendererIds.getValue(id) }
}

/** Tool outlines join only inside the same background block or ordinary run. */
internal fun ThreadRow.joinsToolRow(next: ThreadRow?): Boolean =
    isToolRow() && next.isToolRow() && (this as ThreadRow.Delivered).agentBlockId == (next as ThreadRow.Delivered).agentBlockId

/** This row's own [Message.id], for telling a background block's root row apart from its children in [foldToolRuns]. */
private fun ThreadRow.Delivered.deliveredMessageId(): String? = (item as? ThreadItem.MessageItem)?.message?.id
