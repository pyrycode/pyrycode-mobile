package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #2175: shared E2E evidence is bound once to the production thread selection. */
class HarnessThreadEvidenceBindingTest {
    private val previousSink = RelayLog.sink

    @Before fun quietAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreAndroidLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun negotiatedHostRepositoryYieldsOnlyAuthoritativeEvidence() =
        runTest {
            val route = Route()
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    override val negotiatedThread: NegotiatedThread = route
                }
            val source = harnessThreadEvidence(repository)
            assertEquals(ThreadEvidenceKind.Authoritative, source.kind)
            val evidence = source.observe("c").first()
            assertEquals("host", evidence.owner.host)
            assertTrue(evidence.records.isEmpty())
        }

    @Test fun liveConnectionServingAThreadRouteYieldsAuthoritativeEvidence() =
        runTest {
            val repository =
                RemoteConversationRepository(
                    Pump(),
                    backgroundScope,
                    negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD) },
                    authoritativeHostId = "host",
                )
            assertEquals(ThreadEvidenceKind.Authoritative, harnessThreadEvidence(repository).kind)
        }

    @Test fun demoRouteYieldsAuthoritativeFacts() =
        runTest {
            val repository = FakeConversationRepository()
            val conversation =
                repository
                    .observeConversations(de.pyryco.mobile.data.repository.ConversationFilter.All)
                    .first()
                    .first()
                    .id
            val source = harnessThreadEvidence(repository)
            assertEquals(ThreadEvidenceKind.Authoritative, source.kind)
            val evidence = source.observe(conversation).first()
            assertTrue(evidence.admitted)
            assertTrue(evidence.records.isNotEmpty())
            assertTrue(evidence.records.all { it.fact != null })
        }

    @Test fun missingOrRetiredExactConnectionCannotExposeSavedFacts() =
        runTest {
            var capabilities = setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD)
            val repository =
                RemoteConversationRepository(
                    Pump(),
                    backgroundScope,
                    negotiatedCapabilities = { capabilities },
                    authoritativeHostId = "host",
                )
            repository.authoritativeThreads.restore(
                "host",
                "c",
                AuthoritativeThreadSnapshot(epoch = "e", items = listOf(item()), completedVersion = 10, checkpoint = 10),
                0,
            )
            val source = harnessThreadEvidence(repository)
            assertTrue(
                source
                    .observe("c")
                    .first()
                    .records
                    .isNotEmpty(),
            )
            capabilities = emptySet()
            val retired = source.observe("c").first()
            assertFalse(retired.admitted)
            assertTrue(retired.records.isEmpty())
        }

    @Test fun negotiatedEvidenceRetiresConnectionOwnershipAndSuppressesMissingHostFacts() =
        runTest {
            val route = Route()
            route.store.restore(
                "host",
                "c",
                AuthoritativeThreadSnapshot(epoch = "e", items = listOf(item()), completedVersion = 10, checkpoint = 10),
                0,
            )
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    override val negotiatedThread: NegotiatedThread = route
                }
            val source = harnessThreadEvidence(repository)
            val firstConnection = Any()
            route.support.value = ThreadSupport.Supported(firstConnection)
            val first = source.observe("c").first()
            assertTrue(first.records.isNotEmpty())
            assertSame(firstConnection, first.owner.connection)
            route.support.value = ThreadSupport.Unverified
            val offline = source.observe("c").first()
            assertFalse(first.owner.matches(offline.owner))
            assertTrue(offline.records.isNotEmpty())
            val replacement = Any()
            route.support.value = ThreadSupport.Supported(replacement)
            val next = source.observe("c").first()
            assertSame(replacement, next.owner.connection)
            assertFalse(first.owner.matches(next.owner))
            route.support.value = ThreadSupport.Missing(replacement)
            val missing = source.observe("c").first()
            assertFalse(missing.admitted)
            assertTrue(missing.records.isEmpty())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedHostEvidenceFollowsReplacementConnectionWithoutBorrowingRetiredOwnership() =
        runTest {
            val connections = MutableStateFlow<RelayTransport?>(Transport())
            val pumps = mutableListOf<Pump>()
            val coordinator =
                RelayRepositoryCoordinator(
                    connections,
                    MutableStateFlow(RelayLinkStatus.Connected),
                    { Pump().also(pumps::add) },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    authoritativeHostId = "host",
                )
            try {
                coordinator.start()
                runCurrent()
                pumps.last().state.value = PumpState.Open("first", setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD))
                runCurrent()
                val route = requireNotNull(coordinator.negotiatedThread)
                val firstGeneration =
                    route.store
                        .observe("host", "c")
                        .first()
                        .generation
                route.store.establishComplete("host", "c", "epoch", 10, listOf(item(text = "first")), firstGeneration)
                val source = harnessThreadEvidence(harnessHostRepository(coordinator))
                val first = source.observe("c").first()
                assertTrue(first.admitted)
                assertEquals("first", first.records.single().fullText)

                pumps.last().state.value = PumpState.Handshaking
                connections.value = Transport()
                runCurrent()
                pumps.last().state.value = PumpState.Open("replacement", setOf(CAPABILITY_INTERACTIVE, CAPABILITY_THREAD))
                runCurrent()
                val nextGeneration =
                    route.store
                        .observe("host", "c")
                        .first()
                        .generation
                route.store.establishComplete("host", "c", "epoch", 20, listOf(item(rev = 3, text = "replacement")), nextGeneration)
                val replacement = source.observe("c").first()
                assertTrue("The held host source must admit its current connection", replacement.admitted)
                assertEquals("replacement", replacement.records.single().fullText)
                assertSame(coordinator.currentRepository.value, replacement.owner.connection)
                assertFalse(first.owner.matches(replacement.owner))
            } finally {
                coordinator.close()
            }
        }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }

    private class Route : NegotiatedThread() {
        override val host = "host"
        override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Unverified)
        override val store = AuthoritativeThreadStore()

        override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> = store.observe(host, conversationId)

        override suspend fun open(conversationId: String) = false

        override fun older(conversationId: String) = false

        override fun cancel(conversationId: String) = Unit
    }

    private class Pump : ManagedSessionPump {
        private val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = frames.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)

        override fun start() = Unit

        override fun send(envelope: Envelope) = false

        override fun close() {
            frames.close()
        }
    }
}
