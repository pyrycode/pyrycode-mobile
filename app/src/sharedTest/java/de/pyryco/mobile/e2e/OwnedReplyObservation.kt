package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
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
    private val legacyProducer: Boolean = false,
    private val knownSession: String? = null,
    producerSnapshot: AuthoritativeThreadSnapshot? = null,
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
    private val sessionReads = mutableSetOf<Long>()
    private var readSession: String? = null
    private val passiveSessionReads = mutableMapOf<Pair<Any, Long>, Unit>()

    @Synchronized fun sessionSending(
        connection: Any,
        request: Envelope,
    ) {
        if (request.type == "request_session_settings" && request.field("conversation_id") == before.owner.conversation) {
            passiveSessionReads[connection to request.id] = Unit
        }
    }

    @Synchronized fun sessionReceived(
        connection: Any,
        frame: Envelope,
    ) {
        val replyId = frame.inReplyTo ?: return
        if (frame.type != "session_settings" || passiveSessionReads.remove(connection to replyId) == null) return
        val id = (frame.payload as? JsonObject)?.get("session_id") as? JsonPrimitive ?: return
        if (!id.isString ||
            id.content.isBlank() ||
            frame.sessionStateCleared != JsonPrimitive(false) ||
            frame.conversationId?.let { it != JsonPrimitive(before.owner.conversation) } == true ||
            frame.sessionProvenance.raw?.let { it != id } == true ||
            readSession?.let { it != id.content } == true
        ) {
            failed = true
        } else {
            readSession = id.content
        }
    }

    private val threadStore =
        if (before.owner.kind == ThreadEvidenceKind.Authoritative) {
            AuthoritativeThreadStore().also { store ->
                (producerSnapshot ?: before.sourceSnapshot)?.let { store.restore(host, before.owner.conversation, it, 0) }
                if (store.current(host, before.owner.conversation).epoch == null) {
                    val facts = before.records.mapNotNull { it.fact }
                    store.applyBatch(
                        host,
                        before.owner.conversation,
                        checkNotNull(before.owner.epoch),
                        facts.maxOfOrNull { it.rev } ?: 0,
                        facts,
                        0,
                    )
                }
            }
        } else {
            null
        }
    private val threadRoute = threadStore?.let { AuthoritativeThreadRoute(host, it) }
    private val producerStore = threadStore.takeUnless { legacyProducer }
    private val producerRoute = threadRoute.takeUnless { legacyProducer }
    private val queryWitness =
        threadStore?.let { OwnedThreadQueryObservation(host, before.owner, it, checkNotNull(threadRoute)) }

    @Synchronized fun bindPhoneConnection(
        connection: Any,
        isCurrent: () -> Boolean,
    ) {
        queryWitness?.bind(connection, isCurrent)
    }

    @Synchronized fun phoneSending(
        connection: Any,
        request: Envelope,
    ) {
        queryWitness?.sending(connection, request)
    }

    @Synchronized fun phoneSent(
        connection: Any,
        request: Envelope,
        accepted: Boolean,
    ) {
        queryWitness?.sent(connection, request, accepted)
    }

    @Synchronized fun phoneReceived(
        connection: Any,
        frame: Envelope,
    ) {
        queryWitness?.received(connection, frame)
    }

    @Synchronized fun phoneEnded(connection: Any) {
        queryWitness?.close(connection)
    }

    init {
        require(before.owner.host == null || before.owner.host == host) { "reply observation host mismatch" }
    }

    @Synchronized fun sending(
        connection: Any,
        request: Envelope,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        if (request.type == "request_session_settings" &&
            request.field("conversation_id") == before.owner.conversation &&
            pump === connection &&
            messageId != null
        ) {
            sessionReads += request.id
            return false
        }
        if (request.type != "send_message" || request.field("conversation_id") != before.owner.conversation) return false
        // Queue scenarios send and drop other messages while retaining the first accepted target.
        if (messageId != null) return false
        pump = connection
        pumpCurrent = isCurrent
        this.request = request
        messageId = checkNotNull(request.field("message_id")) { "reply send has no identity" }
        check(
            before.records.none {
                it.message.role == Role.User &&
                    if (it.fact == null) it.message.ordinaryId == messageId else it.fact.field("message_id") == messageId
            },
        ) { "reply send reused a loaded identity" }
        return true
    }

    @Synchronized fun sent(
        connection: Any,
        result: Boolean,
    ) {
        if (pump === connection) accepted = result
    }

    /** Wait for this exact current phone send before another client acts. No reply completion is required. */
    @Synchronized fun acceptedSendCurrent(current: Any?): Boolean =
        current === before.owner.connection &&
            !closed &&
            !failed &&
            pump != null &&
            pumpCurrent() &&
            accepted &&
            messageId != null &&
            request?.field("message_id") == messageId &&
            request?.field("conversation_id") == before.owner.conversation

    @Synchronized fun received(
        connection: Any,
        frame: Envelope,
    ) {
        if (pump !== connection) return
        if (frame.type == "session_settings" && frame.inReplyTo in sessionReads) {
            val id = (frame.payload as? JsonObject)?.get("session_id") as? JsonPrimitive
            if (id == null || !id.isString || id.content.isBlank()) return
            val wrongConversation =
                frame.field("conversation_id")?.let { it != before.owner.conversation } == true ||
                    frame.conversationId?.let { it != JsonPrimitive(before.owner.conversation) } == true
            val wrongSession = frame.sessionProvenance.raw?.let { it != id } == true || readSession?.let { it != id.content } == true
            if (wrongConversation ||
                wrongSession ||
                frame.sessionStateCleared != JsonPrimitive(false)
            ) {
                failed = true
            } else {
                readSession = id.content
            }
            return
        }
        if (frame.field("conversation_id") != before.owner.conversation) return
        if (frame.conversationId != null && frame.conversationId != JsonPrimitive(before.owner.conversation)) {
            failed = true
            return
        }
        if (producerStore != null) {
            if (frame.type !in setOf("thread_item_added", "thread_item_changed", "thread_text_append")) return
            if (frame.field("epoch") != before.owner.epoch) {
                failed = true
                return
            }
            val id = frame.field("item_id")?.toLongOrNull()
            val known = producerStore.current(host, before.owner.conversation).items.any { it.id == id }
            if (frame.type != "thread_item_added" && !known) return
            producerRoute?.accept(frame)
            return
        }
        frame.sessionProvenance.raw?.let(rawSessions::add)
        if (frame.type == "session_transition") failed = true
        val frameTurn = frame.field("turn_id")
        if (end != null && frameTurn != null && frameTurn != turn) return
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

    @Synchronized fun producerCompleted(): Boolean {
        if (failed || closed || !accepted || !pumpCurrent()) return false
        if (producerStore != null) return queryWitness?.owner() != null && authoritativeReply() != null
        return echo != null &&
            turn != null &&
            deltas.isNotEmpty() &&
            deltas.map { it.first } == deltas.indices.toList() &&
            deltas.joinToString("") { it.second }.isNotBlank() &&
            (end?.payload as? JsonObject).isSuccessfulReplyEnd()
    }

    /** Structural diagnostics only: never include prompts, reply text or wire identities. */
    @Synchronized fun producerState(): String =
        "failed=$failed, closed=$closed, accepted=$accepted, current=${pumpCurrent()}, " +
            "echo=${echo != null}, turn=${turn != null}, deltas=${deltas.size}, " +
            "ordered=${deltas.map { it.first } == deltas.indices.toList()}, " +
            "terminal=${end != null}, endTurn=${end?.field("stop_reason") == "end_turn"}, " +
            "success=${end?.field("outcome") == "success"}, noError=${end?.field("is_error") == "false"}, " +
            "reasonEmpty=${end?.field("terminal_reason").isNullOrEmpty()}, " +
            "reasonCompleted=${end?.field("terminal_reason") == "completed"}, sessions=${rawSessions.size}"

    /** Freeze producer evidence before a deliberate disconnect; this alone proves no phone delivery. */
    @Synchronized fun produced(): ProducedReplyEvidence? {
        if (!producerCompleted()) return null
        val produced = if (producerStore != null) authoritativeReply() else null
        val tagged = (rawSessions.singleOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (rawSessions.size > 1) return null
        return ProducedReplyEvidence(
            host,
            before.owner.conversation,
            checkNotNull(messageId),
            checkNotNull(turn),
            session ?: readSession ?: tagged ?: knownSession,
            produced?.second?.joinToString("") { it.field("text").orEmpty() } ?: deltas.joinToString("") { it.second },
        )
    }

    private fun authoritativeReply(): Pair<ThreadItemDto, List<ThreadItemDto>>? {
        val snapshot = producerStore?.current(host, before.owner.conversation) ?: return null
        if (snapshot.repairNeeded || snapshot.resetNeeded || snapshot.epoch != before.owner.epoch) return null
        val user =
            snapshot.items.singleOrNull {
                it.kind == "user_message" &&
                    it.field("message_id") == messageId &&
                    it.status == "delivered" &&
                    !it.active &&
                    (it.order ?: 0) > 0
            } ?: return null
        val ownedSession = user.session?.takeIf { it.isNotBlank() } ?: return null
        if (snapshot.items.any { it.kind == "session_divider" && (it.order ?: 0) > (user.order ?: 0) }) return null
        val afterUser = snapshot.items.filter { it.session == ownedSession && (it.order ?: 0) > (user.order ?: 0) }
        val nextUser =
            afterUser
                .filter { it.kind == "user_message" && it.status == "delivered" }
                .minOfOrNull { it.order ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        val candidate =
            afterUser.sortedBy { it.order }.firstOrNull {
                it.kind == "assistant_message" &&
                    (it.parent ?: 0) == 0L &&
                    (it.order ?: 0) < nextUser &&
                    !it.turn.isNullOrBlank() &&
                    it.turn !in priorTurns
            } ?: return null
        if (turn != null && turn != candidate.turn || session != null && session != ownedSession) return null
        turn = candidate.turn
        session = ownedSession
        val family = afterUser.filter { it.kind == "assistant_message" && it.turn == turn && (it.parent ?: 0) == 0L }.sortedBy { it.order }
        if (family.any {
                it.active ||
                    !it.shown ||
                    it.status !in setOf("done", "finished") ||
                    it.field("text").isNullOrBlank() ||
                    (it.order ?: 0) >= nextUser
            }
        ) {
            return null
        }
        val terminal = afterUser.singleOrNull { it.kind == "turn_end" && it.turn == turn && (it.parent ?: 0) == 0L } ?: return null
        if (terminal.active ||
            terminal.status != "done" ||
            !(terminal.content as? JsonObject).isSuccessfulReplyEnd()
        ) {
            return null
        }
        return user to family
    }

    /** A peer may prove production even when nothing arrived at the phone; it proves no later stage. */
    @Synchronized fun completedBy(frames: List<Envelope>): Boolean {
        val sentRequest = request ?: return false
        val peer = Any()
        val legacy = before.owner.copy(kind = ThreadEvidenceKind.Legacy, epoch = null, generation = null)
        val copy = OwnedReplyObservation(host, ThreadEvidence(legacy, before.records))
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
        return admit(evidence)
    }

    /** Producer recovery treats a lost source as unavailable, keeping strict screen checks separate. */
    @Synchronized fun admittedIfCurrent(
        evidence: ThreadEvidence,
        current: Any?,
    ): CompletedReplyObservation? {
        if (current !== before.owner.connection || closed || !pumpCurrent()) return null
        return admit(evidence)
    }

    private fun admit(evidence: ThreadEvidence): CompletedReplyObservation? {
        val expected = if (queryWitness == null) before.owner else queryWitness.owner() ?: return null
        if (!producerCompleted() || !evidence.admitted || !expected.matches(evidence.owner)) return null
        val produced = if (producerStore != null) authoritativeReply() else null
        val user =
            evidence.records.singleOrNull {
                it.message.role == Role.User &&
                    if (it.fact == null) {
                        it.message.ordinaryId == messageId
                    } else {
                        (produced == null || it.fact.id == produced.first.id) &&
                            it.fact.kind == "user_message" &&
                            it.fact.status == "delivered" &&
                            it.fact.field("message_id") == messageId
                    }
            } ?: return null
        val recordSession = user.session ?: return null
        val tagged = (rawSessions.singleOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val ownedSession = recordSession.takeIf { it.isNotBlank() } ?: readSession ?: tagged ?: knownSession ?: return null
        if (readSession != null && readSession != ownedSession) return null
        if (session != null && session != ownedSession) return null
        if (rawSessions.any { it != JsonPrimitive(ownedSession) }) return null
        val ownedTurn = turn ?: return null
        if (user.turn != null && user.turn != ownedTurn) return null
        val family = evidence.settledReply(expected, recordSession, ownedTurn) ?: return null
        if (family.any { it.agentId != null } || family.map { it.message.id }.distinct().size != family.size) return null
        if (produced != null &&
            (
                family.map { it.fact?.id } != produced.second.map { it.id } ||
                    family.zip(produced.second).any { (record, fact) -> (record.fact?.rev ?: 0) < fact.rev }
            )
        ) {
            return null
        }
        val text = produced?.second?.joinToString("") { it.field("text").orEmpty() } ?: deltas.joinToString("") { it.second }
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
    @Volatile var readTrace: ((Any, Envelope, Boolean) -> Unit)? = null

    private fun traceRead(
        connection: Any,
        frame: Envelope,
        sending: Boolean,
    ) {
        try {
            readTrace?.invoke(connection, frame, sending)
        } catch (_: IllegalArgumentException) {
            // Malformed passive diagnostics cannot interrupt the repository's normal admission.
        }
    }

    private val hosts = WeakHashMap<RelayTransport, String>()

    @Volatile private var active: OwnedReplyObservation? = null
    private val sessionObservers = java.util.concurrent.CopyOnWriteArrayList<OwnedReplyObservation>()

    private class PhoneSource(
        val connection: Any,
        val open: () -> Boolean,
    )

    private val phoneSources = java.util.concurrent.ConcurrentHashMap<String, PhoneSource>()

    private fun bindPhone(observation: OwnedReplyObservation) {
        val source = phoneSources[observation.host] ?: return
        observation.bindPhoneConnection(source.connection) { phoneSources[observation.host] === source && source.open() }
    }

    fun <T> observingSessions(
        observation: OwnedReplyObservation,
        block: () -> T,
    ): T {
        bindPhone(observation)
        sessionObservers += observation
        return try {
            block()
        } finally {
            sessionObservers -= observation
        }
    }

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
        val source = PhoneSource(connection) { delegate.state.value is PumpState.Open }
        phoneSources[host] = source

        fun observers(): List<OwnedReplyObservation> =
            (sessionObservers.filter { it.host == host } + listOfNotNull(active?.takeIf { it.host == host })).distinct()
        val observed =
            object : ManagedSessionPump by delegate {
                override val inbound =
                    flow {
                        try {
                            delegate.inbound.collect { frame ->
                                traceRead(connection, frame, false)
                                ContextResetProbe.before(host, connection, frame)
                                AuthoritativeHistoryProbe.received(host, connection, frame)
                                observers().forEach { it.phoneReceived(connection, frame) }
                                sessionObservers.filter { it.host == host }.forEach { it.sessionReceived(connection, frame) }
                                active?.takeIf { it.host == host }?.received(connection, frame)
                                emit(frame)
                                ContextResetProbe.delivered(host, connection, frame)
                            }
                        } finally {
                            AuthoritativeHistoryProbe.ended(host, connection)
                            observers().forEach { it.phoneEnded(connection) }
                            phoneSources.remove(host, source)
                            active?.takeIf { it.host == host }?.ended(connection)
                        }
                    }

                override fun send(envelope: Envelope): Boolean {
                    traceRead(connection, envelope, true)
                    val contextReceipt = ContextResetProbe.sending(host, connection, envelope)
                    val observers = observers()
                    AuthoritativeHistoryProbe.sending(host, connection, envelope) { phoneSources[host] === source && source.open() }
                    observers.forEach { it.phoneSending(connection, envelope) }
                    sessionObservers.filter { it.host == host }.forEach { it.sessionSending(connection, envelope) }
                    val observation = active?.takeIf { it.host == host }
                    val targeted = observation?.sending(connection, envelope) { delegate.state.value is PumpState.Open } == true
                    return delegate.send(envelope).also { result ->
                        contextReceipt?.complete(result)
                        AuthoritativeHistoryProbe.sent(host, connection, envelope, result)
                        observers.forEach { it.phoneSent(connection, envelope, result) }
                        if (targeted) observation?.sent(connection, result)
                    }
                }

                override fun close() {
                    AuthoritativeHistoryProbe.ended(host, connection)
                    observers().forEach { it.phoneEnded(connection) }
                    phoneSources.remove(host, source)
                    active?.takeIf { it.host == host }?.ended(connection)
                    delegate.close()
                }
            }
        return ThreadQueryReadiness.wrap(host, observed)
    }

    fun <T> observing(
        observation: OwnedReplyObservation,
        block: () -> T,
    ): T {
        bindPhone(observation)
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

private fun ThreadItemDto.field(name: String): String? = ((content as? JsonObject)?.get(name) as? JsonPrimitive)?.content

// The daemon forwards terminal_reason verbatim; older/scripted producers leave it empty.
// The terminal boundary and all success fields are still mandatory, and explicit failure reasons fail.
internal fun JsonObject?.isSuccessfulReplyEnd(): Boolean {
    fun field(name: String) = (this?.get(name) as? JsonPrimitive)?.content
    val reason = this?.get("terminal_reason")
    return field("stop_reason") == "end_turn" &&
        field("outcome") == "success" &&
        field("is_error") == "false" &&
        (reason == null || reason is JsonPrimitive && reason.isString && reason.content in setOf("", "completed"))
}
