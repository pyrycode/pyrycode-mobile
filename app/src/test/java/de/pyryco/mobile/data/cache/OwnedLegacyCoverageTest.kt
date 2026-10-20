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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

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

    @Test fun certificationInvariant_uncommittedProducerWaitsForCommittedRow() =
        runTest {
            fun position(
                rows: List<ThreadItem>,
                cursor: String,
                atStart: Boolean,
                producer: ULong,
            ): HistoryPosition {
                val order = rows.mapIndexed { index, row -> row.historyKeys().first() to if (index == 0) 1uL else producer }.toMap()
                return HistoryPosition(
                    cursor,
                    atStart,
                    HistoryCoverage(
                        unsignedSpans = listOf(UnsignedHistorySpan(1u, order.values.max())),
                        unsignedRowOrder = order,
                        unsignedRowEntries = order.mapValues { setOf(it.value) },
                        proofs = historyRowProofs(rows),
                    ),
                )
            }
            for (producer in listOf(1uL, 2uL)) {
                val cache = open("producer-$producer")
                val first = row(1)
                val streaming =
                    ThreadItem.MessageItem(
                        Message("id-2", "s", Role.Assistant, "partial", Instant.fromEpochSeconds(2), true),
                    )
                val initial = position(listOf(first), "old", false, producer)
                cache.readThread("Host", "c")
                cache.writeThread("Host", "c", listOf(first)).getOrThrow()
                cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
                val source = Source()
                val repository = CachingConversationRepository(source, cache, "Host")
                repository.observeThreadSnapshot("c").first()
                repository.readHistoryPosition("c")
                source.snapshots.value = ThreadSnapshot(listOf(first, streaming))
                val received = position(listOf(first, streaming), "new", true, producer)
                repository.writeHistoryPosition("c", received)
                assertEquals(listOf(first), cache.readThread("Host", "c"))
                assertEquals(initial, cache.readHistoryPosition("Host", "c"))
                val settled = streaming.copy(message = streaming.message.copy(isStreaming = false))
                source.snapshots.value = ThreadSnapshot(listOf(first, settled))
                repository.writeHistoryPosition("c", received)
                val fresh = open("producer-$producer")
                assertEquals(listOf(first, settled), fresh.readThread("Host", "c"))
                assertEquals(received, fresh.readHistoryPosition("Host", "c"))
            }
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

    @Test fun anchorInvariant_extendingSpanKeepsReceivedGapWalk() =
        runTest {
            val cache = open()
            val rows = listOf(row(1), row(2))
            val order = rows.zip(listOf(1uL, ULong.MAX_VALUE)).associate { (row, id) -> row.historyKeys().first() to id }
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 3u), UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                    unsignedGaps = listOf(UnsignedHistoryGap(1u, ULong.MAX_VALUE)),
                    unsignedWalks = mapOf(1uL to "held-walk"),
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { setOf(it.value) },
                    proofs = historyRowProofs(rows),
                )
            val initial = HistoryPosition("saved", false, coverage)
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val source = Source()
            val repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            val added = row(3)
            val key = added.historyKeys().first()
            source.snapshots.value = ThreadSnapshot(listOf(added))
            val next =
                initial.copy(
                    coverage =
                        coverage.copy(
                            unsignedSpans = listOf(UnsignedHistorySpan(1u, 4u), UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                            unsignedRowOrder = order + (key to 4uL),
                            unsignedRowEntries = coverage.unsignedRowEntries + (key to setOf(4uL)),
                            proofs = coverage.proofs + historyRowProofs(listOf(added)),
                        ),
                )
            repository.writeHistoryPosition("c", next)
            val fresh = open()
            assertEquals(listOf(rows[0], added, rows[1]), fresh.readThread("Host", "c"))
            val saved = fresh.readHistoryPosition("Host", "c")
            assertEquals(coverage.unsignedGaps, saved?.coverage?.unsignedGaps)
            assertEquals(coverage.unsignedWalks, saved?.coverage?.unsignedWalks)
            assertEquals(next, saved)
        }

    @Test fun anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk() =
        // Four disk-backed cases complete in about 54s locally; restore/reopen work shares this budget.
        // Keep a finite method-local bound and phase evidence without reducing either fixture.
        runTest(timeout = 2.minutes) {
            val started = TimeSource.Monotonic.markNow()
            val counts = HashMap<Boolean, Pair<LegacyRoomStorage.MutationCounts, Int>>()
            for (size in listOf(20, 18000)) {
                for (remove in listOf(false, true)) {
                    fun phase(name: String) {
                        println(
                            "owned_gap_subtraction_phase saved_rows=$size removal=$remove phase=$name elapsed_ms=${started.elapsedNow().inWholeMilliseconds}",
                        )
                    }
                    phase("open")
                    val name = "subtract-$size-$remove"
                    var cache = open(name)
                    phase("fixture")
                    val rows = (1..size).map { row(it) }
                    val ids = listOf(1uL, 2uL, 3uL, 10uL) + (5..size).map { it.toULong() * 2u + 20u }
                    val order = rows.zip(ids).associate { (row, id) -> row.historyKeys().first() to id }
                    val spans = listOf(UnsignedHistorySpan(1u, 3u)) + ids.drop(3).map { UnsignedHistorySpan(it, it) }
                    val gaps =
                        listOf(UnsignedHistoryGap(2u, 10u)) + spans.drop(1).zipWithNext { a, b -> UnsignedHistoryGap(a.last, b.first) }
                    val evidence =
                        HistoryCoverage(
                            unsignedSpans = spans,
                            unsignedGaps = gaps,
                            unsignedWalks = gaps.associate { it.anchor to "received-walk-${it.anchor}" },
                            unsignedCursors = gaps.associate { it.anchor to "received-cursor-${it.anchor}" },
                            unsignedRowOrder = order,
                            unsignedRowEntries = order.mapValues { setOf(it.value) },
                            proofs = historyRowProofs(rows),
                        )
                    val initial = HistoryPosition("saved", true, evidence)
                    phase("seed_rows")
                    cache.readThread("Host", "c")
                    cache.writeThread("Host", "c", rows).getOrThrow()
                    phase("seed_evidence")
                    cache.writeHistoryPosition("Host", "c", initial).getOrThrow()

                    suspend fun unrelated() =
                        requireNotNull(database).legacy().allEvidence("Host", "c").filterNot {
                            it.ordinal == LEGACY_ORDINAL_STEP || it.kind == "losses" || it.kind == "spans" && it.low.toULong() <= 3u
                        }
                    phase("read_unrelated")
                    val unchanged = unrelated()
                    val source = Source()
                    var repository = CachingConversationRepository(source, cache, "Host")
                    phase("restore_repository")
                    assertEquals(rows, repository.observeThreadSnapshot("c").first().rows)
                    repository.readHistoryPosition("c")
                    val changed = row(1, "changed")
                    source.snapshots.value =
                        if (remove) {
                            ThreadSnapshot(
                                emptyList(),
                                suppressedUserMessageIds = setOf("id-1"),
                            )
                        } else {
                            ThreadSnapshot(listOf(changed))
                        }
                    val expectedRows = if (remove) rows.drop(1) else listOf(changed) + rows.drop(1)
                    phase("mutate")
                    queries.clear()
                    repository.writeHistoryPosition("c", initial)
                    val statements = synchronized(queries) { queries.toList() }.filter { it.contains("legacy_", true) }
                    val work = cache.legacyMutationCounts to statements.size
                    println(
                        "owned_gap_subtraction saved_rows=$size removal=$remove counts=${work.first} storage_sql_statements=${work.second}",
                    )
                    if (size == 20) counts[remove] = work else assertEquals("bounded relevant evidence work", counts[remove], work)
                    phase("verify_mutation")
                    assertFalse(statements.any { it.startsWith("SELECT * FROM legacy_rows", true) && !it.contains("identity=") })
                    assertFalse(statements.any { it.contains("FROM legacy_evidence WHERE host=? AND conversation=? ORDER BY") })
                    assertFalse(statements.any { it == "DELETE FROM legacy_evidence WHERE host=? AND (? IS NULL OR conversation=?)" })
                    assertEquals("untouched received evidence", unchanged, unrelated())
                    phase("repeat_saves")
                    repeat(2) { repository.writeHistoryPosition("c", initial) }
                    phase("first_reopen")
                    cache = open(name)
                    phase("verify_first_reopen")
                    assertEquals(expectedRows, cache.readThread("Host", "c"))
                    val saved = requireNotNull(cache.readHistoryPosition("Host", "c"))
                    val retained = requireNotNull(saved.coverage)
                    assertEquals(UnsignedHistorySpan(2u, 3u), retained.unsignedSpans.first())
                    assertEquals(evidence.unsignedGaps, retained.unsignedGaps)
                    assertEquals(evidence.unsignedWalks, retained.unsignedWalks)
                    assertEquals(evidence.unsignedCursors, retained.unsignedCursors)
                    assertEquals(evidence.proofs - rows.first().historyKeys().first(), retained.proofs)
                    assertFalse(saved.atStart)
                    assertTrue(retained.unsignedUnknown)
                    assertEquals(unchanged, unrelated())
                    phase("restore_reopened_repository")
                    repository = CachingConversationRepository(source, cache, "Host")
                    repository.observeThreadSnapshot("c").first()
                    repository.readHistoryPosition("c")
                    phase("repeat_reopened_saves")
                    repeat(2) { repository.writeHistoryPosition("c", initial) }
                    phase("final_reopen")
                    val reopened = open(name)
                    phase("verify_final_reopen")
                    assertEquals(expectedRows, reopened.readThread("Host", "c"))
                    assertEquals(saved, reopened.readHistoryPosition("Host", "c"))
                    assertEquals(unchanged, unrelated())
                    phase("close")
                    close()
                    phase("complete")
                }
            }
        }

    @Test fun anchorInvariant_subtractingAnchorWithdrawsItsInvalidWalk() =
        runTest {
            for (remove in listOf(false, true)) {
                val name = "invalid-anchor-$remove"
                val cache = open(name)
                val rows = (1..4).map { row(it) }
                val order = rows.zip(listOf(1uL, 2uL, 3uL, 10uL)).associate { (row, id) -> row.historyKeys().first() to id }
                val initial =
                    HistoryPosition(
                        "saved",
                        true,
                        HistoryCoverage(
                            unsignedSpans = listOf(UnsignedHistorySpan(1u, 3u), UnsignedHistorySpan(10u, 10u)),
                            unsignedGaps = listOf(UnsignedHistoryGap(2u, 10u)),
                            unsignedWalks = mapOf(2uL to "received-walk"),
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
                source.snapshots.value =
                    if (remove) {
                        ThreadSnapshot(
                            emptyList(),
                            suppressedUserMessageIds = setOf("id-2"),
                        )
                    } else {
                        ThreadSnapshot(listOf(row(2, "changed")))
                    }
                repeat(2) { repository.writeHistoryPosition("c", initial) }
                val retained = requireNotNull(open(name).readHistoryPosition("Host", "c")?.coverage)
                assertEquals(listOf(UnsignedHistoryGap(1u, 3u), UnsignedHistoryGap(3u, 10u)), retained.unsignedGaps)
                assertTrue(retained.unsignedWalks.isEmpty())
                assertFalse(retained.unsignedSpans.any { 2uL in it.first..it.last })
            }
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

    @Test fun recoveryInvariant_sharedProducerRequiresEverySiblingProof() =
        runTest {
            var cache = open()
            val rows = listOf(row(1), row(2), row(3))
            val keys = rows.map { it.historyKeys().first() }
            val order = mapOf(keys[0] to 1uL, keys[1] to 1uL, keys[2] to 3uL)
            val initial =
                HistoryPosition(
                    "saved",
                    true,
                    HistoryCoverage(
                        unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(3u, 3u)),
                        unsignedGaps = listOf(UnsignedHistoryGap(1u, 3u)),
                        unsignedRowOrder = order,
                        unsignedRowEntries = order.mapValues { setOf(it.value) },
                        proofs = historyRowProofs(rows),
                    ),
                )
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val unrelated = requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.key == keys[2] }
            val changed = listOf(row(1, "changed-a"), row(2, "changed-b"), rows[2])
            var source = Source()
            var repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            source.snapshots.value = ThreadSnapshot(changed)
            repository.writeHistoryPosition("c", initial)
            val partial = initial.copy(coverage = initial.coverage?.copy(proofs = historyRowProofs(listOf(rows[0], changed[1], rows[2]))))
            repository.writeHistoryPosition("c", partial)
            cache = open()
            val saved = requireNotNull(cache.readHistoryPosition("Host", "c"))
            assertFalse("one refreshed sibling cannot restore completeness", saved.atStart)
            assertTrue(requireNotNull(saved.coverage).unsignedUnknown)
            assertFalse(requireNotNull(saved.coverage).unsignedSpans.any { 1uL in it.first..it.last })
            assertEquals(unrelated, requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.key == keys[2] })
            assertEquals(changed, cache.readThread("Host", "c"))
            source = Source()
            repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            repository.writeHistoryPosition("c", partial)
            assertFalse(requireNotNull(cache.readHistoryPosition("Host", "c")).atStart)
            val refreshed = initial.copy(coverage = initial.coverage?.copy(proofs = historyRowProofs(changed)))
            repository.writeHistoryPosition("c", refreshed)
            val fresh = open()
            assertEquals(refreshed, fresh.readHistoryPosition("Host", "c"))
            assertEquals(changed, fresh.readThread("Host", "c"))
        }

    @Test fun recoveryInvariant_claimExtensionPreservesSiblingBindings() =
        runTest {
            val started = TimeSource.Monotonic.markNow()
            val counts = ArrayList<Pair<LegacyRoomStorage.MutationCounts, Int>>()
            for (size in listOf(20, 18000)) {
                for (remove in listOf(false, true)) {
                    fun phase(name: String) {
                        println(
                            "owned_claim_extension_phase saved_rows=$size removal=$remove phase=$name elapsed_ms=${started.elapsedNow().inWholeMilliseconds}",
                        )
                    }
                    phase("open")
                    val name = "extension-$size-$remove"
                    var cache = open(name)
                    phase("fixture")
                    val rows = (1..size).map { row(it) }
                    val keys = rows.take(2).map { it.historyKeys().first() }
                    val order = rows.mapIndexed { index, row -> row.historyKeys().first() to (index + 1).toULong() * 2u }.toMap()
                    val spans = listOf(UnsignedHistorySpan(1u, 2u)) + order.values.drop(2).map { UnsignedHistorySpan(it, it) }
                    val evidence =
                        HistoryCoverage(
                            unsignedSpans = spans,
                            unsignedGaps = spans.zipWithNext { a, b -> UnsignedHistoryGap(a.last, b.first) },
                            unsignedRowOrder = order + keys.associateWith { 1uL },
                            unsignedRowEntries =
                                order.mapValues { setOf(it.value) } +
                                    mapOf(keys[0] to setOf(1uL), keys[1] to setOf(1uL, 2uL)),
                            proofs = historyRowProofs(rows),
                            unsignedUnknown = false,
                        )
                    val initial = HistoryPosition("saved", true, evidence)
                    phase("seed_rows")
                    cache.readThread("Host", "c")
                    cache.writeThread("Host", "c", rows).getOrThrow()
                    phase("seed_evidence")
                    cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
                    phase("read_unrelated")
                    val unchanged =
                        requireNotNull(database).legacy().allEvidence("Host", "c").filter {
                            it.ordinal >=
                                2 * LEGACY_ORDINAL_STEP
                        }
                    val partial =
                        object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                            override suspend fun readThread(
                                serverId: String,
                                conversationId: String,
                            ) = rows.take(1)
                        }
                    val source = Source()
                    val repository = CachingConversationRepository(source, partial, "Host")
                    phase("restore_partial")
                    repository.observeThreadSnapshot("c").first()
                    repository.readHistoryPosition("c")
                    val next =
                        initial.copy(
                            coverage =
                                evidence.copy(
                                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 3u)) + spans.drop(1),
                                    unsignedRowEntries = evidence.unsignedRowEntries + (keys[0] to setOf(1uL, 3uL)),
                                ),
                        )
                    phase("mutate")
                    queries.clear()
                    repository.writeHistoryPosition("c", next)
                    val statements = synchronized(queries) { queries.toList() }.filter { it.contains("legacy_", true) }
                    counts += cache.legacyMutationCounts to statements.size
                    phase("verify_mutation")
                    assertFalse(statements.any { it.startsWith("SELECT * FROM legacy_rows", true) && !it.contains("identity=") })
                    assertFalse(statements.any { it.contains("FROM legacy_evidence WHERE host=? AND conversation=? ORDER BY") })
                    assertFalse(statements.any { it.startsWith("DELETE FROM legacy_rows", true) })
                    assertEquals(
                        "unchanged sibling and unrelated claims",
                        unchanged,
                        requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal >= 2 * LEGACY_ORDINAL_STEP },
                    )
                    phase("first_reopen")
                    cache = open(name)
                    phase("verify_first_reopen")
                    assertEquals(rows, cache.readThread("Host", "c"))
                    assertEquals(next, cache.readHistoryPosition("Host", "c"))
                    val changed =
                        if (remove) {
                            rows.filterIndexed { index, _ -> index != 1 }
                        } else {
                            rows.take(1) + row(2, "changed") +
                                rows.drop(2)
                        }
                    val live = Source()
                    val reopened = CachingConversationRepository(live, cache, "Host")
                    phase("restore_full")
                    reopened.observeThreadSnapshot("c").first()
                    reopened.readHistoryPosition("c")
                    live.snapshots.value = ThreadSnapshot(changed, suppressedUserMessageIds = if (remove) setOf("id-2") else emptySet())
                    phase("mutate_sibling")
                    repeat(2) { reopened.writeHistoryPosition("c", next) }
                    phase("final_reopen")
                    val fresh = open(name)
                    phase("verify_final_reopen")
                    assertEquals(changed, fresh.readThread("Host", "c"))
                    val saved = requireNotNull(fresh.readHistoryPosition("Host", "c"))
                    assertFalse(saved.atStart)
                    assertTrue(requireNotNull(saved.coverage).unsignedUnknown)
                    assertFalse(requireNotNull(saved.coverage).unsignedSpans.any { span -> (1uL..3uL).any { it in span.first..span.last } })
                    assertEquals(
                        unchanged.filter { it.ordinal > 2 * LEGACY_ORDINAL_STEP },
                        requireNotNull(database).legacy().allEvidence("Host", "c").filter { it.ordinal > 2 * LEGACY_ORDINAL_STEP },
                    )
                    phase("close")
                    close()
                    phase("complete")
                }
            }
            assertTrue("same relevant work for short and fragmented histories", counts.all { it == counts.first() })
            println("owned_coverage_claim_extension counts=${counts.first()}")
        }

    @Test fun recoveryInvariant_currentWithdrawalValidatesEverySibling() =
        runTest {
            for (withdrawSibling in listOf(false, true)) {
                val name = "withdrawal-$withdrawSibling"
                val cache = open(name)
                val rows = (1..3).map { row(it) }
                val keys = rows.map { it.historyKeys().first() }
                val evidence =
                    HistoryCoverage(
                        unsignedSpans = listOf(UnsignedHistorySpan(1u, 2u), UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                        unsignedGaps = listOf(UnsignedHistoryGap(2u, ULong.MAX_VALUE)),
                        unsignedWalks = mapOf(2uL to "received-walk"),
                        unsignedCursors = mapOf(2uL to "received-cursor"),
                        unsignedRowOrder = mapOf(keys[0] to 1uL, keys[1] to 1uL, keys[2] to 2uL),
                        unsignedRowEntries =
                            mapOf(
                                keys[0] to setOf(1uL),
                                keys[1] to setOf(1uL, 2uL),
                                keys[2] to setOf(2uL, ULong.MAX_VALUE),
                            ),
                        proofs = historyRowProofs(rows),
                    )
                val initial = HistoryPosition("saved", true, evidence)
                cache.readThread("Host", "c")
                cache.writeThread("Host", "c", rows).getOrThrow()
                cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
                val source = Source()
                val repository = CachingConversationRepository(source, cache, "Host")
                repository.observeThreadSnapshot("c").first()
                repository.readHistoryPosition("c")
                val omitted = if (withdrawSibling) setOf(keys[1]) else emptySet()
                val next =
                    initial.copy(
                        coverage =
                            evidence.copy(
                                unsignedSpans = listOf(UnsignedHistorySpan(1u, 3u), UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                                unsignedRowOrder = (evidence.unsignedRowOrder + (keys[0] to 3uL)) - omitted,
                                unsignedRowEntries = (evidence.unsignedRowEntries + (keys[0] to setOf(3uL))) - omitted,
                                proofs = evidence.proofs - omitted,
                            ),
                    )
                repository.writeHistoryPosition("c", next)
                val fresh = open(name)
                assertEquals(rows, fresh.readThread("Host", "c"))
                val saved = requireNotNull(fresh.readHistoryPosition("Host", "c"))
                val committed = requireNotNull(saved.coverage)
                if (withdrawSibling) {
                    assertFalse(saved.atStart)
                    assertTrue(committed.unsignedUnknown)
                    assertEquals(setOf(keys[0]), committed.proofs.keys)
                    assertEquals(listOf(UnsignedHistorySpan(3u, 3u)), committed.unsignedSpans)
                } else {
                    assertEquals(evidence.unsignedGaps, committed.unsignedGaps)
                    assertEquals(evidence.unsignedWalks, committed.unsignedWalks)
                    assertEquals(evidence.unsignedCursors, committed.unsignedCursors)
                    assertEquals(next.coverage, committed)
                    assertEquals(next.copy(atStart = false), saved)
                    assertTrue(committed.unsignedIncomplete)
                }
            }
        }

    @Test fun retentionInvariant_uncertifiedDiscardSurvivesRestartAndLaterEvidence() =
        runTest {
            var cache = open()
            val rows = (1..MAX_CACHED_THREAD_ROWS).map { row(it) }
            val initial = HistoryPosition("saved", true, HistoryCoverage(unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u))))
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            var source = Source()
            var repository = CachingConversationRepository(source, cache, "Host")
            repository.observeThreadSnapshot("c").first()
            repository.readHistoryPosition("c")
            source.snapshots.value = ThreadSnapshot(listOf(row(MAX_CACHED_THREAD_ROWS + 1)))
            repository.writeHistoryPosition("c", initial)
            cache = open()
            assertEquals(rows.drop(1) + row(MAX_CACHED_THREAD_ROWS + 1), cache.readThread("Host", "c"))
            repeat(2) {
                val saved = requireNotNull(cache.readHistoryPosition("Host", "c"))
                assertEquals("", saved.cursor)
                assertFalse(saved.atStart)
                assertTrue(requireNotNull(saved.coverage).unsignedUnknown)
                source = Source()
                repository = CachingConversationRepository(source, cache, "Host")
                repository.observeThreadSnapshot("c").first()
                repository.readHistoryPosition("c")
                repository.writeHistoryPosition("c", initial.copy(cursor = "later"))
                cache = open()
            }
            val saved = requireNotNull(cache.readHistoryPosition("Host", "c"))
            assertFalse(saved.atStart)
            assertTrue(requireNotNull(saved.coverage).unsignedUnknown)
            // Explicit replacement remains the recovery contract for newly received full metadata.
            cache.writeHistoryPosition("Host", "c", initial.copy(cursor = "received-again")).getOrThrow()
            assertEquals(initial.copy(cursor = "received-again"), open().readHistoryPosition("Host", "c"))
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
