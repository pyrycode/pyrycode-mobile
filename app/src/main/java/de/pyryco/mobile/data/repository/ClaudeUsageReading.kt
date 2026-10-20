package de.pyryco.mobile.data.repository

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One paired host's account allowance; independent of conversation-scoped [UsageLimitReading]. */
sealed interface ClaudeUsageReading {
    /** The host explicitly reports that it has no stored observation. */
    data object None : ClaudeUsageReading

    /** A future state this client cannot present; arbitrary wire state text is never retained. */
    data object Unavailable : ClaudeUsageReading

    data class Observed(
        val observedAt: Instant,
        val fiveHour: Window? = null,
        val sevenDay: Window? = null,
    ) : ClaudeUsageReading {
        override fun toString(): String = "ClaudeUsageReading.Observed(<redacted>)"
    }

    /** Missing values differ from zero. Finite utilization and signed reset seconds are unclamped. */
    data class Window(
        val utilization: Double? = null,
        val resetsAt: Long? = null,
    ) {
        override fun toString(): String = "ClaudeUsageReading.Window(<redacted>)"
    }
}

/** Strict boundary shared by replies and pushes. Null means malformed, never an unavailable replacement. */
internal fun decodeClaudeUsage(payload: JsonElement): ClaudeUsageReading? =
    try {
        val raw = payload as? JsonObject ?: throw IllegalArgumentException()
        val state = raw["state"] as? JsonPrimitive ?: throw IllegalArgumentException()
        require(state.isString)
        if (state.content != "none" && state.content != "observed") {
            ClaudeUsageReading.Unavailable
        } else {
            val observedAt =
                raw["observed_at"]?.let {
                    val text = it as? JsonPrimitive ?: throw IllegalArgumentException()
                    require(text.isString)
                    Instant.parse(text.content)
                }
            val fiveHour = raw["five_hour"]?.let(::decodeClaudeUsageWindow)
            val sevenDay = raw["seven_day"]?.let(::decodeClaudeUsageWindow)
            if (state.content == "none") {
                ClaudeUsageReading.None
            } else {
                ClaudeUsageReading.Observed(requireNotNull(observedAt), fiveHour, sevenDay)
            }
        }
    } catch (_: IllegalArgumentException) {
        // Decode exceptions can quote untrusted payloads; never retain or log them.
        null
    }

private fun decodeClaudeUsageWindow(element: JsonElement): ClaudeUsageReading.Window {
    val raw = element as? JsonObject ?: throw IllegalArgumentException()
    val utilization =
        raw["utilization"]?.let {
            val number = it as? JsonPrimitive ?: throw IllegalArgumentException()
            require(!number.isString)
            requireNotNull(number.doubleOrNull).also { value -> require(value.isFinite()) }
        }
    val resetsAt =
        raw["resets_at"]?.let {
            val number = it as? JsonPrimitive ?: throw IllegalArgumentException()
            require(!number.isString)
            // JsonPrimitive.longOrNull also coerces exponent notation; the wire requires an integer token.
            requireNotNull(number.content.toLongOrNull())
        }
    return ClaudeUsageReading.Window(utilization, resetsAt)
}
