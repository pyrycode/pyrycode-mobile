# 1270 — Fingerprint confirmation in the mobile modal

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt` → `ScannerScreen`, `PairingConfirmContent`: shared QR and code confirmation rendering.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal`, `MobileModalShell`, `ModalCancelButton`, `ModalSubmitButton`: existing dark shell, close asset, scroll and footer styling.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen`: manual route delegates confirming to `ScannerScreen`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `PairCodeViewModel.onEvent`: Back restores the unchanged draft; Confirm saves `confirmation.server`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → scanner route `BackHandler` and `onConfirmPairing`: QR Back declines; Confirm reads the current `AwaitingConfirm.server`.
- `app/src/main/java/de/pyryco/mobile/data/crypto/StaticKeyFingerprint.kt` → `staticKeyFingerprint`: exact 23-character colon-hex form to display verbatim.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/ScannerScreenTest.kt` → awaiting-confirm assertions and synthetic record fixture.
- `docs/knowledge/features/pairing-confirm-gate.md` → immutable displayed fingerprint / saved record binding; preserve comparison against desktop.
- `docs/knowledge/features/mobile-modal.md` → editing shell Back, close, safe-area, and compact-height behavior.
- `docs/knowledge/features/development-verification.md` → device captures need real pixels and nonblank checks.

## Design source

**Figma:** [verification content, 487:2559](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2559); [mobile modal shell, 533:2369](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369); [mobile pair screen, 533:2147](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147). Inspected 2026-09-29; Figma's node tools did not expose a last-modified timestamp.

The older wide verification frame uses a Pair title, a highlighted fingerprint field, centered explanation, close glyph, and two compact actions. The current 412 × 892 dark modal shell supplies the navy rounded full-height container, 28 dp inset, divider, centered content, and bottom action row; the pair screen establishes dark-theme context. There is no mobile verification frame or reference for keyboard-open confirmation, so preserve the existing explicit comparison instruction and decision labels while adapting the older content.

## Context

`PairingConfirmContent` is currently a plain full-screen column. One visual change to the shared rendering path reaches both QR and manual code pairing; the state and persistence contracts remain in their current owners. No in-flight feature branch overlaps the planned files.

## Design

- Render `PairingConfirmContent(fingerprint, onConfirm, onDecline)` inside the existing `MobileModal` shell with title `Pair`. Extend that shell's public entry point with defaulted action labels so this caller can retain its explicit `Don't pair` and `Confirm pairing` controls without changing existing callers. Close, Cancel/decline, and dialog Back invoke the same `onDecline` callback.
- Present the exact fingerprint in a full-width `surfaceVariant` panel with rounded corners and a selectable monospace text style. Let its text wrap at word boundaries in compact width and enlarged text. Keep the desktop `Static-key fp:` comparison instruction as readable centered body text; it is a current security control absent from the older frame.
- Use Material 3 roles and the shell's existing close asset, spacing, scrolling, footer and safe insets. No new asset or dependency is needed. The dialog owns window presentation; `ScannerScreen` continues to receive only the public fingerprint and callbacks, never a token.
- Preserve `ScannerUiState.AwaitingConfirm` and `PairCodeState.confirmation` without any signature or save-path changes. The QR and code routes keep their distinct decline transitions.

## State and concurrency model

No new state or job. `MobileModal` is stateless; its caller removes it when decline changes route state. QR confirmation continues to read the current `AwaitingConfirm.server`; manual confirmation continues to save its immutable `confirmation.server` in `viewModelScope`. The existing route Back handlers and dialog dismissal both dispatch the route's decline event.

## Error handling

No new I/O or parsing. The existing route-specific parse, save and connect errors remain unchanged. The modal logs only its existing static lifecycle event names; it receives no pairing payload, token, or server record.

## Testing strategy

- Add a shared Compose test that first fails on the old full-screen surface: verify modal title/close, exact synthetic fingerprint, comparison copy, and footer actions. Prove close and both actions call their intended callbacks, with no implicit save work in the renderer.
- Test compact width and 1.5× text scale with the full fingerprint and both actions displayed and reachable. Existing ViewModel and route tests continue to cover immutable record binding and route-specific Back/Decline; run the focused classes.
- A device-only screenshot test may use a synthetic `AwaitingConfirm` fixture to save real rendered captures at 412 × 892 and compact enlarged text. Capture with a full emulator image, reject blank output, compare beside current Figma renders with a labelled overlay/difference, and record device geometry and unreferenced keyboard state. No real payload enters the captures.
- Run focused unit tests, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin` if device capture code is added, and `spotlessApply`. A real-Claude stream scenario is not applicable because the ticket changes presentation of an existing gate without adding a new operator action or daemon exchange.

## Open questions

- Does the current device image permit nonblank screenshot capture at a matching 412 × 892 logical viewport? Resolve in device verification and note the image/runtime details in the PR.

## Documentation handoff

- Pending documentation stage: update `docs/knowledge/features/pairing-confirm-gate.md` sections “What it does”, “`PairingConfirmContent`”, and “Edge cases and limitations” to describe the mobile modal and replace the obsolete design-later note. Update `docs/knowledge/features/mobile-modal.md` caller contract for defaulted action labels and this caller.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The untrusted QR/code payload is parsed before `AwaitingConfirm`; this presentation receives only the derived public fingerprint. It does not reinterpret or trust payload text.
- [Tokens, storage] No new credential generation or storage. `PairingConfirmContent` receives no token; QR and code handlers continue to save the immutable pending record only on Confirm.
- [Files and Android surface] No file, intent, deep link, provider, WebView, or exported component is added. Test captures use synthetic records only. Real payloads must not enter logs or screenshots.
- [Cryptography and network] Fingerprint derivation, relay validation, Noise, and transport remain untouched. The exact 23-character result remains unmodified; a UI reformatted value would defeat human comparison.
- [Logs and telemetry] Existing `MobileModal` event logs are static and debug-only; no fingerprint or payload value is logged. No telemetry is added.
- [Concurrency] No new coroutine or shared mutable state. The existing current-state guard on QR Confirm and synchronous manual Saving transition remain authoritative.
- [Threat model] A malicious payload can display its own key fingerprint, which the human must compare with the desktop. Overlay attacks remain a cross-cutting hardening topic described in `pairing-confirm-gate.md`; this visual ticket does not modify Activity window security. The public fingerprint is intentionally selectable, and screenshot evidence uses only a synthetic fixture.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-29

## Revisions

- 2026-09-29 verifier review: use `MaterialTheme.shapes.small` for the fingerprint panel instead of a local corner shape. This is the closest existing theme role to the older verification field; preserve the field's dimensions and compare the refreshed emulator capture with node `487:2559`. Render the confirmation preview in the fixed dark theme.
