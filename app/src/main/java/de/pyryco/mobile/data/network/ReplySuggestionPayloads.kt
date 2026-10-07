package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.ReplySuggestion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strict inbound boundary for `reply_suggestion` (#1865). Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `reply_suggestion`. Explicit JSON kinds/presence avoid MobileJson's absent-null and primitive coercion.
 * Invalid input returns null, never an exception containing daemon text. Accepted text stays verbatim.
 */
internal fun decodeReplySuggestion(payload: JsonElement): ReplySuggestion? {
    val fields = payload as? JsonObject ?: return null
    val conversationId = fields.string("conversation_id") ?: return null
    val sessionId = fields.string("session_id") ?: return null
    if (!isAttachmentIdShape(conversationId) || !isAttachmentIdShape(sessionId)) return null
    val number = fields["revision"] as? JsonPrimitive ?: return null
    if (number.isString || number.content.isEmpty() || number.content.any { it !in '0'..'9' }) return null
    val revision = number.content.toULongOrNull()?.takeIf { it > 0uL } ?: return null
    val text = fields["suggested_reply"] ?: return null
    val suggestedReply =
        if (text == JsonNull) {
            null
        } else {
            val value = (text as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!validSuggestionText(value)) return null
            value
        }
    return ReplySuggestion(conversationId, sessionId, revision, suggestedReply)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Native suggestions have a byte bound, without the fallback-only 240-code-point bound. */
private fun validSuggestionText(text: String): Boolean {
    if (text.isBlank() || text.any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }) return false
    return try {
        text.encodeToByteArray(throwOnInvalidSequence = true).size <= 1024
    } catch (e: CharacterCodingException) {
        false
    }
}
