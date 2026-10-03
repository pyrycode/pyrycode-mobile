# #1572: an open thread asks for the newest page every time its host becomes available

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: the `init` block's #1569 opening
  ask, `historySeed`, `hostAvailable`, `onDemandOlderHistory`, `onRetryOlderHistory`, `launchHistoryAsk`,
  `fetchHistoryPage`, `claimHistorySlot`, and the #1410 context-usage collector whose open/reconnect shape this mirrors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt`: `ThreadHistoryDemand`
  (`canAsk`, `asking`, `settled`, `restored`, `cursorRefused`, `tail`), where the new slot transitions live.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: the #1352, #1354 and #1569
  history tests and the `HistoryRepo` double.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemandTest.kt`: unit tests of the walk
  value.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt`: `live rows merge over
  restored rows with no second row for a drawn key` and `a reconnect after a disconnect merges over everything drawn
  so far` already pin that a newest page re-delivering cached rows draws each once and appends the newer row. This
  ticket does not touch the repository.
- `docs/specs/architecture/1569-never-loaded-thread-asks-newest-page.md`, `1354-saved-history-position.md`,
  `1352-history-on-user-demand.md`: the rules this replaces and keeps.

No other in-flight branch touches these files.

## Design source

Figma node `16-8` (https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8). No visual change: the
fetched rows render exactly as after a history pull, and the oldest-end slot shows `Loading` while the newest-page
ask is in flight, as it did for #1569's opening ask. The visual check has nothing new to compare.

## Context

A reply that reaches a thread nobody is observing is never cached (`CachingConversationRepository.observeMessages`
writes only while collected), and a reconnect discards the connection-scoped projection that held it. Mode A replay
does not resend it, so the thread draws the stale cache forever. The owner decision: an open thread asks for the
newest history page (`request_history`, empty cursor) every time its host becomes available, at open and after
every reconnect while it is open. This replaces #1569's never-loaded condition rather than adding a second trigger.
No decision record beyond the documentation handoff seems needed; the rule is a reversal of #1352's "opening never
asks", which the feature overviews record.

## Design

### Trigger

The `init` block's #1569 coroutine becomes: wait for `historySeed`, then collect
`repositoryAvailable.distinctUntilChanged().filter { it }` and call `askForNewestPage(reconnect = opened)` on each
arrival, the same shape as the #1410 context-usage collector. Opening offline asks once, when the host arrives;
each drop and return asks again. Waiting for the seed first means every claim sees the restored position.

`historySeed` no longer needs its `Boolean` result and becomes a `Job` (`onDemandOlderHistory` only uses
`isCompleted` and `join`).

### Two kinds of newest-page ask

`askForNewestPage` claims the walk's single slot through `claimHistorySlot`, so it never overlaps a pull or a retry,
and is dropped (with a static log) when the slot is taken.

