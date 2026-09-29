# Pair-code form visual alignment (#1269)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` → `PairCodeScreen`, `PairCodeField`: current full-screen layout, field behavior and phase presentation.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeViewModel.kt` → `PairCodeViewModel.onEvent`, `PairCodeState`: validation, targeted re-pairing and fingerprint-before-save contract.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModalShell`: current dark modal spacing, field palette and IME reachability precedent.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme`: fixed dark scheme roles and field colors.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` → `show`, `softwareKeyboardKeepsBothFieldsClearControlsAndActionsReachable`: real IME and capture harness.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenInsetsTest.kt` → `backMatchesScannerBelowStatusBarAndActionsSitAboveNavigationBar`: inset and header contract.
- `docs/knowledge/features/paste-code-dialog.md` § Form and rendering: the form's current behavior, scroll sizing and field clearing lesson.
- `docs/knowledge/features/mobile-modal.md` § Layout and theme: current mobile shell takes precedence over older form geometry.
- `docs/knowledge/features/development-verification.md` § Device gate: real keyboard and screenshot tests belong in `androidTest`.

## Design source

**Figma:** [mobile pair screen, 533:2147](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2147); [pair form content, 487:2498](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=487-2498); [mobile modal shell, 533:2369](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369). Inspected 2026-09-29.

The 412 × 892 mobile frame has a surface-dark full-screen canvas, 20 dp header insets, back arrow, title-large Pairing, a thin inverse-primary divider, two widely spaced navy filled fields over a blue radial glow, and bottom 56 dp Pair and Cancel actions above a small repository footer. The pair form content supplies the field styling and labels, but its close glyph, code-first order, compact buttons and floating desktop card conflict with the current mobile route; the mobile frame and existing controls win. The modal shell confirms the dark palette and header/divider treatment; the pair-code route stays full-screen.

## Context

`PairCodeScreen` is already the production route for new pairing and targeted re-pairing. Its M3 fields and empty-label placement differ from the current mobile frame. This ticket changes presentation and responsive reachability while leaving `PairCodeViewModel`'s credential trust boundary intact.

## Design

- Keep `PairCodeScreen(state, onEvent, modifier)` and its phase handling. `Confirming` continues to render the scanner fingerprint gate; `Saving` and `Connecting` keep their existing disabled/progress labels; Back, Cancel and Retry emit the same events.
- Retune the header, glow, field geometry and footer to node 533:2147, using existing M3 scheme and typography roles. Use the exact 24 dp Figma back-arrow asset as a vector resource if the existing material glyph differs.
- `PairCodeField` presents its label at the top of the filled container even for an empty draft, text below it, and a trailing clear control. The host is read-only for targeted re-pairing. Place safe invalid/wrong-host feedback with the code field; other failures remain near the actions. Keep text semantics and field focus usable for the IME.
- Preserve the single scrollable body and intrinsic viewport sizing while making the focused field and bottom actions reachable on 360 dp width, enlarged text and visible IME. No new ViewModel state or data flow is needed.
- Evidence uses only empty or synthetic non-credential drafts. Commit current Figma renders, nonblank emulator captures at matched logical viewport including 412 × 892, and a labelled overlay/difference image under `app/src/androidTest/assets/pair-code-1269/`.

## State and concurrency model

The form only observes `PairCodeState`; every edit calls `onEvent`. `PairCodeViewModel` retains its `viewModelScope` operation and cancellation on Back. Layout and capture tests add no jobs, network I/O or persistence.

## Error handling

`INVALID_CODE_ERROR` and `WRONG_HOST_ERROR` stay inline with the code. Other existing errors remain above the action. No exception or raw pairing payload reaches a composable error or log. UI tests use fixed safe strings.

## Testing strategy

- Add a failing Compose/device assertion for the empty-label position and responsive action reachability, then make the visual change pass. Keep the existing route, target-name, clear, error, confirmation and IME tests green.
- Run focused `PairCodeScreenTest` on the managed API 33 device because it needs the real IME and actual pixels. Run focused `PairCodeScreenInsetsTest` under Robolectric, then lint, debug assembly and Android test compile.
- Capture empty-state 412 × 892 and compact/IME states with a real emulator; compare against the Figma render and inspect nonblank image dimensions before the PR. No real-Claude scenario is added: this is a presentation change to an existing operator flow, with no new daemon interaction.

## Documentation handoff

No documentation-only acceptance criterion was specified. The documentation stage owns any update to `docs/knowledge/features/paste-code-dialog.md` describing the final field styling and evidence.

## Open questions

- Does the existing back icon match the 24 dp Figma asset exactly? Resolve by inspecting the paths before implementation.
- Does the current `TextField` API support a permanently minimized in-container label at the needed geometry? Resolve with a focused device render; use the smallest field implementation that matches and preserves semantics.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. `PairCodeViewModel.onEvent` alone parses draft text and checks the target ID before opening `ScannerUiState.AwaitingConfirm`; the screen never interprets the payload.
- [Tokens and storage] No token generation, storage or rotation change. `PairCodeViewModel.persist` remains the only credential-write path after explicit confirmation; captures must contain empty or synthetic non-credential strings.
- [File and Android surface] No file paths, intents, providers, WebView or exported components are introduced. Evidence assets contain only UI pixels.
- [Cryptography and network] The existing parser, fingerprint function, store and connection supervisor are unchanged. This plan neither changes Noise nor relay URL handling.
- [Errors and logs] No payload values in logs, screenshots or test failure messages. Existing fixed safe error strings remain the only UI feedback.
- [Concurrency] No new coroutine or state owner. Back continues to cancel the ViewModel operation; display state follows its existing phases.
- [Threat alignment] A hostile pairing payload remains subject to the existing parser, target-ID check and fingerprint confirmation gate. Screen-overlay and third-party keyboard threats are outside this presentation ticket and require a separate security design if prioritized.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-29

## Revisions

- 2026-09-29: The existing Material back arrow has different geometry from node 533:2151, so the route uses a 24 dp vector traced from that supplied SVG. The string-based M3 `TextField` API does not expose the permanently minimized label position available to the state-based overload; a controlled `BasicTextField` keeps `PairCodeState` as the sole draft owner and renders the empty label at the top of the filled container.
- 2026-09-29: The real 412 × 892 capture showed that the scaffold owns the top and bottom system insets. The screen's header now starts at the consumed top inset, and its bottom body padding accounts for the already consumed navigation inset. `PairCodeScreenInsetsTest` now asserts the pair route's Figma header and safe-area contract directly instead of requiring its Back control to match `ScannerScreen`'s older offset.
- 2026-09-29: The verifier's device gate exposed the remaining scanner-parity assertion in `MainActivityInsetsDeviceTest.exercise`. The pair route's Back target starts at the status inset as the mobile frame and `PairCodeScreenInsetsTest` require; the scanner keeps its own 18 dp inset. The real-activity test now checks each route against its own header contract.
