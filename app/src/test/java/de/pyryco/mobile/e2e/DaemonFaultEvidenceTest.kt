package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DaemonFaultEvidenceTest {
    @Test
    fun transportFailurePreservesTheOriginalExceptionWithoutLoggingItsMessage() {
        val original = java.io.IOException("private-cause")
        val records = mutableListOf<String>()
        val caught =
            assertThrows(java.io.IOException::class.java) {
                checkedDaemonFaultRequest("start", records::add) { throw original }
            }
        assertSame(original, caught)
        assertEquals(1, records.size)
        assertFalse(records.single().contains("private"))
        assertFalse(records.single().contains("IOException"))
    }

    @Test
    fun httpFailureRetainsSafeBoundaryEvidence() {
        val records = mutableListOf<String>()
        val caught =
            assertThrows(IllegalStateException::class.java) {
                checkedDaemonFaultRequest("start", records::add) {
                    "HTTP/1.0 503 private-status\r\n\r\n" + """{"generation":4,"phase":"exited"}"""
                }
            }
        assertFalse(caught.message.orEmpty().contains("private"))
        assertEquals(true, caught.message.orEmpty().contains("generation=4 phase=exited"))
        assertEquals(1, records.size)
    }

    @Test
    fun registrationFailureKeepsStatusGenerationAndPhase() {
        val response =
            "HTTP/1.0 503 Service Unavailable\r\nContent-Length: 99\r\n\r\n" +
                """{"generation":2,"phase":"registration_timeout","error":"private-error"}"""
        assertEquals(
            "action=start http_status=503 generation=2 phase=registration_timeout elapsed_ms=15000",
            daemonFaultEvidence("start", response, 15000),
        )
    }

    @Test
    fun spawnFailureKeepsItsDistinctPhaseWithoutPublishingErrorText() {
        val response =
            "HTTP/1.0 503 Service Unavailable\r\n\r\n" +
                """{"generation":2,"phase":"spawn_failed","error":"private-path"}"""
        assertEquals(
            "action=start http_status=503 generation=2 phase=spawn_failed elapsed_ms=1",
            daemonFaultEvidence("start", response, 1),
        )
    }

    @Test
    fun malformedAndUntrustedEvidenceCannotPublishResponseText() {
        for (body in listOf("private-error", """{"generation":{},"phase":"private-error"}""", """{"generation":-1,"phase":{}}""")) {
            val evidence = daemonFaultEvidence("private-action", "HTTP/1.0 503 private-status\r\n\r\n$body", 0)
            assertEquals("action=unknown http_status=503 generation=-1 phase=unknown elapsed_ms=0", evidence)
            assertFalse(evidence.contains("private"))
        }
    }

    @Test
    fun successRequiresAnActualHttpStatusLine() {
        assertEquals(200, daemonFaultHttpStatus("HTTP/1.0 200 OK\r\n\r\n{}"))
        assertEquals(0, daemonFaultHttpStatus("private HTTP/1.0 200 OK"))
        assertEquals(0, daemonFaultHttpStatus("HTTP/1.0 2000 OK"))
        assertEquals(0, daemonFaultHttpStatus(""))
    }
}
