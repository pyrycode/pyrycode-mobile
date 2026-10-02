package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #1346: the readings Channel info's Session section shows. The cost is the latest positive finite
 * `cost_usd_total` among this conversation's turn ends, never a sum; the facts are Claude's claim and stay
 * out of the run configuration the permission control reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelSessionReadingsTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noCostReported_leavesTheCostAbsent() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = collectedVm(events = events)

            events.emit(turnEnd(cost = null))
            runCurrent()

            assertNull(vm.state.value.sessionCostUsd)
        }

    @Test
    fun theLatestPositiveCost_replacesAnEarlierOne() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = collectedVm(events = events)

            events.emit(turnEnd(cost = 0.42))
            runCurrent()
            assertEquals(0.42, vm.state.value.sessionCostUsd)

            // Latest, not the largest and not a sum: a new session's smaller total wins.
            events.emit(turnEnd(cost = 0.1))
            runCurrent()
            assertEquals(0.1, vm.state.value.sessionCostUsd)
        }

    @Test
    fun anUnusableCost_neverReplacesAnEarlierOne() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = collectedVm(events = events)
            events.emit(turnEnd(cost = 1.25))

            for (cost in listOf(null, 0.0, -3.0, Double.POSITIVE_INFINITY, Double.NaN)) {
                events.emit(turnEnd(cost = cost))
                runCurrent()
                assertEquals("after $cost", 1.25, vm.state.value.sessionCostUsd)
            }
        }

    @Test
    fun anotherConversationsCost_isIgnored() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = collectedVm(events = events)

            events.emit(turnEnd(cost = 9.99, conversationId = OTHER_CONV))
            runCurrent()

            assertNull(vm.state.value.sessionCostUsd)
        }

    @Test
    fun aCostReportedWhileNoScreenCollects_isStillShownAfterwards() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vm(events = events)
            val screen = backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            screen.cancel()
            advanceTimeBy(60_000)

            // The view model lives on with no screen collecting, as it does behind another destination.
            events.emit(turnEnd(cost = 0.42))
            runCurrent()
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            assertEquals(0.42, vm.state.value.sessionCostUsd)
        }

    @Test
    fun reportedFacts_reachTheStateButNotThePermissionReading() =
        runTest {
            val facts = SessionFacts("2.1.3", "bypassPermissions", listOf("permission_mode"))
            val repo = ScriptedRepo(MutableStateFlow(facts))
            val vm = collectedVm(repo = repo)

            assertEquals(facts, vm.state.value.reportedSessionFacts)
            assertEquals("default", vm.state.value.runConfig.permissionMode)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun turnEnd(
        cost: Double?,
        conversationId: String = CONV,
    ) = LiveSessionEvent.TurnEnd(conversationId, "t1", "end_turn", costUsdTotal = cost)

    private fun vm(
        repo: ConversationRepository = ScriptedRepo(MutableStateFlow(null)),
        events: Flow<LiveSessionEvent> = MutableSharedFlow(),
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
        repo,
        FakeConnectionStateSource(),
        ComposerDraftStore(),
        liveSessionEvents = events,
    )

    private fun TestScope.collectedVm(
        repo: ConversationRepository = ScriptedRepo(MutableStateFlow(null)),
        events: Flow<LiveSessionEvent> = MutableSharedFlow(),
    ): ThreadViewModel {
        val vm = vm(repo, events)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private class ScriptedRepo(
        private val facts: Flow<SessionFacts?>,
        private val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        override fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = facts

        /** Every conversation's live session is the reading's, so `forLiveSession` keeps the permission mode. */
        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            backing.observeConversations(filter).map { list -> list.map { it.copy(currentSessionId = SESSION) } }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> =
            MutableStateFlow(
                SessionSettings(
                    sessionId = SESSION,
                    model = "",
                    effort = "",
                    effectiveEffort = EffectiveEffort.Unavailable,
                    permissionMode = "default",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                ),
            )
    }

    private companion object {
        const val CONV = "seed-channel-personal"
        const val OTHER_CONV = "seed-channel-pyrycode-mobile"
        const val SESSION = "sess-a"
    }
}
