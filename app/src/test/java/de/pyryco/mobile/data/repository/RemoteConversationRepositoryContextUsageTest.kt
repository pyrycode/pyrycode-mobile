package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context-window reading Claude reports per conversation (#945): the `context_usage` frame, decoded behind the
 * `interactive` gate and held per conversation. Since #1410 the phone asks with `request_context_usage` when a
 * thread opens or its host returns; subscribing alone still asks nothing. Wire SSOT: pyrycode
 * `docs/protocol-mobile.md` § `context_usage` and § "Asking for a context usage reading on demand".
 */
class RemoteConversationRepositoryContextUsageTest {
    // ---- push and reply ---------------------------------------------------------------------------

    // `percentage` is Claude's own number: 50000 / 200000 would be 25, and the reading still says 31.
    @Test
    fun turnEndFrame_nullUntilAFrame_thenVerbatim_latestWins() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            assertEquals(listOf<ContextUsage?>(null), readings)

            pump.push(contextUsage("c1", total = 50_000, max = 200_000, percentage = 31, id = 1L))
            runCurrent()
            pump.push(contextUsage("c1", total = 120_000, max = 1_000_000, percentage = 12, id = 2L))
            runCurrent()

            assertEquals(
                listOf(
                    null,
                    ContextUsage(totalTokens = 50_000, maxTokens = 200_000, percentage = 31, asOf = null),
                    ContextUsage(totalTokens = 120_000, maxTokens = 1_000_000, percentage = 12, asOf = null),
                ),
                readings,
            )
        }

    @Test
    fun laterFrame_replacesTheReading_andARememberedAnswerKeepsItsAsOf() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            pump.push(contextUsage("c1", total = 10_000, max = 200_000, percentage = 5, id = 1L))
            runCurrent()

            pump.push(
                contextUsage(
                    "c1",
                    total = 80_000,
                    max = 200_000,
                    percentage = 40,
                    asOf = "2026-09-16T08:30:00Z",
                    inReplyTo = 99L,
                    id = 2L,
                ),
            )
            runCurrent()

            assertEquals(
                ContextUsage(80_000, 200_000, 40, asOf = Instant.parse("2026-09-16T08:30:00Z")),
                readings.last(),
            )
        }

    // ---- the ask (#1410) -------------------------------------------------------------------------------

    // The thread asks through [ConversationRepository.requestContextUsage]; observing alone never sends.
    @Test
    fun subscription_sendsNothing() =
        runTest {
            val (pump, repo) = repo()
            val first = backgroundScope.launch { repo.observeContextUsage("c1").collect {} }
            val second = backgroundScope.launch { repo.observeContextUsage("c1").collect {} }
            runCurrent()
            first.cancel()
            second.cancel()
            runCurrent()
            collect(repo.observeContextUsage("c1"))
            collect(repo.observeContextUsage(""))
            runCurrent()

            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun requestContextUsage_sendsOneAsk_namingTheConversation() =
        runTest {
            val (pump, repo) = repo()

            repo.requestContextUsage("c1")
            repo.requestContextUsage("c2")

            assertEquals(listOf("request_context_usage", "request_context_usage"), pump.sent.map { it.type })
            assertEquals(
                listOf("""{"conversation_id":"c1"}""", """{"conversation_id":"c2"}"""),
                pump.sent.map { it.payload.toString() },
            )
            assertEquals(
                "each ask takes a fresh envelope id",
                2,
                pump.sent
                    .map { it.id }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun requestContextUsage_withAnEmptyIdOrWithoutInteractive_sendsNothing() =
        runTest {
            val (pump, repo) = repo()
            repo.requestContextUsage("")
            assertTrue(pump.sent.isEmpty())

            val bare = FakeSessionPump()
            RemoteConversationRepository(bare, backgroundScope).requestContextUsage("c1")
            assertTrue(bare.sent.isEmpty())
        }

    @Test
    fun requestContextUsage_aRefusedOrThrowingSend_isAbsorbed() =
        runTest {
            val throwing = FakeSessionPump(sendThrows = true)
            RemoteConversationRepository(throwing, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
                .requestContextUsage("c1")
            assertEquals(1, throwing.sent.size)

            // A send the transport refuses is dropped, not retried.
            val refusing = FakeSessionPump(sendRefuses = true)
            RemoteConversationRepository(refusing, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
                .requestContextUsage("c1")
            assertEquals(1, refusing.sent.size)
        }

    @Test
    fun theCorrelatedReply_replacesTheReading() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            repo.requestContextUsage("c1")
            val ask = pump.sent.single().id

            pump.push(contextUsage("c1", total = 40_000, max = 200_000, percentage = 20, inReplyTo = ask, id = 1L))
            runCurrent()

            assertEquals(listOf(null, ContextUsage(40_000, 200_000, 20, asOf = null)), readings)
        }

    // Both refusals leave the reading as it was, throw nothing into the collector, and the next frame still applies.
    @Test
    fun aRefusedAsk_leavesTheReadingUnchanged_andTheCollectorAlive() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            pump.push(contextUsage("c1", total = 50_000, max = 200_000, percentage = 25, id = 1L))
            runCurrent()

            repo.requestContextUsage("c1")
            pump.push(refusal(pump.sent.last().id, "context_usage.unavailable", id = 2L))
            repo.requestContextUsage("c1")
            pump.push(refusal(pump.sent.last().id, "conversation.not_found", id = 3L))
            runCurrent()

            assertEquals(listOf(null, ContextUsage(50_000, 200_000, 25, asOf = null)), readings)

            pump.push(contextUsage("c1", total = 60_000, max = 200_000, percentage = 30, id = 4L))
            runCurrent()
            assertEquals(ContextUsage(60_000, 200_000, 30, asOf = null), readings.last())
        }

    @Test
    fun closedInteractiveGate_doesNotDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()

            pump.push(contextUsage("c1", total = 1, max = 2, percentage = 50, id = 1L))
            runCurrent()

            assertEquals(listOf<ContextUsage?>(null), readings)
            assertTrue(pump.sent.isEmpty())
        }

    // ---- malformed ----------------------------------------------------------------------------------

    @Test
    fun malformedOrNegativeFrame_droppedCollectorSurvives_neverAZeroReading() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            pump.push(contextUsage("c1", total = 50_000, max = 200_000, percentage = 25, id = 1L))
            runCurrent()

            // Missing each scalar in turn.
            pump.push(probe("context_usage", """{"total_tokens":1,"max_tokens":2,"percentage":3}""", id = 2L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","max_tokens":2,"percentage":3}""", id = 3L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":1,"percentage":3}""", id = 4L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":1,"max_tokens":2}""", id = 5L))
            // Wrong types and explicit nulls.
            pump.push(probe("context_usage", """{"conversation_id":1,"total_tokens":1,"max_tokens":2,"percentage":3}""", id = 6L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":{},"max_tokens":2,"percentage":3}""", id = 7L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":1,"max_tokens":null,"percentage":3}""", id = 8L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":1,"max_tokens":2,"percentage":3.5}""", id = 9L))
            pump.push(probe("context_usage", """{"conversation_id":"c1","total_tokens":1,"max_tokens":2,"percentage":null}""", id = 10L))
            // Structurally valid, but a negative percentage or an unreadable `as_of`.
            pump.push(contextUsage("c1", total = 1, max = 2, percentage = -1, id = 11L))
            pump.push(contextUsage("c1", total = 1, max = 2, percentage = 3, asOf = "yesterday", id = 12L))
            pump.push(probe("context_usage", "[]", id = 13L))
            runCurrent()

            assertEquals(listOf(null, ContextUsage(50_000, 200_000, 25, asOf = null)), readings)

            pump.push(contextUsage("c1", total = 60_000, max = 200_000, percentage = 30, id = 14L))
            runCurrent()
            assertEquals(ContextUsage(60_000, 200_000, 30, asOf = null), readings.last())
        }

    // ---- session_transition ---------------------------------------------------------------------------

    @Test
    fun sessionTransition_clearsTheReading_sendsNothing_andTheNextTurnFillsIt() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            pump.push(contextUsage("c1", total = 150_000, max = 200_000, percentage = 75, id = 1L))
            runCurrent()

            pump.push(sessionTransition("c1", id = 2L))
            runCurrent()
            assertEquals(listOf(null, ContextUsage(150_000, 200_000, 75, asOf = null), null), readings)
            assertTrue(pump.sent.isEmpty())

            pump.push(contextUsage("c1", total = 9_000, max = 200_000, percentage = 4, id = 3L))
            runCurrent()
            assertEquals(ContextUsage(9_000, 200_000, 4, asOf = null), readings.last())
        }

    @Test
    fun sessionTransition_ofAnUnobservedConversation_clears() =
        runTest {
            val (pump, repo) = repo()
            pump.push(contextUsage("c1", total = 150_000, max = 200_000, percentage = 75, id = 1L))
            val watcher = backgroundScope.launch { repo.observeContextUsage("c1").collect {} }
            runCurrent()
            watcher.cancel()
            runCurrent()

            pump.push(sessionTransition("c1", id = 2L))
            runCurrent()

            val readings = collect(repo.observeContextUsage("c1"))
            runCurrent()
            assertEquals("the transition still cleared the reading", listOf<ContextUsage?>(null), readings)
        }

    // ---- isolation ------------------------------------------------------------------------------------

    @Test
    fun anotherConversationsFrameOrTransition_neverTouchesThisReading() =
        runTest {
            val (pump, repo) = repo()
            val c1 = collect(repo.observeContextUsage("c1"))
            runCurrent()
            pump.push(contextUsage("c1", total = 50_000, max = 200_000, percentage = 25, id = 1L))
            runCurrent()

            pump.push(contextUsage("c2", total = 190_000, max = 200_000, percentage = 95, id = 2L))
            pump.push(sessionTransition("c2", id = 3L))
            runCurrent()

            assertEquals("c1 neither changed nor re-emitted", listOf(null, ContextUsage(50_000, 200_000, 25, asOf = null)), c1)
        }

    // A frame correlated to some other request but naming c2 lands under c2, never c1.
    @Test
    fun reply_routesByThePayloadsConversationId_notByInReplyTo() =
        runTest {
            val (pump, repo) = repo()
            val c1 = collect(repo.observeContextUsage("c1"))
            runCurrent()

            pump.push(contextUsage("c2", total = 1_000, max = 200_000, percentage = 1, inReplyTo = 99L, id = 1L))
            runCurrent()

            assertEquals(listOf<ContextUsage?>(null), c1)
            val c2 = collect(repo.observeContextUsage("c2"))
            runCurrent()
            assertEquals(listOf<ContextUsage?>(ContextUsage(1_000, 200_000, 1, asOf = null)), c2)
        }

    // ---- reconnect --------------------------------------------------------------------------------------

    @Test
    fun reconnect_throughTheFacade_dropsTheOldReading_andAsksNothing() =
        runTest {
            val (pumpA, repoA) = repo()
            val (pumpB, repoB) = repo()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)
            val readings = collect(facade.observeContextUsage("c1"))
            runCurrent()
            pumpA.push(contextUsage("c1", total = 50_000, max = 200_000, percentage = 25, id = 1L))
            runCurrent()

            current.value = repoB
            runCurrent()

            assertTrue(pumpA.sent.isEmpty())
            assertTrue(pumpB.sent.isEmpty())
            assertEquals(listOf(null, ContextUsage(50_000, 200_000, 25, asOf = null), null), readings)
        }

    // ---- Fixtures ---------------------------------------------------------------------------------------

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> {
        val emissions = mutableListOf<T>()
        backgroundScope.launch { flow.collect { emissions += it } }
        return emissions
    }

    /**
     * A `context_usage` envelope with the four scalars, an optional `as_of`, and the inventories the phone ignores
     * (each always on the wire as an array, with its dropped count).
     */
    private fun contextUsage(
        conversationId: String,
        total: Long,
        max: Long,
        percentage: Int,
        asOf: String? = null,
        inReplyTo: Long? = null,
        id: Long,
    ): Envelope {
        val asOfKey = asOf?.let { ""","as_of":"$it"""" } ?: ""
        val payload =
            """{"conversation_id":"$conversationId","model":"claude-opus-5-5","total_tokens":$total,""" +
                """"max_tokens":$max,"percentage":$percentage,"categories":[{"name":"Messages","tokens":$total}],""" +
                """"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],""" +
                """"dropped_memory_files":0$asOfKey}"""
        return Envelope(id = id, type = "context_usage", ts = TS, payload = MobileJson.parseToJsonElement(payload), inReplyTo = inReplyTo)
    }

    private fun sessionTransition(
        conversationId: String,
        id: Long,
    ): Envelope =
        probe(
            "session_transition",
            """{"conversation_id":"$conversationId","previous_session_id":"s1","new_session_id":"s2",""" +
                """"reason":"clear","occurred_at":"$TS","workspace_cwd":null}""",
            id,
        )

    private fun refusal(
        inReplyTo: Long,
        code: String,
        id: Long,
    ): Envelope =
        Envelope(
            id = id,
            type = "error",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"static","retryable":false}"""),
            inReplyTo = inReplyTo,
        )

    private fun probe(
        type: String,
        payload: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump(
        private val sendThrows: Boolean = false,
        private val sendRefuses: Boolean = false,
    ) : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            check(!sendThrows) { "transport closed" }
            return !sendRefuses
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-23T10:00:00Z"
    }
}
