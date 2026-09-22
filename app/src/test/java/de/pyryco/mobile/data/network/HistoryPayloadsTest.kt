package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 conversation-history frames (#623). JUnit4, mirroring
 * RecentWorkspacesPayloadsTest.kt. Wire SSOT: `../pyrycode/docs/protocol-mobile.md`
 * § *Conversation history (v2)*; the JSON literals below copy the shapes the daemon commits under
 * `internal/protocol/testdata/` (daemon `main` at `43a52426`) rather than reading those files.
 *
 * This class owns the **page-shape** coverage for the ticket. RemoteConversationRepositoryTest owns
 * the wire round-trip and the failure routing and deliberately does not re-assert these shapes.
 */
class HistoryPayloadsTest {
    // AC #1: the request names the conversation, the verbatim cursor and the limit, under the wire's
    // snake_case keys. All three are always present — a first ask sends `""`/`0`, and neither is
    // elided (an omitted `limit` and a sent `0` mean the same thing, but the frame carries the key).
    @Test
    fun request_encodesAllThreeKeys_includingEmptyCursorAndZeroLimit() {
        val element =
            MobileJson.encodeToJsonElement(
                RequestHistoryPayloadDto(conversationId = CONVERSATION_ID, cursor = "", limit = 0),
            )

        assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"$CONVERSATION_ID","cursor":"","limit":0}"""), element)
    }

    // AC #1: a non-empty cursor rides back verbatim — never re-encoded, re-cased or trimmed.
    @Test
    fun request_carriesCursorVerbatim() {
        val element =
            MobileJson.encodeToJsonElement(
                RequestHistoryPayloadDto(conversationId = CONVERSATION_ID, cursor = OPAQUE_CURSOR, limit = 50),
            )

        assertEquals(JsonPrimitive(OPAQUE_CURSOR), (element as JsonObject)["cursor"])
    }

    // AC #2/#3, shape ① (`history_page.json`): entries with a usable cursor and `at_start` false.
    // The two entries are asserted in WIRE ORDER (newest first, ids descending) — the client never
    // re-sorts — with each entry's id, type, payload and timestamp preserved.
    @Test
    fun page_withEntries_preservesNewestFirstOrderAndCarriesUsableCursor() {
        val page = decodePage(PAGE_WITH_ENTRIES)

        assertEquals(listOf(412L, 411L), page.entries.map { it.id })
        assertEquals(listOf("assistant_delta", "send_message"), page.entries.map { it.type })
        assertEquals(Instant.parse("2026-09-05T10:58:12Z"), page.entries.first().timestamp)
        assertEquals(
            JsonPrimitive("…and that is the last step."),
            (page.entries.first().payload as JsonObject)["text"],
        )
        assertEquals(OPAQUE_CURSOR, page.cursor)
        assertFalse(page.atStart)
    }

    // AC #3, shape ② (`history_page_at_start_entries.json`): a page that ran out while filling carries
    // entries AND terminates — empty cursor, `at_start` true. The pair is the whole point: a client
    // that stopped on an empty `entries` list would miss this page's single entry.
    @Test
    fun page_atStartWithEntries_carriesEntriesAndEmptyCursor() {
        val page = decodePage(PAGE_AT_START_WITH_ENTRIES)

        assertEquals(listOf(1L), page.entries.map { it.id })
        assertEquals("", page.cursor)
        assertTrue(page.atStart)
    }

    // AC #3, shape ③ (`history_page_at_start.json`): the terminal page of a walk whose previous page
    // filled exactly at the log's first entry — no entries, empty cursor, `at_start` true.
    @Test
    fun page_atStartWithoutEntries_isTheTerminalShape() {
        val page = decodePage(PAGE_AT_START_EMPTY)

        assertEquals(emptyList<HistoryEntry>(), page.entries)
        assertEquals("", page.cursor)
        assertTrue(page.atStart)
    }

    // AC #3: `at_start` is the ONLY end-of-log signal — a short page (fewer entries than asked for,
    // because the daemon narrowed the page to fit its envelope cap) says nothing about the start of
    // the log. Decoding must surface `at_start` false here, not infer termination from the count.
    @Test
    fun page_shortButNotAtStart_doesNotReadAsStartOfLog() {
        val page = decodePage(PAGE_SHORT_NOT_AT_START)

        assertEquals(1, page.entries.size)
        assertFalse(page.atStart)
        assertEquals(OPAQUE_CURSOR, page.cursor)
    }

    // AC #4: an entry whose `type` this app does not recognise is carried through verbatim — not
    // dropped, not coerced, not a decode failure. `type` is a stored string nothing re-validates, so
    // tolerating it is the wire's forward-compatibility rule, and the unknown payload rides along.
    @Test
    fun page_withUnrecognizedEntryType_carriesItThrough() {
        val page = decodePage(PAGE_UNKNOWN_TYPE)

        assertEquals(listOf("some_future_frame", "send_message"), page.entries.map { it.type })
        assertEquals(
            JsonPrimitive("opaque"),
            (page.entries.first().payload as JsonObject)["whatever"],
        )
    }

    // AC #2: a history entry's `id` is the durable log id and is NEVER joined to a replay `event_id`.
    // The entry below carries both; the decode reads `id` and leaves `event_id` as an unknown key the
    // DTO does not model, so the two sequences cannot be conflated downstream.
    @Test
    fun entry_withEventIdPresent_readsIdNotEventId() {
        val page = decodePage(PAGE_ENTRY_WITH_EVENT_ID)

        assertEquals(412L, page.entries.single().id)
    }

    // Forward-compat proof for `ignoreUnknownKeys` at both levels: a future top-level page field and a
    // future per-entry field still decode.
    @Test
    fun page_withUnknownFields_stillDecodes() {
        val page = decodePage(PAGE_UNKNOWN_FIELDS)

        assertEquals(listOf(412L), page.entries.map { it.id })
        assertFalse(page.atStart)
    }

    // A missing `at_start` must be a decode failure, never a silent `false`: a defaulted absence would
    // read as "keep walking" on a page that said nothing about the end of the log. Same posture for
    // the other two required keys — the wire emits all three (no omitempty), so absence is malformed.
    @Test
    fun page_missingRequiredKeys_failsToDecode() {
        assertThrows(SerializationException::class.java) {
            decodePage("""{"entries":[],"cursor":""}""")
        }
        assertThrows(SerializationException::class.java) {
            decodePage("""{"cursor":"","at_start":true}""")
        }
        assertThrows(SerializationException::class.java) {
            decodePage("""{"entries":[],"at_start":true}""")
        }
    }

    // An entry missing a required field is the same malformed-page failure — nothing is coerced.
    @Test
    fun entry_missingRequiredField_failsToDecode() {
        assertThrows(SerializationException::class.java) {
            decodePage("""{"entries":[{"id":1,"type":"send_message","ts":"$TS"}],"cursor":"","at_start":true}""")
        }
    }

    // The one failure site after a successful structural decode: `ts` is parsed to an Instant, so a
    // malformed timestamp throws IllegalArgumentException (kotlinx-datetime) instead of producing an
    // entry with a punned or defaulted time. Mirrors MessagePayloadDto.toMessage.
    @Test
    fun entry_malformedTimestamp_throwsAtMapping() {
        assertThrows(IllegalArgumentException::class.java) {
            decodePage("""{"entries":[{"id":1,"type":"send_message","payload":{},"ts":"not-a-time"}],"cursor":"","at_start":true}""")
        }
    }

    private fun decodePage(raw: String): HistoryPage =
        MobileJson
            .decodeFromJsonElement<HistoryPagePayloadDto>(MobileJson.parseToJsonElement(raw))
            .toHistoryPage()

    private companion object {
        const val CONVERSATION_ID = "3f8b1c04-9d27-4e5a-b6c1-2e9f70d8a413"
        const val OPAQUE_CURSOR = "MS4zZjhiMWMwNC05ZDI3LTRlNWEtYjZjMS0yZTlmNzBkOGE0MTMuNy40MDk2"
        const val TS = "2026-09-05T10:58:12Z"

        val PAGE_WITH_ENTRIES =
            """
            {"entries":[
              {"id":412,"type":"assistant_delta","payload":{"conversation_id":"$CONVERSATION_ID","text":"…and that is the last step."},"ts":"2026-09-05T10:58:12Z"},
              {"id":411,"type":"send_message","payload":{"conversation_id":"$CONVERSATION_ID","text":"walk me through the deploy"},"ts":"2026-09-05T10:57:03Z"}
            ],"cursor":"$OPAQUE_CURSOR","at_start":false}
            """.trimIndent()

        val PAGE_AT_START_WITH_ENTRIES =
            """
            {"entries":[
              {"id":1,"type":"send_message","payload":{"conversation_id":"$CONVERSATION_ID","text":"first message of the conversation"},"ts":"2026-09-04T08:00:00Z"}
            ],"cursor":"","at_start":true}
            """.trimIndent()

        const val PAGE_AT_START_EMPTY = """{"entries":[],"cursor":"","at_start":true}"""

        val PAGE_SHORT_NOT_AT_START =
            """{"entries":[{"id":412,"type":"send_message","payload":{},"ts":"$TS"}],"cursor":"$OPAQUE_CURSOR","at_start":false}"""

        val PAGE_UNKNOWN_TYPE =
            """
            {"entries":[
              {"id":9,"type":"some_future_frame","payload":{"whatever":"opaque"},"ts":"$TS"},
              {"id":8,"type":"send_message","payload":{},"ts":"$TS"}
            ],"cursor":"$OPAQUE_CURSOR","at_start":false}
            """.trimIndent()

        val PAGE_ENTRY_WITH_EVENT_ID =
            """{"entries":[{"id":412,"event_id":7,"type":"send_message","payload":{},"ts":"$TS"}],"cursor":"","at_start":true}"""

        val PAGE_UNKNOWN_FIELDS =
            """{"entries":[{"id":412,"type":"send_message","payload":{},"ts":"$TS","future":1}],"cursor":"$OPAQUE_CURSOR","at_start":false,"total":9}"""
    }
}
