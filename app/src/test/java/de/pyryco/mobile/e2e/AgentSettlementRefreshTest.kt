package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.added
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSettlementRefreshTest {
    private val previousSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun refreshCertifiesLaterRowsThroughCurrentSource() =
        runTest {
            val fixture = Fixture(this)
            fixture.seed()
            val launched = fixture.reading()
            val family = requireNotNull(launched.agentFamily(launched.owner))
            fixture.settle()
            val stale = fixture.reading()
            assertEquals(40L, stale.sourceSnapshot?.checkpoint)
            assertEquals(8uL, stale.sourceSnapshot?.coverage?.lowerOrder)
            assertEquals(27uL, stale.sourceSnapshot?.coverage?.upperOrder)
            assertEquals(
                29L,
                stale.records
                    .single { it.fact?.id == 9L }
                    .fact
                    ?.order,
            )
            assertEquals(
                37L,
                stale.records
                    .single { it.fact?.id == 10L }
                    .fact
                    ?.order,
            )
            assertTrue(stale.settledAgentPrecedes(stale.owner, requireNotNull(stale.agentFamily(stale.owner)), "after1783"))
            assertNull(stale.settledAgentAfterRecovery(launched, family, "after1783", fixture.support.value))
            val before = fixture.pump.sent.size
            val proof = async { awaitCertifiedAgentSettlement(fixture.route, "chat", launched, family, "after1783") }
            runCurrent()
            assertEquals("stale coverage must drive a real source query", before + 1, fixture.pump.sent.size)
            val refresh = fixture.pump.sent.last()
            assertEquals(JsonPrimitive(0), (refresh.payload as JsonObject)["version"])
            fixture.batch(refresh, fixture.items(true))
            runCurrent()
            assertFalse("arrived rows are not certification", proof.isCompleted)
            fixture.terminal(refresh, 40, window = true)
            runCurrent()
            val certified = proof.await()
            assertNotNull(certified.settledAgentAfterRecovery(launched, family, "after1783", fixture.support.value))
            assertEquals(41uL, certified.sourceSnapshot?.coverage?.upperOrder)
            assertEquals(1, fixture.releases)
            assertEquals("the destination's interest survives proof completion", 1, fixture.interests)
            fixture.destination.close()
        }

    @Test fun replacementRetiresOldInterestAndRequiresCurrentCertification() =
        runTest {
            val fixture = Fixture(this)
            fixture.seed()
            val launched = fixture.reading()
            val family = requireNotNull(launched.agentFamily(launched.owner))
            fixture.settle()
            val proof = async { awaitCertifiedAgentSettlement(fixture.route, "chat", launched, family, "after1783") }
            runCurrent()
            val retiredRequest = fixture.pump.sent.last()
            val retired = fixture.repository
            fixture.support.value = ThreadSupport.Unverified
            runCurrent()
            assertEquals(1, fixture.releases)
            assertFalse(proof.isCompleted)
            fixture.replace()
            runCurrent()
            val request = fixture.pump.sent.last()
            assertTrue(retired !== fixture.repository)
            fixture.batch(retiredRequest, fixture.items(true), pump = fixture.oldPump)
            fixture.terminal(retiredRequest, 40, window = true, pump = fixture.oldPump)
            runCurrent()
            assertFalse("retired source cannot finish the proof", proof.isCompleted)
            fixture.batch(request, fixture.items(true))
            fixture.terminal(request, 40, window = true)
            runCurrent()
            val current = proof.await()
            assertTrue(current.owner.connection === fixture.repository)
            assertNotNull(current.settledAgentAfterRecovery(launched, family, "after1783", fixture.support.value))
            assertEquals(2, fixture.releases)
            fixture.destination.close()
        }

    @Test fun cancellationReleasesInterest() =
        runTest {
            val fixture = Fixture(this)
            fixture.seed()
            val launched = fixture.reading()
            val family = requireNotNull(launched.agentFamily(launched.owner))
            val proof = async { awaitCertifiedAgentSettlement(fixture.route, "chat", launched, family, "after1783") }
            runCurrent()
            assertEquals(2, fixture.interests)
            proof.cancelAndJoin()
            assertEquals(1, fixture.releases)
            assertEquals(1, fixture.interests)
            fixture.destination.close()
        }

    @Test fun proofDoesNotReplaceTheDestinationsCacheReader() =
        runTest {
            val fixture = Fixture(this)
            fixture.seed()
            val launched = fixture.reading()
            val family = requireNotNull(launched.agentFamily(launched.owner))
            val proof = async { awaitCertifiedAgentSettlement(fixture.route, "chat", launched, family, "after1783") }
            runCurrent()
            assertEquals("the destination retains cache-reader ownership", 0, fixture.routeObservations)
            proof.cancelAndJoin()
            fixture.destination.close()
        }

    @Test fun timeoutKeepsOriginalDeadlineAndReleasesInterest() =
        runTest {
            val fixture = Fixture(this)
            fixture.seed()
            val launched = fixture.reading()
            val family = requireNotNull(launched.agentFamily(launched.owner))
            val proof =
                async {
                    runCatching {
                        withTimeout(30_000) { awaitCertifiedAgentSettlement(fixture.route, "chat", launched, family, "after1783") }
                    }
                }
            runCurrent()
            advanceTimeBy(29_999)
            assertFalse(proof.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(proof.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1, fixture.releases)
            assertEquals(1, fixture.interests)
            assertEquals("waiting is not an assertion retry", 1, fixture.pump.sent.size)
            fixture.destination.close()
        }

    private class Pump : SessionPump {
        val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = frames.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }
    }

    private class Fixture(
        val scope: TestScope,
    ) {
        val store = AuthoritativeThreadStore()
        var pump = Pump()
        var oldPump = pump
        var repository = repository()
        val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(repository))
        var interests = 0
        var releases = 0
        var routeObservations = 0
        lateinit var destination: Closeable
        val route =
            object : NegotiatedThread() {
                override val host = "host"
                override val store = this@Fixture.store
                override val support = this@Fixture.support

                override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
                    flow {
                        routeObservations++
                        emitAll(store.observe(host, conversationId))
                    }

                override suspend fun open(conversationId: String) = repository.openAuthoritativeThread(conversationId)

                override suspend fun acquire(
                    conversationId: String,
                    connection: Any,
                ): Closeable? {
                    if ((support.value as? ThreadSupport.Supported)?.connection !== connection) return null
                    val lease = repository.acquireAuthoritativeThread(conversationId) ?: return null
                    interests++
                    return Closeable {
                        interests--
                        releases++
                        lease.close()
                    }
                }

                override fun older(conversationId: String) = repository.requestOlderAuthoritativeThread(conversationId)

                override fun cancel(conversationId: String) = repository.cancelAuthoritativeThread(conversationId)
            }

        private fun repository() =
            RemoteConversationRepository(
                pump,
                scope.backgroundScope,
                negotiatedCapabilities = { setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) },
                authoritativeHostId = "host",
                authoritativeThreads = store,
                authoritativeNowMillis = { scope.testScheduler.currentTime },
            )

        suspend fun seed() {
            store.reset("host", "chat", "epoch")
            destination = requireNotNull(route.acquire("chat", repository))
            val request = pump.sent.last()
            batch(request, items(false), version = 27)
            terminal(request, 27, window = true)
            scope.runCurrent()
        }

        fun settle() {
            items(true).forEach { fact ->
                val update = added(fact, "chat", "epoch", 40)
                pump.frames.trySend(Envelope(1000, update.type, "", update.raw))
            }
            scope.runCurrent()
            repository.openAuthoritativeThread("chat")
            terminal(pump.sent.last(), 40, window = false)
            scope.runCurrent()
        }

        fun replace() {
            oldPump = pump
            oldPump.frames.close()
            scope.runCurrent()
            pump = Pump()
            repository = repository()
            support.value = ThreadSupport.Supported(repository)
        }

        fun reading() = authoritativeThreadEvidence("host", "chat", repository, store.current("host", "chat"))

        fun items(finished: Boolean) =
            (if (finished) finishedAgentScenarioItems() else runningAgentScenarioItems())
                .map { fact ->
                    val content = fact.content as JsonObject
                    ThreadItemDto.decode(
                        JsonObject(
                            fact.raw +
                                mapOf(
                                    "order" to
                                        JsonPrimitive(
                                            when (fact.id) {
                                                9L -> 29
                                                10L -> 37
                                                else -> fact.id + 8
                                            },
                                        ),
                                    "content" to
                                        JsonObject(
                                            content +
                                                if (fact.kind in setOf("agent", "tool_call")) {
                                                    mapOf(
                                                        "tool_use_id" to JsonPrimitive("tool-${fact.id}"),
                                                        "input" to JsonObject(emptyMap()),
                                                    )
                                                } else {
                                                    emptyMap()
                                                },
                                        ),
                                ) + if (finished && fact.id == 2L) mapOf("ended_order" to JsonPrimitive(35)) else emptyMap(),
                        ),
                    )
                }.groupBy { it.id }
                .values
                .map { versions -> versions.maxBy { it.rev } }

        fun batch(
            request: Envelope,
            items: List<ThreadItemDto>,
            version: Long = 40,
            pump: Pump = this.pump,
        ) {
            reply(
                request,
                pump,
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "epoch")
                    put("version", version)
                    put("items", JsonArray(items.map { it.raw }))
                    put("final", false)
                },
            )
        }

        fun terminal(
            request: Envelope,
            version: Long,
            window: Boolean,
            pump: Pump = this.pump,
        ) {
            reply(
                request,
                pump,
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "epoch")
                    put("version", version)
                    put("items", JsonArray(emptyList()))
                    put("final", true)
                    if (window) {
                        put("lower_order", 8)
                        put("upper_order", if (version == 27L) 27 else 41)
                        put("continuation", 8)
                        put("older_exists", false)
                    } else {
                        put("from_version", (request.payload as JsonObject).getValue("version"))
                    }
                },
            )
        }

        private fun reply(
            request: Envelope,
            pump: Pump,
            payload: JsonObject,
        ) {
            pump.frames.trySend(Envelope(2000, "thread_items", "", payload, inReplyTo = request.id))
        }
    }
}
