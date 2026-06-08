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

`Envelope.payload` stays a generic `JsonElement` until a consumer decodes it into a typed payload DTO. Those application-payload DTOs — and the pure functions that map them to domain types — live in `data/network/` alongside the wire models, **one file per payload `type`**, with **decode as the single validate boundary**: a malformed/field-incomplete payload throws at `MobileJson.decodeFromJsonElement` rather than producing a partial domain object.

The first one landed in [#316](../codebase/316.md): `ConversationsPayload` / `ConversationSummaryDto` (`ConversationsPayload.kt`) for the `conversations` reply, plus a pure `ConversationsPayload.toConversations(): List<Conversation>` mapper to the domain [`Conversation`](data-model.md). Two precedents it sets for the payloads that follow (#317 `message`, #318 write-responses):

- **Object-wrapped, not a bare array.** The `conversations` payload is `{ "conversations": [ … ] }` (server SSOT `conversations_read.go`, #273), so it decodes to a wrapper DTO whose one field is the row list — **not** a top-level `List<…>`.
- **Timestamps decode to `kotlinx.datetime.Instant` at the DTO** (via an explicit `InstantIso8601Serializer`) — a deliberate departure from `Envelope.ts` staying a `String`: here a domain field (`lastUsedAt`) consumes the instant, so validating the RFC 3339 string at decode keeps the mapper total. One consequence: a malformed timestamp surfaces as **`IllegalArgumentException`** (kotlinx-datetime's `DateTimeFormatException`), **not** a `SerializationException` — a consumer catching decode failures must handle both families (or `Exception`).

Wire-absent domain fields are filled with documented list-tier defaults at the mapping site, never `null`-punned (for `conversations`: `currentSessionId=""`, `sessionHistory=emptyList()`, `isSleeping=false`, `archived=false` — full session/sleep/archive state arrives via the detail + message read paths). These payloads are **decode-only** (server → phone), so they get no encode round-trip test.

The second landed in [#317](../codebase/317.md): `MessagePayloadDto` + a closed `WireRole` enum (`MessagePayload.kt`) for the `message` payload, mapped to a domain [`Message`](data-model.md) by `MessagePayloadDto.toMessage(envelope, sessionId)`. It follows #316's per-payload shape with three wrinkles worth folding into the pattern:

> **Premise correction ([#346](../codebase/346.md)).** #317's original framing called this DTO "also the `send_message` response echo" — i.e. assumed `send_message` returns the sender its own persisted `Message` for this mapper to decode. That is **false** against the server SSOT: `send_message`'s only sender-correlated reply is an empty `ack` (success) or an `error` (failure); a `message` envelope is a user-echo to *other* paired devices or the assistant's *later, unsolicited* reply (`in_reply_to: null`), **never** a sender echo. So this mapper has **no** `send_message`-response role — it serves the live-stream / other-device / assistant cases only. The sender's own thread updates via #346's local confirmed-insert. See [[phase4-send-message-acks-not-message-echo]]. (The stale "echo" phrasing still in the code comments and the [#317](../codebase/317.md) ticket file predates this correction and is harmless.)

- **The payload IS the object, not object-wrapped.** No wrapper DTO — `Envelope.payload` decodes straight to `MessagePayloadDto` (server SSOT `internal/protocol/messaging.go`, #272). Object-wrap vs bare is per-payload; check the Go struct, don't assume #316's wrapper.
- **Two validate sites, split across decode and map.** `WireRole` models only the *domain-mappable* roles (`user`/`assistant`), so an unmappable `system` or unknown role is a free `SerializationException` at decode — the technique to reuse whenever a wire enum carries values with no domain target. Meanwhile the message timestamp is the **envelope `ts`** parsed by `Instant.parse` **in the mapper**, so a malformed `ts` throws `IllegalArgumentException` *there*. (Same "a consumer must catch both `SerializationException` and `IllegalArgumentException`" consequence for #312 as #316 — but here the second throw is at *map*, not decode.)
- **A non-wire domain field can be caller-supplied rather than defaulted.** `sessionId` isn't on the `message` payload, so the mapper takes it as a parameter (#312 injects the active session id) instead of giving it a placeholder default like #316's list-tier fields — the value genuinely exists at the call site.

The third landed in [#318](../codebase/318.md): `ConversationResponseDto` (`ConversationResponseDto.kt`) for the **mutation-response** payloads `conversation_created` (reply to `create_conversation`) and `conversation_updated` (reply to `promote_conversation`), mapped to a domain [`Conversation`](data-model.md) by `ConversationResponseDto.toConversation()`. Consumed by the future create/promote mutation slices of `RemoteConversationRepository` (not #312's read flow). Two more wrinkles fold into the pattern:

- **One DTO models multiple `type`-strings when they share a shape.** `conversation_created` and `conversation_updated` carry the *identical* field set (`id`, `is_promoted`, `name?`, `cwd`, `last_used_at`; server SSOT `conversations_write.go`, #274) and differ only in `cwd`↔`name` key order — and kotlinx decodes by name, not position — so one `ConversationResponseDto` decodes both losslessly. The `_created`/`_updated` distinction is a `type`-string routing concern at the `Envelope.type` layer, not a shape concern at decode. It's a **bare object** (like #317, unlike #316's wrapper) and carries no `last_message_ts` — exactly one timestamp, validated at decode like #316 (so the mapper is a total, throw-free field copy — the #316 posture, not #317's map-time throw). It reuses #316's wire-absent-field rule **verbatim** (four list-tier placeholders, cross-referenced, no shared helper extracted — deferred until a third consumer of *that rule* appears).
- **A single-class payload file is named after its class.** ktlint `standard:filename` (spotless) forces a file with exactly one top-level class to be named after it, so the file is `ConversationResponseDto.kt`, **not** the spec's proposed `ConversationResponsePayload.kt`. The siblings escape only because each holds two declarations (#316: wrapper + row DTO; #317: DTO + `WireRole` enum). Name future single-DTO payload files after the DTO. See [[ktlint-filename-rule-single-class]].

### Outbound request encoders + the `ack`/`error` correlated-reply models (#346)

The three payloads above are all **decode-only** (server → phone). [#346](../codebase/346.md)'s `sendMessage` adds the first half of the **other** direction — outbound **request encoders** and the generic **correlated-reply** models that complete the request↔reply round-trip. These are built **inside the mutation tickets** (#346/#347/#348), not as a decode-only mapping slice — there is no "request-mapping" slice; the v2 wire splits decode (shared inbound slices #316/#317/#318) from encode (per-mutation) on a direction axis. See [[phase4-request-encoders-live-in-mutation-tickets]].

- **`SendMessagePayloadDto` (`MessagePayload.kt`)** — the second **encode-only request DTO** (after #313's `BackfillSincePayloadDto`): `@SerialName` snake_case, Go-struct field order, all three fields (`conversation_id`, `message_id`, `text`) required. Encode-only request DTOs get no decode round-trip and no domain mapper — they're built and `encodeToJsonElement`'d straight into the request `Envelope.payload`. `message_id` is **client-generated** (a minted UUID) — distinct from the request *envelope* id used for `ack`/`error` correlation. Wire SSOT: server `internal/protocol/messaging.go` `SendMessagePayload` (#272).
- **`CreateConversationPayloadDto` (`CreateConversationPayloadDto.kt`, [#347](../codebase/347.md))** — the third **encode-only request DTO**, the `create_conversation` request `createDiscussion` sends. In its **own file** (ktlint single-public-type rule — symmetric with the decode-side `ConversationResponseDto.kt`, **not** added to `MessagePayload.kt`). Models **only the two fields the create flow sends**: `@SerialName("is_promoted") isPromoted: Boolean = false` (always sent `false` — `createDiscussion` only ever creates *unpromoted* discussions; promotion is the separate #348 flow) and `cwd: String? = null`. **`name` is intentionally unmodeled** — discussions are *server-auto-named*, so the create flow never sends a `name`; the reply comes back `name: null`. This is the encode-only discipline: model what is *sent*, not the full decode surface (#318's job) — the KDoc forbids "add `name` for completeness". **Load-bearing encoding:** under `MobileJson` (`explicitNulls = false`) a null `cwd` is **omitted** (not `"cwd":null`), so `createDiscussion(null)` → `{"is_promoted":false}`; the server's `*string` `Cwd` (no `omitempty`) decodes an absent key identically to `null`, meaning "server assigns the scratch cwd" (#274 sanctions filling server-side defaults when the field is absent). Do not force an explicit `"cwd":null`. Wire SSOT: server `internal/protocol/conversations_write.go` `CreateConversationPayload` (#274). Built inside the #347 mutation slice, not a shared mapping slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`PromoteConversationPayloadDto` (`PromoteConversationPayloadDto.kt`, [#348](../codebase/348.md))** — the fourth **encode-only request DTO**, the `promote_conversation` request `promote` sends to turn a scratch discussion into a named channel. In its **own file** (ktlint single-public-type rule — symmetric with `CreateConversationPayloadDto.kt`). All **three fields are required/non-null** `String`: `@SerialName("conversation_id") conversationId`, `name`, `cwd` — declaration order mirrors the Go struct `PromoteConversationPayload` (#274). **Contrast `CreateConversationPayloadDto`'s optional `cwd`:** here `cwd` is non-null and always sent (there is no `explicitNulls` elision to reason about — the encoded payload always has all three keys), and the KDoc explicitly forbids relaxing it to nullable. The caller resolves a null `workspace` to the conversation's existing cwd from the read projection *before* encoding (see [`remote-conversation-repository.md` § `promote`](remote-conversation-repository.md#promoteconversationid-name-workspace--the-third-mutation-348)), so the wire always carries a concrete `cwd`. Encode-only — model what is *sent*, not the full decode surface (#318's `ConversationResponseDto` already models the `conversation_updated` reply). Wire SSOT: server `internal/protocol/conversations_write.go` `PromoteConversationPayload` (#274). Built inside the #348 mutation slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`RegisterPushTokenPayloadDto` (`RegisterPushTokenPayloadDto.kt`, [#359](../codebase/359.md))** — the fifth **encode-only request DTO**, the `register_push_token` request `RemoteConversationRepository.registerPushToken` sends to tell the paired daemon where to push a wake notification. In its **own file** (ktlint single-public-type rule — symmetric with the sibling DTOs). All **three fields required/non-null** `String`: `platform`, `token`, `@SerialName("device_name") deviceName` — declaration order mirrors the Go struct `RegisterPushTokenPayload` (spec #275). **`platform` is a plain `String`, not an enum** — the value is the constant `"fcm"` (Android) supplied at the call site, and an encode-only DTO models only what it sends (the server also keeps `Platform` a `string` and accepts `"apns"` for iOS, out of scope). `device_name` equals `HelloClientPayload.deviceName` (the value `hello` already sends); the server dedupes the `(platform, token, device_name)` triple, so the client does no dedupe. The `token` is a sensitive FCM routing credential held only transiently for one round-trip and **never logged** (the #346 no-secrets posture). Like `send_message`, the reply is a bare `ack` — **no response DTO modeled**. The first **device-concern** wire request (push registration is not a conversation operation — see [`remote-conversation-repository.md` § `registerPushToken`](remote-conversation-repository.md#registerpushtokentoken--the-device-concern-push-registration-359)). Wire SSOT: server `internal/protocol/push.go` `RegisterPushTokenPayload` (#275), golden `internal/protocol/testdata/register_push_token.json`, `protocol-mobile.md` § `register_push_token`. Built inside the #359 slice ([[phase4-request-encoders-live-in-mutation-tickets]]).
- **`ErrorPayload(code, message, retryable)` (`MobileWireModels.kt`)** — the **decode-only** payload of an `error` envelope, the failure reply correlated to a request via `Envelope.inReplyTo`. Single-word wire names, no `@SerialName`. The server also emits `retry_after_s`; it is **intentionally not modeled** — `MobileJson`'s `ignoreUnknownKeys = true` tolerates it on decode (add it as a nullable `@SerialName("retry_after_s")` field if a retry-UI ever needs it). Wire SSOT: server `internal/protocol/handshake.go` `ErrorPayload`.
- **`RelayErrorException(code, retryable, message)` (`MobileWireModels.kt`)** — **not** a wire model; the thrown form of an `ErrorPayload` whose `code` is not a contract-mapped domain error. Carries the structured fields so a ViewModel can branch on `retryable` and surface `code`/`message`. The `conversation.not_found` code maps to `IllegalArgumentException` instead (cross-impl parity with the fake's unknown-conversation throw); every other code — and a malformed/undecodable `error` payload — surfaces as this. Its `message` is the server's human-facing text only, **never** the user's message content.
- **No `AckPayload` model — deliberate.** A success `ack` payload is empty `{}` and is **never decoded**; correlation is purely on `Envelope.inReplyTo`. An unused `AckPayload` would be dead code, so it is omitted (a conscious choice, not an oversight).

The `ErrorPayload` / `RelayErrorException` / registry primitives are the **shared correlation
infrastructure** #347 (`createDiscussion`) and #348 (`promote`) reuse verbatim — each mutation adds only
its own `create_conversation` / `promote_conversation` **request encoder** (built in its own ticket,
above) and its own success-`type` handling. Their *success* reply is **not** a bare `ack` (that's
`send_message`'s) but a typed `conversation_created` / `conversation_updated` envelope, so those tickets
route that reply through the same completion arm and **decode #318's `ConversationResponseDto`** from the
payload (in the caller's coroutine, never the collector). Both **landed**: [#347](../codebase/347.md) for
`conversation_created` (`CreateConversationPayloadDto`; the typed reply decoded into the returned
`Conversation`) and [#348](../codebase/348.md) for `conversation_updated` (`PromoteConversationPayloadDto`;
the **same** `ConversationResponseDto` decodes both replies) — so all three #314 mutations now ship live.
The corrected wire premise that motivates all of this — `send_message` returns an `ack`/`error`, **not** a
`Message` echo — is documented above (the #346 premise-correction callout) and in
[[phase4-send-message-acks-not-message-echo]].

### The screen-snapshot exchange ([#374](../codebase/374.md))

The first wire **exchange modeled as a co-located request + event pair in one file** —
`SnapshotPayload.kt` holds both halves because they are one logical round-trip (the in-file precedent is
`BackfillSincePayloadDto` + `MessageChunkPayloadDto` sharing `MessagePayload.kt`). It is the
parser-independent **floor** of ADR 025's safe-degradation strategy: the phone asks the daemon for a
one-shot **text** picture of the current claude screen, and the daemon renders it via tui-driver inside
the substrate seal — depending on no screen parser, so it survives any parser break. Wire SSOT: server
`internal/protocol/snapshot.go` (pyrycode#617, merged; daemon handler #618). This slice is the
**wire-vocabulary half only** — no repository method, no dispatch, no trust decision; those landed in the
consumer [#375](../codebase/375.md) (`ConversationRepository.requestScreenSnapshot`).

- **`RequestSnapshotPayloadDto` (`SnapshotPayload.kt`)** — the sixth **encode-only request DTO**:
  `request_snapshot` `{conversation_id}` (phone → daemon control), a single `@SerialName("conversation_id")`
  field, narrowed from `RegisterPushTokenPayloadDto`'s shape. Built and `encodeToJsonElement`'d straight
  into the request `Envelope.payload` by the consumer (#375); no domain mapper, no round-trip test.
- **`ScreenSnapshotPayloadDto` (`SnapshotPayload.kt`)** — the **first decode-only payload with NO domain
  mapper**. `screen_snapshot` `{conversation_id, text, ts}` (daemon → phone event), all three required +
  non-null `String` in Go-struct order (a malformed frame fails closed with `SerializationException`,
  never a partial value). Unlike every prior decode-only payload (#316 → `Conversation`-list, #317 →
  `Message`, #318 → `Conversation`), this DTO is **terminal display data**, not a wire→domain edge — the
  consumer reads `.text` directly, so there is no `toX()` mapper and no domain target. Two modeling
  decisions are load-bearing:
  - **`text` is modeled verbatim — no trim/normalize/sanitize.** Decode fidelity is the *entire point* of
    the parser-independent floor; the DTO must reproduce the literal screen. The no-raw-bytes guarantee
    (ADR 025: rendered text only, never raw control codes) is enforced **server-side** by the daemon
    renderer (the trusted authenticated peer), not re-litigated client-side. A test pins a multi-line /
    leading-whitespace value round-tripping byte-for-byte.
  - **`ts` is a plain `String`, deliberately unparsed.** A departure from #316/#318, where a payload
    timestamp decodes to `Instant` via `InstantIso8601Serializer` *because* a domain field consumes it.
    Here nothing consumes `ts` (the consumer returns `text` only), so parsing it would defend nothing.
    **Refined rule:** parse-at-decode a payload timestamp only when a domain field consumes the instant;
    otherwise keep it a `String` like `Envelope.ts`.

The envelope `type` strings are defined by the consumer ([#375](../codebase/375.md)) as
`TYPE_REQUEST_SNAPSHOT` / `TYPE_SCREEN_SNAPSHOT` companion constants in `RemoteConversationRepository`,
joining its **complete** mobile-side `TYPE_*` registry — every wire type (including `send_message` /
`list_conversations`) is a named constant there and every request envelope uses `type = TYPE_*`, with no
inline `type = "..."` literals. (This **corrects** the prediction recorded when #374 landed that these
would be inline literals "with no mobile-side type-constants registry" — the registry already existed in
`RemoteConversationRepository`; #375 simply extended it. The registry is local to that class, not a shared
module mirroring the server's `codes.go`.) Because `SnapshotPayload.kt` holds **two** public types, ktlint `standard:filename` does not fire,
so it keeps the spec's `SnapshotPayload.kt` name — unlike #318's single-class rename ([[ktlint-filename-rule-single-class]]).
`ScreenSnapshotPayloadDto` keeps the `data class` auto-`toString()` (which includes `text`) — matching the
content-bearing `MessagePayloadDto`; `toString`-redaction is reserved for the `token` credential. The
no-content-logging obligation (the #346 posture) was a **code-level invariant on the consumer**, now
honored by [#375](../codebase/375.md)'s `requestScreenSnapshot` (which adds zero log calls — the request,
reply, `conversationId`, and decoded `text` are never logged), not this zero-log-call wire slice.

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
  - **[#346](../codebase/346.md)** the first **outbound** half: `SendMessagePayloadDto` (encode-only `send_message` request) + the generic `ErrorPayload` / `RelayErrorException` correlated-reply types (no `AckPayload` — `ack` is empty `{}`). Built inside the `sendMessage` mutation slice (see [Outbound request encoders + the `ack`/`error` correlated-reply models](#outbound-request-encoders--the-ackerror-correlated-reply-models-346)); the shared correlation primitives #347/#348 reuse. Consumed by [`RemoteConversationRepository`](remote-conversation-repository.md).
  - **[#347](../codebase/347.md)** the second outbound request encoder: `CreateConversationPayloadDto` (encode-only `create_conversation` request — `is_promoted` + optional `cwd`, `name` unmodeled), in its own file. Reuses #346's `ErrorPayload` / `RelayErrorException` correlated-reply types and routes the typed `conversation_created` success reply through #318's `ConversationResponseDto`. Consumed by `RemoteConversationRepository.createDiscussion`.
  - **[#348](../codebase/348.md)** the third (and last #314) outbound request encoder: `PromoteConversationPayloadDto` (encode-only `promote_conversation` request — all **three** fields `conversation_id`/`name`/`cwd` required/non-null, contrast #347's optional `cwd`), in its own file. Reuses #346's correlated-reply types and routes the typed `conversation_updated` success reply through the **same** #318 `ConversationResponseDto`. Consumed by `RemoteConversationRepository.promote`.
  - **[#359](../codebase/359.md)** the fifth (and first **device-concern**) outbound request encoder: `RegisterPushTokenPayloadDto` (encode-only `register_push_token` request — all three fields `platform`/`token`/`device_name` required/non-null; `platform` a plain `String "fcm"`, `token` never logged, #275 SSOT), in its own file. Reuses #346's `sendAndAwaitReply` + `ErrorPayload`/`RelayErrorException` correlated-reply types verbatim; the reply is a bare `ack` (no response DTO). Consumed by `RemoteConversationRepository.registerPushToken` (a non-`ConversationRepository`-interface device-concern method, dormant until the Firebase sibling adds a live caller).
  - **[#374](../codebase/374.md)** the screen-snapshot exchange (see [The screen-snapshot exchange](#the-screen-snapshot-exchange-374)): `RequestSnapshotPayloadDto` (sixth encode-only request, `request_snapshot` `{conversation_id}`) + `ScreenSnapshotPayloadDto` (decode-only `screen_snapshot` `{conversation_id, text, ts}` — the **first decode-only payload with no domain mapper**; `text` verbatim, `ts` an unparsed `String`), co-located in `SnapshotPayload.kt`. Wire SSOT pyrycode#617/#618; ADR 025 safe-degradation floor. Consumed by [#375](../codebase/375.md) (repository read, **landed**: `RemoteConversationRepository.requestScreenSnapshot` decodes the reply for its `text`), which owns the dispatch + trust decision + no-content-logging invariant.
- Sibling data-layer doc: [Data model](data-model.md) (the non-wire `Conversation`/`Session`/`Message` schema, which deliberately carries **no** serialization annotations — those belong to this Phase 4 wire layer).
- Spike: vault doc *"Phase 4 — Noise Client Spike Findings"* (`second-brain`, `2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`), § "Proven wire contract (byte-accurate)".
