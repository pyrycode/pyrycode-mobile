package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.validThreadJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.util.Collections

/** Full saved facts, never a negotiated capability or current transient progress. */
@Serializable
data class SavedAuthoritativeThread(
    val epoch: String,
    @Serializable(with = OwnedThreadFactsSerializer::class) val items: List<JsonObject>,
    val appliedVersion: Long,
    val checkpoint: Long,
    val coverage: SavedThreadCoverage? = null,
    val incarnation: String = "",
    val retirements: Map<Long, Long> = emptyMap(),
) {
    @Transient private var ownedSnapshot: AuthoritativeThreadSnapshot? = null

    override fun toString(): String = "SavedAuthoritativeThread(content=***)"

    // Only a deeply owned, read-only decode may reuse validation. Generated copy() starts without this memo.
    internal fun ownedValidated(): SavedAuthoritativeThread {
        val start = System.nanoTime()
        val owned =
            copy(
                items =
                    if (items is OwnedThreadFacts) {
                        items
                    } else {
                        Collections.unmodifiableList(
                            items.map { frozenThreadJson(it) as JsonObject },
                        )
                    },
                retirements = Collections.unmodifiableMap(LinkedHashMap(retirements)),
            )
        val frozen = System.nanoTime()
        val validated = owned.decodeSnapshot(trace = true)
        val decoded = System.nanoTime()
        RelayLog.d { "event=authoritative_owned_validation freeze_ns=${frozen - start} validate_ns=${decoded - frozen}" }
        owned.ownedSnapshot = validated.copy(items = Collections.unmodifiableList(validated.items))
        return owned
    }

    internal fun toSnapshot(): AuthoritativeThreadSnapshot = ownedSnapshot ?: decodeSnapshot()

    private fun decodeSnapshot(trace: Boolean = false): AuthoritativeThreadSnapshot {
        require(epoch.isNotEmpty() && appliedVersion in 0..MAX_THREAD_NUMBER && checkpoint in 0..appliedVersion)
        require(incarnation.length <= 64)
        require(retirements.all { (id, revision) -> id in 1..MAX_THREAD_NUMBER && revision in id..appliedVersion })
        require(retirements.isEmpty() || (checkpoint == 0L && coverage == null))
        var treeNs = 0L
        var typedNs = 0L
        val decoded =
            items.map { raw ->
                val start = if (trace) System.nanoTime() else 0L
                require(validThreadJson(raw))
                val tree = if (trace) System.nanoTime() else 0L
                val decodedItem =
                    ThreadItemDto.decodeReplacement(raw).also { item ->
                        require(item.id > 0 && item.rev >= item.id && item.rev <= appliedVersion)
                    }
                if (trace) {
                    treeNs += tree - start
                    typedNs += System.nanoTime() - tree
                }
                decodedItem
            }
        val typed = if (trace) System.nanoTime() else 0L
        require(decoded.distinctBy { it.id }.size == decoded.size)
        require(decoded.none { it.id in retirements })
        val snapshot =
            AuthoritativeThreadSnapshot(
                epoch = epoch,
                items = decoded.sortedBy { it.order?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE },
                completedVersion = appliedVersion,
                checkpoint = checkpoint,
                repairNeeded = appliedVersion > checkpoint,
                coverage = coverage?.toDomain(),
                incarnation = incarnation,
                retirements = retirements,
            )
        val complete = if (trace) System.nanoTime() else 0L
        if (trace) RelayLog.d { "event=authoritative_validation_phases tree_ns=$treeNs typed_ns=$typedNs rest_ns=${complete - typed}" }
        return snapshot
    }

    internal fun retained(): SavedAuthoritativeThread {
        val snapshot = toSnapshot()
        val trimmed = snapshot.items.size > MAX_CACHED_THREAD_ROWS
        return copy(
            items = snapshot.items.takeLast(MAX_CACHED_THREAD_ROWS).map { it.raw },
            checkpoint = if (trimmed) 0 else checkpoint,
            coverage = if (trimmed) null else coverage,
        )
    }
}

/** Only the serializer owns the builder; AbstractList's mutation methods reject changes. */
private class OwnedThreadFacts(
    private val values: List<JsonObject>,
) : java.util.AbstractList<JsonObject>() {
    override val size: Int get() = values.size

    override fun get(index: Int): JsonObject = values[index]
}

