package de.pyryco.mobile.data.crypto

import kotlinx.serialization.Serializable

/**
 * Compatibility access to encrypted paired-server credentials.
 *
 * After QR pairing the phone holds four values it must re-present to reconnect after process
 * death: the relay URL it dials, the bearer token it sends inside the encrypted hello, the server
 * id, and the server's static Noise public key. This store persists that record encrypted at rest
 * and recovers it byte-faithfully. Consumed by the Noise_IK session (#275, server static pubkey)
 * and the relay WS client (#276, relay URL + token + server id); both see only the typed
 * [PairedServer] and stay decoupled from the Keystore mechanism.
 *
 * Collection-aware consumers use [PairedServerCollectionStore]. Existing connection consumers
 * keep using the most recently saved surviving record through [load].
 */
interface PairedServerStore {
    /**
     * Return the most recently saved surviving server, or `null` if no record is stored OR the
     * record is undecryptable / corrupt. Cancellation still propagates. Unlike
     * [DeviceStaticKeyStore.loadOrCreate], an unreadable record is safe to discard — it is
     * re-fetchable from the QR, so there is no identity-drift risk.
     */
    suspend fun load(): PairedServer?

    /**
     * Replace only [record]'s exact server id, preserving its local name and all other hosts.
     * Makes it the latest saved record. Throws [PairedServerStoreException] on storage/Keystore
     * failure, without changing persisted credentials or their compatibility selection.
     */
    suspend fun save(record: PairedServer)
}

/** Collection access, separate from legacy consumers and their single-record test doubles. */
interface PairedServerCollectionStore : PairedServerStore {
    /** Snapshot ordered oldest-save first; empty for missing or unreadable storage. */
    suspend fun list(): List<PairedServerEntry>

    /** Exact, case-sensitive id lookup; null for an absent id or unreadable storage. */
    suspend fun loadById(serverId: String): PairedServerEntry?

    /** Set or clear local metadata without changing credentials/order; unknown ids are a no-op. */
    suspend fun setDisplayName(
        serverId: String,
        displayName: String?,
    )

    /** Remove only this id and its name; unknown ids are a no-op. Device static keys are untouched. */
    suspend fun remove(serverId: String)
}

/** Local metadata stays outside the four-field pairing contract and inside encrypted storage. */
@Serializable
data class PairedServerEntry(
    val record: PairedServer,
    val displayName: String? = null,
) {
    override fun toString(): String = "PairedServerEntry([REDACTED])"
}

/**
 * The paired-server credential record. All four fields are [String], stored and reloaded
 * byte-faithfully so #275/#276 receive exactly what the server expects.
 *
 * A `data class`, so structural `equals`/`hashCode`/`copy` are correct and useful (the fields are
 * all `String`). The only override is [toString], declared explicitly to redact every field:
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
    override fun toString(): String = "PairedServer([REDACTED])"
}

/** Signals a Keystore / IO failure while persisting in [PairedServerStore.save]. Carries no secret material. */
class PairedServerStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
