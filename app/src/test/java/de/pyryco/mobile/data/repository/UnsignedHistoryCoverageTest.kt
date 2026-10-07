package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlin.ExperimentalUnsignedTypes::class)
class UnsignedHistoryCoverageTest {
    private val boundary = Long.MAX_VALUE.toULong()

    private fun entry(
        id: ULong,
        type: String = "future",
        payload: String = "{}",
    ) = HistoryEntry(
        type = type,
        payload = MobileJson.parseToJsonElement(payload),
        timestamp = Instant.fromEpochSeconds(1),
        unsignedId = id,
    )

    private fun page(
        vararg ids: ULong,
        cursor: String = "older",
        atStart: Boolean = false,
    ) = HistoryPage(
        ids.reversed().map {
            entry(it)
        },
        cursor,
        atStart,
    )

    private fun restore(state: HistoryCoverage) = MobileJson.decodeFromString<HistoryCoverage>(MobileJson.encodeToString(state))

    @Test fun markerInvariant_assistantFragmentsUseUnsignedBoundariesAndRejoinOnlyOnContinuousReceipt() {
        for (anchor in listOf(boundary, ULong.MAX_VALUE - 2u)) {
            val row =
                ThreadItem.MessageItem(
                    Message(
                        "t",
                        "s",
                        Role.Assistant,
                        "ac",
                        Instant.fromEpochSeconds(1),
                        false,
                        segment =
                            AssistantSegment(
                                "t",
                                listOf(
                                    SegmentDelta(0, 1),
                                    SegmentDelta(2, 1),
                                ),
                            ),
                    ),
                )
            val state =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(anchor, anchor), UnsignedHistorySpan(anchor + 2u, anchor + 2u)),
                    unsignedGaps = listOf(UnsignedHistoryGap(anchor, anchor + 2u)),
                    unsignedRowOrder = row.historyKeys().zip(listOf(anchor, anchor + 2u)).toMap(),
                )
            val fragments = state.displayRows(listOf(row)).filterIsInstance<ThreadItem.MessageItem>()
            assertEquals(listOf("a", "c"), fragments.map { it.message.content })
            assertEquals(2, fragments.map { it.message.id }.distinct().size)
            assertEquals(
                listOf(
                    "a",
                    "c",
                ),
                restore(state.received(page(anchor))).displayRows(listOf(row)).filterIsInstance<ThreadItem.MessageItem>().map {
                    it.message.content
                },
            )
            assertEquals(listOf(row), restore(state.received(page(anchor + 1u))).displayRows(listOf(row)))
            assertEquals("ac", row.message.content)
        }
    }

    @Test fun receivedIdentityInvariant_maximumSurvivesCoverageSerialization() {
        val encoded = MobileJson.encodeToString(HistoryCoverage().received(page(ULong.MAX_VALUE)))
        assertTrue("coverage must retain the exact received uint64 identity", encoded.contains(ULong.MAX_VALUE.toString()))
        assertEquals(ULong.MAX_VALUE, restore(HistoryCoverage().received(page(ULong.MAX_VALUE))).unsignedHighWater)
    }

    @Test fun adjacencyInvariant_boundaryAndMaximumMergeInEitherArrivalOrderWithoutOverflow() {
        for (ids in listOf(listOf(boundary - 1u, boundary, boundary + 1u, boundary + 2u), listOf(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE))) {
            for (order in listOf(ids, ids.reversed())) {
                val state = order.fold(HistoryCoverage()) { state, id -> restore(state.received(page(id)).received(page(id))) }
                assertEquals(listOf(UnsignedHistorySpan(ids.first(), ids.last())), state.unsignedSpans)
                assertTrue(state.unsignedGaps.isEmpty())
                assertEquals(ids.last(), state.unsignedHighWater)
                assertTrue(state.rowOrder.values.all { it > 0 })
            }
        }
    }

    @Test fun holeInvariant_overlapAndMiddleSplitsKeepEachOlderAnchorAcrossRestores() {
        for (anchor in listOf(boundary - 1u, ULong.MAX_VALUE - 9u)) {
            var state = HistoryCoverage().received(page(anchor)).received(page(anchor + 8u, cursor = "upper"), newest = true)
            state = restore(state.received(page(anchor + 3u, anchor + 4u, cursor = "middle")))
            assertEquals(listOf(UnsignedHistoryGap(anchor, anchor + 3u), UnsignedHistoryGap(anchor + 4u, anchor + 8u)), state.unsignedGaps)
            for (incoming in listOf(page(), page(anchor), page(anchor + 4u), page(anchor + 8u))) {
                assertEquals(state.unsignedGaps, restore(state.received(incoming).received(incoming)).unsignedGaps)
            }
            state = restore(state.receivedUnsigned(page(anchor + 1u, anchor + 2u), target = anchor))
            assertEquals(listOf(UnsignedHistoryGap(anchor + 4u, anchor + 8u)), state.unsignedGaps)
            assertEquals("upper", state.cursorForUnsigned(anchor + 4u))
            state = restore(state.receivedUnsigned(page(anchor + 6u, anchor + 7u, cursor = "five"), target = anchor + 4u))
            assertEquals("five", state.cursorForUnsigned(anchor + 4u))
            assertEquals(listOf(UnsignedHistoryGap(anchor + 4u, anchor + 6u)), state.unsignedGaps)
            assertTrue(restore(state.received(page(anchor + 5u))).unsignedGaps.isEmpty())
        }
    }

    @Test fun legacyUnknownInvariant_overlapAndReplaysCloseOnlyAtStart() {
        var state = HistoryCoverage(unknown = true).received(page(boundary + 2u, ULong.MAX_VALUE))
        state = restore(state.received(page(boundary + 1u, boundary + 2u)))
        assertTrue(state.unsignedUnknown)
        assertEquals(boundary + 1u, state.unsignedUnknownEdge)
        assertFalse(restore(state.received(page(atStart = true))).unsignedUnknown)
        assertTrue(state.received(page(atStart = true)).unknown)
    }

    @Test fun retentionInvariant_removingMaximumCannotWrapAndCertifyTheWholeIdSpace() {
        for (ids in listOf(listOf(ULong.MAX_VALUE), listOf(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE))) {
            val p =
                HistoryPage(
                    ids.reversed().map { id ->
                        entry(id, "send_message", """{"conversation_id":"c","message_id":"m$id","text":"$id"}""")
                    },
                    "",
                    true,
                )
            val rows = reduceHistoryPage(p.entries, true)
            val state = HistoryCoverage().received(p).boundTo(rows)
            val retained = state.retainedBy(rows.filterNot { (it as ThreadItem.MessageItem).message.id == "m${ULong.MAX_VALUE}" })
            assertEquals(
                if (ids.size ==
                    1
                ) {
                    emptyList()
                } else {
                    listOf(UnsignedHistorySpan(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE - 1u))
                },
                retained.unsignedSpans,
            )
            assertEquals(if (ids.size == 1) 0uL else ULong.MAX_VALUE - 1u, retained.unsignedHighWater)
            assertTrue(retained.unsignedUnknown)
        }
    }

    @Test fun deltaInvariant_splitRowsReplayOnceWithExactUnsignedClaimsAndOrder() {
        fun delta(
            id: ULong,
            seq: Int,
            text: String,
        ) = entry(id, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":$seq,"text":"$text"}""")
        val end = entry(boundary + 3u, "turn_end", """{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}""")
        val first = HistoryPage(listOf(end, delta(boundary, 0, "a")), "old", false)
        val last = HistoryPage(listOf(end, delta(boundary + 2u, 2, "c")), "middle", false)
        val middle = HistoryPage(listOf(end, delta(boundary + 1u, 1, "b")), "older", false)
        for (pages in listOf(listOf(first, last, middle), listOf(last, first, middle))) {
            var rows = emptyList<ThreadItem>()
            var state = HistoryCoverage()
            for (p in pages + pages) {
                val reduced = reduceOrderedHistoryPage(p.entries, true)
                rows =
                    rows.mergeUnsignedHistoryRows(
                        reduced.rows,
                        rows.receivedUnsignedHistoryOrder(state.unsignedPositions()) + reduced.unsignedOrder,
                    )
                state = restore(state.received(p).boundTo(rows))
            }
            assertEquals("abc", (rows.single() as ThreadItem.MessageItem).message.content)
            assertEquals(listOf(boundary, boundary + 1u, boundary + 2u), state.unsignedRowOrder.values.sorted())
            assertEquals(
                setOf(boundary, boundary + 1u, boundary + 2u),
                state.unsignedRowEntries.values
                    .flatten()
                    .toSet(),
            )
            assertTrue(state.unsignedGaps.isEmpty())
            assertEquals(listOf(boundary.toLong()), state.rowOrder.values.toList())
        }
    }

    @Test fun signedCompatibilityInvariant_neverProjectsUpperClaimsOrCompleteCoverage() {
        val lower =
            HistoryCoverage(
                spans = listOf(HistorySpan(1, 2)),
            ).received(page(boundary, boundary + 1u, ULong.MAX_VALUE, atStart = true))
        assertEquals(listOf(HistorySpan(1, 2), HistorySpan(Long.MAX_VALUE, Long.MAX_VALUE)), lower.spans)
        assertEquals(Long.MAX_VALUE, lower.highWater)
        assertFalse(lower.unsignedUnknown)
        assertTrue(lower.unknown)
        assertTrue(lower.unsignedIncomplete)
        assertTrue(lower.gaps.none { it.edge < 0 })
        val upperOnly = HistoryCoverage(unsignedSpans = listOf(UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)))
        assertTrue(upperOnly.spans.isEmpty())
        assertEquals(0L, upperOnly.highWater)
        assertTrue(upperOnly.unknown)
    }
}
