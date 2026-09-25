package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background-task arm of [RemoteConversationRepository.onInbound] (#677, #1042): the four frames reach
 * [RemoteConversationRepository.backgroundTasks] only behind the `interactive` gate, and a malformed frame
 * leaves the single inbound collector running. The merge rules are [BackgroundTaskProjectionTest]'s.
 */
class RemoteConversationRepositoryBackgroundTaskTest {
    @Test
    fun interactiveConnection_foldsAllFourFrames() =
        runTest {
            val (pump, repo) = newRepo(setOf(CAPABILITY_INTERACTIVE))

            pump.push(BackgroundTaskProjectionTest.started("t1"))
            pump.push(BackgroundTaskProjectionTest.rosterFrame(rows = listOf(BackgroundTaskProjectionTest.row("t1")), droppedTasks = 2))
            pump.push(BackgroundTaskProjectionTest.progress("t1", activity = "Reading alpha.txt"))
            runCurrent()
            assertEquals(
                "Reading alpha.txt",
                repo.backgroundTasks.value
                    .getValue("c1")
                    .tasks
                    .single()
                    .progress
                    ?.description,
            )

            pump.push(BackgroundTaskProjectionTest.terminal("t1", "completed"))
            runCurrent()

            val roster = repo.backgroundTasks.value.getValue("c1")
            assertTrue(roster.tasks.single().isFinished)
            assertEquals(2, roster.liveCount)
        }

    @Test
    fun nonInteractiveConnection_ignoresTheFrames() =
        runTest {
            val (pump, repo) = newRepo(emptySet())

            pump.push(BackgroundTaskProjectionTest.started("t1"))
            pump.push(BackgroundTaskProjectionTest.progress("t1"))
            pump.push(BackgroundTaskProjectionTest.rosterFrame(rows = emptyList()))
            runCurrent()

            assertTrue(repo.backgroundTasks.value.isEmpty())
        }

    @Test
    fun malformedFrame_isDroppedAndTheCollectorKeepsRunning() =
        runTest {
            val (pump, repo) = newRepo(setOf(CAPABILITY_INTERACTIVE))

            pump.push(
                BackgroundTaskProjectionTest.envelope(
                    "background_task_roster",
                    """{"conversation_id":"c1","tasks":null,"dropped_tasks":0}""",
                ),
            )
            pump.push(BackgroundTaskProjectionTest.envelope("background_task_progress", """{"conversation_id":"c1","task_id":"t1"}"""))
            pump.push(BackgroundTaskProjectionTest.started("t1"))
            runCurrent()

            assertEquals(
                listOf("t1"),
                repo.backgroundTasks.value
                    .getValue("c1")
                    .tasks
                    .map { it.taskId },
            )
        }

    private fun TestScope.newRepo(capabilities: Set<String>): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
    }

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }
}
