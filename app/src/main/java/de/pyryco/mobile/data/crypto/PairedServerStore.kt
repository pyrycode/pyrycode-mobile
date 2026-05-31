package de.pyryco.mobile.data.crypto

import kotlinx.serialization.Serializable

/**
 * Encrypted custody of the paired-server credential record.
 *
 * After QR pairing the phone holds four values it must re-present to reconnect after process
 * death: the relay URL it dials, the bearer token it sends inside the encrypted hello, the server
 * id, and the server's static Noise public key. This store persists that record encrypted at rest
 * and recovers it byte-faithfully. Consumed by the Noise_IK session (#275, server static pubkey)
 * and the relay WS client (#276, relay URL + token + server id); both see only the typed
 * [PairedServer] and stay decoupled from the Keystore mechanism.
 *
 * Live source of paired-state truth: `MainActivity` picks the start destination from [load] (record
 * present → channel list, absent → welcome) and the Scanner placeholder persists a stub via [save];
 * the QR-pairing consumers (#275/#276) remain dormant.
 */
interface PairedServerStore {
    /**
     * Decrypt and return the persisted paired server, or `null` if no record is stored OR the
     * record is undecryptable / corrupt (graceful → re-pair; never throws, never crashes). Unlike
     * [DeviceStaticKeyStore.loadOrCreate], an unreadable record is safe to discard — it is
     * re-fetchable from the QR, so there is no identity-drift risk.
     */
    suspend fun load(): PairedServer?

    /**
     * Encrypt and persist [record], overwriting any existing one. Throws [PairedServerStoreException]
     * on a Keystore / IO failure (the pairing did not persist — the caller surfaces "try again").
     */
    suspend fun save(record: PairedServer)
}

/**
 * The paired-server credential record. All four fields are [String], stored and reloaded
 * byte-faithfully so #275/#276 receive exactly what the server expects.
 *
 * A `data class`, so structural `equals`/`hashCode`/`copy` are correct and useful (the fields are
 * all `String`). The only override is [toString], declared explicitly to redact the bearer [token]:
 * the compiler-generated `toString` would render it in plaintext and leak it to Logcat via a stray
 * `Log.d("$record")`. Serialization is unaffected — the wire needs the real token.
 */
@Serializable
data class PairedServer(
    val serverId: String,
    val token: String,
    val relayUrl: String,
    val serverStaticPublicKey: String,
) {
    override fun toString(): String = "PairedServer(serverId=$serverId)"
}

/** Signals a Keystore / IO failure while persisting in [PairedServerStore.save]. Carries no secret material. */
class PairedServerStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
