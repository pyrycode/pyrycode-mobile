package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Test-only settings read contract, shared by native callers and controlled regressions. */
internal object SettingsReadProbe {
    private val activeRead = ThreadLocal<Read?>()

    fun wrap(delegate: ManagedSessionPump): ManagedSessionPump = ObservedPump(delegate)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun read(
        repository: RemoteConversationRepository,
        conversationId: String,
        isCurrent: () -> Boolean,
        session: () -> SessionProvenance?,
        onReceipt: (Long, Envelope) -> Unit = { _, _ -> },
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
            val observed = MutableStateFlow<SessionSettings?>(null)
            val read =
                Read(
                    conversationId,
                    authoritative,
                    expectedSession,
                    isCurrent = { isCurrent() && (!authoritative || session() == expectedSession) },
                    admitted = { reading -> repository.observeSessionSettings(conversationId).first() == reading },
                    resetObservation = { observed.value = null },
                )
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
                    val (receipt, reading) =
                        read.receipts
                            .filterNotNull()
                            .mapLatest { result ->
                                val receipt = result.getOrThrow()
                                check(receipt.sent.await()) { "settings read send refused" }
                                val settings =
                                    receipt.reply
                                        .await()
                                        .payload
                                        .toSessionSettings()
                                // A legacy revision cancels its cold waiter and issues another request.
                                // Follow that receipt rather than mixing its observation with the old one.
                                if (!authoritative) observed.first { it != null && !it.held && it == settings }
                                receipt to settings
                            }.first { (receipt, _) -> read.isCurrentReceipt(receipt) }
                    read.checkOwner()
                    onReceipt(receipt.requestId, receipt.reply.getCompleted())
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
        val resetObservation: () -> Unit,
    ) {
        val receipts = MutableStateFlow<Result<Receipt>?>(null)

        @Volatile private var failure: IllegalStateException? = null

        @Volatile private var receipt: Receipt? = null
        private var pump: ObservedPump? = null
        private var detached = false

        @Synchronized fun attach(
            owner: ObservedPump,
            request: Envelope,
        ): Receipt? {
            validateOwner()
            if (pump != null && pump !== owner) fail("settings read owner changed")
            if (failure != null || detached) return null
            // Same-owner invalidation is normal. Retire only its previous request, and reset the
            // observation before send so an immediate new reply is retained without stale equality.
            val previous = receipt
            resetObservation()
            return Receipt(request.id).also {
                pump = owner
                receipt = it
                owner.pending[request.id] = this
                // Keep ownership/termination observation registered across the receipt swap.
                previous?.let { retired -> owner.pending.remove(retired.requestId, this) }
                receipts.value = Result.success(it)
            }
        }

        fun isCurrentReceipt(candidate: Receipt): Boolean = receipt === candidate

        fun validateOwner() {
            if (!isCurrent()) fail("settings read owner changed")
        }

        fun checkOwner() {
            validateOwner()
            failure?.let { throw it }
        }

        @Synchronized fun fail(message: String) {
            if (failure != null || detached) return
            val error = IllegalStateException(message)
            failure = error
            receipts.value = Result.failure(error)
        }

        suspend fun delivered(frame: Envelope) {
            validateOwner()
            val payload = frame.payload as? JsonObject
            if (!authoritative && frame.type == "session_transition" && payload?.get("conversation_id") == JsonPrimitive(conversation)) {
                fail("settings read session changed")
            }
            val current = receipt ?: return
            if (frame.inReplyTo != current.requestId || failure != null) return
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
            if (!authoritative &&
                (
                    frame.conversationId?.let { it != JsonPrimitive(conversation) } == true ||
                        frame.sessionStateCleared != JsonPrimitive(false) ||
                        frame.sessionProvenance.raw?.let {
                            it != if (reading.sessionId.isEmpty()) JsonNull else JsonPrimitive(reading.sessionId)
                        } == true
                )
            ) {
                // The legacy waiter already consumed this correlated reply. Omitted metadata is
                // compatible; explicitly conflicting ownership or a clear cannot authorize it.
                fail("settings reply has conflicting ownership or clear state")
                return
            }
            // The authoritative observation is a StateFlow projection: its first value is
            // synchronous and sends nothing. Read it in the inbound turn, before a later frame
            // can overwrite this reply. Legacy's cold request completes its existing collector.
            if (authoritative && !admitted(reading)) {
                fail("settings reply was not admitted by the repository")
                return
            }
            current.reply.complete(frame)
        }

        @Synchronized fun detach() {
            detached = true
            receipt?.let { pump?.pending?.remove(it.requestId, this) }
        }
    }

    private class Receipt(
        val requestId: Long,
    ) {
        val sent = CompletableDeferred<Boolean>()
        val reply = CompletableDeferred<Envelope>()
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
            val receipt = read?.attach(this, envelope)
            return delegate.send(envelope).also { accepted -> receipt?.sent?.complete(accepted) }
        }

        override fun close() {
            pending.values.forEach { it.fail("settings read connection closed") }
            delegate.close()
        }
    }
}
