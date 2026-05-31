package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode-from-[kotlinx.serialization.json.JsonElement] → domain-map tests for the v2
 * `conversation_created` / `conversation_updated` mutation-response payloads (#318). JUnit4,
 * mirroring ConversationsPayloadTest.kt. Each scenario builds a `JsonElement` via
 * `MobileJson.parseToJsonElement` (mimicking `Envelope.payload`), decodes through [MobileJson],
 * then maps — exercising the real future-mutation-slice consumer path.
 *
 * Decode-only: the phone never sends these payloads (server → phone), so there is no encode
 * round-trip test.
 */
class ConversationResponseDtoTest {
    // Bare `conversation_created` object (server SSOT conversations_write.go, #274):
    // cwd BEFORE name (Go ConversationCreatedPayload key order), unnamed + unpromoted + scratch cwd.
    private val createdFixture =
        """
        {"id":"c-new","is_promoted":false,"cwd":"~/.pyrycode/scratch","name":null,"last_used_at":"2026-05-08T10:34:01Z"}
        """.trimIndent()

    // Bare `conversation_updated` object: name BEFORE cwd (Go ConversationUpdatedPayload key order),
    // named + promoted + bound cwd. Proves the single DTO decodes both wire orderings.
    private val updatedFixture =
        """
        {"id":"c-promoted","is_promoted":true,"name":"weekly-planning","cwd":"/Users/j/Projects/Planner","last_used_at":"2026-05-08T10:34:30Z"}
        """.trimIndent()

    @Test
    fun conversationCreated_mapsUnnamedScratchBranch() {
        val element = MobileJson.parseToJsonElement(createdFixture)
        val dto = MobileJson.decodeFromJsonElement<ConversationResponseDto>(element)

        val result = dto.toConversation()

        assertEquals("c-new", result.id)
        assertNull(result.name)
        assertFalse(result.isPromoted)
        assertEquals(Instant.parse("2026-05-08T10:34:01Z"), result.lastUsedAt)
        // DEFAULT_SCRATCH_CWD survives verbatim (not blanked/normalized). Reference the
        // constant rather than a literal so a sentinel change can't silently pass.
        assertEquals(DEFAULT_SCRATCH_CWD, result.cwd)
    }

    @Test
    fun conversationUpdated_mapsPromotedBranch() {
        val element = MobileJson.parseToJsonElement(updatedFixture)
        val result = MobileJson.decodeFromJsonElement<ConversationResponseDto>(element).toConversation()

        assertEquals("c-promoted", result.id)
        assertEquals("weekly-planning", result.name)
        assertTrue(result.isPromoted)
        assertEquals("/Users/j/Projects/Planner", result.cwd)
        assertEquals(Instant.parse("2026-05-08T10:34:30Z"), result.lastUsedAt)
    }

    @Test
    fun absentDomainFields_useDocumentedDefaults() {
        val element = MobileJson.parseToJsonElement(updatedFixture)
        val mapped = MobileJson.decodeFromJsonElement<ConversationResponseDto>(element).toConversation()

        // The four fields the mutation-response wire does not carry are placeholders, never
        // null-punned (same rule as #316).
        assertEquals("", mapped.currentSessionId)
        assertEquals(emptyList<String>(), mapped.sessionHistory)
        assertFalse(mapped.isSleeping)
        assertFalse(mapped.archived)
    }

    @Test
    fun fieldIncompletePayload_throwsTypedDecodeFailure() {
        // Omits the required non-nullable `cwd`. Dropping `name` would NOT fail (decodes to
        // null), so a required field must be dropped for the test to be meaningful.
        val truncated =
            """{"id":"c-new","is_promoted":false,"name":null,"last_used_at":"2026-05-08T10:34:01Z"}"""
        val element = MobileJson.parseToJsonElement(truncated)

        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<ConversationResponseDto>(element)
        }
    }

    @Test
    fun malformedTimestamp_failsAtDecodeBoundary() {
        // A malformed RFC 3339 string is rejected at decode (proving timestamps validate at the
        // boundary), so the mapper never sees a partial object. InstantIso8601Serializer surfaces
        // this as IllegalArgumentException (kotlinx.datetime.DateTimeFormatException), NOT a
        // SerializationException.
        val badTs =
            """{"id":"c-new","is_promoted":false,"cwd":"/p","name":null,"last_used_at":"not-a-date"}"""
        val element = MobileJson.parseToJsonElement(badTs)

        assertThrows(IllegalArgumentException::class.java) {
            MobileJson.decodeFromJsonElement<ConversationResponseDto>(element)
        }
    }
}
