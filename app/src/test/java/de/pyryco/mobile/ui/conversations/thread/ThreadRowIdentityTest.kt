package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRowIdentityTest {
    private val ts = Instant.parse("2026-10-08T10:00:00Z")

    private fun row(
        id: String,
        role: Role = Role.Tool,
    ) = ThreadItem.MessageItem(
        Message(id, "s", role, id, ts, isStreaming = false, toolCall = if (role == Role.Tool) ToolCall("Read", "", "") else null),
    )

    private fun keys(rows: List<ThreadRow>) = rows.mapIndexed { index, row -> row.listKey(index) }

    private fun queuedKeys(rows: List<ThreadRow>) = rows.mapIndexedNotNull { index, row -> (row as? ThreadRow.Queued)?.listKey(index) }

    private fun assertUnique(rows: List<ThreadRow>) {
        assertEquals(keys(rows).size, keys(rows).toSet().size)
    }

    private fun messages(rows: List<ThreadRow>): List<Message> =
        rows.flatMap {
            when (it) {
                is ThreadRow.Delivered -> listOf((it.item as ThreadItem.MessageItem).message)
                is ThreadRow.ToolRun -> if (it.expanded) emptyList() else it.tools
                else -> emptyList()
            }
        }

    @Test fun firstToolRepresentative_survivesSingletonGrowthAndUnrelatedHistory() {
        val first = row("t1")
        val expected = keys(foldToolRuns(foldQueuedRows(listOf(first), emptyList()), emptySet())).single()
        for (size in 1..5) {
            val tools = (1..size).map { row("t$it") }
            val items = listOf(row("older", Role.User)) + tools + row("after", Role.Assistant)
            repeat(3) {
                val display = foldToolRuns(foldQueuedRows(items, emptyList()), emptySet())
                assertEquals(expected, keys(display)[1])
                assertEquals(items.map { it.message }, messages(display))
                assertEquals("msg:older", keys(display).first())
                assertEquals("msg:after", keys(display).last())
                assertUnique(display)
            }
        }
    }

    @Test fun firstToolRepresentative_survivesMarkerSplitAndRejoinAtEveryPosition() {
        for (size in 1..5) {
            val tools = (1..size).map { row("t$it") }
            val rows = foldQueuedRows(tools, emptyList())
            val combined = foldHistoryToolRuns(rows, emptySet(), emptyList())
            for (target in tools) {
                for (prepared in listOf(false, true)) {
                    val marker =
                        ThreadHistoryMarker(
                            beforeRow = target.historyKeys().first(),
                            unsignedAnchor = 1u,
                            displayRow = target.takeIf { prepared },
                        )
                    repeat(2) {
                        val split = foldHistoryToolRuns(rows, emptySet(), listOf(marker))
                        assertEquals(keys(combined).first(), keys(split).first())
                        assertEquals(tools.map { it.message }, messages(split))
                        assertUnique(split)
                        val markerRow = split.single { historyMarkersFor(it, listOf(marker)).isNotEmpty() }
                        assertEquals(target.message.id, ((markerRow as ThreadRow.Delivered).item as ThreadItem.MessageItem).message.id)
                        assertEquals(combined, foldHistoryToolRuns(rows, emptySet(), emptyList()))
                    }
                }
            }
        }
    }

    @Test fun expandedHeaders_areDistinctAndReexposedToolsKeepMessageIdentity() {
        val tools = (1..4).map { row("t$it") }
        val rows = foldQueuedRows(tools, emptyList())
        for (target in tools) {
            val markers = listOf(ThreadHistoryMarker(beforeRow = target.historyKeys().first(), unsignedAnchor = 1u, displayRow = target))
            for (expanded in listOf(emptySet(), setOf("t1"), tools.map { it.message.id }.toSet())) {
                val display = foldHistoryToolRuns(rows, expanded, markers)
                assertUnique(display)
                assertEquals(tools.map { it.message }, messages(display))
                display.filterIsInstance<ThreadRow.ToolRun>().filter { it.expanded }.forEach {
                    assertEquals("tool-run:${it.runId}", it.listKey(0))
                    assertTrue(keys(display).contains("msg:${it.runId}"))
                }
                display.filterIsInstance<ThreadRow.Delivered>().forEach {
                    assertEquals("msg:${(it.item as ThreadItem.MessageItem).message.id}", it.listKey(99))
                }
            }
        }
        assertEquals(tools.map { "msg:${it.message.id}" }, keys(rows))
        assertEquals(rows, foldQueuedRows(tools, emptyList()))
    }

    @Test fun queueOccurrences_surviveRepeatedHistoryDeliveryAndFoldUpdates() {
        val snapshot =
            listOf(QueuedMessage(7, "first", ts, "echo"), QueuedMessage(7, "second", ts, ""), QueuedMessage(8, "third", ts, "unknown"))
        val echo = row("echo", Role.User)
        val held = listOf(echo, row("t1"))
        val expected = queuedKeys(foldQueuedRows(held, snapshot))
        for (page in listOf(emptyList(), listOf(row("older", Role.User)), (1..4).map { row("old$it", Role.Assistant) })) {
            for (extra in listOf(emptyList(), listOf(row("t2")), listOf(row("unrelated", Role.User), row("t2")))) {
                for (expanded in listOf(emptySet(), setOf("t1"))) {
                    repeat(3) {
                        val rows = foldToolRuns(foldQueuedRows(page + held + extra, snapshot), expanded)
                        assertEquals(expected, queuedKeys(rows))
                        assertUnique(rows)
                        assertEquals(listOf("echo", "second", "third"), rows.filterIsInstance<ThreadRow.Queued>().map { it.text })
                    }
                }
            }
        }
        assertEquals("msg:echo", expected.first())
        val allUnmatched = foldQueuedRows(emptyList(), snapshot)
        assertUnique(allUnmatched)
        assertEquals(
            "matching an earlier occurrence must not renumber later unmatched duplicates",
            keys(allUnmatched).drop(1),
            expected.drop(1),
        )
        assertEquals(keys(allUnmatched), queuedKeys(foldQueuedRows(listOf(row("history", Role.User)), snapshot)))
        assertEquals(emptyList<ThreadRow>(), foldQueuedRows(emptyList(), emptyList()))
        assertEquals(listOf(ThreadRow.Delivered(echo)), foldQueuedRows(listOf(echo), emptyList()))
        val replacement = listOf(QueuedMessage(9, "replacement", ts, ""))
        assertEquals(listOf("replacement"), foldQueuedRows(emptyList(), replacement).filterIsInstance<ThreadRow.Queued>().map { it.text })
    }
}
