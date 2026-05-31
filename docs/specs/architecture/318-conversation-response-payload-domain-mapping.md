# Architecture: conversation create/promote response payload → domain `Conversation` mapping (#318)

## Context

Phase 4 chain, `data/`-layer slice. The mutation paths of `RemoteConversationRepository` need to
turn the Mobile Protocol v2 mutation-response payloads into a domain `Conversation`:
`create_conversation` is answered by `conversation_created`, and `promote_conversation` by
`conversation_updated`. Both responses carry a single conversation object and both map to the
domain `Conversation`.

Today `Envelope.payload` is an **untrusted** `JsonElement` (`MobileWireModels.kt:37-44`, KDoc:
"stays untrusted raw JSON until a consumer decodes and validates it"). This slice adds the one
typed `@Serializable` DTO surface for those two response payloads plus the pure function that maps
it to the existing domain `Conversation` in `data/model/`. It is the **single decode-and-validate
boundary** for those payloads: malformed / field-incomplete wire JSON surfaces a typed decode
failure, never a crash or a partially-populated / `null`-punned domain object.

Scope is *mutation-response* → `Conversation` **only**. No flows, no repository class, no
networking — pure decode-and-map functions consumed by the later mutation slices. `send_message`'s
response is the `message` echo (mapped by #317, **not** this slice); `ack` is a wire-correlation
signal with no domain target (a repository-orchestration concern, out of scope). This slice
**reuses** the wire→`Conversation` mapping *rule* established by the now-landed read slice (#316,
`ConversationsPayload.kt`) — it does not call its mapper (that mapper is `private` and bound to a
DTO carrying `last_message_ts`, which these payloads omit). See [[v2-app-payload-shapes-ssot]].
Sibling #317 (`message` → `Message`) is the structural twin whose self-contained shape this spec
mirrors.

## Design source

N/A — pure `data/`-layer decode/map; no UI, no Figma. The "design source" is the byte contract:
server SSOT `internal/protocol/conversations_write.go` (`ConversationCreatedPayload` /
`ConversationUpdatedPayload`, #274) and `protocol-mobile.md §§ conversation_created /
conversation_updated`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:5-21` — the domain **target**:
  the 9-field `Conversation` data class **and** the `DEFAULT_SCRATCH_CWD` sentinel
  (`"~/.pyrycode/scratch"`). Map to it as-is; **do not modify it** (AC: domain model is fixed).
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt:1-79` — **the landed
  #316 sibling; mirror it and reuse its mapping rule.** Same `@Serializable` DTO + public
  mapper-extension shape, the explicit `InstantIso8601Serializer` on the timestamp, and (the part
  this slice **reuses**) the per-row wire→`Conversation` rule: `name` `null`-preserved, `cwd`
  verbatim, and the four list-tier placeholders (`currentSessionId=""`, `sessionHistory=emptyList()`,
  `isSleeping=false`, `archived=false`). Apply that rule; do not diverge from it.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt:1-111` — **the exact
  JUnit4 test idiom to match**: `org.junit.Test`, `org.junit.Assert.{assertEquals,assertNull,
  assertFalse,assertTrue,assertThrows}`, raw-string fixtures, `MobileJson.parseToJsonElement(...)` →
  `decodeFromJsonElement<T>(...)` → map → assert. **Crucially**, it pins the two exception types:
  `assertThrows(SerializationException::class.java)` for a missing required field, and
  `assertThrows(IllegalArgumentException::class.java)` for a malformed timestamp (the
  `InstantIso8601Serializer` surfaces `kotlinx.datetime.DateTimeFormatException`, an
  `IllegalArgumentException`, **not** a `SerializationException`). No JUnit5 / Kotest / Truth, no
  `runTest` (nothing async).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-44` — `Envelope` and its
  `payload: JsonElement` carrier (what this DTO decodes **from**), plus the `@Serializable` /
  `@SerialName` snake_case↔camelCase idiom the new DTO must mirror exactly.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — the `MobileJson`
  instance and **why its config is load-bearing** (`encodeDefaults` / `explicitNulls=false` /
  `ignoreUnknownKeys=true`). Decode via `MobileJson.decodeFromJsonElement<…>(payload)`; **never** a
  default `Json` (AC).
- `gradle/libs.versions.toml` — confirm `kotlinx-datetime` and `kotlinx-serialization-json` are
  **already present** (both are used by `Conversation.kt` / `ConversationsPayload.kt` already). Add
  no dependency, no build edit.
- `docs/specs/architecture/317-message-payload-domain-mapping.md` — sibling precedent: one
  self-contained `<Payload>.kt` + test, **one DTO per distinct wire shape** (not per wire
  type-string), no shared-helper extraction across slices.
- **SSOT — server structs** `internal/protocol/conversations_write.go` `ConversationCreatedPayload`
  / `ConversationUpdatedPayload` (#274), via QMD
  (`mcp__qmd__query(collection: "pyrycode-docs", query: "conversations_write conversation_created conversation_updated payload")`).
  Pins the exact wire shape (the five fields, `name` nullable, **no** `last_message_ts`, both
  payloads a **bare object** — not wrapped). The Kotlin DTO is the byte-mirror of those Go structs.
- (`docs/lessons.md` — not present in this repo; nothing to read.)

## Design

### Package & file layout

One new production file + one test file, under `data/network/` where the wire models and the
landed #316 / specced #317 siblings live. The DTO and its domain mapper belong together — the file
**is** the "mutation-response decode-and-map" unit.

| File | Kind | Contents |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponsePayload.kt` | production | the 1 `@Serializable` DTO + the public `toConversation()` mapper |
| `app/src/test/java/de/pyryco/mobile/data/network/ConversationResponsePayloadTest.kt` | test | JUnit4 decode-from-`JsonElement` → map assertions + rejection tests |

**Exactly 2 exported symbols:** `ConversationResponseDto` and the
`ConversationResponseDto.toConversation()` extension. Public visibility mirrors #316/#317 (single
Gradle module). Stays `data/`-portable: pure Kotlin + kotlinx-serialization + kotlinx-datetime,
**no `android.*`**.

### ⚠ One DTO models BOTH response types — the central decision

The Go SSOT (#274) pins the two payload shapes:

```go
type ConversationCreatedPayload struct {  // bare object, NOT wrapped
    ID string; IsPromoted bool; Cwd string; Name *string; LastUsedAt time.Time }
type ConversationUpdatedPayload struct {
    ID string; IsPromoted bool; Name *string; Cwd string; LastUsedAt time.Time }
```

They carry the **identical field set** — `id` (required), `is_promoted` (required), `name`
(nullable), `cwd` (required), `last_used_at` (required) — and differ **only** in struct key order
(`cwd`↔`name` swapped). JSON object key order is semantically irrelevant and kotlinx-serialization
decodes by key **name**, not position, so a single DTO decodes both wire orderings losslessly.
Therefore this slice ships **one** `ConversationResponseDto`, not two byte-identical classes. The
`conversation_created` vs `conversation_updated` distinction is a **wire-`type`-string routing
concern** handled at the `Envelope.type` layer by the future mutation slices — *not* a shape
concern at the decode boundary. (Same principle as #317, which ships one `MessagePayloadDto` for
both the inbound `message` and the `send_message` echo.) The DTO's KDoc must state this explicitly
so a future reader does not "fix" it by splitting into two.

Both payloads are a **bare conversation object** at `Envelope.payload` — **not** object-wrapped
(contrast #316's `conversations`, which wraps an *array* under a `conversations` key). So
`Envelope.payload` decodes directly to `ConversationResponseDto`. Neither payload carries
`last_message_ts` (the read summary's wire-fidelity field) — this DTO has exactly **one**
timestamp, `last_used_at`, which maps to the domain.

### DTO — `ConversationResponseDto`

Snake_case wire ↔ camelCase Kotlin; `@SerialName` carries the mapping (the Go-interop contract, not
cosmetic). Field order follows #316's `ConversationSummaryDto` (minus `last_message_ts`) for
consistency; it has no wire effect (decode is by name).

| Kotlin property | wire name | type | `@SerialName` | notes |
|---|---|---|---|---|
| `id` | `id` | `String` | — | required, non-null |
| `name` | `name` | `String?` | — | **nullable, no default**; `null` ⇒ unnamed; preserve `null` |
| `isPromoted` | `is_promoted` | `Boolean` | yes | required |
| `cwd` | `cwd` | `String` | — | required; **verbatim** (incl. `DEFAULT_SCRATCH_CWD`) |
| `lastUsedAt` | `last_used_at` | `Instant` | yes | → domain `lastUsedAt` |

`lastUsedAt` decodes to `kotlinx.datetime.Instant` via an explicit
`@Serializable(with = InstantIso8601Serializer::class)`
(`import kotlinx.datetime.serializers.InstantIso8601Serializer`) — identical to #316's two
timestamps. This makes **decode** the single validate boundary: a malformed RFC 3339 string fails
at decode, so the mapper stays a total, pure field-copy with no second throw site.

`name` is nullable **without a default**: on the wire the `name` key is present as a Go `*string`
(emits explicit `null` for an unnamed conversation); under `MobileJson` (`explicitNulls = false`)
an absent `name` would also decode to `null`. Either way `name == null` is a valid domain state.

### The mapper — `fun ConversationResponseDto.toConversation(): Conversation`

Public extension on the DTO. Single object → single `Conversation`; **no list, no `private`
per-row helper** (contrast #316, which mapped a list). Pure, total over a validly-decoded DTO.

Per-field mapping (each domain field has a defined, tested value — AC #2):

| domain field | source | rule |
|---|---|---|
| `id` | `dto.id` | direct |
| `name` | `dto.name` | direct, `null` preserved |
| `cwd` | `dto.cwd` | **verbatim** — no normalize/blank; `DEFAULT_SCRATCH_CWD` survives unchanged |
| `isPromoted` | `dto.isPromoted` | direct |
| `lastUsedAt` | `dto.lastUsedAt` | direct (already an `Instant`) |
| `currentSessionId` | — | **`""`** (placeholder) |
| `sessionHistory` | — | **`emptyList()`** |
| `isSleeping` | — | **`false`** |
| `archived` | — | **`false`** |

The four placeholder fields are **not** on the mutation-response wire — same rule as #316. Put a
KDoc/comment at the mapping site naming them as defaults and **cross-referencing #316 as the
canonical rule source** (full session / sleep / archive state arrives via the detail + message read
paths, not a mutation response) so a future reader does not "fix" them by `null`-punning or by
plumbing upstream enrichment. These are defined, non-null values, never a silent `null`-pun (AC #2).

**Why duplicate the four-line rule instead of factoring a shared helper?** The rule is reused
**verbatim** from #316, but the helper extraction is deliberately *not* done: (a) it would require
editing the landed `ConversationsPayload.kt` purely for a four-constant DRY win — exactly the
"don't refactor adjacent code while you're there" case; (b) #317, the structural twin, set the
precedent of a self-contained per-shape file with no cross-slice shared mapper; (c) the divergence
risk is low and unobserved (evidence-based — defer the abstraction until a real second consumer or a
domain change forces it). The cross-reference comment keeps the rule discoverable without the
indirection. If a third consumer of the rule lands, revisit (see Open questions).

### Consumer path (informative — future mutation slices, not built here)

For a `conversation_created` (reply to `create_conversation`) or a `conversation_updated` (reply to
`promote_conversation`) envelope: `Envelope.payload` (`JsonElement`) →
`MobileJson.decodeFromJsonElement<ConversationResponseDto>(payload)` → `.toConversation()`. This
slice ships the decode type + mapper; the mutation slices route by `Envelope.type`, supply the
correlation/orchestration, and decide UI surfacing of a thrown decode failure.

## State + concurrency model

**None.** One immutable `@Serializable data class`, the reused thread-safe `MobileJson` singleton,
and a pure stateless function. No `ViewModel`, `StateFlow`, `viewModelScope`, coroutine, or
dispatcher. Thread-safe; the consumer owns the dispatcher choice for the I/O that produces the
`JsonElement`.

## Error handling

The decode is the single failure surface; the mapper cannot throw (total over a validly-decoded
DTO). This mirrors #316's two-type pattern exactly.

| Failure mode | Where | Result |
|---|---|---|
| Missing required field (`id`, `is_promoted`, `cwd`, `last_used_at`) | `MobileJson.decodeFromJsonElement` | throws `kotlinx.serialization.MissingFieldException` (a `SerializationException`) — **propagated**, no partial object |
| Malformed RFC 3339 in `last_used_at` | `InstantIso8601Serializer` (at decode) | throws `IllegalArgumentException` (kotlinx-datetime `DateTimeFormatException`) — propagated |
| Wrong JSON type for a field (e.g. `is_promoted: "yes"`) | `MobileJson` | throws `SerializationException` — propagated |
| Unknown server-added key | `MobileJson` (`ignoreUnknownKeys = true`) | ignored — no throw (forward-compat) |
| `name` absent or `null` | decode | `name == null` (valid unnamed state, not a failure) |

The function does **not** catch or wrap — it surfaces the typed exception to the caller (the
mutation slice decides UI surfacing). "No crash, no partially-populated or `null`-punned domain
object" (AC #4) holds because decode is all-or-nothing: a payload that can't fully populate
`ConversationResponseDto` throws before any `Conversation` is constructed.

## Testing strategy

Unit only (`./gradlew test`) — JUnit4 mirroring `ConversationsPayloadTest.kt`; no instrumented
test, no `ComposeTestRule`, no `runTest`. One file. Each scenario decodes from a **`JsonElement`**
(built via `MobileJson.parseToJsonElement(rawFixture)` to mimic `Envelope.payload`) then maps —
exercising the real consumer path. Write in the project's test idiom; **do not** paste full bodies.
Scenarios:

- **`conversation_created`-shaped payload → domain (AC #3 unnamed branch + AC #2 + AC #3 scratch
  cwd).** Fixture is the bare created object with `cwd` ordered **before** `name` (mirrors the Go
  `ConversationCreatedPayload` key order): `name: null`, `is_promoted: false`,
  `cwd: "~/.pyrycode/scratch"` (the `DEFAULT_SCRATCH_CWD` value), valid `last_used_at`. Decode →
  `toConversation()`. Assert `name == null`, `isPromoted == false`,
  `lastUsedAt == Instant.parse("…")`, and `cwd == DEFAULT_SCRATCH_CWD` (reference the **constant**,
  not a literal, so a sentinel change can't silently pass). This one fixture covers the
  unnamed branch **and** verbatim-scratch-cwd preservation.
- **`conversation_updated`-shaped payload → domain (AC #3 promoted branch).** Fixture is the bare
  updated object with `name` ordered **before** `cwd` (mirrors the Go `ConversationUpdatedPayload`
  key order — and proves the single DTO decodes both wire orderings): `name: "weekly-planning"`,
  `is_promoted: true`, a bound `cwd` (a real path), valid `last_used_at`. Assert
  `name == "weekly-planning"`, `isPromoted == true`, `cwd ==` the bound path verbatim,
  `lastUsedAt == Instant.parse("…")`.
- **Four placeholder fields are the documented defaults (AC #2).** On at least one mapped row assert
  `currentSessionId == ""`, `sessionHistory == emptyList()`, `isSleeping == false`,
  `archived == false`.
- **Field-incomplete payload → typed decode failure (AC #4).** Build a `JsonElement` that **omits a
  required non-nullable field** (e.g. drop `cwd`, or `id`). Assert
  `assertThrows(SerializationException::class.java) { MobileJson.decodeFromJsonElement<ConversationResponseDto>(elem) }`.
  Note: dropping `name` would **not** fail (decodes to `null`) — the test must drop a *required*
  field to be meaningful.
- **(Recommended) Malformed `last_used_at` → `IllegalArgumentException` at decode.** Mirror #316's
  `malformedTimestamp_failsAtDecodeBoundary`: feed `last_used_at: "not-a-date"`, assert
  `assertThrows(IllegalArgumentException::class.java) { decode }`. Proves the timestamp is validated
  at the boundary (and pins the *correct* exception type — not `SerializationException`).

**Do not write an encode round-trip test.** These payloads are decode-only on the phone
(server → phone). Re-encoding a `name: null` row under `explicitNulls = false` would omit the key
and diverge from the Go wire. Test decode → map only.

## Open questions

- **Shared mapping helper, deferred.** This slice and #316 now both inline the four-placeholder
  rule (with cross-reference comments). If a **third** consumer of the rule lands (or the domain
  `Conversation` grows/loses a field), factor `(id, name, cwd, isPromoted, lastUsedAt) →
  Conversation` into one small `internal` helper in `data/network/` and have all per-shape mappers
  delegate — additive, one-time, deferred until a real second pressure exists. Not done now (one
  observed reuse is not enough to justify editing a landed file and adding indirection).
- **Strict-decode posture is intentional.** Every non-`name` field is required, so any omission
  fails the whole decode (the safer posture for an untrusted boundary). The server always emits all
  five keys today (#274); revisit only if it ever makes one optional. Parallel to #316's open
  question on its unused timestamp.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** This function **is** the explicit boundary where untrusted,
  post-Noise-decrypt wire JSON (`Envelope.payload: JsonElement`) becomes a typed in-process
  `Conversation`. The boundary is a single named surface —
  `decodeFromJsonElement<ConversationResponseDto>` + `toConversation()` — not scattered ad-hoc
  decoding. The pre-decode value is a `JsonElement` (the type signals "raw, not yet trusted");
  downstream holds a fully-typed `Conversation`. Decode validates **shape**, not semantics: it does
  **not** enforce the `is_promoted == true ⇒ name != null` invariant. A hostile/buggy server could
  send `{"is_promoted":true,"name":null,…}` and the mapper would faithfully produce a
  promoted-but-unnamed `Conversation`. This is **not** a finding — the domain `Conversation.name` is
  `String?` and is valid `null` for any `isPromoted` value (no crash, no type violation, only a
  display oddity: a channel rendered without a name), and the promoted⇒named invariant is a
  server-registry concern (`Registry.Promote`, #274), deliberately not re-litigated client-side
  (re-validating would be defensive coding against an unobserved failure mode — evidence-based
  deferral). No MUST FIX.
- **[Tokens / secrets / credentials]** N/A — the mutation-response payload carries no secret: only
  `id`, `name`, `cwd`, a bool, and one timestamp. No token is read, stored, logged, or compared.
  The device-pairing secret lives in the Noise early-data / credential store (#291/#294), nowhere in
  this DTO. No redacting `toString()` is needed (unlike `MobileWireModels.kt`'s `HelloClientPayload`
  / `QrPayload`, which redact a `token`).
- **[File / storage operations]** N/A at this layer — performs **no** file or storage I/O. Note
  `cwd` is an attacker-influenceable filesystem-**path string** carried verbatim, but this slice
  never opens, resolves, canonicalises, or traverses it — `cwd` is opaque display data here. Any
  future code that turns a server-supplied `cwd` into an actual `File` (e.g. a workspace-open
  action) **must** do its own canonicalisation + boundary check (`File.canonicalPath` against a
  known root); that is out of scope and owned by whoever performs the I/O, named here so the
  obligation is on record. This slice introduces no persistence — at-rest caching of decoded
  conversations is a future concern, deferred to its owning ticket. No MUST FIX.
- **[Inter-process / Android attack surface]** N/A — no `Activity`/`Service`/`Receiver`/deep
  link/`PendingIntent`/`WebView`, no exported component, no `android.*` import (the spec mandates
  `data/`-portable, pure Kotlin). Pure in-process data transform.
- **[Cryptographic primitives]** N/A — no crypto executed. Bytes arrive already decrypted by the
  Noise transport (#298/#309) upstream; this is plaintext-JSON shaping only. No RNG, no secret
  comparison.
- **[Network & I/O]** N/A at this layer — no socket, no OkHttp, no timeout surface. The inbound
  frame-size cap and read deadlines are the transport's concern (`OkHttpRelayTransport` /
  `RelayConnectionSupervisor`), enforced **before** bytes reach this decode. Adversarial
  resource-exhaustion check: the payload is a **single** bare conversation object (five small
  fields) — no array, so **less** allocation amplification than #316's row-list; `name`/`cwd` string
  lengths are bounded by the already-capped frame. A hostile relay cannot make this function
  allocate unboundedly beyond the already-capped frame. No MUST FIX.
- **[Error messages / logs / telemetry]** No logging is introduced. Decode exceptions are a
  `SerializationException` (names the missing/mismatched **field** — field names and types, never
  values) and an `IllegalArgumentException` (echoes the malformed timestamp **string**, which is
  non-sensitive). There are no secrets in this payload to leak. SHOULD note handed to the consuming
  mutation slice: avoid logging the raw `JsonElement` or the decoded `Conversation` at a level that
  reaches release Logcat — `cwd` (a filesystem path) and `name` (user-chosen) are non-secret but
  privacy-adjacent. Not a finding against this slice (it logs nothing). No MUST FIX.
- **[Concurrency]** N/A — one immutable DTO, a reused thread-safe `Json` singleton, a pure stateless
  function. No coroutine, scope, `StateFlow`, mutex, or shared mutable state; no TOCTOU surface.
- **[Threat model alignment]** This slice hardens the create/promote mutation-response decode path
  against malformed / partial untrusted input (the field-incomplete rejection, AC #4), so a hostile
  or buggy server cannot inject a half-populated or `null`-punned `Conversation` via the
  `conversation_created` / `conversation_updated` responses. Relay-MITM (→ Noise_IK transport,
  #298/#309), server-pubkey authenticity (→ #291/#294), at-rest caching of decoded conversations (no
  persistence introduced here), and the promoted⇒named semantic invariant (→ server registry, #274)
  are each named and **deferred to their owning tickets / layers**.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
