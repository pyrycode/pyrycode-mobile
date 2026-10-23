package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.network.threadInteger
import de.pyryco.mobile.data.network.threadUnsigned
import de.pyryco.mobile.data.repository.ManagedSessionPump
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Passive, test-only receipts. A cached checkpoint cannot complete an in-flight phone query. */
internal object ThreadQueryReadiness {
    private val sources = ConcurrentHashMap<String, MutableStateFlow<ThreadQueryProgress?>>()

    internal class Receipt(
        private val source: ThreadQueryProgress,
        private val conversation: String,
        private val request: Long,
    ) {
        fun isCurrent(): Boolean = sources[source.host]?.value === source && source.ready(conversation) == request
    }

    fun register(
        host: String,
        isOpen: () -> Boolean,
    ): ThreadQueryProgress = ThreadQueryProgress(host, isOpen).also { sources.getOrPut(host) { MutableStateFlow(null) }.value = it }

    suspend fun awaitCurrent(
        host: String,
        conversation: String,
    ): Receipt =
        sources.getOrPut(host) { MutableStateFlow(null) }.firstOnLive(
            { source -> source.changes.map { source.ready(conversation)?.let { Receipt(source, conversation, it) } } },
            { it.isCurrent() },
        )

    fun wrap(
        host: String,
        delegate: ManagedSessionPump,
    ): ManagedSessionPump {
        val progress = register(host) { delegate.state.value is PumpState.Open }
        return object : ManagedSessionPump by delegate {
            override val inbound =
                flow {
                    try {
                        delegate.inbound.collect { frame ->
                            emit(frame)
                            // The real repository has consumed this frame. Cancellation cannot certify it.
                            progress.consumed(frame)
                        }
                    } finally {
                        progress.close()
                    }
                }

            override fun send(envelope: Envelope): Boolean {
                progress.sending(envelope)
                return try {
                    delegate.send(envelope).also { progress.sent(envelope.id, it) }
                } catch (error: Throwable) {
                    progress.sent(envelope.id, false)
                    throw error
                }
            }

            override fun close() {
                progress.close()
                delegate.close()
            }
        }
    }
}

/** One physical phone source. Decoding observes real traffic and never supplies a request or reply. */
internal class ThreadQueryProgress(
    val host: String,
    private val isOpen: () -> Boolean,
) {
    private class Query(
        val decoder: ThreadQueryDecoder,
    ) {
        var accepted: Boolean? = null
        var terminal: Boolean? = null
    }

    private val pending = mutableMapOf<Long, Query>()
    private val completed = mutableMapOf<String, Long>()
    private var closed = false
    val changes = MutableStateFlow(0L)

    @Synchronized fun sending(envelope: Envelope) {
        if (closed || envelope.type !in setOf("thread_catch_up", "thread_page")) return
        val raw = envelope.payload as? JsonObject ?: return
        val conversation = (raw["conversation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val request =
            try {
                val limit = if ("limit" in raw) raw.threadInteger("limit", Long.MAX_VALUE) else null
                if (envelope.type == "thread_catch_up") {
                    val epoch = (raw["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
                    ThreadQueryRequest.CatchUp(conversation, epoch, raw.threadUnsigned("version"), limit)
                } else {
                    ThreadQueryRequest.Page(conversation, raw.threadUnsigned("before_order"), limit)
                }
            } catch (_: IllegalArgumentException) {
                return
            }
        check(envelope.id !in pending) { "Duplicate observed thread query" }
        pending[envelope.id] = Query(ThreadQueryDecoder(host, "scenario-phone", envelope.id, request))
        changed()
    }

    @Synchronized fun sent(
        id: Long,
        accepted: Boolean,
    ) {
        val query = pending[id] ?: return
        query.accepted = accepted
        finish(id, query)
        changed()
    }

    @Synchronized fun consumed(envelope: Envelope) {
        val id = envelope.inReplyTo ?: return
        val query = pending[id] ?: return
        for (outcome in query.decoder.decode(envelope)) {
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> Unit
                is ThreadDecodeOutcome.Repair -> query.terminal = false
                is ThreadDecodeOutcome.Complete ->
                    when (outcome.value) {
                        is ThreadQueryReply.Batch -> Unit
                        is ThreadQueryReply.Success -> query.terminal = true
                        else -> query.terminal = false
                    }
            }
        }
        finish(id, query)
        changed()
    }

    private fun finish(
        id: Long,
        query: Query,
    ) {
        if (query.accepted == null || query.accepted == true && query.terminal == null) return
        val conversation = query.decoder.request.conversationId
        if (query.accepted == true && query.terminal == true) completed[conversation] = id else completed.remove(conversation)
        query.decoder.abandon()
        pending.remove(id)
    }

    @Synchronized fun ready(conversation: String): Long? =
        completed[conversation]?.takeIf {
            !closed && isOpen() && pending.values.none { it.decoder.request.conversationId == conversation }
        }

    @Synchronized fun close() {
        closed = true
        pending.values.forEach { it.decoder.abandon() }
        pending.clear()
        completed.clear()
        changed()
    }

    private fun changed() {
        changes.value += 1
    }
}
