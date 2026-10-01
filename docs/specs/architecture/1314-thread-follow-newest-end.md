# #1314 — Thread follows the newest end while at the bottom and after every send

## Files read

- `ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen`'s `else` arm: `userScrolledAway`, `autoScrollNestedScroll`, the at-bottom reset effect, the streaming pin keyed on `hasStreamingMessage`/`promptPresent`, the #981 pin keyed on `newestRowKey`, the #1305/#1306 reveal keyed on `promptIdentity`, and the `attachmentRefusals` collector the new send signal mirrors.
- `ui/conversations/thread/ThreadViewModel.kt` — `sendMessage`, `sendWithAttachments` and #1311's `sendInLocalWindow`, the single point both accepted sends pass through; the `Channel(...).receiveAsFlow()` one-shot idiom (`attachmentRefusals`).
- `MainActivity.kt` — the thread destination's `ThreadScreen(...)` call binding `attachmentRefusals = vm.attachmentRefusals`.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` — § Streaming auto-scroll, § The newest-row pin, § Inline question rows and the newest-end reveal. Lessons carried here: under `reverseLayout` a row inserted at index 0 keeps the previous first row anchored **by key**, so it lands below the viewport; a `scrollToItem` under a resting finger is refused with a `CancellationException` and must be caught with `ensureActive()`; layout-derived tests need ~30 rows so the list overflows (#777).
- `sharedTest/.../ThreadScreenNewestRowTest.kt`, `ThreadInlineQuestionTest.kt`, `ThreadScreenModalTest.kt`, `ThreadScreenHistoryTest.kt` — the #185/#777/#981/#1305/#1306 tests that must stay green; `ComposerAttachmentStripTest.kt` for how a real `ThreadViewModel` is mounted under a screen test.
- `test/.../ThreadViewModelLocalSendTest.kt` — `GatedRepository` / `BytesReader` doubles to extend for the accepted-send signal.

In-flight overlaps (build through, edits additive): #1329, #1342 (all three files), #1341 (renames `questionState` in the `promptIdentity` line), #1346, #1359, #1337, #1348, #1355, #1410, #1411. #1348/#1355 reshape the `sendMessage` → `sendWithAttachments` dispatch; this ticket edits only `sendInLocalWindow`, so neither is a dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Behaviour only: the message list (`LazyColumn(reverseLayout = true)` in the thread frame) renders exactly as it does today. No layout, token or component changes; the visual check is a no-op on purpose.

## Context

Three effects pin the list and all gate on `userScrolledAway`, which is set by any user drag delta (even an overscroll that cannot move the list), cleared only on a *change* of the at-bottom snapshot (so a reader already at the bottom never gets it back), and never touched by a send. The streaming pin's `scrollToItem(0)` is unguarded, so one refusal under a resting finger ends it for the reply. Desktop's `useThreadScrollPin` derives `following` from position on each scroll event, re-asserts the bottom after render/resize while following, and sets `following` on an accepted send (`followBottom`). This ticket ports that shape. No decision record needed — it is a port of an existing desktop contract.

## Design

### New file `ui/conversations/thread/ThreadListFollow.kt`

A pure rule plus one composable effect host, kept out of the 1200-line screen.

