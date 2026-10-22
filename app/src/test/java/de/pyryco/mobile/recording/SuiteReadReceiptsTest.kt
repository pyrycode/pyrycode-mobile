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
        queryBeforeRead: String? = null,
        queryConversation: String = "chat",
        intermediateRead: Boolean = false,
        terminateAfterRead: Boolean = false,
        notifyIntermediateRead: Boolean = false,
    ): RecordedSuiteTape {
        val item = """{
            "id":11,"kind":"assistant_message","rev":13,"order":11,"status":"done",
            "active":false,"shown":true,"summary":"reply","content":{"text":"reply"}
        }"""
        val items =
            if (intermediateRead) {
                """{"id":10,"kind":"assistant_message","rev":10,"order":10,"status":"done",
                    "active":false,"shown":true,"summary":"earlier","content":{"text":"earlier"}},$item"""
            } else {
                item
            }
        val lower = if (intermediateRead) 10 else 11
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
                *if (!intermediateRead) {
                    emptyArray()
                } else {
                    arrayOf(
                        SuiteStep(
                            "daemon",
                            sourceOwner,
                            frame(8, "conversation_updated", """{"id":"chat","last_shown_version":10,"read_up_to":0}"""),
                        ),
                    )
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
                    frame(2, "thread_items", """{"conversation_id":"chat","epoch":"e","version":15,"items":[$items],"final":false}""", 1),
                ),
                SuiteStep(
                    "daemon",
                    sourceOwner,
                    frame(
                        3,
                        "thread_items",
                        """{"conversation_id":"chat","epoch":"e","version":$finalVersion,"items":[],"final":true,"lower_order":$lower,"upper_order":16,"continuation":$lower,"older_exists":false}""",
                        1,
                    ),
                ),
                *if (!intermediateRead) {
                    emptyArray()
                } else {
                    arrayOf(
                        SuiteStep("client", readerOwner, frame(4, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")),
                        SuiteStep(
                            "daemon",
                            readerOwner,
                            frame(7, "conversation_updated", """{"id":"chat","last_shown_version":11,"read_up_to":10}""", 4),
                        ),
                        *if (notifyIntermediateRead) {
                            arrayOf(
                                SuiteStep(
                                    "daemon",
                                    readerOwner,
                                    frame(10, "conversation_updated", """{"id":"chat","last_shown_version":11,"read_up_to":10}"""),
                                ),
                            )
                        } else {
                            emptyArray()
                        },
                    )
                },
                *if (queryBeforeRead == null) {
                    emptyArray()
                } else {
                    arrayOf(
                        SuiteStep(
                            "client",
                            sourceOwner,
                            frame(
                                3,
                                queryBeforeRead,
                                if (queryBeforeRead ==
                                    "list_conversations"
                                ) {
                                    "{}"
                                } else {
                                    """{"conversation_id":"$queryConversation","epoch":"e","version":15}"""
                                },
                            ),
                        ),
                        SuiteStep(
                            "daemon",
                            sourceOwner,
                            if (queryBeforeRead == "list_conversations") {
                                frame(6, "conversations", """{"conversations":[{"id":"chat","last_shown_version":11,"read_up_to":0}]}""", 3)
                            } else {
                                frame(
                                    6,
                                    "thread_items",
                                    """{"conversation_id":"$queryConversation","epoch":"e","version":15,"items":[],"final":true,"from_version":15}""",
                                    3,
                                )
                            },
                        ),
                    )
                },
                SuiteStep("client", readerOwner, frame(2, "mark_conversation_read", """{"conversation_id":"chat","up_to":11}""")),
                SuiteStep(
                    "daemon",
                    readerOwner,
                    frame(4, "conversation_updated", """{"id":"chat","last_shown_version":11,"read_up_to":11}""", 2),
                ),
                *if (terminateAfterRead) {
                    arrayOf(
                        SuiteStep(
                            "daemon",
                            readerOwner,
                            frame(9, "conversation_updated", """{"id":"chat","last_shown_version":11,"read_up_to":11}"""),
                        ),
                        SuiteStep("inbound_end", readerOwner),
                    )
                } else {
                    emptyArray()
                },
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

    @Test fun coalescedReadCannotExposeItsSnapshotBeforeTheCapturedQuery() {
        for (type in listOf("thread_catch_up", "list_conversations")) {
            val tape = authoritativeTape(queryBeforeRead = type)
            deliverInitial(tape)
            authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
            assertTrue("the read must wait for its captured $type prerequisite", authoritativeRead(tape).deliveries.isEmpty())
            val payload = if (type == "list_conversations") "{}" else """{"conversation_id":"chat","epoch":"e","version":15}"""
            val released = tape.send("phone/host0/0", frame(92, type, payload)).deliveries
            val acknowledgement = released.single { it.message?.type == "conversation_updated" }.message!!
            assertEquals(91L, acknowledgement.inReplyTo)
            assertEquals(JsonPrimitive(11), acknowledgement.payload.jsonObject["last_shown_version"])
            assertEquals(JsonPrimitive(11), acknowledgement.payload.jsonObject["read_up_to"])
            tape.assertComplete()
        }
    }

    @Test fun readyReadAcknowledgementsPrecedeTheirOwnersInboundEnd() {
        for (type in listOf("thread_catch_up", "list_conversations")) {
            val tape = authoritativeTape(queryBeforeRead = type, intermediateRead = true, terminateAfterRead = true)
            deliverInitial(tape)
            authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
            val immediate = authoritativeRead(tape, target = 10).deliveries.single()
            assertEquals(91L, immediate.message!!.inReplyTo)
            assertTrue(
                "the later acknowledgement still requires its captured query",
                tape
                    .send(
                        "phone/host0/0",
                        frame(93, "mark_conversation_read", """{"conversation_id":"chat","up_to":11}"""),
                    ).deliveries
                    .isEmpty(),
            )
            val payload = if (type == "list_conversations") "{}" else """{"conversation_id":"chat","epoch":"e","version":15}"""
            val released = tape.send("phone/host0/0", frame(92, type, payload)).deliveries
            val acknowledgement = released.single { it.message?.inReplyTo == 93L }
            assertEquals("phone/host0/0", acknowledgement.owner)
            assertEquals(JsonPrimitive(11), acknowledgement.message!!.payload.jsonObject["read_up_to"])
            assertTrue(
                "the live channel must receive the acknowledgement before closing",
                released.indexOf(acknowledgement) < released.indexOfFirst { it.direction == "inbound_end" },
            )
            tape.assertComplete()
        }
    }

    @Test fun peerReadProgressCannotChooseAFutureSnapshotForAnEarlierPhoneRead() {
        val tape = authoritativeTape(queryBeforeRead = "thread_catch_up", intermediateRead = true)
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        tape.delivered("peer/host0/0", frame(80, "conversation_updated", """{"id":"chat","read_up_to":11}"""))
        val reply = authoritativeRead(tape, target = 10).deliveries.single().message!!
        assertEquals(91L, reply.inReplyTo)
        assertEquals("confirmed peer progress remains monotonic", JsonPrimitive(11), reply.payload.jsonObject["read_up_to"])
        tape.request("phone/host0/0", frame(92, "thread_catch_up", """{"conversation_id":"chat","epoch":"e","version":15}"""))
        tape.assertComplete()
    }

    @Test fun peerProgressReleasesTheCapturedPhoneNotificationWithoutALocalRead() {
        val tape =
            authoritativeTape(
                queryBeforeRead = "thread_catch_up",
                intermediateRead = true,
                notifyIntermediateRead = true,
                terminateAfterRead = true,
            )
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        tape.delivered("peer/other-host/0", frame(80, "conversation_updated", """{"id":"chat","read_up_to":11}"""))
        assertTrue("another host supplies no read authority", tape.initial().isEmpty())
        tape.delivered("peer/host0/0", frame(80, "conversation_updated", """{"id":"chat","read_up_to":11}"""))
        val notification = tape.initial().single()
        assertEquals("phone/host0/0", notification.owner)
        assertEquals(null, notification.message!!.inReplyTo)
        assertEquals(JsonPrimitive(10), notification.message.payload.jsonObject["read_up_to"])
        assertEquals(JsonPrimitive(11), notification.message.payload.jsonObject["last_shown_version"])
        tape.request("phone/host0/0", frame(92, "thread_catch_up", """{"conversation_id":"chat","epoch":"e","version":15}"""))
        tape.assertComplete()
    }

    @Test fun aForeignConnectionOrConversationCannotHoldAQualifiedRead() {
        for ((reader, conversation) in listOf("phone/host0/1" to "chat", "phone/host0/0" to "other")) {
            val tape = authoritativeTape(readerOwner = reader, queryBeforeRead = "thread_catch_up", queryConversation = conversation)
            deliverInitial(tape)
            authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
            assertEquals(
                91L,
                authoritativeRead(tape, reader)
                    .deliveries
                    .single()
                    .message!!
                    .inReplyTo,
            )
            tape.send("phone/host0/0", frame(92, "thread_catch_up", """{"conversation_id":"$conversation","epoch":"e","version":15}"""))
            tape.assertComplete()
        }
    }

    @Test fun anIntermediateCapturedReadMustNotWaitForTheQueryItCauses() {
        val tape = authoritativeTape(queryBeforeRead = "thread_catch_up", intermediateRead = true)
        deliverInitial(tape)
        authoritativeReplies(tape).forEach { tape.delivered(it.owner, it.message!!) }
        val first = authoritativeRead(tape, target = 10).deliveries
        assertEquals("the earlier captured read releases its own later query", 1, first.size)
        assertEquals(
            JsonPrimitive(10),
            first
                .single()
                .message!!
                .payload.jsonObject["read_up_to"],
        )
        val finalRead = frame(93, "mark_conversation_read", """{"conversation_id":"chat","up_to":11}""")
        assertTrue(tape.send("phone/host0/0", finalRead).deliveries.isEmpty())
        val released =
            tape
                .send(
                    "phone/host0/0",
                    frame(92, "thread_catch_up", """{"conversation_id":"chat","epoch":"e","version":15}"""),
                ).deliveries
        assertEquals(93L, released.single { it.message?.type == "conversation_updated" }.message!!.inReplyTo)
        tape.assertComplete()
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

    @Test fun explicitPeerReadActionsNeverUseAutomaticPhoneReadNormalization() {
        val read = frame(1, "mark_conversation_read", """{"conversation_id":"chat","up_to":10}""")
        val tape = RecordedSuiteTape(listOf(SuiteStep("client", "peer", read)), coalescedReadReceipts = true)
        val changed = frame(90, "mark_conversation_read", """{"conversation_id":"chat","up_to":14}""")
        assertThrows(IllegalStateException::class.java) { tape.send("peer", changed) }
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
