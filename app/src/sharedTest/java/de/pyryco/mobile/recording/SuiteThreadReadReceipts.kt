package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ThreadDecodeOutcome
import de.pyryco.mobile.data.network.ThreadQueryDecoder
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import de.pyryco.mobile.data.network.ThreadUpdateDecoder
import de.pyryco.mobile.data.network.threadInteger
import de.pyryco.mobile.data.network.threadString
import de.pyryco.mobile.data.network.threadUnsigned
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Delivered authoritative facts, independent of the app's mandatory viewport qualification. */
internal class SuiteThreadReadReceipts(
    source: List<SuiteStep>,
) {
    private val owners =
        source
            .filter {
                it.direction == "open" && it.owner.startsWith("phone/") && it.capabilities.containsAll(setOf("interactive", "thread"))
            }.map { it.owner }
            .toSet()
    private val store = AuthoritativeThreadStore()
    private val routes = mutableMapOf<String, AuthoritativeThreadRoute>()
    private val watermarks = mutableMapOf<Pair<String, String>, MutableSet<ULong>>()

    private data class Query(
        val decoder: ThreadQueryDecoder,
        val generation: Long,
    )

    private val queries = mutableMapOf<Pair<String, Long>, Query>()
    private val certified = mutableMapOf<Pair<String, String>, Pair<Long, Long>>()

    private class ReadEvidence(
        val generation: Long,
        val targets: MutableSet<ULong> = mutableSetOf(),
    )

    private val eligible = mutableMapOf<Pair<String, String>, ReadEvidence>()

    private fun family(owner: String) = owner.substringBeforeLast('/')

    private fun route(owner: String) =
        routes.getOrPut(owner) {
            AuthoritativeThreadRoute(family(owner), store, ThreadUpdateDecoder(family(owner), owner))
        }

    fun requested(
        owner: String,
        frame: Envelope,
    ) {
        if (owner !in owners || frame.type !in setOf("thread_catch_up", "thread_page")) return
        val payload = frame.payload as? JsonObject ?: return
        val request =
            try {
                val conversation = payload.threadString("conversation_id")
                val limit = if ("limit" in payload) payload.threadInteger("limit", Long.MAX_VALUE) else null
                when (frame.type) {
                    "thread_catch_up" ->
                        ThreadQueryRequest.CatchUp(
                            conversation,
                            payload.threadString("epoch"),
                            payload.threadUnsigned("version"),
                            limit,
                        )
                    else -> ThreadQueryRequest.Page(conversation, payload.threadUnsigned("before_order"), limit)
                }
            } catch (_: IllegalArgumentException) {
                return
            }
        queries[owner to frame.id] =
            Query(
                ThreadQueryDecoder(family(owner), owner, frame.id, request),
                store.current(family(owner), request.conversationId).generation,
            )
    }

    fun delivered(
        owner: String,
        frame: Envelope,
    ) {
        if (owner !in owners) return
        rememberEligible(owner)
        val payload = frame.payload as? JsonObject ?: return
        val summaries =
            when (frame.type) {
                "conversation_created", "conversation_updated" -> listOf(payload)
                "conversations" -> (payload["conversations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                else -> emptyList()
            }
        summaries.forEach { row ->
            try {
                watermarks.getOrPut(family(owner) to row.threadString("id")) { mutableSetOf() } += row.threadUnsigned("last_shown_version")
            } catch (_: IllegalArgumentException) {
                // Missing metadata grants no read evidence.
            }
        }
        val key = frame.inReplyTo?.let { owner to it }
        val query = key?.let(queries::get)
        if (query != null && frame.type in setOf("thread_items", "thread_reset", "error")) {
            val request = query.decoder.request

            fun fail() {
                store.requestRepair(family(owner), request.conversationId)
                queries.remove(owner to query.decoder.requestId)
            }
            for (outcome in query.decoder.decode(frame)) {
                when (outcome) {
                    is ThreadDecodeOutcome.Pending -> Unit
                    is ThreadDecodeOutcome.Repair -> fail()
                    is ThreadDecodeOutcome.Complete ->
                        when (val reply = outcome.value) {
                            // Expansion grants no read receipt or checkpoint.
                            is ThreadQueryReply.Body, is ThreadQueryReply.Children -> Unit
                            is ThreadQueryReply.Batch -> {
                                if (reply.checkpoint.version > 9007199254740991uL) {
                                    fail()
                                    continue
                                }
                                val applied =
                                    store.applyBatch(
                                        family(owner),
                                        request.conversationId,
                                        reply.checkpoint.epoch,
                                        reply.checkpoint.version.toLong(),
                                        reply.items,
                                        query.generation,
                                    )
                                if (applied !in setOf(AuthoritativeThreadOutcome.Applied, AuthoritativeThreadOutcome.Ignored)) fail()
                            }
                            is ThreadQueryReply.Success -> {
                                val applied = store.certifyQuery(family(owner), request, reply, query.generation)
                                if (applied == AuthoritativeThreadOutcome.Applied && request is ThreadQueryRequest.CatchUp) {
                                    val snapshot = store.current(family(owner), request.conversationId)
                                    certified[owner to request.conversationId] = snapshot.generation to snapshot.checkpoint
                                }
                                queries.remove(owner to query.decoder.requestId)
                            }
                            is ThreadQueryReply.Reset -> {
                                route(owner).reset(request.conversationId, reply.checkpoint.epoch)
                                queries.remove(owner to query.decoder.requestId)
                            }
                            is ThreadQueryReply.Failure -> fail()
                        }
                }
            }
        } else if (frame.type in setOf("thread_item_added", "thread_item_changed", "thread_text_append")) {
            route(owner).accept(frame)
        }
        rememberEligible(owner)
    }

    /** A read can be in flight when a later live fact overtakes its qualifying window. */
    private fun rememberEligible(owner: String) {
        for ((key, targets) in watermarks) {
            if (key.first != family(owner)) continue
            val conversation = key.second
            val snapshot = store.current(family(owner), conversation)
            if (snapshot.repairNeeded || snapshot.resetNeeded) {
                eligible.keys.removeAll { family(it.first) == family(owner) && it.second == conversation }
                continue
            }
            val certificate = certified[owner to conversation] ?: continue
            if (certificate.first != snapshot.generation) continue
            val presentation = projectAuthoritativeThread(family(owner), conversation, snapshot)
            val checkpoint = presentation.certifiedCheckpoint ?: continue
            if (presentation.shownRowRevisions.values.any { it > checkpoint }) continue
            val admitted =
                targets.filter { target ->
                    target <= checkpoint.toULong() &&
                        target <= certificate.second.toULong() &&
                        presentation.shownRowRevisions.values.any { it.toULong() >= target }
                }
            if (admitted.isEmpty()) continue
            val evidence =
                eligible[owner to conversation]?.takeIf { it.generation == snapshot.generation }
                    ?: ReadEvidence(snapshot.generation).also { eligible[owner to conversation] = it }
            evidence.targets += admitted
        }
    }

    fun handles(owner: String): Boolean = owner in owners

    fun ceiling(
        owner: String,
        conversation: String,
    ): ULong? = if (owner in owners) watermarks[family(owner) to conversation]?.maxOrNull() else null

    fun permits(
        owner: String,
        conversation: String,
        target: ULong,
        retained: Boolean,
    ): Boolean {
        if (owner !in owners || target !in watermarks[family(owner) to conversation].orEmpty()) return false
        val snapshot = store.current(family(owner), conversation)
        if (snapshot.repairNeeded || snapshot.resetNeeded) return false
        return eligible.any { (key, value) ->
            key.second == conversation &&
                (key.first == owner || retained && family(key.first) == family(owner)) &&
                value.generation == snapshot.generation &&
                target in value.targets
        }
    }
}
