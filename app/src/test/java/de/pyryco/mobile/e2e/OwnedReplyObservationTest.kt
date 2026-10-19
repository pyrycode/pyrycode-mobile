package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedReplyObservationTest {
    private val repository = Any()
    private val pump = Any()

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
    ): List<Envelope> =
        listOf(frame("message", "role" to "user", "message_id" to "sent", "queued_msg_id" to "7")) +
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
                                    put("rev", 1)
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
                                    put("turn", message.segment?.turnId ?: "turn")
                                    put("status", if (message.role == Role.User) "delivered" else status)
                                    put("active", message.isStreaming)
                                    put("shown", shown)
                                    put("summary", "fallback")
                                    if (message.parentToolUseId.isNotEmpty()) put("parent", 99)
                                    put(
                                        "content",
                                        buildJsonObject {
                                            put("text", message.content)
                                            if (message.role == Role.User) put("queued_msg_id", 7)
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
                producer(texts).forEach { observation.received(pump, it) }
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
                producer().forEach { observation.received(pump, it) }
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
                producer().forEach { observation.received(pump, it) }
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
            producer().forEach { observation.received(pump, it) }
            assertTrue(observation.producerCompleted())
            assertNull(observation.admitted(evidence(ThreadEvidenceKind.Authoritative, rows(), status), repository))
        }
        val observation = observation(ThreadEvidenceKind.Authoritative)
        producer().forEach { observation.received(pump, it) }
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

    @Test fun retiredHostConversationConnectionEpochAndGenerationCannotBeAdmitted() {
        for (kind in ThreadEvidenceKind.entries) {
            val observation = observation(kind)
            producer().forEach { observation.received(pump, it) }
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
}
