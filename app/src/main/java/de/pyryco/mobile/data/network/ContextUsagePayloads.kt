package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.ContextUsage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `context_usage` frame (#945, pyrycode#2370/#2371/#2461): how full a conversation's context window is, in
 * Claude's own arithmetic. One shape serves both producers, the post-turn push and the answer to
 * [RequestContextUsagePayloadDto], so nothing here reads `in_reply_to`. Decode-only. Always decode through
 * [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `context_usage`. Only the scalars are declared: the four
 * numbers are **strict-required with no Kotlin default**, so a frame missing one fails the decode instead of
 * becoming a zero reading, which the contract says a client cannot tell from an empty context. [asOf] is the
 * one optional key, present only on a remembered answer.
 *
 * **SECURITY.** `model` and every inventory string (`categories`, `mcp_tools`, `memory_files`) are claude- or
 * workspace-authored. They are not declared, so [MobileJson]'s `ignoreUnknownKeys` discards them at the
 * boundary and none can reach the UI, a log, a path or an actuation. `conversation_id` is daemon-authored and
 * is the caller's routing key.
 */
@Serializable
internal data class ContextUsagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("total_tokens") val totalTokens: Long,
    @SerialName("max_tokens") val maxTokens: Long,
    val percentage: Int,
    @SerialName("as_of") val asOf: String? = null,
)

/**
 * Map a decoded [ContextUsagePayloadDto] to a [ContextUsage], or **null** for a negative
 * [ContextUsagePayloadDto.percentage], which no reading can carry. Otherwise a verbatim copy; the percentage the
 * thread shows is computed from the token counts downstream (#1411), not here. A malformed [ContextUsagePayloadDto.asOf] makes [Instant.parse] throw
 * [IllegalArgumentException], which the caller's decoder catches, so that frame drops too.
 */
internal fun ContextUsagePayloadDto.toReading(): ContextUsage? =
    if (percentage < 0) null else ContextUsage(totalTokens, maxTokens, percentage, asOf?.let(Instant::parse))

/**
 * `request_context_usage` (#945, pyrycode#2431): ask for a fresh reading of one conversation now rather than at
 * the next turn end. The answer is a `context_usage` correlated by `in_reply_to`, or an `error` carrying
 * `conversation.not_found` or `context_usage.unavailable`. v2-only and `interactive`-gated. Wire SSOT: pyrycode
 * `docs/protocol-mobile.md` § "Asking for a context usage reading on demand". Sent when a thread opens and when its
 * host returns (#1410), now that pyrycode#2563 keeps a mid-turn ask from holding up the connection's later frames.
 */
@Serializable
internal data class RequestContextUsagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
