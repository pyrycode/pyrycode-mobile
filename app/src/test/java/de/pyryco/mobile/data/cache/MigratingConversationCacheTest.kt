package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** #2161: real saved files imported into an on-disk Room database, reopened between assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigratingConversationCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("conversations") }
    private val path by lazy { File(temporary.root, "conversation-cache.db").absolutePath }
    private var database: ConversationCacheDatabase? = null

    /** A fresh cache over a fresh database instance, as after a process restart. */
    private fun open(beforeCommit: suspend () -> Unit = {}): MigratingConversationCache {
        database?.close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ConversationCacheDatabase::class.java, path).build()
        database = db
        return MigratingConversationCache(root, db, Dispatchers.IO, beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private val files get() = FileConversationCache(root)
    private val time = Instant.fromEpochSeconds(1)

    private fun row(
        index: Int,
        content: String = "text-$index",
    ) = ThreadItem.MessageItem(Message("id-$index", "s", Role.User, content, Instant.fromEpochSeconds(index.toLong()), false))

    private val allKinds =
        listOf(
            ThreadItem.MessageItem(
                row(1).message.copy(reconciliationId = "ordinary", attachments = listOf(MessageAttachment("ref", "hint", "mime"))),
            ),
            ThreadItem.MessageItem(
                row(2).message.copy(
                    toolCall =
                        ToolCall(
                            "tool",
                            "input",
                            "output",
                            ToolCallStatus.Failed,
                            mapOf(
                                "k" to "v",
                            ),
                        ),
                ),
            ),
            ThreadItem.MessageItem(
                row(3).message.copy(
                    role = Role.Assistant,
                    content = "prefixsuffix",
                    segment = AssistantSegment("turn", listOf(SegmentDelta(2, 6), SegmentDelta(5, 6))),
                ),
            ),
            ThreadItem.SessionBoundary("old", "new", BoundaryReason.Clear, time, "/workspace"),
            ThreadItem.Banner(BannerLevel.Warning, "banner", true, time),
            ThreadItem.CompactionBoundary(100, 50, true, time, true),
            ThreadItem.ModelRefusal("original", "fallback", "refusal", true, time),
            ThreadItem.StoppedTurn("stopped", "reason", "category", time),
        )

    private fun raw(id: Int): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{"id":$id,"rev":$id,"kind":"future-kind","status":"done","active":false,"shown":true,"summary":"private-content","content":{"future":[null,1,"text"]},"order":$id,"unknown":{"x":null}}""",
            ).jsonObject

    /** Incomplete progress: applied version above the certified checkpoint, with a continuation. */
    private fun saved(
        epoch: String = "epoch",
        ids: List<Int> = listOf(3, 1, 2),
    ) = SavedAuthoritativeThread(epoch, ids.map(::raw), 500, 400, SavedThreadCoverage(2, 3, 2, true))

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()

    private fun legacyFile(
        host: String,
        thread: String,
    ) = File(root, "${sha256(host)}/threads/${sha256(thread)}.json")

    private fun authoritativeFile(
        host: String,
        thread: String,
    ) = File(root, "${sha256(host)}/authoritative-threads/${sha256(thread)}.json")

    private suspend fun outcome(
        host: String,
        thread: String,
        family: String,
    ) = requireNotNull(database)
        .imports()
        .settled(host, thread, family)
        .single()
        .outcome

    private fun assertRefused(
        code: String,
        result: Result<Unit>,
    ) = assertTrue(
        result
            .exceptionOrNull()
            ?.message
            .orEmpty()
            .endsWith(code),
    )

    @Test fun ownershipInvariant_legacyFilesStayInertAcrossReopens() =
        runBlocking {
            val owners = listOf("Host" to "thread", "host" to "thread", "Host" to "file-only")
            owners.forEachIndexed { index, (host, thread) ->
                files.writeThread(host, thread, listOf(row(index + 1))).getOrThrow()
                files.writeHistoryPosition(host, thread, HistoryPosition("old-cursor", true)).getOrThrow()
                files.writeAuthoritativeThread(host, thread, saved("epoch-$index", listOf(index + 1))).getOrThrow()
            }
            // There is no ownership envelope in old hashed thread paths. Never scan or guess their owner.
            val unknown = legacyFile("unknown-owner", "unknown-thread")
            unknown.parentFile?.mkdirs()
            unknown.writeBytes(legacyFile("Host", "thread").readBytes())
            val sources = owners.map { (host, thread) -> legacyFile(host, thread) } + unknown
            val originalBytes = sources.map { it.readBytes() }
            files.writeConversations("Host", listOf(conversation("thread"))).getOrThrow()
            val readPositions = mapOf("thread" to ReadPosition("completed", "read"))
            files.writeReadPositions("Host", readPositions).getOrThrow()
            open()
            val stored = LegacyRoomStorage(requireNotNull(database))
            stored.writeThread("Host", "thread", allKinds).getOrThrow()
            stored.writeHistoryPosition("Host", "thread", HistoryPosition("room-cursor", false)).getOrThrow()
            val header = requireNotNull(database).legacy().header("Host", "thread")

            repeat(2) {
                val cache = open()
                assertEquals(listOf(conversation("thread")), cache.readConversations("Host"))
                assertEquals(readPositions, cache.readReadPositions("Host"))
                assertTrue(cache.readReadPositions("host").isEmpty())
                owners.forEachIndexed { index, (host, thread) ->
                    assertEquals(if (index == 0) allKinds else emptyList<ThreadItem>(), cache.readThread(host, thread))
                    assertEquals(if (index == 0) HistoryPosition("room-cursor", false) else null, cache.readHistoryPosition(host, thread))
                    assertEquals(saved("epoch-$index", listOf(index + 1)).retained(), cache.readAuthoritativeThread(host, thread))
                    assertTrue(requireNotNull(database).imports().settled(host, thread, "legacy").isEmpty())
                    assertEquals("imported", outcome(host, thread, "authoritative"))
                }
                assertEquals(header, requireNotNull(database).legacy().header("Host", "thread"))
                assertEquals(emptyList<ThreadItem>(), cache.readThread("unknown-owner", "unknown-thread"))
                assertNull(cache.readAuthoritativeThread("unknown-owner", "unknown-thread"))
                assertEquals(emptyList<ThreadItem>(), requireNotNull(cache.newestLegacyPage("host", "thread", 2)).rows)
                sources.zip(originalBytes).forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
            }
        }

    @Test fun atomicityInvariant_interruptedImportKeepsSourceAndRetries() =
        runBlocking {
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val bytes = authoritativeFile("Host", "thread").readBytes()
            val failing = open { throw IOException("interrupted before commit") }
            assertNull(failing.readAuthoritativeThread("Host", "thread"))
            assertRefused("import_unavailable", failing.writeAuthoritativeThread("Host", "thread", saved("newer")))
            assertTrue(requireNotNull(database).imports().settled("Host", "thread", "authoritative").isEmpty())
            assertNull(requireNotNull(database).authoritative().header("Host", "thread"))
            assertArrayEquals(bytes, authoritativeFile("Host", "thread").readBytes())

            val entered = CompletableDeferred<Unit>()
            val held =
                open {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            val reader = launch(Dispatchers.IO) { held.readAuthoritativeThread("Host", "thread") }
            entered.await()
            reader.cancelAndJoin()
            assertTrue(requireNotNull(database).imports().settled("Host", "thread", "authoritative").isEmpty())
            assertNull(requireNotNull(database).authoritative().header("Host", "thread"))
            assertArrayEquals(bytes, authoritativeFile("Host", "thread").readBytes())

            assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
            assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
        }

    @Test fun idempotenceInvariant_committedImportIsNeverRepeatedOrOverwritten() =
        runBlocking {
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val source = authoritativeFile("Host", "thread").readBytes()
            assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
            val marker = requireNotNull(database).imports().settled("Host", "thread", "authoritative")
            val cache = open()
            cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))).getOrThrow()
            repeat(2) {
                assertEquals(saved("newer", listOf(4)).retained(), open().readAuthoritativeThread("Host", "thread"))
                assertEquals(marker, requireNotNull(database).imports().settled("Host", "thread", "authoritative"))
            }
            assertArrayEquals(source, authoritativeFile("Host", "thread").readBytes())

            // Database content without a marker is newer than any leftover file.
            files.writeAuthoritativeThread("Host", "held", saved()).getOrThrow()
            AuthoritativeRoomStorage(
                requireNotNull(database),
            ).writeAuthoritativeThread("Host", "held", saved("room", listOf(6))).getOrThrow()
            assertEquals(saved("room", listOf(6)).retained(), open().readAuthoritativeThread("Host", "held"))
            assertEquals("superseded", outcome("Host", "held", "authoritative"))
        }

    @Test fun classificationInvariant_rejectedSourcesArePreservedNeverEmptyReplaced() =
        runBlocking {
            val messages = mutableListOf<String>()
            val oldSink = RelayLog.sink
            val oldEnabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> messages += message }
            try {
                val host = "secret-host"
                files.writeAuthoritativeThread(host, "valid", saved()).getOrThrow()
                authoritativeFile(host, "invalid").writeText("{not json secret-content")
                authoritativeFile(
                    host,
                    "future",
                ).writeText(authoritativeFile(host, "valid").readText().replace("\"version\":1", "\"version\":2"))
                authoritativeFile(host, "copied").writeBytes(authoritativeFile(host, "valid").readBytes())
                val preserved = listOf("invalid", "future", "copied").map { authoritativeFile(host, it) }
                val bytes = preserved.map { it.readBytes() }
                val cache = open()
                for ((thread, expectedOutcome) in listOf(
                    "invalid" to "invalid",
                    "future" to "unsupported",
                    "copied" to "invalid",
                    "absent" to "absent",
                )) {
                    assertNull(cache.readAuthoritativeThread(host, thread))
                    assertEquals(expectedOutcome, outcome(host, thread, "authoritative"))
                }
                cache.writeAuthoritativeThread(host, "invalid", saved("newer")).getOrThrow()
                assertEquals(saved("newer").retained(), open().readAuthoritativeThread(host, "invalid"))
                bytes.zip(preserved).forEach { (content, file) -> assertArrayEquals(content, file.readBytes()) }
                assertEquals(saved().retained(), open().readAuthoritativeThread(host, "valid"))
                assertTrue(messages.isNotEmpty())
                assertFalse(messages.any { "secret" in it || "private-content" in it })
            } finally {
                RelayLog.sink = oldSink
                RelayLog.enabled = oldEnabled
            }
        }

    @Test fun classificationInvariant_unreadableSourceIsRetriedAndBlocksWrites() =
        runBlocking {
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val source = authoritativeFile("Host", "thread")
            source.setReadable(false)
            try {
                assumeFalse("the process can read files without permission", source.canRead())
                val cache = open()
                assertNull(cache.readAuthoritativeThread("Host", "thread"))
                assertRefused("import_unavailable", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertRefused("import_unavailable", cache.resetAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertTrue(requireNotNull(database).imports().settled("Host", "thread", "authoritative").isEmpty())
                source.setReadable(true)
                assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertRefused("import_unobserved", cache.resetAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
                assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
            } finally {
                source.setReadable(true)
            }
        }

    @Test fun classificationInvariant_inaccessibleSourceParentIsRetriedAcrossRestart() =
        runBlocking {
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val source = authoritativeFile("Host", "thread")
            val bytes = source.readBytes()
            val parent = requireNotNull(source.parentFile).toPath()
            val original = Files.getPosixFilePermissions(parent)
            val search = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
            try {
                Files.setPosixFilePermissions(parent, original - search)
                assumeFalse("the process can search directories without permission", source.canRead())
                assertFalse(source.isFile)
                assertTrue(runCatching { Files.readAllBytes(source.toPath()) }.exceptionOrNull() is AccessDeniedException)
                var cache = open()
                repeat(2) {
                    cache = open()
                    assertNull(cache.readAuthoritativeThread("Host", "thread"))
                    assertRefused("import_unavailable", cache.writeAuthoritativeThread("Host", "thread", saved("newer")))
                    assertTrue(requireNotNull(database).imports().settled("Host", "thread", "authoritative").isEmpty())
                }
                Files.setPosixFilePermissions(parent, original)
                assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer")))
                assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
                assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
                assertEquals("imported", outcome("Host", "thread", "authoritative"))
                assertArrayEquals(bytes, source.readBytes())
            } finally {
                Files.setPosixFilePermissions(parent, original)
            }
        }

    @Test fun classificationInvariant_deferredImportIsNeverReplacedByAnUnobservedWrite() =
        runBlocking {
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            files.writeAuthoritativeThread("Host", "unread", saved()).getOrThrow()
            val failing = AtomicBoolean(true)
            val cache = open { if (failing.get()) throw IOException("database busy") }
            assertNull(cache.readAuthoritativeThread("Host", "thread"))
            failing.set(false)
            assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
            assertEquals("imported", outcome("Host", "thread", "authoritative"))
            assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
            // A later read cannot prove that this process's writers observed the imported content.
            assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
            assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "unread", saved("newer", listOf(5))))
            assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "unread"))

            val restarted = open()
            assertEquals(saved().retained(), restarted.readAuthoritativeThread("Host", "thread"))
            restarted.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))).getOrThrow()
            assertEquals(saved("newer", listOf(4)).retained(), open().readAuthoritativeThread("Host", "thread"))
        }

    @Test
    fun cleanupInvariant_lateContinuationCompletesAfterInitialDrain() =
        runBlocking {
            withHeldReader { reader, scheduler, finalizing, release ->
                cancelAndDrain(reader, scheduler, timeoutMillis = 1_000) {
                    assertTrue(finalizing.isCompleted)
                    assertFalse(reader.isCompleted)
                    // Queue the completion strictly after the cleanup's first drain has returned.
                    release.complete(Unit)
                    assertFalse(reader.isCompleted)
                }
                assertTrue(reader.isCancelled)
                assertTrue(reader.isCompleted)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cleanupInvariant_nonCompletionFailsWithoutWaitingForOwner() =
        runBlocking {
            val started = System.nanoTime()
            val scheduler = TestCoroutineScheduler()
            val finalizing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val published = CompletableDeferred<Job>()
            val attempt =
                async(Dispatchers.Default) {
                    val owner = CoroutineScope(StandardTestDispatcher(scheduler))
                    val reader = owner.launch { holdCancellation(finalizing, release) }
                    published.complete(reader)
                    try {
                        scheduler.runCurrent()
                        runCatching { cancelAndDrain(reader, scheduler, timeoutMillis = 100) }
                    } finally {
                        owner.cancel()
                    }
                }
            try {
                // The enclosing coroutine must return before the held child's release, even on failure.
                val failure = withTimeout(1_000) { attempt.await() }.exceptionOrNull()
                val reader = published.await()
                assertTrue("expected a diagnostic cleanup failure, got $failure", failure is AssertionError)
                assertTrue(requireNotNull(failure).message.orEmpty().contains("cancelled=true completed=false"))
                assertTrue(finalizing.isCompleted)
                assertFalse(reader.isCompleted)
                assertFalse(release.isCompleted)
            } finally {
                release.complete(Unit)
                cancelAndDrain(attempt, scheduler, timeoutMillis = 1_000)
                if (published.isCompleted) cancelAndDrain(published.await(), scheduler, timeoutMillis = 1_000)
            }
            assertTrue("cleanup and enclosing scope must return within 10 seconds", System.nanoTime() - started < 10_000_000_000L)
        }

    private suspend fun holdCancellation(
        finalizing: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                finalizing.complete(Unit)
                release.await()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun withHeldReader(
        block: suspend (Job, TestCoroutineScheduler, CompletableDeferred<Unit>, CompletableDeferred<Unit>) -> Unit,
    ) {
        val scheduler = TestCoroutineScheduler()
        val owner = CoroutineScope(StandardTestDispatcher(scheduler))
        val finalizing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reader = owner.launch { holdCancellation(finalizing, release) }
        try {
            scheduler.runCurrent()
            block(reader, scheduler, finalizing, release)
        } finally {
            owner.cancel()
            release.complete(Unit)
            scheduler.runCurrent()
            withTimeout(1_000) { reader.join() }
            assertTrue(reader.isCompleted)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun cancelAndDrain(
        reader: Job,
        scheduler: TestCoroutineScheduler,
        timeoutMillis: Long = 10_000,
        afterFirstDrain: () -> Unit = {},
    ) = withContext(NonCancellable + Dispatchers.Default) {
        // Room can dispatch cancellation back after an earlier runCurrent has already returned.
        val completed =
            withTimeoutOrNull(timeoutMillis) {
                reader.cancel()
                scheduler.runCurrent()
                afterFirstDrain()
                while (!reader.isCompleted) {
                    delay(1)
                    scheduler.runCurrent()
                }
                true
            }
        if (completed != true) {
            throw AssertionError(
                "Cancelled import reader did not stop within ${timeoutMillis}ms: " +
                    "active=${reader.isActive} cancelled=${reader.isCancelled} completed=${reader.isCompleted} " +
                    "virtualTime=${scheduler.currentTime}",
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun classificationInvariant_cancelledCommittedImportCannotReadmitUnobservedWrites() =
        runBlocking {
            for (writeImport in listOf(false, true)) {
                val thread = "cancelled-authoritative-$writeImport"
                files.writeAuthoritativeThread("Host", thread, saved()).getOrThrow()
                val failing = AtomicBoolean(true)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cache =
                    open {
                        if (failing.get()) throw IOException("database busy")
                        entered.complete(Unit)
                        release.await()
                    }

                suspend fun read() = cache.readAuthoritativeThread("Host", thread)

                suspend fun replace() = cache.writeAuthoritativeThread("Host", thread, saved("newer", listOf(4)))

                // Both a retrying read and a first-write import can commit before cancelled result delivery.
                if (!writeImport) assertNull(read())
                failing.set(false)
                val scheduler = TestCoroutineScheduler()
                val readerScope = CoroutineScope(StandardTestDispatcher(scheduler))
                val reader = readerScope.launch { if (writeImport) replace() else read() }
                try {
                    withTimeout(10_000) {
                        while (!entered.isCompleted) {
                            scheduler.runCurrent()
                            delay(1)
                        }
                    }
                    release.complete(Unit)
                    withTimeout(10_000) {
                        while (requireNotNull(database).imports().settled("Host", thread, "authoritative").isEmpty()) delay(1)
                    }
                } finally {
                    release.complete(Unit)
                    readerScope.cancel()
                    cancelAndDrain(reader, scheduler)
                }
                assertTrue(reader.isCancelled)
                assertRefused("import_unobserved", replace())
                assertEquals(saved().retained(), read())
                assertEquals(saved().retained(), open().readAuthoritativeThread("Host", thread))
            }
        }

    @Test fun retirementInvariant_deletionWinsOverHeldWorkAndRestart() =
        runBlocking {
            val rows = listOf(row(1), row(2))
            for (host in listOf("Host", "host")) {
                files.writeThread(host, "thread", rows).getOrThrow()
                files.writeAuthoritativeThread(host, "thread", saved()).getOrThrow()
            }
            val sources = listOf(legacyFile("Host", "thread"), authoritativeFile("Host", "thread"))
            val bytes = sources.map { it.readBytes() }
            val otherSources = listOf(legacyFile("host", "thread"), authoritativeFile("host", "thread"))
            val otherBytes = otherSources.map { it.readBytes() }
            val hold = AtomicReference<CompletableDeferred<Unit>?>()
            val entered = CompletableDeferred<Unit>()
            val cache =
                open {
                    hold.get()?.let {
                        entered.complete(Unit)
                        it.await()
                    }
                }
            // A captured import holds its commit while deletion is requested.
            hold.set(CompletableDeferred())
            val importing = async(Dispatchers.IO) { cache.readAuthoritativeThread("Host", "thread") }
            entered.await()
            val removing = async(Dispatchers.IO) { cache.removeConversation("Host", "thread") }
            delay(50)
            assertFalse(removing.isCompleted)
            requireNotNull(hold.getAndSet(null)).complete(Unit)
            assertEquals(saved().retained(), importing.await())
            removing.await().getOrThrow()
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "thread"))
            assertNull(cache.readAuthoritativeThread("Host", "thread"))

            // A held save is released after deletion was requested; deletion still wins.
            cache.writeAuthoritativeThread("Host", "saved", saved()).getOrThrow()
            val heldSave = CompletableDeferred<Unit>()
            val savingEntered = CompletableDeferred<Unit>()
            val saving =
                open {
                    if (!heldSave.isCompleted) {
                        savingEntered.complete(Unit)
                        heldSave.await()
                    }
                }
            saving.readAuthoritativeThread("Host", "saved")
            val write = async(Dispatchers.IO) { saving.writeAuthoritativeThread("Host", "saved", saved("newer", listOf(3))) }
            savingEntered.await()
            val remove = async(Dispatchers.IO) { saving.removeConversation("Host", "saved") }
            delay(50)
            heldSave.complete(Unit)
            write.await().getOrThrow()
            remove.await().getOrThrow()
            assertNull(saving.readAuthoritativeThread("Host", "saved"))

            // A source cleanup that failed leaves the files; restart still cannot resurrect them.
            sources.zip(bytes).forEach { (file, content) -> file.apply { parentFile?.mkdirs() }.writeBytes(content) }
            val restarted = open()
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("Host", "thread"))
            assertNull(restarted.readAuthoritativeThread("Host", "thread"))
            assertNull(restarted.readAuthoritativeThread("Host", "saved"))
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("host", "thread"))
            assertEquals(saved().retained(), restarted.readAuthoritativeThread("host", "thread"))
            otherSources.zip(otherBytes).forEach { (file, content) -> assertArrayEquals(content, file.readBytes()) }
        }

    @Test fun retirementInvariant_hostForgettingFencesSavesAndRestart() =
        runBlocking {
            val rows = listOf(row(1), row(2))
            for ((host, thread) in listOf("Host" to "a", "Host" to "b", "host" to "a")) {
                files.writeThread(host, thread, rows).getOrThrow()
                files.writeAuthoritativeThread(host, thread, saved()).getOrThrow()
            }
            val sources = listOf(legacyFile("Host", "b"), authoritativeFile("Host", "b"))
            val bytes = sources.map { it.readBytes() }
            val otherSources = listOf(legacyFile("host", "a"), authoritativeFile("host", "a"))
            val otherBytes = otherSources.map { it.readBytes() }
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val holding = AtomicBoolean(false)
            val cache =
                open {
                    if (holding.get()) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "a"))
            holding.set(true)
            val save = async(Dispatchers.IO) { cache.writeAuthoritativeThread("Host", "a", saved("newer", listOf(3))) }
            entered.await()
            holding.set(false)
            val forget = async(Dispatchers.IO) { cache.removeHost("Host") }
            delay(50)
            assertFalse(forget.isCompleted)
            release.complete(Unit)
            save.await().getOrThrow()
            forget.await().getOrThrow()
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "a"))
            assertNull(cache.readAuthoritativeThread("Host", "a"))

            // The forgotten host's own list collector writes after the removal; its closing repository's final save is still refused.
            cache.writeConversations("Host", listOf(conversation("a"))).getOrThrow()
            assertRefused("host_retired", cache.writeAuthoritativeThread("Host", "a", saved()))
            assertRefused("host_retired", cache.resetAuthoritativeThread("Host", "a", saved()))
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "a"))

            // Recursive delete failed to remove sources: restart neither imports them nor touches the other host.
            sources.zip(bytes).forEach { (file, content) -> file.apply { parentFile?.mkdirs() }.writeBytes(content) }
            val restarted = open()
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("Host", "b"))
            assertNull(restarted.readAuthoritativeThread("Host", "b"))
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("host", "a"))
            assertEquals(saved().retained(), restarted.readAuthoritativeThread("host", "a"))
            otherSources.zip(otherBytes).forEach { (file, content) -> assertArrayEquals(content, file.readBytes()) }

            // After a restart no stale save exists, so a re-paired host's saves are accepted.
            val repaired = open()
            repaired.writeAuthoritativeThread("Host", "a", saved()).getOrThrow()
            // In-process, forgetting fences saves until the host is paired again; a list write never readmits it.
            repaired.removeHost("Host").getOrThrow()
            repaired.writeConversations("Host", listOf(conversation("a"))).getOrThrow()
            assertRefused("host_retired", repaired.writeAuthoritativeThread("Host", "a", saved()))
            repaired.admitHost("Host")
            repaired.writeAuthoritativeThread("Host", "a", saved("repaired", listOf(4))).getOrThrow()
            assertEquals(saved("repaired", listOf(4)).retained(), open().readAuthoritativeThread("Host", "a"))
        }

    @Test fun certificationInvariant_importAndPagesKeepSavedCheckpoint() =
        runBlocking {
            val items = (1..450).toList()
            val expected = saved(ids = items).retained()
            files.writeAuthoritativeThread("Host", "thread", saved(ids = items)).getOrThrow()
            val cache = open()
            assertEquals(expected, cache.readAuthoritativeThread("Host", "thread"))
            var page = requireNotNull(cache.newestAuthoritativePage("Host", "thread", 200))
            val visited = page.items.toMutableList()
            while (true) {
                assertEquals(500, page.appliedVersion)
                assertEquals(400, page.checkpoint)
                assertEquals(expected.coverage, page.coverage)
                val token = page.continuation ?: break
                page = requireNotNull(cache.beforeAuthoritativePage("Host", "thread", token, 200))
                visited.addAll(0, page.items)
            }
            assertEquals(expected.items, visited)
            assertEquals(expected, open().readAuthoritativeThread("Host", "thread"))
        }

    private fun conversation(id: String) =
        Conversation(
            id = id,
            name = null,
            cwd = "/probe",
            currentSessionId = "session",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-10-17T00:00:00Z"),
        )
}
