package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCoverageTest {
    private fun page(
        vararg ids: Long,
        cursor: String = "cursor",
        atStart: Boolean = false,
    ) = HistoryPage(
        ids.reversed().map { HistoryEntry(it, "future", JsonObject(emptyMap()), Instant.fromEpochSeconds(it)) },
        cursor,
        atStart,
    )

    @Test fun durableGapInvariant_persistedCoverageKeepsEachGapCursor_acrossRestartBetweenEveryPage() {
        fun restore(state: HistoryCoverage): HistoryCoverage =
            kotlinx.serialization.json.Json.decodeFromString(
                HistoryCoverage.serializer(),
                kotlinx.serialization.json.Json
                    .encodeToString(HistoryCoverage.serializer(), state),
            )
        var state = restore(HistoryCoverage().received(page(1, 2)))
        state = restore(state.received(page(8, 9, cursor = "gap-seven"), newest = true))
        for ((ids, next) in listOf(listOf(6L, 7L) to "gap-five", listOf(4L, 5L) to "gap-three")) {
            val incoming = page(*ids.toLongArray(), cursor = next)
            state = restore(state.received(incoming, target = 2))
            state = restore(state.received(incoming, target = 2))
            assertEquals(next, state.cursorFor(2))
            assertEquals(1, state.gaps.size)
        }
        assertTrue(restore(state.received(page(3), target = 2)).gaps.isEmpty())
    }

    @Test fun receivedIdsIncludeNonRenderingEntries_andAdjacencyCreatesNoGap() {
        val state = HistoryCoverage().received(page(1, 2)).received(page(3, 4), newest = true)
        assertEquals(listOf(HistorySpan(1, 4)), state.spans)
        assertTrue(state.gaps.isEmpty())
        assertEquals(4L, state.highWater)
    }

    @Test fun overlapElsewherePreservesAnOlderHole_inBothArrivalOrders() {
        for (pages in listOf(listOf(page(1, 2), page(4, 5)), listOf(page(4, 5), page(1, 2)))) {
            val state = pages.fold(HistoryCoverage()) { s, p -> s.received(p) }.received(page(5, 6))
            assertEquals(listOf(2L to 4L), state.gaps.map { it.anchor to it.edge })
            assertTrue(state.received(page(3)).gaps.isEmpty())
        }
    }

    @Test fun multiPageGapClosesOnlyWhenCoverageJoinsItsOlderAnchor() {
        var state = HistoryCoverage().received(page(1, 2)).received(page(8, 9, cursor = "seven"), newest = true)
        state = state.received(page(6, 7, cursor = "five"), target = 2)
        assertEquals("five", state.cursorFor(2))
        assertEquals(6L, state.gaps.single().edge)
        state = state.received(page(4, 5, cursor = "three"), target = 2)
        assertEquals(4L, state.gaps.single().edge)
        assertTrue(state.received(page(3), target = 2).gaps.isEmpty())
    }

    @Test fun legacyMatchingAndVerifiedOverlapNeverCertifyUnknownCoverage() {
        var state = HistoryCoverage(unknown = true).received(page(9, 10, cursor = "eight"), newest = true)
        assertTrue(state.unknown)
        state = state.received(page(8, 9, cursor = "seven"), target = 0)
        assertTrue(state.unknown)
        assertEquals(8L, state.unknownEdge)
        assertFalse(state.received(page(atStart = true), target = 0).unknown)
    }

    @Test fun neverLoadedCacheHasNoConservativeMarker_andNewestAtStartClosesLegacy() {
        assertFalse(HistoryCoverage().received(page(9, 10), newest = true).unknown)
        assertFalse(HistoryCoverage(unknown = true).received(page(1, atStart = true), newest = true).unknown)
    }

    @Test fun cursorlessHoleRereadsOneCoveredPage_andRefusalNeedsAnotherPull() {
        val state = HistoryCoverage().received(page(1)).received(page(5, cursor = "four"), newest = true)
        assertEquals("four", state.cursorFor(1))
        val reread = state.received(page(5, cursor = "next"), target = 1)
        assertEquals("next", reread.cursorFor(1))
        assertEquals(1, reread.gaps.size)
        assertEquals("four", reread.refused(1).cursorFor(1))
    }

    @Test fun middlePageCreatesTwoHoles_withoutErasingEither() {
        val state = HistoryCoverage().received(page(1)).received(page(9)).received(page(4, 5))
        assertEquals(listOf(1L to 4L, 5L to 9L), state.gaps.map { it.anchor to it.edge })
        assertEquals(listOf(5L to 9L), state.received(page(2, 3)).gaps.map { it.anchor to it.edge })
    }

    @Test fun replayEmptyAndOverlappingPages_preserveEveryUnjoinedHoleAndUnknownCoverage() {
        val state = HistoryCoverage(unknown = true).received(page(1, 2)).received(page(5, 6)).received(page(9, 10))
        for (overlap in listOf(page(), page(1), page(5), page(6, 9), page(10))) {
            val next = state.received(overlap).received(overlap)
            assertEquals(state.spans, next.spans)
            assertEquals(state.gaps, next.gaps)
            assertTrue(next.unknown)
        }
    }

    @Test fun aGapInsideOneAssistantSegment_splitsOnlyTheDisplay_andFillRejoins() {
        val row =
            ThreadItem.MessageItem(
                Message(
                    "t",
                    "s",
                    Role.Assistant,
                    "ac",
                    Instant.fromEpochSeconds(1),
                    false,
                    segment = AssistantSegment("t", listOf(SegmentDelta(0, 1), SegmentDelta(2, 1))),
                ),
            )
        val order = row.historyKeys().zip(listOf(1L, 3L)).toMap()
        val state = HistoryCoverage(spans = listOf(HistorySpan(1, 1), HistorySpan(3, 3)), gaps = listOf(HistoryGap(1, 3)), rowOrder = order)
        val displayed = state.displayRows(listOf(row)).filterIsInstance<ThreadItem.MessageItem>()
        assertEquals(listOf("a", "c"), displayed.map { it.message.content })
        assertEquals(2, displayed.map { it.message.id }.distinct().size)
        assertEquals(listOf(row), state.received(page(2)).displayRows(listOf(row)))
    }
}
