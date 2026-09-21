package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.staticKeyFingerprint
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.net.URISyntaxException

/** Fixed, byte-safe failure categories (no field values) — see [PairingParseResult.Failure]. */
private object PairingParseFailure {
    const val BAD_ENCODING = "bad-encoding"
    const val MALFORMED_JSON = "malformed-json"
    const val MISSING_FIELD = "missing-field"
    const val INVALID_RELAY = "invalid-relay"
    const val INVALID_SERVER_KEY = "invalid-server-key"
    const val FIELD_TOO_LONG = "field-too-long"
}

private val RELAY_SCHEMES = setOf("ws", "wss")

/**
 * Length ceiling for `server`, `relay` and `token`, in **UTF-8 bytes** (#752).
 *
 * Sized against what `pyry pair` actually emits, with room to spare: `server` is a UUIDv4 (36
 * bytes), `token` is 64 hex characters, and the structurally longest legitimate `relay` origin is
 * `wss://` + a maximum-length FQDN (253) + `:65535` = 265 bytes. On the other side, the stored
 * `serverId` rides every navigation route as an argument, so it lands in the back stack's
 * saved-instance-state `Bundle` where `Uri.encode` can inflate a reserved character threefold —
 * 512 × 3 is three orders of magnitude below the Binder transaction limit that would crash the
 * process on save or restore.
 *
 * A ceiling is **not** a safety property (the wire protocol says the same of its own `device_name`
 * bound): it caps length, nothing else, so every surface that renders one of these values keeps its
 * own clamp and its own escaping on top of this.
 */
private const val MAX_PAIRING_FIELD_BYTES = 512

/** The X25519 server static public key is exactly 32 bytes (mirrors NoiseSessionFactory.REMOTE_STATIC_KEY_SIZE). */
private const val SERVER_STATIC_KEY_SIZE = 32

/**
 * Decode the scanned outer QR-string wrapper → [QrPayload] → validate → [PairedServer].
 *
 * Pure, synchronous, no I/O, no persist, and **never throws**: any malformed input yields
 * [PairingParseResult.Failure] with a fixed byte-safe category [PairingParseResult.Failure.reason].
 * First failure wins. The outer wrapper is base64url-no-pad (Go `base64.RawURLEncoding`); the inner
 * `server_static_pubkey` is base64-std (validated by [decodeServerStaticPubkey]) — the two-alphabet
 * trap. Unknown/extra JSON fields are tolerated ([MobileJson] sets `ignoreUnknownKeys`); trailing
 * bytes after the top-level object are rejected (kotlinx requires EOF after the value).
 *
 * `server`, `relay` and `token` are bounded at [MAX_PAIRING_FIELD_BYTES] UTF-8 bytes and an
 * over-long one is **rejected, never truncated** (#752): a truncated `serverId` would not be the
 * daemon's, and that id is both the storage key and the relay's routing key, so the pairing would
 * fail to connect in a way that looks like a server fault. The wire protocol's `device_name` takes
 * the same line. `server_static_pubkey` needs no bound of its own — [decodeServerStaticPubkey]
 * already pins it to exactly 32 decoded bytes.
 *
 * Deliberately performs **no logging** — both [scanned] and the decoded JSON carry the plaintext
 * token; the caller logs only the fixed `reason`, never a field value.
 */
