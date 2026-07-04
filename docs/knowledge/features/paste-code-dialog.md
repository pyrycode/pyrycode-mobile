# PasteCodeDialog

The **manual paste-code fallback** for pairing, for when the QR camera can't scan the code on the
desktop. A stateless M3 `AlertDialog` that takes a pasted pairing string, validates it, and — on
success — hands the **raw validated payload** to the caller. It is **store-free by design
([#501](../codebase/501.md))**: its only success output is that string; it holds no reference to
[`PairedServerStore`](paired-server-store.md) or the persist helper and **never persists**. The paste
dialog shipped inline in [#503](https://github.com/pyrycode/pyrycode-mobile/issues/503); #501 extracted
it into this file and re-routed its success branch through the fingerprint gate.

Package: `de.pyryco.mobile.ui.onboarding` (`app/src/main/java/de/pyryco/mobile/ui/onboarding/`).
File: `PasteCodeDialog.kt`.

## Shape

```kotlin
@Composable
fun PasteCodeDialog(
    onDismiss: () -> Unit,
    onValidPayload: (String) -> Unit,   // the raw, validated pairing string
)
```

- **`public`** (no `internal`) — consumed by `MainActivity`'s `PyryNavHost`. Single top-level
  `@Composable` function → ktlint's single-class filename rule does not apply (functions aren't
  class-like types; see [[ktlint-filename-rule-single-class]]).
- **No `modifier` parameter.** The dialog is a full-window `AlertDialog` with no host-controlled
  layout slot, so there is nothing for a `modifier` to attach to — matching the shipped #503 shape.
- **`onValidPayload` receives the *trimmed* string**, and it fires **only** on a successful
  `parsePairingPayload`. A bad parse never invokes it.

## What it does

A single `AlertDialog` with four slots — the identical surface shipped in #503:

1. **`onDismissRequest = onDismiss`** — outside-tap / back-press (M3 defaults).
2. **`title`** — `Text("Enter pairing code")`.
3. **`text`** — one `OutlinedTextField` (label `"Pairing code"`, `isError = pasteError != null`,
   `supportingText` showing `pasteError` when set).
4. **`confirmButton`** — `TextButton("Pair")`.
5. **`dismissButton`** — `TextButton("Cancel") → onDismiss`.

The **only** behavioral change vs. #503 is the `Pair` branch:

```kotlin
val trimmed = pasteText.trim()
when (parsePairingPayload(trimmed)) {
    is PairingParseResult.Success -> onValidPayload(trimmed)   // hand off — DO NOT persist
    is PairingParseResult.Failure -> pasteError = "Invalid pairing code"
}
```

The trimmed value is parsed **once** and *that same* value is handed off — there is no suspension
between the parse and the callback, so the parsed-from string and the emitted string are provably
identical (the fingerprint the user later confirms is derived from this exact string). The dialog
calls [`parsePairingPayload`](pairing-payload-parser.md) directly — a pure top-level function, no
injection needed.

### Internal state

```kotlin
var pasteText by remember { mutableStateOf("") }
var pasteError by remember { mutableStateOf<String?>(null) }
```

- **`remember`, not `rememberSaveable`** — matches #503; do not upgrade. The pasted token is
  sensitive and should not survive into saved instance state.
- **Fresh-mount reset.** The dialog is mounted only inside `if (showPasteDialog)` in `MainActivity`,
  so this state re-initializes every time it opens. The explicit `pasteText=""`/`pasteError=null`
  reset that #503 kept in the `onPasteCode` handler became dead and was dropped in #501 — no behavior
  change.

## How it routes into the gate

`PasteCodeDialog` produces a raw payload; `MainActivity` feeds it to the **same** event the camera
path uses, so paste flows through the identical [Pairing confirm gate](pairing-confirm-gate.md)
([#343](../codebase/343.md)):

```kotlin
if (showPasteDialog) {
    PasteCodeDialog(
        onDismiss = { showPasteDialog = false },
        onValidPayload = { payload ->
            showPasteDialog = false
            vm.onEvent(ScannerEvent.QrDecoded(payload))   // camera path's event; paste is a 2nd producer
        },
    )
}
```

`QrDecoded(payload) → Decoded(payload)` (unconditional VM transition) → the shared `Decoded`
`LaunchedEffect` re-parses, derives `serverKeyFingerprint` (#342), and parks in `AwaitingConfirm` →
persist happens **only** behind the Confirm button (`confirmPairAndNavigate`, [#489](../codebase/489.md)).
The re-parse is safe: `parsePairingPayload` is pure/deterministic, so a valid paste re-parses to the
same `Success` and can never fall through to the full-screen QR-worded `PARSE_FAILED_MSG`. See the
[Scanner screen](scanner-screen.md) for the shared `Decoded` effect and the
[Pairing confirm gate](pairing-confirm-gate.md) for the `AwaitingConfirm` surface.

## Security properties

- **Cannot persist, by construction.** The dialog's type has no reference to `PairedServerStore` or
  `confirmPairAndNavigate` — its only output is a raw string handed to a callback. "Paste cannot
  persist" is a **structural type guarantee**, not a reviewer's argument. This is the deliberate
  belt-and-suspenders split of #501: the structural fact plus a UI test pinning the callback contract
  (different fabric, not two stochastic checks).
- **Logs nothing (MUST-hold).** The pasted string and the plaintext `token` it carries must never
  reach `Log.*` — the dialog adds no logging, and [`parsePairingPayload`](pairing-payload-parser.md)
  logs nothing either. The token is further protected downstream: it flows as `QrDecoded → Decoded`,
  both of which have a redacting `toString()` (`<redacted N chars>`), so an accidental `"$state"`/
  `"$event"` log stays byte-safe.
- **Bad paste never reaches the full-screen error.** The Failure branch sets the inline
  `"Invalid pairing code"` (a fixed constant — no payload echo) and emits **no** event, so a bad paste
  never reaches the QR-worded `PARSE_FAILED_MSG` surface.
- **Fingerprint-compare closes the impostor gap.** Pre-#501 a pasted malicious code persisted
  directly. Now paste crosses the same human fingerprint-compare boundary as QR — the user sees the
  server's [static-key fingerprint](static-key-fingerprint.md) before any persist.

## Edge cases / limitations

- **Whitespace is trimmed before parse and hand-off.** `pasteText.trim()` — a code pasted with
  surrounding whitespace parses and hands off the trimmed value.
- **No validation feedback beyond parse Success/Failure.** One rule (`parsePairingPayload` succeeds);
  a Failure shows the single inline error. Downstream failures (bad stored key, save failure) surface
  on the shared gate's Error path, not here.
- **Clipboard / third-party-keyboard capture of the pasted token is out of scope** (inherent to
  allowing paste at all, #503). #501 neither introduces nor worsens it; the fingerprint gate it adds
  mitigates the *impostor* consequence even if the token is observed. No owner assigned; note only.
- **Design-later — no Figma.** Reuses the existing `AwaitingConfirm` surface (no Figma counterpart;
  see [#343](../codebase/343.md) / [[343-gated-on-missing-figma-confirm-pairing-view]]). This dialog
  renders the identical `AlertDialog` shipped in #503; no new visual surface.

## Tests

Four instrumented contract tests in `app/src/androidTest/.../onboarding/PasteCodeDialogTest.kt`
(`createComposeRule` + `AndroidJUnit4`, [Scanner screen](scanner-screen.md) test idiom). Because the
dialog has no store handle, "nothing persists" is guaranteed by type; the tests pin the *callback
contract*:

- `title_label_and_buttons_render` — title / label / `Pair` / `Cancel` all displayed.
- `validPaste_handsOffPayload_andShowsNoError` — type a valid code, tap `Pair`, assert
  `onValidPayload` fired with the code and no inline error.
- `validPaste_trimsSurroundingWhitespace_beforeHandOff` — `"  <valid>  "` → callback receives the
  trimmed value.
- `invalidPaste_showsInlineError_andDoesNotHandOff` — `"garbage"` → inline `"Invalid pairing code"`
  displayed, `onValidPayload` **not** invoked.
- `cancel_invokesOnDismiss_notOnValidPayload` — `Cancel` → `onDismiss` fires, `onValidPayload` not.

The valid-code fixture is built **inline** with `java.util.Base64` (outer base64url-no-pad wrapper,
inner base64-std 32-byte `server_static_pubkey` — the two-alphabet trap, see
[[pairing-qr-two-base64-alphabets]]) because the `src/test/` fixture helpers (`wrap(json())` in
`PairingPayloadParserTest`) are **not** on the androidTest classpath. Instrumented tests were not run
in the pipeline (no emulator) — only compiled via `compileDebugAndroidTestKotlin`
([[androidtest-not-compiled-by-mandatory-gates]]); the fixture was verified by hand against the parser.
The downstream chain (`Decoded → AwaitingConfirm`, Confirm/Decline) is covered by existing
`ScannerViewModelTest` / `ScannerScreenTest`.

## Related

- Ticket notes: [`../codebase/501.md`](../codebase/501.md) (the re-point + extraction);
  [#503](https://github.com/pyrycode/pyrycode-mobile/issues/503) shipped the inline dialog without the
  gate.
- [Pairing confirm gate](pairing-confirm-gate.md) — the shared fingerprint gate both entry paths now
  reach ([#343](../codebase/343.md)).
- [Scanner screen](scanner-screen.md) — hosts the `ScannerViewModel`, the shared `Decoded`
  `LaunchedEffect`, and the `onPasteCode` trigger that mounts this dialog.
- [Pairing payload parser](pairing-payload-parser.md) — the `parsePairingPayload` this dialog gates
  on and that the shared effect re-parses to derive the fingerprint ([#320](../codebase/320.md)).
- `confirmPairingAndConnect` ([#489](../codebase/489.md)) — the persist+connect helper behind the
  Confirm button that both entry paths reach.
</content>
