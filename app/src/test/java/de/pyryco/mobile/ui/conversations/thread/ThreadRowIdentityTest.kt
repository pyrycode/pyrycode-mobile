package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
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
        val expected = keys(foldToolRuns(fixtureQueuedRows(listOf(first), emptyList()), emptySet())).single()
        for (size in 1..5) {
            val tools = (1..size).map { row("t$it") }
            val items = listOf(row("older", Role.User)) + tools + row("after", Role.Assistant)
            repeat(3) {
                val display = foldToolRuns(fixtureQueuedRows(items, emptyList()), emptySet())
                assertEquals(expected, keys(display)[1])
                assertEquals(items.map { it.message }, messages(display))
                assertEquals("msg:older", keys(display).first())
                assertEquals("msg:after", keys(display).last())
                assertUnique(display)
            }
        }
    }

    @Test fun expandedHeaders_areDistinctAndReexposedToolsKeepMessageIdentity() {
        val tools = (1..4).map { row("t$it") }
        val rows = fixtureQueuedRows(tools, emptyList())
        for (expanded in listOf(emptySet(), setOf("t1"), tools.map { it.message.id }.toSet())) {
            val display = foldToolRuns(rows, expanded)
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
        assertEquals(tools.map { "msg:${it.message.id}" }, keys(rows))
        assertEquals(rows, fixtureQueuedRows(tools, emptyList()))
    }
}
