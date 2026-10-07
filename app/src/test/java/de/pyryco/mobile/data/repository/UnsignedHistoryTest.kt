package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class UnsignedHistoryTest {
    private val boundary = Long.MAX_VALUE.toULong()
    private val ids = listOf(1uL, boundary - 1u, boundary, boundary + 1u, ULong.MAX_VALUE)
    private val at = Instant.parse("2026-10-07T00:00:00Z")

    @Test
    fun compatibility_usesExactPositiveSignedIdentityOrNoClaim() {
        for (id in ids) {
            val unsigned = user(id)
            assertEquals(id, unsigned.unsignedId)
            if (id <= boundary) {
                val signed = HistoryEntry(id.toLong(), unsigned.type, unsigned.payload, unsigned.timestamp)
                assertEquals(unsigned, signed)
                assertEquals(id.toLong(), unsigned.id)
            } else {
                assertNull(unsigned.id)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { user(0u) }
        assertThrows(IllegalArgumentException::class.java) { HistoryEntry(-1L, "future", MobileJson.parseToJsonElement("{}"), at) }
    }

    @Test
    fun unsignedOrder_isStableForEveryDisjointPageArrivalOrderAndReplay() =
        runTest {
            for (arrival in permutations(ids)) {
                val projection = ThreadProjection()
                for (id in arrival) {
                    projection.mergeHistoryPage(
                        "c",
                        page(user(id, timestamp = Instant.fromEpochSeconds((ids.size - ids.indexOf(id)).toLong()))),
                        true,
                    )
                    val expected = ids.filter { it in arrival.take(arrival.indexOf(id) + 1) }
                    assertEquals(expected.map(::name), projection.observe("c").first().names())
                }
                val before = projection.observeSnapshot("c").first()
                projection.mergeHistoryPage("c", page(*ids.map { user(it) }.toTypedArray()), true)
                projection.mergeHistoryPage("c", page(), true)
                assertEquals(before, projection.observeSnapshot("c").first())
                assertEquals(ids.toSet(), before.unsignedHistoryOrder.values.toSet())
                assertEquals(setOf(1L, Long.MAX_VALUE - 1, Long.MAX_VALUE), before.historyOrder.values.toSet())
            }
        }

    @Test
    fun overlap_preservesHeldRowsOnBothSidesForStartMiddleAndEndPages() =
        runTest {
            val entries = ids.map { user(it) }
            for (heldIndex in entries.indices) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage("c", page(entries[heldIndex]), true)
                val held = projection.observe("c").first().single()
                val pages = listOf(entries.take(3), entries.drop(1).take(3), entries.takeLast(3))
                for (incoming in pages + pages.reversed()) {
                    projection.mergeHistoryPage("c", page(*incoming.toTypedArray()), true)
                }
                val rows = projection.observe("c").first()
                assertEquals(ids.map(::name), rows.names())
                assertSame(held, rows[heldIndex])
                assertEquals(rows.names().distinct(), rows.names())
            }
        }

    @Test
    fun foldedDeltas_keepUnsignedClaimsAndOneSequenceAcrossOverlap() =
        runTest {
            val entries =
                listOf(delta(boundary - 1u, 0, "a"), delta(boundary, 1, "b"), user(boundary + 1u), delta(ULong.MAX_VALUE - 1u, 2, "c"))
            val reduced = reduceOrderedHistoryPage(page(*entries.toTypedArray()).entries, true)
            assertEquals(setOf(boundary - 1u), reduced.unsignedClaims[listOf("delta", "t", 0)])
            assertEquals(setOf(boundary), reduced.unsignedClaims[listOf("delta", "t", 1)])
            assertEquals(setOf(ULong.MAX_VALUE - 1u), reduced.unsignedClaims[listOf("delta", "t", 2)])
            assertFalse(reduced.order.containsKey(listOf("delta", "t", 2)))
            assertFalse(reduced.claims.containsKey(listOf("delta", "t", 2)))
            for (incoming in listOf(entries, entries.reversed())) {
                val projection = ThreadProjection()
                projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
                // Establish the held live delta's durable slot before surrounding pages can join it.
                projection.mergeHistoryPage("c", page(entries[1]), true)
                for (entry in incoming) projection.mergeHistoryPage("c", page(entry), true)
                repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                val snapshot = projection.observeSnapshot("c").first()
                val messages = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
                assertEquals(listOf("ab", name(boundary + 1u), "c"), messages.map { it.content })
                assertEquals(
                    listOf(0, 1, 2),
                    messages.flatMap {
                        it.segment
                            ?.deltas
                            .orEmpty()
                            .map { it.seq }
                    },
                )
                assertEquals(ULong.MAX_VALUE - 1u, snapshot.unsignedHistoryOrder[listOf("delta", "t", 2)])
                assertFalse(snapshot.historyOrder.containsKey(listOf("delta", "t", 2)))
            }
        }

    @Ignore("blocked on #1913: late durable evidence strands a held live delta across its history separator")
    @Test
    fun lateDurableEvidence_doesNotStrandLiveDeltaBeyondItsHistorySeparator() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
            val entries = listOf(delta(1u, 0, "a"), delta(2u, 1, "b"), user(3u), delta(4u, 2, "c"))
            for (entry in entries.reversed()) projection.mergeHistoryPage("c", page(entry), true)
            repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
            assertEquals(
                listOf("ab", name(3u), "c"),
                projection.observe("c").first().filterIsInstance<ThreadItem.MessageItem>().map {
                    it.message.content
                },
            )
        }

    @Test
    fun duplicateWithinPage_producesOneLogicalDeltaAndClaim() {
        val entry = delta(ULong.MAX_VALUE, 0, "a")
        val reduced = reduceOrderedHistoryPage(listOf(entry, entry), true)
        assertEquals(1, reduced.rows.size)
        assertEquals("a", (reduced.rows.single() as ThreadItem.MessageItem).message.content)
        assertEquals(mapOf(listOf("delta", "t", 0) to setOf(ULong.MAX_VALUE)), reduced.unsignedClaims)
        assertTrue(reduced.order.isEmpty())
        assertTrue(reduced.claims.isEmpty())
    }

    @Test
    fun compactionIdentityTransfer_preservesUnsignedFallingEdgeOrder() =
        runTest {
            val rise = entry(boundary, "compacting", """{"conversation_id":"c","active":true}""")
            val fall = entry(boundary + 1u, "compacting", """{"conversation_id":"c","active":false}""")
            val payload = """{"conversation_id":"c","pre_tokens":10,"post_tokens":5,"trigger":"auto"}"""
            val fill = entry(ULong.MAX_VALUE, "compaction_boundary", payload)
            val reduced = reduceOrderedHistoryPage(page(rise, fall, fill).entries, true)
            val identity = reduced.rows.single().mergeIdentity()
            assertEquals(boundary + 1u, reduced.unsignedOrder[identity])
            assertEquals(setOf(boundary + 1u, ULong.MAX_VALUE), reduced.unsignedClaims[identity])
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(rise, fall), true)
            projection.applyCompactionBoundary(
                de.pyryco.mobile.data.network
                    .Envelope(1L, "compaction_boundary", at.toString(), fill.payload),
            )
            val snapshot = projection.observeSnapshot("c").first()
            assertEquals(boundary + 1u, snapshot.unsignedHistoryOrder[snapshot.rows.single().mergeIdentity()])
            assertTrue(snapshot.historyOrder.isEmpty())
        }

    @Test
    fun identityScope_survivesReconnectWithoutBorrowingClaimsFromLiveRows() =
        runTest {
            for (id in ids) {
                val hostA = ThreadProjection()
                val hostB = ThreadProjection()
                hostA.mergeHistoryPage("c", page(user(id, "same-row")), true)
                hostA.mergeHistoryPage("other", page(user(1u, "same-row")), true)
                hostB.mergeHistoryPage("c", page(user(2u, "same-row")), true)
                assertEquals(
                    listOf(id),
                    hostA
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                assertEquals(
                    listOf(1uL),
                    hostA
                        .observeSnapshot("other")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                assertEquals(
                    listOf(2uL),
                    hostB
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                hostA.remove("c")
                assertTrue(
                    hostA
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                val reconnected = ThreadProjection()
                reconnected.appendMessages(listOf("c" to Message("same-row", "s", Role.User, "held", at, false)))
                assertTrue(
                    reconnected
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                repeat(2) { reconnected.mergeHistoryPage("c", page(user(id, "same-row")), true) }
                val snapshot = reconnected.observeSnapshot("c").first()
                assertEquals(listOf("same-row"), snapshot.rows.names())
                assertEquals("held", (snapshot.rows.single() as ThreadItem.MessageItem).message.content)
                assertEquals(listOf(id), snapshot.unsignedHistoryOrder.values.toList())
            }
        }

    @Test
    fun signedCoverage_declinesUpperRangeTerminalAndMixedPagesWithoutFalseCompleteness() {
        for (previous in listOf(HistoryCoverage(), HistoryCoverage().received(page(user(1u)).copy(atStart = true)))) {
            for (entries in listOf(listOf(user(ULong.MAX_VALUE)), listOf(user(2u), user(boundary + 1u)))) {
                val incoming = page(*entries.toTypedArray()).copy(cursor = "upper", atStart = true)
                val incomplete = previous.received(incoming, newest = true, target = 1L)
                assertEquals(previous.copy(unknown = true, unsignedIncomplete = true), incomplete)
                assertTrue(incomplete.received(page(user(2u)).copy(atStart = true)).unknown)
            }
        }
        val previous = HistoryCoverage(unknown = true).received(page(user(1u)))
        val lower = previous.received(page(user(2u)).copy(atStart = true))
        assertEquals(listOf(HistorySpan(1L, 2L)), lower.spans)
        assertFalse(lower.unknown)
    }

    @Test
    fun signedMergeEntryPoint_retainsLowerRangeOrdering() {
        val older = reduceOrderedHistoryPage(page(user(1u)).entries, true)
        val newer = reduceOrderedHistoryPage(page(user(3u)).entries, true)
        val middle = reduceOrderedHistoryPage(page(user(2u)).entries, true)
        val held = older.rows + newer.rows
        assertEquals(
            listOf(name(1u), name(2u), name(3u)),
            held
                .mergeOrderedHistoryRows(
                    middle.rows,
                    older.order + middle.order + newer.order,
                ).names(),
        )
    }

    @Test
    fun collidingRendererKeys_doNotInventDeltaClaimsOrLoseUnsignedToolOrder() =
        runTest {
            val entries =
                listOf(
                    delta(boundary, 0, "a"),
                    entry(
                        boundary + 1u,
                        "tool_use",
                        """
                        {"conversation_id":"c","turn_id":"t","tool_use_id":"tool","name":"Bash","input_summary":"ls"}
                        """.trimIndent(),
                    ),
                    delta(ULong.MAX_VALUE - 1u, 1, "b"),
                    user(ULong.MAX_VALUE, "t#1"),
                )
            val projection = ThreadProjection()
            // Splitting held text at a later tool row must repair its colliding renderer key.
            projection.mergeHistoryPage("c", page(entries[0], entries[2], entries[3]), true)
            repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
            val snapshot = projection.observeSnapshot("c").first()
            val messages = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
            assertEquals(listOf("a", "Bash", "b", "t#1"), messages.map { it.content })
            assertEquals(messages.map { it.id }.distinct(), messages.map { it.id })
            assertEquals(ULong.MAX_VALUE - 1u, snapshot.unsignedHistoryOrder[listOf("delta", "t", 1)])
            assertEquals(boundary + 1u, snapshot.unsignedHistoryOrder[listOf("message", "tool")])
            assertEquals(ULong.MAX_VALUE, snapshot.unsignedHistoryOrder[listOf("message", "t#1")])
            assertEquals(setOf(Long.MAX_VALUE), snapshot.historyOrder.values.toSet())
        }

    @Test
    fun unrecognizedHistoryRows_useExactUnsignedKeyAndDeduplicateReplay() =
        runTest {
            val payload = """{"conversation_id":"c","site":"undecodable","message_type":"","raw":"inert","truncated":false}"""
            val entry = entry(ULong.MAX_VALUE, "unrecognized_message", payload)
            val projection = ThreadProjection()
            repeat(2) { projection.mergeHistoryPage("c", page(entry), true) }
            val rows = projection.observe("c").first()
            assertEquals("history-18446744073709551615", (rows.single() as ThreadItem.UnrecognizedMessage).id)
        }

    private fun user(
        id: ULong,
        name: String = name(id),
        timestamp: Instant = at,
    ): HistoryEntry = entry(id, "message", """{"conversation_id":"c","message_id":"$name","role":"user","text":"$name"}""", timestamp)

    private fun delta(
        id: ULong,
        seq: Int,
        text: String,
    ): HistoryEntry = entry(id, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":$seq,"text":"$text"}""")

    private fun entry(
        id: ULong,
        type: String,
        payload: String,
        timestamp: Instant = at,
    ): HistoryEntry = HistoryEntry(unsignedId = id, type = type, payload = MobileJson.parseToJsonElement(payload), timestamp = timestamp)

    private fun page(vararg entries: HistoryEntry): HistoryPage = HistoryPage(entries.sortedByDescending { it.unsignedId }, "cursor", false)

    private fun name(id: ULong): String = "m$id"

    private fun List<ThreadItem>.names() = filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { first -> permutations(values - first).map { listOf(first) + it } }
}
