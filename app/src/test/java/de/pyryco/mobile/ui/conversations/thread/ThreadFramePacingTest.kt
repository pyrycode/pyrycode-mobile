package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThreadFramePacingTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    private class ManualFrames {
        private val ticks = Channel<Long>(Channel.UNLIMITED)
        var waiting = 0
        var nanos = 0L

        suspend fun await(): Long {
            waiting++
            return try {
                ticks.receive()
            } finally {
                waiting--
            }
        }

        fun fire(period: Long = 16_666_667L) {
            assertEquals("one frame awaiter per active destination", 1, waiting)
            nanos += period
            check(ticks.trySend(nanos).isSuccess)
        }
    }

    @Test fun completeStateInvariant_finiteBurstDeliversFinalWithoutNewInput() =
        runTest {
            val frames = ManualFrames()
            val seen = mutableListOf<Int>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    flowOf(1, 2, 3)
                        .paceThreadContent(ThreadContentScheduling(StandardTestDispatcher(testScheduler), frames::await))
                        .collect { seen += it }
                }
            runCurrent()
            assertTrue(seen.isEmpty())
            frames.fire()
            runCurrent()
            assertEquals(listOf(3), seen)
            assertTrue(job.isCompleted)
            assertEquals(0, frames.waiting)
        }
}
