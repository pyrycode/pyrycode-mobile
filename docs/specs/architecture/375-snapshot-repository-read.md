# Spec — `ConversationRepository.requestScreenSnapshot` over v2 (`request_snapshot` / `screen_snapshot`) (#375)

> The **consumer / trust-boundary half** of the mobile snapshot slice. Adds a `requestScreenSnapshot`
> read to `ConversationRepository`, fills it on the live `RemoteConversationRepository` by sending a
> `request_snapshot` and returning the correlated `screen_snapshot` reply's `text`, gives the fake a
> deterministic canned contract, and routes it through the `StableConversationRepository` facade.
> Reuses the #346 request↔reply correlation primitive verbatim; the **one genuinely-new bit** is
> routing a *new* correlated reply type (`screen_snapshot`) to its waiter. Consumes the wire DTOs that
> landed on `main` in #374. Mirrors server pyrycode#618 (merged). **`security-sensitive`** — the
> snapshot `text` is server-originated network input returned literally to consumers.

**Size:** S (PO-sized S; not overridden). 4 production files modified, 0 new files, 0 new exported
types, 3 override sites (the throwing default means **no** override cascade into the inline
`object : ConversationRepository` test doubles — same precedent as `delete` / `createWorkspaceFolder`).
No split.

## Files to read first

Generated from `codegraph_context` + the reads done during sizing; pruned to what the implementer
needs on turn 1.

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:44-61,102-120` — the
  **throwing-default precedent**. `delete` (44-61) and `createWorkspaceFolder` (102-120) are
  `suspend fun … = error("…")` defaults: implementations that don't support them inherit the throw,
  so the inline test doubles never override them. Copy this exact shape for `requestScreenSnapshot`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:195-216` — the
  inbound `onInbound` demux `when`. The success branch (195-205) routes `ack` /
  `conversation_created` / `conversation_updated` to `pendingRequests[id]?.complete(envelope.payload)`
  by `inReplyTo`. **This slice adds `TYPE_SCREEN_SNAPSHOT` to that same success branch** — the one
  new bit. The `inReplyTo?.let { pendingRequests[it]?…}` guard already makes an unmatched reply a
  harmless no-op.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:262-279` —
  `sendAndAwaitReply(request: Envelope): JsonElement`, the reusable register→send→await primitive.
  `requestScreenSnapshot` calls it verbatim; reuse the `pendingRequests` registry + `requestId`
  `AtomicLong` (119-130) as-is. **No second pump subscription, no new collector.**
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:494-539` —
  `sendMessage`, the one-shot-suspend template to mirror: inline `Envelope(id =
  requestId.incrementAndGet(), …)`, `sendAndAwaitReply(request)`, then act on the reply. (Contrast
  `createDiscussion` at 432-446, which **decodes** its reply payload — `requestScreenSnapshot` does
  the same decode-the-reply step, returning `.text`.)
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:228-240` —
  `mapError`: `conversation.not_found` → `IllegalArgumentException`, else `RelayErrorException`.
  **Reuse as-is — no new error mapping.** pyrycode#618 emits only `conversation.not_found` and
  `server.binary_offline`, both already handled here.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:596-664` — the
  companion-constant block. Add `TYPE_REQUEST_SNAPSHOT` / `TYPE_SCREEN_SNAPSHOT` alongside the
  existing `TYPE_*` literals.
