package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OwnedReplyObservationTest {
    private val savedSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = savedSink
        assertFalse(logs.any { "normal reply" in it || "First suffix" in it })
    }

    private val repository = Any()
    private val pump = Any()

    @Test fun legacyPlaceholderSessionNeedsCorrelatedCurrentSessionEvidence() {
        val observation = observation()
        producer().forEach { observation.received(pump, it) }
        val blank = evidence(ThreadEvidenceKind.Legacy, rows().map { it.copy(sessionId = "") })
        assertTrue(observation.producerCompleted())
        assertNull(observation.admitted(blank, repository))
        val query = frame("request_session_settings").copy(id = 2)
        observation.sending(pump, query)
        val reply = Envelope(2, "session_settings", "", buildJsonObject { put("session_id", "session") }, inReplyTo = query.id)
        observation.received(pump, reply.copy(inReplyTo = 99))
        assertNull(observation.admitted(blank, repository))
        observation.received(pump, reply)
        assertEquals("session", checkNotNull(observation.admitted(blank, repository)).session)
        observation.received(pump, frame("session_transition"))
        assertNull(observation.admitted(blank, repository))
    }

    private fun authoritativeFrames(texts: List<String> = listOf("Ping! A normal reply.")): List<Envelope> {
        val facts =
            evidence(ThreadEvidenceKind.Authoritative, rows(texts)).records.map { checkNotNull(it.fact) }.map { fact ->
                val content = fact.content as JsonObject
                ThreadItemDto.decode(
                    JsonObject(
                        fact.raw + ("rev" to JsonPrimitive(fact.id)) +
                            if (fact.kind ==
                                "user_message"
                            ) {
                                mapOf("content" to JsonObject(content + ("message_id" to JsonPrimitive("sent"))))
                            } else {
                                emptyMap()
                            },
                    ),
                )
            }
        val end =
            ThreadItemDto.decode(
                buildJsonObject {
                    put("id", facts.size + 1)
                    put("rev", facts.size + 1)
                    put("order", facts.size + 1)
                    put("session", "session")
                    put("turn", "turn")
                    put("kind", "turn_end")
                    put("active", false)
                    put("status", "done")
                    put("shown", false)
                    put("summary", "")
                    put(
                        "content",
                        buildJsonObject {
                            put("stop_reason", "end_turn")
                            put("outcome", "success")
                            put("is_error", false)
                            put("terminal_reason", "completed")
                        },
                    )
                },
            )
        return (facts + end).map { fact ->
            Envelope(
                1,
                "thread_item_added",
                "",
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "epoch")
                    put("version", fact.rev)
                    put("item", fact.raw)
                },
            )
        }
    }

    @Test fun optionalTerminalReasonDoesNotReplaceMandatorySuccessfulCompletion() {
        for (kind in ThreadEvidenceKind.entries) {
            for (reason in listOf(
                null,
                JsonPrimitive(""),
                JsonPrimitive("completed"),
                JsonPrimitive("interrupted"),
                JsonPrimitive(false),
            )) {
                val target = observation(kind)
                val frames =
                    producer(kind = kind).map { frame ->
                        if (kind == ThreadEvidenceKind.Legacy && frame.type == "turn_end") {
                            val fields = frame.payload as JsonObject
                            frame.copy(
                                payload =
                                    JsonObject(
                                        (fields - "terminal_reason") + reason?.let { mapOf("terminal_reason" to it) }.orEmpty(),
                                    ),
                            )
                        } else if (kind == ThreadEvidenceKind.Authoritative && frame.type == "thread_item_added") {
                            replaceItem(frame) { item ->
                                if ((item["kind"] as? JsonPrimitive)?.content != "turn_end") {
                                    item
                                } else {
                                    val content = item["content"] as JsonObject
                                    val fields =
                                        JsonObject(
                                            (content - "terminal_reason") + reason?.let { mapOf("terminal_reason" to it) }.orEmpty(),
                                        )
                                    JsonObject(item + ("content" to fields))
                                }
                            }
                        } else {
                            frame
                        }
                    }
                frames.forEach { target.received(pump, it) }
                val succeeds = reason == null || reason == JsonPrimitive("") || reason == JsonPrimitive("completed")
                assertEquals("$kind terminal reason $reason", succeeds, target.producerCompleted())
            }
        }
    }

    @Test fun authoritativeWireFramesEstablishProductionWithoutLegacyEvents() {
        val observation = observation(ThreadEvidenceKind.Authoritative)
        authoritativeFrames().forEach { observation.received(pump, it) }
        assertTrue(observation.producerCompleted())
        assertNotNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository))
    }

    private fun query(
        id: Long = 400,
        epoch: String = "",
        version: Long = 0,
    ) = Envelope(
        id,
        "thread_catch_up",
        "",
        buildJsonObject {
            put("conversation_id", "chat")
            put("epoch", epoch)
            put("version", version)
        },
    )

    private fun queryBatch(
        request: Envelope,
        items: List<JsonObject>,
        version: Long = 3,
    ) = Envelope(
        500,
        "thread_items",
        "",
        buildJsonObject {
            put("conversation_id", "chat")
            put("epoch", "epoch")
            put("version", version)
            put("items", JsonArray(items))
            put("final", false)
        },
        inReplyTo = request.id,
    )

    private fun queryFinal(
        request: Envelope,
        version: Long = 3,
    ) = Envelope(
        501,
        "thread_items",
        "",
        buildJsonObject {
            put("conversation_id", "chat")
            put("epoch", "epoch")
            put("version", version)
            put("items", JsonArray(emptyList()))
            put("final", true)
            put("lower_order", 1)
            put("upper_order", version)
            put("continuation", 1)
            put("older_exists", false)
        },
        inReplyTo = request.id,
    )

    private fun queryFacts() = authoritativeFrames().map { (it.payload as JsonObject)["item"] as JsonObject }

    private fun OwnedReplyObservation.acceptQuery(
        request: Envelope,
        accepted: Boolean = true,
    ) {
        phoneSending(pump, request)
        phoneSent(pump, request, accepted)
    }

    private fun generationEvidence(generation: Long): ThreadEvidence {
        val held = evidence(ThreadEvidenceKind.Authoritative, rows())
        return authoritativeThreadEvidence(
            "host",
            "chat",
            repository,
            checkNotNull(held.sourceSnapshot).copy(generation = generation),
        )
    }

    @Test fun savedCheckpointProbeNeedsAcceptedOwnedSendAndMatchingCatchUpCertificate() {
        val saved =
            AuthoritativeThreadSnapshot(
                epoch = "epoch",
                checkpoint = 1,
                completedVersion = 1,
                items = listOf(ThreadItemDto.decode(queryFacts().first())),
            )
        for (accepted in listOf(false, true)) {
            AuthoritativeHistoryProbe.begin("host", "chat", saved)
            try {
                val request = query(epoch = "epoch", version = 1)
                AuthoritativeHistoryProbe.sending("other", pump, request) { true }
                AuthoritativeHistoryProbe.sent("other", pump, request, true)
                assertTrue(AuthoritativeHistoryProbe.requests().isEmpty())
                AuthoritativeHistoryProbe.sending("host", pump, request) { true }
                AuthoritativeHistoryProbe.sent("host", pump, request, accepted)
                val batch = queryBatch(request, queryFacts().drop(1))
                AuthoritativeHistoryProbe.received("host", Any(), batch)
                assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
                AuthoritativeHistoryProbe.received("host", pump, batch)
                assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
                val final = queryFinal(request)
                val fields = final.payload as JsonObject
                val catchUp =
                    final.copy(
                        payload =
                            JsonObject(
                                (fields - setOf("lower_order", "upper_order", "continuation", "older_exists")) +
                                    ("from_version" to JsonPrimitive(1)),
                            ),
                    )
                AuthoritativeHistoryProbe.received("host", pump, catchUp)
                assertEquals(if (accepted) 1 else 0, AuthoritativeHistoryProbe.requests().size)
                assertEquals(if (accepted) 1 else 0, AuthoritativeHistoryProbe.certificates().size)
                if (accepted) {
                    val proof = AuthoritativeHistoryProbe.certificates().single()
                    assertEquals(3, proof.snapshot.items.size)
                    assertEquals(3L, proof.snapshot.checkpoint)
                    assertTrue(AuthoritativeHistoryProbe.current())
                    AuthoritativeHistoryProbe.ended("host", pump)
                    assertFalse(AuthoritativeHistoryProbe.current())
                }
            } finally {
                AuthoritativeHistoryProbe.end()
            }
        }
    }

    @Test fun savedCheckpointResetReceiptNeedsAcceptedExactSourceAndFreshCertification() {
        val saved =
            AuthoritativeThreadSnapshot(
                epoch = "epoch",
                checkpoint = 1,
                completedVersion = 1,
                items = listOf(ThreadItemDto.decode(queryFacts().first())),
            )
        for (case in listOf("rejected", "other-source", "other-request", "other-conversation", "valid")) {
            AuthoritativeHistoryProbe.begin("host", "chat", saved)
            try {
                val request = query(epoch = "epoch", version = 1)
                AuthoritativeHistoryProbe.sending("host", pump, request) { true }
                AuthoritativeHistoryProbe.sent("host", pump, request, case != "rejected")
                val reset =
                    Envelope(
                        502,
                        "thread_reset",
                        "",
                        buildJsonObject {
                            put("conversation_id", if (case == "other-conversation") "other" else "chat")
                            put("epoch", "epoch")
                            put("version", 3)
                        },
                        inReplyTo = if (case == "other-request") 999 else request.id,
                    )
                AuthoritativeHistoryProbe.received("host", if (case == "other-source") Any() else pump, reset)
                assertEquals(case, if (case == "valid") 1 else 0, AuthoritativeHistoryProbe.resets().size)
                assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
                if (case == "valid") {
                    assertFalse(AuthoritativeHistoryProbe.current())
                    assertTrue(AuthoritativeHistoryProbe.resets().single().request === AuthoritativeHistoryProbe.requests().single())
                    val fresh = query(401, epoch = "epoch")
                    AuthoritativeHistoryProbe.sending("host", pump, fresh) { true }
                    AuthoritativeHistoryProbe.sent("host", pump, fresh, true)
                    AuthoritativeHistoryProbe.received("host", pump, queryBatch(fresh, queryFacts()))
                    assertFalse(AuthoritativeHistoryProbe.current())
                    AuthoritativeHistoryProbe.received("host", pump, queryFinal(fresh))
                    assertTrue(AuthoritativeHistoryProbe.current())
                    assertEquals(1, AuthoritativeHistoryProbe.certificates().size)
                    assertEquals(
                        1L,
                        AuthoritativeHistoryProbe
                            .certificates()
                            .single()
                            .snapshot.generation,
                    )
                }
            } finally {
                AuthoritativeHistoryProbe.end()
            }
        }
    }

    @Test fun queryDeliveredUserAndLiveReplyNeedTheOwnedQueryCertificate() {
        val target = observation(ThreadEvidenceKind.Authoritative)
        target.bindPhoneConnection(pump) { true }
        val request = query()
        target.acceptQuery(request)
        target.phoneReceived(pump, queryBatch(request, queryFacts().take(1), 1))
        assertFalse(target.producerCompleted())
        assertNull(target.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository))
        target.phoneReceived(pump, queryFinal(request, 1))
        authoritativeFrames().drop(1).forEach { target.received(pump, it) }
        assertTrue(target.producerCompleted())
        assertNotNull(target.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository))
    }

    @Test fun queryOnlyProductionRequiresAcceptedSendExactConnectionAndTerminal() {
        for (rejected in listOf(true, false)) {
            val target = observation(ThreadEvidenceKind.Authoritative)
            target.bindPhoneConnection(pump) { true }
            val request = query()
            target.acceptQuery(request, !rejected)
            target.phoneReceived(Any(), queryBatch(request, queryFacts()))
            target.phoneReceived(pump, queryBatch(request, queryFacts()).copy(inReplyTo = 999))
            assertFalse(target.producerCompleted())
            target.phoneReceived(pump, queryBatch(request, queryFacts()))
            assertFalse(target.producerCompleted())
            target.phoneReceived(pump, queryFinal(request))
            assertEquals(!rejected, target.producerCompleted())
            assertEquals(!rejected, target.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository) != null)
        }
    }

    @Test fun queryReplyBeforeSendReturnCannotPassARejectedSend() {
        for (accepted in listOf(false, true)) {
            val target = observation(ThreadEvidenceKind.Authoritative)
            target.bindPhoneConnection(pump) { true }
            val request = query()
            target.phoneSending(pump, request)
            target.phoneReceived(pump, queryBatch(request, queryFacts()))
            target.phoneReceived(pump, queryFinal(request))
            assertFalse(target.producerCompleted())
            target.phoneSent(pump, request, accepted)
            assertEquals(accepted, target.producerCompleted())
        }
    }

    @Test fun sameEpochResetNeedsFreshCertificationBeforeExactGenerationCanBeAdmitted() {
        for (peerProducer in listOf(false, true)) {
            val peer = Any()
            val target =
                if (peerProducer) {
                    OwnedReplyObservation("host", evidence(ThreadEvidenceKind.Authoritative, emptyList()), legacyProducer = true)
                        .also {
                            it.sending(peer, frame("send_message", "message_id" to "sent"))
                            it.sent(peer, true)
                            producer().forEach { frame -> it.received(peer, frame) }
                        }
                } else {
                    observation(ThreadEvidenceKind.Authoritative).also { observation ->
                        authoritativeFrames().forEach { observation.received(pump, it) }
                    }
                }
            target.bindPhoneConnection(pump) { true }
            val request = query(epoch = "epoch", version = 1)
            target.acceptQuery(request)
            target.phoneReceived(
                pump,
                Envelope(
                    502,
                    "thread_reset",
                    "",
                    buildJsonObject {
                        put("conversation_id", "chat")
                        put("epoch", "epoch")
                        put("version", 3)
                    },
                    inReplyTo = request.id,
                ),
            )
            assertNull(target.admitted(generationEvidence(3), repository))
            val fresh = query(401)
            target.acceptQuery(fresh)
            target.phoneReceived(pump, queryBatch(fresh, queryFacts()))
            assertNull(target.admitted(generationEvidence(3), repository))
            target.phoneReceived(pump, queryFinal(fresh))
            assertNull(target.admitted(generationEvidence(2), repository))
            assertNull(target.admitted(generationEvidence(99), repository))
            assertNotNull(target.admitted(generationEvidence(3), repository))
            target.phoneEnded(pump)
            assertNull(target.admitted(generationEvidence(3), repository))
        }
    }

    @Test fun rejectedQueryBatchCannotBeRehabilitatedByItsValidTerminal() {
        val target = observation(ThreadEvidenceKind.Authoritative)
        target.bindPhoneConnection(pump) { true }
        val request = query()
        target.acceptQuery(request)
        val facts = queryFacts()
        val beyondCheckpoint = JsonObject(facts[1] + ("rev" to JsonPrimitive(4)))
        target.phoneReceived(pump, queryBatch(request, facts + beyondCheckpoint))
        target.phoneReceived(pump, queryFinal(request))
        assertFalse(target.producerCompleted())
        assertNull(target.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository))
    }

    @Test fun differentEpochResetCannotCertifyAnOldSend() {
        val target = observation(ThreadEvidenceKind.Authoritative)
        target.bindPhoneConnection(pump) { true }
        authoritativeFrames().forEach { target.received(pump, it) }
        val request = query(epoch = "epoch", version = 1)
        target.acceptQuery(request)
        target.phoneReceived(
            pump,
            Envelope(
                502,
                "thread_reset",
                "",
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "other")
                    put("version", 3)
                },
                inReplyTo = request.id,
            ),
        )
        val fresh = query(401)
        target.acceptQuery(fresh)
        target.phoneReceived(pump, queryBatch(fresh, queryFacts()))
        target.phoneReceived(pump, queryFinal(fresh))
        assertFalse(target.producerCompleted())
        assertNull(target.admitted(generationEvidence(3), repository))
    }

    @Test fun foreignQueryRouteAndEpochCannotRequalifyThePhone() {
        for (wrongEpoch in listOf(false, true)) {
            val target = observation(ThreadEvidenceKind.Authoritative)
            target.bindPhoneConnection(pump) { true }
            val request = query()
            target.acceptQuery(request)
            val batch = queryBatch(request, queryFacts())
            val payload = batch.payload as JsonObject
            val changed =
                if (wrongEpoch) {
                    JsonObject(payload + ("epoch" to JsonPrimitive("other")))
                } else {
                    JsonObject(payload + ("conversation_id" to JsonPrimitive("other")))
                }
            target.phoneReceived(pump, batch.copy(payload = changed))
            target.phoneReceived(pump, queryFinal(request))
            assertFalse(target.producerCompleted())
            assertNull(target.admitted(evidence(ThreadEvidenceKind.Authoritative, rows()), repository))
        }
    }

    private fun replaceItem(
        frame: Envelope,
        change: (JsonObject) -> JsonObject,
    ): Envelope {
        val payload = frame.payload as JsonObject
        return frame.copy(payload = JsonObject(payload + ("item" to change(payload["item"] as JsonObject))))
    }

    @Test fun authoritativeProducerRejectsWrongSessionRoleChildAndCompletion() {
        val frames = authoritativeFrames()
        val bad =
            listOf(
                Triple(0, "session", JsonPrimitive("earlier")),
                Triple(1, "session", JsonPrimitive("earlier")),
                Triple(1, "kind", JsonPrimitive("notice")),
                Triple(1, "parent", JsonPrimitive(99)),
                Triple(1, "active", JsonPrimitive(true)),
                Triple(1, "status", JsonPrimitive("failed")),
                Triple(1, "status", JsonPrimitive("interrupted")),
                Triple(1, "turn", JsonPrimitive("earlier")),
            )
        for ((index, key, value) in bad) {
            val observation = observation(ThreadEvidenceKind.Authoritative)
            frames
                .mapIndexed { at, frame -> if (at == index) replaceItem(frame) { JsonObject(it + (key to value)) } else frame }
                .forEach { observation.received(pump, it) }
            assertFalse(observation.producerCompleted())
        }
        for ((key, value) in listOf(
            "outcome" to JsonPrimitive("error_max_turns"),
            "is_error" to JsonPrimitive(true),
            "terminal_reason" to JsonPrimitive("interrupted"),
        )) {
            val observation = observation(ThreadEvidenceKind.Authoritative)
            frames.dropLast(1).forEach { observation.received(pump, it) }
            observation.received(
                pump,
                replaceItem(frames.last()) {
                    JsonObject(it + ("content" to JsonObject((it["content"] as JsonObject) + (key to value))))
                },
            )
            assertFalse(observation.producerCompleted())
        }
    }

    @Test fun authoritativeAppendsAndPatchesRequireTheirOwnCompletionAndRepositoryRevision() {
        val texts = listOf("First suffix")
        val frames = authoritativeFrames(texts)
        val observation = observation(ThreadEvidenceKind.Authoritative)
        observation.received(pump, frames.first())
        observation.received(
            pump,
            replaceItem(frames[1]) {
                JsonObject(
                    it +
                        mapOf(
                            "active" to JsonPrimitive(true),
                            "status" to JsonPrimitive("running"),
                            "content" to buildJsonObject { put("text", "First") },
                        ),
                )
            },
        )
        observation.received(
            pump,
            Envelope(
                1,
                "thread_text_append",
                "",
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "epoch")
                    put("version", 3)
                    put("item_id", 2)
                    put("base_rev", 2)
                    put("rev", 3)
                    put("text", " suffix")
                },
            ),
        )
        assertFalse(observation.producerCompleted())
        observation.received(
            pump,
            Envelope(
                1,
                "thread_item_changed",
                "",
                buildJsonObject {
                    put("conversation_id", "chat")
                    put("epoch", "epoch")
                    put("version", 4)
                    put("item_id", 2)
                    put("base_rev", 3)
                    put("rev", 4)
                    put(
                        "changes",
                        buildJsonObject {
                            put("active", false)
                            put("status", "done")
                        },
                    )
                },
            ),
        )
        val terminal =
            replaceItem(frames.last()) {
                JsonObject(
                    it + mapOf("id" to JsonPrimitive(5), "rev" to JsonPrimitive(5), "order" to JsonPrimitive(5)),
                )
            }
        observation.received(pump, terminal.copy(payload = JsonObject((terminal.payload as JsonObject) + ("version" to JsonPrimitive(5)))))
        assertTrue(observation.producerCompleted())
        assertNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, emptyList()), repository))
        assertNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, rows(texts)), repository))
        val facts =
            evidence(ThreadEvidenceKind.Authoritative, rows(texts)).records.map { checkNotNull(it.fact) }.map {
                if (it.kind == "assistant_message") ThreadItemDto.decode(JsonObject(it.raw + ("rev" to JsonPrimitive(4)))) else it
            }
        val current =
            authoritativeThreadEvidence(
                "host",
                "chat",
                repository,
                AuthoritativeThreadSnapshot(epoch = "epoch", generation = 2, items = facts),
            )
        assertNotNull(observation.admitted(current, repository))
    }

    private fun frame(
        type: String,
        vararg fields: Pair<String, String>,
    ) = Envelope(
        1,
        type,
        "2026-10-19T00:00:00Z",
        buildJsonObject {
            put("conversation_id", "chat")
            fields.forEach { (key, value) ->
                when (key) {
                    "seq", "queued_msg_id" -> put(key, value.toLong())
                    "is_error" -> put(key, value.toBooleanStrict())
                    else -> put(key, value)
                }
            }
        },
    )

    private fun observation(kind: ThreadEvidenceKind = ThreadEvidenceKind.Legacy): OwnedReplyObservation {
        val before = evidence(kind, emptyList())
        return OwnedReplyObservation("host", before).also {
            it.sending(pump, frame("send_message", "message_id" to "sent"))
            it.sent(pump, true)
        }
    }

    private fun producer(
        texts: List<String> = listOf("Ping! A normal reply."),
        ending: Boolean = true,
        kind: ThreadEvidenceKind = ThreadEvidenceKind.Legacy,
    ): List<Envelope> =
        if (kind ==
            ThreadEvidenceKind.Authoritative
        ) {
            authoritativeFrames(texts).let { if (ending) it else it.dropLast(1) }
        } else {
            listOf(
                frame(
                    "message",
                    "role" to "user",
                    "message_id" to "sent",
                    "queued_msg_id" to "7",
                ),
            ) +
                texts.mapIndexed { seq, text -> frame("assistant_delta", "turn_id" to "turn", "seq" to "$seq", "text" to text) } +
                if (ending) {
                    listOf(
                        frame(
                            "turn_end",
                            "turn_id" to "turn",
                            "stop_reason" to "end_turn",
                            "outcome" to "success",
                            "is_error" to "false",
                            "terminal_reason" to "completed",
                        ),
                    )
                } else {
                    emptyList()
                }
        }

    private fun message(
        id: String,
        text: String,
        role: Role = Role.Assistant,
        seq: Int = 0,
    ) = Message(
        id,
        "session",
        role,
        text,
        Instant.fromEpochMilliseconds(0),
        false,
        segment = if (role == Role.Assistant) AssistantSegment("turn", listOf(SegmentDelta(seq, text.length))) else null,
    )

    private fun rows(texts: List<String> = listOf("Ping! A normal reply.")) =
        listOf(message("sent", "original input", Role.User)) +
            texts.mapIndexed { index, text -> message("reply-$index", text, seq = index) }

    private fun evidence(
        kind: ThreadEvidenceKind,
        messages: List<Message>,
        status: String = "done",
        shown: Boolean = true,
    ): ThreadEvidence =
        if (kind == ThreadEvidenceKind.Legacy) {
            legacyThreadEvidence("host", "chat", repository, messages.map { ThreadItem.MessageItem(it) })
        } else {
            authoritativeThreadEvidence(
                "host",
                "chat",
                repository,
                AuthoritativeThreadSnapshot(
                    epoch = "epoch",
                    generation = 2,
                    items =
                        messages.mapIndexed { index, message ->
                            ThreadItemDto.decode(
                                buildJsonObject {
                                    put("id", (index + 1).toLong())
                                    put("rev", index + 1)
                                    put("order", (index + 1).toLong())
                                    put(
                                        "kind",
                                        if (message.role ==
                                            Role.User
                                        ) {
                                            "user_message"
                                        } else if (message.role == Role.Assistant) {
                                            "assistant_message"
                                        } else {
                                            "tool_call"
                                        },
                                    )
                                    put("session", message.sessionId)
                                    message.segment?.turnId?.let { put("turn", it) }
                                    put("status", if (message.role == Role.User) "delivered" else status)
                                    put("active", message.isStreaming)
                                    put("shown", shown)
                                    put("summary", "fallback")
                                    if (message.parentToolUseId.isNotEmpty()) put("parent", 99)
                                    put(
                                        "content",
                                        buildJsonObject {
                                            put("text", message.content)
                                            if (message.role == Role.User) {
                                                put("queued_msg_id", 7)
                                                put("message_id", "sent")
                                            }
                                        },
                                    )
                                },
                            )
                        },
                ),
            )
        }

    @Test fun bothRoutesRetainVariedProducerTextAndEveryRenderedFragment() {
        for (kind in ThreadEvidenceKind.entries) {
            for (texts in listOf(
                listOf("Ping."),
                listOf("PING!"),
                listOf("Here is an ordinary answer."),
                listOf("First paragraph. ", "And another reply fragment!"),
            )) {
                val observation = observation(kind)
                producer(texts, kind = kind).forEach { observation.received(pump, it) }
                val admitted = checkNotNull(observation.admitted(evidence(kind, rows(texts)), repository))
                assertEquals(texts.joinToString(""), admitted.producerText)
                assertEquals("host", admitted.host)
                assertEquals(texts, admitted.records.map { it.fullText })
                assertEquals(
                    texts.size,
                    admitted.records
                        .map { it.message.id }
                        .distinct()
                        .size,
                )
            }
        }
    }

    @Test fun bothRoutesRejectWrongRoleSessionTurnAndChildRows() {
        for (kind in ThreadEvidenceKind.entries) {
            val good = rows()
            val reply = good.last()
            for (wrong in listOf(
                reply.copy(role = Role.User, segment = null),
                reply.copy(role = Role.Tool),
                reply.copy(sessionId = "earlier"),
                reply.copy(segment = AssistantSegment("earlier", reply.segment?.deltas.orEmpty())),
                reply.copy(parentToolUseId = "child"),
                reply.copy(isStreaming = true),
                reply.copy(content = " "),
            )) {
                val observation = observation(kind)
                producer(kind = kind).forEach { observation.received(pump, it) }
                assertNull("$kind rejects ${wrong.role}", observation.admitted(evidence(kind, listOf(good.first(), wrong)), repository))
            }
        }
    }

    @Test fun bothRoutesRequireTheExactDeliveredSendAndActualProducerText() {
        for (kind in ThreadEvidenceKind.entries) {
            for (messages in listOf(
                rows().drop(1),
                rows().dropLast(1),
                rows().map {
                    if (it.role ==
                        Role.Assistant
                    ) {
                        it.copy(content = "unrelated")
                    } else {
                        it
                    }
                },
            )) {
                val observation = observation(kind)
                producer(kind = kind).forEach { observation.received(pump, it) }
                assertTrue(observation.producerCompleted())
                assertNull(observation.admitted(evidence(kind, messages), repository))
            }
        }
    }

    @Test fun wrongEchoAndChildProducerCannotEstablishTheTarget() {
        for (frames in listOf(
            producer().map { if (it.type == "message") frame("message", "role" to "user", "message_id" to "earlier") else it },
            producer().map {
                if (it.type ==
                    "assistant_delta"
                ) {
                    frame(
                        "assistant_delta",
                        "turn_id" to "turn",
                        "seq" to "0",
                        "text" to "ping",
                        "parent_tool_use_id" to "child",
                    )
                } else {
                    it
                }
            },
            producer().filter { it.type != "assistant_delta" },
        )) {
            val observation = observation()
            frames.forEach { observation.received(pump, it) }
            assertFalse(observation.producerCompleted())
        }
    }

    @Test fun assistantToolAndSystemRowsCannotReplaceTheDeliveredUserBoundary() {
        for (role in listOf("assistant", "tool", "system")) {
            val observation = observation()
            producer()
                .map {
                    if (it.type == "message") frame("message", "role" to role, "message_id" to "sent", "queued_msg_id" to "7") else it
                }.forEach { observation.received(pump, it) }
            assertFalse(observation.producerCompleted())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnedReplyObservation("other-host", evidence(ThreadEvidenceKind.Legacy, emptyList()))
        }
    }

    @Test fun unsuccessfulInterruptedAndIncompleteTurnsCannotSettle() {
        val endings =
            listOf(
                frame(
                    "turn_end",
                    "turn_id" to "turn",
                    "stop_reason" to "cancelled",
                    "outcome" to "success",
                    "is_error" to "false",
                    "terminal_reason" to "completed",
                ),
                frame(
                    "turn_end",
                    "turn_id" to "turn",
                    "stop_reason" to "end_turn",
                    "outcome" to "error_max_turns",
                    "is_error" to "false",
                    "terminal_reason" to "completed",
                ),
                frame(
                    "turn_end",
                    "turn_id" to "turn",
                    "stop_reason" to "end_turn",
                    "outcome" to "success",
                    "is_error" to "true",
                    "terminal_reason" to "completed",
                ),
                frame(
                    "turn_end",
                    "turn_id" to "turn",
                    "stop_reason" to "end_turn",
                    "outcome" to "success",
                    "is_error" to "false",
                    "terminal_reason" to "interrupted",
                ),
                frame("turn_end", "turn_id" to "different"),
            )
        for (kind in ThreadEvidenceKind.entries) {
            for (ending in endings + listOf(null)) {
                val observation = observation(kind)
                (producer(ending = false) + listOfNotNull(ending)).forEach { observation.received(pump, it) }
                assertFalse(observation.producerCompleted())
                assertNull(observation.admitted(evidence(kind, rows()), repository))
            }
        }
    }

    @Test fun incompleteDeltaSequenceAndMissingTextCannotSettle() {
        for (frames in listOf(
            producer(listOf(" ")),
            producer().map {
                if (it.type ==
                    "assistant_delta"
                ) {
                    frame("assistant_delta", "turn_id" to "turn", "seq" to "1", "text" to "ping")
                } else {
                    it
                }
            },
        )) {
            val observation = observation()
            frames.forEach { observation.received(pump, it) }
            assertFalse(observation.producerCompleted())
        }
    }

    @Test fun authoritativeItemCompletionIsIndependentOfProducerCompletion() {
        for (status in listOf("failed", "interrupted", "running")) {
            val observation = observation(ThreadEvidenceKind.Authoritative)
            producer(kind = ThreadEvidenceKind.Authoritative).forEach { observation.received(pump, it) }
            assertTrue(observation.producerCompleted())
            assertNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, rows(), status), repository))
        }
        val observation = observation(ThreadEvidenceKind.Authoritative)
        producer(kind = ThreadEvidenceKind.Authoritative).forEach { observation.received(pump, it) }
        assertNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, rows(), shown = false), repository))
    }

    @Test fun refusedSendAndEarlierTurnCannotSettle() {
        val refused = observation()
        refused.sent(pump, false)
        producer().forEach { refused.received(pump, it) }
        assertFalse(refused.producerCompleted())
        val before = evidence(ThreadEvidenceKind.Legacy, rows().drop(1))
        val earlier = OwnedReplyObservation("host", before)
        earlier.sending(pump, frame("send_message", "message_id" to "sent"))
        earlier.sent(pump, true)
        producer().forEach { earlier.received(pump, it) }
        assertFalse(earlier.producerCompleted())
    }

    private fun partialCacheFixture(): Pair<CompletedReplyObservation, AuthoritativeThreadSnapshot> {
        val target = observation(ThreadEvidenceKind.Authoritative)
        authoritativeFrames().forEach { target.received(pump, it) }
        val held = generationEvidence(2)
        val captured = checkNotNull(target.admitted(held, repository))
        val original = checkNotNull(held.sourceSnapshot)
        val unfinished =
            ThreadItemDto.decode(
                JsonObject(
                    original.items.last().raw +
                        mapOf(
                            "id" to JsonPrimitive(4),
                            "rev" to JsonPrimitive(4),
                            "order" to JsonPrimitive(4),
                            "turn" to JsonPrimitive("held"),
                            "active" to JsonPrimitive(true),
                            "status" to JsonPrimitive("running"),
                        ),
                ),
            )
        return captured to
            original.copy(
                generation = 0,
                checkpoint = 3,
                completedVersion = 4,
                repairNeeded = true,
                items = original.items + unfinished,
            )
    }

    @Test fun certifiedCachedFamilySurvivesUnrelatedUncertifiedWorkWithoutGrantingLiveAdmission() {
        val (captured, snapshot) = partialCacheFixture()
        val cached = authoritativeThreadEvidence("host", "chat", Any(), snapshot)
        assertFalse(cached.admitted)
        assertNull(cached.retainedReply(captured, "host"))
        assertTrue(cached.retainsCachedReply(captured, "host"))
        assertFalse(cached.admitted)
        assertNull(cached.retainedReply(captured, "host"))
    }

    @Test fun cachedFamilyStillRequiresExactCertifiedFactsAndSource() {
        val (captured, snapshot) = partialCacheFixture()
        val reply = snapshot.items.single { it.id == 2L }
        val alternatives =
            listOf(
                reply.raw + ("rev" to JsonPrimitive(4)),
                reply.raw + ("session" to JsonPrimitive("other")),
                reply.raw + ("turn" to JsonPrimitive("other")),
                reply.raw + ("parent" to JsonPrimitive(99)),
                reply.raw + ("shown" to JsonPrimitive(false)),
                reply.raw + ("active" to JsonPrimitive(true)),
                reply.raw + ("content" to buildJsonObject { put("text", "different") }),
            )
        val invalid =
            alternatives.map { fields ->
                snapshot.copy(items = snapshot.items.map { if (it.id == reply.id) ThreadItemDto.decode(JsonObject(fields)) else it })
            } +
                listOf(
                    snapshot.copy(items = snapshot.items.filter { it.id != reply.id }),
                    snapshot.copy(items = snapshot.items + reply),
                    snapshot.copy(epoch = "other"),
                    snapshot.copy(resetNeeded = true),
                )
        for (wrong in invalid) {
            assertFalse(authoritativeThreadEvidence("host", "chat", Any(), wrong).retainsCachedReply(captured, "host"))
        }
        assertFalse(authoritativeThreadEvidence("other", "chat", Any(), snapshot).retainsCachedReply(captured, "host"))
        assertFalse(authoritativeThreadEvidence("host", "other", Any(), snapshot).retainsCachedReply(captured, "host"))
    }

    @Test fun missedReplyAbsenceInspectsRawHiddenAndPartialFactsFromOneSavedSource() {
        val (_, snapshot) = partialCacheFixture()
        val produced = ProducedReplyEvidence("host", "chat", "missed-message", "missed", "session", "actual reply")
        assertTrue(authoritativeThreadEvidence("host", "chat", Any(), snapshot).cachedReplyAbsent(produced))
        for ((shown, active) in listOf(true to true, false to false, false to true)) {
            val fact =
                ThreadItemDto.decode(
                    JsonObject(
                        snapshot.items.last().raw +
                            mapOf(
                                "id" to JsonPrimitive(5),
                                "rev" to JsonPrimitive(5),
                                "order" to JsonPrimitive(5),
                                "turn" to JsonPrimitive("missed"),
                                "shown" to JsonPrimitive(shown),
                                "active" to JsonPrimitive(active),
                                "status" to JsonPrimitive(if (active) "running" else "done"),
                            ),
                    ),
                )
            val cached = authoritativeThreadEvidence("host", "chat", Any(), snapshot.copy(items = snapshot.items + fact))
            assertFalse(cached.cachedReplyAbsent(produced))
        }
    }

    @Test fun savedRecoveryFollowsOnlyTheActualCorrelatedReplacementEpochAndFreshWindow() {
        val saved =
            AuthoritativeThreadSnapshot(
                epoch = "epoch",
                checkpoint = 1,
                completedVersion = 1,
                items = listOf(ThreadItemDto.decode(queryFacts().first())),
            )
        AuthoritativeHistoryProbe.begin("host", "chat", saved)
        try {
            val request = query(epoch = "epoch", version = 1)
            AuthoritativeHistoryProbe.sending("host", pump, request) { true }
            AuthoritativeHistoryProbe.sent("host", pump, request, true)
            val reset =
                Envelope(
                    502,
                    "thread_reset",
                    "",
                    buildJsonObject {
                        put("conversation_id", "chat")
                        put("epoch", "replacement")
                        put("version", 3)
                    },
                    inReplyTo = request.id,
                )
            AuthoritativeHistoryProbe.received("host", pump, reset)
            assertEquals(1, AuthoritativeHistoryProbe.resets().size)
            assertFalse(AuthoritativeHistoryProbe.current())
            assertTrue(AuthoritativeHistoryProbe.certificates().isEmpty())
            val fresh = query(epoch = "replacement", version = 0).copy(id = 83)
            AuthoritativeHistoryProbe.sending("host", pump, fresh) { true }
            AuthoritativeHistoryProbe.sent("host", pump, fresh, true)

            fun replacement(frame: Envelope) =
                frame.copy(
                    payload =
                        JsonObject(
                            (frame.payload as JsonObject) + ("epoch" to JsonPrimitive("replacement")),
                        ),
                )
            AuthoritativeHistoryProbe.received("host", pump, replacement(queryBatch(fresh, queryFacts())))
            assertFalse(AuthoritativeHistoryProbe.current())
            AuthoritativeHistoryProbe.received("host", pump, replacement(queryFinal(fresh)))
            assertTrue(AuthoritativeHistoryProbe.current())
            assertEquals(
                "replacement",
                AuthoritativeHistoryProbe
                    .certificates()
                    .single()
                    .snapshot.epoch,
            )
            assertEquals(
                3L,
                AuthoritativeHistoryProbe
                    .certificates()
                    .single()
                    .snapshot.checkpoint,
            )
        } finally {
            AuthoritativeHistoryProbe.end()
        }
    }

    @Test fun restoredReplyUsesCanonicalFactsWhenRuntimeGenerationIsDiscarded() {
        val target = observation(ThreadEvidenceKind.Authoritative)
        authoritativeFrames().forEach { target.received(pump, it) }
        val live = generationEvidence(2)
        val captured = checkNotNull(target.admitted(live, repository))
        val restored = generationEvidence(0)
        assertTrue(live.records.map { it.message.id } != restored.records.map { it.message.id })
        assertNotNull(restored.retainedReply(captured, "host"))
        assertNull(ThreadEvidence(restored.owner.copy(epoch = "other"), restored.records).retainedReply(captured, "host"))
        assertNull(ThreadEvidence(restored.owner.copy(conversation = "other"), restored.records).retainedReply(captured, "host"))
    }

    @Test fun retiredHostConversationConnectionEpochAndGenerationCannotBeAdmitted() {
        for (kind in ThreadEvidenceKind.entries) {
            val observation = observation(kind)
            producer(kind = kind).forEach { observation.received(pump, it) }
            val held = evidence(kind, rows())
            for (owner in listOf(
                held.owner.copy(host = "other"),
                held.owner.copy(conversation = "other"),
                held.owner.copy(connection = Any()),
                held.owner.copy(epoch = "other"),
                held.owner.copy(generation = 99),
            )) {
                assertNull(observation.admitted(ThreadEvidence(owner, held.records), repository))
            }
            assertNull(observation.admitted(ThreadEvidence(held.owner, held.records, admitted = false), repository))
            assertNotNull(observation.admitted(held, repository))
            assertThrows(IllegalStateException::class.java) { observation.checkCurrent(Any()) }
            observation.ended(pump)
            assertThrows(IllegalStateException::class.java) { observation.checkCurrent(repository) }
        }
    }

    @Test fun rawWrongSessionAndRetiredPumpCannotComplete() {
        val observation = observation()
        producer().forEach { observation.received(Any(), it) }
        assertFalse(observation.producerCompleted())
        producer()
            .map {
                it.copy(
                    sessionProvenance = SessionProvenance(JsonPrimitive("earlier")),
                )
            }.forEach { observation.received(pump, it) }
        assertNull(observation.admitted(evidence(ThreadEvidenceKind.Legacy, rows()), repository))
    }

    @Test fun connectionClosureIsRecheckedEvenBeforeTheRepositoryHolderUpdates() {
        var open = true
        val observation = OwnedReplyObservation("host", evidence(ThreadEvidenceKind.Legacy, emptyList()))
        observation.sending(pump, frame("send_message", "message_id" to "sent")) { open }
        observation.sent(pump, true)
        producer().forEach { observation.received(pump, it) }
        assertNotNull(observation.admitted(evidence(ThreadEvidenceKind.Legacy, rows()), repository))
        open = false
        assertThrows(IllegalStateException::class.java) { observation.checkCurrent(repository) }
    }

    @Test fun deliveredAliasesRetainWireIdentityButCannotReuseAnEarlierSend() {
        val rows = rows().map { if (it.role == Role.User) it.copy(id = "local-alias", reconciliationId = "sent") else it }
        val observation = observation()
        producer().forEach { observation.received(pump, it) }
        assertNotNull(observation.admitted(evidence(ThreadEvidenceKind.Legacy, rows), repository))
        val reused = OwnedReplyObservation("host", evidence(ThreadEvidenceKind.Legacy, rows))
        assertThrows(IllegalStateException::class.java) { reused.sending(pump, frame("send_message", "message_id" to "sent")) }
    }

    @Test fun producerProofCannotSupplyRepositoryOrScreenEvidence() {
        val observation = observation()
        assertTrue(observation.completedBy(producer()))
        assertFalse(observation.producerCompleted())
        assertNull(observation.admitted(evidence(ThreadEvidenceKind.Legacy, rows()), repository))
        assertTrue(ownedReplyFailure(true, false, listOf(1), true).message.orEmpty().contains("phone repository"))
        assertTrue(ownedReplyFailure(false, true, listOf(1), true).message.orEmpty().contains("host:"))
        assertTrue(ownedReplyFailure(true, null, listOf(1), true).message.orEmpty().contains("unreadable"))
        assertTrue(ownedReplyFailure(true, true, listOf(0), true).message.orEmpty().contains("missing"))
        assertTrue(ownedReplyFailure(true, true, listOf(2), true).message.orEmpty().contains("duplicate"))
        assertTrue(ownedReplyFailure(true, true, listOf(1), false).message.orEmpty().contains("not displayed"))
        assertTrue(ownedReplyFailure(true, true, listOf(1), false, contentMatches = false).message.orEmpty().contains("mismatched content"))
        assertFalse(ownedReplyFailure(true, false, emptyList(), false).message.orEmpty().contains("normal reply"))
    }

    @Test fun retainedReplyRebindsOnlyTheSameCompleteFamilyOnTheCurrentOwner() {
        for (kind in ThreadEvidenceKind.entries) {
            val target = observation(kind)
            producer(listOf("Done. ", "Ordinary prose."), kind = kind).forEach { target.received(pump, it) }
            val held = evidence(kind, rows(listOf("Done. ", "Ordinary prose.")))
            val captured = checkNotNull(target.admitted(held, repository))
            val current = ThreadEvidence(held.owner.copy(connection = Any()), held.records)
            assertNotNull(current.retainedReply(captured, "host"))
            assertNull(current.retainedReply(captured, "wrong-host"))
            assertNull(ThreadEvidence(current.owner.copy(conversation = "other"), current.records).retainedReply(captured, "host"))
            assertNull(ThreadEvidence(current.owner.copy(epoch = "other"), current.records).retainedReply(captured, "host"))
            assertNull(ThreadEvidence(current.owner, current.records, admitted = false).retainedReply(captured, "host"))
            for (replacement in listOf(
                current.records.dropLast(1),
                current.records + current.records.last(),
                current.records.reversed(),
                current.records.dropLast(1) +
                    ThreadEvidenceRecord(
                        current.records.last().message,
                        "truncated",
                        "session",
                        "turn",
                        null,
                        null,
                        current.records.last().fact,
                    ),
            )) {
                assertNull(ThreadEvidence(current.owner, replacement).retainedReply(captured, "host"))
            }
        }
    }

    @Test fun queuedSendsCannotReplaceTheFirstObservedSend() {
        val target = observation()
        assertFalse(target.sending(pump, frame("send_message", "message_id" to "dropped")))
        producer().forEach { target.received(pump, it) }
        assertNotNull(target.admitted(evidence(ThreadEvidenceKind.Legacy, rows()), repository))
    }

    @Test fun laterIndependentTurnDoesNotInvalidateAnAlreadyCompletedTarget() {
        val target = observation()
        producer().forEach { target.received(pump, it) }
        target.received(pump, frame("assistant_delta", "turn_id" to "later", "seq" to "0", "text" to "unrelated"))
        assertNotNull(target.admitted(evidence(ThreadEvidenceKind.Legacy, rows()), repository))
    }

    @Test fun producerEvidenceSurvivesDisconnectButStillNeedsExactCurrentAdmission() {
        val target = observation()
        producer().forEach { target.received(pump, it) }
        val produced = checkNotNull(target.produced())
        target.ended(pump)
        val held = evidence(ThreadEvidenceKind.Legacy, rows())
        val current = ThreadEvidence(held.owner.copy(connection = Any()), held.records)
        assertNotNull(produced.admitted(current, "host"))
        assertNull(produced.admitted(current, "other"))
        assertNull(produced.admitted(ThreadEvidence(current.owner, emptyList()), "host"))
        assertNull(produced.admitted(ThreadEvidence(current.owner, current.records + current.records.last()), "host"))
        assertNull(produced.admitted(ThreadEvidence(current.owner, current.records, admitted = false), "host"))
    }

    @Test fun peerProducerBindsToAuthoritativeAdmittedIdentities() {
        val held = evidence(ThreadEvidenceKind.Authoritative, rows())
        val target = OwnedReplyObservation("host", ThreadEvidence(held.owner, emptyList()), legacyProducer = true)
        target.sending(pump, frame("send_message", "message_id" to "sent"))
        target.sent(pump, true)
        producer().forEach { target.received(pump, it) }
        assertNotNull(target.admitted(held, repository))
    }

    @Test fun passiveSessionBindingNeedsTheSameReadAndConnection() {
        val target = observation()
        producer().forEach { target.received(pump, it) }
        val held = evidence(ThreadEvidenceKind.Legacy, rows().map { it.copy(sessionId = "") })
        val phone = Any()
        val request = frame("request_session_settings").copy(id = 42)
        target.sessionSending(phone, request)
        val response = Envelope(99, "session_settings", "", buildJsonObject { put("session_id", "session") }, inReplyTo = 42)
        target.sessionReceived(Any(), response)
        assertNull(target.admitted(held, repository))
        target.sessionReceived(phone, response)
        assertNotNull(target.admitted(held, repository))
    }

    @Test fun legacyHistoryMayResolveItsPlaceholderToTheCapturedSessionOnly() {
        val target = OwnedReplyObservation("host", evidence(ThreadEvidenceKind.Legacy, emptyList()), knownSession = "session")
        target.sending(pump, frame("send_message", "message_id" to "sent"))
        target.sent(pump, true)
        producer().forEach { target.received(pump, it) }
        val initial = evidence(ThreadEvidenceKind.Legacy, rows().map { it.copy(sessionId = "") })
        val captured = checkNotNull(target.admitted(initial, repository))
        assertNotNull(evidence(ThreadEvidenceKind.Legacy, rows()).retainedReply(captured, "host"))
        val wrongSession = evidence(ThreadEvidenceKind.Legacy, rows().map { it.copy(sessionId = "other") })
        assertNull(wrongSession.retainedReply(captured, "host"))
    }
}
