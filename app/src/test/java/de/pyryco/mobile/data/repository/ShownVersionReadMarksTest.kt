package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostAttentionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShownVersionReadMarksTest {
    @Test fun unavailableMetadataNeverReusesAHeldShownWatermark() =
        runTest {
            val old = RelayLog.enabled
            RelayLog.enabled = false
            try {
                val projection = ConversationListProjection { true }
                projection.applySnapshot(snapshot("8"))
                assertEquals(8uL, projection.currentReadMarks("c")?.unreadWatermark)
                projection.applySnapshot(snapshot(null))
                val unavailable = requireNotNull(projection.currentReadMarks("c"))
                assertNull(unavailable.lastShownVersion)
                assertNull(unavailable.unreadWatermark)
                assertEquals(100uL, unavailable.latestEntryId)
                assertEquals(3uL, unavailable.readUpTo)
                projection.upsertConversation(MobileJson.decodeFromString<ConversationResponseDto>(row("0")))
                assertEquals(0uL, projection.currentReadMarks("c")?.unreadWatermark)
                projection.recordLatestEntry("c", 101uL)
                assertEquals(0uL, projection.currentReadMarks("c")?.unreadWatermark)
                projection.upsertConversation(MobileJson.decodeFromString<ConversationResponseDto>(row(null)))
                assertNull(projection.currentReadMarks("c")?.unreadWatermark)
            } finally {
                RelayLog.enabled = old
            }
        }

    @Test fun safeIntegerBoundaryInvalidatesOnlyShownAvailabilityIncludingMalformedNumbers() =
        runTest {
            val old = RelayLog.enabled
            RelayLog.enabled = false
            try {
                val projection = ConversationListProjection { true }
                for (token in listOf("-1", "1.5", "1e2", "\"8\"", "true", "{}", "null", "9007199254740992", "18446744073709551615")) {
                    projection.applySnapshot(snapshot("7"))
                    projection.applySnapshot(snapshot(token))
                    assertNull(token, projection.currentReadMarks("c")?.unreadWatermark)
                    assertEquals(3uL, projection.currentReadMarks("c")?.readUpTo)
                }
                projection.applySnapshot(snapshot("9007199254740991"))
                assertEquals(9007199254740991uL, projection.currentReadMarks("c")?.lastShownVersion)
            } finally {
                RelayLog.enabled = old
            }
        }

    @Test fun threadAttentionUsesShownAndRetainsPromptRunningPrecedenceAndLegacyFallback() {
        fun attention(marks: ConversationReadMarks) =
            HostAttentionState(readMarks = mapOf("c" to marks)).resolve(emptyList(), emptyList())["c"]
                ?: ConversationAttention.Idle
        assertEquals(ConversationAttention.Unread, attention(ConversationReadMarks(3u, 0u, 4u, true)))
        assertEquals(ConversationAttention.Idle, attention(ConversationReadMarks(3u, 100u, 3u, true)))
        assertEquals(ConversationAttention.Idle, attention(ConversationReadMarks(3u, 100u, null, true)))
        assertEquals(ConversationAttention.Idle, attention(ConversationReadMarks(3u, 4u)))
        val running = HostAttentionState(running = setOf("c"), readMarks = mapOf("c" to ConversationReadMarks(3u, 100u, null, true)))
        assertEquals(ConversationAttention.Running, running.resolve(emptyList(), emptyList())["c"])
    }

    @Test fun unavailableAuthoritativeMetadataKeepsPromptVisible() {
        val held =
            HostAttentionState(
                readMarks = mapOf("c" to ConversationReadMarks(null, 100u, null, true)),
            )
        assertEquals(ConversationAttention.Idle, held.resolve(emptyList(), emptyList())["c"] ?: ConversationAttention.Idle)
        assertEquals(
            ConversationAttention.WaitingForAnswer,
            held.resolve(emptyList(), listOf(QuestionBatch("c", "batch", emptyList())))["c"],
        )
        assertEquals(
            ConversationAttention.Idle,
            held.copy(readMarks = mapOf("c" to ConversationReadMarks(null, 100u))).resolve(emptyList(), emptyList())["c"]
                ?: ConversationAttention.Idle,
        )
    }

    @Test fun sessionReplacementAndPeerConfirmationDoNotResetSharedReadFacts() =
        runTest {
            val old = RelayLog.enabled
            RelayLog.enabled = false
            try {
                val projection = ConversationListProjection { true }
                projection.applySnapshot(snapshot("8"))
                projection.updateCurrentSessionId("c", "new-session")
                projection.upsertConversation(MobileJson.decodeFromString<ConversationResponseDto>(row("8", read = 8)))
                projection.applySnapshot(snapshot("5"))
                assertEquals(8uL, projection.currentReadMarks("c")?.readUpTo)
                assertEquals(8uL, projection.currentReadMarks("c")?.lastShownVersion)
                assertEquals("new-session", projection.current().single().currentSessionId)
                assertNull(ConversationListProjection { true }.currentReadMarks("c"))
            } finally {
                RelayLog.enabled = old
            }
        }

    @Test fun productionSummaryReceiptCannotSendReadAndConfirmedReplyDoesNotReplaceShown() =
        runTest {
            val old = RelayLog.enabled
            RelayLog.enabled = false
            try {
                val inbound = Channel<Envelope>(Channel.UNLIMITED)
                val sent = mutableListOf<Envelope>()
                val pump =
                    object : SessionPump {
                        override val inbound = inbound.receiveAsFlow()

                        override fun send(envelope: Envelope): Boolean {
                            sent += envelope
                            return true
                        }
                    }
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("thread") })
                var reading: ConversationReadMarks? = null
                backgroundScope.launch { repo.observeReadMarks("c").collect { reading = it } }
                inbound.send(snapshot("8"))
                runCurrent()
                assertEquals(8uL, reading?.lastShownVersion)
                assertTrue(sent.none { it.type == "mark_conversation_read" })
                val result = async { repo.markConversationRead("c", 8u) }
                runCurrent()
                val request = sent.single { it.type == "mark_conversation_read" }
                inbound.send(
                    Envelope(
                        76,
                        "conversation_updated",
                        "2026-10-11T00:00:00Z",
                        MobileJson.parseToJsonElement(row("8", read = 7)),
                        inReplyTo = request.id,
                    ),
                )
                runCurrent()
                assertEquals(7uL, result.await().getOrThrow())
                assertEquals(7uL, reading?.readUpTo)
                assertEquals(8uL, reading?.lastShownVersion)
                inbound.send(
                    Envelope(
                        77,
                        "conversation_updated",
                        "2026-10-11T00:00:00Z",
                        MobileJson.parseToJsonElement(row(null, read = 8)),
                    ),
                )
                runCurrent()
                assertEquals(8uL, reading?.readUpTo)
                assertNull(reading?.lastShownVersion)
            } finally {
                RelayLog.enabled = old
            }
        }

    private fun snapshot(shown: String?) =
        Envelope(
            1,
            "conversations",
            "2026-10-11T00:00:00Z",
            MobileJson.parseToJsonElement("""{"conversations":[${row(shown)}]}"""),
        )

    private fun row(
        shown: String?,
        read: Int = 3,
    ): String =
        JsonObject(
            buildMap {
                put("id", JsonPrimitive("c"))
                put("name", JsonPrimitive("Thread"))
                put("is_promoted", JsonPrimitive(true))
                put("cwd", JsonPrimitive(""))
                put("last_message_ts", JsonPrimitive("2026-10-11T00:00:00Z"))
                put("last_used_at", JsonPrimitive("2026-10-11T00:00:00Z"))
                put("read_up_to", JsonPrimitive(read))
                put("latest_entry_id", JsonPrimitive(100))
                shown?.let { put("last_shown_version", MobileJson.parseToJsonElement(it)) }
            },
        ).toString()
}
