package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The harness daemon binds each accepted token to one static key. Retain that identity for the
 * instrumentation process, across peer close and app graph rebuild, independently of app storage.
 * Only the static pair is shared: every dial still owns fresh Noise handshake and cipher state.
 */
internal class PeerDeviceStaticKeyStore(
    private val pairing: PairedServer,
) : DeviceStaticKeyStore {
    override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair {
        val retained = retainedKey(serverId)
        // NoiseSessionFactory wipes its private-key input after copying it into the session.
        return DeviceStaticKeyPair(retained.publicKey.copyOf(), retained.privateKey.copyOf())
    }

    override suspend fun publicKey(serverId: String): ByteArray = retainedKey(serverId).publicKey.copyOf()

    private suspend fun retainedKey(serverId: String): DeviceStaticKeyPair {
        require(serverId == pairing.serverId) { "peer key store belongs to another server" }
        return mutex.withLock {
            identities.getOrPut(serverId) { mutableMapOf() }.getOrPut(pairing.token) { generateKeyPair() }
        }
    }

    private companion object {
        val mutex = Mutex()

        // Nested exact keys avoid concatenation collisions. Never print this credential-bearing map.
        val identities = mutableMapOf<String, MutableMap<String, DeviceStaticKeyPair>>()

        fun generateKeyPair(): DeviceStaticKeyPair {
            val dh = Noise.createDH("25519")
            try {
                dh.generateKeyPair()
                val publicKey = ByteArray(32)
                val privateKey = ByteArray(32)
                dh.getPublicKey(publicKey, 0)
                dh.getPrivateKey(privateKey, 0)
                return DeviceStaticKeyPair(publicKey, privateKey)
            } finally {
                dh.destroy()
            }
        }
    }
}
