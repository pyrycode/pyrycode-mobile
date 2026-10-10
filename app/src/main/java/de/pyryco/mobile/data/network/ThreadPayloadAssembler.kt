package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Only Complete exposes a decoded fact; every other outcome is content-free. */
internal sealed interface ThreadDecodeOutcome<out T> {
    class Complete<T>(
        val value: T,
    ) : ThreadDecodeOutcome<T> {
        override fun toString(): String = "ThreadDecodeOutcome.Complete(content=***)"
    }

    class Pending(
        val conversationId: String,
    ) : ThreadDecodeOutcome<Nothing> {
        override fun toString(): String = "ThreadDecodeOutcome.Pending"
    }

    class Repair(
        val conversationId: String?,
        val code: String,
    ) : ThreadDecodeOutcome<Nothing> {
        override fun toString(): String = "ThreadDecodeOutcome.Repair(code=$code)"
    }
}

/**
 * Connection-confined byte assembly reusable by future full-item/page decoders.
 * Create a fresh instance per host/connection generation. Calls are synchronous and must be serialized.
 * The owner must call [expire] while idle, [abandon] on socket close, and [discard] on fresh catch-up.
 * No item/revision/version is applied here. A SHA-256 digest is integrity, never authorization.
 */
internal class ThreadPayloadAssembler(
    private val hostId: String,
    private val connectionId: String,
    private val maxPayloadBytes: Int = 8 * 1024 * 1024,
    private val maxBufferedBytes: Int = 16 * 1024 * 1024,
    private val maxAssemblies: Int = 32,
    private val maxParts: Int = 4096,
    private val lifetimeMillis: Long = 30000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1000000 },
) {
    private class Assembly(
        val type: String,
        val metadata: JsonObject,
        val updateId: String,
        val total: Int,
        val started: Long,
        val metadataBytes: Int,
        val bytes: ByteArrayOutputStream = ByteArrayOutputStream(),
        var nextIndex: Long = 0,
    )

    private val pending = mutableMapOf<String, Assembly>()
    private var bufferedBytes = 0L
    private var abandoned = false

    init {
        require(maxPayloadBytes > 0 && maxBufferedBytes > 0 && maxAssemblies > 0 && maxParts > 0 && lifetimeMillis > 0)
    }

    override fun toString(): String = "ThreadPayloadAssembler"

    fun <T> accept(
        type: String,
        payload: JsonObject,
        metadataFor: (JsonObject) -> JsonObject?,
        decode: (JsonObject) -> T?,
        metadataOf: (T) -> JsonObject,
    ): ThreadDecodeOutcome<T> {
        val conversation = (payload["conversation_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (abandoned) return reject(conversation, "abandoned")
        val originalOwner = ownerOf(payload)
        if (originalOwner != null && originalOwner != conversation) return reject(originalOwner, "metadata")
        val ordinary = "continuation" !in payload
        if (ordinary) {
            if (conversation in pending) return reject(conversation, "sequence")
            if (boundedThreadJsonSize(payload, maxPayloadBytes) == null) {
                return reject(conversation, "capacity")
            }
        }
        val metadata = metadataFor(payload) ?: return reject(conversation, "malformed")
        val owner = metadata.threadString("conversation_id")
        if (ordinary) {
            val value = decode(payload) ?: return reject(conversation, "malformed")
            RelayLog.i { "event=thread_decode code=complete" }
            return ThreadDecodeOutcome.Complete(value)
        }
        val continuation = payload["continuation"] as? JsonObject ?: return reject(conversation, "malformed")
        val part =
            try {
                val updateId = continuation.threadString("update_id")
                require(updateId.length == 64 && updateId.all { it in '0'..'9' || it in 'a'..'f' })
                val index = continuation.threadInteger("index", Long.MAX_VALUE)
                val offset = continuation.threadInteger("offset", Long.MAX_VALUE)
                val total = continuation.threadInteger("total_bytes", Long.MAX_VALUE)
                val final = continuation.threadBoolean("final")
                val data = payload.threadString("data")
                require(data.isNotEmpty() && validUtf16(data))
                Part(updateId, index, offset, total, final, data)
            } catch (_: IllegalArgumentException) {
                return reject(conversation, "malformed")
            }
        if (part.total !in 1..maxPayloadBytes.toLong() || part.data.length > maxPayloadBytes) return reject(conversation, "capacity")
        var assembly = pending[owner]
        if (assembly == null) {
            if (part.index != 0L || part.offset != 0L) return reject(conversation, "sequence")
            val metadataSize = boundedThreadJsonSize(metadata, maxPayloadBytes) ?: return reject(conversation, "capacity")
            if (pending.size >= maxAssemblies ||
                metadataSize > maxPayloadBytes ||
                bufferedBytes + metadataSize > maxBufferedBytes
            ) {
                return reject(conversation, "capacity")
            }
            assembly = Assembly(type, metadata, part.updateId, part.total.toInt(), nowMillis(), metadataSize)
            pending[owner] = assembly
            bufferedBytes += metadataSize
            RelayLog.i { "event=thread_assembly code=start" }
        }
        if (assembly.type != type ||
            assembly.metadata != metadata ||
            assembly.updateId != part.updateId ||
            assembly.total.toLong() != part.total
        ) {
            return reject(conversation, "metadata")
        }
        if (part.index != assembly.nextIndex || part.offset != assembly.bytes.size().toLong()) return reject(conversation, "sequence")
        if (assembly.nextIndex >= maxParts) return reject(conversation, "capacity")
        val data = part.data.toByteArray(Charsets.UTF_8)
        if (data.size.toLong() > assembly.total.toLong() - assembly.bytes.size()) return reject(conversation, "length")
        if (assembly.metadataBytes.toLong() + assembly.bytes.size() + data.size > maxPayloadBytes ||
            bufferedBytes + data.size > maxBufferedBytes
        ) {
            return reject(conversation, "capacity")
        }
        assembly.bytes.write(data)
        bufferedBytes += data.size
        assembly.nextIndex++
        if (!part.final) return ThreadDecodeOutcome.Pending(owner)
        if (assembly.bytes.size() != assembly.total) return reject(conversation, "length")
        val bytes = assembly.bytes.toByteArray()
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .apply {
                    update(type.toByteArray(Charsets.UTF_8))
                    update(0.toByte())
                }.digest(bytes)
                .toHexString()
        if (digest != assembly.updateId) return reject(conversation, "integrity")
        val serialized = bytes.toString(Charsets.UTF_8)
        val logical = parseThreadJson(serialized)
        val value = logical?.let(decode) ?: return reject(conversation, "malformed")
        if (metadataOf(value) != assembly.metadata) return reject(conversation, "metadata")
        discard(owner)
        RelayLog.i { "event=thread_assembly code=complete bytes=${bytes.size}" }
        return ThreadDecodeOutcome.Complete(value)
    }

    fun expire(): List<ThreadDecodeOutcome.Repair> {
        val now = nowMillis()
        val owners = pending.filterValues { now - it.started >= lifetimeMillis }.keys.toList()
        return owners.map { reject(it, "timeout") }
    }

    fun abandon(): List<ThreadDecodeOutcome.Repair> {
        abandoned = true
        return pending.keys.toList().map { reject(it, "abandoned") }
    }

    fun discard(conversationId: String) {
        val removed = pending.remove(conversationId) ?: return
        bufferedBytes -= removed.metadataBytes + removed.bytes.size().toLong()
    }

    /** A digest names the original payload, so changed routing must repair its retained owner. */
    fun ownerOf(payload: JsonObject?): String? {
        val continuation = payload?.get("continuation") as? JsonObject ?: return null
        val updateId = (continuation["update_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return pending.entries.firstOrNull { it.value.updateId == updateId }?.key
    }

    fun reject(
        conversationId: String?,
        code: String,
    ): ThreadDecodeOutcome.Repair {
        conversationId?.let(::discard)
        RelayLog.w { "event=thread_decode code=$code" }
        return ThreadDecodeOutcome.Repair(conversationId, code)
    }

    private class Part(
        val updateId: String,
        val index: Long,
        val offset: Long,
        val total: Long,
        val final: Boolean,
        val data: String,
    )
}

/** Java UTF-8 encoding replaces lone surrogates; reject them so digest and offset checks stay exact. */
private fun validUtf16(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        if (char.isHighSurrogate()) {
            if (index == text.length || !text[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) {
            return false
        }
    }
    return true
}
