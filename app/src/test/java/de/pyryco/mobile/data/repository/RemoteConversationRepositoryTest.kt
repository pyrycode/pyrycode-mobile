package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
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

    // ---- Every other interface method is a stub naming its owning follow-up ----------------------

    @Test
    fun stubMethods_throwUnsupportedOperationNamingTheFollowUp() =
        runTest {
            val repo = RemoteConversationRepository(FakeSessionPump(), backgroundScope)

            // observeLastMessage (#329), observeMessages (#313), sendMessage (#346), and
            // createDiscussion (#347) are now all implemented; only the remaining mutation / no-wire
            // methods are still stubs. Suspend stubs throw when invoked.
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

    // ---- Helpers --------------------------------------------------------------------------------

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

        /** Togglable to simulate a not-`Open` session (`send` returns `false`, no frame sent). */
        var sendResult = true

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return sendResult
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
