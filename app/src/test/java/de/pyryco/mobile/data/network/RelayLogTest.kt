package de.pyryco.mobile.data.network

import android.util.Log
import de.pyryco.mobile.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RelayLog] (#521): the debug-gated, redaction-safe relay diagnostic logger.
 *
 * The module has no Robolectric and no `unitTests.returnDefaultValues`, so real `android.util.Log.*`
 * throws "not mocked" on plain JVM. Every test that exercises the enabled path installs a capturing
 * [RelayLog.sink] instead of touching `android.util.Log`. Referencing the `Log.DEBUG`/`INFO`/`WARN`
 * `static final int` constants is safe — the compiler inlines them, so the `Log` class is never loaded.
 *
 * [RelayLog] is an `object` with process-global mutable seams; [restoreSeams] resets them after every
 * test so state cannot leak across tests. `BuildConfig.DEBUG == true` on the `testDebugUnitTest`
 * classpath, so the disabled-path test flips `enabled` off explicitly.
 */
class RelayLogTest {
    private val captured = mutableListOf<Triple<Int, String, String>>()

    @After
    fun restoreSeams() {
        RelayLog.enabled = BuildConfig.DEBUG
        RelayLog.sink = { _, _, _ -> }
    }

    private fun captureSink() {
        RelayLog.sink = { priority, tag, message -> captured += Triple(priority, tag, message) }
    }

    // AC5: redactConnId derives a fixed-width hex token, never the live input.
    @Test
    fun redactConnId_producesDerivedEightCharHexToken_notTheInput() {
        val connId = "conn-9f3c2a10b7"
        val token = RelayLog.redactConnId(connId)

        assertNotEquals(connId, token)
        assertTrue("token was '$token'", token.matches(Regex("^[0-9a-f]{8}$")))
        // Deterministic: same conn_id → same token, so log lines from one connection correlate.
        assertEquals(token, RelayLog.redactConnId(connId))
        // A distinct conn_id yields a distinct token.
        assertNotEquals(token, RelayLog.redactConnId("conn-0000000000"))
    }

    // AC5 edge: hashing (unlike prefix truncation) has no short-input hole.
    @Test
    fun redactConnId_shortAndEmptyInput_stillNotTheInput() {
        for (input in listOf("", "abc")) {
            val token = RelayLog.redactConnId(input)
            assertNotEquals(input, token)
            assertTrue("token was '$token' for input '$input'", token.matches(Regex("^[0-9a-f]{8}$")))
        }
    }

    // AC2/AC4: when disabled, the message lambda is never invoked and the sink is never called,
    // so no potentially-sensitive value is assembled in a release build.
    @Test
    fun disabledGate_doesNotInvokeMessageLambdaOrSink() {
        RelayLog.enabled = false
        captureSink()
        var lambdaRan = false

        RelayLog.d {
            lambdaRan = true
            "should never be built"
        }

        assertFalse("message lambda must not run when disabled", lambdaRan)
        assertTrue("sink must not be called when disabled", captured.isEmpty())
    }

    // Positive direction of the gate: enabled builds the message and forwards the redacted string.
    @Test
    fun enabledGate_invokesLambdaAndForwardsRedactedMessageToSink() {
        RelayLog.enabled = true
        captureSink()
        val token = RelayLog.redactConnId("abc123")

        RelayLog.d { "connected ${RelayLog.redactConnId("abc123")}" }

        assertEquals(1, captured.size)
        val (priority, tag, message) = captured.single()
        assertEquals(Log.DEBUG, priority)
        assertEquals("RelayLog", tag)
        assertEquals("connected $token", message)
        // The live conn_id never reaches the sink — only its redacted form does.
        assertFalse("live conn_id must not appear in the emitted line", message.contains("abc123"))
    }

    // The level only sets the logcat priority; d/i/w gate identically.
    @Test
    fun levelMethods_mapToLogPriorities() {
        RelayLog.enabled = true
        captureSink()

        RelayLog.d { "debug line" }
        RelayLog.i { "info line" }
        RelayLog.w { "warn line" }

        assertEquals(listOf(Log.DEBUG, Log.INFO, Log.WARN), captured.map { it.first })
        assertEquals(listOf("debug line", "info line", "warn line"), captured.map { it.third })
    }
}
