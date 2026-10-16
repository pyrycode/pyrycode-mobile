package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuiteReadReceiptsTest {
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
