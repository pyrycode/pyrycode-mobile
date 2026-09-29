# Welcome Figma pass (#1212)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` → `WelcomeScreen`: existing glow, mark shadow, layout, actions and previews.
- `app/src/main/res/drawable/ic_pyry_logo.xml` → logo path: compare its outline with Figma `80:2`.
- `app/src/main/res/drawable/ic_qr_scan_frame.xml` → scan icon paths: compare with Figma `9:41`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/WelcomeScreenTest.kt` → `WelcomeScreenTest`: width and callback proof.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/WelcomeAppearanceDeviceTest.kt` → `WelcomeAppearanceDeviceTest`: real activity capture and inset proof.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` and `Type.kt` → `PyrycodeMobileTheme`, `AppTypography`: shared dark roles and text metrics.
- `docs/knowledge/features/welcome-screen.md` → prior visual decisions and four-line Android observation.
- `docs/knowledge/features/development-verification.md` → real pixels and device capture requirements.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Inspected `6:32` and children `6:33`–`6:44`, logo `80:2`, icon `9:41`, footer `9:53`–`9:54` on 2026-09-29. The 412 × 892 dark frame has a left-aligned mark and text above two full-width M3 pill actions and a centered footer. A tilted blue radial glow sits over the surface background; headlineLarge, titleMedium, bodyLarge and label styles use the shared scheme roles. The live render and node metadata now show a **four-line**, 96 px body at 320 px width, contrary to the ticket's earlier five-line observation; retain the current live design and call out that discrepancy in evidence.

## Context

The retained #1150 real-activity image places the hero about 35 px below the current Figma frame and the CTA about 12 px above it. Its glow is narrower than the current reference. The checked-in QR icon matches Figma paths; the logo differs by one path coordinate and should use the current source. The current UI copy and action destinations remain the product contract.

## Design

Adjust only `WelcomeScreen` and its logo drawable. Use the current node's radius-10 affine radial transform `matrix(43.8 -11.95 13.523 49.567 196 265)` and transparent stop at 0.76012, scaled to the inset-consumed drawing area. Retain the surface and theme color roles. Align the hero and CTA stack against the reference after accounting for real status/navigation insets: 133 dp hero top and 4 dp CTA bottom at the reference viewport. Retain the 320 dp body measure, M3 type and button roles. Update the logo's single differing coordinate to the current SVG; retain its tint and silhouette shadows. Add vertical scrolling that has no effect at reference size but permits the hero, both actions and footer to remain reachable on compact and enlarged-text displays.

This changes one production Kotlin file and one drawable, adds no exported declaration or consumer call-site update, and has no state-machine branches. No in-flight feature branch overlaps these files as of the dependency check.

## State and concurrency model

`WelcomeScreen` remains stateless with the existing `onPaired` and `onSetup` callbacks. Scrolling is composition-local UI state and has no job, flow, I/O or cancellation path.

## Error handling

No new I/O boundary or failure mode. `MainActivity` continues to own navigation and setup URL launch.

## Testing strategy

- Extend `WelcomeScreenTest` first with layout assertions for the reference hero/action positions and four body lines, plus compact/enlarged text reachability and callback checks. Observe a meaningful red before implementation.
- Extend `WelcomeAppearanceDeviceTest` to capture real `MainActivity` at 412 × 892, 360 × 800 and enlarged Android text. Save nonblank screenshots and metadata from a full device with physical bars. Compare to the current Figma screenshot in a labelled side-by-side and overlay or difference image; record the mismatch dispositions on the PR. Device execution is required because Robolectric cannot prove real activity pixels or physical bars.
- Run focused shared and device tests, Spotless, lint, assembleDebug and androidTest compilation. This visual correction does not require a real-Claude scenario.

## Open questions

- Confirm actual status and navigation inset values in fresh captures and tune the hero/button offsets accordingly.
- Confirm whether any difference beyond the logo's one path coordinate is visible after the Figma asset comparison.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/welcome-screen.md` § How it works and § Edge cases / limitations with the current radial transform, reference layout offsets, compact/enlarged-text behavior, and the current four-line Figma observation.
