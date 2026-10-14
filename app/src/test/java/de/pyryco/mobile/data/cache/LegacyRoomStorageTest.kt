package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.AssistantSegment
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
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnsignedHistoryGap
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyIdentity
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import de.pyryco.mobile.di.openConversationCacheDatabase
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyRoomStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null
    private val path by lazy { temporary.newFolder().resolve("legacy.db").absolutePath }

    private fun open(): LegacyRoomStorage {
        database?.close()
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ConversationCacheDatabase::class.java, path).build()
        database = db
        return LegacyRoomStorage(db, Dispatchers.IO)
    }

    @After fun close() {
        database?.close()
    }

    private fun row(
        index: Int,
        timestamp: Long = 0,
    ) = ThreadItem.MessageItem(Message("id-$index", "s", Role.User, "text-$index", Instant.fromEpochSeconds(timestamp), false))

    private suspend fun seed(
        store: LegacyRoomStorage,
        rows: List<ThreadItem>,
        position: HistoryPosition? = null,
    ) {
        store.writeThread("Host", "thread", rows).getOrThrow()
        if (position != null) store.writeHistoryPosition("Host", "thread", position).getOrThrow()
    }

    private fun coverage(
        rows: List<ThreadItem>,
        ids: List<ULong>,
    ): HistoryCoverage =
        HistoryCoverage(
            unsignedSpans = ids.sorted().map { UnsignedHistorySpan(it, it) },
            unsignedGaps = ids.sorted().zipWithNext { a, b -> UnsignedHistoryGap(a, b) },
            unsignedRowOrder = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to id },
            unsignedRowEntries = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to setOf(id) },
            proofs = historyRowProofs(rows),
        ).validated(rows)

    @Test fun roundTripInvariant_allKindsAndFieldsSurviveRestart() =
        runTest {
            val time = Instant.fromEpochSeconds(1)
            val rows =
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
                        row(
                            3,
                        ).message.copy(
                            role = Role.Assistant,
                            content = "prefixsuffix",
                            segment = AssistantSegment("turn", listOf(SegmentDelta(2, 6), SegmentDelta(5, 6))),
                        ),
                    ),
                    ThreadItem.SessionBoundary("old", "new", BoundaryReason.Clear, time, "/workspace"),
                    ThreadItem.SessionBoundary("old", "new", BoundaryReason.Clear, Instant.fromEpochSeconds(2), null),
                    ThreadItem.Banner(BannerLevel.Warning, "banner", true, time),
                    ThreadItem.CompactionBoundary(100, 50, true, time, true),
                    ThreadItem.ModelRefusal("original", "fallback", "refusal", true, time),
                    ThreadItem.ModelRefusal("original", null, "refusal", false, time),
                    ThreadItem.StoppedTurn("stopped", "reason", "category", time),
                )
            seed(open(), rows, HistoryPosition("opaque", false))
            val restored = open()
            assertEquals(rows, restored.readThread("Host", "thread"))
            assertEquals(HistoryPosition("opaque", false), restored.readHistoryPosition("Host", "thread"))
            assertEquals(rows.takeLast(3), restored.newestPage("Host", "thread", 3)?.rows)
            restored.writeHistoryPosition("Host", "thread", null).getOrThrow()
            assertNull(open().readHistoryPosition("Host", "thread"))
            assertEquals(rows, open().readThread("Host", "thread"))
        }

    @Test fun heldOrderInvariant_backwardTraversalVisitsEachRowOnce() =
        runTest {
            val rows = (0..420).reversed().map { row(it, if (it % 2 == 0) 100 else -100) }
            seed(open(), rows)
            var store = open()
            var page = requireNotNull(store.newestPage("Host", "thread", 200))
            var restored = page.rows
            while (page.continuation != null) {
                val token = requireNotNull(page.continuation)
                store = open()
                page = requireNotNull(store.beforePage("Host", "thread", token, 200))
                restored = page.rows + restored
            }
            assertEquals(rows, restored)
            val oldest =
                store.newestPage("Host", "thread", 1)?.let {
                    var current = it
                    while (current.continuation != null) {
                        current = requireNotNull(store.beforePage("Host", "thread", requireNotNull(current.continuation), 1))
                    }
                    current.rows
                }
            assertEquals(listOf(rows.first()), oldest)
            assertNull(store.newestPage("Host", "thread", 0))
            assertEquals(0, store.newestPage("Host", "absent", 200)?.rows?.size)
        }

    @Test fun ownershipInvariant_tokensAndRemovalStayScoped() =
        runTest {
            val store = open()
            for (host in listOf("Host", "host")) {
                for (thread in listOf("thread", "other")) {
                    store.writeThread(host, thread, listOf(row(1), row(2))).getOrThrow()
                    store.writeHistoryPosition(host, thread, HistoryPosition("secret", true)).getOrThrow()
                }
            }
            val token = requireNotNull(store.newestPage("Host", "thread", 1)?.continuation)
            assertNull(store.beforePage("host", "thread", token, 1))
            assertNull(store.beforePage("Host", "other", token, 1))
            store.removeConversation("Host", "thread").getOrThrow()
            assertTrue(open().readThread("Host", "thread").isEmpty())
            assertNull(open().readHistoryPosition("Host", "thread"))
            assertEquals(2, open().readThread("Host", "other").size)
            assertEquals(2, open().readThread("host", "thread").size)
            open().removeHost("Host").getOrThrow()
            assertTrue(open().readThread("Host", "other").isEmpty())
            assertEquals(2, open().readThread("host", "other").size)
            assertEquals(2, open().readThread("host", "thread").size)
        }

    @Test fun replacementInvariant_oldTokensCannotReadNewSnapshot() =
        runTest {
            val store = open()
            seed(store, listOf(row(1), row(2)))
            val token = requireNotNull(store.newestPage("Host", "thread", 1)?.continuation)
            seed(store, listOf(row(3)))
            assertNull(open().beforePage("Host", "thread", token, 200))
            assertEquals(listOf(row(3)), open().readThread("Host", "thread"))
            seed(open(), emptyList())
            assertTrue(requireNotNull(open().newestPage("Host", "thread", 200)).rows.isEmpty())
        }

    @Test fun unsignedAndFragmentInvariant_exactEvidenceSurvivesRestart() =
        runTest {
            val rows = listOf(row(1), row(2), row(3))
            val ids = listOf(1uL, Long.MAX_VALUE.toULong() + 1u, ULong.MAX_VALUE)
            val base =
                coverage(rows, ids).copy(
                    unsignedCursors = ids.associateWith { "cursor-$it" },
                    unsignedWalks = mapOf(0uL to "unknown-walk", ULong.MAX_VALUE to "max-walk"),
                    unsignedUnknown = true,
                    newestCursor = "newest",
                )
            seed(open(), rows, HistoryPosition("saved", false, base))
            val store = open()
            val actual = requireNotNull(store.readHistoryPosition("Host", "thread")?.coverage)
            assertEquals(MobileJson.encodeToJsonElement(base), MobileJson.encodeToJsonElement(actual))
            val page = requireNotNull(store.newestPage("Host", "thread", 1))
            assertEquals(rows.takeLast(1), page.rows)
            assertEquals(ULong.MAX_VALUE, page.evidence?.unsignedRowOrder?.get(rows.last().historyKeys().first()))
            assertTrue(requireNotNull(page.evidence).unsignedIncomplete)
            store.readHistoryPosition("Host", "thread")
            assertEquals(0, store.lastReadCounts.rowDecodes)

            val legacy = row(9).copy(message = row(9).message.copy(role = Role.Assistant, content = "abXXcd"))
            val alias = legacy.historyKeys().first()
            val keys = listOf(historyIdentity(listOf("delta", "id-9", 0)), historyIdentity(listOf("delta", "id-9", 2)))

            fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()
            val fragments =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(3u, 3u)),
                    unsignedGaps = listOf(UnsignedHistoryGap(1u, 3u)),
                    unsignedRowOrder = keys.zip(listOf(1uL, 3uL)).toMap(),
                    unsignedRowEntries = keys.zip(listOf(setOf(1uL), setOf(3uL))).toMap(),
                    proofs = keys.associateWith { cachedThreadRowProof(legacy) },
                    legacyKeys = keys.associateWith { alias },
                    legacyOffsets = keys.zip(listOf(0, 4)).toMap(),
                    deltaLengths = keys.associateWith { 2 },
                    deltaHashes = keys.zip(listOf(hash("ab"), hash("cd"))).toMap(),
                ).validated(listOf(legacy))
            seed(store, listOf(legacy), HistoryPosition("fragments", false, fragments))
            val saved = requireNotNull(open().readHistoryPosition("Host", "thread")?.coverage)
            assertEquals(MobileJson.encodeToJsonElement(fragments), MobileJson.encodeToJsonElement(saved))
            assertEquals(
                MobileJson.encodeToJsonElement(fragments),
                open().newestPage("Host", "thread", 1)?.evidence?.let {
                    kotlinx.serialization.json.JsonObject(
                        it.encoded.filterKeys { key ->
                            key != "savedHighWater" &&
                                key != "savedUnknownEdge"
                        },
                    )
                },
            )
        }

    @Test
    fun committedEvidenceInvariant_changedContentAndTrimInvalidateClaims() =
        runTest(
            timeout = kotlin.time.Duration.parse("3m"),
        ) {
            val original = listOf(row(1), row(2))
            val store = open()
            seed(store, original, HistoryPosition("cursor", true, coverage(original, listOf(1u, 3u))))
            store
                .writeThread(
                    "Host",
                    "thread",
                    listOf(row(1).copy(message = row(1).message.copy(content = "changed")), row(2)),
                ).getOrThrow()
            val saved = requireNotNull(open().readHistoryPosition("Host", "thread")?.coverage)
            assertTrue(saved.unsignedUnknown)
            assertFalse(saved.unsignedRowOrder.containsKey(row(1).historyKeys().first()))
            assertEquals(listOf(UnsignedHistorySpan(3u, 3u)), saved.unsignedSpans)
            seed(open(), original, HistoryPosition("trim-cursor", true))
            open().writeThread("Host", "thread", (0..MAX_CACHED_THREAD_ROWS).map { row(it) }).getOrThrow()
            val after = open()
            assertNull(after.readHistoryPosition("Host", "thread"))
            assertEquals(MAX_CACHED_THREAD_ROWS, after.readThread("Host", "thread").size)
            assertEquals(listOf(row(MAX_CACHED_THREAD_ROWS)), after.newestPage("Host", "thread", 1)?.rows)
            val claims = listOf(row(0), row(1))
            seed(after, claims, HistoryPosition("covered-trim", true, coverage(claims, listOf(1u, 3u))))
            open().writeThread("Host", "thread", (0..MAX_CACHED_THREAD_ROWS).map { row(it) }).getOrThrow()
            val coveredTrim = requireNotNull(open().readHistoryPosition("Host", "thread"))
            assertEquals("", coveredTrim.cursor)
            assertFalse(coveredTrim.atStart)
            assertEquals(listOf(UnsignedHistorySpan(3u, 3u)), coveredTrim.coverage?.unsignedSpans)
            assertFalse(requireNotNull(coveredTrim.coverage).unsignedRowOrder.containsKey(row(0).historyKeys().first()))
        }

    @Test fun committedEvidenceInvariant_invalidHistoryLeavesRowsReadable() =
        runTest {
            val store = open()
            seed(store, listOf(row(1)), HistoryPosition("original", true))
            val header = requireNotNull(database?.legacy()?.header("Host", "thread"))
            database?.legacy()?.deleteHeaders("Host", "thread")
            database?.legacy()?.insertHeader(header.copy(coverageHeader = "malformed"))
            assertNull(open().readHistoryPosition("Host", "thread"))
            assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
            assertNull(open().newestPage("Host", "thread", 1)?.savedCursor)
            assertFalse(requireNotNull(open().newestPage("Host", "thread", 1)).savedAtStart)
            assertEquals("malformed", database?.legacy()?.header("Host", "thread")?.coverageHeader)
        }

    @Test fun atomicityInvariant_failureAndCancellationKeepCommittedSnapshot() =
        runTest {
            val store = open()
            seed(store, listOf(row(1)), HistoryPosition("saved", true))
            assertTrue(store.writeThread("Host", "thread", listOf(row(2), row(2))).isFailure)
            assertTrue(
                store
                    .writeHistoryPosition(
                        "Host",
                        "thread",
                        HistoryPosition("invalid", true, HistoryCoverage(unsignedSpans = listOf(UnsignedHistorySpan(0u, 1u)))),
                    ).isFailure,
            )
            assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
            val failing =
                LegacyRoomStorage(requireNotNull(database), Dispatchers.IO, beforeCommit = { throw java.io.IOException("secret payload") })
            val result = failing.writeThread("Host", "thread", listOf(row(2)))
            assertTrue(result.isFailure)
            assertNull(result.exceptionOrNull()?.cause)
            assertFalse(result.toString().contains("secret payload"))
            assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
            val cancelling =
                LegacyRoomStorage(requireNotNull(database), Dispatchers.IO, beforeCommit = { throw CancellationException("cancel") })
            try {
                cancelling.writeHistoryPosition("Host", "thread", null)
                fail("cancellation must propagate")
            } catch (
                _: CancellationException,
            ) {
            }
            assertEquals(HistoryPosition("saved", true), open().readHistoryPosition("Host", "thread"))
        }

    @Test fun exclusionsInvariant_onlyCacheableUniqueRowsReachStorage() =
        runTest {
            val rows =
                listOf(
                    row(1),
                    row(2).copy(message = row(2).message.copy(isStreaming = true)),
                    row(3).copy(message = row(3).message.copy(toolCall = ToolCall("t", "i", "o", ToolCallStatus.Running))),
                )
            seed(open(), rows)
            assertEquals(listOf(row(1)), open().readThread("Host", "thread"))
        }

    @Test
    fun boundedEvidenceInvariant_fragmentedFixtureDoesNotDecodeOutsideWindow() =
        runTest(
            timeout = kotlin.time.Duration.parse("3m"),
        ) {
            val (smallCoverage, smallRows) = fragmentedHistoryFixture((17800 until 18000).toList())
            seed(open(), smallRows, HistoryPosition("server-cursor", false, smallCoverage))
            var store = open()
            val small = requireNotNull(store.newestPage("Host", "thread", 200))
            val smallCounts = store.lastReadCounts
            val (fullCoverage, fullRows) = fragmentedHistoryFixture((0 until 18000).toList())
            seed(store, fullRows, HistoryPosition("server-cursor", false, fullCoverage))
            store = open()
            var page = requireNotNull(store.newestPage("Host", "thread", 200))
            val largeCounts = store.lastReadCounts
            assertEquals(smallRows, page.rows)
            assertEquals(small.evidence, page.evidence)
            assertEquals(200, largeCounts.rowDecodes)
            assertEquals(200, largeCounts.rowReads)
            assertEquals(1, largeCounts.ordinalReads)
            assertEquals(smallCounts.evidenceReads, largeCounts.evidenceReads)
            assertEquals(smallCounts.evidenceDecodes, largeCounts.evidenceDecodes)
            assertTrue(largeCounts.evidenceReads < 2500)
            var visited = page.rows
            var pages = 1
            while (page.continuation != null) {
                page = requireNotNull(store.beforePage("Host", "thread", requireNotNull(page.continuation), 200))
                assertTrue(store.lastReadCounts.rowDecodes <= 200)
                assertTrue(store.lastReadCounts.evidenceReads < 2500)
                visited = page.rows + visited
                pages++
            }
            assertEquals(90, pages)
            assertEquals(fullRows, visited)
            store.readHistoryPosition("Host", "thread")
            assertEquals(0, store.lastReadCounts.rowReads)
            assertEquals(0, store.lastReadCounts.rowDecodes)
            assertEquals(
                MobileJson.encodeToJsonElement(fullCoverage),
                MobileJson.encodeToJsonElement(requireNotNull(store.readHistoryPosition("Host", "thread")?.coverage)),
            )
            println(
                "legacy_room_probe rows=${largeCounts.rowReads} decodes=${largeCounts.rowDecodes} " +
                    "evidence_reads=${largeCounts.evidenceReads} evidence_decodes=${largeCounts.evidenceDecodes} pages=$pages",
            )
        }

    @Test fun diagnosticsInvariant_resultsAndFactoryExcludeContentOwnersAndCursors() =
        runTest {
            val store = open()
            val previousSink = RelayLog.sink
            val previousEnabled = RelayLog.enabled
            val lines = mutableListOf<String>()
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> lines.add(message) }
            try {
                seed(store, listOf(row(1), row(2)), HistoryPosition("secret-cursor", false))
                val page = requireNotNull(store.newestPage("Host", "thread", 1))
                for (text in listOf(
                    page.toString(),
                    page.evidence.toString(),
                    page.continuation.toString(),
                    database?.legacy()?.header("Host", "thread").toString(),
                )) {
                    for (secret in listOf("Host", "thread", "text-2", "secret-cursor")) assertFalse(text.contains(secret))
                }
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                val db = openConversationCacheDatabase(context)
                try {
                    runBlocking { LegacyRoomStorage(db).writeThread("factory", "thread", listOf(row(1))).getOrThrow() }
                    assertTrue(context.noBackupFilesDir.resolve("conversation-cache.db").isFile)
                    assertFalse(context.getDatabasePath("conversation-cache.db").exists())
                } finally {
                    db.close()
                }
                val failing =
                    LegacyRoomStorage(
                        requireNotNull(database),
                        Dispatchers.IO,
                        beforeCommit = { throw java.io.IOException("text-2 secret-cursor Host") },
                    )
                assertTrue(failing.writeThread("Host", "thread", listOf(row(1))).isFailure)
                database?.close()
                assertTrue(store.readThread("Host", "thread").isEmpty())
                assertTrue(store.writeThread("Host", "thread", listOf(row(1))).isFailure)
                assertTrue(lines.any { it.contains("code=io") })
                for (line in lines) {
                    for (secret in listOf(
                        "Host",
                        "secret-cursor",
                        "text-2",
                        "conversation-cache.db",
                    )) {
                        assertFalse(line.contains(secret))
                    }
                }
            } finally {
                RelayLog.sink = previousSink
                RelayLog.enabled = previousEnabled
            }
        }
}
