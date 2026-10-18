package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OwnedLegacyWritesTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null
    private val queries = CopyOnWriteArrayList<String>()

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

    @Test fun settlementInvariant_unsettledRowCannotRemoveSavedIdentity() =
        runTest {
            val cache = open()
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", listOf(row(1))).getOrThrow()
            val source =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
                        flow {
                            emit(ThreadSnapshot(emptyList()))
                            emit(ThreadSnapshot(listOf(row(1).let { it.copy(message = it.message.copy(isStreaming = true)) })))
                            emit(ThreadSnapshot(emptyList()))
                            emit(ThreadSnapshot(listOf(row(2))))
                        }
                }
            val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = Dispatchers.Default)
            repository.observeThreadSnapshot("c").collect {}
            assertEquals(listOf(row(1), row(2)), open().readThread("Host", "c"))
        }

    @Test fun boundedInvariant_newestUpdateDoesNotScaleWithSavedHistory() =
        runTest {
            val counts =
                listOf(20, 18000).map { size ->
                    val cache = open("cache-$size")
                    val rows = (1..size).map { row(it) }
                    cache.readThread("Host", "c")
                    cache.writeThread("Host", "c", rows).getOrThrow()
                    val producing = listOf(rows.first(), rows.last())
                    val entryIds = listOf(1uL, ULong.MAX_VALUE)
                    val order = producing.zip(entryIds).associate { (row, id) -> row.historyKeys().first() to id }
                    val evidence =
                        HistoryCoverage(
                            unsignedSpans = entryIds.map { UnsignedHistorySpan(it, it) },
                            unsignedGaps = listOf(UnsignedHistoryGap(1u, ULong.MAX_VALUE)),
                            unsignedRowOrder = order,
                            unsignedRowEntries = order.mapValues { setOf(it.value) },
                            proofs = historyRowProofs(producing),
                        )
                    cache.writeHistoryPosition("Host", "c", HistoryPosition("saved", false, evidence)).getOrThrow()
                    val source =
                        object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                            override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
                                flow {
                                    emit(ThreadSnapshot(emptyList()))
                                    emit(ThreadSnapshot(listOf(rows.last())))
                                    queries.clear() // Import, restoration and order restoration are outside mutation work.
                                    emit(ThreadSnapshot(listOf(row(size, "changed"))))
                                }
                        }
                    val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = Dispatchers.Default)
                    repository.observeThreadSnapshot("c").collect {}
                    val rowStatements = queries.filter { it.contains("legacy_rows", true) }
                    val writes = rowStatements.count { it.startsWith("INSERT", true) || it.startsWith("UPDATE", true) }
                    val deletedRows = queries.any { it.startsWith("DELETE FROM legacy_rows", true) }
                    assertFalse("ordinary update deleted saved rows: $queries", deletedRows)
                    assertEquals(1, writes)
                    val mutation = cache.legacyMutationCounts
                    println("owned_mutation saved_rows=$size counts=$mutation sql_statements=${queries.size}")
                    assertEquals(1, mutation.rowReads)
                    assertEquals(1, mutation.rowDecodes)
                    assertEquals(1, mutation.rowSerializations)
                    assertEquals(1, mutation.rowWrites)
                    assertFalse(queries.any { it.contains("FROM legacy_evidence WHERE host=? AND conversation=? ORDER BY") })
                    assertFalse(queries.any { it.startsWith("SELECT * FROM legacy_rows") && !it.contains("identity=") })
                    val restored = open("cache-$size").readThread("Host", "c")
                    assertEquals(rows.dropLast(1) + row(size, "changed"), restored)
                    mutation
                }
            assertEquals(counts.first(), counts.last())
        }

    @Test fun ownershipInvariant_partialViewPreservesUnownedRowsAndEvidence() =
        runTest {
            val cache = open()
            val rows = (1..18000).map { row(it) }
            val view = rows.takeLast(200)
            val producing = listOf(rows.first(), view.first(), view.last())
            val ids = listOf(1uL, ULong.MAX_VALUE - 1u, ULong.MAX_VALUE)
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE)),
                    unsignedGaps = listOf(UnsignedHistoryGap(1u, ULong.MAX_VALUE - 1u)),
                    unsignedRowOrder = producing.zip(ids).associate { (row, id) -> row.historyKeys().first() to id },
                    unsignedRowEntries = producing.zip(ids).associate { (row, id) -> row.historyKeys().first() to setOf(id) },
                    proofs = historyRowProofs(producing),
                    unsignedUnknown = true,
                )
            val position = HistoryPosition("daemon-cursor", false, coverage)
            for (host in listOf("Host", "host")) {
                cache.readThread(host, "c")
                cache.writeThread(host, "c", rows).getOrThrow()
                cache.writeHistoryPosition(host, "c", position).getOrThrow()
            }
            val before = requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal == LEGACY_ORDINAL_STEP }
            val token = requireNotNull(cache.newestLegacyPage("Host", "c", 200)?.continuation)
            val partial =
                object : ConversationCache by cache, OwnedLegacyCache by cache {
                    override suspend fun readThread(
                        serverId: String,
                        conversationId: String,
                    ) = cache.readThread(serverId, conversationId).takeLast(200)
                }
            val changed = view.drop(1).dropLast(1) + row(18000, "changed")
            val source =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
                        flow {
                            emit(ThreadSnapshot(emptyList()))
                            emit(ThreadSnapshot(view))
                            queries.clear()
                            emit(ThreadSnapshot(changed, suppressedUserMessageIds = setOf(view.first().message.id)))
                        }
                }
            val repository = CachingConversationRepository(source, partial, "Host", processingDispatcher = Dispatchers.Default)
            repository.observeThreadSnapshot("c").collect {}
            assertEquals(2, cache.legacyMutationCounts.rowWrites)
            assertEquals(1, cache.legacyMutationCounts.rowSerializations)
            assertNull(cache.beforeLegacyPage("Host", "c", token, 200))
            val unrelated = requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal == LEGACY_ORDINAL_STEP }
            assertEquals(before, unrelated)
            val expected = rows.take(17800) + changed
            val traversed = mutableListOf<ThreadItem>()
            var page = cache.newestLegacyPage("Host", "c", 200)
            while (page != null) {
                traversed.addAll(0, page.rows)
                page = page.continuation?.let { cache.beforeLegacyPage("Host", "c", it, 200) }
            }
            assertEquals(expected, traversed)
            val fresh = open()
            assertEquals(expected, fresh.readThread("Host", "c"))
            assertEquals(rows, fresh.readThread("host", "c"))
            assertEquals(position, fresh.readHistoryPosition("host", "c"))
            val saved = requireNotNull(fresh.readHistoryPosition("Host", "c"))
            assertEquals(listOf(UnsignedHistorySpan(1u, 1u)), saved.coverage?.unsignedSpans)
            assertEquals(historyRowProofs(listOf(rows.first())), saved.coverage?.proofs)
            assertFalse(saved.atStart)
        }

    @Test fun certificationInvariant_sharedProducingClaimsAreInvalidatedAtomically() =
        runTest {
            val fail = AtomicBoolean()
            val cache =
                open(beforeCommit = {
                    if (fail.getAndSet(false)) throw IOException("controlled")
                })
            val rows = (1..4).map { row(it) }
            val keys = rows.map { it.historyKeys().first() }
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 4u)),
                    unsignedRowOrder = keys.zip(listOf(1uL, 2uL, 3uL, 4uL)).toMap(),
                    unsignedRowEntries = keys.zip(listOf(setOf(1uL), setOf(2uL), setOf(2uL, 3uL), setOf(4uL))).toMap(),
                    proofs = historyRowProofs(rows),
                )
            val position = HistoryPosition("original", true, coverage)
            cache.writeHistoryPosition("Host", "c", position).getOrThrow()
            val before = requireNotNull(database).legacy().allEvidence("Host", "c")
            val changed = row(2, "changed")
            val changes = listOf(OwnedLegacyRow(changed, rows.first().legacyRowKey(), rows.last().legacyRowKey()))
            fail.set(true)
            assertTrue(cache.mutateLegacyRows("Host", "c", changes, emptySet()).isFailure)
            val fresh = open()
            assertEquals(rows, fresh.readThread("Host", "c"))
            assertEquals(position, fresh.readHistoryPosition("Host", "c"))
            assertEquals(before, requireNotNull(database).legacy().allEvidence("Host", "c"))
            fresh.mutateLegacyRows("Host", "c", changes, emptySet()).getOrThrow()
            val saved = requireNotNull(open().readHistoryPosition("Host", "c"))
            assertEquals(listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(3u, 4u)), saved.coverage?.unsignedSpans)
            assertEquals(historyRowProofs(listOf(rows.first(), rows.last())), saved.coverage?.proofs)
            assertFalse(saved.atStart)
        }

    @Test fun pagingInvariant_prependedRowsKeepValidEvidenceReadable() =
        runTest {
            val cache = open()
            val rows = (1..4).map { row(it) }
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            val order =
                rows.associate {
                    it.historyKeys().first() to
                        it.message.id
                            .removePrefix("id-")
                            .toULong()
                }
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 4u)),
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { setOf(it.value) },
                    proofs = historyRowProofs(rows),
                )
            cache.writeHistoryPosition("Host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            val added = (10..12).map { row(it) }
            val before = rows.first().legacyRowKey()
            val changes =
                added.mapIndexed { index, item ->
                    OwnedLegacyRow(item, added.getOrNull(index - 1)?.legacyRowKey(), before)
                }
            cache.mutateLegacyRows("Host", "c", changes, emptySet()).getOrThrow()
            val page = requireNotNull(cache.newestLegacyPage("Host", "c", 6))
            assertEquals(added.drop(1) + rows, page.rows)
            assertTrue("owned prepends must not make valid page evidence unreadable", page.evidence != null)
            assertEquals(coverage.proofs, page.evidence?.proofs)
        }

    @Test fun retentionInvariant_discardedContentCannotRemainCertified() =
        runTest {
            val cache = open()
            val rows = (1..MAX_CACHED_THREAD_ROWS).map { row(it) }
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            val key = rows.first().historyKeys().first()
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                    unsignedRowOrder = mapOf(key to ULong.MAX_VALUE),
                    unsignedRowEntries = mapOf(key to setOf(ULong.MAX_VALUE)),
                    proofs = historyRowProofs(listOf(rows.first())),
                )
            cache.writeHistoryPosition("Host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            val newest = row(MAX_CACHED_THREAD_ROWS + 1)
            val changes = listOf(OwnedLegacyRow(newest, rows.last().legacyRowKey(), null))
            cache.mutateLegacyRows("Host", "c", changes, emptySet()).getOrThrow()
            assertEquals(1, cache.legacyMutationCounts.rowSerializations)
            val fresh = open()
            assertEquals(rows.drop(1) + newest, fresh.readThread("Host", "c"))
            val saved = requireNotNull(fresh.readHistoryPosition("Host", "c"))
            assertEquals(emptyList<UnsignedHistorySpan>(), saved.coverage?.unsignedSpans)
            assertTrue(
                saved.coverage
                    ?.proofs
                    .orEmpty()
                    .isEmpty(),
            )
            assertTrue(saved.coverage?.unsignedUnknown == true)
            assertFalse(saved.atStart)
            assertEquals("", saved.cursor)
        }
}
