package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WithClearedHostInstructionsTest {
    @Test
    fun `clear is confirmed before session creation and exact original is restored`() {
        for (original in listOf("", "Keep the main thread free.", "  custom\n雪\n  ")) {
            val host = Host(original)
            val expected = Any()
            val result =
                host.run {
                    assertTrue("instructions must be empty before creating the session", host.value.isEmpty())
                    assertEquals(listOf("read", "clear", "read"), host.operations)
                    expected
                }
            assertSame(expected, result)
            assertTrue("restore the captured text verbatim", host.value == original)
            assertEquals(listOf("read", "clear", "read", "restore", "read"), host.operations)
        }
    }

    @Test
    fun `scenario assertion failure restores instructions and preserves the failure`() {
        val host = Host()
        val assertion = AssertionError("Stop proof failed")
        val failure = runCatching { host.run { throw assertion } }.exceptionOrNull()

        assertSame(assertion, failure)
        host.assertRestored()
    }

    @Test
    fun `failed initial read never mutates host or starts session`() {
        val host = Host()
        val unread = IllegalStateException("read failed")
        host.onRead = { throw unread }
        var started = false
        val failure = runCatching { host.run { started = true } }.exceptionOrNull()

        assertSame(unread, failure)
        assertFalse(started)
        assertEquals(listOf("read"), host.operations)
        assertTrue(host.value == ORIGINAL)
    }

    @Test
    fun `failed clear acknowledgement still restores and never starts session`() {
        val host = Host()
        val clearFailure = IllegalStateException("clear acknowledgement failed")
        host.onWrite = { if (it.isEmpty()) throw clearFailure }
        var started = false
        val failure = runCatching { host.run { started = true } }.exceptionOrNull()

        assertSame(clearFailure, failure)
        assertFalse(started)
        host.assertRestored()
    }

    @Test
    fun `nonempty clear confirmation stops setup and restores without disclosing text`() {
        val host = Host()
        host.onRead = { if (host.operations.count { it == "read" } == 2) ORIGINAL else host.value }
        var started = false
        val failure = runCatching { host.run { started = true } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("isolated host instructions were not cleared", failure?.message)
        assertFalse(started)
        host.assertRestored()
    }

    @Test
    fun `failed clear readback remains visible and restores instructions`() {
        val host = Host()
        val readFailure = IllegalStateException("clear readback failed")
        host.onRead = { if (host.operations.count { it == "read" } == 2) throw readFailure else host.value }
        var started = false
        val failure = runCatching { host.run { started = true } }.exceptionOrNull()

        assertSame(readFailure, failure)
        assertFalse(started)
        host.assertRestored()
    }

    @Test
    fun `restoration failure fails an otherwise successful scenario`() {
        val host = Host()
        val restoreFailure = IllegalStateException("restore write failed")
        host.onWrite = { if (it == ORIGINAL) throw restoreFailure }
        val failure = runCatching { host.run {} }.exceptionOrNull()

        assertSame(restoreFailure, failure)
        assertTrue("restoration was attempted", "restore" in host.operations)
    }

    @Test
    fun `failed restoration confirmation is visible without disclosing original text`() {
        val host = Host()
        host.onRead = { if (host.operations.count { it == "read" } == 3) "" else host.value }
        val failure = runCatching { host.run {} }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("isolated host instructions were not restored", failure?.message)
        assertTrue(host.value == ORIGINAL)
    }

    @Test
    fun `scenario and restoration failures both remain visible`() {
        val host = Host()
        val assertion = AssertionError("Stop proof failed")
        val restoreFailure = IllegalStateException("restore write failed")
        host.onWrite = { if (it == ORIGINAL) throw restoreFailure }
        val failure = runCatching { host.run { throw assertion } }.exceptionOrNull()

        assertSame(assertion, failure)
        assertSame(restoreFailure, failure?.suppressed?.single())
    }

    private class Host(
        var value: String = ORIGINAL,
    ) {
        val operations = mutableListOf<String>()
        var onRead: () -> String = { value }
        var onWrite: (String) -> Unit = {}

        fun <T> run(block: () -> T): T =
            withClearedHostInstructions(
                read = {
                    operations += "read"
                    onRead()
                },
                write = {
                    operations += if ("clear" in operations) "restore" else "clear"
                    value = it
                    onWrite(it)
                },
                block = block,
            )

        fun assertRestored() {
            assertTrue("restore the captured original", value == ORIGINAL)
            assertEquals("read", operations.last())
            assertTrue("restoration was attempted", "restore" in operations)
        }
    }

    private companion object {
        const val ORIGINAL = "  original instructions\nsecond line  "
    }
}
