package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.data.repository.SystemPromptReading
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 system-prompt frames (#823). Wire SSOT: `../pyrycode/docs/protocol-mobile.md`
 * § "Setting a conversation's system prompt" / § "Reading a conversation's system prompt".
 *
 * Owns the **payload-shape** coverage — the three stored states in both directions, the three statuses,
 * the malformed branches and the byte limit. RemoteConversationRepositorySystemPromptTest owns the wire
 * round trip and does not re-assert these shapes.
 */
class SystemPromptPayloadsTest {
    @Test
    fun request_encodesConversationIdOnly() {
        val element = MobileJson.encodeToJsonElement(RequestSystemPromptPayloadDto(conversationId = "conv-1"))

        assertEquals(json("""{"conversation_id":"conv-1"}"""), element)
    }

    // The clear is an explicit null on the wire, never an elided key — MobileJson's explicitNulls=false
    // would drop a DTO field, which is why the payload is built by hand.
    @Test
    fun setPayload_null_encodesExplicitNull() {
        val payload = setSystemPromptPayload("conv-1", null)

        assertEquals(json("""{"conversation_id":"conv-1","system_prompt":null}"""), payload)
        assertEquals(JsonNull, payload["system_prompt"])
    }

    @Test
    fun setPayload_empty_encodesExplicitEmptyString() {
        assertEquals(json("""{"conversation_id":"conv-1","system_prompt":""}"""), setSystemPromptPayload("conv-1", ""))
    }

    @Test
    fun setPayload_text_encodesVerbatim() {
        val text = "  Answer only in haiku.\n\tÄäkköset 🌸  "

        val payload = setSystemPromptPayload("conv-1", text)

        assertEquals(text, (payload["system_prompt"] as JsonPrimitive).content)
    }

    // The payload survives the envelope encode: the explicit null is not stripped one level up.
    @Test
    fun setPayload_null_survivesEnvelopeEncode() {
        val envelope = Envelope(id = 1, type = "set_system_prompt", ts = "t", payload = setSystemPromptPayload("c", null))

        val encoded = MobileJson.encodeToString(Envelope.serializer(), envelope)

        assertTrue(encoded.contains("\"system_prompt\":null"))
    }

    @Test
    fun reply_absentKey_readsAsNoPromptStored() {
        val reading = json("""{"session_prompt_status":"no_session"}""").toSystemPromptReading()

        assertEquals(SystemPromptReading(systemPrompt = null, sessionPromptStatus = SessionPromptStatus.NoSession), reading)
    }

    @Test
    fun reply_emptyString_staysDistinctFromAbsent() {
        val reading = json("""{"system_prompt":"","session_prompt_status":"matches"}""").toSystemPromptReading()

        assertEquals("", reading.systemPrompt)
        assertEquals(SessionPromptStatus.Matches, reading.sessionPromptStatus)
    }

    @Test
    fun reply_text_isHeldVerbatim() {
        val reading = json("""{"system_prompt":"  Haiku 🌸\n ","session_prompt_status":"differs"}""").toSystemPromptReading()

        assertEquals("  Haiku 🌸\n ", reading.systemPrompt)
        assertEquals(SessionPromptStatus.Differs, reading.sessionPromptStatus)
    }

    // The two fields are independent: stored text beside no running session is a normal reading.
    @Test
    fun reply_textWithNoSession_keepsBoth() {
        val reading = json("""{"system_prompt":"x","session_prompt_status":"no_session"}""").toSystemPromptReading()

        assertEquals(SystemPromptReading("x", SessionPromptStatus.NoSession), reading)
    }

    // A read value written back must keep its state: absent → null, "" → "", text → text.
    @Test
    fun readThenWrite_keepsEachStateDistinct() {
        assertEquals(JsonNull, readThenWrite("""{"session_prompt_status":"matches"}"""))
        assertEquals(JsonPrimitive(""), readThenWrite("""{"system_prompt":"","session_prompt_status":"matches"}"""))
        assertEquals(JsonPrimitive("t"), readThenWrite("""{"system_prompt":"t","session_prompt_status":"matches"}"""))
    }

    @Test
    fun reply_malformedPrompt_failsWithoutQuotingIt() {
        for (bad in listOf("null", "42", "true", """{"a":"SECRET"}""", """["SECRET"]""")) {
            val e =
                assertThrows(SerializationException::class.java) {
                    json("""{"system_prompt":$bad,"session_prompt_status":"matches"}""").toSystemPromptReading()
                }
            assertFalse(e.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun reply_malformedStatus_fails() {
        for (bad in listOf(
            """{"system_prompt":"SECRET"}""",
            """{"system_prompt":"SECRET","session_prompt_status":"stale"}""",
            """{"system_prompt":"SECRET","session_prompt_status":"Matches"}""",
            """{"system_prompt":"SECRET","session_prompt_status":1}""",
            """{"system_prompt":"SECRET","session_prompt_status":null}""",
        )) {
            val e = assertThrows(SerializationException::class.java) { json(bad).toSystemPromptReading() }
            assertFalse(e.message.orEmpty().contains("SECRET"))
        }
    }

    @Test
    fun reply_notAnObject_fails() {
        for (bad in listOf("\"SECRET\"", "[]", "null")) {
            assertThrows(SerializationException::class.java) { json(bad).toSystemPromptReading() }
        }
    }

    @Test
    fun limit_countsUtf8BytesNotChars() {
        assertEquals(5, SystemPromptLimit.utf8Bytes("abcde"))
        assertEquals(2, SystemPromptLimit.utf8Bytes("ä"))
        assertEquals(3, SystemPromptLimit.utf8Bytes("€"))
        assertEquals(4, SystemPromptLimit.utf8Bytes("🌸"))
    }

    @Test
    fun limit_isInclusiveAt8192BytesOfMultiByteText() {
        val exactly = "€".repeat(2730) + "ab" // 8190 + 2 bytes, 2732 chars
        assertEquals(8192, SystemPromptLimit.utf8Bytes(exactly))
        assertTrue(SystemPromptLimit.fits(exactly))

        val over = "€".repeat(2730) + "ä" + "a" // 8193 bytes, fewer chars than the limit
        assertEquals(8193, SystemPromptLimit.utf8Bytes(over))
        assertFalse(SystemPromptLimit.fits(over))
        assertTrue(SystemPromptLimit.fits(""))
        assertEquals(8192, SystemPromptLimit.MAX_BYTES)
    }

    @Test
    fun reply_absent_isNullNotEmpty() {
        assertNull(json("""{"session_prompt_status":"differs"}""").toSystemPromptReading().systemPrompt)
    }

    private fun json(raw: String): JsonElement = MobileJson.parseToJsonElement(raw)

    private fun readThenWrite(reply: String): JsonElement? =
        setSystemPromptPayload("c", json(reply).toSystemPromptReading().systemPrompt)["system_prompt"]
}
