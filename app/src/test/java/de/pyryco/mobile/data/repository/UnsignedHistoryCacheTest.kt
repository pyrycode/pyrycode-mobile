package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.ExperimentalUnsignedTypes::class)
class UnsignedHistoryCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sink = RelayLog.sink

    @Before fun setup() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        RelayLog.sink = sink
    }

    private fun disk() = FileConversationCache(tmp.root, UnconfinedTestDispatcher())

    private val boundary = Long.MAX_VALUE.toULong()

    private fun page(vararg ids: ULong) =
        HistoryPage(
            ids.reversed().map { id ->
                HistoryEntry(
                    type = "send_message",
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","message_id":"m$id","text":"$id"}"""),
                    timestamp = Instant.fromEpochSeconds(1),
                    unsignedId = id,
                )
            },
            "older",
            false,
        )

    private fun rows(page: HistoryPage) = reduceHistoryPage(page.entries, true)

    private class Live :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val projection = ThreadProjection()

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = projection.observe(conversationId)

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = projection.observeSnapshot(conversationId)

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage = error("restore must not ask for history")

        override suspend fun markConversationRead(
            conversationId: String,
            upTo: ULong,
        ) = error("cache must not send a read command")
    }

    @Test fun retainedRowsInvariant_malformedHistoryCannotHideReadableRows() =
        runTest {
            val p = page(1u)
            disk().writeThread("h", "c", rows(p)).getOrThrow()
            val file = tmp.root.walkTopDown().first { it.isFile }
            val raw = MobileJson.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            file.writeText(
                kotlinx.serialization.json
                    .JsonObject(
                        raw + (
                            "history" to
                                MobileJson.parseToJsonElement(
                                    """
{"cursor":"older",
"atStart":true,
"coverage":{"spans":[{"first":-1,
"last":2}]}}
""",
                                )
                        ),
                    ).toString(),
            )
            assertEquals(rows(p), disk().readThread("h", "c"))
            assertNull(disk().readHistoryPosition("h", "c"))
            val seed = CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c")
            assertTrue(seed?.coverage?.unknown == true)
        }

    @Test fun restoreOrderInvariant_partialGapFillRetainsBothSidesWithoutCommands() =
        runTest {
            val old = page(boundary - 1u, boundary)
            val newer = page(boundary + 4u, ULong.MAX_VALUE)
            val held = rows(old) + rows(newer)
            val coverage = HistoryCoverage().received(old).received(newer).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("oldest", false, coverage)).getOrThrow()
            for (collect in listOf(false, true)) {
                disk().writeThread("h", "c", held).getOrThrow()
                disk().writeHistoryPosition("h", "c", HistoryPosition("oldest", false, coverage)).getOrThrow()
                val middle = page(boundary + 2u, boundary + 3u)
                val live = Live().apply { projection.mergeHistoryPage("c", middle, true) }
                val wrapper = CachingConversationRepository(live, disk(), "h")
                if (collect) wrapper.observeMessages("c").first()
                wrapper.writeHistoryPosition("c", HistoryPosition("oldest", false, coverage.received(middle)))
                val restored = CachingConversationRepository(Live(), disk(), "h")
                assertEquals(
                    listOf(boundary - 1u, boundary, boundary + 2u, boundary + 3u, boundary + 4u, ULong.MAX_VALUE).map { "m$it" },
                    restored
                        .observeMessages("c")
                        .first()
                        .filterIsInstance<ThreadItem.MessageItem>()
                        .map { it.message.id },
                )
                assertTrue(CachingConversationRepository(Live(), disk(), "other-host").observeMessages("c").first().isEmpty())
                assertNull(disk().readHistoryPosition("h", "other-conversation"))
            }
        }

    @Test fun receivedIdentityInvariant_roundTripRetainsEveryUnsignedMetadataField() =
        runTest {
            val p = page(boundary, boundary + 1u, ULong.MAX_VALUE)
            val held = rows(p)
            val state = HistoryCoverage().receivedUnsigned(p, newest = true, target = boundary + 1u).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("older", false, state)).getOrThrow()
            val restored = disk().readHistoryPosition("h", "c")?.coverage ?: error("missing coverage")
            assertEquals(state, restored)
            assertEquals(ULong.MAX_VALUE, restored.unsignedHighWater)
            assertEquals("older", restored.cursorForUnsigned(boundary + 1u))
            assertEquals(
                setOf(boundary, boundary + 1u, ULong.MAX_VALUE),
                restored.unsignedRowEntries.values
                    .flatten()
                    .toSet(),
            )
            assertTrue(
                restored.rowEntries.values
                    .flatten()
                    .all { it > 0 },
            )
            assertTrue(restored.unknown)
            assertFalse(restored.unsignedUnknown)
        }

    @Test fun legacyCompatibilityInvariant_positiveSignedDocumentRetainsRowsAndCoverage() =
        runTest {
            val p = page(1u, 2u)
            val held = rows(p)
            val state = HistoryCoverage().received(p).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            // The legacy signed writer used these same numeric field names, with omitted defaults.
            val file = tmp.root.walkTopDown().first { it.isFile }
            val raw = MobileJson.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            val key1 = held[0].historyKeys().single()
            val key2 = held[1].historyKeys().single()
            val legacy = """
{"cursor":"legacy",
"atStart":true,
"coverage":{"spans":[{"first":1,
"last":2}],
"cursors":{"1":"legacy"},
"rowOrder":{"$key1":1,
"$key2":2},
"rowEntries":{"$key1":[1],
"$key2":[2]},
"proofs":{"$key1":"${state.proofs[key1]}",
"$key2":"${state.proofs[key2]}"}}}
"""
            file.writeText(
                kotlinx.serialization.json
                    .JsonObject(raw + ("history" to MobileJson.parseToJsonElement(legacy)))
                    .toString(),
            )
            assertEquals(held, disk().readThread("h", "c"))
            val seed = CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c") ?: error("missing legacy position")
            assertTrue(seed.atStart)
            assertEquals(listOf(HistorySpan(1, 2)), seed.coverage?.spans)
            assertEquals(listOf(UnsignedHistorySpan(1u, 2u)), seed.coverage?.unsignedSpans)
            assertEquals(state.unsignedRowOrder, seed.coverage?.unsignedRowOrder)
        }

    @Test fun retainedClaimsInvariant_invalidMetadataRejectsClaimsWithoutLosingRows() =
        runTest {
            val p = page(1u)
            val held = rows(p)
            val state = HistoryCoverage().received(p).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            val file = tmp.root.walkTopDown().first { it.isFile }
            val raw = MobileJson.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            val key = held.single().historyKeys().single()
            val proof = state.proofs[key]
            val metadata =
                listOf(
                    """{"spans":[{"first":0,"last":1}]}""",
                    """{"spans":[{"first":2,"last":1}]}""",
                    """{"spans":[{"first":1,"last":1},{"first":1,"last":2}]}""",
                    """{"spans":[{"first":1,"last":1}],"gaps":[{"anchor":1,"edge":2}]}""",
                    """
{"spans":[{"first":1,
"last":1}],
"rowOrder":{"$key":2},
"rowEntries":{"$key":[2]},
"proofs":{"$key":"$proof"}}
""",
                    """
{"spans":[{"first":1,
"last":1}],
"rowOrder":{"$key":1},
"rowEntries":{"$key":[1]}}
""",
                    """{"spans":[{"first":1,"last":1}],"cursors":{"0":"bad"}}""",
                ) +
                    listOf("-1", "18446744073709551616", "1.0", "1e0", "\"1\"", "null").map { value ->
                        """{"spans":[{"first":$value,"last":1}]}"""
                    }
            val inconsistent = """
                {"spans":[{"first":1,"last":2}],
                 "rowOrder":{"$key":1},"rowEntries":{"$key":[2]},"proofs":{"$key":"$proof"}}
            """
            val malformedValues =
                listOf("-1", "18446744073709551616", "1.0", "1e0", "\"1\"", "null").map { value ->
                    """
                    {"spans":[{"first":1,"last":1}],
                     "rowOrder":{"$key":$value},"rowEntries":{"$key":[1]},"proofs":{"$key":"$proof"}}
                """
                }
            for (coverage in metadata + inconsistent + malformedValues) {
                val history = MobileJson.parseToJsonElement("""{"cursor":"bad","atStart":true,"coverage":$coverage}""")
                file.writeText(
                    kotlinx.serialization.json
                        .JsonObject(raw + ("history" to history))
                        .toString(),
                )
                assertEquals(held, disk().readThread("h", "c"))
                assertNull(disk().readHistoryPosition("h", "c"))
                assertTrue(CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c")?.coverage?.unsignedUnknown == true)
            }
        }

    @Test fun durableWriteInvariant_failedRowsAndStateWritesKeepOnlyPriorClaims() =
        runTest {
            val old = page(boundary)
            val fresh = page(ULong.MAX_VALUE)
            val state = HistoryCoverage().received(old).boundTo(rows(old))
            val live = Live().apply { projection.mergeHistoryPage("c", fresh, true) }
            for (failRows in listOf(true, false)) {
                disk().writeThread("h", "c", rows(old)).getOrThrow()
                disk().writeHistoryPosition("h", "c", HistoryPosition("old", false, state)).getOrThrow()
                var states = 0
                val failing =
                    object : ConversationCache by disk() {
                        override suspend fun writeThread(
                            serverId: String,
                            conversationId: String,
                            rows: List<ThreadItem>,
                        ): Result<Unit> =
                            if (failRows) {
                                Result.failure(
                                    java.io.IOException("failed rows"),
                                )
                            } else {
                                disk().writeThread(serverId, conversationId, rows)
                            }

                        override suspend fun writeHistoryPosition(
                            serverId: String,
                            conversationId: String,
                            position: HistoryPosition?,
                        ): Result<Unit> {
                            states++
                            return Result.failure(java.io.IOException("failed state"))
                        }
                    }
                CachingConversationRepository(live, failing, "h").writeHistoryPosition(
                    "c",
                    HistoryPosition("next", false, state.received(fresh)),
                )
                assertEquals(if (failRows) 0 else 1, states)
                assertEquals(state, disk().readHistoryPosition("h", "c")?.coverage)
                assertEquals(if (failRows) rows(old) else rows(old) + rows(fresh), disk().readThread("h", "c"))
            }
        }

    @Test fun retentionInvariant_staleStateCannotRecertifyRemovedMaximum() =
        runTest {
            val p = page(boundary, ULong.MAX_VALUE)
            val held = rows(p)
            val state = HistoryCoverage().received(p).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("old", true, state)).getOrThrow()
            disk().writeThread("h", "c", held.take(1)).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("stale", true, state)).getOrThrow()
            val restored = disk().readHistoryPosition("h", "c")?.coverage ?: error("missing coverage")
            assertEquals(listOf(UnsignedHistorySpan(boundary, boundary)), restored.unsignedSpans)
            assertEquals(boundary, restored.unsignedHighWater)
            assertTrue(restored.unsignedUnknown)
        }

    @Test fun retentionInvariant_wrapperTrimAndLaterStateCannotRestoreMaximumClaimOrWalkStop() =
        runTest(timeout = 3.minutes) {
            val old = page(ULong.MAX_VALUE)
            val held = rows(old)
            val state = HistoryCoverage().received(old).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("old", true, state)).getOrThrow()
            val template = held.single() as ThreadItem.MessageItem
            val many = List(MAX_CACHED_THREAD_ROWS) { index -> template.copy(message = template.message.copy(id = "new-$index")) }
            val live =
                object : ConversationRepository by Live() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = kotlinx.coroutines.flow.flowOf(many)
                }
            CachingConversationRepository(live, disk(), "h").writeHistoryPosition("c", HistoryPosition("stale", true, state))
            val restored = disk().readHistoryPosition("h", "c") ?: error("missing state")
            assertEquals("", restored.cursor)
            assertFalse(restored.atStart)
            assertTrue(restored.coverage?.unsignedSpans?.isEmpty() == true)
            assertEquals(0uL, restored.coverage?.unsignedHighWater)
            assertEquals(MAX_CACHED_THREAD_ROWS, disk().readThread("h", "c").size)
        }

    @Test fun deltaRestoreInvariant_partialSegmentsKeepHeldTextAndGapAcrossFreshCache() =
        runTest {
            fun delta(
                id: ULong,
                seq: Int,
                text: String,
            ) = HistoryEntry(
                type = "assistant_delta",
                payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","turn_id":"t","seq":$seq,"text":"$text"}"""),
                timestamp = Instant.fromEpochSeconds(1),
                unsignedId = id,
            )
            val end =
                HistoryEntry(
                    type = "turn_end",
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}"""),
                    timestamp = Instant.fromEpochSeconds(1),
                    unsignedId = ULong.MAX_VALUE,
                )
            val initial = HistoryPage(listOf(end, delta(ULong.MAX_VALUE - 1u, 2, "c"), delta(ULong.MAX_VALUE - 3u, 0, "a")), "gap", false)
            val held = rows(initial)
            val state = HistoryCoverage().receivedUnsigned(initial, newest = true, target = ULong.MAX_VALUE - 3u).boundTo(held)
            disk().writeThread("h", "c", held).getOrThrow()
            disk().writeHistoryPosition("h", "c", HistoryPosition("old", false, state)).getOrThrow()
            assertEquals(state.copy(deltaText = emptyMap()), disk().readHistoryPosition("h", "c")?.coverage)
            assertEquals(held, CachingConversationRepository(Live(), disk(), "h").observeMessages("c").first())
            val middle = HistoryPage(listOf(end, delta(ULong.MAX_VALUE - 2u, 1, "b")), "filled", false)
            val live = Live().apply { repeat(2) { projection.mergeHistoryPage("c", middle, true) } }
            val wrapper = CachingConversationRepository(live, disk(), "h")
            val received =
                requireNotNull(
                    wrapper.readHistoryPosition("c")?.coverage,
                ).receivedUnsigned(middle, target = ULong.MAX_VALUE - 3u)
            wrapper.writeHistoryPosition("c", HistoryPosition("old", false, received))
            val restored = CachingConversationRepository(Live(), disk(), "h")
            val row = restored.observeMessages("c").first().single() as ThreadItem.MessageItem
            assertEquals("abc", row.message.content)
            assertEquals(
                listOf(0, 1, 2),
                row.message.segment
                    ?.deltas
                    ?.map { it.seq },
            )
            assertTrue(
                restored
                    .readHistoryPosition("c")
                    ?.coverage
                    ?.unsignedGaps
                    ?.isEmpty() == true,
            )
            assertEquals(
                setOf(ULong.MAX_VALUE - 3u, ULong.MAX_VALUE - 2u, ULong.MAX_VALUE - 1u),
                restored
                    .readHistoryPosition("c")
                    ?.coverage
                    ?.unsignedRowEntries
                    ?.values
                    ?.flatten()
                    ?.toSet(),
            )
        }
}