- **The newest page is the walk's own next page** when the walk could ask now and its cursor is empty: a
  never-loaded thread (#1569's case), a walk whose cursor the daemon refused, or a walk whose newest ask failed. Then
  the ask goes through the existing `asking()` / `launchHistoryAsk` path unchanged: the page settles into the walk
  and its position is saved, exactly as a pull from the newest would.
- **Otherwise it is a side ask** that leaves the walk where it is. The slot is claimed with
  `ThreadHistoryDemand.askingNewest()` (only `inFlight` moves; cursor, page count and stop reason are kept) and
  released with `newestSettled()` (only `inFlight` clears). The page's `cursor` and `atStart` are not read and no
  position is written, so a saved cursor still drives the next pull and a saved `atStart` still reads as fully
  loaded. The rows arrive the same way any page's do: `requestHistory` has already merged them into
  `observeMessages` through `mergeHistoryRows` and `mergeCachedRows`.

New members on `ThreadHistoryDemand`:

- `val newestPageAdvancesWalk: Boolean`: `canAsk && cursor.isEmpty()`.
- `fun askingNewest(): ThreadHistoryDemand`: `copy(inFlight = true)`.
- `fun newestSettled(): ThreadHistoryDemand`: `copy(inFlight = false)`.

New private functions in `ThreadViewModel`: `askForNewestPage(reconnect: Boolean)` (the claim and the branch) and
`launchNewestPageSideAsk()` (the side ask's request, its failure log and the release).

## State and concurrency model

All on `viewModelScope`. The one collector lives as long as the ViewModel, so it stops when the screen's ViewModel
is cleared. The claim is the existing `compareAndSet` loop, so a pull, a retry and a newest-page ask race safely
for the one slot: whichever claims first asks, the others are dropped. The release of a side ask is an `update` in
the launched coroutine, as every walk settle is. A side ask in flight across a reconnect releases when its request
returns or fails; the reconnect's own ask finds the slot taken and is dropped, as an in-flight pull already does.

## Error handling

A walk-advancing ask fails exactly as today (`fetchHistoryPage`'s branches). A side ask's failure
(`RelayErrorException`, `IllegalStateException`, `IllegalArgumentException`; `CancellationException` rethrown first)
logs `event=history_newest_ask_failed` with no daemon-authored value and releases the slot; it leaves the walk's
stop reason alone, so it never turns the oldest-end slot into Retry or a dead end for a page the reader did not ask
for. The next host arrival asks again. Logs: `event=history_newest_ask reason=reconnect` on a reconnect ask (the
opening ask stays log-free, as #1410's does), `event=history_newest_ask_skipped reason=in_flight` when dropped.

## Testing strategy

Unit tests only; the change is ViewModel logic with no new UI.

`ThreadViewModelTest`:

- Replace `history_withASavedPosition_theFirstPullAsksWithTheSavedCursorAndOpeningAsksNothing` with a test where the
  cached rows are drawn, opening asks `""` once with no gesture, the stored newer reply renders after the cached
  rows with none duplicated, no position is written, and the first pull still asks with the saved cursor (AC1, AC3).
- Each return of the host while open asks the newest page once more; opening offline asks once, when the host
  arrives (AC2). The existing #1569 offline-open and #1352 reconnect tests change their "asks nothing" halves to
  this rule.
- A saved `atStart`: the opening ask happens, pulls still ask nothing and the offline notice stays hidden (AC3).
- The newest ask shares the slot: while it is in flight a pull and a retry send nothing; while a pull is in flight
  a reconnect's ask is dropped (AC3).
- A failed side ask leaves the tail `None`, writes no position, logs only the static event, and the next pull asks
  with the saved cursor.
- Existing saved-position tests whose ask sequence gains the opening `""` are updated to the new sequence.

`ThreadHistoryDemandTest`: `newestPageAdvancesWalk` for the untouched, refused, failed, advanced and at-start walks;
`askingNewest` then `newestSettled` round-trips to the original value with a saved cursor and stop reason.

The repository-level dedup is already pinned by the two `CachingConversationRepositoryTest` tests named above. The
rung-3 live scenario is #1581 (AC4), not this ticket.

## Open Questions

- Does a pull issued while the saved position is still being read now lose to the opening ask? Expected yes: both
  wait for the seed, the opening collector registered first, so the pull is dropped under the single-slot rule and
  the reader pulls again. Settle the test's expectations against the run.
  **Resolved (2026-10-03):** confirmed. `history_aPullWhileTheSavedPositionIsBeingRead_asksWithTheSavedCursor` now
  pins that the opening ask goes first, writes no position, and the next pull asks with the saved cursor. No design
  change.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: replace the #1352/#1569 rule ("opening
  never asks", with the never-loaded exception) with: an open thread asks for the newest page whenever it gains its
  host, and why (an off-screen reply was lost across a reconnect, #1572).
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` § "The oldest-end history demand and
  retry (#777, #778, #1569)": the same replacement.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md` § "Resuming from
  the saved position (#1354)": the same replacement.
- `docs/knowledge/features/caching-conversation-repository.md`: rows reaching an unobserved thread are not cached
  and are recovered by this ask.
