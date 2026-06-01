package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Role
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Decode-from-[JsonElement] → domain-map tests for the v2 `message` payload (#317). JUnit4,
 * mirroring ConversationsPayloadTest.kt. Each scenario builds a `JsonElement` via
 * `MobileJson.parseToJsonElement` (mimicking `Envelope.payload`), decodes through [MobileJson],
 * then maps via `toMessage(envelope, sessionId)` — exercising the real #312 consumer path.
 *
 * Decode-only: the phone never sends a `message` payload (server → phone, and the `send_message`
 * response echo is also inbound), so there is no encode round-trip test.
 */
class MessagePayloadTest {
    // Only `ts` is read by the mapper; the rest is a minimal valid envelope wrapping the payload.
    private fun envelopeFor(
        payload: JsonElement,
        ts: String = "2026-05-20T10:00:00Z",
    ): Envelope = Envelope(id = 1, type = "message", ts = ts, payload = payload)

    @Test
    fun userPayload_mapsEveryFieldWithCallerSuppliedSessionId() {
        val fixture = """{"conversation_id":"c1","message_id":"m1","role":"user","text":"hello"}"""
        val element = MobileJson.parseToJsonElement(fixture)
        val dto = MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)

        val message = dto.toMessage(envelopeFor(element), sessionId = "s-active")

        // Every field has a defined value (AC #2). `sessionId` appears nowhere in the payload —
        // asserting it proves the id is caller-injected, not decoded.
        assertEquals("m1", message.id)
        assertEquals("s-active", message.sessionId)
        assertEquals(Role.User, message.role)
        assertEquals("hello", message.content)
        assertEquals(Instant.parse("2026-05-20T10:00:00Z"), message.timestamp)
        assertFalse(message.isStreaming)
        assertNull(message.toolCall)
    }

    @Test
    fun assistantPayload_mapsToAssistantRole() {
        val fixture = """{"conversation_id":"c1","message_id":"m2","role":"assistant","text":"hi"}"""
        val element = MobileJson.parseToJsonElement(fixture)
        val dto = MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)

        assertEquals(Role.Assistant, dto.toMessage(envelopeFor(element), sessionId = "s-active").role)
    }

    @Test
    fun systemRole_isRejectedAtDecode() {
        // `system` is a valid wire role but has no domain target (no Role.System); it is rejected
        // at the structural decode boundary, before any Message is built (AC #3).
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","message_id":"m3","role":"system","text":"sys"}""",
            )
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)
        }
    }

    @Test
    fun unknownRole_isRejectedAtDecode() {
        // The closed mappable-role set rejects ANY non-{user,assistant} string, not just `system` —
        // proving the rejection is closed-set behavior, not a `system`-specific special case.
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","message_id":"m4","role":"wizard","text":"poof"}""",
            )
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)
        }
    }

    @Test
    fun fieldIncompletePayload_throwsTypedDecodeFailure() {
        // Drops the required `text`. All four fields are required, so any omission is a valid
        // trigger; no partial / null-punned Message is produced (AC #4).
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","message_id":"m5","role":"user"}""",
            )
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)
        }
    }

    @Test
    fun malformedEnvelopeTimestamp_throwsAtMap() {
        // A valid payload decodes fine; the envelope `ts` is parsed in the mapper, so a malformed
        // RFC 3339 string surfaces as IllegalArgumentException (kotlinx-datetime) at map, not decode.
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","message_id":"m6","role":"user","text":"hello"}""",
            )
        val dto = MobileJson.decodeFromJsonElement<MessagePayloadDto>(element)

        assertThrows(IllegalArgumentException::class.java) {
            dto.toMessage(envelopeFor(element, ts = "not-a-date"), sessionId = "s")
        }
    }

    // ---- message_chunk (#313): a batch of `message` rows decoded as a whole ---------------------

    @Test
    fun messageChunk_decodesEveryRowInOrder() {
        // The backfill response body: distinct roles and ids must round-trip, order preserved.
        val element =
            MobileJson.parseToJsonElement(
                """
                {"messages":[
                  {"conversation_id":"c1","message_id":"m12","role":"assistant","text":"snow"},
                  {"conversation_id":"c1","message_id":"m13","role":"user","text":"thanks"}
                ]}
                """.trimIndent(),
            )

        val chunk = MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(element)

        assertEquals(2, chunk.messages.size)
        assertEquals(listOf("m12", "m13"), chunk.messages.map { it.messageId })
        assertEquals(listOf(WireRole.Assistant, WireRole.User), chunk.messages.map { it.role })
    }

    @Test
    fun messageChunk_unmappableRowRole_throwsAtDecode() {
        // A single bad row (role `system`) fails the whole-chunk decode at the structural boundary.
        val element =
            MobileJson.parseToJsonElement(
                """{"messages":[{"conversation_id":"c1","message_id":"m1","role":"system","text":"x"}]}""",
            )
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(element)
        }
    }

    // ---- backfill_since (#313): the encode-only catch-up request --------------------------------

    @Test
    fun backfillSince_encodesWireFieldNames() {
        // Encode-only: the phone sends this. Snake_case field names + values must match the SSOT.
        val element =
            MobileJson
                .encodeToJsonElement(
                    BackfillSincePayloadDto(
                        sinceTs = "1970-01-01T00:00:00Z",
                        conversationId = "c1",
                        maxMessages = 10_000,
                    ),
                ).jsonObject

        assertEquals("1970-01-01T00:00:00Z", element.getValue("since_ts").jsonPrimitive.content)
        assertEquals("c1", element.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(
            10_000,
            element
                .getValue("max_messages")
                .jsonPrimitive.content
                .toInt(),
        )
    }
}
