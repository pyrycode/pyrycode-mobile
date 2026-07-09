# Spec #540 — Route "New session" to the repository and surface a not-connected failure

**Ticket:** pyrycode-mobile #540 · size **S** · `security-sensitive`
**Split from #534.** Data child #539 (PR #542) shipped `ConversationRepository.startNewSession` on `main`; this is the UI child. E2e sibling #541 is filed separately (Inbox, gated).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:357-536` — the load-bearing model. `modalSendErrorChannel` + `modalSendErrors` (357-366) is the one-shot error-surface idiom to clone; `sendAnswer` (470-486) is the **surface-on-failure** catch pattern; `sendInterrupt` (524-536) and `onDropQueued` (551-563) are the fire-and-forget send twins. Line 621 `ThreadEvent.NewSession -> Unit` is the no-op to replace.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:114-133` — the `modalSendErrors` param + `LaunchedEffect` snackbar collector to mirror. Note `stringResource` is resolved *outside* the `LaunchedEffect` (127) then captured — do the same.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:371-395` — the `ThreadScreen(...)` call site; `modalSendErrors = vm.modalSendErrors` (383) is the one new-wire line to mirror. `onOverflowEvent = vm::onOverflowEvent` (387) already carries `NewSession` — **no new event wiring needed.**
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:105-108` — `startNewSession(conversationId, workspace: String? = null): Session` contract; call with `workspace` defaulted, discard the return.
- `app/src/main/res/values/strings.xml:97` — `modal_send_failed` string; add a sibling `new_session_failed`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:638-696` — the three interrupt tests (`onInterrupt_sendsInterruptExactlyOnce`, `..._whenSendFailsInert_isSwallowedWithoutCrashing`, `..._scopeCancellationMidSend...`) are the exact test shapes to adapt. The uncaught-handler capture (668-695) is required by AC #5. **Difference:** new-session *surfaces* (assert `newSessionErrors` emits), whereas interrupt swallows.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:840-920` — the throwing-repository test shape (archive/delete). `startNewSession` is a repository method, not an injected lambda, so the failure test drives a fake repo whose `startNewSession` throws — this is the model, not the `InterruptRecorder` lambda double.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:49-56` — **read-only for this ticket.** The `if (mutationsSupported)` gate that hides "New session". See § Reachability gate — we deliberately do **not** touch it.
- Memory: `catch-illegalstate-swallows-cancellation` and `androidtest-not-compiled-by-mandatory-gates` lessons — both bite here (see § Testing).

## Context

The thread overflow "New session" item dispatches `ThreadEvent.NewSession`, which the ViewModel routes to `Unit` — a silent no-op (`ThreadViewModel.kt:621`). #539 shipped `startNewSession` on the remote repo: it sends the bare fire-and-forget `new_session` v2 frame and, when the session isn't connected, throws `IllegalStateException` (`check(pump.send(...))`, `RemoteConversationRepository.kt:1442`). This slice wires the event to that call and gives the not-connected failure a user-visible surface.

Two observable outcomes, both already-established idioms in this VM:

