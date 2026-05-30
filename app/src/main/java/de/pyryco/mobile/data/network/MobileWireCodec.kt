package de.pyryco.mobile.data.network

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Wire-layer codec for Mobile Protocol v2 (#273): a single configured [MobileJson]
 * plus base64 (standard alphabet, with padding) helpers and the 32-byte
 * server-static-pubkey decoder. Top-level by design — the wire layer's exported
 * surface is the five models in MobileWireModels.kt; this file adds no new type.
 *
 * Consumers (de)serialize directly: `MobileJson.encodeToString(value)` /
 * `MobileJson.decodeFromString<T>(text)`. There are deliberately no per-model
 * wrappers — that would only inflate the surface the ticket asks to keep minimal.
 */

/**
 * The single configured [Json] for all Mobile Protocol v2 (de)serialization. The
 * configuration is load-bearing, not cosmetic — a default [Json] would break the
 * wire contract:
 *
 *  - `encodeDefaults = true`   emits `v:2`, `role:"client"`, `protocol_versions:["v2"]`
 *                              even when equal to the Kotlin defaults. The default
 *                              `Json` omits them — a server-breaking bug.
 *  - `explicitNulls = false`   omits `inReplyTo` when null instead of emitting
 *                              `"in_reply_to":null`. Non-null defaults are still
 *                              emitted, so this composes cleanly with the above.
 *  - `ignoreUnknownKeys = true` tolerates server-added fields (e.g. the future
 *                              `payload_encrypted`) on decode rather than throwing
 *                              — a conscious forward-compat lenient-decode choice.
 *
 * Immutable and thread-safe; safe to call from any dispatcher.
 */
val MobileJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/** Base64-encode [bytes] with the standard alphabet, WITH padding (Go `base64.StdEncoding`). */
fun base64StdEncode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Base64-decode [data] with the standard alphabet (Go `base64.StdEncoding`). */
fun base64StdDecode(data: String): ByteArray = Base64.getDecoder().decode(data)

/**
 * Decode [qr]'s `server_static_pubkey` (base64-std) to its raw 32 bytes, rejecting
 * any value that is not valid base64 or does not decode to exactly 32 bytes. Throws
 * [IllegalArgumentException] with a field-named message — the error names the failure
 * category (and an observed length count), never echoes the key bytes — and never
 * silently truncates (AC #4). This stops a malformed/truncated X25519 key from
 * reaching #275's `Noise_IK` handshake.
 */
fun decodeServerStaticPubkey(qr: QrPayload): ByteArray {
    val decoded =
        try {
            base64StdDecode(qr.serverStaticPubkey)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("server_static_pubkey is not valid base64", e)
        }
    require(decoded.size == 32) {
        "server_static_pubkey must decode to 32 bytes, got ${decoded.size}"
    }
    return decoded
}
