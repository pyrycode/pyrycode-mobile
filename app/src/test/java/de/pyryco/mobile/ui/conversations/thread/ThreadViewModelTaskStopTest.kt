package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTaskStopTest {
    private val oldSink = RelayLog.sink
    private val oldLogging = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldLogging
    }

    private class Fixture {
        val roster = MutableStateFlow<BackgroundTaskRoster?>(BackgroundTaskRoster(listOf(task("a"), task("b")), 0))
        val support = MutableStateFlow(true)
        val refusals = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val calls = mutableListOf<Pair<String, String>>()
        var send: suspend () -> Result<Unit> = { Result.success(Unit) }
        lateinit var vm: ThreadViewModel

        fun toggle(id: String) = vm.onOverflowEvent(ThreadEvent.BackgroundTaskToggle(id))

        fun stop(id: String) = vm.onOverflowEvent(ThreadEvent.BackgroundTaskStop(id))
    }

    private fun TestScope.fixture(host: String = "host-secret"): Fixture {
        val f = Fixture()
        f.vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to host, "conversationId" to "conversation-secret")),
                FakeConversationRepository(),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                backgroundTasks = {
                    assertEquals("conversation-secret", it)
                    f.roster
                },
                backgroundTaskCount = { f.roster.map { r -> r?.liveCount ?: 0 } },
                backgroundTaskStopSupported = f.support,
                backgroundTaskStopRefusals = {
                    assertEquals("conversation-secret", it)
                    f.refusals
                },
                stopBackgroundTask = { conversation, task ->
                    f.calls += conversation to task
                    f.send()
                },
            )
        backgroundScope.launch { f.vm.state.collect {} }
        advanceUntilIdle()
        return f
    }

    @Test
    fun onlyEligibleRowsExpandIndependently_andClosedRowsCannotSend() =
        runTest {
            val f = fixture()
            f.stop("a")
            f.toggle("missing")
            f.toggle("a")
            f.toggle("b")
            advanceUntilIdle()
            assertEquals(setOf("a", "b"), f.vm.state.value.expandedBackgroundTaskIds)
            assertTrue(f.calls.isEmpty())
            f.toggle("a")
            advanceUntilIdle()
            assertEquals(setOf("b"), f.vm.state.value.expandedBackgroundTaskIds)
            f.support.value = false
            advanceUntilIdle()
            f.toggle("a")
            f.stop("b")
            advanceUntilIdle()
            assertFalse(f.vm.state.value.backgroundTaskStopSupported)
            assertTrue(
                f.vm.state.value.expandedBackgroundTaskIds
                    .isEmpty(),
            )
            assertTrue(f.calls.isEmpty())
        }

    @Test
    fun pendingStartsBeforeLaunch_andSurvivesCollapseAndReopen_untilOwnRefusal() =
        runTest {
            val f = fixture()
            f.toggle("a")
            f.toggle("b")
            f.stop("a")
            f.stop("a")
            f.stop("b")
            f.toggle("a")
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            assertEquals(listOf("conversation-secret" to "a", "conversation-secret" to "b"), f.calls)
            assertEquals(setOf("a", "b"), f.vm.state.value.pendingBackgroundTaskIds)
            f.refusals.emit("unmatched")
            advanceUntilIdle()
            assertEquals(setOf("a", "b"), f.vm.state.value.pendingBackgroundTaskIds)
            f.refusals.emit("a")
            advanceUntilIdle()
            assertEquals(setOf("b"), f.vm.state.value.pendingBackgroundTaskIds)
            assertEquals(setOf("a", "b"), f.vm.state.value.expandedBackgroundTaskIds)
            assertEquals(
                2,
                f.vm.state.value.backgroundTasks
                    ?.liveCount,
            )
            f.stop("a")
            advanceUntilIdle()
            assertEquals(3, f.calls.size)
        }

    @Test
    fun localFailureReenables_withoutChangingRosterOrExpansion() =
        runTest {
            val f = fixture()
            f.send = { Result.failure(IllegalStateException("secret daemon detail")) }
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            assertTrue(
                f.vm.state.value.pendingBackgroundTaskIds
                    .isEmpty(),
            )
            assertEquals(setOf("a"), f.vm.state.value.expandedBackgroundTaskIds)
            assertEquals(
                2,
                f.vm.state.value.backgroundTasks
                    ?.liveCount,
            )
            f.stop("a")
            advanceUntilIdle()
            assertEquals(2, f.calls.size)
            assertTrue(logs.any { "code=send_failed" in it })
            assertTrue(logs.none { "secret" in it })
        }

    @Test
    fun finishRemovalAndDisconnectClearBothSets_andReappearanceStartsClosed() =
        runTest {
            val f = fixture()
            f.toggle("a")
            f.toggle("b")
            f.stop("a")
            f.stop("b")
            advanceUntilIdle()
            f.roster.value =
                BackgroundTaskRoster(
                    listOf(task("a").copy(isFinished = true, finish = BackgroundTaskUpdate("", "stopped", "done", null))),
                    0,
                )
            advanceUntilIdle()
            assertTrue(
                f.vm.state.value.expandedBackgroundTaskIds
                    .isEmpty(),
            )
            assertTrue(
                f.vm.state.value.pendingBackgroundTaskIds
                    .isEmpty(),
            )
            assertEquals(
                "stopped",
                f.vm.state.value.backgroundTasks
                    ?.tasks
                    ?.single()
                    ?.finish
                    ?.status,
            )
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            assertEquals(2, f.calls.size)
            f.roster.value = BackgroundTaskRoster(listOf(task("a")), 0)
            advanceUntilIdle()
            assertTrue(
                f.vm.state.value.expandedBackgroundTaskIds
                    .isEmpty(),
            )
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            f.roster.value = null
            advanceUntilIdle()
            assertTrue(
                f.vm.state.value.expandedBackgroundTaskIds
                    .isEmpty(),
            )
            assertTrue(
                f.vm.state.value.pendingBackgroundTaskIds
                    .isEmpty(),
            )
        }

    @Test
    fun staleLocalFailureCannotReleaseNewAttempt_afterRemovalAndReappearance() =
        runTest {
            val f = fixture()
            val first = CompletableDeferred<Result<Unit>>()
            f.send = { first.await() }
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            f.roster.value = BackgroundTaskRoster(emptyList(), 0)
            advanceUntilIdle()
            f.roster.value = BackgroundTaskRoster(listOf(task("a")), 0)
            advanceUntilIdle()
            f.send = { Result.success(Unit) }
            f.toggle("a")
            f.stop("a")
            advanceUntilIdle()
            first.complete(Result.failure(IllegalStateException("old")))
            advanceUntilIdle()
            assertEquals(setOf("a"), f.vm.state.value.pendingBackgroundTaskIds)
            assertEquals(2, f.calls.size)
        }

    @Test
    fun capabilityLossClearsPending_andSeparateHostWithSameIdsIsIndependent() =
        runTest {
            val a = fixture("host-a")
            val b = fixture("host-b")
            a.toggle("a")
            a.stop("a")
            advanceUntilIdle()
            assertTrue(
                b.vm.state.value.pendingBackgroundTaskIds
                    .isEmpty(),
            )
            assertTrue(
                b.vm.state.value.expandedBackgroundTaskIds
                    .isEmpty(),
            )
            b.toggle("a")
            b.stop("a")
            advanceUntilIdle()
            a.support.value = false
            advanceUntilIdle()
            assertTrue(
                a.vm.state.value.pendingBackgroundTaskIds
                    .isEmpty(),
            )
            assertEquals(setOf("a"), b.vm.state.value.pendingBackgroundTaskIds)
        }

    companion object {
        private fun task(id: String) = BackgroundTask(id, "tool", "local_bash", "description", null, null, null, false)
    }
}
