package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toHistoryPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadReadRuntimeReceiptsTest {
    @Test fun runtimeReceiptRepairsTheDiagnosedHoleWithoutChangingContent() {
        val disabled = fixture(false)
        val enabled = fixture(true)
        assertTrue(enabled.atStart)
        assertEquals("", enabled.cursor)
        assertEquals((1uL..6uL).reversed().toList(), enabled.entries.map { it.unsignedId })
        for ((page, checkpoint) in listOf(disabled to 5uL, enabled to 6uL)) {
            val reduced = reduceOrderedHistoryPage(page.entries, true)
            val evidence = ThreadReadEvidence().received(page.entries, reduced, reduced.rows)
            assertEquals(2, reduced.rows.filter { it.isReadContent() }.size)
            assertEquals(
                listOf("synthetic", "synthetic reply"),
                reduced.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message.content },
            )
            assertTrue(evidence.unidentified.isEmpty())
            assertTrue(evidence.gaps.isEmpty())
            assertTrue(evidence.facts.values.none { it == null })
            assertEquals(checkpoint, evidence.checkpoint(reply(reduced.rows), 0u))
        }
        val omitted = enabled.entries.filterNot { it.unsignedId == 2uL }
        val reduced = reduceOrderedHistoryPage(omitted, true)
        val evidence = ThreadReadEvidence().received(omitted, reduced, reduced.rows)
        assertTrue(evidence.gaps.isEmpty())
        assertTrue(evidence.facts.values.none { it == null })
        assertNull(evidence.checkpoint(reply(reduced.rows), 0u))
    }

    @Test fun invariantOnlyUnderstoodIdentifiedReceiptsAccountForTheHole() {
        val page = fixture(true)
        val receipt = page.entries.single { it.unsignedId == 2uL }
        val onlyReceipt = reduceOrderedHistoryPage(listOf(receipt), true)
        assertTrue(onlyReceipt.rows.none { it.isReadContent() })
        val receiptEvidence = ThreadReadEvidence().received(listOf(receipt), onlyReceipt, onlyReceipt.rows)
        assertEquals(true, receiptEvidence.facts[2u])
        assertNull(receiptEvidence.checkpoint(reply(reduceOrderedHistoryPage(page.entries, true).rows), 0u))
        for (broken in listOf(
            receipt.copy(type = "future_runtime_fact"),
            receipt.copy(payload = MobileJson.parseToJsonElement("{}")),
        )) {
            val entries = page.entries.map { if (it == receipt) broken else it }
            val reduced = reduceOrderedHistoryPage(entries, true)
            val evidence = ThreadReadEvidence().received(entries, reduced, reduced.rows)
            assertNull(evidence.facts[2u])
            assertNull(evidence.checkpoint(reply(reduced.rows), 0u))
        }
        val reduced = reduceOrderedHistoryPage(page.entries, true)
        val evidence = ThreadReadEvidence().received(page.entries, reduced, reduced.rows)
        assertNull(evidence.copy(gaps = listOf(UnsignedHistoryGap(1u, 3u))).checkpoint(reply(reduced.rows), 0u))
        val legacy = reduceOrderedHistoryPage(page.entries, false)
        assertNull(ThreadReadEvidence().received(page.entries, legacy, legacy.rows).checkpoint(reply(reduced.rows), 0u))
    }

    @Test fun invariantReplayAndOverlappingHistoryPreserveExactClaims() =
        runTest {
            val page = fixture(true)
            val openingReceipt = page.entries.single { it.unsignedId == 2uL }
            val withoutReceipt = page.entries - openingReceipt
            val orders = listOf(page.entries, listOf(openingReceipt) + withoutReceipt, withoutReceipt + openingReceipt)
            for (order in orders) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage(CONVERSATION, page.copy(entries = order), true)
                val held = projection.observeSnapshot(CONVERSATION).first()
                val presented = reply(held.rows)
                assertEquals(6uL, held.readEvidence.checkpoint(presented, 0u))
                assertNull(held.readEvidence.checkpoint(presented.copy(message = presented.message.copy(content = "unseen")), 0u))
                val overlaps = listOf(page.entries.take(2), page.entries.subList(2, 4), page.entries.takeLast(2), emptyList(), page.entries)
                for (overlap in overlaps) {
                    projection.mergeHistoryPage(CONVERSATION, page.copy(entries = overlap), true)
                    val repeated = projection.observeSnapshot(CONVERSATION).first()
                    assertEquals(held.rows, repeated.rows)
                    assertEquals(held.readEvidence.versions, repeated.readEvidence.versions)
                    assertEquals(held.readEvidence.facts, repeated.readEvidence.facts)
                    assertEquals(6uL, repeated.readEvidence.checkpoint(presented, 0u))
                    assertNull(repeated.readEvidence.checkpoint(presented, 6u))
                }
                val receipt = page.entries.single { it.unsignedId == 2uL }
                repeat(2) { delivery ->
                    projection.recordReadEnvelope(envelope(receipt).copy(id = 900L + delivery, eventId = 1000L + delivery), true, held.rows)
                }
                val replay = projection.observeSnapshot(CONVERSATION).first()
                assertEquals(held.rows, replay.rows)
                assertEquals(held.readEvidence.versions, replay.readEvidence.versions)
                assertEquals(held.readEvidence.facts, replay.readEvidence.facts)
                assertEquals(6uL, replay.readEvidence.checkpoint(presented, 0u))
            }
        }

    @Test fun invariantMissingLiveIdentityNeedsHistoryAndFreshConnectionHasNoSight() =
        runTest {
            val page = fixture(true)
            val receipt = page.entries.single { it.unsignedId == 2uL }
            val projection = ThreadProjection()
            projection.mergeHistoryPage(CONVERSATION, page.copy(entries = page.entries - receipt), true)
            val before = projection.observeSnapshot(CONVERSATION).first()
            projection.recordReadEnvelope(envelope(receipt).copy(historyEntryId = null), true, before.rows)
            val missing = projection.observeSnapshot(CONVERSATION).first()
            assertEquals(1, missing.readEvidence.unidentified.size)
            assertNull(missing.readEvidence.checkpoint(reply(missing.rows), 0u))
            projection.mergeHistoryPage(CONVERSATION, page.copy(entries = listOf(receipt)), true)
            val resolved = projection.observeSnapshot(CONVERSATION).first()
            assertTrue(resolved.readEvidence.unidentified.isEmpty())
            assertEquals(6uL, resolved.readEvidence.checkpoint(reply(resolved.rows), 0u))
            val fresh = ThreadProjection().observeSnapshot(CONVERSATION).first()
            assertTrue(fresh.readEvidence.versions.isEmpty())
            assertNull(fresh.readEvidence.checkpoint(reply(resolved.rows), 0u))
            val other = projection.observeSnapshot("other-conversation").first()
            assertTrue(other.readEvidence.facts.isEmpty())
            assertNull(other.readEvidence.checkpoint(reply(resolved.rows), 0u))
        }

    private fun envelope(entry: HistoryEntry) =
        Envelope(900, entry.type, entry.timestamp.toString(), entry.payload, historyEntryId = entry.unsignedId)

    private fun fixture(enabled: Boolean): HistoryPage {
        val name = if (enabled) "enabled" else "disabled"
        val bytes = requireNotNull(javaClass.getResource("/daemon-contract/runtime-read-$name.json")).readText()
        return MobileJson.decodeFromString<HistoryPagePayloadDto>(bytes).toHistoryPage()
    }

    private fun reply(rows: List<ThreadItem>) = rows.filterIsInstance<ThreadItem.MessageItem>().last()

    private companion object {
        const val CONVERSATION = "11111111-1111-4111-8111-111111111111"
    }
}
