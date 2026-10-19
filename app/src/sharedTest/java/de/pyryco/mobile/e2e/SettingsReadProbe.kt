package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Test-only settings read contract, shared by native callers and controlled regressions. */
internal object SettingsReadProbe {
    private val activeRead = ThreadLocal<Read?>()

    fun wrap(delegate: ManagedSessionPump): ManagedSessionPump = ObservedPump(delegate)

    suspend fun read(
        repository: RemoteConversationRepository,
        conversationId: String,
        isCurrent: () -> Boolean,
        session: () -> SessionProvenance?,
    ): SessionSettings =
        coroutineScope {
            val authoritative = repository.usesAuthoritativeLiveState
            val expectedSession =
                if (authoritative) {
                    checkNotNull(session()) { "settings read has no reconciled session owner" }
                } else {
                    // Legacy list rows can carry an empty placeholder. Its request waiter owns the
                    // session read; any session_transition before completion abandons it below.
                    SessionProvenance()
                }
            val read =
                Read(
                    conversationId,
                    authoritative,
                    expectedSession,
                    isCurrent = { isCurrent() && (!authoritative || session() == expectedSession) },
                    admitted = { reading -> repository.observeSessionSettings(conversationId).first() == reading },
                )
            val observed = MutableStateFlow<SessionSettings?>(null)
            withContext(activeRead.asContextElement(read)) {
                // Legacy collection sends its own request on this context. Authoritative collection
                // only observes, so register that subscription before explicitly requesting a read.
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        repository.observeSessionSettings(conversationId).collect { observed.value = it }
                    }
                try {
                    read.checkOwner()
                    if (authoritative) repository.refreshSessionSettings(conversationId)
                    check(read.sent.await()) { "settings read send refused" }
                    val reply = read.reply.await()
                    val reading = reply.payload.toSessionSettings()
                    // Correlation establishes freshness. This cross-check leaves repository admission
                    // in charge and does not require a changed value or any particular applied field.
                    if (!authoritative) observed.first { it != null && !it.held && it == reading }
                    read.checkOwner()
                    reading
                } finally {
                    collector.cancel()
                    read.detach()
                }
            }
        }

    private class Read(
        val conversation: String,
        val authoritative: Boolean,
        val session: SessionProvenance,
        val isCurrent: () -> Boolean,
        val admitted: suspend (SessionSettings) -> Boolean,
    ) {
        val sent = CompletableDeferred<Boolean>()
        val reply = CompletableDeferred<Envelope>()

        @Volatile private var failure: IllegalStateException? = null
        private var pump: ObservedPump? = null
        private var requestId: Long? = null

        @Synchronized fun attach(
            owner: ObservedPump,
            request: Envelope,
        ) {
            if (requestId != null) {
                fail("settings read was superseded")
                return
            }
            pump = owner
            requestId = request.id
            owner.pending[request.id] = this
            validateOwner()
        }

        fun validateOwner() {
            if (!isCurrent()) fail("settings read owner changed")
        }

        fun checkOwner() {
            validateOwner()
            failure?.let { throw it }
        }

        fun fail(message: String) {
            val error = IllegalStateException(message)
            failure = error
            sent.completeExceptionally(error)
            reply.completeExceptionally(error)
        }

        suspend fun delivered(frame: Envelope) {
            validateOwner()
            val payload = frame.payload as? JsonObject
            if (!authoritative && frame.type == "session_transition" && payload?.get("conversation_id") == JsonPrimitive(conversation)) {
                fail("settings read session changed")
            }
            if (frame.inReplyTo != requestId || failure != null) return
            if (frame.type == "error") {
                fail("settings read returned an error")
                return
            }
            if (frame.type != "session_settings") return
            if (payload?.get("conversation_id")?.let { it != JsonPrimitive(conversation) } == true) return
            if (authoritative &&
                (
                    frame.conversationId != JsonPrimitive(conversation) ||
                        frame.sessionProvenance != session ||
                        frame.sessionStateCleared != JsonPrimitive(false) ||
                        frame.eventId != null ||
                        frame.historyEntryId != null
                )
            ) {
                return
            }
            val reading =
                try {
                    frame.payload.toSessionSettings()
                } catch (_: IllegalArgumentException) {
                    fail("settings read returned malformed settings")
                    return
                }
            if (authoritative && reading.sessionId.isNotEmpty() && JsonPrimitive(reading.sessionId) != session.raw) return
            // The authoritative observation is a StateFlow projection: its first value is
            // synchronous and sends nothing. Read it in the inbound turn, before a later frame
            // can overwrite this reply. Legacy's cold request completes its existing collector.
            if (authoritative && !admitted(reading)) {
                fail("settings reply was not admitted by the repository")
                return
            }
            reply.complete(frame)
        }

        @Synchronized fun detach() {
            requestId?.let { pump?.pending?.remove(it, this) }
        }
    }

    private class ObservedPump(
        private val delegate: ManagedSessionPump,
    ) : ManagedSessionPump by delegate {
        val pending = ConcurrentHashMap<Long, Read>()
        override val inbound =
            flow {
                try {
                    delegate.inbound.collect { frame ->
                        emit(frame)
                        // Only the repository collects inbound. The receipt follows its processing.
                        pending.values.forEach { it.delivered(frame) }
                    }
                } finally {
                    pending.values.forEach { it.fail("settings read connection ended") }
                }
            }

        override fun send(envelope: Envelope): Boolean {
            val read =
                activeRead.get()?.takeIf {
                    envelope.type == "request_session_settings" &&
                        (envelope.payload as? JsonObject)?.get("conversation_id") == JsonPrimitive(it.conversation)
                }
            read?.attach(this, envelope)
            return delegate.send(envelope).also { accepted -> read?.sent?.complete(accepted) }
        }

        override fun close() {
            pending.values.forEach { it.fail("settings read connection closed") }
            delegate.close()
        }
    }
}
