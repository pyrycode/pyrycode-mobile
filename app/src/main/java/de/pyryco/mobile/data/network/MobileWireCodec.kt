package de.pyryco.mobile.data.network

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The single configured [Json] for all Mobile Protocol v2 (de)serialization (#273),
 * the root of the wire-layer codec (base64-std helpers + the 32-byte pubkey decoder
 * follow below). Top-level by design — the wire layer's exported surface is the five
 * models in MobileWireModels.kt; this file adds no new type. Consumers (de)serialize
 * directly: `MobileJson.encodeToString(value)` / `MobileJson.decodeFromString<T>(text)`;
 * there are deliberately no per-model wrappers, which would only inflate the surface.
 *
 * The configuration is load-bearing, not cosmetic — a default [Json] would break the
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
 * Base64-decode [data] with the URL-safe alphabet, no padding (Go `base64.RawURLEncoding`).
 *
 * This is for the OUTER QR-string pairing wrapper ONLY — NOT for `server_static_pubkey`, which is
 * base64-std (use [base64StdDecode] / [decodeServerStaticPubkey]). The two alphabet decoders are
 * co-located so the trap stays visible to the next reader: the URL-safe decoder rejects the
 * standard alphabet's `+`/`/`, which is the load-bearing rejection. Tolerates optional `=` padding
 * (real `RawURLEncoding` output is unpadded, so it round-trips). Throws [IllegalArgumentException]
 * on a non-base64url character.
 */
fun decodeBase64UrlNoPad(data: String): ByteArray = Base64.getUrlDecoder().decode(data)

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
