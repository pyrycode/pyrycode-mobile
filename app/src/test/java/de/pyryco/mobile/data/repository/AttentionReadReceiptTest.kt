package de.pyryco.mobile.data.repository

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

/** Synthetic real-store pages from #2009's failing daemon and merged queued-receipt repair. */
class AttentionReadReceiptTest {
    @Test
    fun completedReplyCanQualifyForTheDaemonWatermarkWithAccountedDelivery() =
        runTest {
            val page = fixture("repaired")
            val projection = ThreadProjection()
            projection.mergeHistoryPage(CONVERSATION, page, true)
            val snapshot = projection.observeSnapshot(CONVERSATION).first()
            val reply = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().last()
            assertEquals(setOf(6uL, 7uL), snapshot.readEvidence.versions[reply])
            assertEquals(8uL, snapshot.readEvidence.checkpoint(reply, 0u))
            assertTrue(!reply.message.isStreaming)
            assertNull(snapshot.readEvidence.checkpoint(reply.copy(message = reply.message.copy(content = "unseen")), 0u))
        }

    @Test
    fun originalDeliveryOmissionBlocksEvenWithKnownRowsAndNoStoredGaps() =
        runTest {
            val page = fixture("original")
            val projection = ThreadProjection()
            projection.mergeHistoryPage(CONVERSATION, page, true)
            val snapshot = projection.observeSnapshot(CONVERSATION).first()
            val reply = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().last()
            assertEquals(3, snapshot.rows.size)
            assertEquals(3, snapshot.readEvidence.versions.size)
            assertEquals(
                listOf(2uL, 4uL, 5uL, 6uL, 7uL, 8uL),
                snapshot.readEvidence.facts.keys
                    .sorted(),
            )
            assertTrue(snapshot.readEvidence.unidentified.isEmpty())
            assertTrue(snapshot.readEvidence.gaps.isEmpty())
            assertTrue(
                snapshot.readEvidence.facts.values
                    .none { it == null },
            )
            assertEquals(setOf(6uL, 7uL), snapshot.readEvidence.versions[reply])
            assertNull(snapshot.readEvidence.checkpoint(reply, 0u))
        }

    private fun fixture(name: String): HistoryPage =
        MobileJson
            .decodeFromString<HistoryPagePayloadDto>(
                requireNotNull(javaClass.getResource("/daemon-contract/attention-read-$name.json")).readText(),
            ).toHistoryPage()

    private companion object {
        const val CONVERSATION = "11111111-1111-4111-8111-111111111111"
    }
}
