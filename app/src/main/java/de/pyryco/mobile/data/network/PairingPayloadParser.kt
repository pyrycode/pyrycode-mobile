package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
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
}

private val RELAY_SCHEMES = setOf("ws", "wss")

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
