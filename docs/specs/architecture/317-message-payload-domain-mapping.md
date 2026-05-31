# Architecture: `message` payload → domain `Message` mapping (#317)

## Context

Phase 4 chain, `data/`-layer slice. `RemoteConversationRepository` (#312) needs to turn the
Mobile Protocol v2 `message` payload (server → phone) into a domain `Message` for the
message-thread read path. The **same** `message` payload is what the server returns in answer to
a `send_message`, so the response-echo mapping is covered by this one boundary too.

Today `Envelope.payload` is an **untrusted** `JsonElement` (`MobileWireModels.kt:34-35`, KDoc:
"stays untrusted raw JSON until a consumer decodes and validates it"). This slice adds the one
typed `@Serializable` DTO surface for the `message` application payload plus the pure function
that maps it (together with its envelope) to the existing domain `Message` in `data/model/`. It
is the **single decode-and-validate boundary** for that payload: malformed / field-incomplete /
unmappable-role wire JSON surfaces a typed failure, never a crash or a partially-populated /
`null`-punned domain object.

Scope is `message` → `Message` **only**. Per the ticket's scope correction, the `message`
payload carries **no** `session_id`, **no** session metadata, and **no** boundary field, so this
slice emits **no** `Session` and **no** `ThreadItem.SessionBoundary` — those are synthetic /
sequence-level and owned by the observe slice (#312, see `FakeConversationRepository.buildThreadItems`).
No flows, no repository class, no networking. See [[v2-app-payload-shapes-ssot]]. Sibling #316
(`conversations` → `Conversation`) is the structural twin this spec mirrors.

## Design source

N/A — pure `data/`-layer decode/map; no UI, no Figma. The "design source" is the byte contract:
server SSOT `internal/protocol/messaging.go` `MessagePayload` (#272) and `protocol-mobile.md
§ Application message types → send_message / message`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt:5-22` — the domain **target**: the
  `Message` data class (7 fields, `toolCall` defaults `null`), the `Role` enum
  (`{User, Assistant, Tool}` — note: **no `System`**), and `ToolCall`. Map to it as-is; **do not
  modify it** (AC: domain models are fixed). The KDoc invariant "non-null `toolCall` iff `role`
  is `Tool`" is what makes `toolCall` always `null` here (no `tool` wire role today).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:29-44` — `Envelope`: its
  `ts: String` (the message timestamp source — an **unparsed** RFC-3339 string) and its
  `payload: JsonElement` (what this DTO decodes **from**), plus the `@Serializable`/`@SerialName`
  snake_case↔camelCase idiom the new DTO must mirror.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — the `MobileJson`
  instance and **why its config is load-bearing** (`encodeDefaults` / `explicitNulls=false` /
  `ignoreUnknownKeys=true`; note it does **not** set `coerceInputValues`, which is what makes an
  unknown enum value a hard decode failure — see Design). Decode via
  `MobileJson.decodeFromJsonElement<…>(payload)`; **never** a default `Json` (AC).
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt:1-79` — **the sibling
  #316 mapper; mirror it.** Same `@Serializable` DTO + public mapper-extension + private helper
  shape, and the "modeled-for-fidelity-but-mapped-nowhere" precedent (#316's `last_message_ts`)
  that this slice reuses for `conversation_id`.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt:1-111` — **the
  exact JUnit4 test idiom to match**: raw-string fixtures, `MobileJson.parseToJsonElement(...)` →
  `decodeFromJsonElement<T>(...)` → map → assert; `assertThrows(SerializationException::class.java)`
  for structure failures and `assertThrows(IllegalArgumentException::class.java)` for malformed
  timestamps. No JUnit5 / Kotest / Truth, no `runTest` (nothing async).
- `gradle/libs.versions.toml` — confirm `kotlinx-datetime` and `kotlinx-serialization-json` are
  **already present** (both are used by `Message.kt` / `ConversationsPayload.kt` already). Add no
  dependency, no build edit.
- **SSOT — server struct** `internal/protocol/messaging.go` `MessagePayload` (#272), via QMD
  `pyrycode-docs` (`mcp__qmd__query(collection: "pyrycode-docs", query: "messaging MessagePayload v1 wire fields")`).
  Pins the exact wire shape (the four fields, role set). The Kotlin DTO is the byte-mirror of that
  Go struct. (`protocol-mobile.md § Application message types` confirms "v2 adds no fields and
  removes no fields" vs v1.)
- (`docs/lessons.md` — not present in this repo; nothing to read.)

## Design

### Package & file layout

One new production file + one test file, under `data/network/` where the wire models and the
sibling #316 payload already live. The DTO, the wire-role enum, and the domain mapper belong
together — the file **is** the "`message` decode-and-map" unit.

| File | Kind | Contents |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` | production | `WireRole` enum + `MessagePayloadDto` + the public `toMessage(envelope, sessionId)` mapper + a private `WireRole.toDomain()` |
| `app/src/test/java/de/pyryco/mobile/data/network/MessagePayloadTest.kt` | test | JUnit4 decode-from-`JsonElement` → map assertions + rejection tests |

**Exactly 3 exported symbols:** `MessagePayloadDto`, `WireRole`, and the
`MessagePayloadDto.toMessage(…)` extension. `WireRole.toDomain()` is `private`. Public visibility
mirrors #316 (the wire DTOs there are public; single Gradle module). Stays `data/`-portable: pure
Kotlin + kotlinx-serialization + kotlinx-datetime, **no `android.*`**.

### Confirmed wire shape (SSOT)

The v2 `message` payload is the v1 shape — exactly four fields, all required and present on the
wire (Go `MessagePayload`, #272). It is **not** wrapped (unlike #316's object-wrapped array): the
payload IS the object.

```json
{"conversation_id":"c1","message_id":"m1","role":"user","text":"hello there"}
```

The message **timestamp is the envelope `ts`** (RFC 3339), **not** a payload field. The wire
`role` set is the closed `{user, assistant, system}` today (`tool` is a documented *future-additive*
role, #272 — absent from the wire now). There is **no** `session_id`, **no** `is_streaming`, and
**no** tool-call field on the payload.

### DTO — `MessagePayloadDto`

Snake_case wire ↔ camelCase Kotlin; `@SerialName` carries the mapping (the Go-interop contract,
not cosmetic). All four fields **required, non-null** — a missing field is a decode failure (the
strict-boundary posture, AC #4).

| Kotlin property | wire name | type | `@SerialName` | notes |
|---|---|---|---|---|
| `conversationId` | `conversation_id` | `String` | yes | required; **maps to no domain `Message` field** (see below) |
| `messageId` | `message_id` | `String` | yes | required → domain `id` |
| `role` | `role` | `WireRole` | — (name matches) | required; **enum-typed** so an unmappable role fails at decode |
| `text` | `text` | `String` | — (name matches) | required → domain `content` |

`conversation_id` is **modeled but mapped nowhere** — the domain `Message` carries no
conversation id (the consuming repository already knows which conversation it requested; the wire
field is a consistency echo). This reuses #316's "model-for-fidelity, drop-in-mapper" precedent
(`last_message_ts` there). Keeping it **required** (not optional/ignored) is the deliberate
strict-decode posture — same reasoning as #316's open question on its unused timestamp; revisit
only if the server ever omits it.

### `WireRole` — the closed, *mappable* wire-role enum

```kotlin
@Serializable
enum class WireRole {
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
}
```

**`system` is deliberately omitted**, and that is the pinned handling of the `system` role.
Rationale and the mechanism it relies on:

- The domain `Role` enum has **no `System`** member and is **fixed** (cannot be modified). A
  `system` message therefore has **no domain target** — it is *unmappable*, and the AC requires an
  unmapped/unknown role to be a **typed decode failure**, never a crash or a `null`-pun.
- Modeling `WireRole` as only the *mappable* roles makes kotlinx-serialization reject **`system`
  and any unknown string** at `decodeFromJsonElement` with a `SerializationException` — for free,
  at the structural decode boundary, before any `Message` is constructed. This keeps role
  validation co-located with the rest of payload-shape validation (symmetric with how #316
  validates its timestamps at the DTO).
- This rejection is robust under the current `MobileJson` config: `ignoreUnknownKeys` affects
  unknown *keys*, not unknown enum *values*, and `coerceInputValues` is **not** set. Even if
  `coerceInputValues` were added later, `role` has **no default value**, so coercion cannot apply
  and the unknown value still throws. (Spec note for whoever touches `MobileJson`: do not give
  `role` a default — that would silently turn an unmappable role into a coerced/null-punned one.)

`WireRole` names the set the *mapper accepts*, not the set the wire *can emit* — its KDoc must say
so explicitly (the wire can carry `system`; this type rejects it on purpose).

### The mapper — `fun MessagePayloadDto.toMessage(envelope: Envelope, sessionId: String): Message`

Public extension. It consumes the **envelope** (for `ts`), the decoded payload (receiver), and the
caller-supplied `sessionId`. Per-field mapping (every field a defined, tested value — AC #2):

| domain field | source | rule |
|---|---|---|
| `id` | `messageId` | direct |
| `sessionId` | `sessionId` (param) | **caller-supplied**, non-null; not decoded (the payload has no `session_id`). #312 passes the active session id at map time |
| `role` | `role.toDomain()` | exhaustive `when` over `WireRole` (see below) |
| `content` | `text` | direct |
| `timestamp` | `Instant.parse(envelope.ts)` | parse the envelope RFC-3339 string → `kotlinx.datetime.Instant`; throws `IllegalArgumentException` on a malformed string (see Error handling) |
| `isStreaming` | — | **`false`** — the protocol emits one *finished* message per `message` envelope (token-by-token streaming is the separate `message_chunk` type, out of scope) |
| `toolCall` | — | **`null`** (the domain default) — `Role.Tool`/`ToolCall` are unreachable from a `message` payload today; the domain invariant holds vacuously. Add a one-line comment at the mapping site so a future reader does not "wire it up" |

`role.toDomain()` is a `private fun WireRole.toDomain(): Role` with an **exhaustive `when` and no
`else`** (compiler-checked total over the two members): `WireRole.User → Role.User`,
`WireRole.Assistant → Role.Assistant`. No `Role.Tool` branch (unreachable).

**Why take the whole `Envelope`, not just `ts: String`?** The ticket directs it ("the mapper
consumes the envelope … not just the payload"), and it keeps the seam stable for the
`send_message`-echo path, which may later want `envelope.inReplyTo` / `envelope.id` for request
correlation. The mapper reads only `envelope.ts` today. It does **not** check `envelope.type` —
the caller (#312) routes by type and only invokes `toMessage` for `message`-typed envelopes, and
guarantees the envelope/payload correspondence (it decoded `envelope.payload` into the DTO it
passes back in).

### Consumer path (informative — #312, not built here)

`Envelope.payload` (`JsonElement`) →
`MobileJson.decodeFromJsonElement<MessagePayloadDto>(payload)` →
`dto.toMessage(envelope, activeSessionId)`. This slice ships the decode type + enum + mapper; #312
wires the flow/repository, supplies `activeSessionId`, decides per-message handling of a thrown
unmappable-role/`system` message (skip-the-message vs fail-the-stream — see Open questions), and
may optionally assert `dto.conversationId` matches the requested conversation.

## State + concurrency model

**None.** One immutable `@Serializable data class`, one `@Serializable enum`, the reused
thread-safe `MobileJson` singleton, and pure stateless functions. No `ViewModel`, `StateFlow`,
`viewModelScope`, coroutine, or dispatcher. Thread-safe; the consumer (#312) owns the dispatcher
choice for the I/O that produces the `JsonElement`.

## Error handling

Two typed failure surfaces; neither yields a crash, a partially-populated `Message`, or a
`null`-pun. This mirrors #316's two-type pattern (`SerializationException` for structure,
`IllegalArgumentException` for timestamp), here split across decode (payload) and map (envelope ts).

| Failure mode | Where | Result |
|---|---|---|
| Missing required payload field (`conversation_id`, `message_id`, `role`, `text`) | `decodeFromJsonElement` | `MissingFieldException` (a `SerializationException`) — **propagated**, no partial object |
| Wire `role` is `system`, `tool`, or any unknown string | `decodeFromJsonElement` (enum decoder) | `SerializationException` — propagated, before any `Message` is built |
| Wrong JSON type for a field (e.g. `text: 5`) | `decodeFromJsonElement` | `SerializationException` — propagated |
| Unknown server-added key on the payload | `MobileJson` (`ignoreUnknownKeys = true`) | ignored — no throw (forward-compat) |
| Malformed envelope `ts` (not RFC 3339) | `Instant.parse(envelope.ts)` in the mapper | `IllegalArgumentException` (kotlinx-datetime `DateTimeFormatException`) — propagated |

The function does **not** catch or wrap — it surfaces the typed exception to the caller (#312
decides UI surfacing). "No crash, no partially-populated or `null`-punned `Message`" holds because
the only `Message` construction site runs *after* a fully-validated DTO and a successful `ts`
parse; any failure throws before/at construction, never during it.

## Testing strategy

Unit only (`./gradlew test`) — JUnit4 mirroring `ConversationsPayloadTest.kt`; no instrumented
test, no `ComposeTestRule`, no `runTest`. One file. Each scenario builds a `JsonElement` via
`MobileJson.parseToJsonElement(rawFixture)` (mimicking `Envelope.payload`), decodes through
`MobileJson`, then maps via `toMessage(envelope, sessionId)` — exercising the real #312 consumer
path. Tests construct the `Envelope` directly (it's a public data class; only `ts` is read — pass
a minimal `Envelope(id=…, type="message", ts="…", payload=element)`). Write in the project's test
idiom; **do not** paste full bodies. Scenarios:

- **`user` payload → fully-populated `Message` (AC #2, AC #3 user branch).** Fixture
  `{"conversation_id":"c1","message_id":"m1","role":"user","text":"hello"}`; envelope `ts` =
  `"2026-05-20T10:00:00Z"`; `sessionId = "s-active"`. Assert **every** field: `id == "m1"`,
  `sessionId == "s-active"`, `role == Role.User`, `content == "hello"`,
  `timestamp == Instant.parse("2026-05-20T10:00:00Z")`, `isStreaming == false`,
  `toolCall == null`. The `sessionId` value appears **nowhere** in the payload — asserting it
  proves the id is caller-injected, not decoded.
- **`assistant` payload → `Role.Assistant` (AC #3 assistant branch).** Same shape with
  `"role":"assistant"`; assert `role == Role.Assistant`.
- **`system` role → typed decode failure (AC #3 system handling).** Fixture `"role":"system"`.
  Assert `assertThrows(SerializationException::class.java) { MobileJson.decodeFromJsonElement<MessagePayloadDto>(element) }`.
  Add one unknown-garbage-role case (`"role":"wizard"`) asserting the same exception, to show the
  rejection is the closed-set behavior, not a `system`-specific special-case.
- **Field-incomplete payload → typed decode failure (AC #4).** Fixture omits a required field
  (e.g. drop `text`). Assert `assertThrows(SerializationException::class.java) { decode }`. (Note:
  all four fields are required, so dropping any one is a valid trigger — `text` or `message_id` is
  the clearest.)
- **(Recommended) Malformed envelope `ts` → `IllegalArgumentException` at map.** Decode a valid
  `user` payload, then `toMessage(envelopeWith ts = "not-a-date", "s")`. Assert
  `assertThrows(IllegalArgumentException::class.java) { … }`. Covers the envelope-derived
  timestamp boundary this mapper introduces (the one validation step #316 didn't have, since its
  timestamps were payload fields).

**Do not write an encode round-trip test.** This payload is decode-only on the phone
(server → phone, and the `send_message` *response* echo is also inbound); the phone never *sends*
a `message` payload. Test decode → map only.

## Open questions

- **Per-message handling of an unmappable / `system` role at the observe layer (#312).** This
  slice *rejects* such a message (throws at decode). If the server ever interleaves `system`
  messages into the thread-read stream, #312 must decide whether to **skip** the offending
  message (continue the stream) or **fail** the whole thread load. That is a sequence-level policy
  decision that belongs to the observe slice, not this single-message boundary. Flagged so #312
  makes it deliberately rather than inheriting an accidental whole-stream abort. (Today the mobile
  domain deliberately has no `System` role, i.e. system messages are not rendered as thread
  items — so "skip" is the likely #312 choice.)
- **Strict-decode of the unused `conversation_id`.** Modeled as a required `String`, so a payload
  omitting it fails the whole decode even though it maps nowhere. Strict is the safer posture for
  an untrusted boundary and the field is always present on the wire today; revisit only if the
  server ever makes it optional. (Parallel to #316's open question on its unused `last_message_ts`.)
- **A domain `conversationId` on `Message`.** If a future need arises to carry the conversation id
  into the domain `Message` (e.g. cross-conversation message lists), the domain model grows a
  field and the mapper wires `conversationId` into it in one place — additive, deferred.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** This function **is** the explicit boundary where untrusted,
  post-Noise-decrypt wire JSON (`Envelope.payload: JsonElement`) becomes a typed in-process
  `Message`. The boundary is a single named surface —
  `decodeFromJsonElement<MessagePayloadDto>` + `toMessage(envelope, sessionId)` — replacing
  ad-hoc per-call-site decoding. Decode validates **shape** (four required fields, role ∈ the
  closed mappable set) and the mapper validates the envelope timestamp; neither validates
  *semantics* (e.g. that `message_id` is unique, or `conversation_id` matches the request — the
  latter is a SHOULD note handed to #312). The pre-decode value is a `JsonElement` (the type
  signals "raw, not yet trusted"); downstream callers hold a fully-typed `Message`. No MUST FIX.
- **[Tokens / secrets / credentials]** N/A — the `message` payload carries no secret: only
  `conversation_id`, `message_id`, a closed-set role, and message `text`. No token is read,
  stored, logged, or compared. The device-pairing secret lives in the Noise early-data / credential
  store (#291/#294), nowhere in this DTO. No redacting `toString()` is needed (unlike #273's
  `HelloClientPayload`/`QrPayload`). The message `text` is user content, not a credential.
- **[File / storage operations]** N/A — performs **no** file or storage I/O. Unlike #316's `cwd`,
  this payload carries no filesystem path; every field is opaque in-process data.
- **[Inter-process / Android attack surface]** N/A — no `Activity`/`Service`/`Receiver`/deep
  link/`PendingIntent`/`WebView`. Pure in-process data transform with no exported component and no
  `android.*` import.
- **[Cryptographic primitives]** N/A — no crypto executed. Bytes arrive already decrypted by the
  Noise transport (#298/#309) upstream; this is plaintext-JSON shaping only. No RNG, no compare.
- **[Network & I/O]** N/A at this layer — no socket, no OkHttp, no timeout surface. The inbound
  frame-size cap and read deadlines are the transport's concern (`OkHttpRelayTransport` /
  `RelayConnectionSupervisor`), enforced **before** bytes reach this decode. A hostile relay
  cannot make this function allocate unboundedly beyond the already-capped frame — one `message`
  payload decodes to one small `MessagePayloadDto` (four strings); `text` length is bounded by the
  transport's frame cap. No MUST FIX.
- **[Error messages / logs / telemetry]** No logging is introduced. Decode/parse exceptions name
  the missing/mismatched **field** or the unknown **role string** and the malformed timestamp
  string — never a secret (there are none here). The unknown-role message will echo the *role
  string* (e.g. `"system"`), which is non-sensitive. The function emits no telemetry. SHOULD note
  for #312: avoid logging the raw `JsonElement` or the decoded message `text` at a level that
  reaches release Logcat — message `text` is user content (privacy-sensitive though not a
  credential). Not a finding against this slice. No MUST FIX.
- **[Concurrency]** N/A — one immutable DTO, one immutable enum, a reused thread-safe `Json`
  singleton, pure stateless functions. No coroutine, scope, `StateFlow`, mutex, or shared mutable
  state; no TOCTOU surface.
- **[Threat model alignment]** This slice hardens the message-thread decode path against
  malformed / partial / wrong-role untrusted input (AC #3 + AC #4 rejections), so a hostile or
  buggy server cannot inject a half-populated, `null`-punned, or semantically-misroled (`system`
  rendered as `User`) `Message` into the thread. Relay-MITM (→ Noise_IK transport, #298/#309),
  server-pubkey authenticity (→ #294/#291), and at-rest caching of decoded messages (no
  persistence introduced here) are named and **deferred to their owning tickets**.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
