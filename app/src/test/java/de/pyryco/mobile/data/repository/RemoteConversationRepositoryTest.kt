package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the v2 conversation-list read path (#312): the repository drives a
 * `list_conversations` request and projects inbound `conversations` snapshots — mapped via the
 * #316 [de.pyryco.mobile.data.network.ConversationsPayload] layer — into the filtered, sorted
 * domain list. JUnit4 + `runTest`, a hand-written fake [SessionPump] backed by an unlimited
 * channel (so test pushes are never lost before the repository's inbound collector attaches),
 * mirroring `NoiseSessionPumpTest`. Pure data-layer — no device, no real crypto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryTest {
    // ---- AC #2: a subscription drives the list_conversations request ----------------------------

    @Test
    fun subscribe_issuesListConversationsRequestWithEmptyPayload() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            backgroundScope.launch { repo.observeConversations(ConversationFilter.All).collect {} }
            runCurrent()

            val sent = pump.sent.single()
            assertEquals("list_conversations", sent.type)
            assertEquals(JsonObject(emptyMap()), sent.payload)
        }

    // ---- AC #2, #5: seeding a conversations response surfaces the mapped, sorted list ------------

    @Test
    fun seed_surfacesMappedListSortedByLastUsedDescending() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            // Wire order is ascending by last_used_at, so the desc sort must reorder the rows.
            pump.push(
                conversationsEnvelope(
                    """
                    {"conversations":[
                      {"id":"older","name":"Older","is_promoted":true,"cwd":"/p/older","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"},
                      {"id":"newer","name":"Newer","is_promoted":true,"cwd":"/p/newer","last_message_ts":"2026-05-08T11:00:00Z","last_used_at":"2026-05-08T11:00:00Z"}
                    ]}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            val list = emissions.single()
            assertEquals(listOf("newer", "older"), list.map { it.id })

            val newer = list.first()
            assertEquals("Newer", newer.name)
            assertEquals("/p/newer", newer.cwd)
            assertTrue(newer.isPromoted)
            // The four list-tier placeholders the wire summary does not carry stay at #316 defaults.
            assertEquals("", newer.currentSessionId)
            assertEquals(emptyList<String>(), newer.sessionHistory)
            assertFalse(newer.isSleeping)
            assertFalse(newer.archived)
        }

    // ---- AC #2: the ConversationFilter is applied, each tier sorted desc ------------------------

    @Test
    fun filter_partitionsChannelsDiscussionsAndAll() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            // New collectors after the snapshot loaded immediately receive the current projection.
            val channels = collectConversations(repo, ConversationFilter.Channels)
            val discussions = collectConversations(repo, ConversationFilter.Discussions)
            val archived = collectConversations(repo, ConversationFilter.Archived)
            runCurrent()

            assertEquals(listOf("chan", "disc"), all.last().map { it.id }) // chan 10:00 > disc 09:00
            assertEquals(listOf("chan"), channels.last().map { it.id })
            assertEquals(listOf("disc"), discussions.last().map { it.id })
            // The list payload never carries archived=true (#316 placeholder), so Archived is empty.
            assertEquals(emptyList<String>(), archived.last().map { it.id })
        }

    // ---- AC #3: a server-pushed snapshot re-emits the projection --------------------------------

    @Test
    fun serverPush_reEmitsOnEachChangedSnapshot() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"One","is_promoted":true,"cwd":"/p1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            pump.push(
                conversationsEnvelope(
                    """
                    {"conversations":[
                      {"id":"c1","name":"One","is_promoted":true,"cwd":"/p1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"},
                      {"id":"c2","name":"Two","is_promoted":true,"cwd":"/p2","last_message_ts":"2026-05-08T12:00:00Z","last_used_at":"2026-05-08T12:00:00Z"}
                    ]}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(2, emissions.size)
            assertEquals(listOf("c1"), emissions[0].map { it.id })
            assertEquals(listOf("c2", "c1"), emissions[1].map { it.id })
        }

    // ---- AC #4: multiple concurrent collectors share the single inbound consumer ----------------

    @Test
    fun multipleCollectors_eachReceiveTheSharedProjection() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val a = collectConversations(repo, ConversationFilter.All)
            val b = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            // Each cold subscription issues its own request; the inbound stream stays single-consumer.
            assertEquals(2, pump.sent.size)

            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            assertEquals(listOf("chan", "disc"), a.single().map { it.id })
            assertEquals(listOf("chan", "disc"), b.single().map { it.id })
        }

    // ---- Error handling: a malformed conversations payload is dropped; the collector survives ----

    @Test
    fun malformedSnapshot_isDroppedAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            // Row omits the required non-nullable `cwd` → decode throws → envelope dropped.
            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"bad","name":"x","is_promoted":true,"last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertTrue(emissions.isEmpty()) // nothing surfaced, projection still unloaded

            // A subsequent valid snapshot proves the single inbound collector did not die.
            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"ok","name":"ok","is_promoted":true,"cwd":"/p","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("ok"), emissions.single().map { it.id })
        }

    // ---- Error handling: an unrelated inbound type is ignored (owned by #313/#314/#329) ----------

    @Test
    fun unknownInboundType_isIgnored() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(
                Envelope(
                    id = 1L,
                    type = "messages",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"messages":[]}"""),
                ),
            )
            runCurrent()
            assertTrue(emissions.isEmpty()) // no emission, no crash

            // Still alive: a valid conversations snapshot afterwards surfaces.
            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"ok","name":"ok","is_promoted":true,"cwd":"/p","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("ok"), emissions.single().map { it.id })
        }

    // ---- Every other interface method is a stub naming its owning follow-up ----------------------

    @Test
    fun stubMethods_throwUnsupportedOperationNamingTheFollowUp() =
        runTest {
            val repo = RemoteConversationRepository(FakeSessionPump(), backgroundScope)

            // observeLastMessage is now implemented (#329); observeMessages remains an eager stub.
            assertThrows(UnsupportedOperationException::class.java) { repo.observeMessages("c") }

            // Suspend stubs throw when invoked.
            assertUnsupported { repo.createDiscussion() }
            val sendEx = assertUnsupported { repo.sendMessage("c", "hi") }
            assertTrue(sendEx.message!!.contains("#314"))
            assertUnsupported { repo.promote("c", "name") }
            assertUnsupported { repo.archive("c") }
            assertUnsupported { repo.unarchive("c") }
            assertUnsupported { repo.rename("c", "name") }
            assertUnsupported { repo.startNewSession("c") }
            assertUnsupported { repo.changeWorkspace("c", "/p") }
        }

    // ---- observeLastMessage (#329): live `message` stream → most-recent per conversation ---------

    // AC #2: a never-seen conversation has no entry, so the first emission is null.
    @Test
    fun lastMessage_unknownConversation_emitsNull() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            assertEquals(listOf<Message?>(null), emissions)
        }

    // AC #1, #5: one live `message` surfaces as the mapped most-recent Message.
    @Test
    fun lastMessage_seedOne_surfacesMappedMessage() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "hello", "2026-05-31T10:00:00Z"))
            runCurrent()

            val latest = emissions.last()!!
            assertEquals("m1", latest.id)
            assertEquals(Role.User, latest.role)
            assertEquals("hello", latest.content)
            assertEquals(Instant.parse("2026-05-31T10:00:00Z"), latest.timestamp)
            // The `message` payload carries no session id; the preview never reads it (list-tier "").
            assertEquals("", latest.sessionId)
        }

    // AC #3, #5: a later-ts message for the same conversation re-emits.
    @Test
    fun lastMessage_newerMessage_reEmits() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T11:00:00Z"))
            runCurrent()

            assertEquals(listOf(null, "m1", "m2"), emissions.map { it?.id })
        }

    // AC #1 (most-recent invariant): an earlier-ts arrival is ignored — strictly-greater replaces.
    @Test
    fun lastMessage_olderMessage_doesNotReplace() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "newer", "user", "x", "2026-05-31T11:00:00Z"))
            runCurrent()
            pump.push(messageEnvelope("c1", "older", "user", "y", "2026-05-31T10:00:00Z"))
            runCurrent()

            assertEquals(listOf(null, "newer"), emissions.map { it?.id })
        }

    // Per-conversation keying: each observer reflects only its own conversation's latest.
    @Test
    fun lastMessage_keyedPerConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val c1 = collectLastMessage(repo, "c1")
            val c2 = collectLastMessage(repo, "c2")
            runCurrent()

            pump.push(messageEnvelope("c1", "c1-msg", "user", "in c1", "2026-05-31T10:00:00Z"))
            pump.push(messageEnvelope("c2", "c2-msg", "user", "in c2", "2026-05-31T11:00:00Z"))
            runCurrent()

            assertEquals("c1-msg", c1.last()!!.id)
            assertEquals("c2-msg", c2.last()!!.id)
        }

    // AC #4: a collector subscribing after the message arrived receives the current value first.
    @Test
    fun lastMessage_lateSubscriber_receivesCurrentValue() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            pump.push(messageEnvelope("c1", "m1", "user", "hello", "2026-05-31T10:00:00Z"))
            runCurrent()

            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            assertEquals("m1", emissions.single()!!.id)
        }

    // AC #4: concurrent collectors of one conversation both receive off the single inbound consumer.
    @Test
    fun lastMessage_multipleCollectors_eachReceive() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val a = collectLastMessage(repo, "c1")
            val b = collectLastMessage(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "hello", "2026-05-31T10:00:00Z"))
            runCurrent()

            assertEquals("m1", a.last()!!.id)
            assertEquals("m1", b.last()!!.id)
        }

    // Error handling: an unmappable role is rejected at decode → dropped; the collector survives.
    @Test
    fun lastMessage_malformedPayload_isDroppedAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            // `system` is outside the mappable WireRole set → SerializationException → envelope dropped.
            pump.push(messageEnvelope("c1", "bad", "system", "x", "2026-05-31T10:00:00Z"))
            runCurrent()
            assertEquals(listOf<Message?>(null), emissions)

            // A subsequent valid message proves the single inbound collector did not die.
            pump.push(messageEnvelope("c1", "ok", "user", "y", "2026-05-31T11:00:00Z"))
            runCurrent()
            assertEquals("ok", emissions.last()!!.id)
        }

    // Error handling: a non-parseable envelope ts fails Instant.parse in toMessage → dropped.
    @Test
    fun lastMessage_badTimestamp_isDroppedAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectLastMessage(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "bad", "user", "x", "not-a-timestamp"))
            runCurrent()
            assertEquals(listOf<Message?>(null), emissions)

            pump.push(messageEnvelope("c1", "ok", "user", "y", "2026-05-31T11:00:00Z"))
            runCurrent()
            assertEquals("ok", emissions.last()!!.id)
        }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun TestScope.collectConversations(
        repo: RemoteConversationRepository,
        filter: ConversationFilter,
    ): MutableList<List<Conversation>> {
        val emissions = mutableListOf<List<Conversation>>()
        backgroundScope.launch { repo.observeConversations(filter).collect { emissions += it } }
        return emissions
    }

    private fun TestScope.collectLastMessage(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<Message?> {
        val emissions = mutableListOf<Message?>()
        backgroundScope.launch { repo.observeLastMessage(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun messageEnvelope(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
        ts: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "message",
            ts = ts,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}""",
                ),
        )

    private suspend inline fun assertUnsupported(block: () -> Unit): UnsupportedOperationException {
        val thrown =
            try {
                block()
                null
            } catch (e: UnsupportedOperationException) {
                e
            }
        assertTrue("expected UnsupportedOperationException", thrown != null)
        return thrown!!
    }

    private fun conversationsEnvelope(
        rawConversationsPayload: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "conversations",
            ts = TS,
            payload = MobileJson.parseToJsonElement(rawConversationsPayload),
        )

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-05-31T00:00:00Z"

        // chan: named, promoted, later ts. disc: unnamed, unpromoted scratch, earlier ts.
        val MIXED_FIXTURE =
            """
            {"conversations":[
              {"id":"chan","name":"Channel","is_promoted":true,"cwd":"/p/chan","last_message_ts":"2026-05-08T10:00:00Z","last_used_at":"2026-05-08T10:00:00Z"},
              {"id":"disc","name":null,"is_promoted":false,"cwd":"~/.pyrycode/scratch","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}
            ]}
            """.trimIndent()
    }
}
