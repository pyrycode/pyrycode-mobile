package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionErrorRecoveryObservationTest {
    private val savedSink = RelayLog.sink
    private var source = Any()
    private var current = true
    private var sequence = 0L
    private val frames = mutableListOf<Envelope>()

    @Before fun quietLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = savedSink
    }

    private fun producer() = ProducerThreadObservation("host", "chat", source, setOf("thread", "interactive")) { current }

    private fun query(target: ProducerThreadObservation): Envelope =
        target.nextQuery().let { query ->
            Envelope(++sequence, query.type, "", query.payload()).also {
                target.sending(source, it)
                target.sent(source, it, true)
            }
        }

    private fun batch(
        request: Envelope,
        items: List<JsonObject>,
        version: Int,
        final: Boolean = false,
    ): Envelope =
        Envelope(
            ++sequence,
            "thread_items",
            "",
            buildJsonObject {
                put("conversation_id", "chat")
                put("epoch", "epoch")
                put("version", version)
                put("items", JsonArray(items))
                put("final", final)
                if (final) {
                    val from = (request.payload as JsonObject)["version"]?.jsonPrimitive?.content?.toLong() ?: 0
                    if (from > 0) {
                        put("from_version", from)
                    } else {
                        put("lower_order", 0)
                        put("upper_order", version + 1)
                        put("continuation", 0)
                        put("older_exists", false)
                    }
                }
            },
            inReplyTo = request.id,
        )

    private fun finish(
        target: ProducerThreadObservation,
        items: List<JsonObject>,
        version: Int,
    ) {
        val request = query(target)
        listOf(batch(request, items, version), batch(request, emptyList(), version, true)).forEach {
            frames += it
            target.received(source, it)
        }
    }

    private fun user(
        id: Int = 2,
        rev: Int = id,
        status: String = "queued",
        text: String = "held",
        correlation: String = "phone-send",
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("rev", rev)
            put("kind", "user_message")
            put("status", status)
            put("active", status == "queued")
            put("shown", status != "dropped")
            put("summary", "user")
            if (status == "delivered") {
                put("order", rev)
                put("session", "session")
            }
            put(
                "content",
                buildJsonObject {
                    put("text", text)
                    put("message_id", correlation)
                    put("queued_msg_id", JsonPrimitive("18446744073709551615".toULong()))
                },
            )
        }

    private fun observer(target: ProducerThreadObservation): SessionErrorRecoveryObservation {
        finish(target, emptyList(), 1)
        return SessionErrorRecoveryObservation(target)
    }

    private fun fact(
        id: Int,
        kind: String = "assistant_message",
        session: String = "session",
        turn: String = "reply",
        order: Int = id,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("rev", id)
            put("order", order)
            put("kind", kind)
            put("session", session)
            put("turn", turn)
            put("status", "done")
            put("active", false)
            put("shown", true)
            put("summary", "reply")
            put(
                "content",
                buildJsonObject {
                    if (kind == "turn_end") {
                        put("stop_reason", "end_turn")
                        put("outcome", "success")
                        put("is_error", false)
                    } else {
                        put("text", "recovered1731")
                    }
                },
            )
        }

    private fun JsonObject.change(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(this + fields)

    @Test fun canonicalCatchUpReplacesRetiredFramesForBothArms() {
        for (dropped in listOf(false, true)) {
            val target = producer()
            val observed = observer(target)
            finish(target, listOf(user()), 2)
            val held = checkNotNull(observed.queued()).single()
            assertEquals(ULong.MAX_VALUE, held.queuedMsgId)
            assertTrue(observed.retained(held))
            assertTrue(checkNotNull(observed.deliveries()).isEmpty())
            if (dropped) {
                finish(target, listOf(user(rev = 3, status = "dropped")), 3)
                assertTrue(observed.dropped(held))
            }
            val delivery = if (dropped) user(4, 5, "delivered", "fresh", "fresh-send") else user(rev = 5, status = "delivered")
            finish(target, listOf(delivery, fact(6), fact(7, "turn_end")), 7)
            val accepted = checkNotNull(observed.deliveries()).single()
            assertEquals(if (dropped) 4L else held.id, accepted.id)
            assertTrue(observed.completed(accepted))
            finish(target, emptyList(), 7)
            assertEquals(1, observed.deliveries()?.size)
            assertTrue(observed.completed(accepted))
            assertTrue(checkNotNull(observed.queued()).isEmpty())
            if (dropped) assertTrue(observed.dropped(held))
            assertTrue(frames.all { it.type == "thread_items" })
        }
    }

    @Test fun dropThenFreshNeverMergesAcceptances() {
        val target = producer()
        val observed = observer(target)
        finish(target, listOf(user()), 2)
        val held = checkNotNull(observed.queued()).single()
        finish(target, listOf(user(rev = 3, status = "dropped"), user(4, 5, "delivered"), fact(6), fact(7, "turn_end")), 7)
        val fresh = checkNotNull(observed.deliveries()).single()
        assertNotEquals(held.id, fresh.id)
        assertEquals(held.messageId, fresh.messageId)
        assertEquals(held.text, fresh.text)
        assertTrue(observed.dropped(held))
        assertFalse(observed.completed(held))
        assertTrue(observed.completed(fresh))
    }

    @Test fun incompleteAndForeignEvidenceCannotProveRecovery() {
        for (case in listOf("partial", "source", "conversation", "correlation", "stale", "closed", "reset", "epoch")) {
            current = true
            val target = producer()
            val observed = observer(target)
            val request = query(target)
            var body = batch(request, listOf(user()), 2)
            if (case ==
                "conversation"
            ) {
                body = body.copy(payload = (body.payload as JsonObject).change("conversation_id" to JsonPrimitive("other")))
            }
            if (case == "epoch") body = body.copy(payload = (body.payload as JsonObject).change("epoch" to JsonPrimitive("other")))
            if (case == "correlation") body = body.copy(inReplyTo = request.id + 100)
            if (case == "stale") current = false
            if (case == "closed") target.ended(source)
            val from = if (case == "source") Any() else source
            if (case == "reset") {
                target.received(
                    source,
                    Envelope(
                        ++sequence,
                        "thread_reset",
                        "",
                        buildJsonObject {
                            put("conversation_id", "chat")
                            put("epoch", "replacement")
                            put("version", 2)
                        },
                        inReplyTo = request.id,
                    ),
                )
            } else {
                target.received(from, body)
                if (case != "partial") {
                    val terminal =
                        batch(request, emptyList(), 2, true).let {
                            if (case == "correlation") it.copy(inReplyTo = request.id + 100) else it
                        }
                    target.received(from, terminal)
                }
            }
            assertNull(case, observed.queued())
            assertNull(case, observed.deliveries())
        }
    }

    @Test fun replacementRequiresCurrentCertification() {
        val target = producer()
        val observed = observer(target)
        finish(target, listOf(user()), 2)
        val held = checkNotNull(observed.queued()).single()
        val abandoned = query(target)
        target.received(source, batch(abandoned, listOf(user(rev = 3, status = "dropped")), 3))
        val old = source
        target.ended(old)
        source = Any()
        target.replaceConnection(source, setOf("thread", "interactive")) { current }
        assertNull(observed.queued())
        val request = query(target)
        assertEquals("2", (request.payload as JsonObject)["version"]?.jsonPrimitive?.content)
        target.received(old, batch(abandoned, emptyList(), 3, true))
        assertFalse(observed.dropped(held))
        target.received(source, batch(request, emptyList(), 2, true))
        assertTrue(observed.retained(held))
        finish(target, listOf(user(rev = 3, status = "dropped")), 3)
        assertTrue(observed.dropped(held))
    }

    @Test fun queueIncludesAllActiveItemsAndPreservesTerminalResolution() {
        val target = producer()
        val observed = observer(target)
        finish(target, listOf(user(), user(3)), 3)
        val queued = checkNotNull(observed.queued())
        assertEquals(2, queued.size)
        finish(target, listOf(user(rev = 4, status = "dropped")), 4)
        assertEquals(3L, checkNotNull(observed.queued()).single().id)
        assertTrue(observed.dropped(queued.first { it.id == 2L }))
        finish(target, listOf(user(rev = 2)), 4)
        assertTrue(observed.dropped(queued.first { it.id == 2L }))
        assertEquals(1, observed.queued()?.size)
    }

    @Test fun completionRequiresOwnedOrderedSuccessfulTerminal() {
        for (case in listOf(
            "valid",
            "idle",
            "wrong-session",
            "wrong-turn",
            "before-content",
            "active",
            "failed",
            "child",
            "next-user",
            "wrong-user-turn",
            "replayed-turn",
        )) {
            val target = producer()
            if (case == "replayed-turn") finish(target, listOf(fact(1)), 1) else finish(target, emptyList(), 1)
            val observed = SessionErrorRecoveryObservation(target)
            finish(target, listOf(user()), 2)
            val accepted = checkNotNull(observed.queued()).single()
            var assistant = fact(5)
            var terminal = fact(6, "turn_end")
            var delivery = user(rev = 3, status = "delivered")
            if (case == "wrong-session") terminal = terminal.change("session" to JsonPrimitive("foreign"))
            if (case == "wrong-turn") terminal = terminal.change("turn" to JsonPrimitive("foreign"))
            if (case == "before-content") terminal = terminal.change("order" to JsonPrimitive(4))
            if (case == "active") assistant = assistant.change("active" to JsonPrimitive(true))
            if (case == "failed") terminal = terminal.change("status" to JsonPrimitive("failed"))
            if (case == "child") assistant = assistant.change("parent" to JsonPrimitive(99))
            if (case == "wrong-user-turn") delivery = delivery.change("turn" to JsonPrimitive("foreign"))
            val facts = mutableListOf(delivery, assistant)
            if (case != "idle") facts += terminal
            if (case == "next-user") facts += user(4, 4, "delivered")
            finish(target, facts, 6)
            target.received(
                source,
                Envelope(
                    ++sequence,
                    "turn_state",
                    "",
                    buildJsonObject {
                        put("conversation_id", "chat")
                        put("state", "idle")
                    },
                ),
            )
            assertEquals(case, case == "valid", observed.completed(accepted))
        }
    }

    @Test fun changedHandleOrCorrelationCannotResolveAnAcceptance() {
        for (field in listOf("queued_msg_id", "message_id", "text", "client_sent_at")) {
            val target = producer()
            val observed = observer(target)
            finish(target, listOf(user()), 2)
            val accepted = checkNotNull(observed.queued()).single()
            val delivered = user(rev = 3, status = "delivered")
            val content =
                (delivered["content"] as JsonObject).change(
                    field to if (field == "queued_msg_id") JsonPrimitive(7) else JsonPrimitive("other"),
                )
            finish(target, listOf(delivered.change("content" to content), fact(4), fact(5, "turn_end")), 5)
            assertFalse(field, observed.completed(accepted))
            assertFalse(field, observed.dropped(accepted))
        }
    }
}
