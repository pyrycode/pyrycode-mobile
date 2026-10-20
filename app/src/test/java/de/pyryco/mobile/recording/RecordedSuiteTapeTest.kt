package de.pyryco.mobile.recording

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordedSuiteTapeTest {
    @Test fun allowanceReadRacesOtherReadsWithoutReleasingAFutureActionsReply() {
        val owner = "phone/host/0"
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:action")),
                    client(owner, frame(1, "list_conversations")),
                    daemon(owner, frame(2, "conversations", reply = 1)),
                    client(owner, frame(3, "request_claude_usage")),
                    daemon(owner, frame(4, "claude_usage", """{"state":"none"}""", reply = 3)),
                ),
            )
        assertEquals(emptyList<SuiteStep>(), tape.request(owner, frame(90, "request_claude_usage")))
        assertEquals(emptyList<SuiteStep>(), tape.request(owner, frame(91, "list_conversations")))
        val released = tape.request("control", frame(90, "test-control:action"))
        assertEquals(listOf("conversations", "claude_usage"), released.map { it.message!!.type })
        assertEquals(listOf(91L, 90L), released.map { it.message!!.inReplyTo })
        assertEquals(
            """{"state":"none"}""",
            released
                .last()
                .message!!
                .payload
                .toString(),
        )
        tape.assertComplete()
    }

    @Test fun allowanceRequestsKeepExactHostConnectionAndFailureOwnership() {
        val old = "phone/host-a/0"
        val next = "phone/host-a/1"
        val other = "phone/host-b/0"
        val rows =
            listOf(
                SuiteStep("connect", old),
                SuiteStep("open", old),
                client(old, frame(1, "request_claude_usage")),
                daemon(old, frame(2, "claude_usage", """{"state":"none"}""", reply = 1)),
                SuiteStep("close", old),
                SuiteStep("connect", next),
                SuiteStep("open", next),
                client(next, frame(1, "request_claude_usage")),
                daemon(next, frame(2, "claude_usage", """{"state":"none"}""", reply = 1)),
            )
        val replay = RecordedSuiteTape(rows)
        replay.lifecycle("connect", old)
        assertEquals(
            90L,
            replay
                .request(old, frame(90, "request_claude_usage"))
                .single()
                .message!!
                .inReplyTo,
        )
        replay.lifecycle("close", old)
        replay.lifecycle("connect", next)
        assertEquals(
            90L,
            replay
                .request(next, frame(90, "request_claude_usage"))
                .single()
                .message!!
                .inReplyTo,
        )
        replay.assertComplete()
        for ((owner, payload) in listOf(old to "null", old to "[]", old to """{"conversation_id":"chat"}""", next to "{}", other to "{}")) {
            val invalid = RecordedSuiteTape(rows)
            invalid.lifecycle("connect", old)
            val failure =
                assertThrows(IllegalStateException::class.java) { invalid.send(owner, frame(90, "request_claude_usage", payload)) }
            val later = assertThrows(IllegalStateException::class.java) { invalid.send(old, frame(91, "request_claude_usage")) }
            assertEquals(failure.message, later.message)
        }
        val missing = RecordedSuiteTape(rows)
        missing.lifecycle("connect", old)
        assertThrows(IllegalStateException::class.java) { missing.assertComplete() }
        val duplicate = RecordedSuiteTape(rows)
        duplicate.lifecycle("connect", old)
        duplicate.request(old, frame(90, "request_claude_usage"))
        assertThrows(IllegalStateException::class.java) { duplicate.send(old, frame(91, "request_claude_usage")) }
    }

    @Test fun allowanceCannotCrossAnObservationCheckpoint() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", """{"body":"before-reconnect"}""")),
                    client("phone", frame(1, "request_claude_usage")),
                ),
            )
        assertThrows(IllegalStateException::class.java) { tape.send("phone", frame(90, "request_claude_usage")) }
    }

    private var savedLogSink = RelayLog.sink

    @Before
    fun muteDecoderLogs() {
        savedLogSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun restoreDecoderLogs() {
        RelayLog.sink = savedLogSink
    }

    private data class TailCase(
        val tape: RecordedSuiteTape,
        val owner: String,
        val priorOwner: String,
        val prior: Envelope,
        val tail: Envelope,
        val end: Envelope,
        val boundary: String,
    )

    private fun tailCase(
        condition: String,
        boundary: String,
        duplicate: Boolean = false,
    ): TailCase {
        val owner = "phone/host/0"
        val priorOwner = if (condition == "foreign-owner") "phone/host/1" else owner
        val priorConversation = if (condition == "foreign-conversation") "other" else "chat"
        val priorEpoch = if (condition == "foreign-epoch") "other" else "epoch"

        fun fact(
            id: Int = 1,
            text: String = "controlled reply",
        ) = """{"id":$id,
"order":$id,
"rev":17,
"kind":"assistant_message",
"session":"session",
"turn":"turn",
"status":"done",
"active":false,
"shown":true,
"summary":"reply",
"content":{"text":"$text"}}"""

        fun batch(
            conversation: String,
            epoch: String,
            item: String,
        ) = """{"conversation_id":"$conversation","epoch":"$epoch","version":17,"items":[$item],"final":false}"""
        val window =
            """
{"conversation_id":"$priorConversation",
"epoch":"$priorEpoch",
"version":17,
"items":[],
"final":true,
"lower_order":1,
"upper_order":1,
"continuation":1,
"older_exists":false}
"""
        val prior = frame(1, "thread_catch_up", """{"conversation_id":"$priorConversation","epoch":"$priorEpoch","version":0}""")
        val tail =
            when (condition) {
                "page" -> frame(4, "thread_page", """{"conversation_id":"chat","before_order":1}""")
                "extra-argument" -> frame(4, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":17,"limit":50}""")
                else -> frame(4, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":17}""")
            }
        val end = frame(1, "test-control:test-end", """{"body":""}""")
        val rows =
            mutableListOf(
                client(priorOwner, prior).copy(accepted = condition != "refused-prior"),
                daemon(priorOwner, frame(2, "thread_items", batch(priorConversation, priorEpoch, fact()), reply = 1)),
            )
        if (condition != "partial-prior") rows += daemon(priorOwner, frame(3, "thread_items", window, reply = 1))
        when (condition) {
            "intervening-live", "intervening-unknown" ->
                rows +=
                    daemon(
                        owner,
                        frame(
                            10,
                            if (condition == "intervening-live") "thread_item_added" else "thread_unknown",
                            """{"conversation_id":"chat","epoch":"epoch","version":18,"item":${fact(2)}}""",
                        ),
                    )
            "intervening-external" ->
                rows +=
                    SuiteStep(
                        "external",
                        "android",
                        detail = JsonObject(mapOf("event" to JsonPrimitive("push-wake"))),
                    )
            "intervening-command" -> rows += client("peer/host/0", frame(1, "modal_answer"))
            "intervening-lifecycle" -> rows += SuiteStep("close", "peer/host/0")
        }
        rows += client(owner, tail).copy(accepted = condition != "refused-tail")
        val replyConversation = if (condition == "wrong-reply-conversation") "other" else "chat"
        val version = if (condition == "higher-checkpoint") 18 else 17
        val terminal =
            if (condition == "page") {
                """{"conversation_id":"chat",
"epoch":"epoch",
"version":17,
"items":[],
"final":true,
"lower_order":1,
"upper_order":1,
"continuation":1,
"older_exists":false}"""
            } else {
                """{"conversation_id":"$replyConversation","epoch":"epoch","version":$version,"items":[],"final":true,"from_version":17}"""
            }
        when (condition) {
            "reset" ->
                rows +=
                    daemon(
                        owner,
                        frame(
                            6,
                            "thread_reset",
                            """{"conversation_id":"chat","epoch":"new-epoch","version":17}""",
                            reply = 4,
                        ),
                    )
            "error" ->
                rows +=
                    daemon(
                        owner,
                        frame(
                            6,
                            "error",
                            """{"conversation_id":"chat","code":"controlled","message":"controlled","retryable":false}""",
                            reply = 4,
                        ),
                    )
            "malformed" -> rows += daemon(owner, frame(6, "thread_items", "{}", reply = 4))
            "partial-tail" -> rows += daemon(owner, frame(6, "thread_items", batch("chat", "epoch", fact()), reply = 4))
            else -> {
                if (duplicate || condition in setOf("new-fact", "changed-fact")) {
                    val item =
                        when (condition) {
                            "new-fact" -> fact(2)
                            "changed-fact" -> fact(text = "changed reply")
                            else -> fact()
                        }
                    rows += daemon(owner, frame(5, "thread_items", batch("chat", "epoch", item), reply = 4))
                }
                if (condition == "unexpected-related") rows += daemon(owner, frame(11, "context_usage", "{}", reply = 4))
                rows += daemon(owner, frame(6, "thread_items", terminal, reply = 4))
                if (condition == "after-terminal") rows += daemon(owner, frame(7, "thread_items", terminal, reply = 4))
            }
        }
        if (boundary == "test-end") rows += client("control", end)
        rows += SuiteStep("close", owner)
        rows += SuiteStep("inbound_end", owner)
        return TailCase(RecordedSuiteTape(rows, literalProducerIdentities = true), owner, priorOwner, prior, tail, end, boundary)
    }

    private fun startTail(
        case: TailCase,
        condition: String = "valid",
    ) {
        case.tape.initial()
        if (condition == "unrequested-prior") return
        val delivered = case.tape.request(case.priorOwner, case.prior.copy(id = 90))
        if (condition == "undelivered-prior") return
        delivered.forEach { step ->
            val message = step.message
            if (message != null && !(condition == "undelivered-final" && message.payload.jsonObject["final"] == JsonPrimitive(true))) {
                case.tape.delivered(step.owner, message)
            }
        }
    }

    private fun prepareTail(case: TailCase) =
        if (case.boundary == "test-end") case.tape.prepareTestEnd(case.end) else case.tape.prepareClose(case.owner)

    private fun tailReady(case: TailCase) =
        if (case.boundary == "test-end") case.tape.testEndReady(case.end) else case.tape.closeReady(case.owner)

    private fun finishTail(case: TailCase) {
        if (case.boundary == "test-end") case.tape.request("control", case.end.copy(id = 90))
        case.tape.lifecycle("close", case.owner)
        case.tape.assertComplete()
    }

    @Test fun duplicateDeliveredTailBecomesOptionalOnlyAtActualClosePreparation() {
        for (duplicate in listOf(false, true)) {
            val case = tailCase("valid", "close", duplicate)
            startTail(case)
            assertFalse(case.tape.closeReady(case.owner))
            assertTrue(prepareTail(case).isEmpty())
            assertTrue("actual delivered duplicate tail should not block close", tailReady(case))
            assertThrows(IllegalStateException::class.java) { case.tape.assertComplete() }
            finishTail(case)
        }
    }

    @Test fun duplicateDeliveredTailKeepsTheActualTestEndAndCloseRequired() {
        for (duplicate in listOf(false, true)) {
            val case = tailCase("valid", "test-end", duplicate)
            startTail(case)
            assertFalse(tailReady(case))
            assertTrue(prepareTail(case).isEmpty())
            assertTrue("actual test-end prefix may omit only a delivered duplicate", tailReady(case))
            assertThrows(IllegalStateException::class.java) { case.tape.assertComplete() }
            finishTail(case)
        }
    }

    @Test fun incompleteChangedOrForeignTailsCannotBorrowDeliveredCoverage() {
        val conditions =
            listOf(
                "unrequested-prior",
                "refused-prior",
                "refused-tail",
                "undelivered-prior",
                "undelivered-final",
                "partial-prior",
                "foreign-owner",
                "foreign-conversation",
                "foreign-epoch",
                "wrong-reply-conversation",
                "higher-checkpoint",
                "new-fact",
                "changed-fact",
                "page",
                "extra-argument",
                "reset",
                "error",
                "malformed",
                "partial-tail",
                "after-terminal",
                "unexpected-related",
                "intervening-live",
                "intervening-unknown",
                "intervening-external",
                "intervening-command",
                "intervening-lifecycle",
            )
        for (boundary in listOf("close", "test-end")) {
            for (condition in conditions) {
                val case = tailCase(condition, boundary)
                startTail(case, condition)
                if (boundary == "test-end" && condition == "intervening-command") {
                    assertThrows(IllegalStateException::class.java) { prepareTail(case) }
                } else {
                    prepareTail(case)
                    assertFalse("$boundary/$condition cannot omit its captured query", tailReady(case))
                }
            }
        }
        val case = tailCase("valid", "test-end")
        startTail(case)
        assertThrows(IllegalStateException::class.java) {
            case.tape.prepareTestEnd(case.end.copy(payload = MobileJson.parseToJsonElement("""{"body":"wrong"}""")))
        }
    }

    @Test fun realTailStillConsumesItsExactReplyBeforeOrAfterBoundaryPreparation() {
        for (boundary in listOf("close", "test-end")) {
            for (prepareFirst in listOf(false, true)) {
                val case = tailCase("valid", boundary, duplicate = true)
                startTail(case)
                if (prepareFirst) prepareTail(case)
                val actual = case.tape.request(case.owner, case.tail.copy(id = 91))
                assertEquals(listOf(91L, 91L), actual.map { it.message!!.inReplyTo })
                actual.forEach { case.tape.delivered(it.owner, checkNotNull(it.message)) }
                if (!prepareFirst) prepareTail(case)
                assertTrue(tailReady(case))
                finishTail(case)
            }
        }
    }

    @Test fun replayCanonicalPhoneEchoRetiresPendingButTheSameMessageFromAPeerDoesNot() {
        val oldSink = de.pyryco.mobile.data.network.RelayLog.sink
        de.pyryco.mobile.data.network.RelayLog.sink = { _, _, _ -> }
        try {
            val owner = "phone/host/0"
            val send = frame(1, "send_message", """{"conversation_id":"chat","message_id":"captured-message","text":"hello"}""")
            val echo =
                frame(
                    2,
                    "thread_item_added",
                    """
{"conversation_id":"chat",
"item":{"id":2,
"order":2,
"rev":2,
"kind":"user_message",
"status":"delivered",
"active":false,
"shown":true,
"summary":"hello",
"content":{"message_id":"captured-message",
"device_id":"captured-phone",
"text":"hello"}}}
""",
                )
            val tape = RecordedSuiteTape(listOf(client(owner, send), daemon(owner, echo)), literalProducerIdentities = true)
            tape.bindPhoneDevice(owner, "runtime-phone")
            val actualSend = frame(90, "send_message", """{"conversation_id":"chat","message_id":"runtime-message","text":"hello"}""")
            val delivered =
                tape
                    .request(owner, actualSend)
                    .single()
                    .message!!
                    .payload.jsonObject
                    .getValue("item")
                    .jsonObject
            val own = ThreadItemDto.decodeReplacement(delivered)
            val peer =
                ThreadItemDto.decodeReplacement(
                    JsonObject(
                        delivered + mapOf("id" to JsonPrimitive(1), "order" to JsonPrimitive(1), "rev" to JsonPrimitive(1)) +
                            (
                                "content" to
                                    JsonObject(
                                        delivered.getValue("content").jsonObject + ("device_id" to JsonPrimitive("foreign-device")),
                                    )
                            ),
                    ),
                )
            val store = AuthoritativeThreadStore()
            val message = Message("runtime-message", "", Role.User, "hello", Instant.fromEpochMilliseconds(0), isStreaming = false)
            store.registerPending("host", "chat", "runtime-phone", message)
            store.applyBatch("host", "chat", "epoch", 1, listOf(peer), 0)
            assertEquals(1, store.current("host", "chat").pendingSends.size)
            store.applyBatch("host", "chat", "epoch", 2, listOf(own), 0)
            assertTrue(store.current("host", "chat").pendingSends.isEmpty())
            tape.assertComplete()
        } finally {
            de.pyryco.mobile.data.network.RelayLog.sink = oldSink
        }
    }

    @Test fun replayBindsOnlyThePhoneDeviceProvenByItsOwnCanonicalSend() {
        val owner = "phone/host/0"
        val send = frame(1, "send_message", """{"conversation_id":"chat","message_id":"captured-message","text":"hello"}""")

        fun item(
            device: String,
            conversation: String = "chat",
        ) = frame(
            2,
            "thread_item_added",
            """
{"conversation_id":"$conversation",
"item":{"kind":"user_message",
"content":{"message_id":"captured-message",
"device_id":"$device"}}}
""",
        )
        val own = item("captured-phone")
        val otherConversation = item("other-phone", "other-chat").copy(id = 3)
        val otherHost = item("other-host-device")
        val tape =
            RecordedSuiteTape(
                listOf(client(owner, send), daemon(owner, own), daemon(owner, otherConversation), daemon("phone/other-host/0", otherHost)),
                literalProducerIdentities = true,
            )
        tape.bindPhoneDevice(owner, "runtime-phone")
        val delivered = tape.request(owner, send.copy(id = 90))
        val devices =
            delivered.map {
                it.message!!
                    .payload.jsonObject
                    .getValue("item")
                    .jsonObject
                    .getValue("content")
                    .jsonObject
                    .getValue("device_id")
            }
        assertEquals(listOf("\"runtime-phone\"", "\"other-phone\"", "\"other-host-device\""), devices.map { it.toString() })
        assertEquals(
            "captured-phone",
            own.payload.jsonObject
                .getValue("item")
                .jsonObject
                .getValue("content")
                .jsonObject
                .getValue("device_id")
                .toString()
                .trim('"'),
        )
        tape.assertComplete()
    }

    @Test fun replayPhoneDeviceBindingCannotChangeOnReconnect() {
        val owner = "phone/host/0"
        val tape = phoneDeviceTape(owner, listOf("captured-phone"))
        tape.bindPhoneDevice(owner, "runtime-phone")
        tape.bindPhoneDevice("phone/host/1", "runtime-phone")
        assertThrows(IllegalStateException::class.java) { tape.bindPhoneDevice("phone/host/2", "different-phone") }
    }

    @Test fun conflictingCanonicalPhoneIdentitiesCannotBeGuessed() {
        val owner = "phone/host/0"
        assertThrows(IllegalStateException::class.java) {
            phoneDeviceTape(owner, listOf("first-device", "second-device")).bindPhoneDevice(owner, "runtime-phone")
        }
    }

    private fun phoneDeviceTape(
        owner: String,
        devices: List<String>,
    ): RecordedSuiteTape =
        RecordedSuiteTape(
            listOf(client(owner, frame(1, "send_message", """{"conversation_id":"chat","message_id":"message"}"""))) +
                devices.mapIndexed { index, device ->
                    daemon(
                        owner,
                        frame(
                            index + 2L,
                            "thread_items",
                            """{"conversation_id":"chat","items":[{"kind":"user_message","content":{"message_id":"message","device_id":"$device"}}]}""",
                        ),
                    )
                },
            literalProducerIdentities = true,
        )

    @Test fun aProducerCannotDeliverLiveUpdatesAheadOfItsEarlierUnrequestedQueryReply() {
        val owner = "phone/host/0"
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":0}""")),
                    daemon(owner, frame(2, "thread_items", """{"final":false}""", reply = 1)),
                    daemon(owner, frame(3, "thread_items", """{"final":true}""", reply = 1)),
                    daemon(owner, frame(4, "thread_item_changed")),
                    daemon("peer/host/0", frame(1, "model_list")),
                ),
                literalProducerIdentities = true,
            )
        assertEquals(listOf("peer/host/0"), tape.initial().map { it.owner })
        val delivered = tape.request(owner, frame(90, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":0}"""))
        assertEquals(listOf("thread_items", "thread_items", "thread_item_changed"), delivered.map { it.message!!.type })
        assertEquals(listOf(90L, 90L, null), delivered.map { it.message!!.inReplyTo })
        tape.assertComplete()
    }

    @Test fun independentlyRequestedConversationCanFinishBeforeAnUnrequestedSiblingQuery() {
        val owner = "phone/host/0"
        val a = """{"conversation_id":"a","epoch":"ea","version":4}"""
        val b = """{"conversation_id":"b","epoch":"eb","version":14}"""
        val replyA = """{"conversation_id":"a","epoch":"ea","version":4,"final":true}"""
        val replyB = """{"conversation_id":"b","epoch":"eb","version":14,"final":true}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, frame(1, "thread_catch_up", a)),
                    daemon(owner, frame(2, "thread_items", replyA, reply = 1)),
                    daemon(owner, frame(3, "conversations", """{"conversations":[]}""")),
                    client(owner, frame(4, "thread_catch_up", b)),
                    daemon(owner, frame(5, "thread_items", replyB, reply = 4)),
                    daemon(owner, frame(6, "thread_item_changed", """{"conversation_id":"a"}""")),
                ),
                literalProducerIdentities = true,
            )
        tape.initial()
        val independent = tape.request(owner, frame(90, "thread_catch_up", b))
        assertEquals(listOf(90L), independent.map { it.message!!.inReplyTo })
        assertEquals(
            listOf("b"),
            independent.map {
                it.message!!
                    .payload.jsonObject["conversation_id"]
                    .toString()
                    .trim('"')
            },
        )
        val sibling = tape.request(owner, frame(91, "thread_catch_up", a))
        assertEquals(listOf("thread_items", "conversations", "thread_item_changed"), sibling.map { it.message!!.type })
        assertEquals(listOf(91L, null, null), sibling.map { it.message!!.inReplyTo })
        tape.assertComplete()
    }

    @Test fun independentThreadReplyCrossesOnlyAlreadyRequestedRecognisedMetadata() {
        val owner = "phone/host/0"
        val a = """{"conversation_id":"a","epoch":"ea","version":4}"""
        val b = """{"conversation_id":"b","epoch":"eb","version":0}"""
        val replyA = """{"conversation_id":"a","final":true}"""
        val replyB = """{"conversation_id":"b","final":true}"""
        for (metadataRequested in listOf(false, true)) {
            val rows =
                listOf(
                    client(owner, frame(1, "register_push_token", """{"token":"recorded-push-device-address"}""")),
                    client(owner, frame(2, "mcp_status_request", """{"conversation_id":"b"}""")),
                    client(owner, frame(3, "request_context_usage", """{"conversation_id":"b"}""")),
                    client(owner, frame(4, "request_model_list", """{"conversation_id":"b"}""")),
                    client(owner, frame(5, "thread_catch_up", a)),
                    daemon(owner, frame(6, "ack", "{}", reply = 1)),
                    daemon(owner, frame(7, "error", "{}", reply = 2)),
                    daemon(owner, frame(8, "error", "{}", reply = 3)),
                    daemon(owner, frame(9, "model_list", """{"conversation_id":"b","models":[]}""", reply = 4)),
                    daemon(owner, frame(10, "thread_items", replyA, reply = 5)),
                    client(owner, frame(11, "thread_catch_up", b)),
                    daemon(owner, frame(12, "thread_items", replyB, reply = 11)),
                )
            val tape = RecordedSuiteTape(rows, literalProducerIdentities = true)
            assertTrue(tape.initial().isEmpty())
            if (metadataRequested) {
                for (step in rows.take(4)) assertTrue(tape.request(owner, step.message!!.copy(id = step.message.id + 100)).isEmpty())
            }
            val independent = tape.request(owner, frame(90, "thread_catch_up", b))
            if (metadataRequested) {
                assertEquals(listOf(90L), independent.map { it.message!!.inReplyTo })
                val remaining = tape.request(owner, frame(91, "thread_catch_up", a))
                assertEquals(listOf("ack", "error", "error", "model_list", "thread_items"), remaining.map { it.message!!.type })
                assertEquals(listOf(101L, 102L, 103L, 104L, 91L), remaining.map { it.message!!.inReplyTo })
                tape.assertComplete()
            } else {
                assertTrue("unrequested metadata cannot be borrowed", independent.isEmpty())
            }
        }
    }

    @Test fun independentThreadReplyCrossesOnlyTheSameSourceRequestedMetadataClearPair() {
        val owner = "phone/host/0"
        val a = """{"conversation_id":"a","epoch":"ea","version":4}"""
        val b = """{"conversation_id":"b","epoch":"eb","version":14}"""
        for ((requestType, responseType) in listOf("request_context_usage" to "context_usage", "request_model_list" to "model_list")) {
            for (condition in listOf("accepted", "unrequested", "foreign-owner", "foreign-conversation", "ordinary-push", "missing-pair")) {
                val metadataOwner = if (condition == "foreign-owner") "phone/host/1" else owner
                val responseConversation = if (condition == "foreign-conversation") "other" else "b"
                val clear =
                    frame(4, responseType).copy(
                        conversationId = JsonPrimitive("b"),
                        sessionStateCleared = JsonPrimitive(condition != "ordinary-push"),
                    )
                val response =
                    frame(5, responseType, """{"conversation_id":"$responseConversation"}""", reply = 1)
                        .copy(conversationId = JsonPrimitive(responseConversation))
                val rows =
                    mutableListOf(
                        client(metadataOwner, frame(1, requestType, """{"conversation_id":"b"}""")),
                        client(owner, frame(2, "thread_catch_up", a)),
                        daemon(owner, clear),
                    )
                if (condition != "missing-pair") rows += daemon(owner, response)
                rows += daemon(owner, frame(6, "thread_items", """{"conversation_id":"a","final":true}""", reply = 2))
                rows += client(owner, frame(7, "thread_catch_up", b))
                rows += daemon(owner, frame(8, "thread_items", """{"conversation_id":"b","final":true}""", reply = 7))
                if (condition == "foreign-owner") {
                    assertThrows(IllegalArgumentException::class.java) { RecordedSuiteTape(rows, literalProducerIdentities = true) }
                    continue
                }
                val tape = RecordedSuiteTape(rows, literalProducerIdentities = true)
                assertTrue(tape.initial().isEmpty())
                if (condition != "unrequested") tape.request(metadataOwner, frame(101, requestType, """{"conversation_id":"b"}"""))
                val independent = tape.request(owner, frame(90, "thread_catch_up", b))
                if (condition == "accepted") {
                    assertEquals(listOf(90L), independent.map { it.message!!.inReplyTo })
                    // Metadata itself was not delivered early and every original frame remains required.
                    val remaining = tape.request(owner, frame(91, "thread_catch_up", a))
                    assertEquals(listOf(responseType, responseType, "thread_items"), remaining.map { it.message!!.type })
                    assertEquals(listOf(null, 101L, 91L), remaining.map { it.message!!.inReplyTo })
                    tape.assertComplete()
                } else {
                    assertTrue("$requestType/$condition cannot establish an independent metadata pair", independent.isEmpty())
                }
            }
        }
    }

    @Test fun alreadyQualifiedOptionalCatalogCannotFenceAnIndependentCanonicalReply() {
        val owner = "phone/host/0"
        val a = """{"conversation_id":"a","epoch":"ea","version":4}"""
        val b = """{"conversation_id":"b","epoch":"eb","version":0}"""
        val automatic = frame(1, "model_list", """{"conversation_id":"b","models":[]}""")
        for (sameCatalog in listOf(true, false)) {
            val replyPayload =
                if (sameCatalog) {
                    automatic.payload
                } else {
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"b","models":["changed"]}""",
                    )
                }
            val tape =
                RecordedSuiteTape(
                    listOf(
                        daemon(owner, automatic),
                        client(owner, frame(2, "request_model_list", """{"conversation_id":"b"}""")),
                        client(owner, frame(3, "thread_catch_up", a)),
                        daemon(owner, frame(4, "model_list").copy(payload = replyPayload, inReplyTo = 2)),
                        daemon(owner, frame(5, "thread_items", """{"conversation_id":"a","final":true}""", reply = 3)),
                        client(owner, frame(6, "thread_catch_up", b)),
                        daemon(owner, frame(7, "thread_items", """{"conversation_id":"b","final":true}""", reply = 6)),
                    ),
                    literalProducerIdentities = true,
                    redundantModelQueries = true,
                )
            assertEquals(listOf("model_list"), tape.initial().map { it.message!!.type })
            val independent = tape.request(owner, frame(90, "thread_catch_up", b))
            if (sameCatalog) {
                assertEquals(listOf(90L), independent.map { it.message!!.inReplyTo })
                assertEquals(listOf(91L), tape.request(owner, frame(91, "thread_catch_up", a)).map { it.message!!.inReplyTo })
                tape.assertComplete()
            } else {
                assertTrue("changed catalogs remain required", independent.isEmpty())
                val nowRequested = tape.request(owner, frame(92, "request_model_list", """{"conversation_id":"b"}"""))
                assertEquals(listOf(90L), nowRequested.map { it.message!!.inReplyTo })
                val remaining = tape.request(owner, frame(91, "thread_catch_up", a))
                assertEquals(listOf("model_list", "thread_items"), remaining.map { it.message!!.type })
                assertEquals(listOf(92L, 91L), remaining.map { it.message!!.inReplyTo })
                tape.assertComplete()
            }
        }
    }

    @Test fun independentQueryReplyCannotOvertakeItsOwnHeldLiveFactOrUnknownAttribution() {
        val owner = "phone/host/0"
        val a = """{"conversation_id":"a","epoch":"ea","version":4}"""
        val b = """{"conversation_id":"b","epoch":"eb","version":14}"""
        for (fact in listOf("""{"conversation_id":"b"}""", "{}")) {
            val tape =
                RecordedSuiteTape(
                    listOf(
                        client(owner, frame(1, "thread_catch_up", a)),
                        daemon(owner, frame(2, "thread_items", """{"conversation_id":"a","final":true}""", reply = 1)),
                        daemon(owner, frame(3, "thread_item_changed", fact)),
                        client(owner, frame(4, "thread_catch_up", b)),
                        daemon(owner, frame(5, "thread_items", """{"conversation_id":"b","final":true}""", reply = 4)),
                    ),
                    literalProducerIdentities = true,
                )
            assertTrue(tape.initial().isEmpty())
            assertTrue(tape.request(owner, frame(90, "thread_catch_up", b)).isEmpty())
            val released = tape.request(owner, frame(91, "thread_catch_up", a))
            assertEquals(listOf("thread_items", "thread_item_changed", "thread_items"), released.map { it.message!!.type })
            assertEquals(listOf(91L, null, 90L), released.map { it.message!!.inReplyTo })
            tape.assertComplete()
        }
    }

    @Test fun stalledQueryDiagnosticsExposeOnlyStructureAndFieldEquality() {
        val owner = "phone/host/0"
        val payload = """{"conversation_id":"private-conversation","epoch":"private-epoch","version":12}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, frame(1, "thread_catch_up", payload)),
                    daemon(owner, frame(2, "thread_items", """{"final":true}""", reply = 1)),
                ),
                literalProducerIdentities = true,
            )
        tape.initial()
        val initial = tape.progress()
        assertTrue(initial.contains("query-pending=[index=0,type=thread_catch_up,request=1,checkpoint=12,consumed=false,mapped=false]"))
        assertThrows(IllegalStateException::class.java) {
            tape.request(owner, frame(90, "thread_catch_up", payload.replace(":12", ":13")))
        }
        val stalled = tape.progress()
        assertTrue(stalled.contains("checkpoint=13"))
        assertTrue(stalled.contains("conversationMatches=true"))
        assertTrue(stalled.contains("epochMatches=true"))
        assertTrue(stalled.contains("checkpointMatches=false"))
        assertFalse(stalled.contains("private-conversation"))
        assertFalse(stalled.contains("private-epoch"))
    }

    @Test fun threadQueryStartKeepsInterleavedLiveFramesBehindTheActualRequest() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, frame(1, "thread_catch_up", request)),
                    daemon(owner, frame(2, "thread_item_changed")),
                    daemon(owner, frame(3, "thread_items", """{"final":true}""", reply = 1)),
                    client(owner, frame(4, "thread_catch_up", request)),
                    daemon(owner, frame(5, "thread_items", """{"final":true}""", reply = 4)),
                    daemon("peer/host/0", frame(1, "model_list")),
                ),
                literalProducerIdentities = true,
            )
        assertEquals(listOf("peer/host/0"), tape.initial().map { it.owner })
        val first = tape.request(owner, frame(90, "thread_catch_up", request))
        assertEquals(listOf("thread_item_changed", "thread_items"), first.map { it.message!!.type })
        assertEquals(listOf(null, 90L), first.map { it.message!!.inReplyTo })
        val second = tape.request(owner, frame(91, "thread_catch_up", request))
        assertEquals(listOf(91L), second.map { it.message!!.inReplyTo })
        tape.assertComplete()
    }

    @Test fun threadQueryMayRaceObservationButItsReplyMustWait() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        val checkpoint = """{"body":"phone-send"}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", checkpoint)),
                    client(owner, frame(1, "thread_catch_up", request)),
                    daemon(owner, frame(2, "thread_items", """{"final":true}""", reply = 1)),
                    client(owner, frame(3, "send_message", """{"text":"hello"}""")),
                ),
                literalProducerIdentities = true,
            )
        assertEquals(emptyList<SuiteStep>(), tape.initial())
        assertEquals(emptyList<SuiteStep>(), tape.request(owner, frame(90, "thread_catch_up", request)))
        assertEquals(true, tape.checkpointReady("phone-send"))
        val replies = tape.request("control", frame(1, "test-control:checkpoint", checkpoint))
        assertEquals(listOf("thread_items"), replies.map { it.message!!.type })
        assertEquals(listOf(90L), replies.map { it.message!!.inReplyTo })
        tape.request(owner, frame(91, "send_message", """{"text":"hello"}"""))
        tape.assertComplete()
    }

    @Test fun earlyThreadQueryCannotCrossTheNextUserAction() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")),
                    client(owner, frame(1, "send_message", """{"text":"hello"}""")),
                    client(owner, frame(2, "thread_catch_up", request)),
                    daemon(owner, frame(3, "thread_items", """{"final":true}""", reply = 2)),
                ),
                literalProducerIdentities = true,
            )
        tape.initial()
        assertThrows(IllegalStateException::class.java) { tape.request(owner, frame(90, "thread_catch_up", request)) }
    }

    @Test fun earlyThreadQueryCannotCrossAndroidInputOrConnectionClosure() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        for (boundary in listOf(
            SuiteStep("external", "android", detail = MobileJson.parseToJsonElement("""{"event":"push-wake"}""").jsonObject),
            SuiteStep("close", owner),
        )) {
            val tape =
                RecordedSuiteTape(
                    listOf(
                        client("control", frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")),
                        boundary,
                        client(owner, frame(1, "thread_catch_up", request)),
                        daemon(owner, frame(2, "thread_items", """{"final":true}""", reply = 1)),
                    ),
                    literalProducerIdentities = true,
                )
            tape.initial()
            assertThrows(IllegalStateException::class.java) { tape.request(owner, frame(90, "thread_catch_up", request)) }
        }
    }

    @Test fun earlyThreadQueryRefusalCannotCrossAnObservation() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")),
                    client(owner, frame(1, "thread_catch_up", request)).copy(accepted = false),
                ),
                literalProducerIdentities = true,
            )
        tape.initial()
        assertThrows(IllegalStateException::class.java) { tape.request(owner, frame(90, "thread_catch_up", request)) }
    }

    @Test fun earlyThreadQueryCannotOvertakeItsOwnIncomingFacts() {
        val owner = "phone/host/0"
        val request = """{"conversation_id":"chat","epoch":"epoch","version":0}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")),
                    daemon(owner, frame(1, "thread_item_changed")),
                    client(owner, frame(2, "thread_catch_up", request)),
                    daemon(owner, frame(3, "thread_items", """{"final":true}""", reply = 2)),
                ),
                literalProducerIdentities = true,
            )
        tape.initial()
        assertThrows(IllegalStateException::class.java) { tape.request(owner, frame(90, "thread_catch_up", request)) }
    }

    @Test fun androidInputsCannotArriveBeforeTheirRecordedConnectionRestartAndRequests() {
        for (event in listOf("push-token", "push-wake")) {
            val old = "phone/host/0"
            val next = "phone/host/1"
            val external = SuiteStep("external", "android", detail = MobileJson.parseToJsonElement("""{"event":"$event"}""").jsonObject)
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("connect", old),
                        SuiteStep("open", old),
                        client(old, frame(1, "request_model_list")),
                        daemon(old, frame(2, "model_list", reply = 1)),
                        SuiteStep("close", old),
                        SuiteStep("inbound_end", old),
                        SuiteStep("connect", next),
                        SuiteStep("open", next),
                        client(next, frame(1, "list_conversations")),
                        external,
                        daemon(next, frame(2, "conversations", reply = 1)),
                    ),
                )
            assertEquals(emptyList<SuiteStep>(), tape.initial())
            assertEquals(listOf("open"), tape.lifecycle("connect", old).map { it.direction })
            assertEquals(listOf("daemon"), tape.request(old, frame(90, "request_model_list")).map { it.direction })
            assertEquals(listOf("inbound_end"), tape.lifecycle("close", old).map { it.direction })
            assertEquals(listOf("open"), tape.lifecycle("connect", next).map { it.direction })
            assertEquals(listOf("external", "daemon"), tape.request(next, frame(90, "list_conversations")).map { it.direction })
            tape.assertComplete()
        }
    }

    @Test fun anAndroidInputWaitsForRequiredWorkWhileIndependentFramesCanProgress() {
        val external = SuiteStep("external", "android", detail = MobileJson.parseToJsonElement("""{"event":"push-token"}""").jsonObject)
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone", frame(1, "request_history")),
                    daemon("phone", frame(2, "history_page", reply = 1)),
                    daemon("peer", frame(1, "model_list")),
                    external,
                ),
            )
        assertEquals(listOf("peer"), tape.initial().map { it.owner })
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        assertEquals(listOf("daemon", "external"), tape.request("phone", frame(90, "request_history")).map { it.direction })
        tape.assertComplete()
    }

    @Test fun aConnectionCannotEndBeforeItsEarlierDownloadRequestAndChunks() {
        val owner = "peer/host/0"
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, frame(1, "request_attachment", """{"attachment_id":"file"}""")),
                    daemon(owner, frame(2, "attachment_chunk", """{"data":"YWJj"}""", reply = 1)),
                    SuiteStep("inbound_end", owner),
                    SuiteStep("close", owner),
                ),
                literalProducerIdentities = true,
            )
        assertEquals(emptyList<SuiteStep>(), tape.initial())
        val delivered = tape.request(owner, frame(90, "request_attachment", """{"attachment_id":"file"}"""))
        assertEquals(listOf("daemon", "inbound_end"), delivered.map { it.direction })
        assertEquals(90L, delivered.first().message!!.inReplyTo)
        tape.lifecycle("close", owner)
        tape.assertComplete()
    }

    @Test fun closingOneConnectionCannotCancelItsRequiredReadOrBlockAnotherConnection() {
        val rows =
            listOf(
                client("phone", frame(1, "request_model_list")),
                SuiteStep("inbound_end", "peer"),
                SuiteStep("close", "peer"),
                SuiteStep("inbound_end", "phone"),
                SuiteStep("close", "phone"),
            )
        val tape = RecordedSuiteTape(rows)
        assertEquals(listOf("peer"), tape.initial().map { it.owner })
        tape.lifecycle("close", "peer")
        assertThrows(IllegalStateException::class.java) { tape.lifecycle("close", "phone") }
    }

    @Test fun aFileDownloadIsAReadAndDoesNotTurnARacingCatalogLookupIntoAUserAction() {
        val owner = "phone/host/0"
        val payload = """{"conversation_id":"chat"}"""
        val rows =
            listOf(
                daemon(owner, frame(1, "model_list", """{"conversation_id":"chat","models":[]}""")),
                client(owner, frame(1, "request_attachment", """{"attachment_id":"file"}""")),
                daemon(owner, frame(2, "attachment_chunk", """{"data":"YWJj"}""", reply = 1)),
                client(owner, frame(3, "send_message", """{"text":"next actual user action"}""")),
            )
        val tape = RecordedSuiteTape(rows, literalProducerIdentities = true, redundantModelQueries = true)
        tape.initial()
        assertEquals(true, tape.send(owner, frame(90, "request_model_list", payload)).accepted)
        val downloaded = tape.request(owner, frame(91, "request_attachment", """{"attachment_id":"file"}"""))
        assertEquals(
            "YWJj",
            downloaded.single().message!!.payload.jsonObject.getValue("data").let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            },
        )
        assertEquals(91L, downloaded.single().message!!.inReplyTo)
        tape.request(owner, frame(92, "send_message", """{"text":"next actual user action"}"""))
        tape.assertComplete()
        val changed = RecordedSuiteTape(rows, literalProducerIdentities = true, redundantModelQueries = true)
        changed.initial()
        assertThrows(IllegalStateException::class.java) {
            changed.request(owner, frame(99, "request_attachment", """{"attachment_id":"another-file"}"""))
        }
    }

    @Test fun anObservationCheckpointHoldsFutureQueriesAndRepliesUntilTheTestReachesIt() {
        val checkpoint = client("control", frame(1, "test-control:checkpoint", """{"body":"reply-visible"}"""))
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone", frame(1, "request_model_list")),
                    daemon("phone", frame(2, "model_list", reply = 1)),
                    checkpoint,
                    client("phone", frame(3, "request_context_usage")),
                    daemon("phone", frame(4, "context_usage", reply = 3)),
                ),
            )
        assertEquals(false, tape.checkpointReady("reply-visible"))
        assertEquals(listOf("model_list"), tape.request("phone", frame(90, "request_model_list")).map { it.message!!.type })
        assertEquals(true, tape.checkpointReady("reply-visible"))
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        assertEquals(emptyList<SuiteStep>(), tape.request("control", checkpoint.message!!.copy(id = 80)))
        assertEquals(listOf("context_usage"), tape.request("phone", frame(91, "request_context_usage")).map { it.message!!.type })
        tape.assertComplete()
    }

    @Test fun aQueryCannotBorrowAnAnswerAcrossATestCheckpoint() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:checkpoint", """{"body":"permission-observed"}""")),
                    client("phone", frame(1, "request_model_list")),
                    daemon("phone", frame(2, "model_list", reply = 1)),
                ),
            )
        assertThrows(IllegalStateException::class.java) { tape.request("phone", frame(20, "request_model_list")) }
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
    }

    @Test fun aChangedOrOmittedTestCheckpointCannotCompleteReplay() {
        for (name in listOf("wrong-step", "")) {
            val tape = RecordedSuiteTape(listOf(client("control", frame(1, "test-control:checkpoint", """{"body":"reply-visible"}"""))))
            assertThrows(IllegalStateException::class.java) { tape.checkpointReady(name) }
            assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        }
    }

    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-16T06:00:00Z", MobileJson.parseToJsonElement(payload), reply)

    private fun client(
        owner: String,
        frame: Envelope,
    ) = SuiteStep("client", owner, frame)

    private fun daemon(
        owner: String,
        frame: Envelope,
    ) = SuiteStep("daemon", owner, frame)

    @Test fun aPeerActionDeliversPhoneEventsWithoutBorrowingFutureActions() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("peer", frame(1, "send_message", """{"text":"controlled"}""")),
                    daemon("phone", frame(2, "message", """{"text":"controlled"}""")),
                    daemon("peer", frame(3, "ack", reply = 1)),
                    client("phone", frame(1, "modal_answer", """{"option_id":"allow_once"}""")),
                    daemon("phone", frame(4, "turn_end")),
                ),
            )
        val first = tape.request("peer", frame(90, "send_message", """{"text":"controlled"}"""))
        assertEquals(listOf("phone", "peer"), first.map { it.owner })
        assertEquals(90L, first.last().message!!.inReplyTo)
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val last = tape.request("phone", frame(91, "modal_answer", """{"option_id":"allow_once"}"""))
        assertEquals(listOf("turn_end"), last.map { it.message!!.type })
        tape.assertComplete()
    }

    @Test fun correlationCountersAreIndependentAcrossConnections() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone/a/0", frame(1, "send_message")),
                    daemon("phone/a/0", frame(10, "ack", reply = 1)),
                    client("phone/b/0", frame(1, "send_message")),
                    daemon("phone/b/0", frame(10, "ack", reply = 1)),
                ),
            )
        assertEquals(
            20L,
            tape
                .request("phone/a/0", frame(20, "send_message"))
                .single()
                .message!!
                .inReplyTo,
        )
        assertEquals(
            30L,
            tape
                .request("phone/b/0", frame(30, "send_message"))
                .single()
                .message!!
                .inReplyTo,
        )
        tape.assertComplete()
    }

    @Test fun aCommandCannotMoveToAnotherHostOrPastAnotherCommand() {
        for (wrong in listOf("phone/b/0", "control")) {
            val tape = RecordedSuiteTape(listOf(client("phone/a/0", frame(1, "send_message"))))
            assertThrows(IllegalStateException::class.java) { tape.request(wrong, frame(2, "send_message")) }
        }
        val tape = RecordedSuiteTape(listOf(client("control", frame(1, "test-control:stop")), client("phone", frame(1, "send_message"))))
        assertThrows(IllegalStateException::class.java) { tape.request("phone", frame(2, "send_message")) }
    }

    @Test fun readQueriesCanPermuteWithinAnActionAcrossOwners() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone", frame(1, "request_model_list")),
                    daemon("phone", frame(2, "model_list", reply = 1)),
                    client("peer", frame(1, "list_conversations")),
                    daemon("peer", frame(2, "conversations", reply = 1)),
                    client("phone", frame(3, "send_message")),
                ),
            )
        assertEquals(listOf("peer"), tape.request("peer", frame(20, "list_conversations")).map { it.owner })
        assertEquals(listOf("phone"), tape.request("phone", frame(30, "request_model_list")).map { it.owner })
        tape.request("phone", frame(40, "send_message"))
        tape.assertComplete()
    }

    @Test fun changedPayloadAndLateRequestsFailPersistentlyWithoutPayloadDiagnostics() {
        val tape = RecordedSuiteTape(listOf(client("phone", frame(1, "send_message", """{"text":"controlled"}"""))))
        val failure =
            assertThrows(IllegalStateException::class.java) {
                tape.request("phone", frame(2, "send_message", """{"text":"private changed value"}"""))
            }
        assertEquals("Recorded suite request mismatch at step 1: phone:send_message, expected phone:send_message", failure.message)
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val complete = RecordedSuiteTape(listOf(client("phone", frame(1, "list_conversations"))))
        complete.request("phone", frame(20, "list_conversations"))
        complete.assertComplete()
        assertThrows(IllegalStateException::class.java) { complete.request("phone", frame(21, "list_conversations")) }
        assertThrows(IllegalStateException::class.java) { complete.assertComplete() }
    }

    @Test fun aFailedRecordedSendIsNotReportedAsAccepted() {
        val step = client("phone", frame(1, "send_message")).copy(accepted = false)
        val tape = RecordedSuiteTape(listOf(step))
        assertEquals(false, tape.send("phone", frame(20, "send_message")).accepted)
        tape.assertComplete()
    }

    @Test fun lifecycleBoundariesMustBeConsumed() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("connect", "phone"),
                    SuiteStep("open", "phone", capabilities = setOf("interactive")),
                    client("phone", frame(1, "send_message")),
                    SuiteStep("down", "phone"),
                    SuiteStep("close", "phone"),
                    SuiteStep("connect", "phone/1"),
                    SuiteStep("open", "phone/1"),
                ),
            )
        assertEquals("open", tape.lifecycle("connect", "phone").single().direction)
        assertEquals("down", tape.request("phone", frame(2, "send_message")).single().direction)
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        tape.lifecycle("close", "phone")
        tape.lifecycle("connect", "phone/1")
        tape.assertComplete()
    }

    @Test fun sharedConversationIdentityMustAgreeBetweenPhoneAndPeer() {
        val expectedMessage = """{"conversation_id":"old-chat","message_id":"old-message","text":"controlled"}"""
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone", frame(1, "create_conversation")),
                    daemon("phone", frame(2, "conversation_created", """{"id":"old-chat"}""", 1)),
                    daemon("peer", frame(2, "conversation_created", """{"id":"old-chat"}""")),
                    client("peer", frame(3, "send_message", expectedMessage)),
                ),
            )
        val replies = tape.request("phone", frame(20, "create_conversation"))
        val chat = (replies.first().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("id")
        assertEquals(chat, (replies.last().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("id"))
        val actualMessage = """{"conversation_id":$chat,"message_id":"fresh-message","text":"controlled"}"""
        tape.request("peer", frame(30, "send_message", actualMessage))
        tape.assertComplete()
    }

    @Test fun connectionSetupAndBackgroundQueriesMustAllBeConsumed() {
        val register =
            frame(1, "register_push_token", """{"platform":"fcm","token":"recorded-push-device-address","device_name":"phone"}""")
        val rows =
            listOf(
                SuiteStep("connect", "phone"),
                SuiteStep("open", "phone"),
                client("phone", register),
                client("phone", frame(2, "list_conversations")),
                SuiteStep("connect", "peer"),
                SuiteStep("open", "peer"),
                client("peer", frame(1, "list_conversations")),
                client("phone", frame(3, "create_conversation")),
                SuiteStep("close", "phone"),
            )
        val tape = RecordedSuiteTape(rows)
        tape.lifecycle("connect", "phone")
        assertEquals(listOf("peer"), tape.lifecycle("connect", "peer").map { it.owner })
        tape.send("phone", frame(80, "list_conversations"))
        tape.send("peer", frame(80, "list_conversations"))
        tape.send("phone", register.copy(id = 81))
        tape.send("phone", frame(82, "create_conversation"))
        tape.lifecycle("close", "phone")
        tape.assertComplete()
        val early = RecordedSuiteTape(rows)
        early.lifecycle("connect", "phone")
        early.send("phone", frame(90, "create_conversation"))
        assertThrows(IllegalStateException::class.java) { early.assertComplete() }
    }

    @Test fun redundantModelLookupRequiresAnObservedCatalogAndCannotRepeatOrCrossAnAction() {
        val owner = "phone/host-0/0"
        val catalog = frame(1, "model_list", """{"conversation_id":"chat","models":[],"dropped_models":0}""")
        val query = frame(1, "request_history", """{"conversation_id":"chat"}""")
        val extra = frame(90, "request_model_list", """{"conversation_id":"chat"}""")

        fun tape(entries: List<SuiteStep>) = RecordedSuiteTape(entries, redundantModelQueries = true)
        val entries = listOf(SuiteStep("daemon", owner, catalog), SuiteStep("client", owner, query))
        val replay = tape(entries)
        val mapped =
            replay
                .initial()
                .single()
                .message!!
                .payload.jsonObject
                .getValue("conversation_id")
        val payload = kotlinx.serialization.json.buildJsonObject { put("conversation_id", mapped) }
        assertEquals(true, replay.send(owner, extra.copy(payload = payload)).accepted)
        assertThrows(IllegalStateException::class.java) { replay.send(owner, extra.copy(id = 91, payload = payload)) }
        val wrong = tape(entries)
        wrong.initial()
        assertThrows(IllegalStateException::class.java) { wrong.send(owner, extra) }
        val peer = tape(entries)
        peer.initial()
        assertThrows(IllegalStateException::class.java) { peer.send("peer/host-0/0", extra.copy(payload = payload)) }
        val late = tape(listOf(SuiteStep("daemon", owner, catalog), SuiteStep("client", owner, frame(1, "send_message"))))
        late.initial()
        assertThrows(IllegalStateException::class.java) { late.send(owner, extra.copy(payload = payload)) }
        val missing = tape(listOf(SuiteStep("client", owner, query)))
        assertThrows(IllegalStateException::class.java) { missing.send(owner, extra) }
    }

    @Test fun backgroundQueriesRemainRequiredButCannotBlockTheNextUserAction() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("phone", frame(1, "request_history")),
                    daemon("phone", frame(2, "history_page", reply = 1)),
                    client("phone", frame(3, "modal_answer")),
                    daemon("phone", frame(4, "modal_dismissed", reply = 3)),
                    client("phone", frame(5, "send_message")),
                    daemon("phone", frame(6, "turn_end")),
                ),
            )
        val action = tape.request("phone", frame(90, "modal_answer"))
        assertEquals(listOf("modal_dismissed"), action.map { it.message!!.type })
        assertEquals(90L, action.single().message!!.inReplyTo)
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val history = tape.request("phone", frame(91, "request_history"))
        assertEquals(listOf("history_page"), history.map { it.message!!.type })
        assertEquals(91L, history.single().message!!.inReplyTo)
        assertEquals(listOf("turn_end"), tape.request("phone", frame(92, "send_message")).map { it.message!!.type })
        tape.assertComplete()
    }

    @Test fun independentConnectionCloseCannotBlockAnotherClientsReconnect() {
        val rows =
            listOf(
                SuiteStep("connect", "peer/host/0"),
                SuiteStep("open", "peer/host/0"),
                SuiteStep("close", "peer/host/0"),
                SuiteStep("connect", "phone/host/0"),
                SuiteStep("open", "phone/host/0"),
                SuiteStep("close", "phone/host/0"),
            )
        val tape = RecordedSuiteTape(rows)
        tape.lifecycle("connect", "peer/host/0")
        assertEquals(listOf("phone/host/0"), tape.lifecycle("connect", "phone/host/0").map { it.owner })
        tape.lifecycle("close", "peer/host/0")
        tape.lifecycle("close", "phone/host/0")
        tape.assertComplete()
        val same =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("connect", "phone/host/0"),
                    SuiteStep("open", "phone/host/0"),
                    SuiteStep("close", "phone/host/0"),
                    SuiteStep("connect", "phone/host/1"),
                    SuiteStep("open", "phone/host/1"),
                ),
            )
        same.lifecycle("connect", "phone/host/0")
        assertThrows(IllegalStateException::class.java) { same.lifecycle("connect", "phone/host/1") }
    }

    @Test fun anEarlyBackgroundQueryCannotReceiveAnAnswerFromAFutureUserAction() {
        val tape =
            RecordedSuiteTape(
                listOf(
                    client("control", frame(1, "test-control:stop")),
                    client("phone", frame(1, "request_history")),
                    daemon("phone", frame(2, "history_page", reply = 1)),
                ),
            )
        assertEquals(emptyList<SuiteStep>(), tape.request("phone", frame(90, "request_history")))
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val released = tape.request("control", frame(2, "test-control:stop"))
        assertEquals(listOf("history_page"), released.map { it.message!!.type })
        assertEquals(90L, released.single().message!!.inReplyTo)
        tape.assertComplete()
    }

    @Test fun anIdenticalAutomaticCatalogMakesOneRacingModelQueryOptional() {
        val owner = "phone/host-0/1"
        val query = frame(1, "request_model_list", """{"conversation_id":"chat"}""")
        val catalog = frame(2, "model_list", """{"conversation_id":"chat","models":[]}""")
        val rows = listOf(client(owner, query), daemon(owner, catalog), daemon(owner, catalog.copy(id = 3, inReplyTo = 1)))
        val omitted = RecordedSuiteTape(rows, redundantModelQueries = true)
        assertEquals(listOf("model_list"), omitted.initial().map { it.message!!.type })
        omitted.assertComplete()
        val actual = query.copy(id = 90, payload = omitted.arguments(query.payload.jsonObject))
        assertEquals(
            90L,
            omitted
                .request(owner, actual)
                .single()
                .message!!
                .inReplyTo,
        )
        omitted.assertComplete()
        assertThrows(IllegalStateException::class.java) { omitted.request(owner, actual.copy(id = 91)) }
        val required =
            RecordedSuiteTape(
                rows.drop(1).dropLast(1) + rows.first() +
                    rows.last().copy(
                        message =
                            catalog.copy(
                                id = 3,
                                inReplyTo = 1,
                                payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","models":["different"]}"""),
                            ),
                    ),
                redundantModelQueries = true,
            )
        required.initial()
        assertThrows(IllegalStateException::class.java) { required.assertComplete() }
        val future =
            RecordedSuiteTape(listOf(rows.first(), client(owner, frame(4, "send_message")), rows[1], rows[2]), redundantModelQueries = true)
        future.initial()
        future.request(owner, frame(90, "send_message"))
        assertThrows(IllegalStateException::class.java) { future.assertComplete() }
    }

    @Test fun polledSettingsCannotBorrowAFutureWriteOrDuplicateWireIds() {
        val owner = "phone/host/0"
        val query = frame(1, "request_session_settings", """{"conversation_id":"chat"}""")
        val before = frame(2, "session_settings", """{"permission_mode":"default"}""", 1)
        val after = frame(5, "session_settings", """{"permission_mode":"plan"}""", 4)
        val tape =
            RecordedSuiteTape(
                listOf(
                    client(owner, query),
                    daemon(owner, before),
                    client(owner, frame(3, "set_permission_mode")),
                    client(owner, query.copy(id = 4)),
                    daemon(owner, after),
                ),
                recordedSettingsReads = true,
            )
        val first = tape.initial().single()
        assertEquals("settings_snapshot", first.direction)
        tape.deliveredSnapshot(first)
        val request = query.copy(id = 80, payload = tape.arguments(query.payload.jsonObject))
        assertEquals(
            before.payload,
            tape
                .request(owner, request)
                .single()
                .message!!
                .payload,
        )
        val next = tape.request(owner, frame(81, "set_permission_mode")).single()
        assertEquals(
            before.payload,
            tape
                .request(owner, request.copy(id = 82))
                .single()
                .message!!
                .payload,
        )
        tape.deliveredSnapshot(next)
        assertEquals(
            after.payload,
            tape
                .request(owner, request.copy(id = 83))
                .single()
                .message!!
                .payload,
        )
        tape.assertComplete()
        assertThrows(IllegalStateException::class.java) { tape.request(owner, request.copy(id = 83)) }
    }
}
