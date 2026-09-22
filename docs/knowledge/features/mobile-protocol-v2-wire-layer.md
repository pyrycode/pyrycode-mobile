# Mobile Protocol v2 — wire models + codec

The **root of the Phase 4 wire layer**: typed, serializable Kotlin models for the exact byte contract the pyrycode v2 server speaks, plus a thin codec. This is the seam where untrusted wire bytes (from the relay/server, post-Noise-decrypt) become typed in-process values, and where typed values become the bytes the phone sends. The byte contract was proven end-to-end by the 2026-05-29 Noise client spike against the live `wss://pyrycode-relay.pyryco.de`; this layer ships the types those proven bytes need, **before** any networking or crypto code.

Package: `de.pyryco.mobile.data.network` (`app/src/main/java/de/pyryco/mobile/data/network/`), seeded by [#272](../codebase/272.md). Two production files — `MobileWireModels.kt` and `MobileWireCodec.kt` (the codec). Landed in [#273](../codebase/273.md) with the five core wire models below; reuses #272's `kotlinx-serialization-json` 1.8.1 substrate (no new dependency). Later Phase 4 slices add typed application-payload DTOs (one file per `type`) and control-reply types alongside — e.g. #346's `ErrorPayload`/`RelayErrorException` (see [Application payloads](#application-payloads-decoded-on-top-of-envelope)).

> **First live consumer landed.** The [Noise_IK session](noise-ik-session.md) ([#298](../codebase/298.md), split from #275) consumes `Envelope` + the two Hello payloads as handshake early-data via `MobileJson`. Remaining consumers are still split out: #276 (relay WS client), #277 (QR scan + fingerprint).

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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Envelope(
    val id: Long,                                       // uint64 on the wire
    val type: String,                                   // e.g. "hello", "hello_ack"
    val ts: String,                                     // RFC3339, kept as String (not parsed)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val payload: JsonElement = JsonNull,                // absent payload, still a non-null generic carrier
    @SerialName("in_reply_to") val inReplyTo: Long? = null,  // OMITTED when absent
    @SerialName("event_id") val eventId: Long? = null,       // #412: durable replay cursor; OMITTED when absent
)
```

Carried inside `noise_msg` plaintext and as handshake early-data. `ts` stays a `String` this phase (not parsed to a typed instant). `inReplyTo` is **omitted from the JSON when null** (never serialized as `"in_reply_to":null`) — see the codec config.

`eventId` ([#412](../codebase/412.md)) is the **durable per-conversation replay cursor** — strictly-increasing, stable across reconnects, **distinct from `id`** (the per-connection counter that resets each reconnect). It rides **only** the interactive structured-stream frames and is absent on every other frame, so it is modeled nullable-defaulted **identically to `inReplyTo`**: `explicitNulls = false` omits it on encode (every outbound frame, including `hello`, stays byte-identical to today) and the default tolerates its per-frame absence on decode. Mirrors the server's `omitempty *uint64` (pyrycode#649); a pathological `uint64 > 2^63` decodes to a negative `Long` and is rejected downstream by the [`ReplayCursor`](replay-cursor.md) positive guard. The latest observed value is recorded as a reconnect-spanning cursor — see [Replay cursor](replay-cursor.md).

`payload` is a **generic `JsonElement` carrier** (the `json.RawMessage` analog), polymorphic by `type`, rather than a sealed payload hierarchy. It stays untrusted raw JSON until a consumer decodes and validates it — a deliberate trust-boundary property (the type itself says "not yet trusted"). Consumers bridge typed ↔ generic at the edges:

```kotlin
// decode, when envelope.type == "hello_ack":
val ack = MobileJson.decodeFromJsonElement<HelloAckPayload>(envelope.payload)
// encode, when building a "hello" envelope:
val env = Envelope(id, "hello", ts, MobileJson.encodeToJsonElement(HelloClientPayload(...)))
```

An absent payload decodes as `JsonNull`; callers still receive a non-null
`JsonElement`. `@EncodeDefault(NEVER)` omits that default on encode even though
`MobileJson.encodeDefaults` is true. `explicitNulls = false` alone does not omit
the non-null `JsonNull` object. Object/string payloads retain their representation.
This lets the [host diagnostic transfer](relay-debug-bundle-transfer.md)
send a bare `request_debug_bundle`. Verify key absence in serialized JSON, as
`DebugBundleTransferTest` and the registry's real Noise peer test do: decoding an
envelope back into Kotlin cannot distinguish an omitted payload from `JsonNull`.

### `HelloClientPayload` — the `hello` payload (in `noise_init` early-data)

```kotlin
@Serializable
data class HelloClientPayload(
    val role: String = "client",
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("protocol_versions") val protocolVersions: List<String> = listOf("v2"),
    val token: String,                                  // device-pairing SECRET — toString() redacts
    val capabilities: List<String> = listOf(CAPABILITY_INTERACTIVE),  // #401: advertised feature set
    @SerialName("last_event_id") val lastEventId: Long? = null,       // #416: replay cursor, omit-when-null
)
```

`role="client"`, `protocol_versions=["v2"]`, and `capabilities=["interactive"]` default but are always emitted. `token` is the device-pairing secret (see [Secret handling](#secret-handling)). `capabilities` (added [#401](../codebase/401.md)) advertises the v2 features the phone understands — see [Capability negotiation](#capability-negotiation-401); it is **non-secret**, so the `toString` override surfaces it (only `token` stays `***`). `last_event_id` (added [#416](../codebase/416.md)) is the [replay cursor](replay-cursor.md): on reconnect the phone advertises the latest structured-stream `Envelope.eventId` it observed so the daemon replays the missed tail before the live stream resumes. It is modeled **byte-for-byte like `Envelope.eventId`** — nullable-defaulted, so `explicitNulls = false` **omits it when null** (a fresh connection that observed nothing leaves the field absent — never `0`/`null`, keeping that `hello` byte-identical to today) while a positive value rides as the server's `omitempty *uint64`. Non-secret (an event ordinal), so `toString` surfaces it too. The value is read **live at `hello`-build** via a supplier — see [Noise_IK session § hello](noise-ik-session.md).

### `HelloAckPayload` — the `hello_ack` payload (in `noise_resp` early-data)

```kotlin
@Serializable
data class HelloAckPayload(
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("conn_id") val connId: String,
    val capabilities: List<String> = emptyList(),      // #401: the daemon's granted (intersected) set
)
```

Server-supplied; decoded only. `capabilities` (added [#401](../codebase/401.md)) is the **negotiated** set the daemon grants — the intersection of the phone's advertised set with its own — emitted `omitempty`, so a daemon that grants none omits the key and it decodes to the `emptyList()` default. See [Capability negotiation](#capability-negotiation-401).

### Capability negotiation (#401)

Both Hello payloads carry an optional `capabilities: []string` (wire key `capabilities`, no `@SerialName` — a single lowercase word, like `role`/`token`). The phone **advertises** the features it understands on `hello`; the daemon echoes the **intersection** of that set with its own on `hello_ack` (never a blind mirror). A phone that does not advertise `interactive` — or whose `interactive` is not echoed back — keeps receiving only the coarse v1 `message` fan-out and never sees the structured live-session stream. Wire SSOT: pyrycode `protocol-mobile.md` § "Capability negotiation (v2)"; ADR 025 (Phase 2 structured-streaming exit gate).

```kotlin
internal const val CAPABILITY_INTERACTIVE = "interactive"   // the wire token (#401)
```

- **Advertise** rides for free on `HelloClientPayload.capabilities`'s `listOf(CAPABILITY_INTERACTIVE)` default + `encodeDefaults = true` — exactly the `protocol_versions` mechanism, **confirmed by a wire test** (`"capabilities":["interactive"]` asserted literally, per the ticket's "don't assume it serialises" note), not trusted from the precedent.
- **Surface** is the [Noise_IK session](noise-ik-session.md)'s job: `readResp` decodes `HelloAckPayload.capabilities` (a `List`), `.toSet()`s it (capabilities are a **membership set** — "is `interactive` granted?" — and a `Set` dedups a daemon that repeats an entry), and exposes it as `NoiseIkSession.negotiatedCapabilities`, which the [Noise session pump](noise-session-pump.md) carries onto `PumpState.Open.capabilities`.
- **Surfacing-only.** [#401](../codebase/401.md) makes the negotiated set *readable*; it gates no decoding. The decode gate (#385) and the stall gate (#395) consume it. The daemon's echo is surfaced **verbatim** — a consuming gate treats it as *server-asserted* and is the place to re-derive the intersection before conferring authority.
- `CAPABILITY_INTERACTIVE` is `internal` (single-module app; #385 imports it for its membership check). It is a constant, not a type — no `@SerialName`, no ktlint single-class-filename concern (`MobileWireModels.kt` already holds many top-level declarations).

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

The **decoded** pairing JSON object. This models only the decoded object and its base64-**std** `server_static_pubkey`; the 32-byte invariant is enforced by the codec, not the model (a `String` here; validated on decode). The **outer QR-string transport wrapper** — the scanned string is base64**url**-no-pad (Go `RawURLEncoding`) around this JSON — is decoded by `decodeBase64UrlNoPad` (below) and parsed into a `PairedServer` by the [Pairing payload parser](pairing-payload-parser.md) (#320, split from #277).

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
fun decodeBase64UrlNoPad(data: String): ByteArray  // java.util.Base64, URL-safe alphabet, NO padding
fun decodeServerStaticPubkey(qr: QrPayload): ByteArray  // -> raw 32 bytes, or throws
```

`decodeServerStaticPubkey` base64-std-decodes `qr.serverStaticPubkey` then requires exactly 32 bytes, throwing `IllegalArgumentException` (field-named message, **no echoed key bytes**, reports an observed length count) on bad base64 **or** length ≠ 32. It **never silently truncates** — this stops a malformed/truncated X25519 key from reaching #275's `Noise_IK` handshake, where a wrong-length key would corrupt or weaken it.

The Java standard decoder accepts unpadded input and noncanonical pad bits.
`DebugBundleTransfer` therefore requires
`base64StdEncode(base64StdDecode(data)) == data` before accepting a chunk; the
shared decoder alone does not enforce canonical encoding. Its tests reject both
`YQ` (missing padding) and `YR==` (nonzero pad bits), which otherwise decode to the
same byte as canonical `YQ==`. This stricter check belongs to the bundle receiver;
the shared helper's behavior is unchanged.

`DebugBundleTransfer` also charges a running total of these post-round-trip decoded
bytes against `MAX_ARCHIVE_BYTES` (32 MiB / `33_554_432`), rejecting a chunk that
would take the accumulated total past it — a peer streaming chunks forever now
settles `INVALID_STREAM` instead of growing an unbounded `List<ByteArray>` until
the platform kills the process (\#764). The bound is charged after the canonical
check above, so the peer's choice of encoding cannot buy it budget, and it is a
running counter rather than a re-sum of the buffered chunks, since re-summing on
every chunk would be quadratic in a peer-chosen chunk count. The 32 MiB figure
is derived from the daemon's own per-session push-queue ceiling (`protocol-mobile.md`
§ Error codes, close code `4413`), which retains at most 32 MiB of base64 payload
and so can never actually deliver an archive larger than roughly 25 MB — the same
32 MiB counted in decoded bytes sits above every deliverable archive while still
bounding the phone. See [Relay/repository coordinator § Host diagnostic archive
transfer](relay-debug-bundle-transfer.md) for
the consumer-facing behavior.

**The two-alphabet trap (#320).** Two different base64 alphabets coexist in one pairing payload: the **outer** QR-string wrapper is base64**url**-no-pad (Go `base64.RawURLEncoding`) — `decodeBase64UrlNoPad` (`Base64.getUrlDecoder()`); the **inner** `server_static_pubkey` is base64-**std**-with-padding — `base64StdDecode` / `decodeServerStaticPubkey`. The two decoders are deliberately co-located here so the trap stays visible. `decodeBase64UrlNoPad`'s load-bearing behavior is **rejecting** the std alphabet's `+`/`/` (throws `IllegalArgumentException`); it tolerates optional `=` padding (real `RawURLEncoding` is unpadded, so it round-trips). Using `base64StdDecode` on the outer wrapper — or `decodeBase64UrlNoPad` on the inner key — is a silent bug. The outer-wrapper consumer is the [Pairing payload parser](pairing-payload-parser.md).

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

## Application payloads (decoded on top of `Envelope`)

Split into [Mobile Protocol v2 — wire models + codec — application payloads](mobile-protocol-v2-wire-layer-application-payloads.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. That section, Application payloads (decoded on top of `Envelope`), moved there verbatim, headings and anchors intact.

## What's deliberately absent

- **No per-model encode/decode wrappers.** The configured `MobileJson` + the base64 helpers *are* the codec. Ten trivial typed wrappers would only inflate the surface.
- **No payload type-zoo.** #273 typed only the two Hello payloads; other application payloads are modeled **one per live consumer**, never speculatively (see [Application payloads](#application-payloads-decoded-on-top-of-envelope) — `conversations` (#316) is the first). `Envelope.payload` stays a generic `JsonElement` until a consumer decodes it.
- **No `payload_encrypted` field.** The Go envelope carries an optional `payload_encrypted` (omitempty); it's dropped on decode today via `ignoreUnknownKeys`. The open question "which consumer adds it as a typed field" was **resolved by [#298](../codebase/298.md): not the Noise session** — its transport surface is raw byte arrays, so it never constructs an application `noise_msg` envelope. The field belongs with the **application message set ([#278](https://github.com/pyrycode/pyrycode-mobile/issues/278))** that builds those envelopes; adding it before then would be speculative and untested.
- **No typed instant for `ts`.** Kept a `String` (RFC3339) this phase; promote when a consumer needs to compare/sort timestamps.
- **No Noise handshake, WS transport, real crypto, or QR scanning.** All downstream (#275/#276/#277).

## Related

- Ticket notes: [`../codebase/273.md`](../codebase/273.md) (this layer), [`../codebase/272.md`](../codebase/272.md) (the serialization substrate it reuses).
- Spec: `docs/specs/architecture/273-mobile-protocol-v2-wire-models-codec.md`.
- Upstream contract: pyrycode `docs/protocol-mobile.md` (§ Message envelope, § Pairing flow) + the server's `internal/protocol` package (mirror of these shapes on the Go side).
- Downstream consumers:
  - **[#298](../codebase/298.md)** [Noise_IK session](noise-ik-session.md) (landed; split from #275) — consumes `Envelope` + the two Hello payloads as handshake early-data via `MobileJson`. Does **not** add `payload_encrypted` (raw-bytes transport surface) — that moved to #278.
  - **#276** relay WS client — frames bytes as `InnerFrameV2`; the Phase 4 `RemoteConnectionStateSource` (see [Connection state](connection-state.md)) will own the WebSocket this transport runs over.
  - **[#320](../codebase/320.md)** [Pairing payload parser](pairing-payload-parser.md) (landed; split from #277) — owns the QR-**string** transport wrapper around `QrPayload` (`decodeBase64UrlNoPad` + the parse/validate/map to `PairedServer`), and calls `decodeServerStaticPubkey` for the 32-byte check. The **#277/#321** fingerprint-confirm gate still sits between the parse and the persist.
  - **#278** application message set — builds the `noise_msg` envelopes and owns the typed `payload_encrypted` field.
  - **[#316](../codebase/316.md)** `conversations` → domain `Conversation`-list + **[#317](../codebase/317.md)** `message` → domain `Message` + **[#318](../codebase/318.md)** `conversation_created` / `conversation_updated` → domain `Conversation` — the three typed (decode-only) application payloads decoded on top of `Envelope` (see [Application payloads](#application-payloads-decoded-on-top-of-envelope)). #316/#317 are consumed by #312's read flow; #318 by the create/promote mutation slices of `RemoteConversationRepository` (both **landed** — [#347](../codebase/347.md) `createDiscussion` decodes `conversation_created`, [#348](../codebase/348.md) `promote` decodes `conversation_updated`, through the **same** `ConversationResponseDto.toConversation()`).
  - **[#346](../codebase/346.md)** the first **outbound** half: `SendMessagePayloadDto` (encode-only `send_message` request) + the generic `ErrorPayload` / `RelayErrorException` correlated-reply types (no `AckPayload` — `ack` is empty `{}`). Built inside the `sendMessage` mutation slice (see [Outbound request encoders + the `ack`/`error` correlated-reply models](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346)); the shared correlation primitives #347/#348 reuse. Consumed by [`RemoteConversationRepository`](remote-conversation-repository.md).
  - **[#347](../codebase/347.md)** the second outbound request encoder: `CreateConversationPayloadDto` (encode-only `create_conversation` request — `is_promoted` + optional `cwd`, `name` unmodeled), in its own file. Reuses #346's `ErrorPayload` / `RelayErrorException` correlated-reply types and routes the typed `conversation_created` success reply through #318's `ConversationResponseDto`. Consumed by `RemoteConversationRepository.createDiscussion`.
  - **[#348](../codebase/348.md)** the third (and last #314) outbound request encoder: `PromoteConversationPayloadDto` (encode-only `promote_conversation` request — all **three** fields `conversation_id`/`name`/`cwd` required/non-null, contrast #347's optional `cwd`), in its own file. Reuses #346's correlated-reply types and routes the typed `conversation_updated` success reply through the **same** #318 `ConversationResponseDto`. Consumed by `RemoteConversationRepository.promote`.
  - **[#359](../codebase/359.md)** the fifth (and first **device-concern**) outbound request encoder: `RegisterPushTokenPayloadDto` (encode-only `register_push_token` request — all three fields `platform`/`token`/`device_name` required/non-null; `platform` a plain `String "fcm"`, `token` never logged, #275 SSOT), in its own file. Reuses #346's `sendAndAwaitReply` + `ErrorPayload`/`RelayErrorException` correlated-reply types verbatim; the reply is a bare `ack` (no response DTO). Consumed by `RemoteConversationRepository.registerPushToken` (a non-`ConversationRepository`-interface device-concern method, dormant until the Firebase sibling adds a live caller).
  - **[#374](../codebase/374.md)** the screen-snapshot exchange (see [The screen-snapshot exchange](#the-screen-snapshot-exchange-374)): `RequestSnapshotPayloadDto` (sixth encode-only request, `request_snapshot` `{conversation_id}`) + `ScreenSnapshotPayloadDto` (decode-only `screen_snapshot` `{conversation_id, text, ts}` — the **first decode-only payload with no domain mapper**; `text` verbatim, `ts` an unparsed `String`), co-located in `SnapshotPayload.kt`. Wire SSOT pyrycode#617/#618; ADR 025 safe-degradation floor. Consumed by [#375](../codebase/375.md) (repository read, **landed**: `RemoteConversationRepository.requestScreenSnapshot` decodes the reply for its `text`), which owns the dispatch + trust decision + no-content-logging invariant.
- Sibling data-layer doc: [Data model](data-model.md) (the non-wire `Conversation`/`Session`/`Message` schema, which deliberately carries **no** serialization annotations — those belong to this Phase 4 wire layer).
- Spike: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`), § "Proven wire contract (byte-accurate)".
