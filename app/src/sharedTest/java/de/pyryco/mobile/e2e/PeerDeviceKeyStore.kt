package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import java.security.MessageDigest
import java.util.Base64

/**
 * One in-memory identity per harness pairing for the instrumentation process (#1686). A suite reuses
 * its peer tokens across scenarios; the daemon binds each token to its first accepted static key.
 * Recreating a peer must therefore preserve that key, while sessions still use fresh ephemerals.
 * Nothing is persisted or logged; consumers receive independent arrays they may wipe.
 */
internal class PeerDeviceKeyStore(
    pairing: PairedServer,
) : DeviceStaticKeyStore {
    private val pairedServerId = pairing.serverId
    private val identityKey =
        pairedServerId to
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(pairing.token.toByteArray(Charsets.UTF_8)))

    override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair {
        val identity = identityFor(serverId)
        return DeviceStaticKeyPair(identity.publicKey.copyOf(), identity.privateKey.copyOf())
    }

    override suspend fun publicKey(serverId: String): ByteArray = identityFor(serverId).publicKey.copyOf()

    private fun identityFor(serverId: String): DeviceStaticKeyPair {
        require(serverId == pairedServerId) { "peer key requested for another host" }
        return synchronized(identities) { identities.getOrPut(identityKey, ::generateIdentity) }
    }

    private companion object {
        // The test process owns these keys, not an individual peer's socket/session lifetime.
        val identities = mutableMapOf<Pair<String, String>, DeviceStaticKeyPair>()

        fun generateIdentity(): DeviceStaticKeyPair {
            val publicKey = ByteArray(32)
            val privateKey = ByteArray(32)
            val dh = Noise.createDH("25519")
            try {
                dh.generateKeyPair()
                dh.getPublicKey(publicKey, 0)
                dh.getPrivateKey(privateKey, 0)
            } finally {
                dh.destroy()
            }
            return DeviceStaticKeyPair(publicKey, privateKey)
        }
    }
}
