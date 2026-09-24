# #981 — The thread follows a new newest row, streaming or not

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, the `else` arm holding `listState`, `hasStreamingMessage`, `userScrolledAway`, `autoScrollNestedScroll`, the at-bottom reset effect, the #777 oldest-end demand effect and the streaming auto-pin effect. The only production file this ticket changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` → `ThreadRow.listKey` — the row key the `LazyColumn` anchors its scroll position on; read, not changed.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt` → `rows`, `threadState`, `setScreen` — the Robolectric screen-test shape the new test mirrors, and the #777 lesson that a `layoutInfo` test must overflow the viewport or it passes by accident.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `assistantDelta_interleavesWithMessagesAndTools_inArrivalOrder`, `modalShownEnvelope`, `modalDismissedEnvelope`, `assistantRowOf` — the fold's existing proof and the helpers for the permission-interposed replay.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` (its `@Ignore`), `readReplyDiagnosis`, `hostRepository`.
- `scripts/e2e-emulator.sh` (LIVE `TEST_TARGET`, PASS log) and `scripts/android-test-gate.py` (`LIVE_MINIMUM`) — #977 took the method out of both; this ticket restores it.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` § "Streaming auto-scroll" and § "The oldest-end history demand (#777)" — the pin, its yield flag, and the `rememberUpdatedState` + `snapshotFlow` idiom the new effect copies.

Overlapping in-flight branches, both additive: `feature/934` adds a paste receiver elsewhere in `ThreadScreen`; `feature/965` adds its own method to the same LIVE list and bumps the same `LIVE_MINIMUM` line, so whichever lands second resolves that one number.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread screen: top app bar, a column of assistant (left) and user (right) bubbles whose newest message sits directly above the composer, then the status row, attachment strip, input and footer. This ticket changes no visual; it makes the newest row actually be the one drawn above the composer.

## Context

#977 found that on the live #687 run the daemon sent the allowed Read's reply and the phone composed no bubble holding it. The ticket names two causes.

**Cause 1 ruled out — the fold keeps the reply.** The repository fold already has a test for `message → tool_use → tool_result → assistant_delta → turn_end` (`assistantDelta_interleavesWithMessagesAndTools_inArrivalOrder`). This ticket adds the #687 order with the permission modal between `tool_use` and `tool_result` (`modal_shown`, `modal_dismissed`), and asserts the finalized assistant row carries the text. It passes on `main`, so the thread's items hold the reply after `turn_end`.

**Cause 2 is the failure.** `LazyListState` keeps its first visible item anchored by key. Under `reverseLayout = true` a new newest row is inserted at index 0, the previously first row moves to index 1 and stays anchored at the bottom edge, and the new row lands below the viewport, uncomposed. The only thing that scrolls back to index 0 is the streaming auto-pin, which runs only while some row `isStreaming`. A one-line reply streams for one delta and finalizes at `turn_end` about 1.4 s after the allow; the conflated `StateFlow` can deliver it to the screen already finalized, or finalize it before the pin collects, so nothing ever scrolls to it. When the rows still fit the viewport the list's measure pulls index 0 back in to fill the empty space, which is why the ping turn shows and why #687 passed once and then failed: it depends on whether the thread (with the IME up) overflows by the time the reply arrives. The new screen test reproduces this deterministically on `main`.

No ADR needed.

## Design

One composition-scoped effect beside the existing auto-pin in `ThreadScreen`, the list's scroll handling only. The fold, the row keys and the streaming pin stay as they are.

- `val newestRowKey by rememberUpdatedState(rows.lastOrNull()?.listKey(rows.lastIndex))` — the key of the row drawn at index 0.
- `LaunchedEffect(listState) { snapshotFlow { newestRowKey }.drop(1).collect { if (!userScrolledAway) listState.scrollToItem(0) } }`.

Why this shape:

- **Keyed on the newest row's identity, not on `isStreaming`.** Any new newest row — a finalized reply, a tool row, the operator's own echo, an unmatched queued row — re-anchors at the newest end. A streaming row that grows keeps its key, so the existing size-driven pin still does that job alone.
- **`drop(1)` skips the first value.** `userScrolledAway` is plain `remember`, while `rememberLazyListState` is saveable; without the skip, a rotation would pull a reader who had scrolled away back to the newest end on recreation.
- **Older-history prepends do not move the newest key**, so the #777 walk and `a_prepended_page_leaves_the_row_the_reader_is_looking_at_where_it_was` are untouched.
- **The yield is the existing flag.** `userScrolledAway` is set only by `NestedScrollSource.UserInput`, and the key-anchored measure after an insert does not set it, so "the reader was at the newest end" survives the insert and the effect restores it. A reader who dragged away keeps `userScrolledAway = true` and is not moved, for streaming and non-streaming arrivals alike.
- `scrollToItem(0)`, not `animateScrollToItem` or `requestScrollToItem`: the same primitive the streaming pin uses, instant, and it does not dispatch as user input, so it cannot trip its own yield.

## State + concurrency model

One more `LaunchedEffect(listState)` in the composition's scope, cancelled with the composition like its three siblings. `snapshotFlow` conflates, so a burst of arrivals costs one scroll after the last change. Main dispatcher, no new jobs outside composition.

## Error handling

None: no I/O, no new failure mode.

## Testing strategy

- **Screen test (new, `app/src/sharedTest/.../thread/ThreadScreenNewestRowTest.kt`, Robolectric).** Thirty non-streaming rows so the list overflows (the #777 lesson), at the newest end:
  - a complete, non-streaming assistant message appended to `items` is displayed — RED on `main`, GREEN with the fix;
  - after a real drag away from the newest end (`performTouchInput { swipeDown() }`, which goes through `NestedScrollSource.UserInput`; `performScrollToIndex` does not), a non-streaming arrival leaves the row the reader was looking at displayed and the new row not displayed;
  - same, with a streaming arrival — the existing yield still holds for the streaming pin.
- **Repository test (new, beside `assistantDelta_interleavesWithMessagesAndTools_inArrivalOrder`).** The #687 frame order with a permission `modal_shown` / `modal_dismissed` between `tool_use` and `tool_result`; the assistant row is present, finalized, and carries the text. The cause-1 proof; green on `main`.
- **Rung 3.** Remove the `@Ignore` on `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, restore it to the LIVE `TEST_TARGET` in `scripts/e2e-emulator.sh` (and its PASS log clause), and raise `LIVE_MINIMUM` back to 21 so `test_live_floor_matches_the_curated_list` holds. `readReplyDiagnosis` gains `threadHeldToken` — whether the phone repository's `observeMessages(chat)` holds an assistant row containing the token — so a future live failure names cause 1 or cause 2 by itself (a boolean, never the text). The dispatcher runs the live suite after verifier (`needs-real-claude`).
- Existing thread screen tests in `app/src/sharedTest/.../thread/` re-run as touched scope.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` § "Streaming auto-scroll": the newest-row pin that now sits beside the streaming pin, and cause 2's mechanism (key anchoring under `reverseLayout` hides a non-streaming newest row once the list overflows).
- `docs/e2e-interactive-stream.md`: `LIVE_MINIMUM` back to 21 with the #687 method restored to the LIVE list (it currently says 20 "until #981"), and the new `threadHeldToken` field in the #977 diagnosis.

## Open questions

- Does Robolectric's key-anchored measure reproduce the hidden row on `main`? Expected yes (it is `LazyList` measure logic, not device behaviour); if not, § B1's tiebreaker applies.
