package de.pyryco.mobile.data.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

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
 * `JsonNull` represents absent payloads for bare control frames and is omitted on encode;
 * existing payload consumers keep their non-null [JsonElement] contract.
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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Envelope(
    val id: Long,
    val type: String,
    val ts: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val payload: JsonElement = JsonNull,
    @SerialName("in_reply_to") val inReplyTo: Long? = null,
    @SerialName("event_id") val eventId: Long? = null,
    @SerialName("history_entry_id")
    @Serializable(with = ReadMarkIdSerializer::class)
    val historyEntryId: ULong? = null,
)

/**
 * The wire token for the v2 structured-live-session capability (#401). Advertised in
 * [HelloClientPayload.capabilities] and echoed back (the daemon's intersection) in
 * [HelloAckPayload.capabilities]. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § "Capability negotiation (v2)".
 */
internal const val CAPABILITY_INTERACTIVE = "interactive"

/** Detection only; daemon authorization remains the negotiated interactive capability. */
internal const val CAPABILITY_STOP_BACKGROUND_TASK = "stop_background_task"

/**
 * The wire token for the multi-agent capability (#1119). Advertised in [HelloClientPayload.capabilities]
 * after [CAPABILITY_INTERACTIVE]; without it the daemon withholds every Codex conversation and every
 * frame about one. It also adds the `agent`/`family` tags to `model_list` and the `capabilities` object
 * to `session_settings`, all read as optional keys, so nothing on the phone checks whether it was
 * granted. Wire SSOT: pyrycode `docs/protocol-mobile.md` § "Capability negotiation (v2)" `multi_agent`.
 */
internal const val CAPABILITY_MULTI_AGENT = "multi_agent"

/** App-owned report for `hello.client_features`; prompt admission is defined in pyrycode `docs/protocol-mobile.md`. */
internal const val MOBILE_CLIENT_FEATURES =
    "Markdown links to absolute paths of served .md or .markdown files open in the in-app reader. " +
        "Wrap paths containing spaces in angle brackets: [Note](</Users/me/My Vault/note.md>). " +
        "Paths in backticks are not links. Picked files and shared photos are uploaded to the daemon when Send is tapped; " +
        "Claude receives their daemon-side paths with an instruction to use Read, not inline file contents."

/**
 * Payload of the `hello` envelope, sent as `noise_init` early-data.
 *
 * [token] is the device-pairing secret. [toString] is overridden to redact it so
 * a stray `Log.d("hello: $payload")` or crash-reporter frame cannot leak it to
 * Logcat. Redaction affects [toString] only — serialization emits the real token
 * (the wire needs it). `equals`/`hashCode` are intentionally NOT overridden.
 *
 * [capabilities] advertises the v2 features the phone understands; it defaults to
 * `["interactive", "multi_agent", "stop_background_task"]` and rides every `hello` via `MobileJson`'s `encodeDefaults = true`
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
 *
 * [clientFeatures] is an optional, verbatim self-report, independent of negotiated capabilities.
 * Missing decodes as empty; empty is omitted on encode, matching the daemon's `omitempty` string.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HelloClientPayload(
    val role: String = "client",
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v2"),
    val token: String,
    val capabilities: List<String> = listOf(CAPABILITY_INTERACTIVE, CAPABILITY_MULTI_AGENT, CAPABILITY_STOP_BACKGROUND_TASK),
    @SerialName("last_event_id") val lastEventId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("client_features") val clientFeatures: String = "",
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
    /** The host's minimum app version, carried only by [ERROR_CLIENT_UPDATE_REQUIRED] (pyrycode#2576) and
     *  omitted when the host could not parse this app's version. Raw daemon text: pass it through
     *  [validMinClientVersion] before it reaches any state. */
    @SerialName("min_client_version") val minClientVersion: String? = null,
)

/** The `error.code` a host sends before its `4412` close when this app build is below its minimum (#1008). */
internal const val ERROR_CLIENT_UPDATE_REQUIRED = "client.update_required"

// Three dot-separated ASCII decimal parts, each 1-6 digits: at most 20 characters in all.
private val MIN_CLIENT_VERSION_SHAPE = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")

/** [raw] when the whole string is three bounded decimal parts (`1.4.0`), else `null` — an unusable
 *  minimum is treated as absent (#1008). */
internal fun validMinClientVersion(raw: String?): String? = raw?.takeIf { MIN_CLIENT_VERSION_SHAPE.matches(it) }

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
