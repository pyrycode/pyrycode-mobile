package de.pyryco.mobile.data.crypto

/**
 * Custody of the per-server device static (Noise `s`) keypair.
 *
 * The phone is the Noise_IK initiator; this keypair is its long-term cryptographic identity
 * for a given paired server. One keypair per server-id, generated once and never silently
 * regenerated. The raw private key is protected by the Android Keystore and is never written
 * to disk in plaintext. Consumed by the Noise_IK session (#275) as the initiator's local
 * static key; the consumer sees only raw bytes and stays decoupled from the Keystore mechanism.
 */
interface DeviceStaticKeyStore {
    /**
     * Generate-or-load the per-server device static keypair. Idempotent: never regenerates once
     * persisted, and the keypair survives process death. Throws [DeviceStaticKeyException] on a
     * Keystore / crypto failure (the caller treats this as "needs re-pair" — it must NOT
     * regenerate, as a fresh key would change the device identity the server recognises).
     */
    suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair

    /**
     * The 32-byte device public key for [serverId], read from the plaintext mirror only (no
     * Keystore round-trip). `null` if no keypair has been generated for [serverId] yet.
     */
    suspend fun publicKey(serverId: String): ByteArray?
}

/**
 * A device static keypair as raw 32-byte X25519 (Curve25519) scalars.
 *
 * Holds [ByteArray]s, so the default reference-equality `equals`/`hashCode` are intentionally
 * left in place — compare the byte arrays directly (e.g. `assertArrayEquals`), never the holder.
 */
class DeviceStaticKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/** Signals a Keystore / crypto failure in [DeviceStaticKeyStore]. Carries no key material. */
class DeviceStaticKeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
