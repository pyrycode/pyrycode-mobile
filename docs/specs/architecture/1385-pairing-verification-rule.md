# #1385 — Code-path pairing verification: desktop's wait, failure and retry rule

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `PairCodeViewModel.persist`, `onEvent`, `PairCodeState` — the inline 30 s wait this ticket replaces, and the Pair event that today re-parses on Retry.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen` — the Pair button's enabled/label rule (`state.error != null && codeError == null -> "Retry"`).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingConfirmation.kt` → `confirmPairingAndConnect` — the only save; `controller.connect()` is a no-op while the registry is already foregrounded, so re-saving on Retry never dialled anything new.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `pairingStatus`, `retryHost` — re-collecting treats an initial `DaemonAbsent`/`Offline` as stale: redials, emits `null`. An initial `UpdateRequired` or `PairingRejected` is re-emitted as is.
- `app/src/main/java/de/pyryco/mobile/data/model/RelayLinkStatus.kt` → `RelayLinkStatus` — the statuses the rule classifies.
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModelTest.kt` → `Fixture` — its `status` is a `MutableStateFlow`, so a held `DaemonAbsent` replays on re-collection (unlike the registry). This is why the step itself must ignore a held absence on retry.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenInsetsTest.kt` — Robolectric screen-test setup to mirror.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` — builds `PairCodeState(error = "Pairing saved. Host unavailable…")` and clicks Retry; with no held verification failure that state still labels Retry, so it keeps passing unchanged.
- pyrycode-desktop `src/renderer/src/screens/pairing/pairingState.ts` → `createPairingVerification` (`ignoredAbsence`, `retry`), `PairingScreen.tsx` → `canRetry`, `VerificationFeedback` — the rule mirrored here. Desktop hides the inputs post-save.
- `docs/knowledge/features/pairing-confirm-gate.md` — save happens once, only from Confirm; record never logged.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147

No new visuals: the pair screen keeps its Connecting label and its existing action-area error text slot. Only the error copy, the Pair button's label/enabled rule, and the fields' enabled state after a verification failure change. (The Figma MCP is not authenticated in this run; nothing visual is added, so no node fetch is needed.)

## Context

`persist` ends the wait on `Offline`, reports `PairingRejected`, `DaemonAbsent` and the deadline with one text that always offers Retry, and Retry re-parses, re-confirms and re-saves. Desktop keeps waiting through relay blips, separates rejected from unavailable, and Retry only waits again. The QR path adopts the same step in a sibling ticket, so the rule is extracted as one Android-free function that knows nothing of the pair-code screen.

## Design

### New: `ui/onboarding/PairingVerification.kt` (no Android imports)

```kotlin
internal const val PAIRING_VERIFICATION_DEADLINE_MS = 30_000L

internal sealed interface PairingVerification {
    data object Connected : PairingVerification
    enum class Failure(val message: String, val retryable: Boolean, val code: String) : PairingVerification {
        Unavailable(...), Deadline(...), Rejected(...), UpdateRequired(...)
    }
}