/** Freeze each complete decoded fact before the streaming decoder starts the next one. */
private object OwnedThreadFactsSerializer : KSerializer<List<JsonObject>> {
    private val delegate = ListSerializer(JsonObject.serializer())
    private val exactEncoding = ListSerializer(ExactSavedFactSerializer)
    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<JsonObject>,
    ) = exactEncoding.serialize(encoder, value)

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): List<JsonObject> {
        val input = decoder.beginStructure(descriptor)
        val owned = ArrayList<JsonObject>()

        fun read(index: Int) {
            val raw = input.decodeSerializableElement(descriptor, index, JsonObject.serializer())
            owned.add(frozenThreadJson(raw) as JsonObject)
        }
        if (input.decodeSequentially()) {
            val size = input.decodeCollectionSize(descriptor)
            require(size >= 0)
            repeat(size, ::read)
        } else {
            while (true) {
                val index = input.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                require(index == owned.size)
                read(index)
            }
        }
        input.endStructure(descriptor)
        return OwnedThreadFacts(owned)
    }
}

/** Built-in numeric encoding coerces opaque tokens through Long/Double; preserve their validated spelling. */
private object ExactSavedFactSerializer : KSerializer<JsonObject> {
    private val delegate = JsonObject.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: JsonObject,
    ) {
        require(validThreadJson(value))
        delegate.serialize(encoder, frozenThreadJson(value, preserveNumberLexemes = true) as JsonObject)
    }

    override fun deserialize(decoder: Decoder): JsonObject = delegate.deserialize(decoder)
}

/** Explicit stack preserves arbitrarily nested extension containers without adding recursive traversal. */
@OptIn(ExperimentalSerializationApi::class)
private fun frozenThreadJson(
    root: JsonElement,
    preserveNumberLexemes: Boolean = false,
): JsonElement {
    fun primitive(value: JsonElement): JsonElement {
        if (!preserveNumberLexemes || value !is JsonPrimitive || value.isString) return value
        return when (value.content) {
            "null", "true", "false" -> value
            else -> {
                require(validThreadJson(value))
                JsonUnquotedLiteral(value.content)
            }
        }
    }

    class Frame(
        val source: JsonElement,
    ) {
        val entries = (source as? JsonObject)?.entries?.iterator()
        val values = (source as? JsonArray)?.iterator()
        val fields = if (entries != null) LinkedHashMap<String, JsonElement>() else null
        val elements = if (values != null) ArrayList<JsonElement>() else null
        var key: String? = null

        fun next(): JsonElement? =
            if (entries != null) {
                if (entries.hasNext()) {
                    entries.next().let {
                        key = it.key
                        it.value
                    }
                } else {
                    null
                }
            } else {
                if (values?.hasNext() == true) values.next() else null
            }

        fun accept(value: JsonElement) {
            if (fields != null) fields[requireNotNull(key)] = value else requireNotNull(elements).add(value)
        }

        fun finish(): JsonElement =
            fields?.let { JsonObject(Collections.unmodifiableMap(it)) }
                ?: JsonArray(Collections.unmodifiableList(requireNotNull(elements)))
    }
    if (root !is JsonObject && root !is JsonArray) return primitive(root)
    val stack = ArrayDeque<Frame>()
    stack.add(Frame(root))
    while (true) {
        val frame = stack.last()
        val next = frame.next()
        if (next == null) {
            val complete = stack.removeLast().finish()
            if (stack.isEmpty()) return complete
            stack.last().accept(complete)
        } else if (next is JsonObject || next is JsonArray) {
            stack.add(Frame(next))
        } else {
            frame.accept(primitive(next))
        }
    }
}

@Serializable
data class SavedThreadCoverage(
    val lowerOrder: Long,
    val upperOrder: Long,
    val continuation: Long,
    val olderExists: Boolean,
) {
    internal fun toDomain(): AuthoritativeThreadCoverage {
        require(lowerOrder in 0..MAX_THREAD_NUMBER && upperOrder in lowerOrder..MAX_THREAD_NUMBER && continuation == lowerOrder)
        return AuthoritativeThreadCoverage(lowerOrder.toULong(), upperOrder.toULong(), continuation.toULong(), olderExists)
    }

    override fun toString(): String = "SavedThreadCoverage(content=***)"
}

internal fun AuthoritativeThreadSnapshot.toSavedAuthoritativeThread(): SavedAuthoritativeThread =
    SavedAuthoritativeThread(
        epoch = requireNotNull(epoch),
        items = items.takeLast(MAX_CACHED_THREAD_ROWS).map { it.raw },
        appliedVersion = completedVersion,
        incarnation = incarnation,
        retirements = retirements,
        checkpoint = if (items.size > MAX_CACHED_THREAD_ROWS) 0 else checkpoint,
        coverage =
            coverage?.takeIf { items.size <= MAX_CACHED_THREAD_ROWS }?.let {
                SavedThreadCoverage(
                    it.lowerOrder.toLong(),
                    it.upperOrder.toLong(),
                    it.continuation.toLong(),
                    it.olderExists,
                )
            },
    )

private const val MAX_THREAD_NUMBER = 9007199254740991L
