package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Source-owned producer proof. No phone content or cache participates. */
internal class ProducerThreadObservation(
    private val host: String,
    private val conversation: String,
    val connection: Any,
    capabilities: Set<String>,
    private val current: () -> Boolean,
) {
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

    private val store = AuthoritativeThreadStore()
    private var pending: Query? = null
    private var completedId: Long? = null
    private var retired = false
    private var reply: OwnedReplyObservation? = null
    private var sendId: Long? = null

    private fun owns(source: Any) = source === connection && !retired && current()

    @Synchronized fun nextQuery(): ThreadQueryRequest {
        check(owns(connection) && pending == null) { "producer source unavailable or query incomplete" }
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
            if (reply?.sending(source, envelope, current) == true) sendId = envelope.id
            return
        }
        if (envelope.type !in setOf("thread_catch_up", "thread_page")) return
        val expected = nextQuery()
        check(expected.type == envelope.type && expected.payload() == envelope.payload) { "producer query changed" }
        pending = Query(envelope, ThreadQueryDecoder(host, "owned-producer", envelope.id, expected))
        reply?.phoneSending(source, envelope)
    }

    @Synchronized fun sent(
        source: Any,
        envelope: Envelope,
        accepted: Boolean,
    ) {
        if (!owns(source)) return
        if (envelope.type == "send_message" && sendId == envelope.id) reply?.sent(source, accepted)
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
                is ThreadDecodeOutcome.Repair -> retire()
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
                            if (applied !in setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored)) retire()
                        }
                        is ThreadQueryReply.Success -> {
                            if (store.certifyQuery(host, query.decoder.request, value, 0) != AuthoritativeThreadOutcome.Applied) {
                                retire()
                            } else {
                                completedId = query.envelope.id
                                pending = null
                            }
                        }
                        else -> retire()
                    }
            }
        }
    }

    private fun retire() {
        retired = true
        pending?.decoder?.abandon()
        pending = null
        reply?.phoneEnded(connection)
        reply?.ended(connection)
    }

    @Synchronized fun ended(source: Any) {
        if (source === connection) retire()
    }

    @Synchronized fun completed(requestId: Long): Boolean = owns(connection) && pending == null && completedId == requestId

    @Synchronized fun evidence(): ThreadEvidence? {
        if (!owns(connection) || pending != null || completedId == null) return null
        val snapshot = store.current(host, conversation)
        if (snapshot.repairNeeded || snapshot.resetNeeded || snapshot.coverage?.olderExists != false ||
            snapshot.checkpoint < snapshot.completedVersion
        ) {
            return null
        }
        return authoritativeThreadEvidence(host, conversation, connection, snapshot).takeIf { it.admitted && owns(connection) }
    }

    @Synchronized fun completedPost(text: String): Boolean =
        evidence()?.sourceSnapshot?.items?.singleOrNull {
            it.kind == "assistant_message" && !it.active && it.status in setOf("done", "finished") && it.shown &&
                (it.parent ?: 0) == 0L && (it.order ?: 0) > 0 &&
                ((it.content as? JsonObject)?.get("text") as? JsonPrimitive)?.content == text
        } != null

    @Synchronized fun expectReply() {
        check(reply == null) { "producer reply already armed" }
        reply =
            OwnedReplyObservation(host, checkNotNull(evidence()) { "producer baseline incomplete" }).also {
                it.bindPhoneConnection(connection, current)
            }
    }

    @Synchronized fun produced(): ProducedReplyEvidence? {
        val evidence = evidence() ?: return null
        val observation = reply ?: return null
        return observation.produced()?.takeIf { observation.admitted(evidence, connection) != null && owns(connection) }
    }
}
