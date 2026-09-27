# Restore the denied pairing surface

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt` — `ScannerDeniedScreen`, `DeniedCameraIllustration`: missing header and approximate illustration.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` — `ScannerScreen`: forwards destinations but drops Back in `Denied`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` — `PyryNavHost`: existing pop, app-settings intent, pair-code navigation and consumed Scaffold insets.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` — `ScannerUiState.Denied`: retained permission outcome; no state change needed.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreenTest.kt` and `ScannerScreenTest.kt` — existing screen assertions and callback test patterns.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — real-activity capture, display configuration and real-bar checks.
- `docs/knowledge/features/scanner-denied-screen.md` — obsolete no-header and approximate-Canvas guidance is superseded by this ticket.
- `docs/knowledge/features/scanner-screen.md` and `paste-code-dialog.md` — denied rendering, existing full-page form and cancel return contract.
- `docs/knowledge/features/development-verification.md`, sections “Where a screen test goes” and “Compose evidence” — shared tests; full API 35 image required for real pixels; consumed insets apply once.
- `app/build.gradle.kts` — existing `pixel8Api35` managed device and test dependencies.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2

The 412×892 reference has a 64 dp header at y=4 with a 48 dp Back target and `titleLarge` title. A 120 dp illustration from node `32:8` sits at (146,132), above a centered `headlineSmall` heading and 300 dp wide `bodyMedium` message. Surface/onSurface/onSurfaceVariant, primary/onPrimary and error theme roles color the screen; the actions are 48 dp filled and 40 dp visible text, with 8 dp between them.

## Change

Add required `onNavigateBack` to `ScannerDeniedScreen`, forward the existing callback from `ScannerScreen`, and update its previews and direct test consumers. Use a 64 dp header with 4 dp top padding, Back at x=4 and title at x=52. The body retains 32 dp side margins, adds 64 dp between the header and illustration, then 32 dp to the heading and 16 dp to the message. Bottom actions preserve the reference's 84 dp bottom margin within the safe content area; native system bars shift the reference content once. The text action keeps its 40 dp visual height and the Material minimum 48 dp touch area.

Replace the approximate Canvas with the downloaded node `32:8` SVG, retained as a source resource, and mechanically translate its geometry into Android vector resources. Separate outline and strike tint layers preserve the exact exported curves, coordinates and stroke widths while using Compose theme colors. No network-loaded assets, new library, new state, or route changes. Keep existing light/dark previews. Debug-only content-free action logs identify Back/settings/paste without recording pairing content.

## State, concurrency and errors

The screen remains stateless. Existing route-scoped permission handling and navigation own lifecycle; no coroutine, flow, persistence, network or new error branch is added. Back and cancellation cannot reach pairing confirmation or save.

## Testing strategy

- RED then GREEN: shared `ScannerScreenTest` must display the denied title and dispatch Back exactly once, with a 48 dp target. Shared denied-screen tests assert each action dispatches only its own callback and check action dimensions.
- Add one device-only real-activity regression: fresh unpaired launch, real Android permission denial, header Back to Welcome, reopen denied route, settings destination, paste form and untouched cancellation; assert no saved pairing. Device-only justification: real system permission dialog, settings activity and screenshot pixels.
- Run that class on full `pixel8Api35` with real bars at 412×892 dp in dark mode. Retain screenshot, Figma reference and build/device/density/inset metadata under `app/src/androidTest/assets/scanner-denied-1151/`. Compare frame content after accounting for system bars exactly once; do not substitute a forced state preview.
- Run touched shared tests, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. Full suites remain the verifier gate. No daemon or real-Claude scenario is required for this local Android route, as the ticket explicitly specifies.

## Scope and dependencies

One deliverable: restore the complete permission-denied pairing surface. Estimate about 600 total written lines including plan, tests, vectors and evidence context; two production Kotlin files, zero new exported production types, six existing call sites, three acceptance criteria, zero new error branches. This remains within all six builder limits; the device evidence makes the refiner's roughly 400-line estimate optimistic. Codegraph returned only file self-references for callers; the required Kotlin source fallback found one route renderer, two previews and three direct test calls. No overlapping remote feature branches were found after fetching origin.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/scanner-denied-screen.md` to describe the restored header and reference illustration, replacing “Why no top bar” and the obsolete approximation guidance. Reconcile the denied-branch description in `docs/knowledge/features/scanner-screen.md` (the `ScannerScreen` dispatch description). Builder does not edit these shared documents.

## Open questions

None. The reference is frameless; actual activity evidence records the additional real system bars rather than subtracting them twice.

## Security review

**Verdict:** PASS

- Trust boundaries: `ScannerDeniedScreen` takes static UI callbacks, no QR, pasted or daemon text. `PyryNavHost` retains the existing parse/confirmation boundary.
- Tokens: none enter this screen. Back, settings and paste callbacks do not invoke `confirmPairAndNavigate`; the real-route test checks the store remains empty.
- Files/storage: packaged vector resources only; no runtime file paths or storage changes. Evidence captures an unpaired screen with no credentials.
- Android surface: existing `ACTION_APPLICATION_DETAILS_SETTINGS` uses the app's own `context.packageName`; no exported component, deep link, provider or WebView is introduced.
- Cryptography: no changes to key storage, Noise, RNG, nonce handling or comparisons.
- Network/I/O: no network action is introduced. Relay validation and transport remain outside this static surface.
- Logs: only static action names under `BuildConfig.DEBUG`; no QR payload, token, key, clipboard or user text.
- Concurrency: no new jobs or mutable state; route lifetime and permission state stay with existing owners.
- Threat alignment: screenshots and accessibility expose only fixed permission guidance. Existing relay, daemon and at-rest threats are not expanded by this change; no security remediation is deferred by this plan.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-27
