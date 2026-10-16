package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuiteCaptureTest {
    private fun message(payload: String = "{}") =
        Envelope(1, "send_message", "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload))

    @Test fun failedRunsAndRejectedContentCannotExport() {
        val capture = SuiteCapture()
        capture.observe(SuiteStep("client", "phone", message()))
        assertThrows(IllegalStateException::class.java) { capture.completed(false) }
        capture.observe(SuiteStep("client", "phone", message("""{"token":"private"}""")))
        assertThrows(IllegalStateException::class.java) { capture.completed(true) }
    }

    @Test fun timingAndFailedSendResultsRemainPartOfTheRecording() {
        var tick = 0L
        val capture = SuiteCapture(clockMs = { tick })
        capture.observe(SuiteStep("connect", "phone"))
        tick = 123L
        capture.observe(SuiteStep("client", "phone", message(), accepted = false))
        val steps = capture.completed(true)
        assertEquals(123L, steps.last().delayMs)
        assertEquals(false, steps.last().accepted)
    }

    @Test fun aSynchronousReplyCannotPrecedeItsClientRequestInTheCapture() {
        val capture = SuiteCapture()
        val accepted =
            capture.send("phone", message()) {
                capture.observe(SuiteStep("daemon", "phone", message().copy(id = 2, type = "ack", inReplyTo = 1)))
                false
            }
        assertEquals(false, accepted)
        val steps = capture.completed(true)
        assertEquals(listOf("client", "daemon"), steps.map { it.direction })
        assertEquals(false, steps.first().accepted)
    }

    @Test fun boundsInvalidateTheWholeCandidate() {
        val capture = SuiteCapture(maxBytes = 1)
        capture.observe(SuiteStep("client", "phone", message()))
        assertThrows(IllegalStateException::class.java) { capture.completed(true) }
    }

    @Test fun pushAddressIsNormalizedWithoutChangingTheRequestSentToTheDaemon() {
        val capture = SuiteCapture()
        val original =
            message("""{"platform":"fcm","token":"private-device-address","device_name":"test phone"}""")
                .copy(type = "register_push_token")
        var sent: Envelope? = null
        capture.send("phone", original) {
            sent = original
            true
        }
        assertEquals(original, sent)
        val frame = capture.completed(true).single().message!!
        assertEquals("register_push_token", frame.type)
        assertEquals(
            MobileJson.parseToJsonElement(
                """{"platform":"fcm","token":"recorded-push-device-address","device_name":"test phone"}""",
            ),
            frame.payload,
        )
        val tape = RecordedSuiteTape(capture.completed(true))
        assertThrows(IllegalStateException::class.java) { tape.send("phone", frame.copy(payload = original.payload)) }
    }

    @Test fun aTokenOutsideTheScopedClientRegistrationStillRejectsTheWholeCandidate() {
        for (direction in listOf("daemon", "client")) {
            val capture = SuiteCapture()
            capture.observe(SuiteStep(direction, "phone", message("""{"token":"recorded-push-device-address"}""")))
            assertThrows(IllegalStateException::class.java) { capture.completed(true) }
        }
    }

    @Test fun aRealPayloadlessRequestKeepsItsAbsentPayloadRatherThanBecomingAnEmptyObject() {
        val request = Envelope(1, "request_debug_bundle", "2026-10-16T00:00:00Z")
        val capture = SuiteCapture()
        capture.send("phone", request) { true }
        val steps = capture.completed(true)
        assertEquals(kotlinx.serialization.json.JsonNull, steps.single().message!!.payload)
        val tape = RecordedSuiteTape(steps)
        tape.send("phone", request.copy(id = 90))
        tape.assertComplete()
        val changed = request.copy(id = 90, payload = MobileJson.parseToJsonElement("{}"))
        assertThrows(IllegalStateException::class.java) { RecordedSuiteTape(steps).send("phone", changed) }
    }
}
