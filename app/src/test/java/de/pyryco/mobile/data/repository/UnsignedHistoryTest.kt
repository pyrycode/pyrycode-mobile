package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsignedHistoryTest {
    private val boundary = Long.MAX_VALUE.toULong()
    private val ids = listOf(1uL, boundary - 1u, boundary, boundary + 1u, ULong.MAX_VALUE)
    private val at = Instant.parse("2026-10-07T00:00:00Z")

    @Test
    fun compatibility_usesExactPositiveSignedIdentityOrNoClaim() {
        for (id in ids) {
            val unsigned = user(id)
            assertEquals(id, unsigned.unsignedId)
            if (id <= boundary) {
                val signed = HistoryEntry(id.toLong(), unsigned.type, unsigned.payload, unsigned.timestamp)
                assertEquals(unsigned, signed)
                assertEquals(id.toLong(), unsigned.id)
            } else {
                assertNull(unsigned.id)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { user(0u) }
        assertThrows(IllegalArgumentException::class.java) { HistoryEntry(-1L, "future", MobileJson.parseToJsonElement("{}"), at) }
    }

    @Test
    fun unsignedOrder_isStableForEveryDisjointPageArrivalOrderAndReplay() =
        runTest {
            for (arrival in permutations(ids)) {
                val projection = ThreadProjection()
                for (id in arrival) {
                    projection.mergeHistoryPage(
                        "c",
                        page(user(id, timestamp = Instant.fromEpochSeconds((ids.size - ids.indexOf(id)).toLong()))),
                        true,
                    )
                    val expected = ids.filter { it in arrival.take(arrival.indexOf(id) + 1) }
                    assertEquals(expected.map(::name), projection.observe("c").first().names())
                }
                val before = projection.observeSnapshot("c").first()
                projection.mergeHistoryPage("c", page(*ids.map { user(it) }.toTypedArray()), true)
                projection.mergeHistoryPage("c", page(), true)
                assertEquals(before, projection.observeSnapshot("c").first())
                assertEquals(ids.toSet(), before.unsignedHistoryOrder.values.toSet())
                assertEquals(setOf(1L, Long.MAX_VALUE - 1, Long.MAX_VALUE), before.historyOrder.values.toSet())
            }
        }

    @Test
    fun overlap_preservesHeldRowsOnBothSidesForStartMiddleAndEndPages() =
        runTest {
            val entries = ids.map { user(it) }
            for (heldIndex in entries.indices) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage("c", page(entries[heldIndex]), true)
                val held = projection.observe("c").first().single()
                val pages = listOf(entries.take(3), entries.drop(1).take(3), entries.takeLast(3))
                for (incoming in pages + pages.reversed()) {
                    projection.mergeHistoryPage("c", page(*incoming.toTypedArray()), true)
                }
                val rows = projection.observe("c").first()
                assertEquals(ids.map(::name), rows.names())
                assertSame(held, rows[heldIndex])
                assertEquals(rows.names().distinct(), rows.names())
            }
        }

    @Test
    fun foldedDeltas_keepUnsignedClaimsAndOneSequenceAcrossOverlap() =
        runTest {
            val entries =
                listOf(delta(boundary - 1u, 0, "a"), delta(boundary, 1, "b"), user(boundary + 1u), delta(ULong.MAX_VALUE - 1u, 2, "c"))
            val reduced = reduceOrderedHistoryPage(page(*entries.toTypedArray()).entries, true)
            assertEquals(setOf(boundary - 1u), reduced.unsignedClaims[listOf("delta", "t", 0)])
            assertEquals(setOf(boundary), reduced.unsignedClaims[listOf("delta", "t", 1)])
            assertEquals(setOf(ULong.MAX_VALUE - 1u), reduced.unsignedClaims[listOf("delta", "t", 2)])
            assertFalse(reduced.order.containsKey(listOf("delta", "t", 2)))
            assertFalse(reduced.claims.containsKey(listOf("delta", "t", 2)))
            for (incoming in listOf(entries, entries.reversed())) {
                val projection = ThreadProjection()
                projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
                // Establish the held live delta's durable slot before surrounding pages can join it.
                projection.mergeHistoryPage("c", page(entries[1]), true)
                for (entry in incoming) projection.mergeHistoryPage("c", page(entry), true)
                repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                val snapshot = projection.observeSnapshot("c").first()
                val messages = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
                assertEquals(listOf("ab", name(boundary + 1u), "c"), messages.map { it.content })
                assertEquals(
                    listOf(0, 1, 2),
                    messages.flatMap {
                        it.segment
                            ?.deltas
                            .orEmpty()
                            .map { it.seq }
                    },
                )
                assertEquals(ULong.MAX_VALUE - 1u, snapshot.unsignedHistoryOrder[listOf("delta", "t", 2)])
                assertFalse(snapshot.historyOrder.containsKey(listOf("delta", "t", 2)))
            }
        }

    @Test
    fun lateDurableEvidence_doesNotStrandLiveDeltaBeyondItsHistorySeparator() =
        runTest {
            for (positions in listOf(listOf(1uL, 2uL, 3uL, 4uL), listOf(boundary - 1u, boundary, boundary + 1u, ULong.MAX_VALUE))) {
                val projection = ThreadProjection()
                projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
                val entries =
                    listOf(delta(positions[0], 0, "a"), delta(positions[1], 1, "b"), user(positions[2]), delta(positions[3], 2, "c"))
                for (entry in entries.reversed()) {
                    projection.mergeHistoryPage("c", page(entry), true)
                    if (entry == entries[1]) assertEquals(listOf("b", name(positions[2]), "c"), projection.observe("c").first().texts())
                }
                repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                val rows = projection.observe("c").first()
                assertEquals(listOf("ab", name(positions[2]), "c"), rows.texts())
                assertEquals(listOf(0, 1, 2), rows.seqs())
            }
        }

    @Test
    fun firstEvidenceInvariant_pageCutsAndPermutationsConverge_withoutMovingRetainedAtoms() =
        runTest {
            for (positions in listOf(listOf(1uL, 2uL, 3uL, 4uL), listOf(boundary - 1u, boundary, boundary + 1u, ULong.MAX_VALUE))) {
                val entries =
                    listOf(delta(positions[0], 0, "a"), delta(positions[1], 1, "incoming"), user(positions[2]), delta(positions[3], 2, "c"))
                for (cuts in 0 until 8) {
                    val pages = mutableListOf<MutableList<HistoryEntry>>(mutableListOf())
                    entries.forEachIndexed { index, entry ->
                        if (index > 0 && cuts and (1 shl (index - 1)) != 0) pages.add(mutableListOf())
                        pages.last().add(entry)
                    }
                    for (arrival in permutations(pages.indices.toList())) {
                        for (folded in listOf(false, true)) {
                            val projection = ThreadProjection()
                            val seqs = if (folded) listOf(0, 1, 2) else listOf(1)
                            for (seq in seqs) {
                                projection.applyAssistantDelta(
                                    LiveSessionEvent.AssistantDelta("c", "t", seq, "abc"[seq].toString()),
                                )
                            }
                            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c", "t", "end_turn"))
                            val label = "positions=$positions cuts=$cuts arrival=$arrival folded=$folded"
                            for (index in arrival) {
                                val before = projection.observeSnapshot("c").first()
                                val incoming = page(*pages[index].toTypedArray())
                                projection.mergeHistoryPage("c", incoming, true)
                                assertPlacementInvariants(label, before, projection.observeSnapshot("c").first(), incoming)
                            }
                            val beforeReplay = projection.observeSnapshot("c").first()
                            assertEquals(label, listOf("ab", name(positions[2]), "c"), beforeReplay.rows.texts())
                            assertEquals(label, listOf(0, 1, 2), beforeReplay.rows.seqs())
                            assertTrue(label, beforeReplay.rows.filterIsInstance<ThreadItem.MessageItem>().none { it.message.isStreaming })
                            repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                            projection.mergeHistoryPage("c", page(), true)
                            val afterReplay = projection.observeSnapshot("c").first()
                            assertEquals(label, beforeReplay.rows, afterReplay.rows)
                            assertEquals(label, beforeReplay.unsignedHistoryOrder, afterReplay.unsignedHistoryOrder)
                        }
                    }
                }
            }
        }

    @Test
    fun firstEvidenceInvariant_extractsMiddleOfFoldedSegment_andLeavesUnevidencedAtomsAnchored() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(user(3u), delta(4u, 3, "d")), true)
            val held =
                listOf<ThreadItem>()
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 0, "a"), at)
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 1, "b"), at)
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 2, "c"), at)
            projection.appendMessages(
                held.filterIsInstance<ThreadItem.MessageItem>().map {
                    "c" to
                        it.message.copy(isStreaming = false, parentToolUseId = "parent")
                },
            )
            val incoming = page(delta(2u, 1, "changed", turn = "late"))
            val before = projection.observeSnapshot("c").first()
            projection.mergeHistoryPage("c", incoming, true)
            val after = projection.observeSnapshot("c").first()
            assertPlacementInvariants("folded middle", before, after, incoming)
            assertEquals(listOf("b", name(3u), "d", "ac"), after.rows.texts())
            val late =
                after.rows
                    .filterIsInstance<ThreadItem.MessageItem>()
                    .map { it.message }
                    .filter { it.segment?.turnId == "late" }
            assertTrue(late.all { !it.isStreaming && it.parentToolUseId == "parent" && it.timestamp == at })
            assertEquals(
                listOf(1, 0, 2),
                late.flatMap {
                    it.segment
                        ?.deltas
                        .orEmpty()
                        .map { it.seq }
                },
            )
            assertEquals(setOf(listOf("delta", "late", 1)), after.unsignedHistoryOrder.keys - before.unsignedHistoryOrder.keys)
        }

    @Test
    fun firstClaimInvariant_overlappingDifferentTextAndLaterConflictingClaimsCannotRepairTwice() =
        runTest {
            val entries = listOf(delta(1u, 0, "a"), delta(2u, 1, "b"), user(3u), delta(4u, 2, "c"))
            for (overlap in listOf(entries.take(3), entries.drop(1), entries)) {
                val projection = ThreadProjection()
                projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "held"))
                projection.mergeHistoryPage("c", page(entries[3], entries[2]), true)
                val before = projection.observeSnapshot("c").first()
                val incoming = page(*overlap.toTypedArray())
                projection.mergeHistoryPage("c", incoming, true)
                assertPlacementInvariants("overlap", before, projection.observeSnapshot("c").first(), incoming)
                projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true)
                val established = projection.observeSnapshot("c").first()
                assertEquals(listOf("aheld", name(3u), "c"), established.rows.texts())
                val conflict = page(delta(ULong.MAX_VALUE, 1, "replacement"), entries[0], entries[2], entries[3])
                repeat(2) {
                    projection.mergeHistoryPage("c", conflict, true)
                    val after = projection.observeSnapshot("c").first()
                    assertPlacementInvariants("conflicting replay", established, after, conflict)
                    assertEquals(established.rows, after.rows)
                    assertEquals(established.unsignedHistoryOrder, after.unsignedHistoryOrder)
                }
                projection.mergeHistoryPage("c", page(entries[1], entries[1]), true)
                projection.mergeHistoryPage("c", page(), true)
                assertEquals(established.rows, projection.observe("c").first())
            }
        }

    @Test
    fun scopeInvariant_liveReplayAndProjectionReplacementRequireTheirOwnFirstDurableEvidence() =
        runTest {
            val entries = listOf(delta(1u, 0, "a"), delta(2u, 1, "b"), user(3u), delta(4u, 2, "c"))
            val hostA = ThreadProjection()
            hostA.mergeHistoryPage("c", page(*entries.toTypedArray()), true)
            // An identical turn/sequence in another conversation and another host starts provisional.
            for ((projection, conversation) in listOf(hostA to "other", ThreadProjection() to "c")) {
                projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta(conversation, "t", 1, "b"))
                projection.mergeHistoryPage(conversation, page(entries[3], entries[2]), true)
                val provisional = projection.observeSnapshot(conversation).first()
                repeat(2) { projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta(conversation, "t", 1, "replayed")) }
                assertEquals(provisional, projection.observeSnapshot(conversation).first())
                assertFalse(provisional.unsignedHistoryOrder.containsKey(listOf("delta", "t", 1)))
                projection.mergeHistoryPage(conversation, page(entries[1]), true)
                assertEquals(listOf("b", name(3u), "c"), projection.observe(conversation).first().texts())
                projection.mergeHistoryPage(conversation, page(entries[0]), true)
                assertEquals(listOf("ab", name(3u), "c"), projection.observe(conversation).first().texts())
            }
            assertEquals(listOf("ab", name(3u), "c"), hostA.observe("c").first().texts())
            hostA.remove("c")
            assertTrue(
                hostA
                    .observeSnapshot("c")
                    .first()
                    .unsignedHistoryOrder
                    .isEmpty(),
            )
            for (reloaded in listOf(hostA, ThreadProjection())) {
                reloaded.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
                assertTrue(
                    reloaded
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                for (entry in entries.reversed()) reloaded.mergeHistoryPage("c", page(entry), true)
                repeat(2) { reloaded.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                assertEquals(listOf("ab", name(3u), "c"), reloaded.observe("c").first().texts())
                assertEquals(listOf(0, 1, 2), reloaded.observe("c").first().seqs())
            }
        }

    @Test
    fun placementPermissionInvariant_orderMapAloneCannotMoveHeldDelta() =
        runTest {
            val projection = ThreadProjection()
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
            projection.mergeHistoryPage("c", page(user(3u), delta(4u, 2, "c")), true)
            val held = projection.observeSnapshot("c").first()
            val late = reduceOrderedHistoryPage(page(delta(2u, 1, "changed")).entries, true)
            val order = late.unsignedOrder + held.unsignedHistoryOrder
            assertEquals(listOf(name(3u), "bc"), held.rows.texts())
            assertEquals(held.rows, held.rows.mergeUnsignedHistoryRows(late.rows, order))
            assertEquals(held.rows, held.rows.mergeOrderedHistoryRows(late.rows, order.mapValues { it.value.toLong() }))
            assertEquals(held.rows, held.rows.mergeUnsignedCachedRows(late.rows, order))
        }

    @Test
    fun heldContentInvariant_repairedAtomSurvivesACollidingRendererKey() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(user(3u, "late#1")), true)
            val folded =
                listOf<ThreadItem>()
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 0, "a"), at)
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 1, "b"), at)
                    .withAssistantDelta(LiveSessionEvent.AssistantDelta("c", "late", 2, "c"), at)
            projection.appendMessages(folded.filterIsInstance<ThreadItem.MessageItem>().map { "c" to it.message })
            val before = projection.observeSnapshot("c").first()
            val incoming = page(delta(2u, 1, "changed", turn = "late"))
            repeat(2) { projection.mergeHistoryPage("c", incoming, true) }
            val after = projection.observeSnapshot("c").first()
            assertPlacementInvariants("renderer collision", before, after, incoming)
            assertEquals(listOf("b", "late#1", "ac"), after.rows.texts())
            assertEquals(listOf(1, 0, 2), after.rows.seqs())
        }

    @Test
    fun scopeInvariant_replacementBetweenAnyTwoPagesReloadsWithoutBorrowingClaims() =
        runTest {
            val entries = listOf(delta(1u, 0, "a"), delta(2u, 1, "b"), user(3u), delta(4u, 2, "c"))
            for (cut in 0..entries.size) {
                val original = ThreadProjection()
                original.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "b"))
                for (entry in entries.reversed().take(cut)) original.mergeHistoryPage("c", page(entry), true)
                val held = original.observeSnapshot("c").first()
                val replacement = ThreadProjection()
                replacement.appendMessages(held.rows.filterIsInstance<ThreadItem.MessageItem>().map { "c" to it.message })
                assertTrue(
                    replacement
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                replacement.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 1, "replayed"))
                assertTrue(
                    replacement
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                for (entry in entries.reversed()) {
                    val before = replacement.observeSnapshot("c").first()
                    val incoming = page(entry)
                    replacement.mergeHistoryPage("c", incoming, true)
                    assertPlacementInvariants("replacement after $cut pages", before, replacement.observeSnapshot("c").first(), incoming)
                }
                repeat(2) { replacement.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
                assertEquals(listOf("ab", name(3u), "c"), replacement.observe("c").first().texts())
                assertEquals(listOf(0, 1, 2), replacement.observe("c").first().seqs())
            }
        }

    @Test
    fun duplicateWithinPage_producesOneLogicalDeltaAndClaim() {
        val entry = delta(ULong.MAX_VALUE, 0, "a")
        val reduced = reduceOrderedHistoryPage(listOf(entry, entry), true)
        assertEquals(1, reduced.rows.size)
        assertEquals("a", (reduced.rows.single() as ThreadItem.MessageItem).message.content)
        assertEquals(mapOf(listOf("delta", "t", 0) to setOf(ULong.MAX_VALUE)), reduced.unsignedClaims)
        assertTrue(reduced.order.isEmpty())
        assertTrue(reduced.claims.isEmpty())
    }

    @Test
    fun compactionIdentityTransfer_preservesUnsignedFallingEdgeOrder() =
        runTest {
            val rise = entry(boundary, "compacting", """{"conversation_id":"c","active":true}""")
            val fall = entry(boundary + 1u, "compacting", """{"conversation_id":"c","active":false}""")
            val payload = """{"conversation_id":"c","pre_tokens":10,"post_tokens":5,"trigger":"auto"}"""
            val fill = entry(ULong.MAX_VALUE, "compaction_boundary", payload)
            val reduced = reduceOrderedHistoryPage(page(rise, fall, fill).entries, true)
            val identity = reduced.rows.single().mergeIdentity()
            assertEquals(boundary + 1u, reduced.unsignedOrder[identity])
            assertEquals(setOf(boundary + 1u, ULong.MAX_VALUE), reduced.unsignedClaims[identity])
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", page(rise, fall), true)
            projection.applyCompactionBoundary(
                de.pyryco.mobile.data.network
                    .Envelope(1L, "compaction_boundary", at.toString(), fill.payload),
            )
            val snapshot = projection.observeSnapshot("c").first()
            assertEquals(boundary + 1u, snapshot.unsignedHistoryOrder[snapshot.rows.single().mergeIdentity()])
            assertTrue(snapshot.historyOrder.isEmpty())
        }

    @Test
    fun identityScope_survivesReconnectWithoutBorrowingClaimsFromLiveRows() =
        runTest {
            for (id in ids) {
                val hostA = ThreadProjection()
                val hostB = ThreadProjection()
                hostA.mergeHistoryPage("c", page(user(id, "same-row")), true)
                hostA.mergeHistoryPage("other", page(user(1u, "same-row")), true)
                hostB.mergeHistoryPage("c", page(user(2u, "same-row")), true)
                assertEquals(
                    listOf(id),
                    hostA
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                assertEquals(
                    listOf(1uL),
                    hostA
                        .observeSnapshot("other")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                assertEquals(
                    listOf(2uL),
                    hostB
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder.values
                        .toList(),
                )
                hostA.remove("c")
                assertTrue(
                    hostA
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                val reconnected = ThreadProjection()
                reconnected.appendMessages(listOf("c" to Message("same-row", "s", Role.User, "held", at, false)))
                assertTrue(
                    reconnected
                        .observeSnapshot("c")
                        .first()
                        .unsignedHistoryOrder
                        .isEmpty(),
                )
                repeat(2) { reconnected.mergeHistoryPage("c", page(user(id, "same-row")), true) }
                val snapshot = reconnected.observeSnapshot("c").first()
                assertEquals(listOf("same-row"), snapshot.rows.names())
                assertEquals("held", (snapshot.rows.single() as ThreadItem.MessageItem).message.content)
                assertEquals(listOf(id), snapshot.unsignedHistoryOrder.values.toList())
            }
        }

    @Test
    fun signedCoverage_declinesUpperRangeTerminalAndMixedPagesWithoutFalseCompleteness() {
        for (previous in listOf(HistoryCoverage(), HistoryCoverage().received(page(user(1u)).copy(atStart = true)))) {
            for (entries in listOf(listOf(user(ULong.MAX_VALUE)), listOf(user(2u), user(boundary + 1u)))) {
                val incoming = page(*entries.toTypedArray()).copy(cursor = "upper", atStart = true)
                val incomplete = previous.received(incoming, newest = true, target = 1L)
                assertTrue(incomplete.unknown)
                assertTrue(incomplete.unsignedIncomplete)
                assertTrue(incomplete.unsignedSpans.any { span -> entries.any { it.unsignedId in span.first..span.last } })
                assertTrue(incomplete.received(page(user(2u)).copy(atStart = true)).unknown)
            }
        }
        val previous = HistoryCoverage(unknown = true).received(page(user(1u)))
        val lower = previous.received(page(user(2u)).copy(atStart = true))
        assertEquals(listOf(HistorySpan(1L, 2L)), lower.spans)
        assertFalse(lower.unknown)
    }

    @Test
    fun signedMergeEntryPoint_retainsLowerRangeOrdering() {
        val older = reduceOrderedHistoryPage(page(user(1u)).entries, true)
        val newer = reduceOrderedHistoryPage(page(user(3u)).entries, true)
        val middle = reduceOrderedHistoryPage(page(user(2u)).entries, true)
        val held = older.rows + newer.rows
        assertEquals(
            listOf(name(1u), name(2u), name(3u)),
            held
                .mergeOrderedHistoryRows(
                    middle.rows,
                    older.order + middle.order + newer.order,
                ).names(),
        )
    }

    @Test
    fun collidingRendererKeys_doNotInventDeltaClaimsOrLoseUnsignedToolOrder() =
        runTest {
            val entries =
                listOf(
                    delta(boundary, 0, "a"),
                    entry(
                        boundary + 1u,
                        "tool_use",
                        """
                        {"conversation_id":"c","turn_id":"t","tool_use_id":"tool","name":"Bash","input_summary":"ls"}
                        """.trimIndent(),
                    ),
                    delta(ULong.MAX_VALUE - 1u, 1, "b"),
                    user(ULong.MAX_VALUE, "t#1"),
                )
            val projection = ThreadProjection()
            // Splitting held text at a later tool row must repair its colliding renderer key.
            projection.mergeHistoryPage("c", page(entries[0], entries[2], entries[3]), true)
            repeat(2) { projection.mergeHistoryPage("c", page(*entries.toTypedArray()), true) }
            val snapshot = projection.observeSnapshot("c").first()
            val messages = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
            assertEquals(listOf("a", "Bash", "b", "t#1"), messages.map { it.content })
            assertEquals(messages.map { it.id }.distinct(), messages.map { it.id })
            assertEquals(ULong.MAX_VALUE - 1u, snapshot.unsignedHistoryOrder[listOf("delta", "t", 1)])
            assertEquals(boundary + 1u, snapshot.unsignedHistoryOrder[listOf("message", "tool")])
            assertEquals(ULong.MAX_VALUE, snapshot.unsignedHistoryOrder[listOf("message", "t#1")])
            assertEquals(setOf(Long.MAX_VALUE), snapshot.historyOrder.values.toSet())
        }

    @Test
    fun unrecognizedHistoryRows_useExactUnsignedKeyAndDeduplicateReplay() =
        runTest {
            val payload = """{"conversation_id":"c","site":"undecodable","message_type":"","raw":"inert","truncated":false}"""
            val entry = entry(ULong.MAX_VALUE, "unrecognized_message", payload)
            val projection = ThreadProjection()
            repeat(2) { projection.mergeHistoryPage("c", page(entry), true) }
            val rows = projection.observe("c").first()
            assertEquals("history-18446744073709551615", (rows.single() as ThreadItem.UnrecognizedMessage).id)
        }

    private fun assertPlacementInvariants(
        label: String,
        before: ThreadSnapshot,
        after: ThreadSnapshot,
        incoming: HistoryPage,
    ) {
        val prior = before.rows.atoms()
        val result = after.rows.atoms()
        val evidence = reduceOrderedHistoryPage(incoming.entries, true).unsignedOrder.keys - before.unsignedHistoryOrder.keys
        val retained = prior.keys.filterNot { it in evidence && (it as? List<*>)?.firstOrNull() == "delta" }
        assertEquals(label, retained, result.keys.filter { it in retained })
        assertEquals(label, result.keys.size, after.rows.logicalIdentities().size)
        prior.forEach { (identity, text) -> assertEquals(label, text, result[identity]) }
        before.unsignedHistoryOrder.forEach { (identity, position) -> assertEquals(label, position, after.unsignedHistoryOrder[identity]) }
        val durable = result.keys.filter { it in after.unsignedHistoryOrder }
        assertEquals(label, durable.sortedBy { after.unsignedHistoryOrder[it] }, durable)
        val expected = prior.keys + reduceOrderedHistoryPage(incoming.entries, true).rows.atoms().keys
        assertEquals(label, expected.toSet(), result.keys.toSet())
        val keys = after.rows.names()
        assertEquals(label, keys.distinct(), keys)
    }

    private fun List<ThreadItem>.logicalIdentities(): List<Any> =
        flatMap { row ->
            val segment = (row as? ThreadItem.MessageItem)?.message?.segment
            segment?.deltas?.map { listOf("delta", segment.turnId, it.seq) } ?: listOf(row.mergeIdentity())
        }

    private fun List<ThreadItem>.atoms(): Map<Any, String> =
        linkedMapOf<Any, String>().apply {
            for (row in this@atoms) {
                val message = (row as? ThreadItem.MessageItem)?.message
                val segment = message?.segment
                if (segment == null) {
                    put(row.mergeIdentity(), message?.content.orEmpty())
                } else {
                    var offset = 0
                    for (delta in segment.deltas) {
                        put(listOf("delta", segment.turnId, delta.seq), message.content.substring(offset, offset + delta.length))
                        offset += delta.length
                    }
                }
            }
        }

    private fun user(
        id: ULong,
        name: String = name(id),
        timestamp: Instant = at,
    ): HistoryEntry = entry(id, "message", """{"conversation_id":"c","message_id":"$name","role":"user","text":"$name"}""", timestamp)

    private fun delta(
        id: ULong,
        seq: Int,
        text: String,
        turn: String = "t",
    ): HistoryEntry = entry(id, "assistant_delta", """{"conversation_id":"c","turn_id":"$turn","seq":$seq,"text":"$text"}""")

    private fun entry(
        id: ULong,
        type: String,
        payload: String,
        timestamp: Instant = at,
    ): HistoryEntry = HistoryEntry(unsignedId = id, type = type, payload = MobileJson.parseToJsonElement(payload), timestamp = timestamp)

    private fun page(vararg entries: HistoryEntry): HistoryPage = HistoryPage(entries.sortedByDescending { it.unsignedId }, "cursor", false)

    private fun name(id: ULong): String = "m$id"

    private fun List<ThreadItem>.texts() = filterIsInstance<ThreadItem.MessageItem>().map { it.message.content }

    private fun List<ThreadItem>.seqs() =
        filterIsInstance<ThreadItem.MessageItem>().flatMap { row ->
            row.message.segment?.deltas.orEmpty().map {
                it.seq
            }
        }

    private fun List<ThreadItem>.names() = filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { first -> permutations(values - first).map { listOf(first) + it } }
}
