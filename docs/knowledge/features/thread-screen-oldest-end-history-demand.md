# Thread screen — the oldest-end history demand

Split out of [Thread screen — how it works, the list, the chip, the empty state and the status
row](thread-screen-how-it-works-list-and-status-row.md) when that document passed the size cap (#1569);
that parent document is the map, this child covers the oldest-end history slot in full.

### The oldest-end history demand (#777)

`requestHistory` ([remote repository § the walk that finally calls
`requestHistory`](remote-conversation-repository-reads-and-thread-store-history-paging.md#the-walk-that-finally-calls-requesthistory-777))
had no caller until #777 wired the screen to `ThreadViewModel.onDemandOlderHistory()` via a defaulted
`onDemandOlderHistory: () -> Unit = {}` parameter (`MainActivity` binds `vm::onDemandOlderHistory`, the
only consumer). #777 drove that call from a scroll-position `snapshotFlow` that fired whenever the oldest
loaded row came into view, and the opening and every reconnect asked unconditionally beside it (owned by
`ThreadViewModel`, see [remote repository §
the walk that finally calls `requestHistory`](remote-conversation-repository-reads-and-thread-store-history-paging.md#the-walk-that-finally-calls-requesthistory-777)).
[#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) replaced all of that with a single
trigger, copying desktop's rule: older pages load only on a reader's own pull toward older messages, on
both apps, and never because a screen opened, a connection returned, a page arrived, or a row scrolled
into view.

**[#1569](https://github.com/pyrycode/pyrycode-mobile/issues/1569) adds the one exception: a thread this
phone has never loaded history for asks for the newest page once, when its host is first available.**
"Never loaded" means `ConversationRepository.readHistoryPosition` (#1354) found nothing saved —
`ThreadViewModel.historySeed` reports that back as a `Boolean`, and an `init` coroutine awaits it, then
awaits `hostAvailable.first { it }`, then claims the slot only if the walk is still untouched
(`ThreadHistoryDemand()`, `it == ThreadHistoryDemand()`) before calling the same `launchHistoryAsk` a pull
uses. A thread with a saved position never reaches this ask and keeps #1352's pull-only rule exactly; a
reader whose pull wins the slot first leaves the opening collector nothing to claim, so it never asks a
second, older page. Opening before the host connects asks nothing until the host arrives, asks exactly
once then, and a later drop and return asks nothing more — the collector takes only the first
`hostAvailable` edge. This exists because a dormant channel created on another client — a live session's
rows reach the thread through the live lane, so only a *dormant* conversation with nothing cached was
affected — opened with `EmptyThreadState` and stayed empty until a send woke the session, since the daemon
serves a dormant conversation's history from disk only when asked.

**`OlderHistoryGesture` and `Modifier.olderHistoryPull`** (`ThreadHistoryRows.kt`) replace the #777
scroll-position `snapshotFlow` entirely. `OlderHistoryGesture` is a `NestedScrollConnection` constructed
with `nearOldestEnd: () -> Boolean` and `onDemand: () -> Unit`; `olderHistoryPull(gesture)` attaches it
with `Modifier.nestedScroll(gesture)` and, ahead of that, runs `awaitEachGesture { awaitFirstDown(…, pass =
PointerEventPass.Initial) }` to call `gesture.onGestureStart()` on every touch gesture's first pointer
down, without consuming it. `onGestureStart()` records `armed = nearOldestEnd()` — whether *this* gesture
began at, or within `HistoryAskBand` (200dp) of, the oldest end. `onPreScroll` then calls `onDemand()`
exactly once, the first time an `armed` gesture reports a `NestedScrollSource.UserInput` delta with
`available.y > 0f` (toward older content: under `reverseLayout = true` older content lies at the top, so a
pull toward it moves the finger down), clearing `armed` so the same gesture cannot ask twice.
`onPreFling`, which Compose calls at the end of every drag including a zero-velocity one, also clears
`armed`, closing the gesture whether or not it asked. The connection consumes nothing (`onPreScroll`
always returns `Offset.Zero`), so attaching it changes no scrolling behaviour, and a list that cannot
scroll still dispatches its drags through nested scroll — a short thread's pull asks exactly as desktop's
wheel does.

**The gesture had to start on the pointer down, not on the first scroll delta, because a semantics
scroll looks like a drag but never ends like one.** The plan's first design armed the gesture on a nested
scroll's first delta and disarmed it on `onPreFling`. A Robolectric run then showed `LazyColumn`'s
semantics `ScrollBy` action reaches a parent `NestedScrollConnection` as ordinary `UserInput` deltas with
no fling afterward — so a semantics-driven scroll (as a screen test, TalkBack, or an accessibility service
might issue) armed the gesture and left it armed, and the *next* real drag then asked, regardless of where
that drag started. Arming on `awaitFirstDown` instead ties the gesture to an actual finger touching the
screen, which a semantics action never does, so a scroll with no finger behind it can never open an armed
window for a later unrelated drag to abuse. This is recorded under the plan's Revisions
(`docs/specs/architecture/1352-history-on-user-demand.md`).

**`isNearOldestEnd` reads `LazyListLayoutInfo` under `reverseLayout = true`, where an item's offset runs
from the viewport's bottom, not its top.** `internal fun LazyListLayoutInfo.isNearOldestEnd(oldestIndex:
Int, bandPx: Float): Boolean` treats an empty thread (`oldestIndex < 0`) as always at the end; otherwise it
finds the oldest row in `visibleItemsInfo` and checks `offset + size + afterContentPadding -
viewportEndOffset <= bandPx` — the part of that row still hidden past the viewport's far edge.
[#1562](https://github.com/pyrycode/pyrycode-mobile/issues/1562) added the `afterContentPadding` term: the
`LazyColumn`'s top `contentPadding` (`MessageAreaTopInset`, 28dp — see [overlays and app
bar](thread-screen-how-it-works-overlays-and-app-bar.md#thread-top-overlay-placement-post-1002)) is
after-content padding at this reversed list's oldest end and is folded into `viewportEndOffset`, so without
adding it back the ask band would arm 228dp from the oldest end instead of the documented 200dp —
`ThreadOldestEndBandTest` drives a fake `LazyListLayoutInfo` to pin the corrected formula; a screen-level
probe can't tell 200dp from 228dp apart because by ~214dp the oldest row has already left `visibleItemsInfo`
and both counts treat it as out of band. **A row not laid out at all — because it is
further away than a screenful, or because it is short and fully scrolled past — counts as further away
than the band**, by construction of `firstOrNull { it.index == oldestIndex } ?: return false`; the function
has no way to measure a row it cannot see. The plan names this limitation explicitly, and the verifier
flagged it as a SHOULD FIX (non-blocking): a reader 150dp short of the oldest end, whose fully-hidden
oldest bubble is short enough to have scrolled out of `visibleItemsInfo` already, pulls and only scrolls —
the effective band in that case is `min(200dp, the visible part of the oldest row)`. The regression test
(`ThreadScreenHistoryTest.the_ask_band_is_200dp_from_the_oldest_end`) passes its 300dp (too-far) case only
because that row is fully off screen, not because the 200dp comparison itself was exercised at the
boundary — the 200dp threshold is documented but not pinned by a test.

**The oldest thread row's index still skips the prompt rows, same reasoning as #1305/#1306's row-count
fixes** (§ *Inline question rows* and § *Inline permission rows* in the parent document). `oldestRowIndex`
is `rows.size + promptRowCount - 1` (or `-1` for an empty thread) because prompt rows — an open question
batch or a permission/trust request — take the lowest indices of the reversed list and are never history;
`promptRowCount` is the same `(shownQuestion ? questions.size + 2 : 0) + (openRequest ?
PERMISSION_ROW_COUNT : 0)` sum #1305/#1306 introduced for `FollowNewestEnd`. No screen test pulls while a
prompt is open, so this term is correct by reading, not proven by a test.

**An empty thread gets its own gesture, because it renders `EmptyThreadState` instead of the list.** The
`if (!state.hasMessages && …)` arm applies `Modifier.olderHistoryPull(emptyThreadPull)` plus
`Modifier.scrollable(rememberScrollableState { 0f }, Orientation.Vertical)` to `EmptyThreadState` — the
`scrollable` consumes nothing (its consumption lambda always returns `0f`) but is what lets a drag on a
non-scrolling empty surface reach nested scroll at all; `emptyThreadPull`'s `nearOldestEnd = { true }`
always treats an empty thread as being at its oldest end, so a pull there always asks (this is also the
ticket's explicit "including an empty thread" requirement). The non-empty branch applies the same
`olderHistoryPull(listPull)` to the `LazyColumn` itself, where `listPull`'s `nearOldestEnd` reads
`listState.layoutInfo.isNearOldestEnd(oldestRowIndex, askBandPx)`.

**The screen, not only the ViewModel, gates against a pull while a page is loading.** `pullForOlderHistory
= { if (!historyLoading) demandOlderHistory() }` reads `state.historyTail == ThreadHistoryTail.Loading`
through `rememberUpdatedState` and skips calling `onDemandOlderHistory` at all while a page is in flight.
`ThreadHistoryDemand.canAsk` (owned by the ViewModel, not this screen) is still the authoritative gate — a
gesture that slips through anyway sends nothing there either — but this duplicate check keeps a pending
page's own composable churn from mattering to the gesture's wiring.

**The affordance still rides `reverseLayout` for free.** A keyed `item(key = HISTORY_TAIL_KEY)` is appended
after the `itemsIndexed(...)` block; because `reverseLayout = true` draws a later item further up,
appending it after the message rows places it at the **oldest** end without any special-casing of index 0.
This part is unchanged from #777/#778.

**The known #777 gap — the affordance (now including the offline notice) is unreachable while the thread
reads as empty — is unchanged by #1352 and takes on a different shape.** The oldest-end slot lives inside
the `else` arm of `if (!state.hasMessages)` (§ *Empty-state branch* in the parent document), so an empty
thread shows no loading, retry, dead-end or offline row even while a pull there is in flight or has
failed; it only shows `EmptyThreadState`. Before #1352 this was a one-round-trip flash on the opening ask;
\#1352 removed the opening ask entirely, and [#1569](https://github.com/pyrycode/pyrycode-mobile/issues/1569)
brought one back for the never-loaded case above, so the gap now also covers that opening ask: a
never-loaded thread renders `EmptyThreadState` with no loading feedback while its one opening request is
in flight, exactly as a reader's own pull on an empty thread does. Still open.

### The oldest-end history retry and restart (#778)

[#777](../codebase/777.md)'s loading row was a one-state affordance gated on a `Boolean`; [#778](../codebase/778.md)
widened the **same slot** to four mutually exclusive states, and [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352)
widened it again to five, still without adding rows — the one-slot invariant is enforced by construction,
since `when (state.historyTail)` emits at most one `item(key = HISTORY_TAIL_KEY)`. Despite the heading's
name carried over from #778, there is no restart left on the screen side either: see [remote repository §
the retry and the two restarts](remote-conversation-repository-reads-and-thread-store-history-paging.md#the-retry-and-the-two-restarts-778)
for why #1352 removed both.

- **`ThreadUiState.historyLoading: Boolean` was replaced outright by `historyTail: ThreadHistoryTail`**
  (`None` / `Loading` / `Retry` / `DeadEnd`, joined by `Offline` in #1352), read from
  `ThreadHistoryDemand.tail(connected)` via the same `ThreadContent` `combine` arm #777 used, now also
  combining in the ViewModel's `hostAvailable` to supply `connected`. The walk's *termination* reasons
  (`AtStart` / `NotAdvancing` / `PageCap`) still never reach the screen, only its two failure reasons and
  the offline state do — the screen asks, the ViewModel decides whether the ask is honoured, and a second
  copy of that decision in Compose would be a second place to get it wrong.
- **`HistoryRetryRow(onRetry)`** is an `errorContainer` / `onErrorContainer` `Surface` on
  `MaterialTheme.shapes.small` — the shipped [`ConnectionBanner`](connection-banner.md) idiom, reused
  rather than a new error style, because the Figma thread frame carries no history element of its own but
  does carry one error-plus-action affordance (the status-area "Pairing error - Re-pair" chip) in that same
  shape. Its content `Row` carries `Modifier.clickable(role = Role.Button)`, a `bodySmall` failure label and
  a `labelLarge` action label, with a merged `contentDescription`. Pressing it calls the defaulted
  `onRetryOlderHistory: () -> Unit = {}` parameter, wired by `MainActivity` to `vm::onRetryOlderHistory` —
  the only consumer. Since #1352 the ViewModel also drops a `Retry` press while the host is not connected,
  mirroring the pull gesture.
- **`HistoryDeadEndRow()`** is the same `Surface` with no `clickable` and no action label — visible, with
  nothing to press, because a non-retryable failure has no button that could work. Since #1352 recovery is
  the reader's next qualifying pull or Retry press, not a reconnect restart — #778's reconnect restart,
  which used to be what recovered a closed session automatically, no longer exists; see the remote
  repository section linked above.
- **`HistoryOfflineRow()` (#1352)** shares `HistoryDeadEndRow`'s private `HistoryNoticeRow` body and the
  same `HistoryTailSurface`, with `R.string.thread_history_offline_label` ("Older messages require a
  connection.") as both its label and its content description. `ThreadHistoryDemand.tail(connected =
  false)` yields `Offline` unless the walk already stopped at `AtStart`, in which case it yields `None`
  instead — a thread that has fully loaded its history shows nothing extra just because the host dropped,
  matching desktop's `olderSaved` notice. Nothing is clickable; the next pull after the reconnect asks,
  through the ordinary gesture path, not through any restart.
- **Neither failure row, nor the offline row, reads `RelayErrorException.message`.** All the strings are
  local `strings.xml` resources (`thread_history_retry_label`, `thread_history_retry_action`,
  `thread_history_dead_end_label`, `thread_history_offline_label`, plus their content-description twins)
  with no interpolation — the same "nothing daemon-authored reaches this row" posture `HistoryLoadingRow`
  already had.
- **The demand trigger is a gesture now, not a scroll predicate, and the oldest-end slot's own presence
  still cannot move it.** § *The oldest-end history demand* above replaced the #777/#778 scroll-position
  predicate with `OlderHistoryGesture`/`olderHistoryPull`, which reads `LazyListLayoutInfo` live rather than
  a cached row count — mounting or unmounting the oldest-end slot changes the list's total item count but
  never changes where the *oldest thread row* sits, so the slot's own appearance still cannot retrigger the
  gesture that mounted it, for the same reason the old count-based predicate was careful to exclude it.
