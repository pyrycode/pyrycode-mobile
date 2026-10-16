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
}
