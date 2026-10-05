package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantParentAttributionTest {
    @Test
    fun laneLocalSequences_interleavedMainAndTwoChildren_keepSeparateTextAndParents() =
        runTest {
            val script =
                listOf(
                    delta("main", 0, "M"),
                    delta("a", 0, "A", "agent-a"),
                    delta("a", 1, "a", "agent-a"),
                    delta("b", 0, "B", "agent-b"),
                    delta("main", 1, "m"),
                    delta("b", 1, "b", "agent-b"),
                )
            val projection = ThreadProjection()
            script.forEach(projection::applyAssistantDelta)
            val live = projection.observe("c").first().messages()
            val history = reduceHistoryPage(entries(script), true).messages()
            assertEquals(listOf("M", "Aa", "B", "m", "b"), live.map { it.content })
            assertEquals(listOf("", "agent-a", "agent-b", "", "agent-b"), live.map { it.parentToolUseId })
            assertEquals(live.shape(), history.shape())
            val parentless = reduceHistoryPage(entries(script.map { it.copy(parentToolUseId = "") }), true).messages()
            assertEquals(parentless.map { it.id to it.content }, live.map { it.id to it.content })
            script.forEach(projection::applyAssistantDelta)
            assertEquals(
                live.shape(),
                projection
                    .observe("c")
                    .first()
                    .messages()
                    .shape(),
            )
        }

    @Test
    fun knownAttribution_survivesAppendNewSegmentAndDuplicateWithMissingParent() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(delta("t", 0, "a", PARENT))
            projection.applyAssistantDelta(delta("t", 1, "b"))
            projection.appendMessages(listOf("c" to user("separator")))
            projection.applyAssistantDelta(delta("t", 2, "c"))
            projection.applyAssistantDelta(delta("t", 0, "a"))
            val rows = projection.observe("c").first().messages()
            assertEquals(listOf("t", "separator", "t#2"), rows.map { it.id })
            assertEquals(listOf("ab", "separator", "c"), rows.map { it.content })
            assertEquals(listOf(PARENT, PARENT), rows.filter { it.role == Role.Assistant }.map { it.parentToolUseId })
        }

    @Test
    fun replayCanFillMissingAttribution_withoutDuplicatingOrMovingHeldRows() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c" to user("before")))
            projection.applyAssistantDelta(delta("t", 0, "a"))
            projection.appendMessages(listOf("c" to user("after")))
            projection.applyAssistantDelta(delta("t", 0, "a", PARENT))
            projection.applyAssistantDelta(delta("t", 0, "a", "conflicting"))
            val rows = projection.observe("c").first().messages()
            assertEquals(listOf("before", "t", "after"), rows.map { it.id })
            assertEquals("a", rows[1].content)
            assertEquals(PARENT, rows[1].parentToolUseId)
        }

    @Test
    fun attributionAndDuplicateIdentity_areConfinedToTheConversation() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(delta("same", 0, "one", PARENT))
            projection.applyAssistantDelta(delta("same", 0, "two").copy(conversationId = "other"))
            projection.mergeHistoryPage("other", HistoryPage(entries(listOf(delta("same", 0, "two"))), "", true), true)
            assertEquals(
                PARENT,
                projection
                    .observe("c")
                    .first()
                    .messages()
                    .single()
                    .parentToolUseId,
            )
            assertEquals(
                "",
                projection
                    .observe("other")
                    .first()
                    .messages()
                    .single()
                    .parentToolUseId,
            )
            assertEquals(
                "two",
                projection
                    .observe("other")
                    .first()
                    .messages()
                    .single()
                    .content,
            )
        }

    @Test
    fun historyOverlapBothDirections_preservesHeldParentWhenItsCopyOmitsIt() =
        runTest {
            val complete = (0..4).map { delta("t", it, ('a' + it).toString(), PARENT) }
            for (held in listOf(complete.take(2), complete.drop(2), complete.filterIndexed { index, _ -> index % 2 == 1 })) {
                for (parentOnHeld in listOf(true, false)) {
                    val projection = ThreadProjection()
                    val first = if (parentOnHeld) held else held.map { it.copy(parentToolUseId = "") }
                    val second = if (parentOnHeld) complete.map { it.copy(parentToolUseId = "") } else complete
                    projection.mergeHistoryPage("c", HistoryPage(entries(first), "", false), true)
                    repeat(2) { projection.mergeHistoryPage("c", HistoryPage(entries(second), "", true), true) }
                    val rows = projection.observe("c").first().messages()
                    assertEquals(listOf("t"), rows.map { it.id })
                    assertEquals("abcde", rows.single().content)
                    assertEquals(PARENT, rows.single().parentToolUseId)
                    assertEquals(
                        (0..4).toList(),
                        rows
                            .single()
                            .segment
                            ?.deltas
                            ?.map { it.seq },
                    )
                }
            }
        }

    @Test
    fun olderPagePrepend_splitRejoinAndReplay_preserveHeldSeparatorsAndParents() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c" to user("before")))
            projection.applyAssistantDelta(delta("t", 1, "b", PARENT))
            projection.appendMessages(listOf("c" to user("middle")))
            projection.applyAssistantDelta(delta("t", 3, "d", PARENT))
            projection.appendMessages(listOf("c" to user("after")))
            val complete = (0..4).map { delta("t", it, ('a' + it).toString()) }
            projection.mergeHistoryPage("c", HistoryPage(entries(complete), "", true), true)
            val rows = projection.observe("c").first().messages()
            assertEquals(listOf("before", "t", "middle", "t#3", "after"), rows.map { it.id })
            assertEquals(listOf("abc", "de"), rows.filter { it.segment != null }.map { it.content })
            assertEquals(listOf(PARENT, PARENT), rows.filter { it.segment != null }.map { it.parentToolUseId })
            complete.forEach(projection::applyAssistantDelta)
            assertEquals(
                rows.shape(),
                projection
                    .observe("c")
                    .first()
                    .messages()
                    .shape(),
            )
        }

    @Test
    fun emptyAndOneEntryPages_keepAttributionAndIdentity() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", HistoryPage(emptyList(), "", false), true)
            val one = delta("t", 0, "a", PARENT)
            projection.mergeHistoryPage("c", HistoryPage(entries(listOf(one)), "", true), true)
            projection.mergeHistoryPage("c", HistoryPage(emptyList(), "", true), true)
            projection.applyAssistantDelta(one.copy(parentToolUseId = ""))
            assertEquals(
                listOf(Triple("t", "a", PARENT)),
                projection
                    .observe("c")
                    .first()
                    .messages()
                    .shape(),
            )
        }

    @Test
    fun twoLanesSharingParent_doNotShareSequenceOrMergeIdentity() {
        val one = reduceHistoryPage(entries(listOf(delta("a", 0, "A", PARENT))), true)
        val two = reduceHistoryPage(entries(listOf(delta("b", 0, "B", PARENT))), true)
        for (rows in listOf(one.mergeHistoryRows(two), two.mergeHistoryRows(one))) {
            assertEquals(setOf(Triple("a", "A", PARENT), Triple("b", "B", PARENT)), rows.messages().shape().toSet())
        }
    }

    @Test
    fun conflictingOlderOpener_retainsHeldParentThroughHistoryAndCacheReconstruction() {
        val held =
            listOf(ThreadItem.MessageItem(user("before")))
                .withAssistantDelta(delta("t", 1, "b", PARENT), TS)
                .withMessage(user("after"))
        val incoming = reduceHistoryPage(entries(listOf(delta("t", 0, "a", "incoming"))), true)
        for (cache in listOf(false, true)) {
            val merged = if (cache) held.mergeCachedRows(incoming) else held.mergeHistoryRows(incoming)
            assertEquals(listOf("before", "t", "after"), merged.messages().map { it.id })
            assertEquals(listOf("before", "ab", "after"), merged.messages().map { it.content })
            assertEquals(PARENT, merged.messages()[1].parentToolUseId)
            assertEquals(
                listOf(0, 1),
                merged
                    .messages()[1]
                    .segment
                    ?.deltas
                    ?.map { it.seq },
            )
            assertEquals(merged, if (cache) merged.mergeCachedRows(incoming) else merged.mergeHistoryRows(incoming))
        }
    }

    @Test
    fun conflictingOverlap_retainsHeldParentAndOrderAcrossBothMergePathsAndArrivalDirections() {
        val complete = (0..4).map { delta("t", it, ('a' + it).toString(), "incoming") }
        for (sequences in listOf(listOf(0, 1), listOf(3, 4), listOf(1, 3))) {
            var held: List<ThreadItem> = listOf(ThreadItem.MessageItem(user("before")))
            for (seq in sequences) {
                held = held.withAssistantDelta(delta("t", seq, ('a' + seq).toString(), PARENT), TS)
                if (sequences == listOf(1, 3) && seq == 1) held = held.withMessage(user("middle"))
            }
            held = held.withMessage(user("after"))
            val incoming = reduceHistoryPage(entries(complete), true)
            for (cache in listOf(false, true)) {
                val merged = if (cache) held.mergeCachedRows(incoming) else held.mergeHistoryRows(incoming)
                val baseline =
                    if (cache) {
                        held.withoutParents().mergeCachedRows(incoming.withoutParents())
                    } else {
                        held.withoutParents().mergeHistoryRows(incoming.withoutParents())
                    }
                assertEquals(baseline.messages().map { it.id to it.content }, merged.messages().map { it.id to it.content })
                assertEquals(held.messages().filter { it.role == Role.User }, merged.messages().filter { it.role == Role.User })
                val assistants = merged.messages().filter { it.role == Role.Assistant }
                assertEquals("abcde", assistants.joinToString("") { it.content })
                assertEquals(List(assistants.size) { PARENT }, assistants.map { it.parentToolUseId })
                assertEquals(
                    (0..4).toList(),
                    assistants.flatMap {
                        it.segment
                            ?.deltas
                            .orEmpty()
                            .map { it.seq }
                    },
                )
            }
        }
    }

    @Test
    fun conflictingLegacyReplacement_retainsHeldParentAndSlotAcrossBothMergePaths() {
        val held =
            listOf(ThreadItem.MessageItem(user("before")))
                .withAssistantDelta(delta("t", 0, "b", PARENT), TS)
                .withMessage(user("after"))
        // Only "b" is accounted for: the legacy whole-turn row cannot recover a delta record.
        val incoming = listOf(ThreadItem.MessageItem(Message("t", "", Role.Assistant, "abc", TS, false, parentToolUseId = "incoming")))
        for (cache in listOf(false, true)) {
            val merged = if (cache) held.mergeCachedRows(incoming) else held.mergeHistoryRows(incoming)
            assertEquals(listOf("before", "t", "after"), merged.messages().map { it.id })
            assertEquals(listOf("before", "abc", "after"), merged.messages().map { it.content })
            assertEquals(PARENT, merged.messages()[1].parentToolUseId)
            assertEquals(null, merged.messages()[1].segment)
            assertEquals(merged, if (cache) merged.mergeCachedRows(incoming) else merged.mergeHistoryRows(incoming))
        }
    }

    private fun List<ThreadItem>.withoutParents() =
        map { row ->
            if (row is ThreadItem.MessageItem) row.copy(message = row.message.copy(parentToolUseId = "")) else row
        }

    @Test
    fun collidingTurnKeys_doNotOverwriteHeldLaneAttributionOrGainAuthority() {
        val first = emptyList<ThreadItem>().withAssistantDelta(delta("a#1", 0, "X", "agent-x"), TS)
        val second = emptyList<ThreadItem>().withAssistantDelta(delta("a", 1, "A", "agent-a"), TS)
        assertEquals(first, first.mergeHistoryRows(second))
        val merged = second.mergeCachedRows(first).messages()
        val parentlessFirst = first.map { (it as ThreadItem.MessageItem).copy(message = it.message.copy(parentToolUseId = "")) }
        val parentlessSecond = second.map { (it as ThreadItem.MessageItem).copy(message = it.message.copy(parentToolUseId = "")) }
        val baseline = parentlessSecond.mergeCachedRows(parentlessFirst).messages()
        assertEquals(baseline.map { it.id to it.content }, merged.map { it.id to it.content })
        assertEquals(listOf("agent-x", "agent-a"), merged.map { it.parentToolUseId })
        assertEquals(merged.map { it.id }.distinct(), merged.map { it.id })
        val user = listOf(ThreadItem.MessageItem(user("a#1")))
        assertEquals(user, user.mergeHistoryRows(first))
        assertEquals("", user.messages().single().parentToolUseId)
    }

    private fun delta(
        turn: String,
        seq: Int,
        text: String,
        parent: String = "",
    ) = LiveSessionEvent.AssistantDelta("c", turn, seq, text, parent)

    private fun entries(events: List<LiveSessionEvent.AssistantDelta>): List<HistoryEntry> =
        events
            .mapIndexed { index, event ->
                HistoryEntry(
                    (index + 1).toLong(),
                    "assistant_delta",
                    buildJsonObject {
                        put("conversation_id", event.conversationId)
                        put("turn_id", event.turnId)
                        put("seq", event.seq)
                        put("text", event.text)
                        put("parent_tool_use_id", event.parentToolUseId)
                    },
                    TS,
                )
            }.reversed()

    private fun user(id: String) =
        Message(
            id,
            "",
            Role.User,
            id,
            if (id == "before") Instant.fromEpochMilliseconds(TS.toEpochMilliseconds() - 1000) else TS,
            false,
        )

    private fun List<ThreadItem>.messages() = filterIsInstance<ThreadItem.MessageItem>().map { it.message }

    private fun List<Message>.shape() = map { Triple(it.id, it.content, it.parentToolUseId) }

    private companion object {
        val TS = Instant.parse("2026-10-06T10:00:00Z")
        const val PARENT = " ../Agent <x> ${'$'}(ignored) "
    }
}
