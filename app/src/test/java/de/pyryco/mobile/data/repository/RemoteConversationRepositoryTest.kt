package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    cwd = DEFAULT_SCRATCH_CWD,
                    lastUsedAt = "2026-05-08T11:00:00Z",
                ),
            )
            runCurrent()

            create().getOrThrow()
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

    // Correlation hygiene: a conversation_updated matching no pending request (the unsolicited
    // broadcast shape) is a no-op; the collector survives and a subsequent real promote round-trips.
    @Test
    fun promote_uncorrelatedUpdatedReply_isNoOpAndCollectorSurvives() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            runCurrent()

            pump.push(conversationUpdatedEnvelope(inReplyTo = 999L, id = "ghost", name = "g", cwd = "/p"))
            runCurrent()

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

            val change = startChangeWorkspace(repo, "chan", "/home/me/requested")
            runCurrent()
            val sentId = pump.sent.single { it.type == "change_workspace" }.id
            // The daemon confines to $HOME and stores the resolved realpath, which it echoes back.
            pump.push(conversationUpdatedEnvelope(inReplyTo = sentId, id = "chan", name = "Channel", cwd = "/home/me/resolved"))
            runCurrent()
            change().getOrThrow()

            // Folded in place: still two entries; chan now shows the server's resolved cwd.
            assertEquals(listOf("chan", "disc"), all.last().map { it.id })
            assertEquals("/home/me/resolved", all.last().single { it.id == "chan" }.cwd)
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

    // ---- interrupt (#458): bare fire-and-forget control frame -------------------------------------

    // AC #1, wire contract: interrupt() emits exactly one bare `interrupt` frame whose payload is the
    // empty object `{}` (no conversation_id / no other keys). Fire-and-forget: no reply is awaited.
    @Test
    fun interrupt_sendsBareInterruptMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            repo.interrupt()

            val sent = pump.sent.single { it.type == "interrupt" }
            assertEquals(MobileJson.parseToJsonElement("{}"), sent.payload)
        }

    // AC #3: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang (no awaited reply).
    @Test
    fun interrupt_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val outcome = runCatching { repo.interrupt() }

            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        }

    // ---- startNewSession (#539): bare fire-and-forget control frame --------------------------------

    // AC #1, wire contract: startNewSession emits exactly one bare `new_session` frame whose payload is
    // the empty object `{}` (no conversation_id / no other keys). Fire-and-forget: no reply is awaited.
    // The returned placeholder Session carries the arg conversationId (identity fields are unassigned).
    @Test
    fun startNewSession_sendsBareNewSessionMatchingWireContract() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val session = repo.startNewSession("c-1", null)

            val sent = pump.sent.single { it.type == "new_session" }
            assertEquals(MobileJson.parseToJsonElement("{}"), sent.payload)
            assertEquals("c-1", session.conversationId)
        }

    // AC #2, #3: a not-Open session (pump.send returns false) fails fast with IllegalStateException and
    // does not hang (no awaited reply).
    @Test
    fun startNewSession_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope, deviceName = "Pixel-8")

            val outcome = runCatching { repo.startNewSession("c-1", null) }

            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        }

    // ---- dropQueuedMessage (#466): dequeue_message request → ack/error correlation ----------------

    // AC #1, #5: the sent dequeue_message payload matches the two-key wire contract
    // {conversation_id, queued_msg_id}; queued_msg_id is a JSON NUMBER (the uint64), not a quoted
    // string, and no extra keys are present.
    @Test
    fun dropQueuedMessage_sendsDequeueMessageMatchingWireContract() =
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

            // Resolve so the awaiting coroutine completes cleanly.
            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertTrue(drop().isSuccess)
        }

    // AC #2: an empty ack completes the drop successfully and writes NO local projection — the backlog
    // is owned by observeQueue (#460) and updated only by a later queue_state, never by this send.
    @Test
    fun dropQueuedMessage_onAck_succeedsWithoutMutatingProjection() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val queue = collectQueue(repo, "c-1")
            val messages = collectMessages(repo, "c-1")
            val lastMessage = collectLastMessage(repo, "c-1")
            runCurrent()

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            val sent = pump.sent.single { it.type == "dequeue_message" }
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            assertTrue(drop().isSuccess)
            // No projection write: each stream shows only its initial empty/null emission.
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
            assertEquals(listOf(emptyList<ThreadItem>()), messages)
            assertEquals(listOf<Message?>(null), lastMessage)
        }

    // AC #3: a correlated server error surfaces as RelayErrorException exposing code + retryable (a
    // stale / already-drained id surfaces generically here — no bespoke queue-error mapping).
    @Test
    fun dropQueuedMessage_onServerError_throwsRelayErrorExposingCode() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()
            val sent = pump.sent.single { it.type == "dequeue_message" }
            pump.push(errorEnvelope(sent.id, code = "queue.stale_id", retryable = false))
            runCurrent()

            val ex = drop().exceptionOrNull()
            assertTrue("expected RelayErrorException, got $ex", ex is RelayErrorException)
            assertEquals("queue.stale_id", (ex as RelayErrorException).code)
            assertFalse(ex.retryable)
        }

    // AC #3: an unknown conversation (server conversation.not_found) throws IllegalArgumentException,
    // consistent with sendMessage / requestScreenSnapshot.
    @Test
    fun dropQueuedMessage_onConversationNotFound_throwsIllegalArgument() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val drop = startDropQueuedMessage(repo, "ghost", 42L)
            runCurrent()
            val sent = pump.sent.single { it.type == "dequeue_message" }
            pump.push(errorEnvelope(sent.id, code = "conversation.not_found", message = "no such conversation"))
            runCurrent()

            assertTrue(drop().exceptionOrNull() is IllegalArgumentException)
        }

    // AC #4: a not-Open session (pump.send returns false) fails fast with IllegalStateException; no
    // reply ever arrives, yet the call has already completed exceptionally (it does not hang).
    @Test
    fun dropQueuedMessage_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang() =
        runTest {
            val pump = FakeSessionPump()
            pump.sendResult = false
            val repo = RemoteConversationRepository(pump, backgroundScope)

            val drop = startDropQueuedMessage(repo, "c-1", 42L)
            runCurrent()

            assertTrue(drop().exceptionOrNull() is IllegalStateException)
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

            pump.push(ackEnvelope(sent.id))
            runCurrent()
            assertTrue(drop().isSuccess)
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
                        Triple(1L, "a", "2026-05-31T00:00:01Z"),
                        Triple(2L, "b", "2026-05-31T00:00:02Z"),
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
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":7,"text":"x","ts":"$TS"}]}""",
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
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":"7","text":"x","ts":"$TS"}]}""",
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

            pump.push(queueStateEnvelope("c1", listOf(Triple(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z"))), queue.last())

            pump.push(
                queueStateEnvelope(
                    "c1",
                    listOf(
                        Triple(2L, "b", "2026-05-31T00:00:02Z"),
                        Triple(3L, "c", "2026-05-31T00:00:03Z"),
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

            pump.push(queueStateEnvelope("c1", listOf(Triple(1L, "a", "2026-05-31T00:00:01Z"))))
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
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":1,"ts":"$TS"}]}""",
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
                            """{"conversation_id":"c1","queued":[{"queued_msg_id":1,"text":"x","ts":"not-a-timestamp"}]}""",
                        ),
                ),
            )
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)

            pump.push(queueStateEnvelope("c1", listOf(Triple(9L, "ok", "2026-05-31T00:00:09Z"))))
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

            pump.push(queueStateEnvelope("c1", listOf(Triple(1L, "a", "2026-05-31T00:00:01Z"))))
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

            pump.push(queueStateEnvelope("c1", listOf(Triple(1L, "a", "2026-05-31T00:00:01Z"))))
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queue)
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
                     "default_option_id":"deny"}
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
                    ),
                ),
                events,
            )
            // Explicit order assertion (AC #5): array order is the canonical display order.
            val shown = events.single() as ModalEvent.Shown
            assertEquals(listOf("allow", "deny"), shown.options.map { it.id })
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
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.setSessionSettings(sessionId, model, effort, yolo) } }
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
    ): () -> Result<Unit> {
        var outcome: Result<Unit>? = null
        backgroundScope.launch { outcome = runCatching { repo.answerModal(modalId, optionId) } }
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
     * for its eventual [Result], mirroring [startCancelModal]. The not-Open path completes
     * synchronously, before any reply.
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

    /** A correlated `conversation_updated` reply carrying a bare conversation object (#348). */
    private fun conversationUpdatedEnvelope(
        inReplyTo: Long,
        id: String,
        cwd: String,
        isPromoted: Boolean = true,
        isArchived: Boolean = false,
        name: String? = null,
        lastUsedAt: String = "2026-05-08T10:00:00Z",
        envId: Long = 99L,
    ): Envelope {
        val nameJson = if (name == null) "null" else "\"$name\""
        return Envelope(
            id = envId,
            type = "conversation_updated",
            ts = TS,
            // is_archived is always present on the wire (pyrycode#881, no omitempty).
            payload =
                MobileJson.parseToJsonElement(
                    """{"id":"$id","name":$nameJson,"is_promoted":$isPromoted,"is_archived":$isArchived,"cwd":"$cwd","last_used_at":"$lastUsedAt"}""",
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

    private fun ackEnvelope(
        inReplyTo: Long,
        id: Long = 99L,
    ): Envelope = Envelope(id = id, type = "ack", ts = TS, payload = JsonObject(emptyMap()), inReplyTo = inReplyTo)

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

    /** Arrival-order shape of a mixed thread (#336): a message row → its id, a boundary → "boundary:<reason>". */
    private fun threadShape(thread: List<ThreadItem>): List<String> =
        thread.map {
            when (it) {
                is ThreadItem.MessageItem -> it.message.id
                is ThreadItem.SessionBoundary -> "boundary:${it.reason}"
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
     * A `queue_state` snapshot envelope `{conversation_id, queued:[{queued_msg_id, text, ts}]}` (#460).
     * Each item is a `(queued_msg_id, text, ts)` triple; `queued_msg_id` is emitted as a JSON **number**
     * (the wire uint64). An empty [items] emits `"queued":[]`.
     */
    private fun queueStateEnvelope(
        conversationId: String,
        items: List<Triple<Long, String, String>>,
        id: Long = 1L,
    ): Envelope {
        val queued = items.joinToString(",") { (msgId, text, ts) -> """{"queued_msg_id":$msgId,"text":"$text","ts":"$ts"}""" }
        return Envelope(
            id = id,
            type = "queue_state",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","queued":[$queued]}"""),
        )
    }

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

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
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
