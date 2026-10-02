# #1463 — One pairing header for Scanner, Denied and Pair Screen

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` — `ScannerViewport` draws its own header `Row` (top 18 dp) and the `scanner_divider`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerDeniedScreen.kt` — `ScannerDeniedScreen` draws a 64 dp header row after a 4 dp top pad; its body starts with a 64 dp spacer.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` — `PairCodeScreen` draws a 48 dp header row at the inset edge and its own divider; the form column pads 28 dp on top.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenInsetsTest.kt` — `backStartsAtStatusInsetAndActionsSitAboveNavigationBar` asserts the Back button top equals the status inset; it moves 14 dp down by design. Its `applyBars` technique gives Robolectric real insets for the new test.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt` — reads the `scanner_divider` tag.
- `app/src/androidTest/assets/design-1220/onboarding/index.md` and `README.md` — the audit verdicts this ticket flips and the capture commands.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=13-2, https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=32-2, https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147

Frame coordinates are read as the area below the status bar. All three frames put the title's 28 px `titleLarge` line box at y = 24. Scanner (`533:2134`) and Pair Screen (`533:2148`) draw the "Pairing" title in `onPrimaryContainer` with the back icon at x = 20, title at x = 56 and a 1 px divider at y = 68 inset 20 px each side (`inversePrimary` at 60 %); Scanner's body begins at 85 with the camera card 8 below (y = 93), Pair Screen's at 73 with the hero container 28 below (y = 101). Denied (`32:3`) draws "Pair with pyrycode" in `onSurface`, back button at x = 4, title at x = 52, no divider, illustration at y = 132.

## Change

A new internal composable `PairingHeader(title, titleColor, onBack, backIcon: Painter, modifier, startPadding = 8.dp, backEnabled = true, divider = true)` in `ui/onboarding/PairingHeader.kt` draws a 48 dp row padded 14 dp from its top (so the 28 dp title line box, centred, starts 24 dp down), the 48 dp back `IconButton`, the title in `titleLarge`, and, when `divider`, a `HorizontalDivider` 6 dp under the row (top 44 dp below the title top). The title and divider carry the test tags `pairing_header_title` and `pairing_header_divider`. Each screen still applies `systemBarsPadding` first and places the header at the inset edge:

- **Scanner:** header replaces the row and divider; the camera card gets the divider's old 24 dp bottom pad as its top pad (69 + 24 = 93). The card moves up 4 dp with the title.
- **Denied:** the column's 4 dp top pad goes; `startPadding = 4.dp`, `divider = false`; the body spacer grows from 64 to 70 dp so the illustration stays at 132.
- **Pair Screen:** header with its `ic_pair_back` painter and `backEnabled = !saving`; the form's top pad grows from 28 to 32 dp (69 + 32 = 101), so the form moves down with the header, inside the bars.

`ScannerFrameTest` switches to the new divider tag. Nothing else moves: colours, back-button treatment and the bodies below the header keep their frames' layout.

## Testing strategy

- New `PairingHeaderGeometryTest` in `app/src/sharedTest/.../ui/onboarding/`: renders Scanner (`ReadyToScan`), Denied and Pair Screen in turn at `ForcedSize(412x892)` with 24 dp status and navigation bars applied the way `PairCodeScreenInsetsTest.applyBars` does, and asserts on each that the `pairing_header_title` top is the status inset + 24 dp (within 1 px), equal across the three, and that on Scanner and Pair Screen `pairing_header_divider` top is the title top + 44 dp. Denied asserts no divider node.
- `PairCodeScreenInsetsTest.backStartsAtStatusInsetAndActionsSitAboveNavigationBar`: Back top expectation becomes status inset + 14 dp (the frame's 48 dp button centred on the title row).
- Existing coverage run: `ScannerScreenTest`, `ScannerDeniedScreenTest`, `PairCodeScreenGlowTest`, `PairCodeScreenVerificationTest`, `PairCodeScreenInsetsTest`; `compileDebugAndroidTestKotlin` for `ScannerFrameTest`.
- Device evidence (real pixels, needs the full image): `OnboardingDesignCaptureTest` on `pixel8Api35` with `requireRealSystemBars=true`, recapturing `scanner.png`, `scanner-denied.png`, `pair.png` and the rest of the folder, regenerating side-by-side and overlay images with `scripts/design-compare.py`, and updating `index.md`'s verdicts for `13:2`, `32:2` and `533:2147` (the audit evidence lives under `app/src/androidTest/assets/`).

## Revisions

- **2026-10-02 — title line box.** Compose's default `LineHeightStyle` trims a single line to the font's own height (24 dp under Robolectric's native fonts), so the centred title's box started 2 dp low and did not match Figma's 28 px line box. The header's title now uses `titleLarge` with `LineHeightStyle(Center, Trim.None)`: its box is the full 28 dp with glyphs centred, which is how Figma places the text, and the glyphs land where they did before. The row is `heightIn(min = 48.dp)` so a large font scale grows it instead of clipping. The geometry test sizes the window with `@Config(qualifiers = "w412dp-h892dp")` and `@GraphicsMode(NATIVE)` instead of `ForcedSize`, which rescales density on Robolectric's 320 dp screen.
