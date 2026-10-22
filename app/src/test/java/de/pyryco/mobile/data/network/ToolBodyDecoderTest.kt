package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class ToolBodyDecoderTest {
    private val oldSink = RelayLog.sink

    @Before fun quietLog() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLog() {
        RelayLog.sink = oldSink
    }

    private fun decoder() = ThreadQueryDecoder("host", "connection", 37, ThreadQueryRequest.Body("c", "e", 17uL, 23uL, "tool_call"))

    private fun payload(
        final: Boolean = false,
        content: String = "{\"input\":\"\",\"output\":\"\",\"unknown\":18446744073709551615}",
    ): JsonObject =
        MobileJson.parseToJsonElement(
            """{"conversation_id":"c","epoch":"e","version":18446744073709551615,"item_id":17,"rev":23,"kind":"tool_call","final":$final${if (final) "" else ",\"content\":$content"}}""",
        ) as JsonObject

    private fun frame(
        raw: JsonObject = payload(),
        reply: Long = 37,
    ) = Envelope(80, "thread_tool_body", "now", raw, inReplyTo = reply)

    @Test fun availabilityRequiresDataAndSeparateMatchingSuccess_evenWhenEmpty() {
        val decoder = decoder()
        val data = (decoder.decode(frame()).single() as ThreadDecodeOutcome.Complete).value as ThreadQueryReply.Body
        assertEquals("18446744073709551615", data.raw["version"].toString())
        assertEquals("18446744073709551615", data.content?.get("unknown").toString())
        assertEquals(false, data.final)
        val marker = (decoder.decode(frame(payload(true))).single() as ThreadDecodeOutcome.Complete).value as ThreadQueryReply.Body
        assertTrue(marker.final)
    }

    @Test fun completionCannotInventAnEmptyBody() {
        assertTrue(decoder().decode(frame(payload(true))).single() is ThreadDecodeOutcome.Repair)
        val raw = JsonObject(payload() - "content")
        assertTrue(decoder().decode(frame(raw)).single() is ThreadDecodeOutcome.Repair)
        for (invalid in listOf("null", "[]", "\"missing\"")) {
            assertTrue(decoder().decode(frame(payload(content = invalid))).single() is ThreadDecodeOutcome.Repair)
        }
        val decoder = decoder()
        decoder.decode(frame())
        assertTrue(
            decoder.decode(frame(JsonObject(payload(true) + ("content" to JsonObject(emptyMap()))))).single() is ThreadDecodeOutcome.Repair,
        )
    }

    @Test fun ownershipInvariantEveryCaptureFieldAndCorrelationMustMatch() {
        for ((key, value) in mapOf(
            "epoch" to JsonPrimitive("foreign"),
            "conversation_id" to JsonPrimitive("other"),
            "item_id" to JsonPrimitive(18),
            "rev" to JsonPrimitive(24),
            "kind" to JsonPrimitive("agent"),
        )) {
            assertTrue(key, decoder().decode(frame(JsonObject(payload() + (key to value)))).single() is ThreadDecodeOutcome.Repair)
        }
        assertTrue(decoder().decode(frame(reply = 38)).single() is ThreadDecodeOutcome.Repair)
        val routed = decoder()
        assertTrue(routed.decode(frame().copy(conversationId = JsonPrimitive("route"))).single() is ThreadDecodeOutcome.Complete)
        assertTrue(
            routed.decode(frame(payload(true)).copy(conversationId = JsonPrimitive("other-route"))).single() is ThreadDecodeOutcome.Repair,
        )
        val routing = decoder()
        routing.decode(frame())
        assertTrue(
            routing.decode(frame(payload(true)).copy(sessionStateCleared = JsonPrimitive(true))).single() is ThreadDecodeOutcome.Repair,
        )
        for (key in listOf("version", "kind", "rev", "item_id", "epoch")) {
            val decoder = decoder()
            decoder.decode(frame())
            val changed =
                JsonObject(
                    payload(true) + (key to if (key == "kind" || key == "epoch") JsonPrimitive("other") else JsonPrimitive(42)),
                )
            assertTrue(key, decoder.decode(frame(changed)).single() is ThreadDecodeOutcome.Repair)
        }
    }

    @Test fun assemblyIdentityMismatchIsARejectionRatherThanAnException() {
        val logical = JsonObject(payload() + ("rev" to JsonPrimitive(24))).toString()
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update("thread_tool_body".toByteArray())
                    update(0.toByte())
                }.digest(logical.toByteArray())
                .toHexString()
        val fragment =
            JsonObject(
                (payload() - setOf("content", "final")) +
                    mapOf(
                        "data" to JsonPrimitive(logical),
                        "continuation" to
                            MobileJson.parseToJsonElement(
                                """{"update_id":"$hash","index":0,"offset":0,"total_bytes":${logical.length},"final":true}""",
                            ),
                    ),
            )
        assertTrue(decoder().decode(frame(fragment)).single() is ThreadDecodeOutcome.Repair)
    }

    @Test fun fragmentedBodyPreservesTokensAndCannotCompleteWhileUnfinished() {
        val logical = payload().toString()
        val bytes = logical.toByteArray()
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update("thread_tool_body".toByteArray())
                    update(0.toByte())
                }.digest(bytes)
                .toHexString()

        fun part(
            index: Int,
            data: String,
            last: Boolean,
        ) = JsonObject(
            (payload() - setOf("content", "final")) +
                mapOf(
                    "data" to JsonPrimitive(data),
                    "continuation" to
                        (
                            MobileJson.parseToJsonElement(
                                """{"update_id":"$hash","index":$index,"offset":${if (index == 0) 0 else logical.length / 2},"total_bytes":${bytes.size},"final":$last}""",
                            ) as JsonObject
                        ),
                ),
        )
        val first = part(0, logical.take(logical.length / 2), false)
        val decoder = decoder()
        assertTrue(decoder.decode(frame(first)).single() is ThreadDecodeOutcome.Pending)
        val result = decoder.decode(frame(part(1, logical.drop(logical.length / 2), true))).single() as ThreadDecodeOutcome.Complete
        assertEquals(payload(), result.value.raw)
        assertTrue((decoder.decode(frame(payload(true))).single() as ThreadDecodeOutcome.Complete).value is ThreadQueryReply.Body)
        val unfinished = decoder()
        unfinished.decode(frame(first))
        assertTrue(unfinished.decode(frame(payload(true))).single() is ThreadDecodeOutcome.Repair)
        val corrupt = decoder()
        corrupt.decode(frame(first))
        assertTrue(corrupt.decode(frame(part(1, "X" + logical.drop(logical.length / 2 + 1), true))).single() is ThreadDecodeOutcome.Repair)
    }
}
