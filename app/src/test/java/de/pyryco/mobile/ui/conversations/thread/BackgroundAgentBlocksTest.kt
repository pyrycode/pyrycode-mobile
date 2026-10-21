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
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            toolCall =
                name?.let {
                    ToolCall(
                        it,
                        "input",
                        "Async agent launched",
                        inputFields =
                            if (it == "Agent" ||
                                it == "Task"
                            ) {
                                mapOf("run_in_background" to "true")
                            } else {
                                emptyMap()
                            },
                        parentToolUseId = parent,
                    )
                },
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

    @Test fun invariantLoadedParentChainsOwnEverySiblingPermutationOnce() {
        val children = listOf(row("first", "Read", "a"), row("nested", "Read", "parent"), row("parent", "Agent", "a"))
        val orders = listOf(children, children.reversed(), listOf(children[1], children[0], children[2]))
        for (siblings in orders) {
            for (rootFirst in listOf(true, false)) {
                val roots = listOf(row("a", "Agent"), start(), row("b", "Agent"), start("task-b", "b"))
                val others = listOf(row("foreground", "Read"), row("foreign", "Read", "b"), row("reply"))
                val items = if (rootFirst) roots + siblings + others else siblings + others + roots
                val projected = project(items)
                val owned = projected.filterIsInstance<ThreadRow.Delivered>().filter { it.agentBlockId == "a" }
                assertEquals(
                    items.filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }.filter {
                        it in
                            listOf("a", "first", "nested", "parent")
                    },
                    owned.map { (it.item as ThreadItem.MessageItem).message.id },
                )
                assertEquals(4, owned.size)
                assertEquals(projected.size, keys(projected).toSet().size)
                assertEquals(2, toolNestingDepths(items)["nested"])
                // Parent loading order never moves a foreground or the other Agent's child into a.
                assertNull(
                    projected
                        .filterIsInstance<ThreadRow.Delivered>()
                        .single {
                            (it.item as ThreadItem.MessageItem).message.id ==
                                "foreground"
                        }.agentBlockId,
                )
                assertEquals(
                    "b",
                    projected
                        .filterIsInstance<ThreadRow.Delivered>()
                        .single {
                            (it.item as ThreadItem.MessageItem).message.id ==
                                "foreign"
                        }.agentBlockId,
                )
            }
        }
        for (siblings in listOf(emptyList(), children.take(1))) {
            val items = listOf(row("a", "Agent"), start(), row("reply")) + siblings
            val projected = project(items)
            assertEquals(
                listOf("a") + siblings.map { it.message.id },
                projected
                    .filterIsInstance<ThreadRow.Delivered>()
                    .filter {
                        it.agentBlockId ==
                            "a"
                    }.map { (it.item as ThreadItem.MessageItem).message.id },
            )
            assertTrue(foldToolRuns(projected, emptySet()).none { it is ThreadRow.ToolRun })
        }
    }

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
        assertEquals(listOf("agent-start:a", "msg:user", "queued-row:7:0", "msg:a", "msg:child", "msg:grandchild"), keys(rows))
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

    @Test fun partiallyLoadedLaunchHistoryKeepsTheUnknownAgentInRosterOrder() {
        val items = listOf(row("a", "Agent"), row("b", "Agent"), start("task-b", "b"), row("new"))
        assertEquals(
            listOf("agent-start:a", "agent-start:b", "msg:new", "msg:a", "msg:b"),
            keys(project(items, roster("a", "b"))),
        )
        val backfilled = listOf(start()) + items
        assertEquals(keys(project(items, roster("a", "b"))), keys(project(backfilled, roster("a", "b"))))
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
                row("a-child1", "Read", "a"),
                row("a-child2", "Read", "a"),
                row("b-child1", "Read", "b"),
                row("b-child2", "Read", "b"),
            )
        val projected = project(items)
        val folded = foldToolRuns(projected, emptySet())
        // Each root draws as itself (#1827 follow-up); only its own two-or-more-child run collapses, and
        // that run never merges with the ordinary tool row or with the other block's rows.
        assertEquals(
            listOf("agent-start:a", "agent-start:b", "msg:ordinary", "msg:a", "msg:a-child1", "msg:b", "msg:b-child1"),
            keys(folded),
        )
        val expanded = foldToolRuns(projected, setOf("a-child1"))
        assertEquals(
            listOf(
                "agent-start:a",
                "agent-start:b",
                "msg:ordinary",
                "msg:a",
                "tool-run:a-child1",
                "msg:a-child1",
                "msg:a-child2",
                "msg:b",
                "msg:b-child1",
            ),
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

    @Test fun foregroundOrUnknownAgentLaunchDoesNotMoveEvenWhenItReportsALocalAgentTask() {
        for (fields in listOf(emptyMap(), mapOf("run_in_background" to "false"))) {
            val agent =
                row(
                    "a",
                    "Agent",
                ).let { it.copy(message = it.message.copy(toolCall = it.message.toolCall?.copy(inputFields = fields))) }
            val items = listOf(agent, start(), row("newer"))
            assertEquals(listOf("msg:a", "msg:newer"), keys(project(items)))
        }
    }

    @Test fun openRunIntentWaitsForALoneMovedAgentUntilItsChildFormsARun() {
        val ordinary = listOf(row("o", "Grep"), row("a", "Agent"), row("newer"))
        val joined = ordinary + start()
        val grown = joined + row("c", "Read", "a")
        val split = carryRunExpansion(project(ordinary), project(joined), setOf("o"), emptySet())
        assertEquals(setOf("o"), split.expandedRuns)
        assertEquals(setOf("a"), split.pending)
        val idle = carryRunExpansion(project(joined), project(joined), split.expandedRuns, split.pending)
        assertEquals(split, idle)
        // "a" itself can never join a run (#1827 follow-up: the root always draws as itself), and one
        // child is not enough to form a run either, so "a"'s parked intent has nothing left to resolve
        // into — it stays pending, harmlessly, since the block never collapses behind it.
        val formed = carryRunExpansion(project(joined), project(grown), idle.expandedRuns, idle.pending)
        assertEquals(setOf("o"), formed.expandedRuns)
        assertEquals(setOf("a"), formed.pending)
        // Closing the formed run afterwards sticks: no block change, nothing pending.
        assertEquals(setOf("o"), carryRunExpansion(project(grown), project(grown), setOf("o"), emptySet()).expandedRuns)
    }

    @Test fun parentBackfillCarriesAnOpenRunOfLoadedDescendantsIntoTheAgentBlock() {
        val orphans = listOf(row("o", "Grep"), row("c1", "Read", "a"), row("c2", "Glob", "a"), row("newer"))
        val backfilled = listOf(row("a", "Agent"), start()) + orphans
        val carried = carryRunExpansion(project(orphans), project(backfilled), setOf("o"), emptySet())
        // Before the backfill, "o"/"c1"/"c2" were one ordinary run under "o". After, "a" owns c1/c2 as its
        // own block, so the open intent follows them to their new run id, "c1" — never to "a" itself, which
        // (#1827 follow-up) is never a run id.
        assertEquals(setOf("o", "c1"), carried.expandedRuns)
        assertEquals(emptySet<String>(), carried.pending)
        val closed = carryRunExpansion(project(orphans), project(backfilled), emptySet(), emptySet())
        assertEquals(emptySet<String>(), closed.expandedRuns)
    }
}
