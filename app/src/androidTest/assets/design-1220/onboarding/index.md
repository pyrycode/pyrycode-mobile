# Onboarding audit (#1430)

- **App commit:** `main` at `97ee8d75`, plus the test-only harness on `feature/1430`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-02.
  `32:20` (Scanner — Connecting) is now titled "Retired 2026-10-01" and is not audited; the
  **Pairing States · 2026-10-01** section (`654:4833`) replaces it.
- **Capture:** `OnboardingDesignCaptureTest` (three methods; the Pair Code States recaptured for #1464) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0, font scale 1.0, fixed dark theme, real 24 px status
  and navigation bars. Each `.txt` beside a PNG records the measured values.
- **Result:** `onboarding-results.xml`, 3 executed, 0 failures, 0 errors, 0 skipped.
- **Recaptured for #1463:** every capture, side-by-side and overlay in this folder comes from a 2026-10-02 rerun on
  `feature/1463` after its review rework (same image, viewport and arguments, 3 executed, 0 failures). `figma-32-2.png` and
  `figma-533-2147.png` were re-exported the same day after the frames' headers moved to Scanner's height.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames
have no system chrome. Welcome lays out at the frame's full-screen coordinates and draws under the bars;
Scanner, Denied, Pair Screen and the pairing modal lay out inside the bars, reading each frame as the area
below the status bar (#1463). A 24 px move that keeps a whole screen inside its bars is not counted as a
mismatch. A difference between screens that share a component is.

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
| Geometry | match inside the bars: header, divider and camera card 24 px down at the top; card bottom and paste link 24 px up at the bottom. The title's line box starts 48 px from the window top (glyph top 53 px against the frame's 30), the same height as on Denied and Pair Screen |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | match: header, hint with the monospace `pyry pair`, paste link |
| Colour | match: mask, stripes, reticle and hint surface; the emulator's camera scene shows through where Figma is static |
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
| Geometry | match inside the bars: every element 24 px down at the top and 24 px up at the bottom; the header sits at the same height as Scanner's and Pair Screen's. The title's line box starts 48 px from the window top (`ScannerDeniedRouteDeviceTest` reads 48 px on the full image; glyph top 52 px against the frame's 29) |
| Padding | match |
| Spacing | match |
| Typography | match |
| Colour | match: blue atmosphere, crossed-camera stroke colours |
| Borders | match (none) |
| Radii | match: pill primary button |
| Icon paths | match: crossed camera |
| Component state | match: denied |

