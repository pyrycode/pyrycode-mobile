package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryDisplayProjectionTest {
    @Test fun leadingLifecycleInvariant_targetsFirstDisplayedRow_andNeverOccupiesASpan() {
        val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
        val lifecycle = ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochSeconds(0))
        val held = listOf(lifecycle) + rows
        val projection = coverage.projectDisplay(held)
        assertEquals(held, projection.rows)
        assertEquals(listOf(35998uL, 36002uL), projection.markers.map { it.anchor })
        assertEquals(rows.take(2), projection.markers.map { it.displayRow })
        assertEquals(rows.take(2).map { it.historyKeys().first() }, projection.markers.map { it.beforeRow })
        assertTrue(coverage.projectDisplay(listOf(lifecycle)).markers.isEmpty())
        assertEquals("selected-gap", coverage.cursorForUnsigned(35998uL))
        assertEquals("internal-gap", coverage.cursorForUnsigned(36002uL))
        assertEquals(17999, coverage.unsignedGaps.size)
    }

    @Test fun restoredSparseCoverage_projectsOnlyAdjacentContent_andOneNearestOldestEdge() {
        val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
        var preparations = 0
        var lookups = 0
        val positions = coverage.unsignedPositions()
        val projection =
            coverage.projectDisplay(rows, keysFor = {
                preparations++
                it.historyKeys()
            }, positionFor = {
                lookups++
                positions[it]
            })
        assertEquals(rows, projection.rows)
        assertEquals(listOf(35998uL, 36002uL), projection.markers.map { it.anchor })
        assertEquals(rows.take(2).map { it.historyKeys().first() }, projection.markers.map { it.beforeRow })
        assertEquals(rows.size, preparations)
        assertEquals(rows.size, lookups)
        assertEquals(17999, coverage.unsignedGaps.size)
        assertEquals("selected-gap", coverage.cursorForUnsigned(projection.markers.first().anchor))
        assertTrue(coverage.unsignedUnknown)
        assertEquals(projection, coverage.projectDisplay(rows))
    }

    @Test fun restoredLargeCoverage_preparesEachRowOnce_andPreservesIdentityOrderAndMultiplicity() {
        val (coverage, rows) = fragmentedHistoryFixture((0 until 18000).toList())
        var preparations = 0
        var lookups = 0
        val positions = coverage.unsignedPositions()
        val projection =
            coverage.projectDisplay(rows, keysFor = {
                preparations++
                it.historyKeys()
            }, positionFor = {
                lookups++
                positions[it]
            })
        assertEquals(rows, projection.rows)
        assertEquals(listOf(0uL) + coverage.unsignedGaps.map { it.anchor }, projection.markers.map { it.anchor })
        assertEquals(rows.map { it.historyKeys().first() }, projection.markers.map { it.beforeRow })
        assertEquals(18000, preparations)
        assertEquals(18000, lookups)
    }

    @Test fun hiddenGapInvariant_emptyOrMissingAdjacentSpansNeverInventInternalMarkers() {
        val (coverage, rows) = fragmentedHistoryFixture(listOf(0, 2, 4))
        assertTrue(coverage.projectDisplay(emptyList()).markers.isEmpty())
        for (subset in listOf(rows.take(1), rows.takeLast(1), rows, rows.reversed())) {
            val projection = coverage.projectDisplay(subset)
            assertEquals(subset, projection.rows)
            assertEquals(1, projection.markers.size)
            assertEquals(
                subset.minOf { coverage.unsignedRowOrder.getValue(it.historyKeys().first()) },
                coverage.unsignedGaps.firstOrNull { it.anchor == projection.markers.single().anchor }?.edge ?: coverage.unsignedUnknownEdge,
            )
        }
        assertEquals(17999, coverage.unsignedGaps.size)
        val (_, adjacent) = fragmentedHistoryFixture(listOf(0, 1))
        val revealed = coverage.copy(unsignedRowOrder = coverage.unsignedRowOrder + adjacent.last().historyKeys().associateWith { 5uL })
        assertEquals(listOf(0uL, 2uL), revealed.projectDisplay(adjacent).markers.map { it.anchor })
    }

    @Test fun knownGapWinsSharedUnknownEdge_andUpperUnsignedAnchorIsExact() {
        val anchor = ULong.MAX_VALUE - 2u
        val row = ThreadItem.MessageItem(Message("upper", "s", Role.User, "held", Instant.fromEpochSeconds(1), false))
        val coverage =
            HistoryCoverage(
                unsignedSpans = listOf(UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                unsignedGaps = listOf(UnsignedHistoryGap(anchor, ULong.MAX_VALUE)),
                unsignedUnknown = true,
                unsignedRowOrder = row.historyKeys().associateWith { ULong.MAX_VALUE },
            )
        val restored = Json.decodeFromString<HistoryCoverage>(Json.encodeToString(coverage))
        assertEquals(listOf(anchor), restored.projectDisplay(listOf(row)).markers.map { it.anchor })
    }

    @Test fun splitSegmentInvariant_preservesContentAndCollisionSafeKeys_withoutGapTimesDeltaWork() {
        val (base, _) = fragmentedHistoryFixture(emptyList())
        val row =
            ThreadItem.MessageItem(
                Message(
                    "turn",
                    "s",
                    Role.Assistant,
                    "ac",
                    Instant.fromEpochSeconds(1),
                    false,
                    segment = AssistantSegment("turn", listOf(SegmentDelta(0, 1), SegmentDelta(2, 1))),
                ),
            )
        val keys = row.historyKeys()
        val coverage = base.copy(unsignedRowOrder = keys.zip(listOf(1uL, 5uL)).toMap())
        var preparations = 0
        var lookups = 0
        val projection =
            coverage.projectDisplay(
                listOf(row),
                keysFor = {
                    preparations++
                    it.historyKeys()
                },
                positionFor = {
                    lookups++
                    coverage.unsignedRowOrder[it]
                },
            )
        val messages = projection.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
        assertEquals(listOf("a", "c"), messages.map { it.content })
        assertEquals(2, messages.map { it.id }.distinct().size)
        assertEquals(listOf(0uL, 2uL), projection.markers.map { it.anchor })
        assertEquals(keys, projection.markers.map { it.beforeRow })
        assertEquals(1, preparations)
        assertTrue(lookups <= 4)
        assertEquals("ac", row.message.content)
        assertEquals(17999, coverage.unsignedGaps.size)
    }

    @Test fun cancelledFragmentPreparation_stopsBeforeRemainingDeltas_withoutChangingHeldRowsOrCoverage() {
        val (base, _) = fragmentedHistoryFixture(emptyList())
        val row =
            ThreadItem.MessageItem(
                Message(
                    "large-turn",
                    "s",
                    Role.Assistant,
                    "a".repeat(18000),
                    Instant.fromEpochSeconds(1),
                    false,
                    segment = AssistantSegment("large-turn", (0 until 18000).map { SegmentDelta(it, 1) }),
                ),
            )
        var checkpoints = 0
        try {
            base.projectDisplay(listOf(row), checkActive = {
                if (++checkpoints == 8) throw CancellationException("destination exited")
            })
            fail("cancelled preparation must not return a projection")
        } catch (_: CancellationException) {
            assertEquals(8, checkpoints)
        }
        assertEquals(18000, row.message.content.length)
        assertEquals(17999, base.unsignedGaps.size)
    }
}