internal suspend fun verifySavedPairing(
    server: PairedServer,
    observe: (PairedServer) -> Flow<ConnectionStatus?>,
    retry: Boolean = false,
): PairingVerification
```

Rule, applied to each status of `observe(server)` within `withTimeoutOrNull(PAIRING_VERIFICATION_DEADLINE_MS)`:

| Status | Outcome |
|---|---|
| relay `Connected` and pyrycode `Connected` | `Connected` |
| `null`, `Idle`, `Connecting`, `Reconnecting`, `Offline`, relay `Connected` with pyrycode not connected | keep waiting |
| `DaemonAbsent` | `Failure.Unavailable` — unless `retry` and no non-`DaemonAbsent` status has been seen yet in this wait (desktop's `ignoredAbsence`) |
| `PairingRejected` | `Failure.Rejected` |
| `UpdateRequired` | `Failure.UpdateRequired` |
| deadline, or the flow completes without a decision | `Failure.Deadline` |

Texts: `Unavailable`/`Deadline` → *"The host is temporarily unavailable. The pairing is saved. Retry to wait again, or Cancel."* (retryable); `Rejected` → *"Pairing rejected. The saved host is retained. Cancel, then pair manually with a fresh code."* (not retryable); `UpdateRequired` → today's *"Pairing saved. This app is too old for this host. Update the app, then retry."* (retryable). The daemon's minimum version is never read.

### `PairCodeState`

Two new fields, both covered by the existing redacted `toString`:

- `saved: PairedServer? = null` — the record saved by Confirm, held while waiting and after a verification failure. Never logged.
- `failure: PairingVerification.Failure? = null` — the held verification failure; `error` carries its message.

### `PairCodeViewModel`

- `persist`: save and name save unchanged. Then `phase = Connecting, saved = server`, and `verify(server, retry = false)`.
- `verify(server, retry)` (private, suspend): calls `verifySavedPairing(server, observe, retry)`; `Connected` → `Complete`; a failure → `phase = Editing, error = failure.message, failure = failure` (keeps `saved`), logs `event=pair_code_failed code=<failure.code>`.
- `onEvent(Pair)` in Editing with a held `failure`: if retryable → `phase = Connecting, error = null, failure = null`, launch `verify(saved, retry = true)` as the `operation` (Back cancels it as today); if not retryable → ignored. No parse, no confirmation, no save.
- `Name`/`Code` are ignored while a `failure` is held (desktop hides inputs post-save); the code-path name/save failures keep today's re-parse Retry because they set no `failure`.

### `PairCodeScreen`

- `verificationFailed = state.failure != null`; field `enabled = editing && !verificationFailed`.
- Pair button `enabled = editing && state.failure?.retryable != false`.
- Label: `"Retry"` when `state.error != null && codeError == null && state.failure?.retryable != false`; a non-retryable failure falls to `"Pair"` (disabled). Cancel unchanged.

## State + concurrency model

One `operation: Job` in `viewModelScope` covers save, name save and the wait, or a Retry's wait. Back cancels it (unchanged). Each wait collects `observe(saved)` afresh, so the registry redials a stale `DaemonAbsent`/`Offline` (`pairingStatus`) and the 30 s deadline is fresh per wait. A Pair while Connecting is ignored (phase is not Editing), so two waits cannot overlap. `verifySavedPairing` runs on the caller's dispatcher; no new scope.

## Error handling

All verification outcomes are values; nothing throws past the step except cancellation. A status flow that completes is treated as the deadline rather than crashing on `first()`. Logs carry only the static failure code.

## Testing strategy

- **New `app/src/test/.../ui/onboarding/PairingVerificationTest.kt`** (`runTest`, virtual time, scripted flows):
  - `Offline` then relay+pyrycode `Connected` → `Connected`; `null`/`Idle`/`Connecting`/`Reconnecting` then `Connected` → `Connected`; relay `Connected` + pyrycode `Down` keeps waiting.
  - `DaemonAbsent` → `Unavailable`, retryable, unavailable text; `PairingRejected` → `Rejected`, not retryable, rejected text; `UpdateRequired("1.4.0")` → `UpdateRequired` with today's text not containing `1.4.0`.
  - Holding `Offline`: still waiting at 29 999 ms, `Deadline` at 30 000 ms with the unavailable text.
  - `retry = true` over a held `DaemonAbsent` keeps waiting; after a different status, a new `DaemonAbsent` fails it.
- **`PairCodeViewModelTest`** — update `failuresRetainDraftAndRetrySameHostWithoutLosingName`, `deadlineAndCancellationCannotNavigateLater`, `rejectedPairingEndsTheConnectionWaitImmediately`, `targetModeRejectedWhileConnectingFailsBeforeTheDeadline` for the new texts and Retry; add:
  - `Offline` during the wait does not end it; then `Connected` completes.
  - After `DaemonAbsent`: Pair → Connecting, no confirmation, `store.saves == 1`, held `DaemonAbsent` does not end the new wait, the new wait's deadline is a fresh 30 s from Retry, then `Connected` completes with `saves == 1`.
  - After `PairingRejected`: Pair and Code events change nothing; Back → Cancelled.
- **New `app/src/sharedTest/.../ui/onboarding/PairCodeScreenVerificationTest.kt`** (Robolectric): with a held `Rejected` failure, the button reads Pair, is disabled, no node reads Retry, Cancel sends Back; with a held `Unavailable` failure, Retry is enabled and sends Pair.
- No rung-3 scenario: the operator-facing pairing flow is already covered by existing harnesses; this ticket changes only failure classification on the post-save wait, which the real daemon cannot be scripted into on demand. Not a new flow.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/pairing-confirm-gate.md` § "Security properties" and `paste-code-dialog.md`/`navigation.md#manual-pairing-entry-and-return` describe the post-save wait; they should record the shared `verifySavedPairing` rule, the three texts, and that Retry re-verifies without re-saving.

## Open questions

- Retry after `UpdateRequired` re-verifies (no second save). The registry re-emits the held `UpdateRequired`, so it fails again at once — identical to today's observable behaviour, since today's re-save's `connect()` is a no-op while foregrounded.

## Security review

Run after the plan's first commit: the `security-sensitive` label was noticed only then. No implementation code existed when this section was added; it is committed before Phase B.

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the step reads only the registry's decoded `ConnectionStatus` and maps it to a static `PairingVerification.Failure` enum. `UpdateRequired.minClientVersion` (daemon-authored) is never read, rendered or logged; all three texts are constants.
- [Tokens] No findings — `PairCodeState.saved` keeps the token-bearing `PairedServer` in memory for the screen's lifetime, as `confirmation` already does. Both `PairCodeState.toString` and `PairedServer.toString` are redacted; logs carry only `Failure.code`. `PairCodeViewModelTest.withVm` already asserts no token or code reaches logs or `state.toString()`. Not retrying `PairingRejected` avoids repeatedly presenting a rejected credential.
- [File / storage] No findings — Retry performs no write: the record is saved once, from Confirm, through `confirmPairingAndConnect`. A Retry observes `pairingStatus(saved)`, which matches the exact saved record; if the stored record were replaced, it emits `null` and the wait ends at the deadline rather than verifying a different host.
- [Android attack surface] No findings — no intents, deep links, providers or WebViews touched.
- [Crypto] No findings — success still requires pyrycode `Connected`, the encrypted-session leg; relay `Connected` alone never completes pairing.
- [Network & I/O] No findings — a Retry re-collects the status flow; the registry redials a stale `DaemonAbsent`/`Offline` once per tap through `retryHost`, keeping the supervisor's backoff. A hostile relay holding `Offline`/`Connecting` is bounded by the 30 s deadline.
- [Logs] No findings — `event=pair_code_failed code=<static>`; no host id, token or status payload.
- [Concurrency] No findings — Retry sets `Connecting` synchronously in `onEvent` before launching, and `Pair` outside Editing is ignored, so two waits cannot overlap. Back cancels the single `operation` job; a cancelled wait writes no state. A flow that completes yields `Deadline` instead of a crash from `first()`.
- [Threat model] OUT OF SCOPE — the QR scanner path's adoption of the same step is the sibling ticket split from #1322.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
