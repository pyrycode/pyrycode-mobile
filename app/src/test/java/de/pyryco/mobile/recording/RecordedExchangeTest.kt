package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordedExchangeTest {
    private fun frame(
        id: Long,
        type: String,
        payload: String = "{}",
        reply: Long? = null,
    ) = Envelope(id = id, ts = "2026-10-16T00:00:00Z", type = type, payload = MobileJson.parseToJsonElement(payload), inReplyTo = reply)

    private fun recording(vararg entries: Pair<String, Envelope>): ExchangeRecording =
        ExchangeRecording.parse(
            buildJsonObject {
                put("format", "pyrycode.mobile-exchange")
                put("version", 1)
                put(
                    "source",
                    MobileJson.parseToJsonElement(
                        """{"mobileRevision":"${"a".repeat(
                            40,
                        )}","daemonRevision":"${"b".repeat(
                            40,
                        )}","daemonBinarySha256":"${"c".repeat(64)}","claudeVersion":"test","capturedAt":"2026-10-16T00:00:00Z"}""",
                    ),
                )
                put("capabilities", MobileJson.parseToJsonElement("""["interactive"]"""))
                put(
                    "scenario",
                    MobileJson.parseToJsonElement(
                        """{"prompt":"controlled prompt","expectedReply":"complete reply","allowLabel":"Allow once"}""",
                    ),
                )
                put(
                    "wire",
                    kotlinx.serialization.json.JsonArray(
                        entries.map { (direction, message) ->
                            buildJsonObject {
                                put("direction", direction)
                                put("message", MobileJson.encodeToJsonElement(Envelope.serializer(), message))
                            }
                        },
                    ),
                )
            }.toString(),
        )

    @Test fun requestsAndRepliesUseFreshIds() {
        val exchange =
            RecordedExchange(
                recording(
                    "client" to frame(1, "list_conversations"),
                    "daemon" to frame(2, "conversations", reply = 1),
                ),
            )
        assertEquals(45L, exchange.reply(frame(45, "list_conversations")).single().inReplyTo)
        exchange.assertComplete()
    }

    @Test fun changedAndExtraFieldsFailWithoutLeakingContent() {
        for (payload in listOf("""{"text":"wrong secret content"}""", """{"text":"hello","extra":true}""")) {
            val exchange = RecordedExchange(recording("client" to frame(1, "send_message", """{"text":"hello","message_id":"old"}""")))
            val error = assertThrows(IllegalStateException::class.java) { exchange.reply(frame(2, "send_message", payload)) }
            assertEquals("Recorded exchange request mismatch at step 1", error.message)
        }
    }

    @Test fun clientMessageIdentityMayChangeButTextMayNot() {
        val exchange = RecordedExchange(recording("client" to frame(1, "send_message", """{"text":"hello","message_id":"old"}""")))
        exchange.reply(frame(10, "send_message", """{"text":"hello","message_id":"fresh"}"""))
        exchange.assertComplete()
    }

    @Test fun queriesMayReorderWithinAnAction() {
        val exchange =
            RecordedExchange(
                recording(
                    "client" to frame(1, "list_conversations"),
                    "daemon" to frame(3, "conversations", reply = 1),
                    "client" to frame(2, "request_model_list"),
                    "daemon" to frame(4, "model_list", reply = 2),
                    "client" to frame(5, "send_message", """{"text":"go"}"""),
                ),
            )
        assertEquals(emptyList<Envelope>(), exchange.reply(frame(20, "request_model_list")))
        assertEquals(listOf(10L, 20L), exchange.reply(frame(10, "list_conversations")).map { it.inReplyTo })
        exchange.reply(frame(30, "send_message", """{"text":"go"}"""))
        exchange.assertComplete()
    }

    @Test fun queryCannotBorrowFromAFutureAction() {
        val exchange = RecordedExchange(recording("client" to frame(1, "send_message"), "client" to frame(2, "request_model_list")))
        assertThrows(IllegalStateException::class.java) { exchange.reply(frame(20, "request_model_list")) }
    }

    @Test fun wrongPermissionChoiceFails() {
        val exchange =
            RecordedExchange(
                recording(
                    "client" to frame(1, "modal_answer", """{"option_id":"allow_once","answer_token":"old"}"""),
                ),
            )
        assertThrows(IllegalStateException::class.java) {
            exchange.reply(frame(20, "modal_answer", """{"option_id":"reject_once","answer_token":"fresh"}"""))
        }
    }

    @Test fun duplicateAndLateRequestsFailEvenAfterCompletion() {
        val exchange = RecordedExchange(recording("client" to frame(1, "list_conversations")))
        exchange.reply(frame(20, "list_conversations"))
        exchange.assertComplete()
        assertThrows(IllegalStateException::class.java) { exchange.reply(frame(20, "list_conversations")) }
        assertThrows(IllegalStateException::class.java) { exchange.assertComplete() }
    }

    @Test fun missingRequestsFail() {
        val exchange = RecordedExchange(recording("client" to frame(1, "list_conversations")))
        assertThrows(IllegalStateException::class.java) { exchange.assertComplete() }
    }

    @Test fun handshakeAndSensitiveFramesAreRejected() {
        for (message in listOf(
            frame(1, "hello"),
            frame(1, "send_message", """{"token":"opaque"}"""),
            frame(1, "send_message", """{"text":"sk-ant-forbidden"}"""),
        )) {
            assertThrows(IllegalArgumentException::class.java) { recording("client" to message) }
        }
    }
}
