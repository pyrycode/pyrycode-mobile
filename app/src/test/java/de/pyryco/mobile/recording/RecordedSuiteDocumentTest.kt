package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordedSuiteDocumentTest {
    private fun candidate(): JsonObject {
        val request = Envelope(1, "list_conversations", "2026-10-16T00:00:00Z", JsonObject(emptyMap()))
        val body = RecordedSuiteDocument.body("scenario", JsonObject(emptyMap()), emptySet(), listOf(SuiteStep("client", "phone", request)))
        val source =
            buildJsonObject {
                put("mobileRevision", "a".repeat(40))
                put("daemonRevision", "b".repeat(40))
                put("daemonBinarySha256", "c".repeat(64))
                put("testApkSha256", "d".repeat(64))
                put("claudeVersion", "controlled producer")
                put("capturedAt", "2026-10-16T00:00:00Z")
            }
        return JsonObject(body + ("source" to source))
    }

    @Test fun versionedBodyRetainsTheExactRecordedRequest() {
        val parsed = RecordedSuiteDocument.parse(candidate().toString())
        assertEquals("scenario", parsed.scenario)
        val tape = parsed.tape()
        val actual = Envelope(20, "list_conversations", "2026-10-17T00:00:00Z", JsonObject(emptyMap()))
        tape.request("phone", actual)
        tape.assertComplete()
    }

    @Test fun missingProvenanceWrongVersionAndCredentialsFailWithFixedDiagnostics() {
        val original = candidate()
        val invalid =
            listOf(
                JsonObject(original - "source"),
                JsonObject(original + ("version" to JsonPrimitive(2))),
                JsonObject(original + ("inputs" to MobileJson.parseToJsonElement("""{"token":"private value"}"""))),
            )
        for (document in invalid) {
            val error = assertThrows(IllegalArgumentException::class.java) { RecordedSuiteDocument.parse(document.toString()) }
            assertEquals("Invalid recorded suite document", error.message)
        }
    }
}
