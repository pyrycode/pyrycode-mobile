package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #1313: a thread that stops collecting for longer than its subscription timeout reads the conversation's
 * held turn phase when it returns, over the real [RemoteConversationRepository], with no further frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTurnPhaseTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun aTurnThatEndsWhileTheThreadIsAway_readsIdleOnReturn() =
        runTest {
            val thread = OpenThread(this)
            thread.subscribe()
            thread.push(turnState("thinking"))
            assertEquals(Flags(thinking = true, busy = true), thread.flags())

            thread.unsubscribePastTheTimeout()
            thread.push(turnState("responding"), turnEnd())
            // Still the stale reading: nothing collected while the thread was away.
            assertEquals(Flags(thinking = true, busy = true), thread.flags())

            thread.subscribe()
            assertEquals(Flags(thinking = false, busy = false), thread.flags())
        }

    @Test
    fun aTurnThatStartsWhileTheThreadIsAway_readsRunningOnReturn() =
        runTest {
            val thread = OpenThread(this)
            thread.subscribe()
            thread.push(turnState("thinking"), turnEnd())
            assertEquals(Flags(thinking = false, busy = false), thread.flags())

            thread.unsubscribePastTheTimeout()
            thread.push(turnState("thinking"), turnState("responding"))
            assertEquals(Flags(thinking = false, busy = false), thread.flags())

            thread.subscribe()
            assertEquals(Flags(thinking = false, busy = true), thread.flags())
        }

    private data class Flags(
        val thinking: Boolean,
        val busy: Boolean,
    )

    /** One open thread over a real repository fed by a scripted pump. */
    private inner class OpenThread(
        private val scope: TestScope,
    ) {
        private val pump = FakeSessionPump()
        private val repo =
            RemoteConversationRepository(pump, scope.backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
        private val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repo.liveSessionEvents,
            )
        private val collectors = mutableListOf<Job>()

        fun subscribe() {
            collectors += scope.backgroundScope.launch { vm.isThinking.collect {} }
            collectors += scope.backgroundScope.launch { vm.isBusy.collect {} }
            scope.runCurrent()
        }

        fun unsubscribePastTheTimeout() {
            collectors.forEach { it.cancel() }
            collectors.clear()
            scope.advanceTimeBy(SUBSCRIPTION_TIMEOUT_MS + 1_000)
            scope.runCurrent()
        }

        fun push(vararg envelopes: Envelope) {
            envelopes.forEach(pump::push)
            scope.runCurrent()
        }

        fun flags() = Flags(thinking = vm.isThinking.value, busy = vm.isBusy.value)
    }

    private fun turnState(state: String): Envelope = probe("turn_state", """{"conversation_id":"$CONV","state":"$state"}""")

    private fun turnEnd(): Envelope = probe("turn_end", """{"conversation_id":"$CONV","turn_id":"t1","stop_reason":"end_turn"}""")

    private fun probe(
        type: String,
        payload: String,
    ): Envelope = Envelope(id = 1L, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val CONV = "c1"
        const val TS = "2026-10-01T10:00:00Z"
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
