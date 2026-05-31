# Architecture: `conversations` payload → domain `Conversation`-list mapping (#316)

## Context

Phase 4 chain, `data/`-layer slice. `RemoteConversationRepository` (#312) needs to turn
the Mobile Protocol v2 `conversations` reply (server → phone, in answer to a
`list_conversations` request) into domain `Conversation` values for the channel-list /
recent-discussions read path.

Today `Envelope.payload` is an **untrusted** `JsonElement` (`MobileWireModels.kt:42`, KDoc:
"stays untrusted raw JSON until a consumer decodes and validates it"). This slice adds the one
typed `@Serializable` DTO surface for the `conversations` application payload plus the pure
function that maps it to the existing domain `Conversation` in `data/model/`. It is the
**single decode-and-validate boundary** for that payload: malformed / field-incomplete wire
JSON surfaces a typed decode failure, never a crash or a partially-populated domain object.

Scope is `conversations` → `Conversation` **only**. No flows, no repository class, no
networking. The last-message `Message` preview is **not** in this payload (the wire carries a
`last_message_ts` timestamp but no message content) — that mapping is #317's (`message`
payload → domain `Message`), wired into the read path by #312. See [[v2-app-payload-shapes-ssot]].

## Design source

N/A — pure `data/`-layer decode/map; no UI, no Figma. The "design source" is the byte
contract: server SSOT `internal/protocol/conversations_read.go` (#273) and
`protocol-mobile.md § conversations` (see Files to read first).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-21` — the domain **target**:
  the 9-field `Conversation` data class **and** the `DEFAULT_SCRATCH_CWD` sentinel. Map to it
  as-is; **do not modify it** (AC: domain model is fixed).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-44` — `Envelope` and
  its `payload: JsonElement` carrier (what this DTO decodes **from**), plus the
  `@Serializable` / `@SerialName` snake_case→camelCase idiom your new DTOs must mirror exactly.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — the `MobileJson`
  instance and **why its config is load-bearing** (`encodeDefaults` / `explicitNulls=false` /
  `ignoreUnknownKeys=true`). Decode via `MobileJson.decodeFromJsonElement<…>(payload)`; **never**
  a default `Json` (AC).
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:1-145` — the **exact
  JUnit4 test idiom** to match: `org.junit.Test`, `org.junit.Assert.{assertEquals,assertNull,
  assertThrows}`, raw-string fixtures, `MobileJson.parseToJsonElement(...)` /
  `decodeFromString<T>(...)`. No JUnit5 / Kotest / Truth, no `runTest` (nothing async).
- `gradle/libs.versions.toml:12-13,51-52,63` — confirm `kotlinx-datetime 0.6.2`,
  `kotlinx-serialization-json 1.8.1`, and the `kotlin-serialization` plugin are **already
  present**. Add no dependency, no build edit.
- `docs/specs/architecture/273-mobile-protocol-v2-wire-models-codec.md` — the wire-model
  conventions this slice extends (snake_case `@SerialName` IS the Go-interop contract; the
  `MobileJson` rationale). This DTO is the application-payload analog of those handshake models.
- **SSOT — server struct** `internal/protocol/conversations_read.go` (#273), via QMD
  `pyrycode-docs/specs/architecture/273-conversations-read-payloads.md`. **Pins the exact wire
  shape** (object-wrapped array, field names, `name` nullable). The Kotlin DTO is the byte-mirror
  of `ConversationsPayload` / `ConversationSummary` there.
- (`docs/lessons.md` — not present in this repo; nothing to read.)

## Design

### Package & file layout

One new production file + one test file, under the `data/network/` namespace where the wire
models already live (#273). The DTO and its domain mapper belong together — the file **is** the
"`conversations` decode-and-map" unit; the mapper is the wire→domain edge, the same role #303's
Go handler plays server-side.

| File | Kind | Contents |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` | production | the 2 `@Serializable` DTOs + the public `toConversations()` mapper (+ private per-row mapper) |
| `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt` | test | JUnit4 decode-from-`JsonElement` → map assertions + rejection test |

**Exactly 3 exported symbols:** `ConversationsPayload`, `ConversationSummaryDto`, and the
`ConversationsPayload.toConversations()` extension. The per-row mapper is `private`. Stays
`data/`-portable: pure Kotlin + kotlinx-serialization + kotlinx-datetime, **no `android.*`**.

### ⚠ The payload is an object-wrapped array, NOT a bare array

The AC prose says "the array of per-conversation summaries." The **confirmed wire shape**
(server SSOT `conversations_read.go`, #273) wraps that array in an object keyed `conversations`:

```json
{"conversations":[
  {"id":"c1","name":"kitchen-claw refactor","is_promoted":true,"cwd":"/Users/j/Projects/KitchenClaw","last_message_ts":"2026-05-08T10:31:02Z","last_used_at":"2026-05-08T10:31:02Z"},
  {"id":"c2","name":null,"is_promoted":false,"cwd":"~/.pyrycode/scratch","last_message_ts":"2026-05-08T09:14:11Z","last_used_at":"2026-05-08T09:14:11Z"}
]}
```

So `Envelope.payload` decodes to `ConversationsPayload` (the object), whose `conversations`
field is the `List<ConversationSummaryDto>`. **Do not model the payload as a top-level
`List<…>`** — that would fail to decode the real frame. This is the one easy-to-miss
correctness pin in this slice.

### DTO 1 — `ConversationsPayload` (the object wrapper)

| Kotlin property | wire name | type | notes |
|---|---|---|---|
| `conversations` | `conversations` | `List<ConversationSummaryDto>` | names match → no `@SerialName` |

### DTO 2 — `ConversationSummaryDto` (one row)

Snake_case wire ↔ camelCase Kotlin; `@SerialName` carries the mapping (the Go-interop contract,
not cosmetic). Field order mirrors the Go struct.

| Kotlin property | wire name | type | `@SerialName` | notes |
|---|---|---|---|---|
| `id` | `id` | `String` | — | required, non-null |
| `name` | `name` | `String?` | — | **nullable, no default**; `null` ⇒ unnamed scratch; preserve `null` |
| `isPromoted` | `is_promoted` | `Boolean` | yes | required |
| `cwd` | `cwd` | `String` | — | required; **verbatim** (incl. `DEFAULT_SCRATCH_CWD`) |
| `lastMessageTs` | `last_message_ts` | `Instant` | yes | wire fidelity only — **maps to no domain field** today |
| `lastUsedAt` | `last_used_at` | `Instant` | yes | → domain `lastUsedAt` |

**Timestamps decode to `kotlinx.datetime.Instant` at the DTO**, via an explicit
`@Serializable(with = InstantIso8601Serializer::class)`
(`import kotlinx.datetime.serializers.InstantIso8601Serializer`). Rationale:

- Makes **decode** the single validate boundary — a malformed RFC 3339 string fails at decode
  (a `SerializationException`), so the mapper stays a total, pure field-copy with no second
  throw site. This is exactly the ticket's "one validated place" goal.
- The explicit serializer is version-independent and self-documents the wire format as
  ISO-8601; it removes any "serializer not found for `Instant`" build risk in
  kotlinx-datetime 0.6.2. (Deliberate, documented departure from #273's choice to keep
  `Envelope.ts` a `String` — there, no domain `Instant` consumed it; here `lastUsedAt` does.)

`name` is nullable **without a default**: on the wire the `name` key is always present (Go
`*string`, no `omitempty`, emits explicit `null`); under `MobileJson` (`explicitNulls = false`)
an absent `name` would also decode to `null`. Either way `name == null` is a valid domain state.

### The mapper — `fun ConversationsPayload.toConversations(): List<Conversation>`

Public extension on the payload; `.map { it.toConversation() }` over a **`private fun
ConversationSummaryDto.toConversation(): Conversation`**. Pure, total, order-preserving.

Per-row field mapping (each field has a defined, tested value — AC):

| domain field | source | rule |
|---|---|---|
| `id` | `dto.id` | direct |
| `name` | `dto.name` | direct, `null` preserved |
| `cwd` | `dto.cwd` | **verbatim** — no normalize/blank; `DEFAULT_SCRATCH_CWD` survives unchanged |
| `isPromoted` | `dto.isPromoted` | direct |
| `lastUsedAt` | `dto.lastUsedAt` | direct (already an `Instant`) |
| `currentSessionId` | — | **`""`** (list-tier placeholder) |
| `sessionHistory` | — | **`emptyList()`** |
| `isSleeping` | — | **`false`** |
| `archived` | — | **`false`** |

`dto.lastMessageTs` is intentionally **dropped** — the domain `Conversation` has no distinct
last-message field (it carries `lastUsedAt` only). Modeled on the DTO for wire fidelity; maps
nowhere today.

The four placeholder fields get a **KDoc/comment at the mapping site** naming them as list-tier
defaults: full session / sleep / archive state arrives via the detail + message read paths, not
the conversation-list payload — so a future reader does not "fix" them by null-punning or
plumbing upstream enrichment. These are defined, non-null values, never silent `null`-pun (AC).

**No tiering, no sorting, no filtering.** The mapper returns a flat `List<Conversation>`
preserving wire order and the per-row `isPromoted` flag. Splitting promoted (channels) from
unpromoted (discussions), and any display ordering, is #312's concern — out of scope here.

### Consumer path (informative — #312, not built here)

`Envelope.payload` (`JsonElement`) → `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload)`
→ `.toConversations()`. This slice ships the decode types + mapper; #312 wires the
flow/repository around them.

## State + concurrency model

**None.** Two immutable `@Serializable data class`es, one immutable singleton (`MobileJson`,
reused — not re-created), and pure stateless functions. No `ViewModel`, `StateFlow`,
`viewModelScope`, coroutine, or dispatcher. Thread-safe; the consumer (#312) owns the dispatcher
choice for the I/O that produces the `JsonElement`.

## Error handling

The decode is the single failure surface; the mapper cannot throw (total over a validly-decoded
DTO).

| Failure mode | Where | Result |
|---|---|---|
| Missing required field (`id`, `is_promoted`, `cwd`, `last_used_at`, `last_message_ts`) | `MobileJson.decodeFromJsonElement` | throws `kotlinx.serialization.MissingFieldException` (a `SerializationException`) — **propagated**, no partial object |
| Malformed RFC 3339 in either timestamp | `InstantIso8601Serializer` (at decode) | throws `SerializationException` — propagated |
| Wrong JSON type for a field (e.g. `is_promoted: "yes"`) | `MobileJson` | throws `SerializationException` — propagated |
| Unknown server-added key on a row | `MobileJson` (`ignoreUnknownKeys = true`) | ignored — no throw (forward-compat) |
| `name` absent or `null` | decode | `name == null` (valid unnamed state, not a failure) |

The function does **not** catch or wrap — it surfaces the typed `SerializationException` to the
caller (#312 decides UI surfacing: error banner vs. retry). "No crash, no partially-populated
or `null`-punned domain object" (AC) holds because decode is all-or-nothing: a row that can't
fully populate `ConversationSummaryDto` throws before any `Conversation` is constructed.

## Testing strategy

Unit only (`./gradlew test`) — JUnit4 mirroring `MobileWireCodecTest.kt`; no instrumented test,
no `ComposeTestRule`, no `runTest`. One file. Each scenario decodes from a **`JsonElement`**
(built via `MobileJson.parseToJsonElement(rawFixture)` to mimic `Envelope.payload`) then maps —
exercising the real consumer path. Write as the project's test idiom; **do not** paste full
bodies. Scenarios:

- **Representative two-row payload → domain list (AC #4, covers both branches in one fixture).**
  Fixture = the object-wrapped array above. Row A: named, `is_promoted: true`, bound `cwd`
  (a real path), valid timestamps. Row B: `name: null`, `is_promoted: false`,
  `cwd: "~/.pyrycode/scratch"` (the `DEFAULT_SCRATCH_CWD` value). Decode → `toConversations()`.
  Assert: list size 2, order preserved; Row A → `name == "…"`, `isPromoted == true`,
  `cwd == "/real/path"`, `lastUsedAt == Instant.parse("…")`; Row B → `name == null`,
  `isPromoted == false`.
- **`DEFAULT_SCRATCH_CWD` preserved verbatim (AC #3).** On Row B assert
  `cwd == DEFAULT_SCRATCH_CWD` (reference the constant, not a literal) — not blanked/normalized.
- **Four placeholder fields are the documented defaults (AC #2).** On at least one mapped row
  assert `currentSessionId == ""`, `sessionHistory == emptyList()`, `isSleeping == false`,
  `archived == false`.
- **Field-incomplete payload → typed decode failure (AC #5).** Build a `JsonElement` whose one
  row **omits a required non-nullable field** (e.g. drop `cwd`). Assert
  `assertThrows(SerializationException::class.java) { MobileJson.decodeFromJsonElement<ConversationsPayload>(elem) }`.
  (Optional second case: malformed `last_used_at` such as `"not-a-date"` → same exception, proving
  the timestamp is validated at the boundary.) Note: dropping `name` would **not** fail (decodes
  to `null`) — the test must drop a required field to be meaningful.

**Do not write an encode round-trip test.** This payload is decode-only on the phone
(server → phone); the phone never sends `conversations`. Re-encoding a `name: null` row under
`explicitNulls = false` would omit the key and diverge from the Go wire — irrelevant here, but a
round-trip test would spuriously fail. Test decode → map only.

## Open questions

- **Strict-decode of the unused `last_message_ts`.** Modeled as a required `Instant`, so a
  garbage `last_message_ts` fails the whole row even though it maps nowhere. Today #303 projects
  `last_message_ts = last_used_at` (always equal, always valid), so there is no realistic
  divergence, and strict validation is the safer posture for an untrusted-input boundary.
  Revisit only if the server ever emits a distinct, independently-malformable `last_message_ts`.
- **A real domain `lastMessageTs`.** When message-preview ordering needs a distinct timestamp,
  the domain `Conversation` grows a field and the mapper wires `dto.lastMessageTs` into it in one
  place — additive, deferred (tracked with #317's message-preview work).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** This function **is** the explicit boundary where untrusted, post-Noise-
  decrypt wire JSON (`Envelope.payload: JsonElement`) becomes a typed in-process value. The
  boundary is a single named surface — `decodeFromJsonElement<ConversationsPayload>` +
  `toConversations()` — not scattered ad-hoc decoding (which is the very thing this slice exists
  to replace). Downstream callers hold a domain `Conversation` (fully typed), and the pre-decode
  value is a `JsonElement` (the type itself signals "raw, not yet trusted"). Decode validates
  **shape**, not semantics (e.g. it does not check `isPromoted == true ⇒ name != null` — a
  registry-side invariant per #273, not re-litigated on the client). `ignoreUnknownKeys = true`
  is a conscious lenient-decode choice inherited from `MobileJson` (a client tolerating
  server-added fields is safer for forward-compat than hard-failing). No MUST FIX.
- **[Tokens / secrets / credentials]** N/A — the `conversations` payload carries no secret: only
  `id`, `name`, `cwd`, two timestamps, a bool. No token is read, stored, logged, or compared. The
  device-pairing secret lives in the Noise early-data / credential store (#291/#277), nowhere in
  this DTO. No redacting `toString()` is needed (unlike #273's `HelloClientPayload`/`QrPayload`).
- **[File / storage operations]** N/A — performs **no** file or storage I/O. Note `cwd` is a
  filesystem-**path string** carried verbatim, but this slice never opens, resolves, canonicalises,
  or traverses it — `cwd` is opaque display data here. Any future code that turns a server-supplied
  `cwd` into an actual `File` must do its own canonicalisation + boundary check; that is out of
  scope and owned by whoever performs the I/O, not this decode boundary.
- **[Inter-process / Android attack surface]** N/A — no `Activity`/`Service`/`Receiver`/deep
  link/`PendingIntent`/`WebView`. Pure in-process data transform with no exported component.
- **[Cryptographic primitives]** N/A — no crypto executed. Bytes arrive already decrypted by the
  Noise transport (#298/#309) upstream; this is plaintext-JSON shaping only. No RNG, no compare.
- **[Network & I/O]** N/A at this layer — no socket, no OkHttp, no timeout surface. The inbound
  frame-size cap and read deadlines are the transport's concern (`OkHttpRelayTransport` /
  `RelayConnectionSupervisor`), enforced **before** bytes reach this decode. The function adds no
  size limit of its own and assumes already-capped input; a hostile relay cannot make this
  function allocate unboundedly beyond the already-capped frame (a large-but-capped array of rows
  decodes to a proportional `List` — bounded by the transport cap). No MUST FIX.
- **[Error messages / logs / telemetry]** No logging is introduced. Decode exceptions are
  `SerializationException`s whose messages name the missing/mismatched field — field **names**
  and types, never secret values (there are none in this payload). The function emits no
  telemetry. Consumer (#312) must avoid logging the raw `JsonElement` / decoded list at a level
  that reaches release Logcat, but the values here (`id`/`name`/`cwd`/timestamps) are non-secret;
  this is a SHOULD-style note for #312, not a finding against this slice. No MUST FIX.
- **[Concurrency]** N/A — immutable DTOs, a reused thread-safe `Json` singleton, pure stateless
  functions. No coroutine, scope, `StateFlow`, mutex, or shared mutable state; no TOCTOU surface.
- **[Threat model alignment]** This slice's contribution is hardening the conversation-list
  decode path against malformed/partial untrusted input (the field-incomplete rejection, AC #5),
  so a hostile/buggy server cannot inject a half-populated or `null`-punned `Conversation` into
  the domain. Relay-MITM (→ Noise_IK transport, #298/#309), pubkey authenticity (→ #277/#291),
  and at-rest caching of decoded conversations (no persistence introduced here) are named and
  **deferred to their owning tickets**.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
