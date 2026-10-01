# Scanner screen — edge cases and testing

Split from [Scanner screen](scanner-screen.md) on 2026-10-01 to keep that document
under the 50000-byte cap the docs guard enforces. This covers `## Edge cases /
limitations` and its `### Focused verification` subsection, moved verbatim with
their headings and anchors intact.

## Edge cases / limitations

- **Back and paste are separate actions.** The toolbar calls `popBackStack()`; paste opens `pair_code`. Neither action confirms a pairing.
- **Process-death drops the resolved `Denied`/`ReadyToScan` state.** `requested` is `rememberSaveable` (survives process death) but the resolved state lives only in the VM (does **not**). After process death on the scanner with permission previously denied, `LaunchedEffect(Unit)` sees not-granted + `requested == true` → no branch fires → the viewport shell renders instead of `Denied`. Benign — the paste fallback still completes onboarding. The **on-resume permission re-check is still deferred** (a later pairing-polish ticket): grant-in-settings-then-return stays in `Denied`/shell until re-entering the scanner. Paste remains usable after returning from Settings; leaving and re-entering with permission granted starts the preview. The #640 frame update preserves this behavior. (Non-blocking NIT carried from #326.)
- **Camera success retains Welcome underneath.** Only `Scanner` is popped, so Back from the channel list can return to Welcome. A cold start uses the [saved collection](navigation.md#how-it-works) to select its initial destination. Manual pairing instead clears prior graph entries after target readiness; see [return rules](navigation.md#manual-pairing-entry-and-return).
- **Static scan-line.** The glow marks the scan area without animation; no `rememberInfiniteTransition` runs.
- **Radial gradients are circular, not elliptical.** Figma's SVG payload uses a `gradientTransform` matrix that produces an *elliptical* radial. Compose's `Brush.radialGradient` is circular only; matching the ellipse exactly requires a wrapping `Modifier.scale(...)` Box. The circular approximation reads identically as atmospheric haze and is what shipped — parity-of-intent, not pixel-identity of the SVG matrix.
- **The production design target uses the fixed dark theme.** Earlier #640
  light/dark fixture captures remain historical; #1213 compares the dark
  412×892 frame and checks compact 360×640 and 1.5× text. Stripes remain
  subtle (`onSurface` at 4% opacity); layout has no system-theme branch.
- **Live camera acceptance remains separate from fixture coverage.** A fake
  preview slot proves layout and callback wiring, not CameraX binding or QR
  capture. `InteractiveStreamE2ETest` begins with injected pairing credentials:
  its [eight-test live pass for #640](https://github.com/pyrycode/pyrycode-mobile/issues/640#issuecomment-5756259017)
  does not prove real camera/QR → fingerprint → confirmed pairing against real
  daemons/live relay — that stays [#676](https://github.com/pyrycode/pyrycode-mobile/issues/676)'s
  scope. Scanner → manual pairing against real daemons/live relay is proven by #847
  (`interactiveTurn_twoHostsCollidingConversationId_stayPerHost`, pairing a second
  host by code through this screen's paste-code fallback), and second-host
  management — rename and unpair from the Edit host modal, with the first host left
  untouched — is proven by
  [#1085](https://github.com/pyrycode/pyrycode-mobile/issues/1085)'s
  `interactiveTurn_secondHostRenameAndUnpair_leavesFirstHostUntouched`. See
  [Camera preview](camera-preview.md) and the existing
  [live gate](../../e2e-interactive-stream.md#pre-ship-gate); #640 adds no rung-3
  scenario or rung-4 twin. The #1386 save-then-wait behavior is proven on the JVM
  and by `ScannerScreenTest`, not by a rung-3 scenario either — pairing a fresh QR
  against the live daemon needs a camera the interactive-stream harness doesn't
  drive. [#1394](https://github.com/pyrycode/pyrycode-mobile/issues/1394) (the
  #481/#482 shape) is the follow-up: inject the host-B payload into the scanner
  VM from the harness, confirm, and wait for the channel list under `needs-real-claude`.
- **`ScannerScreen.kt` is foundational, not disposable.** The `ScannerViewModel` state machine, the `when(state)` renderer, and `ScannerViewport` were **consumed** by #334 (the live preview injected through the `cameraPreview` slot, `CameraError` produced on bind failure), not replaced. `Routes.SCANNER` stays a single destination.
- **Camera double-confirm is guarded, since #1386.** `ConfirmPairing` moves state to `Verifying` synchronously before the save launches, so a fast double-tap on Confirm saves once — closing the gap [Pairing confirm gate](pairing-confirm-gate.md#edge-cases-and-limitations) used to carry as open.

### Focused verification

`ScannerScreenTest` covers the Pairing title, permission/decoded viewport shells,
denial/error fallbacks, fake camera slot and fingerprint display, accessibility,
confirmation callbacks and 48 dp actions. `ScannerFrameTest` exercises 412×892 and
360×640 dp in light and dark themes, asserting divider/camera presence, clickable
Back/Paste, square reticle bounds, the centered 248 dp reference reticle, and
separation between the reticle, helper card, camera window and paste action.
The #1213 fixture also checks root atmospheric pixels and 1.5× text at
360×640 dp. Its Scaffold padding must be consumed before the screen applies
`systemBarsPadding()`; otherwise a screenshot can show doubled system insets
while layout assertions still pass.

`ScannerScreenTest.denied_backReturnsToCaller` exercises Back through the denied
branch, checking “Pair with pyrycode”, the “Back” description, a minimum 48×48 dp
target and exactly one Back callback. A direct child-screen test would not catch
`ScannerScreen` dropping the callback. Shared denied-screen tests separately
verify settings/paste dispatch and visible versus touch heights. The
[real permission-denial regression and retained activity evidence](scanner-denied-screen.md#testing)
cover app settings, paste/cancel, Back to Welcome and an empty pairing store on
full API 35; forced state tests do not establish that permission route.

Measure **the helper card's bounds**, not only its text: a reticle-to-text check
can pass while the reticle overlaps the card background. The fixture combines
reported system insets with minimum 24 dp top/bottom bands so bar-clearance checks
still require space on an ATD image without system chrome. Half a dp of tolerance
accounts for density conversion when comparing reticle dimensions. Back is
explicitly sized to 48 dp because the stock M3 button's visual bounds can be
smaller than its expanded touch target.

`PairCodeScreenTest.scannerPasteReturnsFromEveryRecoveryStateWithoutSaving` uses
production navigation and injects ready, denied and camera-error scanner states.
For each, it opens manual entry and returns with both Cancel and Android Back,
compares stored pairing snapshots without emitting credentials, then checks that
scanner Back reaches Welcome. This does not exercise the Settings permission
lifecycle or real QR capture.

On 2026-09-21, all **20 device methods** across these three classes and **35 JVM
regressions** across `ScannerViewModelTest`, `QrCodeAnalyzerTest`,
`PairingConfirmationTest`, `PairCodeViewModelTest` and `PairingPayloadParserTest`
passed without failures, errors or skips. The
[verifier's saved-XML review](https://github.com/pyrycode/pyrycode-mobile/pull/719#issuecomment-5756242269)
confirmed their execution in the broader UI/unit gates. The
[PR evidence](https://github.com/pyrycode/pyrycode-mobile/pull/719) records the
focused command and reviewed `scanner-{412,360}-{dark,light}.png` captures under
`app/build/outputs/managed_device_android_test_additional_output/debug/pixel2Api33Atd/`.
The captures use a non-secret fake preview; they establish frame appearance, not
live camera rendering. The [#1213 retained comparison](../../../app/src/androidTest/assets/scanner-1213/README.md)
pairs the current Figma 13:2 render with nonblank 412×892 API 35 pixels and a
labelled difference; the design was inspected 2026-09-29 and its modification
date was unavailable. Static geometry evidence alone cannot establish how the
60% scrim composites over CameraX.
`ScannerLivePreviewDeviceTest.readyRoute_streamingCameraIsVisibleUnderOverlay`
therefore enters the production ready route and waits for both a nonblank raw
CameraX frame and visible composed pixels before capture: a streaming preview
can briefly be black at startup. The retained result executes one method with
no failures or skips and shows the virtual scene under the mask and guides.

**The #1386 save-then-wait is proven on the JVM, not on a device.**
`ScannerViewModelTest.confirm_navigatesOnlyAfterBothLegsConnect` scripts
`RelayConnectionRegistry.pairingStatus` for the saved record (relay `Connected`
with pyrycode still down keeps `Verifying`; both legs `Connected` reaches
`Paired`) and asserts exactly one save and one connect call. A `DaemonAbsent`
case reaches `VerificationFailed`, Retries into `Verifying` without a second
save, and completes on a later `Connected`; a `PairingRejected` case reaches
`VerificationFailed` with `retryable = false` and ignores `RetryVerification`.
Cancelling during the wait or after a failure reaches `Cancelled` without
letting a later status produce a late `Paired`, and the saved host is left in
place in every case. `ScannerScreenTest.verificationFailed_offersRetryOnlyWhenTheStepAllowsIt`
(Robolectric, `app/src/sharedTest`) proves the two button sets from the design
table: Retry + Cancel when the held failure is retryable, Cancel alone (no
Retry, no Confirm pairing) when it is not; `confirmWaitAndFailure_keepTheSameModalWindow`
compares a semantics node id inside the dialog across the `AwaitingConfirm` →
`Verifying` → `VerificationFailed` transitions to prove the `MobileModal`
window itself is never torn down and rebuilt (see [Pairing confirm gate §
one modal window](pairing-confirm-gate.md#confirm-the-wait-and-a-failure-share-one-modal-window-1386)
for why a per-branch render would fail this silently). The PR's scoped
`testDebugUnitTest` ran `ScannerViewModelTest`, `ScannerScreenTest`,
`QrCodeAnalyzerTest`, `PairCodeViewModelTest` and `PairingConfirmationTest`
with 0 failures and 0 skips; the verifier's full-gate pass separately confirmed
the managed-device suite green (one unrelated, pre-existing flaky capture test
re-ran green and is tracked as [#1402](https://github.com/pyrycode/pyrycode-mobile/issues/1402)),
including `PairCodeScreenTest.scannerPasteReturnsFromEveryRecoveryStateWithoutSaving`
exercising the new-constructor `ScannerViewModel` through `ViewModelProvider`.
No test covers the route's own `Paired → navigate` / `Cancelled → popBackStack`
mapping or its two `BackHandler`s directly — a verifier NIT, left as a gap
since the acceptance criteria only require proof at the VM level.
