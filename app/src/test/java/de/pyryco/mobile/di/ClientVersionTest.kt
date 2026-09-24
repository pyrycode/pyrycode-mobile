package de.pyryco.mobile.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1007: the `hello`'s `client_version` follows the "`client_version` format (#2576)" rules in
 * pyrycode's `docs/protocol-mobile.md` § `hello`. A daemon with a configured minimum rejects a
 * version it cannot parse, so the value the app binds must satisfy every rule.
 */
class ClientVersionTest {
    @Test
    fun `the bound client version matches the spec format`() {
        val bound = mobileClientVersion()
        assertTrue("unparsable client_version: $bound", isSpecClientVersion(bound))
    }

    @Test
    fun `a two-part versionName fails the format`() {
        assertFalse(isSpecClientVersion(mobileClientVersion("1.0")))
    }

    @Test
    fun `a suffixed versionName fails the format`() {
        assertFalse(isSpecClientVersion(mobileClientVersion("1.0.0-beta")))
        assertFalse(isSpecClientVersion(mobileClientVersion("1.0.0+42")))
    }

    @Test
    fun `a leading zero or prefix fails the format`() {
        assertFalse(isSpecClientVersion(mobileClientVersion("1.01.0")))
        assertFalse(isSpecClientVersion(mobileClientVersion("v1.0.0")))
        assertTrue(isSpecClientVersion(mobileClientVersion("0.0.0")))
    }

    @Test
    fun `a version over 32 bytes fails the format`() {
        // "pyrycode-mobile/" is 16 bytes: a 16-byte version makes exactly 32, a 17-byte one 33.
        assertTrue(isSpecClientVersion(mobileClientVersion("123456.123456.12")))
        assertFalse(isSpecClientVersion(mobileClientVersion("1234567.1234567.1")))
    }

    private fun isSpecClientVersion(value: String): Boolean {
        if (value.toByteArray(Charsets.UTF_8).size > 32) return false
        return SPEC_FORMAT.matches(value)
    }

    private companion object {
        const val PART = "(0|[1-9][0-9]*)"
        val SPEC_FORMAT = Regex("pyrycode-mobile/$PART\\.$PART\\.$PART")
    }
}
