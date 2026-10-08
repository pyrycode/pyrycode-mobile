package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.repository.BackgroundTaskProjection
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest
import de.pyryco.mobile.data.repository.FinishedBackgroundTasks
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadProjection
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    @Test fun lifecycleChangingAgentProjectionRequiresThatPresentedVersion() {
        val root =
            row("a", "Agent").let {
                it.copy(message = it.message.copy(toolCall = it.message.toolCall?.copy(status = ToolCallStatus.Running)))
            }
        val launch = start()
        val reply = row("reply")
        val ended = finish(agent = "a").copy(taskType = "local_agent")
        val items = listOf(root, launch, reply, ended)
        val evidence =
            ThreadReadEvidence(
                versions = mapOf(root to setOf(1u), launch to setOf(2u), reply to setOf(3u), ended to setOf(4u)),
                facts = mapOf(1uL to false, 2uL to true, 3uL to false, 4uL to true),
            )
        val rows = foldBackgroundAgentBlocks(foldQueuedRows(items, emptyList()), items, null)
        val actual = rows.filterIsInstance<ThreadRow.Delivered>().first { it.agentBlockId == "a" }.item
        val qualified = evidence.forBackgroundAgentRows(items, rows)
        assertEquals(3uL, qualified.checkpoint(reply, 0u))
        assertNull(qualified.checkpoint(root, 0u))
        assertEquals(4uL, qualified.checkpoint(actual, 0u))
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

    @Test fun rosterOnlyJoinSurvivesFinishRosterReplacementAndLaunchBackfill() =
        runTest {
            for (terminalFirst in listOf(false, true)) {
                val projection = ThreadProjection()
                val initial = listOf(row("a", "Agent"), row("child", "Read", "a"), row("before"))
                projection.appendMessages(initial.map { "c1" to it.message })
                val terminal = BackgroundTaskProjectionTest.terminal("task-a", "completed")
                val roster =
                    BackgroundTaskProjectionTest.rosterFrame(
                        listOf(
                            """{"task_id":"task-a","tool_call_id":"a","task_type":"local_agent","description":"Roster description","truncated_fields":null}""",
                        ),
                    )
                if (terminalFirst) projection.applyBackgroundTaskLifecycle(terminal)
                projection.applyBackgroundTaskLifecycle(roster)
                if (!terminalFirst) {
                    assertEquals(initial, projection.observe("c1").first()) // A roster supplies no launch position.
                    projection.applyBackgroundTaskLifecycle(terminal)
                }
                projection.appendMessages(listOf("c1" to row("later").message))
                val expected = listOf("agent-start:a", "msg:before", "msg:a", "msg:child", "msg:later")
                for (replacement in listOf(emptyList(), listOf(BackgroundTaskProjectionTest.row("other")))) {
                    projection.applyBackgroundTaskLifecycle(BackgroundTaskProjectionTest.rosterFrame(replacement))
                    val retained = projection.observe("c1").first()
                    assertEquals(expected, keys(project(retained)))
                    val finish = retained.filterIsInstance<ThreadItem.BackgroundTaskLifecycle>().single()
                    assertEquals("a", finish.toolCallId)
                    assertEquals("local_agent", finish.taskType)
                    assertEquals(ToolCallStatus.Done, message(project(retained), "a").toolCall?.status)
                }
                val launch =
                    BackgroundTaskProjectionTest.envelope(
                        "background_task_started",
                        """{"conversation_id":"c1","task_id":"task-a","tool_call_id":"a","task_type":"local_agent","description":"Launch description","truncated_fields":null}""",
                    )
                val before =
                    BackgroundTaskProjectionTest.envelope(
                        "message",
                        """{"conversation_id":"c1","message_id":"before","role":"user","text":"before"}""",
                    )
                val page =
                    HistoryPage(
                        listOf(HistoryEntry(2, before.type, before.payload, ts), HistoryEntry(1, launch.type, launch.payload, ts)),
                        "",
                        true,
                    )
                projection.mergeHistoryPage("c1", page, true)
                val backfilled = projection.observe("c1").first()
                assertEquals(expected, keys(project(backfilled)))
                assertEquals("Launch description", project(backfilled).filterIsInstance<ThreadRow.AgentStartMarker>().single().description)
                projection.mergeHistoryPage("c1", page, true)
                assertEquals(backfilled, projection.observe("c1").first())
                projection.remove("c1")
                projection.applyBackgroundTaskLifecycle(terminal)
                assertEquals(
                    null,
                    projection
                        .observe("c1")
                        .first()
                        .filterIsInstance<ThreadItem.BackgroundTaskLifecycle>()
                        .single()
                        .toolCallId,
                )
            }
        }

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

    @Test fun emptyRosterKeepsFinishedBlockWithStartHistory() = finishedRosterReplacement(true, false)

    @Test fun unrelatedRosterKeepsFinishedBlockWithStartHistory() = finishedRosterReplacement(true, true)

    @Test fun emptyRosterKeepsFinishedBlockWithoutStartHistory() = finishedRosterReplacement(false, false)

    @Test fun unrelatedRosterKeepsFinishedBlockWithoutStartHistory() = finishedRosterReplacement(false, true)

    @Test fun emptyReplacementRetainsLateJoinWithoutLifecycleHistory() = lateFinishedJoinReplacement(false)

    @Test fun unrelatedReplacementRetainsLateJoinWithoutLifecycleHistory() = lateFinishedJoinReplacement(true)

    private fun lateFinishedJoinReplacement(unrelated: Boolean) =
        runTest {
            val finished = FinishedBackgroundTasks().apply { mark("c1", "task-a") }
            val tasks = BackgroundTaskProjection(finished)
            val thread = ThreadProjection()
            val loaded = listOf(row("a", "Agent"), row("child", "Read", "a"), row("later"))
            thread.appendMessages(loaded.map { "c1" to it.message })
            val unknownJoin =
                """{"task_id":"task-a","task_type":"local_agent","description":"Roster description","truncated_fields":null}"""
            val replacement =
                BackgroundTaskProjectionTest.rosterFrame(
                    if (unrelated) listOf(BackgroundTaskProjectionTest.row("other")) else emptyList(),
                )

            fun apply(frame: Envelope) {
                tasks.apply(frame)
                thread.applyBackgroundTaskLifecycle(frame)
            }

            suspend fun display() = project(thread.observe("c1").first(), tasks.rosters.value["c1"])

            apply(BackgroundTaskProjectionTest.rosterFrame(listOf(unknownJoin)))
            apply(replacement)
            assertTrue(!finished.contains("c1", "task-a"))
            val joined = unknownJoin.replace("\"task_type\"", "\"tool_call_id\":\"a\",\"task_type\"")
            apply(BackgroundTaskProjectionTest.rosterFrame(listOf(joined)))
            assertTrue(!checkNotNull(tasks.rosters.value["c1"]).tasks.single().isFinished)
            val fallback = listOf("agent-start:a", "msg:a", "msg:child", "msg:later")
            assertEquals(fallback, keys(display()))
            assertTrue(display().filterIsInstance<ThreadRow.AgentStartMarker>().single().finished)

            // No terminal/start frame can rescue the late join when the panel replaces it again.
            apply(replacement)
            val retained = display()
            assertEquals(fallback, keys(retained))
            assertTrue(retained.filterIsInstance<ThreadRow.AgentStartMarker>().single().finished)
            assertEquals(ToolCallStatus.Done, message(retained, "a").toolCall?.status)
            assertEquals(
                listOf("a", "a"),
                retained.filterIsInstance<ThreadRow.Delivered>().filter { it.isToolRow() }.map { it.agentBlockId },
            )
            // "a" always draws as itself, and its one "child" never meets the two-row fold threshold either
            // (#1827 follow-up), so the block shows fully with no collapsible run at all.
            assertEquals(listOf("agent-start:a", "msg:a", "msg:child", "msg:later"), keys(foldToolRuns(retained, emptySet())))
            assertEquals(loaded, thread.observe("c1").first())
            assertEquals(
                if (unrelated) listOf("other") else emptyList<String>(),
                tasks.rosters.value["c1"]
                    ?.tasks
                    ?.map { it.taskId },
            )
            assertEquals(if (unrelated) 1 else 0, tasks.rosters.value["c1"]?.liveCount)
        }

    private fun finishedRosterReplacement(
        startLoaded: Boolean,
        unrelated: Boolean,
    ) = runTest {
        // A reconnect knows the task finished but has not loaded its terminal history position.
        val finished = FinishedBackgroundTasks().apply { mark("c1", "task-a") }
        val tasks = BackgroundTaskProjection(finished)
        val thread = ThreadProjection()
        val initial = listOf(row("a", "Agent"), row("child", "Read", "a"), row("later"))
        thread.appendMessages(initial.map { "c1" to it.message })
        val launch =
            BackgroundTaskProjectionTest.envelope(
                "background_task_started",
                """{"conversation_id":"c1","task_id":"task-a","tool_call_id":"a","task_type":"local_agent","description":"Launch description","truncated_fields":null}""",
            )
        val launchPage = HistoryPage(listOf(HistoryEntry(1, launch.type, launch.payload, ts)), "", true)
        if (startLoaded) thread.mergeHistoryPage("c1", launchPage, true)
        val roster =
            BackgroundTaskProjectionTest.rosterFrame(
                listOf(
                    """{"task_id":"task-a","tool_call_id":"a","task_type":"local_agent","description":"Roster description","truncated_fields":null}""",
                ),
            )
        tasks.apply(roster)
        thread.applyBackgroundTaskLifecycle(roster)

        suspend fun display() = project(thread.observe("c1").first(), tasks.rosters.value["c1"])
        val fallback = listOf("agent-start:a", "msg:a", "msg:child", "msg:later")
        assertEquals(fallback, keys(display()))
        val replacement =
            BackgroundTaskProjectionTest.rosterFrame(
                if (unrelated) listOf(BackgroundTaskProjectionTest.row("other")) else emptyList(),
            )
        tasks.apply(replacement)
        thread.applyBackgroundTaskLifecycle(replacement)
        assertEquals(fallback, keys(display()))
        assertTrue(display().filterIsInstance<ThreadRow.AgentStartMarker>().single().finished)
        assertEquals(ToolCallStatus.Done, message(display(), "a").toolCall?.status)
        assertEquals(
            if (unrelated) listOf("other") else emptyList<String>(),
            tasks.rosters.value["c1"]
                ?.tasks
                ?.map { it.taskId },
        )
        assertEquals(
            if (startLoaded) 1 else 0,
            thread
                .observe("c1")
                .first()
                .filterIsInstance<ThreadItem.BackgroundTaskLifecycle>()
                .size,
        )

        // Late start/replayed running roster cannot undo finished knowledge or create a finish position.
        thread.mergeHistoryPage("c1", launchPage, true)
        tasks.apply(roster)
        thread.applyBackgroundTaskLifecycle(roster)
        assertEquals(fallback, keys(display()))
        val terminal = BackgroundTaskProjectionTest.terminal("task-a", "completed")
        val later =
            BackgroundTaskProjectionTest.envelope(
                "message",
                """{"conversation_id":"c1","message_id":"later","role":"user","text":"later"}""",
            )
        val terminalPage =
            HistoryPage(
                listOf(HistoryEntry(10, terminal.type, terminal.payload, ts), HistoryEntry(9, later.type, later.payload, ts)),
                "",
                true,
            )
        thread.mergeHistoryPage("c1", terminalPage, true)
        val settled = listOf("agent-start:a", "msg:later", "msg:a", "msg:child")
        assertEquals(settled, keys(display()))
        tasks.apply(replacement)
        thread.applyBackgroundTaskLifecycle(replacement)
        thread.mergeHistoryPage("c1", terminalPage, true)
        assertEquals(settled, keys(display()))
        assertEquals(settled, keys(project(thread.observe("c1").first()))) // Reload needs history alone.
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
