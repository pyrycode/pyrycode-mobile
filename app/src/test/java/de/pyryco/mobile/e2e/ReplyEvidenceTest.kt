package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyEvidenceTest {
    private val connection = Any()

    private fun item(
        id: Long,
        kind: String = "assistant_message",
        text: String = "Complete reply",
        active: Boolean = false,
        status: String = "done",
        parent: Long? = null,
        order: Long? = id,
        queued: Long? = null,
    ) = ThreadItemDto.decode(
        buildJsonObject {
            put("id", id)
            put("rev", id)
            put("kind", kind)
            order?.let { put("order", it) }
            put("session", "session")
            put("turn", "turn")
            put("agent", "claude")
            parent?.let { put("parent", it) }
            put("status", status)
            put("active", active)
            put("shown", true)
            put("summary", "fallback")
            put(
                "content",
                buildJsonObject {
                    put("text", text)
                    put("name", if (kind == "agent") "Agent" else "Bash")
                    put("tool_use_id", "raw-$id")
                    queued?.let { put("queued_msg_id", it) }
                },
            )
        },
    )

    private fun snapshot(
        vararg items: ThreadItemDto,
        epoch: String = "epoch",
        generation: Long = 3,
    ) = AuthoritativeThreadSnapshot(epoch = epoch, generation = generation, items = items.toList())

    private fun evidence(
        snapshot: AuthoritativeThreadSnapshot,
        host: String = "host",
        token: Any = connection,
    ) = authoritativeThreadEvidence(host, "chat", token, snapshot)

    private fun legacy(vararg messages: Message) =
        legacyThreadEvidence(null, "chat", connection, messages.map { ThreadItem.MessageItem(it) })

    private fun message(
        id: String,
        role: Role = Role.Assistant,
        text: String = "Complete reply",
        streaming: Boolean = false,
    ) = Message(id, "session", role, text, Instant.fromEpochMilliseconds(0), streaming)

    private val longText = List(24) { "word" }.joinToString(" ")

    @Test fun openMainReplyRequiresAnActiveMainAssistantItem() {
        val open =
            evidence(snapshot(item(1, "user_message", status = "delivered"), item(2, text = longText, active = true, status = "running")))
        assertEquals(2L, open.openMainReply { it.split(" ").size >= 20 }?.fact?.id)
        assertNull(open.openMainReply { it.split(" ").size >= 40 })
        for (closed in listOf(
            snapshot(item(2, text = longText)),
            snapshot(item(1, "agent", active = true, status = "running"), item(2, text = longText, active = true, parent = 1)),
            snapshot(item(2, "user_message", text = longText, active = true, status = "queued")),
            snapshot(item(2, text = longText, active = true, status = "running")).copy(repairNeeded = true),
        )) {
            assertNull(evidence(closed).openMainReply { true })
        }
        assertEquals("a", legacy(message("a", text = longText, streaming = true)).openMainReply { true }?.message?.id)
        assertNull(legacy(message("a", text = longText)).openMainReply { true })
        assertNull(legacy(message("a", text = longText, streaming = true).copy(parentToolUseId = "toolu")).openMainReply { true })
    }

    @Test fun openAndFinishedFollowTheItemsOwnRevision() {
        val streaming = evidence(snapshot(item(2, active = true, status = "running")))
        val captured = requireNotNull(streaming.openMainReply { true })
        assertTrue(captured.isOpenReply())
        assertFalse(captured.isFinishedReply())
        val done = evidence(snapshot(item(2)))
        val settled = requireNotNull(done.reply(streaming.owner, captured.message.id))
        assertTrue(settled.isFinishedReply())
        assertFalse(settled.isOpenReply())
        for (unsettled in listOf(
            item(2, active = true),
            item(2, status = "running"),
            item(2, status = "failed"),
            item(2, status = "interrupted"),
        )) {
            val record = requireNotNull(evidence(snapshot(unsettled)).reply(streaming.owner, captured.message.id))
            assertFalse(record.isFinishedReply())
        }
        for (other in listOf("turn_end", "future_kind", "user_message")) {
            val record = evidence(snapshot(item(2, other, active = true, status = "running"))).records.singleOrNull()
            if (record != null) {
                assertFalse(other, record.isOpenReply())
                assertFalse(other, record.isFinishedReply())
            }
        }
        assertTrue(legacy(message("a", streaming = true)).records.single().isOpenReply())
        assertTrue(legacy(message("a")).records.single().isFinishedReply())
        assertFalse(legacy(message("a", Role.User)).records.single().isFinishedReply())
    }

    @Test fun capturedReplyIgnoresCollidingAndRetiredEvidence() {
        val original = evidence(snapshot(item(2, active = true, status = "running")))
        val id = requireNotNull(original.openMainReply { true }).message.id
        assertNotNull(evidence(snapshot(item(2, active = true, status = "running"))).reply(original.owner, id))
        for (other in listOf(
            evidence(snapshot(item(2, active = true, status = "running")), host = "other-host"),
            evidence(snapshot(item(2, active = true, status = "running")), token = Any()),
            evidence(snapshot(item(2, active = true, status = "running"), epoch = "replaced")),
            evidence(snapshot(item(2, active = true, status = "running"), generation = 4)),
            evidence(snapshot(item(2, active = true, status = "running")).copy(repairNeeded = true)),
            evidence(snapshot(item(2, active = true, status = "running")).copy(resetNeeded = true)),
            evidence(snapshot(item(5, active = true, status = "running"))),
        )) {
            assertNull(other.reply(original.owner, id))
        }
        val first = legacy(message("a", streaming = true))
        assertNotNull(legacy(message("a")).reply(first.owner, "a"))
        assertNull(legacyThreadEvidence(null, "chat", Any(), listOf(ThreadItem.MessageItem(message("a")))).reply(first.owner, "a"))
        assertNull(legacyThreadEvidence(null, "other", connection, listOf(ThreadItem.MessageItem(message("a")))).reply(first.owner, "a"))
        assertNull(legacy(message("a"), message("a")).reply(first.owner, "a"))
    }

    @Test fun pingReplyRequiresAFinishedMainAssistantReply() {
        assertTrue(evidence(snapshot(item(2, text = " Ping\n"))).holdsPingReply())
        for (not in listOf(
            snapshot(item(2, text = "ping", active = true, status = "running")),
            snapshot(item(2, text = "ping", status = "failed")),
            snapshot(item(1, "agent", active = true, status = "running"), item(2, text = "ping", parent = 1)),
            snapshot(item(2, "user_message", text = "ping", status = "delivered")),
            snapshot(item(2, text = "pong")),
            snapshot(item(2, text = "ping")).copy(epoch = null),
        )) {
            assertFalse(evidence(not).holdsPingReply())
        }
        assertTrue(legacy(message("a", text = "ping")).holdsPingReply())
        assertFalse(legacy(message("a", text = "ping", streaming = true)).holdsPingReply())
        assertFalse(legacy(message("a", Role.User, "ping")).holdsPingReply())
    }

    @Test fun assistantTextUsesFullDaemonTextOfAdmittedAssistantItems() {
        assertTrue(evidence(snapshot(item(2, text = "before token_1 after"))).holdsAssistantText("token_1"))
        assertFalse(evidence(snapshot(item(2, "user_message", text = "token_1", status = "delivered"))).holdsAssistantText("token_1"))
        assertFalse(evidence(snapshot(item(2, text = "token_1")).copy(resetNeeded = true)).holdsAssistantText("token_1"))
        assertTrue(legacy(message("a", text = "token_1")).holdsAssistantText("token_1"))
        assertFalse(legacy(message("a", Role.User, "token_1")).holdsAssistantText("token_1"))
    }

    @Test fun queuedDeliveryMatchesTheHandleNotTheMessageId() {
        val placed =
            evidence(
                snapshot(
                    item(1, "user_message", status = "delivered"),
                    item(2, "tool_call", text = "hold_done"),
                    item(3, "user_message", text = "marker", status = "delivered", queued = 7),
                    item(4, text = "marker"),
                ),
            )
        assertEquals(listOf(2), placed.deliveredQueuedUser("app-message", 7))
        assertEquals(1, placed.lastToolIndex())
        assertTrue(placed.deliveredQueuedUser("app-message", 8).isEmpty())
        val reused =
            evidence(
                snapshot(
                    item(1, "user_message", text = "earlier", status = "delivered", queued = 7),
                    item(3, "user_message", text = "marker", status = "delivered", queued = 7),
                ),
            )
        assertEquals("a reused handle must fail the single-delivery check", 2, reused.deliveredQueuedUser("app-message", 7).size)
        val beforeTool =
            evidence(
                snapshot(
                    item(2, "user_message", text = "marker", status = "delivered", queued = 7),
                    item(3, "tool_call", text = "hold_done"),
                ),
            )
        assertTrue(beforeTool.deliveredQueuedUser("app-message", 7).single() < beforeTool.lastToolIndex())
        for (pending in listOf(
            snapshot(item(3, "user_message", text = "marker", active = true, status = "queued", order = null, queued = 7)),
            snapshot(item(3, "user_message", text = "marker", status = "delivered")),
            snapshot(item(3, text = "marker", queued = 7)),
            snapshot(item(3, "user_message", text = "marker", status = "delivered", queued = 7)).copy(repairNeeded = true),
        )) {
            assertTrue(evidence(pending).deliveredQueuedUser("app-message", 7).isEmpty())
        }
        val legacyRows =
            legacy(
                message("tool", Role.Tool).copy(
                    toolCall =
                        de.pyryco.mobile.data.model
                            .ToolCall("Bash", "{}", "hold_done"),
                ),
                message("app-message", Role.User, "marker"),
            )
        assertEquals(listOf(1), legacyRows.deliveredQueuedUser("app-message", 7))
        assertEquals(0, legacyRows.lastToolIndex())
    }

    @Test fun toolFreeTurnSeesAuthoritativeToolItems() {
        assertTrue(evidence(snapshot(item(2))).isToolFree())
        assertFalse(evidence(snapshot(item(1, "tool_call"), item(2))).isToolFree())
    }

    @Test fun liveEvidenceIgnoresARetiredConnection() =
        runTest {
            val retired = Any()
            val current = Any()
            val snapshots = mapOf(retired to MutableSharedFlow<AuthoritativeThreadSnapshot>(), current to MutableSharedFlow())
            val holder = MutableStateFlow<Any?>(retired)
            val read =
                async {
                    holder.liveThreadEvidence("chat") { source ->
                        ThreadEvidenceSource.authoritative("host", source) { snapshots.getValue(source) }
                    }
                }
            runCurrent()
            holder.value = current
            runCurrent()
            snapshots.getValue(retired).emit(snapshot(item(2, text = "ping")))
            snapshots.getValue(current).emit(snapshot(item(2, text = "pong")))
            val held = read.await()
            assertTrue(held.owner.connection === current)
            assertFalse(held.holdsPingReply())
        }

    @Test fun sourcesNameTheirBoundRoute() {
        assertEquals(ThreadEvidenceKind.Authoritative, ThreadEvidenceSource.authoritative("host", connection) { flowOf(snapshot()) }.kind)
        assertEquals(ThreadEvidenceKind.Legacy, ThreadEvidenceSource.legacy(null, connection) { flowOf(emptyList()) }.kind)
    }
}
