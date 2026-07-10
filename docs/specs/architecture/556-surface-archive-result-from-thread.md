# Spec #556 — Surface the archive result from the thread (no crash, no silent no-op)

**Ticket:** pyrycode-mobile #556 · size **S** · `security-sensitive`
**Split from #550** (itself split from #531). Data child #549 (merged) shipped `ConversationRepository.archive` across interface / Fake / Remote / Stable. Sibling #557 (restore-from-Archive, `ui/settings/`) is disjoint — no shared files, no cross-blocker. E2e sibling #551 is Inbox (family reachability gate #537).

This is the **archive-from-thread** UI child: handle the archive **result** in `ThreadViewModel` and give a failure a user surface.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:368-378` — `newSessionErrorChannel` + `newSessionErrors` (#540): the exact one-shot error-flow idiom to clone for `archiveErrors`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:550-575` — `sendNewSession` (#540): the **surface-on-failure** send twin (catch-Cancellation-first → error channel). Your `sendArchive` is this shape **plus** a `RelayErrorException` catch and a success-only `PopBack`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:616-624` — `onOverflowEvent`'s current `ThreadEvent.Archive` branch: `launchGuardedRepoCall { repository.archive(...); navigationChannel.send(PopBack) }`. This is what you replace.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt:15-40` — `launchGuardedRepoCall`'s catch set (Cancellation-rethrow, RelayError, ISE, UnsupportedOperationException; **not** IllegalArgumentException). Read the KDoc: the "never log server-supplied message" confidentiality assertion is why archive must **not** reuse this helper *and* must not read the caught message.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1403-1444` — `archive` → `sendArchiveToggle`: request/reply (`sendAndAwaitReply`, awaits `conversation_updated`). Its throw taxonomy is the load-bearing fact — `IllegalStateException` (not connected), `RelayErrorException` (server `error`), `IllegalArgumentException` (`conversation.not_found`), decode exception (malformed reply). This is why archive catches **two** relay types, unlike fire-and-forget #540.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:116-138` — the `modalSendErrors` / `newSessionErrors` params (116-117) + their `LaunchedEffect` snackbar collectors (129-138). `stringResource` is resolved **outside** the `LaunchedEffect` then captured — mirror exactly.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:371-395` — the `ThreadScreen(...)` call site; `newSessionErrors = vm.newSessionErrors` (384) is the one new-wire line to mirror. `onOverflowEvent = vm::onOverflowEvent` (388) already carries `Archive` — **no new event wiring**.
- `app/src/main/res/values/strings.xml:97-98` — `modal_send_failed` / `new_session_failed`; add a sibling `archive_failed`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:938-1023` — the three shared guard tests. **Two need edits (§ Test interactions):** the multi-failure swallow loop (938-963, includes `UnsupportedOperationException` + an `Archive` call) and the cancellation test (999-1023, `GatingArchiveRepo`).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1892-1915` — `onOverflowEvent_archive_archivesClosesSheetAndPopsBack`: the success-pops-back test. Stays **green** unchanged; it already covers AC "success".
- `app/src/test/java/de/pyryco/mobile/ui/conversations/ThrowingConversationRepository.kt` — the shared double that throws a chosen `Throwable` from `archive` while delegating `observe*` to a seeded fake. Reuse it for the failure tests; **no new double needed.**
- Memory lessons that bite: `catch-illegalstate-swallows-cancellation` (catch ordering), `androidtest-not-compiled-by-mandatory-gates` (any component test needs `compileDebugAndroidTestKotlin`).

## Context

Tapping "Archive" (thread overflow node `16-8`, or Channel Info Sheet node `20-48`) dispatches `ThreadEvent.Archive`. Today `onOverflowEvent` routes it through `launchGuardedRepoCall { repository.archive(...); navigationChannel.send(PopBack) }` (`ThreadViewModel.kt:618-623`). `launchGuardedRepoCall` **inert-swallows** every failure with no user surface (#490), and a swallowed throw short-circuits the `PopBack` that follows inside the same block. So an archive failure today is a **silent no-op that also strands the user on the thread** — both halves of the AC's "never a crash, never a silent no-op" are violated.

#549 shipped `archive` on the remote repo as a **request/reply** send: it encodes the id-only payload, awaits the correlated `conversation_updated` reply, decodes it, and confirmed-upserts (`RemoteConversationRepository.kt:1428-1444`). Because it awaits a reply, a server `error` frame surfaces as `RelayErrorException` — reachable here, unlike fire-and-forget `new_session`.

Two observable outcomes, both established idioms in this VM:

- **Success is list-driven** — the confirmed upsert makes `observeConversations` re-emit with the conversation now in the Archived tier, so it leaves the main list with **no explicit removal call**. The VM only pops the thread back.
- **Failure surfaces** — a transient snackbar with a **fixed local string**, mirroring `modalSendErrors → snackbar` (#452) and `newSessionErrors` (#540). The disconnected case is `IllegalStateException` from the `live` path.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48

Channel Info Sheet — a rounded-`28dp` `surface-container-low` bottom sheet whose "Actions" block hosts the **Archive** `secondary-container` pill (node `20:95`), also reached from the thread overflow (node `16-8`). Both surfaces are **locked and unchanged** — this ticket wires the existing action. The failure feedback reuses the standard transient Material 3 snackbar (the #452 / #540 pattern) drawn by the screen's existing `SnackbarHostState`; **no new visual design**.

## Design

Three production Kotlin files, all additive edits — no new files, no new exported types. The new surface is one `private` method, one payload-free one-shot `Flow<Unit>`, and one string resource.

### 1. ViewModel — one-shot error flow + `sendArchive` (`ThreadViewModel.kt`)

- Add a one-shot channel/flow pair beside `newSessionErrors` (378): private `Channel<Unit>(capacity = BUFFERED)` `archiveErrorChannel` and public `val archiveErrors: Flow<Unit> = archiveErrorChannel.receiveAsFlow()`. Payload-free `Unit` — nothing sensitive can flow through it (same posture as `newSessionErrors`/`modalSendErrors`). KDoc: fires once per caught `RelayErrorException` / `IllegalStateException`; success is passive (pop + list-driven re-emit); the render slice shows a transient snackbar with a **fixed local string**, never an exception message.
- Add private `fun sendArchive()` — the surfacing twin of `sendNewSession`. It `viewModelScope.launch`es `repository.archive(conversationId)` (the VM's own ctor-derived `conversationId`, **never** a caller-supplied id — matches `sendNewSession`/`sendInterrupt`/`onDropQueued`), and on success `navigationChannel.send(ThreadNavigation.PopBack)`. Catch order (see § Catch contract):
  1. `catch (e: CancellationException) { throw e }` — **MUST be first**.
  2. `catch (e: RelayErrorException) { archiveErrorChannel.trySend(Unit) }`.
  3. `catch (e: IllegalStateException) { archiveErrorChannel.trySend(Unit) }`.
- Rewrite the `onOverflowEvent` `Archive` branch (`:618-623`) to keep the sheet-dismiss, delegate the send, and **drop** `launchGuardedRepoCall`:

  ```kotlin
  ThreadEvent.Archive -> {
      pendingChannelInfo.value = false
      sendArchive()
  }
  ```

  `pendingChannelInfo.value = false` stays (closes the Channel Info Sheet when Archive is tapped from it; a harmless no-op from the overflow menu, where it is already false). `PopBack` moves **inside `sendArchive`'s success continuation** — it fires only after `repository.archive(...)` returns without throwing.

### Catch contract — why archive catches **two** relay types (the difference from #540)

`sendNewSession` catches only `IllegalStateException` because `new_session` is fire-and-forget — no awaited reply, so a correlated server `error` (hence `RelayErrorException`) can never originate. **Archive is the opposite:** `sendArchiveToggle` calls `sendAndAwaitReply` (`RemoteConversationRepository.kt:1441`), so a server `error` frame is a real, reachable outcome → `RelayErrorException` must be caught and surfaced. The ticket's Technical Notes state this ordering explicitly. Both caught types map to the **same** payload-free snackbar signal; the design reads the same for both.

**Deliberately not caught (evidence-based fix selection — matches the shipped guard behavior exactly, no regression):**

- `IllegalArgumentException` (`conversation.not_found`, mapped in `mapError`) — `launchGuardedRepoCall` does **not** catch it either, and it is **unreachable** here: you can only archive the conversation you are currently viewing, whose record is in the list by construction (the same "unreachable not_found crash" reasoning shipped for `rename` #530 and archive-via-guard today). Adding a catch would defend an unobserved failure mode.
- The #318 decode exception (malformed reply) — also not caught by the guard today; a malformed reply is a fail-loud protocol violation, not a user-recoverable archive failure. Preserving the crash is exact parity with every other mutation on this VM.
- `UnsupportedOperationException` — archive has no not-wired interface default (both Fake and Remote implement it concretely), so it cannot originate on any real path. Not catching it is why one shared test needs an edit (§ Test interactions).

### 2. String resource (`strings.xml`)

Add one string beside `new_session_failed`:

```xml
<string name="archive_failed">Couldn\'t archive this conversation. Try again.</string>
```

Fixed local text — covers both failure modes (daemon error and disconnected), so unlike `new_session_failed` it does **not** say "check your connection". Never derived from an exception message (§ Security). (`strings.xml` is a resource, not a production `.kt` file — outside the §-scope Kotlin count.)

### 3. ThreadScreen — third snackbar collector (`ThreadScreen.kt`)

- New composable param `archiveErrors: Flow<Unit> = emptyFlow()`, placed **after `modifier`** alongside `newSessionErrors` (satisfies `ComposeParameterOrder` lint — the `compose-parameter-order-lint-defaulted-after-modifier` lesson — and matches the file's existing order). Defaulted → the ~9 existing `ThreadScreen(...)` call sites in `androidTest` + `FakeConversationRepository` preview stay valid; **no edit fan-out**.
- Resolve `stringResource(R.string.archive_failed)` at composable scope, then a third `LaunchedEffect(archiveErrors, snackbarHostState) { archiveErrors.collect { snackbarHostState.showSnackbar(...) } }` — a structural clone of the `newSessionErrors` collector (`:136-138`), reusing the same `snackbarHostState`.

### 4. MainActivity wiring (`MainActivity.kt`)

One line in the `ThreadScreen(...)` call: `archiveErrors = vm.archiveErrors`. `onOverflowEvent = vm::onOverflowEvent` (388) already carries `Archive` — no other change.

### Reachability gate — leave `ThreadOverflowMenu` / `ChannelInfoSheet` untouched (scope decision)

Against the real relay, Archive sits behind `mutationsSupported == false` (the #537 family gate at `ThreadOverflowMenu.kt:49` / `ChannelInfoSheet.kt:118`; `RemoteConversationRepository.mutationsSupported == false`), so a live user cannot yet reach this path — it is exercised via fakes in tests, not the live UI. **This ticket does not flip that gate** (per the ticket's reachability note and #540's identical decision). Un-gating is a family-wide per-mutation decision tracked separately; #551 (e2e) remains gated on it.

## State + concurrency model

- No new `StateFlow`. `archiveErrors` is a `Channel(BUFFERED).receiveAsFlow()` **cold** flow, collected once by `ThreadScreen` — identical lifecycle to `navigationEvents` / `newSessionErrors`.
- `sendArchive` runs on `viewModelScope` (Main dispatcher via the default context); the request/reply send + IO live below the repository seam. No dispatcher switch in the VM.
- Cancellation: `viewModelScope` teardown on screen exit cancels an in-flight archive; the `CancellationException`-first rethrow lets structured cancellation propagate cleanly — no error surfaced, no `PopBack`, no crash.
- `PopBack` is a suspend `navigationChannel.send` inside the success continuation; on a `BUFFERED` VM-lifetime channel (never closed) it does not suspend meaningfully and cannot throw an `IllegalStateException` that would be mis-caught.
- `trySend` on a `BUFFERED` channel never suspends and never fails under normal load; a dropped signal at worst omits one snackbar — acceptable, matches `newSessionErrors`.

## Error handling

| Failure mode | Origin | VM behavior | UI surface |
|---|---|---|---|
| Server `error` reply | `RelayErrorException` from `sendAndAwaitReply` | `archiveErrorChannel.trySend(Unit)`, no `PopBack` | Transient snackbar, `archive_failed` |
| Not connected | `IllegalStateException` (repo `live` path) | `archiveErrorChannel.trySend(Unit)`, no `PopBack` | Transient snackbar, `archive_failed` |
| Scope teardown mid-archive | `CancellationException` (extends ISE) | rethrown (first catch), no `PopBack` | none (inert) |
| `conversation.not_found` | `IllegalArgumentException` | not caught — unreachable (archiving the viewed conversation) | n/a (parity with guard/#530) |
| Malformed reply | #318 decode exception | not caught — fail-loud protocol violation | n/a (parity with guard) |
| **Success** | reply decodes + confirmed-upsert | `navigationChannel.send(PopBack)` | pop; `observeConversations` re-emits without the conv (list-driven) |

## Testing strategy

Unit (`testDebugUnitTest --tests "…ThreadViewModelTest"`, `runTest`). Reuse existing doubles — no new double.

**New tests (add):**

- **Not-connected failure surfaces + stays on thread + no crash** *(AC "at least one driven by disconnected ISE")* — `makeVm(handle, ThrowingConversationRepository(IllegalStateException("not connected")))`; collect `archiveErrors` and `navigationEvents`; install a default uncaught-exception handler (the `viewModelScope` `SupervisorJob` means a leaked throw would **not** fail `runTest` — the handler capture is the only proof the catch ran); trigger `onOverflowEvent(ThreadEvent.Archive)`; `advanceUntilIdle()`. Assert: `archiveErrors` emitted **exactly one** `Unit`; `navigationEvents` empty (no `PopBack`); uncaught list empty.
- **Server-error failure surfaces** *(recommended — proves the archive-specific `RelayErrorException` catch)* — same shape with `ThrowingConversationRepository(RelayErrorException(code = "server.error", retryable = false, message = "no"))`. Assert `archiveErrors` emits one `Unit`, no `PopBack`, no crash. (Also confirms the server-supplied `message` never reaches the surface — the fixed string is shown.)

**Existing tests to edit (§ Test interactions):**

- **Multi-failure swallow loop** (`:938-963`) — **remove** the `vm.onOverflowEvent(ThreadEvent.Archive)` line (`:953`). Archive no longer routes through `launchGuardedRepoCall`, and its new path deliberately does not catch `UnsupportedOperationException` (unreachable for archive); leaving the call in would let the `UnsupportedOperationException` iteration escape as an uncaught throw and fail the test. The remaining actions (delete / rename / promote / send / workspace) still exercise the guard against all three types, so guard coverage stays intact; archive's swallow-and-surface is covered by the new tests above.
- **Cancellation not mis-surfaced** (`guardedRepoCall_scopeCancellationMidCall_propagatesCancellationInert`, `:999-1023`, `GatingArchiveRepo`) — still green (the `CancellationException`-first rethrow in `sendArchive` preserves the behavior). **Extend** it to also collect `archiveErrors` and assert it stayed **empty** — proving structured cancellation is never mis-surfaced as an archive failure (AC #3).

**Existing tests that stay green unchanged (verify, don't edit):**

- `onOverflowEvent_archive_archivesClosesSheetAndPopsBack` (`:1892`) — success: `RecordingRepo` archive records, `PopBack` fires, sheet closes. Covers AC "success-pops-back".
- `guardedRepoCalls_whenArchiveOrDeleteThrows_doNotPopBack` (`:965`) and `navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed` (`:1989`) — both remain green (archive's new path also skips `PopBack` on `RelayErrorException` / pops on success).

**Component snackbar test** — optional, consistent with #540 (which added no androidTest for `newSessionErrors`; the collector is a structural clone already proven for the modal path). If added to `ThreadScreenModalTest.kt`, it is **not** compiled by the mandatory `test`/`lint`/`assembleDebug` gates — verify with `compileDebugAndroidTestKotlin` (`androidtest-not-compiled-by-mandatory-gates` lesson).

## Security review

**Verdict:** PASS

Adversarial self-review per `architect/security-review.md`. The load-bearing threat is the #452/#490/#540 confidentiality posture: server-supplied or exception-derived text must never reach the un-secured Activity window the snackbar draws in. Archive raises the stakes over #540 because it **does** receive a server `error` frame (`RelayErrorException`), whose `message` is attacker-influenced.

**Findings:**

- **[Trust boundaries]** No findings — two explicit boundaries, both in `sendArchive`: the `catch (RelayErrorException)` and `catch (IllegalStateException)`. `RelayErrorException.message` is server-supplied (untrusted) and `IllegalStateException.message` is internal; **both are discarded** — the catch bodies call `trySend(Unit)` and never read, map, log, or forward the message. Downstream `ThreadScreen` holds only `Unit` and shows a fixed local resource. Inbound is clean: `ThreadEvent.Archive` is a payload-free `data object`, and `sendArchive` passes the VM's own `SavedStateHandle`-derived `conversationId`, never a screen-supplied id — no untrusted value crosses into the send. This is the single most important control and it is baked into the design, not left to the developer.
- **[Tokens/secrets]** N/A — this slice generates, stores, or rotates no credential; the `archive_conversation` frame carries only the conversation id (already client-held).
- **[File/storage]** N/A — no filesystem, DataStore, or path operations.
- **[Android attack surface]** N/A — no new Activity/Service/Receiver/deep-link/PendingIntent/ContentProvider/WebView. The event originates in-process from a menu tap.
- **[Cryptographic primitives]** N/A — no RNG/crypto/secret-comparison. The frame rides the already-shipped Noise transport below the repo seam (#309/#549); this slice adds none.
- **[Network & I/O]** N/A at this layer — the request/reply send, correlation, timeouts, TLS, and frame-size caps live in the #549 remote impl / #309 pump below the repo seam. The one new inbound-adjacent surface (the `conversation_updated` reply) is decoded **below** the repo seam by the shipped #318 boundary before `archive` returns; a malformed reply throws there (fail-loud) and never reaches this VM as data — `sendArchive` sees only `Unit` on success or a typed throw on failure.
- **[Error messages / logs / telemetry]** No findings — the sole user-visible string is the fixed local resource `archive_failed`; neither caught exception's `message` is read, logged, or shown. No `Log.*`/`println`/Timber on the send or catch path (also enforced by the `android-log-throws-in-plain-jvm-unit-tests` test constraint). Both failures are caught, so neither reaches an uncaught handler / crash reporter carrying server text. No telemetry added.
- **[Concurrency]** No findings — the send is `viewModelScope`-owned (cancelled on screen exit; no application-scope leak). `CancellationException` is rethrown **before** the two typed catches, so teardown mid-archive propagates cleanly and cannot be masked as a handled failure or fire a stray snackbar (tested — § Testing). `archiveErrors` is a **cold** `Channel(BUFFERED).receiveAsFlow()` — per-collector, single `ThreadScreen` subscriber — not a shared hot flow, so a failure signal cannot leak across screens. `trySend` is atomic (no check-then-mutate TOCTOU). `PopBack` sits strictly inside the success continuation, so a failure can never both surface an error **and** navigate.
- **[Threat model alignment]** No findings — the applicable mobile threat is Activity-window text leakage (screenshot / screen-overlay eavesdropping); the fixed-string contract neutralizes it (nothing server-derived to leak even if captured), which is the whole reason AC #4 forbids surfacing `RelayErrorException.message`. Reachability gating (`mutationsSupported`) is named OUT OF SCOPE (§ Reachability gate) — a hidden-but-wired action is strictly more conservative, not a threat; picked up by the family-wide per-mutation-gate milestone (blocks #551).

No MUST FIX and no SHOULD FIX: the discard-the-message contract, log-free path, cancellation-first ordering, and success-only `PopBack` are structural in the design.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10

## Open questions

- None blocking. The per-mutation reachability gate is deferred (§ Reachability gate) to the family-wide milestone; #551 (e2e) remains gated on it.
