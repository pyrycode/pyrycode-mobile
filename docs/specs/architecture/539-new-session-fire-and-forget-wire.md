# #539 — Send the bare `new_session` fire-and-forget v2 frame from `RemoteConversationRepository.startNewSession`

**Size:** XS · **Labels:** `enhancement`, `security-sensitive` · Split from #534.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1324-1348` — **the template.** `interrupt()` (the bare fire-and-forget frame + `check(pump.send(...)){ ISE }` idiom) and its private `interruptRequest()` builder. This slice is a line-for-line mirror one verb over.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1422-1425` — the current `startNewSession` throwing body to replace.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1559-1563` — `TYPE_INTERRUPT` companion const; add `TYPE_NEW_SESSION` beside it.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1400-1420` — `rename()` (#530) as a **contrast** reference: it *does* `sendAndAwaitReply` + decode + upsert. `new_session` must do **none** of that. Read it to know what NOT to copy.
- `app/src/main/java/de/pyryco/mobile/data/model/Session.kt:1-11` — the `Session` data class (5 fields) the interface forces you to return; drives the placeholder-return decision below.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:105` — the interface signature `startNewSession(conversationId, workspace?): Session`. Do **not** change it (ripples to Fake + Stable + interface — out of scope, see § Return-type reconciliation).
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1778-1806` — the two `interrupt()` repository tests (`interrupt_sendsBareInterruptMatchingWireContract`, `interrupt_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang`) + the `FakeSessionPump` (`sent`, `sendResult`) test seam. The new tests are a direct mirror.

## Context

`RemoteConversationRepository.startNewSession` currently throws `UnsupportedOperationException` — the "New session" (`/clear` equivalent) affordance has no wire. The daemon's message shipped in pyrycode/pyrycode#831 (PR #836).

