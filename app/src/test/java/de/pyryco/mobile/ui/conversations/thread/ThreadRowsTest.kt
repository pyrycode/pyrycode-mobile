package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The #782 render-time join: the thread's items against the daemon's `queue_state` backlog, one row
 * list out. Drives the five rules the plan states (and `pyrycode-desktop`'s `foldQueuedRows.ts` states
 * for the sibling client), plus the key derivation the thread's `LazyColumn` depends on.
 *
 * A pure function, so this is a JVM unit test: no Compose, no repository, no clock.
 */
class ThreadRowsTest {
    private val ts: Instant = Instant.parse("2026-09-22T10:00:00Z")

    private fun userMessage(
        id: String,
        text: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(id = id, sessionId = "", role = Role.User, content = text, timestamp = ts, isStreaming = false),
        )

    private fun assistantMessage(
        id: String,
        text: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(id = id, sessionId = "", role = Role.Assistant, content = text, timestamp = ts, isStreaming = false),
        )

    private fun toolMessage(id: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "",
                role = Role.Tool,
                content = "",
                timestamp = ts,
                isStreaming = false,
                toolCall = ToolCall(toolName = "read_file", input = "a.kt", output = "ok"),
            ),
        )

    private fun unrecognized(id: String): ThreadItem.UnrecognizedMessage =
        ThreadItem.UnrecognizedMessage(
            id = id,
            site = UnrecognizedSite.LineType,
            messageType = "mystery",
            raw = "{}",
            truncated = false,
            occurredAt = ts,
        )

    private fun boundary(
        previous: String = "s0",
        new: String = "s1",
        occurredAt: Instant = ts,
        reason: BoundaryReason = BoundaryReason.Clear,
    ): ThreadItem.SessionBoundary =
        ThreadItem.SessionBoundary(
            previousSessionId = previous,
            newSessionId = new,
            reason = reason,
            occurredAt = occurredAt,
        )

    private fun ThreadItem.key(): String = ThreadRow.Delivered(this).listKey(0)

    private fun queued(
        id: Long,
        text: String,
        messageId: String = "",
    ): QueuedMessage = QueuedMessage(id = id, text = text, timestamp = ts, messageId = messageId)

    private fun List<ThreadRow>.queuedRows(): List<ThreadRow.Queued> = filterIsInstance<ThreadRow.Queued>()

    // AC #1 — a send the daemon parked draws ONE row, at the position it was sent, carrying the queue
    // treatment's two values. Nothing is appended at the tail, which is the whole double-draw bug.
    @Test
    fun `a parked send folds into its own echo rather than adding a second row`() {
        val items = listOf(userMessage("m-1", "hello"), assistantMessage("a-1", "hi"), userMessage("m-2", "wait for me"))

        val rows = foldQueuedRows(items, listOf(queued(7L, "wait for me", messageId = "m-2")))

        assertEquals(3, rows.size)
        assertEquals(ThreadRow.Delivered(items[0]), rows[0])
        assertEquals(ThreadRow.Delivered(items[1]), rows[1])
        assertEquals(ThreadRow.Queued(queuedMessageId = 7L, text = "wait for me", echoId = "m-2"), rows[2])
    }

    // AC #1 — correlation is by id, never by text: equal text under different ids stays two rows and
    // only the named one is marked.
    @Test
    fun `equal text under different ids claims only the named row`() {
        val items = listOf(userMessage("m-1", "same words"), userMessage("m-2", "same words"))

        val rows = foldQueuedRows(items, listOf(queued(7L, "same words", messageId = "m-2")))

        assertEquals(ThreadRow.Delivered(items[0]), rows[0])
        assertEquals(ThreadRow.Queued(queuedMessageId = 7L, text = "same words", echoId = "m-2"), rows[1])
    }

    // AC #2 — delivery is the same fold against a snapshot that no longer holds the item: the row keeps
    // its index, its content and its key, and loses only the treatment.
    @Test
    fun `delivery leaves the row at its index with the same key and no treatment`() {
        val items = listOf(userMessage("m-1", "hello"), userMessage("m-2", "wait for me"))
        val queuedRows = foldQueuedRows(items, listOf(queued(7L, "wait for me", messageId = "m-2")))

        val deliveredRows = foldQueuedRows(items, emptyList())

        assertEquals(queuedRows.size, deliveredRows.size)
        assertEquals(ThreadRow.Delivered(items[1]), deliveredRows[1])
        assertEquals(queuedRows[1].listKey(1), deliveredRows[1].listKey(1))
    }

    // AC #3 — an item this device minted no echo for is its own row after the thread rows, never hidden
    // and never attached to somebody else's message.
    @Test
    fun `an unmatched item becomes its own row after the thread rows`() {
        val items = listOf(userMessage("m-1", "mine"))

        val rows = foldQueuedRows(items, listOf(queued(9L, "from the desktop", messageId = "other-device-id")))

        assertEquals(2, rows.size)
        assertEquals(ThreadRow.Delivered(items[0]), rows[0])
        assertEquals(ThreadRow.Queued(queuedMessageId = 9L, text = "from the desktop", echoId = null), rows[1])
    }

    // AC #3 — `""` matches nothing, on either side. The empty-id echo is the one a pre-#781 path could
    // leave behind; neither it nor an empty-id snapshot entry may correlate.
    @Test
    fun `an empty message id matches nothing on either side`() {
        val items = listOf(userMessage("", "echo with no id"), userMessage("m-1", "mine"))

        val rows = foldQueuedRows(items, listOf(queued(9L, "echo with no id", messageId = "")))

        assertEquals(listOf(ThreadRow.Delivered(items[0]), ThreadRow.Delivered(items[1])), rows.take(2))
        assertEquals(ThreadRow.Queued(queuedMessageId = 9L, text = "echo with no id", echoId = null), rows[2])
    }

    // AC #3 / rule 3 — the guard that keeps a hostile `queue_state` from putting the operator's queued
    // treatment, and its drop control, onto daemon-authored content. Only user rows are candidates.
    @Test
    fun `daemon-authored rows are never candidates for the treatment`() {
        val items = listOf(assistantMessage("a-1", "mine?"), toolMessage("t-1"), unrecognized("u-1"), boundary())
        val snapshot =
            listOf(
                queued(1L, "assistant", messageId = "a-1"),
                queued(2L, "tool", messageId = "t-1"),
                queued(3L, "unrecognized", messageId = "u-1"),
            )

        val rows = foldQueuedRows(items, snapshot)

        assertEquals(items.map { ThreadRow.Delivered(it) }, rows.take(4))
        assertEquals(listOf(null, null, null), rows.queuedRows().map { it.echoId })
        assertEquals(listOf(1L, 2L, 3L), rows.queuedRows().map { it.queuedMessageId })
    }

    // Rule 2 — one echo to at most one item: a snapshot repeating an id claims first-come and leaves the
    // second unmatched rather than double-marking the row.
    @Test
    fun `a repeated message id claims first-come and leaves the second unmatched`() {
        val items = listOf(userMessage("m-1", "once"))
        val snapshot = listOf(queued(1L, "once", messageId = "m-1"), queued(2L, "once", messageId = "m-1"))

        val rows = foldQueuedRows(items, snapshot)

        assertEquals(ThreadRow.Queued(queuedMessageId = 1L, text = "once", echoId = "m-1"), rows[0])
        assertEquals(ThreadRow.Queued(queuedMessageId = 2L, text = "once", echoId = null), rows[1])
    }

    // The security review's finding 1: `queued_msg_id` is daemon-supplied and unvalidated, so two
    // entries repeating one must still produce two distinct list keys — a duplicate key crashes the
    // thread's LazyColumn.
    @Test
    fun `a repeated queued message id still yields distinct list keys`() {
        val snapshot = listOf(queued(4L, "first", messageId = ""), queued(4L, "second", messageId = ""))

        val rows = foldQueuedRows(emptyList(), snapshot)

        assertEquals(2, rows.size)
        assertNotEquals(rows[0].listKey(0), rows[1].listKey(1))
    }

    // A matched row's key is its echo's, so delivery cannot move or recreate it; an unmatched row's key
    // uses its snapshot occurrence, independently of its thread position.
    @Test
    fun `a matched row keys on its echo and an unmatched row keys on its snapshot occurrence`() {
        val items = listOf(userMessage("m-1", "mine"))
        val rows = foldQueuedRows(items, listOf(queued(1L, "mine", messageId = "m-1"), queued(2L, "theirs")))

        assertEquals("msg:m-1", rows[0].listKey(0))
        assertEquals(ThreadRow.Delivered(items[0]).listKey(0), rows[0].listKey(0))
        assertEquals("queued-row:2:0", rows[1].listKey(1))
    }

    // AC #4 — replacement truth. The fold holds no state, so a second snapshot leaves nothing of the
    // first behind, including the empty one a reconnect clears to.
    @Test
    fun `a replacing snapshot leaves no row from the previous backlog`() {
        val items = listOf(userMessage("m-1", "one"), userMessage("m-2", "two"))
        foldQueuedRows(items, listOf(queued(1L, "one", messageId = "m-1"), queued(5L, "stale", messageId = "gone")))

        val afterReplace = foldQueuedRows(items, listOf(queued(2L, "two", messageId = "m-2")))
        val afterClear = foldQueuedRows(items, emptyList())

        assertEquals(listOf(2L), afterReplace.queuedRows().map { it.queuedMessageId })
        assertEquals(ThreadRow.Delivered(items[0]), afterReplace[0])
        assertEquals(items.map { ThreadRow.Delivered(it) }, afterClear)
    }

    // AC #5 — every thread item appears exactly once, at its own index, in order, backlog or no backlog.
    @Test
    fun `every thread item appears exactly once in its own order`() {
        val items =
            listOf(
                userMessage("m-1", "one"),
                assistantMessage("a-1", "reply"),
                boundary(),
                userMessage("m-2", "two"),
                unrecognized("u-1"),
            )
        val snapshot = listOf(queued(1L, "two", messageId = "m-2"), queued(2L, "theirs", messageId = "not-mine"))

        val withBacklog = foldQueuedRows(items, snapshot)
        val withoutBacklog = foldQueuedRows(items, emptyList())

        assertEquals(items.map { ThreadRow.Delivered(it) }, withoutBacklog)
        // The first items.size rows correspond, index for index, to the items — either delivered, or
        // queued under that item's own echo id.
        assertEquals(items.size + 1, withBacklog.size)
        items.forEachIndexed { index, item ->
            when (val row = withBacklog[index]) {
                is ThreadRow.Delivered -> assertEquals(item, row.item)
                is ThreadRow.Queued -> assertEquals((item as ThreadItem.MessageItem).message.id, row.echoId)
                is ThreadRow.AgentStartMarker -> error("queued fold never emits an agent marker")
                is ThreadRow.ToolRun -> error("the queued fold never emits a tool run (#1635)")
            }
        }
        assertNull(withBacklog.queuedRows().single { it.queuedMessageId == 2L }.echoId)
    }

    // #775 — an idle-evicted session keeps its id, so every eviction of it is `A->A`. Two of them share the
    // pair and differ only in occurredAt, and each must key its own row or the LazyColumn throws.
    @Test
    fun `boundaries sharing a session pair but not an instant get distinct keys`() {
        val first = boundary(previous = "A", new = "A", reason = BoundaryReason.IdleEvict)
        val second = first.copy(occurredAt = Instant.parse("2026-09-22T11:00:00Z"))

        assertNotEquals(first.key(), second.key())
    }

    // #775 — the ids are daemon-supplied, so a delimiter inside one must not let two different triples
    // concatenate to one key.
    @Test
    fun `boundary ids that would concatenate identically still get distinct keys`() {
        assertNotEquals(boundary(previous = "a->b", new = "c").key(), boundary(previous = "a", new = "b->c").key())
        assertNotEquals(boundary(previous = "a1:", new = "b").key(), boundary(previous = "a", new = "1:b").key())
    }

    // #775 — the key reads exactly the triple the dedups compare: equal triples key equally whatever the
    // reason, which is why the dedups must collapse them.
    @Test
    fun `a boundary's key reads its session pair and instant and nothing else`() {
        assertEquals(boundary().key(), boundary().key())
        assertEquals(boundary().key(), boundary(reason = BoundaryReason.IdleEvict).key())
        assertNotEquals(boundary().key(), boundary(new = "s2").key())
        assertNotEquals(boundary().key(), boundary(previous = "s9").key())
    }
}