- `internal data class ListFrame(anchorKey: Any?, anchorIndex: Int, anchorOffset: Int, content: Any?)` — one layout frame as the rule reads it: the first visible item's key, `firstVisibleItemIndex`, `firstVisibleItemScrollOffset`, and a content signature (newest row key, prompt identity, and the anchor row's size, the size masked to `null` while a prompt is mounted).
- `internal data class FollowStep(following: Boolean, pin: Boolean)`.
- `internal fun followStep(previous: ListFrame?, current: ListFrame, following: Boolean, tolerancePx: Int): FollowStep`:
  - no previous frame → `following = atEnd(current)`, no pin (opening and recreation decide from position; this replaces #981's `drop(1)`);
  - `anchorKey` or `anchorOffset` changed → a scroll (user drag, fling, programmatic, or our own pin): `following = atEnd(current)`, no pin;
  - otherwise, `anchorIndex` or `content` changed → growth: keep `following`, `pin = following`;
  - otherwise nothing.
  - `atEnd` = `anchorIndex == 0 && anchorOffset <= tolerancePx`.
  Why key+offset: under `reverseLayout` an insert at index 0 moves the anchor's *index* but keeps its key and offset, so growth never reads as a scroll; a history prepend moves neither; an overscroll that cannot move the list moves neither, so following stays on.
- `@Composable internal fun FollowNewestEnd(listState, newestRowKey: Any?, promptIdentity: Any?, promptPresent: Boolean, sentMessages: Flow<Unit>)`:
  - tolerance `4.dp` → px via `LocalDensity` (desktop `AT_BOTTOM_TOLERANCE_PX`);
  - one `LaunchedEffect(listState)` collecting `snapshotFlow { ListFrame(...) }.distinctUntilChanged()` and applying `followStep`; on `pin`, `pinToNewest()`;
  - one `LaunchedEffect(listState, sentMessages)` collecting the accepted-send signal: `following = true`, `pinToNewest()` (desktop `followBottom` + the scroll the next render would do);
  - `pinToNewest()` = `try { listState.scrollToItem(0) } catch (e: CancellationException) { ensureActive() }` — the only programmatic scroll, so every one is guarded.
  - `following` is a remembered holder read only inside the collectors, never inside the `snapshotFlow`, so it cannot itself trigger a frame; both collectors run on the composition's main-thread dispatcher.

### `ThreadScreen.kt`

- Delete `hasStreamingMessage`, `userScrolledAway`, `autoScrollNestedScroll` (and the `.nestedScroll(...)` modifier), the at-bottom reset effect, the streaming pin, the #981 pin and the #1305/#1306 reveal. Keep `newestRowKey`, `promptIdentity` and `promptPresent` as the inputs to `FollowNewestEnd(...)`, called in the same place.
- New defaulted parameter `sentMessages: Flow<Unit> = emptyFlow()` beside `attachmentRefusals`.
- The #777 oldest-end demand effect is untouched.

### `ThreadViewModel.kt`

- `private val sentMessageChannel = Channel<Unit>(Channel.CONFLATED)`, `val sentMessages: Flow<Unit> = sentMessageChannel.receiveAsFlow()`.
- `sendInLocalWindow`: after `send()` returns, `sentMessageChannel.trySend(Unit)` and `RelayLog.d { "event=thread_send_accepted" }`. This is the single accepted-send point #1311 established; a throw (swallowed by `launchGuardedRepoCall`), a blank send, a not-connected skip or a stopped upload never reaches it.

### `MainActivity.kt`

- `sentMessages = vm.sentMessages` in the thread destination, beside `attachmentRefusals`.

## State and concurrency model

- `following` lives in composition (`remember`), not the ViewModel: it is a function of the list's position, and recreation recomputes it from the restored `LazyListState` (saved index/offset) on the first frame.
- Two `LaunchedEffect`s keyed on `listState` (the send collector also on the flow). Both cancel when the list leaves composition (empty state, screen exit). A refused `scrollToItem` costs that one scroll: `following` stays true because the anchor key/offset did not change, and the next growth frame pins again.
- `sentMessages` is a `CONFLATED` channel: several accepted sends before the screen collects collapse to one follow; a send accepted while the list is not composed is delivered when it is.
- Prompt rows: arrival/replacement changes `promptIdentity` → pin only if following. While a prompt is mounted the anchor-size half of the signature is masked, so field edits, the IME and `BringIntoViewRequester` inside the card never re-pin (#1304's rule). A reader in history is not following, so nothing pulls them down.

## Error handling

The only failure is a refused scroll (`CancellationException` from the list's `MutatorMutex`), caught per call with `ensureActive()` so a real cancellation still propagates. Send failures keep their existing paths; they simply emit no signal.

## Testing strategy

- **Unit, `ThreadListFollowTest`** (`app/src/test/.../thread/`): `followStep` — first frame at/away from end; growth at end pins; growth away does not; anchor offset change within 4dp keeps following, beyond drops it; overscroll (identical frame) changes nothing; history prepend (no change) changes nothing; index-only change after a refused pin keeps following and pins on the next content change; prompt-identity change pins only while following.
- **Unit, `ThreadViewModelLocalSendTest`**: `sentMessages` emits once per accepted text send and attachment send; nothing on a thrown send, blank send, or failed upload.
- **Screen, `ThreadScreenFollowTest`** (`app/src/sharedTest/.../thread/`, Robolectric, 30-row lists): overscroll swipe at the newest end then a new row and a streamed delta both in view; scrolled up then an accepted text send through a real `ThreadViewModel` (gated repository) brings the newest row and the following reply into view; same for an attachment send; a refused send leaves the reader where they were; while following, a tool row's result and a queued row stay in view, while not following nothing moves; a refused pin mid-stream followed by later growth is still followed; a `StateRestorationTester` recreation at a restored position away from the end is not pulled down by a new row, and one restored at the end is.
- Existing `ThreadScreenNewestRowTest`, `ThreadInlineQuestionTest`, `ThreadScreenModalTest`, `ThreadScreenHistoryTest` run unchanged.
- No device-only test, no rung-3 scenario: the change is list-position logic Robolectric drives fully, and the live data path (rows, deltas, sends) is unchanged; existing `InteractiveStreamE2ETest` scenarios already assert replies render.

## Open Questions

- Does `visibleItemsInfo.first().key` reliably match `firstVisibleItemIndex` in the same frame? Plan: take the key from the visible item whose index equals `firstVisibleItemIndex`.

## Documentation handoff

Pending for the documentation stage: update § *Streaming auto-scroll (since #185)*, § *The newest-row pin (#981)* and the reveal paragraphs of § *Inline question rows and the newest-end reveal (#1305)* / § *Inline permission rows and the shared reveal (#1306)* in `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` to describe the position-derived following rule and the accepted-send signal.

## Revisions

- **2026-10-01 — Open question resolved as planned.** The anchor key is read from the visible item whose index equals `firstVisibleItemIndex` (inside `FollowNewestEnd`'s `snapshotFlow`), so key, index and offset come from the same frame. A traced run of the refused-mid-stream screen test showed the expected sequence: the tool row's arrival moves only the anchor's index and pins, the pin is refused under the resting finger, the next anchor-size change pins again and lands on the tool row. No design change.

- **2026-10-01 — A refused pin is retried by any non-scroll frame (verifier MUST FIX on PR #1423).** The rule missed the case where the refused pin was for the reply that had just arrived. That reply sat at index 0 below the viewport, so its streamed deltas changed no visible row, `distinctUntilChanged` dropped every frame and the reply streamed out of sight. The traced test above passed only because the row that grew was the anchor, not the refused row. New contract:
  - `ListFrame` gains `scrolling`, which is `isScrollInProgress`, so the finger lifting is a frame.
  - The content signature now also carries the newest `ThreadRow` itself, passed to `FollowNewestEnd` as `newestRow`. A delta to a row below the viewport is growth. Like the anchor size, it is masked while a prompt is mounted (#1304).
  - In `followStep`, a frame that is not a scroll pins when `following && (grew || anchorIndex != 0)`. While following, the list can only be off index 0 because a pin was refused, so the next delta or the finger lifting retries it. A successful pin changes the anchor key, so it reads as a scroll, which ends the retries without a loop.
  - Covered by `ThreadScreenFollowTest.a_reply_whose_pin_is_refused_at_arrival_is_followed_once_the_finger_lifts`, which is red on the previous rule. `ThreadListFollowTest` covers the delta retry and the lift retry.
