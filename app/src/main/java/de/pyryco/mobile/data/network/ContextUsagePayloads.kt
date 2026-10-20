package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.ContextCategory
import de.pyryco.mobile.data.repository.ContextMemoryFile
import de.pyryco.mobile.data.repository.ContextUsage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The `context_usage` frame (#945, pyrycode#2370/#2371/#2461): how full a conversation's context window is, in
 * Claude's own arithmetic. One shape serves both producers, the post-turn push and the answer to
 * [RequestContextUsagePayloadDto], so nothing here reads `in_reply_to`. Decode-only. Always decode through
 * [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `context_usage`. Summary fields are strict-required;
 * only [asOf] is optional, present on remembered answers. Raw primitives prevent string/number coercion.
 *
 * **SECURITY.** Model/category/path strings become bounded inert Text only. Memory types remain descriptive.
 * `conversation_id` alone is the daemon-authored routing key. Never emit caught decode exceptions.
 */
@Serializable
internal data class ContextUsagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("total_tokens") val totalTokens: Long,
    @SerialName("max_tokens") val maxTokens: Long,
    val percentage: Int,
    @SerialName("as_of") val asOf: String? = null,
    val model: JsonElement,
    val categories: List<ContextCategoryPayloadDto>,
    @SerialName("dropped_categories") val droppedCategories: JsonElement,
    @SerialName("memory_files") val memoryFiles: List<ContextMemoryFilePayloadDto>,
    @SerialName("dropped_memory_files") val droppedMemoryFiles: JsonElement,
) {
    override fun toString(): String = "ContextUsagePayloadDto(<redacted>)"
}

@Serializable
internal data class ContextCategoryPayloadDto(
    val name: JsonElement,
    val tokens: JsonElement,
) {
    override fun toString(): String = "ContextCategoryPayloadDto(<redacted>)"
}

@Serializable
internal data class ContextMemoryFilePayloadDto(
    val path: JsonElement,
    val type: JsonElement,
    val tokens: JsonElement,
) {
    override fun toString(): String = "ContextMemoryFilePayloadDto(<redacted>)"
}

/**
 * Map a decoded [ContextUsagePayloadDto] to a [ContextUsage], or **null** for a negative
 * [ContextUsagePayloadDto.percentage], which no reading can carry. Otherwise a verbatim copy; the percentage the
 * thread shows is computed from the token counts downstream (#1411), not here. A malformed [ContextUsagePayloadDto.asOf] makes [Instant.parse] throw
 * [IllegalArgumentException], which the caller's decoder catches, so that frame drops too.
 */
internal fun ContextUsagePayloadDto.toReading(): ContextUsage? {
    if (percentage < 0) return null
    val modelText = (model as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val dropped = (droppedCategories as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    val rows =
        categories.map { category ->
            val name = (category.name as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val tokens = (category.tokens as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
            ContextCategory(name.take(512), tokens)
        }
    val droppedFiles = (droppedMemoryFiles as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
    val files =
        memoryFiles.map { file ->
            val path = (file.path as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val type = (file.type as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val tokens = (file.tokens as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
            ContextMemoryFile(path.take(2048), type.take(512), tokens)
        }
    return ContextUsage(
        totalTokens,
        maxTokens,
        percentage,
        asOf?.let(Instant::parse),
        modelText.take(512),
        rows,
        dropped,
        memoryFiles = files,
        droppedMemoryFiles = droppedFiles,
    )
}

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
