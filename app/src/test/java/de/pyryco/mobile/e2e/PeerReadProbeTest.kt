package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReadProbeTest {
    @Test
    fun unavailableRepositoryKeepsOriginalRowFailureAndIndependentEvidence() {
        val original = AssertionError("B after a peer read, without reopening its thread; observed Unread")
        var daemonSamples = 0
        var projectionSamples = 0
        val failure =
            runCatching {
                throw peerReadProbeFailure(
                    original,
                    confirmed = 8u,
                    peerCheckpoint = 8u,
                    conversationId = "b",
                    repository = { null },
                    daemonMarks = {
                        daemonSamples++
                        ConversationReadMarks(8u, 8u)
                    },
                    projectedUnread = {
                        projectionSamples++
                        true
                    },
                )
            }.exceptionOrNull()

        assertSame("diagnostic must preserve the original row assertion", original, failure?.cause)
        assertEquals(original.message, failure?.cause?.message)
        val message = failure?.message.orEmpty()
        assertTrue(message, message.contains("event=peer_read_probe"))
        assertTrue(message, message.contains("phone_repository_available=false"))
        assertTrue(message, message.contains("phone_received_confirmation=unknown"))
        assertTrue(message, message.contains("daemon_confirmed_target=true"))
        assertTrue(message, message.contains("daemon_read_covers_latest=true"))
        assertTrue(message, message.contains("projected_unread=true"))
        assertEquals(1, daemonSamples)
        assertEquals(1, projectionSamples)
    }

    @Test
    fun failedPhoneReadKeepsTheRowCauseAndIndependentDaemonAndProjectionFacts() {
        val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeReadMarks(conversationId: String): Flow<ConversationReadMarks?> =
                    flow { error("private diagnostic detail") }
            }
        val original = AssertionError("B remained Unread")
        val failure = peerReadProbeFailure(original, 8u, 8u, "b", { repository }, { ConversationReadMarks(8u, 8u) }, { true })

        assertSame(original, failure.cause)
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("phone_repository_available=true"))
        assertTrue(message, message.contains("phone_marks_available=false"))
        assertTrue(message, message.contains("phone_read_covers_latest=unknown"))
        assertTrue(message, message.contains("daemon_read_covers_latest=true"))
        assertTrue(message, message.contains("projected_unread=true"))
        assertTrue(message, !message.contains("private diagnostic detail"))
    }

    @Test
    fun failedSuppliersKeepTheRowCauseAndMarkOnlyUnavailableEvidenceUnknown() {
        val original = AssertionError("B remained Unread")
        val failure =
            peerReadProbeFailure(
                original,
                8u,
                8u,
                "b",
                repository = { error("private lookup detail") },
                daemonMarks = { throw AssertionError("private peer detail") },
                projectedUnread = { error("private projection detail") },
            )

        assertSame(original, failure.cause)
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("event=peer_read_probe daemon_confirmed_target=true"))
        assertTrue(message, message.contains("phone_repository_available=false"))
        assertTrue(message, message.contains("daemon_marks_available=false"))
        assertTrue(message, message.contains("daemon_read_covers_latest=unknown"))
        assertTrue(message, message.contains("phone_received_confirmation=unknown"))
        assertTrue(message, message.contains("projected_unread=unknown"))
        assertTrue(message, !message.contains("private"))
    }

    @Test
    fun availableFactsDistinguishPhoneReceiptFromLatestCoverageAndProjection() {
        val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeReadMarks(conversationId: String): Flow<ConversationReadMarks?> = flowOf(ConversationReadMarks(8u, 10u))
            }
        val original = AssertionError("B remained Unread")
        val failure = peerReadProbeFailure(original, 8u, 8u, "b", { repository }, { ConversationReadMarks(8u, 8u) }, { true })

        assertSame(original, failure.cause)
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("phone_repository_available=true"))
        assertTrue(message, message.contains("phone_marks_available=true daemon_marks_available=true"))
        assertTrue(message, message.contains("daemon_read_covers_latest=true"))
        assertTrue(message, message.contains("phone_received_confirmation=true"))
        assertTrue(message, message.contains("phone_read_covers_latest=false"))
        assertTrue(message, message.contains("phone_latest_exceeds_daemon=true"))
        assertTrue(message, message.contains("projected_unread=true"))
    }

    @Test
    fun cancellationStillPropagatesFromDiagnosticCollection() {
        val cancellation = CancellationException("cancelled")
        val failure =
            runCatching {
                peerReadProbeFailure(AssertionError("B remained Unread"), 8u, 8u, "b", { throw cancellation }, { null }, { true })
            }.exceptionOrNull()

        assertSame(cancellation, failure)
    }
}
