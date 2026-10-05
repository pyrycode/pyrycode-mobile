package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryStopTaskTest {
    private val logs = mutableListOf<String>()
    private val previousEnabled = RelayLog.enabled
    private val previousSink = RelayLog.sink

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After
    fun restoreLogs() {
        RelayLog.enabled = previousEnabled
        RelayLog.sink = previousSink
    }

    @Test
    fun helloAdvertisesStopDetectionAlongsideInteractive() {
        val hello = HelloClientPayload(deviceName = "phone", clientVersion = "1.0.0", token = "secret")
        assertTrue(hello.capabilities.contains("stop_background_task"))
        assertTrue(hello.capabilities.contains(CAPABILITY_INTERACTIVE))
        val encoded = MobileJson.encodeToJsonElement(hello).toString()
        assertTrue(encoded.contains("stop_background_task"))
    }

    @Test
    fun sendsExactlyOneVerbatimEnvelopePerCallAndReturnsWithoutReplyOrStateMutation() =
        runTest {
            val (pump, repo) = repository()
            pump.push(BackgroundTaskProjectionTest.started("task"))
            runCurrent()
            pump.push(
                Envelope(
                    2,
                    "turn_state",
                    "2026-10-06T10:00:00Z",
                    MobileJson.parseToJsonElement("""{"conversation_id":"c1","state":"thinking"}"""),
                ),
            )
            val tasks = repo.backgroundTasks.value
            val messages = repo.observeMessages("c1").first()
            val phase = repo.observeTurnPhase("c1").first()
            pump.sent.clear()
            val conversation = "opaque/\"conversation\n"
            val task = "../opaque/\"task\n"
            assertTrue(repo.stopBackgroundTask(conversation, task).isSuccess)
            assertTrue(repo.stopBackgroundTask("c1", "task").isSuccess)
            assertEquals(2, pump.sent.size)
            val request = pump.sent.first()
            assertEquals("stop_background_task", request.type)
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"opaque/\"conversation\n","task_id":"../opaque/\"task\n"}"""),
                request.payload,
            )
            Instant.parse(request.ts)
            assertTrue(request.id > 0)
            assertNotEquals(request.id, pump.sent.last().id)
            assertNull(request.inReplyTo)
            assertNull(request.eventId)
            assertEquals(tasks, repo.backgroundTasks.value)
            assertEquals(messages, repo.observeMessages("c1").first())
            assertEquals(phase, repo.observeTurnPhase("c1").first())
            assertFalse(logs.any { conversation in it || task in it })
        }

    @Test
    fun refusalDuringSendUsesOriginalPairAndIsConsumedOnce() =
        runTest {
            val (pump, repo) = repository()
            val origin = collect(repo.observeBackgroundTaskStopRefusals("origin"))
            val reflected = collect(repo.observeBackgroundTaskStopRefusals("reflected-secret"))
            pump.onSend = { pump.push(error(it.id)) }
            assertTrue(repo.stopBackgroundTask("origin", "original-task").isSuccess)
            assertEquals(listOf("original-task"), origin)
            assertTrue(reflected.isEmpty())
            pump.push(error(pump.sent.single().id))
            runCurrent()
            assertEquals(listOf("original-task"), origin)
            assertFalse(logs.any { "original-task" in it || "origin" in it || "reflected-secret" in it || "daemon-secret" in it })
        }

    @Test
    fun malformedMissingUnknownAndOtherErrorsDoNotSignalOrConsumeValidCorrelation() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            repo.stopBackgroundTask("c1", "t1")
            val id = pump.sent.single().id
            pump.push(error(null))
            pump.push(error(id + 100))
            pump.push(error(id, """{"code":"different","message":"daemon-secret","retryable":false}"""))
            listOf(
                "null",
                "[]",
                "{}",
                """{"code":"stop_background_task.refused","retryable":false}""",
                """{"code":"stop_background_task.refused","message":42,"retryable":false}""",
                """{"code":"stop_background_task.refused","message":"x","retryable":"false"}""",
                """{"code":"stop_background_task.refused","message":"x","retryable":null}""",
            ).forEach { pump.push(error(id, it)) }
            runCurrent()
            assertTrue(events.isEmpty())
            pump.push(error(id))
            runCurrent()
            assertEquals(listOf("t1"), events)
        }

    @Test
    fun terminalUpdateRetiresEvenAnUnlistedTaskAndOnlyItsConversation() =
        runTest {
            val (pump, repo) = repository()
            val a = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            val b = collect(repo.observeBackgroundTaskStopRefusals("c2"))
            repo.stopBackgroundTask("c1", "shared")
            repo.stopBackgroundTask("c2", "shared")
            pump.push(BackgroundTaskProjectionTest.terminal("shared", "custom-terminal", conversationId = "c1"))
            pump.sent.forEach { pump.push(error(it.id)) }
            runCurrent()
            assertTrue(a.isEmpty())
            assertEquals(listOf("shared"), b)
        }

    @Test
    fun rosterOmissionRetiresOnlyOmittedTasksInThatConversation() =
        runTest {
            val (pump, repo) = repository()
            val a = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            val b = collect(repo.observeBackgroundTaskStopRefusals("c2"))
            repo.stopBackgroundTask("c1", "dropped")
            repo.stopBackgroundTask("c1", "retained")
            repo.stopBackgroundTask("c2", "dropped")
            pump.push(BackgroundTaskProjectionTest.rosterFrame(rows = listOf(BackgroundTaskProjectionTest.row("retained"))))
            pump.sent.forEach { pump.push(error(it.id)) }
            runCurrent()
            assertEquals(listOf("retained"), a)
            assertEquals(listOf("dropped"), b)
        }

    @Test
    fun newerAttemptCannotBeRefusedByAnOlderAttempt() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            repeat(2) { assertTrue(repo.stopBackgroundTask("c1", "t1").isSuccess) }
            pump.push(error(pump.sent.first().id))
            runCurrent()
            assertTrue(events.isEmpty())
            pump.push(error(pump.sent.last().id))
            runCurrent()
            assertEquals(listOf("t1"), events)
        }

    @Test
    fun failedSendRetiresCorrelationAndKeepsTaskData() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            pump.push(BackgroundTaskProjectionTest.started("t1"))
            runCurrent()
            val before = repo.backgroundTasks.value
            pump.acceptSend = false
            assertTrue(repo.stopBackgroundTask("c1", "t1").isFailure)
            pump.push(error(pump.sent.single().id))
            runCurrent()
            assertTrue(events.isEmpty())
            assertEquals(before, repo.backgroundTasks.value)
            pump.acceptSend = true
            repo.stopBackgroundTask("c1", "t1")
            pump.push(error(pump.sent.first().id))
            pump.push(error(pump.sent.last().id))
            runCurrent()
            assertEquals(listOf("t1"), events)
        }

    @Test
    fun failureOfOlderSendDoesNotRetireConcurrentNewerAttempt() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            pump.acceptSend = false
            pump.onSend = {
                pump.acceptSend = true
                pump.onSend = {}
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    assertTrue(repo.stopBackgroundTask("c1", "t1").isSuccess)
                }
            }
            assertTrue(repo.stopBackgroundTask("c1", "t1").isFailure)
            pump.sent.forEach { pump.push(error(it.id)) }
            runCurrent()
            assertEquals(listOf("t1"), events)
        }

    @Test
    fun nonterminalAndMalformedTaskFramesDoNotRetireTracking() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            repo.stopBackgroundTask("c1", "t1")
            pump.push(BackgroundTaskProjectionTest.midLife("t1", "untrusted-update"))
            pump.push(BackgroundTaskProjectionTest.progress("t1"))
            pump.push(BackgroundTaskProjectionTest.rosterFrame(rows = emptyList(), droppedTasks = -1))
            pump.push(
                BackgroundTaskProjectionTest.envelope(
                    "background_task_updated",
                    """{"conversation_id":"c1","task_id":"t1","status":"completed","patch":null}""",
                ),
            )
            pump.push(error(pump.sent.single().id))
            runCurrent()
            assertEquals(listOf("t1"), events)
        }

    @Test
    fun unsupportedAndEndedRepositoryFailWithoutSendAndEndClearsCorrelation() =
        runTest {
            val (pump, repo) = repository(emptySet())
            assertFalse(repo.supportsBackgroundTaskStop)
            assertTrue(repo.stopBackgroundTask("c1", "t1").isFailure)
            assertTrue(pump.sent.isEmpty())
            val (openPump, openRepo) = repository()
            assertTrue(openRepo.supportsBackgroundTaskStop)
            openRepo.stopBackgroundTask("c1", "t1")
            val events = collect(openRepo.observeBackgroundTaskStopRefusals("c1"))
            openRepo.endBackgroundTaskStops()
            openPump.push(error(openPump.sent.single().id))
            runCurrent()
            assertFalse(openRepo.supportsBackgroundTaskStop)
            assertTrue(openRepo.stopBackgroundTask("c1", "t1").isFailure)
            assertEquals(1, openPump.sent.size)
            assertTrue(events.isEmpty())
        }

    @Test
    fun inboundCompletionDisablesAdmission() =
        runTest {
            val (pump, repo) = repository()
            pump.finish()
            runCurrent()
            assertFalse(repo.supportsBackgroundTaskStop)
            assertTrue(repo.stopBackgroundTask("c1", "t1").isFailure)
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun sendExceptionsAreStaticAndCancellationPropagatesWithoutCorrelation() =
        runTest {
            val (pump, repo) = repository()
            val events = collect(repo.observeBackgroundTaskStopRefusals("c1"))
            pump.onSend = { throw IllegalStateException("exception-secret") }
            val result = repo.stopBackgroundTask("c1", "t1")
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull().toString().contains("exception-secret"))
            pump.onSend = { throw CancellationException("cancellation-secret") }
            val cancellation = runCatching { repo.stopBackgroundTask("c1", "t1") }.exceptionOrNull()
            assertTrue(cancellation is CancellationException)
            pump.sent.forEach { pump.push(error(it.id)) }
            runCurrent()
            assertTrue(events.isEmpty())
            assertFalse(logs.any { "exception-secret" in it || "cancellation-secret" in it })
        }

    private fun TestScope.repository(
        capabilities: Set<String> = setOf(CAPABILITY_INTERACTIVE, "stop_background_task"),
    ): Pair<FakePump, RemoteConversationRepository> {
        val pump = FakePump()
        return pump to
            RemoteConversationRepository(
                pump,
                CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                negotiatedCapabilities = { capabilities },
            )
    }

    private fun TestScope.collect(flow: Flow<String>): MutableList<String> {
        val events = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { events += it } }
        return events
    }

    private class FakePump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var acceptSend = true
        var onSend: (Envelope) -> Unit = {}

        override fun send(envelope: Envelope): Boolean {
            val accepted = acceptSend
            sent += envelope
            onSend(envelope)
            return accepted
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun finish() {
            channel.close()
        }
    }

    companion object {
        private const val REFUSAL =
            """{"code":"stop_background_task.refused","message":"daemon-secret","retryable":false,""" +
                """"conversation_id":"reflected-secret"}"""

        fun error(
            id: Long?,
            payload: String = REFUSAL,
        ) = Envelope(900, "error", "2026-10-06T10:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = id)
    }
}
