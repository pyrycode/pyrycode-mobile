package de.pyryco.mobile.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DaemonFaultEvidenceTest {
    @Test
    fun defaultRegistrationTimeoutReachesTheClientOverTheRealSocket() =
        runBlocking {
            val root =
                generateSequence(
                    File(".").absoluteFile,
                ) { it.parentFile }.first { File(it, "scripts/e2e-daemon-fault.py").isFile }
            val fixture = Files.createTempDirectory("daemon-fault-timeout-").toFile()
            val portFile = File(fixture, "port")
            val controllerLog = File(fixture, "controller.log")
            // Hold dispatch briefly before the real default registration wait; equal budgets must fail.
            val launcher =
                """
                import runpy, sys, time
                def dispatch(frame, event, arg):
                    if frame.f_code.co_name == 'await_relay_ready' and event == 'call':
                        time.sleep(0.25)
                    return dispatch
                sys.settrace(dispatch)
                runpy.run_path('e2e-daemon-fault.py', run_name='__main__')
                """.trimIndent()
            val controller =
                ProcessBuilder(
                    "python3",
                    "-c",
                    launcher,
                    "--port-file",
                    portFile.path,
                    "--log",
                    File(fixture, "child.log").path,
                    "--control-socket",
                    File(fixture, "control.sock").path,
                    "--ready-token",
                    "relay: conn established",
                    "--",
                    "python3",
                    "-c",
                    "import time; time.sleep(120)",
                ).directory(File(root, "scripts")).redirectErrorStream(true).redirectOutput(controllerLog).start()
            try {
                withTimeout(5_000) {
                    while (!portFile.isFile) {
                        assertTrue("owned controller exited before publishing its port", controller.isAlive)
                        delay(20)
                    }
                }
                val port = portFile.readText().trim().toInt()
                checkedDaemonFaultRequest("stop", {}) { exchangeDaemonFaultRequest("127.0.0.1", port, "stop") }
                val records = mutableListOf<String>()
                val caught =
                    assertThrows(IllegalStateException::class.java) {
                        checkedDaemonFaultRequest("start", records::add) { exchangeDaemonFaultRequest("127.0.0.1", port, "start") }
                    }
                val evidence = records.single()
                assertTrue(
                    evidence,
                    evidence.startsWith("action=start http_status=503 generation=2 phase=registration_timeout elapsed_ms="),
                )
                assertFalse(evidence.contains("failure=transport"))
                assertTrue(caught.message.orEmpty().contains(evidence))
                val transition = controllerLog.readLines().map { Json.parseToJsonElement(it).jsonObject }.last()
                assertEquals("start", transition.getValue("action").jsonPrimitive.content)
                assertEquals("503", transition.getValue("http_status").jsonPrimitive.content)
                assertEquals("2", transition.getValue("generation").jsonPrimitive.content)
                assertEquals("registration_timeout", transition.getValue("phase").jsonPrimitive.content)
                assertEquals("true", transition.getValue("running").jsonPrimitive.content)
                assertEquals("false", transition.getValue("ready").jsonPrimitive.content)
                assertTrue(
                    transition
                        .getValue("elapsed_ms")
                        .jsonPrimitive.content
                        .toLong() >= 15_000,
                )
            } finally {
                controller.destroy()
                assertTrue("owned controller did not join", controller.waitFor(12, TimeUnit.SECONDS))
                fixture.deleteRecursively()
            }
        }

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
