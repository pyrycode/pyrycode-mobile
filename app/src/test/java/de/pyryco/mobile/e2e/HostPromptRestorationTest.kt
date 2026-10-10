package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPromptRestorationTest {
    @Test
    fun `restoration timeout cannot hide the failing scenario operation`() {
        val scenario = AssertionError("host prompt phase=custom_read operation=reply timed out")
        val restoration = AssertionError("host prompt phase=restore operation=write timed out")
        val failure = runCatching {
            withHostPromptRestoration(
                readOriginal = { ORIGINAL },
                restoreOriginal = { throw restoration },
                block = { throw scenario },
            )
        }.exceptionOrNull()

        assertSame(scenario, failure)
        assertSame(restoration, failure?.suppressed?.single())
    }

    @Test
    fun `success and assertion failure both restore exact empty whitespace and custom originals`() {
        for (original in listOf("", "  \n ", ORIGINAL)) {
            for (fail in listOf(false, true)) {
                var stored = original
                val assertion = AssertionError("host prompt preview did not match")
                var restorations = 0
                val failure = runCatching {
                    withHostPromptRestoration(
                        readOriginal = { stored },
                        restoreOriginal = {
                            stored = it
                            restorations++
                        },
                        block = {
                            stored = "changed"
                            if (fail) throw assertion
                        },
                    )
                }.exceptionOrNull()

                assertTrue("exact original must be restored", stored == original)
                assertEquals(1, restorations)
                if (fail) assertSame(assertion, failure) else assertEquals(null, failure)
            }
        }
    }

    @Test
    fun `unread original prevents mutation and guessed restoration`() {
        val unread = AssertionError("host prompt phase=original_read operation=reply timed out")
        var mutated = false
        var restored = false
        val failure = runCatching {
            withHostPromptRestoration(
                readOriginal = { throw unread },
                restoreOriginal = { restored = true },
                block = { mutated = true },
            )
        }.exceptionOrNull()

        assertSame(unread, failure)
        assertFalse(mutated)
        assertFalse(restored)
    }

    @Test
    fun `failed confirmation after an acknowledged mutation still restores`() {
        var stored = ORIGINAL
        val confirmation = AssertionError("host prompt phase=custom_read operation=reply timed out")
        val failure = runCatching {
            withHostPromptRestoration(
                readOriginal = { stored },
                restoreOriginal = { stored = it },
                block = {
                    stored = "custom\nsecond line"
                    throw confirmation
                },
            )
        }.exceptionOrNull()

        assertSame(confirmation, failure)
        assertTrue("exact original must be restored", stored == ORIGINAL)
    }

    @Test
    fun `failed restoration confirmation fails a successful scenario`() {
        val restoration = AssertionError("host prompt restoration was not confirmed")
        val failure = runCatching {
            withHostPromptRestoration(
                readOriginal = { ORIGINAL },
                restoreOriginal = { throw restoration },
                block = {},
            )
        }.exceptionOrNull()

        assertSame(restoration, failure)
    }

    private companion object {
        const val ORIGINAL = "  sensitive host instructions\nsecond line  "
    }
}
