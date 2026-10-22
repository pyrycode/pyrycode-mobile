package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    @Test
    fun sendMessage_withoutIds_omitsTheAttachmentIdsKey() {
        val element =
            MobileJson
                .encodeToJsonElement(SendMessagePayloadDto(conversationId = "c1", messageId = "m1", text = "hi"))
                .jsonObject

        assertEquals(setOf("conversation_id", "message_id", "text"), element.keys)
    }

    @Test
    fun sendMessage_withIds_encodesThemInOrder() {
        val element =
            MobileJson
                .encodeToJsonElement(
                    SendMessagePayloadDto(conversationId = "c1", messageId = "m1", text = "hi", attachmentIds = listOf("b", "a")),
                ).jsonObject

        assertEquals(listOf("b", "a"), element.getValue("attachment_ids").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun attachmentIdsForSend_dropsRepeatsKeepingFirstSeenOrder() {
        assertEquals(listOf("b", "a", "c"), MessageAttachmentIds.forSend(listOf("b", "a", "b", "c", "a")))
    }

    @Test
    fun attachmentIdsForSend_emptyMeansNoField() {
        assertNull(MessageAttachmentIds.forSend(emptyList()))
    }

    @Test
    fun attachmentIdsForSend_countsAfterRepeatsAreDropped() {
        val distinct = (1..MessageAttachmentIds.MAX).map { "id-$it" }

        assertEquals(distinct, MessageAttachmentIds.forSend(distinct + distinct.take(8)))
        assertThrows(IllegalArgumentException::class.java) {
            MessageAttachmentIds.forSend(distinct + "id-33")
        }
    }
}
