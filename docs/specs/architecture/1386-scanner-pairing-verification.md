# 1386 — Report a QR pairing done only after the host answers

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingVerification.kt` → `verifySavedPairing`, `PairingVerification.Failure` — the shared step from #1385; owns the statuses, failure texts and `retryable`. Used as-is.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `PairCodeViewModel.persist` / `verify` / `onEvent` — the code path's save → wait → retry shape this ticket mirrors (one held `Job`, `confirmPairingAndConnect` with a flag-setting `onPersisted`, `RelayLog` content-free events).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` → `ScannerViewModel`, `ScannerUiState`, `ScannerEvent` — today a synchronous state machine; gains the wait.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingConfirmation.kt` → `confirmPairingAndConnect` — reused unchanged; its KDoc says `onPersisted` navigates, which stops being true for every caller.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` → `ScannerScreen`, `PairingConfirmContent` — the confirm modal that now also renders the wait and the failure.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`, the `Routes.SCANNER` composable, `confirmPairAndNavigate`, `SAVE_FAILED_MSG` — the route that navigates straight away today.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ScannerViewModel` and `PairCodeViewModel` bindings — the latter shows `registry::pairingStatus` wiring.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `pairingStatus` (internal) — the per-record status flow.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`, `MobileGateModal`, `MobileReadOnlyModal` — `MobileModal` always renders a submit; `MobileGateModal` already omits it on a null `submitLabel`.
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModelTest.kt` → `withVm`, `Fixture`, `Store` — the JVM fixture pattern (StandardTestDispatcher as Main, RelayLog sink, scripted `MutableStateFlow<ConnectionStatus?>`).
- `app/src/test/java/de/pyryco/mobile/ui/onboarding/ScannerViewModelTest.kt`, `QrCodeAnalyzerTest.kt` — 12 no-arg `ScannerViewModel()` constructions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenVerificationTest.kt` — the screen-test shape for the held failure's button sets.
- `docs/knowledge/features/scanner-screen.md`, `pairing-confirm-gate.md` § "Camera double-confirm is not guarded", `paste-code-dialog.md` § verification, `development-verification.md` § "Where a screen test goes".

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2559 (verification content) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369 (modal shell)

No new visuals (the ticket's own statement): the existing Pair modal — fingerprint box plus the desktop comparison instruction in the `MobileModal` shell — stays up; the wait uses the shell's existing `loading` state (spinner in a disabled submit) and a failure uses its existing `error` slot above the footer. The Figma MCP was not authenticated in this run, so the nodes were not re-fetched; nothing in this ticket draws a new surface.

## Context

The scanner route's `confirmPairingAndConnect` → `onPersisted` navigates to the channel list immediately, so a QR code for an unreachable or rejecting host looks paired. #1385 extracted the verification rule into `verifySavedPairing` and wired it into `PairCodeViewModel`; this ticket uses the same step from the scanner. No naming step on the QR path.

**Where the wait lives: `ScannerViewModel`.** It must be JVM-testable (AC 1, AC 3) and must survive rotation — a `rememberCoroutineScope` job in the route is cancelled on a configuration change, which would strand the modal in its loading state for a 30 s wait. The VM therefore stops being purely synchronous and takes `PairedServerStore`, `RelayConnectionController` and `observe: (PairedServer) -> Flow<ConnectionStatus?>` in its constructor, exactly like `PairCodeViewModel`.

**Sizing overage, stated.** The constructor change touches 12 test constructions (`ScannerViewModelTest` 8, `QrCodeAnalyzerTest` 4) plus the DI binding: 13 call sites against the boundary of 10. Splitting would produce a "change the constructor" slice whose only consumer is this ticket — the floor rule merges it back. Each test file gets one private factory, so the edit is one replacement per file. All other numbers hold: 6 production files, no new top-level types (7 new cases of the two existing sealed types), 3 AC, 4 reject branches.

**Overlap.** `MainActivity.kt` is also touched by in-flight #1311, #1325, #1327 (thread route) and `AppModule.kt` by #1330 (notifications); all different blocks — building through.

## Design

### `ScannerUiState` — new cases

- `Verifying(fingerprint: String, server: PairedServer)` — saving, then waiting on the saved record. Renders the confirm modal in its loading state.
- `VerificationFailed(fingerprint: String, server: PairedServer, failure: PairingVerification.Failure)` — the step's failure is held; `server` is the exact saved record Retry re-verifies. Declared `internal` because `PairingVerification` is internal.
- `Paired` — the step reported `Connected`; the route navigates to the channel list.
- `Cancelled` — Cancel/Back during the wait or after a failure; the route pops the scanner.

`fingerprint` is carried so the modal keeps rendering the same content without re-deriving. No custom `toString`: `PairedServer.toString` redacts the token, the fingerprint is public. The server lives only in VM memory — never `rememberSaveable`, never logged.

### `ScannerEvent` — new cases

- `ConfirmPairing` — replaces the route-scope confirm callback. Valid only from `AwaitingConfirm`.
- `RetryVerification` — valid only from `VerificationFailed` whose `failure.retryable`.
- `CancelVerification` — valid only from `Verifying` / `VerificationFailed`.

`DeclinePairing` is unchanged (AwaitingConfirm → ReadyToScan, nothing saved).

### `ScannerViewModel`

```kotlin
class ScannerViewModel(
    private val store: PairedServerStore,
    private val controller: RelayConnectionController,
    private val observe: (PairedServer) -> Flow<ConnectionStatus?>,
) : ViewModel()
```

- `ConfirmPairing` from `AwaitingConfirm(fp, server)`: state → `Verifying(fp, server)` **synchronously** (a second tap is a no-op — closes the "camera double-confirm is not guarded" limitation as a side effect), then `operation = viewModelScope.launch { persist(...) }`. `persist` calls `confirmPairingAndConnect` with `onPersisted = { saved = true }`; a store failure → `Error(SAVE_FAILED_MSG)` (unchanged copy, constant moves from `MainActivity.kt`). On save, `verify(server, retry = false)`.
- `verify(server, retry)` awaits `verifySavedPairing(server, observe, retry)`. `Connected` → `Paired`; a `Failure` → `VerificationFailed(fp, server, failure)`. Each result is applied only while the state is still `Verifying` for that server (belt to the job cancel, so a late resume can never navigate or overwrite `Cancelled`).
- `RetryVerification`: state → `Verifying(fp, server)`, launch `verify(server, retry = true)`. No save, no `connect()`.
- `CancelVerification`: `operation?.cancel()`, state → `Cancelled`. The saved host is kept (nothing is deleted).
- Every other event keeps today's synchronous transition. Events arriving in `Verifying`/`VerificationFailed`/`Paired`/`Cancelled` other than the three above are ignored so a stray camera/permission callback cannot knock the modal away mid-wait.

### `MobileModal`

`submitLabel: String? = "OK"`: a null label omits the submit button, mirroring `MobileGateModal`. Existing callers pass a non-null label or the default; nothing else changes.

### `ScannerScreen`

`PairingConfirmContent` gains `cancelLabel`, `submitLabel: String?`, `loading`, `error` and passes them to `MobileModal`. `ScannerScreen` gains `onRetryPairing` and `onCancelPairing` (defaulted `{}` like the existing callbacks):

| State | Cancel label → callback | Submit | loading | error |
|---|---|---|---|---|
| `AwaitingConfirm` | Don't pair → `onDeclinePairing` | Confirm pairing → `onConfirmPairing` | no | — |
| `Verifying` | Cancel → `onCancelPairing` | Confirm pairing (disabled, spinner) | yes | — |
| `VerificationFailed`, retryable | Cancel → `onCancelPairing` | Retry → `onRetryPairing` | no | `failure.message` |
| `VerificationFailed`, not retryable | Cancel → `onCancelPairing` | none | no | `failure.message` |

The modal's dismiss request (close glyph, dialog Back) maps to the same callback as its Cancel. `Paired` and `Cancelled` render the viewport for the one frame before the route navigates (the camera is gated on `ReadyToScan`, so it does not rebind).

### Route (`Routes.SCANNER` in `PyryNavHost`)

- Drops `koinInject<PairedServerStore>`, `koinInject<RelayConnectionController>`, `rememberCoroutineScope` and `confirmPairAndNavigate`.
- `onConfirmPairing = { vm.onEvent(ConfirmPairing) }`, `onRetryPairing`, `onCancelPairing` likewise.
- `LaunchedEffect(state)`: `Paired` → navigate to `CHANNEL_LIST` with `popUpTo(SCANNER) { inclusive = true }` + `launchSingleTop` (today's options); `Cancelled` → `popBackStack()`.
- `BackHandler`: `AwaitingConfirm` → `DeclinePairing` (unchanged); `Verifying` / `VerificationFailed` → `CancelVerification`.

### DI

`viewModel { val registry = get<RelayConnectionRegistry>(); ScannerViewModel(get(), registry, registry::pairingStatus) }` — `get<PairedServerStore>()` is the store the route injected before.

### `confirmPairingAndConnect`

Unchanged behaviour. One KDoc sentence: `onPersisted` no longer navigates for any caller; the reason connect precedes it becomes "the caller's continuation (the verification wait) must observe a started loop".

## State + concurrency model

- One `operation: Job?` in `viewModelScope` (Main), covering save + wait or the retry wait. Cancelled by `CancelVerification` and by `onCleared` (route popped, including system Back with the dialog gone).
- `observe(server)` is the cold-to-the-caller `pairingStatus` flow; `verifySavedPairing` owns the 30 s deadline.
- The VM survives rotation, so the wait and the modal survive it too.

## Error handling

| Failure | Result |
|---|---|
| `PairedServerStoreException` on save | `Error(SAVE_FAILED_MSG)` as today; no `connect()`, no wait |
| Step reports a `Failure` | `VerificationFailed`; message in the modal's error slot; Retry only when `retryable` |
| Cancel/Back mid-save | job cancelled; the save may or may not have completed — either way nothing is deleted and the route pops |

Logs (`RelayLog`, content-free): `scanner_pair_save_started`, `scanner_pair_connection_wait`, `scanner_pair_connected`, `scanner_pair_failed code=<save_failed|failure.code>`, `scanner_pair_retry`, `scanner_pair_cancel`. Never the server, token, fingerprint or status payload.

## Testing strategy

JVM, `ScannerViewModelTest` (fixture mirrors `PairCodeViewModelTest.withVm`: StandardTestDispatcher as Main, fake store counting saves, fake controller counting connects, scripted `MutableStateFlow<ConnectionStatus?>`, RelayLog sink checked for the token):

- Confirm → `Verifying` at once; relay Connected with pyrycode Down → still `Verifying` (no `Paired`); both Connected → `Paired`; one save, one connect, `observe` got the exact parsed record. (AC 1)
- Double `ConfirmPairing` → one save.
- `DaemonAbsent` → `VerificationFailed(Unavailable)`; Retry → `Verifying` without returning to `AwaitingConfirm`; then Connected → `Paired`; still one save, one connect. (AC 3)
- `PairingRejected` → `VerificationFailed(Rejected)`; Retry ignored (not retryable).
- Cancel during the wait → `Cancelled`; a later Connected does not produce `Paired`; the save remains. Cancel after a failure → `Cancelled`. (AC 3)
- Store failure → `Error(SAVE_FAILED_MSG)`, no connect.
- Existing transition tests keep passing via a `vm()` factory.

Shared screen test, `ScannerScreenTest` (Robolectric): `VerificationFailed` retryable shows the step's message plus Retry and Cancel, each routed to its callback; non-retryable shows the message and Cancel only, no Retry and no Confirm pairing. `Verifying` shows Cancel enabled and the submit disabled. (AC 2)

No rung-3 scenario in this ticket: pairing a fresh QR against the live daemon is not exercised by the interactive-stream harness (it pairs by its own fixture), and the scanner needs a camera. Flagged in the PR for the verifier's judgement.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/scanner-screen.md` (confirm path now waits; new states and events; double-confirm guarded) and `pairing-confirm-gate.md` § "Camera double-confirm is not guarded" (now guarded), plus `mobile-modal.md` (nullable `submitLabel` on `MobileModal`).

