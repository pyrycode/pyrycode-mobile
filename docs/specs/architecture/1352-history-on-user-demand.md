# #1352 — Load older history only when the user asks

## Files read

- `ui/conversations/thread/ThreadHistoryDemand.kt` — `ThreadHistoryDemand` (`canAsk`, `asking`, `failed`,
  `retrying`, `restarted`, `walk`, `tail`), `HistoryWalkStop`, `ThreadHistoryTail`. The walk value this
  ticket reshapes.
- `ui/conversations/thread/ThreadViewModel.kt` — `init` (the opening `requestOlderHistory` and the
  `repositoryAvailable` restart collector with its #1311 `closeLocalSendWindow("reconnect")`),
  `onDemandOlderHistory`, `onRetryOlderHistory`, `launchHistoryAsk`, `failWalk`, `applyToWalk`,
  `restartHistoryWalk`, `claimHistorySlot`, `threadContent`.
- `ui/conversations/thread/ThreadScreen.kt` — the `else` arm of `thread-message-region`: the #777
  oldest-row `snapshotFlow`, `promptRowCount`, the `LazyColumn(reverseLayout = true)` and the
  `state.historyTail` slot; the `EmptyThreadState` arm.
- `ui/conversations/thread/ThreadHistoryRows.kt` — `HistoryDeadEndRow`, `HistoryTailSurface`, whose
  treatment the offline notice reuses.
- `ui/conversations/thread/ThreadScreenPreviews.kt` — `HistoryTailPreview`.
- `ui/conversations/thread/ThreadUiState.kt` — the `historyTail` field comment.
- `MainActivity.kt` — binds `vm::onDemandOlderHistory` and `vm::onRetryOlderHistory`; unchanged.
- Tests: `ThreadHistoryDemandTest`, the `history_*` block of `ThreadViewModelTest`,
  `ThreadScreenHistoryTest`, and the two "without history demand" tests in `ThreadScreenModalTest` and
  `ThreadInlineQuestionTest`, which assert the old scroll-driven demand.
- `e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`,
  `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`,
  `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, `awaitReadyAttachmentRow`, `LinkCut.await`.
- Feature overviews: `thread-screen-how-it-works-list-and-status-row.md` ("The oldest-end history demand"),
  `remote-conversation-repository-reads-and-thread-store-history-paging.md` ("The retry and the two
  restarts (#778)"), `development-verification.md` ("Where a screen test goes"). Lesson carried: prompt
  rows sit at the low indices of the reversed list, so the oldest thread row's index is
  `rows.size + promptRowCount - 1`, and prompt rows are never history.

Overlapping in-flight branches: #1340, #1346 and #1355 touch `ThreadViewModel.kt` / `ThreadScreen.kt` /
`strings.xml`, none of them in the history code. Edits here stay local to the history paths.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame draws no history rows (checked against its screenshot), so there is no new
visual. The offline notice reuses `HistoryDeadEndRow`'s treatment: the `errorContainer` /
`onErrorContainer` surface on `MaterialTheme.shapes.small`, `bodySmall` text, inset on the 20dp gutter, in
the same single oldest-end slot.

## Context

Owner decision: older pages load only on the user's request, as on desktop
(`historyPageBridge.requestOlderHistory`, called from `ConversationScreen.demandHistory` within the 200px
near-top band, while connected). Mobile currently asks on open, restarts the walk on every reconnect,
asks whenever the oldest row is visible, and restarts on a refused cursor. A failure also locks out a
fresh gesture. The reconnect restart's premise — a cursor dies with its connection — is wrong: the cursor
names a position in the daemon's append-only log, so the walk keeps it.

No decision record needed; the feature overviews carry it.

## Design

### `ThreadHistoryDemand`

- `canAsk` = `!inFlight && (stoppedBy == null || stoppedBy is a failure)`. `AtStart`, `NotAdvancing` and
  `PageCap` stay terminal. `canRetry` is unchanged (retryable failure only).
- `asking()` claims the slot and clears a failure stop, so one transition serves both a gesture after a
  failure and the Retry press. `retrying()` is removed; `onRetryOlderHistory` claims with `asking()` under
  `canRetry`. Cursor and `pagesLoaded` are kept, so both resume from the failed page.
- New `cursorRefused()`: cursor back to `""`, slot released, no stop, `pagesLoaded` carried. Claims no
  slot — the next qualifying gesture asks from the newest page.
- `restarted()` and the `walk` generation are removed: no path restarts any more, and with exactly one
  ask in flight (the CAS claim) every settle belongs to the current ask. A settle that lands after a
  reconnect is valid because the cursor survives the reconnect. `cursor` stays a constructor parameter, so
  #1354 can seed it.
- `tail(connected: Boolean)`: not connected → `None` when `stoppedBy == AtStart`, otherwise the new
  `ThreadHistoryTail.Offline`. Connected → the existing Loading / Retry / DeadEnd / None mapping.

### `ThreadViewModel`

- `init` loses the opening `requestOlderHistory()`. The `repositoryAvailable` `drop(1)` collector keeps
  only `closeLocalSendWindow("reconnect")`.
- New `hostAvailable: StateFlow<Boolean>` = `repositoryAvailable.distinctUntilChanged()` held `Eagerly`
  (initial `false`). It is desktop's `connectedConversationHostNow`: the repository is published, which on
  a relay host is later than the socket's `Connected` (#861).
- `onDemandOlderHistory()` returns without asking while `hostAvailable` is false (debug log
  `event=history_ask_skipped reason=offline`). `onRetryOlderHistory()` is gated the same way.
- `launchHistoryAsk(claimed)` settles and fails through `historyDemand.update` directly. A
  `history.invalid_cursor` refusal of a non-empty cursor applies `cursorRefused()` and logs
  `event=history_cursor_refused`; no ask follows. On an empty cursor it stays a permanent failure.
  `applyToWalk` and `restartHistoryWalk` are removed.
- `threadContent` combines `hostAvailable` in and maps `demand.tail(connected)`.

### `ThreadScreen`

- The oldest-row `snapshotFlow` and its `historyRowCount` / `hasHistoryRows` go. `promptRowCount` stays
  for `FollowNewestEnd`.
- New `OlderHistoryGesture : NestedScrollConnection` (in `ThreadHistoryRows.kt`, beside the rows it
  serves), given `nearOldestEnd: () -> Boolean` and `onDemand: () -> Unit`. On `onPreScroll` with
  `NestedScrollSource.UserInput` it records, on the gesture's first delta, whether the gesture started
  near the oldest end; on a delta toward older content (`available.y > 0`: the finger moves down, which on
  the reversed list reveals the top) of a gesture that started near, it calls `onDemand` once.
  `onPreFling`, which ends every drag, resets it. It consumes nothing, so scrolling is unchanged.
- It is applied with `Modifier.nestedScroll` on the `LazyColumn` and on `EmptyThreadState`, which gains
  a `scrollable` that consumes nothing so a drag on an empty thread reaches the connection. A short list
  that cannot scroll still dispatches its drags.
- `nearOldestEnd` for the list: true when there are no thread rows; otherwise the oldest thread row
  (`rows.size + promptRowCount - 1`) must be laid out, and its far edge may lie at most
  `HISTORY_ASK_BAND` (200dp) beyond the viewport's far edge (`offset + size - viewportEndOffset`; under
  `reverseLayout` the offset runs from the bottom). A row not yet laid out counts as further away.
- The gesture calls `onDemandOlderHistory` only while `state.historyTail` is not `Loading`, so a second
  gesture while a page is pending sends nothing from the screen either. The VM's `canAsk` stays the gate
  that decides.
- The slot draws `ThreadHistoryTail.Offline` as `HistoryOfflineRow`.

### `ThreadHistoryRows`

- `HistoryOfflineRow()`: `HistoryDeadEndRow`'s body with `R.string.thread_history_offline_label`
  ("Older messages require a connection."). Both share one private notice body.

The empty-thread arm draws `EmptyThreadState`, not the list, so it shows no oldest-end slot today
(Loading included). That stays: an empty thread's pull asks, and the rows it loads replace the empty
state.

## State and concurrency model

`historyDemand` stays a `MutableStateFlow` written through the CAS claim and `update`. `hostAvailable`
is a `stateIn(viewModelScope, Eagerly, false)`; on `Dispatchers.Main.immediate` it reads the coordinator's
current value during construction. The ask runs in `viewModelScope` and is cancelled with it. The gesture
connection is `remember`ed per list and reads its lambdas through `rememberUpdatedState`.

## Error handling

Unchanged failure classification. Changes: any failure leaves `canAsk` true, so a fresh gesture recovers;
a refused cursor resets silently to the newest page; offline gestures are dropped with a content-free log.
No daemon text reaches a log or the screen.

## Testing strategy

- `ThreadHistoryDemandTest` (unit): `canAsk` after each failure kind and not after the terminal stops;
  `asking()` from a failure keeps cursor and budget; `cursorRefused()`; `tail(connected)` including
  `Offline` and the at-start exception. Restart tests are removed with `restarted()`.
- `ThreadViewModelTest` (unit, rewritten `history_*` block): no ask on open, on repository loss and
  return, or on settle; a demand asks `""` then the previous cursor; after a reconnect the next demand
  asks with the pre-reconnect cursor, including a page that settled across the reconnect; a demand while
  the repository is absent asks nothing and the tail is `Offline`, `None` once at start; retryable
  failure → Retry asks the same cursor; a fresh demand after a permanent or retryable failure asks again;
  `history.invalid_cursor` asks nothing and the next demand asks `""`; drop-while-pending, page cap and
  not-advancing guards; log lines carry no daemon value.
- `ThreadScreenHistoryTest` (Robolectric, `sharedTest`): swipe toward older on an empty, a short and a
  long thread scrolled to its oldest end → one demand each; a long thread at the newest end → none; a
  long thread 100dp short of its oldest end → one; two gestures → two demands, and none while the tail is
  `Loading`; programmatic scroll to the oldest row → none; the offline notice shows for `Offline`.
- `ThreadScreenModalTest` / `ThreadInlineQuestionTest`: their scroll-to-oldest expectations move from one
  demand to none.
- Live (rung 3, `InteractiveStreamE2ETest`): the offline-read scenario's KDoc and step comments say the
  prompt now arrives in the reconnect replay; the two cleared-cache scenarios pull for older history after
  opening chat X, through a helper that swipes down on `thread-message-region`. They run in the
  dispatcher's live gate; no device-only test is added.

## Open Questions

1. Does Compose deliver drags from a `LazyColumn` that cannot scroll through nested scroll? Expected yes;
   the short-thread screen test settles it.
2. Is `LazyListItemInfo.offset` measured from the bottom under `reverseLayout`? The 100dp-short and
   newest-end screen tests settle it.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`,
  "The walk that finally calls `requestHistory` (#777)" and "The retry and the two restarts (#778)":
  user-demand paging, no open or reconnect asks, the walk keeps its cursor across reconnects, the
  refused-cursor reset without an ask, `canAsk` after failures.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, "The oldest-end history
  demand (#777)" and the retry/restart section: the nested-scroll gesture and its 200dp band, the
  empty-thread `scrollable`, and the offline notice.
