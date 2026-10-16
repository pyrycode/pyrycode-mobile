package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordedSuiteTapeTest {
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

    @Test fun aCommandCannotMoveToAnotherHostOrPastAControlAction() {
        for (wrong in listOf("phone/b/0", "control")) {
            val tape = RecordedSuiteTape(listOf(client("phone/a/0", frame(1, "send_message"))))
            assertThrows(IllegalStateException::class.java) { tape.request(wrong, frame(2, "send_message")) }
        }
        val tape = RecordedSuiteTape(listOf(client("control", frame(1, "test-control:stop")), client("phone", frame(1, "request_history"))))
        assertThrows(IllegalStateException::class.java) { tape.request("phone", frame(2, "request_history")) }
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
        assertEquals(emptyList<SuiteStep>(), tape.request("peer", frame(20, "list_conversations")))
        assertEquals(listOf("phone", "peer"), tape.request("phone", frame(30, "request_model_list")).map { it.owner })
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
}
