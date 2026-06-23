# #458 — Send the `interrupt` control frame (outbound send path)

> Spec for `feat(net): send the interrupt control frame`. Split from #430; sibling #459
> (`feat(ui): interrupt affordance on the busy turn`, `blockedBy #458`) owns the visible control
> and its busy-state gating. **This slice is the send path only** — repository → coordinator → DI →
> ViewModel action. No UI.

## Files to read first

Read these before writing code; line ranges are current on post-#461 `main`.

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1192-1212` —
  `cancelModal`: the inline-`Envelope` outbound-control-send pattern to mirror. **Difference for
  interrupt:** `cancelModal` uses `sendAndAwaitReply` (modal_cancel gets a correlated `ack`);
  interrupt is fire-and-forget and uses plain `pump.send` (see next two entries).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:600-617` —
  `sendAndAwaitReply`. Line 612 `check(pump.send(request)) { "<type> not sent: session not connected" }`
  is the not-connected→`IllegalStateException` idiom interrupt reuses **without** the `deferred.await()`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:807-837` —
  `observeConversations` + `listConversationsRequest()` (:831-837): the fire-and-forget `pump.send(...)`
  precedent and the **empty-payload** `payload = JsonObject(emptyMap())` shape interrupt copies verbatim.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1259-1384` —
  the `companion object` `TYPE_*` const block; add `TYPE_INTERRUPT = "interrupt"` here (group near the
  modal control types, e.g. after `TYPE_MODAL_CANCEL`:1370).
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:301-308` —
  `cancelModal` passthrough (null-guard → throw `IllegalStateException`); `:148` is the
  `activeRemoteRepo: MutableStateFlow<RemoteConversationRepository?>` declaration. Mirror exactly.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:109-126` — ctor params;
  add the `interrupt` lambda **after** `cancelModal`:125.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:419-467` —
  `onModalCancel` + `sendCancel`: the exact structural mirror (CancellationException-first rethrow, then
  typed catches). **Difference for interrupt:** the catch bodies are EMPTY (no error channel, no log).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:345-354` —
  `modalSendErrorChannel` / `modalSendErrors`: the surface interrupt must **not** add. Interrupt is inert.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:115-128` — the `viewModel { ThreadViewModel(...) }`
  registration; add `interrupt = coordinator::interrupt,` alongside `answerModal`/`cancelModal`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1838-1900` —
  `makeVm` helper (:1840, add the param + positional pass at :1849), `vmWithModalSendPath` (:1869), and
  `ModalSendRecorder` (:1883, the optionally-throwing recorder). Mirror these for interrupt.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1479-1545` —
  `cancelModal_sendsModalCancelMatchingWireContract` / `cancelModal_whenSendReturnsFalse_throwsIllegalState…`:
  mirror for the repo-level interrupt tests (wire-contract assert + not-connected→ISE-no-hang).
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:680-735` —
  `cancelModal_withActiveConnection_delegatesAndCompletesOnAck` /
  `cancelModal_withNoActiveConnection_throwsIllegalState`: mirror for the coordinator-level interrupt tests.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt:68-75` —
  the androidTest VM construction. Uses **named args + relies on defaults** → compiles unchanged when the
  new `interrupt` param is defaulted. **No edit needed** (informational — confirm, don't touch).
- pyrycode repo `docs/protocol-mobile.md` — wire SSOT (NOT in this repo). §"Interrupt (v2)" (~lines
  690-700) and the Application-message-types table row for `interrupt` (~line 441). Already summarized
  in **Wire contract** below; read only if you need the source text.

## Context

Phase 3 (epic pyrycode#597) lets a paired phone interrupt a running turn — the remote equivalent of
pressing **Esc**. The server side shipped in pyrycode#707: the daemon maps an inbound `interrupt`
control frame to the neutral `turnevent.Cancel` and routes it to the supervised claude as a single Esc
keystroke. This ticket builds the phone's **outbound send path** for that frame. The visible busy-state
affordance that triggers it is sibling #459.

## Wire contract (SSOT: pyrycode `docs/protocol-mobile.md` §Interrupt v2, pyrycode#707)

- `interrupt` — phone → binary (encrypted relay frame), **no reply / no ack / no broadcast**.
- **Bare frame.** `{type: "interrupt"}` with **no payload**: no `conversation_id`, no `interrupt_id`,
  no nonce, no `answer_token`, no idempotency key. Do **not** invent a payload DTO.
- **Replay-safe.** A replayed `interrupt` sends another Esc; an Esc with no running turn is a no-op in
  claude. Claude serialises turns ⇒ at most one running turn ⇒ a bare connection-level frame is
  unambiguous. Not part of the reconnect-replay ring; needs no correlation key.
- **Interactive-capability-gated server-side.** A non-interactive connection's `interrupt` is inert
  (the daemon drops it). The phone already advertises `interactive` in its `hello` (#401).
- **Permission-gate-exempt** — interrupting one's own paired session is a normal paired action.

## Design

A vertical send path mirroring the `modal_cancel` outbound slice (#451) one-for-one, with two
deliberate departures driven by the fire-and-forget wire contract.

### Layer 1 — `RemoteConversationRepository` (connection-scoped concrete repo)

Add a `suspend fun interrupt()` — **no `conversationId` argument** (the frame is connection-level: "the
one running turn"). Contract:

- Builds a bare `Envelope`: `id = requestId.incrementAndGet()`, `type = TYPE_INTERRUPT`,
  `ts = Clock.System.now().toString()`, `payload = JsonObject(emptyMap())` (the `listConversationsRequest()`
  empty-payload precedent; `JsonObject` is already imported). A small private `interruptRequest(): Envelope`
  builder mirroring `listConversationsRequest():831` is preferred over an inline literal.
- Sends fire-and-forget via **`check(pump.send(interruptRequest())) { "$TYPE_INTERRUPT not sent: session not connected" }`**
  — plain `pump.send` (Boolean), **NOT** `sendAndAwaitReply`. The server sends no reply, so awaiting one
  would hang. The `check` throws `IllegalStateException` when the pump is not `Open` (send returns false),
  mirroring `sendAndAwaitReply`'s line-612 not-connected behavior so the not-connected path stays a typed
  throw the ViewModel can swallow (AC #3).
- Add `const val TYPE_INTERRUPT = "interrupt"` to the companion `TYPE_*` block.

Invariant asserted by test: the emitted frame's `type == "interrupt"` and its `payload` is the empty
object `{}` (no extra keys) — see Testing strategy.

### Layer 2 — `RelayRepositoryCoordinator` (passthrough)

Add `suspend fun interrupt()` — the exact `cancelModal`:305 mirror:

```
suspend fun interrupt()  // val repo = activeRemoteRepo.value ?: throw IllegalStateException("no active connection"); repo.interrupt()
```

Null-guard only (throws `IllegalStateException` when no connection is active, between connections). No
`Open` gate needed — a connected-but-pre-`Open` pump surfaces as the repo's `check`→ISE. Never logs.

### Layer 3 — DI (`AppModule`)

In the `viewModel { ThreadViewModel(...) }` block, bind the new lambda alongside the modal ones:
`interrupt = coordinator::interrupt,`. One line.

### Layer 4 — `ThreadViewModel`

- New defaulted ctor param **after `cancelModal`:125**:
  `private val interrupt: suspend () -> Unit = {}` (no-op default keeps the fake-backed Koin graph and
  every existing test inert; the VM holds only the suspend lambda, never the coordinator/concrete repo —
  same posture as `answerModal`/`cancelModal`).
- Public `fun onInterrupt()` — the action #459 will call. Launches the private sender; takes no args
  (connection-level). Unlike `onModalCancel` it has no "no modal open" guard — there is no per-VM state
  to gate on; it always attempts the send (minimal client, server is authoritative on whether a turn is
  running). It does **not** read or require any conversation/busy state — #459 owns show/hide gating.
- Private `fun sendInterrupt()` — the `sendCancel`:455 mirror, with **empty catch bodies**:
  - `viewModelScope.launch { try { interrupt() } catch (e: CancellationException) { throw e } catch (e: RelayErrorException) { /* inert */ } catch (e: IllegalStateException) { /* inert */ } }`
  - `CancellationException` rethrow **MUST precede** the typed catches (`j.u.c.CancellationException
    extends IllegalStateException` on the JVM — see [[catch-illegalstate-swallows-cancellation]]).
  - **No `modalSendErrorChannel.trySend`, no log** — AC #3 "nothing logged". Any user-visible surface is
    #459's concern, not this slice's.

#### Why retain the `RelayErrorException` catch (it is unreachable on the real path)

On the real fire-and-forget path interrupt uses plain `pump.send` and never awaits a reply, so
`RelayErrorException` (which only originates from a correlated server `error` inside `sendAndAwaitReply`)
**cannot** occur. The catch is retained deliberately for two reasons: (1) AC #4 mandates a test that the
relay-error path is swallowed — exercised via the injected test double throwing `RelayErrorException`;
(2) it preserves the exact structural twin of `sendCancel`, so the two outbound senders read identically
and a future server that ever replied to `interrupt` with an `error` would already be handled. **Document
this in the method KDoc** so code-review does not flag it as dead code.

### Decisions (resolved — do not re-litigate)

1. **Always send; no client-side `interactive` suppression.** The gate is server-authoritative and
   fail-closed (a non-interactive connection's interrupt is dropped daemon-side). A bare Esc-only frame
   argues for the minimal client per the ticket. The phone does not check its negotiated capability set
   before sending.
2. **No `conversationId` anywhere** in the send path — repo method, coordinator method, and VM action all
   take none. The action lives on the per-conversation `ThreadViewModel` (that is where the busy turn is
   visible to #459) but the frame stays connection-level.

## State + concurrency model

- No new `StateFlow`, no new `UiState` field, no new `Event` type. (#459 adds the busy `StateFlow<Boolean>`
  and the affordance; this slice adds only the action method + injected lambda.)
- `sendInterrupt` launches on `viewModelScope` (Main-immediate by VM convention) and suspends only across
  the injected lambda → coordinator → `pump.send`. On screen exit `viewModelScope` cancellation propagates
  through the lambda; the `CancellationException`-first catch rethrows so structured cancellation is never
  swallowed (AC #3).
- The repo's `pump.send` is the existing connection-scoped send; no new transport, session, or dispatcher
  machinery is introduced (AC #2).

## Error handling

| Failure | Where it surfaces | Result |
|---|---|---|
| No active connection (coordinator `activeRemoteRepo == null`) | coordinator `interrupt()` | throws `IllegalStateException` → VM `catch (IllegalStateException)` → **inert** |
| Connected but pump pre-`Open` (`pump.send` returns false) | repo `check(pump.send(...))` | throws `IllegalStateException` → VM → **inert** |
| Relay/server `error` | not reachable on real path (no reply awaited) | catch retained for AC #4 test + parity → **inert** |
| `viewModelScope` cancelled mid-send | `CancellationException` | rethrown **before** typed catches → propagates (structured cancellation preserved) |

No banner, no dialog, no log, no error channel. The send fails silently inert. #459 may later choose to
surface state, but not via this slice.

## Testing strategy

Unit only (`./gradlew test`); no instrumented test in this slice. Test-first (red → green). Bullet
scenarios — the developer writes them in the project idiom mirroring the cited `cancelModal` tests.

**`ThreadViewModelTest` (AC #4 — the mandated coverage):**
- Extend `makeVm` with `interrupt: suspend () -> Unit = {}` and pass it positionally at the construction
  line (:1849). Add an interrupt recorder (mirror `ModalSendRecorder`: records call count, optionally
  throws a supplied `Throwable` after recording) and a `vmWith…` helper if convenient.
- Happy path: `onInterrupt()` → the injected `interrupt` lambda is invoked **exactly once**.
- Not-connected path: injected lambda throws `IllegalStateException` → `onInterrupt()` completes, no
  crash, no error emitted, no log.
- Relay-error path: injected lambda throws `RelayErrorException` → swallowed, no crash.
- Cancellation path (mirror #451): a `CancellationException` thrown from within the send propagates / is
  rethrown (assert it is **not** swallowed by the typed catches).

**`RemoteConversationRepositoryTest` (mirror `cancelModal_*`):**
- `interrupt()` against an `Open` pump emits exactly one frame whose `type == "interrupt"` and whose
  `payload` is the empty object `{}` (no `conversation_id`/other keys) — the wire-contract assert.
- `interrupt()` when `pump.send` returns false throws `IllegalStateException` and does not hang (no
  awaited reply).

**`RelayRepositoryCoordinatorTest` (mirror `cancelModal_with{Active,No}…`):**
- With an active connection, `interrupt()` delegates → one `interrupt` frame is sent.
- With no active connection, `interrupt()` throws `IllegalStateException`.

## Scope guardrails

- 4 production files modified (`RemoteConversationRepository`, `RelayRepositoryCoordinator`,
  `ThreadViewModel`, `AppModule`), 0 created. 0 new exported types (`TYPE_INTERRUPT` private const,
  `interruptRequest()` private helper, `interrupt` ctor lambda, `onInterrupt()`/`sendInterrupt()` methods).
- Edit fan-out: 2 call sites flip (`AppModule` registration, `makeVm` helper). `ScriptedThreadHarness`
  compiles unchanged (named-arg construction + new defaulted param).
- Do **not** add a busy `StateFlow`, an affordance, or a screen test — those are #459.
- Do **not** add a `conversationId` argument, a payload DTO, or `sendAndAwaitReply` to interrupt.

## Open questions

None. The wire shape (pyrycode#707, CLOSED), the fire-and-forget send choice, the two decisions above,
and the inert-swallow error posture are all resolved. The only judgment left to the developer is the
test idiom, which mirrors the cited `cancelModal` tests directly.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings — the slice is purely *outbound* and parses no untrusted inbound
  data (no decode, no projection mutation). `interrupt()` / `onInterrupt()` take **no arguments** and the
  frame's `payload` is a compile-time-constant `JsonObject(emptyMap())`, so there is no injection surface
  and no untrusted→trusted crossing. Positive property: `pump.send` only transmits over an `Open`
  (post-Noise_IK-handshake, authenticated) session — a pre-`Open`/null pump throws `IllegalStateException`
  (coordinator null-guard + repo `check`) → inert, so an interrupt **cannot** ride an unauthenticated
  channel. The "any interactive paired device may interrupt the single live claude" property is the
  existing trust-domain model of pyrycode#707, not introduced here.
- **[Tokens, secrets, credentials]** N/A — none generated, stored, or compared. The Envelope `id`
  (`requestId.incrementAndGet()`) is a non-security monotonic counter the server ignores (interrupt is not
  correlated). The deliberate absence of a nonce/idempotency token is the protocol's replay-safe design
  (a replayed Esc is a no-op), not an omission.
- **[File / storage]** N/A — no file I/O, no persistence, no path handling.
- **[Inter-process / Android attack surface]** N/A — no new `Activity`/`Service`/`BroadcastReceiver`,
  `intent-filter`, deep link, `PendingIntent`, `ContentProvider`, or `WebView`. `onInterrupt()` is reachable
  only from sibling #459's in-app composable.
- **[Cryptographic primitives]** N/A — none introduced; the send rides the existing Noise_IK encrypted
  transport (`pump.send` encrypts). No RNG, hashing, or key handling in this slice.
- **[Network & I/O]** No findings — no new `OkHttpClient`/timeout/TLS/frame-size config; inherits the
  established transport. Repeated taps emit a few tiny fire-and-forget frames the daemon treats as no-ops —
  not amplification, no auth-retry loop (no tokens to exhaust). Any debounce is a #459 UX concern, not a
  security gate.
- **[Error messages, logs, telemetry]** No findings — AC #3's inert-swallow (empty catch bodies, **no log**,
  no error channel) satisfies the never-log contract; nothing sensitive is in scope to leak. No telemetry or
  crash-reporter surface added.
- **[Concurrency]** No findings — `sendInterrupt` is `viewModelScope`-owned (cancels on screen exit, no
  application-scope leak); the **`CancellationException`-first rethrow ordered before the typed catches** is
  the one real hazard and is addressed by design ([[catch-illegalstate-swallows-cancellation]]:
  `java.util.concurrent.CancellationException` extends `IllegalStateException` on the JVM). No shared-state
  mutation, no mutex, no hot/cold-flow change. Kill-mid-send loses one fire-and-forget frame with no partial
  state to recover (replay-safe).
- **[Threat model alignment]** No findings — faithfully implements pyrycode#707 §Security model:
  permission-gate-exempt (interrupting one's own paired session is a normal paired action), connection-level
  with no per-conversation binding. OUT OF SCOPE → #459: an overlay/accessibility-service tap on the
  affordance could trigger an interrupt, but severity is negligible (an attacker who can already drive the UI
  makes claude stop — a replay-safe no-op with no data exfiltration or privilege gain).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-23
