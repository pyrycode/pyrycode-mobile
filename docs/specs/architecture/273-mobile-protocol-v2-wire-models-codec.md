# Architecture: Mobile Protocol v2 wire models + envelope codec (#273)

## Context

Root of the Phase 4 wire layer. The pyrycode v2 server is shipped + e2e-tested, and a
Kotlin↔Go `Noise_IK` round-trip was **proven end-to-end** (spike, 2026-05-29) against a
local relay and the live `wss://pyrycode-relay.pyryco.de`. This ticket lands the typed wire
models + an envelope/inner-frame codec **before** any networking or crypto code, so #275
(Noise session), #276 (relay WS client), and #277 (QR scan + fingerprint) encode/decode
against the exact byte contract the server already speaks.

Same "primitive + internal tests, no consumers yet" shape as #272 (which provisioned
`kotlinx-serialization-json` 1.8.1 + the serialization compiler plugin — both already wired
and smoke-tested, see `app/build.gradle.kts:10,99`). **Reuse that substrate; add no new
serialization dependency.**

This ticket produces only models + codec + round-trip tests. Nothing references them yet.

## Design source

N/A — data/wire layer; no UI, no Figma. The "design source" is the byte contract in the
spike doc and `protocol-mobile.md` (see Files to read first).

## Files to read first

- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSuiteSmokeTest.kt:1-49` — the
  **proven `@Serializable` + `Json` idiom in this exact package** (`Probe`), and the JUnit4
  test shape your new tests must match: `import org.junit.Test`, `org.junit.Assert.assertEquals`,
  `@Test fun`. No JUnit5/Kotest/Truth. Also confirms the serialization plugin actually
  transforms `@Serializable` at runtime (the AGP-9 unknown #272 closed).
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt:1-11` — existing `data/` data-class
  style (immutable, `de.pyryco.mobile.data.*` package). The new models are the `@Serializable`
  analog of this shape. **Do not** copy the `kotlinx.datetime.Instant` import — `Envelope.ts`
  stays a `String` this ticket (AC: not parsed to a typed instant).
- `gradle/libs.versions.toml:13,51,61` — `kotlinxSerialization = "1.8.1"`,
  `kotlinx-serialization-json` library, `kotlin-serialization` plugin. **Already present.**
  Confirm, then add nothing.
- `app/build.gradle.kts:9-10,99` — `alias(libs.plugins.kotlin.serialization)` applied +
  `implementation(libs.kotlinx.serialization.json)`. **Already present.** No build edits.
- spike doc **"Phase 4 — Noise Client Spike Findings"** (`second-brain`,
  `1f4cb-projects/2026-05-02-pyrycode-mobile/phase-4-noise-client-spike-findings.md`),
  § **"Proven wire contract (byte-accurate)"** — the load-bearing source: base64 is
  `base64.StdEncoding` (standard alphabet, **with padding**) at every `data` field; envelope
  is `{id, type, ts, payload, in_reply_to?}`; hello / hello_ack early-data payload shapes.
  Retrieve via QMD (`mcp__qmd__get`, collection `second-brain`).
- upstream `pyrycode/docs/protocol-mobile.md` § *Message envelope* and § *Pairing flow*
  (QMD collection `pyrycode-docs`) — field-order + snake_case lock; confirms `id/type/ts/payload`
  required, `in_reply_to` optional (`omitempty`), and `server_static_pubkey` is
  `base64.StdEncoding` of the raw 32-byte X25519 key (cross-checked against upstream spec
  `432-pair-server-static-pubkey.md` § `Payload` shape).
- (`docs/lessons.md` — not present in this repo; nothing to read.)

## Design

### Package & file layout

Place directly under the `data/network/` namespace #272 seeded (the test side already lives
there). No deeper sub-package — these five models + the codec **are** the wire-layer root.