- **Routed:** none

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
| Geometry | match inside the bars: header and form top 24 px down, at the same height as Scanner's and Denied's header; Pair, Cancel and footer 24 px up, keeping the frame's 28 px gutter above the navigation bar; the centred fields within 3 px of the frame. The title's line box starts 48 px from the window top (glyph top 53 px against the frame's 30) |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | match, including the `pyrycode-mobile` footer |
| Colour | mismatch: the radial glow is a narrow vertical ellipse with dark side bands; the frame's glow is broad and reaches both edges |
| Borders | match: field underlines and header divider |
| Radii | match: field top corners and pill Pair button |
| Icon paths | match: back arrow and clear icons |
| Component state | match: filled fields, Pair enabled |

- **Routed:** #1462 (glow)

### Pair Screen with the keyboard open — `533:2147`

- **Owning ticket:** #1269 (keyboard handling from #1149)
- **Capture:** `pair-keyboard.png` · **Side-by-side:** `pair-keyboard-side-by-side.png` · **Overlay:** `pair-keyboard-overlay.png`
- The frame has no keyboard variant, so this compares the open-keyboard layout with the closed frame. The
  keyboard is the test IME (`MobileModalTestIme`), not a production keyboard.

| Aspect | Verdict |
|---|---|
| Geometry | match: the form compresses above the keyboard; the header stays put and Pair, Cancel and footer stay above the keyboard (rechecked after #1463 moved the form's bottom inside the bars) |
| Padding | match |
| Spacing | match: field, button and footer gaps keep the frame's order, tightened to fit |
| Typography | match |
| Colour | mismatch: the narrow glow of #1462 |
| Borders | match: focused Host name shows the active underline |
| Radii | match |
| Icon paths | match |
| Component state | match: Host name focused with a cursor |

- **Routed:** #1462 (glow)

## Pair Code States · 2026-10-02

The code path's wait and failures stay on the pair-code form (decision of 2026-10-02 on #1464); only
`Confirming` uses the shared modal, compared above as `pair-confirm.png`. Recaptured for #1464 with
`pairCodeFramesAt412By892` and `rePairFramesAt412By892` (2 executed, 0 failures, same device and settings as
above); `onboarding-results.xml` still records the #1430 run of all three methods. Recaptured again on
`feature/1463` after merging #1464 (3 executed, 0 failures), so these states show the shared pairing header. The Pair button's
progress ring is captured mid-rotation, so its arc length differs from the frame's.

### Pair code — Saving — `663:2887`

- **Owning ticket:** #1269, #1464
- **Capture:** `pair-saving.png` · **Side-by-side:** `pair-saving-side-by-side.png` · **Overlay:** `pair-saving-overlay.png`
- Reached by Confirm with `holdSaves = true`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as on Pair Screen: header 24 px down with the title's line box 48 px from the window top; button, Cancel and footer 24 px up; the centred fields within 1 px of the frame |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | mismatch: the long code clips at the field edge where the frame ends it with an ellipsis |
| Colour | mismatch: the narrow glow of #1462; disabled Pair container and label match |
| Borders | match: field underlines and header divider |
| Radii | match: pill Pair button |
| Icon paths | match: 20 px progress ring before "Saving…", no clear icons |
| Component state | match: both fields read-only, Pair loading, Cancel and Back disabled |

- **Routed:** #1462 (glow), #1506 (ellipsis)

### Pair code — Connecting — `663:2963`

- **Owning ticket:** #1385, #1464
- **Capture:** `pair-connecting.png` · **Side-by-side:** `pair-connecting-side-by-side.png` · **Overlay:** `pair-connecting-overlay.png`
- Reached when the held save completes, with `pairingStatus` still `null`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as on Pair Screen: header 24 px down with the title's line box 48 px from the window top; button, Cancel and footer 24 px up; the centred fields within 1 px of the frame |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | mismatch: the long code clips at the field edge where the frame ends it with an ellipsis |
| Colour | mismatch: the narrow glow of #1462 |
| Borders | match |
| Radii | match |
| Icon paths | match: progress ring before "Connecting…", no clear icons |
| Component state | match: fields read-only, Pair loading, Cancel and Back enabled |

- **Routed:** #1462 (glow), #1506 (ellipsis)

### Pair code — Verification failed, retry — `663:3039`

- **Owning ticket:** #1385
- **Capture:** `pair-failed-retry.png` · **Side-by-side:** `pair-failed-retry-side-by-side.png` · **Overlay:** `pair-failed-retry-overlay.png`
- Reached with `pairingStatus` set to `RelayLinkStatus.DaemonAbsent`. Other messages above the button follow this frame.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as on Pair Screen: header 24 px down with the title's line box 48 px from the window top; button, Cancel and footer 24 px up; the centred fields within 1 px of the frame |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match: message above Retry |
| Typography | mismatch: the long code clips at the field edge where the frame ends it with an ellipsis; the message and its wrap match |
| Colour | mismatch: the narrow glow of #1462; error role on the message |
| Borders | match |
| Radii | match |
| Icon paths | match: no clear icons |
| Component state | match: fields read-only, Retry and Cancel enabled |

- **Routed:** #1462 (glow), #1506 (ellipsis)

### Pair code — Verification failed, rejected — `663:3115`

- **Owning ticket:** #1385
- **Capture:** `pair-failed-rejected.png` · **Side-by-side:** `pair-failed-rejected-side-by-side.png` · **Overlay:** `pair-failed-rejected-overlay.png`
- Reached from Retry with `pairingStatus` set to `RelayLinkStatus.PairingRejected`.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as on Pair Screen: header 24 px down with the title's line box 48 px from the window top; button, Cancel and footer 24 px up; the centred fields within 1 px of the frame |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | mismatch: the long code clips at the field edge where the frame ends it with an ellipsis; the rejected message matches |
| Colour | mismatch: the narrow glow of #1462 |
| Borders | match |
| Radii | match |
| Icon paths | match: no clear icons |
| Component state | match: fields read-only, Pair disabled, Cancel enabled |

- **Routed:** #1462 (glow), #1506 (ellipsis)

### Pair code — Invalid code — `663:3191`

- **Owning ticket:** #1269
- **Capture:** `pair-invalid-code.png` · **Side-by-side:** `pair-invalid-code-side-by-side.png` · **Overlay:** `pair-invalid-code-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: header 24 px down and button, Cancel and footer 24 px up match inside the bars, as on Pair Screen; the fields sit 6 px higher, because the shorter error line under the code takes less height |
| Padding | mismatch: error text indent (see typography); the header's insets match Pair Screen |
| Spacing | mismatch: no gap between underline and error text |
| Typography | mismatch: the field error text starts at the field edge, right under the underline; the frame indents it 16 px with a 4 px gap |
| Colour | mismatch: the narrow glow of #1462; error underline, label and text in the error role |
| Borders | match: 2 px error underline on Pairing code |
| Radii | match |
| Icon paths | match: both fields keep their clear icons |
| Component state | match: both fields editable; the app's focus stays in Host name after the keyboard closed, where the frame's cursor is in Pairing code |

- **Routed:** #1462 (glow), #1506 (error text)

### Re-pair — `663:3266`

- **Owning ticket:** #842
- **Capture:** `repair.png` · **Side-by-side:** `repair-side-by-side.png` · **Overlay:** `repair-overlay.png`
- Reached from the thread's "Pairing error - Re-pair".

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as on Pair Screen: header 24 px down with the title's line box 48 px from the window top; button, Cancel and footer 24 px up; the centred fields within 1 px of the frame |
| Padding | match: title line box 24 px below the status bar, divider 44 px below the title top |
| Spacing | match |
| Typography | match |
| Colour | mismatch: the narrow glow of #1462 |
| Borders | match |
| Radii | match |
| Icon paths | match: no clear icon on Host name, clear icon on Pairing code |
| Component state | match: Host name fixed to the stored name, Pairing code empty and editable |

- **Routed:** #1462 (glow)

### Re-pair — Wrong host — `663:3331`

- **Owning ticket:** #842
- **Capture:** `repair-wrong-host.png` · **Side-by-side:** `repair-wrong-host-side-by-side.png` · **Overlay:** `repair-wrong-host-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: header 24 px down and button, Cancel and footer 24 px up match inside the bars, as on Pair Screen; the fields sit 6 px higher, because of the shorter error line |
| Padding | mismatch: error text indent (see typography); the header's insets match Pair Screen |
| Spacing | mismatch: no gap between underline and error text |
| Typography | mismatch: the field error text starts at the field edge, right under the underline; the frame indents it 16 px with a 4 px gap; mismatch: the long code clips at the field edge where the frame ends it with an ellipsis |
| Colour | mismatch: the narrow glow of #1462; error role on the code field |
| Borders | match: error underline |
| Radii | match |
| Icon paths | match: no clear icon on Host name, clear icon on Pairing code |
| Component state | match: Host name read-only, Pairing code editable with the error |

- **Routed:** #1462 (glow), #1506 (error text, ellipsis)
