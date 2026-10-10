package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositorySwitchAgentTest {
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
    fun payloadPreservesRequiredStringsAndOptionalEffortAndUsesSharedIds() =
        runTest {
            val (pump, repo) = repository()
            ConversationAgent.entries.forEach { agent ->
                listOf<String?>(null, "", "high").forEach { effort ->
                    listOf("", "  model/secret\n").forEach { model ->
                        val call = async { repo.switchAgent("conversation-secret", agent, model, effort) }
                        runCurrent()
                        val request = pump.sent.last()
                        assertEquals("switch_agent", request.type)
                        assertEquals(
                            buildJsonObject {
                                put("conversation_id", "conversation-secret")
                                put("agent", agent.name.lowercase())
                                put("model", model)
                                if (effort != null) put("effort", effort)
                            },
                            request.payload,
                        )
                        assertNull(request.inReplyTo)
                        pump.push(update("conversation-secret", JsonPrimitive(agent.name.lowercase())))
                        assertTrue(call.await().isSuccess)
                    }
                }
            }
            repo.requestContextUsage("other")
            assertEquals(
                pump.sent.size,
                pump.sent
                    .map { it.id }
                    .distinct()
                    .size,
            )
            assertFalse(logs.any { "secret" in it || "high" in it })
        }

    @Test
    fun confirmationArrivingDuringSendIsNotLost() =
        runTest {
            val (pump, repo) = repository()
            pump.onSend = { pump.push(update("c1", JsonPrimitive("codex"))) }
            assertTrue(repo.switchAgent("c1", ConversationAgent.Codex, "").isSuccess)
            assertEquals(1, pump.sent.size)
            assertEquals(
                ConversationAgent.Codex,
                repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single()
                    .agent,
            )
        }

    @Test
    fun bothCapabilitiesAreRequiredAndFailureIsLocal() =
        runTest {
            listOf(emptySet(), setOf("interactive"), setOf("multi_agent")).forEach { capabilities ->
                val (pump, repo) = repository(capabilities)
                assertFailure(repo.switchAgent("secret", ConversationAgent.Codex, "secret"), SwitchAgentFailure.Category.Unsupported)
                assertTrue(pump.sent.isEmpty())
            }
        }

    @Test
    fun confirmationRequiresExplicitMatchingWireIdentityInBothDirections() =
        runTest {
            ConversationAgent.entries.forEach { target ->
                val (pump, repo) = repository()
                val previous = ConversationAgent.entries.first { it != target }
                pump.push(update("c1", JsonPrimitive(previous.name.lowercase())))
                val call = async { repo.switchAgent("c1", target, "") }
                runCurrent()
                assertEquals(
                    previous,
                    repo
                        .observeConversations(ConversationFilter.All)
                        .first()
                        .single()
                        .agent,
                )
                val id = pump.sent.first { it.type == "switch_agent" }.id
                val invalid =
                    listOf(
                        update("c2", JsonPrimitive(target.name.lowercase())),
                        update("c1", null),
                        update("c1", JsonNull),
                        update("c1", JsonPrimitive("unknown")),
                        update("c1", JsonPrimitive(previous.name.lowercase())),
                        update("c1", JsonPrimitive(42)),
                        update("c1", JsonPrimitive(target.name.lowercase()), valid = false),
                        frame("conversation_updated", """{"agent":"${target.name.lowercase()}"}"""),
                        frame("conversation_updated", "null"),
                        frame("ack", "{}", id),
                        frame("resetting", """{"conversation_id":"c1","active":true,"phase":"wrapping_up","handoff":"pending"}"""),
                        frame(
                            "session_transition",
                            """{"conversation_id":"c1","previous_session_id":"old","new_session_id":"new","reason":"clear"}""",
                        ),
                        error(id + 100, "server.binary_offline", true),
                        error(null, "server.binary_offline", true),
                    )
                invalid.forEach {
                    pump.push(it)
                    runCurrent()
                    assertFalse("${it.type} must not confirm", call.isCompleted)
                }
                advanceTimeBy(100_000)
                runCurrent()
                assertFalse(call.isCompleted)
                val confirmed =
                    async(UnconfinedTestDispatcher(testScheduler)) {
                        val result = call.await()
                        val row = repo.observeConversations(ConversationFilter.All).first().first { it.id == "c1" }
                        assertEquals(target, row.agent)
                        result
                    }
                pump.push(update("c1", JsonPrimitive(target.name.lowercase())))
                assertTrue(confirmed.await().isSuccess)
            }
        }

    @Test
    fun concurrentTargetsAndCommittedSuccessAreIndependent() =
        runTest {
            val (pump, repo) = repository()
            val a = async { repo.switchAgent("a", ConversationAgent.Codex, "") }
            val b = async { repo.switchAgent("b", ConversationAgent.Claude, "") }
            runCurrent()
            pump.push(update("a", JsonPrimitive("codex")))
            pump.push(error(pump.sent[0].id, "server.binary_offline", true))
            pump.push(update("a", JsonPrimitive("codex")))
            runCurrent()
            assertTrue(a.await().isSuccess)
            assertFalse(b.isCompleted)
            pump.push(update("b", JsonPrimitive("claude")))
            assertTrue(b.await().isSuccess)
        }

    @Test
    fun correlatedRefusalsPreserveCategoriesAndRetryabilityWithoutDaemonText() =
        runTest {
            val categories =
                mapOf(
                    "conversation.not_found" to SwitchAgentFailure.Category.ConversationNotFound,
                    "protocol.malformed" to SwitchAgentFailure.Category.Malformed,
                    "protocol.unsupported" to SwitchAgentFailure.Category.Unsupported,
                    "model_list.unavailable" to SwitchAgentFailure.Category.ModelListUnavailable,
                    "server.binary_busy" to SwitchAgentFailure.Category.BinaryBusy,
                    "server.binary_offline" to SwitchAgentFailure.Category.BinaryOffline,
                    "unknown-secret" to SwitchAgentFailure.Category.Failed,
                )
            val (pump, repo) = repository()
            categories.forEach { (code, category) ->
                listOf(false, true).forEach { retryable ->
                    val call = async { repo.switchAgent("request-secret", ConversationAgent.Codex, "model-secret", "effort-secret") }
                    runCurrent()
                    pump.push(error(pump.sent.last().id, code, retryable))
                    val failure = assertFailure(call.await(), category)
                    assertEquals(retryable, failure.retryable)
                    assertNull(failure.cause)
                    assertFalse(failure.toString().contains("secret"))
                }
            }
            assertTrue(logs.all { it.matches(Regex("event=switch_agent outcome=[a-z_]+")) })
        }

    @Test
    fun malformedCorrelatedErrorsReturnSanitizedFallback() =
        runTest {
            val (pump, repo) = repository()
            listOf(
                "null",
                "[]",
                "{}",
                """{"code":"protocol.malformed","retryable":false}""",
                """{"code":"protocol.malformed","message":42,"retryable":false}""",
                """{"code":42,"message":"secret","retryable":false}""",
                """{"code":"protocol.malformed","message":"secret","retryable":"true"}""",
                """{"code":"protocol.malformed","message":"secret","retryable":null}""",
            ).forEach { payload ->
                val call = async { repo.switchAgent("c1", ConversationAgent.Codex, "") }
                runCurrent()
                pump.push(frame("error", payload, pump.sent.last().id))
                val failure = assertFailure(call.await(), SwitchAgentFailure.Category.Failed)
                assertFalse(failure.retryable)
                assertFalse(failure.toString().contains("secret"))
            }
            assertFalse(logs.any { "secret" in it })
        }

    @Test
    fun sendFailureAndReentrantTeardownReleasePendingWork() =
        runTest {
            val (pump, repo) = repository()
            pump.acceptSend = false
            assertFailure(repo.switchAgent("c1", ConversationAgent.Codex, ""), SwitchAgentFailure.Category.Unavailable)
            pump.acceptSend = true
            pump.onSend = { throw IllegalStateException("exception-secret") }
            assertFailure(repo.switchAgent("c1", ConversationAgent.Codex, ""), SwitchAgentFailure.Category.Unavailable)
            pump.onSend = { throw CancellationException("cancellation-secret") }
            val cancelled = runCatching { repo.switchAgent("c1", ConversationAgent.Codex, "") }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            pump.onSend = { repo.endSwitchAgentRequests() }
            assertFailure(repo.switchAgent("c1", ConversationAgent.Codex, ""), SwitchAgentFailure.Category.Unavailable)
            pump.sent.forEach { pump.push(error(it.id, "server.binary_offline", true)) }
            assertFalse(logs.any { "secret" in it })
        }

    @Test
    fun teardownSettlesAllAndRejectsLaterCalls() =
        runTest {
            val (pump, repo) = repository()
            val calls =
                listOf(
                    async { repo.switchAgent("a", ConversationAgent.Codex, "") },
                    async { repo.switchAgent("b", ConversationAgent.Claude, "") },
                )
            runCurrent()
            repo.endSwitchAgentRequests()
            repo.endSwitchAgentRequests()
            calls.forEach { assertFailure(it.await(), SwitchAgentFailure.Category.Unavailable) }
            assertFailure(repo.switchAgent("a", ConversationAgent.Codex, ""), SwitchAgentFailure.Category.Unavailable)
            assertEquals(2, pump.sent.size)
            pump.push(update("a", JsonPrimitive("codex")))
            val (newPump, newRepo) = repository()
            val fresh = async { newRepo.switchAgent("a", ConversationAgent.Codex, "") }
            runCurrent()
            assertFalse(fresh.isCompleted)
            newPump.push(update("a", JsonPrimitive("codex")))
            assertTrue(fresh.await().isSuccess)
        }

    @Test
    fun inboundCompletionAndScopeCancellationEndPendingCalls() =
        runTest {
            listOf(false, true).forEach { cancelScope ->
                val scope = CoroutineScope(Job(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))
                val (pump, repo) = repository(scope = scope)
                val call = async { repo.switchAgent("c1", ConversationAgent.Codex, "") }
                runCurrent()
                if (cancelScope) scope.cancel() else pump.finish()
                assertFailure(call.await(), SwitchAgentFailure.Category.Unavailable)
                assertFailure(repo.switchAgent("c1", ConversationAgent.Codex, ""), SwitchAgentFailure.Category.Unavailable)
                assertEquals(1, pump.sent.size)
            }
        }

    @Test
    fun callerCancellationReleasesRegistration() =
        runTest {
            val (pump, repo) = repository()
            val old = async { repo.switchAgent("c1", ConversationAgent.Codex, "") }
            runCurrent()
            old.cancel()
            old.join()
            val next = async { repo.switchAgent("c1", ConversationAgent.Codex, "") }
            runCurrent()
            pump.push(error(pump.sent.first().id, "server.binary_offline", true))
            runCurrent()
            assertFalse(next.isCompleted)
            pump.push(update("c1", JsonPrimitive("codex")))
            assertTrue(next.await().isSuccess)
            assertTrue(old.isCancelled)
            assertEquals(1, logs.count { it == "event=switch_agent outcome=confirmed" })
        }

    @Test
    fun alreadyCancelledCallerSendsNothing() =
        runTest {
            val (pump, repo) = repository()
            val call =
                async {
                    currentCoroutineContext().cancel()
                    repo.switchAgent("c1", ConversationAgent.Codex, "")
                }
            call.join()
            assertTrue(call.isCancelled)
            assertTrue(pump.sent.isEmpty())
        }

    private fun assertFailure(
        result: Result<Unit>,
        category: SwitchAgentFailure.Category,
    ): SwitchAgentFailure {
        val failure = result.exceptionOrNull()
        assertTrue(failure is SwitchAgentFailure)
        failure as SwitchAgentFailure
        assertEquals(category, failure.category)
        assertEquals("Agent switch failed", failure.message)
        assertNull(failure.cause)
        return failure
    }

    private fun TestScope.repository(
        capabilities: Set<String> = setOf("interactive", "multi_agent"),
        scope: CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to RemoteConversationRepository(pump, scope, negotiatedCapabilities = { capabilities })
    }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var acceptSend = true
        var onSend: (Envelope) -> Unit = {}

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            onSend(envelope)
            return acceptSend
        }

        fun push(envelope: Envelope) {
            check(channel.trySend(envelope).isSuccess)
        }

        fun finish() {
            channel.close()
        }
    }

    private fun update(
        id: String,
        agent: kotlinx.serialization.json.JsonElement?,
        valid: Boolean = true,
    ): Envelope {
        val payload =
            buildJsonObject {
                put("id", id)
                put("name", "channel")
                put("is_promoted", true)
                put("cwd", "/workspace-secret")
                put("last_used_at", if (valid) "2026-10-10T10:00:00Z" else "malformed-secret")
                if (agent != null) put("agent", agent)
            }
        return Envelope(900, "conversation_updated", "2026-10-10T10:00:00Z", payload)
    }

    private fun frame(
        type: String,
        payload: String,
        replyTo: Long? = null,
    ) = Envelope(900, type, "2026-10-10T10:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = replyTo)

    private fun error(
        replyTo: Long?,
        code: String,
        retryable: Boolean,
    ) = frame(
        "error",
        buildJsonObject {
            put("code", code)
            put("message", "daemon-secret")
            put("retryable", retryable)
        }.toString(),
        replyTo,
    )
}
