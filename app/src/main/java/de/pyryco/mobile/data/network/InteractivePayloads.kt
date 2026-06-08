package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 structured live-session stream payloads (#385): the `internal` decode DTOs for
 * the five **binary → phone** interactive envelopes — `turn_state`, `assistant_delta`, `tool_use`,
 * `tool_result`, `turn_end` — plus their `toEvent()` mappers to the portable
 * [LiveSessionEvent] family. **Decode-only** — the phone never sends one. Always decode through
 * [MobileJson] (`MobileJson.decodeFromJsonElement<…>(payload)`), never a default `Json`.
 *
 * Wire SSOT: pyrycode `internal/protocol` interactive structs + `docs/protocol-mobile.md`
 * § "Interactive events (v2, capability-gated)" (#607). Every payload field is **always present**
 * (no `omitempty`), so each DTO field is a required non-null `String`/`Int`/`Boolean`; snake_case
 * wire names map to camelCase via [SerialName] (the Go-interop contract, not cosmetic). This strict
 * shape is the fail-closed posture for an untrusted boundary: a missing/wrong-typed field fails the
 * structural decode with a [kotlinx.serialization.SerializationException] rather than a `null`-pun,
 * so the caller can drop the one malformed envelope and keep the stream alive (AC #4).
 *
 * This file carries multiple top-level types, so the ktlint single-class filename rule does not
 * apply (cf. `MobileWireModels.kt`). The DTOs stay `internal` to `data/network` (the ticket's "raw
 * decode DTOs stay internal"); only [LiveSessionEvent] crosses the package boundary.
 */
@Serializable
internal data class TurnStatePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val state: String,
)

@Serializable
internal data class AssistantDeltaPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    val seq: Int,
    val text: String,
)

@Serializable
internal data class ToolUsePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    val name: String,
    @SerialName("input_summary") val inputSummary: String,
)

@Serializable
internal data class ToolResultPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("is_error") val isError: Boolean,
    @SerialName("result_summary") val resultSummary: String,
)

@Serializable
internal data class TurnEndPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("stop_reason") val stopReason: String,
)

/**
 * Map a decoded [TurnStatePayloadDto] to a [LiveSessionEvent.TurnState], or **null** when [state] is
 * not one of the three documented values (AC #3). Modeling `state` as a plain `String` in the DTO
 * (not a strict enum) keeps the unrecognized-value decision a *mapper* concern: an unknown `state`
 * yields `null` here — the demux drops that one envelope and the stream survives — whereas a strict
 * enum would have failed the *decode* and conflated "unknown state" with "malformed envelope". An
 * **absent** `state` field is a different path: the required `String` makes decode itself throw,
 * also dropped (AC #4). Both converge on drop-without-crash, with no `Phase.Unknown` member to push
 * onto consumers.
 */
internal fun TurnStatePayloadDto.toEvent(): LiveSessionEvent? = state.toPhase()?.let { LiveSessionEvent.TurnState(conversationId, it) }

private fun String.toPhase(): LiveSessionEvent.TurnState.Phase? =
    when (this) {
        "thinking" -> LiveSessionEvent.TurnState.Phase.Thinking
        "responding" -> LiveSessionEvent.TurnState.Phase.Responding
        "idle" -> LiveSessionEvent.TurnState.Phase.Idle
        else -> null
    }

/** Total field copy: every [AssistantDeltaPayloadDto] decodes to a [LiveSessionEvent.AssistantDelta]. */
internal fun AssistantDeltaPayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.AssistantDelta(conversationId, turnId, seq, text)

/** Total field copy: every [ToolUsePayloadDto] decodes to a [LiveSessionEvent.ToolUse]. */
internal fun ToolUsePayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.ToolUse(conversationId, turnId, toolUseId, name, inputSummary)

/** Total field copy: every [ToolResultPayloadDto] decodes to a [LiveSessionEvent.ToolResult]. */
internal fun ToolResultPayloadDto.toEvent(): LiveSessionEvent =
    LiveSessionEvent.ToolResult(conversationId, turnId, toolUseId, isError, resultSummary)

/** Total field copy: [stopReason] passes through verbatim (consumers map the wire value). */
internal fun TurnEndPayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.TurnEnd(conversationId, turnId, stopReason)
