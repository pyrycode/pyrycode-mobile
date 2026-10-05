package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ResetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test
    fun eachResetEnd_asksOnce_initialIdleAndActivePhasesAskNothing() =
        runTest {
            val rig = rig(available = true)
            assertEquals(listOf(CONV), rig.repo.asks)
            assertEquals(listOf(CONV), rig.repo.observedResetIds.distinct())

            repeat(2) {
                rig.repo.resetting.value = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
                runCurrent()
                rig.repo.resetting.value = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written)
                runCurrent()
                assertEquals(it + 1, rig.repo.asks.size)

                rig.repo.resetting.value = null
                runCurrent()
                assertEquals(List(it + 2) { CONV }, rig.repo.asks)
                rig.repo.resetting.value = null
                runCurrent()
                assertEquals(it + 2, rig.repo.asks.size)
            }
            assertEquals(
                List(2) { "event=context_usage_ask reason=reset_end" },
                logs.filter { "context_usage_ask" in it },
            )
        }

    @Test
    fun aHostTurnEnding_rereadsSettingsWithoutAnotherContextAsk() =
        runTest {
            val rig = rig(available = true)
            rig.repo.events.emit(LiveSessionEvent.TurnState(CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            rig.repo.events.emit(LiveSessionEvent.TurnState(CONV, LiveSessionEvent.TurnState.Phase.Idle))
            runCurrent()

            assertEquals(listOf(CONV), rig.repo.asks)
            assertEquals(listOf(CONV), rig.repo.settingsRefreshes)
        }

    @Test
    fun postResetReply_replacesTheFooterReading_withoutAnotherMessage() =
        runTest {
            val rig = rig(available = true)
            rig.repo.usage.value = ContextUsage(totalTokens = 150_000, maxTokens = 200_000, percentage = 75, asOf = null)
            runCurrent()
            assertEquals(75, rig.vm.state.value.runConfig.contextPercent)

            rig.repo.resetting.value = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
            runCurrent()
            rig.repo.usage.value = null
            rig.repo.resetting.value = null
            runCurrent()
            assertEquals(listOf(CONV, CONV), rig.repo.asks)

            rig.repo.usage.value = ContextUsage(totalTokens = 2_000, maxTokens = 200_000, percentage = 99, asOf = null)
            runCurrent()
            assertEquals(1, rig.vm.state.value.runConfig.contextPercent)
        }

    // The opening stays log-free; reconnect and reset use static reasons without the conversation id.
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
        val vm: ThreadViewModel,
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
                liveSessionEvents = repo.events,
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return Rig(repo, availability, vm)
    }

    private class AskCountingRepo(
        backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val asks = mutableListOf<String>()
        val settingsRefreshes = mutableListOf<String>()
        val observedResetIds = mutableListOf<String>()
        val resetting = MutableStateFlow<ResetStatus?>(null)
        val usage = MutableStateFlow<ContextUsage?>(null)
        val events = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 8)

        override fun observeResetting(conversationId: String): Flow<ResetStatus?> {
            observedResetIds += conversationId
            return resetting
        }

        override fun observeContextUsage(conversationId: String): Flow<ContextUsage?> = usage

        override fun refreshSessionSettings(conversationId: String) {
            settingsRefreshes += conversationId
        }

        override fun requestContextUsage(conversationId: String) {
            asks += conversationId
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"
    }
}
