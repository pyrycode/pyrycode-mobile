package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordedSuiteDocumentTest {
    private val runnerInfo = NoiseClientInfo("Android ATD built for x86_64", "current-client")

    private fun registration(
        name: String,
        owner: String = "phone/host-0/0",
        id: Long = 1,
    ) = SuiteStep(
        "client",
        owner,
        Envelope(
            id,
            "register_push_token",
            "time",
            MobileJson.parseToJsonElement(
                """{"platform":"fcm","token":"recorded-push-device-address","device_name":"$name"}""",
            ),
        ),
    )

    private fun registeredDocument(steps: List<SuiteStep>): RecordedSuiteDocument {
        val body = RecordedSuiteDocument.body("scenario", JsonObject(emptyMap()), emptySet(), steps)
        return RecordedSuiteDocument.parse(JsonObject(body + ("source" to candidate().getValue("source"))).toString())
    }

    @Test fun replayUsesCapturedPhoneNameWhileKeepingCurrentVersionAndExactTape() {
        val step = registration("Android ATD built for arm64")
        val document = registeredDocument(listOf(step))
        val original = document.document
        val info = document.replayClientInfo(runnerInfo)
        assertEquals(NoiseClientInfo("Android ATD built for arm64", "current-client"), info)
        val request =
            step.message!!.copy(
                id = 20,
                payload =
                    JsonObject(
                        step.message.payload.jsonObject + ("device_name" to JsonPrimitive(info.deviceName)),
                    ),
            )
        val tape = document.tape()
        tape.request(step.owner, request)
        tape.assertComplete()
        assertEquals(original, document.document)
    }

    @Test fun replayStillRejectsWrongRegistrationPayloads() {
        val step = registration("Android ATD built for arm64")
        val document = registeredDocument(listOf(step))
        val info = document.replayClientInfo(runnerInfo)
        val correct = JsonObject(step.message!!.payload.jsonObject + ("device_name" to JsonPrimitive(info.deviceName)))
        for (payload in listOf(
            JsonObject(correct + ("platform" to JsonPrimitive("wrong"))),
            JsonObject(correct + ("device_name" to JsonPrimitive("wrong phone"))),
            JsonObject(correct + ("unexpected" to JsonPrimitive(true))),
        )) {
            assertThrows(IllegalStateException::class.java) {
                document.tape().request(step.owner, step.message.copy(id = 20, payload = payload))
            }
        }
    }

    @Test fun aSuiteWithoutPhoneRegistrationKeepsTheRunnerIdentity() {
        assertEquals(runnerInfo, RecordedSuiteDocument.parse(candidate().toString()).replayClientInfo(runnerInfo))
        val peer = registeredDocument(listOf(registration("peer", "peer/host-0/0")))
        assertEquals(runnerInfo, peer.replayClientInfo(runnerInfo))
    }

    @Test fun reconnectAndMultipleHostsKeepTheSingleRecordedPhoneIdentity() {
        val document =
            registeredDocument(
                listOf(
                    registration("captured phone"),
                    registration("captured phone", "phone/host-1/0"),
                    registration("captured phone", "phone/host-0/1"),
                ),
            )
        assertEquals("captured phone", document.replayClientInfo(runnerInfo).deviceName)
    }

    @Test fun inconsistentRecordedPhoneNamesCannotSilentlyChooseAnIdentity() {
        val document =
            registeredDocument(
                listOf(
                    registration("first"),
                    registration("second", "phone/host-1/0"),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) { document.replayClientInfo(runnerInfo) }
    }

    @Test fun observedCheckpointsAreDeclaredWithoutAddingThemToOlderCaptures() {
        assertEquals(null, candidate()["checkpointPolicy"])
        val request = Envelope(1, "test-control:checkpoint", "time", MobileJson.parseToJsonElement("""{"body":"reply-visible"}"""))
        val original = candidate()
        val body =
            RecordedSuiteDocument.body(
                "scenario",
                original.getValue("inputs").jsonObject,
                emptySet(),
                listOf(SuiteStep("client", "control", request)),
            )
        val document = RecordedSuiteDocument.parse(JsonObject(body + ("source" to original.getValue("source"))).toString())
        assertEquals(JsonPrimitive("test-observations-v1"), document.document["checkpointPolicy"])
        val tape = document.tape()
        assertEquals(true, tape.checkpointReady("reply-visible"))
        tape.send("control", request.copy(id = 20))
        tape.assertComplete()
        assertThrows(IllegalArgumentException::class.java) {
            RecordedSuiteDocument.parse(JsonObject(original + ("checkpointPolicy" to JsonPrimitive("ignore-all"))).toString())
        }
    }

    @Test fun theReadPolicyIsExplicitAndDoesNotRewriteHistoricalCaptureProvenance() {
        val raw = JsonObject(candidate() + ("readReceiptNormalization" to JsonPrimitive("coalesced-visible-history-v3")))
        val parsed = RecordedSuiteDocument.parse(raw.toString())
        val resolved = parsed.withReadPolicy(JsonPrimitive("presented-visible-history-v4"))
        assertEquals(JsonPrimitive("coalesced-visible-history-v3"), parsed.document["readReceiptNormalization"])
        assertEquals(JsonPrimitive("presented-visible-history-v4"), resolved.document["readReceiptNormalization"])
        assertEquals(parsed.steps, resolved.steps)
        assertEquals(parsed.document["source"], resolved.document["source"])
        assertThrows(IllegalArgumentException::class.java) { parsed.withReadPolicy(JsonPrimitive("ignore-all-reads")) }
    }

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

    @Test fun aVersionedSuitePolicyAppliesOnlyKnownQueryRulesWithoutChangingCapturedEvidence() {
        val fields = setOf("modelQueryNormalization", "settingsReadNormalization", "backfillProbeNormalization")
        val original = JsonObject(candidate() - fields)
        val parsed = RecordedSuiteDocument.parse(original.toString())
        val policy =
            MobileJson
                .parseToJsonElement(
                    """{"version":1,"modelQueryNormalization":"reconnect-catalog-race-v1","settingsReadNormalization":"recorded-snapshots-v1","backfillProbeNormalization":"unsupported-legacy-probe-v1"}""",
                ).jsonObject
        val resolved = parsed.withQueryPolicy(policy)
        assertEquals(parsed.steps, resolved.steps)
        assertEquals(original.getValue("source"), resolved.document.getValue("source"))
        fields.forEach { key ->
            assertEquals(null, parsed.document[key])
            assertEquals(policy[key], resolved.document[key])
        }
        for (invalid in listOf(
            JsonObject(policy + ("version" to JsonPrimitive(2))),
            JsonObject(policy + ("settingsReadNormalization" to JsonPrimitive("accept-anything"))),
            JsonObject(policy + ("ignoreCommands" to JsonPrimitive(true))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { parsed.withQueryPolicy(invalid) }
        }
        assertThrows(IllegalStateException::class.java) {
            resolved.tape().send("phone", Envelope(20, "list_conversations", "time", MobileJson.parseToJsonElement("""{"changed":true}""")))
        }
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

    @Test fun opaqueDiagnosticBytesKeepTheSameProducerIdentityThatTheClientObserved() {
        val id = "captured-diagnostic-marker"
        val marker = Envelope(1, "conversation_created", "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement("""{"id":"$id"}"""))
        val request = Envelope(1, "request_debug_bundle", marker.ts)
        val payload =
            buildJsonObject {
                put("seq", 0)
                put(
                    "data",
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(id.toByteArray()),
                )
            }
        val chunk = Envelope(2, "debug_bundle_chunk", marker.ts, payload, inReplyTo = 1)
        val steps = listOf(SuiteStep("daemon", "phone", marker), SuiteStep("client", "phone", request), SuiteStep("daemon", "phone", chunk))
        val body = RecordedSuiteDocument.body("opaque", JsonObject(emptyMap()), emptySet(), steps)
        val parsed = RecordedSuiteDocument.parse(JsonObject(body + ("source" to candidate().getValue("source"))).toString())
        val tape = parsed.tape()
        val observed =
            tape
                .initial()
                .single()
                .message!!
                .payload as JsonObject
        assertEquals(JsonPrimitive(id), observed["id"])
        val bytes =
            tape
                .send("phone", request.copy(id = 90))
                .deliveries
                .single()
                .message!!
                .payload as JsonObject
        val encoded = (bytes.getValue("data") as JsonPrimitive).content
        assertEquals(
            id,
            String(
                java.util.Base64
                    .getDecoder()
                    .decode(encoded),
            ),
        )
        tape.assertComplete()
    }
}
