package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantParentCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val previousSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test
    fun cacheOverlapAtStartMiddleAndEnd_inBothDirections_retainsKnownParentAndHeldOrder() {
        val complete = rows((0..4).toList(), PARENT)
        for (indices in listOf(listOf(0, 1), listOf(1, 3), listOf(3, 4))) {
            for (parentOnHeld in listOf(true, false)) {
                val held = rows(indices, if (parentOnHeld) PARENT else "")
                val cached = if (parentOnHeld) rows((0..4).toList(), "") else complete
                val result = held.mergeCachedRows(cached)
                assertEquals(listOf("t"), result.messages().map { it.id })
                assertEquals("abcde", result.messages().single().content)
                assertEquals(PARENT, result.messages().single().parentToolUseId)
                assertEquals(result, result.mergeCachedRows(cached))
            }
        }
    }

    @Test
    fun legacyCacheWithAndWithoutRecoverableSegmentRecord_gainsAttributionInBothDirections() {
        for (text in listOf("abc", "prefix abc suffix")) {
            val legacy = listOf(ThreadItem.MessageItem(Message("t", "", Role.Assistant, text, TS, false)))
            val attributed = rows(listOf(0, 1, 2), PARENT)
            for (merged in listOf(legacy.mergeHistoryRows(attributed), attributed.mergeCachedRows(legacy))) {
                val assistant = merged.messages().filter { it.role == Role.Assistant }
                assertEquals(text, assistant.joinToString("") { it.content })
                assertTrue(assistant.all { it.parentToolUseId == PARENT })
                assertEquals(assistant.map { it.id }.distinct(), assistant.map { it.id })
            }
        }
    }

    @Test
    fun diskFormatStaysUnattributed_wireEvidenceEnrichesRows_andReconnectKeepsItInMemory() =
        runTest(UnconfinedTestDispatcher()) {
            for (evidenceSeq in 0..2) {
                val cache = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))
                val original = rows(listOf(0, 1, 2), PARENT)
                assertTrue(cache.writeThread("host", "c", original).isSuccess)
                val restored = cache.readThread("host", "c")
                assertEquals("", restored.messages().single().parentToolUseId)
                assertEquals(original.messages().single().segment, restored.messages().single().segment)
                val live = MutableStateFlow<List<ThreadItem>>(emptyList())
                val delegate =
                    object : ConversationRepository by FakeConversationRepository() {
                        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live
                    }
                val emissions = mutableListOf<List<ThreadItem>>()
                val job =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        CachingConversationRepository(delegate, cache, "host").observeMessages("c").collect { emissions += it }
                    }
                assertEquals(
                    "",
                    emissions
                        .last()
                        .messages()
                        .single()
                        .parentToolUseId,
                )
                live.value = rows(listOf(evidenceSeq), PARENT)
                val enriched = emissions.last()
                assertEquals("abc", enriched.messages().single().content)
                assertEquals(PARENT, enriched.messages().single().parentToolUseId)
                live.value = emptyList()
                assertEquals(enriched, emissions.last())
                live.value = rows(listOf((evidenceSeq + 1) % 3), "")
                assertEquals(enriched, emissions.last())
                assertEquals(
                    "",
                    cache
                        .readThread("host", "c")
                        .messages()
                        .single()
                        .parentToolUseId,
                )
                assertTrue(logs.none { PARENT in it })
                job.cancel()
            }
        }

    private fun rows(
        seqs: List<Int>,
        parent: String,
    ): List<ThreadItem> =
        seqs
            .fold(emptyList<ThreadItem>()) { rows, seq ->
                rows.withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", seq, ('a' + seq).toString(), parent), TS)
            }.withFinalizedTurn(LiveSessionEvent.TurnEnd("c", "t", "end_turn"), TS)

    private fun List<ThreadItem>.messages() = filterIsInstance<ThreadItem.MessageItem>().map { it.message }

    private companion object {
        val TS = Instant.parse("2026-10-06T10:00:00Z")
        const val PARENT = "agent-parent"
    }
}
