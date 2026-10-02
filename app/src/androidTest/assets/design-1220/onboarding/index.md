# Onboarding audit (#1430)

- **App commit:** `main` at `97ee8d75`, plus the test-only harness on `feature/1430`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-02.
  `32:20` (Scanner — Connecting) is now titled "Retired 2026-10-01" and is not audited; the
  **Pairing States · 2026-10-01** section (`654:4833`) replaces it.
- **Capture:** `OnboardingDesignCaptureTest` (three methods) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0, font scale 1.0, fixed dark theme, real 24 px status
  and navigation bars. Each `.txt` beside a PNG records the measured values.
- **Result:** `onboarding-results.xml`, 3 executed, 0 failures, 0 errors, 0 skipped.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames
have no system chrome. The app's screens do not share one rule for the bars: Welcome and Pair Screen lay
out at the frame's full-screen coordinates and draw under the bars, while Scanner, Denied and the pairing
modal move their content inside the bars. A 24 px move that keeps a whole screen inside its bars is not
counted as a mismatch. A difference between screens that share a component is, and #1463 records it.

### Welcome — `6:32`

- **Owning ticket:** #1212
- **Capture:** `welcome.png` · **Side-by-side:** `welcome-side-by-side.png` · **Overlay:** `welcome-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match: logo, title, body, both actions and footer at the frame's coordinates |
| Padding | match: 32 px side gutters |
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
| Geometry | mismatch: the header is 28 px below the frame, 4 px more than the status bar and 22 px lower than Pair Screen's identical header; the camera card ends 24 px higher, above the paste link |
| Padding | mismatch: header top inset (see geometry) |
| Spacing | match |
| Typography | match: header, hint with the monospace `pyry pair`, paste link |
| Colour | match: mask, stripes, reticle and hint surface; the emulator's camera scene shows through where Figma is static |
| Borders | match: header divider |
| Radii | match: card and hint radii |
| Icon paths | match: back arrow, reticle corners |
| Component state | match: ready to scan |

- **Routed:** #1463 (pairing header height differs across Scanner, Denied and Pair Screen)

### Scanner — Denied — `32:2`

- **Owning ticket:** #1213
- **Capture:** `scanner-denied.png` · **Side-by-side:** `scanner-denied-side-by-side.png` · **Overlay:** `scanner-denied-overlay.png`
- Reached through the override's scanner view model (`PermissionDenied`), which renders the same screen as a
  real denial; `ScannerDeniedRouteDeviceTest` covers the real permission dialog.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars: every element 24 px down at the top and 24 px up at the bottom; its header differs from Pair Screen's, routed under Scanner |
| Padding | match |
| Spacing | match |
| Typography | match |
| Colour | match: blue atmosphere, crossed-camera stroke colours |
| Borders | match (none) |
| Radii | match: pill primary button |
| Icon paths | match: crossed camera |
| Component state | match: denied |

- **Routed:** #1463 (header height, shared with Scanner)

### Scanner — Camera error — `654:5032`

- **Owning ticket:** #326 (camera error seam), message from `CameraPreview`
- **Capture:** `scanner-camera-error.png` · **Side-by-side:** `scanner-camera-error-side-by-side.png` · **Overlay:** `scanner-camera-error-overlay.png`
- Reached with `ScannerEvent.CameraError` carrying the app's camera-bind message.

| Aspect | Verdict |
|---|---|
| Geometry | match: message and link centred in the window, 3 px above the frame's centre line |
| Padding | match: 32 px gutters, same wrap |
| Spacing | match: message-to-link gap |
| Typography | match |
| Colour | match: canvas, on-surface message, primary link |
| Borders | match (none) |
| Radii | match (none) |
| Icon paths | match (none) |
| Component state | match: error |

- **Routed:** none

### Pairing — Confirm fingerprint — `654:4834`

- **Owning ticket:** #1270 (modal), #1214 (shared with the code path)
- **Capture:** `pairing-confirm.png` (scanner) and `pair-confirm.png` (code path, identical) · **Side-by-side:** `pairing-confirm-side-by-side.png`, `pair-confirm-side-by-side.png` · **Overlay:** `pairing-confirm-overlay.png`, `pair-confirm-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars: modal fills the window, fingerprint block centred |
| Padding | match: 28 px content gutters |
| Spacing | match |
| Typography | match: header, monospace fingerprint, message |
| Colour | match: modal surface, fingerprint surface, outlined and filled buttons |
| Borders | match: header divider, outlined Don't pair |
| Radii | match: modal, fingerprint block, buttons |
| Icon paths | match: close icon |
| Component state | match: awaiting confirm |

- **Routed:** none

### Pairing — Connecting — `654:4882`

- **Owning ticket:** #1386
- **Capture:** `pairing-connecting.png` · **Side-by-side:** `pairing-connecting-side-by-side.png` · **Overlay:** `pairing-connecting-overlay.png`
- Reached by Confirm with `pairingStatus` still `null`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars |
| Padding | match |
| Spacing | match |
| Typography | mismatch: the loading button keeps a dimmed "Confirm pairing" label; the frame has none |
| Colour | mismatch: the loading button is a dark disabled container; the frame's is light |
| Borders | match: outlined Cancel |
| Radii | match |
| Icon paths | mismatch: a small ring left of the label instead of one centred ring |
| Component state | mismatch: loading button content (see above) |

