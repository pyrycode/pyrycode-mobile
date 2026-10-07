package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadReadClaimsTest {
    @Test fun receivedNonvisualTailExtendsOnlyContinuousUnderstoodEntries() {
        val visible = entry(11u, "message", """{"conversation_id":"c","message_id":"m","role":"user","text":"seen"}""")
        val state = entry(12u, "turn_state", """{"conversation_id":"c","state":"idle"}""")
        val banner = entry(13u, "banner", """{"conversation_id":"c","level":"info","text":"inert","truncated":false,"stops_turn":false}""")
        val reduced = reduceOrderedHistoryPage(listOf(banner, state, visible), true)
        val evidence = ThreadReadEvidence().received(listOf(banner, state, visible), reduced, reduced.rows)
        assertEquals(13uL, evidence.checkpoint(reduced.rows.first(), 0u))
        assertNull(evidence.checkpoint(reduced.rows.first(), 13u))
        for (barrier in listOf(
            entry(12u, "future_type", "{}"),
            entry(12u, "turn_state", "{}"),
            entry(12u, "turn_state", """{"conversation_id":"c","state":"future"}"""),
        )) {
            val page = reduceOrderedHistoryPage(listOf(banner, barrier, visible), true)
            val blocked = ThreadReadEvidence().received(listOf(banner, barrier, visible), page, page.rows)
            assertEquals(11uL, blocked.checkpoint(page.rows.first(), 0u))
        }
    }

    @Test fun oldPresentedStreamingVersionDoesNotAcknowledgeLaterUnseenDeltas() {
        val first = entry(21u, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":0,"text":"first"}""")
        val second = entry(22u, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":1,"text":" later"}""")
        val initial = reduceOrderedHistoryPage(listOf(first), true)
        val evidence = ThreadReadEvidence().received(listOf(first), initial, initial.rows)
        val update = reduceOrderedHistoryPage(listOf(second), true, initial.rows)
        val next = evidence.received(listOf(second), update, update.rows)
        assertEquals(21uL, evidence.checkpoint(initial.rows.single(), 0u))
        assertNull(next.checkpoint(initial.rows.single(), 0u))
        assertEquals(22uL, next.checkpoint(update.rows.single(), 0u))
        val duplicate = reduceOrderedHistoryPage(listOf(second), true, update.rows)
        assertEquals(22uL, next.received(listOf(second), duplicate, duplicate.rows).checkpoint(update.rows.single(), 0u))
    }

    @Test fun knownMalformedEntryAndReceivedGapBlockEvenLaterVisibleContent() {
        val first = entry(31u, "message", """{"conversation_id":"c","message_id":"a","role":"user","text":"old"}""")
        val last = entry(33u, "message", """{"conversation_id":"c","message_id":"b","role":"user","text":"new"}""")
        for (entries in listOf(listOf(last, first), listOf(last, entry(32u, "message", "{}"), first))) {
            val reduced = reduceOrderedHistoryPage(entries, true)
            assertNull(ThreadReadEvidence().received(entries, reduced, reduced.rows).checkpoint(reduced.rows.last(), 0u))
        }
    }

    @Test fun unidentifiedContentNeedsOrdinaryReceivedIdentityAndOffscreenRowsProveNoSight() {
        val entry = entry(41u, "message", """{"conversation_id":"c","message_id":"a","role":"user","text":"new"}""")
        val reduced = reduceOrderedHistoryPage(listOf(entry), true)
        val missing = ThreadReadEvidence(unidentified = setOf(Triple("message", entry.timestamp.toString(), entry.payload)))
        assertNull(missing.checkpoint(reduced.rows.single(), 0u))
        val resolved = missing.received(listOf(entry), reduced, reduced.rows)
        assertEquals(41uL, resolved.checkpoint(reduced.rows.single(), 0u))
        assertNull(
            resolved.checkpoint(
                ThreadItem.MessageItem((reduced.rows.single() as ThreadItem.MessageItem).message.copy(content = "offscreen other version")),
                0u,
            ),
        )
        val empty = reduceOrderedHistoryPage(emptyList(), true)
        assertTrue(ThreadReadEvidence().received(emptyList(), empty, emptyList()).facts.isEmpty())
    }

    @Test fun overlapOrderAndRepeatedPagesPreserveExactClaims() {
        val a = entry(51u, "message", """{"conversation_id":"c","message_id":"a","role":"user","text":"first"}""")
        val b = entry(52u, "message", """{"conversation_id":"c","message_id":"b","role":"user","text":"second"}""")
        val c = entry(53u, "message", """{"conversation_id":"c","message_id":"c","role":"user","text":"third"}""")
        for (page in listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, a, c), listOf(c, a, b))) {
            val reduced = reduceOrderedHistoryPage(page, true)
            val evidence = ThreadReadEvidence().received(page, reduced, reduced.rows)
            val newest = reduced.rows.filterIsInstance<ThreadItem.MessageItem>().single { it.message.id == "c" }
            assertEquals(53uL, evidence.checkpoint(newest, 0u))
            for (overlap in listOf(listOf(a), listOf(b), listOf(c), page)) {
                val replay = reduceOrderedHistoryPage(overlap, true, reduced.rows)
                val repeated = evidence.received(overlap, replay, replay.rows)
                assertEquals(53uL, repeated.checkpoint(newest, 0u))
                assertNull(repeated.checkpoint(newest, 53u))
            }
        }
    }

    @Test
    fun presentedToolUseDoesNotGrantUnseenToolResult() {
        val use =
            entry(
                61u,
                "tool_use",
                """{"conversation_id":"c","turn_id":"t","tool_use_id":"tool","name":"Read","input_summary":"a.kt"}""",
            )
        val result =
            entry(
                62u,
                "tool_result",
                """{"conversation_id":"c","turn_id":"t","tool_use_id":"tool","is_error":false,"result_summary":"done"}""",
            )
        val initial = reduceOrderedHistoryPage(listOf(use), true)
        val held = ThreadReadEvidence().received(listOf(use), initial, initial.rows)
        val updated = reduceOrderedHistoryPage(listOf(result), true, initial.rows)
        val next = held.received(listOf(result), updated, updated.rows)
        assertEquals(61uL, held.checkpoint(initial.rows.single(), 0u))
        assertNull(next.checkpoint(initial.rows.single(), 0u))
        assertEquals(62uL, next.checkpoint(updated.rows.single(), 0u))
    }

    private fun entry(
        id: ULong,
        type: String,
        payload: String,
    ) = HistoryEntry(
        unsignedId = id,
        type = type,
        payload = MobileJson.parseToJsonElement(payload),
        timestamp = Instant.parse("2026-10-07T00:00:00Z"),
    )

    @Test
    fun durableIdentitySurvivesEnvelopeWithoutUsingConnectionOrReplayIds() {
        val json =
            """{"id":7,"event_id":9,"history_entry_id":18446744073709551615,"type":"assistant_delta","ts":"2026-10-07T00:00:00Z",
            |"payload":{}}
            """.trimMargin()
        val decoded = MobileJson.decodeFromString(Envelope.serializer(), json)
        assertTrue(MobileJson.encodeToString(Envelope.serializer(), decoded).contains("\"history_entry_id\":18446744073709551615"))
    }

    @Test
    fun finalizedRowClaimsTheTurnEndVersionAsWellAsEveryDelta() {
        fun entry(
            id: ULong,
            type: String,
            payload: String,
        ) = HistoryEntry(
            unsignedId = id,
            type = type,
            payload = MobileJson.parseToJsonElement(payload),
            timestamp = Instant.parse("2026-10-07T00:00:00Z"),
        )
        val reduced =
            reduceOrderedHistoryPage(
                listOf(
                    entry(13u, "turn_end", """{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}"""),
                    entry(12u, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":1,"text":" world"}"""),
                    entry(11u, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":0,"text":"hello"}"""),
                ),
                true,
            )
        assertTrue(
            reduced.readClaims.values
                .flatten()
                .containsAll(listOf(11uL, 12uL, 13uL)),
        )
    }
}
