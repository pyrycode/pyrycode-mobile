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
 * contract for Go interop, not cosmetic. Always (de)serialize via [MobileJson]; a
 * default `Json` instance would drop the defaulted fields (`v`, `role`,
 * `protocol_versions`) and emit `null` for absent `in_reply_to` — both
 * wire-breaking (see MobileWireCodec.kt).
 *
 * [InnerFrameV2] is the bare WS text payload the phone sends/receives.
 */
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
 *
 * [eventId] is the durable, per-conversation, strictly-increasing replay cursor (#412),
 * stable across reconnects — distinct from [id], the per-connection id that resets each
 * reconnect. It rides only the interactive structured-stream frames and is absent on every
 * other frame, so it is modeled nullable-defaulted exactly like [inReplyTo]: `explicitNulls
 * = false` omits it on encode (every outbound frame, including `hello`, stays byte-identical
 * to today), and the default tolerates its per-frame absence on decode. Mirrors the server's
 * `omitempty *uint64` (pyrycode#649); a pathological `uint64 > 2^63` decodes to a negative
 * [Long] and is rejected downstream by [ReplayCursor.record]'s positive guard.
 */
@Serializable
data class Envelope(
    val id: Long,
    val type: String,
    val ts: String,
    val payload: JsonElement,
    @SerialName("in_reply_to") val inReplyTo: Long? = null,
    @SerialName("event_id") val eventId: Long? = null,
)

/**
 * The wire token for the v2 structured-live-session capability (#401). Advertised in
 * [HelloClientPayload.capabilities] and echoed back (the daemon's intersection) in
 * [HelloAckPayload.capabilities]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § "Capability negotiation (v2)".
 */
internal const val CAPABILITY_INTERACTIVE = "interactive"

/**
 * Payload of the `hello` envelope, sent as `noise_init` early-data.
 *
 * [token] is the device-pairing secret. [toString] is overridden to redact it so
 * a stray `Log.d("hello: $payload")` or crash-reporter frame cannot leak it to
 * Logcat. Redaction affects [toString] only — serialization emits the real token
 * (the wire needs it). `equals`/`hashCode` are intentionally NOT overridden.
 *
 * [capabilities] advertises the v2 features the phone understands; it defaults to
 * `["interactive"]` and rides every `hello` via `MobileJson`'s `encodeDefaults = true`
 * (same mechanism as [protocolVersions]). It is non-secret, so [toString] surfaces it.
 *
 * [lastEventId] is the replay cursor (#416): on reconnect the phone advertises the latest
 * structured-stream [Envelope.eventId] it observed (#412's [ReplayCursor]) so the daemon replays the
 * conversation's missed tail (events with id `> last_event_id`) before the live stream resumes. It is
 * modeled exactly like [Envelope.eventId] — nullable-defaulted, so `explicitNulls = false` omits it on
 * encode: a fresh connection that observed nothing leaves the field absent (never `0`/`null`), keeping
 * that `hello` byte-identical to today, while a positive value rides as the server's `omitempty
 * *uint64`. The phone advertises only the cursor it itself recorded through [ReplayCursor.record]'s
 * positive guard — never a conversation id, never an unvalidated server value. Non-secret (an event
 * ordinal), so [toString] surfaces it alongside [capabilities].
 */
@Serializable
data class HelloClientPayload(
    val role: String = "client",
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v2"),
    val token: String,
    val capabilities: List<String> = listOf(CAPABILITY_INTERACTIVE),
    @SerialName("last_event_id") val lastEventId: Long? = null,
) {
    override fun toString(): String =
        "HelloClientPayload(role=$role, deviceName=$deviceName, " +
            "clientVersion=$clientVersion, protocolVersions=$protocolVersions, " +
            "capabilities=$capabilities, lastEventId=$lastEventId, token=***)"
}

/**
 * Payload of the `hello_ack` envelope, received as `noise_resp` early-data.
 *
 * [capabilities] is the negotiated set the daemon grants — the intersection of the phone's
 * advertised [HelloClientPayload.capabilities] with the daemon's own. Emitted `omitempty`, so
 * a daemon that grants none omits the field and it decodes to the `emptyList()` default (#401).
 */
@Serializable
data class HelloAckPayload(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("conn_id") val connId: String,
    val capabilities: List<String> = emptyList(),
)

/**
 * Payload of an `error` envelope — the failure reply correlated to a request via
 * [Envelope.inReplyTo] (#346). Wire SSOT: server `internal/protocol/handshake.go`
 * `ErrorPayload`. [code] is the machine-readable failure category (e.g.
 * `protocol.malformed`, `conversation.not_found`, `server.binary_offline`); [message]
 * is server-authored human-facing text; [retryable] tells the caller whether a retry
 * may succeed. **Decode-only** — the phone never sends an `error`.
 *
 * The server also emits `retry_after_s`; it is **intentionally not modeled** — decode
 * through [MobileJson] (`ignoreUnknownKeys = true`) tolerates it. There is deliberately
 * no `AckPayload`: a success `ack` payload is empty `{}` and is never decoded —
 * correlation is purely on [Envelope.inReplyTo].
 */
@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

/**
 * The thrown form of a server [ErrorPayload] whose [code] is not a contract-mapped
 * domain error (#346). Carries the structured fields so a ViewModel can branch on
 * [retryable] and surface [code] / [message]. The `conversation.not_found` code is
 * mapped to [IllegalArgumentException] instead (mirroring the repository contract);
 * every other code — and a malformed/undecodable error payload — surfaces as this.
 *
 * [message] is the server's human-facing text only; it never carries the user's message
 * content (which is never logged or echoed back through the error path).
 */
class RelayErrorException(
    val code: String,
    val retryable: Boolean,
    message: String,
) : Exception(message)

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
    override fun toString(): String = "QrPayload(server=$server, relay=$relay, token=***, serverStaticPubkey=$serverStaticPubkey)"
}
