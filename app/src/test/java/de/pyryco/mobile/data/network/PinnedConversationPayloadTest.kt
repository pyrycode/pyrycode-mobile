package de.pyryco.mobile.data.network

import kotlinx.datetime.Instant
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedConversationPayloadTest {
    @Test
    fun validNullAndMissingPinsMapExactlyFromBothDtos() {
        listOf("\"2026-05-08T12:30:00.123456789+02:00\"", "null", null).forEach { pin ->
            val expected = if (pin == null || pin == "null") null else Instant.parse("2026-05-08T10:30:00.123456789Z")
            val summary =
                MobileJson.decodeFromJsonElement<ConversationsPayload>(
                    MobileJson.parseToJsonElement("""{"conversations":[${record(pin)}]}"""),
                )
            val mutation = MobileJson.decodeFromJsonElement<ConversationResponseDto>(MobileJson.parseToJsonElement(record(pin)))
            assertEquals(expected, summary.conversations.single().pinnedAt)
            assertEquals(expected, summary.toConversations().single().pinnedAt)
            assertEquals(expected, mutation.pinnedAt)
            assertEquals(expected, mutation.toConversation().pinnedAt)
        }
    }

    @Test
    fun malformedPinFailsSummaryDecoding() {
        malformedPins.forEach { pin ->
            val payload = MobileJson.parseToJsonElement("""{"conversations":[${record(pin)}]}""")
            assertThrows("pin=$pin", IllegalArgumentException::class.java) {
                MobileJson.decodeFromJsonElement<ConversationsPayload>(payload)
            }
        }
    }

    @Test
    fun malformedPinFailsMutationDecoding() {
        malformedPins.forEach { pin ->
            assertThrows("pin=$pin", IllegalArgumentException::class.java) {
                MobileJson.decodeFromJsonElement<ConversationResponseDto>(MobileJson.parseToJsonElement(record(pin)))
            }
        }
    }

    private fun record(pin: String?): String {
        val field = pin?.let { ""","pinned_at":$it""" }.orEmpty()
        return """
            {"id":"channel","name":"Channel","is_promoted":true,"cwd":"/p",
            "last_used_at":"2026-05-08T10:00:00Z","last_message_ts":"2026-05-08T10:00:00Z"$field}
            """.trimIndent()
    }

    private val malformedPins = listOf("\"yesterday\"", "\"\"", "\"+9999999999-01-01T00:00:00Z\"", "42", "true", "{}", "[]")
}
