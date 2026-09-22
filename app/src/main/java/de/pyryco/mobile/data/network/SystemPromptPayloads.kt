package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptReading
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mobile Protocol v2 `request_system_prompt` request payload (#823): the phone asks what system prompt
 * one conversation stores. **Encode-only**, through [MobileJson]. The daemon answers with a correlated
 * `system_prompt` reply ([toSystemPromptReading]) and never with an error frame.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § "Reading a conversation's system prompt". One key,
 * always present; correlation rides [Envelope.inReplyTo], so there is no request-id key. The daemon
 * leaves a conn that did not negotiate `interactive` fully inert on this verb, so the sender gates first.
 */
@Serializable
data class RequestSystemPromptPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * Build the Mobile Protocol v2 `set_system_prompt` request payload (#823).
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § "Setting a conversation's system prompt". The value
 * has three states and each is sent as itself: `null` as an **explicit** JSON `null` (clear), `""` as an
 * empty string, and any other text verbatim. Built by hand rather than from a DTO because [MobileJson]'s
 * `explicitNulls = false` would elide a `null` property, turning the clear into an omitted key. The
 * protocol reads both as a clear, but the explicit form is unambiguous on the wire.
 */
fun setSystemPromptPayload(
    conversationId: String,
    systemPrompt: String?,
): JsonObject =
    buildJsonObject {
        put(KEY_CONVERSATION_ID, conversationId)
        put(KEY_SYSTEM_PROMPT, systemPrompt)
    }

/**
 * Map a raw `system_prompt` reply payload to its domain [SystemPromptReading] (#823) — the **single
 * validate boundary** for this reply.
 *
 * `system_prompt` is read **by presence**: an absent key is `null` (no prompt stored), a JSON string is
 * held verbatim, and anything else fails — an explicit `null` included, since the wire publishes
 * "string or absent". `session_prompt_status` must be a string naming one of the three published values.
 *
 * Decoded by hand, never through a DTO: a kotlinx decode failure can quote the offending input, and the
 * input here is operator-authored text. Every message thrown names the key and nothing else — no value,
 * no length — so the prompt cannot escape into a crash report. Throws [SerializationException] and
 * produces no partial value.
 */
fun JsonElement.toSystemPromptReading(): SystemPromptReading {
    val obj = this as? JsonObject ?: throw SerializationException(REPLY_NOT_AN_OBJECT)
    return SystemPromptReading(
        systemPrompt = obj.readSystemPrompt(),
        sessionPromptStatus = obj.readSessionPromptStatus(),
    )
}

private fun JsonObject.readSystemPrompt(): String? {
    val raw = this[KEY_SYSTEM_PROMPT] ?: return null
    val primitive = raw as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw SerializationException(PROMPT_NOT_A_STRING)
    return primitive.content
}

private fun JsonObject.readSessionPromptStatus(): SessionPromptStatus {
    val primitive = this[KEY_SESSION_PROMPT_STATUS] as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw SerializationException(STATUS_NOT_RECOGNISED)
    return when (primitive.content) {
        "matches" -> SessionPromptStatus.Matches
        "differs" -> SessionPromptStatus.Differs
        "no_session" -> SessionPromptStatus.NoSession
        else -> throw SerializationException(STATUS_NOT_RECOGNISED)
    }
}

private const val KEY_CONVERSATION_ID = "conversation_id"
private const val KEY_SYSTEM_PROMPT = "system_prompt"
private const val KEY_SESSION_PROMPT_STATUS = "session_prompt_status"

// Static — never interpolate the offending value; see [toSystemPromptReading].
private const val REPLY_NOT_AN_OBJECT = "system_prompt reply must be an object"
private const val PROMPT_NOT_A_STRING = "system_prompt reply: system_prompt must be a string when present"
private const val STATUS_NOT_RECOGNISED = "system_prompt reply: session_prompt_status is not a published value"
