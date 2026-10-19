package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** One connection's unfinished query slot. Shared facts survive reconnect; pending work never does. */
internal class AuthoritativeThreadQueries(
    private val host: String,
    private val store: AuthoritativeThreadStore,
    private val route: AuthoritativeThreadRoute,
    private val enabled: () -> Boolean,
    private val nextId: () -> Long,
    private val send: (Envelope) -> Boolean,
    private val nowMillis: () -> Long,
    private val retireConnection: () -> Unit = {},
    private val onCertified: (String, Long, ThreadQueryReply.Success) -> Unit = { _, _, _ -> },
) {
    private data class Intent(
        val conversation: String,
        val older: Boolean,
        val attentionRetries: Int = 0,
        val generation: Long? = null,
    )

    private class Active(
        val decoder: ThreadQueryDecoder,
        val generation: Long,
        val started: Long,
        val attentionRetries: Int,
    ) {
        var abandonedAt: Long? = null
    }

    private val connection = UUID.randomUUID().toString()
    private val opened = mutableSetOf<String>()
    private val queued = ArrayDeque<Intent>()

    // Keep correlation ownership for this connection; late errors must never enter legacy evidence.
    private val ownedRequests = mutableSetOf<Long>()
    private var active: Active? = null
    private var closed = false

    @Synchronized
    fun open(conversation: String): Boolean {
        if (closed || !enabled() || conversation.isEmpty()) return false
        opened += conversation
        enqueue(Intent(conversation, false))
        dispatch()
        return true
    }

    /** Attention can discover a completion after an already-running query captured its boundary. */
    @Synchronized
    fun refreshAttention(conversation: String): Boolean {
        if (closed || !enabled() || conversation.isEmpty()) return false
        opened += conversation
        val intent = Intent(conversation, false, attentionRetries = 2)
        if (intent !in queued) queued.addLast(intent)
        dispatch()
        return true
    }

    @Synchronized
    fun older(conversation: String): Boolean {
        if (closed || !enabled() || conversation !in opened || store.current(host, conversation).coverage?.olderExists != true) return false
        enqueue(Intent(conversation, true))
        dispatch()
        return true
    }

    @Synchronized
    fun cancel(conversation: String) {
        opened -= conversation
        queued.removeAll { it.conversation == conversation }
        if (active?.decoder?.request?.conversationId == conversation) retire("cancelled")
        dispatch()
    }

    @Synchronized
    fun reset(
        conversation: String,
        epoch: String,
    ): Boolean {
        if (closed || !enabled() || conversation.isEmpty() || epoch.isEmpty()) return false
        queued.removeAll { it.conversation == conversation }
        if (active?.decoder?.request?.conversationId == conversation) retire("reset")
        route.reset(conversation, epoch)
        opened += conversation
        enqueue(Intent(conversation, false))
        dispatch()
        return true
    }

    /** Called outside the live route's lock, after a complete live outcome or assembly expiry. */
    @Synchronized
    fun repair(generations: Map<String, Long>) {
        if (closed || !enabled()) return
        val affected =
            opened.filter {
                val snapshot = store.current(host, it)
                generations[it] == snapshot.generation && snapshot.repairNeeded
            }
        if (active?.decoder?.request?.conversationId in affected) retire("live_repair")
        affected.forEach { enqueue(Intent(it, false)) }
        dispatch()
    }

    @Synchronized
    fun accept(envelope: Envelope): Boolean {
        val held = active
        if (held == null || envelope.inReplyTo != held.decoder.requestId) {
            return envelope.type in setOf("thread_items", "thread_reset") ||
                (envelope.type == "error" && envelope.inReplyTo in ownedRequests)
        }
        val request = held.decoder.request
        if (store.current(host, request.conversationId).generation != held.generation) {
            retire("generation")
            if (request.conversationId in opened) enqueue(Intent(request.conversationId, false))
        }
        if (nowMillis() - held.started >= QUERY_TIMEOUT_MILLIS) {
            retire("timeout")
        }
        if (held.abandonedAt != null) {
            if (isTerminal(envelope, held)) retire("drained", failed = false, remoteComplete = true)
            dispatch()
            return true
        }
        held.decoder.decode(envelope).forEach { outcome ->
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> Unit
                is ThreadDecodeOutcome.Repair -> retire("malformed", remoteComplete = isTerminal(envelope, held))
                is ThreadDecodeOutcome.Complete ->
                    when (val reply = outcome.value) {
                        is ThreadQueryReply.Batch -> {
                            val checkpoint = reply.checkpoint
                            val applied =
                                if (checkpoint.version <= 9007199254740991uL) {
                                    store.applyBatch(
                                        host,
                                        request.conversationId,
                                        checkpoint.epoch,
                                        checkpoint.version.toLong(),
                                        reply.items,
                                        held.generation,
                                    )
                                } else {
                                    AuthoritativeThreadOutcome.RepairNeeded
                                }
                            if (applied !in setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored)) {
                                retire("application")
                            }
                        }
                        is ThreadQueryReply.Success -> {
                            val result = store.certifyQuery(host, request, reply, held.generation)
                            if (result == AuthoritativeThreadOutcome.Applied && request is ThreadQueryRequest.CatchUp) {
                                onCertified(request.conversationId, held.generation, reply)
                            }
                            retire(
                                if (result == AuthoritativeThreadOutcome.Applied) "complete" else "coverage",
                                failed = result != AuthoritativeThreadOutcome.Applied,
                                remoteComplete = true,
                            )
                            if (result == AuthoritativeThreadOutcome.Applied &&
                                store.current(host, request.conversationId).repairNeeded
                            ) {
                                retryAttention(held)
                            }
                        }
                        is ThreadQueryReply.Reset -> {
                            retire("reset", failed = false, remoteComplete = true)
                            route.reset(request.conversationId, reply.checkpoint.epoch)
                            enqueue(Intent(request.conversationId, false, held.attentionRetries))
                        }
                        is ThreadQueryReply.Failure -> {
                            retire("failure", remoteComplete = true)
                            if (reply.error.retryable) retryAttention(held)
                        }
                    }
            }
        }
        dispatch()
        return true
    }

    /** A terminal response releases the remote slot; partial/rejected work never grants a retry certificate. */
    private fun retryAttention(held: Active) {
        val request = held.decoder.request as? ThreadQueryRequest.CatchUp ?: return
        if (closed || !enabled() || request.conversationId !in opened ||
            store.current(host, request.conversationId).generation != held.generation
        ) return
        if (held.attentionRetries == 0) return
        val retry = Intent(request.conversationId, false, held.attentionRetries - 1, held.generation)
        val pending = queued.indexOfFirst { it.conversation == retry.conversation && !it.older }
        if (pending < 0) queued.addLast(retry) else if (queued[pending].attentionRetries < retry.attentionRetries) queued[pending] = retry
        RelayLog.d { "event=attention_completion source=catch_up outcome=retry_queued" }
    }

    suspend fun runExpiry() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            expire()
        }
    }

    @Synchronized
    private fun expire() {
        val held = active ?: return
        val abandonedAt = held.abandonedAt
        if (abandonedAt != null) {
            if (nowMillis() - abandonedAt >= QUERY_TIMEOUT_MILLIS) {
                abandon()
                RelayLog.i { "event=thread_sync code=drain_timeout" }
                retireConnection()
            }
            return
        }
        if (nowMillis() - held.started >= QUERY_TIMEOUT_MILLIS || held.decoder.expire().isNotEmpty()) {
            retire("timeout")
            dispatch()
        }
    }

    @Synchronized
    fun abandon() {
        closed = true
        queued.clear()
        opened.clear()
        retire("disconnected", remoteComplete = true)
    }

    private fun enqueue(intent: Intent) {
        val running = active?.decoder?.request
        if (active?.abandonedAt == null &&
            running?.conversationId == intent.conversation &&
            (running is ThreadQueryRequest.Page) == intent.older
        ) {
            return
        }
        if (intent !in queued) queued.addLast(intent)
    }

    private fun dispatch() {
        while (!closed && active == null && queued.isNotEmpty()) {
            val intent = queued.removeFirst()
            if (!enabled() || intent.conversation !in opened) continue
            val snapshot = store.current(host, intent.conversation)
            if (intent.generation != null && intent.generation != snapshot.generation) continue
            val request =
                if (intent.older) {
                    val coverage = snapshot.coverage?.takeIf { it.olderExists } ?: continue
                    ThreadQueryRequest.Page(intent.conversation, coverage.continuation)
                } else {
                    ThreadQueryRequest.CatchUp(intent.conversation, snapshot.epoch.orEmpty(), snapshot.checkpoint.toULong())
                }
            val id = nextId()
            ownedRequests += id
            active = Active(ThreadQueryDecoder(host, connection, id, request, nowMillis = nowMillis), snapshot.generation, nowMillis(), intent.attentionRetries)
            RelayLog.i { "event=thread_sync code=requested" }
            if (!send(Envelope(id, request.type, Clock.System.now().toString(), request.payload()))) {
                retire("send_failed", remoteComplete = true)
            }
        }
    }

    private fun retire(
        code: String,
        failed: Boolean = true,
        remoteComplete: Boolean = false,
    ) {
        val held = active ?: return
        if (remoteComplete) active = null
        held.decoder.abandon()
        if (failed && held.abandonedAt == null && store.current(host, held.decoder.request.conversationId).generation == held.generation) {
            store.requestRepair(host, held.decoder.request.conversationId)
        }
        if (!remoteComplete && held.abandonedAt == null) held.abandonedAt = nowMillis()
        RelayLog.i { "event=thread_sync code=$code" }
    }

    /** Draining proves remote retirement only: discarded batches and markers never certify facts. */
    private fun isTerminal(
        envelope: Envelope,
        held: Active,
    ): Boolean {
        if (envelope.type !in setOf("error", "thread_reset") &&
            (envelope.payload as? JsonObject)?.get("final") != JsonPrimitive(true)
        ) {
            return false
        }
        val drain = ThreadQueryDecoder(host, connection, held.decoder.requestId, held.decoder.request, nowMillis = nowMillis)
        return drain.decode(envelope).any {
            it is ThreadDecodeOutcome.Complete && it.value !is ThreadQueryReply.Batch
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 30000L
    }
}
