package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.HostSystemPromptReading
import de.pyryco.mobile.data.repository.SystemPromptLimit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host reply's single validation boundary. Wire SSOT: pyrycode `docs/protocol-mobile.md`,
 * “Daemon-wide host system prompt”, and `internal/protocol/host_system_prompt.go`.
 * Both fields are required strings, including empty. Never fall back, trim or truncate.
 * Manual decoding keeps submitted/default instructions out of exceptions and their causes.
 */
internal fun JsonElement.toHostSystemPromptReading(): HostSystemPromptReading {
    val obj = this as? JsonObject ?: throw SerializationException("host_system_prompt reply must be an object")
    return HostSystemPromptReading(
        systemPrompt = obj.requiredHostPrompt("system_prompt"),
        defaultSystemPrompt = obj.requiredHostPrompt("default_system_prompt"),
    )
}

private fun JsonObject.requiredHostPrompt(key: String): String {
    val value = this[key] as? JsonPrimitive
    if (value == null || !value.isString) {
        throw SerializationException("host_system_prompt reply: $key must be a string")
    }
    if (!SystemPromptLimit.fits(value.content)) {
        throw SerializationException("host_system_prompt reply: $key exceeds the byte limit")
    }
    return value.content
}
