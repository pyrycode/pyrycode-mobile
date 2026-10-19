package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.repository.ManagedSessionPump
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.WeakHashMap

/** One admitted family, including full producer text and every nonblank rendered fragment. */
internal class CompletedReplyObservation(
    val owner: ThreadEvidenceOwner,
    val session: String,
    val turn: String,
    val producerText: String,
    val records: List<ThreadEvidenceRecord>,
    val thread: ThreadEvidence,
    val host: String,
) {
    override fun toString(): String = "CompletedReplyObservation(redacted)"
}

/** One send, armed before the UI action. No wording or row-count inference participates. */
internal class OwnedReplyObservation(
    val host: String,
    val before: ThreadEvidence,
) {
    private var pump: Any? = null
    private var pumpCurrent: () -> Boolean = { true }
    private var messageId: String? = null
    private var request: Envelope? = null
    private var accepted = false
    private var echo: Envelope? = null
    private val priorTurns = before.records.mapNotNull { it.turn }.toMutableSet()
    private val rawSessions = mutableSetOf<JsonElement>()
    private var turn: String? = null
    private val deltas = mutableListOf<Pair<Int, String>>()
    private var end: Envelope? = null
    private var failed = false
    private var closed = false
    private var session: String? = null

    init {
        require(before.owner.host == null || before.owner.host == host) { "reply observation host mismatch" }
    }

    @Synchronized fun sending(
        connection: Any,
        request: Envelope,
        isCurrent: () -> Boolean = { true },
    ) {
        if (request.type != "send_message" || request.field("conversation_id") != before.owner.conversation) return
        check(messageId == null) { "reply observation saw more than one send" }
        pump = connection
        pumpCurrent = isCurrent
        this.request = request
        messageId = checkNotNull(request.field("message_id")) { "reply send has no identity" }
        check(
            before.records.none { it.message.role == Role.User && it.message.ordinaryId == messageId },
        ) { "reply send reused a loaded identity" }
    }

    @Synchronized fun sent(
        connection: Any,
        result: Boolean,
    ) {
        if (pump === connection) accepted = result
    }

    @Synchronized fun received(
        connection: Any,
        frame: Envelope,
    ) {
        if (pump !== connection || frame.field("conversation_id") != before.owner.conversation) return
        if (frame.conversationId != null && frame.conversationId != JsonPrimitive(before.owner.conversation)) {
            failed = true
            return
        }
        frame.sessionProvenance.raw?.let(rawSessions::add)
        if (frame.type == "session_transition") failed = true
        val frameTurn = frame.field("turn_id")
        if (echo == null) {
            frameTurn?.let(priorTurns::add)
            if (frame.type == "message" && frame.field("role") == "user" && frame.field("message_id") == messageId) echo = frame
            return
        }
        if (frame.type == "assistant_delta" &&
            frame.field("parent_tool_use_id").orEmpty().isEmpty() &&
            !frameTurn.isNullOrBlank() &&
            frameTurn !in priorTurns
        ) {
            if (turn == null) turn = frameTurn
            if (turn != frameTurn || end != null) {
                failed = true
                return
            }
            val seq = frame.field("seq")?.toIntOrNull()
            val text = frame.field("text")
            if (seq == null || text == null) failed = true else deltas += seq to text
        }
        if (frame.type == "turn_end" && frameTurn == turn) end = frame
    }

    @Synchronized fun ended(connection: Any) {
        if (pump === connection) closed = true
    }

    @Synchronized fun producerCompleted(): Boolean =
        !failed &&
            !closed &&
            accepted &&
            echo != null &&
            turn != null &&
            deltas.isNotEmpty() &&
            deltas.map { it.first } == deltas.indices.toList() &&
            deltas.joinToString("") { it.second }.isNotBlank() &&
            end?.field("stop_reason") == "end_turn" &&
            end?.field("outcome") == "success" &&
            end?.field("is_error") == "false" &&
            end?.field("terminal_reason") == "completed"

    /** A peer may prove production even when nothing arrived at the phone; it proves no later stage. */
    @Synchronized fun completedBy(frames: List<Envelope>): Boolean {
        val sentRequest = request ?: return false
        val peer = Any()
        val copy = OwnedReplyObservation(host, before)
        copy.sending(peer, sentRequest)
        copy.sent(peer, accepted)
        frames.forEach { copy.received(peer, it) }
        return copy.producerCompleted()
    }

    /** Recheck the registry's current repository both before admission and after sampling the screen. */
    @Synchronized fun checkCurrent(current: Any?) {
        check(current === before.owner.connection && !closed && pumpCurrent()) { "reply observation source changed or ended" }
    }

    @Synchronized fun admitted(
        evidence: ThreadEvidence,
        current: Any?,
    ): CompletedReplyObservation? {
        checkCurrent(current)
        if (!producerCompleted() || !evidence.admitted || !before.owner.matches(evidence.owner)) return null
        val user =
            evidence.records.singleOrNull {
                it.message.role == Role.User &&
                    if (it.fact == null) {
                        it.message.ordinaryId == messageId
                    } else {
                        val queued = (echo?.payload as? JsonObject)?.get("queued_msg_id")
                        queued != null &&
                            it.fact.kind == "user_message" &&
                            it.fact.status == "delivered" &&
                            (it.fact.content as? JsonObject)?.get("queued_msg_id") == queued
                    }
            } ?: return null
        val ownedSession = user.session?.takeIf { it.isNotBlank() } ?: return null
        if (session != null && session != ownedSession) return null
        if (rawSessions.any { it != JsonPrimitive(ownedSession) }) return null
        val ownedTurn = turn ?: return null
        if (user.turn != null && user.turn != ownedTurn) return null
        val family = evidence.settledReply(before.owner, ownedSession, ownedTurn) ?: return null
        if (family.any { it.agentId != null } || family.map { it.message.id }.distinct().size != family.size) return null
        val text = deltas.joinToString("") { it.second }
        if (family.joinToString("") { it.fullText } != text || family.any { it.message.isStreaming }) return null
        session = ownedSession
        return CompletedReplyObservation(
            evidence.owner,
            ownedSession,
            ownedTurn,
            text,
            family.filter { it.message.content.isNotBlank() },
            evidence,
            host,
        ).takeIf { it.records.isNotEmpty() }
    }

    override fun toString(): String = "OwnedReplyObservation(redacted)"
}

