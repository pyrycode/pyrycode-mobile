package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.threadSnapshots
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassiveThreadObservationTest {
    private val connection = Any()

    private fun owner(
        host: String = "host",
        conversation: String = "chat",
        source: Any = connection,
    ) = ThreadEvidenceOwner(host, conversation, source, ThreadEvidenceKind.Legacy)

    @Test fun inactiveProbeDoesNotResolveOwnershipOrChangeDelivery() =
        runTest {
            val snapshot = ThreadSnapshot(emptyList())
            val delivered = mutableListOf<ThreadSnapshot>()
            PassiveThreadProbe
                .tap({ error("inactive observation must not resolve a source") }, flowOf(snapshot))
                .collect { delivered += it }
            assertSame(snapshot, delivered.single())
        }

    @Test fun upstreamFailurePropagatesAndRetiresItsSubscription() =
        runTest {
            PassiveThreadProbe.open().use { probe ->
                var reading: PassiveThreadReading? = null
                val expected = IllegalStateException("controlled upstream failure")
                val failure =
                    runCatching {
                        PassiveThreadProbe
                            .tap(
                                { owner() },
                                flow {
                                    emit(ThreadSnapshot(emptyList()))
                                    reading = probe.reading("host", "chat")
                                    throw expected
                                },
                            ).collect()
                    }
                assertTrue(failure.isFailure)
                assertSame(expected, failure.exceptionOrNull())
                assertNotNull(reading)
                assertNull(probe.evidence(checkNotNull(reading)))
                assertNull(probe.reading("host", "chat"))
            }
        }

    @Test fun samplingObservesExactApplicationSnapshotsWithoutCollectionsOrRequests() =
        runTest {
            var collections = 0
            var requests = 0
            val snapshot = ThreadSnapshot(emptyList(), readEvidence = ThreadReadEvidence(facts = mapOf(51uL to true)))
            val upstream =
                flow {
                    collections++
                    requests++ // Models the cold backfill at the real repository boundary.
                    emit(snapshot)
                    awaitCancellation()
                }
            val delegate =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = upstream
                }
            val tapped =
                TappingConversationRepository(delegate) { conversation, snapshots ->
                    PassiveThreadProbe.tap({ owner(conversation = conversation) }, snapshots)
                }
            PassiveThreadProbe.open().use { probe ->
                assertNull(probe.reading("host", "chat"))
                assertEquals(0, collections)
                val delivered = mutableListOf<ThreadSnapshot>()
                val app = launch { tapped.threadSnapshots("chat").collect { delivered += it } }
                runCurrent()
                val reading = checkNotNull(probe.reading("host", "chat"))
                repeat(5) { assertNotNull(probe.evidence(reading)) }
                assertSame(snapshot, reading.snapshot)
                assertSame(snapshot, delivered.single())
                assertEquals(1, collections)
                assertEquals(1, requests)
                app.cancel()
                runCurrent()
                assertNull(probe.evidence(reading))
                assertNull(probe.reading("host", "chat"))
            }
        }

    @Test fun reopenRequiresANewActiveSubscriptionEvenWhenItsRowsAreIdentical() =
        runTest {
            val snapshots = MutableSharedFlow<ThreadSnapshot>()
            PassiveThreadProbe.open().use { probe ->
                val first = launch { PassiveThreadProbe.tap({ owner() }, snapshots).collect() }
                runCurrent()
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                val old = checkNotNull(probe.reading("host", "chat"))
                assertNull(probe.reading("host", "chat", after = old.subscription))
                first.cancel()
                runCurrent()
                assertNull(probe.evidence(old))
                val reopened = launch { PassiveThreadProbe.tap({ owner() }, snapshots).collect() }
                runCurrent()
                assertNull(probe.reading("host", "chat", after = old.subscription))
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                val current = checkNotNull(probe.reading("host", "chat", after = old.subscription))
                assertNotNull(probe.evidence(current))
                assertNull(probe.evidence(old))
                reopened.cancel()
            }
        }

    @Test fun connectionReplacementNeverRelabelsOldSnapshots() =
        runTest {
            var current = owner()
            val snapshots = MutableSharedFlow<ThreadSnapshot>()
            PassiveThreadProbe.open().use { probe ->
                val app = launch { PassiveThreadProbe.tap({ current }, snapshots).collect() }
                runCurrent()
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                val old = checkNotNull(probe.reading("host", "chat"))
                assertNull(probe.reading("other-host", "chat"))
                assertNull(probe.reading("host", "other-chat"))
                current = owner(source = Any())
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                assertNull(probe.evidence(old))
                assertNull(probe.reading("host", "chat"))
                current = owner()
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                assertNull(probe.evidence(old))
                assertNull(probe.reading("host", "chat"))
                app.cancel()
            }
        }

    @Test fun closingScopeReleasesReadingsAndCannotLeakIntoALaterScope() =
        runTest {
            val snapshots = MutableSharedFlow<ThreadSnapshot>()
            val probe = PassiveThreadProbe.open()
            val app = launch { PassiveThreadProbe.tap({ owner() }, snapshots).collect() }
            runCurrent()
            snapshots.emit(ThreadSnapshot(emptyList()))
            runCurrent()
            val old = checkNotNull(probe.reading("host", "chat"))
            assertThrows(IllegalStateException::class.java) { PassiveThreadProbe.open() }
            probe.close()
            assertNull(probe.evidence(old))
            PassiveThreadProbe.open().use { next ->
                snapshots.emit(ThreadSnapshot(emptyList()))
                runCurrent()
                assertNull(next.reading("host", "chat"))
                assertNull(next.evidence(old))
            }
            app.cancel()
        }

    @Test fun retainedUserRequiresOriginalIdentityCompleteContentAndUniqueDelivery() {
        val user = Message("peer-send", "session", Role.User, "original prompt", Instant.fromEpochMilliseconds(0), false)
        val original = legacyThreadEvidence("host", "chat", connection, listOf(ThreadItem.MessageItem(user)))
        val captured = original.records.single()
        assertNotNull(original.retainedPeerUser(captured, original.owner, "session"))
        for (messages in listOf(
            emptyList(),
            listOf(user, user),
            listOf(user, user.copy(id = "duplicate")),
            listOf(user.copy(id = "wrong")),
            listOf(user.copy(content = "changed")),
            listOf(user.copy(sessionId = "wrong")),
            listOf(user.copy(role = Role.Assistant)),
        )) {
            val changed = legacyThreadEvidence("host", "chat", connection, messages.map { ThreadItem.MessageItem(it) })
            assertNull(changed.retainedPeerUser(captured, original.owner, "session"))
        }
        assertNull(original.retainedPeerUser(captured, owner(host = "wrong"), "session"))
        assertNull(original.retainedPeerUser(captured, owner(conversation = "wrong"), "session"))
        assertNull(original.retainedPeerUser(captured, owner(source = Any()), "session"))
        assertNull(ThreadEvidence(original.owner, original.records, admitted = false).retainedPeerUser(captured, original.owner, "session"))
    }
}
