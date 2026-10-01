package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.ConversationAgent
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
    fun workspaceLabels_preserveEachRowsExactPairIncludingArchivedNullAndLegacy() {
        val fixture =
            """
            {"conversations":[
              {"id":"active","name":"Channel","is_promoted":true,"cwd":" /work/../A ","workspace_label":"  Työ 🛠 <b>A</b>  ","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"archived","name":null,"is_promoted":false,"is_archived":true,"cwd":"/work/B","workspace_label":"Archive B","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"null","name":null,"is_promoted":false,"cwd":"/work/C","workspace_label":null,"last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"legacy","name":null,"is_promoted":false,"cwd":"~/.pyrycode/scratch","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"}
            ]}
            """.trimIndent()
        val rows = MobileJson.decodeFromJsonElement<ConversationsPayload>(MobileJson.parseToJsonElement(fixture)).toConversations()

        assertEquals(listOf("active", "archived", "null", "legacy"), rows.map { it.id })
        assertEquals(
            listOf(" /work/../A " to "  Työ 🛠 <b>A</b>  ", "/work/B" to "Archive B", "/work/C" to null, DEFAULT_SCRATCH_CWD to null),
            rows.map { it.cwd to it.workspaceLabel },
        )
        assertEquals(listOf(false, true, false, false), rows.map { it.archived })
    }

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
    fun archivedListRows_preserveTheServerFlagAcrossRefresh() {
        val fixture = twoRowFixture.replace("\"is_promoted\":true", "\"is_promoted\":true,\"is_archived\":true")
        val rows = MobileJson.decodeFromJsonElement<ConversationsPayload>(MobileJson.parseToJsonElement(fixture)).toConversations()

        assertTrue(rows[0].archived)
        assertFalse(rows[1].archived)
    }

    @Test
    fun mutedListRows_carryTheServerFlagAndAbsentKeyReadsAsNotMuted() {
        // Row A carries is_muted:true; row B omits the key, as an older daemon does, and stays notifying.
        val fixture = twoRowFixture.replace("\"is_promoted\":true", "\"is_promoted\":true,\"is_muted\":true")
        val rows = MobileJson.decodeFromJsonElement<ConversationsPayload>(MobileJson.parseToJsonElement(fixture)).toConversations()

        assertTrue(rows[0].muted)
        assertFalse(rows[1].muted)
    }

    @Test
    fun agent_readsCodexOnlyForCodexAndClaudeForAnyOtherValueOrAnAbsentKey() {
        val fixture =
            """
            {"conversations":[
              {"id":"codex","name":null,"is_promoted":false,"cwd":"/p","agent":"codex","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"claude","name":null,"is_promoted":false,"cwd":"/p","agent":"claude","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"unknown","name":null,"is_promoted":false,"cwd":"/p","agent":"gemini","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"absent","name":null,"is_promoted":false,"cwd":"/p","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"}
            ]}
            """.trimIndent()
        val rows = MobileJson.decodeFromJsonElement<ConversationsPayload>(MobileJson.parseToJsonElement(fixture)).toConversations()

        assertEquals(
            listOf(ConversationAgent.Codex, ConversationAgent.Claude, ConversationAgent.Claude, ConversationAgent.Claude),
            rows.map { it.agent },
        )
    }

    @Test
    fun archivedAt_readsAValidTimeAndNullsAMissingNullOrUnparsableOneKeepingTheRow() {
        val fixture =
            """
            {"conversations":[
              {"id":"valid","name":"A","is_promoted":false,"is_archived":true,"cwd":"/p","archived_at":"2026-06-10T08:15:30.123456789Z","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"missing","name":"B","is_promoted":false,"is_archived":true,"cwd":"/p","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"null","name":"C","is_promoted":false,"cwd":"/p","archived_at":null,"last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
              {"id":"garbage","name":"D","is_promoted":true,"is_archived":true,"cwd":"/q","archived_at":"yesterday","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-07T09:00:00Z"},
              {"id":"out-of-range","name":"E","is_promoted":false,"is_archived":true,"cwd":"/p","archived_at":"+9999999999-01-01T00:00:00Z","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"}
            ]}
            """.trimIndent()
        val rows = MobileJson.decodeFromJsonElement<ConversationsPayload>(MobileJson.parseToJsonElement(fixture)).toConversations()

        assertEquals(listOf("valid", "missing", "null", "garbage", "out-of-range"), rows.map { it.id })
        assertEquals(
            listOf(Instant.parse("2026-06-10T08:15:30.123456789Z"), null, null, null, null),
            rows.map { it.archivedAt },
        )
        // An unparsable stamp costs only the stamp: the rest of the row is read as usual.
        val garbage = rows[3]
        assertEquals("D", garbage.name)
        assertTrue(garbage.isPromoted)
        assertTrue(garbage.archived)
        assertEquals("/q", garbage.cwd)
        assertEquals(Instant.parse("2026-05-07T09:00:00Z"), garbage.lastUsedAt)
    }

    @Test
    fun archivedAt_nonStringValueFailsTheListDecode() {
        // A number is not "a string that does not parse": like a malformed `last_used_at`, it fails the
        // whole payload, which `applySnapshot` drops through its IllegalArgumentException catch.
        val numeric =
            """{"conversations":[{"id":"c1","name":"x","is_promoted":false,"is_archived":true,"cwd":"/p",""" +
                """"archived_at":1717000000,"last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"}]}"""
        val element = MobileJson.parseToJsonElement(numeric)

        assertThrows(IllegalArgumentException::class.java) {
            MobileJson.decodeFromJsonElement<ConversationsPayload>(element)
        }
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

        // Session and sleep fields remain list-tier placeholders. Older summaries
        // without is_archived retain the active default,
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
