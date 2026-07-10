# Spec — #543 wire session settings (model / effort / YOLO) to `set_session_settings` v2

**Size:** S (confirmed; not split — see § Scope). **Security-sensitive:** yes (see § Security review).

Data-layer slice of #536 (three-way split: **#543 data → #544 UI/ViewModel → #545 e2e**). This ticket lands the repository seam; #544 wires the Status-sheet controls to it.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1400-1420` — the shipped `rename` method (#530). **This is the template** for the request → `sendAndAwaitReply` → typed-decode shape. `setSessionSettings` mirrors it **minus the `upsertConversation` fold** — session settings live in the ViewModel (#544), not in this repo's projection, so there is *no* state mutation here.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:276-351` — the `onInbound` inbound demux. **Line 332 is the reply-completion arm** (`TYPE_ACK, TYPE_CONVERSATION_CREATED, … -> pendingRequests[id]?.complete(payload)`); the new `session_settings_updated` reply type **must be added here** or the awaiting caller never completes (hangs until teardown). Line 346 is the `TYPE_ERROR` arm — **unchanged**; it already routes every correlated error through `mapError`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:618-630` — `mapError`. Confirms `conversation.not_found → IllegalArgumentException`, **every other code → `RelayErrorException`**. This verb's daemon codes are `session.not_found` / `protocol.malformed` / `server.binary_offline` (see § Context) — **none equal `conversation.not_found`**, so this verb has **no IAE-crash path**; all server errors are swallowable `RelayErrorException`. No change to `mapError`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:660-669` — `sendAndAwaitReply`. Registers the deferred, sends, throws `IllegalStateException` if the pump is not `Open`, awaits the correlated reply. Reused verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1505-1517` — the `TYPE_PROMOTE_CONVERSATION` / `TYPE_RENAME_CONVERSATION` / `TYPE_CONVERSATION_UPDATED` constants. Add the two new constants adjacent to these.
- `app/src/main/java/de/pyryco/mobile/data/network/RenameConversationPayloadDto.kt` (whole file) — sibling **encode-only** DTO; clone its `@Serializable` shape and kdoc register for the request DTO.
- `app/src/main/java/de/pyryco/mobile/data/network/ModalOutboundPayloads.kt` — precedent for **two `@Serializable` classes grouped in one file** (also `InteractivePayloads.kt` = 12). This is why `SessionSettingsPayloads.kt` may hold both DTOs — the ktlint single-class-filename rule fires only on a *single* top-level class, so a two-class payload file needs no per-class filename.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-33` — `MobileJson` config. **`explicitNulls = false`** is load-bearing: a `String? = null` / `Boolean? = null` field is **omitted** from the JSON (not emitted as `null`). This is the entire mechanism behind "omitted field = leave unchanged" (AC #1). A **non-null** value — including `""` or `false` — is always emitted.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:9` — `currentSessionId: String` (non-null). The routing key #544's ViewModel sources; **this method takes a session id, not a conversation id** — the first session-scoped mobile mutation.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:98,155-173` — the **throwing-default** pattern (`delete`, `createWorkspaceFolder`, `requestScreenSnapshot` = `error("…")`). Mirror it for the new method's default. **Rationale in § Design 3** — a *required* (no-default) method would cascade a stub override across the 13 anonymous `object : ConversationRepository` test doubles.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:100-113` — `rename` / `startNewSession` signatures; place the new method next to them.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:234-246` — the Fake's `rename` impl (state-`update` shape). The Fake's `setSessionSettings` is simpler (records the request; models no settings state — see § Design 5).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:99-113` — the `delete` / `rename` / `startNewSession` facade delegations. **NOT touched in #543** — the facade delegation is deferred to #544 (§ Scope). Shown so #544 follows this exact one-line pattern.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:592` (`sendMessage_sendsRequestMatchingWireContract`) and `:1035` (`promote_*` suite) — the **encode-assertion** and **round-trip** patterns to mirror; `startSend` / `startPromote` / `startRename` (~:3737) are the deferred-launch helper pattern to clone as `startSetSessionSettings`. The `error`-envelope helper used by `promote`/`rename`'s error tests is reused verbatim.
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt` — where the Fake-records-the-request test (AC #4) lands.
- **Server SSOT — inlined below** (§ Context). Source: `pyrycode/internal/protocol/settings.go` (#844) + handler `pyrycode/internal/relay/v2session.go:2536` `handleSetSessionSettings` (#845). The developer's dispatched context may not include the sibling repo — the shapes and codes here are authoritative for this spec.
- Relevant lessons (grep — codegraph doesn't index markdown): ktlint filename rule (grouped 2-class file is fine); `catch(IllegalStateException)` swallows `CancellationException` (N/A here — this verb adds **no** `catch`); `android.util.Log` throws in plain unit tests (N/A — this path **logs nothing**).

## Context

The Status sheet ("Run configuration") exposes model / effort / YOLO controls; today changing one updates ViewModel-local state only and never reaches the daemon (Tier-3 placebo in the 2026-07-03 backend-gaps audit). This ticket adds the **data-layer round-trip**: encode the request, send it, decode the daemon's ack. The ViewModel wiring (send-on-change, revert-on-failure, snackbar) is #544; this ticket lands the repository seam #544 calls.

This is the direct analogue of the shipped `rename` path (#530) — encode a payload → `sendAndAwaitReply` → typed-decode the reply — **minus the state fold** (rename upserts the returned conversation; settings have no read-model in this repo).

### Server SSOT (verified against `pyrycode` on `main`, not `protocol-mobile.md`)

**Request** — verb `set_session_settings` (phone → binary; intercepted in `dispatchAppFrame` before dispatch routing, like `new_session` / `modal_answer`). Payload (`SetSessionSettingsPayload`, `internal/protocol/settings.go`):

```go
type SetSessionSettingsPayload struct {
    SessionID string  `json:"session_id"`          // required — the target SESSION, not the conversation
    Model     *string `json:"model,omitempty"`     // nil = leave unchanged
    Effort    *string `json:"effort,omitempty"`    // nil = leave unchanged
    YOLO      *bool   `json:"yolo,omitempty"`       // nil = leave unchanged
}
```

The three fields are pointers as a **presence contract**: an omitted field means "leave unchanged"; a present field (including `*""` / `*false`) means "set to this value". Go's `omitempty` treats a non-nil pointer as non-empty regardless of the pointee, so `*false` still marshals — an omitted YOLO can never masquerade as a sent `false`. **Kotlin mirrors this exactly**: `String? = null` / `Boolean? = null` under `MobileJson`'s `explicitNulls = false` (§ Files to read → `MobileWireCodec.kt`).

**Reply** — verb `session_settings_updated`, payload `{ session_id }` **only** (`SessionSettingsUpdatedPayload`). It does **not** echo the applied settings; request↔reply correlation rides `Envelope.inReplyTo`. **Ack-shaped, like `send_message`** — the client already knows what it sent.

**Errors** (from the #845 handler; all messages are **fixed constants**, never attacker-influenced bytes):

| Condition | Code | Client mapping |
|---|---|---|
| Unhosted / unknown session (`ErrSessionUnknown`) | `session.not_found` | `RelayErrorException(code = "session.not_found")` |
| Decode failure / invalid `model` or `effort` value | `protocol.malformed` | `RelayErrorException(code = "protocol.malformed")` |
| Nil settings-updater / persist failure | `server.binary_offline` | `RelayErrorException(code = "server.binary_offline")` |
| Non-interactive conn | *(inert — no reply)* | not reachable from a capability-gated consumer; see § State + concurrency |

The daemon **re-validates** `model` / `effort` server-side (`validModel` / `validEffort`) and gates the whole verb on the interactive capability. The client is not the vocabulary authority: it forwards the caller-supplied strings verbatim (exactly as `rename` forwards the dialog's name), and the daemon rejects an invalid value with `protocol.malformed` before persisting.

## Design source

N/A — data-layer wire-up; no UI is added or modified. The Status-sheet controls (`StatusSheet.kt`) are wired to this seam in **#544**, which carries its own Figma anchor (Bottom Sheets node 22-4). Visual-fidelity check intentionally deferred to #544.

## Design

Four production touchpoints across four files. No new coroutine, no state fold, no `mapError` change.

### 1. New DTO file — `data/network/SessionSettingsPayloads.kt`

Two top-level `@Serializable data class`es in one file (grouped, per `ModalOutboundPayloads.kt`):

- **`SetSessionSettingsPayloadDto`** (encode-only): `@SerialName("session_id") sessionId: String`, `model: String? = null`, `effort: String? = null`, `yolo: Boolean? = null`. Both non-`session_id` fields nullable-default-null → omitted when null (`explicitNulls = false`), a non-null value (incl. `""` / `false`) always emitted. Wire field names snake_case (`session_id`; `model` / `effort` / `yolo` are already snake-safe).
- **`SessionSettingsUpdatedPayloadDto`** (decode-only): `@SerialName("session_id") sessionId: String` only.

kdoc register mirrors `RenameConversationPayloadDto.kt`: request is encode-only (phone sends it, never decodes), reply is decode-only; cite the server SSOT (`SetSessionSettingsPayload` / `SessionSettingsUpdatedPayload`, #844) and the presence-contract rationale (omitted = leave unchanged).

### 2. Two companion constants

Add to the private `companion object` (adjacent to `TYPE_RENAME_CONVERSATION`, ~:1508):

```kotlin
const val TYPE_SET_SESSION_SETTINGS = "set_session_settings"        // request
const val TYPE_SESSION_SETTINGS_UPDATED = "session_settings_updated" // correlated ack
```

Both are referenced in prod: the request type on the outbound `Envelope`, the reply type in the demux arm (§ 3).

### 3. Extend the inbound demux reply-completion arm (~:332)

Add `TYPE_SESSION_SETTINGS_UPDATED` to the existing `complete(payload)` case:

```kotlin
TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED, TYPE_SCREEN_SNAPSHOT, TYPE_SESSION_SETTINGS_UPDATED ->
    envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
