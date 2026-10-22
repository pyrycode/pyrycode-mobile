package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One request on one host/connection generation. Dispatch only that request's frames here, serialize
 * calls, tick [expire] while idle, and [abandon] on teardown. Never reuse an instance for a retry.
 * Outcomes are attributed by these immutable owner fields, even errors without conversation_id.
 */
internal class ThreadQueryDecoder(
    val hostId: String,
    val connectionId: String,
    val requestId: Long,
    val request: ThreadQueryRequest,
    private val maxPayloadBytes: Int = 8 * 1024 * 1024,
    maxBufferedBytes: Int = 16 * 1024 * 1024,
    maxAssemblies: Int = 32,
    maxParts: Int = 4096,
    lifetimeMillis: Long = 30000,
    nowMillis: () -> Long = { System.nanoTime() / 1000000 },
    private val draining: Boolean = false,
) {
    private val assembler =
        ThreadPayloadAssembler(hostId, connectionId, maxPayloadBytes, maxBufferedBytes, maxAssemblies, maxParts, lifetimeMillis, nowMillis)
    private var routing: JsonObject? = null
    private var checkpoint: ThreadQueryReply.Checkpoint? = null
    private var pending = false
    private var terminal = false
    private var bodyData = false

    override fun toString(): String = "ThreadQueryDecoder(redacted)"

    fun decode(envelope: Envelope): List<ThreadDecodeOutcome<ThreadQueryReply>> {
        val expired = expire()
        if (expired.isNotEmpty()) return expired
        if (terminal) return listOf(reject("terminal"))
        if (envelope.inReplyTo != requestId) return listOf(reject("attribution"))
        val raw = envelope.payload as? JsonObject ?: return listOf(reject("malformed"))
        val route = envelopeRouting(envelope) ?: return listOf(reject("attribution"))
        if (routing != null && routing != route) return listOf(reject("attribution"))
        if (boundedThreadJsonSize(route, maxPayloadBytes) == null) return listOf(reject("capacity"))
        routing = route
        val fragment = envelope.type in setOf("thread_items", "thread_tool_body") && raw["continuation"] is JsonObject
        if (!fragment && boundedThreadJsonSize(JsonObject(mapOf("payload" to raw, "routing" to route)), maxPayloadBytes) == null) {
            return listOf(reject("capacity"))
        }
        val current =
            try {
                if (envelope.type == "error") null else ThreadQueryReply.Checkpoint(raw)
            } catch (_: IllegalArgumentException) {
                return listOf(reject("malformed"))
            }
        if ((current != null && current.conversationId != request.conversationId) ||
            (envelope.type == "error" && "conversation_id" in raw && raw["conversation_id"] != JsonPrimitive(request.conversationId))
        ) {
            return listOf(reject("attribution"))
        }
        if (current != null && envelope.type != "thread_reset") {
            val captured = checkpoint
            val initialCatchUp = request is ThreadQueryRequest.CatchUp && request.version == 0uL && request.epoch.isEmpty()
            if ((request is ThreadQueryRequest.CatchUp && !initialCatchUp && current.epoch != request.epoch) ||
                (captured != null && (captured.epoch != current.epoch || captured.version != current.version))
            ) {
                return listOf(reject("attribution"))
            }
            checkpoint = current
        }
        if (request is ThreadQueryRequest.Body && envelope.type != "error") {
            return listOf(decodeBody(envelope, raw, route, fragment))
        }
        if (envelope.type != "thread_items") {
            val value = decodeThreadQueryReply(envelope.type, raw) ?: return listOf(reject("malformed"))
            if (value !is ThreadQueryReply.Reset && value !is ThreadQueryReply.Failure) return listOf(reject("malformed"))
            finish()
            if (value is ThreadQueryReply.Reset) {
                RelayLog.i { "event=thread_query code=reset" }
            } else {
                RelayLog.i { "event=thread_query code=failure" }
            }
            return listOf(ThreadDecodeOutcome.Complete(value))
        }
        if (!fragment && raw["final"] == JsonPrimitive(true)) {
            if (pending) return listOf(reject("sequence"))
            val success = decodeThreadQueryReply(envelope.type, raw) as? ThreadQueryReply.Success ?: return listOf(reject("coverage"))
            val validCoverage =
                when (val coverage = success.coverage) {
                    is ThreadQueryReply.Coverage.CatchUp ->
                        request is ThreadQueryRequest.CatchUp &&
                            request.version != 0uL &&
                            coverage.fromVersion == request.version
                    is ThreadQueryReply.Coverage.Window ->
                        request is ThreadQueryRequest.Page ||
                            (request is ThreadQueryRequest.CatchUp && request.version == 0uL)
                }
            if (!validCoverage) return listOf(reject("coverage"))
            finish()
            RelayLog.i { "event=thread_query code=success" }
            return listOf(ThreadDecodeOutcome.Complete(success))
        }
        val outcome =
            assembler.accept(
                envelope.type,
                raw,
                { assemblyMetadata(it, route, fragment) },
                { decodeThreadQueryReply("thread_items", it) as? ThreadQueryReply.Batch },
                { metadata(it.checkpoint, route, it.items.singleOrNull()?.let { item -> item.id to item.rev }) },
            )
        return listOf(
            when (outcome) {
                is ThreadDecodeOutcome.Pending -> {
                    pending = true
                    outcome
                }
                is ThreadDecodeOutcome.Complete -> {
                    pending = false
                    outcome
                }
                is ThreadDecodeOutcome.Repair -> {
                    finish()
                    outcome
                }
            },
        )
    }

    fun expire(): List<ThreadDecodeOutcome.Repair> {
        if (terminal) return emptyList()
        val result = assembler.expire()
        if (result.isNotEmpty()) finish()
        return result
    }

    private fun decodeBody(
        envelope: Envelope,
        raw: JsonObject,
        route: JsonObject,
        fragment: Boolean,
    ): ThreadDecodeOutcome<ThreadQueryReply> {
        val expected = request as ThreadQueryRequest.Body

        fun metadata(fields: JsonObject): JsonObject? =
            try {
                require(fields.threadString("epoch") == expected.epoch)
                require(fields.threadUnsigned("item_id") == expected.itemId && fields.threadUnsigned("rev") == expected.expectedRev)
                require(fields.threadString("kind") == expected.kind)
                require(fields.threadString("conversation_id") == expected.conversationId)
                JsonObject(
                    fields.filterKeys { it in setOf("conversation_id", "epoch", "version", "item_id", "rev", "kind") } +
                        ("envelope_routing" to route),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        if (envelope.type != "thread_tool_body" || metadata(raw) == null) return reject("attribution")
        if (!fragment && raw["final"] == JsonPrimitive(true)) {
            if (pending || !bodyData && !draining) return reject("sequence")
            val marker = decodeThreadQueryReply(envelope.type, raw) as? ThreadQueryReply.Body ?: return reject("malformed")
            finish()
            return ThreadDecodeOutcome.Complete(marker)
        }
        if (bodyData) return reject("sequence")
        val outcome =
            assembler.accept(
                envelope.type,
                raw,
                { fields ->
                    if ("continuation" in fields &&
                        setOf("content", "final", "items", "base_rev").any { it in fields }
                    ) {
                        null
                    } else {
                        metadata(fields)
                    }
                },
                {
                    (decodeThreadQueryReply(envelope.type, it) as? ThreadQueryReply.Body)?.takeIf { body ->
                        !body.final &&
                            metadata(body.raw) != null
                    }
                },
                { requireNotNull(metadata(it.raw)) },
            )
        when (outcome) {
            is ThreadDecodeOutcome.Pending -> pending = true
            is ThreadDecodeOutcome.Complete -> {
                pending = false
                bodyData = true
            }
            is ThreadDecodeOutcome.Repair -> finish()
        }
        return outcome
    }

    /** Explicit abandonment also retires requests with no pending fragments. */
    fun abandon(): List<ThreadDecodeOutcome.Repair> {
        val result = assembler.abandon()
        finish()
        return result
    }

    /** A discarded body still owns its captured routing/version until its remote slot drains. */
    fun drain(envelope: Envelope): List<ThreadDecodeOutcome<ThreadQueryReply>> {
        val reader = ThreadQueryDecoder(hostId, connectionId, requestId, request, draining = true)
        if (request is ThreadQueryRequest.Body) {
            reader.routing = routing
            reader.checkpoint = checkpoint
        }
        return reader.decode(envelope)
    }

    private fun finish() {
        terminal = true
        pending = false
        assembler.discard(request.conversationId)
        if (request !is ThreadQueryRequest.Body) {
            routing = null
            checkpoint = null
        }
    }

    private fun reject(code: String): ThreadDecodeOutcome.Repair {
        finish()
        return assembler.reject(request.conversationId, code)
    }

    private fun envelopeRouting(envelope: Envelope): JsonObject? {
        val conversation = envelope.conversationId
        if (conversation != null && (conversation !is JsonPrimitive || !conversation.isString)) return null
        if (!envelope.sessionProvenance.usable) return null
        if (envelope.sessionStateCleared != JsonPrimitive(false) && envelope.sessionStateCleared != JsonPrimitive(true)) return null
        return JsonObject(
            buildMap {
                conversation?.let { put("conversation_id", it) }
                envelope.sessionProvenance.raw?.let { put("session_id", it) }
                put("session_state_cleared", envelope.sessionStateCleared)
            },
        )
    }

    /** Include retained envelope metadata in the assembler's combined byte charge. */
    private fun assemblyMetadata(
        raw: JsonObject,
        route: JsonObject,
        fragment: Boolean,
    ): JsonObject? =
        try {
            val current = ThreadQueryReply.Checkpoint(raw)
            val identity =
                if (fragment) {
                    require((threadQueryCoverageKeys - "continuation" + setOf("items", "final", "base_rev")).none { it in raw })
                    raw.threadInteger("item_id") to raw.threadInteger("rev")
                } else {
                    null
                }
            metadata(current, route, identity)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun metadata(
        current: ThreadQueryReply.Checkpoint,
        route: JsonObject,
        identity: Pair<Long, Long>?,
    ): JsonObject =
        JsonObject(
            buildMap {
                put("conversation_id", JsonPrimitive(current.conversationId))
                put("epoch", JsonPrimitive(current.epoch))
                put("version", threadUnsignedJson(current.version))
                put("envelope_routing", route)
                identity?.let {
                    put("item_id", JsonPrimitive(it.first))
                    put("rev", JsonPrimitive(it.second))
                }
            },
        )
}
