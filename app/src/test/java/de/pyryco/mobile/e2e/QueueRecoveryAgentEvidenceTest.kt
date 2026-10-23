package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativePendingSend
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.foldToolRuns
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2174: the queue, send-now, replay-order and background-agent scenario assertions over daemon facts. */
class QueueRecoveryAgentEvidenceTest {
    private val connection = Any()

    private fun snapshot(vararg items: ThreadItemDto) = scenarioSnapshot(items.toList())

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
    ) = authoritativeThreadEvidence(host, "chat", connection, snapshot)

    private fun evidence(vararg items: ThreadItemDto) = evidence(snapshot(*items))

    // --- queued follow-up -------------------------------------------------------------------------

    private val heldItems =
        arrayOf(
            scenarioUser(1, "hello"),
            scenarioItem(2, text = "Hello, streamed ", rev = 2, active = true),
            scenarioUser(3, "bye", "queued", order = null),
        )

    @Test fun queuedFollowUpNeverSplitsOrSettlesTheHeldReply() {
        val start = evidence(*heldItems)
        val held = requireNotNull(start.heldOpenReply(start.owner, "Hello, streamed"))
        assertNull(start.heldOpenReply(start.owner, "Hello"))
        assertNull(start.settledHeldReply(start.owner, held, "Hello, streamed "))
        val queued = evidence(heldItems[0], scenarioItem(2, text = "Hello, streamed ", rev = 3, active = true), heldItems[2])
        assertTrue(queued.holdsOpen(start.owner, held))
        assertEquals("a queued receipt is not delivery", 0, queued.userEchoCount("bye"))
        val done = scenarioItem(2, text = "Hello, streamed world", rev = 4)
        val finished = evidence(heldItems[0], done, heldItems[2])
        assertFalse(finished.holdsOpen(start.owner, held))
        assertEquals("Hello, streamed world", finished.settledHeldReply(start.owner, held, "Hello, streamed world")?.fullText)
        assertNull(finished.settledHeldReply(start.owner, held, "Hello, streamed"))
        for (bad in listOf(
            // A suffix row in the same turn splits the reply.
            evidence(heldItems[0], done, scenarioItem(4, text = "world")),
            evidence(heldItems[0], scenarioItem(2, text = "Hello, streamed world", rev = 4, active = true)),
            evidence(heldItems[0], scenarioItem(2, text = "Hello, streamed world", rev = 4, status = "running")),
            // A completed fact needs a newer revision than the held one.
            evidence(heldItems[0], scenarioItem(2, text = "Hello, streamed world", rev = 2)),
            evidence(snapshot(heldItems[0], done).copy(repairNeeded = true)),
        )) {
            assertNull(bad.settledHeldReply(start.owner, held, "Hello, streamed world"))
        }
    }

    @Test fun newerRevisionsSurviveRecoveryForEveryScenario() {
        val start = evidence(*heldItems)
        val held = requireNotNull(start.heldOpenReply(start.owner, "Hello, streamed"))
        // A recovered page re-delivers an older revision after the newer completion.
        val recovered =
            evidence(
                heldItems[0],
                scenarioItem(2, text = "Hello, streamed world", rev = 4),
                scenarioItem(2, text = "Hello, streamed ", rev = 3, active = true),
            )
        assertNotNull(recovered.settledHeldReply(start.owner, held, "Hello, streamed world"))
        val replay = evidence(*replayItems, scenarioItem(4, text = "char", rev = 2, active = true))
        assertTrue(replay.hasCompletedReplay(ORDERED))
        assertNull(replay.authoritativeReplayFailure(ORDERED, "hello"))
        val agent =
            evidence(
                *finishedAgent,
                scenarioItem(2, "agent", rev = 1, active = true),
                scenarioItem(8, "tool_call", rev = 1, active = true, parent = 2),
            )
        assertTrue(agent.settledAgentPrecedes(agent.owner, requireNotNull(agent.agentFamily(agent.owner)), "after1783"))
        val sendNow = evidence(*sendNowItems, scenarioItem(2, "tool_call", rev = 1, active = true))
        assertNull(sendNow.sendNowPlacementFailure("bye", "send-now-marker"))
    }

    @Test fun wrongHostCollidingIdsSatisfyNoScenario() {
        val start = evidence(*heldItems)
        val held = requireNotNull(start.heldOpenReply(start.owner, "Hello, streamed"))
        val other = evidence(snapshot(heldItems[0], scenarioItem(2, text = "Hello, streamed world", rev = 4)), host = "other")
        assertNull(other.settledHeldReply(start.owner, held, "Hello, streamed world"))
        assertFalse(evidence(snapshot(*heldItems), host = "other").holdsOpen(start.owner, held))
        assertNull(other.heldOpenReply(start.owner, "Hello, streamed"))
        val running = evidence(*runningAgent)
        val family = requireNotNull(running.agentFamily(running.owner))
        val elsewhere = evidence(snapshot(*finishedAgent), host = "other")
        assertNull(elsewhere.agentFamily(running.owner))
        assertFalse(elsewhere.settledAgentPrecedes(running.owner, family, "after1783"))
        // Own owner, other block identity: colliding raw item IDs do not collide rendered identities.
        assertFalse(elsewhere.settledAgentPrecedes(elsewhere.owner, family, "after1783"))
        assertNotEquals(family.agentId, requireNotNull(elsewhere.agentFamily(elsewhere.owner)).agentId)
    }

    // --- send now ---------------------------------------------------------------------------------

    private val sendNowItems =
        arrayOf(scenarioUser(1, "hello"), scenarioItem(2, "tool_call"), scenarioUser(3, "bye"), scenarioItem(4, text = "send-now-marker"))

    @Test fun sendNowNeedsDeliveredEchoAfterCompletedTool() {
        assertNull(evidence(*sendNowItems).sendNowPlacementFailure("bye", "send-now-marker"))
        val (hello, tool, bye, reply) = sendNowItems
        for (bad in listOf(
            evidence(hello, tool, scenarioUser(3, "bye", "queued", order = null), reply),
            evidence(snapshot(hello, tool, reply).copy(pendingSends = listOf(AuthoritativePendingSend("device", legacyUser("bye"))))),
            evidence(hello, scenarioItem(2, "tool_call", order = 5), bye, scenarioItem(4, text = "send-now-marker", order = 6)),
            evidence(hello, scenarioItem(2, "tool_call", active = true), bye, reply),
            evidence(hello, tool, bye, scenarioUser(5, "bye"), scenarioItem(6, text = "send-now-marker")),
            evidence(hello, tool, bye, scenarioItem(4, text = "send-now-marker", active = true)),
            evidence(hello, tool, scenarioItem(4, text = "send-now-marker"), scenarioUser(5, "bye")),
            evidence(snapshot(*sendNowItems).copy(resetNeeded = true)),
        )) {
            assertNotNull(bad.sendNowPlacementFailure("bye", "send-now-marker"))
        }
    }

    // --- replay order -----------------------------------------------------------------------------

    private val replayItems = replayScenarioItems().toTypedArray()

    @Test fun replayCannotBeCertifiedByARetiredOrDifferentSource() {
        val snap = snapshot(*replayItems)
        val held = evidence(snap)
        assertTrue(held.isCurrentReplay("host", "chat", connection, snap))
        assertFalse(held.isCurrentReplay("host", "chat", Any(), snap))
        assertFalse(held.isCurrentReplay("host", "chat", null, snap))
        assertFalse(held.isCurrentReplay("other", "chat", connection, snap))
        assertFalse(held.isCurrentReplay("host", "other", connection, snap))
        assertFalse(held.isCurrentReplay("host", "chat", connection, snap.copy()))
        assertFalse(held.isCurrentReplay("host", "chat", connection, null))
        val replaced = snap.copy(generation = snap.generation + 1)
        assertFalse(held.isCurrentReplay("host", "chat", connection, replaced))
        assertTrue(evidence(replaced).isCurrentReplay("host", "chat", connection, replaced))
        val broken = snap.copy(resetNeeded = true)
        assertFalse(evidence(broken).isCurrentReplay("host", "chat", connection, broken))
    }

    @Test fun replayRecoveryRequiresCompleteOrderedSettledItems() {
        assertTrue(evidence(*replayItems).hasCompletedReplay(ORDERED))
        assertNull(evidence(*replayItems).authoritativeReplayFailure(ORDERED, "hello"))
        assertNull(evidence(scenarioUser(1, "hello"), scenarioItem(2, text = ORDERED)).authoritativeReplayFailure(ORDERED, "hello"))
        val (hello, alpha, bravo, charlie) = replayItems
        for (bad in listOf(
            evidence(hello, alpha, charlie),
            evidence(hello, scenarioItem(2, text = "bravo "), scenarioItem(3, text = "alpha "), charlie),
            evidence(hello, alpha, bravo, charlie, scenarioItem(5, text = "charlie")),
            evidence(hello, alpha, bravo, scenarioItem(4, text = "charlie", active = true)),
            evidence(snapshot(*replayItems).copy(epoch = null)),
        )) {
            assertFalse(bad.hasCompletedReplay(ORDERED))
            assertNotNull(bad.authoritativeReplayFailure(ORDERED, "hello"))
        }
        for (bad in listOf(
            evidence(hello, alpha, bravo, scenarioItem(4, text = "charlie", turn = "other")),
            evidence(hello, scenarioUser(5, "hello"), alpha, bravo, charlie),
            evidence(alpha, bravo, charlie, scenarioUser(5, "hello")),
            evidence(scenarioUser(1, "hello", "queued", order = null), alpha, bravo, charlie),
        )) {
            assertNotNull(bad.authoritativeReplayFailure(ORDERED, "hello"))
        }
    }

    // --- background agent -------------------------------------------------------------------------

    private val runningAgent = runningAgentScenarioItems().toTypedArray()

    private val finishedAgent = finishedAgentScenarioItems().toTypedArray()

    @Test fun agentFamilyUsesRenderedIdentitiesNotRawJoins() {
        val snap = snapshot(*finishedAgent)
        val held = evidence(snap)
        val family = requireNotNull(held.agentFamily(held.owner))
        val projection = projectAuthoritativeThread("host", "chat", snap)
        val run = foldToolRuns(projection.rows, emptySet()).filterIsInstance<ThreadRow.ToolRun>().single()
        assertEquals("${projection.scope}:2", family.agentId)
        assertEquals(run.runId, family.runId)
        assertEquals(listOf(3L, 6L, 7L, 8L, 9L).map { "${projection.scope}:$it" }, family.childIds)
        assertEquals(3, family.tools.size)
        assertTrue(family.ownsSingleProse("child1827-after"))
        assertTrue(family.ownsSingleProse("child1827-before"))
        assertFalse(family.ownsSingleProse("unmatched1827"))
        assertTrue(held.holdsOutsideAgent("unmatched1827", family))
        assertTrue(held.holdsOutsideAgent("main1827", family))
        assertFalse(held.holdsOutsideAgent("child1827-after", family))
        assertTrue(held.settledAgentPrecedes(held.owner, family, "after1783"))
        val running = evidence(*runningAgent)
        val early = requireNotNull(running.agentFamily(running.owner))
        assertEquals(family.agentId, early.agentId)
        assertEquals(family.runId, early.runId)
        assertEquals(3, early.tools.size)
    }

    @Test fun activeOrIncompleteAgentEvidenceNeverSettles() {
        val running = evidence(*runningAgent)
        val family = requireNotNull(running.agentFamily(running.owner))
        assertFalse(running.settledAgentPrecedes(running.owner, family, "after1783"))
        val head = runningAgent.copyOfRange(0, 7)
        val prose = scenarioItem(9, text = "child1827-after", parent = 2)
        val reply = scenarioItem(10, text = "after1783", order = 12)
        val finished = scenarioItem(2, "agent", rev = 11, status = "finished", endedOrder = 11)
        val doneTool = scenarioItem(8, "tool_call", rev = 10, parent = 2)
        for (bad in listOf(
            // The agent is still active, so its family follows the main reply.
            evidence(*head, scenarioItem(2, "agent", rev = 11, active = true), doneTool, prose, reply),
            evidence(*head, finished, scenarioItem(8, "tool_call", rev = 10, parent = 2, active = true), prose, reply),
            evidence(*head, finished, scenarioItem(8, "tool_call", rev = 10, parent = 2, turn = "other"), prose, reply),
            evidence(*head, scenarioItem(2, "agent", rev = 11, status = "finished", endedOrder = 13), doneTool, prose, reply),
            evidence(snapshot(*finishedAgent).copy(repairNeeded = true)),
        )) {
            assertFalse(bad.settledAgentPrecedes(running.owner, family, "after1783"))
        }
        assertNull(evidence(snapshot(*finishedAgent).copy(resetNeeded = true)).let { it.agentFamily(it.owner) })
    }

    // --- legacy -----------------------------------------------------------------------------------

    private fun legacyUser(text: String) = Message("user-$text", "session", Role.User, text, Instant.fromEpochMilliseconds(0), false)

    private fun legacyReply(
        id: String,
        text: String,
        lengths: List<Int>,
        streaming: Boolean = false,
        parent: String = "",
    ) = Message(
        id,
        "session",
        Role.Assistant,
        text,
        Instant.fromEpochMilliseconds(0),
        streaming,
        segment = AssistantSegment("turn", lengths.mapIndexed { seq, length -> SegmentDelta(seq, length) }),
        parentToolUseId = parent,
    )

    private fun legacyTool(
        id: String,
        name: String = "Read",
        parent: String = "",
        status: ToolCallStatus = ToolCallStatus.Done,
    ) = Message(
        id,
        "session",
        Role.Tool,
        "",
        Instant.fromEpochMilliseconds(0),
        false,
        toolCall =
            ToolCall(
                name,
                "",
                "",
                status,
                inputFields = if (name == "Agent") mapOf("run_in_background" to "true") else emptyMap(),
                parentToolUseId = parent,
            ),
    )

    private fun legacy(vararg items: Any) =
        legacyThreadEvidence(
            null,
            "chat",
            connection,
            items.map { if (it is Message) ThreadItem.MessageItem(it) else it as ThreadItem },
        )

    @Test fun legacyEvidenceKeepsExistingChecks() {
        val start = legacy(legacyUser("hello"), legacyReply("seg", "Hello, streamed ", listOf(7, 9), streaming = true))
        val held = requireNotNull(start.heldOpenReply(start.owner, "Hello, streamed"))
        val done = legacyReply("seg", "Hello, streamed world", listOf(7, 9, 5))
        assertNotNull(legacy(legacyUser("hello"), done).settledHeldReply(start.owner, held, "Hello, streamed world"))
        assertNull(
            legacy(
                legacyUser("hello"),
                done.copy(content = "Hello, streamed wor"),
            ).settledHeldReply(start.owner, held, "Hello, streamed wor"),
        )
        assertNull(
            legacy(legacyUser("hello"), legacyReply("seg", "Hello, streamed ", listOf(7, 9)), legacyReply("suffix", "world", listOf(5)))
                .settledHeldReply(start.owner, held, "Hello, streamed "),
        )
        val sendNow =
            arrayOf(legacyUser("hello"), legacyTool("toolu_e2e"), legacyUser("bye"), legacyReply("r", "send-now-marker", listOf(15)))
        assertNull(legacy(*sendNow).sendNowPlacementFailure("bye", "send-now-marker"))
        assertNotNull(
            legacy(
                sendNow[0],
                legacyTool("toolu_e2e", status = ToolCallStatus.Running),
                sendNow[2],
                sendNow[3],
            ).sendNowPlacementFailure("bye", "send-now-marker"),
        )
        val replay = listOf(legacyUser("hello"), legacyReply("a", "alpha ", listOf(6)), legacyReply("b", "bravo charlie", listOf(6, 7)))
        val rows = replay.map { ThreadItem.MessageItem(it) }
        assertEquals(rows.hasCompletedReplay(ORDERED), legacy(*replay.toTypedArray()).hasCompletedReplay(ORDERED))
        assertTrue(legacy(*replay.toTypedArray()).hasCompletedReplay(ORDERED))
        assertNotNull(legacy(*replay.toTypedArray()).authoritativeReplayFailure(ORDERED, "hello"))
        val agent =
            legacy(
                legacyTool("agent1783", "Agent"),
                ThreadItem.BackgroundTaskLifecycle("task1783", Instant.fromEpochMilliseconds(0), "agent1783", "Agent", "local_agent"),
                legacyReply("before", "child1827-before", listOf(16), parent = "agent1783"),
                legacyReply("unmatched", "unmatched1827", listOf(13), parent = "unknown1827"),
                legacyTool("child1783", parent = "agent1783"),
                legacyTool("late1951-one", parent = "agent1783"),
                legacyTool("late1951-two", parent = "agent1783"),
                legacyReply("after", "child1827-after", listOf(15), parent = "agent1783"),
            )
        val family = requireNotNull(agent.agentFamily(agent.owner))
        assertEquals("agent1783", family.agentId)
        assertEquals("child1783", family.runId)
        assertEquals(listOf("before", "child1783", "late1951-one", "late1951-two", "after"), family.childIds)
        assertTrue(family.ownsSingleProse("child1827-after"))
        assertTrue(agent.holdsOutsideAgent("unmatched1827", family))
    }

    private companion object {
        const val ORDERED = "alpha bravo charlie"
    }
}
