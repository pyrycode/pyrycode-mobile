# Onboarding audit (#1430)

- **App commit:** `main` at `2e1e2e46`, plus the test-only harness on `feature/1430`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-01.
- **Capture:** `OnboardingDesignCaptureTest.onboardingFramesAt412By892` on the full `pixel8Api35` image (API 35)
  with `requireRealSystemBars=true`: 412x892 px at density 1.0, font scale 1.0, fixed dark theme, real
  24 px status and navigation bars. Each `.txt` beside a PNG records the measured values.
- **Result:** `onboarding-results.xml`, 1 executed, 0 failures, 0 errors, 0 skips.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames
have no system chrome; the app's window is inset by the 24 px bars, so content anchored to the top moves
down 24 px and content anchored to the bottom moves up 24 px. That offset is not counted as a mismatch.
Everything else inside the window is.

### Welcome — `6:32`

- **Owning ticket:** #1212
- **Capture:** `welcome.png` · **Side-by-side:** `welcome-side-by-side.png` · **Overlay:** `welcome-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match: logo, title, body and both actions sit at the frame's positions |
| Padding | match: 20 px side gutters |
| Spacing | match |
| Typography | match: title, subtitle, body and footer styles and wrapping |
| Colour | match: canvas, radial glow, primary button and text roles |
| Borders | match (none) |
| Radii | match: pill primary button |
| Icon paths | match: snowflake and scan icons |
| Component state | match: idle |

- **Routed:** none

### Scanner — `13:2`

- **Owning ticket:** #1213
- **Capture:** `scanner.png` · **Side-by-side:** `scanner-side-by-side.png` · **Overlay:** `scanner-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match within the bar insets: header below the status bar, camera card fills to the paste link above the navigation bar |
| Padding | match |
| Spacing | match |
| Typography | match: header, hint with the monospace `pyry pair`, paste link |
| Colour | match: mask, stripes, reticle and hint surface; the live emulator scene shows through the mask where Figma is static |
| Borders | match: header divider |
| Radii | match: card and hint radii |
| Icon paths | match: back arrow, reticle corners |
| Component state | match: ready to scan |

- **Routed:** none

### Scanner — Denied — `32:2`

- **Owning ticket:** #1213
- **Capture:** `scanner-denied.png` · **Side-by-side:** `scanner-denied-side-by-side.png` · **Overlay:** `scanner-denied-overlay.png`
- Reached through the override's scanner view model (`PermissionDenied`), which renders the same screen as a
  real denial; `ScannerDeniedRouteDeviceTest` covers the real permission dialog.

| Aspect | Verdict |
|---|---|
| Geometry | match within the bar insets |
| Padding | match |
| Spacing | match |
| Typography | match |
| Colour | match: blue atmosphere, crossed-camera stroke colours |
| Borders | match (none) |
| Radii | match: pill primary button |
| Icon paths | match: crossed camera |
| Component state | match: denied |

- **Routed:** none

### Scanner — Connecting — `32:20`

- **Owning ticket:** #1386 (post-confirm wait); the frame's screen came from #62 and #122
- **Capture:** `scanner-connecting.png` · **Side-by-side:** `scanner-connecting-side-by-side.png` · **Overlay:** `scanner-connecting-overlay.png`
- Reached by a decoded QR payload, Confirm, and a pairing status that never answers.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the app keeps the full-height confirmation modal up; the frame is a full-screen connecting state |
| Padding | mismatch (different surface) |
| Spacing | mismatch (different surface) |
| Typography | mismatch: no "Connecting to your pyrycode server…" or host address line |
| Colour | mismatch: modal surface over the canvas instead of the glow canvas |
| Borders | mismatch: modal outline and header divider are not in the frame |
| Radii | mismatch: modal corners are not in the frame |
| Icon paths | mismatch: the spinner sits inside the Confirm button instead of the centred progress ring |
| Component state | mismatch: confirm modal loading instead of the connecting screen |

- **Routed:** #1435 (`ScannerConnectingScreen` has only preview callers)

### Pair Screen — `533:2147`

- **Owning ticket:** #1269
- **Capture:** `pair.png` · **Side-by-side:** `pair-side-by-side.png` · **Overlay:** `pair-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match within the bar insets |
| Padding | match |
| Spacing | match |
| Typography | mismatch: the footer reads `pyrycode-mobile`, the frame `pyrycode-desktop`; all other text matches |
| Colour | match |
| Borders | match: field underlines and header divider |
| Radii | match: field top corners and pill Pair button |
| Icon paths | match: back arrow and clear icons |
| Component state | match: empty fields, Pair enabled styling |

- **Routed:** #1436 (footer copy; the frame looks like the error, so the issue asks the design owner first)

### Pair Screen with the keyboard open — `533:2147`

- **Owning ticket:** #1269 (keyboard handling from #1149)
- **Capture:** `pair-keyboard.png` · **Side-by-side:** `pair-keyboard-side-by-side.png` · **Overlay:** `pair-keyboard-overlay.png`
- The frame has no keyboard variant, so this compares the open-keyboard layout with the closed frame. The
  keyboard is the test IME (`MobileModalTestIme`), not a production keyboard.

| Aspect | Verdict |
|---|---|
| Geometry | match: the form compresses above the keyboard; header stays below the status bar, Host name focused and Pair, Cancel and footer all above the keyboard |
| Padding | match |
| Spacing | match: field, button and footer gaps keep the frame's order, tightened to fit |
| Typography | match |
| Colour | match |
| Borders | match: focused Host name shows the active underline |
| Radii | match |
| Icon paths | match |
| Component state | match: Host name focused with a cursor |

- **Routed:** none

## Gaps

States reachable from `MainActivity` with no current Mobile frame:

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Fingerprint confirmation (`ScannerUiState.AwaitingConfirm`); its reference was the Components-page `Pairing verify` modal `487:2559` | `pairing-confirm.png` | #1270 | #1435 |
| Post-confirm verification failure with Retry (`ScannerUiState.VerificationFailed`) | not captured | #1386 | #1435 |
| Scanner camera error (`ScannerUiState.Error`) | not captured | #326 | #1435 |
