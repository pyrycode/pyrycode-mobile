package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.added
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthoritativeThreadCacheTest {
    private val originalSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = originalSink
    }

    @get:Rule val temporary = TemporaryFolder()

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope).getOrThrow()
        }
    }

    private fun TestScope.fileCache() = FileConversationCache(temporary.root, StandardTestDispatcher(testScheduler))

    private fun TestScope.wrapper(
        cache: ConversationCache = fileCache(),
        host: String = "host",
        delegate: ConversationRepository = FakeConversationRepository(),
    ) = CachingConversationRepository(delegate, cache, host, processingDispatcher = StandardTestDispatcher(testScheduler))

    private fun TestScope.remote(
        host: String = "host",
        capabilities: Set<String> = setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE),
        scope: CoroutineScope = backgroundScope,
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                scope,
                negotiatedCapabilities = { capabilities },
                authoritativeHostId = host,
                authoritativeNowMillis = { testScheduler.currentTime },
            )
    }

    private fun reply(
        request: Envelope,
        payload: String,
    ) = Envelope(100, "thread_items", "2026-10-14T00:00:00Z", MobileJson.parseToJsonElement(payload), inReplyTo = request.id)

    private fun batch(
        request: Envelope,
        version: Long,
        items: List<ThreadItemDto>,
    ) = reply(
        request,
        """{"conversation_id":"c","epoch":"e","version":$version,"items":[${items.joinToString { it.raw.toString() }}],"final":false}""",
    )

    private fun window(
        request: Envelope,
        version: Long = 10,
    ) = reply(
        request,
        """{"conversation_id":"c","epoch":"e","version":$version,"items":[],"final":true,"lower_order":5,"upper_order":11,"continuation":5,"older_exists":true}""",
    )

    private fun TestScope.seed(
        pump: Pump,
        remote: RemoteConversationRepository,
    ) {
        remote.openAuthoritativeThread("c")
        val first = pump.sent.last()
        pump.push(
            Envelope(
                99,
                "thread_reset",
                "2026-10-14T00:00:00Z",
                MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":10}"""),
                inReplyTo = first.id,
            ),
        )
        runCurrent()
        val request = pump.sent.last()
        pump.push(batch(request, 10, listOf(item(1, 2, 5))))
        pump.push(window(request))
        runCurrent()
    }

    private suspend fun assertFacts(
        cache: ConversationCache,
        host: String,
        expected: AuthoritativeThreadSnapshot,
    ) {
        val saved = requireNotNull(cache.readAuthoritativeThread(host, "c"))
        assertEquals(expected.epoch, saved.epoch)
        assertEquals(expected.items.map { it.raw }, saved.items)
        assertEquals(expected.completedVersion, saved.appliedVersion)
        assertEquals(expected.checkpoint, saved.checkpoint)
        assertEquals(expected.coverage, saved.toSnapshot().coverage)
    }

    @Test fun restartRestoresFactsAndOnlyCertifiedReconnectCheckpoint() =
        runTest {
            val (pump, remote) = remote()
            val repo = wrapper(delegate = remote)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            seed(pump, remote)
            advanceTimeBy(101)
            runCurrent()
            val expected = remote.authoritativeThreads.current("host", "c")
            assertFacts(fileCache(), "host", expected)
            observer.cancel()
            runCurrent()
            val offline = wrapper()
            val restored = offline.observeAuthoritativeThread("c").first()
            assertEquals(expected.items.map { it.raw }, restored.items.map { it.raw })
            assertEquals(expected.checkpoint, restored.checkpoint)
            assertEquals(expected.coverage, restored.coverage)
            assertFalse(offline.openAuthoritativeThread("c"))
            val (newPump, newRemote) = remote()
            val reconnect = wrapper(delegate = newRemote)
            assertTrue(reconnect.openAuthoritativeThread("c"))
            val request = newPump.sent.last()
            val payload = request.payload as JsonObject
            assertEquals("10", payload.getValue("version").toString())
            assertEquals("\"e\"", payload.getValue("epoch").toString())
            assertEquals(AuthoritativeLiveProgress(), newRemote.observeAuthoritativeProgress("c").first())
            val (legacyPump, legacyRemote) = remote(capabilities = emptySet())
            assertFalse(wrapper(delegate = legacyRemote).openAuthoritativeThread("c"))
            assertTrue(legacyPump.sent.isEmpty())
        }

    @Test fun interruptedBatchesKeepPriorCertificationWithNewestItems() =
        runTest {
            val (pump, remote) = remote()
            val repo = wrapper(delegate = remote)
            backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            seed(pump, remote)
            advanceTimeBy(101)
            runCurrent()
            remote.openAuthoritativeThread("c")
            val query = pump.sent.last()
            pump.push(batch(query, 20, listOf(item(2, 3, 1, kind = "agent"), item(1, 15, 5, extra = "\"parent\":2,"))))
            runCurrent()
            val update = added(item(1, 25, 5, extra = "\"parent\":2,"), version = 25)
            pump.push(Envelope(200, update.type, "2026-10-14T00:00:00Z", update.raw))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val expected = remote.authoritativeThreads.current("host", "c")
            assertEquals(10L, expected.checkpoint)
            assertEquals(5uL, expected.coverage?.lowerOrder)
            assertEquals(listOf(2L, 1L), expected.items.map { it.id })
            assertFacts(fileCache(), "host", expected)
            val (nextPump, nextRemote) = remote()
            val next = wrapper(delegate = nextRemote)
            val restored = next.observeAuthoritativeThread("c").first()
            assertEquals(25L, restored.items.last().rev)
            assertEquals(2L, restored.items.last().parent)
            assertEquals(10L, restored.checkpoint)
            assertEquals(expected.coverage, restored.coverage)
            next.openAuthoritativeThread("c")
            assertEquals("10", (nextPump.sent.last().payload as JsonObject).getValue("version").toString())
        }

    private class HeldCache(
        private val actual: ConversationCache,
    ) : ConversationCache by actual {
        var beforeRead: (suspend () -> Unit)? = null
        var afterRead: (suspend () -> Unit)? = null
        var beforeWrite: (suspend () -> Unit)? = null
        var afterWrite: (suspend () -> Unit)? = null
        var fail = false
        val writes = mutableListOf<SavedAuthoritativeThread>()

        override suspend fun readAuthoritativeThread(
            serverId: String,
            conversationId: String,
        ): SavedAuthoritativeThread? {
            beforeRead?.invoke()
            val saved = actual.readAuthoritativeThread(serverId, conversationId)
            afterRead?.invoke()
            return saved
        }

        override suspend fun writeAuthoritativeThread(
            serverId: String,
            conversationId: String,
            saved: SavedAuthoritativeThread,
        ): Result<Unit> {
            writes += saved
            beforeWrite?.invoke()
            if (fail) return Result.failure(IllegalStateException("test failure"))
            val result = actual.writeAuthoritativeThread(serverId, conversationId, saved)
            afterWrite?.invoke()
            return result
        }
    }

    private fun apply(
        repo: CachingConversationRepository,
        version: Long,
        items: List<ThreadItemDto> = listOf(item(1, version, 5)),
        host: String = "host",
    ) {
        val generation = repo.authoritativeThreads.current(host, "c").generation
        assertTrue(
            repo.authoritativeThreads.applyBatch(host, "c", "e", version, items, generation) in
                setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored),
        )
    }

    @Test fun staleRestoreCannotReplaceNewerLiveFactsOrCertification() =
        runTest {
            val cache = fileCache()
            val old =
                AuthoritativeThreadSnapshot(
                    "e",
                    listOf(item(1, 2, 5), item(2, 3, 6)),
                    20,
                    10,
                    coverage = AuthoritativeThreadCoverage(5u, 11u, 5u, true),
                )
            cache.writeAuthoritativeThread("host", "c", old.toSavedAuthoritativeThread())
            val held = HeldCache(cache)
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            held.afterRead = {
                captured.complete(Unit)
                release.await()
            }
            val repo = wrapper(held)
            val restore = backgroundScope.launch { repo.observeAuthoritativeThread("c").first() }
            runCurrent()
            assertTrue(captured.isCompleted)
            apply(repo, 30)
            repo.authoritativeThreads.establishComplete("host", "c", "e", 30, emptyList(), 0)
            release.complete(Unit)
            runCurrent()
            restore.join()
            val current = repo.authoritativeThreads.current("host", "c")
            assertEquals(listOf(1L, 2L), current.items.map { it.id })
            assertEquals(30L, current.items.first().rev)
            assertEquals(30L, current.checkpoint)
            assertNull(current.coverage)
            assertFalse(current.repairNeeded)
        }

    @Test fun failedSavePreservesAtomicRecordAndBaseline() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val prior = repo.authoritativeThreads.current("host", "c")
            held.fail = true
            apply(repo, 20)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertFacts(fileCache(), "host", prior)
            held.fail = false
            observer.cancelAndJoin()
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
            assertEquals(listOf(10L, 20L, 20L), held.writes.map { it.appliedVersion })
        }

    @Test fun heldIoDoesNotBlockAuthoritativeDelivery() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            held.beforeWrite = {
                entered.complete(Unit)
                release.await()
            }
            val seen = mutableListOf<Long>()
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { seen += it.completedVersion } }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(entered.isCompleted)
            apply(repo, 20)
            runCurrent()
            assertEquals(listOf(0L, 10L, 20L), seen)
            apply(repo, 30)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertEquals(30L, seen.last())
            release.complete(Unit)
            runCurrent()
            observer.cancelAndJoin()
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
            assertEquals(listOf(10L, 30L), held.writes.map { it.appliedVersion })
        }

    @Test fun postCommitCancellationKeepsSuccessfulBaseline() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            held.afterWrite = {
                committed.complete(Unit)
                release.await()
            }
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(committed.isCompleted)
            observer.cancel()
            runCurrent()
            apply(repo, 20)
            release.complete(Unit)
            runCurrent()
            observer.join()
            assertEquals(1, held.writes.size)
            held.afterWrite = null
            val next = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            next.cancelAndJoin()
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
            assertEquals(listOf(10L, 20L), held.writes.map { it.appliedVersion })
        }

    @Test fun olderFinalFlushCannotOverwriteNewerCommit() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val old = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val newer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 20)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            newer.cancelAndJoin()
            old.cancelAndJoin()
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
            assertEquals(listOf(10L, 20L), held.writes.map { it.appliedVersion })
        }

    @Test fun resetFencesCapturedRestoreAndFinalWrites() =
        runTest {
            for (epoch in listOf("e", "new")) {
                val held = HeldCache(fileCache())
                val repo = wrapper(held)
                val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
                runCurrent()
                apply(repo, 10)
                runCurrent()
                advanceTimeBy(101)
                runCurrent()
                val captured = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                held.afterRead = {
                    captured.complete(Unit)
                    release.await()
                }
                val stale = backgroundScope.launch { repo.observeAuthoritativeThread("c").first() }
                runCurrent()
                assertTrue(captured.isCompleted)
                repo.resetAuthoritativeThread("c", epoch)
                repo.resetAuthoritativeThread("c", epoch)
                release.complete(Unit)
                runCurrent()
                stale.join()
                observer.cancelAndJoin()
                val restored = wrapper().observeAuthoritativeThread("c").first()
                assertEquals(epoch, restored.epoch)
                assertTrue(restored.items.isEmpty())
                assertEquals(0L, restored.checkpoint)
                assertNull(restored.coverage)
            }
        }

    @Test fun deletionFencesWritesAndKeepsOtherHost() =
        runTest {
            for (inFlight in listOf(false, true)) {
                val cache = fileCache()
                val held = HeldCache(cache)
                val repo = wrapper(held)
                val other = wrapper(host = "other")
                val first = backgroundScope.launch { other.observeAuthoritativeThread("c").collect {} }
                runCurrent()
                apply(other, 40, host = "other")
                runCurrent()
                advanceTimeBy(101)
                runCurrent()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                held.beforeWrite = {
                    entered.complete(Unit)
                    release.await()
                }
                val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
                runCurrent()
                apply(repo, 10)
                runCurrent()
                if (inFlight) {
                    advanceTimeBy(101)
                    runCurrent()
                    assertTrue(entered.isCompleted)
                }
                val deletion = backgroundScope.launch { repo.delete("c") }
                runCurrent()
                apply(repo, 20)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                deletion.join()
                observer.cancelAndJoin()
                first.cancelAndJoin()
                assertNull(fileCache().readAuthoritativeThread("host", "c"))
                assertTrue(
                    wrapper()
                        .observeAuthoritativeThread("c")
                        .first()
                        .items
                        .isEmpty(),
                )
                assertFacts(fileCache(), "other", other.authoritativeThreads.current("other", "c"))
            }
        }

    @Test fun refusedDeletionPreservesSavedFacts() =
        runTest {
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun delete(conversationId: String): Unit = throw IllegalStateException("refused")
                }
            val repo = wrapper(delegate = delegate)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            try {
                repo.delete("c")
                fail("expected refusal")
            } catch (_: IllegalStateException) {
            }
            observer.cancelAndJoin()
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
        }

    @Test fun deletionFencesCapturedRestore() =
        runTest {
            val cache = fileCache()
            val saved =
                AuthoritativeThreadSnapshot(
                    "e",
                    listOf(item(1, 10, 5)),
                    10,
                    10,
                    coverage = AuthoritativeThreadCoverage(5u, 11u, 5u, true),
                ).toSavedAuthoritativeThread()
            cache.writeAuthoritativeThread("host", "c", saved)
            val held = HeldCache(cache)
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            held.afterRead = {
                captured.complete(Unit)
                release.await()
            }
            val repo = wrapper(held)
            val result = CompletableDeferred<AuthoritativeThreadSnapshot>()
            val restore = backgroundScope.launch { result.complete(repo.observeAuthoritativeThread("c").first()) }
            runCurrent()
            assertTrue(captured.isCompleted)
            repo.delete("c")
            release.complete(Unit)
            runCurrent()
            restore.join()
            assertTrue(result.await().items.isEmpty())
            assertEquals(0L, result.await().checkpoint)
            assertNull(result.await().coverage)
            assertNull(fileCache().readAuthoritativeThread("host", "c"))
        }

    @Test fun resetDuringInflightWriteCannotRestoreRetiredItems() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            held.beforeWrite = {
                entered.complete(Unit)
                release.await()
            }
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(entered.isCompleted)
            val resetResult = CompletableDeferred<Result<Unit>>()
            val reset = backgroundScope.launch { resetResult.complete(repo.resetAuthoritativeThread("c", "e")) }
            runCurrent()
            assertFalse(resetResult.isCompleted)
            release.complete(Unit)
            runCurrent()
            reset.join()
            assertTrue(resetResult.await().isSuccess)
            observer.cancelAndJoin()
            val offline = wrapper().observeAuthoritativeThread("c").first()
            assertEquals("e", offline.epoch)
            assertTrue(offline.items.isEmpty())
            assertEquals(0L, offline.checkpoint)
            assertNull(offline.coverage)
            assertEquals(listOf(10L, 0L), held.writes.map { it.appliedVersion })
        }

    @Test fun remoteResetSurvivesCancellationDuringDelivery() = runTest { cancelledRemoteReset(holdEarlierSnapshot = false) }

    @Test fun remoteResetDuringEarlierDeliverySurvivesCancellation() = runTest { cancelledRemoteReset(holdEarlierSnapshot = true) }

    private suspend fun TestScope.cancelledRemoteReset(holdEarlierSnapshot: Boolean) {
        val saved =
            AuthoritativeThreadSnapshot(
                "e",
                listOf(item(1, 10, 5)),
                10,
                10,
                coverage = AuthoritativeThreadCoverage(5u, 11u, 5u, true),
            )
        fileCache().writeAuthoritativeThread("host", "c", saved.toSavedAuthoritativeThread()).getOrThrow()
        val (pump, remote) = remote()
        val repo = wrapper(delegate = remote)
        val deliveringReset = CompletableDeferred<Unit>()
        val holdDelivery = CompletableDeferred<Unit>()
        val observer =
            backgroundScope.launch {
                repo.observeAuthoritativeThread("c").collect {
                    if (holdEarlierSnapshot || it.generation > 0) {
                        deliveringReset.complete(Unit)
                        holdDelivery.await()
                    }
                }
            }
        runCurrent()
        assertTrue(repo.openAuthoritativeThread("c"))
        val request = pump.sent.last()
        assertEquals("10", (request.payload as JsonObject).getValue("version").toString())
        pump.push(
            Envelope(
                101,
                "thread_reset",
                "2026-10-14T00:00:00Z",
                MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":20}"""),
                inReplyTo = request.id,
            ),
        )
        runCurrent()
        assertTrue(deliveringReset.isCompleted)
        assertEquals("0", (pump.sent.last().payload as JsonObject).getValue("version").toString())
        observer.cancelAndJoin()
        val reset = remote.authoritativeThreads.current("host", "c")
        assertFacts(fileCache(), "host", reset)
        val restored = wrapper().observeAuthoritativeThread("c").first()
        assertEquals("e", restored.epoch)
        assertTrue(restored.items.isEmpty())
        assertEquals(0L, restored.completedVersion)
        assertEquals(0L, restored.checkpoint)
        assertNull(restored.coverage)
    }

    @Test fun overlappingResetCannotAcknowledgeHeldReplacement() = runTest { overlappingResets(failReplacement = false) }

    @Test fun overlappingResetCannotAcknowledgeFailedReplacement() = runTest { overlappingResets(failReplacement = true) }

    @Test fun finalFlushWaitingForWriteLockPersistsLatestReset() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            repo.authoritativeThreads.establishComplete("host", "c", "e", 10, emptyList(), 0)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val firstWrite = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val latestWrite = CompletableDeferred<Unit>()
            val releaseLatest = CompletableDeferred<Unit>()
            held.beforeWrite = {
                if (held.writes.last().epoch == "reset-a") {
                    firstWrite.complete(Unit)
                    releaseFirst.await()
                } else {
                    latestWrite.complete(Unit)
                    releaseLatest.await()
                }
            }
            val resultA = CompletableDeferred<Result<Unit>>()
            val resetA = backgroundScope.launch { resultA.complete(repo.resetAuthoritativeThread("c", "reset-a")) }
            runCurrent()
            assertTrue(firstWrite.isCompleted)
            observer.cancel()
            runCurrent()
            assertFalse(observer.isCompleted)
            val resultB = CompletableDeferred<Result<Unit>>()
            val resetB = backgroundScope.launch { resultB.complete(repo.resetAuthoritativeThread("c", "reset-b")) }
            runCurrent()
            releaseFirst.complete(Unit)
            runCurrent()
            assertTrue(latestWrite.isCompleted)
            val finishedBeforeInvalidation = observer.isCompleted
            assertFalse(resultB.isCompleted)
            releaseLatest.complete(Unit)
            runCurrent()
            observer.join()
            resetA.join()
            resetB.join()
            assertFalse(finishedBeforeInvalidation)
            assertTrue(resultA.await().isSuccess)
            // B is satisfied by cleanup's newer committed capture, without a duplicate write.
            assertTrue(resultB.await().isSuccess)
            assertEquals(listOf("e", "reset-a", "reset-b"), held.writes.map { it.epoch })
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
            val restored = wrapper().observeAuthoritativeThread("c").first()
            assertEquals("reset-b", restored.epoch)
            assertTrue(restored.items.isEmpty())
            assertEquals(0L, restored.completedVersion)
            assertEquals(0L, restored.checkpoint)
            assertNull(restored.coverage)
        }

    private suspend fun TestScope.overlappingResets(failReplacement: Boolean) {
        val held = HeldCache(fileCache())
        val repo = wrapper(held)
        val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
        runCurrent()
        apply(repo, 10)
        repo.authoritativeThreads.establishComplete("host", "c", "e", 10, emptyList(), 0)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        val oldWrite = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val replacementWrite = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        held.beforeWrite = {
            if (held.writes.last().epoch == "reset-b") {
                replacementWrite.complete(Unit)
                releaseReplacement.await()
            } else {
                oldWrite.complete(Unit)
                releaseOldWrite.await()
            }
        }
        apply(repo, 20)
        val retired = repo.authoritativeThreads.current("host", "c")
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertTrue(oldWrite.isCompleted)
        // Stop collection while the started write finishes, so only the explicit resets compete.
        observer.cancel()
        runCurrent()
        val resultA = CompletableDeferred<Result<Unit>>()
        val resetA = backgroundScope.launch { resultA.complete(repo.resetAuthoritativeThread("c", "reset-a")) }
        runCurrent()
        assertEquals(1L, repo.authoritativeThreads.current("host", "c").generation)
        assertFalse(resultA.isCompleted)
        val resultB = CompletableDeferred<Result<Unit>>()
        val resetB = backgroundScope.launch { resultB.complete(repo.resetAuthoritativeThread("c", "reset-b")) }
        runCurrent()
        assertEquals(2L, repo.authoritativeThreads.current("host", "c").generation)
        assertFalse(resultB.isCompleted)
        releaseOldWrite.complete(Unit)
        runCurrent()
        assertTrue(replacementWrite.isCompleted)
        assertTrue(resultA.isCompleted)
        assertFalse(resultB.isCompleted)
        assertFacts(fileCache(), "host", retired)
        val beforeCommit = wrapper().observeAuthoritativeThread("c").first()
        assertEquals(retired.items.map { it.raw }, beforeCommit.items.map { it.raw })
        assertEquals(retired.completedVersion, beforeCommit.completedVersion)
        assertEquals(retired.checkpoint, beforeCommit.checkpoint)
        assertEquals(retired.coverage, beforeCommit.coverage)
        // Release every owned operation before asserting the acknowledgement under test.
        held.fail = failReplacement
        releaseReplacement.complete(Unit)
        runCurrent()
        resetA.join()
        resetB.join()
        observer.join()
        assertTrue(resultA.await().isFailure)
        assertEquals(failReplacement, resultB.await().isFailure)
        if (failReplacement) {
            assertFacts(fileCache(), "host", retired)
        } else {
            assertFacts(fileCache(), "host", repo.authoritativeThreads.current("host", "c"))
        }
        val restored = wrapper().observeAuthoritativeThread("c").first()
        assertEquals(if (failReplacement) "e" else "reset-b", restored.epoch)
        assertEquals(if (failReplacement) retired.items.map { it.raw } else emptyList<JsonObject>(), restored.items.map { it.raw })
        assertEquals(if (failReplacement) retired.completedVersion else 0L, restored.completedVersion)
        assertEquals(if (failReplacement) retired.checkpoint else 0L, restored.checkpoint)
        assertEquals(if (failReplacement) retired.coverage else null, restored.coverage)
    }

    @Test fun certificationOnlyChangesPersistAtomically() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val uncertified = repo.authoritativeThreads.current("host", "c")
            val checkpoint =
                ThreadQueryReply.Checkpoint(
                    MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":10}""") as JsonObject,
                )
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                repo.authoritativeThreads.certifyQuery(
                    "host",
                    ThreadQueryRequest.CatchUp("c", "e", 0u),
                    ThreadQueryReply.Success(JsonObject(emptyMap()), checkpoint, ThreadQueryReply.Coverage.Window(5u, 11u, 5u, true)),
                    0,
                ),
            )
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            val certified = repo.authoritativeThreads.current("host", "c")
            assertEquals(uncertified.items.map { it.raw }, certified.items.map { it.raw })
            assertEquals(10L, certified.checkpoint)
            assertEquals(AuthoritativeThreadCoverage(5u, 11u, 5u, true), certified.coverage)
            assertFacts(fileCache(), "host", certified)
            observer.cancelAndJoin()
            assertEquals(listOf(0L, 10L), held.writes.map { it.checkpoint })
        }

    @Test fun olderCapturedEmissionCannotOverwriteNewerCommitOnCancellation() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val newerDelivery = CompletableDeferred<Unit>()
            val holdNewerDelivery = CompletableDeferred<Unit>()
            val older =
                backgroundScope.launch {
                    repo.observeAuthoritativeThread("c").collect {
                        if (it.completedVersion == 10L) {
                            captured.complete(Unit)
                            release.await()
                        } else if (it.completedVersion == 20L) {
                            newerDelivery.complete(Unit)
                            holdNewerDelivery.await()
                        }
                    }
                }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            assertTrue(captured.isCompleted)
            val newer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 20)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            newer.cancelAndJoin()
            val committed = repo.authoritativeThreads.current("host", "c")
            assertFacts(fileCache(), "host", committed)
            release.complete(Unit)
            runCurrent()
            assertTrue(newerDelivery.isCompleted)
            older.cancelAndJoin()
            assertFacts(fileCache(), "host", committed)
            assertEquals(listOf(20L), held.writes.map { it.appliedVersion })
        }

    @Test fun resetFailurePreservesSavedRepresentationAndReportsContentFreeFailure() =
        runTest {
            val held = HeldCache(fileCache())
            val repo = wrapper(held)
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
            runCurrent()
            apply(repo, 10)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            observer.cancelAndJoin()
            val prior = repo.authoritativeThreads.current("host", "c")
            val logs = mutableListOf<String>()
            RelayLog.sink = { _, _, line -> logs += line }
            held.fail = true
            assertTrue(repo.resetAuthoritativeThread("c", "private-reset-epoch").isFailure)
            assertFacts(fileCache(), "host", prior)
            assertTrue(logs.contains("event=authoritative_cache_reset_failed"))
            assertTrue(logs.none { "private-reset-epoch" in it || "test failure" in it })
        }

    @Test fun identityProbesConvergeWithoutExpandingCoverage() =
        runTest {
            val facts = listOf(item(1, 2, 5), item(2, 3, 1, kind = "future_kind", extra = "\"opaque\":{\"secret\":\"inert\"},"))
            for (incoming in listOf(facts, facts.reversed(), facts + facts, emptyList(), facts.take(1))) {
                val repo = wrapper()
                val coverage = AuthoritativeThreadCoverage(5u, 11u, 5u, true)
                repo.authoritativeThreads.restore("host", "c", AuthoritativeThreadSnapshot("e", facts, 10, 10, coverage = coverage), 0)
                apply(repo, 20, incoming + ThreadItemDto.decodeReplacement(JsonObject(item(1, 20, 5).raw + ("parent" to JsonNull))))
                val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect {} }
                runCurrent()
                advanceTimeBy(101)
                runCurrent()
                observer.cancelAndJoin()
                val current = repo.authoritativeThreads.current("host", "c")
                assertEquals(listOf(2L, 1L), current.items.map { it.id })
                assertEquals(20L, current.items.last().rev)
                assertEquals(coverage, current.coverage)
                assertFacts(fileCache(), "host", current)
            }
        }
}
