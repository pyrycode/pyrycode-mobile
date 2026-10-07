package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ReplayCursor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryReplySuggestionTest {
    private val oldEnabled = RelayLog.enabled
    private val oldSink = RelayLog.sink

    @Before
    fun captureLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun restoreLogs() {
        RelayLog.enabled = oldEnabled
        RelayLog.sink = oldSink
    }

    @Test
    fun absentAndLateSubscribers_receiveLatestSetAndExplicitClear() =
        runTest {
            val (pump, repo) = remote()
            runCurrent()
            assertNull(repo.observeReplySuggestion(C1, S1).first())
            pump.push(frame(1, "  next reply  "))
            runCurrent()
            assertEquals(ReplySuggestion(C1, S1, 1uL, "  next reply  "), repo.observeReplySuggestion(C1, S1).first())
            pump.push(frame(2, null))
            runCurrent()
            assertEquals(ReplySuggestion(C1, S1, 2uL, null), repo.observeReplySuggestion(C1, S1).first())
        }

    @Test
    fun strictlyHigherRevision_winsInEveryArrivalOrder_andEnvelopeIdsDoNotOrder() =
        runTest {
            for (order in listOf(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L), listOf(2L, 3L, 1L))) {
                val (pump, repo) = remote()
                val values = collect(repo.observeReplySuggestion(C1, S1))
                runCurrent()
                for (revision in order) {
                    pump.push(frame(revision, "reply $revision"))
                    runCurrent()
                }
                assertEquals(3uL, values.last()?.revision)
                assertEquals("reply 3", values.last()?.suggestedReply)
                val count = values.size
                pump.push(frame(3, "duplicate replacement"))
                pump.push(frame(2, null))
                runCurrent()
                assertEquals(count, values.size)
            }
        }

    @Test
    fun clearRetainsWatermark_duplicateOrStaleSetCannotReviveText() =
        runTest {
            val (pump, repo) = remote()
            val values = collect(repo.observeReplySuggestion(C1, S1))
            runCurrent()
            pump.push(frame(10, "set"))
            runCurrent()
            pump.push(frame(11, null))
            runCurrent()
            val afterClear = values.toList()
            pump.push(frame(11, "equal"))
            pump.push(frame(10, "older"))
            pump.push(frame(1, "restart without new connection"))
            runCurrent()
            assertEquals(afterClear, values)
            assertEquals(11uL, values.last()?.revision)
            assertNull(values.last()?.suggestedReply)
            pump.push(frame(12, "new"))
            runCurrent()
            assertEquals("new", values.last()?.suggestedReply)
        }

    @Test
    fun conversationSessionAndHostPairs_areIndependentEvenWithSharedHostReadings() =
        runTest {
            val held = HostReadings()
            val (pumpA, hostA) = remote(held = held)
            val (pumpB, hostB) = remote(held = held)
            val a1 = collect(hostA.observeReplySuggestion(C1, S1))
            val a2 = collect(hostA.observeReplySuggestion(C1, S2))
            val a3 = collect(hostA.observeReplySuggestion(C2, S1))
            val b1 = collect(hostB.observeReplySuggestion(C1, S1))
            runCurrent()
            pumpA.push(frame(50, "A1"))
            pumpA.push(frame(1, "A2", session = S2))
            pumpA.push(frame(2, "A3", conversation = C2))
            pumpB.push(frame(1, "B1"))
            runCurrent()
            assertEquals(listOf("A1", "A2", "A3", "B1"), listOf(a1, a2, a3, b1).map { it.last()?.suggestedReply })
            val others = listOf(a1.toList(), a3.toList(), b1.toList())
            pumpA.push(frame(2, null, session = S2))
            runCurrent()
            assertNull(a2.last()?.suggestedReply)
            assertEquals(others, listOf(a1, a3, b1))
        }

    @Test
    fun malformedFrames_changeNeitherTextNorWatermark_andConsumerSurvives() =
        runTest {
            val (pump, repo) = remote()
            val values = collect(repo.observeReplySuggestion(C1, S1))
            runCurrent()
            pump.push(frame(1, "held"))
            runCurrent()
            val original = values.toList()
            val valid = MobileJson.parseToJsonElement(payload("99", JsonPrimitive("replacement").toString())).toString()
            val fields = listOf("conversation_id", "session_id", "revision", "suggested_reply")
            val objectFields = MobileJson.parseToJsonElement(valid) as kotlinx.serialization.json.JsonObject
            for (field in fields) {
                pump.push(
                    raw(
                        kotlinx.serialization.json
                            .JsonObject(objectFields - field)
                            .toString(),
                    ),
                )
                for (wrong in listOf("true", "[]", "{}")) {
                    pump.push(
                        raw(
                            kotlinx.serialization.json
                                .JsonObject(objectFields + (field to MobileJson.parseToJsonElement(wrong)))
                                .toString(),
                        ),
                    )
                }
            }
            for (identity in listOf("", " ", "not-an-id", C1.uppercase(), "00000000-0000-0000-0000-000000000000")) {
                pump.push(raw(payload("99", "null", conversation = identity)))
                pump.push(raw(payload("99", "null", session = identity)))
            }
            pump.push(raw(payload("99", "123")))
            pump.push(raw(payload("99", "null").replace("\"$C1\"", "null")))
            pump.push(raw(payload("99", "null").replace("\"$S1\"", "null")))
            for (revision in listOf("0", "-1", "1.5", "1e2", "\"99\"", "null", "18446744073709551616")) {
                pump.push(raw(payload(revision, "null")))
            }
            for (text in listOf("", " \t ", "a\nb", "a\rb", "a\u0085b", "a\u2028b", "a\u2029b", "x".repeat(1025), "ä".repeat(513))) {
                pump.push(raw(payload("99", JsonPrimitive(text).toString())))
            }
            pump.push(raw(payload("99", "\"\\ud800\"")))
            pump.push(raw(payload("99", "\"\\udc00\"")))
            pump.push(raw("[]"))
            pump.push(raw("null"))
            runCurrent()
            assertEquals(original, values)
            pump.push(frame(2, "valid lower than malformed revision"))
            runCurrent()
            assertEquals(2uL, values.last()?.revision)
        }

    @Test
    fun utf8ByteBound_andFullUnsignedRevisionRange_arePreservedVerbatim() =
        runTest {
            val (pump, repo) = remote()
            runCurrent()
            for ((index, text) in listOf(
                "x".repeat(1024),
                "ä".repeat(512),
                "😀".repeat(256),
                "  inert <tag> https://example.test  ",
            ).withIndex()) {
                pump.push(frame(index.toLong() + 1, text))
                runCurrent()
                assertEquals(text, repo.observeReplySuggestion(C1, S1).first()?.suggestedReply)
            }
            for (revision in listOf("9223372036854775808", "18446744073709551615")) {
                pump.push(raw(payload(revision, "\"unsigned\"")))
                runCurrent()
                assertEquals(revision.toULong(), repo.observeReplySuggestion(C1, S1).first()?.revision)
            }
            pump.push(frame(100, null))
            runCurrent()
            assertEquals(ULong.MAX_VALUE, repo.observeReplySuggestion(C1, S1).first()?.revision)
        }

    @Test
    fun reconciledNull_preventsAnOlderLiveSet() =
        runTest {
            val (pump, repo) = remote()
            runCurrent()
            pump.push(frame(8, null))
            pump.push(frame(7, "stale snapshot"))
            runCurrent()
            assertEquals(ReplySuggestion(C1, S1, 8uL, null), repo.observeReplySuggestion(C1, S1).first())
        }

    @Test
    fun stableFacade_disconnectAndFreshConnection_discardTextAndWatermark() =
        runTest {
            val held = HostReadings()
            val (oldPump, old) = remote(held = held)
            val delegates = MutableStateFlow<ConversationRepository?>(old)
            val facade = StableConversationRepository(delegates, held)
            val values = collect(facade.observeReplySuggestion(C1, S1))
            runCurrent()
            oldPump.push(frame(100, "old"))
            runCurrent()
            assertEquals("old", values.last()?.suggestedReply)
            delegates.value = null
            runCurrent()
            assertNull(values.last())
            oldPump.push(frame(101, "detached"))
            runCurrent()
            assertNull(values.last())
            val (newPump, fresh) = remote(held = held)
            delegates.value = fresh
            runCurrent()
            assertNull(values.last())
            newPump.push(frame(1, "new daemon"))
            runCurrent()
            assertEquals(1uL, values.last()?.revision)
            assertEquals("new daemon", facade.observeReplySuggestion(C1, S1).first()?.suggestedReply)
            // Direct replacement must drop the old reading without requiring a null delegate interval.
            val (thirdPump, third) = remote(held = held)
            delegates.value = third
            runCurrent()
            assertNull(values.last())
            newPump.push(frame(2, "detached second"))
            thirdPump.push(frame(1, null))
            runCurrent()
            assertEquals(ReplySuggestion(C1, S1, 1uL, null), values.last())
        }

    @Test
    fun inboundCompletion_releasesConnectionState() =
        runTest {
            val (pump, repo) = remote()
            val values = collect(repo.observeReplySuggestion(C1, S1))
            runCurrent()
            pump.push(frame(2, "temporary"))
            runCurrent()
            pump.close()
            runCurrent()
            assertNull(values.last())
            assertNull(repo.observeReplySuggestion(C1, S1).first())
        }

    @Test
    fun nonInteractiveAndDefaultRepositories_remainAbsent() =
        runTest {
            val (pump, repo) = remote(interactive = false)
            runCurrent()
            pump.push(frame(1, "ignored"))
            runCurrent()
            assertNull(repo.observeReplySuggestion(C1, S1).first())
            assertNull(FakeConversationRepository().observeReplySuggestion(C1, S1).first())
            assertNull(StableConversationRepository(MutableStateFlow(null)).observeReplySuggestion(C1, S1).first())
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun suggestions_neverAppendRowsOpenTurnsEmitLiveEventsOrAdvanceReplay() =
        runTest {
            val cursor = ReplayCursor()
            val (pump, repo) = remote(cursor = cursor)
            val rows = collect(repo.observeMessages(C1))
            val turns = collect(repo.observeTurnPhase(C1))
            val live = collect(repo.liveSessionEvents)
            runCurrent()
            pump.push(frame(1, "state only"))
            pump.push(frame(2, null))
            runCurrent()
            assertTrue(rows.all { it.isEmpty() })
            assertTrue(turns.all { it == LiveSessionEvent.TurnState.Phase.Idle })
            assertTrue(live.isEmpty())
            pump.push(frame(3, "must not replay").copy(eventId = 100))
            val history = async { repo.requestHistory(C1, "", 10) }
            runCurrent()
            val request = pump.sent.last { it.type == "request_history" }
            pump.push(
                raw(
                    """{"entries":[{"id":1,"type":"reply_suggestion","ts":"$TS","payload":${payload(
                        "99",
                        "\"history\"",
                    )}}],"cursor":"","at_start":true}""",
                ).copy(type = "history_page", inReplyTo = request.id),
            )
            runCurrent()
            assertEquals(1, history.await().entries.size)
            assertEquals(2uL, repo.observeReplySuggestion(C1, S1).first()?.revision)
            assertNull(repo.observeReplySuggestion(C1, S1).first()?.suggestedReply)
            assertNull(cursor.latest)
            assertTrue(rows.all { it.isEmpty() })
            assertTrue(live.isEmpty())
        }

    @Test
    fun cachingWrapper_forwardsLiveStateWithoutReadingOrWritingPersistentCache() =
        runTest {
            val (pump, repo) = remote()
            val delegates = MutableStateFlow<ConversationRepository?>(repo)
            val cache =
                object : ConversationCache {
                    override suspend fun readConversations(serverId: String): List<Conversation> = error("unexpected cache read")

                    override suspend fun writeConversations(
                        serverId: String,
                        conversations: List<Conversation>,
                    ): Result<Unit> = error("unexpected cache write")

                    override suspend fun removeHost(serverId: String): Result<Unit> = error("unexpected cache removal")

                    override suspend fun removeConversation(
                        serverId: String,
                        conversationId: String,
                    ): Result<Unit> = error("unexpected cache removal")

                    override suspend fun readThread(
                        serverId: String,
                        conversationId: String,
                    ): List<ThreadItem> = error("unexpected thread read")

                    override suspend fun writeThread(
                        serverId: String,
                        conversationId: String,
                        rows: List<ThreadItem>,
                    ): Result<Unit> = error("unexpected thread write")
                }
            val wrapper = CachingConversationRepository(StableConversationRepository(delegates), cache, "host-A")
            val values = collect(wrapper.observeReplySuggestion(C1, S1))
            runCurrent()
            pump.push(frame(1, "live only"))
            runCurrent()
            assertEquals("live only", values.last()?.suggestedReply)
            delegates.value = null
            runCurrent()
            assertNull(values.last())
            assertNull(wrapper.observeReplySuggestion(C1, S1).first())
        }

    @Test
    fun suggestionDiagnostics_neverIncludeDaemonTextOrIdentities() =
        runTest {
            val beforeEnabled = RelayLog.enabled
            val beforeSink = RelayLog.sink
            val logs = mutableListOf<String>()
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> logs += message }
            try {
                val (pump, repo) = remote()
                runCurrent()
                pump.push(frame(1, "PRIVATE_SUGGESTION_MARKER"))
                pump.push(frame(1, "STALE_MARKER"))
                pump.push(frame(99, "MALFORMED_MARKER\n"))
                runCurrent()
                val reading = repo.observeReplySuggestion(C1, S1).first()
                assertFalse(reading.toString().contains("PRIVATE_SUGGESTION_MARKER"))
                assertTrue(logs.any { it.contains("event=reply_suggestion") })
                assertTrue(logs.none { it.contains("MARKER") || it.contains(C1) || it.contains(S1) })
            } finally {
                RelayLog.enabled = beforeEnabled
                RelayLog.sink = beforeSink
            }
        }

    private fun TestScope.remote(
        interactive: Boolean = true,
        held: HostReadings = HostReadings(),
        cursor: ReplayCursor = ReplayCursor(),
    ): Pair<Pump, RemoteConversationRepository> {
        val pump = Pump()
        return pump to
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { if (interactive) setOf(CAPABILITY_INTERACTIVE) else emptySet() },
                hostReadings = held,
                replayCursor = cursor,
            )
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> =
        mutableListOf<T>().also { values ->
            backgroundScope.launch { flow.collect { values += it } }
        }

    private fun frame(
        revision: Long,
        text: String?,
        conversation: String = C1,
        session: String = S1,
    ): Envelope =
        raw(payload(revision.toString(), text?.let { JsonPrimitive(it).toString() } ?: JsonNull.toString(), conversation, session))

    private fun payload(
        revision: String,
        text: String,
        conversation: String = C1,
        session: String = S1,
    ): String = """{"conversation_id":"$conversation","session_id":"$session","revision":$revision,"suggested_reply":$text}"""

    private fun raw(payload: String): Envelope =
        Envelope(id = 1, type = "reply_suggestion", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            check(channel.trySend(envelope).isSuccess)
        }

        fun close() {
            channel.close()
        }
    }

    private companion object {
        const val C1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val C2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val S1 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val S2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val TS = "2026-10-07T12:00:00Z"
    }
}
