# #1464 — Pair-code form states match the Pair Code States frames

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` — `PairCodeScreen` (the Pair `Button` label `when`) and `PairCodeField` (the trailing clear `IconButton`). The only production file.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — the modal's loading button: a 20 dp `CircularProgressIndicator`, 2 dp stroke, `colorScheme.primary`, 8 dp before the label. Mirrored, not shared.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` — `targetModeNamesTheHostReadOnlyAndShowsWrongHostOnTheCode` asserts the re-pair "Clear host name" exists disabled; it becomes `assertDoesNotExist`.
- `app/src/androidTest/java/de/pyryco/mobile/design/OnboardingDesignCaptureTest.kt` — `pairCodeFramesAt412By892` and `rePairFramesAt412By892` label captures with frame ids; the seven states take the `663:*` ids.
- `app/src/androidTest/assets/design-1220/onboarding/index.md` and `../README.md` — audit format and capture commands.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=663-2886 (frames `663:2887`, `663:2963`, `663:3039`, `663:3115`, `663:3191`, `663:3266`, `663:3331`)

The existing Pair Screen form. While Saving or Connecting the pill Pair button keeps its disabled container and label colours and draws a 20 dp indeterminate ring in `Schemes/Primary` before "Saving…"/"Connecting…", as the Button `State=Loading` variant. Read-only fields (both fields while busy or after a verification failure, Host name in re-pair) draw no trailing clear icon; editable fields, including one in error, keep it.

## Change

In `PairCodeScreen`, the Pair button content draws a `CircularProgressIndicator` (20 dp, 2 dp stroke, `colorScheme.primary`, 8 dp end padding) before the label when the phase is `Saving` or `Connecting`; enablement and labels are unchanged. In `PairCodeField`, the clear `IconButton` is drawn only when `enabled`; without it the decoration box takes a 16 dp end padding so text keeps the frame's right inset. Every call site already passes `enabled` as "editable" (`drafting`, or `false` in the `targetName` branch), so nothing else moves. Back and Cancel enablement is untouched.

## Testing strategy

New Robolectric screen test `app/src/sharedTest/.../ui/onboarding/PairCodeScreenFormStatesTest.kt`:

- Saving and Connecting each show an indeterminate `ProgressBarRangeInfo` node and the busy label; Editing shows none.
- Saving, Connecting and a held verification failure show neither clear icon; re-pair mode shows no "Clear host name" but keeps "Clear pairing code".
- An editable field with `INVALID_CODE_ERROR` keeps both clear icons enabled.

Device test `PairCodeScreenTest.targetModeNamesTheHostReadOnlyAndShowsWrongHostOnTheCode` changes its "Clear host name" assertion to `assertDoesNotExist`, run focused on `pixel2Api33Atd`. The design captures are recaptured with `OnboardingDesignCaptureTest` on `pixel8Api35` with `requireRealSystemBars=true`, the seven `663:*` frames exported, compared with `scripts/design-compare.py`, and `index.md` moves the seven states from Gaps to compared sections.

Overlap: #1463 (in flight) changes the header block of `PairCodeScreen` and recaptures the same onboarding PNGs; edits here stay in the button and field blocks, and whichever lands second recaptures.