- **Routed:** #1461

### Pairing — Verification failed, retry — `654:4932`

- **Owning ticket:** #1386
- **Capture:** `pairing-failed-retry.png` · **Side-by-side:** `pairing-failed-retry-side-by-side.png` · **Overlay:** `pairing-failed-retry-overlay.png`
- Reached with `pairingStatus` set to `RelayLinkStatus.DaemonAbsent`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars |
| Padding | match |
| Spacing | match: error text under the message |
| Typography | match: same unavailable message and wrap |
| Colour | match: error role on the message |
| Borders | match: outlined Cancel |
| Radii | match |
| Icon paths | match: close icon |
| Component state | match: Cancel and Retry |

- **Routed:** none

### Pairing — Verification failed, rejected — `654:4982`

- **Owning ticket:** #1386
- **Capture:** `pairing-failed-rejected.png` · **Side-by-side:** `pairing-failed-rejected-side-by-side.png` · **Overlay:** `pairing-failed-rejected-overlay.png`
- Reached from Retry with `pairingStatus` set to `RelayLinkStatus.PairingRejected`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars |
| Padding | match |
| Spacing | match |
| Typography | match: rejected message and wrap |
| Colour | match |
| Borders | match: outlined Cancel |
| Radii | match |
| Icon paths | match |
| Component state | match: Cancel only, no Retry |

- **Routed:** none

### Pair Screen — `533:2147`

- **Owning ticket:** #1269
- **Capture:** `pair.png` (fields filled, as in the frame) · **Side-by-side:** `pair-side-by-side.png` · **Overlay:** `pair-overlay.png`
- `figma-533-2147.png` was re-exported on 2026-10-02 after #1436 changed the footer to `pyrycode-mobile`.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: fields, Pair, Cancel and footer sit at the frame's coordinates, but the header is only 6 px below the frame, 18 px higher in the window than the same header on Denied |
| Padding | mismatch: header top inset (see geometry) |
| Spacing | match |
| Typography | match, including the `pyrycode-mobile` footer |
| Colour | mismatch: the radial glow is a narrow vertical ellipse with dark side bands; the frame's glow is broad and reaches both edges |
| Borders | match: field underlines and header divider |
| Radii | match: field top corners and pill Pair button |
| Icon paths | match: back arrow and clear icons |
| Component state | match: filled fields, Pair enabled |

- **Routed:** #1462 (glow), #1463 (header height)

### Pair Screen with the keyboard open — `533:2147`

- **Owning ticket:** #1269 (keyboard handling from #1149)
- **Capture:** `pair-keyboard.png` · **Side-by-side:** `pair-keyboard-side-by-side.png` · **Overlay:** `pair-keyboard-overlay.png`
- The frame has no keyboard variant, so this compares the open-keyboard layout with the closed frame. The
  keyboard is the test IME (`MobileModalTestIme`), not a production keyboard.

| Aspect | Verdict |
|---|---|
| Geometry | match: the form compresses above the keyboard; the header stays put and Pair, Cancel and footer stay above the keyboard |
| Padding | match |
| Spacing | match: field, button and footer gaps keep the frame's order, tightened to fit |
| Typography | match |
| Colour | mismatch: the narrow glow of #1462 |
| Borders | match: focused Host name shows the active underline |
| Radii | match |
| Icon paths | match |
| Component state | match: Host name focused with a cursor |

- **Routed:** #1462 (glow); the header height of #1463 applies here too

## Gaps

States reachable from `MainActivity` with no current Mobile frame. They are captured here and compared
with the nearest frame; #1464 asks for frames or a decision.

| State | Capture | Nearest frame | Owning ticket | Routed |
|---|---|---|---|---|
| Pair code `Saving` (button "Saving…", Cancel disabled) | `pair-saving.png` | none | #1269 | #1464 |
| Pair code `Connecting`, on the form instead of the modal | `pair-connecting.png`, `pair-connecting-side-by-side.png` | `654:4882` | #1385 | #1464 |
| Pair code verification failed with Retry, on the form | `pair-failed-retry.png`, `-side-by-side.png` | `654:4932` | #1385 | #1464 |
| Pair code verification failed, rejected, on the form | `pair-failed-rejected.png`, `-side-by-side.png` | `654:4982` | #1385 | #1464 |
| `INVALID_CODE_ERROR` field error | `pair-invalid-code.png`, `-side-by-side.png` | `533:2147` | #1269 | #1464 |
| Re-pair target label, reached from the thread's "Pairing error - Re-pair" | `repair.png`, `-side-by-side.png` | `533:2147` | #842 | #1464 |
| `WRONG_HOST_ERROR` field error in re-pair mode | `repair-wrong-host.png`, `-side-by-side.png` | `533:2147` | #842 | #1464 |

`pair-invalid-code.png` shows a text-selection handle under Host name. The keyboard step before it focused
the field, and closing the keyboard with Back keeps the focus. The app behaves this way after any Back
that closes the keyboard, so the handle is not a capture artefact.
