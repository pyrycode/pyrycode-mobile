package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A routing credential is an explicit test input, never an unchecked wildcard in a replay. */
internal const val RECORDED_PUSH_ADDRESS = "recorded-push-device-address"

/** Compressed artifacts must be checked after decoding, before they become accepted recording bytes. */
internal fun requireSafeCapturedBytes(
    bytes: ByteArray,
    credentials: Collection<String>,
) {
    val text = bytes.toString(Charsets.UTF_8)
    check(credentials.none { it.isNotEmpty() && text.contains(it) }) { "Credential rejected from suite artifact" }
    requireSafe(JsonPrimitive(text))
}

internal fun capturedSuiteFrame(
    direction: String,
    frame: Envelope,
): Envelope {
    if (direction != "client" || frame.type != "register_push_token") return frame
    val payload = frame.payload.jsonObject
    require(
        payload
            .getValue("token")
            .jsonPrimitive.content
            .isNotEmpty(),
    )
    return frame.copy(payload = JsonObject(payload + ("token" to JsonPrimitive(RECORDED_PUSH_ADDRESS))))
}

internal fun requireSafeSuiteFrame(
    direction: String,
    frame: Envelope,
) {
    if (frame.payload == JsonNull) return
    val payload = frame.payload.jsonObject
    if (direction == "client" && frame.type == "register_push_token") {
        require(payload["token"] == JsonPrimitive(RECORDED_PUSH_ADDRESS))
        requireSafe(JsonObject(payload - "token"))
    } else {
        requireSafe(payload)
    }
}

internal fun requireSafeSuiteDocument(document: JsonObject) {
    requireSafe(JsonObject(document - "steps"))
    document.getValue("steps").jsonArray.forEach { element ->
        val step = element.jsonObject
        requireSafe(JsonObject(step - "message"))
        step["message"]?.let {
            val frame =
                de.pyryco.mobile.data.network.MobileJson
                    .decodeFromJsonElement(Envelope.serializer(), it)
            requireSafeSuiteFrame(step.getValue("direction").jsonPrimitive.content, frame)
        }
    }
}
