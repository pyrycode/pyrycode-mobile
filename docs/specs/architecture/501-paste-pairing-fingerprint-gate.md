# #501 — Route paste-pairing through the fingerprint-confirm gate

**Size:** XS · **Security-sensitive:** yes (pre-release blocker) · **Figma:** N/A

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:286-320` — the inline paste
  `AlertDialog`; the `confirmButton` at `:299-312` calls `confirmPairAndNavigate(result.server)`
  on parse Success — **the bypass this ticket removes.**
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:165-191` — `confirmPairAndNavigate`
  (the single persist+connect helper) and the "ONLY … persist" comment (`:165`) that AC #2
  requires to become true again.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:217-243` — the shared `Decoded`
  `LaunchedEffect`: parse → derive `serverKeyFingerprint` → emit `PairingPrepared`
  (or `PairingFailed(PARSE_FAILED_MSG)` on parse-fail / null-derive). **This is the gate the
  paste path must reuse unchanged.**
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:245-284` — `BackHandler` (Decline on Back
  while `AwaitingConfirm`) + `onConfirmPairing`/`onDeclinePairing` wiring; all shared, unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt:49-115` — `ScannerEvent`
  / `ScannerUiState` / `onEvent`. Note `QrDecoded → Decoded` is **unconditional** (source-agnostic;
  fires from any state). Redacting `toString()` on both `QrDecoded` and `Decoded` (`:33`, `:67`).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:92-99,369-443` — the
  `AwaitingConfirm` → `PairingConfirmContent` fingerprint surface (already shipped).
- `app/src/main/java/de/pyryco/mobile/data/network/PairingPayloadParser.kt:24-40,110` —
  `parsePairingPayload` (pure, never throws, byte-safe failure categories) and
  `serverKeyFingerprint`. Parse is deterministic → a Success re-parses to the same Success.
