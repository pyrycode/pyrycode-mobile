package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.AuthoritativeHistoryLoad
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NegotiatedLocalHistoryDecoratorTest {
    @Test fun undecoratedProviderRetainsLocalRetry() = verify { it }

    @Test fun recordedRouteRetainsLocalRetry() = verify { tapped(it, passive = false) }

    @Test fun attentionGateRetainsLocalRetry() = verify { InitialAttentionQueryGate.negotiated(it) }

    @Test fun passiveProbeRetainsLocalRetry() = verify { PassiveThreadProbe.negotiated(it) }

    @Test fun installedDestinationChainRetainsLocalRetry() = verify { tapped(it, passive = true) }

    private fun verify(decorate: (NegotiatedThread) -> NegotiatedThread) =
        runTest {
            val provider = Route()
            val route = decorate(provider)
            val generation = provider.store.current("host", "chat").generation
            assertEquals(provider.host, route.host)
            assertSame(provider.support, route.support)
            assertSame(provider.connection, (route.support.value as ThreadSupport.Supported).connection)
            assertSame(provider.store, route.store)
            val history = route.localHistory("chat")
            assertEquals("decorated destination lost actual Loading", AuthoritativeHistoryLoad.Loading, history.first())
            assertSame("history must remain the provider's exact flow", provider.history, history)
            provider.history.value = AuthoritativeHistoryLoad.Retry
            assertEquals(AuthoritativeHistoryLoad.Retry, history.first())
            assertTrue("Retry must reach the selected provider", route.older("chat"))
            assertEquals(1, provider.demands)
            assertEquals(AuthoritativeHistoryLoad.Loading, history.first())
            provider.history.value = AuthoritativeHistoryLoad.Idle
            assertEquals(AuthoritativeHistoryLoad.Idle, history.first())
            assertEquals(generation, route.store.current("host", "chat").generation)
            assertEquals(0, provider.observations)
            assertEquals(0, provider.opens)
            assertEquals(0, provider.cancellations)
        }

    private fun tapped(
        route: NegotiatedThread,
        passive: Boolean,
    ): NegotiatedThread {
        val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override val negotiatedThread = route
            }
        return checkNotNull(TappingConversationRepository(repository, tapNegotiated = passive).negotiatedThread)
    }

    private class Route : NegotiatedThread() {
        val connection = Any()
        override val host = "host"
        override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(connection))
        override val store = AuthoritativeThreadStore()
        val history = MutableStateFlow(AuthoritativeHistoryLoad.Loading)
        var demands = 0
        var observations = 0
        var opens = 0
        var cancellations = 0

        override fun localHistory(conversationId: String) = history.also { require(conversationId == "chat") }

        override fun observe(conversationId: String) = store.observe(host, conversationId).onStart { observations++ }

        override suspend fun open(conversationId: String): Boolean {
            opens++
            return true
        }

        override fun older(conversationId: String): Boolean {
            require(conversationId == "chat")
            check(history.value == AuthoritativeHistoryLoad.Retry)
            demands++
            history.value = AuthoritativeHistoryLoad.Loading
            return true
        }

        override fun cancel(conversationId: String) {
            cancellations++
        }
    }
}
