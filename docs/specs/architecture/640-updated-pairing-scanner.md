# Updated pairing scanner (#640)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` — `ScannerViewport`, `Reticle`, `HintCard`, `PairingConfirmContent`: existing camera layers and confirmation surface.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` — `PairCodeScreen`: matching header roles and full-screen editing/confirmation flow.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost`: all scanner paste callbacks already navigate to manual entry; Back and confirmation remain route-owned.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` — `ScannerViewModel.onEvent`: synchronous state reducer, with no layout responsibilities.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/CameraPreview.kt` — `CameraPreview`: compatible preview surface, analyzer lifetime and fixed error feedback.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` — `PyrycodeMobileTheme`: existing light/dark roles; no new theme values.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` — `ScannerScreenTest`: viewport, camera slot and confirmation regressions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` — `cancelToolbarAndAndroidBackReturnToCallerWithoutSaving`: production navigation return proof to extend for the scanner.
- `docs/knowledge/features/scanner-screen.md` — “Edge cases / limitations”: Settings permission takes effect on re-entry, circular atmospheric gradients are an existing approximation.
- `docs/knowledge/features/paste-code-dialog.md` and `pairing-confirm-gate.md` — manual cancellation and immutable fingerprint/record binding must survive the visual change.
- `docs/knowledge/features/development-verification.md` — “Compose evidence” and “Emulator and real evidence”: capture both sizes and distinguish fixture proof from live camera acceptance.

Codegraph located `ScannerScreen` and manual-entry symbols; private `ScannerViewport`/`HintCard` callees were absent, so their source was read directly.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2

Read design context and screenshot on 2026-09-21. A surface column has a left back arrow and “Pairing” (`titleLarge`, `onPrimaryContainer`), an inset `inversePrimary` divider at 60%, then a 24 dp rounded camera window with blue/coral atmosphere, stripes, a centered 248 dp reticle and scan-line glow. A bottom helper uses `bodyMedium` with a monospace tertiary command; a primary `labelLarge` paste link sits below the window. Reuse the matching Material ArrowBack glyph and existing guide drawing; no new asset is needed.

## Change

Retune only `ScannerScreen.kt`: mirror the manual screen's M3 header pattern (48 dp Back target), add the 20 dp inset divider, use 16 dp viewport sides, 24 dp divider-to-window gap, and bottom/paste spacing from the frame. Preserve the camera as the first child with guides composited above it. Keep the reticle centered at full size; measure the helper before placing the reticle so short windows move/shrink the reticle only as needed to maintain a 16 dp gap. Use the theme surface at 94% opacity for the helper and `onSurface`/`tertiary` text so it remains readable over a camera in either theme. Native touch targets and system-bar insets intentionally consume more space than the frameless Figma canvas. The existing circular gradient approximation remains.

No new public type, signature, state, job, persistence, network behavior or error branch. `ScannerViewModel`, `CameraPreview`, analyzer debounce, parser, fingerprint gate and save → connect → list ownership remain unchanged. Existing fixed-code error logging stays at its current boundaries; styling adds no lifecycle event or payload logging. Settings-return Paste and permission-on-re-entry retain their current behavior.

## Testing strategy

- RED: change the scanner title assertions to “Pairing” and run the affected device method before production edits.
- Add focused frame tests at 412×892 and 360×640 with actual system-bar insets, light/dark screenshots, visible/clickable Back and Paste, divider and camera slot, and disjoint reticle/helper/action bounds. Use non-secret fake preview content for deterministic visual review.
- Extend manual-entry production-route coverage to scanner Paste from ready, denied and error states; Cancel/Android Back return to the invoking scanner with stored pairing unchanged, then scanner Back returns to its caller.
- Run affected Compose classes on `pixel2Api33Atd` and inspect fresh executed-count XML. Run existing `ScannerViewModelTest`, `QrCodeAnalyzerTest`, pairing parser and confirmation orchestration regressions; lint, debug assembly, Android-test compilation and Spotless.
- Dispatcher owns full UI/scripted gates and `python3 scripts/android-test-gate.py live` after verification. Follow-up #676 owns real camera/QR → fingerprint → confirmed pairing and scanner → manual pairing against real daemons/live relay, plus its second-host management scenario. Existing live tests do not prove those future scenarios.

## Size and overlap

One deliverable: the updated scanner frame with preserved behavior. Estimate approximately 320 written lines including this plan, tests and production changes; one production source file, zero exported declarations, zero consumer signature updates, four acceptance criteria and zero new reject branches. #122's analogue added 203 lines and deleted 25. Remote feature branches were refreshed and checked against the planned scanner and test paths: no overlap. No dependency or Gradle changes.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/scanner-screen.md` sections “What it does”, “ScannerViewport — the locked viewport body” and “Edge cases / limitations” for the Pairing header/divider, compact guide placement, readable light helper and fresh focused evidence. Preserve the Settings re-entry limitation and #676 live-camera coverage boundary. The ticket specifies no additional documentation-only acceptance requirement.

## Security review

**Verdict:** PASS

- **Trust boundaries:** `PyryNavHost` retains `parsePairingPayload` then `serverKeyFingerprint`; layout receives no QR text. `PairingConfirmContent` still receives only the public fingerprint and callbacks, and no decorative action confirms trust.
- **Tokens/credentials:** no new generation, storage, copy, saved state or exposure; `AwaitingConfirm` keeps displayed fingerprint bound to the saved record. Tests compare collection snapshots after cancellation without emitting them.
- **File/storage:** no production file I/O or path derivation; existing credential storage and backup policy are untouched. Screenshot fixtures contain no credentials.
- **Android surface:** no new intent, exported component, WebView, deep link or permission flow. Paste remains internal navigation; system Back remains owned by the current route.
- **Cryptography:** no change to key derivation, comparison, Noise primitives or nonce/session ownership.
- **Network/I/O:** styling introduces no endpoint or I/O; parser validation, save-before-connect and relay lifecycle remain outside this diff.
- **Errors/logging:** no payload-derived display/log fields are added. Fixed camera/parser recovery messages and static lifecycle logs remain intact.
- **Concurrency:** measurement is synchronous Compose layout, with no shared mutable state or new coroutines. Camera disposal and route/registry scopes retain their cancellation paths.
- **Threat alignment:** the unchanged human confirmation gate remains essential against a substituted QR. Relay compromise, rooted storage extraction, hostile frames and OS overlay/screenshot/keyboard threats gain no new surface from this change; broader existing hardening remains outside this visual ticket. #676 owns the outstanding live pairing proof.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