/** Passive phone-boundary tap; settings correlation and recording contracts remain in their own decorators. */
internal object OwnedReplyProbe {
    private val hosts = WeakHashMap<RelayTransport, String>()

    @Volatile private var active: OwnedReplyObservation? = null

    @Synchronized fun transport(
        host: String,
        transport: RelayTransport,
    ): RelayTransport {
        hosts[transport] = host
        return transport
    }

    @Synchronized fun wrap(
        transport: RelayTransport,
        delegate: ManagedSessionPump,
    ): ManagedSessionPump {
        val host = checkNotNull(hosts[transport]) { "reply probe transport has no host" }
        val connection = Any()
        return object : ManagedSessionPump by delegate {
            override val inbound =
                flow {
                    try {
                        delegate.inbound.collect { frame ->
                            active?.takeIf { it.host == host }?.received(connection, frame)
                            emit(frame)
                        }
                    } finally {
                        active?.takeIf { it.host == host }?.ended(connection)
                    }
                }

            override fun send(envelope: Envelope): Boolean {
                val observation = active?.takeIf { it.host == host }
                observation?.sending(connection, envelope) { delegate.state.value is PumpState.Open }
                return delegate.send(envelope).also {
                    if (envelope.type == "send_message") observation?.sent(connection, it)
                }
            }

            override fun close() {
                active?.takeIf { it.host == host }?.ended(connection)
                delegate.close()
            }
        }
    }

    fun <T> observing(
        observation: OwnedReplyObservation,
        block: () -> T,
    ): T {
        check(active == null) { "reply observation already armed" }
        active = observation
        return try {
            block()
        } finally {
            active = null
        }
    }
}

private fun Envelope.field(name: String): String? = ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.content
