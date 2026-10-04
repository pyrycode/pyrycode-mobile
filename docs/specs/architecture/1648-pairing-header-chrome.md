# Pairing header shared chrome (#1648)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingHeader.kt`: `PairingHeader` preserves the title line box, Back target and optional rule.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt`: `PairCodeScreen` owns the full-screen glow and inset-aware form.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt`: `ScannerViewport` owns atmosphere, camera and measured guides.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt`: `ScannerDeniedScreen` preserves its distinct title, inset and absent rule.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChromeEffects.kt`: `chromeBackdrop` samples a sibling Haze source; `defaultChromeShadow` draws the foreground sharp after its alpha-following shadow.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ThreadColors.kt` and `Theme.kt`: `headerBackdrop` is the shared theme role, including light-theme fallback.
- `docs/knowledge/features/scanner-screen.md`, `scanner-denied-screen.md`, `paste-code-dialog.md`: preserve the full untrimmed title line box and caller-specific colors and geometry.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairingHeaderGeometryTest.kt`, `PairCodeScreenGlowTest.kt`: inset geometry and full-screen background proof; header pixel equality with Welcome intentionally changes.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt` and `design/ViewportRule.kt`: device frame regressions and display setup before Activity launch.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2, inspected 2026-10-04 with design context and screenshots.

The full-width Top bar uses the shared #09141D downward gradient and progressive background blur, with Default shadow on the Back glyph and title. Keep Material titleLarge/onPrimaryContainer, the existing arrow assets, 24dp title offset, 44dp title-to-rule offset and inset rule; Denied keeps onSurface, its narrower start inset and no rule. Reuse #1646 effects and theme roles without copying their constants.

## Change

Pass a screen-local remembered HazeState into PairingHeader at its three call sites. Record each screen's existing full-size background/atmosphere as a separate sibling hazeSource behind the inset-aware content; this avoids sampling foreground controls or feedback into the effect. Apply chromeBackdrop to the header Column and defaultChromeShadow to its content Row, leaving the divider outside the shadow. Preserve screen and body geometry, inset consumption, titles, colors, enabled state and callbacks. No new domain state, jobs, error modes, dependencies or lifecycle logging are introduced. Composition owns graphics resources. No overlapping in-flight branches were found. Forecast: about 350 written lines including plan and tests, no new production types, three consumer updates, two criteria and no reject branches.

## Testing strategy

First add native Robolectric pixel checks for the gradient on all three screens and update the existing Welcome comparison to expect a changed header while retaining identical body glow; watch the new assertion fail before implementation. Run PairingHeaderGeometryTest, PairCodeScreenGlowTest, PairCodeScreenInsetsTest, ScannerScreenTest, ScannerDeniedScreenTest, PairCodeScreenFieldLayoutTest, PairCodeScreenFormStatesTest and PairCodeScreenVerificationTest. Existing geometry assertions remain unchanged.

Add a device-only capture of all three surfaces with real bars on full pixel8Api35: progressive blur and alpha-following shadows need hardware framebuffer rendering. Retain nonblank PNGs and inset metadata and inspect against the Figma references; keep sharp titles and Back targets and assert actual pointer routing. Run existing ScannerFrameTest frame/interaction coverage. This decoration-only adoption adds no daemon-facing action and needs no real-Claude scenario. Finish with lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. Dispatcher owns the complete verifier gates.
