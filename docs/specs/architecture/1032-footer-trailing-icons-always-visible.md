# #1032 — Keep the paperclip and Status opener visible when the composer footer is full

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ThreadComposerFooter`, `FooterButton`, `ContextSegment`. This is the only production file the change touches.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` shows the existing footer screen tests, which run at Robolectric's 320dp width. The new test sits beside them.
- `docs/knowledge/features/thread-composer-footer.md` § the `Cxt:` segment and § the paperclip (#933). The segment's `weight(1f)` was meant to be "measured last" so the opener keeps the end. It is, but the icons after it are measured in sequence with whatever the buttons leave, which is the bug.
- `docs/knowledge/features/development-verification.md` § "Where a screen test goes". It covers `DeviceConfigurationOverride.ForcedSize` for a wider screen and `@GraphicsMode(NATIVE)` for exact text measurement. Both are needed here: without real fonts the label widths are not the device's, and the test could pass while the layout is still broken.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=115-3654

The node is Figma's `Attachment` glyph: an 11×12 paperclip tinted `Schemes/Primary`. It sits at the trailing end of the `Input footer` (110:3494). This ticket changes no visuals. The paperclip and the Status opener keep their sizes, tints and trailing position. Only the width the text controls take changes when they overflow.

## Context

`ThreadComposerFooter` is a plain `Row` with this order: Actions, permission, model, effort, `ContextSegment` (`weight(1f)`), paperclip, Status opener. A `Row` measures non-weighted children in order, each against the width still left. At 1080 px (about 411dp) with "Actions, Manual approval, default, medium", the four buttons and the 16dp gaps use up the width. The two 32dp icon boxes are then measured at 0 width and disappear. The fix makes the icons claim their width first. The text controls then share what remains.

## Design

The footer gets two regions:

- **Outer `Row`** (unchanged padding and 16dp `spacedBy`). It holds a text region with `Modifier.weight(1f)`, then the paperclip box, then the Status opener box. Weighted children are measured after fixed ones, so both icon boxes always get their 32dp.
- **`FooterTextRow`**, a private `Layout`. Its children are the footer buttons followed by the `ContextSegment`, and it places them left to right with the same 16dp gap.
  - Each button is measured at `min(natural, cap)`. Its natural width is its `maxIntrinsicWidth`, and `cap = footerShrinkCap(naturalWidths, available)`.
  - The `ContextSegment`, the last child, gets whatever width the buttons leave, which may be 0. It gives up its space first, as it does today.
- **`internal fun footerShrinkCap(widths: List<Int>, available: Int): Int`**, pure. It returns `Int.MAX_VALUE` when `widths.sum() <= available`. Otherwise it returns the largest cap `L` for which `sum(min(w, L)) <= available`. The widest labels give up space first, and no button is squeezed out while another keeps its full label.
- **`FooterButton`**: the label `Text` gets `Modifier.weight(1f, fill = false)` inside the button's own `Row`. The chevron is then measured first, and a capped button ellipsizes its label instead of dropping its chevron.

A plain nested `Row` was rejected. It would keep the icons, but it measures buttons in order, so at 1080 px the effort button would get about 9dp and vanish instead of the paperclip. Equal `weight(fill = false)` on every button was also rejected. It does not hand unused width back, so it would ellipsize a long model label even when the whole row fits.

## State + concurrency model

None. This is layout only. No state, flows or jobs change.

## Error handling

None. There are no new failure modes.

## Testing strategy

- **Unit test** `ThreadComposerFooterLayoutTest` (in `app/src/test`) covers `footerShrinkCap`:
  - everything fits → `Int.MAX_VALUE`;
  - one wide label over budget → only it is capped, and the result sums to exactly `available`;
  - everything over budget → an equal cap;
  - available is 0 → cap 0.
- **Compose screen test** in `ThreadComposerFooterTest` (shared, Robolectric, `@GraphicsMode(NATIVE)`) hosts `ThreadComposerFooter` inside `DeviceConfigurationOverride.ForcedSize` at 411dp. The run config shows "Manual approval", "default" and "medium".
  - The paperclip and the Status opener are each displayed, 32dp wide, and inside the footer's bounds.
  - Clicking each one fires `onAttach` or `onStatusClick` once.
  - The four buttons are all still displayed.
  - Written first, this test must fail on the current `Row` (RED).

This is not an operator-facing flow change, so it needs no rung-3 scenario. The existing paperclip and Status flows are unchanged, and only their layout is fixed.

## Open questions

- Does `@GraphicsMode(NATIVE)` apply per test method, or does the whole class need it? Use a method annotation if Robolectric honours it. Otherwise put the test in its own class.

## Documentation handoff

None requested by the ticket. The documentation stage may note in `docs/knowledge/features/thread-composer-footer.md` that the text controls now shrink before the trailing icons do (pending).

## Revisions

- **2026-09-24 (build).** The open question is resolved. The screen test is its own class, `ThreadComposerFooterWidthTest`, annotated `@GraphicsMode(NATIVE)` at class level like `EditHostModalTest`. This leaves the existing 320dp `ThreadComposerFooterTest` measuring as before. Before the fix, it failed with the paperclip squeezed to 24.5dp.
- **2026-09-24 (build).** The buttons and the `Cxt:` segment are written inline in `FooterTextRow`'s content lambda. They are not a separate composable. Android Lint's `MultipleEmitters` rejects a helper that emits several top-level nodes. The contract is unchanged.
- **2026-09-25 (rework 1).** The design is unchanged. The verifier blamed `ThreadComposerFooterWidthTest` for 11 device-only failures, but the same tree at 84f91c98 passed the full device-only gate 66/66 when run alone. It then failed the same 11 tests while verifier-1067's gate ran at the same time, and that run, on an unrelated branch without this test, failed the same 11 too. The flake comes from overlapping gate runs on one host, filed as #1071. The width test stays in `app/src/sharedTest`.
