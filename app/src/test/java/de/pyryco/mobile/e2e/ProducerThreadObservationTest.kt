package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadQueryRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    @Test fun lostSourceCannotAcquireBaseline() {
        assertNull(ProducerThreadObservation.acquire("host", "chat", source, null) { true })
        assertNull(ProducerThreadObservation.acquire("host", "chat", source, setOf("thread", "interactive")) { false })
        var reads = 0
        assertNull(ProducerThreadObservation.acquire("host", "chat", source, setOf("thread", "interactive")) { ++reads == 1 })
        assertThrows(IllegalArgumentException::class.java) {
            ProducerThreadObservation.acquire("host", "chat", source, setOf("interactive")) { true }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun baselineAcquisitionWaitsForRedialWithinOriginalDeadline() =
        runTest {
            val ended = CompletableDeferred<String>()
            val replacementEnd = CompletableDeferred<String>()
            val replacement = Any()
            var dials = 0
            val link =
                RedialingLink(
                    backgroundScope,
                    { if (++dials == 1) source else replacement },
                    { if (it === source) ended.await() else replacementEnd.await() },
                    {},
                )
            link.start()
            ended.complete("closed before baseline")
            runCurrent()
            assertNull(link.current.value)
            val baseline =
                async {
                    withTimeout(5_000) {
                        link.request("producer baseline", resend = true) { live ->
                            val observation =
                                ProducerThreadObservation.acquire("host", "chat", live, setOf("thread", "interactive")) {
                                    link.current.value === live
                                } ?: return@request RedialingLink.Attempt.Ended
                            RedialingLink.Attempt.Answered(observation)
                        }
                    }
                }
            runCurrent()
            assertFalse(baseline.isCompleted)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue(baseline.await().connection === replacement)
            link.close()
        }

    @Test fun sourceLossBetweenPreparationStepsReturnsNoQueryWithoutHidingInvariants() {
        val target = observer()
        baseline(target)
        val replacement = Any()
        assertFalse(target.bindConnection(source, null) { false })
        assertFalse(target.bindConnection(replacement, null) { true })
        assertTrue(target.connection === source)
        var open = true
        assertTrue(target.bindConnection(replacement, setOf("thread", "interactive")) { open })
        open = false
        assertNull(target.queryIfCurrent())
        open = true
        val query = checkNotNull(target.queryIfCurrent()).let { Envelope(++sequence, it.type, "", it.payload()) }
        open = false
        target.sending(replacement, query)
        target.sent(replacement, query, false)
        assertNull(target.evidence())
        open = true
        target.sending(replacement, query)
        assertThrows(IllegalStateException::class.java) { target.queryIfCurrent() }
        val invalid = observer()
        assertThrows(IllegalArgumentException::class.java) {
            invalid.bindConnection(Any(), setOf("interactive")) { true }
        }
    }

    @Test fun closureAfterSendingOwnershipCheckCannotThrowDuringQueryPreparation() {
        val target = observer()
        baseline(target)
        val replacement = Any()
        var closing = false
        var reads = 0
        target.bindConnection(replacement, setOf("thread", "interactive")) { !closing || ++reads == 1 }
        val query = checkNotNull(target.queryIfCurrent()).let { Envelope(++sequence, it.type, "", it.payload()) }
        closing = true
        target.sending(replacement, query)
        target.sent(replacement, query, false)
        assertNull(target.evidence())
    }

    @Test fun sourceClosingDuringBindCannotPrepareAQuery() {
        val target = observer()
        baseline(target)
        var reads = 0
        assertFalse(target.bindConnection(Any(), setOf("thread", "interactive")) { ++reads == 1 })
        assertNull(target.queryIfCurrent())
    }

    @Test fun replacementDiscardsPartialBatchesAndRequiresItsOwnCatchUp() {
        val target = observer()
        baseline(target)
        val abandoned = request(target)
        target.received(source, batch(abandoned, listOf(item(2, text = "uncommitted")), 2))
        target.ended(source)
        val replacement = Any()
        target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
        assertNull(target.evidence())
        assertEquals(ThreadQueryRequest.CatchUp("chat", "epoch", 1uL).payload(), target.nextQuery().payload())
        val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
        target.sending(replacement, query)
        target.sent(replacement, query, true)
        target.received(source, batch(abandoned, emptyList(), 2, final = true))
        assertNull(target.evidence())
        target.received(replacement, batch(query, listOf(item(2, text = "last-older")), 2))
        assertFalse(target.completedPost("last-older"))
        target.received(replacement, batch(query, emptyList(), 2, final = true))
        assertTrue(target.completedPost("last-older"))
        assertTrue(checkNotNull(target.evidence()).owner.connection === replacement)
    }

    @Test fun replacementRetainsOneActualSendButCannotReuseItsOldCompletion() {
        val target = observer()
        baseline(target)
        send(target)
        val first = request(target)
        finish(target, first, reply(), 4)
        assertNotNull(target.produced())
        target.ended(source)
        val replacement = Any()
        target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
        assertNull(target.produced())
        val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
        assertEquals(ThreadQueryRequest.CatchUp("chat", "epoch", 4uL).payload(), query.payload)
        target.sending(replacement, query)
        target.sent(replacement, query, true)
        target.received(source, batch(query, emptyList(), 4, final = true))
        assertNull(target.produced())
        target.received(replacement, batch(query, emptyList(), 4, final = true))
        assertEquals("send-id", target.produced()?.messageId)
        assertEquals("ping", target.produced()?.text)
        target.ended(source)
        assertNotNull(target.produced())
    }

    @Test fun sourceLossBetweenCompletionAndAdmissionRequiresFreshReplacementCertification() {
        var closing = false
        var reads = 0
        val target =
            ProducerThreadObservation("host", "chat", source, setOf("thread", "interactive")) {
                if (closing && ++reads == 5) current = false
                current
            }
        baseline(target)
        send(target)
        finish(target, request(target), reply(), 4)
        closing = true
        // Evidence and producer completion both read a live source; admission sees its retirement.
        assertNull(target.produced())
        assertEquals(5, reads)
        val replacement = Any()
        target.ended(source)
        target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
        assertNull(target.produced())
        val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
        target.sending(replacement, query)
        target.sent(replacement, query, true)
        target.received(replacement, batch(query, emptyList(), 4, final = true))
        assertEquals("send-id", target.produced()?.messageId)
        assertEquals("ping", target.produced()?.text)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sourceLossAfterCompletedPostRecoversArmingWithinOriginalBound() =
        runTest {
            var reads = 0
            var closing = false
            val target =
                ProducerThreadObservation("host", "chat", source, setOf("thread", "interactive")) {
                    val open = current
                    if (closing && ++reads == 2) current = false
                    open
                }
            baseline(target)
            closing = true
            val replacement = Any()
            var sends = 0
            var queries = 0
            val produced =
                target.awaitReplyAfterPost("older", 90_000, catchUp = {
                    if (target.connection === source) {
                        assertFalse(current)
                        target.ended(source)
                        target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
                    }
                    val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
                    target.sending(replacement, query)
                    target.sent(replacement, query, true)
                    target.received(replacement, batch(query, if (sends == 0) emptyList() else reply(), if (sends == 0) 1 else 4))
                    target.received(replacement, batch(query, emptyList(), if (sends == 0) 1 else 4, final = true))
                    queries++
                }, send = {
                    assertTrue(target.connection === replacement)
                    val envelope = sendEnvelope("send-id")
                    target.sending(replacement, envelope)
                    target.sent(replacement, envelope, true)
                    sends++
                })
            assertEquals(1, sends)
            assertEquals(2, queries)
            assertEquals("send-id", produced.messageId)
            assertEquals("ping", produced.text)
            assertTrue(testScheduler.currentTime < 90_000)
            assertThrows(IllegalStateException::class.java) { target.expectReply() }
        }

    @Test fun retirementAfterSuccessfulFreezeDoesNotRequireAnotherEvidenceRead() =
        runTest {
            var reads = 0
            var retireOnRead: Int? = null
            val target =
                ProducerThreadObservation("host", "chat", source, setOf("thread", "interactive")) {
                    val open = current
                    if (++reads == retireOnRead) current = false
                    open
                }
            baseline(target)
            var sends = 0
            var queries = 0
            val produced =
                target.awaitReplyAfterPost("older", 90_000, catchUp = {
                    error("completed reply must be returned without another query")
                }, send = {
                    val envelope = sendEnvelope("send-id")
                    target.sending(source, envelope)
                    target.sent(source, envelope, true)
                    sends++
                    finish(target, request(target), reply(), 4)
                    reads = 0
                    assertNotNull(target.produced())
                    // Retire immediately after the last ownership read that successfully freezes the reply.
                    retireOnRead = reads
                    reads = 0
                    queries++
                })
            assertFalse(current)
            assertNull(target.produced())
            assertEquals(1, sends)
            assertEquals(1, queries)
            assertEquals("send-id", produced.messageId)
            assertEquals("reply", produced.turn)
            assertEquals("session", produced.session)
            assertEquals("ping", produced.text)
        }

    @Test fun refusedSendDoesNotOccupyReplacementReceipt() {
        val target = observer()
        baseline(target)
        target.expectReply()
        val refused = sendEnvelope("send-id")
        target.sending(source, refused)
        target.sent(source, refused, false)
        target.ended(source)
        val replacement = Any()
        target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
        val accepted = sendEnvelope("send-id")
        target.sending(replacement, accepted)
        target.sent(replacement, accepted, true)
        // Delayed callbacks for the refused attempt cannot replace the actual receipt.
        target.sent(source, refused, true)
        assertNull(target.produced())
        val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
        target.sending(replacement, query)
        target.sent(replacement, query, true)
        target.received(replacement, batch(query, reply(), 4))
        assertNull(target.produced())
        target.received(replacement, batch(query, emptyList(), 4, final = true))
        assertEquals("send-id", target.produced()?.messageId)
        assertEquals("ping", target.produced()?.text)
    }

    @Test fun acceptedSendCallbackSurvivesRetirementButForeignCallbacksCannotAcceptIt() {
        for (callbackAfterReplacement in listOf(false, true)) {
            current = true
            val target = observer()
            baseline(target)
            target.expectReply()
            val accepted = sendEnvelope("send-id")
            target.sending(source, accepted)
            // The pump accepted the frame, but its collector closes before sent returns.
            current = false
            target.ended(source)
            val replacement = Any()
            if (!callbackAfterReplacement) target.sent(source, accepted, true)
            target.replaceConnection(replacement, setOf("thread", "interactive")) { true }
            val query = target.nextQuery().let { Envelope(++sequence, it.type, "", it.payload()) }
            target.sending(replacement, query)
            target.sent(replacement, query, true)
            target.received(replacement, batch(query, reply(), 4))
            assertNull(target.produced())
            target.received(replacement, batch(query, emptyList(), 4, final = true))
            if (callbackAfterReplacement) {
                target.sent(Any(), accepted, true)
                target.sent(source, accepted.copy(id = accepted.id + 100), true)
                target.sent(source, accepted.copy(payload = JsonObject(emptyMap())), true)
                assertNull(target.produced())
                target.sent(source, accepted, true)
            }
            target.sent(source, accepted, false)
            assertEquals("send-id", target.produced()?.messageId)
            assertEquals("ping", target.produced()?.text)
        }
    }

    private fun sendEnvelope(messageId: String) =
        Envelope(
            ++sequence,
            "send_message",
            "",
            buildJsonObject {
                put("conversation_id", "chat")
                put("message_id", messageId)
                put("text", "prompt")
            },
        )

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
