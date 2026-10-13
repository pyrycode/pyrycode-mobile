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
) {
    private data class Intent(
        val conversation: String,
        val older: Boolean,
    )

    private class Active(
        val decoder: ThreadQueryDecoder,
        val generation: Long,
        val started: Long,
    )

    private val connection = UUID.randomUUID().toString()
    private val opened = mutableSetOf<String>()
    private val queued = ArrayDeque<Intent>()
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
        if (held == null || envelope.inReplyTo != held.decoder.requestId) return envelope.type in setOf("thread_items", "thread_reset")
        val request = held.decoder.request
        if (store.current(host, request.conversationId).generation != held.generation) {
            retire("generation")
            if (request.conversationId in opened) enqueue(Intent(request.conversationId, false))
            dispatch()
            return true
        }
        if (nowMillis() - held.started >= QUERY_TIMEOUT_MILLIS) {
            retire("timeout")
            dispatch()
            return true
        }
        held.decoder.decode(envelope).forEach { outcome ->
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> Unit
                is ThreadDecodeOutcome.Repair -> retire("malformed")
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
                            retire(
                                if (result == AuthoritativeThreadOutcome.Applied) "complete" else "coverage",
                                failed = result != AuthoritativeThreadOutcome.Applied,
                            )
                        }
                        is ThreadQueryReply.Reset -> {
                            retire("reset", failed = false)
                            route.reset(request.conversationId, reply.checkpoint.epoch)
                            enqueue(Intent(request.conversationId, false))
                        }
                        is ThreadQueryReply.Failure -> retire("failure")
                    }
            }
        }
        dispatch()
        return true
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
        retire("disconnected")
    }

    private fun enqueue(intent: Intent) {
        val running = active?.decoder?.request
        if (running?.conversationId == intent.conversation && (running is ThreadQueryRequest.Page) == intent.older) return
        if (intent !in queued) queued.addLast(intent)
    }

    private fun dispatch() {
        while (!closed && active == null && queued.isNotEmpty()) {
            val intent = queued.removeFirst()
            if (!enabled() || intent.conversation !in opened) continue
            val snapshot = store.current(host, intent.conversation)
            val request =
                if (intent.older) {
                    val coverage = snapshot.coverage?.takeIf { it.olderExists } ?: continue
                    ThreadQueryRequest.Page(intent.conversation, coverage.continuation)
                } else {
                    ThreadQueryRequest.CatchUp(intent.conversation, snapshot.epoch.orEmpty(), snapshot.checkpoint.toULong())
                }
            val id = nextId()
            active = Active(ThreadQueryDecoder(host, connection, id, request, nowMillis = nowMillis), snapshot.generation, nowMillis())
            RelayLog.i { "event=thread_sync code=requested" }
            if (!send(Envelope(id, request.type, Clock.System.now().toString(), request.payload()))) retire("send_failed")
        }
    }

    private fun retire(
        code: String,
        failed: Boolean = true,
    ) {
        val held = active ?: return
        active = null
        held.decoder.abandon()
        if (failed && store.current(host, held.decoder.request.conversationId).generation == held.generation) {
            store.requestRepair(host, held.decoder.request.conversationId)
        }
        RelayLog.i { "event=thread_sync code=$code" }
    }

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 30000L
    }
}
