package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.ReplayCursor
import de.pyryco.mobile.data.network.ScreenSnapshotPayloadDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- observeMessages (#313): backfill + live `message` stream → ordered thread -------------

    // AC #2: subscribing issues a `backfill_since` request carrying the conversation id.
    @Test
    fun observeMessages_subscribe_issuesBackfillSinceRequestForConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            collectMessages(repo, "c1")
            runCurrent()

            val sent = pump.sent.single()
            assertEquals("backfill_since", sent.type)
            val payload = sent.payload.jsonObject
            assertEquals("c1", payload.getValue("conversation_id").jsonPrimitive.content)
            // The full-history request carries the epoch cursor and an advisory cap (server SSOT #272).
            assertEquals("1970-01-01T00:00:00Z", payload.getValue("since_ts").jsonPrimitive.content)
            assertTrue(
                payload
                    .getValue("max_messages")
                    .jsonPrimitive.content
                    .toInt() > 0,
            )
        }

    // AC #1, #2: backfilled history fills ahead of the live stream; the thread is ordered chunk-then-live.
    @Test
    fun observeMessages_backfillThenLive_emitsOrderedThread() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "m1", "user", "first"),
                        chunkRow("c1", "m2", "assistant", "second"),
                    ),
                ),
            )
            runCurrent()
            pump.push(messageEnvelope("c1", "m3", "user", "live", "2026-05-31T12:00:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "m2", "m3"), messageIds(emissions.last()))
            // The live row maps to a finished MessageItem with content + ts from its own envelope.
            val live = (emissions.last()[2] as ThreadItem.MessageItem).message
            assertEquals("live", live.content)
            assertEquals(Instant.parse("2026-05-31T12:00:00Z"), live.timestamp)
        }

    // AC #2: a message_id present in both the chunk and a later live `message` appears once,
    // position fixed at first occurrence; the live payload wins (last-write-in-place).
    @Test
    fun observeMessages_dedupesByMessageId_fixingFirstPosition() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "m1", "user", "first"),
                        chunkRow("c1", "m2", "assistant", "from-backfill"),
                    ),
                ),
            )
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "assistant", "from-live", "2026-05-31T12:00:00Z"))
            runCurrent()

            val thread = emissions.last()
            assertEquals(listOf("m1", "m2"), messageIds(thread))
            // Same id, updated in place at its original index; the live payload replaces the row.
            assertEquals("from-live", (thread[1] as ThreadItem.MessageItem).message.content)
        }

    // AC #3: a `message` (and chunk) for a different conversation does not re-emit this flow.
    @Test
    fun observeMessages_otherConversation_doesNotReEmit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c2", "x1", "user", "elsewhere", "2026-05-31T10:00:00Z"))
            pump.push(messageChunkEnvelope(listOf(chunkRow("c2", "x2", "user", "also-elsewhere"))))
            runCurrent()

            // Only the initial empty emission — c1's projection never changed.
            assertEquals(listOf(emptyList<String>()), emissions.map { messageIds(it) })
        }

    // AC #1: order follows wire/arrival order, never a client-side timestamp sort. The second live
    // message has an EARLIER ts than the first but still appends last.
    @Test
    fun observeMessages_preservesArrivalOrder_notTimestampSort() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "later-ts", "user", "a", "2026-05-31T11:00:00Z"))
            runCurrent()
            pump.push(messageEnvelope("c1", "earlier-ts", "user", "b", "2026-05-31T10:00:00Z"))
            runCurrent()

            assertEquals(listOf("later-ts", "earlier-ts"), messageIds(emissions.last()))
        }

    // AC #4 (headline): a full round-trip against the v2 server double — backfilled history plus
    // live `message` envelopes yields the expected ordered List<ThreadItem.MessageItem>.
    @Test
    fun observeMessages_roundTripAgainstServerDouble_yieldsExpectedThread() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "h1", "user", "what's the weather?"),
                        chunkRow("c1", "h2", "assistant", "4C, light snow."),
                    ),
                ),
            )
            runCurrent()
            pump.push(messageEnvelope("c1", "l1", "user", "thanks", "2026-05-31T12:00:00Z"))
            runCurrent()

            val thread = emissions.last()
            assertEquals(listOf("h1", "h2", "l1"), messageIds(thread))
            assertEquals(listOf(Role.User, Role.Assistant, Role.User), thread.map { (it as ThreadItem.MessageItem).message.role })
            assertTrue(thread.all { it is ThreadItem.MessageItem })
        }

    // Error handling: a malformed `message_chunk` (one bad row) is dropped whole; the collector survives.
    @Test
    fun observeMessages_malformedChunk_isDroppedAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // `system` is outside the mappable WireRole set → whole-chunk decode throws → dropped.
            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "ok", "user", "fine"),
                        chunkRow("c1", "bad", "system", "nope"),
                    ),
                ),
            )
            runCurrent()
            assertEquals(listOf(emptyList<String>()), emissions.map { messageIds(it) })

            // A subsequent valid chunk proves the single inbound collector did not die.
            pump.push(messageChunkEnvelope(listOf(chunkRow("c1", "later", "user", "y"))))
            runCurrent()
            assertEquals(listOf("later"), messageIds(emissions.last()))
        }

    // ---- sendMessage (#346): send_message request → ack/error correlation → confirmed-insert ----

    // AC #5, #1: the sent envelope matches the send_message wire contract {conversation_id, message_id, text}.
    @Test
    fun sendMessage_sendsRequestMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val send = startSend(repo, "c1", "hi")
            runCurrent()

            val sent = pump.sent.single { it.type == "send_message" }
            val payload = sent.payload.jsonObject
            assertEquals("c1", payload.getValue("conversation_id").jsonPrimitive.content)
            assertEquals("hi", payload.getValue("text").jsonPrimitive.content)
            assertTrue(
                payload
                    .getValue("message_id")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )

            // Resolve so the awaiting coroutine completes cleanly.
            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertEquals(payload.getValue("message_id").jsonPrimitive.content, send().getOrThrow().id)
        }

    // AC #1, #2: the correlated ack resolves to a reconstructed Role.User Message and both read
    // streams re-emit with it (confirmed-insert into observeMessages AND observeLastMessage).
    @Test
    fun sendMessage_onAck_returnsUserMessageAndReEmitsBothStreams() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val thread = collectMessages(repo, "c1")
            val last = collectLastMessage(repo, "c1")
            runCurrent()

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            val sent = pump.sent.single { it.type == "send_message" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            val message = send().getOrThrow()
            assertEquals(Role.User, message.role)
            assertEquals("hi", message.content)
            assertEquals("", message.sessionId)
            assertTrue(message.id.isNotBlank())

            // Both projections now carry the confirmed-inserted message.
            assertEquals(listOf(message.id), messageIds(thread.last()))
            assertEquals(message.id, last.last()!!.id)
        }

    // AC #4: a correlated server error surfaces as RelayErrorException (exposing code); no projection.
    @Test
    fun sendMessage_onServerError_throwsRelayErrorAndLeavesProjectionsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val thread = collectMessages(repo, "c1")
            val last = collectLastMessage(repo, "c1")
            runCurrent()

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            val sent = pump.sent.single { it.type == "send_message" }
            pump.push(errorEnvelope(sent.id, code = "server.binary_offline", retryable = true))
            runCurrent()

            val ex = send().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("server.binary_offline", (ex as RelayErrorException).code)
            assertTrue(ex.retryable)

            // Nothing inserted on the failure path.
            assertEquals(listOf(emptyList<String>()), thread.map { messageIds(it) })
            assertEquals(listOf<Message?>(null), last)
        }

    // AC #3: an unknown conversation (server error conversation.not_found) throws IllegalArgumentException.
    @Test
    fun sendMessage_onConversationNotFound_throwsIllegalArgument() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val thread = collectMessages(repo, "c1")
            runCurrent()

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            val sent = pump.sent.single { it.type == "send_message" }
            pump.push(errorEnvelope(sent.id, code = "conversation.not_found", message = "no such conversation"))
            runCurrent()

            val ex = send().exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
            assertEquals(listOf(emptyList<String>()), thread.map { messageIds(it) })
        }

    // AC #4: a not-Open session (pump.send returns false) throws IllegalStateException; no projection.
    @Test
    fun sendMessage_whenSendReturnsFalse_throwsIllegalStateAndLeavesProjectionsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val thread = collectMessages(repo, "c1")
            val last = collectLastMessage(repo, "c1")
            runCurrent()

            val send = startSend(repo, "c1", "hi")
            runCurrent()

            val ex = send().exceptionOrNull()
            assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
            assertEquals(listOf(emptyList<String>()), thread.map { messageIds(it) })
            assertEquals(listOf<Message?>(null), last)
        }

    // Correlation hygiene: an ack matching no pending request is a no-op; the collector survives and
    // a subsequent real send round-trips successfully.
    @Test
    fun sendMessage_uncorrelatedAck_isNoOpAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            runCurrent()

            pump.push(ackEnvelope(inReplyTo = 999L))
            runCurrent()

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            val sent = pump.sent.single { it.type == "send_message" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertEquals("hi", send().getOrThrow().content)
        }

    // A malformed (undecodable) error payload still unblocks the waiter as a RelayErrorException —
    // the collector never hangs the pending request.
    @Test
    fun sendMessage_onMalformedError_unblocksWaiterAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            val sent = pump.sent.single { it.type == "send_message" }
            // Empty payload omits the required error fields → mapError falls back rather than hanging.
            pump.push(
                Envelope(id = 99L, type = "error", ts = TS, payload = JsonObject(emptyMap()), inReplyTo = sent.id),
            )
            runCurrent()

            assertTrue(send().exceptionOrNull() is RelayErrorException)
        }

    // ---- teardown (#488): in-flight requests fail fast when the connection tears down -----------

    // AC #2, #3: a request awaiting in sendAndAwaitReply throws promptly when the collector scope is
    // cancelled mid-request (teardownActive()'s current.scope.cancel(), the primary trigger) — with
    // the same IllegalStateException the not-connected path surfaces, deliberately NOT a
    // CancellationException (which would read as the caller's own scope dying).
    @Test
    fun teardown_scopeCancelledMidRequest_awaitingCallerThrowsIllegalState() =
        runTest {
            val pump = FakeSessionPump()
            // A cancellable repo scope on the test scheduler, separate from backgroundScope: cancelling
            // it simulates current.scope.cancel() without tearing the test itself down.
            val repoScope = CoroutineScope(coroutineContext + Job())
            val repo = RemoteConversationRepository(pump, repoScope)

            val send = startSend(repo, "c1", "hi")
            runCurrent() // caller registers its deferred, pump.send returns true, suspends on await()
            assertTrue("request should be in-flight", pump.sent.any { it.type == "send_message" })

            repoScope.cancel() // teardownActive()'s current.scope.cancel()
            runCurrent() // finally → failAllPending → await throws → runCatching captures

            val error = send().exceptionOrNull()
            assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
            assertFalse("must not be a CancellationException", error is CancellationException)
        }

    // AC #1 ("or pump.inbound completes"): closing the pump's inbound stream ends the collector and
    // runs the same failAllPending sweep, so the awaiting caller throws IllegalStateException.
    @Test
    fun teardown_inboundCompletes_awaitingCallerThrowsIllegalState() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val send = startSend(repo, "c1", "hi")
            runCurrent()
            assertTrue("request should be in-flight", pump.sent.any { it.type == "send_message" })

            pump.close() // inbound channel closes → receiveAsFlow completes → collect returns → finally
            runCurrent()

            assertTrue(send().exceptionOrNull() is IllegalStateException)
        }

    // ---- createDiscussion (#347): create_conversation request → conversation_created reply ------

    // AC #1, #4: a null workspace sends create_conversation with is_promoted=false and no cwd key
    // (explicitNulls=false omits the null cwd — the server assigns the scratch cwd).
    @Test
    fun createDiscussion_nullWorkspace_sendsCreateConversationWithPromotedFalseAndNoCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startCreate(repo, null)
            runCurrent()

            val sent = pump.sent.single { it.type == "create_conversation" }
            assertEquals(MobileJson.parseToJsonElement("""{"is_promoted":false}"""), sent.payload)

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationCreatedEnvelope(inReplyTo = sent.id, id = "c-new", cwd = DEFAULT_SCRATCH_CWD))
            runCurrent()
        }

    // AC #1, #4: an explicit workspace pins the cwd on the wire.
    @Test
    fun createDiscussion_explicitWorkspace_sendsCreateConversationWithCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startCreate(repo, "/work/proj")
            runCurrent()

            val sent = pump.sent.single { it.type == "create_conversation" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"is_promoted":false,"cwd":"/work/proj"}"""),
                sent.payload,
            )

            pump.push(conversationCreatedEnvelope(inReplyTo = sent.id, id = "c-new", cwd = "/work/proj"))
            runCurrent()
        }

    // AC #1: the conversation_created reply decodes to an unpromoted Conversation whose cwd is the
    // server's reply value, not the (null) workspace arg.
    @Test
    fun createDiscussion_onCreatedReply_returnsUnpromotedConversationWithServerCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(
                conversationCreatedEnvelope(
                    inReplyTo = sentId,
                    id = "c-new",
                    isPromoted = false,
                    name = null,
                    cwd = DEFAULT_SCRATCH_CWD,
                    lastUsedAt = "2026-05-08T10:34:01Z",
                ),
            )
            runCurrent()

            val conversation = create().getOrThrow()
            assertEquals("c-new", conversation.id)
            assertFalse(conversation.isPromoted)
            assertNull(conversation.name)
            // The server-assigned scratch cwd, not the null workspace argument.
            assertEquals(DEFAULT_SCRATCH_CWD, conversation.cwd)
        }

    // AC #2: observeConversations re-emits to include the new conversation after a successful create.
    @Test
    fun createDiscussion_onSuccess_observeConversationsReEmitsIncludingNew() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(
                conversationCreatedEnvelope(
                    inReplyTo = sentId,
                    id = "c-new",
                    cwd = "/work/named",
                    lastUsedAt = "2026-05-08T11:00:00Z",
                ).withWorkspaceLabel("  Named workspace  "),
            )
            runCurrent()

            val created = create().getOrThrow()
            assertEquals("/work/named", created.cwd)
            assertEquals("  Named workspace  ", created.workspaceLabel)
            assertEquals(created, all.last().single { it.id == "c-new" })
            // c-new (11:00) sorts ahead of chan (10:00) and disc (09:00).
            assertEquals(listOf("c-new", "chan", "disc"), all.last().map { it.id })
        }

    // AC #2: the new (unpromoted) conversation surfaces in the Discussions tier.
    @Test
    fun createDiscussion_onSuccess_appearsInDiscussionsTier() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val discussions = collectConversations(repo, ConversationFilter.Discussions)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("disc"), discussions.last().map { it.id })

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(
                conversationCreatedEnvelope(
                    inReplyTo = sentId,
                    id = "c-new",
                    cwd = DEFAULT_SCRATCH_CWD,
                    lastUsedAt = "2026-05-08T11:00:00Z",
                ),
            )
            runCurrent()
            create().getOrThrow()

            // c-new (11:00) sorts ahead of disc (09:00); chan is promoted, so it stays out.
            assertEquals(listOf("c-new", "disc"), discussions.last().map { it.id })
        }

    // AC #3: a server error surfaces as RelayErrorException and leaves the list uncorrupted.
    @Test
    fun createDiscussion_onServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "server.binary_offline", retryable = true))
            runCurrent()

            val ex = create().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("server.binary_offline", (ex as RelayErrorException).code)
            // No partial/failed conversation injected (AC #3 "uncorrupted").
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #3, #4: a not-Open session (pump.send returns false) throws IllegalStateException; no fold.
    @Test
    fun createDiscussion_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreate(repo, null)
            runCurrent()

            val ex = create().exceptionOrNull()
            assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // A malformed conversation_created success reply (missing required field) throws the #318 decode
    // exception before the fold, so a garbage success reply cannot inject a partial conversation.
    @Test
    fun createDiscussion_onMalformedCreatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_created",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"c-bad","name":null,"is_promoted":false,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(create().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // Correlation hygiene: a conversation_created matching no pending request is a no-op; the
    // collector survives and a subsequent real create round-trips successfully.
    @Test
    fun createDiscussion_uncorrelatedCreatedReply_isNoOpAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            runCurrent()

            pump.push(conversationCreatedEnvelope(inReplyTo = 999L, id = "ghost", cwd = DEFAULT_SCRATCH_CWD))
            runCurrent()

            val create = startCreate(repo, null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(conversationCreatedEnvelope(inReplyTo = sentId, id = "c-new", cwd = DEFAULT_SCRATCH_CWD))
            runCurrent()

            assertEquals("c-new", create().getOrThrow().id)
        }

    // ---- createChannel (#956): promoted create_conversation → conversation_created reply --------

    // AC #1: one create_conversation with is_promoted=true and the name and cwd verbatim — the
    // surrounding whitespace survives, so nothing on the way trims.
    @Test
    fun createChannel_sendsOneCreateConversationWithPromotedTrueAndVerbatimNameAndCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startCreateChannel(repo, "  Weekly planning ", "/work/wp")
            runCurrent()

            val sent = pump.sent.single { it.type == "create_conversation" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"is_promoted":true,"name":"  Weekly planning ","cwd":"/work/wp"}"""),
                sent.payload,
            )

            pump.push(conversationCreatedEnvelope(inReplyTo = sent.id, id = "c-new", cwd = "/work/wp", isPromoted = true))
            runCurrent()
        }

    // AC #1: the return is the daemon's confirmed conversation, not the request echoed back.
    @Test
    fun createChannel_onCreatedReply_returnsDaemonValuesNotRequest() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreateChannel(repo, "  Weekly planning ", "/work/wp")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(
                conversationCreatedEnvelope(
                    inReplyTo = sentId,
                    id = "c-chan",
                    isPromoted = true,
                    name = "Weekly planning",
                    cwd = "/srv/work/wp",
                ),
            )
            runCurrent()

            val conversation = create().getOrThrow()
            assertEquals("c-chan", conversation.id)
            assertTrue(conversation.isPromoted)
            assertEquals("Weekly planning", conversation.name)
            assertEquals("/srv/work/wp", conversation.cwd)
        }

    // AC #1: the confirmed conversation appears as a promoted row — Channels, not Discussions.
    @Test
    fun createChannel_onSuccess_appearsInChannelsTierOnly() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val channels = collectConversations(repo, ConversationFilter.Channels)
            val discussions = collectConversations(repo, ConversationFilter.Discussions)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreateChannel(repo, "Weekly planning", "/work/wp")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(
                conversationCreatedEnvelope(
                    inReplyTo = sentId,
                    id = "c-chan",
                    isPromoted = true,
                    name = "Weekly planning",
                    cwd = "/work/wp",
                    lastUsedAt = "2026-05-08T11:00:00Z",
                ),
            )
            runCurrent()

            val created = create().getOrThrow()
            assertEquals(listOf("c-chan", "chan"), channels.last().map { it.id })
            assertEquals(created, channels.last().first())
            assertEquals(listOf("disc"), discussions.last().map { it.id })
        }

    // AC #2: a server error throws RelayErrorException and inserts nothing.
    @Test
    fun createChannel_onServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreateChannel(repo, "Weekly planning", "/work/wp")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "protocol.malformed", retryable = false))
            runCurrent()

            val ex = create().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("protocol.malformed", (ex as RelayErrorException).code)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #2: a disconnected session (pump.send returns false) throws IllegalStateException; no fold.
    @Test
    fun createChannel_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreateChannel(repo, "Weekly planning", "/work/wp")
            runCurrent()

            val ex = create().exceptionOrNull()
            assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #2: a malformed conversation_created reply throws the #318 decode exception before the fold.
    @Test
    fun createChannel_onMalformedCreatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val create = startCreateChannel(repo, "Weekly planning", "/work/wp")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_conversation" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_created",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"c-bad","name":"Weekly planning","is_promoted":true,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            assertTrue(create().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // ---- promote (#348): promote_conversation request → conversation_updated reply --------------

    // AC #1, #4: an explicit workspace pins the cwd; the request carries all three required fields.
    @Test
    fun promote_explicitWorkspace_sendsPromoteConversationWithAllThreeFields() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startPromote(repo, "disc", "weekly-planning", "/work/wp")
            runCurrent()

            val sent = pump.sent.single { it.type == "promote_conversation" }
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"disc","name":"weekly-planning","cwd":"/work/wp"}""",
                ),
                sent.payload,
            )

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "disc", name = "weekly-planning", cwd = "/work/wp"))
            runCurrent()
        }

    // AC #1, #4: a null workspace resolves the conversation's existing cwd from the projection.
    @Test
    fun promote_nullWorkspace_resolvesExistingCwdFromProjection() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            startPromote(repo, "disc", "weekly-planning", null)
            runCurrent()

            val sent = pump.sent.single { it.type == "promote_conversation" }
            // cwd resolved from disc's projection entry (the scratch cwd), not "".
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"disc","name":"weekly-planning","cwd":"$DEFAULT_SCRATCH_CWD"}""",
                ),
                sent.payload,
            )

            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "disc", name = "weekly-planning", cwd = DEFAULT_SCRATCH_CWD))
            runCurrent()
        }

    // AC #1, #4: a null workspace with no projection entry falls back to "" (unreachable-from-UI).
    @Test
    fun promote_nullWorkspaceNoProjectionEntry_fallsBackToEmptyCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startPromote(repo, "ghost", "n", null)
            runCurrent()

            val sent = pump.sent.single { it.type == "promote_conversation" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"ghost","name":"n","cwd":""}"""),
                sent.payload,
            )

            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "ghost", name = "n", cwd = ""))
            runCurrent()
        }

    // AC #1: the conversation_updated reply decodes to a promoted Conversation whose cwd is the
    // server's reply value, not the request's resolved cwd.
    @Test
    fun promote_onUpdatedReply_returnsPromotedConversationWithServerCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val promote = startPromote(repo, "disc", "weekly-planning", null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            pump.push(
                conversationUpdatedEnvelope(
                    inReplyTo = sentId,
                    id = "disc",
                    isPromoted = true,
                    name = "weekly-planning",
                    cwd = "/work/wp",
                    lastUsedAt = "2026-05-08T10:34:30Z",
                ),
            )
            runCurrent()

            val conversation = promote().getOrThrow()
            assertEquals("disc", conversation.id)
            assertTrue(conversation.isPromoted)
            assertEquals("weekly-planning", conversation.name)
            // The server-assigned cwd from the reply, not the request's resolved cwd.
            assertEquals("/work/wp", conversation.cwd)
        }

    // AC #2: a successful promote flips disc into the Channels tier (present in Channels, absent from
    // Discussions), replaced in place (no duplicate, list count unchanged).
    @Test
    fun promote_onSuccess_flipsConversationToChannelsTier() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            val channels = collectConversations(repo, ConversationFilter.Channels)
            val discussions = collectConversations(repo, ConversationFilter.Discussions)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals(listOf("chan"), channels.last().map { it.id })
            assertEquals(listOf("disc"), discussions.last().map { it.id })

            val promote = startPromote(repo, "disc", "weekly-planning", null)
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            pump.push(
                conversationUpdatedEnvelope(
                    inReplyTo = sentId,
                    id = "disc",
                    isPromoted = true,
                    name = "weekly-planning",
                    cwd = DEFAULT_SCRATCH_CWD,
                    lastUsedAt = "2026-05-08T11:00:00Z",
                ),
            )
            runCurrent()
            promote().getOrThrow()

            // Folded in place: still two entries; disc now promoted and (at 11:00) sorts ahead of chan.
            assertEquals(listOf("disc", "chan"), all.last().map { it.id })
            assertEquals(listOf("disc", "chan"), channels.last().map { it.id })
            assertEquals(emptyList<String>(), discussions.last().map { it.id })
        }

    // AC #3: a conversation.not_found error surfaces as IllegalArgumentException; list unchanged.
    @Test
    fun promote_onConversationNotFound_throwsIllegalArgumentAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val promote = startPromote(repo, "missing", "n", "/p")
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            val ex = promote().exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
            assertFalse("conversation.not_found must not be a RelayErrorException", ex is RelayErrorException)
            // No partial promote injected (AC #3 "uncorrupted").
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #3: any other server error surfaces as RelayErrorException; list unchanged.
    @Test
    fun promote_onOtherServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val promote = startPromote(repo, "disc", "n", "/p")
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "server.binary_offline", retryable = true))
            runCurrent()

            val ex = promote().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("server.binary_offline", (ex as RelayErrorException).code)
            // disc is still unpromoted in the list.
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "disc" }.isPromoted)
        }

    // AC #3, #4: a not-Open session (pump.send returns false) throws IllegalStateException; no fold.
    @Test
    fun promote_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val promote = startPromote(repo, "disc", "n", "/p")
            runCurrent()

            assertTrue(promote().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "disc" }.isPromoted)
        }

    // A malformed conversation_updated success reply (missing required field) throws the #318 decode
    // exception before the fold, so a garbage success reply cannot inject a partial promote.
    @Test
    fun promote_onMalformedUpdatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val promote = startPromote(repo, "disc", "n", "/p")
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_updated",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"disc","name":"n","is_promoted":true,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(promote().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "disc" }.isPromoted)
        }

    // AC #2 (#721): a conversation_updated whose in_reply_to matches no pending request is the
    // unsolicited-broadcast shape — it is FOLDED into the projection (it was a no-op before #721), and
    // a subsequent real promote still correlates and round-trips on the same collector.
    @Test
    fun promote_updatedReplyMatchingNoPendingRequest_foldsAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(conversationUpdatedEnvelope(inReplyTo = 999L, id = "ghost", name = "g", cwd = "/p"))
            runCurrent()
            // Folding into the pre-first-snapshot (null) projection yields a single-element list.
            assertEquals(listOf("ghost"), all.last().map { it.id })

            val promote = startPromote(repo, "disc", "weekly-planning", "/work/wp")
            runCurrent()
            val sentId = pump.sent.single { it.type == "promote_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = sentId, id = "disc", name = "weekly-planning", cwd = "/work/wp"))
            runCurrent()

            assertEquals("disc", promote().getOrThrow().id)
        }

    // ---- rename (#530): rename_conversation request → conversation_updated/error correlation -----

    // AC #1: the sent envelope matches the rename_conversation wire contract {conversation_id, name}
    // — exactly two keys, no cwd (contrast promote). The name is forwarded verbatim.
    @Test
    fun rename_sendsRenameConversationWithConversationIdAndName() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startRename(repo, "chan", "Renamed Channel")
            runCurrent()

            val sent = pump.sent.single { it.type == "rename_conversation" }
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"chan","name":"Renamed Channel"}""",
                ),
                sent.payload,
            )

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "chan", name = "Renamed Channel", cwd = "/p/chan"))
            runCurrent()
        }

    // AC #1: the returned Conversation carries the server's reply name, not the request's — the reply
    // is the authority (feed a reply whose name differs from the request to prove it).
    @Test
    fun rename_onUpdatedReply_returnsConversationWithServerName() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val rename = startRename(repo, "chan", "requested-name")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            pump.push(
                conversationUpdatedEnvelope(
                    inReplyTo = sentId,
                    id = "chan",
                    isPromoted = true,
                    name = "server-name",
                    cwd = "/p/chan",
                    lastUsedAt = "2026-05-08T10:34:30Z",
                ),
            )
            runCurrent()

            val conversation = rename().getOrThrow()
            assertEquals("chan", conversation.id)
            // The server-authoritative name from the reply, not the request's "requested-name".
            assertEquals("server-name", conversation.name)
        }

    // AC #1: a successful rename folds the renamed record into the list projection in place (the new
    // name appears in observeConversations, no duplicate, list count unchanged).
    @Test
    fun rename_onSuccess_foldsRenamedConversationIntoList() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("Channel", all.last().single { it.id == "chan" }.name)

            val rename = startRename(repo, "chan", "Renamed Channel")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = sentId, id = "chan", name = "Renamed Channel", cwd = "/p/chan"))
            runCurrent()
            rename().getOrThrow()

            // Folded in place: still two entries; chan now shows the new name.
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("Renamed Channel", all.last().single { it.id == "chan" }.name)
        }

    // AC #2, #3: a not-Open session (pump.send returns false) throws IllegalStateException; no fold.
    @Test
    fun rename_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val rename = startRename(repo, "chan", "Renamed Channel")
            runCurrent()

            assertTrue(rename().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("Channel", all.last().single { it.id == "chan" }.name)
        }

    // AC #2, #3: a conversation.not_found error surfaces as IllegalArgumentException; list unchanged.
    @Test
    fun rename_onConversationNotFound_throwsIllegalArgumentAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val rename = startRename(repo, "missing", "n")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            val ex = rename().exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
            assertFalse("conversation.not_found must not be a RelayErrorException", ex is RelayErrorException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #2, #3: any other server error (e.g. protocol.malformed for an empty title) surfaces as
    // RelayErrorException carrying the code; list unchanged.
    @Test
    fun rename_onOtherServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val rename = startRename(repo, "chan", "n")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "protocol.malformed"))
            runCurrent()

            val ex = rename().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("protocol.malformed", (ex as RelayErrorException).code)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("Channel", all.last().single { it.id == "chan" }.name)
        }

    // A malformed conversation_updated success reply (missing required field) throws the #318 decode
    // exception before the fold, so a garbage success reply cannot inject a partial rename.
    @Test
    fun rename_onMalformedUpdatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val rename = startRename(repo, "chan", "n")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_updated",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"chan","name":"n","is_promoted":true,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(rename().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("Channel", all.last().single { it.id == "chan" }.name)
        }

    // ---- changeWorkspace (#560): change_workspace request → conversation_updated/error correlation,
    // ---- folding the new cwd. Mirrors rename (#530); the reply reuses conversation_updated. ---------

    // AC #1: the sent envelope matches the change_workspace wire contract {conversation_id, cwd} —
    // exactly two keys, the path field is `cwd` (not `workspace`), no `name`. The path is verbatim.
    @Test
    fun changeWorkspace_sendsChangeWorkspaceWithConversationIdAndCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startChangeWorkspace(repo, "chan", "/home/me/proj")
            runCurrent()

            val sent = pump.sent.single { it.type == "change_workspace" }
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"chan","cwd":"/home/me/proj"}""",
                ),
                sent.payload,
            )

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "chan", cwd = "/home/me/proj"))
            runCurrent()
        }

    // AC #2: a successful change_workspace folds the updated record into the list projection in place,
    // and the folded cwd is the server-authoritative reply value (the daemon's resolved realpath), not
    // the request's — feed a reply whose cwd differs from the request to prove it.
    @Test
    fun changeWorkspace_onSuccess_foldsServerAuthoritativeCwdIntoList() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            val original = all.last().single { it.id == "chan" }
            val unrelated = all.last().single { it.id == "disc" }

            val change = startChangeWorkspace(repo, "chan", "/home/me/requested")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            // The daemon confines to $HOME and stores the resolved realpath, which it echoes back.
            pump.push(
                conversationUpdatedEnvelope(inReplyTo = sentId, id = "chan", name = "Channel", cwd = "/home/me/resolved")
                    .withWorkspaceLabel("Destination workspace"),
            )
            runCurrent()
            change().getOrThrow()

            // Folded in place: still two entries; chan now shows the server's resolved cwd.
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            val destination = original.copy(cwd = "/home/me/resolved", workspaceLabel = "Destination workspace")
            assertEquals(destination, all.last().single { it.id == "chan" })

            val clear = startChangeWorkspace(repo, "chan", "/home/me/unnamed")
            runCurrent()
            val clearId = pump.sent.last { it.type == "change_workspace" }.id
            pump.push(
                conversationUpdatedEnvelope(inReplyTo = clearId, id = "chan", name = "Channel", cwd = "/home/me/unnamed")
                    .withWorkspaceLabel(null),
            )
            runCurrent()
            clear().getOrThrow()
            assertEquals(destination.copy(cwd = "/home/me/unnamed", workspaceLabel = null), all.last().single { it.id == "chan" })
            assertEquals(unrelated, all.last().single { it.id == "disc" })
        }

    // § Design ④: the interface forces a Session return, but change_workspace performs no session
    // transition, so the returned placeholder's identity fields are explicitly unassigned (empty
    // strings, never a fabricated UUID); it carries only the arg conversationId and is discarded.
    @Test
    fun changeWorkspace_onSuccess_returnsVestigialPlaceholderSession() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val change = startChangeWorkspace(repo, "chan", "/home/me/proj")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = sentId, id = "chan", cwd = "/home/me/proj"))
            runCurrent()

            val session = change().getOrThrow()
            assertEquals("", session.id)
            assertEquals("", session.claudeSessionUuid)
            assertEquals("chan", session.conversationId)
        }

    // AC #3: a not-Open session (pump.send returns false) throws IllegalStateException; no fold.
    @Test
    fun changeWorkspace_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val change = startChangeWorkspace(repo, "chan", "/home/me/proj")
            runCurrent()

            assertTrue(change().exceptionOrNull() is IllegalStateException)
            assertEquals("/p/chan", all.last().single { it.id == "chan" }.cwd)
        }

    // AC #3: a conversation.not_found error surfaces as IllegalArgumentException (family-typed, not a
    // RelayErrorException) and is deliberately NOT swallowed by the guard; list unchanged.
    @Test
    fun changeWorkspace_onConversationNotFound_throwsIllegalArgumentAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val change = startChangeWorkspace(repo, "missing", "/home/me/proj")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            val ex = change().exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
            assertFalse("conversation.not_found must not be a RelayErrorException", ex is RelayErrorException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #3: any other server error — including protocol.malformed for a path the daemon rejects as
    // outside $HOME — surfaces as RelayErrorException carrying the code; list unchanged. The daemon's
    // static string is never a path (confidentiality), so the exception leaks no picked path.
    @Test
    fun changeWorkspace_onOtherServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val change = startChangeWorkspace(repo, "chan", "/etc/outside-home")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            pump.push(errorEnvelope(sentId, code = "protocol.malformed", message = "workspace rejected"))
            runCurrent()

            val ex = change().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("protocol.malformed", (ex as RelayErrorException).code)
            assertFalse("must not echo the picked path", ex.message.orEmpty().contains("/etc/outside-home"))
            assertEquals("/p/chan", all.last().single { it.id == "chan" }.cwd)
        }

    // A malformed conversation_updated success reply (missing required field) throws the #318 decode
    // exception before the fold, so a garbage success reply cannot inject a partial workspace change.
    @Test
    fun changeWorkspace_onMalformedUpdatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val change = startChangeWorkspace(repo, "chan", "/home/me/proj")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_updated",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"chan","name":"n","is_promoted":true,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(change().exceptionOrNull() is IllegalArgumentException)
            assertEquals("/p/chan", all.last().single { it.id == "chan" }.cwd)
        }

    // ---- create_workspace_folder (#564): create_workspace_folder request → workspace_folder_created/
    // ---- error correlation, returning the daemon's canonical created path (no projection fold) -------

    // AC #1: the sent envelope matches the create_workspace_folder wire contract {parent, name} —
    // exactly two keys. `parent` is the fixed `~/pyry-workspace` client root (tilde-anchored to the
    // daemon $HOME), and `name` is the client-trimmed value (input has surrounding whitespace).
    @Test
    fun createWorkspaceFolder_sendsFixedParentAndTrimmedName() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startCreateWorkspaceFolder(repo, "  foo  ")
            runCurrent()

            val sent = pump.sent.single { it.type == "create_workspace_folder" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"parent":"~/pyry-workspace","name":"foo"}"""),
                sent.payload,
            )

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(workspaceFolderCreatedEnvelope(inReplyTo = sent.id, path = "/home/op/pyry-workspace/foo"))
            runCurrent()
        }

    // AC #1/#2: success returns the daemon's server-authoritative path verbatim — NOT a client-side
    // join of the request. The reply path is deliberately unrelated to the sent name to prove the
    // client returns the reply's value. This also proves the demux registration (§ Design ④): without
    // the new workspace_folder_created arm the deferred never completes and getOrThrow would hang.
    @Test
    fun createWorkspaceFolder_onSuccess_returnsServerAuthoritativePath() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreateWorkspaceFolder(repo, "foo")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_workspace_folder" }.id
            pump.push(workspaceFolderCreatedEnvelope(inReplyTo = sentId, path = "/home/op/pyry-workspace/resolved-elsewhere"))
            runCurrent()

            assertEquals("/home/op/pyry-workspace/resolved-elsewhere", create().getOrThrow())
        }

    // AC #4: a blank / whitespace-only name fails with IllegalArgumentException BEFORE any send — no
    // create_workspace_folder frame reaches the wire.
    @Test
    fun createWorkspaceFolder_blankName_throwsIllegalArgumentAndSendsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val empty = startCreateWorkspaceFolder(repo, "")
            val whitespace = startCreateWorkspaceFolder(repo, "   ")
            runCurrent()

            assertTrue(empty().exceptionOrNull() is IllegalArgumentException)
            assertTrue(whitespace().exceptionOrNull() is IllegalArgumentException)
            assertTrue(pump.sent.none { it.type == "create_workspace_folder" })
        }

    // AC #3: a not-Open session (pump.send returns false) throws IllegalStateException; nothing returned.
    @Test
    fun createWorkspaceFolder_whenSendReturnsFalse_throwsIllegalState() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreateWorkspaceFolder(repo, "foo")
            runCurrent()

            assertTrue(create().exceptionOrNull() is IllegalStateException)
        }

    // AC #3: any server error — create_workspace_folder has no not_found code, so every reject is
    // protocol.malformed — surfaces as RelayErrorException carrying the code. The daemon's static
    // string is never a path/name (confidentiality), so the exception leaks no picked name.
    @Test
    fun createWorkspaceFolder_onServerError_throwsRelayErrorAndLeaksNoName() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreateWorkspaceFolder(repo, "../escape")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_workspace_folder" }.id
            pump.push(errorEnvelope(sentId, code = "protocol.malformed", message = "invalid folder name"))
            runCurrent()

            val ex = create().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("protocol.malformed", (ex as RelayErrorException).code)
            assertFalse("must not echo the attempted name", ex.message.orEmpty().contains("escape"))
        }

    // A malformed workspace_folder_created reply (missing required `path`) throws the #318 decode
    // exception, so a garbage success reply cannot yield a bogus path.
    @Test
    fun createWorkspaceFolder_onMalformedReply_throws() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val create = startCreateWorkspaceFolder(repo, "foo")
            runCurrent()
            val sentId = pump.sent.single { it.type == "create_workspace_folder" }.id
            // Payload omits the required `path` → WorkspaceFolderCreatedPayloadDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "workspace_folder_created",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{}"""),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(create().exceptionOrNull() is IllegalArgumentException)
        }

    // ---- recentWorkspaces (#565): recent_workspaces request → recent_workspaces_list reply, a cold
    // ---- one-shot read verb (no projection fold), fail-closed-to-empty on any wire/connection error ----

    // AC #1: the sent envelope matches the recent_workspaces wire contract — an empty `{}` payload
    // (cloned from list_conversations), and it is the only frame construction emits.
    @Test
    fun recentWorkspaces_sendsEmptyPayloadRequest() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startRecentWorkspaces(repo)
            runCurrent()

            val sent = pump.sent.single { it.type == "recent_workspaces" }
            assertEquals(MobileJson.parseToJsonElement("""{}"""), sent.payload)
            assertEquals(1, pump.sent.size)

            // Unblock the launched collector so backgroundScope completes cleanly.
            pump.push(recentWorkspacesListEnvelope(inReplyTo = sent.id, paths = emptyList()))
            runCurrent()
        }

    // AC #1/#2/#3: the populated reply emits its paths in WIRE ORDER (deliberately non-alphabetical to
    // prove the client does not re-sort — ordering is daemon-authoritative), with both "no bound
    // workspace" sentinels filtered client-side (the empty string and DEFAULT_SCRATCH_CWD). This also
    // proves the demux registration (§ Design ④): without the recent_workspaces_list arm the deferred
    // never completes and the flow degrades to empty, failing the assertion.
    @Test
    fun recentWorkspaces_populatedReply_filtersSentinelsAndPreservesWireOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val recents = startRecentWorkspaces(repo)
            runCurrent()
            val sentId = pump.sent.single { it.type == "recent_workspaces" }.id
            pump.push(
                recentWorkspacesListEnvelope(
                    inReplyTo = sentId,
                    paths = listOf("/z/proj", "", "~/.pyrycode/scratch", "/a/proj"),
                ),
            )
            runCurrent()

            assertEquals(listOf("/z/proj", "/a/proj"), recents().getOrThrow())
        }

    // AC #5: an empty registry ({"workspaces":[]}) emits an empty list — not an error.
    @Test
    fun recentWorkspaces_emptyRegistry_emitsEmptyList() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val recents = startRecentWorkspaces(repo)
            runCurrent()
            val sentId = pump.sent.single { it.type == "recent_workspaces" }.id
            pump.push(recentWorkspacesListEnvelope(inReplyTo = sentId, paths = emptyList()))
            runCurrent()

            assertEquals(emptyList<String>(), recents().getOrThrow())
        }

    // AC #4: a not-Open session (pump.send returns false) degrades to empty — the not-connected `check`
    // throws IllegalStateException synchronously → .catch → empty, WITHOUT any reply and without hanging.
    @Test
    fun recentWorkspaces_whenNotConnected_emitsEmptyWithoutReply() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val recents = startRecentWorkspaces(repo)
            runCurrent()

            assertEquals(emptyList<String>(), recents().getOrThrow())
        }

    // AC #4: any server `error` reply (recent_workspaces names no conversation, so every code is an
    // ordinary RelayErrorException) is caught by the flow's .catch and degraded to empty.
    @Test
    fun recentWorkspaces_serverError_emitsEmpty() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val recents = startRecentWorkspaces(repo)
            runCurrent()
            val sentId = pump.sent.single { it.type == "recent_workspaces" }.id
            pump.push(errorEnvelope(sentId, code = "internal", message = "boom"))
            runCurrent()

            assertEquals(emptyList<String>(), recents().getOrThrow())
        }

    // A malformed recent_workspaces_list reply (a row missing the required `path`) throws at decode and
    // is caught by .catch → empty, so a garbage success reply cannot yield a bogus list.
    @Test
    fun recentWorkspaces_malformedReply_emitsEmpty() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val recents = startRecentWorkspaces(repo)
            runCurrent()
            val sentId = pump.sent.single { it.type == "recent_workspaces" }.id
            pump.push(
                Envelope(
                    id = 99L,
                    type = "recent_workspaces_list",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"workspaces":[{"last_used_at":"$TS"}]}"""),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            assertEquals(emptyList<String>(), recents().getOrThrow())
        }

    // ---- observeSessionSettings (#590): request_session_settings → session_settings, a cold read that
    // ---- re-issues on four triggers. Payload SHAPES are proven at the decode boundary
    // ---- (SessionSettingsPayloadsTest); this block owns the wire round trip, the triggers and the
    // ---- isolation properties -------------------------------------------------------------------

    // AC #1/#4: subscribing issues exactly ONE frame, it is a request_session_settings naming the
    // conversation, and nothing else rides along — in particular no set_session_settings.
    @Test
    fun observeSessionSettings_onSubscription_sendsOnlyTheRequestNamingTheConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            collectSessionSettings(repo, "c1")
            runCurrent()

            val sent = pump.sent.single()
            assertEquals("request_session_settings", sent.type)
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"c1"}"""), sent.payload)
        }

    // AC #1: the correlated reply reaches the collector as a decoded reading, preceded by the null that
    // resets a fresh subscription to "unavailable". This also proves the demux registration — without
    // the session_settings arm in onInbound the read never completes and only the null would land.
    @Test
    fun observeSessionSettings_correlatedReply_emitsDecodedReadingAfterInitialNull() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(sessionSettingsEnvelope(inReplyTo = pump.sent.single().id))
            runCurrent()

            assertEquals(2, emissions.size)
            assertNull(emissions.first())
            val reading = requireNotNull(emissions.last())
            assertEquals("sess-a", reading.sessionId)
            assertEquals("high", reading.effort)
            assertEquals(EffectiveEffort.Applied("medium"), reading.effectiveEffort)
            assertEquals("default", reading.permissionMode)
        }

    // AC #4, trigger ③: this conversation's session transition issues a FRESH read, and the newer
    // reply replaces the older reading.
    @Test
    fun observeSessionSettings_sessionTransition_issuesFreshReadAndReplacesReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(sessionSettingsEnvelope(inReplyTo = pump.sent.single().id))
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", previousSessionId = "sess-a", newSessionId = "sess-b", reason = "clear"))
            runCurrent()
            val second = pump.sent.filter { it.type == "request_session_settings" }
            assertEquals(2, second.size)
            pump.push(sessionSettingsEnvelope(inReplyTo = second.last().id, raw = REPLACEMENT_SETTINGS))
            runCurrent()

            assertEquals("sess-b", requireNotNull(emissions.last()).sessionId)
            assertEquals(EffectiveEffort.NotReported, requireNotNull(emissions.last()).effectiveEffort)
        }

    // AC #1/#4: a transition for ANOTHER conversation leaves this reading alone and sends no second
    // frame — the per-conversation revision slice, not a global tick.
    @Test
    fun observeSessionSettings_transitionForAnotherConversation_doesNotReRead() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(sessionSettingsEnvelope(inReplyTo = pump.sent.single().id))
            runCurrent()

            pump.push(sessionTransitionEnvelope("c2", previousSessionId = "x", newSessionId = "y", reason = "clear"))
            runCurrent()

            assertEquals(1, pump.sent.count { it.type == "request_session_settings" })
            assertEquals(2, emissions.size)
        }

    // AC #4, trigger ④: the caller's own invalidation — what a settled settings write uses — issues a
    // fresh read on the flow the caller already collects.
    @Test
    fun refreshSessionSettings_issuesFreshRead() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(sessionSettingsEnvelope(inReplyTo = pump.sent.single().id))
            runCurrent()

            repo.refreshSessionSettings("c1")
            runCurrent()
            val asks = pump.sent.filter { it.type == "request_session_settings" }
            assertEquals(2, asks.size)
            pump.push(sessionSettingsEnvelope(inReplyTo = asks.last().id, raw = REPLACEMENT_SETTINGS))
            runCurrent()

            assertEquals("sess-b", requireNotNull(emissions.last()).sessionId)
        }

    // AC #1: a reply to a SUPERSEDED request — one whose session has since been replaced — cannot
    // overwrite the current reading. The in-flight read is cancelled by the newer trigger and its
    // pending entry deregistered, so the late reply correlates with nothing.
    @Test
    fun observeSessionSettings_replyToSupersededRequest_cannotOverwriteCurrentReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            val staleAsk = pump.sent.single { it.type == "request_session_settings" }

            // The session is replaced before the first reply ever lands.
            pump.push(sessionTransitionEnvelope("c1", previousSessionId = "sess-a", newSessionId = "sess-b", reason = "clear"))
            runCurrent()
            val freshAsk = pump.sent.last { it.type == "request_session_settings" }
            pump.push(sessionSettingsEnvelope(inReplyTo = freshAsk.id, raw = REPLACEMENT_SETTINGS))
            runCurrent()

            // ...and only now does the retired context's reply turn up.
            pump.push(sessionSettingsEnvelope(inReplyTo = staleAsk.id, raw = POPULATED_SETTINGS))
            runCurrent()

            assertEquals("sess-b", requireNotNull(emissions.last()).sessionId)
        }

    // AC #1: a duplicate reply cannot overwrite the reading either — the deferred is removed on
    // completion, so the second copy completes nothing and emits nothing.
    @Test
    fun observeSessionSettings_duplicateReply_changesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            val ask = pump.sent.single { it.type == "request_session_settings" }
            pump.push(sessionSettingsEnvelope(inReplyTo = ask.id, raw = POPULATED_SETTINGS))
            runCurrent()
            val afterFirst = emissions.size

            pump.push(sessionSettingsEnvelope(inReplyTo = ask.id, raw = REPLACEMENT_SETTINGS))
            runCurrent()

            assertEquals(afterFirst, emissions.size)
            assertEquals("sess-a", requireNotNull(emissions.last()).sessionId)
        }

    // AC #4: two conversations' readings are independent — each is routed by the id its own read asked
    // with, and the replies carry no conversation identity that could cross-route them.
    @Test
    fun observeSessionSettings_twoConversations_readIndependently() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val first = collectSessionSettings(repo, "c1")
            val second = collectSessionSettings(repo, "c2")
            runCurrent()
            val asks = pump.sent.filter { it.type == "request_session_settings" }
            assertEquals(2, asks.size)

            pump.push(sessionSettingsEnvelope(inReplyTo = asks.first().id, raw = POPULATED_SETTINGS))
            runCurrent()

            assertEquals("sess-a", requireNotNull(first.last()).sessionId)
            assertNull(second.last())
        }

    // AC #4: without the negotiated `interactive` capability the daemon leaves this verb fully inert, so
    // the client fails closed — no frame at all, and the reading stays unavailable rather than hanging.
    @Test
    fun observeSessionSettings_withoutInteractive_sendsNothingAndStaysUnavailable() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()

            assertTrue(pump.sent.none { it.type == "request_session_settings" })
            assertEquals(listOf(null, null), emissions)
        }

    // AC #4: a failed read leaves the reading UNAVAILABLE — never device defaults, never another
    // conversation's values — and the flow survives to read again on the next trigger.
    @Test
    fun observeSessionSettings_errorReply_leavesReadingUnavailableAndFlowAlive() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(errorEnvelope(pump.sent.single().id, code = "server.binary_offline", retryable = true))
            runCurrent()
            assertNull(emissions.last())

            repo.refreshSessionSettings("c1")
            runCurrent()
            val retry = pump.sent.last { it.type == "request_session_settings" }
            pump.push(sessionSettingsEnvelope(inReplyTo = retry.id, raw = POPULATED_SETTINGS))
            runCurrent()

            assertEquals("sess-a", requireNotNull(emissions.last()).sessionId)
        }

    // AC #2/#4: a malformed reply fails the frame and leaves the reading unavailable — the decode
    // exception is confined to this one read and reaches no collector as a crash.
    @Test
    fun observeSessionSettings_malformedReply_leavesReadingUnavailable() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()
            pump.push(sessionSettingsEnvelope(inReplyTo = pump.sent.single().id, raw = """{"session_id":"sess-a"}"""))
            runCurrent()

            assertNull(emissions.last())
        }

    // AC #4: a not-Open pump fails the read closed rather than throwing into the collector.
    @Test
    fun observeSessionSettings_notConnected_leavesReadingUnavailable() =
        runTest {
            val pump = FakeSessionPump().apply { sendResult = false }
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })

            val emissions = collectSessionSettings(repo, "c1")
            runCurrent()

            assertEquals(listOf(null, null), emissions)
        }

    // ---- requestHistory (#623): request_history → history_page, a correlated one-shot read with no
    // ---- projection fold. Page SHAPES are proven at the decode boundary (HistoryPayloadsTest); this
    // ---- block owns the wire round-trip and the failure routing ----------------------------------

    // AC #1: the sent envelope matches the request_history wire contract — the conversation, the
    // cursor handed back verbatim and the limit, all three keys present under their snake_case names.
    @Test
    fun requestHistory_sendsConversationCursorAndLimit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startRequestHistory(repo, "c1", cursor = CURSOR, limit = 50)
            runCurrent()

            val sent = pump.sent.single { it.type == "request_history" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"c1","cursor":"$CURSOR","limit":50}"""),
                sent.payload,
            )

            // Unblock the launched caller so backgroundScope completes cleanly.
            pump.push(historyPageEnvelope(inReplyTo = sent.id, raw = EMPTY_TERMINAL_PAGE))
            runCurrent()
        }

    // AC #1/#2: the correlated reply resolves to a decoded page. This also proves the demux
    // registration — without the history_page arm in onInbound the deferred never completes and this
    // assertion hangs rather than failing (the delete/create-family hazard).
    @Test
    fun requestHistory_correlatedReply_resolvesToDecodedPage() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val page = startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.single { it.type == "request_history" }.id
            pump.push(
                historyPageEnvelope(
                    inReplyTo = sentId,
                    raw =
                        """
                        {"entries":[
                          {"id":412,"type":"assistant_delta","payload":{"text":"b"},"ts":"2026-09-05T10:58:12Z"},
                          {"id":411,"type":"send_message","payload":{"text":"a"},"ts":"2026-09-05T10:57:03Z"}
                        ],"cursor":"$CURSOR","at_start":false}
                        """.trimIndent(),
                ),
            )
            runCurrent()

            val decoded = page().getOrThrow()
            assertEquals(listOf(412L, 411L), decoded.entries.map { it.id })
            assertEquals(CURSOR, decoded.cursor)
            assertFalse(decoded.atStart)
        }

    // AC #4: `history.unavailable` — the one RETRYABLE member of the group — reaches the caller with
    // its code and retryable flag intact. mapError needed no new mapping for it; this pins that the
    // generic arm actually carries the distinction a walking caller needs.
    @Test
    fun requestHistory_unavailableReject_surfacesRetryableRelayError() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val page = startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.single { it.type == "request_history" }.id
            pump.push(errorEnvelope(sentId, code = "history.unavailable", retryable = true))
            runCurrent()

            val error = page().exceptionOrNull()
            assertTrue(error is RelayErrorException)
            assertEquals("history.unavailable", (error as RelayErrorException).code)
            assertTrue(error.retryable)
        }

    // AC #4: the three permanent history.* codes reach the caller as non-retryable RelayErrorExceptions
    // carrying their own code — a caller can tell "stop asking" from "ask again".
    @Test
    fun requestHistory_permanentRejects_surfaceNonRetryableRelayErrors() =
        runTest {
            for (code in listOf("history.invalid_cursor", "history.invalid_page_size", "history.invalid_request")) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope)

                val page = startRequestHistory(repo, "c1")
                runCurrent()
                val sentId = pump.sent.single { it.type == "request_history" }.id
                pump.push(errorEnvelope(sentId, code = code, retryable = false))
                runCurrent()

                val error = page().exceptionOrNull()
                assertTrue("$code should be a RelayErrorException", error is RelayErrorException)
                assertEquals(code, (error as RelayErrorException).code)
                assertFalse(error.retryable)
            }
        }

    // AC #4: `conversation.not_found` — the daemon's answer for an unknown conversation id, and the
    // one code that behaves differently — surfaces as the IllegalArgumentException the repository
    // contract pins, not as a generic RelayErrorException.
    @Test
    fun requestHistory_unknownConversation_surfacesIllegalArgument() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val page = startRequestHistory(repo, "nope")
            runCurrent()
            val sentId = pump.sent.single { it.type == "request_history" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            assertTrue(page().exceptionOrNull() is IllegalArgumentException)
        }

    // AC #4: a not-Open session fails fast with IllegalStateException — the send `check` throws before
    // anything is awaited, so the caller never hangs waiting for a reply that cannot come.
    @Test
    fun requestHistory_whenNotConnected_failsWithoutAwaiting() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val page = startRequestHistory(repo, "c1")
            runCurrent()

            assertTrue(page().exceptionOrNull() is IllegalStateException)
        }

    // AC #4, the load-bearing one: a malformed page fails ONLY the ask that drew it. The shared inbound
    // collector survives (a second request on the same repository still completes), and another
    // conversation's thread projection is untouched by the bad frame.
    @Test
    fun requestHistory_malformedPage_failsOnlyThatAskAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val otherThread = mutableListOf<List<ThreadItem>>()
            backgroundScope.launch { repo.observeMessages("other").collect { otherThread += it } }
            runCurrent()

            val bad = startRequestHistory(repo, "c1")
            runCurrent()
            val badId = pump.sent.single { it.type == "request_history" }.id
            // `at_start` missing entirely — the absence that must never read as "keep walking".
            pump.push(historyPageEnvelope(inReplyTo = badId, raw = """{"entries":[],"cursor":""}""", id = 90L))
            runCurrent()

            assertTrue(bad().exceptionOrNull() is IllegalArgumentException)

            // The collector is still alive: an unrelated conversation's live message still folds...
            pump.push(messageEnvelope("other", "m1", "user", "still here", TS, id = 91L))
            runCurrent()
            assertEquals(listOf("still here"), otherThread.last().map { (it as ThreadItem.MessageItem).message.content })

            // ...and a second history ask on the same repository still completes.
            val good = startRequestHistory(repo, "c1")
            runCurrent()
            val goodId = pump.sent.last { it.type == "request_history" }.id
            pump.push(historyPageEnvelope(inReplyTo = goodId, raw = EMPTY_TERMINAL_PAGE, id = 92L))
            runCurrent()

            assertTrue(good().getOrThrow().atStart)
        }

    // ---- #645: requestHistory folds its page into the thread. The reduction and the merge are proven
    // ---- exhaustively and without a relay in HistoryPageReducerTest; this block owns the WIRING —
    // ---- that the fold happens at all, where it lands, and what it leaves alone -------------------

    // AC #1: a page's rows reach observeMessages, oldest-first and ahead of what is already there,
    // and the page is still returned to the caller for its cursor / at_start.
    @Test
    fun requestHistory_foldsThePageAheadOfTheLiveThreadAndStillReturnsIt() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c1")
            runCurrent()
            pump.push(messageEnvelope("c1", "live-1", "user", "live", TS))
            runCurrent()

            val page = startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.last { it.type == "request_history" }.id
            pump.push(
                historyPageEnvelope(
                    inReplyTo = sentId,
                    raw =
                        """
                        {"entries":[
                          {"id":2,"type":"message","payload":{"conversation_id":"c1","message_id":"h2","role":"assistant","text":"b"},"ts":"2026-09-05T10:02:00Z"},
                          {"id":1,"type":"message","payload":{"conversation_id":"c1","message_id":"h1","role":"user","text":"a"},"ts":"2026-09-05T10:01:00Z"}
                        ],"cursor":"$CURSOR","at_start":false}
                        """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(listOf("h1", "h2", "live-1"), messageIds(thread.last()))
            assertEquals(CURSOR, page().getOrThrow().cursor)
        }

    // Routing: the fold lands under the conversation the client ASKED about, never under an entry
    // payload's own conversation_id — a hostile page cannot write into a neighbouring thread.
    @Test
    fun requestHistory_foldsUnderTheAskedConversation_notThePayloadsOwn() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val asked = collectMessages(repo, "c1")
            val other = collectMessages(repo, "elsewhere")
            runCurrent()

            startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.last { it.type == "request_history" }.id
            pump.push(
                historyPageEnvelope(
                    inReplyTo = sentId,
                    raw =
                        """
                        {"entries":[
                          {"id":1,"type":"message","payload":{"conversation_id":"elsewhere","message_id":"h1","role":"user","text":"a"},"ts":"$TS"}
                        ],"cursor":"","at_start":true}
                        """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(listOf("h1"), messageIds(asked.last()))
            assertEquals(emptyList<ThreadItem>(), other.last())
        }

    // AC #4: a page of stored STATE frames folds no row and — the point of the criterion — reaches no
    // live-state holder. A stored `stall` does not re-stall, a stored `api_retry` does not re-open the
    // retry indicator, a stored `compacting` does not restart it, and none of them reach the live-event
    // stream the thinking indicator and the modal overlay read.
    @Test
    fun requestHistory_pageOfStateFrames_leavesEveryLiveStateHolderUntouched() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c1")
            val stall = collectStall(repo, "c1")
            val queue = collectQueue(repo, "c1")
            val events = collectLiveEvents(repo)
            val modals = collectModalEvents(repo)
            runCurrent()

            startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.last { it.type == "request_history" }.id
            pump.push(
                historyPageEnvelope(
                    inReplyTo = sentId,
                    raw =
                        """
                        {"entries":[
                          {"id":5,"type":"modal_shown","payload":{"modal_id":"md","class":"permission","title":"t","prompt":"p","options":[],"default_option_id":""},"ts":"$TS"},
                          {"id":4,"type":"compacting","payload":{"conversation_id":"c1","active":true},"ts":"$TS"},
                          {"id":3,"type":"api_retry","payload":{"conversation_id":"c1","active":true,"attempt":{"current":1,"total":3}},"ts":"$TS"},
                          {"id":2,"type":"queue_state","payload":{"conversation_id":"c1","queued":[{"queued_msg_id":1,"message_id":"m-fixture","text":"q","ts":"$TS"}]},"ts":"$TS"},
                          {"id":1,"type":"turn_state","payload":{"conversation_id":"c1","state":"thinking"},"ts":"$TS"}
                        ],"cursor":"","at_start":true}
                        """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(emptyList<ThreadItem>(), thread.last())
            assertEquals(listOf(false), stall)
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(emptyList<ModalEvent>(), modals)
        }

    // A failed ask mutates nothing: the fold is unreachable on every failure path, so a rejected walk
    // step leaves the thread exactly as the live lane left it.
    @Test
    fun requestHistory_rejectedAsk_foldsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c1")
            runCurrent()
            pump.push(messageEnvelope("c1", "live-1", "user", "live", TS))
            runCurrent()

            val outcome = startRequestHistory(repo, "c1")
            runCurrent()
            val sentId = pump.sent.last { it.type == "request_history" }.id
            pump.push(errorEnvelope(inReplyTo = sentId, code = "history.unavailable", message = "busy", retryable = true, id = 93L))
            runCurrent()

            assertTrue(outcome().exceptionOrNull() is RelayErrorException)
            assertEquals(listOf("live-1"), messageIds(thread.last()))
        }

    // ---- #810: a tool call's input fields and parent identity reach the retained row on both lanes

    // AC #3: the same tool_use + tool_result pair, once live under c1 and once replayed through a
    // history_page under c2, retains the same ToolCall. Timestamps differ by lane by design (the live
    // clock vs the stored entry's ts), so the comparison is the ToolCall, not the whole Message.
    @Test
    fun toolCall_liveAndHistoryLanes_retainIdenticalRows() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val live = collectMessages(repo, "c1")
            val replayed = collectMessages(repo, "c2")
            runCurrent()

            pump.push(
                Envelope(id = 1L, type = "tool_use", ts = TS, payload = MobileJson.parseToJsonElement(TOOL_USE_810.replace("CONV", "c1"))),
            )
            pump.push(
                Envelope(
                    id = 2L,
                    type = "tool_result",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement(TOOL_RESULT_810.replace("CONV", "c1")),
                ),
            )
            runCurrent()

            startRequestHistory(repo, "c2")
            runCurrent()
            val sentId = pump.sent.last { it.type == "request_history" }.id
            val useEntry = TOOL_USE_810.replace("CONV", "c2")
            val resultEntry = TOOL_RESULT_810.replace("CONV", "c2")
            pump.push(
                historyPageEnvelope(
                    inReplyTo = sentId,
                    raw =
                        """
                        {"entries":[
                          {"id":2,"type":"tool_result","payload":$resultEntry,"ts":"2026-09-05T10:02:00Z"},
                          {"id":1,"type":"tool_use","payload":$useEntry,"ts":"2026-09-05T10:01:00Z"}
                        ],"cursor":"","at_start":true}
                        """.trimIndent(),
                ),
            )
            runCurrent()

            val liveCall = (live.last().single() as ThreadItem.MessageItem).message.toolCall
            val replayedCall = (replayed.last().single() as ThreadItem.MessageItem).message.toolCall
            assertEquals(
                ToolCall(
                    toolName = "Edit",
                    input = "a.kt",
                    output = "ok",
                    status = ToolCallStatus.Done,
                    inputFields = mapOf("file_path" to "../src/a.kt", "old_string" to "x\ny…"),
                    parentToolUseId = "agent-1",
                ),
                liveCall,
            )
            assertEquals(liveCall, replayedCall)
        }

    // AC #4: frames for c1 never alter c2's retained rows — even a tool_result for c1 whose
    // tool_use_id collides with a row c2 already holds.
    @Test
    fun toolCall_framesForOneConversation_neverAlterAnothersRows() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val first = collectMessages(repo, "c1")
            val second = collectMessages(repo, "c2")
            runCurrent()

            pump.push(
                Envelope(id = 1L, type = "tool_use", ts = TS, payload = MobileJson.parseToJsonElement(TOOL_USE_810.replace("CONV", "c2"))),
            )
            runCurrent()
            val before = second.last()

            pump.push(
                Envelope(
                    id = 2L,
                    type = "tool_use",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","turn_id":"t9","tool_use_id":"tu-other","parent_tool_use_id":"agent-9",""" +
                                """"name":"Bash","input_summary":"ls","input":{"command":"ls"}}""",
                        ),
                ),
            )
            pump.push(
                Envelope(
                    id = 3L,
                    type = "tool_result",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement(TOOL_RESULT_810.replace("CONV", "c1").replace("\"ok\"", "\"boom\"")),
                ),
            )
            runCurrent()

            assertEquals(before, second.last())
            assertEquals(ToolCallStatus.Running, (second.last().single() as ThreadItem.MessageItem).message.toolCall?.status)
            val firstCall = (first.last().single() as ThreadItem.MessageItem).message.toolCall
            assertEquals(mapOf("command" to "ls"), firstCall?.inputFields)
            assertEquals("agent-9", firstCall?.parentToolUseId)
        }

    // ---- archive / unarchive (#549): archive_conversation / unarchive_conversation request →
    // ---- conversation_updated/error correlation, folding the is_archived flag ---------------------

    // AC #1: the sent envelope matches the archive_conversation wire contract — payload is
    // {conversation_id} only (a single shared id-only DTO serves both verbs).
    @Test
    fun archive_sendsArchiveConversationWithConversationId() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startArchive(repo, "chan")
            runCurrent()

            val sent = pump.sent.single { it.type == "archive_conversation" }
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"chan"}"""), sent.payload)

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "chan", cwd = "/p/chan", isArchived = true))
            runCurrent()
        }

    // AC #2: the sent envelope matches the unarchive_conversation wire contract — same id-only payload.
    @Test
    fun unarchive_sendsUnarchiveConversationWithConversationId() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startUnarchive(repo, "chan")
            runCurrent()

            val sent = pump.sent.single { it.type == "unarchive_conversation" }
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"chan"}"""), sent.payload)

            pump.push(conversationUpdatedEnvelope(inReplyTo = sent.id, id = "chan", cwd = "/p/chan", isArchived = false))
            runCurrent()
        }

    // AC #1: a successful archive folds is_archived:true into the projection — chan moves under the
    // Archived filter and leaves Channels.
    @Test
    fun archive_onSuccess_foldsConversationIntoArchived() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            val channels = collectConversations(repo, ConversationFilter.Channels)
            val archivedList = collectConversations(repo, ConversationFilter.Archived)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(listOf("chan"), channels.last().map { it.id })
            assertEquals(emptyList<String>(), archivedList.last().map { it.id })

            val archive = startArchive(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "archive_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = sentId, id = "chan", name = "Channel", cwd = "/p/chan", isArchived = true))
            runCurrent()
            archive().getOrThrow()

            // Folded in place: chan is now archived — present under Archived, gone from Channels, still in All.
            assertEquals(listOf("chan"), archivedList.last().map { it.id })
            assertEquals(emptyList<String>(), channels.last().map { it.id })
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertTrue(all.last().single { it.id == "chan" }.archived)
        }

    // AC #2: a successful unarchive folds is_archived:false — chan returns to Channels. First archive it
    // (the only way to reach an archived local state, since the list snapshot never carries archived).
    @Test
    fun unarchive_onSuccess_foldsConversationBackIntoChannels() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val channels = collectConversations(repo, ConversationFilter.Channels)
            val archivedList = collectConversations(repo, ConversationFilter.Archived)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val archive = startArchive(repo, "chan")
            runCurrent()
            val archiveId = pump.sent.single { it.type == "archive_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = archiveId, id = "chan", name = "Channel", cwd = "/p/chan", isArchived = true))
            runCurrent()
            archive().getOrThrow()
            assertEquals(listOf("chan"), archivedList.last().map { it.id })

            val unarchive = startUnarchive(repo, "chan")
            runCurrent()
            val unarchiveId = pump.sent.single { it.type == "unarchive_conversation" }.id
            pump.push(
                conversationUpdatedEnvelope(inReplyTo = unarchiveId, id = "chan", name = "Channel", cwd = "/p/chan", isArchived = false),
            )
            runCurrent()
            unarchive().getOrThrow()

            assertEquals(listOf("chan"), channels.last().map { it.id })
            assertEquals(emptyList<String>(), archivedList.last().map { it.id })
            assertFalse(channels.last().single { it.id == "chan" }.archived)
        }

    // Per pyrycode#881 a re-archive is an idempotent no-op that still broadcasts the (unchanged) state.
    // Folding a reply whose is_archived already matches local state succeeds without error, no duplicate.
    @Test
    fun archive_idempotentReArchive_foldsWithoutError() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val archivedList = collectConversations(repo, ConversationFilter.Archived)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val first = startArchive(repo, "chan")
            runCurrent()
            val firstId = pump.sent.single { it.type == "archive_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = firstId, id = "chan", name = "Channel", cwd = "/p/chan", isArchived = true))
            runCurrent()
            first().getOrThrow()

            val second = startArchive(repo, "chan")
            runCurrent()
            val secondId = pump.sent.last { it.type == "archive_conversation" }.id
            pump.push(conversationUpdatedEnvelope(inReplyTo = secondId, id = "chan", name = "Channel", cwd = "/p/chan", isArchived = true))
            runCurrent()
            second().getOrThrow()

            // Still exactly one archived entry — the equal-value re-upsert is a benign no-op.
            assertEquals(listOf("chan"), archivedList.last().map { it.id })
        }

    // AC #3: a not-Open session throws IllegalStateException (not UnsupportedOperationException); no fold.
    @Test
    fun archive_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val archive = startArchive(repo, "chan")
            runCurrent()

            assertTrue(archive().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "chan" }.archived)
        }

    // AC #3: the disconnected path holds for unarchive too — IllegalStateException, no fold.
    @Test
    fun unarchive_whenSendReturnsFalse_throwsIllegalStateAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val unarchive = startUnarchive(repo, "chan")
            runCurrent()

            assertTrue(unarchive().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // A conversation.not_found error surfaces as IllegalArgumentException (mirrors the fake); no fold.
    @Test
    fun archive_onConversationNotFound_throwsIllegalArgumentAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val archive = startArchive(repo, "missing")
            runCurrent()
            val sentId = pump.sent.single { it.type == "archive_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            val ex = archive().exceptionOrNull()
            assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
            assertFalse("conversation.not_found must not be a RelayErrorException", ex is RelayErrorException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // Any other server error surfaces as RelayErrorException carrying the code; no fold.
    @Test
    fun archive_onOtherServerError_throwsRelayErrorAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val archive = startArchive(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "archive_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "internal.error"))
            runCurrent()

            val ex = archive().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("internal.error", (ex as RelayErrorException).code)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "chan" }.archived)
        }

    // A malformed conversation_updated success reply (missing required field) throws the #318 decode
    // exception before the fold, so a garbage success reply cannot inject a partial archive.
    @Test
    fun archive_onMalformedUpdatedReply_throwsAndLeavesListUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val archive = startArchive(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "archive_conversation" }.id
            // Payload omits the required `cwd` → ConversationResponseDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_updated",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"chan","name":"Channel","is_promoted":true,"is_archived":true,"last_used_at":"2026-05-08T10:00:00Z"}""",
                        ),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(archive().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertFalse(all.last().single { it.id == "chan" }.archived)
        }

    // ---- delete (#532): delete_conversation request → conversation_deleted ack / error ------------

    // AC #1 / codec: the sent envelope matches the delete_conversation wire contract — an id-only
    // payload (exactly {conversation_id}, no name/cwd).
    @Test
    fun delete_sendsDeleteConversationWithConversationId() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startDelete(repo, "chan")
            runCurrent()

            val sent = pump.sent.single { it.type == "delete_conversation" }
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"chan"}"""), sent.payload)

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(conversationDeletedEnvelope(inReplyTo = sent.id, id = "chan"))
            runCurrent()
        }

    // AC #1: a successful delete removes chan from ALL THREE read projections — the list, the thread,
    // and the last-message preview. This is the delete-specific divergence from archive's in-place fold.
    @Test
    fun delete_onSuccess_removesFromAllThreeStreams() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            val messages = collectMessages(repo, "chan")
            val lastMessage = collectLastMessage(repo, "chan")
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            pump.push(messageEnvelope("chan", "m1", "user", "hi", ts = TS))
            runCurrent()
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals(listOf("m1"), messageIds(messages.last()))
            assertEquals("m1", lastMessage.last()?.id)

            val delete = startDelete(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            pump.push(conversationDeletedEnvelope(inReplyTo = sentId, id = "chan"))
            runCurrent()
            delete().getOrThrow()

            assertEquals(listOf("disc"), all.last().map { it.id })
            assertEquals(emptyList<ThreadItem>(), messages.last())
            assertNull(lastMessage.last())
        }

    // The ack field is `id`, NOT `conversation_id`: an ack whose only key is `conversation_id` has no
    // `id`, so it is rejected at the decode boundary and nothing is removed — pins the field-name SSOT.
    @Test
    fun delete_ackWithConversationIdKeyInsteadOfId_isMalformedAndLeavesStreamsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val delete = startDelete(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_deleted",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"chan"}"""),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // Missing required `id` → SerializationException (an IllegalArgumentException subtype).
            assertTrue(delete().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #3: a not-Open session throws IllegalStateException; no removal from any stream.
    @Test
    fun delete_whenSendReturnsFalse_throwsIllegalStateAndLeavesStreamsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val delete = startDelete(repo, "chan")
            runCurrent()

            assertTrue(delete().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #4: conversation.not_found CONVERGES on the delete post-condition — the result is SUCCESS
    // (not the IllegalArgumentException failure archive surfaces), and chan is removed locally.
    @Test
    fun delete_onConversationNotFound_convergesAsSuccessAndRemoves() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val delete = startDelete(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            assertTrue("not_found must converge as success", delete().isSuccess)
            assertEquals(listOf("disc"), all.last().map { it.id })
        }

    // AC #4: not_found on an already-absent id is a no-op success — converges without a re-emit
    // (removing an absent id yields an equals-identical projection, which StateFlow conflates).
    @Test
    fun delete_notFoundOnAbsentId_isNoOpSuccess() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            val emissionsBefore = all.size

            val delete = startDelete(repo, "missing")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "conversation.not_found"))
            runCurrent()

            assertTrue(delete().isSuccess)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals(emissionsBefore, all.size)
        }

    // Any other server error surfaces as RelayErrorException carrying the code; no removal.
    @Test
    fun delete_onOtherServerError_throwsRelayErrorAndLeavesStreamsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val delete = startDelete(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            pump.push(errorEnvelope(sentId, code = "internal.error"))
            runCurrent()

            val ex = delete().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("internal.error", (ex as RelayErrorException).code)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // AC #2: a malformed conversation_deleted ack (missing required id) throws the #318 decode
    // exception before the removal, so a garbage ack cannot delete a conversation.
    @Test
    fun delete_onMalformedAck_throwsAndLeavesStreamsUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()

            val delete = startDelete(repo, "chan")
            runCurrent()
            val sentId = pump.sent.single { it.type == "delete_conversation" }.id
            // Payload omits the required `id` → ConversationDeletedPayloadDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "conversation_deleted",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{}"""),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(delete().exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
        }

    // ---- setSessionSettings (#543): set_session_settings request → session_settings_updated/error --

    // AC #2, #1: a single-control (model) change sends exactly {session_id, model} — effort/yolo are
    // ABSENT, proving null = omitted = "leave unchanged" (the presence contract).
    @Test
    fun setSessionSettings_modelOnly_sendsOnlyModelField() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "opus")
            runCurrent()

            val sent = pump.sent.single { it.type == "set_session_settings" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"session_id":"s1","model":"opus"}"""),
                sent.payload,
            )

            // Unblock the launched coroutine so backgroundScope completes cleanly.
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1"))
            runCurrent()
        }

    // AC #2, #1: a single-control (effort) change sends exactly {session_id, effort}.
    @Test
    fun setSessionSettings_effortOnly_sendsOnlyEffortField() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startSetSessionSettings(repo, "s1", effort = "high")
            runCurrent()

            val sent = pump.sent.single { it.type == "set_session_settings" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"session_id":"s1","effort":"high"}"""),
                sent.payload,
            )
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1"))
            runCurrent()
        }

    // AC #1: a non-null yolo=false IS sent (not omitted), so the daemon can distinguish "set false"
    // from "leave unchanged" — the crux of the presence contract.
    @Test
    fun setSessionSettings_yoloFalse_isSentNotOmitted() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startSetSessionSettings(repo, "s1", yolo = false)
            runCurrent()

            val sent = pump.sent.single { it.type == "set_session_settings" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"session_id":"s1","yolo":false}"""),
                sent.payload,
            )
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1"))
            runCurrent()
        }

    // #650: a permission-mode change carries `permission_mode` alone — no `yolo`, which the daemon would
    // reject as malformed beside it.
    @Test
    fun setSessionSettings_permissionMode_isSentWithoutYolo() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startSetSessionSettings(repo, "s1", permissionMode = "plan")
            runCurrent()

            val sent = pump.sent.single { it.type == "set_session_settings" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"session_id":"s1","permission_mode":"plan"}"""),
                sent.payload,
            )
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1"))
            runCurrent()
        }

    // #650: a frame carrying both posture spellings cannot be built, so nothing is sent.
    @Test
    fun setSessionSettings_permissionModeWithYolo_isRefusedBeforeSending() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val outcome = startSetSessionSettings(repo, "s1", yolo = true, permissionMode = "plan")
            runCurrent()

            assertTrue(outcome().exceptionOrNull() is IllegalArgumentException)
            assertTrue(pump.sent.none { it.type == "set_session_settings" })
        }

    // AC #1: a combined change carries all four keys.
    @Test
    fun setSessionSettings_combinedFields_sendsAllFields() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            startSetSessionSettings(repo, "s1", model = "opus", effort = "high", yolo = true)
            runCurrent()

            val sent = pump.sent.single { it.type == "set_session_settings" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"session_id":"s1","model":"opus","effort":"high","yolo":true}"""),
                sent.payload,
            )
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1"))
            runCurrent()
        }

    // AC #2: the correlated session_settings_updated ack completes the call (proves the demux arm
    // routes the new reply type to the awaiting waiter).
    @Test
    fun setSessionSettings_onAck_completesSuccessfully() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "opus")
            runCurrent()
            val sentId = pump.sent.single { it.type == "set_session_settings" }.id
            pump.push(sessionSettingsUpdatedEnvelope(inReplyTo = sentId, sessionId = "s1"))
            runCurrent()

            assertTrue("expected success, got ${settings().exceptionOrNull()}", settings().isSuccess)
        }

    // A malformed session_settings_updated ack (missing required session_id) throws the #318 decode
    // exception through the typed reply boundary.
    @Test
    fun setSessionSettings_onMalformedAck_throwsDecodeException() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "opus")
            runCurrent()
            val sentId = pump.sent.single { it.type == "set_session_settings" }.id
            // Payload omits the required `session_id` → SessionSettingsUpdatedPayloadDto decode throws.
            pump.push(
                Envelope(
                    id = 99L,
                    type = "session_settings_updated",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{}"""),
                    inReplyTo = sentId,
                ),
            )
            runCurrent()

            // SerializationException is an IllegalArgumentException subtype.
            assertTrue(settings().exceptionOrNull() is IllegalArgumentException)
        }

    // AC #3: an unhosted session (session.not_found) surfaces as RelayErrorException carrying the code
    // — and, unlike the conversation-scoped verbs, NOT an IllegalArgumentException (no IAE-crash path).
    @Test
    fun setSessionSettings_onSessionNotFound_throwsRelayErrorWithCode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "opus")
            runCurrent()
            val sentId = pump.sent.single { it.type == "set_session_settings" }.id
            pump.push(errorEnvelope(sentId, code = "session.not_found"))
            runCurrent()

            val ex = settings().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            // Asserted before the `as` cast below smart-casts `ex` (which would make this check dead).
            assertFalse("session.not_found must not be an IllegalArgumentException", ex is IllegalArgumentException)
            assertEquals("session.not_found", (ex as RelayErrorException).code)
        }

    // AC #3: an invalid model/effort (protocol.malformed) surfaces as RelayErrorException carrying code.
    @Test
    fun setSessionSettings_onProtocolMalformed_throwsRelayErrorWithCode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "not-a-model")
            runCurrent()
            val sentId = pump.sent.single { it.type == "set_session_settings" }.id
            pump.push(errorEnvelope(sentId, code = "protocol.malformed"))
            runCurrent()

            val ex = settings().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("protocol.malformed", (ex as RelayErrorException).code)
        }

    // AC #3: the not-connected path (pump.send returns false) throws IllegalStateException; no reply.
    @Test
    fun setSessionSettings_whenSendReturnsFalse_throwsIllegalState() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val settings = startSetSessionSettings(repo, "s1", model = "opus")
            runCurrent()

            assertTrue(settings().exceptionOrNull() is IllegalStateException)
        }

    // ---- registerPushToken (#359): register_push_token request → ack/error correlation ---------

    // AC #1, #2: the sent envelope matches the register_push_token wire contract
    // {platform, token, device_name}; device_name is sourced from the injected constructor param.
    @Test
    fun registerPushToken_sendsRequestMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val register = startRegisterPushToken(repo, "fcm-tok-123")
            runCurrent()

            val sent = pump.sent.single { it.type == "register_push_token" }
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"platform":"fcm","token":"fcm-tok-123","device_name":"Pixel-8"}""",
                ),
                sent.payload,
            )

            // Resolve so the awaiting coroutine completes cleanly.
            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertTrue(register().isSuccess)
        }

    // AC #3: the correlated empty ack completes the call successfully (no throw).
    @Test
    fun registerPushToken_onAck_completesSuccessfully() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val register = startRegisterPushToken(repo, "fcm-tok-123")
            runCurrent()
            val sent = pump.sent.single { it.type == "register_push_token" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertTrue(register().isSuccess)
        }

    // AC #4: a retryable server error surfaces as RelayErrorException exposing code + retryable.
    @Test
    fun registerPushToken_onRetryableServerError_throwsRelayErrorWithRetryableTrue() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val register = startRegisterPushToken(repo, "fcm-tok-123")
            runCurrent()
            val sent = pump.sent.single { it.type == "register_push_token" }
            pump.push(errorEnvelope(sent.id, code = "server.binary_busy", retryable = true))
            runCurrent()

            val ex = register().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("server.binary_busy", (ex as RelayErrorException).code)
            assertTrue(ex.retryable)
        }

    // AC #4: a non-retryable server error surfaces with retryable == false, so a caller can branch.
    @Test
    fun registerPushToken_onNonRetryableServerError_throwsRelayErrorWithRetryableFalse() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val register = startRegisterPushToken(repo, "fcm-tok-123")
            runCurrent()
            val sent = pump.sent.single { it.type == "register_push_token" }
            pump.push(errorEnvelope(sent.id, code = "auth.invalid_token", retryable = false))
            runCurrent()

            val ex = register().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("auth.invalid_token", (ex as RelayErrorException).code)
            assertFalse(ex.retryable)
        }

    // AC #5: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang — no reply is ever fed, yet the call has already completed exceptionally.
    @Test
    fun registerPushToken_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val register = startRegisterPushToken(repo, "fcm-tok-123")
            runCurrent()

            assertTrue(register().exceptionOrNull() is IllegalStateException)
        }

    // ---- answerModal / cancelModal (#438): modal_answer / modal_cancel outbound control ----------

    // AC #1, #5: the sent modal_answer payload matches the wire contract — exactly the three keys
    // {modal_id, option_id, answer_token}; modal_id/option_id echoed verbatim, answer_token present.
    @Test
    fun answerModal_sendsModalAnswerMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val answer = startAnswerModal(repo, "mdl-7f3a", "allow")
            runCurrent()

            val sent = pump.sent.single { it.type == "modal_answer" }
            val payload = sent.payload.jsonObject
            assertEquals(setOf("modal_id", "option_id", "answer_token"), payload.keys)
            assertEquals("mdl-7f3a", payload.getValue("modal_id").jsonPrimitive.content)
            assertEquals("allow", payload.getValue("option_id").jsonPrimitive.content)
            assertTrue(
                payload
                    .getValue("answer_token")
                    .jsonPrimitive.content
                    .isNotEmpty(),
            )

            // Resolve so the awaiting coroutine completes cleanly.
            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertTrue(answer().isSuccess)
        }

    // AC #2, #5: two resends of one logical answer (same modal_id + option_id) carry a STABLE token,
    // so the daemon dedups the replay to a no-op rather than double-answering.
    @Test
    fun answerModal_tokenIsStableAcrossResendOfSameAnswer() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            startAnswerModal(repo, "mdl-7f3a", "allow")
            startAnswerModal(repo, "mdl-7f3a", "allow")
            runCurrent()

            val tokens =
                pump.sent
                    .filter { it.type == "modal_answer" }
                    .map {
                        it.payload.jsonObject
                            .getValue("answer_token")
                            .jsonPrimitive.content
                    }
            assertEquals(2, tokens.size)
            assertEquals(tokens[0], tokens[1])
        }

    // AC #2, #5: distinct answers carry DISTINCT tokens — both option_id (deny vs allow) and modal_id
    // (mdl-OTHER vs mdl-7f3a, SAME option id) must participate, so no two collapse to one.
    @Test
    fun answerModal_tokenIsDistinctAcrossDistinctAnswers() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            startAnswerModal(repo, "mdl-7f3a", "allow")
            startAnswerModal(repo, "mdl-7f3a", "deny")
            startAnswerModal(repo, "mdl-OTHER", "allow")
            runCurrent()

            val tokens =
                pump.sent
                    .filter { it.type == "modal_answer" }
                    .map {
                        it.payload.jsonObject
                            .getValue("answer_token")
                            .jsonPrimitive.content
                    }
            assertEquals(3, tokens.size)
            assertEquals(3, tokens.toSet().size)
        }

    // AC #4: the correlated empty ack completes the call successfully (no throw).
    @Test
    fun answerModal_onAck_completesSuccessfully() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val answer = startAnswerModal(repo, "mdl-7f3a", "allow")
            runCurrent()
            val sent = pump.sent.single { it.type == "modal_answer" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertTrue(answer().isSuccess)
        }

    // AC #4: a server error (stand-in for the #702 ungranted-device reject) surfaces as a
    // RelayErrorException exposing code + retryable — this slice does NOT degrade (#440 does).
    @Test
    fun answerModal_onServerError_throwsRelayErrorExposingCode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val answer = startAnswerModal(repo, "mdl-7f3a", "allow")
            runCurrent()
            val sent = pump.sent.single { it.type == "modal_answer" }
            pump.push(errorEnvelope(sent.id, code = "modal.answer_forbidden", retryable = false))
            runCurrent()

            val ex = answer().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("modal.answer_forbidden", (ex as RelayErrorException).code)
            assertFalse(ex.retryable)
        }

    // AC #4: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang — no reply is ever fed, yet the call has already completed exceptionally.
    @Test
    fun answerModal_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val answer = startAnswerModal(repo, "mdl-7f3a", "allow")
            runCurrent()

            assertTrue(answer().exceptionOrNull() is IllegalStateException)
        }

    // #818: an accepted always-allow offer rides the answer as `always_allow: true`.
    @Test
    fun answerModal_withAlwaysAllow_sendsTheFlagSet() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            startAnswerModal(repo, "mdl-7f3a", "allow_once", alwaysAllow = true)
            runCurrent()

            val payload =
                pump.sent
                    .single { it.type == "modal_answer" }
                    .payload.jsonObject
            assertEquals(setOf("modal_id", "option_id", "answer_token", "always_allow"), payload.keys)
            assertEquals(JsonPrimitive(true), payload.getValue("always_allow"))
        }

    // #818: an unset flag is omitted rather than sent as `false`, so the ordinary answer's bytes are unchanged.
    @Test
    fun answerModal_withoutAlwaysAllow_omitsTheFlag() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            startAnswerModal(repo, "mdl-7f3a", "allow_once", alwaysAllow = false)
            runCurrent()

            val payload =
                pump.sent
                    .single { it.type == "modal_answer" }
                    .payload.jsonObject
            assertEquals(setOf("modal_id", "option_id", "answer_token"), payload.keys)
        }

    // AC #3: the sent modal_cancel payload matches the single-key wire contract {modal_id}.
    @Test
    fun cancelModal_sendsModalCancelMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val cancel = startCancelModal(repo, "mdl-7f3a")
            runCurrent()

            val sent = pump.sent.single { it.type == "modal_cancel" }
            assertEquals(MobileJson.parseToJsonElement("""{"modal_id":"mdl-7f3a"}"""), sent.payload)

            // Resolve so the awaiting coroutine completes cleanly.
            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertTrue(cancel().isSuccess)
        }

    // AC #4: the correlated empty ack completes the cancel successfully (no throw).
    @Test
    fun cancelModal_onAck_completesSuccessfully() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val cancel = startCancelModal(repo, "mdl-7f3a")
            runCurrent()
            val sent = pump.sent.single { it.type == "modal_cancel" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertTrue(cancel().isSuccess)
        }

    // AC #4: a server error surfaces as RelayErrorException exposing code + retryable.
    @Test
    fun cancelModal_onServerError_throwsRelayErrorExposingCode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val cancel = startCancelModal(repo, "mdl-7f3a")
            runCurrent()
            val sent = pump.sent.single { it.type == "modal_cancel" }
            pump.push(errorEnvelope(sent.id, code = "modal.stale_id", retryable = false))
            runCurrent()

            val ex = cancel().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("modal.stale_id", (ex as RelayErrorException).code)
            assertFalse(ex.retryable)
        }

    // AC #4: a not-Open session (pump.send returns false) fails fast with IllegalStateException.
    @Test
    fun cancelModal_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val cancel = startCancelModal(repo, "mdl-7f3a")
            runCurrent()

            assertTrue(cancel().exceptionOrNull() is IllegalStateException)
        }

    // Explicit target, fire-and-forget: prior traffic in A must not redirect Stop from B.
    @Test
    fun interrupt_targetsBWithoutAwaitingReplyOrChangingMessages() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")
            val messagesA = collectMessages(repo, "c-a")
            val messagesB = collectMessages(repo, "c-b")
            runCurrent()
            pump.push(messageEnvelope("c-b", "b1", "user", "in B", "2026-05-31T10:00:00Z"))
            pump.push(messageEnvelope("c-a", "a1", "user", "in A", "2026-05-31T11:00:00Z"))
            repo.startNewSession("c-a", null)
            runCurrent()
            val beforeA = messagesA.toList()
            val beforeB = messagesB.toList()

            repo.interrupt("c-b")
            runCurrent()

            val sent = pump.sent.single { it.type == "interrupt" }
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"c-b"}"""), sent.payload)
            assertEquals(beforeA, messagesA)
            assertEquals(beforeB, messagesB)
        }

    // AC #3: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang (no awaited reply).
    @Test
    fun interrupt_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val outcome = runCatching { repo.interrupt("c-b") }

            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        }

    // Reset targets the viewed conversation, independent of prior traffic in another conversation.
    @Test
    fun startNewSession_targetsBWithAndWithoutPriorActivityInA_withoutChangingMessages() =
        runTest {
            for (activityInA in listOf(false, true)) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")
                val messagesA = collectMessages(repo, "c-a")
                val messagesB = collectMessages(repo, "c-b")
                runCurrent()
                pump.push(messageEnvelope("c-b", "b1", "user", "in B", "2026-05-31T10:00:00Z"))
                if (activityInA) {
                    pump.push(messageEnvelope("c-a", "a1", "user", "in A", "2026-05-31T11:00:00Z"))
                    repo.startNewSession("c-a", null)
                }
                runCurrent()
                val beforeA = messagesA.toList()
                val beforeB = messagesB.toList()

                // No reply is provided; the send must complete immediately.
                val session = repo.startNewSession("c-b", null)
                runCurrent()

                val sent = pump.sent.last { it.type == "new_session" }
                assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"c-b"}"""), sent.payload)
                assertEquals("c-b", session.conversationId)
                assertEquals("", session.id)
                assertEquals(beforeA, messagesA)
                assertEquals(beforeB, messagesB)
            }
        }

    // AC #2, #3: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang (no awaited reply).
    @Test
    fun startNewSession_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")
            val messages = collectMessages(repo, "c-1")
            runCurrent()
            pump.push(messageEnvelope("c-1", "m1", "user", "retained", "2026-05-31T10:00:00Z"))
            runCurrent()
            val before = messages.toList()
            pump.sendResult = false

            val outcome = runCatching { repo.startNewSession("c-1", null) }
            runCurrent()

            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
            assertEquals(before, messages)
        }

    // ---- dropQueuedMessage (#466, #859): a fire-and-forget dequeue_message, settled by queue_state ----
    // The daemon never replies to dequeue_message (protocol-mobile.md § Queue (v2)), so no test here
    // pushes an ack or error for it: the only confirmation is a later queue_state lacking the item.

    // #466 AC #1, #5: the sent dequeue_message payload matches the two-key wire contract
    // {conversation_id, queued_msg_id}; queued_msg_id is a JSON NUMBER (the uint64), not a quoted
    // string, and no extra keys are present. #859 AC #1: the call returns once the frame is sent.
    @Test
    fun dropQueuedMessage_sendsDequeueMessageAndReturnsWithoutReply() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()

            val sent = pump.sent.single { it.type == "dequeue_message" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"c-1","queued_msg_id":42}"""),
                sent.payload,
            )
            assertTrue(drop().isSuccess)
        }

    // #720 width guard: a queued_msg_id near Long.MAX_VALUE encodes as a bare JSON number (a uint64),
    // never a quoted string — the round-trip is Long → number with no precision loss.
    @Test
    fun dropQueuedMessage_largeQueuedMsgId_encodesAsBareNumber() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val drop = startDropQueuedMessage(repo, "c-1", 9223372036854775807L)
            runCurrent()

            val sent = pump.sent.single { it.type == "dequeue_message" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"c-1","queued_msg_id":9223372036854775807}"""),
                sent.payload,
            )
            assertTrue(drop().isSuccess)
        }

    // #466 AC #2: the send writes NO local projection — the backlog is owned by observeQueue (#460)
    // and updated only by a later queue_state, never by this send.
    @Test
    fun dropQueuedMessage_withoutSnapshot_mutatesNoProjection() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val queue = collectQueue(repo, "c-1")
            val messages = collectMessages(repo, "c-1")
            val lastMessage = collectLastMessage(repo, "c-1")
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()

            assertTrue(drop().isSuccess)
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
            assertEquals(listOf(emptyList<ThreadItem>()), messages)
            assertEquals(listOf<Message?>(null), lastMessage)
        }

    // #859 AC #2: a not-Open session (pump.send returns false) fails fast with IllegalStateException,
    // and because the drop was never sent, a later snapshot without the item removes nothing.
    @Test
    fun dropQueuedMessage_whenNotConnected_throwsAndLeavesEchoInPlace() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val queued = sendAndAck(repo, pump, "c-1", "two")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = queued))))
            runCurrent()

            pump.sendResult = false
            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertTrue(drop().exceptionOrNull() is IllegalStateException)
            assertEquals(listOf(queued), messageIds(thread.last()))
        }

    // ---- #781 / #859: a confirmed drop also removes the undelivered echo this device minted --------

    // #859 AC #1: the drop settles when a queue_state for the conversation arrives without the dropped
    // queued_msg_id — and not before. It removes the entry's own echo and NOTHING else: every other
    // thread row keeps its place, in order.
    @Test
    fun dropQueuedMessage_settlesOnQueueStateWithoutItem_removesOwnEchoOnly() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val first = sendAndAck(repo, pump, "c-1", "one")
            val queued = sendAndAck(repo, pump, "c-1", "two")
            val third = sendAndAck(repo, pump, "c-1", "three")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = queued))))
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            assertTrue(drop().isSuccess)
            assertEquals(listOf(first, queued, third), messageIds(thread.last()))

            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertEquals(listOf(first, third), messageIds(thread.last()))
        }

    // #859 AC #1: a snapshot that still carries the dropped id (the backlog changed for another reason,
    // e.g. an enqueue landed before the dequeue) does not settle the drop; the next one without it does.
    @Test
    fun dropQueuedMessage_snapshotStillCarryingItem_keepsEchoUntilItLeaves() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val queued = sendAndAck(repo, pump, "c-1", "two")
            val later = sendAndAck(repo, pump, "c-1", "three")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = queued))))
            runCurrent()

            startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            pump.push(
                queueStateEnvelope(
                    "c-1",
                    listOf(QueuedFixture(42L, "two", TS, messageId = queued), QueuedFixture(43L, "three", TS, messageId = later)),
                ),
            )
            runCurrent()
            assertEquals(listOf(queued, later), messageIds(thread.last()))

            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(43L, "three", TS, messageId = later))))
            runCurrent()
            assertEquals(listOf(later), messageIds(thread.last()))
        }

    // #859 AC #2: an item that leaves the snapshot without a drop request from this device keeps its
    // echo — a drain shrinks the backlog exactly as a drop does, so removal is keyed on the dropped id.
    @Test
    fun dropQueuedMessage_drainedItemWithoutDropRequest_keepsItsEcho() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val drained = sendAndAck(repo, pump, "c-1", "one")
            val dropped = sendAndAck(repo, pump, "c-1", "two")
            pump.push(
                queueStateEnvelope(
                    "c-1",
                    listOf(QueuedFixture(41L, "one", TS, messageId = drained), QueuedFixture(42L, "two", TS, messageId = dropped)),
                ),
            )
            runCurrent()

            startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            // One snapshot carries both the head's drain and the drop.
            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertEquals(listOf(drained), messageIds(thread.last()))
        }

    // #859 AC #1: a queue_state for ANOTHER conversation settles nothing here.
    @Test
    fun dropQueuedMessage_queueStateForOtherConversation_settlesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val queued = sendAndAck(repo, pump, "c-1", "two")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = queued))))
            runCurrent()

            startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            pump.push(queueStateEnvelope("c-2", emptyList()))
            runCurrent()

            assertEquals(listOf(queued), messageIds(thread.last()))
        }

    // #781 AC #4: an item carrying `""` correlates with NOTHING. The drop still goes — the row must leave
    // the backlog even against a daemon that mints no id — and no echo is guessed at.
    @Test
    fun dropQueuedMessage_emptyMessageId_removesNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val queued = sendAndAck(repo, pump, "c-1", "two")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = ""))))
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertTrue(drop().isSuccess)
            assertEquals(1, pump.sent.count { it.type == "dequeue_message" })
            assertEquals(listOf(queued), messageIds(thread.last()))
        }

    // #781 AC #4 and the multi-device rule (protocol-mobile.md § Queue (v2)): `queue_state` fans out to
    // EVERY interactive connection, so items routinely carry ids this device never minted. Such an item
    // is another device's real queued message: it correlates with nothing here even when a thread row
    // carries that very id and the texts are identical. Text is never used to match.
    @Test
    fun dropQueuedMessage_foreignMessageId_removesNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            // A row this device did NOT mint, carrying the id the queued item will carry.
            pump.push(messageEnvelope("c-1", messageId = "foreign-1", role = "user", text = "two", ts = TS))
            val own = sendAndAck(repo, pump, "c-1", "two")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = "foreign-1"))))
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertTrue(drop().isSuccess)
            assertEquals(listOf("foreign-1", own), messageIds(thread.last()))
        }

    // The drained-between-render-and-tap race: the id is no longer in this connection's snapshot, so
    // nothing correlates. The send still goes (the daemon silently ignores it) and no row moves, even
    // when the item's own echo later drains out of a following snapshot.
    @Test
    fun dropQueuedMessage_queuedIdAbsentFromSnapshot_removesNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = collectMessages(repo, "c-1")
            runCurrent()

            val queued = sendAndAck(repo, pump, "c-1", "two")
            pump.push(queueStateEnvelope("c-1", listOf(QueuedFixture(42L, "two", TS, messageId = queued))))
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 7L)
            runCurrent()
            pump.push(queueStateEnvelope("c-1", emptyList()))
            runCurrent()

            assertTrue(drop().isSuccess)
            assertEquals(1, pump.sent.count { it.type == "dequeue_message" })
            assertEquals(listOf(queued), messageIds(thread.last()))
        }

    // ---- requestScreenSnapshot (#375): request_snapshot request → screen_snapshot correlation ----

    // AC #2 + happy path: the sent envelope matches the request_snapshot wire contract
    // {conversation_id}, and the correlated screen_snapshot reply's text is returned VERBATIM (a
    // whitespace-laden, multi-line value proves the consumer never trims or sanitizes it).
    @Test
    fun requestScreenSnapshot_sendsRequestSnapshotAndReturnsTextVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val snapshot = startSnapshot(repo, "c1")
            runCurrent()

            val sent = pump.sent.single { it.type == "request_snapshot" }
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"c1"}"""), sent.payload)

            val screen = "  top line\n    indented body  \n"
            pump.push(screenSnapshotEnvelope(inReplyTo = sent.id, conversationId = "c1", text = screen))
            runCurrent()

            assertEquals(screen, snapshot().getOrThrow())
        }

    // AC #3: a correlated server error surfaces as RelayErrorException exposing the structured code.
    @Test
    fun requestScreenSnapshot_onServerError_throwsRelayError() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val snapshot = startSnapshot(repo, "c1")
            runCurrent()
            val sent = pump.sent.single { it.type == "request_snapshot" }
            pump.push(errorEnvelope(sent.id, code = "server.binary_offline", retryable = true))
            runCurrent()

            val ex = snapshot().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("server.binary_offline", (ex as RelayErrorException).code)
            assertTrue(ex.retryable)
        }

    // AC #3: an unknown conversation (server conversation.not_found) throws IllegalArgumentException.
    @Test
    fun requestScreenSnapshot_onConversationNotFound_throwsIllegalArgument() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val snapshot = startSnapshot(repo, "ghost")
            runCurrent()
            val sent = pump.sent.single { it.type == "request_snapshot" }
            pump.push(errorEnvelope(sent.id, code = "conversation.not_found", message = "no such conversation"))
            runCurrent()

            assertTrue(snapshot().exceptionOrNull() is IllegalArgumentException)
        }

    // AC #3: a not-Open session (pump.send returns false) throws IllegalStateException; no reply ever
    // arrives, yet the call has already completed exceptionally (it does not hang).
    @Test
    fun requestScreenSnapshot_whenSendReturnsFalse_throwsIllegalState() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val snapshot = startSnapshot(repo, "c1")
            runCurrent()

            assertTrue(snapshot().exceptionOrNull() is IllegalStateException)
        }

    // Correlation hygiene: a screen_snapshot matching no pending request is a harmless no-op; the
    // single inbound collector survives and a subsequent real round-trip succeeds.
    @Test
    fun requestScreenSnapshot_uncorrelatedSnapshot_isNoOpAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            runCurrent()

            pump.push(screenSnapshotEnvelope(inReplyTo = 999L, conversationId = "c1", text = "stray"))
            runCurrent()

            val snapshot = startSnapshot(repo, "c1")
            runCurrent()
            val sent = pump.sent.single { it.type == "request_snapshot" }
            pump.push(screenSnapshotEnvelope(inReplyTo = sent.id, conversationId = "c1", text = "real"))
            runCurrent()

            assertEquals("real", snapshot().getOrThrow())
        }

    // A malformed screen_snapshot (missing required `text`) fails the strict decode caller-side; the
    // throw is an IllegalArgumentException (SerializationException is a subtype) and the collector
    // survives, proven by a following valid round-trip.
    @Test
    fun requestScreenSnapshot_onMalformedSnapshot_throwsAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val first = startSnapshot(repo, "c1")
            runCurrent()
            val sent1 = pump.sent.single { it.type == "request_snapshot" }
            pump.push(
                Envelope(
                    id = 99L,
                    type = "screen_snapshot",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","ts":"$TS"}"""),
                    inReplyTo = sent1.id,
                ),
            )
            runCurrent()
            assertTrue(first().exceptionOrNull() is IllegalArgumentException)

            val second = startSnapshot(repo, "c1")
            runCurrent()
            val sent2 = pump.sent.last { it.type == "request_snapshot" }
            pump.push(screenSnapshotEnvelope(inReplyTo = sent2.id, conversationId = "c1", text = "ok"))
            runCurrent()
            assertEquals("ok", second().getOrThrow())
        }

    // ---- #385: v2 structured live-session stream decode (gated on `interactive`) -----------------

    // AC #1, #3: each of the three documented turn_state values decodes to its Phase, verbatim id.
    @Test
    fun liveEvents_turnState_decodesEachPhase() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            pump.push(turnStateEnvelope("c1", "responding"))
            pump.push(turnStateEnvelope("c1", "idle"))
            runCurrent()

            assertEquals(
                listOf(
                    LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Thinking),
                    LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Responding),
                    LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Idle),
                ),
                events,
            )
        }

    // AC #1: assistant_delta decodes all four fields, including the seq == 0 boundary.
    @Test
    fun liveEvents_assistantDelta_decodesAllFieldsIncludingSeqZero() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "hel"))
            runCurrent()

            assertEquals(listOf(LiveSessionEvent.AssistantDelta("c1", "t1", 0, "hel")), events)
        }

    // AC #1: tool_use decodes every field.
    @Test
    fun liveEvents_toolUse_decodesAllFields() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls -la"))
            runCurrent()

            assertEquals(listOf(LiveSessionEvent.ToolUse("c1", "t1", "tu1", "Bash", "ls -la")), events)
        }

    // AC #1: tool_result decodes both is_error boundaries.
    @Test
    fun liveEvents_toolResult_decodesIsErrorBothValues() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "ok"))
            pump.push(toolResultEnvelope("c1", "t1", "tu2", isError = true, resultSummary = "boom"))
            runCurrent()

            assertEquals(
                listOf(
                    LiveSessionEvent.ToolResult("c1", "t1", "tu1", false, "ok"),
                    LiveSessionEvent.ToolResult("c1", "t1", "tu2", true, "boom"),
                ),
                events,
            )
        }

    // AC #1: turn_end passes a non-mapped stop_reason string through verbatim (no enum).
    @Test
    fun liveEvents_turnEnd_passesStopReasonVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            pump.push(turnEndEnvelope("c1", "t2", "some_future_reason"))
            runCurrent()

            assertEquals(
                listOf(
                    LiveSessionEvent.TurnEnd("c1", "t1", "end_turn"),
                    LiveSessionEvent.TurnEnd("c1", "t2", "some_future_reason"),
                ),
                events,
            )
        }

    // AC #2: without `interactive` negotiated, a well-formed structured envelope is ignored.
    @Test
    fun liveEvents_capabilityGateClosed_blocksEmission() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()

            assertEquals(emptyList<LiveSessionEvent>(), events)
        }

    // AC #2: a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun liveEvents_capabilityGateOtherTokenOnly_blocksEmission() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()

            assertEquals(emptyList<LiveSessionEvent>(), events)
        }

    // AC #2: with `interactive` negotiated, the same envelope surfaces.
    @Test
    fun liveEvents_capabilityGateOpen_allowsEmission() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()

            assertEquals(
                listOf(LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Thinking)),
                events,
            )
        }

    // AC #3: an unrecognized turn_state value is dropped; a subsequent valid envelope still surfaces.
    @Test
    fun liveEvents_unrecognizedTurnState_droppedNextSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "compacting"))
            runCurrent()
            assertEquals(emptyList<LiveSessionEvent>(), events)

            pump.push(turnStateEnvelope("c1", "idle"))
            runCurrent()
            assertEquals(
                listOf(LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Idle)),
                events,
            )
        }

    // AC #4: a malformed envelope (missing required field) is dropped; the next envelope still surfaces.
    @Test
    fun liveEvents_malformedEnvelope_droppedNextSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            // `tool_use` missing the required `tool_use_id` → SerializationException → envelope dropped.
            pump.push(
                Envelope(
                    id = 1L,
                    type = "tool_use",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","turn_id":"t1","name":"Bash","input_summary":"ls"}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(emptyList<LiveSessionEvent>(), events)

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 1, text = "ok"))
            runCurrent()
            assertEquals(listOf(LiveSessionEvent.AssistantDelta("c1", "t1", 1, "ok")), events)
        }

    // AC #1: a full five-envelope turn surfaces all five typed events in push order.
    @Test
    fun liveEvents_fullTurnSequence_surfacesAllFiveInOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "done"))
            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            runCurrent()

            assertEquals(
                listOf("turn_state", "tool_use", "tool_result", "assistant_delta", "turn_end"),
                events.map {
                    when (it) {
                        is LiveSessionEvent.TurnState -> "turn_state"
                        is LiveSessionEvent.AssistantDelta -> "assistant_delta"
                        is LiveSessionEvent.ToolUse -> "tool_use"
                        is LiveSessionEvent.ToolResult -> "tool_result"
                        is LiveSessionEvent.TurnEnd -> "turn_end"
                        is LiveSessionEvent.ReplayGap -> "replay_gap"
                    }
                },
            )
        }

    // AC #1: hot fan-out — two collectors of the one flow both receive the event.
    @Test
    fun liveEvents_multipleCollectors_eachReceive() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val a = collectLiveEvents(repo)
            val b = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "idle"))
            runCurrent()

            assertEquals(1, a.size)
            assertEquals(1, b.size)
        }

    // ---- #395: observe the `stall` v2 control event as a thread-observable stall state -----------

    // AC #1, #5: an inbound `stall` envelope flips the observable stall state on for its conversation.
    @Test
    fun stall_onset_flipsOn() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            runCurrent()
            assertEquals(listOf(false), stalls)

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)
        }

    // AC #2, #5: the explicit on-then-off round-trip — a stall, then a forward-progress event clears it.
    @Test
    fun stall_roundTrip_onThenOffOnForwardProgress() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            pump.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()
            assertEquals(listOf(false, true, false), stalls)
        }

    // AC #2: every forward-progress event type clears the stall — including `idle` and `turn_end`,
    // the deliberate contract (the quiet-while-not-idle condition no longer holds).
    @Test
    fun stall_clearedByEachForwardProgressEvent() =
        runTest {
            assertClearsStall(turnStateEnvelope("c1", "responding"))
            assertClearsStall(turnStateEnvelope("c1", "idle"))
            assertClearsStall(turnStateEnvelope("c1", "thinking"))
            assertClearsStall(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "x"))
            assertClearsStall(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            assertClearsStall(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "ok"))
            assertClearsStall(turnEndEnvelope("c1", "t1", "end_turn"))
        }

    // AC #3: a malformed `stall` payload is dropped without tearing down the inbound consumer — a later
    // valid `stall` still flips the state, proving the single inbound collector survived.
    @Test
    fun stall_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            runCurrent()

            // Missing the required `conversation_id` → SerializationException → envelope dropped.
            pump.push(Envelope(id = 1L, type = "stall", ts = TS, payload = MobileJson.parseToJsonElement("""{}""")))
            // Wrong-typed `conversation_id` (number, not string) → SerializationException → dropped.
            pump.push(Envelope(id = 2L, type = "stall", ts = TS, payload = MobileJson.parseToJsonElement("""{"conversation_id":123}""")))
            runCurrent()
            assertEquals(listOf(false), stalls)

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)
        }

    // AC #2 (fail-closed): without `interactive` negotiated, a well-formed `stall` never surfaces.
    @Test
    fun stall_capabilityGateClosed_blocksOnset() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val stalls = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false), stalls)
        }

    // AC #2 (fail-closed): a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun stall_capabilityGateOtherTokenOnly_blocksOnset() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val stalls = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false), stalls)
        }

    // AC #1: stall state is per-conversation; a forward event for one clears only that one.
    @Test
    fun stall_perConversationIsolation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectStall(repo, "c1")
            val c2 = collectStall(repo, "c2")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            pump.push(stallEnvelope("c2"))
            runCurrent()
            assertEquals(listOf(false, true), c1)
            assertEquals(listOf(false, true), c2)

            pump.push(turnStateEnvelope("c1", "idle"))
            runCurrent()
            assertEquals(listOf(false, true, false), c1)
            assertEquals(listOf(false, true), c2)
        }

    // observeStall is distinctUntilChanged: stalling another conversation does not re-emit this flow.
    @Test
    fun stall_distinctUntilChanged_otherConversationDoesNotReemit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectStall(repo, "c1")
            runCurrent()
            assertEquals(listOf(false), c1)

            pump.push(stallEnvelope("c2"))
            runCurrent()
            assertEquals(listOf(false), c1)
        }

    // Re-receipt of `stall` for an already-stalled conversation is an idempotent Set add — no re-emit.
    @Test
    fun stall_repeatedOnset_isIdempotent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), c1)
        }

    // The precise-clearing contract: only a *successfully decoded* forward-progress event clears. An
    // unrecognized turn_state and a malformed live envelope both decode to null → the stall persists.
    @Test
    fun stall_notClearedByUnrecognizedOrMalformedLiveEvent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), c1)

            // Unrecognized turn_state → mapper returns null → not forward progress → does not clear.
            pump.push(turnStateEnvelope("c1", "compacting"))
            // Malformed live-session envelope (tool_use missing tool_use_id) → decodes to null → no clear.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "tool_use",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","turn_id":"t1","name":"Bash","input_summary":"ls"}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(listOf(false, true), c1)

            // A recognized forward-progress event does clear.
            pump.push(turnStateEnvelope("c1", "idle"))
            runCurrent()
            assertEquals(listOf(false, true, false), c1)
        }

    // ---- #460: decode `queue_state` into an observable per-conversation queued backlog ----------

    // AC #1, #5: a `queue_state` snapshot decodes into an ordered list (id, text, timestamp) in wire
    // order; `id` is the Long from `queued_msg_id` and `timestamp` is `Instant.parse(ts)`.
    @Test
    fun queue_orderedDecode_preservesWireOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            pump.push(
                queueStateEnvelope(
                    "c1",
                    listOf(
                        QueuedFixture(1L, "a", "2026-05-31T00:00:01Z"),
                        QueuedFixture(2L, "b", "2026-05-31T00:00:02Z"),
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                listOf(
                    emptyList(),
                    listOf(
                        QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z")),
                        QueuedMessage(2L, "b", Instant.parse("2026-05-31T00:00:02Z")),
                    ),
                ),
                queue,
            )
        }

    // #781 AC #1: the client-minted `message_id` (pyrycode#2092) reaches QueuedMessage VERBATIM — a
    // mixed-case, space-padded value proves no trim, no case fold and no re-encode on the way through.
    @Test
    fun queue_messageId_carriedVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            val raw = "  AbC-123_Xy  "
            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z", messageId = raw))))
            runCurrent()

            assertEquals(listOf(raw), queue.last().map { it.messageId })
        }

    // #781 AC #1: `""` is a LEGAL wire value (the client sent no id; the daemon mints none), so the
    // snapshot decodes and the backlog surfaces — it is an absent *correlation*, not a malformed item.
    @Test
    fun queue_emptyMessageId_decodesAndSurfacesBacklog() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z", messageId = ""))))
            runCurrent()

            assertEquals(
                listOf(QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z"), messageId = "")),
                queue.last(),
            )
        }

    // #781: `message_id` is strict-required like every sibling field (the wire sets no omitempty), so an
    // item that OMITS it is a malformed item and drops the whole snapshot — the prior value stands. This
    // pins the posture: an absent field is a decode failure, an empty one is a legal value (above).
    @Test
    fun queue_missingMessageId_dropsSnapshot() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z", messageId = "m1"))))
            runCurrent()
            pump.push(
                Envelope(
                    id = 2L,
                    type = "queue_state",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":2,"text":"b","ts":"$TS"}]}""",
                        ),
                ),
            )
            runCurrent()

            assertEquals(listOf(1L), queue.last().map { it.id })
        }

    // The #720 trap: `queued_msg_id` is a wire uint64 number decoded as a Long. A numeric id decodes;
    // a string-typed id is a wrong-typed field → SerializationException → the whole snapshot is dropped.
    @Test
    fun queue_queuedMsgId_decodedAsNumberNotString() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(
                Envelope(
                    id = 1L,
                    type = "queue_state",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":7,"message_id":"m-fixture","text":"x","ts":"$TS"}]}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(7L, queue.last().single().id)

            // A string-typed queued_msg_id is wrong-typed → snapshot dropped, the prior value stands.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "queue_state",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":"7","message_id":"m-fixture","text":"x","ts":"$TS"}]}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(7L, queue.last().single().id)
        }

    // AC #2: empty until the first snapshot; an empty backlog (`[]`, `null`, or a missing `queued`
    // key) stays empty — no spurious non-empty emission (distinctUntilChanged suppresses the equal).
    @Test
    fun queue_emptyUntilFirstState_toleratesEmptyArrayNullAndMissing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            // queued: [] → empty backlog, no new emission.
            pump.push(queueStateEnvelope("c1", emptyList()))
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            // queued: null → also empty backlog.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "queue_state",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","queued":null}"""),
                ),
            )
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            // missing queued key → also empty backlog (the DTO's nullable default).
            pump.push(
                Envelope(
                    id = 3L,
                    type = "queue_state",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1"}"""),
                ),
            )
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
        }

    // AC #2: each snapshot is the authoritative backlog — it fully replaces the prior list (not append)
    // and re-emits.
    @Test
    fun queue_eachSnapshot_fullyReplaces() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z"))), queue.last())

            pump.push(
                queueStateEnvelope(
                    "c1",
                    listOf(
                        QueuedFixture(2L, "b", "2026-05-31T00:00:02Z"),
                        QueuedFixture(3L, "c", "2026-05-31T00:00:03Z"),
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                listOf(
                    QueuedMessage(2L, "b", Instant.parse("2026-05-31T00:00:02Z")),
                    QueuedMessage(3L, "c", Instant.parse("2026-05-31T00:00:03Z")),
                ),
                queue.last(),
            )
        }

    // AC #3: a snapshot for one conversation does not re-emit another conversation's queue flow.
    @Test
    fun queue_perConversationIsolation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectQueue(repo, "c1")
            val c2 = collectQueue(repo, "c2")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z"))), c1.last())
            assertEquals(listOf(emptyList<QueuedMessage>()), c2)
        }

    // AC #4: a malformed `queue_state` (missing conversation_id, a bad item, or an unparseable ts) is
    // dropped without tearing down the single inbound consumer — a later valid snapshot still surfaces.
    @Test
    fun queue_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            // Missing conversation_id → SerializationException → dropped.
            pump.push(Envelope(id = 1L, type = "queue_state", ts = TS, payload = MobileJson.parseToJsonElement("""{"queued":[]}""")))
            // A queued item missing `text` → SerializationException → whole snapshot dropped.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "queue_state",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":1,"message_id":"m-fixture","ts":"$TS"}]}""",
                        ),
                ),
            )
            // An unparseable `ts` → IllegalArgumentException (Instant.parse) → whole snapshot dropped.
            pump.push(
                Envelope(
                    id = 3L,
                    type = "queue_state",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":1,"message_id":"m-fixture","text":"x","ts":"not-a-timestamp"}]}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(9L, "ok", "2026-05-31T00:00:09Z"))))
            runCurrent()
            assertEquals(listOf(QueuedMessage(9L, "ok", Instant.parse("2026-05-31T00:00:09Z"))), queue.last())
        }

    // Fail-closed: without `interactive` negotiated, a well-formed `queue_state` never surfaces.
    @Test
    fun queue_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
        }

    // Fail-closed: a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun queue_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val queue = collectQueue(repo, "c1")
            runCurrent()

            pump.push(queueStateEnvelope("c1", listOf(QueuedFixture(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
        }

    // ---- #593: decode `api_retry` into an observable per-conversation retry state ----------------

    // AC #1: a rising edge carrying a parsed counter reads "in an API retry at attempt N of M".
    @Test
    fun apiRetry_risingEdgeWithCounter_observesAttempt() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10)), retries)
        }

    // AC #1: `active: true` with `{0, 0}` is a legitimate "retrying, counter unknown" state —
    // AttemptUnknown, neither NotRetrying nor a drop. Every undocumented counter shape (partially
    // zero, negative) folds into the same total-mapper branch rather than dropping a real onset.
    @Test
    fun apiRetry_unparsedOrUndocumentedCounter_observesAttemptUnknown() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            // One conversation per shape — distinctUntilChanged would swallow a repeat on a shared id.
            val shapes = listOf(0 to 0, 3 to 0, 0 to 5, -1 to 10)
            val observed = shapes.indices.map { collectApiRetry(repo, "c$it") }
            runCurrent()

            shapes.forEachIndexed { i, (current, total) ->
                pump.push(apiRetryEnvelope("c$i", active = true, current = current, total = total, id = i.toLong()))
            }
            runCurrent()
            observed.forEachIndexed { i, emissions ->
                assertEquals("shape ${shapes[i]}", listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.AttemptUnknown), emissions)
            }
        }

    // AC #2, the load-bearing test of the slice: a re-fired rising edge with a climbed counter reaches
    // the observer as a NEW emission. Asserting the full emission list is what pins "no dedup, no
    // pinned-first value, no collapsing the climb" — a `.last()` assertion would pass under a dedup bug.
    @Test
    fun apiRetry_counterClimb_reachesObserverAsNewEmission() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10, id = 1L))
            runCurrent()
            pump.push(apiRetryEnvelope("c1", active = true, current = 4, total = 10, id = 2L))
            runCurrent()

            assertEquals(
                listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10), ApiRetryStatus.Attempt(4, 10)),
                retries,
            )
        }

    // AC #2: an `active: false` clears the state, and the last-known counter the daemon copies onto the
    // falling edge is ignored — the mapper discards it, so the stale value never reaches the projection.
    @Test
    fun apiRetry_fallingEdge_clearsAndIgnoresStaleCounter() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10, id = 1L))
            runCurrent()
            pump.push(apiRetryEnvelope("c1", active = false, current = 3, total = 10, id = 2L))
            runCurrent()

            assertEquals(
                listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10), ApiRetryStatus.NotRetrying),
                retries,
            )
        }

    // A lone falling edge with no prior rising edge leaves the observer at NotRetrying and emits
    // nothing new (the stored NotRetrying is value-identical to the absent-key default).
    @Test
    fun apiRetry_fallingEdgeWithNoPriorRisingEdge_emitsNothingNew() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = false, current = 7, total = 9))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)
        }

    // AC #3: a frame for one conversation leaves every other conversation's state undisturbed.
    @Test
    fun apiRetry_perConversationIsolation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectApiRetry(repo, "c1")
            val c2 = collectApiRetry(repo, "c2")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = true, current = 2, total = 5))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(2, 5)), c1)
            assertEquals(listOf(ApiRetryStatus.NotRetrying), c2)
        }

    // observeApiRetry is distinctUntilChanged: another conversation's status change does not re-emit
    // this flow (the suppression is value-identity, which is exactly why the climb above still emits).
    @Test
    fun apiRetry_distinctUntilChanged_otherConversationDoesNotReemit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectApiRetry(repo, "c1")
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), c1)

            pump.push(apiRetryEnvelope("c2", active = true, current = 1, total = 3, id = 1L))
            pump.push(apiRetryEnvelope("c2", active = true, current = 2, total = 3, id = 2L))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), c1)
        }

    // AC #3: a malformed `api_retry` is dropped without tearing down the single inbound consumer — a
    // later valid frame still surfaces, proving the lone collector survived.
    @Test
    fun apiRetry_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            // Missing the required `conversation_id` → SerializationException → envelope dropped.
            pump.push(
                Envelope(
                    id = 1L,
                    type = "api_retry",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"active":true,"current":1,"total":2}"""),
                ),
            )
            // Wrong-typed `conversation_id` (number, not string) → SerializationException → dropped.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "api_retry",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":123,"active":true,"current":1,"total":2}"""),
                ),
            )
            // Non-integral `current` (a float) → JsonDecodingException → dropped. Note what is NOT a
            // usable strictness probe here: kotlinx's tree decoder accepts a *quoted* primitive whose
            // content is otherwise valid even with `isLenient = false`, so `"active":"true"` and
            // `"current":"1"` both decode rather than dropping. A genuinely wrong shape — a float, a
            // non-numeric string, an Int32 overflow, a number where a String is declared — is rejected.
            pump.push(
                Envelope(
                    id = 3L,
                    type = "api_retry",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","active":true,"current":1.5,"total":2}"""),
                ),
            )
            // Missing `total` → SerializationException → dropped (no field is defaulted).
            pump.push(
                Envelope(
                    id = 4L,
                    type = "api_retry",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","active":true,"current":1}"""),
                ),
            )
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)

            pump.push(apiRetryEnvelope("c1", active = true, current = 1, total = 2, id = 5L))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(1, 2)), retries)
        }

    // AC #3 (fail-closed): without `interactive` negotiated, a well-formed `api_retry` never surfaces.
    @Test
    fun apiRetry_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)
        }

    // AC #3 (fail-closed): a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun apiRetry_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)
        }

    // AC #4: inert toward its neighbours — an `api_retry` does NOT clear an active stall (a retry is
    // not turn forward-progress; claude is stuck, not progressing) and folds no thread row, while its
    // own state still lands.
    @Test
    fun apiRetry_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            val thread = collectMessages(repo, "c1")
            val retries = collectApiRetry(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            pump.push(apiRetryEnvelope("c1", active = true, current = 3, total = 10, id = 2L))
            runCurrent()
            assertEquals("a retry is not forward progress — the stall stands", listOf(false, true), stalls)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
            assertEquals(listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10)), retries)
        }

    // ---- #596: decode `compacting` as a thread-observable per-conversation compaction state ------

    // AC #1: a rising-edge `compacting` envelope flips the observable compaction state on.
    @Test
    fun compacting_risingEdge_flipsOn() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()
            assertEquals(listOf(false), compacting)

            pump.push(compactingEnvelope("c1", active = true))
            runCurrent()
            assertEquals(listOf(false, true), compacting)
        }

    // AC #2, the load-bearing test of the slice: the explicit falling edge clears the state, and it
    // never remains active afterwards. This is the behaviour the `stall` arm structurally cannot have
    // (no clearing edge on the wire), so cloning that arm too literally fails exactly here.
    @Test
    fun compacting_roundTrip_fallingEdgeClears() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = true, id = 1L))
            runCurrent()
            assertEquals(listOf(false, true), compacting)

            pump.push(compactingEnvelope("c1", active = false, id = 2L))
            runCurrent()
            assertEquals(listOf(false, true, false), compacting)
            assertEquals("the state must not remain active after a falling edge", false, compacting.last())
        }

    // A re-fired rising edge for an already-compacting conversation is an idempotent Set add — no
    // re-emit. Unlike `api_retry` there is no counter to climb, so nothing distinguishes the repeat.
    @Test
    fun compacting_repeatedRisingEdge_isIdempotent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = true, id = 1L))
            pump.push(compactingEnvelope("c1", active = true, id = 2L))
            runCurrent()
            assertEquals(listOf(false, true), compacting)
        }

    // A lone falling edge with no prior rising edge leaves the observer at `false` and emits nothing
    // new (removal of an absent id is a no-op).
    @Test
    fun compacting_fallingEdgeWithNoPriorRisingEdge_emitsNothingNew() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = false))
            runCurrent()
            assertEquals(listOf(false), compacting)
        }

    // AC #3: a frame naming one conversation leaves every other conversation's state undisturbed.
    @Test
    fun compacting_perConversationIsolation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectCompacting(repo, "c1")
            val c2 = collectCompacting(repo, "c2")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = true))
            runCurrent()
            assertEquals(listOf(false, true), c1)
            assertEquals(listOf(false), c2)
        }

    // observeCompacting is distinctUntilChanged: another conversation's compaction edges do not
    // re-emit this flow.
    @Test
    fun compacting_distinctUntilChanged_otherConversationDoesNotReemit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectCompacting(repo, "c1")
            runCurrent()
            assertEquals(listOf(false), c1)

            pump.push(compactingEnvelope("c2", active = true, id = 1L))
            pump.push(compactingEnvelope("c2", active = false, id = 2L))
            runCurrent()
            assertEquals(listOf(false), c1)
        }

    // AC #5: a malformed `compacting` is dropped without disturbing the observed state or tearing down
    // the single inbound consumer — a later valid frame still surfaces, proving the collector survived.
    @Test
    fun compacting_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            // Missing the required `conversation_id` → SerializationException → envelope dropped.
            pump.push(compactingProbe(1L, """{"active":true}"""))
            // Wrong-typed `conversation_id` (number, not string) → dropped.
            pump.push(compactingProbe(2L, """{"conversation_id":123,"active":true}"""))
            // Missing `active` → dropped (no field is defaulted).
            pump.push(compactingProbe(3L, """{"conversation_id":"c1"}"""))
            // Genuinely wrong-shaped `active` — an object, then an array. NOT usable as a probe:
            // a *quoted* primitive (`"active":"true"`), which kotlinx's tree decoder accepts as `true`
            // even with `isLenient = false` (measured — see the ApiRetryPayloadDto KDoc), so it would
            // decode green and prove nothing.
            pump.push(compactingProbe(4L, """{"conversation_id":"c1","active":{}}"""))
            pump.push(compactingProbe(5L, """{"conversation_id":"c1","active":[true]}"""))
            runCurrent()
            assertEquals(listOf(false), compacting)

            pump.push(compactingEnvelope("c1", active = true, id = 6L))
            runCurrent()
            assertEquals(listOf(false, true), compacting)
        }

    // AC #1 (fail-closed): without `interactive` negotiated, a well-formed `compacting` never surfaces.
    @Test
    fun compacting_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = true))
            runCurrent()
            assertEquals(listOf(false), compacting)
        }

    // AC #1 (fail-closed): a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun compacting_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(compactingEnvelope("c1", active = true))
            runCurrent()
            assertEquals(listOf(false), compacting)
        }

    // Inert toward its neighbours — a `compacting` does NOT clear an active stall (compaction is not
    // turn forward-progress, and clearing here would be a hostile-daemon lever for suppressing the
    // stall indicator) and folds no thread row, while its own state still lands.
    @Test
    fun compacting_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            val thread = collectMessages(repo, "c1")
            val compacting = collectCompacting(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            pump.push(compactingEnvelope("c1", active = true, id = 2L))
            runCurrent()
            assertEquals("compaction is not forward progress — the stall stands", listOf(false, true), stalls)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
            assertEquals(listOf(false, true), compacting)
        }

    // ---- #871: decode `resetting` as a thread-observable per-conversation reset-phase reading ------

    // AC #1: nothing is readable until a frame lands; the first rising edge surfaces its phase and handoff.
    @Test
    fun resetting_risingEdge_surfacesPhaseAndHandoff() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()
            assertEquals(listOf<ResetStatus?>(null), readings)

            pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending"))
            runCurrent()
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)), readings)
        }

    // AC #1 + #2: the documented sequence — two rising edges, then one falling edge. The second rising edge
    // REPLACES the reading (a phase change, not a second reset), and the falling edge clears it.
    @Test
    fun resetting_fullSequence_replacesThenClears() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending", id = 1L))
            runCurrent()
            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "written", id = 2L))
            runCurrent()
            pump.push(resettingEnvelope("c1", active = false, phase = "", handoff = "", id = 3L))
            runCurrent()
            assertEquals(
                listOf(
                    null,
                    ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending),
                    ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written),
                    null,
                ),
                readings,
            )
        }

    // `skipped` is a reported outcome, not a missing value — it surfaces like `written`.
    @Test
    fun resetting_skippedHandoff_isAReportedOutcome() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "skipped"))
            runCurrent()
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Skipped)), readings)
        }

    // AC #2: the falling edge clears that conversation only; another conversation's reading stands.
    @Test
    fun resetting_fallingEdge_clearsOnlyItsConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectResetting(repo, "c1")
            val c2 = collectResetting(repo, "c2")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending", id = 1L))
            pump.push(resettingEnvelope("c2", active = true, phase = "restarting", handoff = "skipped", id = 2L))
            runCurrent()
            pump.push(resettingEnvelope("c1", active = false, phase = "", handoff = "", id = 3L))
            runCurrent()

            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending), null), c1)
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Skipped)), c2)
        }

    // AC #2: a falling edge with no prior rising edge does nothing — no emission, no state.
    @Test
    fun resetting_fallingEdgeWithNoPriorRisingEdge_doesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = false, phase = "", handoff = ""))
            runCurrent()
            assertEquals(listOf<ResetStatus?>(null), readings)
        }

    // The falling edge's strings are meaningless, so it clears even when they are not the documented
    // empty strings — the clear must not hang on validating fields the contract says nothing about.
    @Test
    fun resetting_fallingEdgeWithNonEmptyStrings_stillClears() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "written", id = 1L))
            runCurrent()
            pump.push(resettingEnvelope("c1", active = false, phase = "restarting", handoff = "bogus", id = 2L))
            runCurrent()
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written), null), readings)
        }

    // AC #1: a malformed payload, or a rising edge whose `phase` / `handoff` is outside its closed set, is
    // dropped — the prior reading stands, and a later valid frame still lands, proving the collector survived.
    @Test
    fun resetting_malformedOrOutOfSet_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectResetting(repo, "c1")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending", id = 1L))
            runCurrent()

            // Missing the required `conversation_id`.
            pump.push(resettingProbe(2L, """{"active":false,"phase":"","handoff":""}"""))
            // Wrong-typed `conversation_id` (number, not string).
            pump.push(resettingProbe(3L, """{"conversation_id":1,"active":false,"phase":"","handoff":""}"""))
            // Missing `handoff` — no field is defaulted.
            pump.push(resettingProbe(4L, """{"conversation_id":"c1","active":false,"phase":""}"""))
            // Genuinely wrong-shaped values. A *quoted* primitive would not do: kotlinx's tree decoder
            // accepts `"active":"false"` even with `isLenient = false` (see the ApiRetryPayloadDto KDoc).
            pump.push(resettingProbe(5L, """{"conversation_id":"c1","active":{},"phase":"","handoff":""}"""))
            pump.push(resettingProbe(6L, """{"conversation_id":"c1","active":true,"phase":["restarting"],"handoff":"written"}"""))
            // Well-formed, but a rising edge carrying a token outside the closed sets — including the
            // falling edge's empty strings, which are not members while `active` is true.
            pump.push(resettingEnvelope("c1", active = true, phase = "exploding", handoff = "written", id = 7L))
            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "lost", id = 8L))
            pump.push(resettingEnvelope("c1", active = true, phase = "", handoff = "", id = 9L))
            pump.push(resettingEnvelope("c1", active = true, phase = "Restarting", handoff = "written", id = 10L))
            runCurrent()
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)), readings)

            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "written", id = 11L))
            runCurrent()
            assertEquals(ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written), readings.last())
        }

    // AC #1 (fail-closed): a phone that did not negotiate `interactive` decodes none.
    @Test
    fun resetting_capabilityGateClosed_blocksDecode() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val readings = collectResetting(repo, "c1")
                runCurrent()

                pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending"))
                runCurrent()
                assertEquals("gate closed for $capabilities", listOf<ResetStatus?>(null), readings)
            }
        }

    // AC #2: that conversation's `session_transition` clears the reading — the daemon emits `restarting`
    // before it respawns claude, so no rising edge can follow the transition — and no other conversation's.
    @Test
    fun resetting_sessionTransitionClearsOnlyItsConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectResetting(repo, "c1")
            val c2 = collectResetting(repo, "c2")
            runCurrent()

            pump.push(resettingEnvelope("c1", active = true, phase = "restarting", handoff = "written", id = 1L))
            pump.push(resettingEnvelope("c2", active = true, phase = "wrapping_up", handoff = "pending", id = 2L))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", id = 3L))
            runCurrent()

            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written), null), c1)
            assertEquals(listOf(null, ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)), c2)
        }

    // AC #3: a `resetting` frame neither raises nor clears a stall — on c1 an existing stall survives both
    // edges, and on c2 no stall appears. Clearing here would hand a daemon a lever to suppress the stall
    // indicator; raising one would draw a stall where the daemon is doing its own reset work.
    @Test
    fun resetting_neitherRaisesNorClearsAStall() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1Stall = collectStall(repo, "c1")
            val c2Stall = collectStall(repo, "c2")
            val thread = collectMessages(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), c1Stall)

            pump.push(resettingEnvelope("c1", active = true, phase = "wrapping_up", handoff = "pending", id = 2L))
            pump.push(resettingEnvelope("c1", active = false, phase = "", handoff = "", id = 3L))
            pump.push(resettingEnvelope("c2", active = true, phase = "wrapping_up", handoff = "pending", id = 4L))
            pump.push(resettingEnvelope("c2", active = false, phase = "", handoff = "", id = 5L))
            runCurrent()
            assertEquals("a reset is not forward progress — the stall stands", listOf(false, true), c1Stall)
            assertEquals("a reset raises no stall", listOf(false), c2Stall)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
        }

    // ---- #802: decode `rate_limited` as a conversation-observable usage-limit reading ------------
    // Decode-only. The falling edge, the two unvalidated numbers and the expiry are what separate this
    // arm from its `compacting` sibling, so those carry the load here.

    // AC #1: nothing is readable until a frame lands, and the first frame surfaces every field
    // VERBATIM — both claude-authored strings, both unvalidated numbers, and the truncation report.
    @Test
    fun usageLimit_absentUntilAFrameArrives_thenSurfacesEveryFieldVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)

            pump.push(
                rateLimitedEnvelope(
                    "c1",
                    status = "allowed_warning",
                    limitType = "seven_day",
                    resetsAt = FUTURE_RESET,
                    utilization = 0.94,
                    truncatedFields = listOf("status"),
                ),
            )
            runCurrent()
            assertEquals(
                listOf(null, UsageLimitReading("allowed_warning", "seven_day", FUTURE_RESET, 0.94, listOf("status"))),
                readings,
            )
        }

    // AC #1: a malformed payload is dropped without disturbing the reading or tearing down the single
    // inbound consumer — a later valid frame still surfaces, proving the collector survived.
    @Test
    fun usageLimit_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            // Missing the required `conversation_id` → SerializationException → envelope dropped.
            pump.push(rateLimitedProbe(1L, """{"status":"allowed_warning","limit_type":"seven_day","resets_at":0}"""))
            // Wrong-typed `conversation_id` (number, not string) → dropped.
            pump.push(rateLimitedProbe(2L, """{"conversation_id":7,"status":"s","limit_type":"l","resets_at":0}"""))
            // Missing `status` → dropped (no required field is defaulted).
            pump.push(rateLimitedProbe(3L, """{"conversation_id":"c1","limit_type":"seven_day","resets_at":0}"""))
            // Genuinely wrong-shaped numbers — an object, then an array. NOT usable as probes: a
            // *quoted* primitive (`"resets_at":"0"`), which kotlinx's tree decoder accepts even with
            // `isLenient = false` (measured — see the ApiRetryPayloadDto KDoc), so it would decode
            // green and prove nothing.
            pump.push(rateLimitedProbe(4L, """{"conversation_id":"c1","status":"s","limit_type":"l","resets_at":{}}"""))
            pump.push(
                rateLimitedProbe(5L, """{"conversation_id":"c1","status":"s","limit_type":"l","resets_at":0,"utilization":[1]}"""),
            )
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)

            pump.push(rateLimitedEnvelope("c1", id = 6L))
            runCurrent()
            assertEquals("the lone inbound collector survived every drop", 2, readings.size)
            assertEquals("allowed_warning", readings.last()?.status)
        }

    // AC #1 (fail-closed): without `interactive` negotiated, a well-formed `rate_limited` never
    // surfaces — a daemon ignoring the server-side fan-out gate cannot push one to a phone that did
    // not negotiate the capability. The clock is pinned before FUTURE_RESET: on the real clock the
    // fixture's reading is already expired, so a `null` would pass with the gate removed.
    @Test
    fun usageLimit_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { emptySet() },
                    now = { Instant.fromEpochSeconds(FIXED_NOW) },
                )
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1"))
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)
        }

    // AC #1 (fail-closed): a negotiated set carrying another token but NOT `interactive` still blocks.
    // Clock pinned for the same reason as the closed-gate case.
    @Test
    fun usageLimit_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf("something_else") },
                    now = { Instant.fromEpochSeconds(FIXED_NOW) },
                )
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1"))
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)
        }

    // AC #2, the load-bearing test of the slice: a benign-status frame clears THAT conversation's
    // entry and no other's. The `stall` arm structurally cannot have a clearing edge, so cloning that
    // arm too literally fails exactly here.
    @Test
    fun usageLimit_benignStatus_clearsOnlyThatConversation() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val c1 = collectUsageLimit(repo, "c1")
            val c2 = collectUsageLimit(repo, "c2")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", id = 1L))
            pump.push(rateLimitedEnvelope("c2", id = 2L))
            runCurrent()
            assertEquals("allowed_warning", c1.last()?.status)
            assertEquals("allowed_warning", c2.last()?.status)

            pump.push(rateLimitedEnvelope("c1", status = "allowed", limitType = "five_hour", id = 3L))
            runCurrent()
            assertEquals("the benign frame cleared the conversation it named", null, c1.last())
            assertEquals("and left every other conversation's reading standing", "allowed_warning", c2.last()?.status)
        }

    // AC #2: the clearing frame names a DIFFERENT `limit_type` than the warning it clears — every
    // benign reading on record carries `five_hour` against `seven_day` on every warning. Pairing the
    // clear to `limit_type` would never match; this asserts the pairing is the conversation id.
    @Test
    fun usageLimit_benignStatus_clearsAcrossADifferentLimitType() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", status = "allowed_warning", limitType = "seven_day", id = 1L))
            runCurrent()
            assertEquals("seven_day", readings.last()?.limitType)

            pump.push(rateLimitedEnvelope("c1", status = "allowed", limitType = "five_hour", id = 2L))
            runCurrent()
            assertEquals("a mismatched limit_type must not stop the clear", null, readings.last())
        }

    // A benign frame for a conversation holding nothing is inert — removing an absent key is a no-op,
    // so the observer stays at `null` and emits nothing new.
    @Test
    fun usageLimit_benignStatusWithNoPriorReading_emitsNothingNew() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", status = "allowed", limitType = "five_hour"))
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)
        }

    // AC #3: an ABSENT `utilization` stays absent rather than becoming `0.0`, and an explicit `0.0`
    // stays `0.0`. Reading a missing reading as zero would render a fresh window as an exhausted one,
    // so the two are asserted APART rather than merely asserted present.
    @Test
    fun usageLimit_absentUtilizationStaysNull_andExplicitZeroStaysZero() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val absent = collectUsageLimit(repo, "c1")
            val zero = collectUsageLimit(repo, "c2")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", utilization = null, id = 1L))
            pump.push(rateLimitedEnvelope("c2", utilization = 0.0, id = 2L))
            runCurrent()
            assertNull("an omitted reading must not be punned to 0.0", absent.last()?.utilization)
            assertEquals("an explicit 0.0 is a real reading claude sent", 0.0, zero.last()?.utilization)
            assertNotEquals(absent.last(), zero.last())
        }

    // AC #3: an out-of-range `utilization` is carried rather than rejected or clamped — it is claude's
    // number and not a bounded fraction, so neither a negative nor an above-one value may be rewritten
    // at the decode boundary.
    @Test
    fun usageLimit_outOfRangeUtilization_isCarriedNotClamped() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val negative = collectUsageLimit(repo, "c1")
            val aboveOne = collectUsageLimit(repo, "c2")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", utilization = -0.5, id = 1L))
            pump.push(rateLimitedEnvelope("c2", utilization = 2.5, id = 2L))
            runCurrent()
            assertEquals(-0.5, negative.last()?.utilization)
            assertEquals(2.5, aboveOne.last()?.utilization)
        }

    // AC #3: an out-of-range `resets_at` is carried too. The year-40000 case is also the
    // Long-not-Int regression guard — it exceeds Int32, so an `Int` DTO field would fail the
    // STRUCTURAL decode and drop the very frame this criterion requires be carried. The clock starts
    // before the negative value so the carry is observable; "dropped at decode" would read `null`.
    @Test
    fun usageLimit_outOfRangeResetsAt_isCarriedNotRejected() =
        runTest {
            val pump = FakeSessionPump()
            var nowSeconds = -100L
            val repo = interactiveRepo(pump) { Instant.fromEpochSeconds(nowSeconds) }
            val negative = collectUsageLimit(repo, "c1")
            val farFuture = collectUsageLimit(repo, "c2")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", resetsAt = -42L, id = 1L))
            pump.push(rateLimitedEnvelope("c2", resetsAt = YEAR_40000_RESET, id = 2L))
            runCurrent()
            assertEquals("a negative resets_at is carried, not rejected", -42L, negative.last()?.resetsAt)
            assertEquals(YEAR_40000_RESET, farFuture.last()?.resetsAt)

            nowSeconds = FIXED_NOW
            val late = collectUsageLimit(repo, "c1")
            runCurrent()
            assertEquals("and, being a past instant, it is unreadable on the real timeline", listOf<UsageLimitReading?>(null), late)
        }

    // The DTO KDoc's claim: an out-of-contract `truncated_fields: []` decodes to an empty list rather
    // than being punned to `null`, so the two stay distinguishable.
    @Test
    fun usageLimit_emptyTruncatedFields_staysEmptyNotNull() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", truncatedFields = emptyList()))
            runCurrent()
            assertEquals(emptyList<String>(), readings.last()?.truncatedFields)
        }

    // AC #4: a reading stops being readable once its `resets_at` has passed. Expiry is one comparison
    // at READ time and there is deliberately no timer, so the proof is a reader asking after the
    // deadline — the same shape desktop's selector has.
    @Test
    fun usageLimit_stopsBeingReadableOnceResetsAtHasPassed() =
        runTest {
            val pump = FakeSessionPump()
            var nowSeconds = FIXED_NOW
            val repo = interactiveRepo(pump) { Instant.fromEpochSeconds(nowSeconds) }
            val early = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", resetsAt = FUTURE_RESET))
            runCurrent()
            assertEquals("readable while the clock is before resets_at", FUTURE_RESET, early.last()?.resetsAt)

            nowSeconds = FUTURE_RESET + 1
            val late = collectUsageLimit(repo, "c1")
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), late)
        }

    // AC #4: the boundary is exclusive — a reading is unreadable AT `resets_at`, not just after it.
    // The reset instant is when the window is fresh again, not the last instant it was stale.
    @Test
    fun usageLimit_exactlyAtResetsAt_isUnreadable() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump) { Instant.fromEpochSeconds(FUTURE_RESET) }
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", resetsAt = FUTURE_RESET))
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)
        }

    // AC #4: `resets_at == 0` means claude reported NO reset, emphatically not the epoch, so there is
    // no time to expire at and the reading stays readable at any clock. Folding the zero into the
    // comparison would read as "expired in 1970" and make every unreported reading invisible on
    // arrival — the failure this branch forecloses.
    @Test
    fun usageLimit_zeroResetsAt_neverExpires() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump) { Instant.fromEpochSeconds(YEAR_40000_RESET) }
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(rateLimitedEnvelope("c1", resetsAt = 0L))
            runCurrent()
            assertEquals("0 is not the epoch — the reading stands", 0L, readings.last()?.resetsAt)
        }

    // observeUsageLimit is distinctUntilChanged: another conversation's frames do not re-emit this
    // flow, while a genuinely different reading for this one does.
    @Test
    fun usageLimit_distinctUntilChanged_otherConversationDoesNotReemit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val c1 = collectUsageLimit(repo, "c1")
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), c1)

            // Separate batches, so c2 genuinely passes through two distinct states rather than being
            // conflated into one no-op by the StateFlow.
            pump.push(rateLimitedEnvelope("c2", id = 1L))
            runCurrent()
            pump.push(rateLimitedEnvelope("c2", status = "allowed", id = 2L))
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), c1)
        }

    // AC #5: a `rate_limited` neither raises nor clears a stall, and folds no thread row. A usage-limit
    // report is neither a stall nor turn forward progress, and clearing one here would hand a daemon a
    // lever for suppressing the phone's stall indicator.
    @Test
    fun usageLimit_inertTowardNeighbours_keepsStallAndFoldsNoThreadRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = interactiveRepo(pump)
            val stalls = collectStall(repo, "c1")
            val thread = collectMessages(repo, "c1")
            val readings = collectUsageLimit(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            // Each edge gets its own runCurrent(): pushed in one batch, the StateFlow conflates the
            // raise and the clear and the collector would observe neither, so a single-batch version
            // of this test would assert nothing about either edge.
            pump.push(rateLimitedEnvelope("c1", id = 2L))
            runCurrent()
            assertEquals("the raising edge landed", "allowed_warning", readings.last()?.status)
            assertEquals("a usage-limit report is not forward progress — the stall stands", listOf(false, true), stalls)

            pump.push(rateLimitedEnvelope("c1", status = "allowed", id = 3L))
            runCurrent()
            assertEquals("the clearing edge landed", null, readings.last())
            assertEquals("and a clear does not clear a stall either", listOf(false, true), stalls)
            assertEquals("no thread row folded by either edge", listOf(emptyList<ThreadItem>()), thread)
        }

    // ---- #801: decode `thinking_progress` as a conversation-observable reading --------------------

    // AC #1: a conversation no frame has named reads as NO READING (`null`), and the first frame
    // surfaces the daemon's two integers verbatim.
    @Test
    fun thinkingProgress_noReadingUntilAFrameArrives_thenSurfacesItVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()
            assertEquals(listOf<ThinkingProgress?>(null), readings)

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 184, estimatedTokensDelta = 12))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(184, 12)), readings)
        }

    // AC #2, the load-bearing test of the slice: the reading is NOT monotonic — it restarts near zero
    // at every inference-request boundary, several times inside one turn. A falling reading must reach
    // the collector as the LOWER value; a running `max` guard would pin it at 184 and pass every other
    // test in this block. The sequence is the committed capture's shape (5→184, then 4→167).
    @Test
    fun thinkingProgress_fallingReading_carriedVerbatimWithNoMaxGuard() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            // runCurrent() between the frames: the projection is a StateFlow, so two pushes drained in
            // one turn conflate to the latest and the intermediate reading is never observed — which
            // would leave this asserting something weaker than it reads.
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 184, estimatedTokensDelta = 21, id = 1L))
            runCurrent()
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 4, estimatedTokensDelta = 4, id = 2L))
            runCurrent()

            assertEquals(listOf(null, ThinkingProgress(184, 21), ThinkingProgress(4, 4)), readings)
            assertEquals("a restart must not be clamped to the prior maximum", ThinkingProgress(4, 4), readings.last())
        }

    // AC #2: a `0` reading is a REAL reading (the inference-request restart), never absence. A
    // truthiness check on the token count would surface `null` here and read as "nothing to show".
    @Test
    fun thinkingProgress_zeroReading_isAReadingNotAbsence() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 197, estimatedTokensDelta = 9, id = 1L))
            runCurrent()
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 0, estimatedTokensDelta = 0, id = 2L))
            runCurrent()

            assertEquals(listOf(null, ThinkingProgress(197, 9), ThinkingProgress(0, 0)), readings)
            assertNotNull("a zero reading is a reading, not an absent one", readings.last())
        }

    // AC #2: a value-identical repeat leaves the HELD reading exactly what the daemon sent. The
    // projection is distinctUntilChanged, so the repeat adds no emission — that suppresses a duplicate
    // value, it does not alter the reading, which is what this asserts.
    @Test
    fun thinkingProgress_repeatedReading_holdsTheDaemonsValue() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 126, estimatedTokensDelta = 64, id = 1L))
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 126, estimatedTokensDelta = 64, id = 2L))
            runCurrent()

            assertEquals(listOf(null, ThinkingProgress(126, 64)), readings)
            assertEquals(ThinkingProgress(126, 64), readings.last())
        }

    // AC #3: a frame naming one conversation leaves every other conversation's reading undisturbed,
    // and observeThinkingProgress is distinctUntilChanged, so another conversation's frames — including
    // a whole restart sequence — never re-emit this flow.
    @Test
    fun thinkingProgress_perConversationIsolationAndNoCrossReemit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectThinkingProgress(repo, "c1")
            val c2 = collectThinkingProgress(repo, "c2")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 5, estimatedTokensDelta = 5, id = 1L))
            runCurrent()
            pump.push(thinkingProgressEnvelope("c2", estimatedTokens = 88, estimatedTokensDelta = 17, id = 2L))
            runCurrent()
            pump.push(thinkingProgressEnvelope("c2", estimatedTokens = 3, estimatedTokensDelta = 3, id = 3L))
            runCurrent()

            assertEquals(listOf(null, ThinkingProgress(5, 5)), c1)
            assertEquals(listOf(null, ThinkingProgress(88, 17), ThinkingProgress(3, 3)), c2)
        }

    // AC #3: the reading has no falling edge of its own, so the conversation's TURN END clears it.
    @Test
    fun thinkingProgress_turnEndClearsTheReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 167, estimatedTokensDelta = 31, id = 1L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(167, 31)), readings)

            pump.push(turnEndEnvelope("c1", "t1", "end_turn", id = 2L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(167, 31), null), readings)
            assertNull("a finished turn must not leave a reading standing", readings.last())
        }

    // AC #3: the clear is scoped to the conversation the clearing frame names — one conversation's turn
    // ending must not wipe another's live reading. This is the case a clear hoisted out of the
    // per-conversation routing would fail.
    @Test
    fun thinkingProgress_turnEndForAnotherConversation_leavesThisReadingStanding() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 42, estimatedTokensDelta = 42, id = 1L))
            pump.push(turnEndEnvelope("c2", "t9", "end_turn", id = 2L))
            runCurrent()

            assertEquals(listOf(null, ThinkingProgress(42, 42)), c1)
        }

    // AC #3: a SESSION TRANSITION clears the reading too — the second of the two clears, since the
    // think the reading described belongs to the session that just ended.
    @Test
    fun thinkingProgress_sessionTransitionClearsTheReading() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 91, estimatedTokensDelta = 8, id = 1L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(91, 8)), readings)

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", id = 2L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(91, 8), null), readings)
        }

    // The reading survives an ordinary live event that is not a turn end — an `assistant_delta` must
    // leave it standing. This is the test that fails if the turn-end clear is hoisted beside the
    // live-session arm's pre-`when` stall clear, where it would fire on EVERY live event: that
    // placement still passes the turn-end case above, so this is the one that catches it.
    @Test
    fun thinkingProgress_nonTurnEndLiveEvent_leavesTheReadingStanding() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 73, estimatedTokensDelta = 11, id = 1L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(73, 11)), readings)

            pump.push(assistantDeltaEnvelope("c1", "t1", 0, "still working", id = 2L))
            runCurrent()
            assertEquals("an assistant delta is not a turn end — the reading stands", ThinkingProgress(73, 11), readings.last())
            assertEquals(listOf(null, ThinkingProgress(73, 11)), readings)
        }

    // AC #4: a frame neither raises a stall nor clears a standing one. Raising one is forbidden by the
    // wire contract (the rate bound means a quiet window is not a stall, and the PTY surface emits none
    // at all); clearing one would hand a hostile daemon a lever to suppress the phone's stall indicator
    // by emitting these frames. Its own reading still lands, and it folds no thread row.
    @Test
    fun thinkingProgress_inertTowardStall_neitherRaisesNorClearsIt() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val stalls = collectStall(repo, "c1")
            val thread = collectMessages(repo, "c1")
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            // Raises nothing: a reading on an unstalled conversation leaves it unstalled.
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 64, estimatedTokensDelta = 64, id = 1L))
            runCurrent()
            assertEquals("a reading is not a stall", listOf(false), stalls)

            pump.push(stallEnvelope("c1", id = 2L))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            // Clears nothing: the stall stands across a further reading.
            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 128, estimatedTokensDelta = 64, id = 3L))
            runCurrent()
            assertEquals("a reading is not forward progress — the stall stands", listOf(false, true), stalls)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
            assertEquals(listOf(null, ThinkingProgress(64, 64), ThinkingProgress(128, 64)), readings)
        }

    // AC #1: a malformed `thinking_progress` is dropped without disturbing the observed reading or
    // tearing down the single inbound consumer — a later valid frame still surfaces, proving it
    // survived. No quoted-primitive probe here: kotlinx's tree decoder accepts one even at
    // `isLenient = false` (measured against ApiRetryPayloadDto), so it would decode green and prove
    // nothing.
    @Test
    fun thinkingProgress_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            // Missing the required `conversation_id` → SerializationException → envelope dropped.
            pump.push(thinkingProgressProbe(1L, """{"estimated_tokens":10,"estimated_tokens_delta":10}"""))
            // Wrong-typed `conversation_id` (number, not string) → dropped.
            pump.push(thinkingProgressProbe(2L, """{"conversation_id":7,"estimated_tokens":10,"estimated_tokens_delta":10}"""))
            // Missing `estimated_tokens` → dropped (no field is defaulted; the wire never omits it).
            pump.push(thinkingProgressProbe(3L, """{"conversation_id":"c1","estimated_tokens_delta":10}"""))
            // Missing `estimated_tokens_delta` → dropped.
            pump.push(thinkingProgressProbe(4L, """{"conversation_id":"c1","estimated_tokens":10}"""))
            // Genuinely wrong-shaped readings — an object, then an array.
            pump.push(thinkingProgressProbe(5L, """{"conversation_id":"c1","estimated_tokens":{},"estimated_tokens_delta":10}"""))
            pump.push(thinkingProgressProbe(6L, """{"conversation_id":"c1","estimated_tokens":10,"estimated_tokens_delta":[1]}"""))
            runCurrent()
            assertEquals(listOf<ThinkingProgress?>(null), readings)

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 33, estimatedTokensDelta = 33, id = 7L))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(33, 33)), readings)
        }

    // AC #1 (fail-closed): without `interactive` negotiated, a well-formed frame never surfaces.
    @Test
    fun thinkingProgress_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 184, estimatedTokensDelta = 12))
            runCurrent()
            assertEquals(listOf<ThinkingProgress?>(null), readings)
        }

    // AC #1 (fail-closed): a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun thinkingProgress_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val readings = collectThinkingProgress(repo, "c1")
            runCurrent()

            pump.push(thinkingProgressEnvelope("c1", estimatedTokens = 184, estimatedTokensDelta = 12))
            runCurrent()
            assertEquals(listOf<ThinkingProgress?>(null), readings)
        }

    // ---- #791: decode `model_list` into an observable per-conversation model menu ----------------
    // Payload SHAPES are proven at the decode boundary (ModelListPayloadsTest); this block owns the
    // inbound arm — the `interactive` gate, the routing, the snapshot replace and the drop posture.

    // AC #3: a conversation the connection has heard no menu for reads as UNAVAILABLE (`null`), and
    // AC #1: the first frame surfaces that conversation's rows with every string verbatim.
    @Test
    fun modelMenu_unavailableUntilAFrameArrives_thenSurfacesRowsVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()
            assertEquals(listOf<ModelMenu?>(null), menus)

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET, ROW_DEFAULT), droppedModels = 4))
            runCurrent()

            assertEquals(
                ModelMenu(
                    rows =
                        listOf(
                            ModelMenuRow("claude-sonnet-5", "sonnet", "Sonnet 5", listOf("low", "high"), true, null),
                            ModelMenuRow("claude-sonnet-5", "default", "Default", emptyList(), false, listOf("display_name")),
                        ),
                    droppedModels = 4,
                ),
                menus.last(),
            )
        }

    // AC #3: unavailable is a normal resting state that stays put — one conversation's menu is NEVER
    // another's. `c2` keeps reading `null` while `c1` holds rows.
    @Test
    fun modelMenu_unheardConversation_staysUnavailableWhileAnotherHoldsRows() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val first = collectModelMenu(repo, "c1")
            val second = collectModelMenu(repo, "c2")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET)))
            runCurrent()

            assertEquals(listOf("sonnet"), first.last()?.rows?.map { it.value })
            assertEquals(listOf<ModelMenu?>(null), second)
        }

    // AC #4: a later frame REPLACES that conversation's rows wholesale — no merge, no append. A
    // shorter replacement is the shape that catches a merge: an append would leave the old row behind.
    @Test
    fun modelMenu_laterFrame_replacesWholesaleRatherThanMerging() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET, ROW_OPUS, ROW_DEFAULT), droppedModels = 9, id = 1L))
            runCurrent()
            assertEquals(listOf("sonnet", "opus[1m]", "default"), menus.last()?.rows?.map { it.value })

            pump.push(modelListEnvelope("c1", listOf(ROW_OPUS), droppedModels = 0, id = 2L))
            runCurrent()

            assertEquals(listOf("opus[1m]"), menus.last()?.rows?.map { it.value })
            assertEquals("the frame-level count is replaced too", 0, menus.last()?.droppedModels)
        }

    // AC #4: routing is the frame's OWN conversation_id and nothing else. Every envelope in the
    // reconcile burst carries the same non-load-bearing envelope id, so two frames sharing `id` and
    // naming different conversations must land independently — position is not a correlation key.
    @Test
    fun modelMenu_burstSharingOneEnvelopeId_routesByConversationIdAlone() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val first = collectModelMenu(repo, "c1")
            val second = collectModelMenu(repo, "c2")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET), id = 7L))
            pump.push(modelListEnvelope("c2", listOf(ROW_OPUS), id = 7L))
            runCurrent()

            assertEquals(listOf("sonnet"), first.last()?.rows?.map { it.value })
            assertEquals(listOf("opus[1m]"), second.last()?.rows?.map { it.value })
        }

    // AC #1/#3: an empty `models` array is a PRESENT menu that published nothing — structurally
    // distinct from unavailable, which is the pun this reading exists to avoid.
    @Test
    fun modelMenu_emptyModelsArray_isPresentAndDistinctFromUnavailable() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", emptyList(), droppedModels = 12))
            runCurrent()

            assertEquals(ModelMenu(rows = emptyList(), droppedModels = 12), menus.last())
            assertEquals(listOf(null, ModelMenu(emptyList(), 12)), menus)
        }

    // AC #2/#5: the per-row effort levels and both incompleteness readings reach a consumer intact —
    // `droppedModels` is not recomputed from the retained row count, and each row keeps its own cuts.
    @Test
    fun modelMenu_effortLevelsAndIncompletenessReachTheConsumer() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET, ROW_DEFAULT), droppedModels = 37))
            runCurrent()
            val menu = menus.last()

            assertEquals("not derived from the 2 retained rows", 37, menu?.droppedModels)
            assertEquals(listOf(listOf("low", "high"), emptyList<String>()), menu?.rows?.map { it.effortLevels })
            assertEquals(listOf(null, listOf("display_name")), menu?.rows?.map { it.truncatedFields })
        }

    // Fail-closed: without `interactive` negotiated, a well-formed `model_list` never surfaces — the
    // client mirror of the server-side fan-out gate, matching the `queue_state` / `stall` siblings.
    @Test
    fun modelMenu_capabilityGateClosed_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET)))
            runCurrent()
            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // Fail-closed: a negotiated set with another token but NOT `interactive` still blocks.
    @Test
    fun modelMenu_capabilityGateOtherTokenOnly_blocksDecode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET)))
            runCurrent()
            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // Decode-or-drop: a malformed frame is dropped whole, the previously retained menu STANDS (nothing
    // was written), and the single inbound collector survives to apply a later valid frame.
    @Test
    fun modelMenu_malformed_droppedPriorMenuStandsCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET), id = 1L))
            runCurrent()
            assertEquals(listOf("sonnet"), menus.last()?.rows?.map { it.value })

            // Missing conversation_id.
            pump.push(modelListProbe(2L, """{"models":[],"dropped_models":0}"""))
            // `models` explicitly null — out of contract, never a stand-in for an empty menu.
            pump.push(modelListProbe(3L, """{"conversation_id":"c1","models":null,"dropped_models":0}"""))
            // A row missing a required string drops the WHOLE frame, not just that row.
            pump.push(
                modelListProbe(
                    4L,
                    """{"conversation_id":"c1","models":[{"resolved_model":"r","value":"v",
                       "effort_levels":[],"supports_auto_mode":false}],"dropped_models":0}""",
                ),
            )
            runCurrent()
            assertEquals("the prior menu stands — nothing was written", listOf("sonnet"), menus.last()?.rows?.map { it.value })

            pump.push(modelListEnvelope("c1", listOf(ROW_OPUS), id = 5L))
            runCurrent()
            assertEquals(listOf("opus[1m]"), menus.last()?.rows?.map { it.value })
        }

    // A `model_list` folds NO thread row and clears NO stall — it is a menu, not turn forward progress.
    @Test
    fun modelMenu_foldsNoThreadRowAndClearsNoStall() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val thread = mutableListOf<List<ThreadItem>>()
            backgroundScope.launch { repo.observeMessages("c1").collect { thread += it } }
            val stalls = collectStall(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET), id = 2L))
            runCurrent()

            assertEquals("a menu is not forward progress — the stall stands", listOf(false, true), stalls)
            assertEquals("no thread row folded", listOf(emptyList<ThreadItem>()), thread)
        }

    // A value-identical re-snapshot does not re-emit, so a consumer's state does not churn on every
    // reconnect burst; a menu for ANOTHER conversation never re-emits this flow at all.
    @Test
    fun modelMenu_identicalReSnapshotAndForeignFrames_doNotReEmit() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET), id = 1L))
            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET), id = 2L))
            pump.push(modelListEnvelope("c2", listOf(ROW_OPUS), id = 3L))
            runCurrent()

            assertEquals(2, menus.size)
        }

    // ---- #792: ask for a model menu the connect burst did not cover -----------------------------

    // AC #1: a conversation with no retained menu is asked for one, and the payload is the single key.
    @Test
    fun modelMenuAsk_noRetainedMenu_sendsExactlyOneRequestNamingIt() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            collectModelMenu(repo, "c1")
            runCurrent()

            val asks = pump.modelListAsks()
            assertEquals(1, asks.size)
            assertEquals(
                MobileJson.parseToJsonElement("""{"conversation_id":"c1"}"""),
                asks.single().payload,
            )
        }

    // AC #1 "once": the one-shot is per conversation per connection, not per collector.
    @Test
    fun modelMenuAsk_repeatedSubscriptions_askOnlyOnce() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            collectModelMenu(repo, "c1")
            collectModelMenu(repo, "c1")
            runCurrent()
            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(1, pump.modelListAsks().size)
        }

    // AC #1: a conversation that already holds a menu is not asked again.
    @Test
    fun modelMenuAsk_conversationAlreadyHoldingAMenu_isNeverAsked() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            pump.push(modelListEnvelope("c1", listOf(ROW_SONNET)))
            runCurrent()

            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(emptyList<Envelope>(), pump.modelListAsks())
            assertEquals(
                ModelMenu(listOf(ModelMenuRow("claude-sonnet-5", "sonnet", "Sonnet 5", listOf("low", "high"), true, null)), 0),
                menus.last(),
            )
        }

    // AC #1: the reply is applied through the same retention path a broadcast frame takes, and the
    // answered conversation is not asked a second time.
    @Test
    fun modelMenuAsk_correlatedReply_appliesThroughTheSameRetentionPathAndEndsTheAsking() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            val askId = pump.modelListAsks().single().id
            pump.push(modelListEnvelope("c1", listOf(ROW_OPUS), droppedModels = 3, id = 41L, inReplyTo = askId))
            runCurrent()
            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(
                ModelMenu(listOf(ModelMenuRow("claude-opus-5", "opus[1m]", "Opus 5", listOf("high"), false, null)), 3),
                menus.last(),
            )
            assertEquals(1, pump.modelListAsks().size)
        }

    // SECURITY (plan § Security review, trust boundaries): the correlation is consumed and discarded,
    // never used to route the retention. A reply naming another conversation lands under the payload's
    // id — the asked conversation stays unavailable rather than inheriting rows it was never sent.
    @Test
    fun modelMenuAsk_correlatedReplyNamingAnotherConversation_retainsUnderThePayloadIdOnly() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val asked = collectModelMenu(repo, "c1")
            runCurrent()

            val askId = pump.modelListAsks().single().id
            pump.push(modelListEnvelope("c2", listOf(ROW_SONNET), id = 41L, inReplyTo = askId))
            runCurrent()
            val named = collectModelMenu(repo, "c2")
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), asked)
            assertEquals(
                ModelMenu(listOf(ModelMenuRow("claude-sonnet-5", "sonnet", "Sonnet 5", listOf("low", "high"), true, null)), 0),
                named.last(),
            )
        }

    // AC #4: nothing is sent on a connection that has not negotiated `interactive`.
    @Test
    fun modelMenuAsk_withoutInteractiveCapability_sendsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("push") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(emptyList<Envelope>(), pump.modelListAsks())
            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // The empty string names nothing and is refused daemon-side, so it is not sent at all.
    @Test
    fun modelMenuAsk_emptyConversationId_sendsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "")
            runCurrent()

            assertEquals(emptyList<Envelope>(), pump.modelListAsks())
            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // A send the transport refused is not an ask: the one-shot is rolled back so a later subscription
    // may still ask, the reading keeps emitting, and nothing is thrown into the collector.
    @Test
    fun modelMenuAsk_sendRefusedByTransport_rollsBackTheOneShotAndKeepsTheReadingAlive() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val first = collectModelMenu(repo, "c1")
            runCurrent()

            pump.sendResult = true
            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), first)
            assertEquals(2, pump.modelListAsks().size)
        }

    // A transport that throws is absorbed exactly like one that reports a failed send: nothing reaches
    // the subscribing collector, the reading keeps emitting, and the one-shot is rolled back.
    @Test
    fun modelMenuAsk_transportThrowsOnSend_isAbsorbedAndRollsBackTheOneShot() =
        runTest {
            val pump = FakeSessionPump()
            pump.throwOnSend = true
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val first = collectModelMenu(repo, "c1")
            runCurrent()

            pump.throwOnSend = false
            val second = collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), first)
            assertEquals(listOf<ModelMenu?>(null), second)
            assertEquals(2, pump.modelListAsks().size)
        }

    // AC #2: `conversation.not_found` is terminal for that id — the reading stays unavailable and a
    // later subscription does not ask again.
    @Test
    fun modelMenuAsk_conversationNotFound_isTerminalAndLeavesTheReadingUnavailable() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(errorEnvelope(pump.modelListAsks().single().id, "conversation.not_found"))
            runCurrent()
            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), menus)
            assertEquals(1, pump.modelListAsks().size)
        }

    // AC #2 + AC #3: `model_list.unavailable` means the same ask may succeed later — so the one-shot is
    // released — WITHOUT anything re-sending. The live collector sees no second ask; only a NEW
    // subscription asks again.
    @Test
    fun modelMenuAsk_modelListUnavailable_releasesTheOneShotWithoutResending() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            pump.push(errorEnvelope(pump.modelListAsks().single().id, "model_list.unavailable", retryable = true))
            runCurrent()

            assertEquals(1, pump.modelListAsks().size)

            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(2, pump.modelListAsks().size)
            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // AC #3: an unrecognised code and an undecodable error payload both fail closed — terminal, no
    // re-ask, no empty menu.
    @Test
    fun modelMenuAsk_unrecognisedCodeAndUndecodableError_areBothTerminal() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val unknown = collectModelMenu(repo, "c1")
            val malformed = collectModelMenu(repo, "c2")
            runCurrent()

            val asks =
                pump.modelListAsks().associateBy {
                    it.payload.jsonObject
                        .getValue("conversation_id")
                        .jsonPrimitive.content
                }
            pump.push(errorEnvelope(asks.getValue("c1").id, "model_list.exploded", retryable = true))
            pump.push(
                Envelope(
                    id = 98L,
                    type = "error",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"nope":1}"""),
                    inReplyTo = asks.getValue("c2").id,
                ),
            )
            runCurrent()
            collectModelMenu(repo, "c1")
            collectModelMenu(repo, "c2")
            runCurrent()

            assertEquals(2, pump.modelListAsks().size)
            assertEquals(listOf<ModelMenu?>(null), unknown)
            assertEquals(listOf<ModelMenu?>(null), malformed)
        }

    // A malformed correlated reply is still an answer: the correlation is consumed, the one-shot is not
    // released, and the previously retained menu (here, none) stands.
    @Test
    fun modelMenuAsk_malformedCorrelatedReply_consumesTheCorrelationAndDoesNotReAsk() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val menus = collectModelMenu(repo, "c1")
            runCurrent()

            val askId = pump.modelListAsks().single().id
            pump.push(
                Envelope(
                    id = 41L,
                    type = "model_list",
                    ts = TS,
                    payload = MobileJson.parseToJsonElement("""{"models":[]}"""),
                    inReplyTo = askId,
                ),
            )
            runCurrent()
            collectModelMenu(repo, "c1")
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), menus)
            assertEquals(1, pump.modelListAsks().size)
        }

    // The ask's correlation ledger and `pendingRequests` are disjoint: a refusal addressed to the ask
    // leaves a waiting correlated request untouched, and that request still fails on its own error.
    @Test
    fun modelMenuAsk_refusal_doesNotDisturbAWaitingCorrelatedRequest() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            collectModelMenu(repo, "c1")
            runCurrent()
            var failure: Throwable? = null
            backgroundScope.launch {
                failure = runCatching { repo.requestScreenSnapshot("c1") }.exceptionOrNull()
            }
            runCurrent()

            val askId = pump.modelListAsks().single().id
            val snapshotId = pump.sent.single { it.type == "request_snapshot" }.id
            pump.push(errorEnvelope(askId, "model_list.unavailable", retryable = true))
            runCurrent()

            assertNull(failure)

            pump.push(errorEnvelope(snapshotId, "snapshot.unavailable", retryable = true))
            runCurrent()

            assertEquals("snapshot.unavailable", (failure as RelayErrorException).code)
        }

    // ---- #387: correlate tool_use/tool_result into live tool-call thread items with status ------

    // AC #1: a tool_use produces a running tool row carrying the tool name + input, empty output.
    @Test
    fun toolCall_toolUse_producesRunningRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls -la"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(
                ToolCall(toolName = "Bash", input = "ls -la", output = "", status = ToolCallStatus.Running),
                toolCallOf(emissions.last(), "tu1"),
            )
        }

    // AC #2: the matching tool_result updates that same row in place → Done with output attached,
    // tool name + input unchanged, still exactly one row at the same position.
    @Test
    fun toolCall_toolResult_updatesRowInPlaceToDone() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls -la"))
            runCurrent()
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(
                ToolCall(toolName = "Bash", input = "ls -la", output = "files", status = ToolCallStatus.Done),
                toolCallOf(emissions.last(), "tu1"),
            )
        }

    // AC #2: a tool_result with is_error=true marks the row Failed and attaches the result summary.
    @Test
    fun toolCall_toolResultIsError_marksRowFailed() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "boom"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = true, resultSummary = "exit 1"))
            runCurrent()

            assertEquals(
                ToolCall(toolName = "Bash", input = "boom", output = "exit 1", status = ToolCallStatus.Failed),
                toolCallOf(emissions.last(), "tu1"),
            )
        }

    // AC #2: correlation is by tool_use_id, not position — a result completes only its own row.
    @Test
    fun toolCall_correlatesById_notPosition() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "first"))
            pump.push(toolUseEnvelope("c1", "t1", "tu2", "Read", "second"))
            runCurrent()
            pump.push(toolResultEnvelope("c1", "t1", "tu2", isError = false, resultSummary = "done2"))
            runCurrent()

            assertEquals(listOf("tu1", "tu2"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals(ToolCallStatus.Done, toolCallOf(emissions.last(), "tu2")?.status)
            assertEquals("done2", toolCallOf(emissions.last(), "tu2")?.output)
        }

    // AC #3: a tool_result with no matching tool_use is tolerated — no row appears, no crash.
    @Test
    fun toolCall_resultWithNoMatchingUse_producesNoRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolResultEnvelope("c1", "t1", "tuX", isError = false, resultSummary = "orphan"))
            runCurrent()

            assertEquals(emptyList<String>(), messageIds(emissions.last()))
        }

    // AC #3: out-of-order (result before its use) — the early result is dropped, the use still opens
    // exactly one running row.
    @Test
    fun toolCall_resultBeforeUse_dropsResultKeepsSingleRunningRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "early"))
            runCurrent()
            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals("", toolCallOf(emissions.last(), "tu1")?.output)
        }

    // AC #3: a duplicate tool_use creates no second row and does not reset a completed row to Running.
    @Test
    fun toolCall_duplicateToolUse_keepsSingleRowAndStatus() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()
            // A repeat tool_use for the same id arrives after completion — must not reopen the row.
            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Done, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals("files", toolCallOf(emissions.last(), "tu1")?.output)
        }

    // AC #3: a duplicate tool_result is idempotent — one row, status stable.
    @Test
    fun toolCall_duplicateToolResult_isIdempotent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(
                ToolCall(toolName = "Bash", input = "ls", output = "files", status = ToolCallStatus.Done),
                toolCallOf(emissions.last(), "tu1"),
            )
        }

    // AC #4: tool rows interleave chronologically with messages in observeMessages (arrival order).
    @Test
    fun toolCall_interleavesChronologicallyWithMessages() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "run it", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "assistant", "done", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "tu1", "m2"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Done, toolCallOf(emissions.last(), "tu1")?.status)
        }

    // AC #2 (fail-closed): without `interactive` negotiated, tool events fold nothing — no row.
    @Test
    fun toolCall_capabilityGateClosed_producesNoRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()

            assertEquals(emptyList<String>(), messageIds(emissions.last()))
        }

    // #811: a tool_denied marks its row denied, distinct from the failed state an is_error result gives.
    @Test
    fun toolDenied_thenErrorResult_retainsDeniedNotFailed() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "rm x"))
            pump.push(toolUseEnvelope("c1", "t1", "tu2", "Bash", "ls"))
            pump.push(toolDeniedEnvelope("c1", "tu1", message = "Permission denied", reasonType = "rule", truncated = """["message"]"""))
            runCurrent()
            assertEquals(ToolCallStatus.Denied, toolCallOf(emissions.last(), "tu1")?.status)

            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = true, resultSummary = "refused"))
            pump.push(toolResultEnvelope("c1", "t1", "tu2", isError = true, resultSummary = "exit 1"))
            runCurrent()

            val denied = toolCallOf(emissions.last(), "tu1")
            assertEquals(ToolCallStatus.Denied, denied?.status)
            assertEquals("refused", denied?.output)
            assertEquals(
                ToolDenial("Bash", "rule", "", "Permission denied", truncatedFields = listOf("message"), droppedFields = null),
                denied?.denial,
            )
            assertEquals(ToolCallStatus.Failed, toolCallOf(emissions.last(), "tu2")?.status)
            assertEquals(null, toolCallOf(emissions.last(), "tu2")?.denial)
            assertEquals(listOf("tu1", "tu2"), messageIds(emissions.last()))
        }

    // #811: result-line recovery reports a denial after the result shipped — the row still ends denied.
    @Test
    fun toolDenied_afterResult_marksRowDenied() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "rm x"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = true, resultSummary = "refused"))
            runCurrent()
            pump.push(toolDeniedEnvelope("c1", "tu1"))
            runCurrent()

            assertEquals(ToolCallStatus.Denied, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals("refused", toolCallOf(emissions.last(), "tu1")?.output)
        }

    // #811: a denial naming no known row adds none, and one conversation's denial never touches another's
    // row even when the tool_use_id is the same.
    @Test
    fun toolDenied_unknownRowOrOtherConversation_changesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()
            pump.push(toolDeniedEnvelope("c2", "tu1"))
            pump.push(toolDeniedEnvelope("c1", "tuX"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)
            assertEquals(null, toolCallOf(emissions.last(), "tu1")?.denial)
        }

    // #811: a malformed tool_denied drops that one envelope; the stream stays alive for the next frame.
    @Test
    fun toolDenied_malformed_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            // dropped_fields as a string, not an array or null → SerializationException → envelope dropped.
            pump.push(toolDeniedEnvelope("c1", "tu1", dropped = "\"tool_name\""))
            runCurrent()
            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)

            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = true, resultSummary = "exit 1"))
            runCurrent()
            assertEquals(ToolCallStatus.Failed, toolCallOf(emissions.last(), "tu1")?.status)
        }

    // #811 (fail-closed): without `interactive` negotiated, a tool_denied is never decoded.
    @Test
    fun toolDenied_capabilityGateClosed_ignored() =
        runTest {
            val pump = FakeSessionPump()
            var capabilities = setOf("interactive")
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()
            capabilities = emptySet()
            pump.push(toolDeniedEnvelope("c1", "tu1"))
            runCurrent()

            assertEquals(ToolCallStatus.Running, toolCallOf(emissions.last(), "tu1")?.status)
        }

    // AC #5: a malformed tool_use folds nothing and does not tear down the collector — a later valid
    // tool_use still surfaces its row.
    @Test
    fun toolCall_malformedToolUse_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // tool_use missing the required tool_use_id → SerializationException → envelope dropped.
            pump.push(
                Envelope(
                    id = 1L,
                    type = "tool_use",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","turn_id":"t1","name":"Bash","input_summary":"ls"}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(emptyList<String>(), messageIds(emissions.last()))

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            runCurrent()
            assertEquals(listOf("tu1"), messageIds(emissions.last()))
        }

    // ---- #337: fold assistant_delta into a live streaming assistant row -------------------------

    // AC #1: a single assistant_delta opens a streaming Role.Assistant row keyed by turn_id, carrying
    // the delta text verbatim.
    @Test
    fun assistantDelta_singleDelta_opensStreamingAssistantRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "hel"))
            runCurrent()

            assertEquals(listOf("t1"), messageIds(emissions.last()))
            val row = assistantRowOf(emissions.last(), "t1")!!
            assertEquals(Role.Assistant, row.role)
            assertEquals("hel", row.content)
            assertTrue(row.isStreaming)
        }

    // AC #1: successive deltas for the same turn concatenate in arrival order into the one row —
    // position + id fixed, still streaming (the wire delivers a turn's deltas in seq order).
    @Test
    fun assistantDelta_multipleDeltas_concatenateInArrivalOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "hel"))
            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 1, text = "lo "))
            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 2, text = "world"))
            runCurrent()

            assertEquals(listOf("t1"), messageIds(emissions.last()))
            val row = assistantRowOf(emissions.last(), "t1")!!
            assertEquals("hello world", row.content)
            assertTrue(row.isStreaming)
        }

    // AC #2: turn_end finalizes the row in place — content unchanged, isStreaming flipped to false,
    // still one row at the same position (turn_end carries no final text of its own).
    @Test
    fun assistantDelta_turnEnd_finalizesRowToNonStreaming() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "pi"))
            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 1, text = "ng"))
            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            runCurrent()

            assertEquals(listOf("t1"), messageIds(emissions.last()))
            val row = assistantRowOf(emissions.last(), "t1")!!
            assertEquals("ping", row.content)
            assertFalse(row.isStreaming)
        }

    // AC #2: turn_end for a turn with no assistant text (a tool-only turn) is a no-op — it adds no
    // synthetic assistant row and does not crash; the tool row is the only row.
    @Test
    fun turnEnd_withNoAssistantDelta_isNoOp() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            runCurrent()

            assertEquals(listOf("tu1"), messageIds(emissions.last()))
            assertNull(assistantRowOf(emissions.last(), "t1"))
        }

    // AC #3 (fail-closed): without `interactive` negotiated, assistant_delta folds nothing — no row.
    @Test
    fun assistantDelta_capabilityGateClosed_producesNoRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "hello"))
            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            runCurrent()

            assertEquals(emptyList<String>(), messageIds(emissions.last()))
        }

    // AC #4: assistant text interleaves chronologically with user messages and tool rows by arrival
    // order — the full structured-turn shape the e2e prototype renders end to end.
    @Test
    fun assistantDelta_interleavesWithMessagesAndTools_inArrivalOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "reply with exactly: ping", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(toolUseEnvelope("c1", "t1", "tu1", "Bash", "ls"))
            pump.push(toolResultEnvelope("c1", "t1", "tu1", isError = false, resultSummary = "files"))
            runCurrent()
            pump.push(assistantDeltaEnvelope("c1", "t1", seq = 0, text = "ping"))
            pump.push(turnEndEnvelope("c1", "t1", "end_turn"))
            runCurrent()

            assertEquals(listOf("m1", "tu1", "t1"), messageIds(emissions.last()))
            val assistant = assistantRowOf(emissions.last(), "t1")!!
            assertEquals("ping", assistant.content)
            assertFalse(assistant.isStreaming)
        }

    // ---- #336: fold session_transition into the thread as ThreadItem.SessionBoundary ------------

    // AC #1: a session_transition folds a SessionBoundary between message runs, in arrival order,
    // without disturbing message ordering.
    @Test
    fun sessionTransition_foldsBoundaryBetweenMessagesInArrivalOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "boundary:Clear", "m2"), threadShape(emissions.last()))
        }

    // AC #1: a boundary routes strictly by its payload conversation_id — a session_transition for "c2"
    // never appears in "c1"'s thread, and surfaces only in "c2"'s.
    @Test
    fun sessionTransition_routesByConversationId_neverCrossRoutes() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c2", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(c1.last()))
            assertEquals(listOf("boundary:Clear"), threadShape(c2.last()))
        }

    // AC #2 (fail-closed): without `interactive` negotiated, a well-formed session_transition folds nothing.
    @Test
    fun sessionTransition_capabilityGateClosed_foldsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(emissions.last()))
        }

    // AC #2 (fail-closed): a non-`interactive` capability set also folds nothing.
    @Test
    fun sessionTransition_capabilityGateUnrelated_foldsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(emissions.last()))
        }

    // AC #3: each reason maps to its BoundaryReason.
    @Test
    fun sessionTransition_reasonMapsToBoundaryReason() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            pump.push(sessionTransitionEnvelope("c1", "s2", "s3", "idle_evict"))
            pump.push(sessionTransitionEnvelope("c1", "s3", "s4", "workspace_change", workspaceCwd = "/w"))
            runCurrent()

            assertEquals(
                listOf(BoundaryReason.Clear, BoundaryReason.IdleEvict, BoundaryReason.WorkspaceChange),
                boundariesOf(emissions.last()).map { it.reason },
            )
        }

    // AC #3: an unrecognized reason drops the one envelope (no boundary) without killing the collector —
    // a later valid session_transition still folds.
    @Test
    fun sessionTransition_unknownReason_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "bogus"))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(sessionTransitionEnvelope("c1", "s2", "s3", "clear"))
            runCurrent()
            assertEquals(listOf("boundary:Clear"), threadShape(emissions.last()))
        }

    // AC #4: the workspaceCwd-non-null-iff-WorkspaceChange invariant holds, and on idle_evict the evicted
    // id is carried verbatim in both previous and new.
    @Test
    fun sessionTransition_workspaceCwdInvariantAndEvictionIdsVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            pump.push(sessionTransitionEnvelope("c1", "ev", "ev", "idle_evict"))
            pump.push(sessionTransitionEnvelope("c1", "s2", "s3", "workspace_change", workspaceCwd = "/x"))
            runCurrent()

            val boundaries = boundariesOf(emissions.last())
            assertNull(boundaries[0].workspaceCwd)
            assertNull(boundaries[1].workspaceCwd)
            assertEquals("/x", boundaries[2].workspaceCwd)
            assertEquals("ev", boundaries[1].previousSessionId)
            assertEquals("ev", boundaries[1].newSessionId)
        }

    // #775 AC #1: a session evicted, woken and evicted again keeps its id, so an honest daemon sends `A->A`
    // twice with different occurred_at. Both are real delimiters and both land, in arrival order.
    @Test
    fun sessionTransition_repeatedEvictionOfOneSession_foldsOneBoundaryPerInstant() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "A", "A", "idle_evict", occurredAt = "2026-06-23T12:00:00Z", id = 1L))
            runCurrent()
            pump.push(messageEnvelope("c1", "m1", "user", "wake up", "2026-06-23T12:30:00Z"))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c1", "A", "A", "idle_evict", occurredAt = "2026-06-23T13:00:00Z", id = 2L))
            runCurrent()

            assertEquals(listOf("boundary:IdleEvict", "m1", "boundary:IdleEvict"), threadShape(emissions.last()))
            assertEquals(
                listOf(Instant.parse("2026-06-23T12:00:00Z"), Instant.parse("2026-06-23T13:00:00Z")),
                boundariesOf(emissions.last()).map { it.occurredAt },
            )
        }

    // #775 AC #2: a frame repeating a held boundary's pair AND occurred_at is the same boundary, and adds no
    // second row — whatever arrived in between keeps its place.
    @Test
    fun sessionTransition_repeatOfAHeldBoundary_addsNoSecondRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", occurredAt = "2026-06-23T12:00:00Z", id = 1L))
            runCurrent()
            pump.push(messageEnvelope("c1", "m1", "user", "hi", "2026-06-23T12:30:00Z"))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", occurredAt = "2026-06-23T12:00:00Z", id = 2L))
            runCurrent()

            assertEquals(listOf("boundary:Clear", "m1"), threadShape(emissions.last()))
        }

    // AC #4: occurred_at parses to the SessionBoundary.occurredAt Instant.
    @Test
    fun sessionTransition_occurredAtParsesToInstant() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", occurredAt = "2026-06-23T12:34:56Z"))
            runCurrent()

            assertEquals(Instant.parse("2026-06-23T12:34:56Z"), boundariesOf(emissions.last()).single().occurredAt)
        }

    // AC #5: a malformed payload (missing required field) and an unparseable occurred_at are each dropped
    // without crashing the lone collector — a later valid envelope still folds.
    @Test
    fun sessionTransition_malformedDropped_collectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Missing the required new_session_id → SerializationException → dropped.
            pump.push(
                Envelope(
                    id = 1L,
                    type = "session_transition",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","previous_session_id":"s1","reason":"clear","occurred_at":"$TS","workspace_cwd":null}""",
                        ),
                ),
            )
            // Unparseable occurred_at → Instant.parse throws → dropped.
            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear", occurredAt = "not-a-timestamp"))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(sessionTransitionEnvelope("c1", "s2", "s3", "clear"))
            runCurrent()
            assertEquals(listOf("boundary:Clear"), threadShape(emissions.last()))
        }

    // AC #5: round-trip against the v2 server message set — backfill chunk, then live messages
    // interleaved with session_transitions across two conversations, yields the expected ordered
    // List<ThreadItem> with boundaries folded into the matching conversation only.
    @Test
    fun sessionTransition_roundTripAgainstV2MessageSet() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(messageChunkEnvelope(listOf(chunkRow("c1", "h1", "user", "history"))))
            runCurrent()
            pump.push(messageEnvelope("c1", "m1", "assistant", "hi", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()
            pump.push(sessionTransitionEnvelope("c2", "x1", "x2", "idle_evict"))
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "user", "again", "2026-05-31T10:02:00Z"))
            runCurrent()

            assertEquals(listOf("h1", "m1", "boundary:Clear", "m2"), threadShape(c1.last()))
            assertEquals(listOf("boundary:IdleEvict"), threadShape(c2.last()))
        }

    // ---- #578: fold new_session_id into the projection Conversation.currentSessionId -------------

    // #578 AC #1/#4: a session_transition on the relay path folds new_session_id into the projection
    // Conversation's currentSessionId, and observeConversations surfaces the updated id (the conversation
    // stays present and unduplicated).
    @Test
    fun sessionTransition_foldsNewSessionIdIntoConversationCurrentSessionId() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"C1","is_promoted":true,"cwd":"/p/c1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            // Pre-fold: the wire summary omits session identity, so currentSessionId defaults to "".
            assertEquals("", emissions.last().single().currentSessionId)

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(listOf("c1"), emissions.last().map { it.id })
            assertEquals("s2", emissions.last().single { it.id == "c1" }.currentSessionId)
        }

    // #578 AC #2: a session_transition for a conversation_id absent from the projection is a no-op — no
    // phantom entry, no currentSessionId change, and (conflation) no fresh emission.
    @Test
    fun sessionTransition_absentConversation_isNoOp() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"C1","is_promoted":true,"cwd":"/p/c1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            val emissionCountBefore = emissions.size

            // c2 is not in the projection.
            pump.push(sessionTransitionEnvelope("c2", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(listOf("c1"), emissions.last().map { it.id })
            assertEquals("", emissions.last().single().currentSessionId)
            // Element-equal list ⇒ StateFlow conflation suppresses re-emission.
            assertEquals(emissionCountBefore, emissions.size)
        }

    // #578 AC #3 (fail-closed): without `interactive` negotiated, a well-formed session_transition leaves
    // currentSessionId unchanged — same gate as the #336 boundary fold.
    @Test
    fun sessionTransition_capabilityGateClosed_leavesCurrentSessionIdUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"C1","is_promoted":true,"cwd":"/p/c1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()

            assertEquals("", emissions.last().single().currentSessionId)
        }

    // #578: a transition arriving before any conversations snapshot (null projection) emits nothing and
    // does not crash — pins the current?.map null-safety.
    @Test
    fun sessionTransition_nullProjection_emitsNothingAndDoesNotCrash() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectConversations(repo, ConversationFilter.All)
            runCurrent()

            pump.push(sessionTransitionEnvelope("c1", "s1", "s2", "clear"))
            runCurrent()

            assertEquals(emptyList<List<Conversation>>(), emissions)
        }

    // ---- #609: fold unrecognized_message into the thread as ThreadItem.UnrecognizedMessage -------

    // AC #1: a well-formed frame folds one row carrying all four wire fields verbatim, plus a stamped
    // arrival instant (the wire carries no timestamp) and a client-owned id.
    @Test
    fun unrecognizedMessage_foldsRowCarryingWireFieldsVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            val before = Clock.System.now()
            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "wobble", """{"type":"wobble","x":1}"""))
            runCurrent()
            val after = Clock.System.now()

            val row = unrecognizedRowsOf(emissions.last()).single()
            assertEquals(UnrecognizedSite.LineType, row.site)
            assertEquals("wobble", row.messageType)
            assertEquals("""{"type":"wobble","x":1}""", row.raw)
            assertFalse(row.truncated)
            assertTrue("occurredAt is stamped from the wall clock", row.occurredAt >= before && row.occurredAt <= after)
            assertTrue("id is non-empty", row.id.isNotEmpty())
        }

    // AC #1: the row interleaves by arrival order with the live `message` rows in the same thread.
    @Test
    fun unrecognizedMessage_interleavesWithMessagesInArrivalOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            runCurrent()
            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "wobble", "{}"))
            runCurrent()
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "unrecognized:LineType", "m2"), threadShape(emissions.last()))
        }

    // AC #1: each of the four closed-set site values maps to its UnrecognizedSite constant.
    @Test
    fun unrecognizedMessage_eachSiteMapsToItsConstant() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "a", "{}"))
            pump.push(unrecognizedMessageEnvelope("c1", "assistant_block", "b", "{}", id = 2L))
            pump.push(unrecognizedMessageEnvelope("c1", "user_block", "c", "{}", id = 3L))
            pump.push(unrecognizedMessageEnvelope("c1", "undecodable", "", "{}", id = 4L))
            runCurrent()

            assertEquals(
                listOf(
                    UnrecognizedSite.LineType,
                    UnrecognizedSite.AssistantBlock,
                    UnrecognizedSite.UserBlock,
                    UnrecognizedSite.Undecodable,
                ),
                unrecognizedRowsOf(emissions.last()).map { it.site },
            )
        }

    // AC #1: `truncated` round-trips both ways — `false` is a value, not an absence, so it must never be
    // read through truthiness nor defaulted.
    @Test
    fun unrecognizedMessage_truncatedRoundTripsBothWays() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "a", "cut...", truncated = true))
            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "b", "whole", truncated = false, id = 2L))
            runCurrent()

            assertEquals(listOf(true, false), unrecognizedRowsOf(emissions.last()).map { it.truncated })
        }

    // AC #1: an empty message_type (the `undecodable` site — nothing decoded, so no type was read) folds a
    // row carrying "" rather than being dropped as malformed.
    @Test
    fun unrecognizedMessage_emptyMessageTypeOnUndecodableSite_folds() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "undecodable", "", "not json at all"))
            runCurrent()

            val row = unrecognizedRowsOf(emissions.last()).single()
            assertEquals(UnrecognizedSite.Undecodable, row.site)
            assertEquals("", row.messageType)
            assertEquals("not json at all", row.raw)
        }

    // AC #1: repeats are never coalesced — two byte-identical frames yield two rows with distinct ids, so
    // the thread's LazyColumn can key them apart. How often the frame fires is the signal; merging hides it.
    @Test
    fun unrecognizedMessage_backToBackRepeats_produceTwoRowsWithDistinctIds() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "wobble", """{"type":"wobble"}"""))
            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "wobble", """{"type":"wobble"}""", id = 2L))
            runCurrent()

            val rows = unrecognizedRowsOf(emissions.last())
            assertEquals(listOf("unrecognized:LineType", "unrecognized:LineType"), threadShape(emissions.last()))
            assertEquals(2, rows.size)
            assertNotEquals("repeats must be keyable apart", rows[0].id, rows[1].id)
        }

    // AC #4: a site outside the closed set drops that one frame without killing the collector — a later
    // well-formed frame on the same conversation still folds.
    @Test
    fun unrecognizedMessage_unknownSite_droppedCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "wormhole", "a", "{}"))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "b", "{}", id = 2L))
            runCurrent()
            assertEquals(listOf("unrecognized:LineType"), threadShape(emissions.last()))
        }

    // AC #4: a malformed payload drops that one frame and the lone collector survives. Both probes use a
    // genuinely wrong shape (a missing required field; an object where a String belongs) — a *quoted*
    // primitive is a known false green, since kotlinx's tree decoder accepts it even at isLenient = false.
    @Test
    fun unrecognizedMessage_malformedDropped_collectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Missing the required `truncated` → SerializationException → dropped.
            pump.push(unrecognizedProbe(1L, """{"conversation_id":"c1","site":"line_type","message_type":"a","raw":"{}"}"""))
            // An object where `raw` declares a String → wrong-typed → dropped.
            pump.push(
                unrecognizedProbe(
                    2L,
                    """{"conversation_id":"c1","site":"line_type","message_type":"a","raw":{"n":1},"truncated":false}""",
                ),
            )
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "b", "{}", id = 3L))
            runCurrent()
            assertEquals(listOf("unrecognized:LineType"), threadShape(emissions.last()))
        }

    // AC #2 (fail-closed): without `interactive` negotiated, a well-formed frame folds nothing.
    @Test
    fun unrecognizedMessage_capabilityGateClosed_foldsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "a", "{}"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(emissions.last()))
        }

    // AC #2 (fail-closed): a negotiated set with another token but NOT `interactive` also folds nothing.
    @Test
    fun unrecognizedMessage_capabilityGateUnrelated_foldsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("something_else") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "a", "{}"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(emissions.last()))
        }

    // AC #1: the row routes strictly by its payload conversation_id — a frame naming "c2" never appears in
    // "c1"'s thread, and surfaces only in "c2"'s.
    @Test
    fun unrecognizedMessage_routesByConversationId_neverCrossRoutes() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(unrecognizedMessageEnvelope("c2", "line_type", "a", "{}"))
            runCurrent()

            assertEquals(emptyList<String>(), threadShape(c1.last()))
            assertEquals(listOf("unrecognized:LineType"), threadShape(c2.last()))
        }

    // AC #3: the frame opens/closes/alters no turn, surfaces on no live-event stream, and clears neither a
    // stall nor any other conversation status — while its own row still lands.
    @Test
    fun unrecognizedMessage_inertTowardNeighbours_keepsStallAndEmitsNoLiveEvent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            val stalls = collectStall(repo, "c1")
            val apiRetry = collectApiRetry(repo, "c1")
            val compacting = collectCompacting(repo, "c1")
            val thread = collectMessages(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            pump.push(unrecognizedMessageEnvelope("c1", "line_type", "a", "{}", id = 2L))
            runCurrent()

            assertEquals("an unparseable message is not turn forward progress — the stall stands", listOf(false, true), stalls)
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(listOf(ApiRetryStatus.NotRetrying), apiRetry)
            assertEquals(listOf(false), compacting)
            assertEquals(listOf("unrecognized:LineType"), threadShape(thread.last()))
        }

    // ---- #873: fold banner into the thread as ThreadItem.Banner ---------------------------------

    // AC #1: one row, text verbatim (sanitizing is the renderer's), identity from the envelope ts.
    @Test
    fun banner_foldsRowCarryingTextVerbatimStampedWithEnvelopeTs() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(bannerEnvelope("c1", "warning", text = "Blocked\u001b[31m by hook", truncated = true))
            runCurrent()

            val row = bannerRowsOf(emissions.last()).single()
            assertEquals(BannerLevel.Warning, row.level)
            assertEquals("Blocked\u001b[31m by hook", row.text)
            assertTrue(row.truncated)
            assertEquals(Instant.parse(TS), row.occurredAt)
        }

    // AC #1: warning reads as a warning; every other level, including empty and unknown, as a notice.
    @Test
    fun banner_everyLevelButWarning_mapsToNotice() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            listOf("warning", "info", "notice", "suggestion", "", "brand-new").forEachIndexed { i, level ->
                pump.push(bannerEnvelope("c1", level, ts = "2026-05-31T00:00:0${i}Z", id = i + 1L))
            }
            runCurrent()

            assertEquals(
                listOf(BannerLevel.Warning) + List(5) { BannerLevel.Notice },
                bannerRowsOf(emissions.last()).map { it.level },
            )
        }

    // AC #1: the row interleaves with messages in arrival order and routes by its conversation_id.
    @Test
    fun banner_interleavesInArrivalOrderAndNeverCrossRoutes() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            pump.push(bannerEnvelope("c1", "warning", id = 2L))
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "banner:Warning", "m2"), threadShape(c1.last()))
            assertEquals(emptyList<String>(), threadShape(c2.last()))
        }

    // AC #2: (type, ts) is the join key, so a repeat of one ts is one row and a new ts is another.
    @Test
    fun banner_repeatOfOneTimestamp_foldsOnce_distinctTimestampsFoldTwice() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(bannerEnvelope("c1", "warning", ts = TS))
            pump.push(bannerEnvelope("c1", "warning", ts = TS, id = 2L))
            pump.push(bannerEnvelope("c1", "warning", ts = "2026-05-31T00:00:01Z", id = 3L))
            runCurrent()

            assertEquals(listOf("banner:Warning", "banner:Warning"), threadShape(emissions.last()))
        }

    // AC #3: stops_turn is a report — no live event, the stall stands, no status indicator moves.
    @Test
    fun banner_stopsTurn_changesNoTurnOrStatusState() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            val stalls = collectStall(repo, "c1")
            val apiRetry = collectApiRetry(repo, "c1")
            val compacting = collectCompacting(repo, "c1")
            val thread = collectMessages(repo, "c1")
            runCurrent()

            pump.push(stallEnvelope("c1"))
            runCurrent()
            pump.push(bannerEnvelope("c1", "warning", stopsTurn = true, id = 2L))
            runCurrent()

            assertEquals(listOf(false, true), stalls)
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(listOf(ApiRetryStatus.NotRetrying), apiRetry)
            assertEquals(listOf(false), compacting)
            assertEquals(listOf("banner:Warning"), threadShape(thread.last()))
        }

    // AC #3: a malformed payload or ts drops that one frame; the lone collector survives.
    @Test
    fun banner_malformedDropped_collectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Missing the required stops_turn.
            pump.push(
                Envelope(
                    id = 1L,
                    type = "banner",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement("""{"conversation_id":"c1","level":"warning","text":"x","truncated":false}"""),
                ),
            )
            // An object where text declares a String.
            pump.push(
                Envelope(
                    id = 2L,
                    type = "banner",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","level":"warning","text":{"n":1},"truncated":false,"stops_turn":false}""",
                        ),
                ),
            )
            // A ts that is no instant.
            pump.push(bannerEnvelope("c1", "warning", ts = "yesterday", id = 3L))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(bannerEnvelope("c1", "notice", id = 4L))
            runCurrent()
            assertEquals(listOf("banner:Notice"), threadShape(emissions.last()))
        }

    // AC #3 (fail-closed): nothing is decoded without the negotiated `interactive` capability.
    @Test
    fun banner_capabilityGateClosedOrUnrelated_foldsNothing() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val emissions = collectMessages(repo, "c1")
                runCurrent()

                pump.push(bannerEnvelope("c1", "warning"))
                runCurrent()

                assertEquals(emptyList<String>(), threadShape(emissions.last()))
            }
        }

    // ---- #875: fold model_refusal_fallback / model_refusal_no_fallback into the thread -----------

    // AC #1: each frame adds one row carrying claude's values verbatim, identity from the envelope ts.
    @Test
    fun modelRefusal_bothFramesFoldOneRowEachCarryingValuesVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(refusalEnvelope("c1", fallbackModel = "claude-sonnet-5", banner = "Retried\u001b[31m on sonnet"))
            pump.push(refusalEnvelope("c1", fallbackModel = null, originalModel = "", banner = "", ts = "2026-05-31T00:00:01Z", id = 2L))
            runCurrent()

            assertEquals(
                listOf(
                    ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "Retried\u001b[31m on sonnet", false, Instant.parse(TS)),
                    ThreadItem.ModelRefusal("", null, "", false, Instant.parse("2026-05-31T00:00:01Z")),
                ),
                refusalRowsOf(emissions.last()),
            )
        }

    // AC #2: a banner the daemon cut is marked; a null report or one naming other fields is not.
    @Test
    fun modelRefusal_bannerNamedInTruncatedFields_isMarkedCut() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(refusalEnvelope("c1", fallbackModel = "b", truncatedFields = """["original_model","banner"]"""))
            pump.push(refusalEnvelope("c1", fallbackModel = null, truncatedFields = """["banner"]""", ts = "2026-05-31T00:00:01Z", id = 2L))
            pump.push(
                refusalEnvelope(
                    "c1",
                    fallbackModel = "b",
                    truncatedFields = """["original_model"]""",
                    ts = "2026-05-31T00:00:02Z",
                    id = 3L,
                ),
            )
            pump.push(refusalEnvelope("c1", fallbackModel = "b", truncatedFields = "null", ts = "2026-05-31T00:00:03Z", id = 4L))
            runCurrent()

            assertEquals(listOf(true, true, false, false), refusalRowsOf(emissions.last()).map { it.bannerTruncated })
        }

    // AC #1: the row interleaves with messages in arrival order and routes by its conversation_id.
    @Test
    fun modelRefusal_interleavesInArrivalOrderAndNeverCrossRoutes() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            pump.push(refusalEnvelope("c1", fallbackModel = "b", id = 2L))
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "refusal:fallback", "m2"), threadShape(c1.last()))
            assertEquals(emptyList<String>(), threadShape(c2.last()))
        }

    // AC #3: (type, ts) is the join key — a repeat is one row, a new ts or the sibling type is another.
    @Test
    fun modelRefusal_repeatOfOneTypeAndTimestamp_foldsOnce() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(refusalEnvelope("c1", fallbackModel = "b", ts = TS))
            pump.push(refusalEnvelope("c1", fallbackModel = "b", ts = TS, id = 2L))
            pump.push(refusalEnvelope("c1", fallbackModel = null, ts = TS, id = 3L))
            pump.push(refusalEnvelope("c1", fallbackModel = "b", ts = "2026-05-31T00:00:01Z", id = 4L))
            runCurrent()

            assertEquals(
                listOf("refusal:fallback", "refusal:no-fallback", "refusal:fallback"),
                threadShape(emissions.last()),
            )
        }

    // AC #4: a refusal is a report — no live event, the stall stands, the model menu and rows are untouched.
    @Test
    fun modelRefusal_changesNoTurnStatusModelOrExistingRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            val stalls = collectStall(repo, "c1")
            val apiRetry = collectApiRetry(repo, "c1")
            val compacting = collectCompacting(repo, "c1")
            val modelMenu = collectModelMenu(repo, "c1")
            val thread = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "assistant", "partial answer", "2026-05-31T10:00:00Z"))
            pump.push(stallEnvelope("c1"))
            runCurrent()
            val before = thread.last()
            pump.push(refusalEnvelope("c1", fallbackModel = "b", id = 2L))
            pump.push(refusalEnvelope("c1", fallbackModel = null, ts = "2026-05-31T00:00:01Z", id = 3L))
            runCurrent()

            assertEquals(listOf(false, true), stalls)
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(listOf(ApiRetryStatus.NotRetrying), apiRetry)
            assertEquals(listOf(false), compacting)
            assertEquals(listOf<ModelMenu?>(null), modelMenu)
            assertEquals(before, thread.last().take(before.size))
            assertEquals(listOf("m1", "refusal:fallback", "refusal:no-fallback"), threadShape(thread.last()))
        }

    // AC #4: a malformed payload or ts drops that one frame; the lone collector survives.
    @Test
    fun modelRefusal_malformedDropped_collectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Missing the required fallback_model.
            pump.push(
                refusalProbe(
                    1L,
                    "model_refusal_fallback",
                    """{"conversation_id":"c1","original_model":"a","scope":"session","refusal_category":"x","banner":"",""" +
                        """"truncated_fields":null,"dropped_fields":null}""",
                ),
            )
            // An object where banner declares a String.
            pump.push(
                refusalProbe(
                    2L,
                    "model_refusal_no_fallback",
                    """{"conversation_id":"c1","original_model":"a","refusal_category":"x","banner":{"n":1},""" +
                        """"truncated_fields":null,"dropped_fields":null}""",
                ),
            )
            // A string where truncated_fields declares an array.
            pump.push(
                refusalProbe(
                    3L,
                    "model_refusal_no_fallback",
                    """{"conversation_id":"c1","original_model":"a","refusal_category":"x","banner":"",""" +
                        """"truncated_fields":"banner","dropped_fields":null}""",
                ),
            )
            // A ts that is no instant.
            pump.push(refusalEnvelope("c1", fallbackModel = "b", ts = "yesterday", id = 4L))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(refusalEnvelope("c1", fallbackModel = null, id = 5L))
            runCurrent()
            assertEquals(listOf("refusal:no-fallback"), threadShape(emissions.last()))
        }

    // AC #4 (fail-closed): nothing is decoded without the negotiated `interactive` capability.
    @Test
    fun modelRefusal_capabilityGateClosedOrUnrelated_foldsNothing() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val emissions = collectMessages(repo, "c1")
                runCurrent()

                pump.push(refusalEnvelope("c1", fallbackModel = "b"))
                pump.push(refusalEnvelope("c1", fallbackModel = null, id = 2L))
                runCurrent()

                assertEquals(emptyList<String>(), threadShape(emissions.last()))
            }
        }

    // ---- #874: fold compaction_boundary into the thread as ThreadItem.CompactionBoundary ---------

    // AC #1: one row carrying claude's counts and the manual trigger, identity from the envelope ts.
    @Test
    fun compactionBoundary_foldsRowStampedWithEnvelopeTs() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(compactionEnvelope("c1"))
            runCurrent()

            assertEquals(
                listOf(ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = Instant.parse(TS))),
                compactionRowsOf(emissions.last()),
            )
        }

    // AC #1: a null, missing or invalid count claims no size; only the exact `manual` token reads as manual.
    @Test
    fun compactionBoundary_nullMissingOrInvalidCount_claimsNoSize_onlyManualIsManual() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(compactionEnvelope("c1", trigger = "auto", post = "null", ts = "2026-05-31T00:00:01Z", id = 1L))
            pump.push(compactionProbe(2L, """{"conversation_id":"c1","trigger":""}""", ts = "2026-05-31T00:00:02Z"))
            pump.push(compactionEnvelope("c1", trigger = "Manual", pre = "-5", ts = "2026-05-31T00:00:03Z", id = 3L))
            pump.push(compactionEnvelope("c1", pre = "9007199254740992", post = "0", ts = "2026-05-31T00:00:04Z", id = 4L))
            runCurrent()

            assertEquals(
                listOf(
                    "compaction:24000->null:false",
                    "compaction:null->null:false",
                    "compaction:null->3000:false",
                    "compaction:null->0:true",
                ),
                threadShape(emissions.last()),
            )
        }

    // AC #1: the row interleaves with messages in arrival order and routes by its conversation_id.
    @Test
    fun compactionBoundary_interleavesInArrivalOrderAndNeverCrossRoutes() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val c1 = collectMessages(repo, "c1")
            val c2 = collectMessages(repo, "c2")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "user", "first", "2026-05-31T10:00:00Z"))
            pump.push(compactionEnvelope("c1", id = 2L))
            pump.push(messageEnvelope("c1", "m2", "assistant", "second", "2026-05-31T10:01:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "compaction:24000->3000:true", "m2"), threadShape(c1.last()))
            assertEquals(emptyList<String>(), threadShape(c2.last()))
        }

    // AC #2: (type, ts) is the join key, so a repeat of one ts is one row and a new ts is another.
    @Test
    fun compactionBoundary_repeatOfOneTimestamp_foldsOnce_distinctTimestampsFoldTwice() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(compactionEnvelope("c1", ts = TS))
            pump.push(compactionEnvelope("c1", ts = TS, id = 2L))
            pump.push(compactionEnvelope("c1", ts = "2026-05-31T00:00:01Z", id = 3L))
            runCurrent()

            assertEquals(2, compactionRowsOf(emissions.last()).size)
        }

    // AC #3: no live event, no stall cleared, no compacting indicator moved — with or without a prior edge.
    @Test
    fun compactionBoundary_changesNoTurnOrStatusState() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            val stalls = collectStall(repo, "c1")
            val apiRetry = collectApiRetry(repo, "c1")
            val compacting = collectCompacting(repo, "c1")
            val thread = collectMessages(repo, "c1")
            runCurrent()

            // No compacting edge before it.
            pump.push(compactionEnvelope("c1", ts = "2026-05-31T00:00:01Z", id = 1L))
            runCurrent()
            pump.push(compactingEnvelope("c1", active = true, id = 2L))
            pump.push(stallEnvelope("c1"))
            runCurrent()
            // One arriving while compacting is still active leaves it active.
            pump.push(compactionEnvelope("c1", ts = "2026-05-31T00:00:02Z", id = 3L))
            runCurrent()

            assertEquals(listOf(false, true), stalls)
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(listOf(ApiRetryStatus.NotRetrying), apiRetry)
            assertEquals(listOf(false, true), compacting)
            assertEquals(2, compactionRowsOf(thread.last()).size)
        }

    // AC #3: a malformed payload or ts drops that one frame; the lone collector survives.
    @Test
    fun compactionBoundary_malformedDropped_collectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Missing the required trigger.
            pump.push(compactionProbe(1L, """{"conversation_id":"c1","pre_tokens":1,"post_tokens":1}"""))
            // A count that is no integer.
            pump.push(compactionEnvelope("c1", pre = "1.5", id = 2L))
            // A count that is a string.
            pump.push(compactionEnvelope("c1", post = "\"many\"", id = 3L))
            // An object where conversation_id declares a String.
            pump.push(compactionProbe(4L, """{"conversation_id":{"n":1},"trigger":"manual","pre_tokens":1,"post_tokens":1}"""))
            // A ts that is no instant.
            pump.push(compactionEnvelope("c1", ts = "yesterday", id = 5L))
            runCurrent()
            assertEquals(emptyList<String>(), threadShape(emissions.last()))

            pump.push(compactionEnvelope("c1", id = 6L))
            runCurrent()
            assertEquals(listOf("compaction:24000->3000:true"), threadShape(emissions.last()))
        }

    // AC #3 (fail-closed): nothing is decoded without the negotiated `interactive` capability.
    @Test
    fun compactionBoundary_capabilityGateClosedOrUnrelated_foldsNothing() =
        runTest {
            for (capabilities in listOf(emptySet(), setOf("something_else"))) {
                val pump = FakeSessionPump()
                val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { capabilities })
                val emissions = collectMessages(repo, "c1")
                runCurrent()

                pump.push(compactionEnvelope("c1"))
                runCurrent()

                assertEquals(emptyList<String>(), threadShape(emissions.last()))
            }
        }

    // ---- #412: replay-cursor recording on the inbound path --------------------------------------

    // AC #2: each interactive structured frame's event_id advances the high-water mark; an
    // out-of-order (smaller) event_id leaves it unchanged.
    @Test
    fun replayCursor_advancesOnInteractiveFrames_ignoresOutOfOrder() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking", eventId = 10))
            runCurrent()
            assertEquals(10L, cursor.latest)

            pump.push(turnStateEnvelope("c1", "responding", eventId = 20))
            runCurrent()
            assertEquals(20L, cursor.latest)

            pump.push(turnStateEnvelope("c1", "idle", eventId = 15))
            runCurrent()
            assertEquals(20L, cursor.latest)
        }

    // AC #3: a non-interactive frame (no event_id) leaves the cursor unchanged.
    @Test
    fun replayCursor_nonInteractiveFrame_leavesCursorUnchanged() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking", eventId = 7))
            runCurrent()
            assertEquals(7L, cursor.latest)

            // A `conversations` snapshot carries no event_id → the cursor must not move.
            pump.push(conversationsEnvelope(MIXED_FIXTURE))
            runCurrent()
            assertEquals(7L, cursor.latest)
        }

    // AC #4 / defence-in-depth: without `interactive` negotiated, an injected event_id never advances
    // a cursor the phone will never advertise.
    @Test
    fun replayCursor_gateClosed_doesNotRecord() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { emptySet() },
                replayCursor = cursor,
            )
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking", eventId = 99))
            runCurrent()
            assertNull(cursor.latest)
        }

    // Envelope-level recording is independent of payload decode: a frame with a valid event_id but a
    // malformed structured payload (dropped by decodeLiveSessionEvent) still advances the cursor.
    @Test
    fun replayCursor_advancesEvenWhenPayloadMalformed() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf("interactive") },
                    replayCursor = cursor,
                )
            val events = collectLiveEvents(repo)
            runCurrent()

            // `tool_use` missing the required tool_use_id → the payload decode drops the event …
            pump.push(
                Envelope(
                    id = 1L,
                    type = "tool_use",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","turn_id":"t1","name":"Bash","input_summary":"ls"}""",
                        ),
                    eventId = 33,
                ),
            )
            runCurrent()

            // … but the envelope-level event_id still advanced the cursor.
            assertEquals(emptyList<LiveSessionEvent>(), events)
            assertEquals(33L, cursor.latest)
        }

    // AC #4: the cursor survives a reconnect — a fresh per-connection repo sharing one cursor (as the
    // coordinator hands it over) reads the high-water mark recorded by the previous connection's repo.
    @Test
    fun replayCursor_survivesReconnect_viaSharedCursor() =
        runTest {
            val cursor = ReplayCursor()

            val pump1 = FakeSessionPump()
            RemoteConversationRepository(
                pump1,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()
            pump1.push(turnStateEnvelope("c1", "thinking", eventId = 12))
            runCurrent()
            assertEquals(12L, cursor.latest)

            // Reconnect: a brand-new repo over a fresh pump, handed the same cursor.
            val pump2 = FakeSessionPump()
            RemoteConversationRepository(
                pump2,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()
            // Readable before the new connection observes anything (the hello-build moment, #413).
            assertEquals(12L, cursor.latest)

            pump2.push(turnStateEnvelope("c1", "responding", eventId = 13))
            runCurrent()
            assertEquals(13L, cursor.latest)
        }

    // ---- #417: the `resync` marker — reset the replay cursor + surface the gap -------------------

    // AC #1: a `resync` resets the cursor, so the next hello-build omits last_event_id. Asserting
    // `cursor.latest == null` is the proxy for "next hello omits it" — #416's MobileWireCodecTest
    // proves omit-on-null on the wire (MobileJson has explicitNulls = false).
    @Test
    fun resync_resetsCursor_soNextHelloOmitsLastEventId() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()

            // Pre-advance the cursor via an interactive frame — the position a reconnect would advertise.
            pump.push(turnStateEnvelope("c1", "thinking", eventId = 10))
            runCurrent()
            assertEquals(10L, cursor.latest)

            pump.push(resyncEnvelope("c1"))
            runCurrent()
            assertNull("resync clears the cursor → next hello omits last_event_id", cursor.latest)
        }

    // AC #2: a `resync` surfaces the gap as a ReplayGap on the existing liveSessionEvents stream.
    @Test
    fun resync_surfacesReplayGapOnLiveEvents() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(resyncEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(LiveSessionEvent.ReplayGap("c1")), events)
        }

    // AC #2 (fail-closed): without `interactive` negotiated, a well-formed `resync` neither resets the
    // cursor (never advanced under the closed gate anyway) nor surfaces a gap.
    @Test
    fun resync_capabilityGateClosed_noResetNoSurface() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { emptySet() },
                    replayCursor = cursor,
                )
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(resyncEnvelope("c1"))
            runCurrent()
            assertNull(cursor.latest)
            assertEquals(emptyList<LiveSessionEvent>(), events)
        }

    // Malformed/absent conversation_id still resets the cursor (the safety action is unconditional on the
    // type match — the cursor is process-global), but surfaces no ReplayGap (it needs an id to route).
    // A later valid event still processes, proving the single inbound collector survived.
    @Test
    fun resync_malformedConversationId_stillResetsButNoSurface() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf("interactive") },
                    replayCursor = cursor,
                )
            val events = collectLiveEvents(repo)
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking", eventId = 10))
            runCurrent()
            assertEquals(10L, cursor.latest)

            // Missing conversation_id, and a wrong-typed (number) one → no id decodes → no ReplayGap …
            pump.push(Envelope(id = 1L, type = "resync", ts = TS, payload = MobileJson.parseToJsonElement("""{}""")))
            pump.push(Envelope(id = 2L, type = "resync", ts = TS, payload = MobileJson.parseToJsonElement("""{"conversation_id":123}""")))
            runCurrent()
            assertNull("the cursor reset happens regardless of payload shape", cursor.latest)
            assertTrue("a malformed conversation_id surfaces no gap", events.none { it is LiveSessionEvent.ReplayGap })

            // … and the collector survived: a later valid resync still surfaces the gap.
            pump.push(resyncEnvelope("c1"))
            runCurrent()
            assertEquals(listOf(LiveSessionEvent.ReplayGap("c1")), events.filterIsInstance<LiveSessionEvent.ReplayGap>())
        }

    // Robustness: after a resync clears the cursor, a later interactive frame re-advances it — those are
    // events the phone now genuinely holds and would legitimately advertise on the next reconnect.
    @Test
    fun resync_thenInteractiveFrame_readvancesCursor() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            RemoteConversationRepository(
                pump,
                backgroundScope,
                negotiatedCapabilities = { setOf("interactive") },
                replayCursor = cursor,
            )
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking", eventId = 10))
            runCurrent()
            assertEquals(10L, cursor.latest)

            pump.push(resyncEnvelope("c1"))
            runCurrent()
            assertNull(cursor.latest)

            pump.push(turnStateEnvelope("c1", "responding", eventId = 30))
            runCurrent()
            assertEquals(30L, cursor.latest)
        }

    // ---- #416 AC#3: ring-replayed events compose with the live stream on the single inbound path --

    // After a reconnect advertised the cursor, the daemon replays the missed tail (event_id > cursor)
    // ahead of the live stream, all on the same single inbound path. Distinct message_ids → one row
    // each, in arrival order; the cursor advances monotonically through replay-then-live.
    @Test
    fun replayedThenLiveMessages_areAppliedOnceAndAdvanceCursor() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            cursor.record(100) // a prior connection's high-water mark — the value the reconnect advertised
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf("interactive") },
                    replayCursor = cursor,
                )
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            // Replayed-from-ring tail (event_id > advertised cursor), then the live stream that follows.
            pump.push(messageEnvelope("c1", "m101", "assistant", "replayed-1", TS, eventId = 101))
            pump.push(messageEnvelope("c1", "m102", "assistant", "replayed-2", TS, eventId = 102))
            runCurrent()
            pump.push(messageEnvelope("c1", "m103", "user", "live", "2026-05-31T12:00:00Z", eventId = 103))
            runCurrent()

            assertEquals(listOf("m101", "m102", "m103"), messageIds(emissions.last()))
            assertEquals(103L, cursor.latest)
        }

    // Defensive overlap: a replayed row whose message_id the live stream also carries folds in place
    // (one row, last write wins) via the existing appendMessages dedup — never a duplicate row (AC#3).
    @Test
    fun replayedMessageOverlappingLive_foldsInPlaceNoDuplicateRow() =
        runTest {
            val pump = FakeSessionPump()
            val cursor = ReplayCursor()
            cursor.record(100)
            val repo =
                RemoteConversationRepository(
                    pump,
                    backgroundScope,
                    negotiatedCapabilities = { setOf("interactive") },
                    replayCursor = cursor,
                )
            val emissions = collectMessages(repo, "c1")
            runCurrent()

            pump.push(messageEnvelope("c1", "m1", "assistant", "from-replay", TS, eventId = 101))
            runCurrent()
            pump.push(messageEnvelope("c1", "m1", "assistant", "from-live", "2026-05-31T12:00:00Z", eventId = 102))
            runCurrent()

            val thread = emissions.last()
            assertEquals(listOf("m1"), messageIds(thread))
            assertEquals("from-live", (thread[0] as ThreadItem.MessageItem).message.content)
            assertEquals(102L, cursor.latest)
        }

    // ---- #437: decode modal_shown / modal_dismissed into the modalEvents stream -----------------

    // AC #1, #5: modal_shown decodes every field; options keep wire array order; default id carried.
    @Test
    fun modalShown_decodesAllFieldsPreservingOptionOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"permission","title":"Allow?","prompt":"Run rm -rf build/?",
                     "options":[{"id":"allow","label":"Allow"},{"id":"deny","label":"Deny"}],
                     "default_option_id":"deny","conversation_id":"c1"}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(
                listOf(
                    ModalEvent.Shown(
                        modalId = "m1",
                        modalClass = "permission",
                        title = "Allow?",
                        prompt = "Run rm -rf build/?",
                        options = listOf(ModalOption("allow", "Allow"), ModalOption("deny", "Deny")),
                        defaultOptionId = "deny",
                        conversationId = "c1",
                    ),
                ),
                events,
            )
            // Explicit order assertion (AC #5): array order is the canonical display order.
            val shown = events.single() as ModalEvent.Shown
            assertEquals(listOf("allow", "deny"), shown.options.map { it.id })
        }

    // #816: a modal_shown without conversation_id still decodes, as unscoped (`""`), so it renders in no
    // thread rather than being dropped or treated as belonging to every thread.
    @Test
    fun modalShown_withoutConversationId_decodesAsUnscoped() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """{"modal_id":"m1","class":"permission","title":"t","prompt":"p","options":[],"default_option_id":"d"}""",
                ),
            )
            runCurrent()

            assertEquals(listOf(ModalEvent.Shown("m1", "permission", "t", "p", emptyList(), "d", "")), events)
        }

    // ---- #817: modal_shown's four optional permission-context fields -----------------------------

    /** Pushes one `modal_shown` whose payload is the base fields plus [contextJson], and returns its context. */
    private fun TestScope.decodeModalContext(contextJson: String): ModalContext {
        val pump = FakeSessionPump()
        val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
        val events = collectModalEvents(repo)
        runCurrent()
        val separator = if (contextJson.isEmpty()) "" else ","
        pump.push(
            modalShownEnvelope(
                """{"modal_id":"m1","class":"permission","title":"t","prompt":"p","options":[],""" +
                    """"default_option_id":"d","conversation_id":"c1"$separator$contextJson}""",
            ),
        )
        runCurrent()
        return (events.single() as ModalEvent.Shown).context
    }

    @Test
    fun modalShown_decodesPermissionContextStrings() =
        runTest {
            val context =
                decodeModalContext(
                    """"reason":"A rule matched","reason_type":"rule","blocked_path":"/etc/hosts",""" +
                        """"description":"Edit the hosts file"""",
                )

            assertEquals(
                ModalContext(
                    reason = "A rule matched",
                    reasonType = "rule",
                    blockedPath = "/etc/hosts",
                    description = "Edit the hosts file",
                ),
                context,
            )
        }

    // A reason with no guaranteed JSON shape stays visible as its JSON text — `false`, `0` and `null` included.
    @Test
    fun modalShown_nonStringReason_decodesAsItsJsonText() =
        runTest {
            val cases =
                listOf(
                    "false" to "false",
                    "0" to "0",
                    "null" to "null",
                    """{"rule":"Bash(ls)"}""" to """{"rule":"Bash(ls)"}""",
                    """[1,"a"]""" to """[1,"a"]""",
                )
            for ((raw, expected) in cases) {
                assertEquals(raw, ModalContext(reason = expected), decodeModalContext(""""reason":$raw"""))
            }
        }

    @Test
    fun modalShown_absentOrEmptyContext_decodesAsNone() =
        runTest {
            assertEquals(ModalContext.None, decodeModalContext(""))
            assertEquals(
                ModalContext.None,
                decodeModalContext(""""reason":"","reason_type":"","blocked_path":"","description":"""""),
            )
        }

    // An open vocabulary: an unknown category is carried verbatim, never dropped.
    @Test
    fun modalShown_unknownReasonType_isCarriedVerbatim() =
        runTest {
            assertEquals(
                ModalContext(reasonType = "futureCategory_v9"),
                decodeModalContext(""""reason_type":"futureCategory_v9""""),
            )
        }

    // A wrong-typed display field is absent rather than dropping the whole permission prompt.
    @Test
    fun modalShown_wrongTypedStringContextFields_areAbsentAndTheModalStillSurfaces() =
        runTest {
            assertEquals(
                ModalContext(reason = "r"),
                decodeModalContext(""""reason":"r","reason_type":7,"blocked_path":{"p":1},"description":[true]"""),
            )
        }

    @Test
    fun modalShown_overLongContextValues_areClamped() =
        runTest {
            val long = "x".repeat(5000)
            val context =
                decodeModalContext(
                    """"reason":"$long","reason_type":"$long","blocked_path":"$long","description":"$long"""",
                )

            assertEquals(2048, context.reason?.length)
            assertEquals(128, context.reasonType?.length)
            assertEquals(2048, context.blockedPath?.length)
            assertEquals(2048, context.description?.length)
        }

    // The clamp never leaves a lone high surrogate where it cut through an emoji.
    @Test
    fun modalShown_clampDoesNotSplitASurrogatePair() =
        runTest {
            val value = "x".repeat(2047) + "😀"
            val context = decodeModalContext(""""description":"$value"""")

            assertEquals("x".repeat(2047), context.description)
        }

    // ---- #818: modal_shown's always_allow offer ------------------------------------------------------

    /** Pushes one `modal_shown` carrying [alwaysAllowJson] as its `always_allow` value (omitted when null). */
    private fun TestScope.decodeAlwaysAllowRules(alwaysAllowJson: String?): List<String> {
        val pump = FakeSessionPump()
        val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
        val events = collectModalEvents(repo)
        runCurrent()
        val field = if (alwaysAllowJson == null) "" else ""","always_allow":$alwaysAllowJson"""
        pump.push(
            modalShownEnvelope(
                """{"modal_id":"m1","class":"permission","title":"t","prompt":"p","options":[],""" +
                    """"default_option_id":"d","conversation_id":"c1"$field}""",
            ),
        )
        runCurrent()
        return (events.single() as ModalEvent.Shown).alwaysAllowRules
    }

    private fun rulesJson(rules: List<String>): String = rules.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    @Test
    fun modalShown_offeredAlwaysAllow_decodesItsRulesInOrder() =
        runTest {
            assertEquals(
                listOf("Bash(npm test)", "Read", "Edit()"),
                decodeAlwaysAllowRules("""{"offered":true,"rules":["Bash(npm test)","Read","Edit()"]}"""),
            )
        }

    // Every unavailable or malformed shape is no offer, and the prompt it decorates still surfaces.
    @Test
    fun modalShown_unavailableOrMalformedAlwaysAllow_decodesAsNoOffer() =
        runTest {
            val cases =
                listOf(
                    null,
                    "null",
                    """{"offered":false,"rules":[]}""",
                    """{"offered":false,"rules":["Read"]}""",
                    """{"offered":true,"rules":[]}""",
                    """{"offered":"true","rules":["Read"]}""",
                    """{"offered":1,"rules":["Read"]}""",
                    """{"rules":["Read"]}""",
                    """{"offered":true}""",
                    """{"offered":true,"rules":"Read"}""",
                    """{"offered":true,"rules":["Read",7]}""",
                    """{"offered":true,"rules":["Read",null]}""",
                    """{"offered":true,"rules":["Read",""]}""",
                    """{"offered":true,"rules":[["Read"]]}""",
                    """"offered"""",
                    """[true,["Read"]]""",
                )
            for (case in cases) {
                assertEquals(case.toString(), emptyList<String>(), decodeAlwaysAllowRules(case))
            }
        }

    // The daemon's 16-rule bound: at the bound the list decodes, one over it rejects the whole list.
    @Test
    fun modalShown_alwaysAllowRuleCount_isBoundedWithoutKeepingAPrefix() =
        runTest {
            val sixteen = List(16) { "Bash(cmd$it)" }
            assertEquals(sixteen, decodeAlwaysAllowRules("""{"offered":true,"rules":${rulesJson(sixteen)}}"""))
            val seventeen = List(17) { "Bash(cmd$it)" }
            assertEquals(emptyList<String>(), decodeAlwaysAllowRules("""{"offered":true,"rules":${rulesJson(seventeen)}}"""))
        }

    // The 1024-byte bound counts UTF-8 bytes, not chars: 512 two-byte chars pass, 513 do not.
    @Test
    fun modalShown_alwaysAllowRuleLength_isBoundedInUtf8Bytes() =
        runTest {
            val atBound = "é".repeat(512)
            assertEquals(
                listOf("Read", atBound),
                decodeAlwaysAllowRules("""{"offered":true,"rules":${rulesJson(listOf("Read", atBound))}}"""),
            )
            val overBound = "é".repeat(513)
            assertEquals(
                emptyList<String>(),
                decodeAlwaysAllowRules("""{"offered":true,"rules":${rulesJson(listOf("Read", overBound))}}"""),
            )
        }

    // AC #2: modal_dismissed source `remote`, outcome = a selected option id.
    @Test
    fun modalDismissed_sourceRemote() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(modalDismissedEnvelope("m1", outcome = "allow", source = "remote"))
            runCurrent()

            assertEquals(listOf(ModalEvent.Dismissed("m1", "allow", "remote")), events)
        }

    // AC #2: modal_dismissed source `local`.
    @Test
    fun modalDismissed_sourceLocal() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(modalDismissedEnvelope("m1", outcome = "deny", source = "local"))
            runCurrent()

            assertEquals(listOf(ModalEvent.Dismissed("m1", "deny", "local")), events)
        }

    // AC #2: modal_dismissed source `timeout`, with a producer sentinel outcome carried verbatim.
    @Test
    fun modalDismissed_sourceTimeout() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(modalDismissedEnvelope("m1", outcome = "cancelled", source = "timeout"))
            runCurrent()

            assertEquals(listOf(ModalEvent.Dismissed("m1", "cancelled", "timeout")), events)
        }

    // AC #3: an unknown/forward-compat `class` is preserved verbatim, not coerced or dropped.
    @Test
    fun modalShown_unknownClassPreservedVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"some_future_class","title":"T","prompt":"P",
                     "options":[{"id":"ok","label":"OK"}],"default_option_id":"ok"}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals("some_future_class", (events.single() as ModalEvent.Shown).modalClass)
        }

    // AC #3: an unknown/forward-compat `source` and `outcome` are preserved verbatim, not dropped.
    @Test
    fun modalDismissed_unknownSourceAndOutcomePreservedVerbatim() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(modalDismissedEnvelope("m1", outcome = "some_future_outcome", source = "some_future_source"))
            runCurrent()

            assertEquals(
                listOf(ModalEvent.Dismissed("m1", "some_future_outcome", "some_future_source")),
                events,
            )
        }

    // AC #3: a trailing/unknown JSON field is tolerated (ignoreUnknownKeys) — decode still surfaces.
    @Test
    fun modalShown_trailingUnknownFieldTolerated() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"permission","title":"T","prompt":"P",
                     "options":[{"id":"ok","label":"OK"}],"default_option_id":"ok","deadline_ms":5000}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(
                ModalEvent.Shown("m1", "permission", "T", "P", listOf(ModalOption("ok", "OK")), "ok"),
                events.single(),
            )
        }

    // A malformed modal_shown (missing required `title`) is dropped; the next event still surfaces.
    @Test
    fun modalShown_malformedDroppedNextSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"permission","prompt":"P",
                     "options":[{"id":"ok","label":"OK"}],"default_option_id":"ok"}
                    """.trimIndent(),
                ),
            )
            runCurrent()
            assertEquals(emptyList<ModalEvent>(), events)

            pump.push(modalDismissedEnvelope("m1", outcome = "ok", source = "remote"))
            runCurrent()
            assertEquals(listOf(ModalEvent.Dismissed("m1", "ok", "remote")), events)
        }

    // AC #4: without `interactive` negotiated, a well-formed modal envelope is ignored (gate, siblings).
    @Test
    fun modal_capabilityGateClosed_blocksEmission() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { emptySet() })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"permission","title":"T","prompt":"P",
                     "options":[{"id":"ok","label":"OK"}],"default_option_id":"ok"}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(emptyList<ModalEvent>(), events)
        }

    // AC #1: a shown→dismissed lifecycle for one modal_id surfaces both typed events in push order.
    @Test
    fun modal_shownThenDismissed_surfacesBothInOrder() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
            val events = collectModalEvents(repo)
            runCurrent()

            pump.push(
                modalShownEnvelope(
                    """
                    {"modal_id":"m1","class":"permission","title":"T","prompt":"P",
                     "options":[{"id":"ok","label":"OK"}],"default_option_id":"ok"}
                    """.trimIndent(),
                ),
            )
            pump.push(modalDismissedEnvelope("m1", outcome = "ok", source = "remote"))
            runCurrent()

            assertEquals(
                listOf(
                    ModalEvent.Shown("m1", "permission", "T", "P", listOf(ModalOption("ok", "OK")), "ok"),
                    ModalEvent.Dismissed("m1", "ok", "remote"),
                ),
                events,
            )
        }

    // ---- workspace_updated (#721): the host-owned label push reaches the live projection ---------

    // AC #1: a workspace is a folder, so one `workspace_updated` relabels EVERY row whose cwd equals
    // its path — and only those. Driven through real inbound dispatch, never a direct projection poke.
    @Test
    fun workspaceUpdated_unsolicitedPush_labelsEveryRowSharingThatCwd() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            val rows = all.last().associateBy { it.id }
            assertEquals("Tax filing", rows.getValue("a1").workspaceLabel)
            assertEquals("Tax filing", rows.getValue("a2").workspaceLabel)
            // A different path on the same host keeps its own label.
            assertEquals("Beta label", rows.getValue("b1").workspaceLabel)
        }

    // AC #1: the SAME frame arrives as a correlated reply to rename_workspace. The protocol requires a
    // client to accept both kinds, so the apply is unconditional on in_reply_to rather than gated by it.
    @Test
    fun workspaceUpdated_carryingInReplyTo_appliesTheSameWay() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Tax filing", inReplyTo = 7L))
            runCurrent()

            assertEquals("Tax filing", all.last().single { it.id == "a1" }.workspaceLabel)
        }

    // AC #1: a null label CLEARS the stored one — the protocol's "clear" state, distinct from a blank.
    @Test
    fun workspaceUpdated_nullLabel_clearsTheStoredLabel() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()
            assertEquals("Beta label", all.last().single { it.id == "b1" }.workspaceLabel)

            pump.push(workspaceUpdatedEnvelope(path = "/w/beta", label = null))
            runCurrent()

            assertNull(all.last().single { it.id == "b1" }.workspaceLabel)
        }

    // AC #1: archiving a conversation does not un-name its folder, so archived rows are relabelled too.
    @Test
    fun workspaceUpdated_labelsArchivedRowsToo() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val archived = collectConversations(repo, ConversationFilter.Archived)
            runCurrent()
            pump.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"gone","name":"Archived","is_promoted":true,"cwd":"/w/alpha","is_archived":true,"last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z","workspace_label":null}]}""",
                ),
            )
            runCurrent()

            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            assertEquals("Tax filing", archived.last().single { it.id == "gone" }.workspaceLabel)
        }

    // AC #1, #3: host isolation. Two repositories over two pumps seeded with BYTE-IDENTICAL ids and
    // cwds — a push on one host must not reach the other's projection.
    @Test
    fun workspaceUpdated_onOneHost_leavesTheOtherHostUnchanged() =
        runTest {
            val hostA = FakeSessionPump()
            val hostB = FakeSessionPump()
            val repoA = RemoteConversationRepository(hostA, backgroundScope)
            val repoB = RemoteConversationRepository(hostB, backgroundScope)
            val a = collectConversations(repoA, ConversationFilter.All)
            val b = collectConversations(repoB, ConversationFilter.All)
            runCurrent()
            hostA.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            hostB.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            hostA.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Tax filing"))
            runCurrent()

            assertEquals("Tax filing", a.last().single { it.id == "a1" }.workspaceLabel)
            assertNull(b.last().single { it.id == "a1" }.workspaceLabel)
        }

    // AC #3: a malformed notification (no `path`) leaves current data intact and does not stop the
    // single inbound collector — the next valid frame still applies.
    @Test
    fun workspaceUpdated_malformedPayload_leavesDataIntactAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()
            val emissionsBefore = all.size

            pump.push(workspaceUpdatedRawEnvelope("""{"label":"Tax filing"}"""))
            runCurrent()
            assertEquals(emissionsBefore, all.size) // dropped: nothing re-emitted
            assertNull(all.last().single { it.id == "a1" }.workspaceLabel)

            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Tax filing"))
            runCurrent()
            assertEquals("Tax filing", all.last().single { it.id == "a1" }.workspaceLabel)
        }

    // AC #4: the push is live-only with no replay, so a client disconnected during a rename or a clear
    // reads the current label off its next snapshot — which stays authoritative in BOTH directions.
    @Test
    fun workspaceUpdated_thenSnapshot_theSnapshotLabelWins() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Stale local"))
            runCurrent()
            // A reconnect snapshot whose row carries no label clears the locally-applied one.
            pump.push(conversationsEnvelope(workspaceFixture(alphaLabel = null)))
            runCurrent()
            assertNull(all.last().single { it.id == "a1" }.workspaceLabel)

            // And the mirror case: a stale local clear is restored by the snapshot's stored label.
            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = null))
            runCurrent()
            pump.push(conversationsEnvelope(workspaceFixture(alphaLabel = "Tax filing")))
            runCurrent()
            assertEquals("Tax filing", all.last().single { it.id == "a1" }.workspaceLabel)
        }

    // ---- conversation_updated (#721): the unsolicited push folds into the projection -------------

    // AC #2: an unsolicited conversation_updated (no in_reply_to at all) is folded by conversation id,
    // carrying its record AND its label — in place, with no duplicate row.
    @Test
    fun conversationUpdated_unsolicitedPush_foldsRecordAndLabelInPlace() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            pump.push(
                conversationUpdatedEnvelope(
                    id = "a1",
                    name = "Named on the host",
                    cwd = "/w/alpha",
                    workspaceLabel = "Tax filing",
                ),
            )
            runCurrent()

            val rows = all.last()
            assertEquals(3, rows.size) // folded in place — dedup by id, no second "a1"
            val a1 = rows.single { it.id == "a1" }
            assertEquals("Named on the host", a1.name)
            assertEquals("Tax filing", a1.workspaceLabel)
        }

    // AC #2: a frame moving the conversation to a differently-labelled workspace lands the DESTINATION
    // cwd and the destination label — the case a client cannot resolve for itself.
    @Test
    fun conversationUpdated_unsolicitedMove_destinationLabelWins() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()
            pump.push(workspaceUpdatedEnvelope(path = "/w/alpha", label = "Alpha label"))
            runCurrent()
            assertEquals("Alpha label", all.last().single { it.id == "a1" }.workspaceLabel)

            pump.push(
                conversationUpdatedEnvelope(id = "a1", name = "Alpha one", cwd = "/w/beta", workspaceLabel = "Beta label"),
            )
            runCurrent()

            val a1 = all.last().single { it.id == "a1" }
            assertEquals("/w/beta", a1.cwd)
            assertEquals("Beta label", a1.workspaceLabel)
        }

    // AC #2: the correlated half still completes its waiter, returns the label the reply carried, and
    // leaves exactly one row for that id (the waiter's own upsert, not a second fold).
    @Test
    fun conversationUpdated_correlatedRenameReply_preservesLabelAndLeavesOneRow() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()

            val rename = startRename(repo, "a1", "Renamed")
            runCurrent()
            val sentId = pump.sent.single { it.type == "rename_conversation" }.id
            pump.push(
                conversationUpdatedEnvelope(
                    inReplyTo = sentId,
                    id = "a1",
                    name = "Renamed",
                    cwd = "/w/alpha",
                    workspaceLabel = "Tax filing",
                ),
            )
            runCurrent()

            assertEquals("Tax filing", rename().getOrThrow().workspaceLabel)
            val rows = all.last()
            assertEquals(1, rows.count { it.id == "a1" })
            assertEquals("Tax filing", rows.single { it.id == "a1" }.workspaceLabel)
        }

    // AC #3: a malformed unsolicited conversation_updated (no `cwd`) mutates nothing — decode precedes
    // the fold — and the single inbound collector survives for the next valid frame.
    @Test
    fun conversationUpdated_malformedUnsolicitedPush_leavesDataIntactAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val all = collectConversations(repo, ConversationFilter.All)
            runCurrent()
            pump.push(conversationsEnvelope(WORKSPACE_FIXTURE))
            runCurrent()
            val emissionsBefore = all.size

            pump.push(
                Envelope(
                    id = 42L,
                    type = "conversation_updated",
                    ts = TS,
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"id":"a1","name":"x","is_promoted":true,"last_used_at":"2026-05-08T12:00:00Z"}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(emissionsBefore, all.size)
            assertEquals("Alpha one", all.last().single { it.id == "a1" }.name)

            pump.push(conversationUpdatedEnvelope(id = "a1", name = "Named on the host", cwd = "/w/alpha"))
            runCurrent()
            assertEquals("Named on the host", all.last().single { it.id == "a1" }.name)
        }

    // ---- Helpers --------------------------------------------------------------------------------

    /**
     * Launch [RemoteConversationRepository.requestScreenSnapshot] on [backgroundScope] (it suspends
     * awaiting the screen_snapshot/error reply) and return a getter for its eventual [Result]. Read the
     * result only after the correlated reply has been pushed and [runCurrent] has drained the cascade.
     */
    private fun TestScope.startSnapshot(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): () -> Result<String> {
        var outcome: Result<String>? = null
        backgroundScope.launch { outcome = runCatching { repo.requestScreenSnapshot(conversationId) } }
        return { requireNotNull(outcome) { "requestScreenSnapshot has not completed" } }
    }

    /** A correlated `screen_snapshot` reply carrying the rendered screen [text] (#375). */
    private fun screenSnapshotEnvelope(
        inReplyTo: Long,
        conversationId: String,
        text: String,
        ts: String = TS,
        envId: Long = 99L,
    ): Envelope =
        Envelope(
            id = envId,
            type = "screen_snapshot",
            ts = TS,
            payload =
                MobileJson.encodeToJsonElement(
                    ScreenSnapshotPayloadDto(conversationId = conversationId, text = text, ts = ts),
                ),
            inReplyTo = inReplyTo,
        )

    /**
     * Launch [RemoteConversationRepository.sendMessage] on [backgroundScope] (it suspends awaiting the
     * ack/error reply) and return a getter for its eventual [Result]. Read the result only after the
     * correlated reply has been pushed and [runCurrent] has drained the cascade.
     */
    private fun TestScope.startSend(
        repo: RemoteConversationRepository,
        conversationId: String,
        text: String,
    ): () -> Result<Message> {
        var outcome: Result<Message>? = null
        backgroundScope.launch { outcome = runCatching { repo.sendMessage(conversationId, text) } }
        return { requireNotNull(outcome) { "sendMessage has not completed" } }
    }

    /**
     * Send [text], ack it, and return the `message_id` the repository minted (#781) — the id now on the
     * confirmed-inserted echo in the thread, and the one a `queue_state` item would carry back if the
     * daemon parked this message. Drains the cascade, so the caller can assert immediately afterwards.
     */
    private fun TestScope.sendAndAck(
        repo: RemoteConversationRepository,
        pump: FakeSessionPump,
        conversationId: String,
        text: String,
    ): String {
        val send = startSend(repo, conversationId, text)
        runCurrent()
        pump.push(ackEnvelope(pump.sent.last { it.type == "send_message" }.id))
        runCurrent()
        return send().getOrThrow().id
    }

    /**
     * Launch [RemoteConversationRepository.createDiscussion] on [backgroundScope] (it suspends
     * awaiting the conversation_created/error reply) and return a getter for its eventual [Result].
     * Read the result only after the correlated reply has been pushed and [runCurrent] has drained
     * the cascade.
     */
    private fun TestScope.startCreate(
        repo: RemoteConversationRepository,
        workspace: String?,
    ): () -> Result<Conversation> {
        var outcome: Result<Conversation>? = null
        backgroundScope.launch { outcome = runCatching { repo.createDiscussion(workspace) } }
        return { requireNotNull(outcome) { "createDiscussion has not completed" } }
    }

    /** Launch [RemoteConversationRepository.createChannel] like [startCreate] (#956). */
    private fun TestScope.startCreateChannel(
        repo: RemoteConversationRepository,
        name: String,
        workspace: String,
    ): () -> Result<Conversation> {
        var outcome: Result<Conversation>? = null
        backgroundScope.launch { outcome = runCatching { repo.createChannel(name, workspace) } }
        return { requireNotNull(outcome) { "createChannel has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.promote] on [backgroundScope] (it suspends awaiting the
     * conversation_updated/error reply) and return a getter for its eventual [Result]. Read the result
     * only after the correlated reply has been pushed and [runCurrent] has drained the cascade.
     */
    private fun TestScope.startPromote(
        repo: RemoteConversationRepository,
        conversationId: String,
        name: String,
        workspace: String?,
    ): () -> Result<Conversation> {
        var outcome: Result<Conversation>? = null
        backgroundScope.launch { outcome = runCatching { repo.promote(conversationId, name, workspace) } }
        return { requireNotNull(outcome) { "promote has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.rename] on [backgroundScope] (it suspends awaiting the
     * conversation_updated/error reply) and return a getter for its eventual [Result]. Read the result
     * only after the correlated reply has been pushed and [runCurrent] has drained the cascade.
     */
    private fun TestScope.startRename(
        repo: RemoteConversationRepository,
        conversationId: String,
        name: String,
    ): () -> Result<Conversation> {
        var outcome: Result<Conversation>? = null
        backgroundScope.launch { outcome = runCatching { repo.rename(conversationId, name) } }
        return { requireNotNull(outcome) { "rename has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.changeWorkspace] on [backgroundScope] (it suspends awaiting
     * the conversation_updated/error reply) and return a getter for its eventual [Result]. Read the
     * result only after the correlated reply has been pushed and [runCurrent] has drained the cascade.
     */
    private fun TestScope.startChangeWorkspace(
        repo: RemoteConversationRepository,
        conversationId: String,
        workspace: String,
    ): () -> Result<Session> {
        var outcome: Result<Session>? = null
        backgroundScope.launch { outcome = runCatching { repo.changeWorkspace(conversationId, workspace) } }
        return { requireNotNull(outcome) { "changeWorkspace has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.createWorkspaceFolder] on [backgroundScope] (it suspends
     * awaiting the workspace_folder_created/error reply, unless the blank-name guard throws first) and
     * return a getter for its eventual [Result]. Read the result only after the correlated reply has
     * been pushed and [runCurrent] has drained the cascade (the blank-name and not-Open paths complete
     * synchronously, before any reply).
     */
    private fun TestScope.startCreateWorkspaceFolder(
        repo: RemoteConversationRepository,
        name: String,
    ): () -> Result<String> {
        var outcome: Result<String>? = null
        backgroundScope.launch { outcome = runCatching { repo.createWorkspaceFolder(name) } }
        return { requireNotNull(outcome) { "createWorkspaceFolder has not completed" } }
    }

    /**
     * Collect the first emission of [RemoteConversationRepository.recentWorkspaces] (a cold one-shot
     * flow that suspends awaiting the recent_workspaces_list/error reply, unless the not-Open `check`
     * throws first) on [backgroundScope] and return a getter for its eventual [Result]. Read the result
     * only after the correlated reply has been pushed and [runCurrent] has drained the cascade (the
     * not-Open path completes synchronously via .catch, before any reply).
     */
    private fun TestScope.startRecentWorkspaces(repo: RemoteConversationRepository): () -> Result<List<String>> {
        var outcome: Result<List<String>>? = null
        backgroundScope.launch { outcome = runCatching { repo.recentWorkspaces().first() } }
        return { requireNotNull(outcome) { "recentWorkspaces has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.requestHistory] on [backgroundScope] (it suspends awaiting
     * the history_page/error reply) and return a getter for its eventual [Result]. As [startArchive]:
     * read the result only after the correlated reply has been pushed and [runCurrent] has drained.
     */
    private fun TestScope.startRequestHistory(
        repo: RemoteConversationRepository,
        conversationId: String,
        cursor: String = "",
        limit: Int = 0,
    ): () -> Result<HistoryPage> {
        var outcome: Result<HistoryPage>? = null
        backgroundScope.launch { outcome = runCatching { repo.requestHistory(conversationId, cursor, limit) } }
        return { requireNotNull(outcome) { "requestHistory has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.archive] on [backgroundScope] (it suspends awaiting the
     * conversation_updated/error reply) and return a getter for its eventual [Result]. Read the result
     * only after the correlated reply has been pushed and [runCurrent] has drained the cascade (the
     * not-Open path completes synchronously, before any reply).
     */
    private fun TestScope.startArchive(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.archive(conversationId) } }
        return { requireNotNull(outcome) { "archive has not completed" } }
    }

    /** As [startArchive], for [RemoteConversationRepository.unarchive]. */
    private fun TestScope.startUnarchive(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.unarchive(conversationId) } }
        return { requireNotNull(outcome) { "unarchive has not completed" } }
    }

    /** As [startArchive], for [RemoteConversationRepository.delete] (awaits the conversation_deleted/error reply). */
    private fun TestScope.startDelete(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.delete(conversationId) } }
        return { requireNotNull(outcome) { "delete has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.setSessionSettings] on [backgroundScope] (it suspends
     * awaiting the session_settings_updated/error reply) and return a getter for its eventual [Result].
     * Read the result only after the correlated reply has been pushed and [runCurrent] has drained the
     * cascade (the not-Open path completes synchronously, before any reply).
     */
    private fun TestScope.startSetSessionSettings(
        repo: RemoteConversationRepository,
        sessionId: String,
        model: String? = null,
        effort: String? = null,
        yolo: Boolean? = null,
        permissionMode: String? = null,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.setSessionSettings(sessionId, model, effort, yolo, permissionMode) } }
        return { requireNotNull(outcome) { "setSessionSettings has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.registerPushToken] on [backgroundScope] (it suspends
     * awaiting the ack/error reply) and return a getter for its eventual [Result]. Read the result
     * only after the correlated reply has been pushed and [runCurrent] has drained the cascade (the
     * not-Open path completes synchronously, before any reply).
     */
    private fun TestScope.startRegisterPushToken(
        repo: RemoteConversationRepository,
        token: String,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.registerPushToken(token) } }
        return { requireNotNull(outcome) { "registerPushToken has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.answerModal] on [backgroundScope] (it suspends awaiting the
     * ack/error reply) and return a getter for its eventual [Result]. Read the result only after the
     * correlated reply has been pushed and [runCurrent] has drained the cascade (the not-Open path
     * completes synchronously, before any reply).
     */
    private fun TestScope.startAnswerModal(
        repo: RemoteConversationRepository,
        modalId: String,
        optionId: String,
        alwaysAllow: Boolean = false,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.answerModal(modalId, optionId, alwaysAllow) } }
        return { requireNotNull(outcome) { "answerModal has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.cancelModal] on [backgroundScope] and return a getter for
     * its eventual [Result], mirroring [startAnswerModal].
     */
    private fun TestScope.startCancelModal(
        repo: RemoteConversationRepository,
        modalId: String,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.cancelModal(modalId) } }
        return { requireNotNull(outcome) { "cancelModal has not completed" } }
    }

    /**
     * Launch [RemoteConversationRepository.dropQueuedMessage] on [backgroundScope] and return a getter
     * for its eventual [Result], mirroring [startCancelModal]. The daemon never replies to the dequeue
     * (#859), so the call completes once the frame is sent; the getter throws if it is still suspended.
     */
    private fun TestScope.startDropQueuedMessage(
        repo: RemoteConversationRepository,
        conversationId: String,
        queuedMessageId: Long,
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.dropQueuedMessage(conversationId, queuedMessageId) } }
        return { requireNotNull(outcome) { "dropQueuedMessage has not completed" } }
    }

    private fun Envelope.withWorkspaceLabel(label: String?): Envelope =
        copy(payload = JsonObject(payload.jsonObject + ("workspace_label" to JsonPrimitive(label))))

    /** A correlated `conversation_created` reply carrying a bare conversation object (#347). */
    private fun conversationCreatedEnvelope(
        inReplyTo: Long,
        id: String,
        cwd: String,
        isPromoted: Boolean = false,
        name: String? = null,
        lastUsedAt: String = "2026-05-08T10:00:00Z",
        envId: Long = 99L,
    ): Envelope {
        val nameJson = if (name == null) "null" else "\"$name\""
        return Envelope(
            id = envId,
            type = "conversation_created",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"id":"$id","name":$nameJson,"is_promoted":$isPromoted,"cwd":"$cwd","last_used_at":"$lastUsedAt"}""",
                ),
            inReplyTo = inReplyTo,
        )
    }

    /**
     * A `conversation_updated` envelope carrying a bare conversation object (#348). [inReplyTo] defaults
     * to `null` — the unsolicited-push shape (#721), which folds into the projection by the payload's
     * own `id` rather than completing a waiter.
     */
    private fun conversationUpdatedEnvelope(
        id: String,
        cwd: String,
        inReplyTo: Long? = null,
        isPromoted: Boolean = true,
        isArchived: Boolean = false,
        name: String? = null,
        workspaceLabel: String? = null,
        lastUsedAt: String = "2026-05-08T10:00:00Z",
        envId: Long = 99L,
    ): Envelope {
        val nameJson = if (name == null) "null" else "\"$name\""
        val labelJson = if (workspaceLabel == null) "null" else "\"$workspaceLabel\""
        return Envelope(
            id = envId,
            type = "conversation_updated",
            ts = TS,
            // is_archived and workspace_label are always present on the wire (pyrycode#881, #2210 —
            // both nullable but never omitted).
            payload =
                MobileJson.parseToJsonElement(
                    """{"id":"$id","name":$nameJson,"is_promoted":$isPromoted,"is_archived":$isArchived,"cwd":"$cwd","last_used_at":"$lastUsedAt","workspace_label":$labelJson}""",
                ),
            inReplyTo = inReplyTo,
        )
    }

    /** A correlated `session_settings_updated` ack carrying only `{session_id}` (#543). */
    private fun sessionSettingsUpdatedEnvelope(
        inReplyTo: Long,
        sessionId: String,
        id: Long = 99L,
    ): Envelope =
        Envelope(
            id = id,
            type = "session_settings_updated",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"session_id":"$sessionId"}"""),
            inReplyTo = inReplyTo,
        )

    /** A correlated `conversation_deleted` ack carrying only `{id}` (#532). */
    private fun conversationDeletedEnvelope(
        inReplyTo: Long,
        id: String,
        envId: Long = 99L,
    ): Envelope =
        Envelope(
            id = envId,
            type = "conversation_deleted",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"id":"$id"}"""),
            inReplyTo = inReplyTo,
        )

    /** A correlated `workspace_folder_created` reply carrying only the created folder's `{path}` (#564). */
    private fun workspaceFolderCreatedEnvelope(
        inReplyTo: Long,
        path: String,
        envId: Long = 99L,
    ): Envelope =
        Envelope(
            id = envId,
            type = "workspace_folder_created",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"path":"$path"}"""),
            inReplyTo = inReplyTo,
        )

    /**
     * A correlated `recent_workspaces_list` reply carrying `{workspaces:[{path, last_used_at}]}` (#565),
     * one row per [paths] entry in the given order. The daemon-authoritative `last_used_at` is included
     * on the wire (the client discards it via `ignoreUnknownKeys` — only `path` is modeled).
     */
    private fun recentWorkspacesListEnvelope(
        inReplyTo: Long,
        paths: List<String>,
        envId: Long = 99L,
    ): Envelope {
        val rows = paths.joinToString(",") { """{"path":"$it","last_used_at":"$TS"}""" }
        return Envelope(
            id = envId,
            type = "recent_workspaces_list",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"workspaces":[$rows]}"""),
            inReplyTo = inReplyTo,
        )
    }

    private fun ackEnvelope(
        inReplyTo: Long,
        id: Long = 99L,
    ): Envelope = Envelope(id = id, type = "ack", ts = TS, payload = JsonObject(emptyMap()), inReplyTo = inReplyTo)

    /**
     * Collect [RemoteConversationRepository.observeSessionSettings] on [backgroundScope] into a live
     * list (#590). The flow is cold and per-collector, so subscribing is what issues the first read;
     * read the list after [runCurrent] has drained the cascade.
     */
    private fun TestScope.collectSessionSettings(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<SessionSettings?> {
        val emissions = mutableListOf<SessionSettings?>()
        backgroundScope.launch { repo.observeSessionSettings(conversationId).collect { emissions += it } }
        return emissions
    }

    /** A correlated `session_settings` reply carrying [raw] verbatim, so a test can push a malformed one. */
    private fun sessionSettingsEnvelope(
        inReplyTo: Long,
        raw: String = POPULATED_SETTINGS,
        id: Long = 97L,
    ): Envelope =
        Envelope(
            id = id,
            type = "session_settings",
            ts = TS,
            payload = MobileJson.parseToJsonElement(raw),
            inReplyTo = inReplyTo,
        )

    private fun errorEnvelope(
        inReplyTo: Long,
        code: String,
        message: String = "boom",
        retryable: Boolean = false,
        id: Long = 99L,
    ): Envelope =
        Envelope(
            id = id,
            type = "error",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"$message","retryable":$retryable}"""),
            inReplyTo = inReplyTo,
        )

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

    private fun TestScope.collectMessages(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<List<ThreadItem>> {
        val emissions = mutableListOf<List<ThreadItem>>()
        backgroundScope.launch { repo.observeMessages(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun messageIds(thread: List<ThreadItem>): List<String> = thread.map { (it as ThreadItem.MessageItem).message.id }

    /**
     * Arrival-order shape of a mixed thread (#336): a message row → its id, a boundary →
     * "boundary:<reason>", an unrecognized row → "unrecognized:<site>" (the row type shipped in #608;
     * #609 wired the decode arm that folds it).
     */
    private fun threadShape(thread: List<ThreadItem>): List<String> =
        thread.map {
            when (it) {
                is ThreadItem.MessageItem -> it.message.id
                is ThreadItem.SessionBoundary -> "boundary:${it.reason}"
                is ThreadItem.UnrecognizedMessage -> "unrecognized:${it.site}"
                is ThreadItem.Banner -> "banner:${it.level}"
                is ThreadItem.CompactionBoundary -> "compaction:${it.preTokens}->${it.postTokens}:${it.manual}"
                is ThreadItem.ModelRefusal -> if (it.fallbackModel != null) "refusal:fallback" else "refusal:no-fallback"
            }
        }

    /** Every [ThreadItem.SessionBoundary] in [thread], in order (#336). */
    private fun boundariesOf(thread: List<ThreadItem>): List<ThreadItem.SessionBoundary> =
        thread.filterIsInstance<ThreadItem.SessionBoundary>()

    /** The [ToolCall] of the [Role.Tool] thread row with [id] in [thread], or null if absent (#387). */
    private fun toolCallOf(
        thread: List<ThreadItem>,
        id: String,
    ): ToolCall? =
        thread
            .filterIsInstance<ThreadItem.MessageItem>()
            .firstOrNull { it.message.id == id && it.message.role == Role.Tool }
            ?.message
            ?.toolCall

    /** The [Role.Assistant] thread row with [id] (the turn id) in [thread], or null if absent (#337). */
    private fun assistantRowOf(
        thread: List<ThreadItem>,
        id: String,
    ): Message? =
        thread
            .filterIsInstance<ThreadItem.MessageItem>()
            .firstOrNull { it.message.id == id && it.message.role == Role.Assistant }
            ?.message

    /** One `message_chunk` row JSON object (same shape as a `message` payload). */
    private fun chunkRow(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
    ): String = """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}"""

    private fun messageChunkEnvelope(
        rows: List<String>,
        ts: String = TS,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "message_chunk",
            ts = ts,
            payload = MobileJson.parseToJsonElement("""{"messages":[${rows.joinToString(",")}]}"""),
        )

    private fun messageEnvelope(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
        ts: String,
        id: Long = 1L,
        eventId: Long? = null,
    ): Envelope =
        Envelope(
            id = id,
            type = "message",
            ts = ts,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}""",
                ),
            eventId = eventId,
        )

    private fun TestScope.collectStall(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<Boolean> {
        val emissions = mutableListOf<Boolean>()
        backgroundScope.launch { repo.observeStall(conversationId).collect { emissions += it } }
        return emissions
    }

    /** A `stall` control envelope `{conversation_id}` (#395) — onset-only, no clearing edge on the wire. */
    private fun stallEnvelope(
        conversationId: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "stall",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId"}"""),
        )

    private fun TestScope.collectQueue(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<List<QueuedMessage>> {
        val emissions = mutableListOf<List<QueuedMessage>>()
        backgroundScope.launch { repo.observeQueue(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * One `queued` array element. [messageId] is the client-minted `message_id` pyrycode#2092 relays
     * verbatim (#781) — defaulted to the legal `""` ("this item correlates with no echo") so a fixture
     * says nothing about correlation unless the case under test is about it.
     */
    private data class QueuedFixture(
        val queuedMsgId: Long,
        val text: String,
        val ts: String,
        val messageId: String = "",
    )

    /**
     * A `queue_state` snapshot envelope `{conversation_id, queued:[{queued_msg_id, message_id, text, ts}]}`
     * (#460, #781). `queued_msg_id` is emitted as a JSON **number** (the wire uint64) and `message_id` as
     * a string; both are strict-required on the wire. An empty [items] emits `"queued":[]`.
     */
    private fun queueStateEnvelope(
        conversationId: String,
        items: List<QueuedFixture>,
        id: Long = 1L,
    ): Envelope {
        val queued =
            items.joinToString(",") {
                """{"queued_msg_id":${it.queuedMsgId},"message_id":"${it.messageId}","text":"${it.text}","ts":"${it.ts}"}"""
            }
        return Envelope(
            id = id,
            type = "queue_state",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","queued":[$queued]}"""),
        )
    }

    private fun TestScope.collectApiRetry(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<ApiRetryStatus> {
        val emissions = mutableListOf<ApiRetryStatus>()
        backgroundScope.launch { repo.observeApiRetry(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * An `api_retry` control envelope `{conversation_id, active, current, total}` (#593). All four
     * fields are always present on the wire (no `omitempty`), so the helper always emits all four;
     * [current] / [total] are JSON **numbers** and are carried even on a falling edge (the daemon
     * copies the last-known counter there, and the client is contractually required to ignore it).
     */
    private fun apiRetryEnvelope(
        conversationId: String,
        active: Boolean,
        current: Int,
        total: Int,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "api_retry",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","active":$active,"current":$current,"total":$total}""",
                ),
        )

    private fun TestScope.collectCompacting(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<Boolean> {
        val emissions = mutableListOf<Boolean>()
        backgroundScope.launch { repo.observeCompacting(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * A `compacting` control envelope `{conversation_id, active}` (#596). Both fields are always present
     * on the wire (no `omitempty`), so the helper always emits both; [active] is the edge — `true` on
     * onset, `false` once compaction finished. Banner-only: the payload carries no counter to build.
     */
    private fun compactingEnvelope(
        conversationId: String,
        active: Boolean,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "compacting",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","active":$active}"""),
        )

    /** A raw `compacting` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun compactingProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "compacting", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /**
     * A `resetting` control envelope `{conversation_id, active, phase, handoff}` (#871). All four keys are
     * always on the wire (no `omitempty`); a falling edge carries both strings as `""`.
     */
    private fun resettingEnvelope(
        conversationId: String,
        active: Boolean,
        phase: String,
        handoff: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "resetting",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","active":$active,"phase":"$phase","handoff":"$handoff"}""",
                ),
        )

    /** A raw `resetting` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun resettingProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "resetting", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    private fun TestScope.collectResetting(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<ResetStatus?> {
        val emissions = mutableListOf<ResetStatus?>()
        backgroundScope.launch { repo.observeResetting(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * An `interactive`-capable repository (#802), optionally on a caller-supplied clock. The clock is
     * what makes the read-time expiry deterministic: `Clock.System.now()` would make every
     * `resets_at`-dependent assertion depend on the wall clock of the machine running the suite.
     */
    private fun TestScope.interactiveRepo(
        pump: FakeSessionPump,
        now: () -> Instant = { Instant.fromEpochSeconds(FIXED_NOW) },
    ): RemoteConversationRepository =
        RemoteConversationRepository(
            pump,
            backgroundScope,
            negotiatedCapabilities = { setOf("interactive") },
            now = now,
        )

    private fun TestScope.collectUsageLimit(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<UsageLimitReading?> {
        val emissions = mutableListOf<UsageLimitReading?>()
        backgroundScope.launch { repo.observeUsageLimit(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun TestScope.collectThinkingProgress(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<ThinkingProgress?> {
        val emissions = mutableListOf<ThinkingProgress?>()
        backgroundScope.launch { repo.observeThinkingProgress(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * A `rate_limited` envelope (#802). All six keys are always present on the wire, so the helper
     * always emits all six — including `utilization` and `truncated_fields`, whose **explicit `null`**
     * is the shape the daemon sends when claude reported none, and which must stay distinguishable
     * from `0` and from `[]` respectively. The defaults are the one measured non-benign reading:
     * `allowed_warning` against `seven_day`.
     */
    private fun rateLimitedEnvelope(
        conversationId: String,
        status: String = "allowed_warning",
        limitType: String = "seven_day",
        resetsAt: Long = FUTURE_RESET,
        utilization: Double? = null,
        truncatedFields: List<String>? = null,
        id: Long = 1L,
    ): Envelope {
        val truncated = truncatedFields?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"
        return Envelope(
            id = id,
            type = "rate_limited",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","status":"$status","limit_type":"$limitType",""" +
                        """"resets_at":$resetsAt,"utilization":${utilization ?: "null"},"truncated_fields":$truncated}""",
                ),
        )
    }

    /** A raw `rate_limited` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun rateLimitedProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "rate_limited", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /**
     * A `thinking_progress` envelope `{conversation_id, estimated_tokens, estimated_tokens_delta}`
     * (#801). All three fields are always present on the wire (no `omitempty`), so the helper always
     * emits all three — including a `0` reading, which is the inference-request restart and not an
     * omitted value. Neither reading is bounded here: a fixture may fall, repeat or sit at zero.
     */
    private fun thinkingProgressEnvelope(
        conversationId: String,
        estimatedTokens: Long,
        estimatedTokensDelta: Long,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "thinking_progress",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","estimated_tokens":$estimatedTokens,"estimated_tokens_delta":$estimatedTokensDelta}""",
                ),
        )

    /** A raw `thinking_progress` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun thinkingProgressProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "thinking_progress", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    private fun TestScope.collectModelMenu(
        repo: RemoteConversationRepository,
        conversationId: String,
    ): MutableList<ModelMenu?> {
        val emissions = mutableListOf<ModelMenu?>()
        backgroundScope.launch { repo.observeModelMenu(conversationId).collect { emissions += it } }
        return emissions
    }

    /**
     * One `models` array element (#791). [truncatedFields] emits the key only when non-null, so the
     * default fixture exercises the omitted shape the wire uses when nothing was cut; [effortLevels]
     * defaults to a populated list so a fixture says nothing about the empty case unless it means to.
     */
    private data class ModelRowFixture(
        val resolvedModel: String,
        val value: String,
        val displayName: String,
        val effortLevels: List<String> = listOf("low", "high"),
        val supportsAutoMode: Boolean = true,
        val truncatedFields: List<String>? = null,
    )

    /**
     * A `model_list` snapshot envelope `{conversation_id, models:[…], dropped_models}` (#791).
     * `dropped_models` is a JSON **number** carried verbatim, never derived from [rows]. [id] is the
     * ENVELOPE id, which the reconcile burst repeats across every frame — fixtures that share one are
     * proving that routing ignores it.
     */
    private fun modelListEnvelope(
        conversationId: String,
        rows: List<ModelRowFixture>,
        droppedModels: Int = 0,
        id: Long = 1L,
        /** Set to make the frame the correlated answer to a `request_model_list` (#792); the two
         *  delivery paths are otherwise byte-identical, so the default is the unsolicited shape. */
        inReplyTo: Long? = null,
    ): Envelope {
        val models =
            rows.joinToString(",") { row ->
                val levels = row.effortLevels.joinToString(",") { """"$it"""" }
                val truncated = row.truncatedFields?.joinToString(",") { """"$it"""" }
                val truncatedKey = if (truncated == null) "" else ""","truncated_fields":[$truncated]"""
                """{"resolved_model":"${row.resolvedModel}","value":"${row.value}",""" +
                    """"display_name":"${row.displayName}","effort_levels":[$levels],""" +
                    """"supports_auto_mode":${row.supportsAutoMode}$truncatedKey}"""
            }
        return Envelope(
            id = id,
            type = "model_list",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","models":[$models],"dropped_models":$droppedModels}""",
                ),
            inReplyTo = inReplyTo,
        )
    }

    /** Every `request_model_list` this connection sent, in order (#792). */
    private fun FakeSessionPump.modelListAsks(): List<Envelope> = sent.filter { it.type == "request_model_list" }

    /** A raw `model_list` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun modelListProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "model_list", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /**
     * A `session_transition` envelope `{conversation_id, previous_session_id, new_session_id, reason,
     * occurred_at, workspace_cwd}` (#336). [workspaceCwd] emits `"workspace_cwd":null` when null (the
     * `clear` / `idle_evict` shape) and a quoted string otherwise (the `workspace_change` shape).
     */
    private fun sessionTransitionEnvelope(
        conversationId: String,
        previousSessionId: String,
        newSessionId: String,
        reason: String,
        occurredAt: String = TS,
        workspaceCwd: String? = null,
        id: Long = 1L,
    ): Envelope {
        val cwd = workspaceCwd?.let { "\"$it\"" } ?: "null"
        return Envelope(
            id = id,
            type = "session_transition",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","previous_session_id":"$previousSessionId","new_session_id":"$newSessionId","reason":"$reason","occurred_at":"$occurredAt","workspace_cwd":$cwd}""",
                ),
        )
    }

    /**
     * An `unrecognized_message` envelope `{conversation_id, site, message_type, raw, truncated}` (#609).
     * All five fields are always present on the wire (no `omitempty`), so the helper always emits all
     * five — including an empty [messageType] (the `undecodable` shape) and `truncated: false`. Built
     * through [buildJsonObject] rather than string interpolation because [raw] is arbitrary JSON text
     * whose own quotes and braces must survive into the payload intact.
     */
    private fun unrecognizedMessageEnvelope(
        conversationId: String,
        site: String,
        messageType: String,
        raw: String,
        truncated: Boolean = false,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "unrecognized_message",
            ts = TS,
            payload =
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("site", site)
                    put("message_type", messageType)
                    put("raw", raw)
                    put("truncated", truncated)
                },
        )

    /** A raw `unrecognized_message` envelope carrying [payload] verbatim — for the malformed-payload probes. */
    private fun unrecognizedProbe(
        id: Long,
        payload: String,
    ): Envelope = Envelope(id = id, type = "unrecognized_message", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** A `banner` envelope (#873); [ts] is the envelope timestamp the row takes as its identity. */
    private fun bannerEnvelope(
        conversationId: String,
        level: String,
        text: String = "Blocked by hook",
        truncated: Boolean = false,
        stopsTurn: Boolean = false,
        ts: String = TS,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "banner",
            ts = ts,
            payload =
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("level", level)
                    put("text", text)
                    put("truncated", truncated)
                    put("stops_turn", stopsTurn)
                },
        )

    /**
     * A `compaction_boundary` envelope (#874). [pre] and [post] are raw JSON literals so a test can send
     * `null`, a negative or a non-integer; the default is the observed manual shape.
     */
    private fun compactionEnvelope(
        conversationId: String,
        trigger: String = "manual",
        pre: String = "24000",
        post: String = "3000",
        ts: String = TS,
        id: Long = 1L,
    ): Envelope =
        compactionProbe(
            id = id,
            ts = ts,
            payload = """{"conversation_id":"$conversationId","trigger":"$trigger","pre_tokens":$pre,"post_tokens":$post}""",
        )

    /** A raw `compaction_boundary` envelope carrying [payload] verbatim (#874). */
    private fun compactionProbe(
        id: Long,
        payload: String,
        ts: String = TS,
    ): Envelope = Envelope(id = id, type = "compaction_boundary", ts = ts, payload = MobileJson.parseToJsonElement(payload))

    /** Every [ThreadItem.CompactionBoundary] in [thread], in order (#874). */
    private fun compactionRowsOf(thread: List<ThreadItem>): List<ThreadItem.CompactionBoundary> =
        thread.filterIsInstance<ThreadItem.CompactionBoundary>()

    /**
     * A model refusal envelope (#875): `model_refusal_fallback` when [fallbackModel] is non-null, else
     * `model_refusal_no_fallback`, each carrying exactly its own wire fields. [truncatedFields] is a raw JSON
     * literal so a test can send `null` or an array.
     */
    private fun refusalEnvelope(
        conversationId: String,
        fallbackModel: String?,
        originalModel: String = "claude-opus-5-5",
        banner: String = "Claude declined this request.",
        truncatedFields: String = "null",
        ts: String = TS,
        id: Long = 1L,
    ): Envelope {
        val common =
            """"conversation_id":"$conversationId","original_model":"$originalModel","refusal_category":"cyber",""" +
                """"banner":${JsonPrimitive(banner)},"truncated_fields":$truncatedFields,"dropped_fields":null"""
        return if (fallbackModel != null) {
            refusalProbe(id, "model_refusal_fallback", """{$common,"fallback_model":"$fallbackModel","scope":"session"}""", ts)
        } else {
            refusalProbe(id, "model_refusal_no_fallback", "{$common}", ts)
        }
    }

    /** A raw model refusal envelope of [type] carrying [payload] verbatim (#875). */
    private fun refusalProbe(
        id: Long,
        type: String,
        payload: String,
        ts: String = TS,
    ): Envelope = Envelope(id = id, type = type, ts = ts, payload = MobileJson.parseToJsonElement(payload))

    /** Every [ThreadItem.ModelRefusal] in [thread], in order (#875). */
    private fun refusalRowsOf(thread: List<ThreadItem>): List<ThreadItem.ModelRefusal> = thread.filterIsInstance<ThreadItem.ModelRefusal>()

    /** Every [ThreadItem.Banner] in [thread], in order (#873). */
    private fun bannerRowsOf(thread: List<ThreadItem>): List<ThreadItem.Banner> = thread.filterIsInstance<ThreadItem.Banner>()

    /** Every [ThreadItem.UnrecognizedMessage] in [thread], in order (#609). */
    private fun unrecognizedRowsOf(thread: List<ThreadItem>): List<ThreadItem.UnrecognizedMessage> =
        thread.filterIsInstance<ThreadItem.UnrecognizedMessage>()

    /** A `resync` control marker `{conversation_id}` (#417) — no event_id; daemon's aged-out-of-ring signal. */
    private fun resyncEnvelope(
        conversationId: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "resync",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId"}"""),
        )

    /** Stall `"c1"`, assert it flipped on, push [forwardEvent], assert it cleared. Fresh repo per call. */
    private fun TestScope.assertClearsStall(forwardEvent: Envelope) {
        val pump = FakeSessionPump()
        val repo = RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf("interactive") })
        val stalls = collectStall(repo, "c1")
        runCurrent()
        pump.push(stallEnvelope("c1"))
        runCurrent()
        assertEquals(listOf(false, true), stalls)
        pump.push(forwardEvent)
        runCurrent()
        assertEquals(listOf(false, true, false), stalls)
    }

    private fun TestScope.collectLiveEvents(repo: RemoteConversationRepository): MutableList<LiveSessionEvent> {
        // liveSessionEvents is a replay=0 SharedFlow, so the collector must subscribe before any push;
        // callers runCurrent() after this to let the subscription attach, then push.
        val events = mutableListOf<LiveSessionEvent>()
        backgroundScope.launch { repo.liveSessionEvents.collect { events += it } }
        return events
    }

    private fun TestScope.collectModalEvents(repo: RemoteConversationRepository): MutableList<ModalEvent> {
        // modalEvents is a replay=0 SharedFlow, so the collector must subscribe before any push; callers
        // runCurrent() after this to let the subscription attach, then push. Mirrors collectLiveEvents.
        val events = mutableListOf<ModalEvent>()
        backgroundScope.launch { repo.modalEvents.collect { events += it } }
        return events
    }

    /** A `modal_shown` envelope (#437) — raw payload so tests can vary fields, options, and extra keys. */
    private fun modalShownEnvelope(
        rawPayload: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "modal_shown",
            ts = TS,
            payload = MobileJson.parseToJsonElement(rawPayload),
        )

    /** A `modal_dismissed` envelope (#437) — three flat string fields `{modal_id, outcome, source}`. */
    private fun modalDismissedEnvelope(
        modalId: String,
        outcome: String,
        source: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "modal_dismissed",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"modal_id":"$modalId","outcome":"$outcome","source":"$source"}""",
                ),
        )

    private fun turnStateEnvelope(
        conversationId: String,
        state: String,
        id: Long = 1L,
        eventId: Long? = null,
    ): Envelope =
        Envelope(
            id = id,
            type = "turn_state",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","state":"$state"}"""),
            eventId = eventId,
        )

    private fun assistantDeltaEnvelope(
        conversationId: String,
        turnId: String,
        seq: Int,
        text: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "assistant_delta",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","turn_id":"$turnId","seq":$seq,"text":"$text"}""",
                ),
        )

    private fun toolUseEnvelope(
        conversationId: String,
        turnId: String,
        toolUseId: String,
        name: String,
        inputSummary: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "tool_use",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","turn_id":"$turnId","tool_use_id":"$toolUseId","name":"$name","input_summary":"$inputSummary"}""",
                ),
        )

    private fun toolResultEnvelope(
        conversationId: String,
        turnId: String,
        toolUseId: String,
        isError: Boolean,
        resultSummary: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "tool_result",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","turn_id":"$turnId","tool_use_id":"$toolUseId","is_error":$isError,"result_summary":"$resultSummary"}""",
                ),
        )

    private fun toolDeniedEnvelope(
        conversationId: String,
        toolUseId: String,
        message: String = "denied",
        reasonType: String = "",
        truncated: String = "null",
        dropped: String = "null",
    ): Envelope =
        Envelope(
            id = 1L,
            type = "tool_denied",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","turn_id":"t1","tool_use_id":"$toolUseId","tool_name":"Bash",""" +
                        """"decision_reason_type":"$reasonType","decision_reason":"","message":"$message",""" +
                        """"truncated_fields":$truncated,"dropped_fields":$dropped}""",
                ),
        )

    private fun turnEndEnvelope(
        conversationId: String,
        turnId: String,
        stopReason: String,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "turn_end",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","turn_id":"$turnId","stop_reason":"$stopReason"}""",
                ),
        )

    /**
     * A `workspace_updated` envelope (#721) — the `{path, label}` push. [inReplyTo] defaults to `null`
     * (the unsolicited shape); pass one to exercise the correlated-reply shape, which applies the same.
     */
    private fun workspaceUpdatedEnvelope(
        path: String,
        label: String?,
        inReplyTo: Long? = null,
        id: Long = 1L,
    ): Envelope {
        val labelJson = if (label == null) "null" else "\"$label\""
        return workspaceUpdatedRawEnvelope("""{"path":"$path","label":$labelJson}""", inReplyTo, id)
    }

    /** Raw-payload variant so a test can supply a malformed `workspace_updated` body. */
    private fun workspaceUpdatedRawEnvelope(
        rawPayload: String,
        inReplyTo: Long? = null,
        id: Long = 1L,
    ): Envelope =
        Envelope(
            id = id,
            type = "workspace_updated",
            ts = TS,
            payload = MobileJson.parseToJsonElement(rawPayload),
            inReplyTo = inReplyTo,
        )

    /** A correlated `history_page` reply carrying [raw] verbatim, so a test can push a malformed one. */
    private fun historyPageEnvelope(
        inReplyTo: Long,
        raw: String,
        id: Long = 98L,
    ): Envelope =
        Envelope(
            id = id,
            type = "history_page",
            ts = TS,
            payload = MobileJson.parseToJsonElement(raw),
            inReplyTo = inReplyTo,
        )

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

        /** Togglable to simulate a not-`Open` session (`send` returns `false`, no frame sent). */
        var sendResult = true

        /** Togglable to simulate a transport that throws rather than reporting a failed send (#792). */
        var throwOnSend = false

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            if (throwOnSend) throw IllegalStateException("transport down")
            return sendResult
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }

        /** Closes the inbound stream so `receiveAsFlow()` completes — simulates session teardown (#488). */
        fun close() {
            inboundChannel.close()
        }
    }

    private companion object {
        const val TS = "2026-05-31T00:00:00Z"

        /** A #810 `tool_use` payload with input fields and a parent; `CONV` is the conversation placeholder. */
        const val TOOL_USE_810 =
            """{"conversation_id":"CONV","turn_id":"t1","tool_use_id":"tu-810","parent_tool_use_id":"agent-1",""" +
                """"name":"Edit","input_summary":"a.kt","input":{"file_path":"../src/a.kt","old_string":"x\ny…"}}"""

        /** The `tool_result` completing [TOOL_USE_810], carrying the same parent. */
        const val TOOL_RESULT_810 =
            """{"conversation_id":"CONV","turn_id":"t1","tool_use_id":"tu-810","parent_tool_use_id":"agent-1",""" +
                """"is_error":false,"result_summary":"ok"}"""

        /** A plain row: auto mode accepted, two effort levels, nothing cut (#791). */
        val ROW_SONNET = ModelRowFixture("claude-sonnet-5", "sonnet", "Sonnet 5")

        /** A bracketed variant — `value` is an alias, never a parseable version (#791). */
        val ROW_OPUS = ModelRowFixture("claude-opus-5", "opus[1m]", "Opus 5", listOf("high"), supportsAutoMode = false)

        /**
         * The `default` alias: exposes NO effort control (`[]`, a positive statement rather than a cue
         * to substitute the five `Effort` entries), refuses auto mode, and reports its own cut.
         */
        val ROW_DEFAULT =
            ModelRowFixture(
                "claude-sonnet-5",
                "default",
                "Default",
                effortLevels = emptyList(),
                supportsAutoMode = false,
                truncatedFields = listOf("display_name"),
            )

        /** An opaque daemon-minted history cursor (#623) — echoed back verbatim, never parsed. */
        const val CURSOR = "MS4zZjhiMWMwNC05ZDI3LTRlNWEtYjZjMS0yZTlmNzBkOGE0MTMuNy40MDk2"

        /** The usage-limit expiry's fixed "now" (#802), in unix seconds — 2026-05-31, matching [TS]. */
        const val FIXED_NOW = 1_780_185_600L

        /** A `resets_at` comfortably after [FIXED_NOW], so a reading carrying it is readable. */
        const val FUTURE_RESET = FIXED_NOW + 3_600L

        /**
         * A `resets_at` in year 40000 (#802) — representable on the wire, rejected nowhere, and **past
         * `Int32`**, so it doubles as the Long-not-Int regression guard on [UsageLimitReading.resetsAt].
         */
        const val YEAR_40000_RESET = 1_200_000_000_000L

        /** The terminal shape of a history walk: no entries, empty cursor, `at_start` true. */
        const val EMPTY_TERMINAL_PAGE = """{"entries":[],"cursor":"","at_start":true}"""

        /** A populated `session_settings` reply (#590) — the protocol document's own example. */
        const val POPULATED_SETTINGS =
            """{"session_id":"sess-a","model":"opus","effort":"high","effective_effort":"medium",""" +
                """"yolo":false,"permission_mode":"default","used_tokens":12480,"window_tokens":200000}"""

        /** A second populated reply, distinguishable from [POPULATED_SETTINGS] by every field that matters. */
        const val REPLACEMENT_SETTINGS =
            """{"session_id":"sess-b","model":"sonnet","effort":"low","effective_effort":null,""" +
                """"yolo":true,"permission_mode":"bypassPermissions","used_tokens":7,"window_tokens":200000}"""

        /**
         * #721 workspace fixture: `a1` and `a2` SHARE the `/w/alpha` workspace (both unlabelled), `b1`
         * sits in an already-labelled `/w/beta`. Sorted desc by last_used_at ⇒ a1, a2, b1.
         */
        fun workspaceFixture(alphaLabel: String?): String {
            val alphaJson = if (alphaLabel == null) "null" else "\"$alphaLabel\""
            return """
                {"conversations":[
                  {"id":"a1","name":"Alpha one","is_promoted":true,"cwd":"/w/alpha","last_message_ts":"2026-05-08T12:00:00Z","last_used_at":"2026-05-08T12:00:00Z","workspace_label":$alphaJson},
                  {"id":"a2","name":"Alpha two","is_promoted":true,"cwd":"/w/alpha","last_message_ts":"2026-05-08T11:00:00Z","last_used_at":"2026-05-08T11:00:00Z","workspace_label":$alphaJson},
                  {"id":"b1","name":"Beta","is_promoted":true,"cwd":"/w/beta","last_message_ts":"2026-05-08T10:00:00Z","last_used_at":"2026-05-08T10:00:00Z","workspace_label":"Beta label"}
                ]}
                """.trimIndent()
        }

        val WORKSPACE_FIXTURE = workspaceFixture(alphaLabel = null)

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
