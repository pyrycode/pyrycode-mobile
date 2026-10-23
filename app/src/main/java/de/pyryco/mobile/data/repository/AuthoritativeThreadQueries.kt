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
import java.io.Closeable
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
    private val expansionEnabled: () -> Boolean = { false },
) {
    private data class BodyKey(
        val request: ThreadQueryRequest.Expansion,
        val generation: Long,
        val incarnation: String,
    ) {
        val children get() = request is ThreadQueryRequest.Children
        val cursor get() = (request as? ThreadQueryRequest.Children)?.cursor
    }

    private data class Intent(
        val conversation: String,
        val older: Boolean,
        val attentionRetries: Int = 0,
        val generation: Long? = null,
        val body: BodyKey? = null,
    )

    private class Active(
        val decoder: ThreadQueryDecoder,
        val generation: Long,
        val started: Long,
        val attentionRetries: Int,
        val body: BodyKey? = null,
    ) {
        var abandonedAt: Long? = null
        var bodyData: ThreadQueryReply.Body? = null
        var childrenData: List<de.pyryco.mobile.data.network.ThreadItemDto> = emptyList()
    }

    private val connection = UUID.randomUUID().toString()
    private val opened = mutableSetOf<String>()
    private val consumers = mutableMapOf<String, MutableSet<Any>>()
    private val queued = ArrayDeque<Intent>()

    // Keep correlation ownership for this connection; late errors must never enter legacy evidence.
    private val ownedRequests = mutableSetOf<Long>()
    private var active: Active? = null
    private var closed = false
    private val bodyConsumers = mutableMapOf<Any, Set<BodyKey>>()
    private val childConsumers = mutableMapOf<Any, Set<BodyKey>>()

    @Synchronized
    fun children(
        conversation: String,
        consumer: Any,
        snapshot: AuthoritativeThreadSnapshot,
        ids: Set<Long>,
        retry: Long? = null,
        more: Long? = null,
    ): Closeable? {
        if (closed || !enabled() || !expansionEnabled()) return null
        val current = store.current(host, conversation) === snapshot
        val epoch = snapshot.epoch?.takeIf { it.isNotEmpty() } ?: return null
        val keys =
            snapshot.expansionItems
                .filter {
                    it.id in ids && it.shown && it.kind == "agent" && it.raw["detail_omitted"] == JsonPrimitive(true)
                }.mapTo(mutableSetOf()) {
                    val page = snapshot.childPages[it.id to it.rev]
                    // Keep the active/queued page's exact key while Loading changes the supplied snapshot.
                    val pending =
                        childConsumers[consumer]?.firstOrNull { key ->
                            key.request.itemId == it.id.toULong() &&
                                key.request.expectedRev == it.rev.toULong()
                        }
                    val cursor =
                        if (retry == it.id) {
                            null
                        } else if (more == it.id) {
                            page?.cursor
                        } else {
                            pending?.cursor
                        }
                    BodyKey(
                        ThreadQueryRequest.Children(conversation, epoch, it.id.toULong(), it.rev.toULong(), cursor),
                        snapshot.generation,
                        snapshot.incarnation,
                    )
                }
        childConsumers[consumer] = if (current) keys else childConsumers[consumer].orEmpty().intersect(keys)
        pruneBodies()
        for (key in keys.takeIf { current }.orEmpty()) {
            val id = key.request.itemId.toLong()
            val pair = id to key.request.expectedRev.toLong()
            val state = store.current(host, conversation).childLoads[pair]
            if (state == BodyLoad.Retry && retry != id) continue
            if (snapshot.childPages.containsKey(pair) && retry != id && (more != id || key.cursor == null)) continue
            if (active?.takeIf { it.abandonedAt == null }?.body == key || queued.any { it.body == key }) continue
            load(key, BodyLoad.Loading)
            queued.addLast(Intent(conversation, false, body = key))
        }
        dispatch()
        return Closeable { releaseChildren(consumer) }
    }

    @Synchronized
    private fun releaseChildren(consumer: Any) {
        childConsumers.remove(consumer)
        pruneBodies()
        dispatch()
    }

    private fun load(
        key: BodyKey,
        state: BodyLoad?,
    ) {
        val r = key.request
        if (key.children) {
            store.childLoad(host, r.conversationId, r.itemId.toLong(), r.expectedRev.toLong(), state)
        } else {
            store.bodyLoad(host, r.conversationId, r.itemId.toLong(), r.expectedRev.toLong(), state)
        }
    }

    @Synchronized
    fun bodies(
        conversation: String,
        consumer: Any,
        snapshot: AuthoritativeThreadSnapshot,
        ids: Set<Long>,
        retry: Long? = null,
    ): Closeable? {
        if (closed || !enabled() || !expansionEnabled()) return null
        val current = store.current(host, conversation) === snapshot
        val epoch = snapshot.epoch?.takeIf { it.isNotEmpty() } ?: return null
        val keys =
            snapshot.expansionItems
                .filter {
                    it.id in ids &&
                        it.shown &&
                        !snapshot.hasToolBody(it) &&
                        it.kind in setOf("tool_call", "agent")
                }.mapTo(mutableSetOf()) {
                    BodyKey(
                        ThreadQueryRequest.Body(conversation, epoch, it.id.toULong(), it.rev.toULong(), it.kind),
                        snapshot.generation,
                        snapshot.incarnation,
                    )
                }
        // A held presentation may withdraw exact targets, but cannot add demand or retry a read.
        bodyConsumers[consumer] = if (current) keys else bodyConsumers[consumer].orEmpty().intersect(keys)
        pruneBodies()
        for (key in keys.takeIf { current }.orEmpty()) {
            val request = key.request
            val state = store.current(host, conversation).bodyLoads[request.itemId.toLong() to request.expectedRev.toLong()]
            if (state == BodyLoad.Retry && retry?.toULong() != request.itemId) continue
            if (active?.takeIf { it.abandonedAt == null }?.body == key || queued.any { it.body == key }) continue
            store.bodyLoad(host, conversation, request.itemId.toLong(), request.expectedRev.toLong(), BodyLoad.Loading)
            queued.addLast(Intent(conversation, false, body = key))
        }
        dispatch()
        return Closeable { releaseBodies(consumer) }
    }

    @Synchronized
    fun releaseBodies(consumer: Any) {
        bodyConsumers.remove(consumer)
        pruneBodies()
        dispatch()
    }

    private fun bodyCurrent(key: BodyKey): Boolean {
        val snapshot = store.current(host, key.request.conversationId)
        return expansionEnabled() &&
            (if (key.children) childConsumers else bodyConsumers).values.any { keys ->
                if (key.children) {
                    keys.any {
                        it.request.conversationId == key.request.conversationId &&
                            it.request.epoch == key.request.epoch &&
                            it.request.itemId == key.request.itemId &&
                            it.request.expectedRev == key.request.expectedRev &&
                            it.generation == key.generation &&
                            it.incarnation == key.incarnation
                    }
                } else {
                    key in keys
                }
            } &&
            snapshot.generation == key.generation &&
            snapshot.incarnation == key.incarnation &&
            snapshot.epoch == key.request.epoch &&
            snapshot.expansionItems.any {
                it.id.toULong() == key.request.itemId &&
                    it.rev.toULong() == key.request.expectedRev &&
                    it.kind == ((key.request as? ThreadQueryRequest.Body)?.kind ?: "agent") &&
                    it.shown
            }
    }

    private fun pruneBodies() {
        queued.removeAll { intent ->
            intent.body?.let { key ->
                if (bodyCurrent(key)) {
                    false
                } else {
                    load(key, null)
                    true
                }
            } == true
        }
        active?.body?.let { if (!bodyCurrent(it)) retire("cancelled") }
    }

    @Synchronized
    fun open(conversation: String): Boolean {
        if (closed || !enabled() || conversation.isEmpty()) return false
        opened += conversation
        enqueue(Intent(conversation, false))
        dispatch()
        return true
    }

    /** Multiple destinations share one conversation's synchronization on this connection. */
    @Synchronized
    fun acquire(conversation: String): Closeable? {
        if (closed || !enabled() || conversation.isEmpty()) return null
        val token = Any()
        val leases = consumers.getOrPut(conversation) { mutableSetOf() }
        if (leases.isEmpty()) open(conversation)
        leases += token
        RelayLog.d { "event=thread_query_consumer code=acquired" }
        return Closeable { release(conversation, token) }
    }

    @Synchronized
    private fun release(
        conversation: String,
        token: Any,
    ) {
        val leases = consumers[conversation] ?: return
        if (!leases.remove(token)) return
        RelayLog.d { "event=thread_query_consumer code=released" }
        if (leases.isEmpty()) cancel(conversation)
    }

    /** A live completion needs a boundary captured after it, even when an older query is running. */
    @Synchronized
    fun refresh(
        conversation: String,
        generation: Long,
    ): Boolean {
        if (closed || !enabled() || conversation !in opened || store.current(host, conversation).generation != generation) return false
        val intent = Intent(conversation, false)
        // Coalesce queued refreshes, but never let the active query consume a later completion.
        if (intent !in queued) queued.addLast(intent)
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
        bodyConsumers.entries.removeAll { (_, keys) -> keys.any { it.request.conversationId == conversation } }
        childConsumers.entries.removeAll { (_, keys) -> keys.any { it.request.conversationId == conversation } }
        consumers.remove(conversation)
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
            return envelope.type in setOf("thread_items", "thread_reset", "thread_tool_body", "thread_children") ||
                (envelope.type == "error" && envelope.inReplyTo in ownedRequests)
        }
        val request = held.decoder.request
        if (held.body?.let { !bodyCurrent(it) } == true) retire("cancelled")
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
                        is ThreadQueryReply.Children -> {
                            if (!reply.final) {
                                held.childrenData = reply.children
                            } else {
                                val key = held.body
                                val applied =
                                    key != null &&
                                        key.children &&
                                        bodyCurrent(key) &&
                                        request is ThreadQueryRequest.Children &&
                                        store.enrichChildren(host, request, held.childrenData, reply, key.generation, key.incarnation)
                                retire(if (applied) "complete" else "application", failed = !applied, remoteComplete = true)
                            }
                        }
                        is ThreadQueryReply.Body -> {
                            if (!reply.final) {
                                held.bodyData = reply
                            } else {
                                val key = held.body
                                val data = held.bodyData
                                val applied =
                                    key != null &&
                                        data != null &&
                                        bodyCurrent(key) &&
                                        request is ThreadQueryRequest.Body &&
                                        store.enrichBody(host, request, data, key.generation, key.incarnation)
                                retire(if (applied) "complete" else "cancelled", failed = !applied, remoteComplete = true)
                            }
                        }
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
        if (closed ||
            !enabled() ||
            request.conversationId !in opened ||
            store.current(host, request.conversationId).generation != held.generation
        ) {
            return
        }
        if (held.attentionRetries == 0) return
        val retry = Intent(request.conversationId, false, held.attentionRetries - 1, held.generation)
        val pending = queued.indexOfFirst { it.conversation == retry.conversation && !it.older && it.body == null }
        if (pending < 0) {
            queued.addLast(retry)
        } else if (queued[pending].attentionRetries < retry.attentionRetries) {
            queued[pending] = retry
        }
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
        consumers.clear()
        bodyConsumers.clear()
        childConsumers.clear()
        retire("disconnected", remoteComplete = true)
    }

    private fun enqueue(intent: Intent) {
        val running = active?.decoder?.request
        if (active?.abandonedAt == null &&
            running !is ThreadQueryRequest.Body &&
            running !is ThreadQueryRequest.Children &&
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
            if (!enabled() || intent.body == null && intent.conversation !in opened) continue
            val snapshot = store.current(host, intent.conversation)
            if (intent.generation != null && intent.generation != snapshot.generation) continue
            if (intent.body?.let { !bodyCurrent(it) } == true) continue
            val request =
                if (intent.body != null) {
                    intent.body.request
                } else if (intent.older) {
                    val coverage = snapshot.coverage?.takeIf { it.olderExists } ?: continue
                    ThreadQueryRequest.Page(intent.conversation, coverage.continuation)
                } else {
                    ThreadQueryRequest.CatchUp(intent.conversation, snapshot.epoch.orEmpty(), snapshot.checkpoint.toULong())
                }
            val id = nextId()
            ownedRequests += id
            active =
                Active(
                    ThreadQueryDecoder(host, connection, id, request, nowMillis = nowMillis),
                    snapshot.generation,
                    nowMillis(),
                    intent.attentionRetries,
                    intent.body,
                )
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
        held.bodyData = null
        held.childrenData = emptyList()
        if (held.body != null && held.abandonedAt == null) {
            load(
                held.body,
                if (failed && code != "cancelled" && code != "disconnected") BodyLoad.Retry else null,
            )
        }
        if (held.body == null &&
            failed &&
            held.abandonedAt == null &&
            store.current(host, held.decoder.request.conversationId).generation == held.generation
        ) {
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
        return held.decoder.drain(envelope).any {
            it is ThreadDecodeOutcome.Complete && it.value !is ThreadQueryReply.Batch
        }
    }

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 30000L
    }
}
