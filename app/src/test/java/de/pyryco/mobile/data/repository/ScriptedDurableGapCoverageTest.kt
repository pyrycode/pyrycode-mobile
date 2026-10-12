package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toHistoryPage
import de.pyryco.mobile.e2e.DurableHistoryProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Daemon #3167's real receipt fixture also guards the durable-gap proof owned by #2069. */
class ScriptedDurableGapCoverageTest {
    @Test
    fun passivePageReceiptObservationResetsAtConversationAndReconnectBoundaries() {
        val page = repairedPage()
        DurableHistoryProbe.begin(CONVERSATION)
        try {
            DurableHistoryProbe.received("other", page)
            assertEquals(0, DurableHistoryProbe.completed())
            DurableHistoryProbe.received(CONVERSATION, page)
            assertEquals(1, DurableHistoryProbe.completed())
            assertEquals(listOf((1uL..7uL).toList()), DurableHistoryProbe.pageReceipts())
            assertTrue(DurableHistoryProbe.asks().isEmpty())
            DurableHistoryProbe.begin(CONVERSATION)
            assertEquals(0, DurableHistoryProbe.completed())
            assertTrue(DurableHistoryProbe.pageReceipts().isEmpty())
        } finally {
            DurableHistoryProbe.end()
        }
        DurableHistoryProbe.received(CONVERSATION, page)
        assertTrue(DurableHistoryProbe.pageReceipts().isEmpty())
    }

    @Test
    fun repairedNonrenderingReceiptJoinsOlderAnchorAndRemovesMarkerWithoutMoreContent() =
        runTest {
            val page = repairedPage()
            val incomplete = page.copy(entries = page.entries.filter { it.unsignedId != 3uL })
            val held = ThreadProjection()
            held.mergeHistoryPage(CONVERSATION, incomplete, true)
            val before = held.observeSnapshot(CONVERSATION).first().rows
            val coverage = HistoryCoverage().receivedUnsigned(incomplete)
            assertEquals(listOf(UnsignedHistoryGap(2u, 4u)), coverage.unsignedGaps)
            assertEquals(listOf(2uL), coverage.projectDisplay(before).markers.map { it.anchor })

            val receipt = page.copy(entries = page.entries.filter { it.unsignedId == 3uL })
            held.mergeHistoryPage(CONVERSATION, receipt, true)
            val after = held.observeSnapshot(CONVERSATION).first().rows
            val resolved = coverage.receivedUnsigned(receipt)
            assertEquals(
                "non-rendering receipt cannot create or replace content",
                before.filter {
                    it.isReadContent()
                },
                after.filter { it.isReadContent() },
            )
            assertEquals(1, after.size - before.size)
            assertFalse(after.first { it !in before }.isReadContent())
            assertEquals(listOf(UnsignedHistorySpan(1u, 7u)), resolved.unsignedSpans)
            assertTrue(resolved.unsignedGaps.isEmpty())
            assertTrue(resolved.projectDisplay(after).markers.isEmpty())
        }

    @Test
    fun contentRecoveryAndTerminalOverlapsCannotCloseAnUnreceivedEligibleGapAcrossReconnect() =
        runTest {
            val page = repairedPage()
            val incomplete = page.copy(entries = page.entries.filter { it.unsignedId != 3uL })
            val held = ThreadProjection()
            var coverage = HistoryCoverage().receivedUnsigned(incomplete)
            held.mergeHistoryPage(CONVERSATION, incomplete, true)
            val rows = held.observeSnapshot(CONVERSATION).first().rows
            val messages = rows.filterIsInstance<ThreadItem.MessageItem>()
            assertEquals(listOf("ping", "pong"), messages.map { it.message.content })
            assertFalse(messages.last().message.isStreaming)
            for (overlap in listOf(
                emptyList(),
                incomplete.entries.take(1),
                incomplete.entries.take(3),
                incomplete.entries.takeLast(2),
                incomplete.entries,
            )) {
                // A fresh process reuses the same durable identities and held cached neighbours.
                coverage = Json.decodeFromString<HistoryCoverage>(Json.encodeToString(coverage))
                val received = incomplete.copy(entries = overlap)
                coverage = coverage.receivedUnsigned(received, newest = true)
                held.mergeHistoryPage(CONVERSATION, received, true)
                assertEquals(rows, held.observeSnapshot(CONVERSATION).first().rows)
                assertEquals(listOf(UnsignedHistoryGap(2u, 4u)), coverage.unsignedGaps)
                assertEquals(listOf(2uL), coverage.projectDisplay(rows).markers.map { it.anchor })
                assertFalse("at_start cannot fill a known internal hole", coverage.unsignedUnknown)
            }
        }

    @Test
    fun hiddenGapRemainsAuthoritativeAndReturnsWhenAdjacentContentIsDelivered() =
        runTest {
            val page = repairedPage().let { it.copy(entries = it.entries.filter { entry -> entry.unsignedId != 3uL }) }
            val held = ThreadProjection()
            held.mergeHistoryPage(CONVERSATION, page, true)
            val rows = held.observeSnapshot(CONVERSATION).first().rows
            val coverage = HistoryCoverage().receivedUnsigned(page)
            val user = rows.filterIsInstance<ThreadItem.MessageItem>().first()
            assertTrue(coverage.projectDisplay(rows, isDisplayed = { it == user }).markers.isEmpty())
            assertEquals(listOf(UnsignedHistoryGap(2u, 4u)), coverage.unsignedGaps)
            assertEquals(listOf(2uL), coverage.projectDisplay(rows).markers.map { it.anchor })
            assertEquals(rows, coverage.projectDisplay(rows).rows)
        }

    @Test
    fun duplicateAndReorderedReceiptPagesPreserveCachedNeighboursAndSettledReply() =
        runTest {
            val page = repairedPage()
            val batches = listOf(page.entries.take(3), page.entries.subList(3, 5), page.entries.takeLast(2))
            for (order in listOf(batches, batches.reversed(), listOf(batches[1], batches[2], batches[0]))) {
                var coverage = HistoryCoverage()
                val held = ThreadProjection()
                for (entries in order + listOf(page.entries, emptyList(), page.entries.take(1), page.entries)) {
                    val received = page.copy(entries = entries)
                    coverage = Json.decodeFromString<HistoryCoverage>(Json.encodeToString(coverage)).receivedUnsigned(received)
                    held.mergeHistoryPage(CONVERSATION, received, true)
                }
                val rows = held.observeSnapshot(CONVERSATION).first().rows
                val messages = rows.filterIsInstance<ThreadItem.MessageItem>()
                assertEquals(listOf("ping", "pong"), messages.map { it.message.content })
                assertFalse(messages.last().message.isStreaming)
                assertEquals(listOf(UnsignedHistorySpan(1u, 7u)), coverage.unsignedSpans)
                assertTrue(coverage.unsignedGaps.isEmpty())
                assertTrue(coverage.projectDisplay(rows).markers.isEmpty())
            }
        }

    private fun repairedPage(): HistoryPage {
        val bytes = requireNotNull(javaClass.getResource("/daemon-contract/queued-read-repaired.json")).readText()
        return MobileJson.decodeFromString<HistoryPagePayloadDto>(bytes).toHistoryPage()
    }

    private companion object {
        const val CONVERSATION = "11111111-1111-4111-8111-111111111111"
    }
}
