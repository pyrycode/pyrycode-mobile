package de.pyryco.mobile.recording

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Only test-owned control endpoints pass here. Replay never invokes their network suppliers. */
internal object SuiteHostFixtures {
    fun text(
        url: String,
        live: () -> String,
    ): String {
        val reply =
            RecordedSuiteNative.exchange("background-fixture", buildJsonObject { put("url", url) }) {
                buildJsonObject { put("text", live()) }
            }
        return reply.getValue("text").jsonPrimitive.content
    }

    /** Keep the fixture's safe response and credential field names, then mint synthetic pairing locally. */
    fun pairing(
        action: String,
        live: () -> JsonObject,
    ): JsonObject {
        var actual: JsonObject? = null
        val result =
            RecordedSuiteNative.exchange(action, JsonObject(emptyMap())) {
                live().also { actual = it }.let { response ->
                    val fields = response.keys.intersect(setOf("pairCode", "peerToken", "serverStaticPublicKey", "relayUrl"))
                    fields.filter { it != "relayUrl" }.forEach {
                        RecordedSuiteNative.rememberCredential(
                            response.getValue(it).jsonPrimitive.content,
                        )
                    }
                    JsonObject((response - fields) + ("syntheticFields" to JsonArray(fields.sorted().map(::JsonPrimitive))))
                }
            }
        if (!RecordedSuiteNative.replaying) return checkNotNull(actual)
        val fields =
            result
                .getValue("syntheticFields")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        if (fields.isEmpty()) return JsonObject(result - "syntheticFields")
        val synthetic = RecordedSuiteNative.syntheticCredentials(result.getValue("serverId").jsonPrimitive.content)
        check(fields.all { it in synthetic }) { "Unknown fixture credential field" }
        return JsonObject((result - "syntheticFields") + synthetic.filterKeys { it in fields })
    }
}
