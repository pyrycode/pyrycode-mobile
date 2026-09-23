package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem

/**
 * One rendered row of the thread (#782) — the unit [ThreadScreen]'s list walks, produced by
 * [foldQueuedRows] from the thread's items and the daemon's queued backlog.
 *
 * The two arms are one message's two states, not two kinds of content: a send the daemon parked draws
 * as [Queued] at the position it was sent, and the same row becomes [Delivered] when the next snapshot
 * no longer holds it.
 */
sealed interface ThreadRow {
    /** A row the daemon has run, or a row that is not a message at all (a boundary, an unrecognized frame). */
    data class Delivered(
        val item: ThreadItem,
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

    return items.mapIndexed { index, item -> claimed[index] ?: ThreadRow.Delivered(item) } + unmatched
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
 * - The four namespaces are distinct string literals, so no arm can collide with another.
 * - `msg:` keys are unique because `withMessage` upserts by id, and because [foldQueuedRows] lets at
 *   most one row claim a given echo (rule 2) and never emits the claimed item a second time.
 * - `boundary:` keys encode exactly the `(previousSessionId, newSessionId, occurredAt)` identity both
 *   boundary writers dedup on (`holdsBoundary`, #775), so no two boundaries the thread holds share a key.
 *   The two ids are daemon-supplied and length-prefixed, so an id containing a separator cannot make two
 *   different triples spell the same key; `occurredAt` comes last and needs no prefix.
 * - An **unmatched** row keys on its position, deliberately **not** on `queued_msg_id`: that value is
 *   daemon-supplied and nothing on this client checks it for uniqueness, so a snapshot repeating one
 *   would mint two identical keys. Position is unique by construction.
 */
internal fun ThreadRow.listKey(chronologicalIndex: Int): String =
    when (this) {
        is ThreadRow.Delivered -> item.listKey()
        is ThreadRow.Queued -> echoId?.let { "msg:$it" } ?: "queued-row:$chronologicalIndex"
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
