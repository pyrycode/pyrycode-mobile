package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mobile Protocol v2 wire models (#273): typed, serializable representations of
 * the exact byte contract the pyrycode v2 server already speaks (proven by the
 * 2026-05-29 Noise client spike). Consumed by #275 (Noise session), #276 (relay
 * WS client), and #277 (QR scan + fingerprint).
 *
 * Wire field names are snake_case; Kotlin properties are camelCase, with
 * [SerialName] carrying the mapping where they differ — this mapping IS the wire
 * contract for Go interop, not cosmetic.
 *
 * Always (de)serialize via [MobileJson]; a default `Json` instance would drop the
 * defaulted fields (`v`, `role`, `protocol_versions`) and emit `null` for absent
 * `in_reply_to` — both wire-breaking (see MobileWireCodec.kt).
 */

/** The bare WS text payload the phone sends/receives. */
@Serializable
data class InnerFrameV2(
    val v: Int = 2,
    val type: String,
    val data: String,
)

/**
 * Envelope carried inside `noise_msg` plaintext and as handshake early-data.
 *
 * [payload] is a generic [JsonElement] carrier (the `json.RawMessage` analog),
 * polymorphic by [type]: consumer tickets bridge typed ↔ generic at the edges via
 * `MobileJson.decodeFromJsonElement` / `encodeToJsonElement`. It stays untrusted
 * raw JSON until a consumer decodes and validates it.
 */
@Serializable
data class Envelope(
    val id: Long,
    val type: String,
    val ts: String,
    val payload: JsonElement,
    @SerialName("in_reply_to") val inReplyTo: Long? = null,
)

/**
 * Payload of the `hello` envelope, sent as `noise_init` early-data.
 *
 * [token] is the device-pairing secret. [toString] is overridden to redact it so
 * a stray `Log.d("hello: $payload")` or crash-reporter frame cannot leak it to
 * Logcat. Redaction affects [toString] only — serialization emits the real token
 * (the wire needs it). `equals`/`hashCode` are intentionally NOT overridden.
 */
@Serializable
data class HelloClientPayload(
    val role: String = "client",
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v2"),
    val token: String,
) {
    override fun toString(): String =
        "HelloClientPayload(role=$role, deviceName=$deviceName, " +
            "clientVersion=$clientVersion, protocolVersions=$protocolVersions, token=***)"
}

/** Payload of the `hello_ack` envelope, received as `noise_resp` early-data. */
@Serializable
data class HelloAckPayload(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("conn_id") val connId: String,
)

/**
 * Decoded pairing payload. The outer QR-string transport wrapper (which may itself
 * be base64url-encoded) is #277's concern; this models the decoded JSON object only.
 *
 * [serverStaticPubkey] carries the base64-std raw 32-byte X25519 key as a string;
 * the 32-byte invariant is enforced by [decodeServerStaticPubkey], not the model.
 * [token] is the device-pairing secret — [toString] redacts it (serialization is
 * unaffected); `equals`/`hashCode` are intentionally NOT overridden.
 */
@Serializable
data class QrPayload(
    val server: String,
    val relay: String,
    val token: String,
    @SerialName("server_static_pubkey") val serverStaticPubkey: String,
) {
    override fun toString(): String =
        "QrPayload(server=$server, relay=$relay, token=***, serverStaticPubkey=$serverStaticPubkey)"
}
