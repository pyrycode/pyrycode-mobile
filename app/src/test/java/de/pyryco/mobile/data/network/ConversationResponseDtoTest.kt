package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
    // named + promoted + bound cwd. Proves the single DTO decodes both wire orderings. Omits
    // is_archived deliberately (proving the field defaults to false when the key is absent).
    private val updatedFixture =
        """
        {"id":"c-promoted","is_promoted":true,"name":"weekly-planning","cwd":"/Users/j/Projects/Planner","last_used_at":"2026-05-08T10:34:30Z"}
        """.trimIndent()

    // A `conversation_updated` reply to `archive_conversation` (#549): carries is_archived:true.
    // pyrycode#881 added is_archived to ConversationUpdatedPayload (no omitempty → always present).
    private val archivedFixture =
        """
        {"id":"c-archived","is_promoted":true,"is_archived":true,"name":"weekly-planning","cwd":"/Users/j/Projects/Planner","last_used_at":"2026-05-08T10:34:30Z"}
        """.trimIndent()

    @Test
    fun workspaceLabel_createdAndUpdated_preserveStringsNullAndLegacyWithoutChangingOtherFields() {
        for (fixture in listOf(createdFixture, updatedFixture)) {
            val legacyPayload = MobileJson.parseToJsonElement(fixture).jsonObject
            val legacy = MobileJson.decodeFromJsonElement<ConversationResponseDto>(legacyPayload).toConversation()
            assertNull(legacy.workspaceLabel)
            for (label in listOf("  Työ 🛠 <b>workspace</b>  ", "", null)) {
                val payload = JsonObject(legacyPayload + ("workspace_label" to JsonPrimitive(label)))
                val mapped = MobileJson.decodeFromJsonElement<ConversationResponseDto>(payload).toConversation()
                assertEquals(legacy.copy(workspaceLabel = label), mapped)
            }
        }
    }

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

        // The three fields the mutation-response wire does not carry are placeholders, never
        // null-punned (same rule as #316). `archived` is no longer here — it is a decoded field
        // (#549), covered by the is_archived tests below.
        assertEquals("", mapped.currentSessionId)
        assertEquals(emptyList<String>(), mapped.sessionHistory)
        assertFalse(mapped.isSleeping)
    }

    @Test
    fun agent_carriedCodexMapsToCodexAndAnAbsentKeyToClaude() {
        val codex = MobileJson.parseToJsonElement(updatedFixture).jsonObject + ("agent" to JsonPrimitive("codex"))
        val record = MobileJson.decodeFromJsonElement<ConversationResponseDto>(JsonObject(codex))
        assertEquals("codex", record.agent)
        assertEquals(ConversationAgent.Codex, record.toConversation().agent)

        val absent = MobileJson.decodeFromJsonElement<ConversationResponseDto>(MobileJson.parseToJsonElement(updatedFixture))
        assertNull(absent.agent)
        assertEquals(ConversationAgent.Claude, absent.toConversation().agent)
    }

    @Test
    fun absentIsArchived_defaultsToFalse() {
        // updatedFixture omits is_archived (e.g. a conversation_created reply, #347, which pyrycode#881
        // did not extend). The defaulted field decodes to false — the correct non-archived state.
        val element = MobileJson.parseToJsonElement(updatedFixture)
        val mapped = MobileJson.decodeFromJsonElement<ConversationResponseDto>(element).toConversation()
        assertFalse(mapped.archived)
    }

    @Test
    fun isArchivedTrue_mapsToArchivedConversation() {
        // A conversation_updated reply to archive_conversation (#549): is_archived:true → archived=true.
        val element = MobileJson.parseToJsonElement(archivedFixture)
        val mapped = MobileJson.decodeFromJsonElement<ConversationResponseDto>(element).toConversation()
        assertTrue(mapped.archived)
    }

    @Test
    fun isArchivedFalse_explicitlyPresent_mapsToNotArchived() {
        // A conversation_updated reply to unarchive_conversation (#549): is_archived:false → archived=false.
        val fixture =
            """{"id":"c1","is_promoted":true,"is_archived":false,"name":"n","cwd":"/p","last_used_at":"2026-05-08T10:34:30Z"}"""
        val mapped =
            MobileJson.decodeFromJsonElement<ConversationResponseDto>(MobileJson.parseToJsonElement(fixture)).toConversation()
        assertFalse(mapped.archived)
    }

    @Test
    fun isMuted_presentValueMapsToMutedAndAbsentKeyReadsAsNotMuted() {
        // A conversation_updated record always carries is_muted; a record without the key (an older
        // daemon, or conversation_created) decodes to false and keeps the conversation notifying.
        fun mutedOf(fixture: String) =
            MobileJson.decodeFromJsonElement<ConversationResponseDto>(MobileJson.parseToJsonElement(fixture)).toConversation().muted
        val base = """{"id":"c1","is_promoted":true,"name":"n","cwd":"/p","last_used_at":"2026-05-08T10:34:30Z""""
        assertTrue(mutedOf("""$base,"is_muted":true}"""))
        assertFalse(mutedOf("""$base,"is_muted":false}"""))
        assertFalse(mutedOf("""$base}"""))
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