```

Extend the arm's kdoc: `session_settings_updated` (#543) carries the bare `{session_id}` ack the `setSessionSettings` waiter decodes for validation. It is **always** a correlated reply — the daemon replies only to the requester, never broadcasts (SSOT) — so an unmatched `inReplyTo` is a harmless no-op (like `screen_snapshot`), and `complete` is idempotent against a duplicate. The `TYPE_ERROR` arm (~:346) already handles this verb's errors — no change.

### 4. New interface method + throwing default — `ConversationRepository.kt`

Add adjacent to `rename` (~:104), mirroring the `delete` / `requestScreenSnapshot` **throwing-default** idiom:

```kotlin
suspend fun setSessionSettings(
    sessionId: String,
    model: String? = null,
    effort: String? = null,
    yolo: Boolean? = null,
): Unit = error("setSessionSettings is not implemented for this ConversationRepository")
```

Return **`Unit`** — the reply carries no new information (its `session_id` equals the input), so there is nothing to return; success is a normal return, failure is a thrown exception the caller (#544) catches. The `= null` defaults make the single-control call site (`setSessionSettings(id, model = "opus")`) ergonomic (AC #1 "omitted settings mean leave unchanged").

**Why a default and not a required override.** A required method would force a stub override on the **13 anonymous `object : ConversationRepository`** doubles and the 2 `RecordingRepo` classes (§ fan-out check) — a ~15-file test cascade for a method none of them exercise in #543. The throwing default is the codebase's established cascade-avoidance (kdoc on `delete`/`observeStall`/`requestScreenSnapshot`). The Fake and Remote override it; the `by delegate` doubles inherit their delegate's impl; the anonymous doubles inherit the throw (unreached).

### 5. Remote implementation — `RemoteConversationRepository.kt`

Contract: `override suspend fun setSessionSettings(sessionId, model, effort, yolo): Unit`. Behaviour (mirror `rename` :1404-1419, **minus the upsert**):

1. Build an `Envelope` with `type = TYPE_SET_SESSION_SETTINGS`, `id = requestId.incrementAndGet()`, `ts = Clock.System.now().toString()`, `payload = MobileJson.encodeToJsonElement(SetSessionSettingsPayloadDto(sessionId, model, effort, yolo))`.
2. `val reply = sendAndAwaitReply(request)` — throws `IllegalStateException` (not connected) or the mapped `RelayErrorException` (server `error`) before returning; on ack, returns the `{session_id}` payload.
3. `MobileJson.decodeFromJsonElement<SessionSettingsUpdatedPayloadDto>(reply)` — the **AC #2 typed reply boundary**: a malformed ack throws here (#318 posture). The decoded value is discarded — it echoes only the input `session_id`; the decode's purpose is *validation*, not a return value.

No state fold, no `upsertConversation`, no `Log.*`. kdoc: describe the round-trip, the presence-contract encoding (omitted = leave unchanged), the ack-validation decode, `Unit` return, and the three throw types (`IllegalStateException` not-connected, `RelayErrorException` any server error incl. `session.not_found`, `#318` decode exception for a malformed ack) — **none mutate any projection** (there is none to mutate).

