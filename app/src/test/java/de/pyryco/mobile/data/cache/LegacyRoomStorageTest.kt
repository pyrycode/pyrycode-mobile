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

    @Test fun boundedEvidenceInvariant_olderCursorsAndWalksDoNotGrowContinuousSpanPages() =
        runTest {
            val rows = (99800..100000).map { row(it) }
            val order = rows.zip(99800..100000).associate { (row, id) -> row.historyKeys().first() to id.toULong() }
            val base =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 100000u)),
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { (_, id) -> setOf(id) },
                    proofs = historyRowProofs(rows),
                    unsignedUnknown = true,
                    unsignedCursors = listOf(1uL, 99801uL, 99900uL, 100000uL).associateWith { "cursor-$it" },
                    unsignedWalks = listOf(0uL, 99801uL).associateWith { "walk-$it" },
                ).validated(rows)
            seed(open(), rows, HistoryPosition("saved", false, base))
            var store = open()
            val small = requireNotNull(store.newestPage("Host", "thread", 200))
            val counts = store.lastReadCounts
            val older = (2..18001).map { it.toULong() }
            store
                .writeHistoryPosition(
                    "Host",
                    "thread",
                    HistoryPosition(
                        "saved",
                        false,
                        base.copy(
                            unsignedCursors = base.unsignedCursors + older.associateWith { "old-cursor-$it" },
                            unsignedWalks = base.unsignedWalks + older.associateWith { "old-walk-$it" },
                        ),
                    ),
                ).getOrThrow()
            store = open()
            val large = requireNotNull(store.newestPage("Host", "thread", 200))
            assertEquals(rows.takeLast(200), large.rows)
            assertEquals(small.evidence, large.evidence)
            assertEquals(counts, store.lastReadCounts)
            assertEquals(200, counts.rowReads)
            assertEquals(200, counts.rowDecodes)
            assertEquals(1, counts.ordinalReads)
            assertTrue(counts.evidenceReads < 620)
            assertTrue(counts.evidenceDecodes < 620)
            assertEquals(base.unsignedCursors, large.evidence?.unsignedCursors)
            assertEquals(base.unsignedWalks, large.evidence?.unsignedWalks)
            println("legacy_room_continuous_probe small=$counts large=${store.lastReadCounts} older_anchors=${older.size * 2}")
        }

    private suspend fun assertInvalidProjection(
        rows: List<ThreadItem>,
        coverage: HistoryCoverage,
        kind: String,
        payload: String,
        reindex: Boolean = false,
    ) {
        seed(open(), rows, HistoryPosition("saved", true, coverage))
        val dao = requireNotNull(database).legacy()
        val original = dao.allEvidence("Host", "thread")
        val cell = original.first { it.kind == kind }
        val replacement =
            cell.copy(payload = payload).let {
                if (reindex) it.copy(low = payload.padStart(20, '0'), high = payload.padStart(20, '0')) else it
            }
        val corrupt = original.map { if (it == cell) replacement else it }
        dao.deleteEvidence("Host", "thread")
        dao.insertEvidence(corrupt)
        val store = open()
        val page = requireNotNull(store.newestPage("Host", "thread", 200))
        assertEquals(kind, rows, page.rows)
        assertNull(kind, page.evidence)
        assertNull(kind, page.savedCursor)
        assertFalse(kind, page.savedAtStart)
        assertEquals(rows.size, store.lastReadCounts.rowDecodes)
        assertEquals(rows, store.readThread("Host", "thread"))
        assertEquals(corrupt, requireNotNull(database).legacy().allEvidence("Host", "thread"))
    }

    @Test fun boundedEvidenceInvariant_overlappingGapKeepsItsOutsideWindowWalkAndNearestCursor() =
        runTest {
            val rows = listOf(row(1), row(2))
            val claims =
                coverage(rows, listOf(100u, 1000u))
                    .copy(
                        unsignedSpans = listOf(UnsignedHistorySpan(1u, 100u), UnsignedHistorySpan(1000u, 2000u)),
                        unsignedGaps = listOf(UnsignedHistoryGap(50u, 1000u)),
                        unsignedCursors = mapOf(1100uL to "gap-cursor", 2200uL to "later"),
                        unsignedWalks = mapOf(50uL to "gap-walk", 10uL to "unrelated"),
                    ).validated(rows)
            seed(open(), rows, HistoryPosition("saved", false, claims))
            val evidence = requireNotNull(open().newestPage("Host", "thread", 1)?.evidence)
            assertEquals(claims.unsignedGaps, evidence.unsignedGaps)
            assertEquals(mapOf(1100uL to "gap-cursor"), evidence.unsignedCursors)
            assertEquals(mapOf(50uL to "gap-walk"), evidence.unsignedWalks)
        }

    @Test fun boundedEvidenceInvariant_sharedGapBoundaryKeepsBothSides() = runTest { assertSharedGapBoundary(0u) }

    @Test fun boundedEvidenceInvariant_olderEvidenceDoesNotGrowSharedBoundaryPages() = runTest { assertSharedGapBoundary(40000u) }

    @Test fun boundedEvidenceInvariant_sharedGapBoundaryPreservesUnsignedExtremes() =
        runTest {
            assertSharedGapBoundary(
                ULong.MAX_VALUE - 5u,
            )
        }

    private suspend fun assertSharedGapBoundary(shift: ULong) {
        val rows = listOf(row(1), row(2), row(3))
        val ids = listOf(1uL, 3uL, 5uL).map { it + shift }
        val claims =
            coverage(rows, ids)
                .copy(
                    unsignedCursors = ids.associateWith { "cursor-$it" },
                    unsignedWalks = ids.dropLast(1).associateWith { "walk-$it" },
                ).validated(rows)
        seed(open(), rows, HistoryPosition("saved", false, claims))

        suspend fun traverse(expected: HistoryCoverage): LegacyRoomStorage.ReadCounts {
            var token: LegacyThreadPage.Continuation? = null
            var middleCounts: LegacyRoomStorage.ReadCounts? = null
            var restored = emptyList<ThreadItem>()
            for (index in rows.indices.reversed()) {
                val store = open()
                val page =
                    requireNotNull(
                        token?.let { store.beforePage("Host", "thread", it, 1) }
                            ?: store.newestPage("Host", "thread", 1),
                    )
                val counts = store.lastReadCounts
                val evidence = requireNotNull(page.evidence)
                val gaps = expected.unsignedGaps.filter { ids[index] in it.anchor..it.edge }
                assertEquals(listOf(rows[index]), page.rows)
                assertEquals(gaps, evidence.unsignedGaps)
                assertEquals(gaps.associate { it.anchor to expected.unsignedWalks.getValue(it.anchor) }, evidence.unsignedWalks)
                assertEquals(
                    (listOf(ids[index]) + gaps.map { it.edge }).associateWith { expected.unsignedCursors.getValue(it) },
                    evidence.unsignedCursors,
                )
                assertEquals(mapOf(rows[index].historyKeys().first() to ids[index]), evidence.unsignedRowOrder)
                assertEquals(1, counts.rowReads)
                assertEquals(1, counts.rowDecodes)
                restored = page.rows + restored
                token = page.continuation
                if (index == 0) assertNull(token) else assertTrue(token != null)
                if (index == 1) {
                    middleCounts = counts
                    assertEquals(1, counts.ordinalReads)
                    assertTrue(counts.evidenceReads <= 16)
                    assertTrue(counts.evidenceDecodes <= 14)
                    // The page-edge lookup can mask a missing gap cursor; check this sibling query too.
                    val boundary = ids[index].toString().padStart(20, '0')
                    assertEquals(
                        gaps.map { it.edge }.toSet(),
                        requireNotNull(database)
                            .legacy()
                            .gapCursors("Host", "thread", boundary, boundary)
                            .map { it.key.toULong() }
                            .toSet(),
                    )
                }
            }
            assertEquals(rows, restored)
            return requireNotNull(middleCounts)
        }

        val small = traverse(claims)
        println("legacy_room_boundary_probe baseline=$small")
        if (shift == 0uL) return
        val older = (1..1000).map { (it * 2 + 1).toULong() }
        val grown =
            claims
                .copy(
                    unsignedSpans = older.map { UnsignedHistorySpan(it, it) } + claims.unsignedSpans,
                    unsignedGaps = (older + ids).zipWithNext { anchor, edge -> UnsignedHistoryGap(anchor, edge) },
                    unsignedCursors = claims.unsignedCursors + older.associateWith { "cursor-$it" },
                    unsignedWalks = claims.unsignedWalks + older.associateWith { "walk-$it" },
                ).validated(rows)
        open().writeHistoryPosition("Host", "thread", HistoryPosition("saved", false, grown)).getOrThrow()
        assertEquals(small, traverse(grown))
        println("legacy_room_boundary_probe older_evidence=4000 counts=$small")
    }

    @Test fun committedEvidenceInvariant_parseableInvalidSpansAndClaimsAreIsolated() =
        runTest {
            val rows = listOf(row(1), row(2))
            val claims = coverage(rows, listOf(1u, 3u))
            for ((kind, payload) in listOf(
                "spans" to "{\"first\":0,\"last\":1}",
                "spans" to "{\"first\":1,\"last\":2}",
                "gaps" to "{\"anchor\":0,\"edge\":3}",
                "rowOrder" to "2",
                "rowEntries" to "2",
                "proofs" to "\"wrong-proof\"",
            )) {
                assertInvalidProjection(rows, claims, kind, payload)
            }
            for (kind in listOf("rowOrder", "rowEntries")) assertInvalidProjection(rows, claims, kind, "3", reindex = true)
        }

    @Test fun committedEvidenceInvariant_parseableInvalidFragmentBindingsAreIsolated() =
        runTest {
            val row = row(1).copy(message = row(1).message.copy(role = Role.Assistant, content = "ab"))
            val key = historyIdentity(listOf("delta", "id-1", 0))
            val claims =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u)),
                    unsignedRowOrder = mapOf(key to 1uL),
                    unsignedRowEntries = mapOf(key to setOf(1uL)),
                    proofs = mapOf(key to cachedThreadRowProof(row)),
                    legacyKeys = mapOf(key to row.historyKeys().first()),
                    legacyOffsets = mapOf(key to 0),
                    deltaLengths = mapOf(key to 2),
                    deltaHashes = mapOf(key to MessageDigest.getInstance("SHA-256").digest("ab".toByteArray()).toHexString()),
                ).validated(listOf(row))
            for ((kind, payload) in listOf(
                "legacyKeys" to "\"missing-alias\"",
                "legacyOffsets" to "1",
                "deltaLengths" to "3",
                "deltaHashes" to "\"wrong-hash\"",
            )) {
                assertInvalidProjection(listOf(row), claims, kind, payload)
            }
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
            assertEquals(page.rows.flatMap { it.historyKeys() }.toSet(), requireNotNull(page.evidence).unsignedRowOrder.keys)
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
                assertEquals(page.rows.flatMap { it.historyKeys() }.toSet(), requireNotNull(page.evidence).unsignedRowOrder.keys)
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
