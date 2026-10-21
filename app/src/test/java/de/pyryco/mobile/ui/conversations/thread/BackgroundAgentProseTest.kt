package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundAgentProseTest {
    private val ts = Instant.parse("2026-10-06T10:00:00Z")

    private fun tool(
        id: String,
        parent: String = "",
        name: String = "Agent",
    ) = ThreadItem.MessageItem(
        Message(
            id,
            "s",
            Role.Tool,
            "",
            ts,
            false,
            toolCall =
                ToolCall(
                    name,
                    "",
                    "",
                    parentToolUseId = parent,
                    inputFields = mapOf("run_in_background" to "true"),
                ),
        ),
    )

    private fun text(
        id: String,
        parent: String = "",
    ) = ThreadItem.MessageItem(Message(id, "s", Role.Assistant, id, ts, false, parentToolUseId = parent))

    private fun start(id: String) = ThreadItem.BackgroundTaskLifecycle("task-$id", ts, id, "Agent", "local_agent")

    private fun finish(id: String) =
        ThreadItem.BackgroundTaskLifecycle(
            "task-$id",
            ts,
            terminal = BackgroundTaskUpdate("", "completed", "", null),
        )

    private fun project(items: List<ThreadItem>) = foldBackgroundAgentBlocks(foldQueuedRows(items, emptyList()), items, null)

    private fun delivered(rows: List<ThreadRow>) = rows.filterIsInstance<ThreadRow.Delivered>()

    private fun ids(rows: List<ThreadRow>) = delivered(rows).mapNotNull { (it.item as? ThreadItem.MessageItem)?.message?.id }

    private fun assertUnique(rows: List<ThreadRow>) {
        val keys = rows.mapIndexed { i, row -> row.listKey(i) }
        assertEquals(keys.distinct(), keys)
    }

    @Test fun eachLaneRendersOnceAndChildOrderMatchesLoadedOrder() {
        val items =
            listOf(
                tool("a"),
                start("a"),
                tool("b"),
                start("b"),
                text("main"),
                text("a-first", "a"),
                text("b-first", "b"),
                tool("read", "a", "Read"),
                text("a-second", "a"),
                text("main-later"),
                text("b-second", "b"),
            )
        val rows = project(items)
        assertEquals(listOf("main", "main-later", "a", "a-first", "read", "a-second", "b", "b-first", "b-second"), ids(rows))
        assertEquals(listOf("a", "a", "a", "a", "b", "b", "b"), delivered(rows).drop(2).map { it.agentBlockId })
        assertUnique(rows)
        // Each root always draws as itself (#1827 follow-up); only "a"'s one tool-bearing child run — "read" —
        // is ever collapsible. Lane "b" has no tool among its children, so it never folds at all.
        val open = foldToolRuns(rows, setOf("read"))
        assertEquals(ids(rows), ids(open))
        assertUnique(open)
        assertEquals(listOf("main", "main-later", "a", "b", "b-first", "b-second"), ids(foldToolRuns(rows, emptySet())))
        assertEquals(
            listOf(listOf("read")),
            foldToolRuns(rows, emptySet()).filterIsInstance<ThreadRow.ToolRun>().map { it.tools.map(Message::id) },
        )
    }

    @Test fun historyGapBeforeChildDoesNotLetItsProseEscapeAClosedAgentRun() {
        val agent = tool("a")
        val child = text("child", "a")
        val items = listOf(agent, start("a"), text("main"), child, tool("read", "a", "Read"))
        val rows = project(items)
        for (prepared in listOf(false, true)) {
            val markers =
                listOf(
                    ThreadHistoryMarker(
                        beforeRow = child.historyKeys().first(),
                        unsignedAnchor = 1u,
                        displayRow = child.takeIf { prepared },
                    ),
                    ThreadHistoryMarker(
                        beforeRow = agent.historyKeys().first(),
                        unsignedAnchor = 2u,
                        displayRow = agent.takeIf { prepared },
                    ),
                )
            val closed = foldHistoryToolRuns(rows, emptySet(), markers)
            val open = foldHistoryToolRuns(rows, setOf("read"), markers)
            val reclosed = foldHistoryToolRuns(rows, emptySet(), markers)
            // Check every row: expanded runs retain both their header and the original first tool.
            for (display in listOf(closed, open, reclosed, rows)) {
                val projected = foldedAgentHistoryMarkers(rows, display, markers)
                assertEquals(listOf(1L, 2L), display.flatMap { historyMarkersFor(it, projected) }.map { it.anchor }.sorted())
                assertUnique(display)
                val run = display.filterIsInstance<ThreadRow.ToolRun>().singleOrNull()
                if (run != null && !run.expanded) {
                    // "a" itself is never folded away (#1827 follow-up); only its "read" child run collapses.
                    assertEquals(listOf("main", "a"), ids(display))
                    assertEquals("read", run.runId)
                    assertEquals(projected, historyMarkersFor(run, projected))
                } else {
                    assertEquals(ids(rows), ids(display))
                    assertEquals(markers, projected)
                    if (run != null) assertEquals(emptyList<ThreadHistoryMarker>(), historyMarkersFor(run, projected))
                    for ((id, marker) in listOf("child" to markers[0], "a" to markers[1])) {
                        val row = delivered(display).single { (it.item as? ThreadItem.MessageItem)?.message?.id == id }
                        assertEquals(listOf(marker), historyMarkersFor(row, projected))
                    }
                }
            }
        }
    }

    @Test fun emptyUnknownUntrackedAndCyclicParentsRetainOrdinaryText() {
        val items =
            listOf(
                tool("a"),
                start("a"),
                text("main"),
                text("missing", "absent"),
                tool("untracked"),
                text("untracked-text", "untracked"),
                start("absent"),
                tool("x", "y", "Read"),
                tool("y", "x", "Read"),
                text("cycle", "x"),
            )
        val rows = project(items)
        assertEquals(listOf("main", "missing", "untracked", "untracked-text", "x", "y", "cycle", "a"), ids(rows))
        delivered(rows)
            .filter { (it.item as? ThreadItem.MessageItem)?.message?.role == Role.Assistant }
            .forEach { assertNull(it.agentBlockId) }
        assertUnique(rows)
    }

    @Test fun loadedChildrenBeforeOrAfterTheirParentKeepIdentityAndRelativeOrder() {
        val children = listOf(text("lane-one", "a"), tool("read", "a", "Read"), text("lane-two", "a"))
        for (split in 0..children.size) {
            val rows = project(children.take(split) + tool("a") + start("a") + children.drop(split) + text("main"))
            assertEquals(listOf("lane-one", "read", "lane-two"), ids(rows).filter { it != "a" && it != "main" })
            assertEquals(
                listOf("lane-one", "lane-two"),
                delivered(rows).mapNotNull {
                    (it.item as? ThreadItem.MessageItem)?.message?.takeIf { message -> message.parentToolUseId == "a" }?.content
                },
            )
            assertUnique(rows)
            assertUnique(foldToolRuns(rows, setOf("a")))
        }
    }

    @Test fun knownFinishAnchorStaysBetweenNeighborsAfterLateChildAndRosterReplacement() {
        val base = listOf(tool("a"), start("a"), text("before"), text("child", "a"), finish("a"), text("after"))
        val rows = project(base)
        assertEquals(listOf("before", "a", "child", "after"), ids(rows))
        val backfilled = listOf(text("older")) + base + text("late", "a")
        val later = project(backfilled)
        assertEquals(listOf("older", "before", "a", "child", "late", "after"), ids(later))
        assertEquals("a", delivered(later).single { (it.item as? ThreadItem.MessageItem)?.message?.id == "late" }.agentBlockId)
        assertUnique(later)
    }

    @Test fun lateParentJoinClaimsLoadedProseAndCarriesPendingExpansion() {
        val base = listOf(tool("ordinary", name = "Read"), tool("a"), text("child", "a"), text("main"))
        val before = project(base)
        assertEquals(listOf("ordinary", "a", "child", "main"), ids(before))
        val after = project(base + start("a"))
        assertEquals(listOf("ordinary", "main", "a", "child"), ids(after))
        val carried = carryRunExpansion(before, after, setOf("ordinary"), emptySet())
        // "a" never becomes a run id itself (#1827 follow-up), and its lone "child" never forms a run
        // either, so "a"'s carried intent has nothing left to resolve into — it stays pending, harmlessly,
        // since the block it names never collapses.
        assertEquals(setOf("ordinary"), carried.expandedRuns)
        assertEquals(setOf("a"), carried.pending)
        assertEquals(listOf("ordinary", "main", "a", "child"), ids(foldToolRuns(after, carried.expandedRuns)))
        assertUnique(after)
    }

    private fun entry(
        id: Long,
        type: String,
        json: String,
    ) = HistoryEntry(id, type, MobileJson.parseToJsonElement(json), ts)

    private fun deltaEntry(
        id: Long,
        lane: String,
        text: String,
        parent: String = "",
    ) = HistoryEntry(
        id,
        "assistant_delta",
        buildJsonObject {
            put("conversation_id", "c")
            put("turn_id", lane)
            put("seq", 0)
            put("text", text)
            put("parent_tool_use_id", parent)
        },
        ts,
    )
}
