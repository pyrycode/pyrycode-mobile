package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.foldToolRuns
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2173: the background-agent scenario's waits and joins over both evidence sources. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundAgentEvidenceTest {
    private val connection = Any()

    private fun fact(
        id: Long,
        kind: String,
        rev: Long = id,
        parent: Long? = null,
        toolUseId: String? = null,
        active: Boolean = false,
        status: String = "done",
        turn: String = "main-turn",
        session: String = "session",
        text: String = "Reply",
        ordered: Boolean = true,
    ) = ThreadItemDto.decode(
        buildJsonObject {
            put("id", id)
            put("rev", rev)
            put("kind", kind)
            if (ordered) put("order", id)
            put("session", session)
            put("turn", turn)
            put("agent", "claude")
            parent?.let { put("parent", it) }
            put("status", status)
            put("active", active)
            put("shown", true)
            put("summary", "fallback")
            putJsonObject("content") {
                put("text", text)
                put("name", if (kind == "agent") "Agent" else "Bash")
                toolUseId?.let { put("tool_use_id", it) }
                if (kind == "agent") {
                    putJsonObject("input") { put("run_in_background", "true") }
                    put("task_id", "task")
                    putJsonObject("task_link") { put("task_type", "local_agent") }
                }
            }
        },
    )

    private fun agent(
        active: Boolean = true,
        rev: Long = 1,
    ) = fact(1, "agent", rev = rev, toolUseId = "raw-agent", active = active, status = if (active) "running" else "finished")

    private fun childReply(
        active: Boolean = false,
        rev: Long = 4,
    ) = fact(
        4,
        "assistant_message",
        rev = rev,
        parent = 1,
        active = active,
        status = if (active) "running" else "done",
        turn = "child-turn",
    )

    private fun family(vararg extra: ThreadItemDto) =
        listOf(
            agent(),
            fact(2, "tool_call", parent = 1, toolUseId = "raw-1"),
            fact(3, "tool_call", parent = 1, toolUseId = "raw-2"),
            childReply(),
            fact(5, "tool_call", toolUseId = "raw-main"),
            fact(6, "assistant_message"),
        ) + extra

    private fun snapshot(items: List<ThreadItemDto>) = AuthoritativeThreadSnapshot(epoch = "epoch", generation = 3, items = items)

    private fun evidence(
        items: List<ThreadItemDto>,
        host: String = "host",
    ) = authoritativeThreadEvidence(host, "chat", connection, snapshot(items))

    @Test fun finishedPlacementRejectsLostOrChangedTopLevelEndOrderWithIdenticalContent() {
        val expected = ThreadItemDto.decode(JsonObject(agent(active = false, rev = 12).raw + ("ended_order" to JsonPrimitive(8))))
        val before = fact(5, "user_message", status = "delivered")
        val after = fact(10, "user_message", status = "delivered", session = "session-b")
        assertTrue(evidence(listOf(expected, before, after)).retainsFinishedAgentPlacement(expected, before, after))
        for (end in listOf(null, 7L, 9L)) {
            val raw = expected.raw.toMutableMap()
            if (end == null) raw.remove("ended_order") else raw["ended_order"] = JsonPrimitive(end)
            val changed = ThreadItemDto.decode(JsonObject(raw))
            assertEquals(expected.content, changed.content)
            assertEquals(expected.order, changed.order)
            assertEquals(expected.status, changed.status)
            assertEquals(expected.active, changed.active)
            assertFalse(
                "top-level finished placement must survive independently of content",
                evidence(listOf(changed, before, after)).retainsFinishedAgentPlacement(expected, before, after),
            )
        }
    }

    @Test fun finishedPlacementRequiresBothChronologicalAnchorsInTheProjectedOrder() {
        val root = ThreadItemDto.decode(JsonObject(agent(active = false, rev = 12).raw + ("ended_order" to JsonPrimitive(8))))
        val before = fact(5, "user_message", status = "delivered")
        val after = fact(10, "user_message", status = "delivered", session = "session-b")
        val reading = evidence(listOf(root, before, after))
        assertTrue(reading.retainsFinishedAgentPlacement(root, before, after))
        val reversed = ThreadEvidence(reading.owner, reading.records.reversed(), sourceSnapshot = reading.sourceSnapshot)
        assertFalse(
            "correct facts alone cannot excuse misplaced rendered rows",
            reversed.retainsFinishedAgentPlacement(root, before, after),
        )
        assertFalse(evidence(listOf(root, before)).retainsFinishedAgentPlacement(root, before, after))
        assertFalse(evidence(listOf(root, after)).retainsFinishedAgentPlacement(root, before, after))
    }

    @Test fun authoritativeFamilyCorrelatesRawIdsToProjectedIdentities() {
        val snap = snapshot(family())
        val held = authoritativeThreadEvidence("host", "chat", connection, snap)
        val agent = requireNotNull(held.renderedAgent("raw-agent"))
        val projection = projectAuthoritativeThread("host", "chat", snap)

        fun rendered(id: Long) = "${projection.scope}:$id"
        assertEquals(rendered(1), agent.agentId)
        assertNotEquals("raw-agent", agent.agentId)
        assertEquals(listOf(rendered(2), rendered(3), rendered(4)), agent.childIds)
        val run = foldToolRuns(projection.rows, emptySet()).filterIsInstance<ThreadRow.ToolRun>().single()
        assertEquals(run.runId, agent.runId)
        assertEquals(rendered(2), agent.runId)
        assertTrue(agent.ownsTools(listOf("raw-1", "raw-2")))
        assertFalse("a main-thread tool is not owned", agent.ownsTools(listOf("raw-1", "raw-main")))
        assertFalse("the root is not its own child", agent.ownsTools(listOf("raw-agent")))
        assertNull("a child tool is not an Agent root", held.renderedAgent("raw-1"))
        assertNull(held.renderedAgent("missing"))
        assertNull(held.renderedAgent(""))
    }

    @Test fun childReplySettlesOnlyOnItsOwnTerminalRevision() {
        val running = family().filterNot { it.id == 4L }
        assertNull(evidence(running + childReply(active = true)).settledTurnReply("child-turn", "raw-agent"))
        assertNull(
            "the highest revision decides",
            evidence(running + childReply() + childReply(active = true, rev = 9)).settledTurnReply("child-turn", "raw-agent"),
        )
        val settled = evidence(running + childReply(active = true) + childReply(rev = 9))
        assertEquals(listOf(4L), settled.settledTurnReply("child-turn", "raw-agent")?.map { it.fact?.id })
        assertNull("a child reply is not the main reply", settled.settledTurnReply("child-turn"))
        assertNull(settled.settledTurnReply("main-turn", "raw-agent"))
        assertNull(settled.settledTurnReply("child-turn", "raw-1"))
        assertNotNull(settled.settledTurnReply("main-turn"))
        val ended = evidence(listOf(agent(active = false, rev = 7)) + family().drop(1))
        assertNotNull("a terminal Agent keeps its owned reply", ended.settledTurnReply("child-turn", "raw-agent"))
        assertNotNull(ended.renderedAgent("raw-agent"))
    }

    @Test fun wrongHostAndRetiredEvidenceCannotSatisfyWaits() {
        val held = evidence(family())
        val agent = requireNotNull(held.renderedAgent("raw-agent"))
        val other = evidence(family(), host = "other")
        val collision = requireNotNull(other.renderedAgent("raw-agent"))
        assertNotEquals("raw IDs never become rendering keys", agent.agentId, collision.agentId)
        assertTrue(agent.childIds.none { it in collision.childIds })
        assertFalse(agent.owner.matches(other.owner))
        assertTrue(agent.owner.matches(held.owner))
        for (retired in listOf(
            snapshot(family()).copy(epoch = null),
            snapshot(family()).copy(repairNeeded = true),
            snapshot(family()).copy(resetNeeded = true),
        )) {
            val unadmitted = authoritativeThreadEvidence("host", "chat", connection, retired)
            assertNull(unadmitted.renderedAgent("raw-agent"))
            assertNull(unadmitted.settledTurnReply("child-turn", "raw-agent"))
            assertNull(unadmitted.settledTurnReply("main-turn"))
            assertNull(unadmitted.deliveredUser("later"))
        }
    }

    @Test fun retiredSourceCannotSettleAWait() =
        runTest {
            val oldFacts = MutableStateFlow(snapshot(family().filterNot { it.id == 6L }))
            val old = ThreadEvidenceSource.authoritative("host", Any()) { oldFacts }
            val current = ThreadEvidenceSource.authoritative("host", connection) { MutableStateFlow(snapshot(family())) }
            val live = MutableStateFlow<ThreadEvidenceSource?>(old)
            val result = async { live.firstOnLive({ it.observe("chat") }) { it.settledTurnReply("main-turn") != null } }
            runCurrent()
            assertFalse(result.isCompleted)
            live.value = null
            runCurrent()
            oldFacts.value = snapshot(family())
            runCurrent()
            assertFalse("the retired source settled after replacement", result.isCompleted)
            live.value = current
            assertSame(connection, result.await().owner.connection)
        }

    @Test fun mainReplySettlementNeedsOneSessionForTheTurn() {
        assertNotNull(evidence(family()).settledTurnReply("main-turn"))
        assertNull(evidence(family(fact(7, "assistant_message", session = "other"))).settledTurnReply("main-turn"))
        assertNull(evidence(family()).settledTurnReply("missing-turn"))

        fun reply(
            seq: Int,
            text: String,
        ) = ThreadItem.MessageItem(
            Message("r$seq", "session", Role.Assistant, text, Instant.fromEpochMilliseconds(0), false, segment = segment(seq, text)),
        )
        val legacy = legacyThreadEvidence(null, "chat", connection, listOf(reply(0, "Complete "), reply(1, "reply")))
        assertEquals("Complete reply", legacy.settledTurnReply("main-turn")?.joinToString("") { it.fullText })
        val gap = legacyThreadEvidence(null, "chat", connection, listOf(reply(0, "Complete "), reply(2, "reply")))
        assertNull("legacy settlement keeps contiguous deltas", gap.settledTurnReply("main-turn"))
    }

    private fun segment(
        seq: Int,
        text: String,
        turn: String = "main-turn",
    ) = AssistantSegment(turn, listOf(SegmentDelta(seq, text.length)))

    private fun legacyFamily(): List<ThreadItem> {
        fun tool(
            raw: String,
            parent: String = "",
            agent: Boolean = false,
        ) = ThreadItem.MessageItem(
            Message(
                "$raw#0",
                "session",
                Role.Tool,
                "",
                Instant.fromEpochMilliseconds(0),
                false,
                toolCall =
                    ToolCall(
                        if (agent) "Agent" else "Bash",
                        "",
                        "",
                        inputFields = if (agent) mapOf("run_in_background" to "true") else emptyMap(),
                        parentToolUseId = parent,
                    ),
                reconciliationId = raw,
            ),
        )
        return listOf(
            tool("A", agent = true),
            ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochMilliseconds(0), "A", "Agent", "local_agent"),
            tool("B", parent = "A"),
            tool("C", parent = "A"),
            ThreadItem.MessageItem(
                Message(
                    "child",
                    "session",
                    Role.Assistant,
                    "Done",
                    Instant.fromEpochMilliseconds(0),
                    false,
                    segment = segment(0, "Done", "child-turn"),
                    parentToolUseId = "A",
                ),
            ),
            tool("M"),
        )
    }

    @Test fun legacyFamilyUsesRenderedAliasesAndRawCorrelation() {
        val held = legacyThreadEvidence(null, "chat", connection, legacyFamily())
        val agent = requireNotNull(held.renderedAgent("A"))
        assertEquals("A#0", agent.agentId)
        assertEquals(listOf("B#0", "C#0", "child"), agent.childIds)
        assertEquals("B#0", agent.runId)
        assertTrue(agent.ownsTools(listOf("B", "C")))
        assertFalse("rendered aliases are not raw identities", agent.ownsTools(listOf("B#0")))
        assertFalse(agent.ownsTools(listOf("M")))
        assertNull(held.renderedAgent("A#0"))
        assertEquals(listOf("child"), held.settledTurnReply("child-turn", "A")?.map { it.message.id })
        assertNull("legacy families key the raw parent", held.settledTurnReply("child-turn", "A#0"))
        assertNull(held.settledTurnReply("child-turn"))
    }

    @Test fun laterUserIsTheSingleDeliveredEcho() {
        val user = fact(8, "user_message", text = "later", status = "delivered")
        val held = evidence(family(user))
        assertEquals(
            held.records
                .single { it.fact?.id == 8L }
                .message.id,
            held.deliveredUser("later")?.message?.id,
        )
        assertNull(held.deliveredUser("late"))
        assertNull(evidence(family(user, fact(9, "user_message", text = "later", status = "delivered"))).deliveredUser("later"))
        assertNull(evidence(family(fact(8, "user_message", text = "later", status = "queued", ordered = false))).deliveredUser("later"))
        assertNull(
            "an assistant echo is not the user row",
            evidence(family(fact(8, "assistant_message", text = "later"))).deliveredUser("later"),
        )
    }
}