- `app/src/test/java/de/pyryco/mobile/data/network/PairingPayloadParserTest.kt:19-42` —
  `wrap(json())` builds a **valid** base64url-no-pad pairing string. Reuse this exact pattern to
  produce a valid pasted code in the new dialog test.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt:26-83` —
  `ComposeTestRule` idiom (setContent → `onNode(hasText…)` → `performClick`) the new dialog test
  follows.

## Context

PR #503 shipped the paste-pairing dialog **without** the MITM fingerprint gate — an explicit
operator decision to ship the feature and track the safety check separately (this ticket). The QR
path (#343) enforces: decode → derive the server static-key fingerprint → park in `AwaitingConfirm`
→ persist **only** behind the Confirm button, after the user compares the fingerprint against what
`pyry pair` prints. The paste dialog's `Pair` button skips all of that: on parse Success it calls
`confirmPairAndNavigate(result.server)` directly and persists immediately. A user pasting a
malicious code is silently paired to an impostor server, never seeing its identity. There are now
two persist paths and the `MainActivity.kt:165` comment claiming "The ONLY scan-path persist" is
false. Manifests only with `USE_RELAY_REPOSITORY` on. Pre-release blocker.

The connect-after-pairing dependency (#489) already merged — `confirmPairingAndConnect` is live and
the paste path already funnels through the same `confirmPairAndNavigate`. No sequencing blocker.

## Design

The gate machinery is entirely shipped; this is a **re-point of one entry edge** plus a small
extraction to make the changed code testable. Two production files.

### Core behavioral change — paste Success drives the shared gate

The paste `Pair` button must stop persisting and instead feed the raw validated payload into the
same event the camera path uses. Because `onEvent(QrDecoded(payload))` unconditionally sets state to
`Decoded(payload)` (ScannerViewModel `:105`), the shared `Decoded` `LaunchedEffect` then re-parses,
derives the fingerprint, and emits `PairingPrepared` → `AwaitingConfirm` — identical to QR. The
persist stays behind the Confirm button (`confirmPairAndNavigate`, MainActivity `:272-273`), which
becomes the *sole* caller of `confirmPairAndNavigate`.

Success branch, conceptually:

```
val trimmed = pasteText.trim()
when (parsePairingPayload(trimmed)) {
    Success -> { dismiss(); onValidPayload(trimmed) }   // → vm.onEvent(QrDecoded(trimmed))
    Failure -> pasteError = "Invalid pairing code"      // inline, unchanged (AC #4)
}
```

- Parse `trimmed` **once**; on Success hand that **same** `trimmed` value to the callback (no
  suspension between parse and callback, so `pasteText` cannot change — but capture-once anyway so
  the parsed-from string and the emitted string are provably identical).
- Feed the **raw payload string** (not `result.server`). This is deliberate: it routes through the
  one derive site in the `Decoded` effect instead of duplicating the fingerprint-derive + null-guard
  into the paste path. The re-parse is a pure, deterministic, microsecond function — a Success
  cannot become a Failure on re-parse, so a valid paste can never fall through to the full-screen
  QR-worded `PARSE_FAILED_MSG`.
- Bad paste keeps the inline `"Invalid pairing code"` error and never emits any event, so it never
  reaches the shared full-screen error surface (AC #4).

### Testability extraction — `PasteCodeDialog`

The paste dialog currently lives inline inside the private `PyryNavHost` composable, wired directly
to local state and (today) to the persist helper — **it has no test coverage**, so a regression that
re-introduced the bypass would pass every existing test. For a security-sensitive fix whose ACs are
all phrased "Verify: …", extract the dialog into a stateless composable in `ui/onboarding/` and give
it a store-free contract, so "paste cannot persist" is true **by construction** (the dialog has no
reference to `PairedServerStore` or `confirmPairAndNavigate` — its only success output is a raw
payload string). This is the belt-and-suspenders split: a deterministic structural guarantee (dialog
type can't reach the store) plus a UI test asserting the callback contract.

New file `app/src/main/java/de/pyryco/mobile/ui/onboarding/PasteCodeDialog.kt`:

```
@Composable
fun PasteCodeDialog(
    onDismiss: () -> Unit,
    onValidPayload: (String) -> Unit,   // raw validated pairing string; MainActivity feeds it to QrDecoded
)
```

- Owns its own `pasteText` / `pasteError` via `remember` (**not** `rememberSaveable` — match the
  current behavior; do not upgrade). Because the dialog is mounted fresh each time
  `showPasteDialog` flips true, its `remember` state re-initializes on show — the explicit
  `pasteText=""`/`pasteError=null` reset in the old `onPasteCode` handler becomes unnecessary.
- Renders exactly the current `AlertDialog`: `onDismissRequest = onDismiss`, title "Enter pairing
  code", `OutlinedTextField` (label "Pairing code", `isError`, `supportingText`), `confirmButton`
  "Pair", `dismissButton` "Cancel" → `onDismiss`. Only the Success branch changes (calls
  `onValidPayload` instead of persisting).
- Calls `parsePairingPayload` directly (pure top-level function; no injection needed). Logs nothing —
  the pasted string and the token it contains must never reach `Log.*` (the parser itself logs
  nothing; the dialog must not add any).
- File contains only the `@Composable fun` (+ private helpers if any) → ktlint's single-class
  filename rule does not apply to functions (see [[ktlint-filename-rule-single-class]]).

### MainActivity wiring after the change

- Delete the inline `AlertDialog` block (`:286-319`) and the `pasteText` / `pasteError` locals
  (`:162-163`). Keep `showPasteDialog`. Simplify `onPasteCode` to `{ showPasteDialog = true }`.
- Replace with:

```
if (showPasteDialog) {
    PasteCodeDialog(
        onDismiss = { showPasteDialog = false },
        onValidPayload = { payload ->
            showPasteDialog = false
            vm.onEvent(ScannerEvent.QrDecoded(payload))
        },
    )
}
```

- Update the `:165` comment so AC #2 holds: it is now the ONLY persist for **both** entry paths
  (drop "scan-path"; state that QR-scan and paste both reach it solely through the `AwaitingConfirm`
  Confirm button after the user compares the fingerprint).

`QrDecoded` is reused as-is, not renamed — it already carries "a raw untrusted pairing payload," and
paste is just a second producer of the same. No rename fan-out; no change to `ScannerViewModel`.

## State + concurrency model

No new state, flows, or coroutines. `ScannerViewModel` is a pure synchronous state machine
(`MutableStateFlow.value =`); `QrDecoded` is a synchronous set with no TOCTOU. The paste path no
longer launches anything — the only `scope.launch` (the persist in `confirmPairAndNavigate`) stays
behind the Confirm button on the lifecycle-scoped `scope`, unchanged. `onConfirmPairing` still reads
the current collected state via `(state as? AwaitingConfirm)`, so a save cannot fire after Decline/
Back closed the gate (existing guard, untouched).

## Error handling

- **Parse failure (paste):** inline `"Invalid pairing code"` in the dialog; no event emitted; no
  persist; full-screen `PARSE_FAILED_MSG` never shown (AC #4).
- **Parse Success (paste):** `QrDecoded` → `Decoded` → shared effect derives fingerprint →
  `AwaitingConfirm`. A null fingerprint derive (structurally unreachable for a Success — key already
  proven valid) still routes to `PairingFailed(PARSE_FAILED_MSG)` via the shared effect, same as QR.
- **Decline / Back while `AwaitingConfirm`:** `DeclinePairing` → `ReadyToScan`, persists nothing
  (AC #3) — shared with QR, no paste-specific code.
- **Persist failure:** unchanged — `confirmPairingAndConnect.onFailed` → `PairingFailed(SAVE_FAILED_MSG)`.

## Testing strategy

Unit (`testDebugUnitTest`): the `QrDecoded → Decoded` transition is already asserted in
`ScannerViewModelTest`; no VM change, no new unit test.

Instrumented (`connectedAndroidTest`) — new `PasteCodeDialogTest` (mirror `ScannerScreenTest`; note
androidTest is not compiled by the default gates — run `compileDebugAndroidTestKotlin`,
see [[androidtest-not-compiled-by-mandatory-gates]]):

- **Valid paste → hands off, never persists.** Set content with `PasteCodeDialog`, capture
  `onValidPayload`. Type a valid code (build via the `wrap(json())` fixture pattern from
  `PairingPayloadParserTest`), click "Pair" → assert `onValidPayload` fired with the trimmed code and
  no inline error is shown. (Structural: the dialog has no store handle, so "nothing persists" is
  guaranteed by type — the test pins the callback contract.)
- **Whitespace trimmed.** Type `"  <valid>  "` → `onValidPayload` receives the trimmed value.
- **Invalid paste → inline error, no hand-off.** Type `"garbage"`, click "Pair" → assert
  "Invalid pairing code" renders and `onValidPayload` was **not** invoked.
- **Cancel dismisses.** Click "Cancel" → `onDismiss` fired, `onValidPayload` not.

The rest of the chain is already covered: `Decoded → AwaitingConfirm` derive is the shared QR effect;
`AwaitingConfirm` → fingerprint surface + Confirm/Decline are asserted in `ScannerScreenTest`.

## Open questions

- None blocking. The `QrDecoded` event name reads slightly QR-specific now that paste also produces
  it; a source-neutral rename is deliberately **not** in scope (rename fan-out across the VM, its
  test, and the camera wiring would exceed XS for no behavioral gain). If a future ticket renames it,
  update both producers together.

## Design source

N/A — reuses the existing `AwaitingConfirm` confirm-pairing / fingerprint-display surface, which has
no Figma counterpart (design still owed; see #343, relaxed design-later). This ticket adds no new
visual surface — the extracted `PasteCodeDialog` renders the identical `AlertDialog` shipped in #503.
Visual-fidelity check intentionally skipped.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** This ticket *is* the fix. The pasted string is untrusted external input; the
  paste path previously crossed straight to persist, bypassing the human fingerprint-compare boundary
  the QR path enforces. The design routes paste Success through the same
  `parsePairingPayload → serverKeyFingerprint → AwaitingConfirm → Confirm-persist` boundary, so both
  entry sources cross identically. The double-parse (dialog gates Success/Failure; shared effect
  re-parses to derive) is safe — `parsePairingPayload` is pure/deterministic and the same `trimmed`
  string is fed to both, so a Success cannot diverge into a Failure. No residual finding.
- **[Tokens/secrets]** The pasted code carries the plaintext `token`. Covered by inheritance: it flows
  as `QrDecoded(payload)` → `Decoded(payload)`, both of which have redacting `toString()`
  (`<redacted N chars>`), so even an accidental `Log("$state")`/`"$event"` is safe. **Design
  constraint (MUST hold):** the extracted `PasteCodeDialog` must log nothing — no `Log.*` of
  `pasteText` or the parsed token. Storage is unchanged (persist reuses the Keystore-wrapped
  `PairedServerStore`, out of scope).
- **[Cryptographic primitives]** No new crypto. `serverKeyFingerprint` (#342 BLAKE2s
  full-then-truncate) is reused unchanged; the fix ensures it is derived **and displayed** before any
  paste persist — a security improvement, not a new surface. No finding.
- **[File/storage]** No filesystem path is built from the payload; no new storage path. N/A.
- **[Inter-process / Android]** No new exported component, intent, deep link, or PendingIntent. N/A.
- **[Network & I/O]** No network change; `confirmPairingAndConnect` unchanged. N/A.
- **[Error messages / logs]** Inline error is a fixed constant ("Invalid pairing code") — no payload
  echo. Bad paste deliberately avoids the full-screen path; neither path logs a field value. No finding.
- **[Concurrency]** No new coroutine/flow. Synchronous `MutableStateFlow` set, no TOCTOU; the sole
  persist launch stays behind the Confirm button on the lifecycle scope; existing `as? AwaitingConfirm`
  guard prevents a save after the gate closes. No finding.
- **[Threat model alignment]** Closes the impostor-server MITM gap for the paste entry path, aligning
  it with the QR path's #343 control. **OUT OF SCOPE (pre-existing, not introduced here):** clipboard
  and third-party-keyboard capture of the pasted token are inherent to allowing paste at all (#503);
  this ticket neither introduces nor worsens them, and the fingerprint-compare gate it adds actually
  mitigates the *impostor* consequence even if the token is observed. No owner assigned; note only.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
