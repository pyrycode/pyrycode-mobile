package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ReplayCursor
import de.pyryco.mobile.data.network.TYPE_THREAD_ITEM_CHANGED
import de.pyryco.mobile.data.network.TYPE_THREAD_TEXT_APPEND
import de.pyryco.mobile.data.network.ThreadUpdateDecoder
import de.pyryco.mobile.data.network.ThreadUpdateDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.added
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.patch
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryAuthoritativeTest {
    private val both = setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE)
    private val previousSink = RelayLog.sink
    private val previousEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
        RelayLog.enabled = previousEnabled
    }

    @Test fun suppliedRouteRequiresBothCapabilitiesAndHost() =
        runTest {
            val capabilitySets =
                listOf(
                    emptySet(),
                    setOf(CAPABILITY_THREAD),
                    setOf(CAPABILITY_INTERACTIVE),
                    setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE),
                )
            for (capabilities in capabilitySets) {
                val pump = Pump()
                val repo =
                    RemoteConversationRepository(
                        pump,
                        backgroundScope,
                        negotiatedCapabilities = { capabilities },
                        authoritativeHostId = "host",
                    )
                pump.push(frame(added(item())))
                runCurrent()
                assertEquals(
                    if (capabilities.size == 2) 1 else 0,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items.size,
                )
                assertTrue(
                    repo
                        .observeThreadSnapshot("c")
                        .first()
                        .rows
                        .isEmpty(),
                )
            }
            val pump = Pump()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { both },
                )
            pump.push(frame(added(item())))
            runCurrent()
            assertTrue(
                repo.authoritativeThreads
                    .current("", "c")
                    .items
                    .isEmpty(),
            )
        }

    @Test fun suppliedUpdatesKeepRawReadEvidenceAndReplayCursorUntouched() =
        runTest {
            val pump = Pump()
            val cursor = ReplayCursor()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { both },
                    replayCursor = cursor,
                    authoritativeHostId = "host",
                )
            val before = repo.observeThreadSnapshot("c").first()
            pump.push(frame(added(item())).copy(eventId = 77, historyEntryId = 88u))
            pump.push(frame(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" tail\"")))
            runCurrent()
            assertEquals(
                "start tail",
                text(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .single(),
                ),
            )
            assertEquals(before, repo.observeThreadSnapshot("c").first())
            assertNull(cursor.latest)
            assertTrue(pump.sent.none { it.type.startsWith("thread_") })
        }

    @Test fun continuationsNeverPublishPartialAndAbandonmentRepairs() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame(added(item())))
            runCurrent()
            val parts = parts(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" tail\""))
            pump.push(parts.first())
            runCurrent()
            assertEquals(
                2L,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .single()
                    .rev,
            )
            pump.close()
            runCurrent()
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals("start", text(held.items.single()))
            assertEquals(100L, held.completedVersion)
            assertTrue(held.repairNeeded)
            assertEquals(0L, held.checkpoint)
        }

    @Test fun completeContinuationAppliesOnceAndFailureCannotRegressItems() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame(added(item())))
            val parts = parts(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" tail\""))
            parts.forEach(pump::push)
            runCurrent()
            assertEquals(
                "start tail",
                text(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .single(),
                ),
            )
            parts.forEach(pump::push)
            runCurrent()
            assertEquals(
                "start tail",
                text(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .single(),
                ),
            )
            assertTrue(repo.authoritativeThreads.current("host", "c").repairNeeded)
            pump.push(parts.first())
            pump.push(frame(added(item(1, 4, 1))))
            runCurrent()
            assertEquals(
                3L,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .single()
                    .rev,
            )
        }

    @Test fun resetDiscardsPendingAssembly() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame(added(item())))
            runCurrent()
            val parts = parts(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" old\""))
            pump.push(parts.first())
            runCurrent()
            repo.authoritativeRoute.reset("c", "new")
            pump.push(parts.last())
            pump.push(frame(added(item(), epoch = "new")))
            runCurrent()
            assertEquals("new", repo.authoritativeThreads.current("host", "c").epoch)
            assertEquals(
                "start",
                text(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .single(),
                ),
            )
        }

    @Test fun resetGenerationRejectsPendingCompletionWhenEpochIsReused() =
        runTest {
            val (pump, repo) = repository()
            val parts = parts(added(item()))
            pump.push(parts.first())
            runCurrent()
            repo.authoritativeThreads.reset("host", "c", "e")
            pump.push(parts.last())
            runCurrent()
            val snapshot = repo.authoritativeThreads.current("host", "c")
            assertTrue(snapshot.items.isEmpty())
            assertEquals(0L, snapshot.completedVersion)
            assertEquals(0L, snapshot.checkpoint)
            assertTrue(snapshot.repairNeeded)
        }

    @Test fun idleExpiryRepairs() =
        runTest {
            val store = AuthoritativeThreadStore()
            val decoder = ThreadUpdateDecoder("host", "connection", nowMillis = { testScheduler.currentTime })
            val route = AuthoritativeThreadRoute("host", store, decoder)
            route.accept(frame(added(item())))
            route.accept(parts(patch(TYPE_THREAD_TEXT_APPEND, 2, 3, "\"text\":\" missing\"")).first())
            val expiry = backgroundScope.launch { route.runExpiry() }
            advanceTimeBy(31000)
            runCurrent()
            assertTrue(store.current("host", "c").repairNeeded)
            assertEquals("start", text(store.current("host", "c").items.single()))
            expiry.cancel()
        }

    @Test fun legacyUnnegotiatedMessagesStillProduceRawRows() =
        runTest {
            val pump = Pump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            pump.push(
                Envelope(
                    1,
                    "message",
                    "2026-10-11T10:00:00Z",
                    MobileJson.parseToJsonElement(
                        """
            {"conversation_id":"c","message_id":"raw","role":"user","text":"legacy","is_streaming":false}
            """,
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                1,
                repo
                    .observeThreadSnapshot("c")
                    .first()
                    .rows.size,
            )
            assertTrue(
                repo.authoritativeThreads
                    .current("", "c")
                    .items
                    .isEmpty(),
            )
        }

    @Test fun suppliedRepairStaysStickyThroughLaterLiveUpdatesAndUnknownFacts() =
        runTest {
            val (pump, repo) = repository()
            pump.push(frame(added(item())))
            runCurrent()
            repo.authoritativeRoute.establishComplete("c", "e", 100, emptyList(), 0)
            pump.push(frame(patch(TYPE_THREAD_TEXT_APPEND, 3, 4, "\"text\":\"missed\"", 101)))
            pump.push(frame(patch(TYPE_THREAD_ITEM_CHANGED, 2, 5, "\"changes\":{\"content\":null,\"session\":null,\"summary\":\"\"}", 102)))
            val unknown = item(6, 6, null, kind = "future", status = "future_status", content = "[null,{}]")
            pump.push(frame(added(unknown, version = 103)))
            runCurrent()
            val snapshot = repo.authoritativeThreads.current("host", "c")
            assertEquals(100L, snapshot.checkpoint)
            assertEquals(103L, snapshot.completedVersion)
            assertTrue(snapshot.repairNeeded)
            assertEquals(JsonNull, snapshot.items.first { it.id == 1L }.content)
            assertEquals(unknown.raw, snapshot.items.first { it.id == 6L }.raw)
            assertTrue(logs.any { "event=thread_store" in it })
            assertTrue(logs.none { "missed" in it || "future_status" in it || "start" in it })
        }

    @Test fun connectionCancellationAndReconnectCannotCombineFragments() =
        runTest {
            val pump = Pump()
            val connectionScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val repo =
                RemoteConversationRepository(
                    pump,
                    connectionScope,
                    negotiatedCapabilities = { both },
                    authoritativeHostId = "host",
                )
            val parts = parts(added(item()))
            pump.push(parts.first())
            runCurrent()
            connectionScope.cancel()
            runCurrent()
            assertTrue(repo.authoritativeThreads.current("host", "c").repairNeeded)
            assertTrue(
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .isEmpty(),
            )
            val (newPump, newRepo) = repository()
            newPump.push(parts.last())
            runCurrent()
            assertTrue(
                newRepo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .isEmpty(),
            )
            assertTrue(newRepo.authoritativeThreads.current("host", "c").repairNeeded)
            newPump.push(frame(added(item())))
            runCurrent()
            assertEquals(
                1,
                newRepo.authoritativeThreads
                    .current("host", "c")
                    .items.size,
            )
            assertTrue(newRepo.authoritativeThreads.current("host", "c").repairNeeded)
        }

    private fun TestScope.repository(): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { both },
                authoritativeHostId = "host",
            )
    }

    private fun frame(update: ThreadUpdateDto) = Envelope(1, update.type, "2026-10-11T10:00:00Z", update.raw)

    private fun parts(update: ThreadUpdateDto): List<Envelope> {
        val serialized = update.raw.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest((update.type + "\u0000" + serialized).toByteArray()).toHexString()
        val cut = serialized.length / 2
        return listOf(serialized.take(cut), serialized.drop(cut)).mapIndexed { index, data ->
            frame(update).copy(
                payload =
                    buildJsonObject {
                        update.metadata().forEach { (key, value) -> put(key, value) }
                        put(
                            "continuation",
                            buildJsonObject {
                                put("update_id", hash)
                                put("index", index)
                                put("offset", if (index == 0) 0 else cut)
                                put("total_bytes", serialized.toByteArray().size)
                                put("final", index == 1)
                            },
                        )
                        put("data", data)
                    },
            )
        }
    }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun close() = channel.close()
    }
}
