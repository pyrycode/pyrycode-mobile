package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMessageIdentityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val previousSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test
    fun heldIdentityInvariant_lateLegacyCannotTakeOpeningSegmentId_onHistoryAndCache() {
        val opener = segment("t", 0, "novel", at = 2)
        val legacy = message("t", "unmatched legacy", at = 1)
        for (cache in listOf(false, true)) {
            val expected = listOf(legacy.copy(id = "t#0"), opener)
            var held = rows(opener)
            assertRows(listOf(opener), held)
            held = merge(held, rows(legacy), cache)
            assertRows(expected, held)
            for (page in listOf(rows(legacy), rows(opener), emptyList(), rows(legacy, opener))) {
                held = merge(held, page, cache)
                assertRows(expected, held)
            }
        }
    }

    @Test
    fun heldIdentityInvariant_legacyFirstKeepsBareId_andOpeningAliasSurvivesUnrelatedMerges() {
        val opener = segment("t", 0, "novel", at = 2)
        val legacy = message("t", "unmatched legacy", at = 1)
        val unrelated = message("other", "later", at = 3, role = Role.User)
        for (cache in listOf(false, true)) {
            var held = merge(rows(legacy), rows(opener), cache)
            val expected = listOf(legacy, opener.copy(id = "t#0"))
            assertRows(expected, held)
            held = merge(held, rows(unrelated), cache)
            assertRows(expected + unrelated, held)
            for (page in listOf(rows(opener), rows(legacy), emptyList(), rows(legacy, opener, unrelated))) {
                held = merge(held, page, cache)
                assertRows(expected + unrelated, held)
            }
        }
    }

    @Test
    fun uniqueIdentityInvariant_occupiedAlternativesStayWithOwners_andOriginalLegacyReplayMatches() {
        val opener = segment("t", 0, "novel", at = 2)
        val aliasOwner = message("t#0", "real alias-shaped id", at = 3, role = Role.User)
        val suffixOwner = message("t#0~1", "real suffix-shaped id", at = 4, role = Role.User)
        val legacy = message("t", "unmatched legacy", at = 1)
        for (cache in listOf(false, true)) {
            var held = rows(opener, aliasOwner, suffixOwner)
            val expected = listOf(legacy.copy(id = "t#0~2"), opener, aliasOwner, suffixOwner)
            held = merge(held, rows(legacy), cache)
            assertRows(expected, held)
            for (page in listOf(rows(legacy), rows(aliasOwner, suffixOwner), rows(opener), emptyList(), rows(legacy, opener))) {
                held = merge(held, page, cache)
                assertRows(expected, held)
            }
        }
    }

    @Test
    fun heldIdentityInvariant_existingSegmentSuffixIsNotCanonicalizedByUnrelatedInput() {
        val heldSegment = segment("t", 2, "held", at = 1).copy(id = "t#2~3", isStreaming = true)
        val other = message("other", "new", at = 2, role = Role.User)
        for (cache in listOf(false, true)) {
            var held = rows(heldSegment)
            for (page in listOf(rows(other), rows(segment("t", 2, "changed", at = 1)), emptyList(), rows(other))) {
                held = merge(held, page, cache)
                assertRows(listOf(heldSegment, other), held)
            }
        }
    }

    @Test
    fun placementInvariant_admittedSplitCollisionGetsUnusedSuffix_thenRetainsItOnReconstruction() {
        val keyOwner = message("t#1", "separator", at = 3, role = Role.User)
        val suffixOwner = message("t#1~1", "suffix owner", at = 4, role = Role.User)
        val original =
            segment("t", 0, "abc", at = 5).copy(
                segment = AssistantSegment("t", listOf(SegmentDelta(0, 1), SegmentDelta(1, 1), SegmentDelta(2, 1))),
            )
        val firstEvidence = rows(segment("t", 1, "changed", at = 2))
        val order = mapOf(keyOwner.row().mergeIdentity() to 3uL, firstEvidence.single().mergeIdentity() to 2uL)
        var held =
            rows(keyOwner, suffixOwner, original).mergeUnsignedHistoryRows(
                firstEvidence,
                order,
                firstEvidence.mapTo(mutableSetOf()) { it.mergeIdentity() },
            )
        val middle = segment("t", 1, "b", at = 5).copy(id = "t#1~2")
        val remainder = original.copy(content = "ac", segment = AssistantSegment("t", listOf(SegmentDelta(0, 1), SegmentDelta(2, 1))))
        val expected = listOf(middle, keyOwner, suffixOwner, remainder)
        assertRows(expected, held)
        val later = message("later", "later", at = 6, role = Role.User)
        held = held.mergeHistoryRows(rows(later))
        assertRows(expected + later, held)
        held = held.mergeUnsignedHistoryRows(firstEvidence, order)
        assertRows(expected + later, held)
        held = held.mergeCachedRows(rows(original))
        assertRows(expected + later, held)
    }

    @Test
    fun logicalIdentityInvariant_aliasedLegacyStillReconcilesLaterMatchingText_withoutDuplicatingIt() {
        val opener = segment("t", 0, "a", at = 2)
        val legacy = message("t", "bc", at = 1)
        for (cache in listOf(false, true)) {
            var held = merge(rows(opener), rows(legacy), cache)
            assertRows(listOf(legacy.copy(id = "t#0"), opener), held)
            val matched =
                legacy.copy(
                    id = "t#1",
                    segment = AssistantSegment("t", listOf(SegmentDelta(1, 1), SegmentDelta(2, 1))),
                )
            val expected = listOf(matched, opener)
            val fragments = rows(segment("t", 1, "b", at = 3), segment("t", 2, "c", at = 4))
            for (page in listOf(fragments, rows(legacy), fragments, emptyList())) {
                held = merge(held, page, cache)
                assertRows(expected, held)
            }
        }
    }

    @Test
    fun reconnectInvariant_fileRestoreRetainsOrdinaryReplayIdentity_andHistoryAfterReconnect() =
        runTest {
            val root = tmp.newFolder()
            val cache = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler))
            val opener = segment("t", 0, "novel", at = 2)
            val legacy = message("t", "unmatched legacy", at = 1)
            for (legacyFirst in listOf(false, true)) {
                val first = if (legacyFirst) legacy else opener
                val second = if (legacyFirst) opener else legacy
                val expected = if (legacyFirst) listOf(legacy, opener.copy(id = "t#0")) else listOf(legacy.copy(id = "t#0"), opener)
                val merged = rows(first).mergeCachedRows(rows(second))
                assertRows(expected, merged)
                assertEquals(true, cache.writeThread("host", "c", merged).isSuccess)
                val restored = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)).readThread("host", "c")
                assertRows(expected, restored)
                val reconnect = emptyList<ThreadItem>().mergeCachedRows(restored)
                assertRows(expected, reconnect)
                for (cacheLane in listOf(false, true)) {
                    var receiving = reconnect
                    for (page in listOf(rows(legacy), rows(opener), emptyList(), rows(legacy, opener))) {
                        receiving = merge(receiving, page, cacheLane)
                        assertRows(expected, receiving)
                        assertEquals(true, cache.writeThread("host", "c", receiving).isSuccess)
                        receiving =
                            emptyList<ThreadItem>().mergeCachedRows(
                                FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)).readThread("host", "c"),
                            )
                        assertRows(expected, receiving)
                    }
                }
            }
        }

    @Test
    fun reconciliationInvariant_overlapAtStartMiddleAndEnd_preservesHeldTextStateAndSuffixOwnership() {
        val original =
            segment("t", 2, "abc", at = 2).copy(
                id = "t#2~1",
                isStreaming = true,
                segment = AssistantSegment("t", listOf(SegmentDelta(2, 1), SegmentDelta(3, 1), SegmentDelta(4, 1))),
            )
        val other = message("other", "later", at = 3, role = Role.User)
        for (cache in listOf(false, true)) {
            for (seqs in listOf(listOf(2, 3, 4), listOf(4, 3, 2), listOf(3, 2, 4))) {
                var held = rows(original)
                // Existing placement uses the overlap as the separator's neighbour, splitting this row.
                val cut = seqs.first() - 1
                val left =
                    original.copy(
                        content = original.content.take(cut),
                        segment = original.segment?.copy(deltas = original.segment.deltas.take(cut)),
                    )
                val right =
                    original.copy(
                        id = "t#${seqs.first() + 1}",
                        content = original.content.drop(cut),
                        segment = original.segment?.copy(deltas = original.segment.deltas.drop(cut)),
                    )
                val expected = listOf(left, other) + listOf(right).filter { it.content.isNotEmpty() }
                for (seq in seqs) {
                    held = merge(held, rows(segment("t", seq, "changed", at = 1), other), cache)
                    assertRows(expected, held)
                    held = merge(held, emptyList(), cache)
                    assertRows(expected, held)
                }
            }
        }
    }

    @Test
    fun receivingListInvariant_projectionAndCacheObserver_keepIdentityAcrossReconnectAndOriginalHistoryReplay() =
        runTest(UnconfinedTestDispatcher()) {
            val opener = segment("t", 0, "novel", at = 2)
            val legacy = message("t", "unmatched legacy", at = 1)
            val legacyPage =
                historyPage(1, "message", """{"conversation_id":"c","message_id":"t","role":"assistant","text":"unmatched legacy"}""")
            val openerPage = historyPage(2, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":0,"text":"novel"}""")
            for (legacyFirst in listOf(false, true)) {
                val root = tmp.newFolder()
                val cache = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler))
                val first = if (legacyFirst) legacy else opener
                val expected = if (legacyFirst) listOf(legacy, opener.copy(id = "t#0")) else listOf(legacy.copy(id = "t#0"), opener)
                val projection = ThreadProjection()
                projection.appendMessages(listOf("c" to first))
                val live = MutableStateFlow(projection.observe("c").first())
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
                assertRows(listOf(first), emissions.last())
                projection.mergeHistoryPage("c", if (legacyFirst) openerPage else legacyPage, true)
                live.value = projection.observe("c").first()
                runCurrent()
                assertRows(expected, emissions.last())
                live.value = emptyList()
                runCurrent()
                assertRows(expected, emissions.last())
                val restored = FileConversationCache(root, UnconfinedTestDispatcher(testScheduler)).readThread("host", "c")
                assertRows(expected, restored)
                val reconnected = ThreadProjection()
                reconnected.appendMessages(restored.filterIsInstance<ThreadItem.MessageItem>().map { "c" to it.message })
                for (page in listOf(legacyPage, openerPage, historyPage(), openerPage, legacyPage)) {
                    reconnected.mergeHistoryPage("c", page, true)
                    live.value = reconnected.observe("c").first()
                    runCurrent()
                    assertRows(expected, live.value)
                    assertRows(expected, emissions.last())
                }
                job.cancel()
            }
        }

    private fun historyPage(
        id: Int = 0,
        type: String = "",
        payload: String = "{}",
    ): HistoryPage {
        val entry =
            if (id == 0) {
                ""
            } else {
                """{"id":$id,"type":"$type","payload":$payload,"ts":"2026-10-01T10:00:%02dZ"}""".format(id)
            }
        val entries =
            if (type == "assistant_delta") {
                """{"id":3,"type":"turn_end","payload":{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"},"ts":"2026-10-01T10:00:03Z"},$entry"""
            } else {
                entry
            }
        return MobileJson
            .decodeFromString<HistoryPagePayloadDto>(
                """{"entries":[$entries],"cursor":"","at_start":true}""",
            ).toHistoryPage()
    }

    private fun merge(
        held: List<ThreadItem>,
        incoming: List<ThreadItem>,
        cache: Boolean,
    ) = if (cache) held.mergeCachedRows(incoming) else held.mergeHistoryRows(incoming)

    private fun assertRows(
        expected: List<Message>,
        actual: List<ThreadItem>,
    ) {
        val messages = actual.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
        assertEquals("logical row multiplicity", expected.size, actual.size)
        assertEquals("ids and ownership", expected.map { it.id }, messages.map { it.id })
        assertEquals("unique ids", messages.size, messages.map { it.id }.distinct().size)
        assertEquals("text/order", expected.map { it.content }, messages.map { it.content })
        assertEquals("state", expected.map { it.isStreaming }, messages.map { it.isStreaming })
        assertEquals("logical segments", expected.map { it.segment }, messages.map { it.segment })
        assertEquals("roles", expected.map { it.role }, messages.map { it.role })
        assertEquals("timestamps", expected.map { it.timestamp }, messages.map { it.timestamp })
    }

    private fun message(
        id: String,
        text: String,
        at: Int,
        role: Role = Role.Assistant,
    ) = Message(id, "", role, text, Instant.parse("2026-10-01T10:00:%02dZ".format(at)), false)

    private fun segment(
        turn: String,
        seq: Int,
        text: String,
        at: Int,
    ) = message(
        if (seq ==
            0
        ) {
            turn
        } else {
            "$turn#$seq"
        },
        text,
        at,
    ).copy(segment = AssistantSegment(turn, listOf(SegmentDelta(seq, text.length))))

    private fun Message.row() = ThreadItem.MessageItem(this)

    private fun rows(vararg messages: Message): List<ThreadItem> = messages.map { it.row() }
}
