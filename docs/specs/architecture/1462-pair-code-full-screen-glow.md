# #1462 Pair code full-screen glow

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt`: the `drawBehind` on the body `BoxWithConstraints` in `PairCodeScreen`, which draws the narrow scaled ellipse.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt`: the `drawWithCache` glow in `WelcomeScreen`, the reference transform to share.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/OnboardingGlow.kt` (new): the shared glow modifier.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsLayoutTest.kt`: `darkArchiveBackdropHasTheReferenceGlow`, the pixel-sampling pattern to mirror.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147

The whole 412×892 frame, header included, carries the same radial glow as Welcome `6:32`: `gradientTransform matrix(43.8 -11.95 13.523 49.567 196 265)`, `r=10`, `Schemes/Primary Container` at 0 fading to transparent `Schemes/On Primary` at 0.76012, over `Schemes/Surface`. Between the header and the Pair button the glow reaches both side edges.

## Change

Move Welcome's `drawWithCache` glow, unchanged, into an internal `Modifier.onboardingGlow()` in `OnboardingGlow.kt`, scaled to the drawn size exactly as now. `WelcomeScreen` calls it in the same place, so its pixels do not change. `PairCodeScreen` drops the body's `drawBehind`/`scale` ellipse and applies `onboardingGlow()` to the outer column right after its `surface` background and before `systemBarsPadding`, matching Welcome's placement, so the glow covers the full screen including the header in every non-confirming state (first pair, re-pair, invalid code share that column). The confirming state renders `ScannerScreen` and is untouched.

## Testing strategy

New Robolectric screen test `PairCodeScreenGlowTest` under `app/src/sharedTest/.../ui/onboarding/` with `@GraphicsMode(NATIVE)`, dark theme, drawing the root view to a bitmap:

- `glowReachesBothSideEdges`: in first-pair, re-pair (`targetName`) and invalid-code states, pixels 2px in from the left and right edges at the glow's vertical centre differ from the plain `surface` at the bottom edge (no dark side bands), and a header-row pixel at the far right also carries glow.
- `glowMatchesWelcome`: at the same window size, a content-free side-edge pixel in the header band and mid-body is identical on `PairCodeScreen` and `WelcomeScreen`.

Existing `WelcomeScreenTest`, `PairCodeScreenInsetsTest` and `PairCodeScreenVerificationTest` run unchanged.