- **Success is passive** — `new_session` has no ack/reply. The client learns it worked when the daemon later emits `session_transition (reason: "clear")`, which the **#336 fold already renders as a thread delimiter**. This slice renders nothing on success; it sends and discards the placeholder `Session` return.
- **Failure is not-connected only** — surface it as a transient snackbar with a **fixed local string**, mirroring the `modalSendErrors → snackbar` idiom (#452). Do **not** reuse `launchGuardedRepoCall` — it swallows failures with no surface (#490), the opposite of AC #2.

## Design source

N/A — no bespoke visual (per ticket body). Success reuses the shipped #336 `SessionBoundary` delimiter; failure reuses the existing Material 3 snackbar idiom (#452). No new screen, component, dialog, or layout. The visual-fidelity check is intentionally skipped.

## Design

Three production Kotlin files, all additive edits (no new files, no new exported types). The new surface is one `private` method, one payload-free one-shot flow, and one string resource.

### 1. ViewModel — one-shot error flow + `sendNewSession` (`ThreadViewModel.kt`)

Add a second one-shot channel/flow pair beside `modalSendErrors`, and a private send method that is the **surfacing** twin of `sendInterrupt`:

- New private `Channel<Unit>(capacity = BUFFERED)` `newSessionErrorChannel` and public `val newSessionErrors: Flow<Unit> = newSessionErrorChannel.receiveAsFlow()`. Payload-free `Unit` — nothing sensitive can flow through it (same posture as `modalSendErrors`). KDoc: fires once per caught not-connected failure; the render slice shows a transient snackbar.
- New private `fun sendNewSession()` that `viewModelScope.launch`es `repository.startNewSession(conversationId)` (the VM's own `conversationId`, never caller-supplied; `workspace` defaulted null) inside a try/catch with **exactly two catches, in this order**:
  1. `catch (e: CancellationException) { throw e }` — **MUST be first** (`j.u.c.CancellationException extends IllegalStateException` on the JVM; a bare ISE catch would swallow `viewModelScope` teardown mid-send — the `catch-illegalstate-swallows-cancellation` lesson).
  2. `catch (e: IllegalStateException) { newSessionErrorChannel.trySend(Unit) }` — the not-connected surface.
- Route the event: `ThreadEvent.NewSession -> Unit` (`:621`) becomes `ThreadEvent.NewSession -> sendNewSession()` inside `onOverflowEvent`. The returned `Session` is discarded.

**Catch contract — why no `RelayErrorException` branch.** The two swallowing twins (`sendInterrupt`, `onDropQueued`) retain an inert `RelayErrorException` catch for parity, but they swallow into empty bodies — harmless. Here each catch that fires maps to a *user-visible* snackbar. `startNewSession` is fire-and-forget (no awaited reply), so a correlated server `error` — hence `RelayErrorException` — can never originate; adding a branch would be dead code routing a never-thrown error to the UI. Evidence-based fix selection: catch only what `startNewSession` actually throws. This is a documented, intentional deviation from the interrupt sibling.

### 2. String resource (`strings.xml`)

Add one string beside `modal_send_failed`:

```xml
<string name="new_session_failed">Couldn\'t start a new session. Check your connection.</string>
```

Fixed local text — the failure is exclusively not-connected. Never derived from an exception message (§ Security).

### 3. ThreadScreen — second snackbar collector (`ThreadScreen.kt`)

- New composable param `newSessionErrors: Flow<Unit> = emptyFlow()`, placed **after `modifier`** alongside `modalSendErrors` (the `ComposeParameterOrder` lint errors on a defaulted param before `modifier` — the `compose-parameter-order-lint-defaulted-after-modifier` lesson; this placement satisfies it and matches the file's existing order).
- Resolve `stringResource(R.string.new_session_failed)` at composable scope, then a second `LaunchedEffect(newSessionErrors, snackbarHostState) { newSessionErrors.collect { snackbarHostState.showSnackbar(...) } }` — a structural clone of the `modalSendErrors` collector (128-130), reusing the same `snackbarHostState`.

### 4. MainActivity wiring (`MainActivity.kt`)

One line in the `ThreadScreen(...)` call: `newSessionErrors = vm.newSessionErrors`. `onOverflowEvent = vm::onOverflowEvent` already carries the `NewSession` event — no other change.

### Reachability gate — leave `ThreadOverflowMenu` untouched (scope decision)

The "New session" item sits inside `if (mutationsSupported)` (`ThreadOverflowMenu.kt:49`), and `RemoteConversationRepository.mutationsSupported == false` in relay mode, so a real relay user cannot yet reach this item. The ticket flags un-gating "New session" alone as a *defensible* optional change. **We decline it**, deliberately:

- The gate bundles New session **+ Rename + Change workspace + Archive** together (`:49-78`). Un-gating New session alone yields an inconsistent partial menu — Rename is *also* wired (#530) yet would stay hidden, while Change workspace / Archive still throw and *must* stay hidden. A coherent per-item gate is a family-wide decision (tracked in the recommended-but-unfiled per-mutation-gate prereq), not a #540-local one; pre-empting it piecemeal here is scope creep.
- Simplicity First + scope discipline: this ticket's contract is *wire the event + surface the failure*. The component/VM test proves it at the seam. Real-user reachability (and thus the #541 e2e) waits on the coarse-flag milestone or a dedicated per-mutation-gate ticket.
- Keeps the change at 3 production Kotlin files and avoids touching a file other mutation-wire tickets may edit.

**Do not flip `mutationsSupported` globally** — it would un-hide the still-throwing archive/change-workspace siblings.

## State + concurrency model

- No new `StateFlow`. The one-shot `newSessionErrors` is a `Channel(BUFFERED).receiveAsFlow()` cold flow, collected once by `ThreadScreen` — identical lifecycle to `navigationEvents` / `modalSendErrors`.
- `sendNewSession` runs on `viewModelScope` (Main dispatcher via the default `viewModelScope` context); the actual frame send / IO lives below the repository seam. No dispatcher switch in the VM.
- Cancellation: `viewModelScope` teardown on screen exit cancels an in-flight send; the `CancellationException`-first rethrow lets structured cancellation propagate cleanly (no error surfaced, no crash).
- `trySend` on a `BUFFERED` channel never suspends and never fails under normal load; a dropped signal (buffer full) at worst omits one snackbar — acceptable, matches `modalSendErrors`.

## Error handling

| Failure mode | Origin | VM behavior | UI surface |
|---|---|---|---|
| Not connected | `IllegalStateException` from `check(pump.send(...))` | `newSessionErrorChannel.trySend(Unit)` | Transient snackbar, `new_session_failed` |
| Scope teardown mid-send | `CancellationException` (extends ISE) | rethrown (first catch) | none (inert) |
| Server `error` reply | impossible — fire-and-forget, no reply | n/a (no catch) | n/a |
| Unexpected throw | not produced by `startNewSession` | propagates (fail-loud on programmer error) | n/a |

Success path surfaces nothing; the #336 fold renders the delimiter when `session_transition` arrives.

## Testing strategy

Unit (`testDebugUnitTest --tests "…ThreadViewModelTest"`, `runTest`) — adapt the three interrupt tests (`ThreadViewModelTest.kt:638-720`). Drive the failure via a fake `ConversationRepository` whose `startNewSession` throws (the archive/delete throwing-repo shape, `:840-920`), not a lambda double.

- **Routing** — `onOverflowEvent(ThreadEvent.NewSession)` → `startNewSession` invoked exactly once with the VM's `conversationId`; no error emitted. (Fake records the call + returns a placeholder `Session`.)
- **Not-connected failure surface** — fake `startNewSession` throws `IllegalStateException`; assert `newSessionErrors` **emits exactly one** `Unit` **and** install a default uncaught-exception handler (per AC #5 / the interrupt lesson) proving the throw never escaped the launched coroutine. Both assertions matter: `viewModelScope` is a separate `SupervisorJob`, so a leaked throw would *not* fail `runTest` — the uncaught-handler capture is the only proof the catch ran.
- **Cancellation not swallowed** — a suspending-gate `startNewSession` held in-flight, then `viewModelScope` cleared; assert no crash and no error emission (structured cancellation propagates inert). Guards the catch **ordering**.

Snackbar rendering itself is the exact `modalSendErrors` `LaunchedEffect` idiom already covered for the modal path; the new collector is a structural clone, so a dedicated androidTest snackbar assertion is optional. Prefer not to gate correctness on androidTest — it is **not compiled by the mandatory `test`/`lint`/`assembleDebug` gates** (`androidtest-not-compiled-by-mandatory-gates` lesson); if added, verify with `compileDebugAndroidTestKotlin`.

## Security review

**Verdict:** PASS

Adversarial self-review per `architect/security-review.md`. The load-bearing threat is the #452/#490 confidentiality posture — server-supplied or exception-derived text must never reach the un-secured Activity window the snackbar draws in.

**Findings:**

- **[Trust boundaries]** No findings — one explicit boundary: the single `catch (IllegalStateException)` in `sendNewSession`. Its message (untrusted-adjacent) is **discarded** (`trySend(Unit)`), never read/mapped. Downstream `ThreadScreen` holds only `Unit`. Inbound is clean too: `ThreadEvent.NewSession` is a payload-free `data object` and `sendNewSession` passes the VM's own `SavedStateHandle` `conversationId`, never a screen-supplied id — no untrusted value crosses into the send.
- **[Tokens/secrets]** N/A — this slice generates, stores, or rotates no credential; the `new_session` frame carries no payload.
- **[File/storage]** N/A — no filesystem, DataStore, or path operations.
- **[Android attack surface]** N/A — no new Activity/Service/Receiver/deep-link/PendingIntent/ContentProvider/WebView. The event originates in-process from a dropdown tap.
- **[Cryptographic primitives]** N/A — no RNG/crypto/secret-comparison. The frame is sent through the already-shipped Noise transport below the repo seam (#309/#539); this slice adds none.
- **[Network & I/O]** N/A at this layer — the send (`pump.send`), timeouts, TLS, and frame-size caps live in the #539 remote impl / #309 pump below the repo seam. Fire-and-forget: no inbound reply to parse, so no new inbound trust surface.
- **[Error messages / logs / telemetry]** No findings — the sole user-visible string is the fixed local resource `new_session_failed`; the `IllegalStateException.message` is never read, logged, or shown. No `Log.*`/`println`/Timber on the send or catch path (also enforced by the `android-log-throws-in-plain-jvm-unit-tests` test constraint). The exception is caught, so it never reaches an uncaught handler / crash reporter carrying state. No telemetry added.
- **[Concurrency]** No findings — the send is `viewModelScope`-owned (cancelled on screen exit, no application-scope leak). `CancellationException` is rethrown **before** the `IllegalStateException` catch, so teardown mid-send propagates cleanly and cannot be masked as a handled failure (tested). `newSessionErrors` is a **cold** `Channel(BUFFERED).receiveAsFlow()` — per-collector, single `ThreadScreen` subscriber — not a shared hot flow, so a failure signal cannot leak across screens. `trySend` is atomic (no check-then-mutate TOCTOU). Buffered-replay-after-config-change behavior is identical to `modalSendErrors` and carries only `Unit` — not a leak.
- **[Threat model alignment]** No findings — the one applicable mobile threat is Activity-window text leakage (screenshot / screen-overlay eavesdropping); the fixed-string contract neutralizes it (nothing sensitive to leak even if captured). Reachability gating (`mutationsSupported`) is named OUT OF SCOPE (§ Reachability gate) — a hidden-but-wired action is strictly more conservative, not a threat.

No MUST FIX and no SHOULD FIX: the fixed-string surface, log-free path, and cancellation-first ordering are baked into the design.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-09

## Open questions

- None blocking. The per-mutation reachability gate is explicitly deferred (§ Reachability gate) to the family-wide milestone / prereq ticket; #541 (e2e) remains gated on it.