fun parsePairingPayload(scanned: String): PairingParseResult {
    val bytes =
        try {
            decodeBase64UrlNoPad(scanned)
        } catch (_: IllegalArgumentException) {
            return PairingParseResult.Failure(PairingParseFailure.BAD_ENCODING)
        }

    val json = String(bytes, Charsets.UTF_8)

    val qr =
        try {
            MobileJson.decodeFromString<QrPayload>(json)
        } catch (_: SerializationException) {
            return PairingParseResult.Failure(PairingParseFailure.MALFORMED_JSON)
        }

    if (qr.server.isBlank() || qr.relay.isBlank() || qr.token.isBlank()) {
        return PairingParseResult.Failure(PairingParseFailure.MISSING_FIELD)
    }

    if (!isWithinFieldBound(qr.server) || !isWithinFieldBound(qr.relay) || !isWithinFieldBound(qr.token)) {
        return PairingParseResult.Failure(PairingParseFailure.FIELD_TOO_LONG)
    }

    if (!isValidRelayOrigin(qr.relay)) {
        return PairingParseResult.Failure(PairingParseFailure.INVALID_RELAY)
    }

    try {
        // Validation only — PairedServer stores the base64-std string, not the decoded bytes.
        decodeServerStaticPubkey(qr)
    } catch (_: IllegalArgumentException) {
        return PairingParseResult.Failure(PairingParseFailure.INVALID_SERVER_KEY)
    }

    return PairingParseResult.Success(
        PairedServer(
            serverId = qr.server,
            token = qr.token,
            relayUrl = qr.relay,
            serverStaticPublicKey = qr.serverStaticPubkey,
        ),
    )
}

/**
 * True iff [value] fits [MAX_PAIRING_FIELD_BYTES] when encoded as UTF-8 (#752).
 *
 * Counted in bytes, not characters, so a multi-byte value cannot sit under a character count while
 * being over in bytes. The char-count pre-check is not a second bound but an allocation guard: a
 * UTF-8 encoding is never shorter than the string's char count, so a string longer than the bound in
 * characters is certainly over it in bytes and can be rejected without encoding a copy of it at all.
 */
private fun isWithinFieldBound(value: String): Boolean =
    value.length <= MAX_PAIRING_FIELD_BYTES &&
        value.toByteArray(Charsets.UTF_8).size <= MAX_PAIRING_FIELD_BYTES

/**
 * The relay is a trust-on-first-use origin (`wss://host[:port]`) the transport appends `/v1/client`
 * to (see `OkHttpRelayTransport.buildRequest`). Accept it iff it parses as a [URI] with a ws/wss
 * scheme and a non-empty host. Validating with `java.net.URI` (not `android.net.Uri` or OkHttp's
 * `HttpUrl`) keeps this `data/`-portable. A present path/port is not rejected — staying close to
 * the Go emitter's opaque-origin treatment avoids false rejections of legitimately-shaped relays.
 */
private fun isValidRelayOrigin(relay: String): Boolean {
    val uri =
        try {
            URI(relay)
        } catch (_: URISyntaxException) {
            return false
        }
    val scheme = uri.scheme?.lowercase()
    return scheme != null && scheme in RELAY_SCHEMES && !uri.host.isNullOrEmpty()
}

/**
 * Derive the human-comparable static-key fingerprint (#342) for the confirm gate (#343) from the
 * base64-std-encoded server static public key carried in a parsed [PairedServer].
 *
 * Decode [staticKeyBase64] (base64-std) → re-validate exactly 32 bytes → [staticKeyFingerprint].
 * Returns `null` if the stored key can't be decoded to a 32-byte value. Pure, synchronous, no I/O,
 * **no logging, and never throws** — the re-validate keeps [staticKeyFingerprint]'s `require(size ==
 * 32)` structurally unreachable, so a malformed stored key routes to a typed `null` (the caller maps
 * it to the same recovery path as a parse failure) instead of crashing the confirm-flow coroutine.
 * For a freshly-parsed [PairingParseResult.Success] the `null` branch is unreachable (the parser
 * already proved base64-std-of-32-bytes), but this is deterministic belt-and-suspenders over that
 * guarantee. Mirrors `NoiseSessionFactory.create`'s decode-then-revalidate; never echoes key bytes.
 */
fun serverKeyFingerprint(staticKeyBase64: String): String? {
    val bytes =
        try {
            base64StdDecode(staticKeyBase64)
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (bytes.size != SERVER_STATIC_KEY_SIZE) return null
    return staticKeyFingerprint(bytes)
}
