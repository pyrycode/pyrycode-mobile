package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResetContextUsageTest {
    @Test
    fun serializedClearAndReply_areObservedEvenWhenCallerIsDelayed() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            val observed = repository.observeContextUsage("c1")
            val fresh = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { observed.awaitResetContextUsage() }
            // The old live pipeline, with an undispatched subscription but scheduled resumptions.
            val scheduled =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    observed.dropWhile { it != null }.filterNotNull().first()
                }

            // One inbound collector consumes both queued frames without yielding to the caller.
            // A different current value independently proves the product reply was applied.
            // The repeated-reset regression below separately covers equal-valued fresh replies.
            pump.push(transition())
            pump.push(reading(total = 4_000))
            runCurrent()

            val reply = USAGE.copy(totalTokens = 4_000)
            assertEquals(reply, observed.first())
            assertFalse("the scheduled negative control misses the clear despite a product reply", scheduled.isCompleted)
            assertTrue("the live observer must receive the clear and the fresh reply", fresh.isCompleted)
            assertEquals(reply, fresh.await())
        }

    @Test
    fun readingWithoutClear_cannotSatisfyFreshness() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeContextUsage("c1").awaitResetContextUsage()
                }
            pump.push(reading(total = 4_000))
            runCurrent()

            assertFalse("a newer pre-transition reading is not reset freshness", fresh.isCompleted)
        }

    @Test
    fun clearWithoutReply_staysPending() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeContextUsage("c1").awaitResetContextUsage()
                }
            pump.push(transition())
            runCurrent()

            assertEquals(null, repository.observeContextUsage("c1").first())
            assertFalse("a missing product response cannot pass", fresh.isCompleted)
        }

    @Test
    fun anotherConversationsReset_cannotSatisfyFreshness() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeContextUsage("c1").awaitResetContextUsage()
                }
            pump.push(transition("c2"))
            pump.push(reading("c2"))
            runCurrent()

            assertFalse("another conversation's clear/reply cannot satisfy the wait", fresh.isCompleted)
        }

    @Test
    fun repeatedResets_eachRequireTheirOwnClearAndReply() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            repeat(2) {
                val fresh =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        repository.observeContextUsage("c1").awaitResetContextUsage()
                    }
                assertFalse("the previous reset's reading cannot pass", fresh.isCompleted)
                pump.push(transition())
                pump.push(reading())
                runCurrent()

                assertTrue("each reset must resolve independently", fresh.isCompleted)
                assertEquals(USAGE, fresh.await())
            }
        }

    @Test
    fun cancelledWatcher_doesNotConsumeALaterReset() =
        runTest {
            val (pump, repository) = repository()
            pump.push(reading())
            runCurrent()
            val cancelled =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeContextUsage("c1").awaitResetContextUsage()
                }
            cancelled.cancel()
            runCurrent()
            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    repository.observeContextUsage("c1").awaitResetContextUsage()
                }
            pump.push(transition())
            pump.push(reading())
            runCurrent()

            assertTrue(cancelled.isCancelled)
            assertTrue(fresh.isCompleted)
            assertEquals(USAGE, fresh.await())
        }

    private fun TestScope.repository(): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun reading(
        conversation: String = "c1",
        total: Long = USAGE.totalTokens,
    ): Envelope =
        frame(
            "context_usage",
            """{"conversation_id":"$conversation","model":"claude-opus-5-5","total_tokens":$total,"max_tokens":200000,"percentage":1,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0}""",
        )

    private fun transition(conversation: String = "c1"): Envelope =
        frame(
            "session_transition",
            """{"conversation_id":"$conversation","previous_session_id":"s1","new_session_id":"s2","reason":"clear","occurred_at":"2026-10-09T10:00:00Z","workspace_cwd":null}""",
        )

    private fun frame(
        type: String,
        payload: String,
    ): Envelope = Envelope(id = 1, type = type, ts = "2026-10-09T10:00:00Z", payload = MobileJson.parseToJsonElement(payload))

    private class Pump : SessionPump {
        private val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = frames.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            check(frames.trySend(envelope).isSuccess)
        }
    }

    private companion object {
        val USAGE = ContextUsage(2_000, 200_000, 1, asOf = null)
    }
}