### 6. Fake implementation (AC #4) — `FakeConversationRepository.kt`

The Fake models no per-session settings state (the data model has none, and adding one is out of scope). Implement it to **record** each request so #544's ViewModel tests can assert send-on-change:

- Expose `val setSessionSettingsCalls` (a `MutableStateFlow<List<…>>` or plain list) of recorded requests.
- `override suspend fun setSessionSettings(…)` appends the four fields verbatim and returns `Unit` (success).
- Record shape: reuse `SetSessionSettingsPayloadDto` (same four fields, `data class` structural equality for free) to avoid minting a parallel record type. A 4-field local `data class` is an acceptable substitute if the wire-DTO import reads oddly; do **not** add a new *exported* type.

### Facade — deferred to #544 (documented)

`StableConversationRepository` (the injected facade) is **intentionally not touched here**. It inherits the throwing default, which is safe: no consumer calls `setSessionSettings` through the facade until #544 wires the Status-sheet controls — and #544 adds the one-line delegation as part of that wiring:

```kotlin
override suspend fun setSessionSettings(sessionId, model, effort, yolo) =
    live.setSessionSettings(sessionId, model, effort, yolo)
```

This keeps #543 scoped to what its ACs and tests exercise (they construct `RemoteConversationRepository` / `FakeConversationRepository` **directly**, never through the facade — see the `rename`/`promote` test pattern). See § Scope and § Open questions.

