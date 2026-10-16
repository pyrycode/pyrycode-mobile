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
}
