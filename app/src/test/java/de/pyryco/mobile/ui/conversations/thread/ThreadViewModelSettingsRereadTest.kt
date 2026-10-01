package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.network.RelayLog
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
 * #1309: an open thread asks for one fresh settings reading at each turn end on its host, when Run
 * configuration or Channel info opens, and when a reset ends — and on nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelSettingsRereadTest {
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
    fun aTurnEndingOnTheOpenConversation_rereadsOnce() =
        runTest {
            val rig = rig()
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Thinking))
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Responding))
            assertEquals(emptyList<String>(), rig.repo.refreshes)

            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))
            assertEquals(listOf(CONV), rig.repo.refreshes)
        }

    @Test
    fun aTurnEndingOnAnotherConversation_rereadsTheOpenOneOnce() =
        runTest {
            val rig = rig()
            rig.events.emit(LiveSessionEvent.TurnState(OTHER, Phase.Responding))
            rig.events.emit(LiveSessionEvent.TurnState(OTHER, Phase.Idle))

            assertEquals(listOf(CONV), rig.repo.refreshes)
            // The log carries a static reason only, never either conversation id.
            assertEquals(listOf("event=run_settings_reread reason=turn_end"), logs.filter { "run_settings_reread" in it })
        }

    @Test
    fun aRepeatedIdle_sendsNothingMore() =
        runTest {
            val rig = rig()
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Thinking))
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))

            assertEquals(listOf(CONV), rig.repo.refreshes)
        }

    @Test
    fun aTurnRunningAcrossANewConnection_isForgotten() =
        runTest {
            val rig = rig()
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Thinking))
            rig.available.value = false
            rig.available.value = true
            rig.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))

            assertEquals(emptyList<String>(), rig.repo.refreshes)
        }

    @Test
    fun openingRunConfiguration_rereadsOnce() =
        runTest {
            val rig = rig()
            rig.vm.onOverflowEvent(ThreadEvent.RunConfigOpen)

            assertEquals(listOf(CONV), rig.repo.refreshes)
        }

    @Test
    fun openingChannelInfo_rereadsOnce_andClosingSendsNothing() =
        runTest {
            val rig = rig()
            rig.vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            rig.vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            assertEquals(listOf(CONV), rig.repo.refreshes)

            rig.vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            assertEquals(listOf(CONV), rig.repo.refreshes)
        }

    @Test
    fun aResetEnding_rereadsOnce() =
        runTest {
            val rig = rig()
            rig.repo.resetting.value = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
            rig.repo.resetting.value = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written)
            assertEquals(emptyList<String>(), rig.repo.refreshes)

            rig.repo.resetting.value = null
            assertEquals(listOf(CONV), rig.repo.refreshes)
        }

    private class Rig(
        val vm: ThreadViewModel,
        val repo: CountingRepo,
        val events: MutableSharedFlow<LiveSessionEvent>,
        val available: MutableStateFlow<Boolean>,
    )

    private fun TestScope.rig(): Rig {
        val repo = CountingRepo()
        val events = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 64)
        val available = MutableStateFlow(true)
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                liveSessionEvents = events,
                repositoryAvailable = available,
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return Rig(vm, repo, events, available)
    }

    private class CountingRepo(
        backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val refreshes = mutableListOf<String>()
        val resetting = MutableStateFlow<ResetStatus?>(null)

        override fun refreshSessionSettings(conversationId: String) {
            refreshes += conversationId
        }

        override fun observeResetting(conversationId: String): Flow<ResetStatus?> = resetting
    }

    private companion object {
        const val CONV = "seed-channel-personal"
        const val OTHER = "another-conversation"
    }
}
