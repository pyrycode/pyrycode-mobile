package de.pyryco.mobile.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte parity tests for [staticKeyFingerprint] against pyrycode#432's
 * `Fingerprint([32]byte) string` (server-side SSOT). The pinned vector is a hard-coded literal,
 * NOT recomputed from the hash primitive — a recomputed expectation is tautological and would
 * pass against a silently-wrong implementation (AC #2).
 */
class StaticKeyFingerprintTest {
    @Test
    fun pinnedZeroVectorMatchesServer() {
        // Mirror of pyrycode#432 TestFingerprint_FixedVector: BLAKE2s-256 of 32 zero bytes,
        // first 8 bytes, colon-lowercase-hex. Hard-coded literal — never recomputed in-test.
        assertEquals("32:0b:5e:a9:9e:65:3b:c2", staticKeyFingerprint(ByteArray(32)))
    }

    @Test
    fun shapeAndLengthInvariant() {
        val key = ByteArray(32) { it.toByte() }

        val fingerprint = staticKeyFingerprint(key)

        assertEquals(23, fingerprint.length)
        assertTrue(
            "fingerprint $fingerprint must be 8 colon-separated lowercase-hex byte pairs",
            fingerprint.matches(Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){7}$")),
        )
    }

    @Test
    fun bytesAboveSignBitRenderAsTwoHexChars() {
        // The zero vector's digest includes a9/9e/c2 (all >= 0x80). An unmasked, sign-extending
        // implementation renders these as 8 chars (ffffffa9); assert every byte is exactly 2 chars.
        val segments = staticKeyFingerprint(ByteArray(32)).split(":")

        assertEquals(8, segments.size)
        assertTrue(segments.all { it.length == 2 })
    }

    @Test
    fun derivationIsDeterministic() {
        val key = ByteArray(32) { (it * 7).toByte() }

        assertEquals(staticKeyFingerprint(key), staticKeyFingerprint(key))
    }

    @Test
    fun rejectsTooShortKey() {
        assertThrows(IllegalArgumentException::class.java) {
            staticKeyFingerprint(ByteArray(31))
        }
    }

    @Test
    fun rejectsTooLongKey() {
        assertThrows(IllegalArgumentException::class.java) {
            staticKeyFingerprint(ByteArray(33))
        }
    }
}
