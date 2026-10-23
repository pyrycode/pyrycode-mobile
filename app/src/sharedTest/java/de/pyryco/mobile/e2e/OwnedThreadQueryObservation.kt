package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.network.threadInteger
import de.pyryco.mobile.data.network.threadUnsigned
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Passive phone query evidence. Every call is serialized by its owning observation monitor. */
internal class OwnedThreadQueryObservation(
    private val host: String,
    private val before: ThreadEvidenceOwner,
    private val store: AuthoritativeThreadStore,
    private val route: AuthoritativeThreadRoute,
    private val onAccepted: (ThreadQueryRequest) -> Unit = {},
    private val onCertified: (ThreadQueryRequest, ThreadQueryReply.Success, AuthoritativeThreadSnapshot) -> Unit = { _, _, _ -> },
    private val onReset: (ThreadQueryRequest, ThreadQueryReply.Reset) -> Unit = { _, _ -> },
    private val followReplacementReset: Boolean = false,
) {
    /** Immutable identity and disposition; payloads and wire identities never enter diagnostics. */
    internal class Receipt(
        val host: String,
        val connection: Any,
        val requestId: Long,
        val request: ThreadQueryRequest,
        val epoch: String?,
        val generation: Long,
        val disposition: String,
    ) {
        override fun toString(): String = "PhoneQueryReceipt(redacted)"
    }

    private class Query(
        val decoder: ThreadQueryDecoder,
        val generation: Long,
        val ordinal: Int,
        val epoch: String?,
    ) {
        var accepted: Boolean? = null
        var disposition = "pending"
        val early = mutableListOf<Envelope>()
        var earlyBytes = 0
    }

    private var connection: Any? = null
    private var current: () -> Boolean = { true }
    private var closed = false
    private var expected = before
    private var awaitingCertification = false
    private val queries = mutableMapOf<Long, Query>()
    private var nextOrdinal = 0

    private fun trace(
        query: Query,
        stage: String,
    ) {
        val snapshot = store.current(host, before.conversation)
        val request = query.decoder.request as? ThreadQueryRequest.CatchUp
        RelayLog.i {
            "event=phone_query history=$followReplacementReset ordinal=${query.ordinal} stage=$stage " +
                "accepted=${query.accepted} disposition=${query.disposition} " +
                "generation=${query.generation} current_generation=${snapshot.generation} " +
                "requested_version=${request?.version} checkpoint=${snapshot.checkpoint} " +
                "completed=${snapshot.completedVersion} epoch_same=${request == null || request.epoch == snapshot.epoch} " +
                "current=${current()} closed=$closed"
        }
    }

    fun bind(
        connection: Any,
        current: () -> Boolean,
    ) {
        if (this.connection != null && this.connection !== connection) {
            close(this.connection!!)
            return
        }
        this.connection = connection
        this.current = current
    }

    fun sending(
        connection: Any,
        envelope: Envelope,
    ) {
        if (!owns(connection) || envelope.type !in setOf("thread_catch_up", "thread_page")) return
        val raw = envelope.payload as? JsonObject ?: return
        if (raw["conversation_id"] != JsonPrimitive(before.conversation)) return
        val request =
            try {
                val limit = if ("limit" in raw) raw.threadInteger("limit", Long.MAX_VALUE) else null
                if (envelope.type == "thread_catch_up") {
                    val epoch = (raw["epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
                    ThreadQueryRequest.CatchUp(before.conversation, epoch, raw.threadUnsigned("version"), limit)
                } else {
                    ThreadQueryRequest.Page(before.conversation, raw.threadUnsigned("before_order"), limit)
                }
            } catch (_: IllegalArgumentException) {
                return
            }
        if (queries.size >= 4096 || queries.values.count { it.disposition == "pending" } >= 32 || envelope.id in queries) {
            close(connection)
            return
        }
        queries[envelope.id] =
            Query(
                ThreadQueryDecoder(host, "owned-phone", envelope.id, request),
                store.current(host, before.conversation).generation,
                ++nextOrdinal,
                store.current(host, before.conversation).epoch,
            )
        trace(checkNotNull(queries[envelope.id]), "sending")
    }

    fun sent(
        connection: Any,
        envelope: Envelope,
        accepted: Boolean,
    ) {
        // A transport can close or lose ownership before its send returns. Acceptance still counts.
        if (this.connection !== connection) return
        val query = queries[envelope.id] ?: return
        if (query.accepted != null) return
        query.accepted = accepted
        trace(query, "sent")
        if (!accepted) {
            query.decoder.abandon()
            queries.remove(envelope.id)
            return
        }
        awaitingCertification = true
        onAccepted(query.decoder.request)
        if (!owns(connection) || query.disposition != "pending") {
            abandon(query)
            return
        }
        val early = query.early.toList()
        query.early.clear()
        query.earlyBytes = 0
        early.forEach { received(connection, it) }
    }

    fun received(
        connection: Any,
        envelope: Envelope,
    ) {
        if (!owns(connection)) return
        val query = envelope.inReplyTo?.let(queries::get) ?: return
        if (query.disposition != "pending") return
        trace(query, if (query.accepted == null) "early" else "received")
        if (query.accepted == null) {
            // A synchronous transport can deliver before send returns. Keep the decoder's byte bound.
            val size =
                envelope.payload
                    .toString()
                    .length
                    .toLong() * 4
            if (query.early.size >= 4096 || size + query.earlyBytes > 16 * 1024 * 1024) {
                close(connection)
                return
            }
            query.early += envelope
            query.earlyBytes += size.toInt()
            return
        }
        if (query.accepted != true) return
        if (query.generation != store.current(host, before.conversation).generation) {
            abandon(query)
            return
        }
        for (outcome in query.decoder.decode(envelope)) {
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> Unit
                is ThreadDecodeOutcome.Repair -> {
                    reject(query)
                }
                is ThreadDecodeOutcome.Complete ->
                    when (val reply = outcome.value) {
                        is ThreadQueryReply.Body, is ThreadQueryReply.Children -> Unit // Expansion never certifies history observation.
                        is ThreadQueryReply.Batch -> {
                            if (reply.checkpoint.epoch != expected.epoch) {
                                close(connection)
                                return
                            }
                            val applied =
                                store.applyBatch(
                                    host,
                                    before.conversation,
                                    reply.checkpoint.epoch,
                                    reply.checkpoint.version.toLong(),
                                    reply.items,
                                    query.generation,
                                )
                            if (applied !in setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored)) reject(query)
                        }
                        is ThreadQueryReply.Success -> {
                            if (reply.checkpoint.epoch != expected.epoch) {
                                close(connection)
                                return
                            }
                            val certified = store.certifyQuery(host, query.decoder.request, reply, query.generation)
                            val snapshot = store.current(host, before.conversation)
                            trace(query, "certification_${certified.name}")
                            if (certified == AuthoritativeThreadOutcome.Applied && !snapshot.repairNeeded && !snapshot.resetNeeded) {
                                awaitingCertification = false
                                query.disposition = "certified"
                                trace(query, "certified")
                                onCertified(query.decoder.request, reply, snapshot)
                            } else {
                                reject(query)
                            }
                        }
                        is ThreadQueryReply.Reset -> {
                            if (reply.checkpoint.epoch != expected.epoch && !followReplacementReset) {
                                close(connection)
                                return
                            }
                            query.disposition = "reset"
                            trace(query, "reset")
                            queries.values.filter { it !== query && it.disposition == "pending" }.forEach(::abandon)
                            route.reset(before.conversation, reply.checkpoint.epoch)
                            expected =
                                expected.copy(
                                    epoch = reply.checkpoint.epoch,
                                    generation =
                                        checkNotNull(before.generation) + store.current(host, before.conversation).generation,
                                )
                            awaitingCertification = true
                            onReset(query.decoder.request, reply)
                        }
                        is ThreadQueryReply.Failure -> {
                            reject(query)
                        }
                    }
            }
        }
    }

    fun owner(): ThreadEvidenceOwner? =
        expected.takeIf {
            !closed &&
                current() &&
                !awaitingCertification &&
                expected.epoch == store.current(host, before.conversation).epoch &&
                expected.generation == checkNotNull(before.generation) + store.current(host, before.conversation).generation &&
                queries.values.all { query ->
                    query.accepted != null &&
                        (query.accepted == false || query.disposition in setOf("certified", "reset"))
                }
        }

    fun receipts(): List<Receipt> =
        queries.values.filter { it.accepted == true }.map {
            Receipt(host, checkNotNull(connection), it.decoder.requestId, it.decoder.request, it.epoch, it.generation, it.disposition)
        }

    fun end() {
        connection?.let(::close)
    }

    fun close(connection: Any) {
        if (this.connection !== connection) return
        closed = true
        queries.values.filter { it.disposition == "pending" }.forEach(::abandon)
    }

    private fun reject(query: Query) {
        query.disposition = "failed"
        trace(query, "rejected")
        query.decoder.abandon()
        awaitingCertification = true
        store.requestRepair(host, before.conversation)
    }

    private fun abandon(query: Query) {
        query.disposition = "abandoned"
        query.decoder.abandon()
        query.early.clear()
        query.earlyBytes = 0
        trace(query, "abandoned")
    }

    private fun owns(connection: Any): Boolean = this.connection === connection && !closed && current()
}
