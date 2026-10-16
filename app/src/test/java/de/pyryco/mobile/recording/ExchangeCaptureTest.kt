package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class ExchangeCaptureTest {
    private fun message(payload: String = "{}") =
        Envelope(1, "send_message", "2026-10-16T00:00:00Z", MobileJson.parseToJsonElement(payload))

    @Test fun failedFlowCannotExportAndExistingCandidateIsPreserved() {
        val capture = ExchangeCapture()
        capture.observe("client", message())
        val file = Files.createTempDirectory("exchange-test").resolve("candidate.json").toFile()
        assertThrows(IllegalStateException::class.java) { capture.writeWire(file, completed = false) }
        assertEquals(false, file.exists())
        file.writeText("previous")
        assertThrows(IllegalArgumentException::class.java) { capture.writeWire(file, completed = true) }
        assertEquals("previous", file.readText())
    }

    @Test fun sensitiveContentAndBoundsInvalidateTheWholeCapture() {
        for ((capture, payload) in listOf(ExchangeCapture(maxBytes = 1) to "{}", ExchangeCapture() to """{"text":"sk-ant-secret"}""")) {
            capture.observe("client", message(payload))
            assertThrows(IllegalStateException::class.java) { capture.take() }
        }
    }

    @Test fun captureIsPrivateOrderedAndCannotBeChangedByTheCaller() {
        val capture = ExchangeCapture()
        capture.observe("client", message())
        val file = Files.createTempDirectory("exchange-test").resolve("wire.json").toFile()
        capture.writeWire(file, completed = true)
        assertEquals(1, MobileJson.parseToJsonElement(file.readText()).let { (it as kotlinx.serialization.json.JsonArray).size })
        assertEquals(
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }
}
