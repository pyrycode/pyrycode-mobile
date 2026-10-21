package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SuiteReadReceiptsTest {
    private val previousLogSink = RelayLog.sink

    @Before fun muteThreadStoreLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreThreadStoreLogs() {
        RelayLog.sink = previousLogSink
    }

    private fun authoritativeTape(
        sourceOwner: String = "phone/host0/0",
        readerOwner: String = sourceOwner,
        finalVersion: Long = 15,
        legacyEvidence: Boolean = false,
    ): RecordedSuiteTape {
        val item = """{
            "id":11,"kind":"assistant_message","rev":13,"order":11,"status":"done",
            "active":false,"shown":true,"summary":"reply","content":{"text":"reply"}
        }"""
        return RecordedSuiteTape(
            listOf(
                SuiteStep("open", sourceOwner, capabilities = setOf("interactive", "thread")),
                *if (readerOwner ==
                    sourceOwner
                ) {
                    emptyArray()
                } else {
                    arrayOf(SuiteStep("open", readerOwner, capabilities = setOf("interactive", "thread")))
                },
                SuiteStep(
                    "daemon",
                    sourceOwner,
                    frame(1, "conversation_created", """{"id":"chat","last_shown_version":11,"read_up_to":0}"""),
                ),
                *if (!legacyEvidence) {
                    emptyArray()
                } else {
                    arrayOf(
                        SuiteStep(
                            "daemon",
                            sourceOwner,
                            frame(5, "assistant_delta", """{"conversation_id":"chat","text":"legacy reply"}""").copy(historyEntryId = 11u),
                        ),
                    )
                },
                SuiteStep("client", sourceOwner, frame(1, "thread_catch_up", """{"conversation_id":"chat","epoch":"","version":0}""")),
                SuiteStep(
                    "daemon",
                    sourceOwner,
                    frame(2, "thread_items", """{"conversation_id":"chat","epoch":"e","version":15,"items":[$item],"final":false}""", 1),
                ),
                SuiteStep(
                    "daemon",
                    sourceOwner,
                    frame(
                        3,
                        "thread_items",
                        """{"conversation_id":"chat","epoch":"e","version":$finalVersion,"items":[],"final":true,"lower_order":11,"upper_order":16,"continuation":11,"older_exists":false}""",
                        1,
                    ),
                ),
                SuiteStep("client", readerOwner, frame(2, "mark_conversation_read", """{"conversation_id":"chat","up_to":11}""")),
                SuiteStep(
                    "daemon",
                    readerOwner,
                    frame(4, "conversation_updated", """{"id":"chat","last_shown_version":11,"read_up_to":11}""", 2),
                ),
            ),
            coalescedReadReceipts = true,
            retainedPhoneHistory = true,
            literalProducerIdentities = true,
        )
    }

    private fun deliverInitial(tape: RecordedSuiteTape) {
        tape.initial().filter { it.message != null }.forEach { tape.delivered(it.owner, it.message!!) }
    }

    private fun authoritativeReplies(
        tape: RecordedSuiteTape,
        owner: String = "phone/host0/0",
    ) = tape.request(owner, frame(90, "thread_catch_up", """{"conversation_id":"chat","epoch":"","version":0}"""))

    private fun authoritativeRead(
        tape: RecordedSuiteTape,
        owner: String = "phone/host0/0",
        target: Long = 11,
    ) = tape.send(owner, frame(91, "mark_conversation_read", """{"conversation_id":"chat","up_to":$target}"""))

    @Test fun aCertifiedAuthoritativeWindowSuppliesOnlyItsObservedShownWatermark() {
        for (reader in listOf("phone/host0/0", "phone/host0/1")) {
            val tape = authoritativeTape(readerOwner = reader)
            deliverInitial(tape)
            authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
            val acknowledgement = authoritativeRead(tape, reader).deliveries.single().message!!
            assertEquals(
                MobileJson.parseToJsonElement("11"),
                (acknowledgement.payload as kotlinx.serialization.json.JsonObject)["read_up_to"],
            )
            tape.assertComplete()
        }
    }

    @Test fun aNegotiatedThreadCannotUseLegacyDeliveryAsReadEvidence() {
        val tape = authoritativeTape(legacyEvidence = true)
        deliverInitial(tape)
        assertThrows(IllegalStateException::class.java) { authoritativeRead(tape) }
    }

    @Test fun aReadQualifiedByDeliveredCertifiedContentSurvivesANewerLiveRevision() {
        val tape = authoritativeTape()
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        // The phone can qualify sight on this certified window before a newer live fact arrives.
        // Its already qualified read may reach the pump after that fact, before the next catch-up.
        tape.delivered(
            "phone/host0/0",
            frame(
                6,
                "thread_item_changed",
                """{"conversation_id":"chat","epoch":"e","version":16,"item_id":11,
                    "base_rev":13,"rev":16,"changes":{"content":{"text":"new reply"}}}""",
            ),
        )
        val acknowledgement = authoritativeRead(tape).deliveries.single().message!!
        assertEquals(91L, acknowledgement.inReplyTo)
        tape.assertComplete()
    }

    @Test fun theRecordedPermissionAnswerCannotEraseTheEarlierCertifiedReadWindow() {
        val window = PermissionReadWindow()
        assertEquals(13uL, window.target)
        assertTrue("The captured pre-answer window certifies the phone's read", window.permits())
        val notice = window.following.first { it.owner == window.owner && it.message?.type == "thread_item_added" }
        window.receipts.delivered(window.owner, notice.message!!)
        assertTrue("The captured answer notice cannot revoke an already qualified read", window.permits())
    }

    @Test fun aNewWatermarkStillNeedsItsOwnDeliveredCertification() {
        val window = PermissionReadWindow()
        val metadata = window.following.indexOfFirst { it.owner == window.owner && it.message?.type == "conversations" }
        window.following.take(metadata + 1).forEach(window::accept)
        assertTrue(window.permits())
        assertFalse(window.receipts.permits(window.owner, window.conversation, 14u, false))
        val end = window.following.indexOfFirst { it.owner.startsWith("peer/") && it.message?.type == "modal_answer" }
        window.following.subList(metadata + 1, end).forEach(window::accept)
        assertTrue(
            "Only the subsequent real query terminal certifies the new notice",
            window.receipts.permits(window.owner, window.conversation, 14u, false),
        )
    }

    @Test fun aSameEpochResetNeedsFreshCompleteEvidenceForTheRepeatedWatermark() {
        val window = PermissionReadWindow()
        assertTrue(window.permits())
        window.requery(1001)
        val epoch =
            window.request.message!!
                .payload.jsonObject
                .getValue("epoch")
        window.receipts.delivered(
            window.owner,
            frame(1002, "thread_reset", """{"conversation_id":"${window.conversation}","epoch":$epoch,"version":13}""", 1001),
        )
        assertFalse(window.permits())
        val replies = window.requery(1003)
        window.receipts.delivered(window.owner, replies.first())
        assertFalse("Receiving the batch cannot revive pre-reset certification", window.permits())
        window.receipts.delivered(window.owner, replies.last())
        assertTrue(window.permits())
    }

    @Test fun aQueryRepairCannotRetainOldReadEvidence() {
        val window = PermissionReadWindow()
        assertTrue(window.permits())
        val broken = window.requery(1001).first()
        window.receipts.delivered(
            window.owner,
            broken.copy(payload = JsonObject(broken.payload.jsonObject + ("epoch" to JsonPrimitive("foreign")))),
        )
        assertFalse(window.permits())
        val replies = window.requery(1003)
        replies.forEach { window.receipts.delivered(window.owner, it) }
        assertTrue(window.permits())
    }

    @Test fun rememberedEvidenceCannotCrossConversationHostOrClient() {
        val window = PermissionReadWindow()
        assertTrue(window.permits())
        for ((owner, conversation) in listOf(
            window.owner to "another-conversation",
            "phone/foreign/0" to window.conversation,
            window.owner.replace("phone/", "peer/") to window.conversation,
        )) {
            assertFalse(window.receipts.permits(owner, conversation, window.target, true))
        }
    }

    private class PermissionReadWindow {
        private val recording =
            java.io
                .File("src/androidTest/assets/recorded/suite")
                .resolve("interactiveTurn_permissionPrompts_heldPerConversation.json")
        private val steps = RecordedSuiteDocument.parse(recording.readText()).steps
        private val answer = steps.indexOfFirst { it.owner.startsWith("phone/") && it.message?.type == "modal_answer" }
        val request = steps.take(answer).last { it.direction == "client" && it.message?.type == "thread_catch_up" }
        val owner = request.owner
        private val payload = requireNotNull(request.message).payload.jsonObject
        val conversation = payload.getValue("conversation_id").jsonPrimitive.content
        val target =
            payload
                .getValue("version")
                .jsonPrimitive.content
                .toULong()
        val receipts = SuiteThreadReadReceipts(steps)
        val following = steps.drop(answer + 1)

        init {
            steps.take(answer + 1).forEach(::accept)
        }

        fun permits() = receipts.permits(owner, conversation, target, false)

        fun accept(step: SuiteStep) {
            step.message?.let { frame ->
                when (step.direction) {
                    "client" -> receipts.requested(step.owner, frame)
                    "daemon" -> receipts.delivered(step.owner, frame)
                }
            }
        }

        fun requery(id: Long): List<Envelope> {
            val original =
                steps
                    .take(answer)
                    .last {
                        it.direction == "client" &&
                            it.owner == owner &&
                            it.message?.type == "thread_catch_up" &&
                            it.message.payload.jsonObject["conversation_id"]
                                ?.jsonPrimitive
                                ?.content == conversation &&
                            it.message.payload.jsonObject["version"] == JsonPrimitive(0)
                    }.message!!
            receipts.requested(owner, original.copy(id = id))
            return steps
                .filter { it.direction == "daemon" && it.owner == owner && it.message?.inReplyTo == original.id }
                .map { it.message!!.copy(inReplyTo = id) }
        }
    }

    @Test fun enqueuedOrPartialAuthoritativeRepliesCannotAcknowledgeARead() {
        for (delivered in listOf(0, 1)) {
            val tape = authoritativeTape()
            deliverInitial(tape)
            authoritativeReplies(tape).take(delivered).forEach { tape.delivered(it.owner, it.message!!) }
            assertThrows(IllegalStateException::class.java) { authoritativeRead(tape) }
        }
    }

    @Test fun aMismatchedAuthoritativeTerminalCannotCertifyReceivedItems() {
        val tape = authoritativeTape(finalVersion = 14)
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        assertThrows(IllegalStateException::class.java) { authoritativeRead(tape) }
    }

    @Test fun authoritativeReadsCannotUseAnotherClientsOrHostsWindow() {
        for (source in listOf("peer/host0/0", "phone/host1/0")) {
            val tape = authoritativeTape(sourceOwner = source, readerOwner = "phone/host0/1")
            deliverInitial(tape)
            authoritativeReplies(tape, source).forEach { tape.delivered(it.owner, it.message!!) }
            assertThrows(IllegalStateException::class.java) { authoritativeRead(tape, "phone/host0/1") }
        }
    }

    @Test fun aCertifiedVersionDoesNotInventAnUnobservedShownWatermark() {
        val tape = authoritativeTape()
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        assertThrows(IllegalStateException::class.java) { authoritativeRead(tape, target = 12) }
    }

    private fun frame(
        id: Long,
        type: String,
        payload: String,
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = reply)

    private fun tape(): RecordedSuiteTape {
        val ten = frame(1, "message", """{"conversation_id":"chat"}""").copy(eventId = 10, historyEntryId = 10u)
        val fourteen = frame(2, "message", """{"conversation_id":"chat"}""").copy(eventId = 14, historyEntryId = 14u)
        val readTen = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val readFourteen = frame(2, "mark_conversation_read", """{"conversation_id":"chat","up_to":14}""")
        val ackTen = frame(3, "conversation_updated", """{"id":"chat","read_up_to":10}""", 1)
        val ackFourteen = frame(4, "conversation_updated", """{"id":"chat","read_up_to":11}""", 2)
        val rows =
            listOf(
                SuiteStep("daemon", "phone", ten),
                SuiteStep("daemon", "phone", fourteen),
                SuiteStep("client", "phone", readTen),
                SuiteStep("daemon", "phone", ackTen),
                SuiteStep("client", "phone", readFourteen),
                SuiteStep("daemon", "phone", ackFourteen),
            )
        return RecordedSuiteTape(rows, coalescedReadReceipts = true)
    }

    @Test fun coalescingRequiresTheSameFinalProgressAndNeverAcknowledgesUndeliveredHistory() {
        val tape = tape()
        val delivered = tape.initial()
        delivered.forEach { tape.delivered(it.owner, it.message!!) }
        val chat =
            delivered
                .first()
                .message!!
                .payload
                .toString()
                .let { MobileJson.parseToJsonElement(it) }
        val id = (chat as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        val actual = frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":14}""")
        val response =
            tape
                .send("phone", actual)
                .deliveries
                .single()
                .message!!
        assertEquals(90L, response.inReplyTo)
        assertEquals(MobileJson.parseToJsonElement("11"), (response.payload as kotlinx.serialization.json.JsonObject)["read_up_to"])
        tape.assertComplete()
        assertThrows(IllegalStateException::class.java) { tape.send("phone", actual.copy(id = 91)) }
    }

    @Test fun missingReceiptsAndFutureOrWrongConversationReadsFail() {
        for (payload in listOf(
            """{"conversation_id":"chat","up_to":14}""",
            """{"conversation_id":"other","up_to":10}""",
            """{"conversation_id":"chat","up_to":15}""",
        )) {
            val tape = tape()
            tape.initial() // Enqueued is not delivered.
            assertThrows(IllegalStateException::class.java) { tape.send("phone", frame(99, "mark_conversation_read", payload)) }
        }
        val tape = tape()
        tape.initial().forEach { tape.delivered(it.owner, it.message!!) }
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
    }

    @Test fun anAutomaticReadAfterTheCapturedLastUiFrameUsesOnlyKnownDeliveredContent() {
        val created = frame(1, "conversation_created", """{"id":"chat","is_promoted":false,"cwd":"/test","name":"Chat"}""")
        val reply =
            frame(2, "assistant_delta", """{"conversation_id":"chat","text":"captured reply"}""")
                .copy(historyEntryId = 7u)
        val tape =
            RecordedSuiteTape(
                listOf(SuiteStep("daemon", "phone", created), SuiteStep("daemon", "phone", reply), SuiteStep("close", "peer")),
                coalescedReadReceipts = true,
            )
        val initial = tape.initial()
        initial.forEach { tape.delivered(it.owner, it.message!!) }
        val id = (initial.last().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        val request = frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":7}""")
        val response =
            tape
                .send("phone", request)
                .deliveries
                .single()
                .message!!
        assertEquals(90L, response.inReplyTo)
        assertEquals(MobileJson.parseToJsonElement("7"), (response.payload as kotlinx.serialization.json.JsonObject)["read_up_to"])
        tape.lifecycle("close", "peer")
        tape.assertComplete()
    }

    @Test fun peerCannotObserveThePhonesRecordedReadBeforeThePhoneActuallyAcknowledgesIt() {
        val message = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 10u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val update = frame(2, "conversation_updated", """{"id":"chat","read_up_to":10}""")
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone", message),
                    SuiteStep("client", "phone", read),
                    SuiteStep("daemon", "peer", update),
                    SuiteStep("daemon", "phone", update.copy(inReplyTo = 1)),
                ),
                coalescedReadReceipts = true,
            )
        val initial = tape.initial()
        assertEquals(listOf("phone"), initial.map { it.owner })
        initial.forEach { tape.delivered(it.owner, it.message!!) }
        val id = (initial.single().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        val actual = frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":10}""")
        assertEquals(listOf("phone", "peer"), tape.send("phone", actual).deliveries.map { it.owner })
        tape.assertComplete()
    }

    @Test fun aPendingPhoneReadCannotBlockThePeersPermissionPromptOrAnotherConversation() {
        val message = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 10u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val update = frame(2, "conversation_updated", """{"id":"chat","read_up_to":10}""")
        val prompt = frame(3, "modal_shown", """{"conversation_id":"chat","modal_id":"permission"}""")
        val other = frame(4, "assistant_delta", """{"conversation_id":"other","text":"independent"}""").copy(historyEntryId = 1u)
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone", message),
                    SuiteStep("client", "phone", read),
                    SuiteStep("daemon", "peer", update),
                    SuiteStep("daemon", "phone", update.copy(inReplyTo = 1)),
                    SuiteStep("daemon", "peer", prompt),
                    SuiteStep("daemon", "peer", other),
                ),
                coalescedReadReceipts = true,
            )
        val initial = tape.initial()
        assertEquals(listOf("assistant_delta", "modal_shown", "assistant_delta"), initial.map { it.message!!.type })
        initial.forEach { tape.delivered(it.owner, it.message!!) }
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val id = (initial.first().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        val acknowledgement = tape.send("phone", frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":10}"""))
        assertEquals(listOf("phone", "peer"), acknowledgement.deliveries.map { it.owner })
        tape.assertComplete()
    }

    @Test fun intermediateObservationCannotMakeAnAutomaticReadARequiredPreconditionForTheNextAction() {
        val message = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 10u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val update = frame(2, "conversation_updated", """{"id":"chat","read_up_to":10}""")
        val checkpoint = frame(1, "test-control:checkpoint", """{"body":"phone-send"}""")
        val complete = frame(2, "test-control:checkpoint", """{"body":"scenario-complete"}""")
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone", message),
                    SuiteStep("client", "phone", read),
                    SuiteStep("daemon", "peer", update),
                    SuiteStep("daemon", "phone", update.copy(inReplyTo = 1)),
                    SuiteStep("client", "phone", frame(2, "request_model_list", "{}")),
                    SuiteStep("daemon", "phone", frame(3, "model_list", "{}", 2)),
                    SuiteStep("client", "control", checkpoint),
                    SuiteStep("client", "phone", frame(3, "send_message", """{"text":"next action"}""")),
                    SuiteStep("client", "control", complete),
                ),
                coalescedReadReceipts = true,
            )
        val initial = tape.initial()
        assertEquals(listOf("assistant_delta"), initial.map { it.message!!.type })
        initial.forEach { tape.delivered(it.owner, it.message!!) }
        // Ordinary preceding requests stay required despite the deferred background notification.
        assertEquals(false, tape.checkpointReady("phone-send"))
        assertEquals(listOf("model_list"), tape.request("phone", frame(90, "request_model_list", "{}")).map { it.message!!.type })
        assertEquals(true, tape.checkpointReady("phone-send"))
        assertEquals(emptyList<SuiteStep>(), tape.request("control", checkpoint.copy(id = 90)))
        assertEquals(emptyList<SuiteStep>(), tape.request("phone", frame(91, "send_message", """{"text":"next action"}""")))
        // Passing an observation does not forge the phone read or release its notification.
        assertEquals(false, tape.checkpointReady("scenario-complete"))
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        val id = (initial.single().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        assertEquals(
            listOf("phone", "peer"),
            tape.send("phone", frame(92, "mark_conversation_read", """{"conversation_id":$id,"up_to":10}""")).deliveries.map { it.owner },
        )
        assertEquals(true, tape.checkpointReady("scenario-complete"))
        tape.request("control", complete.copy(id = 91))
        tape.assertComplete()
    }

    @Test fun presentationDrivenReadsCancelOnlyAnUnsentReadNotificationWhenItsConnectionEnds() {
        val message = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 10u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val update = frame(2, "conversation_updated", """{"id":"chat","read_up_to":10}""")
        val complete = frame(1, "test-control:checkpoint", """{"body":"scenario-complete"}""")
        val entries =
            listOf(
                SuiteStep("daemon", "phone", message),
                SuiteStep("client", "phone", read),
                SuiteStep("daemon", "peer", update),
                SuiteStep("daemon", "phone", update.copy(inReplyTo = 1)),
                SuiteStep("client", "phone", frame(2, "send_message", """{"text":"actual next action"}""")),
                SuiteStep("inbound_end", "peer"),
                SuiteStep("close", "peer"),
                SuiteStep("client", "control", complete),
            )
        for (required in listOf(false, true)) {
            val tape = RecordedSuiteTape(entries, coalescedReadReceipts = true, requireRecordedReadProgress = required)
            val initial = tape.initial()
            assertEquals(listOf("assistant_delta"), initial.map { it.message!!.type })
            initial.forEach { tape.delivered(it.owner, it.message!!) }
            assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
            // No read notification may be invented while the phone has not acknowledged anything.
            val result = tape.send("phone", frame(90, "send_message", """{"text":"actual next action"}"""))
            assertEquals(if (required) emptyList<String>() else listOf("inbound_end"), result.deliveries.map { it.direction })
            tape.lifecycle("close", "peer")
            assertEquals(!required, tape.checkpointReady("scenario-complete"))
            if (required) {
                assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
            } else {
                tape.request("control", complete.copy(id = 91))
                tape.assertComplete()
                assertThrows(IllegalStateException::class.java) {
                    tape.send("peer", frame(92, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}"""))
                }
            }
        }
    }

    @Test fun explicitPeerReadActionsNeverUseAutomaticPhoneReadNormalization() {
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val tape = RecordedSuiteTape(listOf(SuiteStep("client", "peer", read)), coalescedReadReceipts = true)
        val changed = frame(90, "mark_conversation_read", """{"conversation_id":"chat","up_to":14}""")
        assertThrows(IllegalStateException::class.java) { tape.send("peer", changed) }
    }

    @Test fun reconnectCanAcknowledgeRetainedPhoneHistoryButNeverAnotherClientsOrHostsHistory() {
        val old = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 14u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":14}""")
        val confirmed = frame(2, "conversation_updated", """{"id":"chat","read_up_to":14}""", 1)
        for (prior in listOf("phone/host0/0", "peer/host0/0", "phone/host1/0")) {
            val tape =
                RecordedSuiteTape(
                    listOf(
                        SuiteStep("daemon", prior, old),
                        SuiteStep("client", "phone/host0/1", read),
                        SuiteStep("daemon", "phone/host0/1", confirmed),
                    ),
                    coalescedReadReceipts = true,
                    retainedPhoneHistory = true,
                )
            val initial = tape.initial().single()
            tape.delivered(prior, initial.message!!)
            val id = (initial.message.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
            val actual = frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":14}""")
            if (prior == "phone/host0/0") {
                assertEquals(true, tape.send("phone/host0/1", actual).accepted)
                tape.assertComplete()
            } else {
                assertThrows(IllegalStateException::class.java) { tape.send("phone/host0/1", actual) }
            }
        }
    }

    @Test fun retainedHistoryReadMayFinishEarlierThanTheCapturedAutomaticAcknowledgement() {
        val shown = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 11u)
        val metadata =
            frame(
                2,
                "turn_end",
                """{"conversation_id":"chat","stop_reason":"end_turn","outcome":"success"}""",
            ).copy(historyEntryId = 12u)
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val confirmed = frame(3, "conversation_updated", """{"id":"chat","read_up_to":10}""", 1)
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone/host0/0", shown),
                    SuiteStep("daemon", "phone/host0/0", metadata),
                    SuiteStep("client", "phone/host0/1", read),
                    SuiteStep("daemon", "phone/host0/1", confirmed),
                ),
                coalescedReadReceipts = true,
                retainedPhoneHistory = true,
            )
        val initial = tape.initial()
        initial.forEach { tape.delivered(it.owner, it.message!!) }
        val id = (initial.first().message!!.payload as kotlinx.serialization.json.JsonObject).getValue("conversation_id")
        val actual = frame(90, "mark_conversation_read", """{"conversation_id":$id,"up_to":12}""")
        val reply =
            tape
                .send("phone/host0/1", actual)
                .deliveries
                .single()
                .message!!
        assertEquals(MobileJson.parseToJsonElement("11"), (reply.payload as kotlinx.serialization.json.JsonObject)["read_up_to"])
        tape.assertComplete()
    }

    @Test fun anUnconfirmedAutomaticReadCancelledByConnectionCloseIsNotARequiredUserAction() {
        val message = frame(1, "assistant_delta", """{"conversation_id":"chat","text":"captured"}""").copy(historyEntryId = 11u)
        val pending = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":11}""")
        val tape =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone/host0/0", message),
                    SuiteStep("client", "phone/host0/0", pending),
                    SuiteStep("close", "phone/host0/0"),
                ),
                coalescedReadReceipts = true,
                retainedPhoneHistory = true,
            )
        tape.initial().forEach { tape.delivered(it.owner, it.message!!) }
        tape.lifecycle("close", "phone/host0/0")
        tape.assertComplete()
        val strict =
            RecordedSuiteTape(
                listOf(
                    SuiteStep("daemon", "phone/host0/0", message),
                    SuiteStep("client", "phone/host0/0", pending),
                ),
                coalescedReadReceipts = true,
                retainedPhoneHistory = true,
            )
        strict.initial()
        assertThrows(IllegalStateException::class.java) { strict.assertComplete() }
    }
}
