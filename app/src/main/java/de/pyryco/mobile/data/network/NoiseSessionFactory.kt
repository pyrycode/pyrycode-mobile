package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.DeviceStaticKeyException
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServerStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the crypto inputs for a [NoiseIkSession] and constructs one ready to handshake:
 * the device static key (#291) as the local `s`, and the PairedServer record (#294) for the
 * server static `rs` + the `hello` token.
 *
 * Every setup failure collapses into a single [NoiseSessionException] (cause chain preserved),
 * so the WS client (#276) catches one type to surface "re-pair / retry". This does NOT open a
 * socket — that is #276's concern.
 */
class NoiseSessionFactory(
    private val deviceStaticKeyStore: DeviceStaticKeyStore,
    private val pairedServerStore: PairedServerStore,
    private val clientInfo: NoiseClientInfo,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Builds a fresh session. Throws [NoiseSessionException] if setup fails. */
    suspend fun create(): NoiseIkSession =
        withContext(ioDispatcher) {
            val paired = pairedServerStore.load() ?: throw NoiseSessionException("not paired")

            // Re-validate the stored server static to exactly 32 bytes (mirrors decodeServerStaticPubkey);
            // never echo the bytes. Bad base64 or a wrong-length rs would corrupt the handshake.
            val remoteStaticKey =
                try {
                    base64StdDecode(paired.serverStaticPublicKey)
                } catch (e: IllegalArgumentException) {
                    throw NoiseSessionException("invalid server static key", e)
                }
            if (remoteStaticKey.size != REMOTE_STATIC_KEY_SIZE) {
                throw NoiseSessionException("invalid server static key")
            }

            val keyPair =
                try {
                    deviceStaticKeyStore.loadOrCreate(paired.serverId)
                } catch (e: DeviceStaticKeyException) {
                    throw NoiseSessionException("device static key unavailable", e)
                }

            NoiseIkSession(keyPair.privateKey, remoteStaticKey, paired.token, clientInfo).also {
                // The constructor has copied the private key into the DH state — zero our copy.
                keyPair.privateKey.fill(0)
            }
        }

    /**
     * Re-loads the device static private key for the paired server, for a re-key (#304/#303 decision (B):
     * `s` is re-supplied per re-key, never retained on the session). Returns a fresh, caller-owned 32-byte
     * scalar the caller MUST zero after use (mirrors [create]'s zero-after-copy); the public key is unused.
     * Throws [NoiseSessionException] if not paired or the device static key is unavailable.
     */
    suspend fun reloadDeviceStaticKey(): ByteArray =
        withContext(ioDispatcher) {
            val paired = pairedServerStore.load() ?: throw NoiseSessionException("not paired")
            try {
                deviceStaticKeyStore.loadOrCreate(paired.serverId).privateKey
            } catch (e: DeviceStaticKeyException) {
                throw NoiseSessionException("device static key unavailable", e)
            }
        }

    private companion object {
        const val REMOTE_STATIC_KEY_SIZE = 32
    }
}
