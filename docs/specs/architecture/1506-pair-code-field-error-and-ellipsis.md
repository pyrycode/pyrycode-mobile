# #1506 — Pair-code field error inset and long-code ellipsis

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairCodeScreen.kt` — the private `PairCodeField`: its `decorationBox` and the `error` `Text` under the field row.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenFormStatesTest.kt` — the Pair Code States screen test (#1464) the new tests sit beside.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsRowLayoutTest.kt` — the `GetTextLayoutResult` pattern for asserting overflow.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=663-2886

Pair Code States: in `663:3191` and `663:3331` the supporting text (`bodySmall`, `colorScheme.error`) sits 16 dp in from the field's start edge and 4 dp below its underline. In `663:2887`, `663:2963`, `663:3039`, `663:3115` and `663:3331` a long code that is not being edited ends with "…" before the field's end inset (before the clear icon when shown, 16 dp from the edge when read-only).

## Change

The `error` `Text` in `PairCodeField` gains `padding(start = 16.dp, top = 4.dp)`. In the `decorationBox`, while the field is not focused and has a value, the `innerTextField` is kept composed but drawn at `alpha(0f)`, and a single-line `Text(value, overflow = TextOverflow.Ellipsis)` in the same text style draws over it, tagged `"<label> value"` and hidden from accessibility so the field's own semantics stay the only reader of the value. When focused, only `innerTextField` shows, so editing and scrolling are unchanged. Nothing outside `PairCodeField` moves.

## Testing strategy

New `PairCodeScreenFieldLayoutTest` in `app/src/sharedTest/.../ui/onboarding/` (`@GraphicsMode(NATIVE)` for real text measurement):

- error text left − pairing-code field left = 16 dp and error top − field bottom = 4 dp, for both `INVALID_CODE_ERROR` and the re-pair `WRONG_HOST_ERROR`;
- a long unfocused code's `"Pairing code value"` layout has its line ellipsized, and its right edge sits at or left of the clear icon (editable) or field right − 16 dp (read-only);
- after focusing the field the overlay is gone and the field's editable text is the full value.

Existing `PairCodeScreenFormStatesTest`, `PairCodeScreenVerificationTest`, `PairCodeScreenInsetsTest` run alongside; `compileDebugAndroidTestKotlin` covers the device `PairCodeScreenTest`, which reads values through the field's `contentDescription` node and so is unaffected.
