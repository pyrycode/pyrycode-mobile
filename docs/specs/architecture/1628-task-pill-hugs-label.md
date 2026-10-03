# #1628 — The background task pill hugs its label

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the task `NoticePill` call in `ThreadStatusArea`, whose modifier carries the 104dp minimum width.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: `PillHorizontalPadding` (8dp) and the label's `weight(1f, fill = false)`, which leaves the surplus of a minimum width blank to the label's right.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt`: the existing geometry cases and the new one's home.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3162

A hug-width row with 8dp horizontal and 4dp vertical padding, `primaryContainer` fill, 6dp radius, and a `bodySmall` `onPrimaryContainer` label. Its 104×24 frame is the hug size of "2 tasks running", so the design needs no edit.

## Change

The task pill's modifier changes from `sizeIn(minWidth = 104.dp, minHeight = 24.dp)` to `sizeIn(minHeight = 24.dp)`. The pill then measures its label plus padding, so "1 task running" no longer shows blank pill to its right. The band's reading `Box` keeps `weight(1f)`, so the pill stays at the band's right end. Nothing else moves; "2 tasks running" still hugs to about 104dp.

## Testing strategy

A new case in `TaskCountPillTest` sets one task, alone and beside a thinking reading. It measures the unmerged label node and asserts the pill is the label's width plus 16dp, 24dp tall, with its right edge at the root's right minus `ComposerGutter`. It fails against the 104dp minimum. The existing `TaskCountPillTest` cases, including `figmaVariant_anchorsTaskPillAtTheInputAreasTopRight`, compact width with large text, and the pointer-target case, must still pass unchanged.