## Open questions

- "Retry waits again … without showing the fingerprint again" — read as: Retry does not return to the confirmation step or ask for a second confirm. The modal keeps its fingerprint content throughout the wait and the failure because the ticket says the confirmation modal "stays up"; it is not a new prompt.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the payload boundary (`parsePairingPayload` → `serverKeyFingerprint` → `AwaitingConfirm`) is unchanged. `ConfirmPairing` saves the `server` held in the VM's own `AwaitingConfirm` state, so the saved record stays bound to the displayed fingerprint (tighter than today's route lambda reading collected state). The only text rendered in the new states is `PairingVerification.Failure.message`, an app constant; no daemon- or relay-authored text (the `UpdateRequired` minimum is not echoed) reaches Compose.
- [Trust boundaries] No findings — success means both legs Connected, and the pyrycode leg only reaches Connected after a Noise IK handshake against the QR's server static key, so an on-path relay cannot forge success. It can forge a failure status (`DaemonAbsent`, `PairingRejected`); the effect is a shown failure with the host retained — denial of service only, same as the code path.
- [Tokens] SHOULD FIX — the new states carry `PairedServer` (with the token) in VM memory for up to the 30 s wait and while a failure is held. `PairedServer.toString` redacts (`PairedServer([REDACTED])`), so the data-class `toString` of `Verifying`/`VerificationFailed` is safe; Phase B asserts that a state's `toString` and every `RelayLog` line in the JVM test contain neither the token nor the fingerprint. The record never enters `rememberSaveable` or `SavedStateHandle`.
- [Storage] No findings — the save goes through the existing Keystore-wrapped `PairedServerStore.save` via the unchanged `confirmPairingAndConnect`; nothing new is written. Cancel never deletes the saved host (the ticket's contract).
- [Android surface] No findings — no new intent, deep link, pending intent or WebView. The modal remains the existing `MobileModal`; overlay/tapjacking on Confirm is the known gap in `pairing-confirm-gate.md` and is OUT OF SCOPE here (unchanged by this ticket).
- [Crypto] No findings — no primitive touched.
- [Network & I/O] No findings — `connect()` is the existing idempotent supervisor start; the 30 s deadline belongs to `verifySavedPairing`. Cancel stops only the wait; the supervisor keeps serving the saved host, as on the code path.
- [Logs] No findings — new logs go through `RelayLog` (debug-gated by `BuildConfig.DEBUG`) and carry only static event names and `failure.code`. The route's `Log.w` with the exception class name moves to a static `code=save_failed`.
- [Concurrency] MUST FIX folded into the design before commit — the route's `LaunchedEffect(Unit)` re-sends `PermissionGranted` after a rotation; under today's unconditional transition that would replace `Verifying` with `ReadyToScan`, hiding the modal while the job keeps running. The design ignores every event other than Retry/Cancel while `Verifying`/`VerificationFailed` and ignores everything in `Paired`/`Cancelled`; Phase B covers it with a JVM test (PermissionGranted during the wait leaves `Verifying`, and Connected still yields `Paired`).
- [Concurrency] No findings — one `Job` on `viewModelScope` (Main); `CancelVerification` and `onCleared` cancel it; results are applied only while the state is still `Verifying` for the same server, so a late resume cannot navigate after Cancel. `ConfirmPairing` moves to `Verifying` synchronously, so a double tap saves once.
- [Threat model] OUT OF SCOPE — a process death mid-wait loses the wait (the host stays saved; the next launch connects through the normal driver). No ticket needed: the user-visible result equals a Cancel.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
