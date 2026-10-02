# #1484 — Question actions match the frames when disabled, with the keyboard open and at large text

## Files read

- `ui/conversations/thread/QuestionBatchModal.kt` — `QuestionBatchActions` (the Cancel/Continue pair, its
  `stacked` branch and Continue's default disabled colours) and `QuestionBlock` (the Other field's
  `BringIntoViewRequester`, the `LaunchedEffect(focused, imeBottom)` and the `onFocusChanged` launch).
- `ui/conversations/thread/ThreadScreen.kt` — the `shownQuestion` items in the thread `LazyColumn`
  (`question-actions:<generation>` first, under `reverseLayout = true`), the `permission-rejection` item emitted
  before it, and `listState`.
- `ui/conversations/thread/ThreadListFollow.kt` — `FollowNewestEnd`/`pinToNewest`: the newest end is index 0,
  pinned with `scrollToItem(0)`; a held finger refuses the scroll with a `CancellationException`.
- `ui/conversations/thread/ThreadPermissionModal.kt`, `ui/components/MobileModal.kt` — explicit
  `ButtonDefaults.buttonColors(containerColor = primary, contentColor = onPrimary)` precedents.
- `androidTest/.../design/PromptsDesignCaptureTest.kt`, `DesignCapture.openKeyboard`,
  `androidTest/assets/design-1220/prompts/index.md` and `README.md` (capture command).
- `sharedTest/.../thread/ThreadInlineQuestionTest.kt` — where the new Robolectric screen tests sit.
- `docs/knowledge/features/question-batch-modal.md` § Rendering — the focus scroll was documented as carrying
  "it and the actions into view", which only the field's own requester actually did.

In-flight overlap: #1483 touches `ThreadScreen.kt` (status area, `PERMISSION_ROW_COUNT`) and the audit
`index.md` (permission items). Different blocks; my edits stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=635-2036

`636:3535` (in `636:3279`): the disabled Continue is the enabled button — `Schemes/Primary` fill,
`Schemes/On Primary` `bodyLarge` Medium label, 6 dp radius, 20×8 padding — with `opacity: 38%` on the whole
button. `636:3803`: with the keyboard open the stream shows the focused Other field of the second question and
the side-by-side pair, the pair's bottom at the stream's bottom edge above "Waiting for answers". `636:4325`
(in `636:4066`): the stacked pair is a 143.5 px wide column at the card's start edge, Cancel (118 px) centred
over Continue (143.5 px), 12 px apart.

## Context

The #1433 audit found the three differences above. The keyboard one is a behaviour bug: the actions are their
own lazy item, so the Other field's `bringIntoView()` only reveals its own row, and the remaining scroll
depends on how the focused-child-in-view handling of the shrinking viewport and the text field's own cursor
reveal interleave with the IME animation. That is why the offset varies between runs.

## Design

1. **Disabled Continue.** `Button` gets `ButtonDefaults.buttonColors(containerColor = primary, contentColor =
   onPrimary, disabledContainerColor = primary, disabledContentColor = onPrimary)` and `Modifier.alpha(0.38f)`
   while disabled. Group alpha (not per-colour alpha) is what Figma's layer opacity draws: the label is
   composited on the fill first, then the whole button is faded. Enabled Continue is unchanged.
2. **Stacked pair.** The stacked `Column` drops `fillMaxWidth()`, so it wraps to the wider Continue and sits
   at the `BoxWithConstraints`' start; `horizontalAlignment = CenterHorizontally` keeps Cancel centred over
   Continue. The side-by-side `Row` is untouched.
3. **Keyboard reveal.** `QuestionBlock` takes a new `revealActions: suspend () -> Unit` (default no-op, so
   other callers and previews are unaffected). The field's reveal becomes one local suspend step, used by both
   the `onFocusChanged` launch and the `LaunchedEffect(focused, imeBottom)`: `revealActions()`, then
   `requester.bringIntoView()`. `ThreadScreen` passes `{ listState.scrollToItem(actionsIndex) }`, where
   `actionsIndex` is the actions item's index: `1` when the `permission-rejection` item precedes it, else `0`
   (permission rows and the question are never shown together, since `shownQuestion` is null while a request is
   open). Under `reverseLayout`, `scrollToItem(i, 0)` puts that item's bottom at the viewport's bottom, and the
   field's `bringIntoView()` afterwards scrolls only if the field is still not fully visible (an earlier
   question, or a stack taller than the viewport), so the focused field always wins over the actions. The
   effect reruns on every IME inset change, and its last run is on the settled inset, so the final position
   is the same on every run.

## State and concurrency model

No new state. `revealActions` runs in the `LaunchedEffect` (cancelled on recomposition with a new inset or on
leaving composition) and in the composable's `rememberCoroutineScope`. A finger held on the list refuses
`scrollToItem` with a `CancellationException`, which ends that reveal quietly; the user is scrolling.

## Error handling

None new; scroll refusal is above.

## Testing strategy

- `ThreadInlineQuestionTest` (sharedTest, Robolectric), new tests:
  - `focusing_other_reveals_the_field_and_both_actions`: a two-question batch with history, scrolled so the
    second question's Other field shows but the actions do not; clicking the field leaves the field, Cancel and
    Continue fully inside `thread-message-region`. Red before: only the field was brought into view.
  - `stacked_actions_sit_at_the_start_with_cancel_centred_over_continue`: font scale 1.5 at the 320 dp
    Robolectric width (stacked), Continue's left equals the actions column's left and Cancel's centre equals
    Continue's centre.
- The disabled colour is pixel-only: judged in the device capture `question-unanswered.png`.
- `PromptsDesignCaptureTest#questionKeyboardFrame` (device, `pixel8Api35`): after `openKeyboard`, before any
  further scroll, asserts `question_other_1`, Cancel and Continue are displayed and their bounds lie inside
  `thread-message-region` (so not under the composer). Device-only because it needs a real IME inset.
- Re-capture: the whole `PromptsDesignCaptureTest` class in one run with the README command, then
  `scripts/design-compare.py` for the three question captures, and update the `636:3279`, `636:3803` and
  `636:4066` items in `index.md` for these aspects. Existing coverage run: `ThreadInlineQuestionTest`,
  `QuestionBatchModalTest` (device, keyboard reachability).

## Open Questions

- Whether `scrollToItem` before the field's `bringIntoView()` is stable across the IME animation on the
  device. Resolved: see Revisions.

## Documentation handoff

- `docs/knowledge/features/question-batch-modal.md` § Rendering — pending: the focus scroll now places the
  actions item at the stream's bottom edge before bringing the field into view; disabled Continue is the
  enabled colours at 38 % group alpha; the stacked pair is start-aligned with Cancel centred over Continue.

## Revisions

### 2026-10-02 — the reveal waits a frame before scrolling

- **What changed.** The reveal runs `withFrameNanos {}` before `revealActions()`.
- **What drove it.** The first device run of `questionKeyboardFrame` crashed with "performMeasureAndLayout called
  during measure layout": when a focused question's lazy item is composed inside the list's measure pass, its
  `LaunchedEffect` starts there, and `LazyListState.scrollToItem` forces a remeasure, which is illegal inside
  measure. The field's own `bringIntoView()` never forced a remeasure, so the old code did not hit this.
- **New contract.** The reveal scrolls on the next frame, outside layout, then brings the field into view. The
  Open Question is resolved: the next full-class run passed except for the harness's known 332 px keyboard
  (`the test IME's keyboard is open`, before the reveal is asserted), and the following full-class run passed
  6 of 6 with the field and both actions inside the message region.