| File | Kind | Contents |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` | production | the 5 `@Serializable` data classes |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt` | production | top-level `MobileJson` + base64 helpers + `decodeServerStaticPubkey` (no new type) |
| `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt` | test | JUnit4 round-trip + vector + rejection tests |

Exactly **5 exported types** (the models). The codec is top-level functions + one top-level
`val` — deliberately **not** wrapped in an `object`/`class`, to stay within the wire-layer's
minimal surface.

### The five models — field / wire-name / type / default contract

Wire is snake_case; Kotlin properties are camelCase. `@SerialName` carries the mapping where
they differ — this mapping **is the wire contract** for Go interop, not cosmetic. Every field
below is exact; do not abbreviate or reorder relative to the contract.

**`InnerFrameV2`** — the bare WS text payload the phone sends/receives.

| Kotlin property | wire name | type | default | notes |
|---|---|---|---|---|
| `v` | `v` | `Int` | `2` | must be emitted (see Json config) |
| `type` | `type` | `String` | — | `"noise_init"` \| `"noise_resp"` \| `"noise_msg"` |
| `data` | `data` | `String` | — | base64-**std-padded** raw bytes (string on the model) |

No `@SerialName` needed (all three names already match). `data` is a valid property name
(only a hard keyword in `data class`).

**`Envelope`** — inside `noise_msg` plaintext and as handshake early-data.

