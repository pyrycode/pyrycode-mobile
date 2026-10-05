package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAgentBlocksTest {
    private val ts = Instant.parse("2026-10-05T10:00:00Z")

    private fun row(
        id: String,
        name: String? = null,
        parent: String = "",
    ) = ThreadItem.MessageItem(
        Message(
            id,
            "s",
            if (name == null) Role.User else Role.Tool,
            id,
            ts,
            false,
            toolCall = name?.let { ToolCall(it, "input", "Async agent launched", parentToolUseId = parent) },
        ),
    )

    private fun start(
        task: String = "task-a",
        agent: String = "a",
        type: String = "local_agent",
    ) = ThreadItem.BackgroundTaskLifecycle(task, ts, agent, "Launch description", type)

    private fun finish(
        task: String = "task-a",
        agent: String? = null,
        status: String = "completed",
    ) = ThreadItem.BackgroundTaskLifecycle(task, ts, agent, terminal = BackgroundTaskUpdate("", status, "", null))

    private fun roster(vararg ids: String) =
        BackgroundTaskRoster(
            ids.map {
                BackgroundTask("task-$it", it, "local_agent", "Roster description", null, null, null, false)
            },
            0,
        )

    private fun project(
        items: List<ThreadItem>,
        roster: BackgroundTaskRoster? = null,
        queued: List<QueuedMessage> = emptyList(),
    ) = foldBackgroundAgentBlocks(foldQueuedRows(items, queued), items, roster)

    private fun keys(rows: List<ThreadRow>) = rows.mapIndexed { index, row -> row.listKey(index) }

    private fun message(
        rows: List<ThreadRow>,
        id: String,
    ) = rows
        .filterIsInstance<ThreadRow.Delivered>()
        .mapNotNull { (it.item as? ThreadItem.MessageItem)?.message }
        .single { it.id == id }

    @Test fun runningMovesLoadedFamilyAfterNewUsersAndQueueWithoutMutatingItems() {
        val items = listOf(row("a", "Agent"), start(), row("child", "Read", "a"), row("user"), row("grandchild", "Bash", "child"))
        val rows = project(items, queued = listOf(QueuedMessage(7, "queued", ts, "unknown")))
        assertEquals(listOf("agent-start:a", "msg:user", "queued-row:2", "msg:a", "msg:child", "msg:grandchild"), keys(rows))
        assertEquals(ToolCallStatus.Running, message(rows, "a").toolCall?.status)
        assertEquals(ToolCallStatus.Done, (items.first() as ThreadItem.MessageItem).message.toolCall?.status)
        assertEquals(mapOf("child" to 1, "grandchild" to 2), toolNestingDepths(items))
    }

    @Test fun finishesAtTerminalPositionAndSurvivesAnEmptyOrReplacingRoster() {
        val items = listOf(row("a", "Agent"), start(), row("before"), row("child", "Read", "a"), finish(), row("later"))
        for (roster in listOf(null, BackgroundTaskRoster(emptyList(), 0), roster("other"))) {
            val rows = project(items, roster)
            assertEquals(listOf("agent-start:a", "msg:before", "msg:a", "msg:child", "msg:later"), keys(rows))
            assertEquals(ToolCallStatus.Done, message(rows, "a").toolCall?.status)
        }
        for (status in listOf("failed", "cancelled", "future-terminal")) {
            assertEquals(
                ToolCallStatus.Done,
                message(
                    project(
                        items.map {
                            if (it is ThreadItem.BackgroundTaskLifecycle &&
                                it.terminal != null
                            ) {
                                finish(status = status)
                            } else {
                                it
                            }
                        },
                    ),
                    "a",
                ).toolCall?.status,
            )
        }
    }

    @Test fun runningBlocksUseRosterOrderThenKnownLaunchOrderAndFinishedBlocksSettleIndependently() {
        val base = listOf(row("b", "Agent"), row("a", "Agent"), row("b-child", "Read", "b"), row("a-child", "Read", "a"), row("new"))
        assertEquals(
            listOf("agent-start:b", "agent-start:a", "msg:new", "msg:a", "msg:a-child", "msg:b", "msg:b-child"),
            keys(project(base, roster("a", "b"))),
        )
        val started = listOf(base[0], start("task-b", "b"), base[1], start()) + base.drop(2)
        assertEquals(
            listOf("agent-start:b", "agent-start:a", "msg:new", "msg:b", "msg:b-child", "msg:a", "msg:a-child"),
            keys(project(started, roster("a", "b"))),
        )
        assertEquals(
            listOf("agent-start:b", "agent-start:a", "msg:new", "msg:b", "msg:b-child", "msg:later", "msg:a", "msg:a-child"),
            keys(
                project(
                    started + finish("task-b") + row("later"),
                    roster("a", "b"),
                ),
            ),
        )
    }

    @Test fun rosterBeforeHistoryJoinsOnceAndTerminalBeforeLaunchBackfillKeepsFinishNeighbors() {
        val newest = listOf(row("before"), finish(), row("after"), row("child", "Read", "a"))
        assertEquals(listOf("msg:before", "msg:after", "msg:child"), keys(project(newest, roster("a"))))
        val withAgent = listOf(row("a", "Agent")) + newest
        val before = project(withAgent, roster("a"))
        assertEquals(listOf("agent-start:a", "msg:before", "msg:a", "msg:child", "msg:after"), keys(before))
        val backfilled = listOf(row("older"), row("a", "Agent"), start()) + newest
        val after = project(backfilled, BackgroundTaskRoster(emptyList(), 0))
        assertEquals(listOf("msg:older") + keys(before), keys(after))
        assertEquals(keys(after), keys(project(backfilled))) // reload uses history alone
        assertEquals(keys(after).size, keys(after).toSet().size)
    }

    @Test fun missingJoinsWrongTypesAndForegroundAgentsStayInPlaceAndCyclesTerminate() {
        val base = listOf(row("a", "Agent"), row("child", "Read", "a"), row("later"))
        assertEquals(keys(foldQueuedRows(base, emptyList())), keys(project(base)))
        assertEquals(keys(foldQueuedRows(base, emptyList())), keys(project(base + start(type = "local_bash"))))
        assertEquals(keys(foldQueuedRows(base, emptyList())), keys(project(base + start(agent = "unknown"))))
        val wrong = listOf(row("a", "Bash"), start(), row("later"))
        assertEquals(listOf("msg:a", "msg:later"), keys(project(wrong)))
        val cyclic = listOf(row("a", "Agent"), start(), row("x", "Read", "y"), row("y", "Read", "x"), row("later"))
        assertEquals(listOf("agent-start:a", "msg:x", "msg:y", "msg:later", "msg:a"), keys(project(cyclic)))
    }

    @Test fun duplicateTaskJoinsCannotDuplicateAgentOrMarkerAndDescriptionsNeverBecomeKeys() {
        val tasks = roster("a").let { it.copy(tasks = it.tasks + it.tasks.first().copy(taskId = "other", description = "msg:evil")) }
        val rows = project(listOf(row("a", "Agent"), row("new")), tasks)
        assertEquals(listOf("agent-start:a", "msg:new", "msg:a"), keys(rows))
    }

    @Test fun movedBlocksCannotMergeWithOrdinaryToolsOrEachOtherWhenCollapsedOrExpanded() {
        val items =
            listOf(
                row("a", "Agent"),
                start(),
                row("b", "Agent"),
                start("task-b", "b"),
                row("ordinary", "Read"),
                row("a-child", "Read", "a"),
                row("b-child", "Read", "b"),
            )
        val projected = project(items)
        val folded = foldToolRuns(projected, emptySet())
        assertEquals(listOf("agent-start:a", "agent-start:b", "msg:ordinary", "tool-run:a", "tool-run:b"), keys(folded))
        val expanded = foldToolRuns(projected, setOf("a"))
        assertEquals(
            listOf("agent-start:a", "agent-start:b", "msg:ordinary", "tool-run:a", "msg:a", "msg:a-child", "tool-run:b"),
            keys(expanded),
        )
        assertTrue(expanded.filterIsInstance<ThreadRow.ToolRun>().first().expanded)
    }

    @Test fun finishedRosterWithoutHistoryKeepsMarkerBeforeAgentAndTerminalEvidenceWinsLater() {
        val finishedRoster = roster("a").let { it.copy(tasks = it.tasks.map { task -> task.copy(isFinished = true) }) }
        val base = listOf(row("a", "Agent"), row("later"))
        assertEquals(listOf("agent-start:a", "msg:a", "msg:later"), keys(project(base, finishedRoster)))
        assertEquals(listOf("agent-start:a", "msg:later", "msg:a"), keys(project(base + finish(agent = "a"), finishedRoster)))
    }
}
