# #1679 Status dots follow the redrawn states

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: `ConversationStatusDot` owns paint and blink; `TreeConversationRow` is its only caller (repository search confirms the codegraph caller gap).
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` and `Theme.kt`: existing `primary`, `success` and `warning` tokens supply the design colours.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`: replace the previous redraw's ring/selected-fill assertion; reuse native hand-drawn view pixels because `captureToImage` does not settle here.
- `docs/knowledge/features/channel-list-screen.md` and `channel-list-screen-how-it-works.md`: selection remains the last opened row; dot descriptions remain accessible through the row.
- `docs/specs/architecture/1524-conversation-status-dot-colours.md`: nearest analogue, superseded paint rules.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` and `ChannelListColoursTest.kt`: existing list layout, selection, action and colour coverage.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SharedDarkColourCaptureTest.kt`: existing real-device colour capture.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=106-3077

Design context and screenshot show four circular 6dp marks: hollow blue Idle, solid blue Busy, solid green Success and solid gold Waiting. Reuse the existing Compose circle paint, the exact equivalent of these simple ellipse assets. Ticket #1679 pins blue to `primary` (#9DCBFC in static dark), idle opacity to 50%, green to `success` (#2FC038, confirmed by Figma variables) and gold to `warning` (#D8B85A). Only Idle retains the existing 1dp ring.

## Change

Use `primary` for Running's solid fill and Idle's half-alpha ring in every palette. Idle always has a transparent centre, including selected rows. Apply the border only for Idle; Unread and WaitingForAnswer retain their existing solid success/warning fills. Remove the now-unneeded private dot `selected` parameter and its sole argument. Keep size, semantics, row selection, and Running's eased 1 → 0.3 → 1 two-second blink unchanged. There is no new flow, operator action, state, error branch, logging or dependency. No in-flight branch overlaps these files. Estimated total written work is about 170 lines including tests and plan, with no exported declarations or public consumer changes and two acceptance criteria, within the sizing boundaries.

## Testing strategy

Test first in `ConversationTreeRowsTest`: render all four states plus selected Idle on known static-dark backgrounds at xxxhdpi, assert 6dp bounds, centre and ring-region pixel colours, and compare dot pixels across controlled blink half-periods. This rejects the old ring on solid states, the old peach Running fill, the old selected Idle fill, incorrect idle alpha, and blinking on any other state. Preserve existing semantics tests. Run the affected component class and both list screen classes under Robolectric, plus the existing `SharedDarkColourCaptureTest#sidebarAt412By892` device method for real colour evidence. This is a rendering retune of an existing flow; it adds no daemon interaction or new live scenario. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply, and forced spotlessCheck before handoff.
