# Spec #561 — Surface the change_workspace result from the thread (no crash, no silent no-op)

**Ticket:** pyrycode-mobile #561 · size **S** · `security-sensitive`
**Split from #533.** Data child #560 (PR #563, `659f3ef`, merged) shipped `RemoteConversationRepository.changeWorkspace` — the real `change_workspace` v2 round-trip (request/reply, awaits `conversation_updated`). This is the **surfacing** UI child: move `onWorkspacePicked` off the silent guard onto a dedicated failure surface. E2e sibling #562 is Inbox (family reachability gate #537).

This ticket is a near-exact clone of the **archive-from-thread** surface (#556), with two structural differences: the trigger is `onWorkspacePicked(path)` (a picker selection carrying a path), not a payload-free overflow event; and success is **passive** (the chip updates via the projection — **no `PopBack`**), so the send twin is `sendArchive`'s two-catch shape **minus** the navigation side effect.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:380-393` — `archiveErrorChannel` + `archiveErrors` flow + KDoc (#556): the exact one-shot payload-free error-flow idiom to clone for `changeWorkspaceErrors`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:592-628` — `sendArchive` (#556): the **two-catch** surfacing twin (`CancellationException` first → `RelayErrorException` → `IllegalStateException`). `sendChangeWorkspace(path)` is this shape **minus the success `PopBack`** and **plus a `path` parameter** (see § Design ①).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:565-590` — `sendNewSession` (#540): the **passive-success** send twin (no `PopBack`). `sendChangeWorkspace`'s success continuation matches this (no side effect); its **catch set** matches `sendArchive` (two types), not `sendNewSession` (one type). The two twins together define `sendChangeWorkspace`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:452-457` — the current `onWorkspacePicked(path)`: `pendingWorkspacePicker.value = false` then `launchGuardedRepoCall { repository.changeWorkspace(conversationId, path) }`. **This is what you replace.** Keep the flag-clear; move the send into `sendChangeWorkspace`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:669-677` — `onOverflowEvent`'s `ThreadEvent.Archive` branch (#556): the "clear the pending flag, then call the private surfacing send" shape `onWorkspacePicked` mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` (whole, 43 lines) — the #490 guard. **Read the KDoc:** the "never log server-supplied `RelayErrorException.message`" confidentiality assertion is why change-workspace must **not** reuse this helper and must not read the caught message. ⚠️ **False-precedent warning** — the ticket's Technical Note says "mirror #556 dropping Archive," but **#556 did not touch this file** (last commit `5e9e41b`, #490); `archive` is still listed at lines 12–13 and 17. There is **no archive-drop to mirror** — do not go hunting for one. Make the change-workspace doc edit directly (§ Design ④), and leave `archive`/`rename` alone (pre-existing debt, out of scope).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` `changeWorkspace` (wired by #560, `659f3ef`) — the request/reply round-trip. Its **throw taxonomy is the load-bearing fact**: `IllegalStateException` (not connected), `RelayErrorException` (server `error`, incl. `protocol.malformed` = empty / out-of-`$HOME` path), `IllegalArgumentException` (`conversation.not_found`), decode exception (malformed reply). Because it **awaits a reply**, a server `error` frame (`RelayErrorException`) is reachable — this is why change-workspace catches **two** relay types, unlike fire-and-forget #540. Full detail: `docs/specs/architecture/560-change-workspace-wire.md` § Error handling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:116-146` — the `modalSendErrors`/`newSessionErrors`/`archiveErrors` params (116-118) + their `LaunchedEffect` snackbar collectors (129-146). `stringResource` is resolved **outside** the `LaunchedEffect` then captured — mirror exactly. Add the 4th param immediately after `archiveErrors` (after `modifier` → satisfies `ComposeParameterOrder` lint).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:371-395` — the `ThreadScreen(...)` call site; `archiveErrors = vm.archiveErrors` (385) is the one new-wire line to mirror. `onWorkspacePicked = vm::onWorkspacePicked` (395) **already carries the event** — no new event wiring.
- `app/src/main/res/values/strings.xml:97-99` — `modal_send_failed` / `new_session_failed` / `archive_failed`; add a sibling `change_workspace_failed`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:929-965` — the multi-failure guard-swallow loop. **Remove the `vm.onWorkspacePicked(...)` line (:955)** and update the comment (§ Testing) — change-workspace no longer routes through the guard, and its new path deliberately does not catch `UnsupportedOperationException`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1002-1106` — the three #556 archive tests: cancellation-inert (`archive_scopeCancellationMidCall_…`, 1002-1031, `GatingArchiveRepo`), not-connected surface (1036-1067), server-error surface (1069-1106). **Your `changeWorkspace_*` tests mirror these**, dropping every PopBack / `navigationEvents` assertion (change-workspace never pops).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1922-1946` — `onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag`: the success/routing test (real `FakeConversationRepository`, asserts cwd folded + picker flag cleared). Stays **green unchanged** — the VM-seam success coverage (AC #1 / AC #5). Verify, don't edit.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/ThrowingConversationRepository.kt` (whole, 47 lines) — the shared decorator; `changeWorkspace` already `throw failWith` (:26-29), so **reuse it verbatim** for the failure tests. **No new throwing double.**
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:2675-2684` — `GatingArchiveRepo` gates only `archive` (its `changeWorkspace` delegates to the fake and would not suspend). Add a sibling `GatingWorkspaceRepo` gating `changeWorkspace` for the cancellation test (keep #556's double untouched).
- Memory lessons that bite: `catch-illegalstate-swallows-cancellation` (catch ordering), `androidtest-not-compiled-by-mandatory-gates` (any component test needs `compileDebugAndroidTestKotlin`), `clone-pattern-default-trap` (the GuardedRepoLaunch false precedent above — verify the ticket's clone claim against the code, don't trust it).

## Context

Picking a workspace from the thread — via the workspace chip (`onWorkspaceChipTapped`) or the "Change workspace…" overflow, both opening the Workspace Picker sheet — calls `ThreadViewModel.onWorkspacePicked(path)`. Today that routes through `launchGuardedRepoCall { repository.changeWorkspace(conversationId, path) }` (`ThreadViewModel.kt:452-456`). Before #560 the repo method threw `UnsupportedOperationException` (guard-swallowed → silent no-op). #560 replaced that stub with the real round-trip, so a failure now produces a real `IllegalStateException` (not connected) or `RelayErrorException` (server `error`, e.g. a path the daemon rejects as outside `$HOME`) — but the guard **still inert-swallows both** (#490), so a failed workspace change is a **silent no-op** today. This slice moves that call off the silent guard onto a dedicated one-shot failure surface, mirroring the archive-from-thread surface (#556).

Two observable outcomes, both established idioms in this VM:

- **Success is passive and list-driven** — `changeWorkspace`'s confirmed `upsertConversation` fold (#560) makes `observeConversations` re-emit with the new `cwd`; the workspace chip derives its label from that projection (`Conversation.workspaceLabel()` ← `cwd`). The VM does **nothing** on success — **no `PopBack`** (the user stays on the thread, the chip just updates). This is the key divergence from `sendArchive`, which pops.
- **Failure surfaces** — a transient snackbar with a **fixed local string**, mirroring `archiveErrors` (#556). Because change-workspace is request/reply, **both** the server-error (`RelayErrorException`) and not-connected (`IllegalStateException`) modes are reachable and both surface the same payload-free `Unit` signal.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (thread — the workspace chip) · https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-2 (Workspace Picker sheet — the trigger)

Node `20-2` is a rounded-`28dp` `surface-container-low` M3 bottom sheet ("Choose workspace" `title-large`, drag handle, `label-large` "Recent"/"Other" section headers, monospace path rows with a `secondary-container` "default" chip, and a "Create new folder…" row) — the affordance that dispatches `onWorkspacePicked(path)`. On success the existing thread **workspace chip** (node `16-8`) re-labels to the new `cwd`; on failure the existing Material 3 `SnackbarHostState` (the #452 / #540 / #556 pattern) shows the fixed failure string. **Both surfaces are locked and unchanged — this ticket wires the existing action; no new visual design.**

## Design

Four production Kotlin files: three additive edits (`ThreadViewModel.kt`, `ThreadScreen.kt`, `MainActivity.kt`) plus one **doc-only** KDoc edit (`GuardedRepoLaunch.kt`). No new files, no new exported types. The new surface is one `private` method, one payload-free one-shot `Flow<Unit>`, and one string resource — plus dropping the now-inaccurate change-workspace mention from the guard's KDoc.

### ① ViewModel — one-shot error flow + `sendChangeWorkspace(path)` (`ThreadViewModel.kt`)

- **Error flow** — add a one-shot channel/flow pair beside `archiveErrors` (:393): private `Channel<Unit>(capacity = Channel.BUFFERED)` `changeWorkspaceErrorChannel` and public `val changeWorkspaceErrors: Flow<Unit> = changeWorkspaceErrorChannel.receiveAsFlow()`. Payload-free `Unit` (same posture as `archiveErrors`/`newSessionErrors`). KDoc: fires once per caught `RelayErrorException` / `IllegalStateException`; success is passive (no signal — the chip updates via the `observeConversations` re-emit from #560's confirmed upsert); the render slice shows a transient snackbar with a **fixed local string**, never an exception message.
- **Send method** — add private `fun sendChangeWorkspace(path: String)`, the surfacing twin of `sendArchive` **minus the `PopBack`**. It `viewModelScope.launch`es `repository.changeWorkspace(conversationId, path)` (the VM's own ctor-derived `conversationId`, **never** re-deriving it; the `path` is the caller-supplied selection — see § Security) and **discards** the vestigial `Session` return (#560 § Design ④). On success it does **nothing else** (no navigation; the chip is list-driven). Catch order (§ Catch contract):
  1. `catch (e: CancellationException) { throw e }` — **MUST be first**.
  2. `catch (e: RelayErrorException) { changeWorkspaceErrorChannel.trySend(Unit) }`.
  3. `catch (e: IllegalStateException) { changeWorkspaceErrorChannel.trySend(Unit) }`.

  The caught `message` is **never** read/logged/forwarded (AC #2 — the server-supplied `RelayErrorException.message` must not reach the surface).
- **Rewrite `onWorkspacePicked(path)`** (:452-457) to keep the picker-flag clear and delegate the send, dropping `launchGuardedRepoCall`:

  ```kotlin
  fun onWorkspacePicked(path: String) {
      pendingWorkspacePicker.value = false
      sendChangeWorkspace(path)
  }
  ```

  `pendingWorkspacePicker.value = false` stays (closes the picker sheet synchronously). The send moves into `sendChangeWorkspace`. There is **no success continuation to move** (unlike archive's `PopBack`) — success is simply the absence of a caught throw.

### Catch contract — why change-workspace catches **two** relay types (like archive, unlike new-session)

`sendNewSession` catches only `IllegalStateException` because `new_session` is fire-and-forget (no awaited reply → no correlated server `error`). **Change-workspace is request/reply** (#560's `changeWorkspace` → `sendAndAwaitReply`, awaits `conversation_updated`), so a server `error` frame is a real, reachable outcome → `RelayErrorException` must be caught and surfaced alongside the not-connected `IllegalStateException`. The ticket's Technical Notes state this ordering explicitly. Both caught types map to the **same** payload-free snackbar signal.

**Deliberately not caught (evidence-based — exact parity with #560's shipped taxonomy and the guard's behavior, no regression):**

- `IllegalArgumentException` (`conversation.not_found`, mapped in #560's `mapError`) — the guard does **not** catch it either, and it is **unreachable** here: you can only change the workspace of the conversation you are currently viewing, whose record is in the list by construction (the same reasoning shipped for `rename` #530, `archive` #556, and `changeWorkspace` #560). Under the old guard path a not_found IAE would crash; under the new path it also crashes — **same behavior, no regression**. Adding a catch would defend an unobserved failure mode.
- The #318 decode exception (malformed reply) — not caught; a malformed reply is a fail-loud protocol violation, exact parity with every other mutation on this VM.
- `UnsupportedOperationException` — change-workspace is now wired (#560); it cannot originate on any real path. Not catching it is why the shared guard test needs an edit (§ Testing).

### ② String resource (`strings.xml`)

Add one string beside `archive_failed`:

```xml
<string name="change_workspace_failed">Couldn\'t change the workspace. Try again.</string>
```

Fixed local text — covers both failure modes (daemon error, incl. a rejected out-of-`$HOME` path, and disconnected), so like `archive_failed` it does **not** say "check your connection". Never derived from an exception message (§ Security). (`strings.xml` is a resource, not a production `.kt` file.)

### ③ ThreadScreen — fourth snackbar collector (`ThreadScreen.kt`)

- New composable param `changeWorkspaceErrors: Flow<Unit> = emptyFlow()`, placed **immediately after `archiveErrors`** (:118). It sits after `modifier`, satisfying `ComposeParameterOrder` lint (the `compose-parameter-order-lint-defaulted-after-modifier` lesson); defaulted → the ~9 existing `ThreadScreen(...)` call sites (androidTest + preview) stay valid — **no edit fan-out**.
- Resolve `val changeWorkspaceFailedMessage = stringResource(R.string.change_workspace_failed)` at composable scope, then a fourth `LaunchedEffect(changeWorkspaceErrors, snackbarHostState) { changeWorkspaceErrors.collect { snackbarHostState.showSnackbar(changeWorkspaceFailedMessage) } }` — a structural clone of the `archiveErrors` collector (:143-146), reusing the same `snackbarHostState`.

### ④ MainActivity wiring (`MainActivity.kt`)

One line in the `ThreadScreen(...)` call, beside `archiveErrors = vm.archiveErrors` (:385): `changeWorkspaceErrors = vm.changeWorkspaceErrors`. `onWorkspacePicked = vm::onWorkspacePicked` (:395) already carries the trigger — no other change.

### ⑤ GuardedRepoLaunch KDoc — drop the change-workspace mention (doc-only)

Change-workspace no longer routes through `launchGuardedRepoCall`, so its two references in the guard's KDoc are now inaccurate. Make a **minimal, doc-only** edit:

- Line 12–13 action list: remove `change-workspace,` from `(send, create-discussion, change-workspace, archive/rename/delete/promote)`.
- Line 17 UOE example: remove `changeWorkspace` from `(`archive` / `rename` / `changeWorkspace`)` (also stale — #560 wired it, so it no longer throws `UnsupportedOperationException`).

**Do not touch `archive`/`rename` or the guard's code** — the `archive` staleness is pre-existing #556 debt (out of scope; #556 chose not to touch this file), and the catch bodies, catch ordering, and the `IllegalArgumentException`-not-caught comment are all load-bearing and unchanged. This is the ticket's "drop change-workspace from the documented action list" note, applied to the two change-workspace-specific mentions only.

## State + concurrency model

- No new `StateFlow`. `changeWorkspaceErrors` is a `Channel(BUFFERED).receiveAsFlow()` **cold** flow, collected once by `ThreadScreen` — identical lifecycle to `archiveErrors` / `newSessionErrors`.
- `sendChangeWorkspace` runs on `viewModelScope` (Main dispatcher via the default context); the request/reply send + IO live below the repository seam (#560 / #309 pump). No dispatcher switch in the VM.
- Cancellation: `viewModelScope` teardown on screen exit cancels an in-flight change-workspace; the `CancellationException`-first rethrow lets structured cancellation propagate cleanly — no error surfaced, no crash (AC #4).
- `trySend` on a `BUFFERED` channel never suspends and never fails under normal load; a dropped signal at worst omits one snackbar — acceptable, matches `archiveErrors`.
- **Single source of state** unchanged — the workspace chip reads the conversation projection folded by #560's `upsertConversation`; this slice adds no VM fold and no parallel state (`two-folds-repo-wins-vm-dormant` — the repo fold is canonical).

## Error handling

| Failure mode | Origin | VM behavior | UI surface |
|---|---|---|---|
| Server `error` reply (incl. `protocol.malformed` = out-of-`$HOME` / empty path) | `RelayErrorException` from #560's `sendAndAwaitReply` | `changeWorkspaceErrorChannel.trySend(Unit)`; nothing else | Transient snackbar, `change_workspace_failed` |
| Not connected | `IllegalStateException` (repo `live` path / pump not Open) | `changeWorkspaceErrorChannel.trySend(Unit)`; nothing else | Transient snackbar, `change_workspace_failed` |
| Scope teardown mid-call | `CancellationException` (extends ISE) | rethrown (first catch) | none (inert) |
| `conversation.not_found` | `IllegalArgumentException` | not caught — unreachable (changing the viewed conversation) | n/a (parity with guard / #560 / #556) |
| Malformed reply | #318 decode exception | not caught — fail-loud protocol violation | n/a (parity with guard) |
| **Success** | reply decodes + confirmed-upsert (#560) | nothing (no `PopBack`) | chip re-labels via `observeConversations` re-emit (list-driven, AC #1) |

## Testing strategy

Unit (`testDebugUnitTest --tests "…ThreadViewModelTest"`, `runTest`). Reuse existing doubles — one new **gating** double only.

**New tests (add) — mirror the #556 archive tests, dropping every PopBack / `navigationEvents` assertion (change-workspace never pops):**

- **Not-connected failure surfaces + stays on thread + no crash** *(AC #3 — disconnected ISE)* — `makeVm(handle, ThrowingConversationRepository(IllegalStateException("not connected")))`; collect `changeWorkspaceErrors`; install a default uncaught-exception handler (the `viewModelScope` `SupervisorJob` means a leaked throw would **not** fail `runTest` — the handler capture is the only proof the catch ran); call `vm.onWorkspacePicked("…")`; `advanceUntilIdle()`. Assert: `changeWorkspaceErrors` emitted **exactly one** `Unit`; uncaught list empty.
- **Server-error failure surfaces + message never leaks** *(AC #2 — proves the change-workspace-specific `RelayErrorException` catch and the confidentiality property)* — same shape with `ThrowingConversationRepository(RelayErrorException(code = "server.error", retryable = false, message = "no"))`. Assert `changeWorkspaceErrors` emits one `Unit`, no crash; the server `message` never reaches the surface (the `Unit` signal carries no text — the fixed string is shown).
- **Cancellation inert — no signal, no crash** *(AC #4)* — add a sibling gating double `GatingWorkspaceRepo(gate, entered)` (mirror `GatingArchiveRepo` at :2675, override `changeWorkspace` to `entered.complete(Unit); gate.await()`); drive `onWorkspacePicked("…")`, assert the call is in-flight, then clear the `ViewModelStore` to cancel `viewModelScope`; assert `changeWorkspaceErrors` stayed **empty** and no uncaught throw fired. Proves the `CancellationException`-first ordering (teardown is never mis-surfaced as a change-workspace failure).

**Existing tests to edit:**

- **Multi-failure guard-swallow loop** (`:929-965`) — **remove the `vm.onWorkspacePicked("pyry-workspace/app")` line (:955)** and update the comment (:931-936) to note change-workspace, like archive since #556/#561, routes through its own surfacing path (`sendChangeWorkspace`), which does not catch `UnsupportedOperationException`; leaving the call in would let the `UnsupportedOperationException` iteration (:947) escape as an uncaught throw and fail the `uncaught.isEmpty()` assertion. The remaining actions (`sendMessage` / `DeleteConfirm` / `RenameSubmit` / `SaveAsChannelSubmit`) still drive the guard against all three types, so guard coverage stays intact.

**Existing tests that stay green unchanged (verify, don't edit):**

- `onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag` (`:1922-1946`) — success/routing over a real `FakeConversationRepository`: the new path still calls `changeWorkspace` once, clears the picker flag synchronously, and the fake folds the new `cwd` (asserted). This is the VM-seam **success** coverage (AC #1 / AC #5 "component coverage asserts the success chip update"). It must remain green.
- `onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace` (`:1948+`) — unaffected; dismiss never sends.

**Component snackbar test** — optional, consistent with #540/#556 (which added no androidTest for their error collectors; the collector is a structural clone already proven for the modal path). If added to `ThreadScreenModalTest.kt`, it is **not** compiled by the mandatory `test`/`lint`/`assembleDebug` gates — verify with `compileDebugAndroidTestKotlin` (`androidtest-not-compiled-by-mandatory-gates` lesson).

## Security review

**Verdict:** PASS

Adversarial self-review per `architect/security-review.md`. The load-bearing threat is the #452/#490/#540/#556 confidentiality posture: server-supplied or exception-derived text must never reach the un-secured Activity window the snackbar draws in. This slice inherits #556's request/reply stakes (a server `error` frame's `message` is attacker-influenced) **and** adds one boundary #556/#540 did not have — a caller-supplied `path` crossing into the send — so that crossing is examined explicitly below.

**Findings:**

- **[Trust boundaries]** No findings — three crossings, all contained. **(a) Outbound path:** `onWorkspacePicked(path)` passes a caller-supplied filesystem path into `changeWorkspace(conversationId, path)`. Unlike #556/#540 (which pass only the VM's own id), this **is** a screen-supplied value — but it is the *user's own picker selection*, forwarded **verbatim** as a wire field to #560's already-secured `changeWorkspace`; the phone **never touches the filesystem with it** and never validates/canonicalises it. `$HOME` confinement is the daemon's job (#560's `change_workspace.go`, fail-closed, stores the resolved realpath), which re-validates and rejects out-of-`$HOME` paths as `protocol.malformed`. This slice adds **no new path handling** — it routes the same #560 call. The `conversationId` is still the VM's own `SavedStateHandle`-derived id, never screen-supplied. **(b) Inbound failure:** both `catch` bodies call `trySend(Unit)` and **discard** the caught exception — `RelayErrorException.message` (server-supplied/untrusted) and `IllegalStateException.message` (internal) are never read, mapped, logged, or forwarded; `ThreadScreen` holds only `Unit` and shows a fixed local resource. **(c) Inbound reply:** the success `conversation_updated` is decoded below the repo seam by #560's #318 boundary before `changeWorkspace` returns; a malformed reply throws there (fail-loud) and never reaches this VM as data.
- **[Tokens/secrets]** N/A — no credential generated, stored, or rotated; the `change_workspace` frame carries the conversation id (client-held) + the user-picked path.
- **[File/storage]** N/A on the mobile side — the security-relevant point: the `path` is a wire string only, never opened, `File`-wrapped, canonicalised, or concatenated into a path on the phone. Path traversal / TOCTOU / storage-scope are server-side concerns the daemon owns (#560).
- **[Android attack surface]** N/A — no new Activity/Service/Receiver/deep-link/PendingIntent/ContentProvider/WebView. The event originates in-process from a picker tap; the affordance is additionally `mutationsSupported`-gated (dormant until #537/#562).
- **[Cryptographic primitives]** N/A — no RNG/crypto/secret-comparison. The frame rides the already-shipped Noise transport below the repo seam (#309/#560); this slice adds none. `conversationId` is matched by ordinary identity in #560's fold (a routing key, not a secret).
- **[Network & I/O]** N/A at this layer — the request/reply send, correlation, timeouts, TLS, and frame-size caps live in #560 / the #309 pump below the repo seam. #488's `failAllPending` guarantees a change-workspace in flight at teardown fails promptly (ISE, surfaced) rather than hanging.
- **[Error messages / logs / telemetry]** No findings — the sole user-visible string is the fixed local resource `change_workspace_failed`; neither caught exception's `message` is read, logged, or shown. No `Log.*`/`println`/Timber on the send or catch path (also enforced by the `android-log-throws-in-plain-jvm-unit-tests` constraint). Both failures are caught, so neither reaches an uncaught handler / crash reporter carrying server text or the picked path. The daemon returns fixed static error strings and never echoes the path (#560 § Security). No telemetry added.
- **[Concurrency]** No findings — the send is `viewModelScope`-owned (cancelled on screen exit; no application-scope leak). `CancellationException` is rethrown **before** the two typed catches, so teardown mid-call propagates cleanly and cannot be masked as a handled failure or fire a stray snackbar (tested — § Testing). `changeWorkspaceErrors` is a **cold** `Channel(BUFFERED).receiveAsFlow()` — per-collector, single `ThreadScreen` subscriber — not a shared hot flow, so a failure signal cannot leak across screens. `trySend` is atomic (no check-then-mutate TOCTOU). There is no success side effect that could race a failure (no `PopBack` to fire-and-navigate).
- **[Threat model alignment]** No findings — the applicable mobile threat is Activity-window text leakage (screenshot / screen-overlay eavesdropping); the fixed-string contract neutralizes it (nothing server-derived to leak even if captured), which is why AC #2 forbids surfacing `RelayErrorException.message`. A hostile relay folding an attacker-chosen `cwd` into the projection is a **display-only** effect (the chip shows a path) — it grants no filesystem access on the phone (mobile never uses the path) and the authoritative write is the daemon's, `$HOME`-confined (#560). Reachability gating (`mutationsSupported`) is named OUT OF SCOPE (§ deferred below) — a hidden-but-wired action is strictly more conservative, not a threat; picked up by the family-wide per-mutation-gate milestone (blocks #562).

No MUST FIX and no SHOULD FIX: the discard-the-message contract, the never-touch-the-path-on-device posture (path handling is entirely #560's already-reviewed wire field), the log-free path, and the cancellation-first ordering are structural in the design.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-10

## Open questions

None blocking. Deliberate deferrals, all named:

- **Reachability gate.** Against the real relay the picker / "Change workspace…" affordance sits behind `mutationsSupported == false` (the #537 family gate at `ThreadOverflowMenu.kt:49` / `ChannelInfoSheet.kt:118`; `RemoteConversationRepository.mutationsSupported == false`), so a live user cannot yet reach this path — it is exercised via fakes in tests, not the live UI. **This ticket does not flip that gate** (per #540/#556's identical decision). Un-gating is a family-wide per-mutation decision tracked separately; #562 (e2e) remains gated on it.
- **`GuardedRepoLaunch.kt` `archive`/`rename` KDoc staleness** — pre-existing #556/#530 debt (both moved off / were wired but remain listed), left untouched (out of scope; §① Design ⑤).
- **Operator-facing rung-3 real-claude e2e** — filed separately as **#562** (Inbox), parked behind the family per-mutation `mutationsSupported` reachability gate (#537 / #551).
