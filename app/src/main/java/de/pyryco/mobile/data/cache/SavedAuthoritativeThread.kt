package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.validThreadJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadCoverage
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Full saved facts, never a negotiated capability or current transient progress. */
@Serializable
data class SavedAuthoritativeThread(
    val epoch: String,
    val items: List<JsonObject>,
    val appliedVersion: Long,
    val checkpoint: Long,
    val coverage: SavedThreadCoverage? = null,
) {
    override fun toString(): String = "SavedAuthoritativeThread(content=***)"

    internal fun toSnapshot(): AuthoritativeThreadSnapshot {
        require(epoch.isNotEmpty() && appliedVersion in 0..MAX_THREAD_NUMBER && checkpoint in 0..appliedVersion)
        val decoded =
            items.map { raw ->
                require(validThreadJson(raw))
                ThreadItemDto.decodeReplacement(raw).also { item ->
                    require(item.id > 0 && item.rev >= item.id && item.rev <= appliedVersion)
                }
            }
        require(decoded.distinctBy { it.id }.size == decoded.size)
        return AuthoritativeThreadSnapshot(
            epoch = epoch,
            items = decoded.sortedBy { it.order?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE },
            completedVersion = appliedVersion,
            checkpoint = checkpoint,
            repairNeeded = appliedVersion > checkpoint,
            coverage = coverage?.toDomain(),
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
        items = items.map { it.raw },
        appliedVersion = completedVersion,
        checkpoint = checkpoint,
        coverage =
            coverage?.let {
                SavedThreadCoverage(
                    it.lowerOrder.toLong(),
                    it.upperOrder.toLong(),
                    it.continuation.toLong(),
                    it.olderExists,
                )
            },
    ).retained()

private const val MAX_THREAD_NUMBER = 9007199254740991L
