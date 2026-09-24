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
- **Rung 3.** Remove the `@Ignore` on `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, restore it to the LIVE `TEST_TARGET` in `scripts/e2e-emulator.sh` (and its PASS log clause), and raise `LIVE_MINIMUM` to 22 (see Revisions) so `test_live_floor_matches_the_curated_list` holds. `readReplyDiagnosis` gains `threadHeldToken` — whether the phone repository's `observeMessages(chat)` holds an assistant row containing the token — so a future live failure names cause 1 or cause 2 by itself (a boolean, never the text). The dispatcher runs the live suite after verifier (`needs-real-claude`).
- Existing thread screen tests in `app/src/sharedTest/.../thread/` re-run as touched scope.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` § "Streaming auto-scroll": the newest-row pin that now sits beside the streaming pin, and cause 2's mechanism (key anchoring under `reverseLayout` hides a non-streaming newest row once the list overflows).
- `docs/e2e-interactive-stream.md`: `LIVE_MINIMUM` at 22 with the #687 method restored to the LIVE list on top of #965's stop method (see Revisions), and the new `threadHeldToken` field in the #977 diagnosis.

## Open questions

- Does Robolectric's key-anchored measure reproduce the hidden row on `main`? Expected yes (it is `LazyList` measure logic, not device behaviour); if not, § B1's tiebreaker applies.

## Revisions

### 2026-09-24 — security review, and the pin survives a refused scroll

Driven by the verifier's MUST FIX on PR #988: the plan had no `## Security review` although #981 carries `security-sensitive`. The pass below ran against the design and the diff already on the branch. It found one real defect in the new effect (Concurrency, SHOULD FIX, fixed here):

- **The newest-row pin catches a refused scroll and keeps collecting.** `scrollToItem` runs under the list's `MutatorMutex` at default priority. A drag holds it at `UserInput` priority, and the mutex refuses a lower-priority scroll by throwing `CancellationException("Current mutation had a higher priority")`. A finger resting at the newest end leaves `userScrolledAway` false while the drag still holds the mutex, so a row arriving then gets its scroll refused. Thrown inside `collect`, that ended the `LaunchedEffect(listState)` for good. The streaming pin relaunches on every `hasStreamingMessage` flip, but this effect does not relaunch, so every later non-streaming row would land hidden until the screen left composition. The new contract is that the collect wraps `scrollToItem(0)` in `try`/`catch (CancellationException)` and calls `ensureActive()`. A refusal costs that one scroll, and a real cancellation of the effect still ends it. The streaming pin is unchanged.
- **New screen test** `a_scroll_refused_under_a_resting_finger_does_not_stop_later_rows_being_followed` in `ThreadScreenNewestRowTest` drags away and back to the newest end. The finger stays down. It appends a row whose scroll the held drag refuses, lifts the finger without a fling, and asserts that the next row is displayed. The test fails without the catch and passes with it.
- **`LIVE_MINIMUM` is 22, not 21.** #965 merged first and added its stop method to the LIVE list, so restoring the #687 method takes the floor from 21 to 22. `scripts/android-test-gate.py` now sets it to 22 directly. The merge had left `= 21` plus `+= 1`. The Testing strategy's "back to 21" and the Documentation handoff's "21" now read 22.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The only daemon-derived value the new effect reads is the newest row's `listKey`, a string that `ThreadRow.listKey` builds from the wire message id or a client-stamped id. The `LazyColumn` already uses it as its item key. The effect compares it for change and never renders, logs, parses or stores it. Daemon-authored assistant text reaches Compose through the existing `MessageItem` render path as text, and this ticket does not change that path, the fold or the keys. A hostile daemon that floods new newest rows can only scroll a reader who is already at the newest end to the newest end. `snapshotFlow` conflates a burst to one scroll per frame, and a reader who dragged away (`userScrolledAway`) is not moved.
- [Tokens, secrets] No findings. The token in the new e2e diagnostic `threadHoldsReply` is the #687 harness's file witness, a nonce written for the Read to find, not a credential. It is used only in a `contains` check. The diagnostic returns `"true"`, `"false"` or `"unread"`, never the token, the row text or an exception message. The peer's pairing token (`ARG_BYPASS_PEER_TOKEN`) is not touched.
- [File / storage] No findings for the production change: no file or storage I/O. The diagnostic collects `observeMessages` through the app's repository chain, so `CachingConversationRepository` may write the conversation's thread rows to its existing cache. It is the same write that opening the thread performs, into the same app-private store.
- [Android attack surface] No findings. No intents, deep links, pending intents, providers or WebViews are added. The restored LIVE entry in `scripts/e2e-emulator.sh` and the `LIVE_MINIMUM` floor in `scripts/android-test-gate.py` are host-side test configuration and ship nothing in the APK.
- [Crypto] No findings. No primitives, keys or Noise code are touched.
- [Network & I/O] No findings for production. The diagnostic's `observeMessages` collection sends one `backfillSinceRequest` over the already-paired session. It runs only on the failure path of step 5 and is bounded by `withTimeout(THREAD_TIMEOUT_MS)`.
- [Errors, logs] No findings. The production change logs nothing. The assertion message gains one boolean-or-`unread` field. `runCatching` folds any failure to `"unread"`, so an exception's text cannot carry row content into the test report.
- [Concurrency] SHOULD FIX, fixed in this revision. A refused `scrollToItem` ended the non-relaunching newest-row effect permanently (see Revisions). Otherwise the effect is composition-scoped and cancelled with its three siblings. It suspends only in `scrollToItem`, and it reads `userScrolledAway` and scrolls on the main thread with no shared mutable state across threads. `runBlocking` in `threadHoldsReply` is test-only instrumentation code.
- [Threat model] No findings. A compromised relay or hostile daemon gains no new capability: the change adds no inbound verb, no new rendered field and no new outbound frame. UI-side leakage is unchanged, because the change only decides which already-rendered row is on screen.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
