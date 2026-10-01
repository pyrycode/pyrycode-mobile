package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #1410: an open thread asks for a fresh context reading once when it opens on a live host and once more each
 * time the host's repository returns — and never while no repository is published.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelContextUsageAskTest {
    private val logs = mutableListOf<String>()
    private val previousSink = RelayLog.sink

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        RelayLog.sink = previousSink
        Dispatchers.resetMain()
    }

    @Test
    fun openingOnALiveHost_asksOnce() =
        runTest {
            val rig = rig(available = true)

            assertEquals(listOf(CONV), rig.repo.asks)
        }

    @Test
    fun eachReturnOfTheRepository_asksOnceMore_andARepeatedAvailabilityAsksNothing() =
        runTest {
            val rig = rig(available = true)
            rig.available.value = false
            assertEquals(listOf(CONV), rig.repo.asks)

            rig.available.value = true
            rig.available.value = true
            assertEquals(listOf(CONV, CONV), rig.repo.asks)

            rig.available.value = false
            rig.available.value = true
            assertEquals(listOf(CONV, CONV, CONV), rig.repo.asks)
        }

    @Test
    fun openingWithNoRepository_asksNothing_untilItArrives() =
        runTest {
            val rig = rig(available = false)
            assertEquals(emptyList<String>(), rig.repo.asks)

            rig.available.value = true
            assertEquals(listOf(CONV), rig.repo.asks)
        }

    // Only the reconnect ask logs, with a static reason and never the conversation id.
    @Test
    fun theReconnectAskLogsAStaticReason_neverTheConversationId() =
        runTest {
            val rig = rig(available = true)
            rig.available.value = false
            rig.available.value = true

            val asks = logs.filter { "context_usage_ask" in it }
            assertEquals(listOf("event=context_usage_ask reason=reconnect"), asks)
        }

    private class Rig(
        val repo: AskCountingRepo,
        val available: MutableStateFlow<Boolean>,
    )

    private fun TestScope.rig(available: Boolean): Rig {
        val repo = AskCountingRepo()
        val availability = MutableStateFlow(available)
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repositoryAvailable = availability,
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return Rig(repo, availability)
    }

    private class AskCountingRepo(
        backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val asks = mutableListOf<String>()

        override fun requestContextUsage(conversationId: String) {
            asks += conversationId
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"
    }
}
