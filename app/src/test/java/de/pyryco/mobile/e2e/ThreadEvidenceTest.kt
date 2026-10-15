package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.foldToolRuns
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadEvidenceTest {
    private val connection = Any()

    private fun item(
        id: Long = 2,
        kind: String = "assistant_message",
        text: String = "Complete reply",
        active: Boolean = false,
        status: String = "done",
        parent: Long? = null,
        session: String = "session",
        turn: String = "turn",
        shown: Boolean = true,
        ordered: Boolean = true,
        rev: Long = id,
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
            put("shown", shown)
            put("summary", "fallback")
            put(
                "content",
                buildJsonObject {
                    put("text", text)
                    put("name", if (kind == "agent") "Agent" else "Bash")
                    put("tool_use_id", "raw-tool")
                },
            )
        },
    )

    private fun snapshot(vararg items: ThreadItemDto) = AuthoritativeThreadSnapshot(epoch = "epoch", generation = 3, items = items.toList())

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
        token: Any = connection,
        chat: String = "chat",
    ) = authoritativeThreadEvidence(host, chat, token, snapshot)

    private fun legacyMessage(
        text: String = "Complete reply",
        seq: Int = 0,
    ) = Message(
        "reply-$seq",
        "session",
        Role.Assistant,
        text,
        Instant.fromEpochMilliseconds(0),
        false,
        segment = AssistantSegment("turn", listOf(SegmentDelta(seq, text.length))),
    )

    @Test fun legacySettlementRetainsContiguousDeltasAndFullLengths() {
        fun read(vararg messages: Message) = legacyThreadEvidence("host", "chat", connection, messages.map { ThreadItem.MessageItem(it) })
        val first = legacyMessage("Complete ")
        val last = legacyMessage("reply", 1)
        assertEquals(
            "Complete reply",
            read(first, last).settledReply(read(first, last).owner, "session", "turn")?.joinToString("") { it.fullText },
        )
        for (bad in listOf(
            last.copy(segment = AssistantSegment("turn", listOf(SegmentDelta(2, 5)))),
            last.copy(content = "truncated"),
            last.copy(isStreaming = true),
        )) {
            val held = read(first, bad)
            assertNull(held.settledReply(held.owner, "session", "turn"))
        }
        val ordinary = read(first.copy(segment = null))
        assertNull(ordinary.settledReply(ordinary.owner, "session", "turn"))
        val wrongSession = read(first.copy(sessionId = "other"))
        assertNull(wrongSession.settledReply(wrongSession.owner, "session", "turn"))
        assertSame(first, read(first).records.single().message)
    }

    @Test fun authoritativeSettlementUsesTheAssistantItemsOwnCompletion() {
        val parent = item(1, "agent", active = true, status = "running")
        val held = evidence(snapshot(parent, item(parent = 1)))
        val agent =
            held.records
                .first { it.fact?.id == 1L }
                .message.id
        assertEquals("Complete reply", held.settledReply(held.owner, "session", "turn", agent)?.single()?.fullText)
        assertNull(held.settledReply(held.owner, "session", "turn"))
        assertNull(held.settledReply(held.owner, "session", "other", agent))
        assertNull(held.settledReply(held.owner, "other", "turn", agent))
        assertNull(held.settledReply(held.owner, "session", "turn", "raw-tool"))
        val orphan = evidence(snapshot(item(parent = 99)))
        assertNull(orphan.settledReply(orphan.owner, "session", "turn"))
        val endedParent = item(1, "agent")
        for (reply in listOf(
            item(parent = 1, active = true),
            item(parent = 1, status = "running"),
            item(parent = 1, text = " "),
            item(parent = 1, shown = false),
            item(parent = 1, ordered = false),
        )) {
            val incomplete = evidence(snapshot(endedParent, reply))
            assertNull(incomplete.settledReply(incomplete.owner, "session", "turn", agent))
        }
    }

    @Test fun highestRevisionAndAdmissionControlSettlementWithoutInventedDeltas() {
        val current = evidence(snapshot(item(), item(rev = 8, active = true)))
        assertEquals(
            8L,
            current.records
                .single()
                .fact
                ?.rev,
        )
        assertNull(current.settledReply(current.owner, "session", "turn"))
        val complete = evidence(snapshot(item(rev = 9)))
        assertNull(
            complete.records
                .single()
                .message.segment,
        )
        assertNotNull(complete.settledReply(complete.owner, "session", "turn"))
        for (unadmitted in listOf(
            snapshot(item()).copy(epoch = null),
            snapshot(item()).copy(repairNeeded = true),
            snapshot(item()).copy(resetNeeded = true),
            snapshot().copy(completedVersion = 100, checkpoint = 100),
        )) {
            val held = evidence(unadmitted)
            assertNull(held.settledReply(held.owner, "session", "turn"))
        }
    }

    @Test fun ownershipRejectsReusedIdentities() {
        val snap = snapshot(item())
        val held = evidence(snap)
        for (replacement in listOf(
            evidence(snap, host = "other"),
            evidence(snap, chat = "other"),
            evidence(snap, token = Any()),
            evidence(snap.copy(epoch = "new")),
            evidence(snap.copy(generation = 4)),
        )) {
            assertNull(held.settledReply(replacement.owner, "session", "turn"))
        }
        val rows = listOf(ThreadItem.MessageItem(legacyMessage()))
        val legacy = legacyThreadEvidence("host", "chat", connection, rows)
        val collision = legacyThreadEvidence("other", "chat", connection, rows)
        assertNull(held.settledReply(legacy.owner, "session", "turn"))
        assertEquals(
            legacy.records
                .single()
                .message.id,
            collision.records
                .single()
                .message.id,
        )
        assertNull(legacy.settledReply(collision.owner, "session", "turn"))
    }

    @Test fun authoritativeIdentitiesMatchProjectionAndToolRuns() {
        val snap = snapshot(item(1, "agent"), item(2, "tool_call", parent = 1), item(3, "tool_call", parent = 1), item(4, parent = 1))
        val held = evidence(snap)
        val projection = projectAuthoritativeThread("host", "chat", snap)
        val rows = projection.rows.filterIsInstance<ThreadRow.Delivered>()
        assertEquals(rows.map { (it.item as ThreadItem.MessageItem).message.id }, held.records.map { it.message.id })
        assertEquals(rows.map { it.agentBlockId }, held.records.map { it.agentId })
        val run = foldToolRuns(projection.rows, emptySet()).filterIsInstance<ThreadRow.ToolRun>().single()
        assertEquals(run.runId, held.records.first { it.fact?.id == 3L }.toolRunId)
        assertEquals(
            1L,
            held.records
                .last()
                .fact
                ?.parent,
        )
        assertNotEquals("raw-tool", run.runId)
        assertNotEquals(
            held.records
                .last()
                .message.id,
            evidence(snap, host = "other")
                .records
                .last()
                .message.id,
        )
    }

    @Test fun fullReplyTextSurvivesDisplayBounding() {
        val text = "x".repeat(70_000)
        val held = evidence(snapshot(item(text = text)))
        assertEquals(text, held.settledReply(held.owner, "session", "turn")?.single()?.fullText)
        assertTrue(
            held.records
                .single()
                .message.content.length < text.length,
        )
        val invisible = evidence(snapshot(item(text = " ".repeat(65_536) + "tail")))
        assertNotNull(invisible.settledReply(invisible.owner, "session", "turn"))
        assertNull(invisible.loadedMainReply())
    }

    @Test fun recordedTaskIdentityIsTheProjectedAgentItem() {
        val raw = item(1, "agent", active = true, status = "running").raw
        val content = raw.getValue("content") as JsonObject
        val agent =
            ThreadItemDto.decode(
                JsonObject(
                    raw + (
                        "content" to
                            JsonObject(
                                content +
                                    mapOf(
                                        "task_id" to JsonPrimitive("raw-task"),
                                        "task_link" to buildJsonObject { put("task_type", "local_agent") },
                                    ),
                            )
                    ),
                ),
            )
        val snap = snapshot(agent, item(parent = 1))
        val held = evidence(snap)
        val task = projectAuthoritativeThread("host", "chat", snap).tasks.tasks.single()
        assertEquals("raw-task", task.taskId)
        assertEquals(
            task.toolCallId,
            held.records
                .single { it.fact?.id == 1L }
                .message.id,
        )
        assertEquals(task.toolCallId, held.records.single { it.fact?.id == 2L }.agentId)
        assertNotEquals(task.taskId, task.toolCallId)
        assertNotNull(held.settledReply(held.owner, "session", "turn", requireNotNull(task.toolCallId)))
    }

    @Test fun userEchoCountRequiresExactlyOneDeliveredUser() {
        val user = item(kind = "user_message", text = "exact", status = "delivered")
        assertEquals(1, evidence(snapshot(user, item(text = "exact"))).userEchoCount("exact"))
        assertEquals(
            2,
            evidence(snapshot(user, item(3, kind = "user_message", text = "exact", status = "delivered"))).userEchoCount("exact"),
        )
        assertEquals(
            0,
            evidence(snapshot(item(kind = "user_message", text = "exact", status = "queued", ordered = false))).userEchoCount("exact"),
        )
        assertEquals(0, evidence(snapshot(user)).userEchoCount("exac"))
        val legacy =
            legacyThreadEvidence("host", "chat", connection, listOf(ThreadItem.MessageItem(legacyMessage("exact").copy(role = Role.User))))
        assertEquals(1, legacy.userEchoCount("exact"))
    }

    @Test fun replacementCancelsOldEvidenceAndAcceptsOnlyCurrentSource() =
        runTest {
            var closed = false
            val old =
                ThreadEvidenceSource.authoritative("old", Any()) {
                    flow {
                        try {
                            emit(snapshot())
                            awaitCancellation()
                        } finally {
                            closed =
                                true
                        }
                    }
                }
            val current = ThreadEvidenceSource.authoritative("new", connection) { MutableStateFlow(snapshot(item())) }
            val live = MutableStateFlow<ThreadEvidenceSource?>(old)
            val result = async { live.firstOnLive({ it.observe("chat") }) { it.settledReply(it.owner, "session", "turn") != null } }
            runCurrent()
            assertFalse(result.isCompleted)
            live.value = null
            runCurrent()
            assertTrue(closed)
            live.value = current
            assertEquals("new", result.await().owner.host)
        }

    @Test fun cancellationClosesEvidenceObservation() =
        runTest {
            var closed = false
            val source =
                ThreadEvidenceSource.legacy("host", connection) {
                    flow {
                        try {
                            emit(emptyList())
                            awaitCancellation()
                        } finally {
                            closed =
                                true
                        }
                    }
                }
            val live = MutableStateFlow<ThreadEvidenceSource?>(source)
            val result = async { live.firstOnLive({ it.observe("chat") }) { false } }
            runCurrent()
            result.cancel()
            runCurrent()
            assertTrue(result.isCancelled)
            assertTrue(closed)
        }

    @Test fun authoritativeFixtureWorksBeforeHarnessActivation() =
        runTest {
            val source = ThreadEvidenceSource.authoritative("host", connection) { MutableStateFlow(snapshot(item())) }
            val held = source.observe("chat").first()
            assertEquals(ThreadEvidenceKind.Authoritative, held.owner.kind)
            assertEquals("Complete reply", held.loadedMainReply()?.content)
        }

    @Test fun legacyBindingStaysExplicitEvenWhenEmpty() =
        runTest {
            val rows = MutableStateFlow<List<ThreadItem>>(emptyList())
            val source = ThreadEvidenceSource.legacy("host", connection) { rows }
            assertEquals(
                ThreadEvidenceKind.Legacy,
                source
                    .observe("chat")
                    .first()
                    .owner.kind,
            )
            val reply = legacyMessage()
            rows.value = listOf(ThreadItem.MessageItem(reply))
            val held = source.observe("chat").first()
            assertSame(connection, held.owner.connection)
            assertSame(reply, held.loadedMainReply())
            assertNotNull(held.settledReply(held.owner, "session", "turn"))
        }
}
