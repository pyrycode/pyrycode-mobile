package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.authoritativeAgent
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import de.pyryco.mobile.ui.conversations.thread.authoritativeSnapshot
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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
        val noToolUse = evidence(agentReplySnapshot(root = authoritativeAgent(1, task = "raw-task", turn = "launch")))
        assertEquals("$scope:1", noToolUse.reply()?.agent?.block)
        // An Agent block without owned child tools has no run to toggle.
        val toolless =
            evidence(
                snapshot.copy(items = snapshot.items.filter { it.kind != "tool_call" }),
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
                            emit(agentReplySnapshot(main = authoritativeItem(5, active = true, status = "running", turn = "main")))
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
            authoritativeItem(2, parent = 1, active = true, turn = "child"),
            authoritativeItem(2, parent = 1, status = "running", turn = "child"),
            authoritativeItem(2, parent = 1, content = """{"text":" "}""", turn = "child"),
        )) {
            assertNull(evidence(agentReplySnapshot(child = child)).reply())
        }
        for (main in listOf(
            authoritativeItem(5, active = true, turn = "main"),
            authoritativeItem(5, status = "running", turn = "main"),
            authoritativeItem(5, content = """{"text":""}""", turn = "main"),
            authoritativeItem(5, parent = 1, turn = "main"),
            authoritativeItem(5, turn = "child"),
        )) {
            val held = evidence(agentReplySnapshot(main = main))
            assertNull(held.reply())
            assertNull(held.settledMainReply(held.owner, "main"))
        }
        // One turn recorded under two sessions has no single settlement family.
        val split =
            agentReplySnapshot().let { it.copy(items = it.items + authoritativeItem(6, session = "other", turn = "main")) }
        assertNull(evidence(split).settledMainReply(evidence(split).owner, "main"))
    }

    @Test fun heldParagraphProvesOwnershipWithoutSettling() {
        for ((status, active) in listOf("running" to true, "interrupted" to false, "done" to true)) {
            val reading =
                evidence(agentReplySnapshot(child = authoritativeItem(2, parent = 1, active = active, status = status, turn = "child")))
            val held = requireNotNull(reading.heldAgentReplyEvidence(reading.owner, "raw-task", "raw-agent", "child", "main"))
            assertEquals(2L, held.heldReply.fact?.id)
            assertEquals(held.agent.block, held.heldReply.agentId)
            assertNull("owned active prose must not qualify as settled", reading.reply())
        }
    }

    @Test fun heldParagraphRejectsRetiredOwnersAndAmbiguousSessions() {
        val reading = evidence(agentReplySnapshot())
        val owners =
            listOf(
                reading.owner.copy(host = "other"),
                reading.owner.copy(conversation = "other"),
                reading.owner.copy(connection = Any()),
                reading.owner.copy(epoch = "other"),
                reading.owner.copy(generation = requireNotNull(reading.owner.generation) + 1),
            )
        for (owner in owners) assertNull(reading.heldAgentReplyEvidence(owner, "raw-task", "raw-agent", "child", "main"))
        val split =
            evidence(
                agentReplySnapshot().let {
                    it.copy(
                        items =
                            it.items + authoritativeItem(6, parent = 1, session = "other", turn = "child"),
                    )
                },
            )
        assertNull(split.heldAgentReplyEvidence(split.owner, "raw-task", "raw-agent", "child", "main"))
        val unadmitted = evidence(agentReplySnapshot().copy(repairNeeded = true))
        assertNull(unadmitted.heldAgentReplyEvidence(unadmitted.owner, "raw-task", "raw-agent", "child", "main"))
    }

    @Test fun heldParagraphNeedsItsOwnParentToolsAndSettledMainReply() {
        val baseline = agentReplySnapshot()
        val invalid =
            listOf(
                agentReplySnapshot(child = authoritativeItem(2, parent = 99, turn = "child")),
                agentReplySnapshot(main = authoritativeItem(5, active = true, turn = "main")),
                agentReplySnapshot(main = authoritativeItem(5, status = "interrupted", turn = "main")),
                baseline.copy(items = baseline.items.filter { it.kind != "tool_call" }),
                agentReplySnapshot(child = authoritativeItem(2, parent = 1, content = """{"text":" "}""", turn = "child")),
            )
        for (snapshot in invalid) {
            val reading = evidence(snapshot)
            assertNull(reading.heldAgentReplyEvidence(reading.owner, "raw-task", "raw-agent", "child", "main"))
        }
    }

    @Test fun stopMainReplySettlesOnItsOwnTurnOnly() {
        val task =
            authoritativeItem(
                1,
                "tool_call",
                active = true,
                status = "running",
                content = """{"name":"Bash","task_id":"raw-bash","task_link":{"task_type":"local_bash"}}""",
                turn = "launch",
            )
        val snapshot = authoritativeSnapshot(task, authoritativeItem(2, turn = "launch"), authoritativeItem(5, turn = "main"))
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
            reconciliation: String? = null,
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
                reconciliationId = reconciliation,
            ),
        )
        for (aliased in listOf(false, true)) {
            // A collided root renders under its local alias; its children keep the raw call id as their parent.
            val root = if (aliased) "raw-agent#1" else "raw-agent"
            val agent = ToolCall("Agent", "", "", inputFields = mapOf("run_in_background" to "true"))
            val items =
                listOfNotNull(
                    message("raw-agent", Role.User, text = "Colliding row").takeIf { aliased },
                    message(root, tool = agent, reconciliation = "raw-agent".takeIf { aliased }),
                    ThreadItem.BackgroundTaskLifecycle("raw-task", Instant.fromEpochMilliseconds(0), "raw-agent", "Agent", "local_agent"),
                    message("child:0", Role.Assistant, "raw-agent", "child", "Child paragraph"),
                    message("B", tool = ToolCall("Bash", "", "", parentToolUseId = "raw-agent")),
                    message("C", tool = ToolCall("Bash", "", "", parentToolUseId = "raw-agent")),
                    message("main:0", Role.Assistant, turn = "main", text = "Main reply"),
                )
            val held = legacyThreadEvidence(null, "chat", connection, items)
            val reply = requireNotNull(held.reply())
            assertEquals(root, reply.agent.block)
            assertEquals("raw-agent", reply.agent.family)
            assertEquals("child:0", reply.heldReply.message.id)
            assertEquals(root, reply.heldReply.agentId)
            assertEquals("raw-agent", reply.heldReply.message.parentToolUseId)
            assertEquals("B", reply.runId)
            assertNotEquals(reply.agent.block, reply.runId)
            assertEquals("main:0", reply.mainReply.message.id)
            assertNull(reply.mainReply.agentId)
            assertEquals("main:0", held.settledMainReply(held.owner, "main")?.message?.id)
            val blank = legacyThreadEvidence(null, "chat", connection, items.dropLast(1) + message("main:0", Role.Assistant, turn = "main"))
            assertNull(blank.reply())
        }
    }
}
