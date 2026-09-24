package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.BackgroundTaskProjection
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.FinishedBackgroundTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The thread's background-task reading (#678): the open conversation's roster and live count, read
 * through the two per-conversation lambdas the Koin factory binds to the host's coordinator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelBackgroundTasksTest {
    private val oldSink = RelayLog.sink

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    private val projection = BackgroundTaskProjection(FinishedBackgroundTasks())
    private val requestedIds = mutableListOf<String>()

    private fun TestScope.collectedVm(bound: Boolean): ThreadViewModel {
        val handle = SavedStateHandle(mapOf("conversationId" to CONV, "serverId" to SERVER))
        val vm =
            if (bound) {
                // Bound exactly as the coordinator binds them: its observeBackgroundTasks and
                // observeLiveBackgroundTaskCount over the connection's projection.
                ThreadViewModel(
                    handle,
                    FakeConversationRepository(),
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    backgroundTasks = { id ->
                        requestedIds += id
                        projection.rosters.map { it[id] }.distinctUntilChanged()
                    },
                    backgroundTaskCount = { id ->
                        requestedIds += id
                        projection.rosters.map { it[id]?.liveCount ?: 0 }.distinctUntilChanged()
                    },
                )
            } else {
                ThreadViewModel(handle, FakeConversationRepository(), FakeConnectionStateSource(), ComposerDraftStore())
            }
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun demoOrNonRelayHost_readsNoReportAndZero() =
        runTest {
            val vm = collectedVm(bound = false)

            assertEquals(0, vm.state.value.backgroundTaskCount)
            assertNull(vm.state.value.backgroundTasks)
        }

    @Test
    fun startedTask_countsOne_andItsCompletedUpdate_bringsTheCountToZero() =
        runTest {
            val vm = collectedVm(bound = true)
            assertNull(vm.state.value.backgroundTasks)
            assertEquals(0, vm.state.value.backgroundTaskCount)

            projection.apply(BackgroundTaskProjectionTest.started("t1", conversationId = CONV))
            advanceUntilIdle()
            assertEquals(1, vm.state.value.backgroundTaskCount)
            assertEquals(
                listOf("t1"),
                vm.state.value.backgroundTasks
                    ?.tasks
                    ?.map { it.taskId },
            )

            projection.apply(BackgroundTaskProjectionTest.terminal("t1", "completed", conversationId = CONV))
            advanceUntilIdle()
            assertEquals(0, vm.state.value.backgroundTaskCount)
            // The finished task stays listed, so the panel agrees with a count that excludes it.
            assertEquals(
                true,
                vm.state.value.backgroundTasks
                    ?.tasks
                    ?.single()
                    ?.isFinished,
            )
        }

    @Test
    fun anotherConversationsTasks_doNotReachThisThread() =
        runTest {
            val vm = collectedVm(bound = true)

            projection.apply(BackgroundTaskProjectionTest.started("t9", conversationId = "other"))
            advanceUntilIdle()

            assertEquals(0, vm.state.value.backgroundTaskCount)
            assertNull(vm.state.value.backgroundTasks)
            assertTrue(requestedIds.isNotEmpty())
            assertTrue(requestedIds.all { it == CONV })
        }

    @Test
    fun droppedTasks_countTowardTheLiveCount() =
        runTest {
            val vm = collectedVm(bound = true)

            projection.apply(
                BackgroundTaskProjectionTest.rosterFrame(
                    listOf(BackgroundTaskProjectionTest.row("t1")),
                    conversationId = CONV,
                    droppedTasks = 3,
                ),
            )
            advanceUntilIdle()

            assertEquals(4, vm.state.value.backgroundTaskCount)
            assertEquals(
                3,
                vm.state.value.backgroundTasks
                    ?.droppedTasks,
            )
        }

    private companion object {
        const val CONV = "conv-1"
        const val SERVER = "server-1"
    }
}
