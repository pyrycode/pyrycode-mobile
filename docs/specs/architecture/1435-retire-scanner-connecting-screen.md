# #1435 — Retire `ScannerConnectingScreen`

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreen.kt`: `ScannerConnectingScreen` and its two `@Preview`s. Deleted.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/ScannerConnectingScreenTest.kt`: `ScannerConnectingScreenTest`. Deleted.

## Design source

N/A: the change removes an unreachable screen and changes nothing the user sees. Figma `32:20` is retired; the post-confirm wait is the loading pairing modal, `654:4882`, which already matches the scanner route.

## Change

Delete the composable file and its screen test. A search of `app/src/` finds no other reference to `ScannerConnectingScreen`: the scanner route keeps the confirmation modal up during `ScannerUiState.Verifying`. The screen uses only literal strings and theme tokens, so no resource or helper becomes orphaned and nothing else moves.

## Testing strategy

No new test. `testDebugUnitTest` on the onboarding screen and ViewModel tests, `assembleDebug` and `lint` confirm nothing depended on the removed symbol.

## Documentation handoff

Pending for the documentation stage:

- Delete `docs/knowledge/features/scanner-connecting-screen.md`.
- In `docs/knowledge/CATALOG.md`, replace the "Scanner Connecting screen" line with a one-line note: retired by #1435, `32:20` retired in Figma, and the post-confirm wait is the loading pairing modal, `654:4882`.
- Leave `docs/knowledge/codebase/` and `docs/specs/` untouched.
