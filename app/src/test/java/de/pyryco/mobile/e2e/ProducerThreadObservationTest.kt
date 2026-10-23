package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadQueryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProducerThreadObservationTest {
    private val savedSink = RelayLog.sink

    @Before fun quietLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = savedSink
    }

    private val source = Any()
    private var current = true
    private var sequence = 0L

    private fun observer() = ProducerThreadObservation("host", "chat", source, setOf("thread", "interactive")) { current }

    private fun request(target: ProducerThreadObservation): Envelope {
        val query = target.nextQuery()
        return Envelope(++sequence, query.type, "", query.payload()).also {
            target.sending(source, it)
            target.sent(source, it, true)
        }
    }

    private fun item(
        id: Int,
        kind: String = "assistant_message",
        text: String = "older",
        turn: String = "post",
        session: String = "session",
    ) = buildJsonObject {
        put("id", id)
        put("rev", id)
        put("order", id)
        put("kind", kind)
        put("session", session)
        put("turn", turn)
        put("active", false)
        put("status", if (kind == "user_message") "delivered" else "done")
        put("shown", kind != "turn_end")
        put("summary", "")
        put(
            "content",
            buildJsonObject {
                put("text", text)
                if (kind == "user_message") put("message_id", "send-id")
                if (kind == "turn_end") {
                    put("stop_reason", "end_turn")
                    put("outcome", "success")
                    put("is_error", false)
                    put("terminal_reason", "completed")
                }
            },
        )
    }

    private fun batch(
        request: Envelope,
        items: List<JsonObject>,
        version: Int,
        final: Boolean = false,
        older: Boolean = false,
    ): Envelope =
        Envelope(
            100 + request.id,
            "thread_items",
            "",
            buildJsonObject {
                put("conversation_id", "chat")
                put("epoch", "epoch")
                put("version", version)
                put("items", JsonArray(items))
                put("final", final)
                if (final) {
                    val query = request.payload as JsonObject
                    val from = (query["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                    if (from > 0) {
                        put("from_version", from)
                    } else {
                        put("lower_order", if (request.type == "thread_page") 0 else 1)
                        put("upper_order", if (request.type == "thread_page") 1 else version + 1)
                        put("continuation", if (request.type == "thread_page") 0 else 1)
                        put("older_exists", older)
                    }
                }
            },
            inReplyTo = request.id,
        )

    private fun finish(
        target: ProducerThreadObservation,
        request: Envelope,
        facts: List<JsonObject>,
        version: Int,
    ) {
        target.received(source, batch(request, facts, version))
        target.received(source, batch(request, emptyList(), version, final = true))
    }

    private fun baseline(target: ProducerThreadObservation) = request(target).also { finish(target, it, listOf(item(1)), 1) }

    private fun send(target: ProducerThreadObservation) {
        target.expectReply()
        val send =
            Envelope(
                ++sequence,
                "send_message",
                "",
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("message_id", "send-id")
                    put("text", "prompt")
                },
            )
        target.sending(source, send)
        target.sent(source, send, true)
    }

    private fun reply() =
        listOf(item(2, "user_message", "prompt", "reply"), item(3, text = "ping", turn = "reply"), item(4, "turn_end", "", "reply"))

    @Test fun baselineRequiresSeparateSuccessfulFinal() {
        val target = observer()
        val query = request(target)
        target.received(source, batch(query, listOf(item(1)), 1))
        assertNull(target.evidence())
        assertFalse(target.completedPost("older"))
        assertThrows(IllegalStateException::class.java) { target.nextQuery() }
        target.received(source, batch(query, emptyList(), 1, final = true))
        assertTrue(target.completed(query.id))
        assertTrue(target.completedPost("older"))
    }

    @Test fun canonicalCatchUpRecoversPostThenExactReplyWithoutRawFrames() {
        val target = observer()
        baseline(target)
        val post = request(target)
        assertEquals(ThreadQueryRequest.CatchUp("chat", "epoch", 1uL).payload(), post.payload)
        finish(target, post, listOf(item(2, text = "last-older")), 2)
        assertTrue(target.completedPost("last-older"))
        send(target)
        val query = request(target)
        val facts =
            reply().map {
                JsonObject(
                    it +
                        mapOf(
                            "id" to JsonPrimitive((it["id"] as JsonPrimitive).content.toInt() + 1),
                            "rev" to JsonPrimitive((it["rev"] as JsonPrimitive).content.toInt() + 1),
                            "order" to JsonPrimitive((it["order"] as JsonPrimitive).content.toInt() + 1),
                        ),
                )
            }
        target.received(source, batch(query, facts, 5))
        assertNull(target.produced())
        target.received(source, batch(query, emptyList(), 5, final = true))
        val produced = checkNotNull(target.produced())
        assertEquals("ping", produced.text)
        assertEquals("reply", produced.turn)
        assertEquals("session", produced.session)
        val repeated = request(target)
        finish(target, repeated, facts, 5)
        assertEquals(5, checkNotNull(target.evidence()).sourceSnapshot?.items?.size)
        assertEquals("ping", target.produced()?.text)
        target.ended(source)
        assertNull(target.produced())
    }

    @Test fun baselinePagesCannotBeReplacedByAnUncertifiedNewestWindow() {
        val target = observer()
        val query = request(target)
        target.received(source, batch(query, listOf(item(1)), 1))
        target.received(source, batch(query, emptyList(), 1, final = true, older = true))
        assertNull(target.evidence())
        val page = request(target)
        assertEquals("thread_page", page.type)
        finish(target, page, emptyList(), 1)
        assertNotNull(target.evidence())
    }

    @Test fun missingCapabilitiesCannotStartProduction() {
        for (capabilities in listOf(emptySet(), setOf("thread"), setOf("interactive"))) {
            assertThrows(IllegalArgumentException::class.java) { ProducerThreadObservation("host", "chat", source, capabilities) { true } }
        }
    }

    @Test fun sessionlessCanonicalPostStillNeedsItsOwnCompletion() {
        val target = observer()
        val query = request(target)
        finish(target, query, listOf(item(1, session = "")), 1)
        assertTrue(target.completedPost("older"))
        val changed = request(target)
        finish(
            target,
            changed,
            listOf(JsonObject(item(1, session = "") + mapOf("rev" to JsonPrimitive(2), "active" to JsonPrimitive(true)))),
            2,
        )
        assertFalse(target.completedPost("older"))
    }

    @Test fun partialStaleForeignAndAbandonedQueriesNeverProveProduction() {
        for (case in listOf(
            "partial",
            "stale",
            "foreign-source",
            "foreign-conversation",
            "foreign-epoch",
            "foreign-query",
            "closed",
            "reset",
            "error",
        )) {
            current = true
            val target = observer()
            baseline(target)
            send(target)
            val query = request(target)
            val from = if (case == "foreign-source") Any() else source
            if (case == "stale") current = false
            if (case == "closed") target.ended(source)
            var body = batch(query, reply(), 4)
            if (case.startsWith("foreign-") && case != "foreign-source") {
                body =
                    if (case ==
                        "foreign-query"
                    ) {
                        body.copy(inReplyTo = 999)
                    } else {
                        body.copy(
                            payload =
                                JsonObject(
                                    (body.payload as JsonObject) +
                                        ((if (case == "foreign-epoch") "epoch" else "conversation_id") to JsonPrimitive("other")),
                                ),
                        )
                    }
            }
            target.received(from, body)
            if (case in
                listOf("reset", "error")
            ) {
                target.received(
                    source,
                    body.copy(
                        type =
                            if (case ==
                                "reset"
                            ) {
                                "thread_reset"
                            } else {
                                "error"
                            },
                        payload =
                            buildJsonObject {
                                put("conversation_id", "chat")
                                put("epoch", "epoch")
                                put("version", 4)
                                put("code", "unavailable")
                            },
                    ),
                )
            }
            if (case != "partial") target.received(from, batch(query, emptyList(), 4, final = true))
            assertNull(case, target.produced())
        }
    }

    @Test fun unrelatedIdenticalTextCannotSubstituteForTheSentReply() {
        for (case in listOf("message", "turn", "session", "failed", "active", "ack")) {
            val target = observer()
            baseline(target)
            send(target)
            val query = request(target)
            val facts =
                reply().mapIndexed { index, fact ->
                    when {
                        case == "message" && index == 0 ->
                            JsonObject(
                                fact + (
                                    "content" to
                                        buildJsonObject {
                                            put("message_id", "unrelated")
                                            put("text", "prompt")
                                        }
                                ),
                            )
                        case == "turn" && index > 0 -> JsonObject(fact + ("turn" to JsonPrimitive("unrelated")))
                        case == "session" && index > 0 -> JsonObject(fact + ("session" to JsonPrimitive("unrelated")))
                        case == "failed" && index == 2 -> JsonObject(fact + ("status" to JsonPrimitive("failed")))
                        case == "active" && index == 1 -> JsonObject(fact + ("active" to JsonPrimitive(true)))
                        else -> fact
                    }
                }
            finish(target, query, if (case == "ack") emptyList() else facts, 4)
            assertNull(case, target.produced())
        }
    }

    @Test fun earlyRepliesRequireAcceptedSendAndRetirementRemainsPermanent() {
        for (accepted in listOf(false, true)) {
            current = true
            val target = observer()
            val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
            target.sending(source, query)
            finish(target, query, listOf(item(1)), 1)
            assertNull(target.evidence())
            target.sent(source, query, accepted)
            assertEquals(accepted, target.completedPost("older"))
            target.ended(Any())
            assertEquals(accepted, target.completedPost("older"))
            target.ended(source)
            assertNull(target.evidence())
            current = false
            current = true
            assertNull(target.evidence())
        }
    }
}