The wire contract is **`interrupt`-shaped, not `promote`-shaped** (the #534 body's "daemon acks" premise was wrong — corrected at split): `new_session` is a **bare, fire-and-forget v2 control frame**. No payload (no `conversation_id`, no `workspace` — the daemon operates connection-level on the single live claude; per-conversation scoping is a future *server* ticket). No ack, no reply, no broadcast owed. The client learns a new session started via the pre-existing `session_transition` marker (`reason: "clear"`) that the #336 fold already renders as a thread delimiter — **that observe path is out of scope here; this slice only sends.** Authorization is server-side (the daemon enforces `interactive`), so the phone always sends — exactly as `interrupt()` does.

This is a line-for-line mirror of `interrupt()` (`RemoteConversationRepository.kt:1337`), sitting one verb over.

## Design

Three edits to `RemoteConversationRepository.kt`, all mirroring the `interrupt()` triplet:

**1. Companion const** (beside `TYPE_INTERRUPT`, ~line 1563):

```kotlin
/** Outbound control: the phone's bare `new_session` (#539, pyrycode#831) — the wire half of
 *  "New session" (/clear). No payload, no reply, interactive-gated server-side; replay-safe. */
const val TYPE_NEW_SESSION = "new_session"
```

**2. Private bare-frame builder** (beside `interruptRequest()`, ~line 1342) — signature + behaviour, not a body to expand:

- `private fun newSessionFrame(): Envelope` — returns an `Envelope(id = requestId.incrementAndGet(), type = TYPE_NEW_SESSION, ts = Clock.System.now().toString(), payload = JsonObject(emptyMap()))`. Identical in shape to `interruptRequest()`; only `type` differs. No payload DTO — there is nothing to encode. The `id` is populated for a well-formed envelope but is never used for correlation (fire-and-forget).

**3. Replace the `startNewSession` throwing body** with the `interrupt()`-shaped send-then-return:

- `check(pump.send(newSessionFrame())) { "$TYPE_NEW_SESSION not sent: session not connected" }` — the fire-and-forget send. Uses `SessionPump.send` (non-suspend, returns `false` when not Open), **never** `sendAndAwaitReply` (which would hang awaiting a reply the daemon never sends — AC #3).
- Then `return` a placeholder `Session` (see § Return-type reconciliation). Does **not** touch `projection` (contrast `rename`, which upserts — `new_session` mutates no local state; the real session identity arrives later via the out-of-scope `session_transition` fold).

### Return-type reconciliation (architect's call)

The interface is `startNewSession(conversationId, workspace?): Session`, but a fire-and-forget frame yields no `Session` from any reply, and the sole consumer (the #540 UI wire, `ThreadEvent.NewSession -> Unit`) **discards the return value**. Two options:

- **(A) Local placeholder `Session`** — low ripple, chosen.
- (B) Change the interface signature to `Unit` — ripples to `FakeConversationRepository`, `StableConversationRepository`, the interface, and the `ThreadViewModelTest` fakes. Out of scope for XS, and the ticket calls it out as such.

**Decision: (A).** Return a locally-constructed placeholder immediately after the send. The remote impl genuinely has no session identity at send-time (it arrives later via `session_transition`), so the placeholder's identity fields are **explicitly unknown**, not fabricated-to-look-real:

```kotlin
// Placeholder — the fire-and-forget frame yields no Session; the real identity
// arrives later via the out-of-scope session_transition marker (#336 fold).
// Never persisted, never enters `projection`; the #540 consumer discards it.
Session(id = "", conversationId = conversationId, claudeSessionUuid = "",
        startedAt = Clock.System.now(), endedAt = null)
```

Rationale for empty-string identity fields over a minted UUID: an empty string is a self-documenting "not-yet-assigned" sentinel; a fabricated UUID *looks* like a real session id and would be a latent trap if a future consumer ever stopped discarding the value. `conversationId` is populated from the arg (the honest "which conversation the user pressed New-session on"); `startedAt` is the send moment. The `conversationId` / `workspace` args are **not** sent on the wire (the frame is bare) — they are vestigial for the remote impl (meaningful only to the fake, which mints per-conversation).

### `mutationsSupported` stays `false`

Do **not** flip it. Its siblings `archive` / `unarchive` / `changeWorkspace` still throw (only `rename` was wired, by #530). The single coarse #507/#508 flag stays `false` until all mutations land — flipping it here would un-hide the still-throwing siblings in the overflow menu (`ThreadOverflowMenu.kt:49` gates on it). Menu reachability is the #540 UI-wire child's / a later coarse-flag milestone's concern, not this slice.

## State + concurrency model

None added. No new `viewModelScope` job, no new `StateFlow`, no coroutine launch. `startNewSession` is already a `suspend fun` (interface); `pump.send` is non-suspend and returns synchronously. The method sends one frame and returns a value — no awaited reply, no timer, no watchdog, no cancellation window to manage. Fire-and-forget is by construction free of the concurrency hazards the reply-awaiting mutations carry.

## Error handling

Exactly one client-observable failure, mirroring `interrupt()`:

- **Not connected** (`pump.send` returns `false`) → `IllegalStateException` via `check(...)` with message `"new_session not sent: session not connected"`. Thrown **before** the placeholder return, so the caller (#540) can surface it (per the split plan: a snackbar, **not** `launchGuardedRepoCall` which swallows silently — that's the #540 child's concern). AC #2.
- **No server-error path.** The daemon sends nothing — no ack, no error frame — so there is no `RelayErrorException` / `conversation.not_found` path here (contrast `rename`). Nothing to decode means nothing to fail on.
- The frame carries no `conversation_id` and no idempotency token; a replayed `new_session` with no running turn is a daemon-side best-effort no-op (replay-safe, as documented for `interrupt`).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.repository.RemoteConversationRepositoryTest"`). Two tests mirroring the `interrupt()` pair, added beside them in `RemoteConversationRepositoryTest.kt`, using the existing `FakeSessionPump`:

- **`startNewSession_sendsBareNewSessionMatchingWireContract`** — connected path. Call `repo.startNewSession("c-1", null)`; assert `pump.sent.single { it.type == "new_session" }` exists and its `payload` equals `MobileJson.parseToJsonElement("{}")` (empty object, no `conversation_id` / no other keys). Optionally assert the returned `Session.conversationId == "c-1"` to pin the placeholder shape. AC #1.
- **`startNewSession_whenSendReturnsFalse_throwsIllegalStateAndDoesNotHang`** — not-connected path. Set `pump.sendResult = false`; assert `runCatching { repo.startNewSession("c-1", null) }.exceptionOrNull() is IllegalStateException`; the test completing (not hanging) is itself the "never awaits a reply" assertion. AC #2, #3.

No instrumented test (no UI, no device dependency). No `advanceUntilIdle`/timer concerns — the send is synchronous.

## Open questions

None. The wire contract (bare, no-payload, no-reply) is settled by pyrycode#831; the return-type reconciliation is resolved above; `mutationsSupported` stays `false` per the split plan.

## Security review

**Verdict:** PASS

**Findings:**

- **[1. Trust boundaries]** No findings. This slice only **sends** a frame containing no caller-derived data — the payload is a constant empty `{}` object. No untrusted→trusted crossing is introduced. The complementary inbound `session_transition` boundary (which *is* server-controlled) is out of scope and already lives in the #336 fold; this ticket adds no new inbound parsing.
- **[2. Tokens, secrets, credentials]** No findings. No token minted, stored, compared, or logged. The `answerToken` idempotency machinery (used by `modal_answer`) is deliberately **not** replicated — `new_session` carries no idempotency token by wire design.
- **[3. File / storage operations]** N/A. No filesystem access, no path construction, no persistence — the method neither reads nor writes disk, and does not even touch the in-memory `projection` `StateFlow`.
- **[4. Inter-process / Android attack surface]** N/A. No Intent, deep link, PendingIntent, ContentProvider, or WebView. Pure data-layer method.
- **[5. Cryptographic primitives]** N/A. No RNG, no hashing, no key handling, no comparison of attacker-controlled values. `Clock.System.now()` (placeholder `startedAt`) and `requestId.incrementAndGet()` (envelope id) are non-security monotonic/time values, not randomness.
- **[6. Network & I/O]** No findings. Sends over the **existing** `SessionPump` on the already-established Noise_IK WebSocket — introduces no new socket, no timeout config, no TLS/frame-size decision (all inherited from the live transport). Uses the non-suspend `pump.send`, so it cannot hang on a slow/hostile relay (unlike `sendAndAwaitReply`, which is explicitly avoided — AC #3).
- **[7. Error messages, logs, telemetry]** No findings. The single `IllegalStateException` message is a static string (`"new_session not sent: session not connected"`) — no `conversation_id`, no payload, no token, no header, no message body. Nothing is logged. The never-log contract holds by construction (the frame carries no user data to leak). The placeholder `Session` uses empty-string identity fields, so even if it were ever logged downstream it discloses nothing.
- **[8. Concurrency]** No findings. No coroutine launched, no scope owned, no `StateFlow` mutated (no check-then-mutate TOCTOU — `projection` is untouched), no mutex, no `NonCancellable`. Fire-and-forget with a synchronous `pump.send` has no cancellation window and no shutdown-mid-write hazard beyond what the shared transport already handles.
- **[9. Threat model alignment]** No findings. Authorization for `new_session` is **server-side** (the daemon enforces the `interactive` capability and routes to `supervisor.StartNewSession()` best-effort) — mirrors `interrupt()`; the phone is not the enforcement point, by design (pyrycode#831). A malicious/confused caller can at worst trigger a `/clear` on the single live claude it is already authorized to drive — no privilege escalation, no cross-conversation reach (the frame is connection-level and bare). No mobile-specific threat (screenshot leak, overlay, deep link) applies to a payload-free outbound control frame.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-09
