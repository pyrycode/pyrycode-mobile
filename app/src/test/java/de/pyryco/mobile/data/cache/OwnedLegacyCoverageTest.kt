package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.UnsignedHistoryGap
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OwnedLegacyCoverageTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null
    private val queries = Collections.synchronizedList(ArrayList<String>())

    private fun open(
        name: String = "cache",
        beforeCommit: suspend () -> Unit = {},
    ): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "$name.db").absolutePath,
                ).setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
                .build()
        database = db
        return MigratingConversationCache(File(temporary.root, name), db, Dispatchers.IO, beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private fun row(
        id: Int,
        text: String = "text-$id",
    ) = ThreadItem.MessageItem(
        Message("id-$id", "s", Role.User, text, Instant.fromEpochSeconds(id.toLong()), false),
    )

    private fun coverage(rows: List<ThreadItem>): HistoryCoverage {
        val ids = rows.indices.map { if (it == rows.lastIndex) ULong.MAX_VALUE else (it + 1).toULong() * 2u }
        val order = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to id }
        return HistoryCoverage(
            unsignedSpans = ids.map { UnsignedHistorySpan(it, it) },
            unsignedGaps = ids.zipWithNext { a, b -> UnsignedHistoryGap(a, b) },
            unsignedRowOrder = order,
            unsignedRowEntries = order.mapValues { setOf(it.value) },
            proofs = historyRowProofs(rows),
            unsignedUnknown = true,
        )
    }

    private class Source :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val snapshots = MutableStateFlow(ThreadSnapshot(emptyList()))

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = snapshots
    }

    @Test fun anchorInvariant_ownedUpdatesPreserveUnrelatedAnchors() =
        runTest {
            val cache = open()
            val rows = (1..4).map { row(it) }
            val initial =
                coverage(rows).copy(
                    unsignedCursors = mapOf(2uL to "old", 4uL to "other"),
                    unsignedWalks =
                        mapOf(
                            0uL to "unknown",
                            2uL to "gap",
                        ),
                )
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", HistoryPosition("saved", false, initial)).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            val next =
                initial.copy(
                    unsignedCursors = mapOf(4uL to "other", ULong.MAX_VALUE to "maximum"),
                    unsignedWalks =
                        mapOf(
                            0uL to "advanced",
                            2uL to "",
                        ),
                )
            repository.writeHistoryPosition("c", HistoryPosition("next", false, next))
            val saved = requireNotNull(open().readHistoryPosition("Host", "c"))
            assertEquals(next.unsignedCursors, saved.coverage?.unsignedCursors)
            assertEquals(next.unsignedWalks, saved.coverage?.unsignedWalks)
            assertEquals(next.unsignedGaps, saved.coverage?.unsignedGaps)
            assertEquals(next.proofs, saved.coverage?.proofs)
        }

    @Test fun certificationInvariant_changedProducersCannotRegainStaleClaims() =
        runTest {
            val cache = open()
            val rows = (1..4).map { row(it) }
            val keys = rows.map { it.historyKeys().first() }
            val initial = coverage(rows).copy(unsignedRowEntries = coverage(rows).unsignedRowEntries + (keys[2] to setOf(4uL, 6uL)))
            val position = HistoryPosition("saved", false, initial)
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", position).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            source.snapshots.value = ThreadSnapshot(listOf(row(2, "changed")))
            repeat(2) { repository.writeHistoryPosition("c", position) }
            val fresh = open()
            assertEquals(listOf(rows[0], row(2, "changed"), rows[2], rows[3]), fresh.readThread("Host", "c"))
            val committed = requireNotNull(fresh.readHistoryPosition("Host", "c")).coverage
            assertEquals(setOf(keys[0], keys[3]), requireNotNull(committed).proofs.keys)
            assertFalse(committed.unsignedSpans.any { 4uL in it.first..it.last })
            assertTrue(committed.unsignedSpans.any { ULong.MAX_VALUE in it.first..it.last })
        }

    @Test fun failureInvariant_rowsAndEvidenceAdvanceSeparately() =
        runTest {
            val failAt = AtomicInteger()
            val cache =
                open(beforeCommit = {
                    if (failAt.get() > 0 && failAt.decrementAndGet() == 0) throw IOException("controlled")
                })
            val rows = (1..3).map { row(it) }
            val initial = HistoryPosition("saved", false, coverage(rows))
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            val changed = rows.dropLast(1) + row(3, "received-new")
            source.snapshots.value = ThreadSnapshot(changed)
            val next = initial.copy(cursor = "new", coverage = initial.coverage?.copy(proofs = historyRowProofs(changed)))
            failAt.set(1)
            repository.writeHistoryPosition("c", next)
            assertEquals(rows, cache.readThread("Host", "c"))
            assertEquals(initial, cache.readHistoryPosition("Host", "c"))
            failAt.set(2)
            repository.writeHistoryPosition("c", next)
            assertEquals(changed, cache.readThread("Host", "c"))
            val conservative = requireNotNull(cache.readHistoryPosition("Host", "c"))
            assertFalse(requireNotNull(conservative.coverage).proofs.containsKey(rows.last().historyKeys().first()))
            assertEquals("saved", conservative.cursor)
            repository.writeHistoryPosition("c", next)
            val fresh = open()
            assertEquals(changed, fresh.readThread("Host", "c"))
            assertEquals(next.coverage?.proofs, fresh.readHistoryPosition("Host", "c")?.coverage?.proofs)
            assertEquals("new", fresh.readHistoryPosition("Host", "c")?.cursor)
        }

    @Test fun fragmentInvariant_ownedUpdatesKeepExactUnsignedBindings() =
        runTest {
            val cache = open()
            val held = listOf(ThreadItem.MessageItem(Message("turn", "s", Role.Assistant, "ab", Instant.fromEpochSeconds(1), false)))
            val entries =
                listOf("a", "b").mapIndexed { index, text ->
                    HistoryEntry(
                        type = "assistant_delta",
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","turn_id":"turn","seq":$index,"text":"$text"}"""),
                        timestamp = Instant.fromEpochSeconds(1),
                        unsignedId = ULong.MAX_VALUE - 1u + index.toULong(),
                    )
                }
            val coverage =
                HistoryCoverage()
                    .receivedUnsigned(
                        HistoryPage(entries.reversed(), "old", true),
                    ).boundTo(held)
                    .copy(deltaText = emptyMap())
            val initial = HistoryPosition("saved", false, coverage)
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", held).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            repository.writeHistoryPosition("c", initial.copy(cursor = "new"))
            val saved = requireNotNull(cache.readHistoryPosition("Host", "c")).coverage
            assertEquals(coverage.legacyKeys, saved?.legacyKeys)
            assertEquals(coverage.legacyOffsets, saved?.legacyOffsets)
            assertEquals(coverage.deltaLengths, saved?.deltaLengths)
            assertEquals(coverage.deltaHashes, saved?.deltaHashes)
            source.snapshots.value = ThreadSnapshot(listOf(held.single().copy(message = held.single().message.copy(content = "a"))))
            repeat(2) { repository.writeHistoryPosition("c", initial) }
            val fresh = open()
            assertEquals("a", (fresh.readThread("Host", "c").single() as ThreadItem.MessageItem).message.content)
            assertTrue(requireNotNull(fresh.readHistoryPosition("Host", "c")?.coverage).proofs.isEmpty())
        }

    @Test fun recoveryInvariant_receivedProofClearsOnlyItsOwnLoss() =
        runTest {
            val cache = open()
            val rows = listOf(row(1), row(2))
            val order = rows.mapIndexed { index, row -> row.historyKeys().first() to (index + 1).toULong() }.toMap()
            val initial =
                HistoryPosition(
                    "saved",
                    true,
                    HistoryCoverage(
                        unsignedSpans = listOf(UnsignedHistorySpan(1u, 2u)),
                        unsignedRowOrder = order,
                        unsignedRowEntries = order.mapValues { setOf(it.value) },
                        proofs = historyRowProofs(rows),
                    ),
                )
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            val changed = listOf(row(1, "new-one"), row(2, "new-two"))
            source.snapshots.value = ThreadSnapshot(changed)
            repository.writeHistoryPosition("c", initial)
            assertFalse(requireNotNull(cache.readHistoryPosition("Host", "c")).atStart)
            val oneProof =
                initial.copy(
                    coverage =
                        initial.coverage?.copy(
                            proofs =
                                requireNotNull(initial.coverage).proofs + historyRowProofs(changed.take(1)),
                        ),
                )
            repository.writeHistoryPosition("c", oneProof)
            assertFalse(requireNotNull(cache.readHistoryPosition("Host", "c")).atStart)
            val bothProofs = initial.copy(coverage = initial.coverage?.copy(proofs = historyRowProofs(changed)))
            repository.writeHistoryPosition("c", bothProofs)
            val saved = requireNotNull(open().readHistoryPosition("Host", "c"))
            assertEquals(bothProofs, saved)
        }

    @Test fun retentionInvariant_coverageCannotRecertifyDiscardedRows() =
        runTest {
            val cache = open()
            val rows = (1..MAX_CACHED_THREAD_ROWS).map { row(it) }
            val key = rows.first().historyKeys().first()
            val initial =
                HistoryPosition(
                    "saved",
                    false,
                    HistoryCoverage(
                        unsignedSpans = listOf(UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                        unsignedRowOrder = mapOf(key to ULong.MAX_VALUE),
                        unsignedRowEntries = mapOf(key to setOf(ULong.MAX_VALUE)),
                        proofs = historyRowProofs(listOf(rows.first())),
                    ),
                )
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            source.snapshots.value = ThreadSnapshot(listOf(row(MAX_CACHED_THREAD_ROWS + 1)))
            repository.writeHistoryPosition("c", initial)
            val fresh = open()
            assertEquals(rows.drop(1) + row(MAX_CACHED_THREAD_ROWS + 1), fresh.readThread("Host", "c"))
            val saved = requireNotNull(fresh.readHistoryPosition("Host", "c"))
            assertTrue(requireNotNull(saved.coverage).proofs.isEmpty())
            assertTrue(requireNotNull(saved.coverage).unsignedSpans.isEmpty())
            assertEquals("", saved.cursor)
            assertFalse(saved.atStart)
        }

    @Test fun boundedInvariant_coverageSaveDoesNotScaleWithSavedHistory() =
        runTest {
            val counts =
                listOf(20, 18000).map { size ->
                    val cache = open("cache-$size")
                    val rows = (1..size).map { row(it) }
                    val saved = HistoryPosition("saved", false, coverage(rows))
                    cache.readThread("Host", "c")
                    cache.writeThread("Host", "c", rows).getOrThrow()
                    cache.writeHistoryPosition("Host", "c", saved).getOrThrow()
                    val source = Source()
                    val repository = CachingConversationRepository(source, cache, "Host")
                    assertEquals(rows, repository.observeThreadSnapshot("c").first().rows)
                    repository.readHistoryPosition("c")
                    val newest = row(size, "changed")
                    source.snapshots.value = ThreadSnapshot(listOf(newest))
                    val next =
                        saved.copy(
                            cursor = "advanced",
                            coverage =
                                requireNotNull(saved.coverage).copy(
                                    proofs = requireNotNull(saved.coverage).proofs + historyRowProofs(listOf(newest)),
                                    unsignedWalks =
                                        mapOf(
                                            0uL to "advanced-walk",
                                        ),
                                ),
                        )
                    queries.clear()
                    repository.writeHistoryPosition("c", next)
                    val statements = synchronized(queries) { queries.toList() }
                    val storageStatements = statements.filter { it.contains("legacy_", true) }
                    assertFalse("coverage save replaced rows", statements.any { it.startsWith("DELETE FROM legacy_rows", true) })
                    assertFalse(
                        "coverage save scanned rows",
                        statements.any {
                            it.startsWith("SELECT * FROM legacy_rows", true) &&
                                !it.contains("identity=")
                        },
                    )
                    assertFalse(
                        "coverage save scanned evidence",
                        statements.any {
                            it.contains("FROM legacy_evidence WHERE host=? AND conversation=? ORDER BY")
                        },
                    )
                    assertFalse(
                        "coverage save broadly replaced evidence",
                        statements.any {
                            it ==
                                "DELETE FROM legacy_evidence WHERE host=? AND (? IS NULL OR conversation=?)"
                        },
                    )
                    val mutation = cache.legacyMutationCounts
                    assertEquals(1, mutation.rowWrites)
                    assertEquals(2, mutation.rowSerializations)
                    assertEquals(2, mutation.rowReads)
                    assertEquals(2, mutation.rowDecodes)
                    println(
                        "owned_coverage saved_rows=$size counts=$mutation storage_sql_statements=${storageStatements.size} total_sql_statements=${statements.size}",
                    )
                    val fresh = open("cache-$size")
                    assertEquals(rows.dropLast(1) + newest, fresh.readThread("Host", "c"))
                    assertEquals(next.coverage?.proofs, fresh.readHistoryPosition("Host", "c")?.coverage?.proofs)
                    assertEquals(next.cursor, fresh.readHistoryPosition("Host", "c")?.cursor)
                    mutation to storageStatements.size
                }
            assertEquals(counts.first(), counts.last())
        }

    @Test fun ownershipInvariant_partialCoveragePreservesUnloadedRowsAndEvidence() =
        runTest {
            val cache = open()
            val rows = (1..18000).map { row(it) }
            val saved =
                HistoryPosition(
                    "saved",
                    false,
                    coverage(rows).copy(
                        unsignedCursors =
                            mapOf(
                                2uL to "unloaded",
                                ULong.MAX_VALUE to "loaded",
                            ),
                    ),
                )
            for (host in listOf("Host", "host")) {
                cache.readThread(host, "c")
                cache.writeThread(host, "c", rows).getOrThrow()
                cache.writeHistoryPosition(host, "c", saved).getOrThrow()
            }
            val partial =
                object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                    override suspend fun readThread(
                        serverId: String,
                        conversationId: String,
                    ) = cache.readThread(serverId, conversationId).takeLast(200)
                }
            val source = Source()
            val repository = CachingConversationRepository(source, partial, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            val cutoff = 17800 * LEGACY_ORDINAL_STEP
            val before = requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal in 1L..cutoff }
            val token = requireNotNull(cache.newestLegacyPage("Host", "c", 200)?.continuation)
            val changed = rows.takeLast(199).dropLast(1) + row(18000, "changed")
            source.snapshots.value = ThreadSnapshot(changed, suppressedUserMessageIds = setOf("id-17801"))
            val ownedKeys = rows.takeLast(200).flatMap { it.historyKeys() }.toSet()
            val full = requireNotNull(saved.coverage)
            val received =
                full.copy(
                    unsignedSpans = full.unsignedSpans.takeLast(200),
                    unsignedGaps = full.unsignedGaps.takeLast(199),
                    unsignedRowOrder = full.unsignedRowOrder.filterKeys { it in ownedKeys },
                    unsignedRowEntries = full.unsignedRowEntries.filterKeys { it in ownedKeys },
                    proofs = full.proofs.filterKeys { it in ownedKeys },
                    unsignedCursors = mapOf(ULong.MAX_VALUE to "loaded"),
                )
            repository.writeHistoryPosition("c", saved.copy(coverage = received))
            assertEquals(before, requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal in 1L..cutoff })
            assertNull(cache.beforeLegacyPage("Host", "c", token, 200))
            val traversed = ArrayList<ThreadItem>()
            var page = cache.newestLegacyPage("Host", "c", 200)
            while (page != null) {
                traversed.addAll(0, page.rows)
                page = page.continuation?.let { cache.beforeLegacyPage("Host", "c", it, 200) }
            }
            assertEquals(rows.take(17800) + changed, traversed)
            val fresh = open()
            assertEquals(rows.take(17800) + changed, fresh.readThread("Host", "c"))
            assertEquals(rows, fresh.readThread("host", "c"))
            assertEquals(saved, fresh.readHistoryPosition("host", "c"))
            val committed = requireNotNull(fresh.readHistoryPosition("Host", "c")).coverage
            assertFalse(requireNotNull(committed).proofs.containsKey(rows.last().historyKeys().first()))
            assertTrue(committed.proofs.keys.containsAll(rows.take(17800).flatMap { it.historyKeys() }))
            assertEquals("unloaded", committed.unsignedCursors[2uL])
            CachingConversationRepository(source, fresh, "Host").writeHistoryPosition("c", null)
            assertEquals(rows.take(17800) + changed, fresh.readThread("Host", "c"))
            assertNull(fresh.readHistoryPosition("Host", "c"))
        }
}
