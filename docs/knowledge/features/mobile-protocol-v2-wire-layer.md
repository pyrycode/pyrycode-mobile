# Mobile Protocol v2 — wire models + codec

The **root of the Phase 4 wire layer**: typed, serializable Kotlin models for the exact byte contract the pyrycode v2 server speaks, plus a thin codec. This is the seam where untrusted wire bytes (from the relay/server, post-Noise-decrypt) become typed in-process values, and where typed values become the bytes the phone sends. The byte contract was proven end-to-end by the 2026-05-29 Noise client spike against the live `wss://pyrycode-relay.pyryco.de`; this layer ships the types those proven bytes need, **before** any networking or crypto code.

Package: `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), seeded by [#272](../codebase/272.md). Two production files — `MobileWireModels.kt` (the five models) and `MobileWireCodec.kt` (the codec). Landed in [#273](../codebase/273.md); reuses #272's `kotlinx-serialization-json` 1.8.1 substrate (no new dependency).

> **Greenfield.** Nothing references these yet. The consumers are split out: #275 (Noise session), #276 (relay WS client), #277 (QR scan + fingerprint).

## The wire ↔ Kotlin mapping is the contract

Wire field names are **snake_case**; Kotlin properties are camelCase, with `@SerialName` carrying the mapping where they differ. **This mapping is load-bearing for Go interop, not cosmetic** — the field names and types below match the server's `internal/protocol` package and upstream `docs/protocol-mobile.md` byte-for-byte. Base64 fields (`InnerFrameV2.data`, `QrPayload.server_static_pubkey`) use the **standard alphabet WITH padding** (Go's `base64.StdEncoding`) — not url-safe, not unpadded.

## Models

### `InnerFrameV2` — the bare WS text payload

```kotlin
@Serializable
data class InnerFrameV2(
    val v: Int = 2,          // protocol version; MUST be emitted (see codec config)
    val type: String,        // "noise_init" | "noise_resp" | "noise_msg"
    val data: String,        // base64-std raw bytes
)
```

What the phone sends/receives over the WebSocket. `data` carries the raw Noise frame bytes as a base64-std string. `v` defaults to `2` but is always emitted on the wire.

### `Envelope` — application message frame

```kotlin
@Serializable
data class Envelope(
    val id: Long,                                       // uint64 on the wire
    val type: String,                                   // e.g. "hello", "hello_ack"
    val ts: String,                                     // RFC3339, kept as String (not parsed)
    val payload: JsonElement,                           // generic carrier, polymorphic by `type`
    @SerialName("in_reply_to") val inReplyTo: Long? = null,  // OMITTED when absent
)
```

Carried inside `noise_msg` plaintext and as handshake early-data. `ts` stays a `String` this phase (not parsed to a typed instant). `inReplyTo` is **omitted from the JSON when null** (never serialized as `"in_reply_to":null`) — see the codec config.

`payload` is a **generic `JsonElement` carrier** (the `json.RawMessage` analog), polymorphic by `type`, rather than a sealed payload hierarchy. It stays untrusted raw JSON until a consumer decodes and validates it — a deliberate trust-boundary property (the type itself says "not yet trusted"). Consumers bridge typed ↔ generic at the edges:

```kotlin
// decode, when envelope.type == "hello_ack":
val ack = MobileJson.decodeFromJsonElement<HelloAckPayload>(envelope.payload)
// encode, when building a "hello" envelope:
val env = Envelope(id, "hello", ts, MobileJson.encodeToJsonElement(HelloClientPayload(...)))
```

### `HelloClientPayload` — the `hello` payload (in `noise_init` early-data)

```kotlin
@Serializable
data class HelloClientPayload(
    val role: String = "client",
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v2"),
    val token: String,                                  // device-pairing SECRET — toString() redacts
)
```

`role="client"` and `protocol_versions=["v2"]` default but are always emitted. `token` is the device-pairing secret (see [Secret handling](#secret-handling)).

### `HelloAckPayload` — the `hello_ack` payload (in `noise_resp` early-data)

```kotlin
@Serializable
data class HelloAckPayload(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("conn_id") val connId: String,
)
```

Server-supplied; decoded only, no defaults.

### `QrPayload` — decoded pairing payload

```kotlin
@Serializable
data class QrPayload(
    val server: String,
    val relay: String,
    val token: String,                                          // pairing SECRET — toString() redacts
    @SerialName("server_static_pubkey") val serverStaticPubkey: String,  // base64-std raw 32-byte X25519
)
```

The **decoded** pairing JSON object. The outer QR-string transport wrapper (the scanned string may itself be base64url-encoded) is **#277's** concern — this models only the decoded object and its base64-**std** `server_static_pubkey`. The 32-byte invariant is enforced by the codec, not the model (a `String` here; validated on decode).

## Codec (`MobileWireCodec.kt`)

Top-level by design — no wrapping object/class, no per-model encode/decode functions. The exported surface is the five models; consumers (de)serialize directly with `MobileJson.encodeToString(value)` / `MobileJson.decodeFromString<T>(text)`.

### `MobileJson` — the single configured `Json` (configuration is load-bearing)

```kotlin
val MobileJson: Json = Json {
    encodeDefaults = true       // emit v:2 / role:"client" / protocol_versions:["v2"] — a default Json drops them (server-breaking)
    explicitNulls = false       // omit in_reply_to when null instead of emitting "in_reply_to":null
    ignoreUnknownKeys = true    // tolerate server-added fields (e.g. future payload_encrypted) — forward-compat
}
```

**Always (de)serialize via `MobileJson`, never a fresh `Json {}`.** A default `Json` would break the wire two ways: it omits fields equal to their Kotlin default (dropping `"v":2`), and it emits `"in_reply_to":null` instead of omitting the key. Both are invisible at compile time and pinned by guard tests. `ignoreUnknownKeys = true` is a conscious lenient-decode choice: a client tolerating server-added fields is safer for forward-compat than hard-failing.

### Base64 + pubkey helpers

```kotlin
fun base64StdEncode(bytes: ByteArray): String   // java.util.Base64, standard alphabet WITH padding
fun base64StdDecode(data: String): ByteArray
fun decodeServerStaticPubkey(qr: QrPayload): ByteArray  // -> raw 32 bytes, or throws
```

`decodeServerStaticPubkey` base64-std-decodes `qr.serverStaticPubkey` then requires exactly 32 bytes, throwing `IllegalArgumentException` (field-named message, **no echoed key bytes**, reports an observed length count) on bad base64 **or** length ≠ 32. It **never silently truncates** — this stops a malformed/truncated X25519 key from reaching #275's `Noise_IK` handshake, where a wrong-length key would corrupt or weaken it.

## Error handling

- **Malformed JSON on decode** → `kotlinx.serialization.SerializationException`, propagated (UI surfacing is the consumer's call, #276).
- **Unknown server field** → silently ignored (`ignoreUnknownKeys = true`), no throw.
- **`server_static_pubkey` not base64, or ≠ 32 bytes** → `IllegalArgumentException`, field-named, no echoed value.

This layer performs only in-memory transforms — no network/file/permission failures live here. The inbound frame-size cap, timeouts, TLS, and cert pinning are #276's concern, enforced *before* bytes reach this codec.

## Secret handling

`HelloClientPayload.token` and `QrPayload.token` are the device-pairing **secret** (it travels inside encrypted Noise early-data; never a plaintext header under v2). Both classes **override `toString()` to render `token=***`** — a `data class`'s auto-`toString()` would otherwise leak the token to Logcat via a stray `Log.d("hello: $payload")` or a crash-reporter frame.

Two invariants the override preserves:

- **`equals`/`hashCode` are NOT overridden** — round-trip tests assert structural equality via the generated `equals`.
- **Redaction is `toString()`-only, never serialization** — kotlinx-serialization encodes from the `@Serializable` descriptor, so `MobileJson.encodeToString(payload)` still emits the **real** token (the wire needs it). A test pins both halves.

`Envelope` gets **no** redaction — it's the generic carrier and doesn't know its `payload` is sensitive. Logging discipline for raw envelopes / decrypted plaintext is the consumer tickets' concern (#275/#276); the typed `HelloClientPayload` is the safe-to-log representation. Token *storage* (EncryptedSharedPreferences / Keystore) is #277's concern — this layer neither generates, stores, nor transmits the token, only models its in-memory shape.

## CMP seam (one JVM-only spot)

`java.util.Base64` (API 26+, available in plain JVM unit tests with no Robolectric) is the one JVM-only dependency in an otherwise-portable data layer. It was chosen over `kotlin.io.encoding.Base64` for a 1:1 byte-correspondence with Go's `base64.StdEncoding` and to avoid the `@ExperimentalEncodingApi` opt-in. CLAUDE.md flags Compose Multiplatform as a walk-back trigger and asks `data/` to stay portable — this is a contained `expect/actual` *if/when* iOS lands, isolated to the two codec helpers. **The five models stay fully portable.** Conscious, documented trade-off.

## What's deliberately absent

- **No per-model encode/decode wrappers.** The configured `MobileJson` + the base64 helpers *are* the codec. Ten trivial typed wrappers would only inflate the surface.
- **No payload type-zoo.** Only the two Hello payloads are typed models this phase. Other application payloads (`send_message`, `conversations`, …) are added by their consumer tickets; `Envelope.payload` stays a generic `JsonElement` until then.
- **No `payload_encrypted` field.** The Go envelope carries an optional `payload_encrypted` (omitempty); it's dropped on decode today via `ignoreUnknownKeys`. **#275 should add it as a typed field** rather than assume it round-trips.
- **No typed instant for `ts`.** Kept a `String` (RFC3339) this phase; promote when a consumer needs to compare/sort timestamps.
- **No Noise handshake, WS transport, real crypto, or QR scanning.** All downstream (#275/#276/#277).

## Related

- Ticket notes: [`../codebase/273.md`](../codebase/273.md) (this layer), [`../codebase/272.md`](../codebase/272.md) (the serialization substrate it reuses).
- Spec: `docs/specs/architecture/273-mobile-protocol-v2-wire-models-codec.md`.
- Upstream contract: pyrycode `docs/protocol-mobile.md` (§ Message envelope, § Pairing flow) + the server's `internal/protocol` package (mirror of these shapes on the Go side).
- Downstream consumers (all open):
  - **#275** Noise session — consumes `Envelope` early-data, adds the `payload_encrypted` field.
  - **#276** relay WS client — frames bytes as `InnerFrameV2`; the Phase 4 `RemoteConnectionStateSource` (see [Connection state](connection-state.md)) will own the WebSocket this transport runs over.
  - **#277** QR scan + fingerprint — owns the QR-**string** transport wrapper around `QrPayload`, and calls `decodeServerStaticPubkey` before the fingerprint-confirm step.
- Sibling data-layer doc: [Data model](data-model.md) (the non-wire `Conversation`/`Session`/`Message` schema, which deliberately carries **no** serialization annotations — those belong to this Phase 4 wire layer).
- Spike: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`), § "Proven wire contract (byte-accurate)".
