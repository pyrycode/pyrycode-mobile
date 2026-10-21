package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.first
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
    ) = fixtureBackgroundRows(fixtureQueuedRows(items, queued), items, roster)

    private fun keys(rows: List<ThreadRow>) = rows.mapIndexed { index, row -> row.listKey(index) }

    private fun message(
        rows: List<ThreadRow>,
        id: String,
    ) = rows
        .filterIsInstance<ThreadRow.Delivered>()
        .mapNotNull { (it.item as? ThreadItem.MessageItem)?.message }
        .single { it.id == id }

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
