# Spec #374 — snapshot wire DTOs (`request_snapshot` / `screen_snapshot`)

**Size:** XS · **Label:** `security-sensitive` (Phase-4 encrypted-wire surface) · **Mirrors** server pyrycode#617.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt:31-37,113-159` — the canonical pattern. `MessagePayloadDto` (decode-only, lines 31-37) and `BackfillSincePayloadDto` / `SendMessagePayloadDto` (encode-only, lines 134-159) are the exact shapes to copy: `@Serializable data class`, `@SerialName` for snake_case wire names, declaration order matching the Go struct, KDoc that names the SSOT + the encode-only/decode-only direction. **The fact that one file already holds an encode-only request (`BackfillSincePayloadDto`) alongside a decode-only response (`MessageChunkPayloadDto`) is the precedent for co-locating both snapshot DTOs in one file.**
- `app/src/main/java/de/pyryco/mobile/data/network/RegisterPushTokenPayloadDto.kt:27-32` — minimal single-purpose encode-only DTO. `RequestSnapshotPayloadDto` is this shape narrowed to one field.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` config (`encodeDefaults = true`, `explicitNulls = false`, `ignoreUnknownKeys = true`). This is the only (de)serialization entry point; unknown-field tolerance (AC #2) is inherited from `ignoreUnknownKeys` here — you do **not** add anything for it.
- `app/src/test/java/de/pyryco/mobile/data/network/MessagePayloadTest.kt:33-173` — the JUnit4 test idiom to mirror exactly: decode via `MobileJson.parseToJsonElement(fixture)` → `MobileJson.decodeFromJsonElement<T>(element)`; encode via `MobileJson.encodeToJsonElement(dto).jsonObject` then assert `element.getValue("conversation_id").jsonPrimitive.content`. `backfillSince_encodesWireFieldNames` (line 152) is the direct template for the request-encode test.

There is no `docs/lessons.md`, `docs/PROJECT-MEMORY.md`, or `docs/knowledge/architecture/system-overview.md` in this repo — don't look for them.

## Context

Part of pyrycode#596 (Phase 2 structured streaming). The screen snapshot is the **always-available, parser-independent floor** of ADR 025's safe-degradation strategy: the phone asks for a one-shot text picture of the current claude screen, and the daemon renders it via tui-driver inside the substrate seal. It depends on no screen parser, so it survives any parser break.

The server already shipped the matching `request_snapshot` / `screen_snapshot` Type constants + structs (pyrycode#617, merged) and the daemon handler (pyrycode#618, merged). **This slice is the wire-vocabulary half of the mobile side: the two data shapes only.** No repository method, no dispatch, no trust decision — those land in the consumer slice #375 (blocked on this). It mirrors the server's own line, where #617 shipped the structs as a standalone "only structs and constants" slice and the trust boundary lives in the consumer.

## Design

One new file, two DTOs, co-located. Both go in:

```
app/src/main/java/de/pyryco/mobile/data/network/SnapshotPayload.kt
```

Naming follows the established multi-type wire-model files (`MessagePayload.kt`, `ConversationsPayload.kt`): singular `XPayload.kt` holding several payload types. **ktlint's `filename` rule does not fire here** — it only requires a filename match when a file has exactly one public top-level type. Two public DTOs ⇒ no constraint (proven by `MessagePayload.kt`, whose types are `MessagePayloadDto` et al., none named `MessagePayload`). Do **not** split into one-DTO-per-file; the request/event pair is one logical exchange and the in-file precedent (`BackfillSincePayloadDto` + `MessageChunkPayloadDto`) is exact.

### DTO 1 — `RequestSnapshotPayloadDto` (encode-only)

Wire: `request_snapshot` — `{conversation_id}` (phone → daemon control). Contract:

```kotlin
@Serializable
data class RequestSnapshotPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
```

- Single required field; `@SerialName("conversation_id")` carries the snake_case wire name (the Go-interop contract, not cosmetic).
- Encode-only is a **usage convention documented in the KDoc**, not a structural restriction — identical to `SendMessagePayloadDto` / `BackfillSincePayloadDto`. The class is plain `@Serializable`.

### DTO 2 — `ScreenSnapshotPayloadDto` (decode-only)

Wire: `screen_snapshot` — `{conversation_id, text, ts}` (daemon → phone event). Contract:

```kotlin
@Serializable
data class ScreenSnapshotPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val text: String,
    val ts: String,
)
```

- Field declaration order matches the server Go struct (SSOT pyrycode#617): `conversation_id`, `text`, `ts`. `text` and `ts` are already snake-case-free wire names, so they need no `@SerialName`.
- **`text` is a plain `String`** — the **rendered text** of the screen, never raw control codes / raw bytes (ADR 025 no-raw-bytes invariant, enforced server-side by the daemon's tui-driver renderer). The DTO models it verbatim and must **not** mutate, strip, or sanitize it — decode fidelity is the whole point of the snapshot floor. See § Security review, Trust boundaries.
- **`ts` is a plain `String`** — the raw wire timestamp. Do **not** parse it to `Instant` / `kotlinx.datetime`. The consumer (#375) returns `text` only and never reads `ts`; modeling it strict (required, non-null `String`) keeps the untrusted-boundary decode honest without adding a parse this slice doesn't need. (Contrast `MessagePayloadDto`, where the timestamp lives on the *envelope* and is parsed by `toMessage`; here `ts` is a *payload* field and stays a string.)
- Decode-only is a usage convention in the KDoc (the phone never sends a `screen_snapshot`), identical to `MessagePayloadDto`.

### Out of scope — do NOT add

- **No type-string constants.** The envelope `type` values `"request_snapshot"` / `"screen_snapshot"` are set by the consumer (#375) when it builds the `Envelope`, exactly as `"send_message"` / `"list_conversations"` are inline literals at their call sites today. There is no mobile-side type-constants registry; do not invent one.
- **No repository method, no dispatch, no `Envelope` wiring, no `toMessage`-style mapper.** This slice is the data shapes only. `ScreenSnapshotPayloadDto` has no domain-mapping function — the consumer reads `.text` directly.
- **No `MobileWireCodec.kt` change.** `MobileJson` already does everything needed.

## State + concurrency model

None. Pure serializable data classes with no coroutines, no flows, no scopes, no mutable state. Portable `data/` (kotlinx.serialization only; no `android.*`, no `kotlinx.datetime`).

## Error handling

Decode failure for `ScreenSnapshotPayloadDto` (missing required `conversation_id` / `text` / `ts`, or wrong JSON type) surfaces as `kotlinx.serialization.SerializationException` from `MobileJson.decodeFromJsonElement` — the standard structural-decode boundary, same as every sibling DTO. This slice neither catches nor maps it; the consumer (#375) decides how a malformed `screen_snapshot` envelope is handled. Unknown extra fields are tolerated (not an error) via `MobileJson`'s `ignoreUnknownKeys = true`.

`RequestSnapshotPayloadDto` encoding cannot fail (one non-null `String` field).

## Testing strategy

Unit only (`./gradlew test`), JUnit4, one new file `app/src/test/java/de/pyryco/mobile/data/network/SnapshotPayloadTest.kt`, mirroring `MessagePayloadTest.kt`. Scenarios (write each as a test method; assertions described, not pre-written):

1. **request encodes wire field name** (AC #1) — encode `RequestSnapshotPayloadDto("c1")` via `MobileJson.encodeToJsonElement(...).jsonObject`; assert the object has key `conversation_id` with content `"c1"`. (Template: `backfillSince_encodesWireFieldNames`.)
2. **screen_snapshot decodes all three fields** (AC #2) — `parseToJsonElement` a `{"conversation_id":"c1","text":"<rendered screen>","ts":"2026-06-08T10:00:00Z"}` fixture → `decodeFromJsonElement<ScreenSnapshotPayloadDto>`; assert `conversationId == "c1"`, `text` equals the fixture string verbatim, `ts == "2026-06-08T10:00:00Z"`.
3. **screen_snapshot tolerates unknown fields** (AC #2) — same as (2) but the fixture carries an extra unmodeled key (e.g. `"cols":80`); assert decode succeeds and the three modeled fields are unchanged. (Proves `ignoreUnknownKeys` forward-compat.)
4. **`text` round-trips a multi-line / whitespace-laden value verbatim** — fixture `text` containing `\n` and leading spaces (a realistic rendered screen); assert the decoded `text` is byte-for-byte the fixture value, proving the DTO does not trim/normalize. (Guards the no-mutation decode-fidelity invariant.)
5. *(recommended, mirrors `fieldIncompletePayload_throwsTypedDecodeFailure`)* **missing required field throws** — a `screen_snapshot` fixture dropping `text` decodes to `SerializationException`. Cheap, matches the strict-decode posture of every sibling boundary.

No instrumented tests, no `ComposeTestRule`, no `runTest` (no coroutines).

## Open questions

None. The wire shapes are frozen by pyrycode#617 (merged SSOT); the modeling decisions (plain-`String` `text` and `ts`, one co-located file, no type constants) are all settled above.

## Security review

**Verdict:** PASS

Adversarial self-review per `architect/security-review.md`. Default-FAIL posture; each applicable category produced a concrete finding or a stated reason it does not apply.

**Findings:**

- **[Trust boundaries]** No MUST FIX. `ScreenSnapshotPayloadDto` is the untrusted→typed boundary for an inbound `screen_snapshot`. The boundary is *explicit and single*: `MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(envelope.payload)` at one consumer site (#375), over a peer that is **already cryptographically authenticated** — `screen_snapshot` arrives inside `noise_msg` on the Noise_IK channel (`NoiseSessionPump` / `OkHttpRelayTransport`), so a relay or MITM can neither forge nor read it. Strict required fields (`conversation_id`, `text`, `ts`, all non-null) mean a malformed frame fails closed with `SerializationException`, never a partial/`null`-punned value. **The DTO must not mutate/strip/normalize `text`** — decode fidelity is the entire purpose of the parser-independent snapshot floor; the no-raw-bytes guarantee (ADR 025) is enforced server-side by the daemon renderer, the trusted authenticated peer. Spec § Design DTO 2 + test scenario 4 lock this in.

- **[Tokens / secrets]** No MUST FIX. Neither DTO carries a credential — `conversation_id`/`ts` are opaque ids, `text` is message-class *content*, not a secret. Deliberately **no `toString` redaction**, keeping parity with the sibling content-bearing `MessagePayload.kt:31-37` (`MessagePayloadDto` has none); the `toString`-redaction precedent (`HelloClientPayload`, `QrPayload`) is reserved for the `token` *credential*, and redacting content here would be an inconsistent defense against an unobserved failure mode. SHOULD-note, routed to consumer #375 / code-review (not a gate on this slice, which adds zero log calls): `text` is potentially-sensitive rendered screen content and MUST NOT be logged when decoded/dispatched, per the #346 no-content-logging posture.

- **[Error messages / logs / telemetry]** No findings. This slice adds no log calls, no error strings, no telemetry. A decode failure surfaces kotlinx-serialization's own `SerializationException`, which names the *absent/mismatched schema key* (e.g. `text`), not the field *value* — it does not echo screen content.

- **[Network & I/O]** No findings (out of scope by construction). No new ingress, OkHttp config, timeout, or frame policy. Unbounded-`text`-size DoS is bounded upstream by the existing transport's frame-size cap (`OkHttpRelayTransport` / Noise pump), not by this DTO; this slice decodes only what the transport already admitted.

- **[File / storage]** N/A — no file I/O, no persistence, no path handling. `conversation_id` is never used as a filesystem path here. `data/` portability preserved (kotlinx.serialization only; no `android.*`).

- **[Android attack surface]** N/A — no Activity/Service/Receiver/Intent/deep-link/PendingIntent/ContentProvider/WebView. Pure data classes.

- **[Cryptographic primitives]** N/A — no RNG, hashing, or key handling introduced. The authenticated/encrypted channel is the pre-existing Noise_IK transport, unchanged.

- **[Concurrency]** N/A — no coroutines, scopes, flows, or shared mutable state. Immutable `data class`es are inherently thread-safe.

- **[Threat model alignment]** A hostile relay/MITM cannot forge or read a `screen_snapshot` (Noise_IK seal); a malformed authenticated frame fails closed at decode. **OUT OF SCOPE, named:** display-time handling of snapshot text — screenshot leakage (`FLAG_SECURE`), accessibility-service eavesdropping, and any terminal/control-sequence rendering concern — is a render-layer concern owned by consumer #375 and the thread UI, not this wire-DTO slice. (Compose `Text` does not interpret terminal control codes as a TTY would, so there is no terminal-injection surface introduced by modeling `text` as a verbatim `String`.)

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
