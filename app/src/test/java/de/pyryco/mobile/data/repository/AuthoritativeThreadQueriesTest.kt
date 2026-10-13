package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.TYPE_THREAD_TEXT_APPEND
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadUpdateDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.added
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.patch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeThreadQueriesTest {
    @Test fun delayedLiveRepairCannotRetireWorkAfterReset() =
        runTest {
            val store = AuthoritativeThreadStore()
            val pump = Pump()
            lateinit var queries: AuthoritativeThreadQueries
            var delayedRepair: () -> Unit = {}
            val route =
                AuthoritativeThreadRoute("host", store, onRepair = { owners ->
                    delayedRepair = { queries.repair(owners) }
                })
            var requestId = 0L
            queries =
                AuthoritativeThreadQueries(
                    "host",
                    store,
                    route,
                    { true },
                    { ++requestId },
                    pump::send,
                    { testScheduler.currentTime },
                )
            store.reset("host", "c", "e")
            queries.open("c")
            route.accept(live(patch(TYPE_THREAD_TEXT_APPEND, 9, 25, "\"text\":\"gap\"", 25)))
            queries.reset("c", "e")
            val fresh = pump.sent.last().id
            delayedRepair()
            assertEquals(fresh, pump.sent.last().id)
        }

    private val previousSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun failedBatchApplicationCannotBeRescuedByTerminal() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val request = pump.sent.last()
            pump.push(batch(request, 20, listOf(item(1, 20, 5, kind = "agent"))))
            pump.push(success(request, 20))
            runCurrent()
            assertEquals(10L, repo.authoritativeThreads.current("host", "c").checkpoint)
            assertEquals(
                "assistant_message",
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .single()
                    .kind,
            )
            assertTrue(repo.authoritativeThreads.current("host", "c").repairNeeded)
            assertTrue(logs.none { "start" in it || "private" in it || "fallback" in it })
        }

    @Test fun newestWindowsRetainOnlyContinuousCoverage() =
        runTest {
            val (pump, repo) = repository()
            repo.authoritativeThreads.reset("host", "c", "e")
            repo.openAuthoritativeThread("c")
            val first = pump.sent.last()
            pump.push(batch(first, 100, listOf(item(1, 2, 5))))
            pump.push(live(added(item(2, 200, 190), version = 200)))
            pump.push(window(first, 100, 5, 101, true))
            runCurrent()
            assertEquals(0L, repo.authoritativeThreads.current("host", "c").checkpoint)
            repo.openAuthoritativeThread("c")
            val next = pump.sent.last()
            pump.push(window(next, 200, 190, 201, true))
            runCurrent()
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(190uL, held.coverage?.lowerOrder)
            assertEquals(2, held.items.size)
            assertEquals(200L, held.checkpoint)
        }

    @Test fun disconnectAndCancellationBeforeCollectionRetireQueries() =
        runTest {
            val owned = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val (_, repo) = repository(scope = owned)
                repo.openAuthoritativeThread("c")
                owned.cancel()
                runCurrent()
                assertFalse(repo.openAuthoritativeThread("c"))
                val (pump, connected) = repository()
                connected.openAuthoritativeThread("c")
                pump.close()
                runCurrent()
                assertFalse(connected.openAuthoritativeThread("c"))
                assertTrue(connected.authoritativeThreads.current("host", "c").repairNeeded)
            } finally {
                owned.cancel()
            }
        }

    @Test fun liveRepairDuringCatchUpCannotBeClearedByItsOldMarker() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val interrupted = pump.sent.last()
            pump.push(live(patch(TYPE_THREAD_TEXT_APPEND, 9, 25, "\"text\":\"gap\"", 25)))
            pump.push(success(interrupted, 20))
            runCurrent()
            assertNotEquals(interrupted.id, pump.sent.last().id)
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(10L, held.checkpoint)
            assertTrue(held.repairNeeded)
        }

    @Test fun droppedFinalFragmentWithNewerLiveAndOutsideParentSurvivesRetry() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val interrupted = pump.sent.last()
            pump.push(batch(interrupted, 20, listOf(item(2, 3, 1, kind = "agent"))))
            val child = item(1, 15, 5, extra = "\"parent\":2,")
            val parts = fragments(batch(interrupted, 20, listOf(child)))
            pump.push(parts.first())
            pump.push(live(added(item(1, 25, 5, extra = "\"parent\":2,"), version = 25)))
            pump.push(success(interrupted, 20))
            runCurrent()
            assertEquals(10L, repo.authoritativeThreads.current("host", "c").checkpoint)
            assertEquals(
                5uL,
                repo.authoritativeThreads
                    .current("host", "c")
                    .coverage
                    ?.continuation,
            )
            repo.openAuthoritativeThread("c")
            val retry = pump.sent.last()
            pump.push(parts.last())
            pump.push(batch(retry, 25, listOf(child)))
            pump.push(success(retry, 25))
            runCurrent()
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(listOf(2L, 1L), held.items.map { it.id })
            assertEquals(25L, held.items.last().rev)
            assertEquals(25L, held.checkpoint)
            assertEquals(5uL, held.coverage?.continuation)
        }

    @Test fun assemblyMustFinishBeforeTerminalAndExpiresWhileIdle() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            for (expire in listOf(false, true)) {
                repo.requestOlderAuthoritativeThread("c")
                val request = pump.sent.last()
                val parts = fragments(batch(request, 10, listOf(item(2, 3, 4))))
                pump.push(parts.first())
                runCurrent()
                assertEquals(
                    1,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items.size,
                )
                if (expire) advanceTimeBy(31000) else pump.push(window(request, 10, 4, 5, false))
                runCurrent()
                pump.push(parts.last())
                pump.push(window(request, 10, 4, 5, false))
                runCurrent()
                assertEquals(
                    5uL,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .coverage
                        ?.continuation,
                )
                assertEquals(
                    1,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items.size,
                )
            }
            repo.requestOlderAuthoritativeThread("c")
            val request = pump.sent.last()
            fragments(batch(request, 10, listOf(item(2, 3, 4)))).forEach(pump::push)
            pump.push(window(request, 10, 4, 5, false))
            runCurrent()
            assertEquals(
                2,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items.size,
            )
            assertEquals(
                4uL,
                repo.authoritativeThreads
                    .current("host", "c")
                    .coverage
                    ?.continuation,
            )
        }

    @Test fun queriesSerializeAcrossConversationsAndUseCertifiedCheckpoint() =
        runTest {
            val (pump, repo) = repository()
            repo.authoritativeThreads.reset("host", "c", "e")
            repo.openAuthoritativeThread("c")
            repo.openAuthoritativeThread("other")
            assertEquals(1, pump.sent.size)
            val first = pump.sent.last()
            assertEquals("0", field(first, "version"))
            pump.push(window(first, 10, 4, 11, true))
            runCurrent()
            assertEquals(2, pump.sent.size)
            assertEquals("other", field(pump.sent.last(), "conversation_id"))
            repo.cancelAuthoritativeThread("other")
            repo.openAuthoritativeThread("c")
            assertEquals("10", field(pump.sent.last(), "version"))
            assertEquals("e", field(pump.sent.last(), "epoch"))
            assertTrue(
                pump.sent
                    .map { it.id }
                    .distinct()
                    .size == pump.sent.size,
            )
        }

    @Test fun upsertsConvergeWithoutDeletingHeldItems() =
        runTest {
            for (reverse in listOf(false, true)) {
                val (pump, repo) = repository()
                repo.authoritativeThreads.reset("host", "c", "e")
                repo.openAuthoritativeThread("c")
                val request = pump.sent.last()
                val rows = listOf(item(1, 2, 9), item(3, 4, 5), item(6, 7, null))
                (if (reverse) rows.reversed() else rows).forEach { pump.push(batch(request, 10, listOf(it))) }
                pump.push(batch(request, 10, rows))
                runCurrent()
                assertEquals(
                    listOf(3L, 1L, 6L),
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.id },
                )
                assertEquals(0L, repo.authoritativeThreads.current("host", "c").checkpoint)
                pump.push(window(request, 10, 5, 11, false))
                runCurrent()
                repo.openAuthoritativeThread("c")
                val next = pump.sent.last()
                pump.push(batch(next, 12, listOf(item(1, 12, 9))))
                pump.push(success(next, 12))
                runCurrent()
                val held = repo.authoritativeThreads.current("host", "c")
                assertEquals(3, held.items.size)
                assertEquals(12L, held.items.first { it.id == 1L }.rev)
                assertEquals(12L, held.checkpoint)
            }
        }

    @Test fun interruptedCatchUpRetainsNewerLiveAndCapturedCheckpoint() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val interrupted = pump.sent.last()
            pump.push(batch(interrupted, 20, listOf(item(1, 15, 5))))
            pump.push(live(patch(TYPE_THREAD_TEXT_APPEND, 15, 25, "\"text\":\" newer\"", 25)))
            runCurrent()
            advanceTimeBy(31000)
            runCurrent()
            assertEquals(10L, repo.authoritativeThreads.current("host", "c").checkpoint)
            repo.openAuthoritativeThread("c")
            val retry = pump.sent.last()
            assertNotEquals(interrupted.id, retry.id)
            assertEquals("10", field(retry, "version"))
            pump.push(batch(retry, 20, listOf(item(1, 20, 5))))
            pump.push(success(retry, 20))
            runCurrent()
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(25L, held.items.single().rev)
            assertEquals(10L, held.checkpoint)
            assertTrue(held.repairNeeded)
            repo.openAuthoritativeThread("c")
            val complete = pump.sent.last()
            pump.push(success(complete, 25))
            runCurrent()
            assertEquals(25L, repo.authoritativeThreads.current("host", "c").checkpoint)
            assertFalse(repo.authoritativeThreads.current("host", "c").repairNeeded)
        }

    @Test fun pagesCertifyNumericRangesIndependentlyOfExtras() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            assertTrue(repo.requestOlderAuthoritativeThread("c"))
            val page = pump.sent.last()
            assertEquals("5", field(page, "before_order"))
            val parent = item(2, 2, 1, kind = "agent")
            val child = item(3, 3, 4, extra = "\"parent\":2,")
            pump.push(batch(page, 10, listOf(parent, child, item(4, 4, 4))))
            pump.push(window(page, 10, 4, 5, true))
            runCurrent()
            val coverage = repo.observeAuthoritativeThread("c").first().coverage
            assertEquals(4uL, coverage?.continuation)
            assertEquals(11uL, coverage?.upperOrder)
            assertEquals(
                4,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items.size,
            )
            repo.requestOlderAuthoritativeThread("c")
            val empty = pump.sent.last()
            assertEquals("4", field(empty, "before_order"))
            pump.push(window(empty, 10, 0, 4, false))
            runCurrent()
            assertEquals(
                0uL,
                repo.authoritativeThreads
                    .current("host", "c")
                    .coverage
                    ?.continuation,
            )
            assertFalse(repo.requestOlderAuthoritativeThread("c"))
            assertEquals(10L, repo.authoritativeThreads.current("host", "c").checkpoint)
        }

    @Test fun failedAndExpiredQueriesKeepBoundariesRetryable() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            for (failure in listOf("error", "malformed", "timeout", "boundary")) {
                repo.requestOlderAuthoritativeThread("c")
                val request = pump.sent.last()
                when (failure) {
                    "error" ->
                        pump.push(
                            reply(
                                request,
                                "error",
                                "{\"code\":\"thread.query_failed\",\"message\":\"private\",\"retryable\":true}",
                            ),
                        )
                    "malformed" -> pump.push(reply(request, "thread_items", "{}"))
                    "boundary" -> pump.push(window(request, 10, 1, 6, true))
                    else -> advanceTimeBy(31000)
                }
                runCurrent()
                assertEquals(
                    5uL,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .coverage
                        ?.continuation,
                )
                assertEquals(10L, repo.authoritativeThreads.current("host", "c").checkpoint)
            }
            repo.requestOlderAuthoritativeThread("c")
            pump.push(window(pump.sent.last(), 10, 1, 5, false))
            runCurrent()
            assertEquals(
                1uL,
                repo.authoritativeThreads
                    .current("host", "c")
                    .coverage
                    ?.continuation,
            )
        }

    @Test fun pageCoverageCannotCertifyNewestHistory() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            repo.requestOlderAuthoritativeThread("c")
            val page = pump.sent.last()
            pump.push(batch(page, 40, listOf(item(2, 2, 4))))
            pump.push(window(page, 40, 4, 5, true))
            runCurrent()
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(10L, held.checkpoint)
            assertEquals(40L, held.completedVersion)
            assertEquals(4uL, held.coverage?.lowerOrder)
            repo.openAuthoritativeThread("c")
            assertEquals("10", field(pump.sent.last(), "version"))
        }

    @Test fun resetFencesRetiredRepliesAndPreservesOtherOwners() =
        runTest {
            val store = AuthoritativeThreadStore()
            val (pump, repo) = repository(store)
            store.reset("elsewhere", "c", "e")
            store.applyBatch("elsewhere", "c", "e", 10, listOf(item()), 1)
            store.reset("host", "other", "e")
            store.applyBatch("host", "other", "e", 10, listOf(item()), 1)
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val old = pump.sent.last()
            repo.resetAuthoritativeThread("c", "e")
            val fresh = pump.sent.last()
            assertNotEquals(old.id, fresh.id)
            assertEquals("0", field(fresh, "version"))
            pump.push(batch(old, 30, listOf(item(1, 30, 5))))
            pump.push(success(old, 30))
            runCurrent()
            assertTrue(store.current("host", "c").items.isEmpty())
            assertNull(store.current("host", "c").coverage)
            assertEquals(1, store.current("host", "other").items.size)
            assertEquals(1, store.current("elsewhere", "c").items.size)
            pump.push(window(fresh, 0, 0, 0, false))
            runCurrent()
            assertFalse(store.current("host", "c").repairNeeded)
        }

    @Test fun terminalResetRequestsReportedEpochZeroWindow() =
        runTest {
            val (pump, repo) = repository()
            repo.openAuthoritativeThread("c")
            val old = pump.sent.last()
            pump.push(reply(old, "thread_reset", "{\"conversation_id\":\"c\",\"epoch\":\"new\",\"version\":50}"))
            runCurrent()
            assertEquals("new", field(pump.sent.last(), "epoch"))
            assertEquals("0", field(pump.sent.last(), "version"))
            assertNotEquals(old.id, pump.sent.last().id)
        }

    @Test fun reconnectReusesOnlyCertifiedFacts() =
        runTest {
            val store = AuthoritativeThreadStore()
            val ownedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val (pump, repo) = repository(store, ownedScope)
            seed(pump, repo)
            repo.openAuthoritativeThread("c")
            val old = pump.sent.last()
            pump.push(batch(old, 20, listOf(item(1, 20, 5))))
            runCurrent()
            ownedScope.cancel()
            runCurrent()
            assertFalse(repo.openAuthoritativeThread("c"))
            val (newPump, newRepo) = repository(store)
            newRepo.openAuthoritativeThread("c")
            assertEquals("10", field(newPump.sent.last(), "version"))
            pump.push(success(old, 20))
            runCurrent()
            assertEquals(10L, store.current("host", "c").checkpoint)
            newPump.push(success(newPump.sent.last(), 20))
            runCurrent()
            assertEquals(20L, store.current("host", "c").checkpoint)
            assertEquals(newRepo.observeAuthoritativeThread("c").first(), newRepo.observeAuthoritativeThread("c").first())
        }

    @Test fun cancellationAndSendFailureReleaseSlot() =
        runTest {
            val (pump, repo) = repository()
            repo.openAuthoritativeThread("c")
            val retired = pump.sent.last()
            repo.openAuthoritativeThread("other")
            repo.cancelAuthoritativeThread("c")
            assertEquals("other", field(pump.sent.last(), "conversation_id"))
            pump.push(reply(retired, "thread_reset", "{\"conversation_id\":\"c\",\"epoch\":\"bad\",\"version\":1}"))
            runCurrent()
            assertNull(repo.authoritativeThreads.current("host", "c").epoch)
            repo.cancelAuthoritativeThread("other")
            pump.acceptSend = false
            repo.openAuthoritativeThread("c")
            val refused = pump.sent.last()
            pump.acceptSend = true
            repo.openAuthoritativeThread("c")
            assertNotEquals(refused.id, pump.sent.last().id)
        }

    @Test fun liveRepairRequestsCatchUpForOpenedConversation() =
        runTest {
            val (pump, repo) = repository()
            seed(pump, repo)
            pump.push(live(patch(TYPE_THREAD_TEXT_APPEND, 9, 11, "\"text\":\"gap\"", 11)))
            runCurrent()
            assertEquals("thread_catch_up", pump.sent.last().type)
            assertEquals("10", field(pump.sent.last(), "version"))
            assertEquals(2, pump.sent.size)
        }

    @Test fun querySeamsRequireSuppliedHostAndBothCapabilities() =
        runTest {
            for (caps in listOf(emptySet(), setOf(CAPABILITY_THREAD), setOf(CAPABILITY_INTERACTIVE))) {
                val pump = Pump()
                val repo =
                    RemoteConversationRepository(
                        pump,
                        backgroundScope,
                        negotiatedCapabilities = { caps },
                        authoritativeHostId = "host",
                    )
                assertFalse(repo.openAuthoritativeThread("c"))
                assertTrue(pump.sent.isEmpty())
            }
        }

    private fun TestScope.repository(
        store: AuthoritativeThreadStore = AuthoritativeThreadStore(),
        scope: CoroutineScope = backgroundScope,
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                scope,
                negotiatedCapabilities = { setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) },
                authoritativeHostId = "host",
                authoritativeThreads = store,
                authoritativeNowMillis = { testScheduler.currentTime },
            )
    }

    private fun TestScope.seed(
        pump: Pump,
        repo: RemoteConversationRepository,
    ) {
        repo.authoritativeThreads.reset("host", "c", "e")
        repo.openAuthoritativeThread("c")
        val request = pump.sent.last()
        pump.push(batch(request, 10, listOf(item(1, 2, 5))))
        pump.push(window(request, 10, 5, 11, true))
        runCurrent()
    }

    private fun field(
        frame: Envelope,
        name: String,
    ) = ((frame.payload as JsonObject)[name] as JsonPrimitive).content

    private fun reply(
        request: Envelope,
        type: String,
        raw: String,
    ) = Envelope(1000, type, "2026-10-13T10:00:00Z", MobileJson.parseToJsonElement(raw), inReplyTo = request.id)

    private fun batch(
        request: Envelope,
        version: Long,
        items: List<ThreadItemDto>,
    ): Envelope =
        reply(
            request,
            "thread_items",
            buildJsonObject {
                put("conversation_id", field(request, "conversation_id"))
                put("epoch", "e")
                put("version", version)
                put("items", JsonArray(items.map { it.raw }))
                put("final", false)
            }.toString(),
        )

    private fun window(
        request: Envelope,
        version: Long,
        lower: Long,
        upper: Long,
        older: Boolean,
    ) = reply(
        request,
        "thread_items",
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", lower)
            put("upper_order", upper)
            put("continuation", lower)
            put("older_exists", older)
        }.toString(),
    )

    private fun success(
        request: Envelope,
        version: Long,
    ) = reply(
        request,
        "thread_items",
        buildJsonObject {
            put("conversation_id", field(request, "conversation_id"))
            put("epoch", "e")
            put("version", version)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("from_version", field(request, "version").toLong())
        }.toString(),
    )

    private fun live(update: ThreadUpdateDto) = Envelope(2000, update.type, "2026-10-13T10:00:00Z", update.raw)

    private fun fragments(batch: Envelope): List<Envelope> {
        val raw = batch.payload as JsonObject
        val text = raw.toString()
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(("thread_items\u0000" + text).toByteArray())
                .joinToString("") { "%02x".format(it) }
        val cut = text.length / 2
        val row = (raw.getValue("items") as JsonArray).single() as JsonObject
        return listOf(text.take(cut), text.drop(cut)).mapIndexed { index, data ->
            batch.copy(
                payload =
                    buildJsonObject {
                        raw.filterKeys { it in setOf("conversation_id", "epoch", "version") }.forEach { (k, v) -> put(k, v) }
                        put("item_id", row.getValue("id"))
                        put("rev", row.getValue("rev"))
                        put("data", data)
                        put(
                            "continuation",
                            buildJsonObject {
                                put("update_id", hash)
                                put("index", index)
                                put("offset", if (index == 0) 0 else cut)
                                put("total_bytes", text.toByteArray().size)
                                put("final", index == 1)
                            },
                        )
                    },
            )
        }
    }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        var acceptSend = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return acceptSend
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }

        fun close() {
            channel.close()
        }
    }
}
