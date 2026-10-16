package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExchangeCaptureTest {
    private fun message(payload: String = "{}") =
        Envelope(1, "send_message", "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload))

    @Test fun sensitiveContentAndBoundsInvalidateTheWholeCapture() {
        for ((capture, payload) in listOf(ExchangeCapture(maxBytes = 1) to "{}", ExchangeCapture() to """{"text":"sk-ant-secret"}""")) {
            capture.observe("client", message(payload))
            assertThrows(IllegalStateException::class.java) { capture.take() }
        }
    }

    @Test fun capturePreservesOrderAndReturnedSnapshotsDoNotChange() {
        val capture = ExchangeCapture()
        capture.observe("client", message("""{"text":"first"}"""))
        val first = capture.take()
        capture.observe("daemon", message("""{"text":"second"}"""))
        assertEquals(1, first.size)
        assertEquals(2, capture.take().size)
        assertEquals("client", (first[0] as kotlinx.serialization.json.JsonObject)["direction"]?.toString()?.trim('"'))
    }
}
