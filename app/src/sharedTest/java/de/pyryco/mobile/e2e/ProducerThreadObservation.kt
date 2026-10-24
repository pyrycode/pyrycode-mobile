package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Source-owned producer proof. No phone content or cache participates. */
internal class ProducerThreadObservation(
    private val host: String,
    private val conversation: String,
    connection: Any,
    capabilities: Set<String>,
    current: () -> Boolean,
) {
    companion object {
        fun acquire(
            host: String,
            conversation: String,
            source: Any,
            capabilities: Set<String>?,
            current: () -> Boolean,
        ): ProducerThreadObservation? {
            if (capabilities == null || !current()) return null
            return ProducerThreadObservation(host, conversation, source, capabilities, current).takeIf { current() }
        }
    }

    var connection: Any = connection
        private set
    private var current = current

    init {
        require(setOf("thread", "interactive").all { it in capabilities }) { "producer capabilities unavailable" }
    }

    private class Query(
        val envelope: Envelope,
        val decoder: ThreadQueryDecoder,
    ) {
        var accepted: Boolean? = null
        val early = mutableListOf<Envelope>()
        var bytes = 0L
    }

    private var store = AuthoritativeThreadStore()
    private var committed: AuthoritativeThreadSnapshot? = null
    private var pending: Query? = null
    private var completedId: Long? = null
    private var retired = false
    private var reply: OwnedReplyObservation? = null
    private var beforeReply: ThreadEvidence? = null
    private var sendReceipt: Pair<Any, Envelope>? = null
    private var sendAccepted: Boolean? = null
    private var rejected = false
    private var rejection = "none"
    private var lastQueryId: Long? = null

    private fun owns(source: Any) = source === connection && !retired && current()

    @Synchronized fun bindConnection(
        source: Any,
        capabilities: Set<String>?,
        isCurrent: () -> Boolean,
    ): Boolean {
        check(!rejected) { "producer query rejected" }
        if (capabilities == null || !isCurrent()) return false
        if (source !== connection) replaceConnection(source, capabilities, isCurrent)
        return owns(source)
    }

    @Synchronized fun queryIfCurrent(): ThreadQueryRequest? {
        check(!rejected) { "producer query rejected" }
        return if (owns(connection)) queryRequest() else null
    }

    /** Retained facts are unqualified until a fresh query on the replacement completes. */
    @Synchronized fun replaceConnection(
        source: Any,
        capabilities: Set<String>,
        isCurrent: () -> Boolean,
    ) {
        check(!rejected && source !== connection) { "producer replacement unavailable" }
        require(setOf("thread", "interactive").all { it in capabilities }) { "producer capabilities unavailable" }
        pending?.decoder?.abandon()
        reply?.phoneEnded(connection)
        reply?.ended(sendReceipt?.first ?: connection)
        connection = source
        current = isCurrent
        retired = false
        pending = null
        completedId = null
        lastQueryId = null
        store = AuthoritativeThreadStore().also { fresh -> committed?.let { fresh.restore(host, conversation, it, 0) } }
        if (beforeReply != null) rebuildReply()
    }

    @Synchronized fun nextQuery(): ThreadQueryRequest {
        check(owns(connection)) { "producer source unavailable" }
        return queryRequest()
    }

    private fun queryRequest(): ThreadQueryRequest {
        check(pending == null) { "producer query incomplete" }
        val snapshot = store.current(host, conversation)
        val coverage = snapshot.coverage
        return if (coverage?.olderExists == true) {
            ThreadQueryRequest.Page(conversation, coverage.continuation)
        } else {
            ThreadQueryRequest.CatchUp(conversation, snapshot.epoch.orEmpty(), snapshot.checkpoint.toULong())
        }
    }

    @Synchronized fun sending(
        source: Any,
        envelope: Envelope,
    ) {
        if (!owns(source)) return
        if (envelope.type == "send_message") {
            if (reply?.sending(source, envelope, current) == true) {
                sendReceipt = source to envelope
                sendAccepted = null
            }
            return
        }
        if (envelope.type !in setOf("thread_catch_up", "thread_page")) return
        val expected = queryRequest()
        check(expected.type == envelope.type && expected.payload() == envelope.payload) { "producer query changed" }
        lastQueryId = envelope.id
        pending = Query(envelope, ThreadQueryDecoder(host, "owned-producer", envelope.id, expected))
        reply?.phoneSending(source, envelope)
    }

    @Synchronized fun sent(
        source: Any,
        envelope: Envelope,
        accepted: Boolean,
    ) {
        if (envelope.type == "send_message") {
            val (sender, request) = sendReceipt ?: return
            // This is a historical transport result, independent of current query ownership.
            if (source !== sender || envelope != request || sendAccepted != null) return
            sendAccepted = accepted
            if (accepted) {
                reply?.sent(source, true)
            } else {
                sendReceipt = null
                sendAccepted = null
                rebuildReply()
            }
            return
        }
        if (!owns(source)) return
        val query = pending?.takeIf { it.envelope.id == envelope.id } ?: return
        reply?.phoneSent(source, envelope, accepted)
        query.accepted = accepted
        if (!accepted) {
            retire()
            return
        }
        val early = query.early.toList()
        query.early.clear()
        early.forEach { decode(query, it) }
    }

    @Synchronized fun received(
        source: Any,
        envelope: Envelope,
    ) {
        if (!owns(source)) return
        val query = pending?.takeIf { it.envelope.id == envelope.inReplyTo } ?: return
        reply?.phoneReceived(source, envelope)
        if (query.accepted == null) {
            query.bytes += envelope.payload
                .toString()
                .length
                .toLong() * 4
            if (query.early.size >= 4096 || query.bytes > 16 * 1024 * 1024) retire() else query.early += envelope
            return
        }
        if (query.accepted == true) decode(query, envelope)
    }

    private fun decode(
        query: Query,
        envelope: Envelope,
    ) {
        if (retired || pending !== query) return
        for (outcome in query.decoder.decode(envelope)) {
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> Unit
                is ThreadDecodeOutcome.Repair -> reject("decoder_repair")
                is ThreadDecodeOutcome.Complete ->
                    when (val value = outcome.value) {
                        is ThreadQueryReply.Batch -> {
                            val applied =
                                store.applyBatch(
                                    host,
                                    conversation,
                                    value.checkpoint.epoch,
                                    value.checkpoint.version.toLong(),
                                    value.items,
                                    0,
                                )
                            if (applied !in setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored)) reject("batch")
                        }
                        is ThreadQueryReply.Success -> {
                            if (store.certifyQuery(host, query.decoder.request, value, 0) != AuthoritativeThreadOutcome.Applied) {
                                reject("certification")
                            } else {
                                committed = store.current(host, conversation)
                                completedId = query.envelope.id
                                pending = null
                            }
                        }
                        is ThreadQueryReply.Failure ->
                            reject(
                                when (value.error.code) {
                                    "thread.query_failed" -> "query_failed"
                                    "history_unavailable", "history.unavailable" -> "history_unavailable"
                                    else -> "query_failure"
                                },
                            )
                        is ThreadQueryReply.Reset -> reject("reset")
                        else -> reject("terminal")
                    }
            }
        }
    }

    private fun retire() {
        retired = true
        pending?.decoder?.abandon()
        pending = null
        reply?.phoneEnded(connection)
        reply?.ended(sendReceipt?.first ?: connection)
    }

    private fun reject(reason: String) {
        rejection = reason
        rejected = true
        retire()
    }

    @Synchronized fun ended(source: Any) {
        if (source === connection) retire()
    }

    /** Passive scalar state for the bounded native failure report. */
    @Synchronized fun diagnostic(): String {
        val snapshot = store.current(host, conversation)
        return "owned=${owns(connection)} rejected=$rejected rejection=$rejection retired=$retired " +
            "pending=${pending != null} accepted=${pending?.accepted} last_query=$lastQueryId completed_query=$completedId " +
            "items=${snapshot.items.size} checkpoint=${snapshot.checkpoint} completed_version=${snapshot.completedVersion} " +
            "older=${snapshot.coverage?.olderExists} repair=${snapshot.repairNeeded} reset=${snapshot.resetNeeded}"
    }

    @Synchronized fun completed(requestId: Long): Boolean = owns(connection) && pending == null && completedId == requestId

    @Synchronized fun evidence(): ThreadEvidence? {
        if (!owns(connection) || pending != null || completedId == null) return null
        val snapshot = store.current(host, conversation)
        if (snapshot.repairNeeded ||
            snapshot.resetNeeded ||
            snapshot.coverage?.olderExists != false ||
            snapshot.checkpoint < snapshot.completedVersion
        ) {
            return null
        }
        return authoritativeThreadEvidence(host, conversation, connection, snapshot).takeIf { it.admitted && owns(connection) }
    }

    @Synchronized fun completedPost(text: String): Boolean =
        evidence()?.sourceSnapshot?.items?.singleOrNull {
            it.kind == "assistant_message" &&
                !it.active &&
                it.status in setOf("done", "finished") &&
                it.shown &&
                (it.parent ?: 0) == 0L &&
                (it.order ?: 0) > 0 &&
                ((it.content as? JsonObject)?.get("text") as? JsonPrimitive)?.content == text
        } != null

    @Synchronized fun expectReply(): Boolean {
        check(reply == null) { "producer reply already armed" }
        beforeReply = evidence() ?: return false
        rebuildReply()
        return true
    }

    private fun rebuildReply() {
        val before = checkNotNull(beforeReply)
        val rebound = authoritativeThreadEvidence(host, conversation, connection, checkNotNull(before.sourceSnapshot))
        reply =
            OwnedReplyObservation(host, rebound, producerSnapshot = committed).also { observation ->
                observation.bindPhoneConnection(connection, current)
                // Rehydrate the actual original receipt; no transport send occurs here.
                sendReceipt?.let { (original, envelope) ->
                    observation.sending(original, envelope) { owns(connection) }
                    sendAccepted?.let { observation.sent(original, it) }
                }
            }
    }

    @Synchronized fun produced(): ProducedReplyEvidence? {
        val evidence = evidence() ?: return null
        val observation = reply ?: return null
        return observation.produced()?.takeIf { observation.admittedIfCurrent(evidence, connection) != null && owns(connection) }
    }
}

/** The durable-gap scenario's bounded producer sequence; the input is submitted only once. */
internal suspend fun ProducerThreadObservation.awaitReplyAfterPost(
    post: String,
    timeoutMs: Long,
    catchUp: suspend () -> Unit,
    send: suspend () -> Unit,
): ProducedReplyEvidence {
    withTimeout(timeoutMs) {
        while (!completedPost(post) || !expectReply()) {
            catchUp()
            if (!completedPost(post)) delay(50)
        }
    }
    send()
    return withTimeout(timeoutMs) {
        var frozen = produced()
        while (frozen == null) {
            catchUp()
            frozen = produced()
            if (frozen == null) delay(50)
        }
        frozen
    }
}
