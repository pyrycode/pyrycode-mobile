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
 * `conversations` payload (#316). JUnit4, mirroring MobileWireCodecTest.kt. Each scenario
 * builds a `JsonElement` via `MobileJson.parseToJsonElement` (mimicking `Envelope.payload`),
 * decodes through [MobileJson], then maps — exercising the real #312 consumer path.
 *
 * Decode-only: the phone never sends `conversations` (server → phone), so there is no
 * encode round-trip test (re-encoding a `name: null` row under `explicitNulls = false`
 * would omit the key and diverge from the Go wire).
 */
class ConversationsPayloadTest {
    // Object-wrapped array (server SSOT conversations_read.go, #273), two rows:
    // Row A — named, promoted, bound cwd. Row B — unnamed (name:null), unpromoted, scratch cwd.
    private val twoRowFixture =
        """
        {"conversations":[
          {"id":"c1","name":"kitchen-claw refactor","is_promoted":true,"cwd":"/Users/j/Projects/KitchenClaw","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
          {"id":"c2","name":null,"is_promoted":false,"cwd":"~/.pyrycode/scratch","last_message_ts":"2026-05-08T09:14:11Z","last_used_at":"2026-05-08T09:14:11Z"}
        ]}
        """.trimIndent()

    @Test
    fun conversationsPayload_mapsBothBranchesPreservingOrder() {
        val element = MobileJson.parseToJsonElement(twoRowFixture)
        val payload = MobileJson.decodeFromJsonElement<ConversationsPayload>(element)

        val result = payload.toConversations()

        assertEquals(2, result.size)

        val rowA = result[0]
        assertEquals("c1", rowA.id)
        assertEquals("kitchen-claw refactor", rowA.name)
        assertTrue(rowA.isPromoted)
        assertEquals("/Users/j/Projects/KitchenClaw", rowA.cwd)
        assertEquals(Instant.parse("2026-05-08T10:31:02Z"), rowA.lastUsedAt)

        // Unnamed-scratch branch.
        val rowB = result[1]
        assertEquals("c2", rowB.id)
        assertNull(rowB.name)
        assertFalse(rowB.isPromoted)
        assertEquals(Instant.parse("2026-05-08T09:14:11Z"), rowB.lastUsedAt)
    }

    @Test
    fun scratchCwd_isPreservedVerbatim() {
        val element = MobileJson.parseToJsonElement(twoRowFixture)
        val result = MobileJson.decodeFromJsonElement<ConversationsPayload>(element).toConversations()

        // DEFAULT_SCRATCH_CWD survives unchanged (not blanked/normalized). Reference the
        // constant rather than a literal so a sentinel change can't silently pass.
        assertEquals(DEFAULT_SCRATCH_CWD, result[1].cwd)
    }

    @Test
    fun absentDomainFields_useDocumentedDefaults() {
        val element = MobileJson.parseToJsonElement(twoRowFixture)
        val mapped =
            MobileJson.decodeFromJsonElement<ConversationsPayload>(element).toConversations()[0]

        // The four fields the wire summary does not carry are list-tier placeholders,
        // never null-punned.
        assertEquals("", mapped.currentSessionId)
        assertEquals(emptyList<String>(), mapped.sessionHistory)
        assertFalse(mapped.isSleeping)
        assertFalse(mapped.archived)
    }

    @Test
    fun fieldIncompletePayload_throwsTypedDecodeFailure() {
        // The row omits the required non-nullable `cwd`. Dropping `name` would NOT fail
        // (decodes to null), so a required field must be dropped for the test to be meaningful.
        val truncated =
            """{"conversations":[{"id":"c1","name":"x","is_promoted":true,""" +
                """"last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"}]}"""
        val element = MobileJson.parseToJsonElement(truncated)

        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<ConversationsPayload>(element)
        }
    }

    @Test
    fun malformedTimestamp_failsAtDecodeBoundary() {
        // A malformed RFC 3339 string is rejected at decode (proving timestamps validate at
        // the boundary), so the mapper never sees a partial row. InstantIso8601Serializer
        // surfaces this as IllegalArgumentException (kotlinx.datetime.DateTimeFormatException).
        val badTs =
            """{"conversations":[{"id":"c1","name":"x","is_promoted":true,"cwd":"/p",""" +
                """"last_message_ts":"not-a-date","last_used_at":"2026-05-08T10:31:02Z"}]}"""
        val element = MobileJson.parseToJsonElement(badTs)

        assertThrows(IllegalArgumentException::class.java) {
            MobileJson.decodeFromJsonElement<ConversationsPayload>(element)
        }
    }
}