| Kotlin property | wire name | type | default | notes |
|---|---|---|---|---|
| `id` | `id` | `Long` | — | `uint64` on the wire; `Long` here (AC) |
| `type` | `type` | `String` | — | e.g. `"hello"`, `"hello_ack"` |
| `ts` | `ts` | `String` | — | RFC3339, kept as String (not parsed this ticket) |
| `payload` | `payload` | `JsonElement` | — | **generic carrier** — see § Polymorphic payload |
| `inReplyTo` | `in_reply_to` | `Long?` | `null` | **omitted when null** (AC #5) |

`@SerialName("in_reply_to")` on `inReplyTo`. `payload` uses
`kotlinx.serialization.json.JsonElement` (the `json.RawMessage` analog) — required, non-null
(`protocol-mobile.md` § Message envelope lists `payload` required, `in_reply_to` /
`payload_encrypted` optional).

**`HelloClientPayload`** — payload of the `hello` envelope (in `noise_init` early-data).

| Kotlin property | wire name | type | default |
|---|---|---|---|
| `role` | `role` | `String` | `"client"` |
| `deviceName` | `device_name` | `String` | — |
| `clientVersion` | `client_version` | `String` | — |
| `protocolVersions` | `protocol_versions` | `List<String>` | `listOf("v2")` |
| `token` | `token` | `String` | — |

`@SerialName` on `deviceName`, `clientVersion`, `protocolVersions`. `token` is the
device-pairing **secret** — see § Secret handling.

**`HelloAckPayload`** — payload of the `hello_ack` envelope (in `noise_resp` early-data).

| Kotlin property | wire name | type | default |
|---|---|---|---|
| `protocolVersion` | `protocol_version` | `String` | — |
| `serverId` | `server_id` | `String` | — |
| `connId` | `conn_id` | `String` | — |

`@SerialName` on all three. No defaults — these are server-supplied, decoded only.

**`QrPayload`** — decoded pairing payload (the *decoded* JSON object; the outer QR-string
transport wrapper is **#277's** concern, not this ticket).

| Kotlin property | wire name | type | default |
|---|---|---|---|
| `server` | `server` | `String` | — |
| `relay` | `relay` | `String` | — |
| `token` | `token` | `String` | — |
| `serverStaticPubkey` | `server_static_pubkey` | `String` | — |

`@SerialName("server_static_pubkey")` on `serverStaticPubkey`. Carries the base64-std raw
32-byte X25519 key as a **string** on the model; the 32-byte invariant is enforced by the
codec, not the model (see § Codec). `token` is a **secret** — see § Secret handling.

### Codec surface (`MobileWireCodec.kt`) — top-level, no new type

- `val MobileJson: Json` — the single configured instance. Configuration is **load-bearing**;
  the developer must NOT use a default `Json` (it would drop the defaulted fields). Required flags:
  - `encodeDefaults = true` — so `v:2`, `role:"client"`, `protocol_versions:["v2"]` are
    **emitted** even when equal to their Kotlin defaults. Default `Json` has this `false` and
    would silently omit `"v":2` from the wire — a server-breaking bug.
  - `explicitNulls = false` — so `inReplyTo == null` is **omitted**, not emitted as
    `"in_reply_to": null` (AC #5). Interacts cleanly with `encodeDefaults = true`: non-null
    defaults are still emitted; only nulls are dropped.
  - `ignoreUnknownKeys = true` — tolerate server-added fields (e.g. `payload_encrypted`) on
    decode rather than throwing. Conscious lenient-decode choice for a client (see Security
    review, Trust boundaries).
- `fun base64StdEncode(bytes: ByteArray): String` — standard alphabet, **with** padding.
- `fun base64StdDecode(data: String): ByteArray` — standard alphabet decode.
- `fun decodeServerStaticPubkey(qr: QrPayload): ByteArray` — base64-std-decodes
  `qr.serverStaticPubkey`, then `require(size == 32)`; throws `IllegalArgumentException`
  with a field-named message (no echoed value) on malformed base64 **or** length ≠ 32 (AC #4).

**No per-model encode/decode wrappers.** The configured `MobileJson` *is* the encode/decode
helper — consumers call `MobileJson.encodeToString(value)` / `MobileJson.decodeFromString<T>(s)`
directly (the AC's "encode/decode helpers" = `MobileJson` + the base64 helpers). Adding ten
trivial typed wrappers would violate "keep the named-model count minimal."

**Base64 implementation: `java.util.Base64`** (`getEncoder()` / `getDecoder()`).
- It is the exact JVM analog of Go's `base64.StdEncoding`: standard alphabet, padding on
  encode, padding required-if-present on decode. 1:1 byte-contract correspondence.
- Zero opt-in, stable, available in JVM unit tests (no Robolectric) and on min SDK 33
  (`java.util.Base64` is API 26+). This deliberately avoids the `kotlin.io.encoding.Base64`
  `@ExperimentalEncodingApi` opt-in question — a deferred-decision rework loop is the failure
  mode to avoid here.
- **Isolated CMP seam:** `java.util.Base64` is JVM-only. Per CLAUDE.md the data layer stays
  portable for the Compose-Multiplatform walk-back, but this is one well-contained helper pair
  in the codec (not the models) — a trivial `expect/actual` *if/when* iOS lands. The models
  themselves stay fully portable. Conscious, documented trade-off.

### Polymorphic payload (the "generic carrier")

`Envelope.payload` is `JsonElement`, not a sealed hierarchy. This is the minimal way to make
the envelope polymorphic-by-`type` without a payload type-zoo (per ticket Technical Notes).
Consumer tickets bridge typed ↔ generic at the edges:

- decode: `MobileJson.decodeFromJsonElement<HelloAckPayload>(envelope.payload)` when
  `envelope.type == "hello_ack"`.
- encode: `payload = MobileJson.encodeToJsonElement(HelloClientPayload(...))` when building a
  `hello` envelope.

This ticket ships only the two Hello payloads as typed models + the generic `JsonElement`
carrier. Other application payloads (`send_message`, `conversations`, …) are added by their
consumer tickets — **do not** model them now.

### Secret handling — redacting `toString()` (design requirement)

`HelloClientPayload.token` and `QrPayload.token` are the device-pairing **secret** (travels
inside the encrypted Noise early-data; never a header under v2). A Kotlin `data class`
auto-generates a `toString()` that renders **every** field — so a downstream
`Log.d("hello: $payload")` or a crash-reporter stack frame would leak the token to Logcat.
That is a deterministic, well-known Android footgun and this is a `security-sensitive` ticket.

**Requirement:** override `toString()` on `HelloClientPayload` and `QrPayload` to render the
class with the `token` value replaced by `"***"` (other fields may show, for debuggability).
This is the deterministic safety net (different fabric from the stochastic "developer
remembers not to log it").

Two invariants the developer must preserve:
- **Do NOT override `equals` / `hashCode`** — the round-trip tests assert structural equality
  via the auto-generated `equals`. Override `toString()` only.
- Redaction affects `toString()` **only**, never serialization. kotlinx-serialization encodes
  from the `@Serializable` descriptor, not `toString()`, so `MobileJson.encodeToString(payload)`
  still emits the **real** token (the wire needs it). Confirm with a test that the encoded JSON
  contains the real token while `toString()` does not.

`Envelope` gets **no** redaction override — it's the generic carrier and doesn't know its
`payload` is sensitive. Logging discipline for raw envelopes / decrypted plaintext frames is
the consumer tickets' concern (#275/#276); the typed `HelloClientPayload` is the safe-to-log
representation.

## State + concurrency model

**None.** This is a pure-data ticket:

- All five models are immutable `@Serializable data class`es. No `MutableState`, no `StateFlow`,
  no `ViewModel`, no `viewModelScope`, no dispatchers — there is no UI or coroutine surface here.
- `MobileJson` is an immutable, thread-safe singleton (`Json` instances are safe for concurrent
  use once configured). The base64 helpers and `decodeServerStaticPubkey` are pure, stateless
  functions. Safe to call from any thread/dispatcher; the choice of dispatcher belongs to the
  consumer tickets that do I/O.
- No lifecycle, no cancellation, no shutdown behavior — nothing is launched.

## Error handling

| Failure mode | Where | Result type / behavior |
|---|---|---|
| Malformed JSON frame on decode | `MobileJson.decodeFromString` | throws `kotlinx.serialization.SerializationException` — **propagated**, not swallowed. UI surfacing is the consumer's call (#276). |
| Unknown server field on decode | `MobileJson` (`ignoreUnknownKeys=true`) | silently ignored — no throw (forward-compat with server additions). |
| `server_static_pubkey` not valid base64 | `decodeServerStaticPubkey` | throws `IllegalArgumentException`, field-named message, **no echoed value**. |
| `server_static_pubkey` decodes to ≠ 32 bytes | `decodeServerStaticPubkey` (`require`) | throws `IllegalArgumentException`; message names the field + the observed length count (a count, not the key bytes). Never silently truncates (AC #4). |

No network / file / permission failures exist in this layer — it performs only in-memory
transforms. Error **messages** follow the upstream leak-discipline (`432-pair-server-static-pubkey.md`):
decode errors name the failure category, not the input bytes. The pubkey is public material,
so echoing it would not be a *secret* leak, but the no-echo discipline is kept for consistency.

## Testing strategy

Unit only (`./gradlew test`) — no instrumented tests, no `ComposeTestRule`, no `runTest`
(nothing async). JUnit4, mirroring `NoiseSuiteSmokeTest.kt` (`@Test`, `org.junit.Assert.*`).
One file: `MobileWireCodecTest.kt`. Per-model round-trip = decode-the-fixture **and**
encode-then-decode both recover the value (AC #2). Write these as scenarios in the project's
test idiom; do not paste full bodies.

**Per-model round-trip (one test each):**
- `InnerFrameV2` — decode the exact fixture `{"v":2,"type":"noise_init","data":"<b64>"}` →
  assert fields; re-encode → assert the encoded string **contains** `"v":2` (guards the
  `encodeDefaults` subtlety) and decode-equals the original.
- `Envelope` (with `in_reply_to`) — decode `{"id":1,"type":"hello","ts":"2026-05-29T...","payload":{...},"in_reply_to":2}`
  → `inReplyTo == 2`, `id == 1L`, `payload` is the JsonObject; re-encode contains `"in_reply_to":2`.
- `Envelope` (without `in_reply_to`) — decode the same minus the key → `inReplyTo == null`.
- `HelloClientPayload` — decode a full fixture → camelCase props; re-encode emits all snake_case
  keys including `"role":"client"` and `"protocol_versions":["v2"]`.
- `HelloAckPayload` — decode `{"protocol_version":"v2","server_id":"s","conn_id":"c"}` →
  encode-then-decode recovers the value.
- `QrPayload` — decode `{"server":"...","relay":"...","token":"...","server_static_pubkey":"<b64-32>"}`
  → encode-then-decode recovers the value.

**Defaults emitted (guards `encodeDefaults = true`):**
- Construct `InnerFrameV2` and `HelloClientPayload` using their **defaults** (`v`, `role`,
  `protocol_versions`); assert the encoded JSON contains `"v":2`, `"role":"client"`,
  `"protocol_versions":["v2"]`. A default `Json` would fail this.

**`in_reply_to` omitted when absent (AC #5):**
- Encode an `Envelope` with `inReplyTo = null`; assert the encoded string does **not** contain
  `in_reply_to`; decode that string → `inReplyTo == null`.

**Base64 standard-alphabet-with-padding (AC #3) — known vectors, pinned literals:**
- `base64StdEncode(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) == "//8="` — proves the standard
  alphabet (`/`, not url-safe `_`) **and** padding (`=`) in one literal. Assert it does **not**
  equal the url-safe/unpadded form `"__8"`.
- `base64StdEncode(byteArrayOf(0xFF.toByte())) == "/w=="` — pins the `==` two-char padding case.
- Round-trip property on an arbitrary 32-byte vector:
  `base64StdDecode(base64StdEncode(v)).contentEquals(v)`. (These two literals are independent
  of the impl — do not compute the expected string from `base64StdEncode` at test time.)

**`server_static_pubkey` → 32 bytes + rejection (AC #4):**
- valid 32-byte base64 (e.g. base64-std of 32 zero bytes) → `decodeServerStaticPubkey` returns
  a 32-byte array.
- base64 of 31 bytes **and** of 33 bytes → each throws `IllegalArgumentException`.
- `serverStaticPubkey = "!!!"` (invalid base64) → throws `IllegalArgumentException`.

**Secret redaction (security-sensitive):**
- For `HelloClientPayload` and `QrPayload` built with a sentinel token (e.g. `"SECRET_TOKEN"`):
  assert `payload.toString()` does **not** contain the token and **does** contain `***`, while
  `MobileJson.encodeToString(payload)` **does** contain the real token. (Proves redaction is
  `toString`-only and never touches the wire bytes.)

## Open questions

- **`Envelope.payload` optionality.** Modeled as required non-null `JsonElement`, matching
  `protocol-mobile.md` (payload required). If a payload-less control envelope appears in a
  consumer ticket, that ticket relaxes it to `JsonElement? = null` then — out of scope now.
- **`payload_encrypted`.** The Go envelope carries an optional `payload_encrypted` (omitempty)
  for the encrypted-application-payload path. Not modeled here (encryption is #275);
  `ignoreUnknownKeys = true` drops it on decode without error until #275 models it. Flagged so
  #275 knows to add the field rather than assume it round-trips today.
- **`kotlin.io.encoding.Base64` vs `java.util.Base64`.** Spec picks `java.util.Base64` to kill
  the opt-in risk (see § Base64 implementation). If the CMP walk-back ever lands, revisit as an
  `expect/actual` over the two codec helpers — a contained change, models untouched.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** The codec is the single explicit boundary where untrusted wire bytes
  (relay/server, post-Noise-decrypt) become typed in-process values:
  `MobileJson.decodeFromString` / `decodeServerStaticPubkey`. Decode validates **shape**, not
  semantics — `Envelope.payload` stays an untrusted `JsonElement` until a consumer decodes +
  validates it; downstream callers hold "raw JSON, not yet trusted" by the type itself (a
  `JsonElement`, not a typed payload). `ignoreUnknownKeys = true` is a conscious lenient-decode
  choice (a client tolerating server-added fields is safer than hard-failing on forward-compat
  additions); it drops unknown keys rather than rejecting. No MUST FIX.
- **[Tokens / secrets / credentials]** `HelloClientPayload.token` and `QrPayload.token` are the
  device-pairing secret. The real risk for a *models* ticket is the `data class` auto-`toString()`
  leaking the token to Logcat / crash reporters. **Addressed in-design** by the redacting
  `toString()` requirement on both classes (deterministic safety net; serialization unaffected,
  so the wire still carries the real token). Token generation/rotation/revocation and **secure
  storage** (`EncryptedSharedPreferences` / Android Keystore per the spike doc) are **OUT OF
  SCOPE → #277** (QR scan / credential storage) and the Noise transport in #275 — this ticket
  neither generates, stores, nor transmits the token, only models its in-memory shape. No MUST FIX.
- **[File / storage operations]** N/A — this ticket performs **no** file or storage I/O. The
  decoded `QrPayload` secrets are persisted by #277; that ticket owns `EncryptedSharedPreferences`
  / Keystore, `allowBackup` exclusion, and atomic-write concerns.
- **[Inter-process / Android attack surface]** N/A — no `Activity` / `Service` /
  `BroadcastReceiver` / deep link / `PendingIntent` / `WebView`. The QR-**string** transport
  (a potential intent/deep-link surface, and possibly itself base64url-wrapped) is explicitly
  **OUT OF SCOPE → #277**; this ticket models only the *decoded* `QrPayload` JSON object.
- **[Cryptographic primitives]** No crypto is *executed* here. The one security-relevant
  invariant is `decodeServerStaticPubkey`'s **reject-if-not-32-bytes** check (AC #4): it stops a
  malformed/truncated X25519 public key from silently reaching #275's `setPublicKey(pubkey, 0)`
  (where a wrong-length or zero-padded key would corrupt or weaken the `Noise_IK` handshake).
  The pubkey is **public** material (no wipe, no constant-time compare needed). Base64 is
  `StdEncoding` via `java.util.Base64` — standards-grade, no alphabet confusion, **no hand-rolled
  crypto**. The actual Noise handshake is #275; pubkey *authenticity* (fingerprint confirm) is
  #277. No MUST FIX.
- **[Network & I/O]** N/A — no network in this layer. The inbound frame-size cap (65519 B
  plaintext per the spike doc), OkHttp timeouts, TLS config, and cert pinning are **#276's**
  (WS client) concern, enforced **before** bytes reach this codec — so the codec assumes
  already-capped input and adds no size limit of its own.
- **[Error messages / logs / telemetry]** Decode errors follow the upstream leak-discipline
  (`432-pair-server-static-pubkey.md`): category, not input bytes. `decodeServerStaticPubkey`
  reports a length **count**, never the key. The redacting `toString()` closes the data-class
  log-leak path. Raw-envelope / decrypted-plaintext logging discipline (where a `hello` envelope's
  `payload` JSON would contain a token) is the consumer tickets' concern (#275/#276) — named,
  deferred. No telemetry/analytics introduced. No MUST FIX.
- **[Concurrency]** N/A — immutable models, a thread-safe `Json` singleton, and pure stateless
  functions. No coroutines, no shared mutable state, no `StateFlow`, no hot/cold-flow surface.
- **[Threat model alignment]** This ticket's contribution to the v2 threat model is twofold:
  (a) the 32-byte pubkey validation hardens the input to #275's handshake against a
  malformed-key class, and (b) the token-redacting `toString()` removes a Logcat leak vector.
  Relay-MITM-with-replaced-pubkey (→ #277 fingerprint confirm + #275 `Noise_IK`), token theft in
  transit (→ #275 encrypted early-data), and secret-at-rest (→ #277 secure storage) are named and
  **deferred to their owning tickets**.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-30
