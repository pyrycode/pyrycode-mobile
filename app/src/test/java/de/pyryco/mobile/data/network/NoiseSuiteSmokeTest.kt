package de.pyryco.mobile.data.network

import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Phase 4 dependency-substrate smoke test (#272). There is no production
 * networking/crypto code yet — this proves the provisioned substrate is real at
 * runtime, not just resolved on the classpath:
 *
 *  - the vendored `noise-java` ChaCha20-Poly1305 + BLAKE2s + Curve25519 suite
 *    actually loads (AC #5), and
 *  - the kotlinx-serialization compiler plugin is genuinely applied under
 *    AGP-9 built-in Kotlin (the one integration unknown for #273).
 */
class NoiseSuiteSmokeTest {
    @Test
    fun noiseIkSuiteLoads() {
        // Mirrors noise-spike Main.kt:73, the proven IK initiator construction. A
        // missing primitive or an unparseable suite string surfaces here as
        // NoSuchAlgorithmException from the constructor → test fails.
        val handshake = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.INITIATOR)
        try {
            assertNotNull(handshake)
        } finally {
            // Destroyable — wipe key material so it doesn't leak across tests.
            handshake.destroy()
        }
    }

    @Test
    fun serializationPluginIsWired() {
        // Fails to COMPILE if the serialization plugin is on the classpath but
        // not actually applied (the AGP-9 built-in-Kotlin seam), turning a silent
        // #273 surprise into a hard signal now.
        val encoded = Json.encodeToString(Probe(1))
        val decoded = Json.decodeFromString<Probe>(encoded)
        assertEquals(Probe(1), decoded)
    }

    @Serializable
    private data class Probe(
        val x: Int,
    )
}
