package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryDurabilityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val oldSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
    }

    private fun cache() = FileConversationCache(tmp.root, UnconfinedTestDispatcher())

    private fun page(
        vararg ids: Long,
        cursor: String = "opaque",
        atStart: Boolean = false,
    ) = HistoryPage(
        ids.reversed().map { id ->
            HistoryEntry(
                id,
                "send_message",
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c","message_id":"m$id","text":"message $id"}""",
                ),
                Instant.fromEpochSeconds(id),
            )
        },
        cursor,
        atStart,
    )

    private fun rows(page: HistoryPage) = reduceHistoryPage(page.entries, true)

    @Test fun oneLegacyOccurrenceCannotCertifyTwoReceivedDeltas_andTextIsNotPersisted() {
        val text = "unique legacy fragment"
        val legacy = listOf(ThreadItem.MessageItem(Message("t", "s", Role.Assistant, text, Instant.fromEpochSeconds(1), false)))
        val entries =
            (1L..2L).map { id ->
                HistoryEntry(
                    id,
                    "assistant_delta",
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c","turn_id":"t","seq":$id,"text":"$text"}""",
                    ),
                    Instant.fromEpochSeconds(id),
                )
            }
        val state = HistoryCoverage(unknown = true).received(HistoryPage(entries.reversed(), "older", false)).boundTo(legacy)
        assertEquals(listOf(HistorySpan(1, 1)), state.spans)
        assertTrue(state.unknown)
        assertTrue(!MobileJson.encodeToString(state).contains(text))
    }

    @Test fun matchingPartOfALegacyTurnPreservesCoverage_butDoesNotCloseUnknownUntilAtStart() =
        runTest {
            val legacy = listOf(ThreadItem.MessageItem(Message("t", "s", Role.Assistant, "ab", Instant.fromEpochSeconds(1), false)))
            val delta =
                HistoryEntry(
                    9,
                    "assistant_delta",
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c","turn_id":"t","seq":1,"text":"b"}""",
                    ),
                    Instant.fromEpochSeconds(9),
                )
            val state = HistoryCoverage(unknown = true).received(HistoryPage(listOf(delta), "older", false), newest = true).boundTo(legacy)
            assertEquals(listOf(HistorySpan(9, 9)), state.spans)
            assertTrue(state.unknown)
            cache().writeThread("h", "c", legacy)
            cache().writeHistoryPosition("h", "c", HistoryPosition("", true, state))
            val restored = cache().readHistoryPosition("h", "c")?.coverage ?: error("missing state")
            val terminal = restored.received(HistoryPage(emptyList(), "", true), target = 0).boundTo(legacy)
            cache().writeHistoryPosition("h", "c", HistoryPosition("", true, terminal))
            assertEquals(listOf(HistorySpan(9, 9)), cache().readHistoryPosition("h", "c")?.coverage?.spans)
            assertTrue(cache().readHistoryPosition("h", "c")?.coverage?.unknown == false)
        }

    @Test fun partialFillRestoresSpansCursorsAndBothSides_fromAFreshFileCache() =
        runTest {
            val older = page(1, 2, cursor = "oldest")
            val newer = page(8, 9, cursor = "seven")
            val middle = page(6, 7, cursor = "five")
            val held = rows(older) + rows(middle) + rows(newer)
            val state =
                HistoryCoverage()
                    .received(older)
                    .received(newer, newest = true)
                    .received(middle, target = 2)
                    .boundTo(held)
            assertTrue(cache().writeThread("host-a", "c", held).isSuccess)
            assertTrue(cache().writeHistoryPosition("host-a", "c", HistoryPosition("oldest", true, state)).isSuccess)
            val restored = cache().readHistoryPosition("host-a", "c")
            assertEquals(state, restored?.coverage)
            assertEquals("five", restored?.coverage?.cursorFor(2))
            assertEquals(9L, restored?.coverage?.highWater)
            assertEquals(held, cache().readThread("host-a", "c"))
            assertNull(cache().readHistoryPosition("host-b", "c"))
            assertNull(cache().readHistoryPosition("host-a", "other"))
        }

    @Test fun stateWriteCannotCertifyRowsThatHaveNotLanded() =
        runTest {
            val page = page(8, 9)
            val state = HistoryCoverage().received(page, newest = true).boundTo(rows(page))
            cache().writeHistoryPosition("h", "c", HistoryPosition("older", true, state))
            val restored = cache().readHistoryPosition("h", "c")?.coverage
            assertEquals(emptyList<HistorySpan>(), restored?.spans)
            assertTrue(restored?.unknown == true)
        }

    @Test fun deathBetweenRowsAndState_keepsOnlyEarlierDurableClaims() =
        runTest {
            val old = page(1, 2)
            val state = HistoryCoverage().received(old).boundTo(rows(old))
            cache().writeThread("h", "c", rows(old))
            cache().writeHistoryPosition("h", "c", HistoryPosition("old", true, state))
            cache().writeThread("h", "c", rows(old) + rows(page(8, 9)))
            assertEquals(2L, cache().readHistoryPosition("h", "c")?.coverage?.highWater)
            assertEquals(4, cache().readThread("h", "c").size)
        }

    @Test fun failedRowWritePreventsTheRepositoryStateWrite() =
        runTest {
            val disk = cache()
            val old = page(1)
            val state = HistoryCoverage().received(old).boundTo(rows(old))
            disk.writeThread("h", "c", rows(old))
            disk.writeHistoryPosition("h", "c", HistoryPosition("old", false, state))
            var stateWrites = 0
            val failing =
                object : ConversationCache by disk {
                    override suspend fun writeThread(
                        serverId: String,
                        conversationId: String,
                        rows: List<ThreadItem>,
                    ): Result<Unit> = Result.failure(IOException("static failure"))

                    override suspend fun writeHistoryPosition(
                        serverId: String,
                        conversationId: String,
                        position: HistoryPosition?,
                    ): Result<Unit> {
                        stateWrites++
                        return disk.writeHistoryPosition(serverId, conversationId, position)
                    }
                }
            val newPage = page(8, 9)
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(rows(newPage))
                }
            CachingConversationRepository(
                delegate,
                failing,
                "h",
            ).writeHistoryPosition("c", HistoryPosition("old", false, state.received(newPage, newest = true)))
            assertEquals(0, stateWrites)
            assertEquals(state, cache().readHistoryPosition("h", "c")?.coverage)
        }

    @Test fun legacyRowsRemainUnknown_withAndWithoutSavedAtStart() =
        runTest {
            val page = page(1)
            for (atStart in listOf(false, true)) {
                cache().writeThread("h", "c", rows(page))
                cache().writeHistoryPosition("h", "c", if (atStart) HistoryPosition("", true) else null)
                val repository = CachingConversationRepository(FakeConversationRepository(), cache(), "h")
                val restored = repository.readHistoryPosition("c")
                assertTrue(restored?.coverage?.unknown == true)
                assertTrue(restored?.coverage?.spans?.isEmpty() == true)
                assertEquals(rows(page), cache().readThread("h", "c"))
            }
        }

    @Test fun trimmingAndStaleRowWritersRemoveClaimsForDiscardedContent() =
        runTest {
            val old = page(1, 2)
            val held = rows(old)
            val state = HistoryCoverage().received(old).boundTo(held)
            cache().writeThread("h", "c", held)
            cache().writeHistoryPosition("h", "c", HistoryPosition("older", true, state))
            val newest = held.last() as ThreadItem.MessageItem
            val many = List(MAX_CACHED_THREAD_ROWS) { index -> newest.copy(message = newest.message.copy(id = "new-$index")) }
            cache().writeThread("h", "c", held + many)
            val restored = cache().readHistoryPosition("h", "c")?.coverage
            assertTrue(restored?.spans?.isEmpty() == true)
            assertTrue(restored?.unknown == true)
            assertEquals("", cache().readHistoryPosition("h", "c")?.cursor)
            assertEquals(false, cache().readHistoryPosition("h", "c")?.atStart)
            assertEquals(MAX_CACHED_THREAD_ROWS, cache().readThread("h", "c").size)
        }
}
