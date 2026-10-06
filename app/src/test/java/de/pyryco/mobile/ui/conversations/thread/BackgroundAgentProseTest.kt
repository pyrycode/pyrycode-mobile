package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadProjection
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
        val open = foldToolRuns(rows, setOf("a", "b"))
        assertEquals(ids(rows), ids(open))
        assertUnique(open)
        assertEquals(listOf("main", "main-later"), ids(foldToolRuns(rows, emptySet())))
        assertEquals(
            listOf(listOf("a", "read"), listOf("b")),
            foldToolRuns(rows, emptySet()).filterIsInstance<ThreadRow.ToolRun>().map { it.tools.map(Message::id) },
        )
    }

    @Test fun historyGapBeforeChildDoesNotLetItsProseEscapeAClosedAgentRun() {
        val child = text("child", "a")
        val items = listOf(tool("a"), start("a"), text("main"), child, tool("read", "a", "Read"))
        val rows = project(items)
        val markers = listOf(ThreadHistoryMarker(1, child.historyKeys().first()))
        val closed = foldHistoryToolRuns(rows, emptySet(), markers)
        assertEquals(listOf("main"), ids(closed))
        assertEquals(listOf("a"), closed.filterIsInstance<ThreadRow.ToolRun>().map { it.runId })
        val projected = foldedAgentHistoryMarkers(rows, closed, markers)
        assertEquals(listOf(1L), projected.map { it.anchor })
        assertEquals(projected, historyMarkersFor(closed.filterIsInstance<ThreadRow.ToolRun>().single(), projected))
        val open = foldHistoryToolRuns(rows, setOf("a"), markers)
        assertEquals(ids(rows), ids(open))
        assertEquals(markers, foldedAgentHistoryMarkers(rows, open, markers))
        assertEquals(
            markers,
            historyMarkersFor(
                open.filterIsInstance<ThreadRow.Delivered>().first {
                    (it.item as? ThreadItem.MessageItem)?.message?.id == "child"
                },
                markers,
            ),
        )
        assertUnique(closed)
        assertUnique(open)
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

    @Test fun replayedDistinctLanesSharingOneParentNeverMergeTheirText() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c" to tool("a").message))
            val script =
                listOf(
                    LiveSessionEvent.AssistantDelta("c", "one", 0, "A", "a"),
                    LiveSessionEvent.AssistantDelta("c", "two", 0, "B", "a"),
                    LiveSessionEvent.AssistantDelta("c", "one", 1, "C", "a"),
                )
            for (event in script + script) projection.applyAssistantDelta(event)
            val rows = project(listOf(start("a")) + projection.observe("c").first())
            assertEquals(listOf("a", "one", "two", "one#1"), ids(rows))
            assertEquals(
                listOf("A", "B", "C"),
                delivered(rows).mapNotNull {
                    (it.item as? ThreadItem.MessageItem)?.message?.takeIf { m -> m.role == Role.Assistant }?.content
                },
            )
            assertUnique(rows)
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
        assertEquals(setOf("ordinary", "a"), carried.expandedRuns)
        assertEquals(emptySet<String>(), carried.pending)
        assertEquals(listOf("ordinary", "main", "a", "child"), ids(foldToolRuns(after, carried.expandedRuns)))
        assertUnique(after)
    }

    @Test fun syntheticRetainsParentAndNeverConcatenatesDifferentLanes() {
        val base = listOf(tool("a"), start("a"), tool("b"), start("b"))
        var fold = ThreadFold(base, null)
        for ((lane, parent) in listOf("lane-a" to "a", "main" to "", "lane-b" to "b")) {
            fold = fold.reduce(ThreadInput.Live(LiveSessionEvent.AssistantDelta("c", lane, 0, lane, parent), ts), "c")
            val synthetic = (fold.render().last() as ThreadItem.MessageItem).message
            assertEquals(lane, synthetic.content)
            assertEquals(parent, synthetic.parentToolUseId)
            val row = delivered(project(fold.render())).single { (it.item as? ThreadItem.MessageItem)?.message?.id == lane }
            assertEquals(parent.takeIf { it.isNotEmpty() }, row.agentBlockId)
            assertUnique(project(fold.render()))
        }
    }

    @Test fun syntheticParentSurvivesParentlessAppendReplayAndRepositoryHandoff() {
        val delta = LiveSessionEvent.AssistantDelta("c", "lane", 0, "a", "agent")
        var fold = ThreadFold(emptyList(), null).reduce(ThreadInput.Live(delta, ts), "c")
        fold =
            fold
                .reduce(ThreadInput.Live(delta.copy(seq = 1, text = "b", parentToolUseId = ""), ts), "c")
                .reduce(ThreadInput.Live(delta.copy(text = "duplicate"), ts), "c")
        assertEquals("ab", (fold.render().single() as ThreadItem.MessageItem).message.content)
        assertEquals("agent", (fold.render().single() as ThreadItem.MessageItem).message.parentToolUseId)
        val held = text("lane", "agent")
        assertEquals(listOf(held), fold.reduce(ThreadInput.Finished(listOf(held)), "c").render())
    }

    @Test fun lateParentOnReplayOrOlderDeltaEnrichesOnlyAttributionAndKeepsProseInItsBlock() {
        val base = listOf(tool("a"), start("a"), text("main"))
        for (seq in listOf(0, 1)) {
            val delta = LiveSessionEvent.AssistantDelta("c", "child", seq, "reply", "")
            val initial = ThreadFold(base, null).reduce(ThreadInput.Live(delta, ts), "c")
            val before = (initial.render().last() as ThreadItem.MessageItem).message
            assertNull(delivered(project(initial.render())).single { it.item == ThreadItem.MessageItem(before) }.agentBlockId)

            val replay = delta.copy(seq = 0, text = "ignored", parentToolUseId = "a")
            val learned = initial.reduce(ThreadInput.Live(replay, Instant.parse("2026-10-06T10:00:01Z")), "c")
            val after = (learned.render().last() as ThreadItem.MessageItem).message
            assertEquals(before.copy(parentToolUseId = "a"), after)
            assertEquals(initial.stream?.copy(parentToolUseId = "a"), learned.stream)
            val rows = project(learned.render())
            assertEquals("a", delivered(rows).single { it.item == ThreadItem.MessageItem(after) }.agentBlockId)
            assertEquals(listOf("main"), ids(foldToolRuns(rows, emptySet())))
            assertEquals(listOf("main", "a", "child"), ids(foldToolRuns(rows, setOf("a"))))
            assertEquals(listOf("main"), ids(foldToolRuns(rows, emptySet())))
            assertUnique(rows)
            assertUnique(foldToolRuns(rows, setOf("a")))

            val conflicting = replay.copy(parentToolUseId = "other")
            assertEquals(learned, learned.reduce(ThreadInput.Live(conflicting, ts), "c"))
            assertEquals(initial, initial.reduce(ThreadInput.Live(replay.copy(conversationId = "other"), ts), "c"))
        }
    }

    @Test fun lateParentAfterTurnEndPreservesSettledTextIdentityAndTimestamp() {
        val delta = LiveSessionEvent.AssistantDelta("c", "child", 0, "reply", "")
        val settled =
            ThreadFold(emptyList(), null)
                .reduce(ThreadInput.Live(delta, ts), "c")
                .reduce(ThreadInput.Live(LiveSessionEvent.TurnEnd("c", "child", "end_turn"), ts), "c")
        val learned = settled.reduce(ThreadInput.Live(delta.copy(parentToolUseId = "a"), ts), "c")
        assertEquals(settled.stream?.copy(parentToolUseId = "a"), learned.stream)
        assertEquals(
            (settled.render().single() as ThreadItem.MessageItem).message.copy(parentToolUseId = "a"),
            (learned.render().single() as ThreadItem.MessageItem).message,
        )
    }

    @Test fun historyOverlapReplayAndReconnectKeepEachSegmentOnceAtTheFinishAnchor() =
        runTest {
            val script =
                listOf(
                    entry(
                        1,
                        "tool_use",
                        """{"conversation_id":"c","turn_id":"outer","tool_use_id":"a","name":"Agent","input_summary":"","input":{"run_in_background":"true"},"parent_tool_use_id":""}""",
                    ),
                    entry(
                        2,
                        "background_task_started",
                        """{"conversation_id":"c","task_id":"task-a","tool_call_id":"a","description":"Agent","task_type":"local_agent","truncated_fields":null}""",
                    ),
                    deltaEntry(3, "main", "M"),
                    deltaEntry(4, "child", "A", "a"),
                    entry(
                        5,
                        "background_task_updated",
                        """{"conversation_id":"c","task_id":"task-a","status":"completed","summary":"Done","patch":"","truncated_fields":null}""",
                    ),
                    deltaEntry(6, "later", "L"),
                )
            for (held in listOf(script.take(3), script.subList(2, 5), script.takeLast(3), emptyList(), listOf(script[3]))) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage("c", HistoryPage(held.asReversed(), "", false), true)
                val initial = project(projection.observe("c").first())
                assertUnique(initial)
                repeat(2) {
                    // reconnect/backfill replaces the roster with no roster while replaying the overlap
                    projection.mergeHistoryPage("c", HistoryPage(script.asReversed(), "", true), true)
                    val rows = project(projection.observe("c").first())
                    val messages = delivered(rows).mapNotNull { (it.item as? ThreadItem.MessageItem)?.message }
                    assertEquals(listOf("M", "Agent", "A", "L"), messages.map(Message::content))
                    assertEquals(listOf("main", "a", "child", "later"), messages.map(Message::id))
                    assertEquals("a", delivered(rows).single { (it.item as? ThreadItem.MessageItem)?.message?.id == "child" }.agentBlockId)
                    assertUnique(rows)
                    assertUnique(foldToolRuns(rows, setOf("a")))
                }
            }
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
