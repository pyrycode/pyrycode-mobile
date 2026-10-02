# #1483 — Inline permission request matches the Questions and permissions frames

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` — `permissionRequestItems`
  (the three lazy items: Cancel, card, title), `PermissionRequestCard`, `PermissionContext`, `ModalOptionButton`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — `PERMISSION_ROW_COUNT` (feeds
  `FollowNewestEnd`'s `promptRows` and the oldest-row index), `ThreadStatusArea` and its `waitingForAnswers` arm.
- `app/src/main/res/values/strings.xml` — `modal_context_blocked_path`, `modal_armed_option_desc`,
  `question_waiting_for_answers`.
- `app/src/sharedTest/.../thread/ThreadScreenModalTest.kt` — the #1306 inline-request tests, including
  `arrival_and_grant_toggles_keep_a_history_reader_anchored_without_history_demand`, which scrolls to the oldest
  row by index (30 rows plus the prompt rows).
- `app/src/sharedTest/.../thread/ThreadStatusBandTest.kt` — the band tests and the waiting-for-answers precedent.
- `app/src/androidTest/.../design/PromptsDesignCaptureTest.kt` and `app/src/androidTest/assets/design-1220/prompts/index.md`
  — the #1433 audit captures and items this ticket re-captures.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=635-2036 (frames `639:2242`, `639:2451`,
`639:2666`, `639:2882`, `639:3099`, `639:3308`)

The pending permission prompt is a 12 px column: the card, then an outlined Cancel at the start edge. The card
(`Schemes/Background` fill, `Schemes/Primary Container` 1 px border, 16 px padding, 16 px gaps) opens with the
title, 16 sp Roboto Medium, 24 lh, `Schemes/On Background` (`titleMedium`), then the prompt. The context row for
the path is labelled "Folder". In the armed frames the choices column (8 px gaps) carries "Tap Allow once again to
confirm." between the armed tonal button and the next choice, 12 sp Medium, 16 lh, `Schemes/On Primary Container`
(`labelMedium`). The status area shows the snowflake glyph and "Waiting for permission" in `M3/body/small`,
`Schemes/Primary`.

## Change

`permissionRequestItems` emits two items, Cancel and the card; the title moves into `PermissionRequestCard` as the
Column's first child, `titleMedium` in the card's on-background content colour, keeping `heading()` and the
`permission-request-title` tag. `PERMISSION_ROW_COUNT` becomes 2. Cancel's box aligns `TopStart` instead of
`Center`; the existing item gutters (4 dp each) plus the button's 48 dp touch floor around its 40 dp surface already
put its visible top 12 dp under the card. `modal_context_blocked_path` reads "Folder".

In `PermissionRequestCard`'s option loop, the armed option (`option.id == armedOptionId`) is followed by a plain
`Text` from a new `modal_armed_option_hint` ("Tap %1$s again to confirm.") formatted with the bounded server label,
`labelMedium`, `onPrimaryContainer`. It sits inside the same 8 dp `spacedBy` column, so it appears only while that
option is armed and leaves with the arm. The default option is never armed (the VM sends it on one tap), so only
the armed non-default shows a hint. The `stateDescription` marker stays.

`ThreadStatusArea` gains `waitingForPermission: Boolean`, passed `openRequest != null && connectionState ==
Connected`. When `waitingForAnswers` is false and `waitingForPermission` is true, the reading box shows
`thread_status_waiting_for_permission` ("Waiting for permission") in the waiting-for-answers text's style; the
snowflake glyph stays (the frame draws it, unlike the question glyph). A question batch is never shown while a
permission is open (`shownQuestion` is null then), so "Waiting for answers" is untouched. While not connected the
existing connection reading shows instead.

The plain `Text` hint is a server-string sink like the label itself: bounded by `MAX_PERMISSION_TEXT`, no
markdown, no selection.

## Testing strategy

Robolectric screen tests in `ThreadScreenModalTest`:

- title is inside `permission-request-card` (`hasAnyAncestor`) and keeps `Heading`;
- Cancel's left edge equals the card's left edge;
- the blocked-path row reads "Folder";
- armed non-default shows "Tap Allow once again to confirm." between the armed button and the next choice; no hint
  when nothing is armed; the hint leaves when `armedOptionId` returns to null;
- status band reads "Waiting for permission" while a request is open and connected, not while connecting, and
  "Waiting for answers" is absent.

`arrival_and_grant_toggles_keep_a_history_reader_anchored_without_history_demand` scrolls to the oldest index,
which drops from 32 to 31 with two prompt rows; `ThreadScreenFollowTest` re-runs to prove `FollowNewestEnd` still
reveals the request.

Device: `PromptsDesignCaptureTest#permissionFrames` and `#permissionCompactFrame` re-run on `pixel8Api35` (real
pixels, `FLAG_SECURE` decor draw; device-only by nature as in #1433), compared with `scripts/design-compare.py`,
and the six permission items in `prompts/index.md` updated. The device copy of the `ThreadPermissionCaptureTest`
component host re-runs because it asserts the armed state description.

No rung-3 scenario: the flow's behaviour is unchanged (layout and copy only), and
`InteractiveStreamE2ETest`'s permission scenarios read the tags and strings this keeps.
