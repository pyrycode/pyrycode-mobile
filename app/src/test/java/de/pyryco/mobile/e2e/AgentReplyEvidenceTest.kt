package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentReplyEvidenceTest {
    private val connection = Any()

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
        token: Any = connection,
    ) = authoritativeThreadEvidence(host, "chat", token, snapshot)

    private fun ThreadEvidence.reply(current: ThreadEvidenceOwner = owner) =
        agentReplyEvidence(current, "raw-task", "raw-agent", "child", "main")

    @Test fun authoritativeAgentReplyUsesProjectedIdentities() {
        val snapshot = agentReplySnapshot()
        val held = evidence(snapshot)
        val scope = projectAuthoritativeThread("host", "chat", snapshot).scope
        val reply = requireNotNull(held.reply())
        assertEquals("$scope:1", reply.agent.block)
        assertEquals(reply.agent.block, reply.agent.family)
        assertEquals(2L, reply.heldReply.fact?.id)
        assertEquals(reply.agent.block, reply.heldReply.agentId)
        assertEquals("$scope:3", reply.runId)
        assertEquals(5L, reply.mainReply.fact?.id)
        assertNull(reply.mainReply.agentId)
        assertNotEquals("raw-agent", reply.agent.block)
        assertNotEquals("raw-tool-3", reply.runId)
        assertNull(held.agentReplyEvidence(held.owner, "other-task", "raw-agent", "child", "main"))
        assertNull(held.agentReplyEvidence(held.owner, "raw-task", "other-agent", "child", "main"))
        val noToolUse = evidence(agentReplySnapshot(root = agentReplyRoot(toolUse = null)))
        assertEquals("$scope:1", noToolUse.reply()?.agent?.block)
        // An Agent block without owned child tools has no run to toggle.
        val toolless =
            evidence(
                AuthoritativeThreadSnapshot(
                    epoch = "epoch",
                    generation = 3,
                    items = listOf(agentReplyRoot(), agentReplyItem(2, parent = 1, turn = "child"), agentReplyItem(5)),
                ),
            )
        assertNull(toolless.reply())
    }

    @Test fun collidingHostCannotSatisfyAgentReplyOrMainReply() {
        val held = evidence(agentReplySnapshot())
        val other = evidence(agentReplySnapshot(), host = "other")
        assertNotNull(other.reply())
        assertNotEquals(held.reply()?.agent?.block, other.reply()?.agent?.block)
        assertNull(other.reply(held.owner))
        assertNull(other.settledMainReply(held.owner, "main"))
        assertNull(held.settledMainReply(other.owner, "main"))
    }

    @Test fun retiredConnectionEvidenceIsRejectedAndOnlyTheCurrentSourceSettles() =
        runTest {
            val retained = evidence(agentReplySnapshot(), token = Any())
            assertNull(retained.reply(evidence(agentReplySnapshot()).owner))
            var closed = false
            val old =
                ThreadEvidenceSource.authoritative("host", Any()) {
                    flow {
                        try {
                            emit(agentReplySnapshot(main = agentReplyItem(5, active = true, status = "running")))
                            awaitCancellation()
                        } finally {
                            closed = true
                        }
                    }
                }
            val live = MutableStateFlow<ThreadEvidenceSource?>(old)
            val result = async { live.firstOnLive({ it.observe("chat") }) { it.reply() != null } }
            runCurrent()
            assertFalse(result.isCompleted)
            live.value = ThreadEvidenceSource.authoritative("host", connection) { MutableStateFlow(agentReplySnapshot()) }
            val settled = result.await()
            assertTrue(closed)
            assertTrue(settled.owner.connection === connection)
            assertNotNull(settled.reply())
        }

    @Test fun incompleteOrMisattributedRepliesDoNotSettle() {
        for (child in listOf(
            agentReplyItem(2, parent = 1, turn = "child", active = true),
            agentReplyItem(2, parent = 1, turn = "child", status = "running"),
            agentReplyItem(2, parent = 1, turn = "child", text = " "),
        )) {
            assertNull(evidence(agentReplySnapshot(child = child)).reply())
        }
        for (main in listOf(
            agentReplyItem(5, active = true),
            agentReplyItem(5, status = "running"),
            agentReplyItem(5, text = ""),
            agentReplyItem(5, parent = 1),
            agentReplyItem(5, turn = "child"),
        )) {
            val held = evidence(agentReplySnapshot(main = main))
            assertNull(held.reply())
            assertNull(held.settledMainReply(held.owner, "main"))
        }
        // One turn recorded under two sessions has no single settlement family.
        val split =
            agentReplySnapshot().let { it.copy(items = it.items + agentReplyItem(6, session = "other")) }
        assertNull(evidence(split).settledMainReply(evidence(split).owner, "main"))
    }

    @Test fun stopMainReplySettlesOnItsOwnTurnOnly() {
        val task =
            agentReplyItem(
                1,
                "tool_call",
                turn = "launch",
                active = true,
                status = "running",
                content =
                    buildJsonObject {
                        put("name", "Bash")
                        put("task_id", "raw-bash")
                        put("task_link", buildJsonObject { put("task_type", "local_bash") })
                    },
            )
        val snapshot =
            AuthoritativeThreadSnapshot(
                epoch = "epoch",
                generation = 3,
                items = listOf(task, agentReplyItem(2, turn = "launch"), agentReplyItem(5)),
            )
        val held = evidence(snapshot)
        assertEquals(5L, held.settledMainReply(held.owner, "main")?.fact?.id)
        assertEquals(2L, held.settledMainReply(held.owner, "launch")?.fact?.id)
        assertNull(held.settledMainReply(held.owner, "later"))
        val unadmitted = evidence(snapshot.copy(repairNeeded = true))
        assertNull(unadmitted.settledMainReply(unadmitted.owner, "main"))
    }

    @Test fun legacyAgentReplyKeepsRawAttributionAndRenderedBlocks() {
        fun message(
            id: String,
            role: Role = Role.Tool,
            parent: String = "",
            turn: String? = null,
            text: String = "",
            tool: ToolCall? = null,
        ) = ThreadItem.MessageItem(
            Message(
                id,
                "",
                role,
                text,
                Instant.fromEpochMilliseconds(0),
                false,
                segment = turn?.let { AssistantSegment(it, listOf(SegmentDelta(0, text.length))) },
                toolCall = tool,
                parentToolUseId = parent,
            ),
        )
        val items =
            listOf(
                message("raw-agent", tool = ToolCall("Agent", "", "", inputFields = mapOf("run_in_background" to "true"))),
                ThreadItem.BackgroundTaskLifecycle("raw-task", Instant.fromEpochMilliseconds(0), "raw-agent", "Agent", "local_agent"),
                message("child:0", Role.Assistant, "raw-agent", "child", "Child paragraph"),
                message("B", tool = ToolCall("Bash", "", "", parentToolUseId = "raw-agent")),
                message("C", tool = ToolCall("Bash", "", "", parentToolUseId = "raw-agent")),
                message("main:0", Role.Assistant, turn = "main", text = "Main reply"),
            )
        val held = legacyThreadEvidence(null, "chat", connection, items)
        val reply = requireNotNull(held.reply())
        assertEquals("raw-agent", reply.agent.block)
        assertEquals("raw-agent", reply.agent.family)
        assertEquals("child:0", reply.heldReply.message.id)
        assertEquals("B", reply.runId)
        assertEquals("main:0", reply.mainReply.message.id)
        assertEquals("main:0", held.settledMainReply(held.owner, "main")?.message?.id)
        val blank = legacyThreadEvidence(null, "chat", connection, items.dropLast(1) + message("main:0", Role.Assistant, turn = "main"))
        assertNull(blank.reply())
    }
}
