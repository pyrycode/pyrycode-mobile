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
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnsignedHistoryGap
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyIdentity
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
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

    private fun unsignedCoverage(rows: List<ThreadItem>): HistoryCoverage {
        val ids = listOf(1uL, Long.MAX_VALUE.toULong() + 1u, ULong.MAX_VALUE)
        return HistoryCoverage(
            unsignedSpans = ids.map { UnsignedHistorySpan(it, it) },
            unsignedGaps = ids.zipWithNext { a, b -> UnsignedHistoryGap(a, b) },
            unsignedRowOrder = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to id },
            unsignedRowEntries = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to setOf(id) },
            proofs = historyRowProofs(rows),
            unsignedCursors = ids.associateWith { "cursor-$it" },
            unsignedWalks = mapOf(0uL to "unknown-walk", ULong.MAX_VALUE to "max-walk"),
            unsignedUnknown = true,
            newestCursor = "newest",
        ).validated(rows)
    }

    private fun fragments(): Pair<ThreadItem, HistoryCoverage> {
        val legacy = row(9).copy(message = row(9).message.copy(role = Role.Assistant, content = "abXXcd"))
        val keys = listOf(historyIdentity(listOf("delta", "id-9", 0)), historyIdentity(listOf("delta", "id-9", 2)))

        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
        return legacy to
            HistoryCoverage(
                unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(3u, 3u)),
                unsignedGaps = listOf(UnsignedHistoryGap(1u, 3u)),
                unsignedRowOrder = keys.zip(listOf(1uL, 3uL)).toMap(),
                unsignedRowEntries = keys.zip(listOf(setOf(1uL), setOf(3uL))).toMap(),
                proofs = keys.associateWith { cachedThreadRowProof(legacy) },
                legacyKeys = keys.associateWith { legacy.historyKeys().first() },
                legacyOffsets = keys.zip(listOf(0, 4)).toMap(),
                deltaLengths = keys.associateWith { 2 },
                deltaHashes = keys.zip(listOf(hash("ab"), hash("cd"))).toMap(),
            ).validated(listOf(legacy))
    }

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

    private fun json(value: Any?) =
        when (value) {
            null -> null
            is HistoryPosition -> MobileJson.encodeToJsonElement(value.coverage) to (value.cursor to value.atStart)
            else -> error("unexpected")
        }

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

    @Test fun ownershipInvariant_bothFamiliesMigrateExactlyAndStayIsolated() =
        runBlocking {
            val (fragment, fragmentCoverage) = fragments()
            val unsignedRows = listOf(row(4), row(5), row(6))
            val owners =
                mapOf(
                    ("Host" to "thread") to (allKinds to HistoryPosition("opaque", false)),
                    ("Host" to "other") to (unsignedRows to HistoryPosition("saved", false, unsignedCoverage(unsignedRows))),
                    ("host" to "thread") to (listOf(fragment) to HistoryPosition("fragments", false, fragmentCoverage)),
                )
            val writer = files
            owners.forEach { (owner, content) ->
                writer.writeThread(owner.first, owner.second, content.first).getOrThrow()
                writer.writeHistoryPosition(owner.first, owner.second, content.second).getOrThrow()
            }
            writer.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            writer.writeAuthoritativeThread("host", "thread", saved("other-epoch", listOf(7))).getOrThrow()
            // Today's reader defines exactness; read it before anything is migrated.
            val expected =
                owners.keys.associateWith { (host, thread) ->
                    Triple(
                        files.readThread(host, thread),
                        json(files.readHistoryPosition(host, thread)),
                        files.readAuthoritativeThread(host, thread),
                    )
                }
            assertEquals(allKinds, expected.getValue("Host" to "thread").first)
            assertEquals(saved().retained(), expected.getValue("Host" to "thread").third)

            repeat(2) {
                val cache = open()
                expected.forEach { (owner, value) ->
                    assertEquals(value.first, cache.readThread(owner.first, owner.second))
                    assertEquals(value.second, json(cache.readHistoryPosition(owner.first, owner.second)))
                    assertEquals(value.third, cache.readAuthoritativeThread(owner.first, owner.second))
                }
            }
            // Served from Room: the storage reads the same facts without the files.
            val db = requireNotNull(database)
            assertEquals(allKinds, LegacyRoomStorage(db).readThread("Host", "thread"))
            assertEquals(saved().retained(), AuthoritativeRoomStorage(db).readAuthoritativeThread("Host", "thread"))
            // Legacy rows never become authoritative items, and case-distinct owners stay apart.
            assertNull(open().readAuthoritativeThread("Host", "other"))
            assertEquals(emptyList<ThreadItem>(), open().readThread("host", "other"))
            assertEquals("imported", outcome("Host", "other", "legacy"))
            assertEquals("absent", outcome("Host", "other", "authoritative"))
        }

    @Test fun ownershipInvariant_invalidOptionalHistoryImportsRowsWithoutCertification() =
        runBlocking {
            val rows = listOf(row(1), row(2), row(3))
            val variants =
                listOf(
                    "{\"atStart\":false}",
                    "{\"cursor\":null,\"atStart\":false}",
                    "{\"cursor\":\"saved\",\"atStart\":false,\"coverage\":{\"spans\":\"broken\"}}",
                    "{\"cursor\":\"saved\",\"atStart\":true}",
                )
            variants.forEachIndexed { index, history ->
                val thread = "thread-$index"
                files.writeThread("Host", thread, rows).getOrThrow()
                val document = legacyFile("Host", thread)
                val stored = MobileJson.parseToJsonElement(document.readText()).jsonObject
                document.writeText(JsonObject(stored + ("history" to MobileJson.parseToJsonElement(history))).toString())
                val expected = json(files.readHistoryPosition("Host", thread))
                val cache = open()
                assertEquals(rows, cache.readThread("Host", thread))
                assertEquals(expected, json(cache.readHistoryPosition("Host", thread)))
                assertEquals("imported", outcome("Host", thread, "legacy"))
            }
            assertNull(open().readHistoryPosition("Host", "thread-0"))
            assertEquals(HistoryPosition("saved", true), open().readHistoryPosition("Host", "thread-3"))
        }

    @Test fun ownershipInvariant_historyOnlyRoomRejectsImportsRowsWithoutCertification() =
        runBlocking {
            val rows = listOf(row(1), row(2))
            val keys = rows.map { it.historyKeys().first() }
            // Two rows of one history entry, the second with a stale proof. Retention drops the entry with that row, so
            // the projection today's reader serves keeps the first row's claim outside every span.
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(5u, 5u)),
                    unsignedRowOrder = keys.associateWith { 5uL },
                    unsignedRowEntries = keys.associateWith { setOf(5uL) },
                    proofs = mapOf(keys[0] to cachedThreadRowProof(rows[0]), keys[1] to cachedThreadRowProof(row(9))),
                )
            files.writeThread("Host", "thread", rows).getOrThrow()
            val document = legacyFile("Host", "thread")
            val stored = MobileJson.parseToJsonElement(document.readText()).jsonObject
            val history =
                JsonObject(
                    mapOf(
                        "cursor" to JsonPrimitive("saved"),
                        "atStart" to JsonPrimitive(false),
                        "coverage" to MobileJson.encodeToJsonElement(coverage),
                    ),
                )
            document.writeText(JsonObject(stored + ("history" to history)).toString())
            val bytes = document.readBytes()
            val served = requireNotNull(files.readHistoryPosition("Host", "thread")?.coverage)
            assertTrue(runCatching { served.validated(rows) }.isFailure)

            val cache = open()
            assertEquals(rows, cache.readThread("Host", "thread"))
            assertNull(cache.readHistoryPosition("Host", "thread"))
            assertEquals("imported", outcome("Host", "thread", "legacy"))
            assertEquals(rows, open().readThread("Host", "thread"))
            assertNull(open().readHistoryPosition("Host", "thread"))
            assertArrayEquals(bytes, document.readBytes())
        }

    @Test fun atomicityInvariant_interruptedImportKeepsSourceAndRetries() =
        runBlocking {
            val rows = listOf(row(1), row(2))
            files.writeThread("Host", "thread", rows).getOrThrow()
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val bytes = legacyFile("Host", "thread").readBytes()

            val failing = open { throw IOException("interrupted before commit") }
            assertEquals(emptyList<ThreadItem>(), failing.readThread("Host", "thread"))
            assertRefused("import_unavailable", failing.writeThread("Host", "thread", listOf(row(3))))
            assertNull(failing.readAuthoritativeThread("Host", "thread"))
            assertTrue(requireNotNull(database).imports().settled("Host", "thread", "legacy").isEmpty())
            assertArrayEquals(bytes, legacyFile("Host", "thread").readBytes())

            // Cancellation inside the transaction rolls back content and marker together.
            val entered = CompletableDeferred<Unit>()
            val held =
                open {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            val reader = launch(Dispatchers.IO) { held.readThread("Host", "thread") }
            entered.await()
            reader.cancelAndJoin()
            assertTrue(requireNotNull(database).imports().settled("Host", "thread", "legacy").isEmpty())
            assertTrue(requireNotNull(database).legacy().header("Host", "thread") == null)

            val retried = open()
            assertEquals(rows, retried.readThread("Host", "thread"))
            assertEquals(saved().retained(), retried.readAuthoritativeThread("Host", "thread"))
            assertEquals(rows, open().readThread("Host", "thread"))
        }

    @Test fun idempotenceInvariant_committedImportIsNeverRepeatedOrOverwritten() =
        runBlocking {
            files.writeThread("Host", "thread", listOf(row(1))).getOrThrow()
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val source = legacyFile("Host", "thread").readBytes()
            assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
            assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
            val cache = open()
            cache.writeThread("Host", "thread", listOf(row(2))).getOrThrow()
            cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))).getOrThrow()
            repeat(2) {
                val restarted = open()
                assertEquals(listOf(row(2)), restarted.readThread("Host", "thread"))
                assertEquals(saved("newer", listOf(4)).retained(), restarted.readAuthoritativeThread("Host", "thread"))
            }
            assertArrayEquals(source, legacyFile("Host", "thread").readBytes())

            // Database content written without a marker is newer than any leftover file.
            files.writeThread("Host", "held", listOf(row(5))).getOrThrow()
            LegacyRoomStorage(requireNotNull(database)).writeThread("Host", "held", listOf(row(6))).getOrThrow()
            assertEquals(listOf(row(6)), open().readThread("Host", "held"))
            assertEquals("superseded", outcome("Host", "held", "legacy"))
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
                files.writeThread(host, "valid", listOf(row(1, "secret-content"))).getOrThrow()
                files.writeAuthoritativeThread(host, "valid", saved()).getOrThrow()
                legacyFile(host, "invalid").apply { parentFile?.mkdirs() }.writeText("{not json secret-content")
                legacyFile(host, "future").writeText(legacyFile(host, "valid").readText().replace("\"version\":1", "\"version\":2"))
                // Exact owner check: a valid envelope copied under another conversation's name is rejected.
                authoritativeFile(host, "copied").writeBytes(authoritativeFile(host, "valid").readBytes())
                val preserved = listOf(legacyFile(host, "invalid"), legacyFile(host, "future"), authoritativeFile(host, "copied"))
                val bytes = preserved.map { it.readBytes() }

                val cache = open()
                assertEquals(emptyList<ThreadItem>(), cache.readThread(host, "invalid"))
                assertEquals(emptyList<ThreadItem>(), cache.readThread(host, "future"))
                assertNull(cache.readAuthoritativeThread(host, "copied"))
                assertEquals("invalid", outcome(host, "invalid", "legacy"))
                assertEquals("unsupported", outcome(host, "future", "legacy"))
                assertEquals("invalid", outcome(host, "copied", "authoritative"))
                assertEquals(emptyList<ThreadItem>(), cache.readThread(host, "absent"))
                assertEquals("absent", outcome(host, "absent", "legacy"))
                // Later saves go to the database; the rejected bytes are never rewritten or deleted.
                cache.writeThread(host, "invalid", listOf(row(2))).getOrThrow()
                assertEquals(listOf(row(2)), open().readThread(host, "invalid"))
                bytes.zip(preserved).forEach { (content, file) -> assertArrayEquals(content, file.readBytes()) }

                assertEquals(listOf(row(1, "secret-content")), open().readThread(host, "valid"))
                assertTrue(messages.isNotEmpty())
                assertFalse(messages.any { "secret" in it || "private-content" in it })
            } finally {
                RelayLog.sink = oldSink
                RelayLog.enabled = oldEnabled
            }
        }

    @Test fun classificationInvariant_unreadableSourceIsRetriedAndBlocksWrites() =
        runBlocking {
            files.writeThread("Host", "thread", listOf(row(1))).getOrThrow()
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val locked = listOf(legacyFile("Host", "thread"), authoritativeFile("Host", "thread"))
            locked.forEach { it.setReadable(false) }
            try {
                assumeFalse("the process can read files without permission", locked.any { it.canRead() })
                val cache = open()
                assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "thread"))
                assertNull(cache.readAuthoritativeThread("Host", "thread"))
                assertRefused("import_unavailable", cache.writeThread("Host", "thread", listOf(row(2))))
                assertRefused("import_unavailable", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertRefused("import_unavailable", cache.resetAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertTrue(requireNotNull(database).imports().settled("Host", "thread", "legacy").isEmpty())
                locked.forEach { it.setReadable(true) }
                // The next save was merged from the empty restore: the import lands and that save does not replace it.
                assertRefused("import_unobserved", cache.writeThread("Host", "thread", listOf(row(2))))
                assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertRefused("import_unobserved", cache.resetAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
                assertEquals(listOf(row(1)), cache.readThread("Host", "thread"))
                assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
                assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
                assertEquals(saved().retained(), open().readAuthoritativeThread("Host", "thread"))
            } finally {
                locked.forEach { it.setReadable(true) }
            }
        }

    @Test fun classificationInvariant_inaccessibleSourceParentIsRetriedAcrossRestart() =
        runBlocking {
            val rows = listOf(row(1))
            files.writeThread("Host", "thread", rows).getOrThrow()
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            val sources = listOf(legacyFile("Host", "thread"), authoritativeFile("Host", "thread"))
            val bytes = sources.map { it.readBytes() }
            val permissions =
                sources.associate {
                    val parent = requireNotNull(it.parentFile).toPath()
                    parent to Files.getPosixFilePermissions(parent)
                }
            val search = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
            try {
                permissions.forEach { (parent, original) -> Files.setPosixFilePermissions(parent, original - search) }
                assumeFalse("the process can search directories without permission", sources.any { it.canRead() })
                sources.forEach { source ->
                    assertFalse(source.isFile)
                    assertTrue(runCatching { Files.readAllBytes(source.toPath()) }.exceptionOrNull() is AccessDeniedException)
                }
                var cache = open()
                repeat(2) {
                    cache = open()
                    assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "thread"))
                    assertNull(cache.readAuthoritativeThread("Host", "thread"))
                    assertRefused("import_unavailable", cache.writeThread("Host", "thread", listOf(row(2))))
                    assertRefused("import_unavailable", cache.writeAuthoritativeThread("Host", "thread", saved("newer")))
                    listOf("legacy", "authoritative").forEach { family ->
                        assertTrue(requireNotNull(database).imports().settled("Host", "thread", family).isEmpty())
                    }
                }
                permissions.forEach { (parent, original) -> Files.setPosixFilePermissions(parent, original) }
                // Recovery must import the saved content without admitting a writer built from the failed restore.
                assertRefused("import_unobserved", cache.writeThread("Host", "thread", listOf(row(2))))
                assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer")))
                assertEquals(rows, cache.readThread("Host", "thread"))
                assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
                val restarted = open()
                assertEquals(rows, restarted.readThread("Host", "thread"))
                assertEquals(saved().retained(), restarted.readAuthoritativeThread("Host", "thread"))
                listOf("legacy", "authoritative").forEach { assertEquals("imported", outcome("Host", "thread", it)) }
                sources.zip(bytes).forEach { (source, original) -> assertArrayEquals(original, source.readBytes()) }
            } finally {
                permissions.forEach { (parent, original) -> Files.setPosixFilePermissions(parent, original) }
            }
        }

    @Test fun classificationInvariant_deferredImportIsNeverReplacedByAnUnobservedWrite() =
        runBlocking {
            val rows = listOf(row(1), row(2))
            files.writeThread("Host", "thread", rows).getOrThrow()
            files.writeAuthoritativeThread("Host", "thread", saved()).getOrThrow()
            files.writeThread("Host", "unread", rows).getOrThrow()
            val failing = AtomicBoolean(true)
            val cache = open { if (failing.get()) throw IOException("database busy") }
            // Both restores see nothing while the import cannot commit.
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "thread"))
            assertNull(cache.readAuthoritativeThread("Host", "thread"))
            failing.set(false)
            assertRefused("import_unobserved", cache.writeThread("Host", "thread", listOf(row(3))))
            assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
            assertEquals("imported", outcome("Host", "thread", "legacy"))
            assertEquals("imported", outcome("Host", "thread", "authoritative"))
            assertEquals(rows, cache.readThread("Host", "thread"))
            assertEquals(saved().retained(), cache.readAuthoritativeThread("Host", "thread"))
            // A collection that restored empty may still be saving, so a later read cannot readmit this process's saves.
            assertRefused("import_unobserved", cache.writeThread("Host", "thread", listOf(row(3))))
            assertRefused("import_unobserved", cache.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))))
            // A write that performs the import itself was built without the file too.
            assertRefused("import_unobserved", cache.writeThread("Host", "unread", listOf(row(5))))
            assertEquals(rows, cache.readThread("Host", "unread"))

            // After a restart every restore reads the imported content, so its merged saves are accepted.
            val restarted = open()
            assertEquals(rows, restarted.readThread("Host", "thread"))
            restarted.writeThread("Host", "thread", rows + row(3)).getOrThrow()
            restarted.writeAuthoritativeThread("Host", "thread", saved("newer", listOf(4))).getOrThrow()
            assertEquals(rows + row(3), open().readThread("Host", "thread"))
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
                for (authoritative in listOf(false, true)) {
                    val family = if (authoritative) "authoritative" else "legacy"
                    val thread = "cancelled-$family-$writeImport"
                    files.writeThread("Host", thread, listOf(row(1))).getOrThrow()
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

                    suspend fun read() =
                        if (authoritative) {
                            cache.readAuthoritativeThread("Host", thread)
                        } else {
                            cache.readThread("Host", thread)
                        }

                    suspend fun replace() =
                        if (authoritative) {
                            cache.writeAuthoritativeThread("Host", thread, saved("newer", listOf(4)))
                        } else {
                            cache.writeThread("Host", thread, listOf(row(2)))
                        }

                    // Probe both a retry after an empty restore and a write that performs the first import.
                    if (!writeImport) assertEquals(if (authoritative) null else emptyList<ThreadItem>(), read())
                    failing.set(false)
                    val scheduler = TestCoroutineScheduler()
                    // This owner is cancelled explicitly: a cleanup failure must not make runBlocking await the reader.
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
                        // Let Room commit while the caller's dispatcher remains paused, then cancel before result delivery.
                        withTimeout(10_000) {
                            while (requireNotNull(database).imports().settled("Host", thread, family).isEmpty()) delay(1)
                        }
                    } finally {
                        release.complete(Unit)
                        readerScope.cancel()
                        cancelAndDrain(reader, scheduler)
                    }
                    assertTrue(reader.isCancelled)
                    assertRefused("import_unobserved", replace())
                    assertEquals(if (authoritative) saved().retained() else listOf(row(1)), read())
                    val restarted = open()
                    assertEquals(listOf(row(1)), restarted.readThread("Host", thread))
                    assertEquals(saved().retained(), restarted.readAuthoritativeThread("Host", thread))
                }
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
            val importing = async(Dispatchers.IO) { cache.readThread("Host", "thread") }
            entered.await()
            val removing = async(Dispatchers.IO) { cache.removeConversation("Host", "thread") }
            delay(50)
            assertFalse(removing.isCompleted)
            requireNotNull(hold.getAndSet(null)).complete(Unit)
            assertEquals(rows, importing.await())
            removing.await().getOrThrow()
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "thread"))
            assertNull(cache.readAuthoritativeThread("Host", "thread"))

            // A held save is released after deletion was requested; deletion still wins.
            cache.writeThread("Host", "saved", rows).getOrThrow()
            val heldSave = CompletableDeferred<Unit>()
            val savingEntered = CompletableDeferred<Unit>()
            val saving =
                open {
                    if (!heldSave.isCompleted) {
                        savingEntered.complete(Unit)
                        heldSave.await()
                    }
                }
            saving.readThread("Host", "saved")
            val write = async(Dispatchers.IO) { saving.writeThread("Host", "saved", rows + row(3)) }
            savingEntered.await()
            val remove = async(Dispatchers.IO) { saving.removeConversation("Host", "saved") }
            delay(50)
            heldSave.complete(Unit)
            write.await().getOrThrow()
            remove.await().getOrThrow()
            assertEquals(emptyList<ThreadItem>(), saving.readThread("Host", "saved"))

            // A source cleanup that failed leaves the files; restart still cannot resurrect them.
            sources.zip(bytes).forEach { (file, content) -> file.apply { parentFile?.mkdirs() }.writeBytes(content) }
            val restarted = open()
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("Host", "thread"))
            assertNull(restarted.readAuthoritativeThread("Host", "thread"))
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("Host", "saved"))
            assertEquals(rows, restarted.readThread("host", "thread"))
            assertEquals(saved().retained(), restarted.readAuthoritativeThread("host", "thread"))
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
            assertEquals(rows, cache.readThread("Host", "a"))
            holding.set(true)
            val save = async(Dispatchers.IO) { cache.writeThread("Host", "a", rows + row(3)) }
            entered.await()
            holding.set(false)
            val forget = async(Dispatchers.IO) { cache.removeHost("Host") }
            delay(50)
            assertFalse(forget.isCompleted)
            release.complete(Unit)
            save.await().getOrThrow()
            forget.await().getOrThrow()
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "a"))

            // The forgotten host's own list collector writes after the removal; its closing repository's final save is still refused.
            cache.writeConversations("Host", listOf(conversation("a"))).getOrThrow()
            assertRefused("host_retired", cache.writeThread("Host", "a", rows))
            assertTrue(cache.writeAuthoritativeThread("Host", "a", saved()).isFailure)
            assertRefused("host_retired", cache.resetAuthoritativeThread("Host", "a", saved()))
            assertEquals(emptyList<ThreadItem>(), cache.readThread("Host", "a"))

            // Recursive delete failed to remove sources: restart neither imports them nor touches the other host.
            sources.zip(bytes).forEach { (file, content) -> file.apply { parentFile?.mkdirs() }.writeBytes(content) }
            val restarted = open()
            assertEquals(emptyList<ThreadItem>(), restarted.readThread("Host", "b"))
            assertNull(restarted.readAuthoritativeThread("Host", "b"))
            assertEquals(rows, restarted.readThread("host", "a"))
            assertEquals(saved().retained(), restarted.readAuthoritativeThread("host", "a"))

            // After a restart no stale save exists, so a re-paired host's saves are accepted.
            val repaired = open()
            repaired.writeThread("Host", "a", rows).getOrThrow()
            // In-process, forgetting fences saves until the host is paired again; a list write never readmits it.
            repaired.removeHost("Host").getOrThrow()
            repaired.writeConversations("Host", listOf(conversation("a"))).getOrThrow()
            assertRefused("host_retired", repaired.writeThread("Host", "a", rows))
            repaired.admitHost("Host")
            repaired.writeThread("Host", "a", listOf(row(4))).getOrThrow()
            assertEquals(listOf(row(4)), open().readThread("Host", "a"))
        }

    @Test fun certificationInvariant_importAndPagesKeepSavedCheckpoint() =
        runBlocking {
            val items = (1..450).toList()
            val expected = saved(ids = items).retained()
            files.writeAuthoritativeThread("Host", "thread", saved(ids = items)).getOrThrow()
            val rows = listOf(row(4), row(5), row(6))
            files.writeThread("Host", "thread", rows).getOrThrow()
            files.writeHistoryPosition("Host", "thread", HistoryPosition("saved", true, unsignedCoverage(rows))).getOrThrow()

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

            val legacyPage = requireNotNull(open().newestLegacyPage("Host", "thread", 2))
            assertEquals(rows.takeLast(2), legacyPage.rows)
            assertTrue(requireNotNull(legacyPage.evidence).unsignedIncomplete)
            val oldest = requireNotNull(open().beforeLegacyPage("Host", "thread", requireNotNull(legacyPage.continuation), 2))
            assertEquals(rows.take(1), oldest.rows)
            assertNull(oldest.continuation)
            assertTrue(requireNotNull(oldest.evidence).unsignedIncomplete)
            assertEquals(json(files.readHistoryPosition("Host", "thread")), json(open().readHistoryPosition("Host", "thread")))
        }

    @Test fun boundedInvariant_fragmentedFixtureMigratesAndPagesWithinBounds() =
        runBlocking {
            val (coverage, rows) = fragmentedHistoryFixture((0 until 18000).toList())
            files.writeThread("Host", "thread", rows).getOrThrow()
            files.writeHistoryPosition("Host", "thread", HistoryPosition("server-cursor", false, coverage)).getOrThrow()
            val expected = json(files.readHistoryPosition("Host", "thread"))

            val cache = open()
            var page = requireNotNull(cache.newestLegacyPage("Host", "thread", 200))
            assertEquals(rows.takeLast(200), page.rows)
            assertEquals(200, cache.legacyReadCounts.rowDecodes)
            assertEquals(page.rows.flatMap { it.historyKeys() }.toSet(), requireNotNull(page.evidence).unsignedRowOrder.keys)
            var visited = page.rows
            var pages = 1
            while (page.continuation != null) {
                page = requireNotNull(cache.beforeLegacyPage("Host", "thread", requireNotNull(page.continuation), 200))
                assertTrue(cache.legacyReadCounts.rowDecodes <= 200)
                visited = page.rows + visited
                pages++
            }
            assertEquals(90, pages)
            assertEquals(rows, visited)
            val restarted = open()
            assertEquals(expected, json(restarted.readHistoryPosition("Host", "thread")))
            assertEquals(rows, restarted.readThread("Host", "thread"))
        }

    @Test fun repositoryInvariant_restoreSaveAndDeleteUseRoomStorage() =
        runBlocking {
            val saved = listOf(row(1), row(2))
            files.writeThread("server-a", "conv-1", saved).getOrThrow()
            val live = MutableStateFlow<List<ThreadItem>>(emptyList())
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live

                    override suspend fun delete(conversationId: String) = Unit
                }

            fun repository(cache: ConversationCache) =
                CachingConversationRepository(delegate, cache, "server-a", processingDispatcher = Dispatchers.Default)
            assertEquals(saved, repository(open()).observeMessages("conv-1").first())

            val cache = open()
            val emissions = mutableListOf<List<ThreadItem>>()
            val collector =
                launch(Dispatchers.Default) {
                    repository(cache).observeMessages("conv-1").collect {
                        synchronized(emissions) {
                            emissions +=
                                it
                        }
                    }
                }
            withTimeout(10_000) { while (synchronized(emissions) { emissions.isEmpty() }) delay(10) }
            live.value = listOf(row(3))
            withTimeout(10_000) { while (row(3) !in cache.readThread("server-a", "conv-1")) delay(20) }
            collector.cancelAndJoin()
            val merged = synchronized(emissions) { emissions.last() }
            assertTrue(merged.containsAll(saved + row(3)))
            assertEquals(merged, open().readThread("server-a", "conv-1"))
            assertEquals(saved, files.readThread("server-a", "conv-1"))

            repository(open()).delete("conv-1")
            assertEquals(emptyList<ThreadItem>(), open().readThread("server-a", "conv-1"))
            assertFalse(legacyFile("server-a", "conv-1").exists())
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