- `app/src/main/java/de/pyryco/mobile/data/network/SnapshotPayload.kt:20-50` — the landed #374 DTOs.
  `RequestSnapshotPayloadDto(conversationId)` (encode-only) and
  `ScreenSnapshotPayloadDto(conversationId, text, ts)` (decode-only, strict). The consumer encodes
  the first, decodes the second, returns `.text` only, never reads `ts`. **Never mutate/strip/sanitize
  `text`.**
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson` config.
  Always (de)serialize through `MobileJson`, never a default `Json`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:255-281,335` —
  the fake's `sendMessage` (the observable-contract idiom to mirror: read `state`, throw on unknown)
  and `unknown(id)` (335: `IllegalArgumentException("Unknown conversation: $id")` — the exact type +
  message AC #4 mirrors).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:84-110` — the
  facade's one-shot delegations. `delete` (88) and `createWorkspaceFolder` (110) are **explicit
  overrides that delegate to `live`** even though the interface defaults them — because the facade
  must reach the live impl, not inherit the throw. Add `requestScreenSnapshot` the same way.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:581-740` —
  the `sendMessage` round-trip tests: the exact idiom to clone. Plus the helpers at
  `1345-1353` (`startSend` → an analogous `startSnapshot` launcher), `1447-1465` (`ackEnvelope` /
  `errorEnvelope`), and `1558-1576` (`FakeSessionPump`: `sent` list, togglable `sendResult`, `push`).
- `app/src/test/java/de/pyryco/mobile/data/repository/FakeConversationRepositoryTest.kt:630-699` —
  the fake's `sendMessage` append test + `sendMessage_onUnknownId_throws` (694-699): the
  known-id / unknown-id `try/catch (IllegalArgumentException)` idiom to mirror.
- `app/src/test/java/de/pyryco/mobile/data/repository/StableConversationRepositoryTest.kt:179-277` —
  the facade delegation test (`oneShots_whenLive_delegateWithExactArgsAndReturn`, 179-197) and the
  `RecordingConversationRepository` double (221-277). Extend the double with a
  `requestScreenSnapshotCalls` / `requestScreenSnapshotResult` pair (mirroring `sendMessageCalls` /
  `sendMessageResult`) and add a delegation assertion.
- `docs/specs/architecture/346-remote-send-message.md` and `docs/specs/architecture/374-snapshot-wire-dtos.md`
  — the mirrored correlation slice and the wire-DTO slice this consumes. Read for the rationale, not
  to copy.

There is no `docs/lessons.md`, `docs/PROJECT-MEMORY.md`, or `docs/knowledge/architecture/system-overview.md`
in this repo — don't look for them.

## Context

Phase 4 backend integration; part of pyrycode#596 (Phase 2 structured streaming), ADR 025 §
Safe degradation. The screen snapshot is the **always-available, parser-independent floor** of the
degrade strategy: the phone asks for a one-shot text picture of the current claude screen and the
daemon renders it via tui-driver inside the substrate seal. It depends on no screen parser, so it
survives any parser break.

The wire vocabulary landed in #374 (`RequestSnapshotPayloadDto` / `ScreenSnapshotPayloadDto`). The
server consumer (pyrycode#618, merged) intercepts `request_snapshot` and replies with a
`screen_snapshot` carrying `{conversation_id, text, ts}`, correlated by `Envelope.InReplyTo`. This
slice is the **mobile repository-read half**: it exposes the capability as a `ConversationRepository`
method, fills it on the live remote, gives the fake an observable canned contract, and routes it
through the facade.

**Not gated on the `interactive` capability (#369) — confirmed against the daemon handler.**
pyrycode#618's `handleRequestSnapshot` validates only `KnownConversation` (registry membership) +
a live `Snapshotter`; there is **no** capability-negotiation gate, and ADR 025 § Security model
(line 141) deliberately places read-only screen viewing *outside* the per-device permission gate.
The snapshot is the always-available floor, independent of capability negotiation. No blocker to add.

**Why `security-sensitive`** (where #374 was not): #374 added the wire *vocabulary*; this slice is
where the snapshot `text` — **server-originated network input** — is returned *literally* to
consumers. The no-raw-bytes invariant (enforced server-side by the daemon's tui-driver renderer) and
the no-log-of-snapshot-text discipline are load-bearing here. See § Security review.

## Design

Four edits across the interface and its three implementations. No new file. The new wire DTOs already
exist (#374); this slice only *uses* them.

### 1. Interface method — `ConversationRepository.kt`

Add one `suspend` method with a **throwing default**, mirroring `delete` / `createWorkspaceFolder`:

```kotlin
suspend fun requestScreenSnapshot(conversationId: String): String =
    error("requestScreenSnapshot is not implemented for this ConversationRepository")
```

KDoc contract (the developer writes the prose):
- Returns the **rendered text** of the current claude screen for `conversationId` — verbatim, never
  parsed, trimmed, or sanitized.
- Throws `IllegalArgumentException` for an unknown `conversationId` (mirrors the fake's type and the
  server's `conversation.not_found` path).
- Throws on a server `error` (`RelayErrorException`) or a not-connected session
  (`IllegalStateException`) — the caller handles failure.
- **Default throws** — implementations that don't support snapshots inherit it; the inline test
  doubles in the ViewModel tests therefore need **no** override (the cascade-avoidance the throwing
  default buys, identical to `delete`).

### 2. Remote implementation — `RemoteConversationRepository.kt`

**(a) Route the new reply type.** Add `TYPE_SCREEN_SNAPSHOT` to the existing success branch of the
`onInbound` `when` (line 195) so a correlated `screen_snapshot` resolves its waiter:

```kotlin
TYPE_ACK, TYPE_CONVERSATION_CREATED, TYPE_CONVERSATION_UPDATED, TYPE_SCREEN_SNAPSHOT ->
    envelope.inReplyTo?.let { id -> pendingRequests[id]?.complete(envelope.payload) }
```

`screen_snapshot` is **always** a correlated reply on this wire (pyrycode#618 sets `InReplyTo:
&env.ID`; there is no unsolicited `screen_snapshot` push — eager-push-on-stall is future/out-of-scope
on the server). A `screen_snapshot` whose `inReplyTo` matches no pending entry (or is null) is the
same harmless no-op the branch already tolerates for `conversation_updated`'s unsolicited broadcast.
Update the branch comment to name `screen_snapshot` (#375) as a fourth correlated success reply, so
the "single-row deltas extend this in their own slice" note stays accurate.

**(b) Fill the method.** A one-shot suspend mirroring `sendMessage`'s shape, but it **decodes** its
reply (like `createDiscussion`) rather than reconstructing from input:

```kotlin
override suspend fun requestScreenSnapshot(conversationId: String): String
```

Behaviour (≤ ~12 lines, envelope built inline as `sendMessage` does):
1. Build `Envelope(id = requestId.incrementAndGet(), type = TYPE_REQUEST_SNAPSHOT, ts =
   Clock.System.now().toString(), payload =
   MobileJson.encodeToJsonElement(RequestSnapshotPayloadDto(conversationId)))`.
2. `val reply = sendAndAwaitReply(request)` — throws `IllegalStateException` if the pump is not
   `Open`, rethrows the mapped server `error` (`IllegalArgumentException` for `conversation.not_found`,
   else `RelayErrorException`). No projection is touched (this is a pure read — there is nothing to
   mutate).
3. `return MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(reply).text` — return `text`
   verbatim. **Never read `ts`. Never mutate/sanitize `text`. Never log `text`, the reply, or the
   request.**

No local membership guard for unknown `conversationId`: the server is authoritative (the cached
`projection` can be stale), so an unknown id surfaces through the server's `conversation.not_found`
`error` → `IllegalArgumentException` — observably the same type the fake throws synchronously (the
contract pins the type, not the timing). This matches `sendMessage`'s decision exactly.

**(c) Companion constants:** `TYPE_REQUEST_SNAPSHOT = "request_snapshot"`,
`TYPE_SCREEN_SNAPSHOT = "screen_snapshot"`.

**(d) Imports:** `RequestSnapshotPayloadDto`, `ScreenSnapshotPayloadDto` from
`de.pyryco.mobile.data.network`. (`MobileJson`, `encodeToJsonElement`, `decodeFromJsonElement` are
already imported.)

### 3. Fake implementation — `FakeConversationRepository.kt`

Override `requestScreenSnapshot` to give UI/tests an observable contract: known id → a deterministic
canned screen-text; unknown id → `unknown(conversationId)` (the existing
`IllegalArgumentException("Unknown conversation: $id")` helper). Read the current `state.value`
membership the same way `sendMessage`/`mintNewSession` do (`records[conversationId] ?: throw
unknown(conversationId)`), then return a fixed canned constant (e.g. a `private const val` or
companion string). The canned value should be **non-blank and deterministic**; a multi-line literal
is a nicety (it lets a test assert verbatim round-trip) but not required. The fake performs no
network and no mutation — it's a read.

### 4. Facade delegation — `StableConversationRepository.kt`

Add an **explicit override** that delegates to the live repository (not the interface default — the
facade must reach the live impl, exactly as `delete` (line 88) and `createWorkspaceFolder` (110) do):

```kotlin
override suspend fun requestScreenSnapshot(conversationId: String): String =
    live.requestScreenSnapshot(conversationId)
```

`live` throws `IllegalStateException(NOT_CONNECTED)` when no connection is live (AC #1 reachability;
the same not-connected type the remote throws on a not-`Open` pump), so a caller catches one type
whether the connection was absent at call time or dropped mid-flight. The facade adds no logging.

### Data flow

```
caller (ViewModel scope)
  └─ StableConversationRepository.requestScreenSnapshot(convId)
       └─ live.requestScreenSnapshot(convId)        // RemoteConversationRepository
            ├─ Envelope{request_snapshot, {conversation_id}} ── pump.send ──▶ relay ──▶ daemon
            │       (registered in pendingRequests[id] before send)
            │                                                                    │ #618 renders
            ▼  suspend on CompletableDeferred.await()                            ▼
       (connection-scoped init collector) ◀── noise_msg(screen_snapshot{…, InReplyTo:id}) ◀── relay
            └─ onInbound: TYPE_SCREEN_SNAPSHOT → pendingRequests[id].complete(payload)
                 ▼
       decode ScreenSnapshotPayloadDto(reply).text  ──▶  return String (verbatim)
```

## State + concurrency model

- **No new state, no new collector, no second subscription.** Correlation rides the existing single
  `init` `pump.inbound` collector — the absolute constraint inherited from #346/#313. The new method
  registers a deferred in the shared `pendingRequests` registry (via `sendAndAwaitReply`) and the
  collector resolves it; nothing new is launched or collected.
- **No projection mutation.** Unlike `sendMessage` / `createDiscussion` / `promote`, this is a pure
  read — it returns a value and folds nothing into `projection` / `lastMessages` /
  `messagesByConversation`. The "two writers" KDoc on those fields is unaffected; **do not touch it.**
- **Suspend/await.** The caller suspends on `CompletableDeferred.await()` in its own coroutine
  (a ViewModel `viewModelScope`); the reply is delivered by the connection-scoped collector.
  `complete` is thread-safe and idempotent across the two coroutines. Caller cancellation →
  `CancellationException` → `sendAndAwaitReply`'s `finally` removes the registry entry (no leak),
  identical to every other request.
- **Dispatcher.** None chosen here — in-memory state + a non-blocking `pump.send`; transport
  dispatching lives below the pump.
- **Portability.** `data/` stays portable — no `android.*`, no `kotlinx.datetime` parse added (`text`
  is a `String`, `ts` is never read).

## Error handling

| Failure | Surfaced to caller as | Reuses |
|---|---|---|
| Unknown / foreign `conversationId` (server `error` `conversation.not_found`, retryable=false) | `IllegalArgumentException` | existing `mapError` (AC #3) |
| `server.binary_offline` (snapshotter nil / no live session, retryable=true) | `RelayErrorException(code, retryable, message)` | existing `mapError` (AC #3) |
| Session not `Open` (`pump.send` returns `false`) | `IllegalStateException` | `sendAndAwaitReply`'s `check(...)` (AC #3) |
| Malformed `error` reply (undecodable) | fallback `RelayErrorException` — waiter unblocked, collector survives | existing `mapError` fallback |
| Malformed `screen_snapshot` reply (missing `text` / wrong type) | `kotlinx.serialization.SerializationException` (subtype of `IllegalArgumentException`) thrown **caller-side** from `decodeFromJsonElement` | — |

The malformed-`screen_snapshot` decode runs in the **caller's** coroutine *after* `sendAndAwaitReply`
returns (the collector handed the raw payload to the waiter and moved on), so it never threatens the
single inbound collector — same posture as `createDiscussion`/`promote` decoding their typed replies.
No new error code, no new exception type, no `mapError` change. Nothing is logged on any path.

## Testing strategy

Unit only (`./gradlew test`); no instrumented tests, no `ComposeTestRule`. Run the new Remote class
with `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`
(the bare `test --tests` form is rejected by Gradle in this repo). `./gradlew spotlessCheck` must pass.

**Remote** — extend `RemoteConversationRepositoryTest` with the `runTest` + `runCurrent()` +
`FakeSessionPump` idiom. Add a `startSnapshot(repo, convId): () -> Result<String>` launcher mirroring
`startSend`, and a `screenSnapshotEnvelope(inReplyTo, convId, text, ts)` helper mirroring
`conversationCreatedEnvelope` (build the `{conversation_id, text, ts}` payload via
`MobileJson.parseToJsonElement`). Scenarios (bullets — write in the project idiom):
- **AC #2 request shape + happy path:** call `requestScreenSnapshot("c1")` on `backgroundScope`;
  assert the single sent envelope has `type == "request_snapshot"` and payload
  `{conversation_id:"c1"}`; push a correlated `screen_snapshot` (`inReplyTo = pump.sent.single { it.type
  == "request_snapshot" }.id`) carrying a known `text`; assert the call returns that `text` **verbatim**
  (use a multi-line / whitespace-laden value to prove no trimming).
- **AC #3 server error → RelayErrorException:** push a correlated `error`
  `{code:"server.binary_offline", retryable:true}`; assert `RelayErrorException` exposing that `code`.
- **AC #3 unknown id → IllegalArgumentException:** push a correlated `error`
  `{code:"conversation.not_found"}`; assert `IllegalArgumentException`.
- **AC #3 not-open → IllegalStateException:** set `FakeSessionPump.sendResult = false`; assert
  `IllegalStateException`; assert no `request_snapshot` round-trip resolved.
- **Correlation hygiene:** a `screen_snapshot` with an `inReplyTo` matching no pending request is a
  no-op (collector survives — prove with a subsequent valid snapshot round-trip).
- *(recommended)* **Malformed `screen_snapshot`:** push a correlated reply missing `text`; assert the
  call throws `SerializationException` / `IllegalArgumentException` and the collector survives a
  following valid round-trip.

**Fake** — extend `FakeConversationRepositoryTest`: known seeded id returns the non-blank canned text
(assert verbatim if a multi-line constant is used); unknown id throws `IllegalArgumentException`
(mirror `sendMessage_onUnknownId_throws`'s `try/catch`).

**Stable** — extend `StableConversationRepositoryTest`: add `requestScreenSnapshotCalls` /
`requestScreenSnapshotResult` to `RecordingConversationRepository`, then assert (a) when live, the
facade forwards `conversationId` verbatim and returns the live result (extend
`oneShots_whenLive_delegateWithExactArgsAndReturn` or add a sibling), and (b) while absent it throws
`IllegalStateException` (extend `oneShots_whileAbsent_throwIllegalState`).

## Open questions

- **No reply-payload `conversation_id` cross-check.** Correlation is purely envelope-level
  (`inReplyTo`), matching the #346 pattern; the consumer returns `reply.text` without asserting
  `reply.conversationId == conversationId`. pyrycode#618 always echoes the requested id, and the
  request-id space is client-minted/monotonic against the paired server, so a cross-check would guard
  an unobserved failure mode. Flagged, not added (Evidence-Based Fix Selection). If a future
  multi-conversation phase warrants it, the decoded `conversationId` is available to compare.
- **Display-time handling is out of scope.** This slice returns the `text` as a `String`. Rendering
  it (and any `FLAG_SECURE` / control-sequence / screenshot-leakage concern) is the UI slice's
  responsibility (#372, the manual-UI half), not this data-layer read. Named here so code-review
  doesn't expect a display guard in this slice. (Compose `Text` does not interpret terminal control
  codes as a TTY would, so modeling `text` as a verbatim `String` introduces no terminal-injection
  surface at this layer.)
- **Connection-drop-mid-request leak.** Identical to #346's flagged case: if the connection scope is
  cancelled while a caller awaits, the deferred only completes when the *caller* is cancelled. No
  timeout added (no observed hang; a timeout value is a product call). Inherited from the shared
  primitive, not introduced here.

## Security review

> The canonical `security-review.md` referenced by the architect prompt is not symlinked into this
> worktree (as #346/#374 also noted). The adversarial pass below walks the standard categories with a
> default-FAIL posture; each applicable category produced a concrete finding or a stated reason it
> does not apply. **Verdict: PASS.**

**Findings:**

- **[Trust boundaries]** No MUST FIX (key control, specified + tested). The untrusted input is the
  inbound `screen_snapshot` payload, the **single** untrusted→typed boundary, decoded through
  `MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>` at one consumer site over a peer that
  is **already cryptographically authenticated** — `screen_snapshot` arrives inside `noise_msg` on the
  Noise_IK channel (`NoiseSessionPump` / `OkHttpRelayTransport`), so a relay or MITM can neither forge
  nor read it. Strict required fields (`conversation_id`, `text`, `ts`, all non-null) fail a malformed
  frame **closed** with `SerializationException` (caller-side, never partial/`null`-punned, never
  hanging the collector). The `text` is returned **verbatim** — no mutation/strip/sanitize — because
  decode fidelity is the entire purpose of the parser-independent floor; the no-raw-bytes guarantee
  (ADR 025) is enforced server-side by the daemon's tui-driver renderer, the trusted authenticated
  peer. There is no `eval` / shell / SQL / path / URL construction from payload data → no injection
  surface introduced at this layer.
- **[Error messages / logs / telemetry]** No MUST FIX (the load-bearing control — specified + tested).
  The snapshot `text` is potentially-sensitive rendered screen content and is **NEVER logged** —
  `requestScreenSnapshot` adds **zero** `Log.*` calls; it logs neither the request envelope, the
  `conversationId`, the reply, nor the returned `text`, preserving the existing collector posture
  ("message content may be sensitive"). `mapError` (reused unchanged) does not log the payload, and a
  `RelayErrorException` carries only the server's human-facing `error.message` (server-authored
  control text), never the snapshot `text`. **Developer constraint (MUST):** do not add any
  `Log.*`/`println` of the request, envelope, reply, `conversationId`, or `text`. **Code-review:** grep
  the new method + the touched `when` branch for any log field carrying `text` or the payload. A
  decode failure surfaces kotlinx-serialization's own exception, which names the *absent/mismatched
  schema key* (e.g. `text`), not the field *value*.
- **[Correlation integrity]** No code change warranted (documented boundary, no observed attack).
  Request ids are client-minted and monotonic (`AtomicLong`) within a session; the server only echoes
  them as `inReplyTo`. A buggy/duplicate `inReplyTo` resolves the matching pending request at most once
  (`CompletableDeferred.complete` is idempotent). The consumer does not cross-check the reply payload's
  `conversation_id` against the request — correlation is envelope-level only, bounded to the session's
  own id space against the paired server (the same trust model #346 documented). Recorded in § Open
  questions as the boundary, not fixed.
- **[Tokens / secrets]** No finding. The method touches no token or key. `conversation_id` is an opaque
  routing id (the phone already holds it from `message` envelopes); `text` is message-class *content*,
  not a credential. No `toString`-redaction added, keeping parity with the sibling content-bearing DTOs
  — and consistent with #374, which deliberately omitted redaction here.
- **[Resource / DoS]** No finding. `pendingRequests` grows by one per in-flight request and is removed
  in `finally` (success, error, cancellation) — bounded by caller concurrency, no unbounded growth.
  Inbound `text` size is bounded upstream by the transport's frame cap (`OkHttpRelayTransport` / Noise
  pump), not by this slice, which decodes only what the transport already admitted. The
  connection-drop single-entry leak is inherited from the shared primitive (flagged in § Open
  questions), not introduced here.
- **[Input validation]** No finding. The only caller-supplied input is `conversationId`, never used
  to build a path/URL/query — it is encoded into one JSON field and validated **server-side** (unknown
  → `conversation.not_found` → `IllegalArgumentException`). No client-side membership guard is added
  (server is authoritative), matching `sendMessage`.
- **[File / storage]** N/A — no file I/O, no persistence, no path handling; `conversation_id` is never
  a filesystem path. `data/` portability preserved (no `android.*`).
- **[Android attack surface]** N/A — no Activity/Service/Receiver/Intent/deep-link/PendingIntent/
  ContentProvider/WebView. Pure data-layer suspend method.
- **[Cryptographic primitives]** N/A — no RNG, hashing, or key handling. The authenticated/encrypted
  channel is the pre-existing Noise_IK transport, unchanged.
- **[Concurrency]** No finding. No new collector, scope, or shared mutable state; the shared
  `pendingRequests`/`CompletableDeferred` machinery is reused unchanged and is thread-safe/idempotent
  across the collector and caller coroutines.
- **[Threat model alignment]** A hostile relay/MITM cannot forge or read a `screen_snapshot` (Noise_IK
  seal); a malformed authenticated frame fails closed at decode. **OUT OF SCOPE, named:** display-time
  handling of the returned snapshot text (screenshot leakage / `FLAG_SECURE`, accessibility
  eavesdropping, terminal-control rendering) is the render-layer concern owned by the UI slice (#372)
  and the thread UI, not this wire/data-layer read — recorded in § Open questions so the future
  implementer does not mistake "returns a verbatim String" for "safe to render unguarded".

No FAIL findings; no spec revision required. Proceeding to commit.

**Reviewer:** architect (self-review; canonical `security-review.md` not symlinked into the worktree)
**Date:** 2026-06-08