### Data flow

```
#544 ViewModel (onModelSelected / onEffortSelected / onYoloToggled)   [not in #543]
  └─ repository.setSessionSettings(currentSessionId, model?, effort?, yolo?)
       └─ RemoteConversationRepository.setSessionSettings
            ├─ encode SetSessionSettingsPayloadDto ──▶ Envelope(set_session_settings)
            │      (null fields omitted → "leave unchanged"; non-null incl. false/"" sent)
            ├─ sendAndAwaitReply ──▶ pump.send; await correlated reply by inReplyTo
            │      ├─ not Open        → throw IllegalStateException
            │      └─ server `error`  → mapError → RelayErrorException(code)   (session.not_found | protocol.malformed | server.binary_offline)
            └─ decode<SessionSettingsUpdatedPayloadDto>(reply)   (typed ack boundary; malformed → throw)
                   └─ return Unit                                (no projection touched)
       ▲
onInbound demux: session_settings_updated (inReplyTo) → pendingRequests[id].complete(payload)
                 error (inReplyTo)                     → pendingRequests[id].completeExceptionally(mapError)
```

## State + concurrency model

- **No new coroutine / scope.** `setSessionSettings` is a plain `suspend` method; #544 calls it from its existing guarded launch. The request↔reply await runs on the shared single inbound collector via `sendAndAwaitReply`, correlated by `Envelope.id` ↔ reply `inReplyTo` — **no second pump subscription**.
- **No state.** Unlike every other mutation, this verb folds **nothing** into the projection — session settings are ViewModel state (#544). So there is no single-source-of-state concern here; the read models (`observeConversations`, `observeMessages`) are untouched.
- **Cancellation / teardown.** Inherited from `sendAndAwaitReply`: the deferred is registered before send, removed in `finally` (success / error / caller cancellation); #488's `failAllPending` completes a mid-await deferred with `IllegalStateException` on teardown — so a settings change in flight when the connection drops surfaces as ISE (the caller catches it), never hangs.
- **Non-interactive daemon (no-reply) edge.** The daemon is *inert* (sends no reply) for a non-interactive conn. That path is **not reachable** from a capability-gated consumer: the Status-sheet controls are only live on an interactive session (#544 gates them). If it ever occurred, `sendAndAwaitReply` would suspend until #488's teardown sweep surfaces ISE — the same bounded failure mode as every other correlated verb, never a permanent hang. No special handling in #543.

## Error handling

All mapping is **centralized** in `mapError` (:618) + `sendAndAwaitReply`'s `check(pump.send(...))` (:664). The `setSessionSettings` body adds **no per-error catch** and needs **no `mapError` change**:

| Failure | Produced by | Type thrown | #544 handling (out of scope) |
|---|---|---|---|
| Not connected (pump not Open) | `sendAndAwaitReply` `check(...)` | `IllegalStateException` | revert + snackbar |
| `session.not_found` (unhosted session) | `mapError` else-branch | `RelayErrorException(code = "session.not_found")` | revert + snackbar; **distinguishable by `.code`** (AC #3) |
| `protocol.malformed` (invalid model/effort) | `mapError` else-branch | `RelayErrorException(code = "protocol.malformed")` | revert + snackbar |
| `server.binary_offline` (unwired/persist) | `mapError` else-branch | `RelayErrorException(code = "server.binary_offline")` | revert + snackbar |
| Malformed `session_settings_updated` ack | `SessionSettingsUpdatedPayloadDto` decode | `SerializationException` / `IllegalArgumentException` | (defensive; not reachable from a well-behaved daemon) |

**Key contrast with `rename`.** `rename`'s `conversation.not_found` maps to `IllegalArgumentException` (the guard deliberately crashes on it as a programming-bug signal). `setSessionSettings` has **no such path**: its unhosted-session code is `session.not_found`, which `mapError` routes to `RelayErrorException` (the else-branch), not IAE. So **every server error here is a swallowable/surface-able `RelayErrorException`** carrying its typed `code` — the consumer never crashes on a settings failure. This is simpler than `rename`; do **not** introduce an IAE branch.

**No server text leaks.** The daemon returns fixed constants (`msgSettingsMalformed` / `msgSettingsNotFound` / `msgSettingsUnavailable`), never payload bytes or `model`/`effort` values. On the client, `RelayErrorException.message` is server-supplied and this spec **never logs or surfaces it** (encode / decode / error paths add no `Log.*`). #544 surfaces a *generic* snackbar, not the server message.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`); no instrumented test. Remote-level tests construct `RemoteConversationRepository(FakeSessionPump(), backgroundScope)` directly (the `rename`/`promote` pattern) and drive via a `startSetSessionSettings` helper (clone `startRename`). Add a small `sessionSettingsUpdatedEnvelope(inReplyTo, sessionId)` reply builder; reuse the existing `error`-envelope helper. Scenarios (assert inputs → expected wire/behaviour; the developer writes the bodies in-idiom):

**Request encoding (mirror `sendMessage_sendsRequestMatchingWireContract`):**
- **Single field — model only:** `setSessionSettings(sessionId = "s1", model = "opus")` → sent frame `type == "set_session_settings"`, payload **exactly** `{"session_id":"s1","model":"opus"}` (two keys — `effort` / `yolo` **absent**, proving null = omitted = leave unchanged).
- **Single field — effort only** → `{"session_id":"s1","effort":"high"}`.
- **Single field — yolo `false`** → `{"session_id":"s1","yolo":false}` (proves a non-null `false` **is** sent — distinguishable from omitted; the presence-contract invariant).
- **Combined fields:** `model = "opus", effort = "high", yolo = true` → payload has all four keys.

**Success ack:**
- **Ack completes the call:** send, then push `sessionSettingsUpdatedEnvelope(inReplyTo = sent.id, sessionId = "s1")`; the call returns successfully (proves the demux arm routes the new reply type to the waiter).
- **Malformed ack → decode throws:** push a `session_settings_updated` reply missing `session_id`; the call fails with the #318 decode exception.

**Error mapping:**
- **`session.not_found` → `RelayErrorException`,** and assert `.code == "session.not_found"` (AC #3 distinguishability) **and** that it is **not** `IllegalArgumentException` (contrast with conversation-scoped verbs).
- **`protocol.malformed` → `RelayErrorException(code = "protocol.malformed")`.**
- **(Optional, mirrors above)** `server.binary_offline` → `RelayErrorException` carrying the code.

**Not connected:**
- **Pump never Open (`FakeSessionPump` default) → `IllegalStateException`**; no reply awaited.

**Fake (AC #4) — in `FakeConversationRepositoryTest.kt`:**
- **Records the request:** call `setSessionSettings("s1", model = "opus", effort = null, yolo = true)` on the Fake; assert `setSessionSettingsCalls` holds the verbatim four fields and the call returns without throwing.

## Scope

Production source files (Kotlin, excluding tests / md / spec): **4** —
1. `data/network/SessionSettingsPayloads.kt` (**new**),
2. `data/repository/RemoteConversationRepository.kt` (modified: demux arm + 2 constants + method),
3. `data/repository/ConversationRepository.kt` (modified: interface method + default),
4. `data/repository/FakeConversationRepository.kt` (modified: recording impl).

New exported types: **2** (`SetSessionSettingsPayloadDto`, `SessionSettingsUpdatedPayloadDto`; the Fake reuses the request DTO as its record shape). New reject branches: **0** (error mapping centralized and reused; no IAE path). Consumer fan-out: **0** (the throwing default absorbs the 13 anonymous test doubles; the facade delegation is deferred to #544). ~75 production LOC + ~180 test LOC ≈ **~255 total**. Every § 1 red line clears with margin; the ≥5-prod-file commit gate clears at **4** because the facade delegation lands with its consumer (#544), where it is a documented one-liner. **Size S confirmed** — PO's `size:s` upheld, not overridden.

## Open questions

None blocking. Deliberate boundaries, all documented above:
- **Facade delegation deferred to #544.** `StableConversationRepository.setSessionSettings` is added by #544 alongside the Status-sheet wiring. Nothing on `main` calls the facade method in the interim (no consumer exists), and the throwing default makes an accidental early call fail loudly rather than silently no-op. #544's architect: add the one-line `live.setSessionSettings(...)` delegation (mirror `rename` at `StableConversationRepository.kt:105`).
- **Model / effort vocabulary is the caller's (#544's) concern.** This method forwards `String?` verbatim; the daemon re-validates. #544 maps the `Model` / `Effort` enums to their wire strings before calling. #543's encode tests use representative values (`"opus"`, `"high"`) to document the shape, not to pin the vocabulary.
- **`session_transition` (#336) is the source of `currentSessionId` freshness** — out of scope here; #544 reads `Conversation.currentSessionId` as the routing key.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. The one untrusted→trusted crossing is the `session_settings_updated` reply, decoded at the single explicit `SessionSettingsUpdatedPayloadDto` boundary (#318). A malformed reply throws at decode; there is **no state fold at all** on this path (settings live in the ViewModel, #544), so even a well-formed-but-hostile ack reaches nothing but a discarded local value. The outbound request carries only the caller-supplied `sessionId` (the currently-active `currentSessionId`) and the caller-supplied `model` / `effort` / `yolo`, all of which the daemon **re-validates** server-side (`validModel` / `validEffort`, and the interactive-capability gate) — the client is authority for none of them.
- **[Tokens, secrets, credentials]** N/A — no tokens / keys / credentials on this path. It rides the already-established Noise_IK session (`data/network`, `data/crypto` untouched). `session_id` is a routing id, not a secret.
- **[File / storage operations]** N/A — no filesystem access. `model` / `effort` are never concatenated into a path or argv on the client; they are wire fields. (The daemon's argv-injection defense — rejecting invalid values before they reach claude's argv — is server-side, #845, and independent of this client change.)
- **[Inter-process / Android attack surface]** N/A — no new `Activity` / `Service` / `Receiver` / deep-link / `PendingIntent` / provider / WebView. An internal repository method behind the DI-wired `ConversationRepository`.
- **[Cryptographic primitives]** N/A — no RNG / hashing / key handling / comparisons introduced. `Envelope.id` reuses the existing `requestId.incrementAndGet()` monotonic correlation counter — not a secret — as every sibling verb does.
- **[Network & I/O]** No findings. Reuses `sendAndAwaitReply` over the existing `SessionPump` / OkHttp WebSocket transport — no new socket, timeout, TLS, or frame-size config. #488's `failAllPending` bounds a settings change in flight at teardown to a prompt `IllegalStateException`, never a hang. The demux-arm addition (one type token) is on the single inbound collector — race-free under the existing single-consumer invariant.
- **[Error messages, logs, telemetry]** No findings — and the security-relevant heart of the ticket. The daemon returns **fixed constant** error strings (`msgSettingsMalformed` / `msgSettingsNotFound` / `msgSettingsUnavailable`), never a decode error, payload byte, or `model`/`effort` value (`settings.go` handler comment, #845). On the client, `RelayErrorException.message` (server-supplied) is **never logged and never surfaced** by this spec; encode / decode / error paths add **no `Log.*`**. #544 surfaces a *generic* snackbar, not the server text.
- **[Concurrency]** No findings. No new coroutine / scope / mutex; **no state mutation** on any path, so no TOCTOU and no partial-write window (a failed settings change literally cannot corrupt a read model — there is none to touch). Every throw is ordered before the (no-op) return. `complete` / `completeExceptionally` on the pending deferred are idempotent.
- **[Threat model alignment]** The relevant mobile-wire threat — a malicious / compromised relay returning a crafted `session_settings_updated` or `error` frame — is contained: a crafted ack fails the typed decode (throws, no effect) or, if well-formed, is discarded; a crafted error maps to a swallowable `RelayErrorException` with **no text leak** and **no IAE-crash path** (unlike `rename`, this verb never produces `conversation.not_found` → IAE). Applying an attacker-chosen model/effort is a *server-authority* concern: the daemon re-validates and persists; the client faithfully forwarding the operator's own selection is correct, not a vulnerability. Out of scope (named): the `Model`/`Effort` → wire-string mapping and the interactive gate on the controls, both #544; server-side value validation, #845.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10
