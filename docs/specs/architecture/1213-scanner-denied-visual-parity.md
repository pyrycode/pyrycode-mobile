# Scanner and camera-denied visual parity (#1213)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` → `ScannerViewport`, `ScannerGuides`, `Reticle`, `HintCard`: scanner composition and responsive overlay.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt` → `ScannerDeniedScreen`, `DeniedCameraIllustration`: denied layout and existing exported vector layers.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` and `Color.kt` → `PyrycodeMobileTheme`, `darkScheme`: fixed dark role values.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt` → `checkFrame`: real-pixel scanner fixture and compact layout assertions.
- `app/src/androidTest/java/de/pyryco/mobile/ScannerDeniedRouteDeviceTest.kt` → `realDenial_backSettingsAndPaste_preserveUnpairedState`: real permission and navigation path with screenshot capture.
- `app/src/main/res/raw/scanner_denied_source.svg` → `DeniedIllustration`: compare the checked-in export with node 32:8.
- `docs/knowledge/features/scanner-screen.md` and `scanner-denied-screen.md` → current route and inset behavior; retain CameraX and callbacks.
- `docs/knowledge/features/development-verification.md` → device screenshot and executed-test evidence requirements.

## Design source

**Figma:** [Scanner 13:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2), [camera denied 32:2](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2). Inspected 2026-09-29. Node 32:20 is reference-only: production caller search for `ScannerConnectingScreen` found none.

The 412 × 892 scanner has a Pairing header, inset divider, rounded dark live-camera window, blue/coral atmosphere, stripe mask, centered 248 dp reticle, bottom hint and paste action. The denied screen has a separate Pair with pyrycode header, exported 120 dp camera illustration, centered copy, blue atmospheric background and bottom settings/paste actions. M3 titleLarge, headlineSmall, bodyMedium and labelLarge styles and dark `colorScheme` roles supply text and action colors.

## Context

Both routes already contain the reference structure and callbacks. Current real denied pixels show a flat background where node 32:2 has a blue atmospheric center. The scanner root and live camera mask also need comparison against node 13:2. The change is visual only; no state machine or route contract changes.

## Design

Keep `ScannerScreen` state dispatch and both composables' signatures. Draw the atmospheric background behind content with current dark theme roles, so the existing route remains stateless. Preserve the live preview as the first camera-window child, then layer the dark mask, atmosphere, stripes, reticle and guidance above it. Adjust only measured spacing or typography that the matching-viewport evidence shows differs. Reuse the existing exported illustration if its geometry still matches node 32:8. Never display pairing payloads in evidence.

## State and concurrency model

No new state, flows or jobs. `ScannerScreen` receives the existing `ScannerUiState` and callbacks; CameraX lifecycle and QR decode remain route-owned. The denied branch forwards Back, Settings and Paste unchanged.

## Error handling

No new failure mode. Existing permission denial, camera error, invalid QR and cancelled manual entry continue through their current branches. The visual layers do not consume input.

## Testing strategy

- Start with a failing focused visual/layout assertion in `ScannerFrameTest` or a scanner/denied shared screen test, then implement and rerun it.
- Run affected scanner and denied Compose classes, Android lint and debug build; compile androidTest when device test changes.
- Run focused managed-device `ScannerFrameTest` capture and the real denied route where an API 35 device is available. Compare nonblank actual pixels with current node renders at 412 × 892, save side-by-side plus labelled difference evidence, and check compact width and enlarged text for action reachability.
- Existing permission, settings, paste/cancel and Back device test covers navigation. QR decode and confirmation remain on the existing scanner route; no new operator flow is introduced, so no new live-Claude scenario is required.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/scanner-screen.md` and `scanner-denied-screen.md` to describe any changed visual layers, pixel evidence and fixed-dark responsive behavior. Do not update those shared documents in this builder stage.

## Open questions

- Does the current checked-in denied illustration exactly match the live node export? Compare before deciding whether an asset edit is necessary.
- How much of the Figma atmosphere is visible over a real camera preview? Resolve from device pixels without weakening preview/QR functionality.
