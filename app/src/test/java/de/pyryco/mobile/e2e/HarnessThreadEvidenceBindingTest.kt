package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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

    @Test fun onlyTheLegacyDemoRouteYieldsLegacyEvidence() {
        assertEquals(ThreadEvidenceKind.Legacy, harnessThreadEvidence(FakeConversationRepository()).kind)
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
        override val inbound: Flow<Envelope> = emptyFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)

        override fun start() = Unit

        override fun send(envelope: Envelope) = false

        override fun close() = Unit
    }
}
