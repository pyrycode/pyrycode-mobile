package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.validThreadJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** Full saved facts, never a negotiated capability or current transient progress. */
@Serializable
data class SavedAuthoritativeThread(
    val epoch: String,
    val items: List<JsonObject>,
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
                items = Collections.unmodifiableList(items.map { frozenThreadJson(it) as JsonObject }),
                retirements = Collections.unmodifiableMap(LinkedHashMap(retirements)),
            )
        val frozen = System.nanoTime()
        val validated = owned.decodeSnapshot()
        val decoded = System.nanoTime()
        RelayLog.d { "event=authoritative_owned_validation freeze_ns=${frozen - start} validate_ns=${decoded - frozen}" }
        owned.ownedSnapshot = validated.copy(items = Collections.unmodifiableList(validated.items))
        return owned
    }

    internal fun toSnapshot(): AuthoritativeThreadSnapshot = ownedSnapshot ?: decodeSnapshot()

    private fun decodeSnapshot(): AuthoritativeThreadSnapshot {
        require(epoch.isNotEmpty() && appliedVersion in 0..MAX_THREAD_NUMBER && checkpoint in 0..appliedVersion)
        require(incarnation.length <= 64)
        require(retirements.all { (id, revision) -> id in 1..MAX_THREAD_NUMBER && revision in id..appliedVersion })
        require(retirements.isEmpty() || (checkpoint == 0L && coverage == null))
        val decoded =
            items.map { raw ->
                require(validThreadJson(raw))
                ThreadItemDto.decodeReplacement(raw).also { item ->
                    require(item.id > 0 && item.rev >= item.id && item.rev <= appliedVersion)
                }
            }
        require(decoded.distinctBy { it.id }.size == decoded.size)
        require(decoded.none { it.id in retirements })
        return AuthoritativeThreadSnapshot(
            epoch = epoch,
            items = decoded.sortedBy { it.order?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE },
            completedVersion = appliedVersion,
            checkpoint = checkpoint,
            repairNeeded = appliedVersion > checkpoint,
            coverage = coverage?.toDomain(),
            incarnation = incarnation,
            retirements = retirements,
        )
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

/** Explicit stack preserves arbitrarily nested extension containers without adding recursive traversal. */
private fun frozenThreadJson(root: JsonElement): JsonElement {
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
    if (root !is JsonObject && root !is JsonArray) return root
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
            frame.accept(next)
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
