package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.HistoryPagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.toHistoryPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReconciliationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val previousSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test
    fun disjointPages_olderNewerAndMiddle_keepDaemonOrderInEveryArrivalOrder() =
        runTest {
            val entries = (1..5).map { user(it, "m$it", at = 6 - it) }
            for (order in permutations(entries.indices.toList())) {
                val projection = ThreadProjection()
                for (index in order) {
                    projection.mergeHistoryPage("c", page(entries[index]), true)
                }
                assertEquals("order $order", (1..5).map { "m$it" }, projection.observe("c").first().ids())
                projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true)
                assertEquals((1..5).map { "m$it" }, projection.observe("c").first().ids())
            }
        }

    @Test
    fun newestPageAfterOlderRows_equalClocks_andOverlap_keepOneRowPerIdentity() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(user(1, "a"), user(2, "b")), true)
            projection.mergeHistoryPage("c", page(user(3, "c"), user(4, "d")), true)
            projection.mergeHistoryPage("c", page(user(2, "b"), user(3, "c")), true)
            assertEquals(listOf("a", "b", "c", "d"), projection.observe("c").first().ids())
        }

    @Test
    fun liveArrivalOrder_isNeverSortedByTimestamp_whenMiddleRowsJoin() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c" to message("a", 5), "c" to message("b", 1)))
            projection.mergeHistoryPage("c", page(user(1, "a", 5), user(2, "middle", 3), user(3, "b", 1)), true)
            assertEquals(listOf("a", "middle", "b"), projection.observe("c").first().ids())
        }

    @Test
    fun sparseAssistantOverlap_fillsBeforeBetweenAndAfter_inBothOrdersAndRepeats() =
        runTest {
            val complete =
                arrayOf(
                    delta(1, 0, "a"),
                    delta(2, 1, "b"),
                    delta(3, 2, "c"),
                    tool(4),
                    delta(5, 3, "d"),
                    delta(6, 4, "e"),
                    end(7),
                )
            val sparse = arrayOf(delta(2, 1, "b"), tool(4), delta(5, 3, "d"), end(7))
            for (pages in listOf(listOf(sparse, complete), listOf(complete, sparse))) {
                val projection = ThreadProjection()
                for (entries in pages + pages) projection.mergeHistoryPage("c", page(*entries), true)
                val rows = projection.observe("c").first()
                assertEquals(listOf("abc", "Bash", "de"), rows.messages().map { it.content })
                assertEquals(listOf(0, 1, 2, 3, 4), rows.seqs())
                assertEquals(rows.ids().distinct(), rows.ids())
                assertTrue(rows.messages().none { it.isStreaming })
            }
        }

    @Test
    fun liveOverlap_retainsUserSeparator_andAddsOnlyMissingSequences() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
            projection.appendMessages(listOf("c" to message("echo", 2)))
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 3, "d"))
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c", "t", "end_turn"))
            val history =
                page(delta(1, 0, "a"), delta(2, 1, "b"), delta(3, 2, "c"), delta(4, 3, "d"), delta(5, 4, "e"), user(6, "echo"), end(7))
            repeat(2) { projection.mergeHistoryPage("c", history, true) }
            val rows = projection.observe("c").first()
            assertEquals(listOf("abc", "echo", "de"), rows.messages().map { it.content })
            assertEquals(listOf(0, 1, 2, 3, 4), rows.seqs())
            assertTrue(rows.messages().none { it.isStreaming })
        }

    @Test
    fun legacyWholeTurn_dedupesMatchingText_withoutSwallowingDistinctSuffixOrPrefix() {
        val legacy = ThreadItem.MessageItem(message("t", 0).copy(role = Role.Assistant, content = "b"))
        val segmented = reduced(delta(1, 0, "a"), delta(2, 1, "b"), tool(3), delta(4, 2, "c"), end(5))
        for (rows in listOf(segmented.mergeCachedRows(listOf(legacy)), listOf(legacy).mergeHistoryRows(segmented))) {
            assertEquals("abc", rows.messages().filter { it.role == Role.Assistant }.joinToString("") { it.content })
            assertEquals(rows.ids().distinct(), rows.ids())
            assertEquals(1, rows.messages().sumOf { it.content.count { char -> char == 'b' } })
        }
    }

    @Test
    fun legacyKnownFragments_withAMiddleHole_keepNewTextBetweenItsSequences() {
        val legacy = ThreadItem.MessageItem(message("t", 0).copy(role = Role.Assistant, content = "bd"))
        val page = reduced(delta(1, 1, "b"), delta(2, 2, "c"), tool(3), delta(4, 3, "d"), end(5))
        for (rows in listOf(page.mergeCachedRows(listOf(legacy)), listOf(legacy).mergeHistoryRows(page))) {
            assertEquals(listOf("bc", "Bash", "d"), rows.messages().map { it.content })
            assertEquals(listOf(1, 2, 3), rows.seqs())
            assertEquals(rows.ids().distinct(), rows.ids())
        }
    }

    @Test
    fun cacheObserve_reducedPages_fillMiddleAndSuffix_keepOfferHintsAndReconnect() =
        runTest(UnconfinedTestDispatcher()) {
            val hint = MessageAttachment(ATTACHMENT, "report.md", "text/markdown")
            val restored =
                reduced(
                    user(1, "u", ids = "[\"$ATTACHMENT\"]"),
                    delta(2, 0, "a"),
                    tool(4),
                    result(4),
                    delta(5, 2, "c"),
                    end(7),
                ).toMutableList()
            val first = restored[0] as ThreadItem.MessageItem
            restored[0] = first.copy(message = first.message.copy(attachments = listOf(hint)))
            val offer = ThreadItem.MessageItem(message("offer", 4).copy(role = Role.Assistant, attachments = listOf(hint)))
            restored.add(3, offer)
            val cache = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))
            assertTrue(cache.writeThread("host", "c", restored).isSuccess)
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
            runCurrent()
            live.value = reduced(user(1, "u", ids = "[\"$ATTACHMENT\"]"), delta(3, 1, "b"), tool(4), result(4), delta(6, 3, "d"), end(7))
            runCurrent()
            val merged = emissions.last()
            assertEquals(listOf("u", "t", "tool", "offer", "t#2"), merged.ids())
            assertEquals("abcd", merged.messages().filter { it.segment != null }.joinToString("") { it.content })
            assertEquals(listOf(hint), merged.messages().first().attachments)
            runCurrent()
            live.value = emptyList()
            runCurrent()
            assertEquals(merged.ids(), emissions.last().ids())
            assertEquals(merged.messages().map { it.content }, emissions.last().messages().map { it.content })
            assertEquals(merged.seqs(), emissions.last().seqs())
            runCurrent()
            live.value = reduced(delta(6, 3, "d"), end(7))
            runCurrent()
            assertEquals(merged.ids(), emissions.last().ids())
            assertEquals(merged.messages().map { it.content }, emissions.last().messages().map { it.content })
            assertEquals(merged.seqs(), emissions.last().seqs())
            val persisted = cache.readThread("host", "c")
            assertEquals(merged.ids(), persisted.ids())
            assertEquals(merged.messages().map { it.content }, persisted.messages().map { it.content })
            assertEquals(merged.seqs(), persisted.seqs())
            assertEquals(listOf(hint), persisted.messages().first().attachments)
            job.cancel()
        }

    @Test
    fun assistantPages_everyThreePageCutAndArrivalOrder_retainsToolSidesAndSettledText() =
        runTest {
            val all =
                listOf(delta(1, 0, "a"), delta(2, 1, "b"), tool(3), result(4), delta(5, 2, "c"), delta(6, 3, "d"), end(7))
                    .map { it.replace(Regex("10:00:[0-9]{2}Z"), "10:00:00Z") }
            for (first in 1 until all.size) {
                for (second in first + 1 until all.size) {
                    val slices = listOf(all.take(first), all.subList(first, second), all.drop(second))
                    for (order in permutations(listOf(0, 1, 2))) {
                        val projection = ThreadProjection()
                        for (index in order) projection.mergeHistoryPage("c", page(*slices[index].toTypedArray()), true)
                        val rows = projection.observe("c").first()
                        val label = "cuts $first/$second order $order"
                        assertEquals(label, listOf("ab", "Bash", "cd"), rows.messages().map { it.content })
                        assertEquals(label, listOf(0, 1, 2, 3), rows.seqs())
                        assertEquals(label, rows.ids().distinct(), rows.ids())
                        assertTrue(label, rows.messages().none { it.isStreaming })
                        projection.mergeHistoryPage("c", page(*all.toTypedArray()), true)
                        assertEquals(label, rows, projection.observe("c").first())
                    }
                }
            }
        }

    @Test
    fun disjointNewerHistory_usesLiveRowsInsideTheLogBounds() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(user(1, "older", 1)), true)
            projection.appendMessages(listOf("c" to message("live", 2)))
            projection.mergeHistoryPage("c", page(user(3, "newer", 3)), true)
            assertEquals(listOf("older", "live", "newer"), projection.observe("c").first().ids())
        }

    @Test
    fun missingToolRow_splitsHeldSegmentAtItsDaemonPosition() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(delta(1, 0, "before"), delta(3, 1, "after"), end(4)), true)
            projection.mergeHistoryPage("c", page(tool(2)), true)
            val rows = projection.observe("c").first()
            assertEquals(listOf("before", "Bash", "after"), rows.messages().map { it.content })
            assertEquals(listOf(0, 1), rows.seqs())
            assertTrue(rows.messages().none { it.isStreaming })
        }

    @Test
    fun hostileOrdinaryKey_cannotEvictHeldAssistantText() {
        val held = reduced(delta(1, 2, "keep"), end(2))
        val collision = listOf(ThreadItem.MessageItem(message("t#2", 2)))
        assertEquals(held, held.mergeCachedRows(collision))
        val ordinary = listOf(ThreadItem.MessageItem(message("t#2", 2)))
        val drawn = ordinary.mergeHistoryRows(held)
        assertEquals(listOf("t#2"), drawn.ids())
        assertEquals("t#2", drawn.messages().single().content)
    }

    @Test
    fun hostileSegmentKey_cannotEvictHeldAssistantText_onHistoryOrCacheRepeats() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t#2", 0, "keep"))
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t#2", 1, " tail"))
            val held = projection.observe("c").first()
            val collision = page(delta(1, 2, "older"), end(2))
            var cached = held
            repeat(3) {
                projection.mergeHistoryPage("c", collision, true)
                assertEquals(held, projection.observe("c").first())
                cached = cached.mergeCachedRows(reduceHistoryPage(collision.entries, true))
                assertEquals(held, cached)
                assertEquals(cached.ids().distinct(), cached.ids())
            }
        }

    @Test
    fun legacyOpenerAlias_cannotEvictAnotherHeldTurn() {
        val legacy = ThreadItem.MessageItem(message("t", 0).copy(role = Role.Assistant, content = "unmatched legacy"))
        val held = listOf(legacy) + reduced(delta(1, 0, "keep", turn = "t#0"))
        val incoming = reduced(delta(2, 0, "novel"))
        var cached = held
        var history = held
        repeat(3) {
            history = history.mergeHistoryRows(incoming)
            cached = cached.mergeCachedRows(incoming)
            assertEquals(held, history)
            assertEquals(held, cached)
            assertEquals(cached.ids().distinct(), cached.ids())
        }
    }

    @Test
    fun cacheObserve_reopen_keepsLeadingRowBeforeLiveOnlyRowsAndSharedNeighbour() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))
            val restored = reduced(user(1, "older", 10), user(3, "own", 5))
            assertTrue(cache.writeThread("host", "c", restored).isSuccess)
            val live = MutableStateFlow(reduced(tool(2), user(3, "own", 5)))
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live
                }
            val repository = CachingConversationRepository(delegate, cache, "host")
            repeat(2) {
                val emissions = mutableListOf<List<ThreadItem>>()
                val reader =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        repository.observeMessages("c").collect { emissions += it }
                    }
                runCurrent()
                assertEquals(listOf("older", "tool", "own"), emissions.last().ids())
                assertEquals(listOf("older", "own"), cache.readThread("host", "c").ids())
                reader.cancel()
                runCurrent()
            }
        }

    @Test
    fun filledCompactionDivider_keepsItsFallingEdgeOrder_whenBoundaryChangesItsTimestamp() =
        runTest {
            val compacting = entry(1, "compacting", """{"conversation_id":"c","active":true}""", 0)
            val inactive = entry(2, "compacting", """{"conversation_id":"c","active":false}""", 1)
            val boundary =
                entry(4, "compaction_boundary", """{"conversation_id":"c","pre_tokens":10,"post_tokens":5,"trigger":"auto"}""", 4)
            val divider = page(compacting, inactive, boundary)
            val middle = page(user(3, "middle", 4))
            for (pages in listOf(listOf(divider, middle), listOf(middle, divider))) {
                val projection = ThreadProjection()
                for (incoming in pages + pages) projection.mergeHistoryPage("c", incoming, true)
                val rows = projection.observe("c").first()
                assertEquals(2, rows.size)
                assertTrue(rows.first() is ThreadItem.CompactionBoundary)
                assertEquals(10L, (rows.first() as ThreadItem.CompactionBoundary).preTokens)
                assertEquals(listOf("middle"), rows.ids())
            }
        }

    @Test
    fun contextualCompactionDivider_usesDaemonOrder_inBothPageOrdersAndRepeats() =
        runTest {
            for (newerClock in listOf(0, 5)) {
                val compacting = entry(1, "compacting", """{"conversation_id":"c","active":true}""", 0)
                val failed = entry(2, "compacting", """{"conversation_id":"c","active":false,"compact_result":"failed"}""", 5)
                val older = page(compacting, failed)
                val newer = page(user(3, "newer", newerClock))
                for (pages in listOf(listOf(older, newer), listOf(newer, older))) {
                    val projection = ThreadProjection()
                    for (incoming in pages + pages) projection.mergeHistoryPage("c", incoming, true)
                    val rows = projection.observe("c").first()
                    assertEquals(2, rows.size)
                    assertTrue(rows.first() is ThreadItem.CompactionBoundary)
                    assertTrue((rows.first() as ThreadItem.CompactionBoundary).failed)
                    assertEquals(listOf("newer"), rows.ids())
                }
            }
        }

    @Test
    fun cacheObserve_doesNotRebaseAndResurrectDeliberatelyRemovedLiveRows() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))
            val live = MutableStateFlow(reduced(user(1, "kept"), user(2, "removed")))
            val delegate =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live
                }
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    CachingConversationRepository(delegate, cache, "host").observeMessages("c").collect { emissions += it }
                }
            runCurrent()
            live.value = reduced(user(1, "kept"), user(3, "newer"))
            runCurrent()
            assertEquals(listOf("kept", "newer"), emissions.last().ids())
            assertFalse(emissions.last().ids().contains("removed"))
            job.cancel()
        }

    private fun user(
        id: Int,
        key: String,
        at: Int = 0,
        ids: String = "[]",
    ) = entry(id, "send_message", """{"conversation_id":"c","message_id":"$key","text":"$key","attachment_ids":$ids}""", at)

    private fun delta(
        id: Int,
        seq: Int,
        text: String,
        turn: String = "t",
    ) = entry(id, "assistant_delta", """{"conversation_id":"c","turn_id":"$turn","seq":$seq,"text":"$text"}""")

    private fun tool(id: Int) =
        entry(id, "tool_use", """{"conversation_id":"c","turn_id":"t","tool_use_id":"tool","name":"Bash","input_summary":"ls"}""")

    private fun result(id: Int) =
        entry(id, "tool_result", """{"conversation_id":"c","turn_id":"t","tool_use_id":"tool","is_error":false,"result_summary":"ok"}""")

    private fun end(id: Int) = entry(id, "turn_end", """{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}""")

    private fun entry(
        id: Int,
        type: String,
        payload: String,
        at: Int = id,
    ) = """{"id":$id,"type":"$type","payload":$payload,"ts":"2026-10-01T10:00:%02dZ"}""".format(at)

    private fun page(vararg entries: String): HistoryPage =
        MobileJson
            .decodeFromString<HistoryPagePayloadDto>(
                """{"entries":[${entries.reversed().joinToString(",")}],"cursor":"","at_start":true}""",
            ).toHistoryPage()

    private fun reduced(vararg entries: String) = reduceHistoryPage(page(*entries).entries, true)

    private fun message(
        id: String,
        at: Int,
    ) = Message(id, "", Role.User, id, Instant.parse("2026-10-01T10:00:%02dZ".format(at)), false)

    private fun List<ThreadItem>.messages() = filterIsInstance<ThreadItem.MessageItem>().map { it.message }

    private fun List<ThreadItem>.ids() = messages().map { it.id }

    private fun List<ThreadItem>.seqs() = messages().flatMap { it.segment?.deltas.orEmpty() }.map { it.seq }

    private fun permutations(values: List<Int>): List<List<Int>> =
        if (values.isEmpty()) {
            listOf(emptyList())
        } else {
            values.flatMap { value ->
                permutations(
                    values - value,
                ).map { listOf(value) + it }
            }
        }

    private companion object {
        const val ATTACHMENT = "00000000-0000-4000-8000-000000000001"
    }
}
