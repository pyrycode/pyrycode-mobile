package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Supplies complete live-update DTOs; no capability advertisement, repository dispatch or application. */
internal class ThreadUpdateDecoder(
    val hostId: String,
    val connectionId: String,
    maxPayloadBytes: Int = 8 * 1024 * 1024,
    maxBufferedBytes: Int = 16 * 1024 * 1024,
    maxAssemblies: Int = 32,
    maxParts: Int = 4096,
    lifetimeMillis: Long = 30000,
    nowMillis: () -> Long = { System.nanoTime() / 1000000 },
) {
    private val assembler =
        ThreadPayloadAssembler(hostId, connectionId, maxPayloadBytes, maxBufferedBytes, maxAssemblies, maxParts, lifetimeMillis, nowMillis)

    override fun toString(): String = "ThreadUpdateDecoder"

    fun decode(envelope: Envelope): List<ThreadDecodeOutcome<ThreadUpdateDto>> {
        val expired = assembler.expire()
        val raw = envelope.payload as? JsonObject
        val conversation = (raw?.get("conversation_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val metadata = raw?.let { metadata(envelope.type, it) }
        if (raw == null || metadata == null) return expired + assembler.reject(assembler.ownerOf(raw) ?: conversation, "malformed")
        return expired + assembler.accept(envelope.type, raw, metadata, { ThreadUpdateDto.decode(envelope.type, it) }, { it.metadata() })
    }

    fun expire(): List<ThreadDecodeOutcome.Repair> = assembler.expire()

    fun abandon(): List<ThreadDecodeOutcome.Repair> = assembler.abandon()

    fun discard(conversationId: String) = assembler.discard(conversationId)

    private fun metadata(
        type: String,
        raw: JsonObject,
    ): JsonObject? =
        try {
            if ("continuation" !in raw) {
                ThreadUpdateDto.decode(type, raw)?.metadata()
            } else {
                require(type in setOf(TYPE_THREAD_ITEM_ADDED, TYPE_THREAD_ITEM_CHANGED, TYPE_THREAD_TEXT_APPEND))
                JsonObject(
                    buildMap {
                        put("conversation_id", JsonPrimitive(raw.threadString("conversation_id")))
                        put("epoch", JsonPrimitive(raw.threadString("epoch")))
                        for (key in listOf("version", "item_id", "rev")) put(key, JsonPrimitive(raw.threadInteger(key)))
                        if (type == TYPE_THREAD_ITEM_ADDED) {
                            require("base_rev" !in raw)
                        } else {
                            put("base_rev", JsonPrimitive(raw.threadInteger("base_rev")))
                        }
                    },
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
}
