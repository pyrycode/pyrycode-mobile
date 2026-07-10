# Spec #557 — Surface restore failure from the Archive screen (no silent no-op)

**Ticket:** pyrycode-mobile #557 · size **S** · `security-sensitive`
**Split from #550** (itself split from #531). Twin of #556 (archive-from-thread, DONE PR#558) — same "no crash, no silent no-op" mutation-surface pattern, disjoint files. Data child #549 (merged) shipped `ConversationRepository.unarchive` across interface / Fake / Remote / Stable. E2e sibling #551 is Inbox (family reachability gate #537).

This is the **restore-from-Archive-screen** UI child: give the *failure* path of `ArchivedDiscussionsViewModel.RestoreRequested` a user surface, while retaining the existing success surface. It is strictly smaller than #556: it **reuses the screen's existing `effects` flow** — no new flow, no `MainActivity` wire.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:48-52` — `ArchivedDiscussionsEffect` sealed interface (only `RestoreSucceeded` today). You add a `RestoreFailed` variant here.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:87-105` — `onEvent`; the `RestoreRequested` branch (`:89-100`) is the `runCatching { unarchive }.onSuccess { emit }` **silent-no-op** you replace. Note the current catch is `Throwable`-wide (via `runCatching`) — it swallows *everything*, including `CancellationException`. Read the existing deferral comment (`:91-93`) — this ticket is that deferral.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:57-60` — the existing `_effects` `Channel(Channel.BUFFERED)` + `effects: Flow<…> = receiveAsFlow()`. Reuse it as-is; `RestoreFailed` rides the same channel as `RestoreSucceeded`. Delivery primitive to mirror: `_effects.send(...)` (`:96`).
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:44-57` — the `effects: Flow<…> = emptyFlow()` param + the `LaunchedEffect { effects.collect { when (effect) … } }` collector. `resources = LocalResources.current` (`:47`) is resolved at composable scope, then `resources.getString(...)` inside the collector (`:53`). The `when` is exhaustive over the sealed interface — adding `RestoreFailed` **forces** a new branch (compile error otherwise). Mirror the `RestoreSucceeded` branch exactly.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:462-476` — the `ArchivedDiscussionsScreen(...)` call site. `effects = vm.effects` is **already wired** (`:476`). **No change here** — the new variant flows through the existing wire. (Contrast #556, which needed a new `archiveErrors = vm.archiveErrors` line because it introduced a *new* flow.)
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:77-98` — `unarchive` contract + the `mutationsSupported` gate (`:57-67`). The doc (`:85`) states `unarchive` throws `IllegalArgumentException` on unknown ids — read this before deciding the catch set (§ Catch contract).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1405, 1409-1444` — `unarchive` → `sendArchiveToggle(id, TYPE_UNARCHIVE_CONVERSATION)`: request/reply (`sendAndAwaitReply`, awaits `conversation_updated`). Its throw taxonomy is the load-bearing fact — `IllegalStateException` (not connected, `live` path), `RelayErrorException` (server `error`), `IllegalArgumentException` (`conversation.not_found`), decode exception (malformed reply). Same body as `archive` (#549 shared it). This is why the catch surfaces **two** relay types.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:147` — `RelayErrorException`. Its `message` is server-supplied (untrusted) — the § Security control is that it is **never read**.
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt` — the existing test surface. In scope: the success tests (`:277-297`) stay green; `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows` (`:299-318`) is **rewritten** (§ Testing); the `throwingUnarchiveRepo` helper (`:496-535`, currently hard-throws `RuntimeException("boom")`) is **parameterized** to throw a caller-chosen `Throwable`. The `RecordingRepo` (`:448-492`) is reused unchanged.
- `app/src/main/res/values/strings.xml:46, 98-99` — `restored_snackbar` (the success string) and `new_session_failed` / `archive_failed` (#540/#556 failure strings). Add a sibling `restore_failed` next to them.
- Memory lessons that bite: `catch-illegalstate-swallows-cancellation` (catch ordering — the whole ballgame here), `android-log-throws-in-plain-jvm-unit-tests` (no `Log.*` on the VM path), `androidtest-not-compiled-by-mandatory-gates` (only if you add the optional component test).

## Context

The Archive screen lists archived channels + discussions; each row has a restore affordance (Figma node `18:23`). Tapping it dispatches `ArchivedDiscussionsEvent.RestoreRequested(conversationId, displayName)`. Today `onEvent` handles it with:

```kotlin
runCatching { repository.unarchive(event.conversationId) }
    .onSuccess { _effects.send(RestoreSucceeded(event.displayName)) }
```

On **failure** it emits nothing — a silent no-op with an explicit "unarchive yields no UI surface in this slice" deferral comment (`:91-93`). This slice is that deferral: a failed restore must surface a transient message, never vanish, never falsely claim success.

#549 shipped `unarchive` on the remote repo as a **request/reply** send: it encodes the id-only payload, awaits the correlated `conversation_updated` reply, decodes it, and confirmed-upserts. Because it awaits a reply, a server `error` frame surfaces as `RelayErrorException` — reachable here, unlike a fire-and-forget send. The disconnected case is `IllegalStateException` from the repository's `live` path.

Two observable outcomes:

- **Success is list-driven** — the confirmed upsert makes `observeConversations(Archived)` re-emit *without* the conversation (it is no longer archived), so the row leaves the Archived list on its own, with **no explicit removal call**. The existing `RestoreSucceeded` snackbar is retained.
- **Failure surfaces** — a transient snackbar with a **fixed local string**, mirroring `newSessionErrors` (#540) / `archiveErrors` (#556) / the modal-send pattern (#452).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Archive Screen — a dark `surface` (`schemes/surface`) column: a top bar (back arrow + `title-large` "Archived"), a Channels / Discussions `SecondaryTabRow` (`label-large`, `primary` underline on the active tab), then a `LazyColumn` of rows, each with the conversation name (`title-medium`), an "Archived {relative}" subtitle (`body-small`, `on-surface-variant`), and a **restore icon button** (node `18:23` — a 40dp frame holding a 22dp circular-arrow icon). The surface is **locked and unchanged** — this ticket wires the existing restore action. The failure feedback reuses the standard transient Material 3 snackbar (the #452 / #540 / #556 pattern) drawn by the screen's existing `SnackbarHostState`; **no new visual design**.

## Design

Two production Kotlin files, all additive edits — no new files, no new top-level exported types. The new failure surface is one nested effect variant, one rewritten `onEvent` branch, one `when`-branch, and one string resource.

### 1. ViewModel — add `RestoreFailed`, rewrite the catch (`ArchivedDiscussionsViewModel.kt`)

- **Add the effect variant** to the existing sealed interface (`:48-52`):

  ```kotlin
  data object RestoreFailed : ArchivedDiscussionsEffect
  ```

  Payload-free `data object` — nothing sensitive can flow through it (same posture as #540/#556). The render slice shows a **fixed local string**, never an exception message.

- **Rewrite the `RestoreRequested` branch** (`:89-100`): replace the `runCatching { … }.onSuccess { … }` with an explicit `try`/`catch` inside the existing `viewModelScope.launch`. Contract (not a full body — the developer writes it):
  1. `try { repository.unarchive(event.conversationId); _effects.send(RestoreSucceeded(event.displayName)) }` — success path unchanged (id from the event, delivery via the file's existing `send`).
  2. `catch (e: CancellationException) { throw e }` — **MUST be first** (§ Catch contract).
  3. `catch (e: RelayErrorException) { _effects.send(RestoreFailed) }`.
  4. `catch (e: IllegalStateException) { _effects.send(RestoreFailed) }`.

  Delete the "swallow failures" deferral comment (`:91-93`); it no longer describes the behavior.

- New imports: `kotlinx.coroutines.CancellationException`, `de.pyryco.mobile.data.network.RelayErrorException`.

### Catch contract — why two relay types, and why `CancellationException` first

- **`CancellationException` first, rethrown.** JVM: `kotlinx.coroutines.CancellationException` → `java.util.concurrent.CancellationException` → `IllegalStateException`. If the `IllegalStateException` catch ran first it would swallow a teardown cancellation and mis-surface it as a restore failure (AC #4 violation). The `catch-illegalstate-swallows-cancellation` lesson — and #540's shipped ordering — put the `CancellationException` rethrow ahead of the ISE catch. This is the load-bearing correctness fact of the ticket.
- **`RelayErrorException` (server `error`).** `unarchive` awaits a correlated reply (`sendAndAwaitReply`), so a server `error` frame is a real, reachable outcome. AC #1's "daemon error". Surface it.
- **`IllegalStateException` (not connected).** The repository's `live` path throws ISE when no connection is up. AC #1's "disconnected". Surface it.
- Both surfaced types map to the **same** payload-free `RestoreFailed`.

**Deliberately not caught** (evidence-based fix selection — matches the #556/#530 family decision exactly; note this *narrows* today's over-broad `runCatching(Throwable)`):

- `IllegalArgumentException` (`conversation.not_found`, per the interface doc `:85`) — **unreachable by construction**: you can only restore a conversation currently shown in the Archived list (it is in `observeConversations(Archived)` by construction). #556 and #530 both leave `not_found` uncaught for the same reason; adding a catch defends an unobserved failure mode. Under the new narrow catch this now **escapes fail-loud** rather than being silently swallowed — that is the intended parity with every other mutation VM, not a regression.
- The #318 decode exception (malformed reply) — a fail-loud protocol violation, not a user-recoverable restore failure. Preserving the crash is exact parity with the family.

> **Behavior-change flag for the developer:** the current `runCatching` catches `Throwable`, so it silently swallows `IllegalArgumentException`, decode exceptions, and any other unlisted throw. The new narrow catch lets those escape (fail-loud). This is deliberate and matches #556. Its one test consequence: the existing `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows` test throws a plain `RuntimeException("boom")`, which the new catch does **not** handle → it must be rewritten to the in-scope failure types (§ Testing).

### 2. Screen — third `when` branch on the existing collector (`ArchivedDiscussionsScreen.kt`)

- The `effects.collect { when (effect) … }` (`:50-55`) becomes exhaustive over two variants. Add a `RestoreFailed` branch that calls `snackbarHostState.showSnackbar(resources.getString(R.string.restore_failed))` — a structural clone of the `RestoreSucceeded` branch (`:51-54`), reusing the same `snackbarHostState` and the already-resolved `resources`. The composable stays **stateless** — no new param, no new state; it already owns the `SnackbarHostState`.

### 3. String resource (`strings.xml`)

Add one string beside `archive_failed` (`:99`):

```xml
<string name="restore_failed">Couldn\'t restore this conversation. Try again.</string>
```

Fixed local text — covers both failure modes (daemon error and disconnected), so like `archive_failed` it does **not** say "check your connection". Never derived from an exception message (§ Security). (`strings.xml` is a resource, not a production `.kt` file — outside the §-scope Kotlin count.)

### No `MainActivity` change

`effects = vm.effects` is already wired at `MainActivity.kt:476`; `RestoreFailed` rides the existing flow. No call-site edit, no new param, **zero edit fan-out**.

### Reachability gate — leave the gate untouched (scope decision)

Against the real relay, `unarchive` sits behind `mutationsSupported == false` (`RemoteConversationRepository`), the #537 family gate, so a live user cannot yet reach this path — it is exercised via fakes in tests, not the live UI. **This ticket does not flip that gate** (per the ticket's reachability note and #540/#556's identical decision). Un-gating is a family-wide per-mutation decision tracked separately; #551 (e2e) remains gated on it.

## State + concurrency model

- No new `StateFlow`, no new `Channel`. `RestoreFailed` reuses the existing `_effects` `Channel(BUFFERED).receiveAsFlow()` **cold** flow, collected once by `ArchivedDiscussionsScreen` — identical lifecycle to the existing `RestoreSucceeded`.
- The handler runs on `viewModelScope` (Main via the default context); the request/reply send + IO live below the repository seam. No dispatcher switch in the VM.
- Cancellation: `viewModelScope` teardown on screen exit cancels an in-flight restore; the `CancellationException`-first rethrow lets structured cancellation propagate cleanly — no effect surfaced, no crash.
- `send` on a `BUFFERED` VM-lifetime channel (never closed) does not suspend meaningfully under normal load and cannot throw an ISE that would be mis-caught. A `send` inside a catch that races teardown throws `CancellationException`, which propagates (not re-caught) — structured cancellation, no crash. (`trySend` is an equally acceptable delivery primitive; use whichever matches the file — the file uses `send`.)

## Error handling

| Failure mode | Origin | VM behavior | UI surface |
|---|---|---|---|
| Server `error` reply | `RelayErrorException` from `sendAndAwaitReply` | `_effects.send(RestoreFailed)`, no `RestoreSucceeded` | Transient snackbar, `restore_failed` |
| Not connected | `IllegalStateException` (repo `live` path) | `_effects.send(RestoreFailed)`, no `RestoreSucceeded` | Transient snackbar, `restore_failed` |
| Scope teardown mid-restore | `CancellationException` (extends ISE) | rethrown (first catch), no effect | none (inert) |
| `conversation.not_found` | `IllegalArgumentException` | not caught — unreachable (restoring a listed conversation) | n/a (fail-loud parity with #556/#530) |
| Malformed reply | #318 decode exception | not caught — fail-loud protocol violation | n/a (parity) |
| **Success** | reply decodes + confirmed-upsert | `_effects.send(RestoreSucceeded(displayName))` | `restored_snackbar`; `observeConversations` re-emits without the conv (list-driven; row leaves the list) |

## Testing strategy

Unit (`testDebugUnitTest --tests "…ArchivedDiscussionsViewModelTest"`, `runTest`). The existing file already sets `Dispatchers.setMain(UnconfinedTestDispatcher())` and collects `vm.effects` via `async { vm.effects.first() }` — reuse that shape.

**New / rewritten tests:**

- **Rewrite `restoreRequested_doesNotEmitEffect_whenUnarchiveThrows` (`:299-318`)** — it is now semantically wrong (the new design *does* surface a failure). Replace it with a **disconnected-failure surfaces** test *(AC #6 "at least one driven by disconnected ISE")*: repo whose `unarchive` throws `IllegalStateException("not connected")`; trigger `RestoreRequested`; `advanceUntilIdle()`; assert `vm.effects.first()` (with a bounded `withTimeoutOrNull`) is `ArchivedDiscussionsEffect.RestoreFailed`. Because the VM path deliberately drops the over-broad `Throwable` catch, a leaked non-cancellation throw would escape to the uncaught handler — install `Thread.setDefaultUncaughtExceptionHandler` capture (the `android-log`/`#430` pattern) and assert it stayed empty, proving the catch ran rather than the throw escaping.
- **Server-error surfaces** *(recommended — proves the `RelayErrorException` catch, and that the server `message` never reaches the surface)* — same shape with `unarchive` throwing `RelayErrorException(code = "server.error", retryable = false, message = "leak me")`; assert `RestoreFailed` emitted, uncaught-handler empty. The asserted effect is the payload-free `RestoreFailed`, so the server `message` is structurally unable to surface — that *is* the AC #5 proof.
- **Cancellation not mis-surfaced** *(AC #4)* — a gating repo whose `unarchive` suspends until cancelled (mirror the existing `async`/gate pattern, or a `CompletableDeferred` that never completes); start the restore, cancel the VM's scope / the collecting job, `advanceUntilIdle()`; assert **no** `RestoreFailed` was emitted (bounded `withTimeoutOrNull` → `null`) and the uncaught handler stayed empty. Proves the `CancellationException`-first rethrow keeps teardown inert.

**Existing tests that stay green unchanged (verify, don't edit):**

- `restoreRequested_callsUnarchive_withConversationId` (`:261-274`) — the id still reaches `unarchive`.
- `restoreRequested_emitsRestoreSucceededEffect_afterUnarchive` (`:277-297`) — success still emits `RestoreSucceeded`. Covers AC #2 (success surface retained).
- All `state`/tab/partition tests (`:45-258`) — untouched.

**Helper change:** parameterize `throwingUnarchiveRepo(source)` (`:496-535`) to `throwingUnarchiveRepo(source, error: Throwable)` so the two failure tests can inject `IllegalStateException` vs `RelayErrorException` through one double. No new top-level double.

**Component snackbar test (androidTest) — optional**, consistent with #540/#556 (which added none for their error flows; the collector is a structural clone of the already-proven `RestoreSucceeded` branch). There is no existing `ArchivedDiscussionsScreen` androidTest, so one would be net-new. If added, it is **not** compiled by the mandatory `test`/`lint`/`assembleDebug` gates — verify with `compileDebugAndroidTestKotlin` (`androidtest-not-compiled-by-mandatory-gates` lesson). The mandatory AC #6 coverage is satisfied by the ViewModel unit tests above.

## Security review

**Verdict:** PASS

Adversarial self-review per `architect/security-review.md`. The load-bearing threat is the #452/#490/#540/#556 confidentiality posture: server-supplied or exception-derived text must never reach the un-secured Activity window the snackbar draws in. Restore raises the stakes over a fire-and-forget send because it **does** receive a server `error` frame (`RelayErrorException`), whose `message` is attacker-influenced.

**Findings:**

- **[Trust boundaries]** No findings — two explicit boundaries in the rewritten handler: `catch (RelayErrorException)` and `catch (IllegalStateException)`. `RelayErrorException.message` is server-supplied (untrusted); `IllegalStateException.message` is internal. **Both are discarded** — the catch bodies call `_effects.send(RestoreFailed)` and never read, map, log, or forward the message. Downstream `ArchivedDiscussionsScreen` holds only a payload-free `RestoreFailed` and shows a fixed local resource. Inbound is clean: `RestoreRequested` carries only a `conversationId`/`displayName` that originate from the VM's own list projection (a conversation the repo already surfaced), and `unarchive` receives that id — no attacker-authored value crosses into the send. This control is baked into the design (a payload-free effect), not left to the developer.
- **[Tokens/secrets]** N/A — no credential generated, stored, or rotated; the `unarchive_conversation` frame carries only the conversation id (already client-held).
- **[File/storage]** N/A — no filesystem, DataStore, or path operations.
- **[Android attack surface]** N/A — no new Activity/Service/Receiver/deep-link/PendingIntent/ContentProvider/WebView. The event originates in-process from a row-button tap.
- **[Cryptographic primitives]** N/A — no RNG/crypto/secret-comparison. The frame rides the already-shipped Noise transport below the repo seam (#309/#549); this slice adds none.
- **[Network & I/O]** N/A at this layer — the request/reply send, correlation, timeouts, TLS, and frame-size caps live in the #549 remote impl / #309 pump below the repo seam. The one inbound-adjacent surface (the `conversation_updated` reply) is decoded **below** the repo seam by the shipped #318 boundary before `unarchive` returns; a malformed reply throws there (fail-loud) and never reaches this VM as data — the handler sees only success or a typed throw.
- **[Error messages / logs / telemetry]** No findings — the sole user-visible string is the fixed local resource `restore_failed`; neither caught exception's `message` is read, logged, or shown. No `Log.*`/`println`/Timber on the handler or catch path (also enforced by the `android-log-throws-in-plain-jvm-unit-tests` test constraint — the VM path stays log-free). Both in-scope failures are caught, so neither reaches an uncaught handler / crash reporter carrying server text. No telemetry added.
- **[Concurrency]** No findings — the send is `viewModelScope`-owned (cancelled on screen exit; no application-scope leak). `CancellationException` is rethrown **before** the two typed catches, so teardown mid-restore propagates cleanly and cannot be masked as a handled failure or fire a stray snackbar (tested — § Testing). `effects` is a **cold** `Channel(BUFFERED).receiveAsFlow()` — per-collector, single `ArchivedDiscussionsScreen` subscriber — not a shared hot flow, so a failure signal cannot leak across screens. `send`/`trySend` on the buffered channel is atomic (no check-then-mutate TOCTOU). `RestoreSucceeded` is emitted strictly inside the success continuation, so a failure can never both surface an error **and** claim success.
- **[Threat model alignment]** No findings — the applicable mobile threat is Activity-window text leakage (screenshot / screen-overlay eavesdropping); the fixed-string contract neutralizes it (nothing server-derived to leak even if captured), which is exactly why AC #5 forbids surfacing `RelayErrorException.message`. Reachability gating (`mutationsSupported`) is named OUT OF SCOPE (§ Reachability gate) — a hidden-but-wired action is strictly more conservative, not a threat; picked up by the family-wide per-mutation-gate milestone (blocks #551).

No MUST FIX and no SHOULD FIX: the discard-the-message contract, log-free path, cancellation-first ordering, and success-only `RestoreSucceeded` are structural in the design.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10

## Open questions

- None blocking. The per-mutation reachability gate is deferred (§ Reachability gate) to the family-wide milestone; #551 (e2e) remains gated on it.
