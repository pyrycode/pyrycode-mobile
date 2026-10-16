package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Versioned suite body. The host adds producer metadata and validates it before publishing a candidate. */
internal class RecordedSuiteDocument private constructor(
    val document: JsonObject,
    val steps: List<SuiteStep>,
) {
    val scenario = document.getValue("scenario").jsonPrimitive.content
    val inputs = document.getValue("inputs").jsonObject
    val declaredIdentities =
        document
            .getValue("identities")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    fun tape() = RecordedSuiteTape(steps, declaredIdentities)

    companion object {
        private fun stepJson(step: SuiteStep): JsonObject =
            buildJsonObject {
                put("owner", step.owner)
                put("direction", step.direction)
                put("delayMs", step.delayMs)
                put("accepted", step.accepted)
                put("detail", step.detail)
                if (step.direction == "open") {
                    put("capabilities", JsonArray(step.capabilities.sorted().map(::JsonPrimitive)))
                }
                step.message?.let { put("message", MobileJson.encodeToJsonElement(Envelope.serializer(), it)) }
            }

        fun body(
            scenario: String,
            inputs: JsonObject,
            identities: Set<String>,
            steps: List<SuiteStep>,
        ): JsonObject =
            buildJsonObject {
                put("format", "pyrycode.mobile-suite")
                put("version", 1)
                put("scenario", scenario)
                put("inputs", inputs)
                put("identities", JsonArray(identities.sorted().map(::JsonPrimitive)))
                put("steps", JsonArray(steps.map(::stepJson)))
            }

        fun parse(text: String): RecordedSuiteDocument {
            try {
                require(text.toByteArray().size <= 20_000_000)
                val document = MobileJson.parseToJsonElement(text).jsonObject
                requireSafe(document)
                require(document.getValue("format").jsonPrimitive.content == "pyrycode.mobile-suite")
                require(document.getValue("version").jsonPrimitive.long == 1L)
                require(
                    document
                        .getValue("scenario")
                        .jsonPrimitive.content
                        .matches(Regex("[A-Za-z0-9_]{1,180}")),
                )
                val source = document.getValue("source").jsonObject
                for (key in listOf("mobileRevision", "daemonRevision")) {
                    require(
                        source
                            .getValue(key)
                            .jsonPrimitive.content
                            .matches(Regex("[a-f0-9]{40}")),
                    )
                }
                for (key in listOf("daemonBinarySha256", "testApkSha256")) {
                    require(
                        source
                            .getValue(key)
                            .jsonPrimitive.content
                            .matches(Regex("[a-f0-9]{64}")),
                    )
                }
                require(
                    source
                        .getValue("claudeVersion")
                        .jsonPrimitive.content
                        .isNotBlank(),
                )
                java.time.Instant.parse(source.getValue("capturedAt").jsonPrimitive.content)
                val steps =
                    document.getValue("steps").jsonArray.map { element ->
                        val row = element.jsonObject
                        SuiteStep(
                            direction = row.getValue("direction").jsonPrimitive.content,
                            owner = row.getValue("owner").jsonPrimitive.content,
                            message = row["message"]?.let { MobileJson.decodeFromJsonElement(Envelope.serializer(), it) },
                            capabilities =
                                row["capabilities"]
                                    ?.jsonArray
                                    ?.map { it.jsonPrimitive.content }
                                    ?.toSet()
                                    .orEmpty(),
                            delayMs = row.getValue("delayMs").jsonPrimitive.long,
                            accepted = requireNotNull(row.getValue("accepted").jsonPrimitive.booleanOrNull),
                            detail = row.getValue("detail").jsonObject,
                        )
                    }
                val result = RecordedSuiteDocument(document, steps)
                result.tape()
                return result
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid recorded suite document")
            }
        }
    }
}
