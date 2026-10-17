package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #2175: shared E2E evidence is bound once to the production thread selection. */
class HarnessThreadEvidenceBindingTest {
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
