package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.threadSnapshots
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableHistoryProbeTest {
    @Test fun decoratorPreservesExactReadEvidenceWithoutAnotherSubscription() =
        runBlocking {
            var subscriptions = 0
            val snapshot = ThreadSnapshot(emptyList(), readEvidence = ThreadReadEvidence(facts = mapOf(51uL to true)))
            val delegate =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = flowOf(snapshot).also { subscriptions++ }
                }
            assertEquals(snapshot, TappingConversationRepository(delegate).threadSnapshots("selected").first())
            assertEquals(1, subscriptions)
        }

    @Test
    fun selected_requests_delegate_once_and_capture_newest_then_older_without_extra_subscription() =
        runBlocking {
            var calls = 0
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun requestHistory(
                        conversationId: String,
                        cursor: String,
                        limit: Int,
                    ): HistoryPage {
                        calls++
                        return HistoryPage(emptyList(), "next", false)
                    }
                }
            DurableHistoryProbe.begin("selected")
            try {
                val tapped = TappingConversationRepository(delegate)
                tapped.requestHistory("selected", "", 0)
                tapped.requestHistory("selected", "opaque", 0)
                tapped.requestHistory("other", "", 0)
                assertEquals(3, calls)
                assertEquals(listOf(true, false), DurableHistoryProbe.asks())
                assertEquals(2, DurableHistoryProbe.completed())
                assertTrue(DurableHistoryProbe.rows().isEmpty())
            } finally {
                DurableHistoryProbe.end()
            }
        }

    @Test
    fun failure_is_not_counted_as_a_completed_page_and_probe_is_opt_in() =
        runBlocking {
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun requestHistory(
                        conversationId: String,
                        cursor: String,
                        limit: Int,
                    ): HistoryPage = throw IllegalStateException("fixture")
                }
            DurableHistoryProbe.begin("selected")
            try {
                val result = runCatching { TappingConversationRepository(delegate).requestHistory("selected", "", 0) }
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertEquals(listOf(true), DurableHistoryProbe.asks())
                assertEquals(0, DurableHistoryProbe.completed())
            } finally {
                DurableHistoryProbe.end()
            }
            runCatching { TappingConversationRepository(delegate).requestHistory("selected", "", 0) }
            assertTrue(DurableHistoryProbe.asks().isEmpty())
        }
}
