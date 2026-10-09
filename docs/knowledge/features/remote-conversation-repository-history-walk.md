# Remote conversation repository — history walk and retry

Part of [remote-conversation-repository-reads-and-thread-store-history-paging](remote-conversation-repository-reads-and-thread-store-history-paging.md). The backwards walk, failure recovery and retired reconnect restarts.

## The walk that finally calls `requestHistory` (#777)

[#645](../codebase/645.md) shipped the fold and left `requestHistory` with no caller. [#777](../codebase/777.md)
adds the caller, and it lives **beside `ThreadViewModel`**, not in this repository — the contract above is
unchanged, and this section exists because the demand's design leans on guarantees this document already
records.

- **`ThreadHistoryDemand`** (`ui/conversations/thread/ThreadHistoryDemand.kt`) is a pure value — cursor,
  pages-loaded count, in-flight flag, and a `HistoryWalkStop?` (`AtStart` / `NotAdvancing` / `PageCap` /
  `Failed`, `null` while still walking). As shipped by #777, `ThreadViewModel` asked with it in `init`
  (empty cursor = newest) and again each time the thread screen reported the reader had reached the oldest
  loaded row. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) removed both triggers: older
  pages now load only on the reader's own gesture, never on open — see
  [§ the retry and the two restarts (#778)](remote-conversation-repository-history-walk.md#the-retry-and-the-two-restarts-778) below and
  [Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
  for what replaced the scroll-driven ask.
- **The walk reads `requestHistory`'s returned `HistoryPage` for `cursor` and `atStart` only.**
  `settled(pageCursor: String, atStart: Boolean)` takes the two scalars rather than the whole `HistoryPage`
  — `ThreadHistoryDemand.kt` imports neither `HistoryPage` nor `HistoryEntry`, so no daemon-authored entry
  text can structurally reach the walk's state. This is the caller-side half of "nothing needs a second
  fold": `RemoteConversationRepository.requestHistory` already merged the page into `threadByConversation`
  before returning (the § above), and `ThreadViewModel` reads that merged result through the existing
  `observeMessages` collector exactly as it does today — the walk never touches an entry.
- **One outstanding request per conversation, claimed CAS-style.** `ThreadViewModel` claims the slot with a
  `MutableStateFlow.compareAndSet` retry loop, not a read-then-assign — the settle runs in a launched
  coroutine, so a plain check-then-act would open a window for two concurrent asks. An ask arriving while
  one is in flight is dropped, never queued.
- **Two termination rules, and only one is a security bound.** `atStart` is the wire's only true
  termination signal and is checked before the cursor comparison, because the wire leaves the returned
  cursor empty whenever `atStart` is true. `pageCursor.isEmpty() || pageCursor == cursor` (`NotAdvancing`)
  is an **honest-bug guard only** — a daemon alternating between two distinct cursor values defeats it
  while still answering `atStart = false` forever. The load-bearing bound against a deliberately
  adversarial daemon is the client-side `MAX_HISTORY_PAGES = 100` cap in `settled()`, which does not read
  anything the daemon sent to decide when to stop. The cap is per `ThreadViewModel` instance (so per
  screen-open), with a fresh count on every open — **leaving and re-entering a thread does not start a
  fresh walk any more.** As shipped by #777 it did: nothing survived a screen close. [#1354](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354)
  changed that by saving the walk's position (not the count) beside the cached rows, so re-entering a
  saved thread continues its cursor from where the last visit left off while the page budget still
  resets to 100 for the new open.
- **A failed ask keeps the cursor and page count, clears in-flight, and stops asking — no retry, as
  shipped here.** `failed()` set `stoppedBy = Failed` without touching `cursor` or `pagesLoaded`, so every
  row already loaded and the walk's position survived a failure. `HistoryWalkStop` was an enum rather than
  a `Boolean` specifically so [#778](../codebase/778.md) could reopen `Failed` alone — `AtStart` /
  `NotAdvancing` / `PageCap` stayed terminal. Nothing here retried, restarted on reconnect, or persisted
  the cursor **across a screen close**: the projections above are connection-scoped
  (`threadByConversation` starts empty on each connection), so a cursor surviving a reconnect would be a
  stale-cursor bug rather than a resume point. [#778](remote-conversation-repository-history-walk.md#the-retry-and-the-two-restarts-778) reopened the
  reconnect gap; [#1354](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354) later gave the *in-memory* cursor a
  disk-backed twin that does survive a screen close, on the far side of a received page rather than a
  failed one — see that section for why a failed ask still writes nothing to it.
- **The opening ask stayed unconditional as #777 shipped it**, resolving the plan's second Open Question:
  `mergeHistoryRows` (the § above) already skips any row the thread holds, keyed on typed reconciliation identity, so a first page overlapping the `backfill_since` replay ring was fully absorbed with no duplicate
  rows. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) answered the question differently
  by removing the opening ask altogether — older history loads only on request, on both apps, so there is
  no first page to suppress or keep. The dedup argument still holds for every ask that remains: a page the
  reader does ask for that overlaps the ring is still fully absorbed with no duplicate rows.

## The retry and the two restarts (#778)

[#777](../codebase/777.md) left `Failed` as a one-way door: a page that failed left every loaded row and
the cursor in place but stopped the walk forever, and a reconnect left the walk holding a cursor the new
connection's projections could never honour. [#778](../codebase/778.md) reopened that door with a retry and
two self-triggered restarts. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) then removed
both restarts outright: their shared premise — that a cursor cannot outlive its connection — was wrong. The
cursor names a position in the daemon's append-only on-disk log (pyrycode `docs/protocol-mobile.md`, "The
cursor"), not in the connection, and the rows already drawn survive a reconnect as
`CachingConversationRepository.observeMessages`'s base. Only the retry survives from #778, joined by a new
always-available recovery path: any failure, not only a retryable one, now lets a fresh gesture ask again.
The design lives beside `ThreadViewModel`, not in this repository, and this section records only what it
depends on here.

- **`HistoryWalkStop.Failed` split into `RetryableFailure` and `PermanentFailure`** (#778), unchanged by
  #1352, on `RelayErrorException.retryable` — `requestHistory`'s own contract names `history.unavailable`
  as the **only** retryable code; the unknown-conversation `IllegalArgumentException` and the
  closed-session `IllegalStateException` this repository's KDoc documents both settle permanently. `AtStart`
  / `NotAdvancing` / `PageCap` are unchanged and stay terminal.
- **`ThreadHistoryDemand` still reads `requestHistory`'s return for `cursor` and `atStart` only** — the
  retry asks through the same `requestHistory` call this document describes above, so a retried page folds
  into `threadByConversation` exactly the way any other page does, via `mergeHistoryPage`'s existing
  typed reconciliation. Nothing on the caller side needed a second fold, and `ThreadHistoryDemand.kt` still
  imports neither `HistoryPage` nor `HistoryEntry`.
- **Neither failure is a one-way door any more (#1352).** `ThreadHistoryDemand.canAsk` holds after
  `RetryableFailure` and after `PermanentFailure` alike — only the three terminal stops (`AtStart`,
  `NotAdvancing`, `PageCap`) refuse a further ask. `asking()` claims the outstanding-request slot and
  clears a failure stop in the same step, so both a fresh gesture after any failure and the Retry press
  resume from the same `cursor` and `pagesLoaded`, loading the page that failed. The `retrying()`
  transition #778 added is gone; `onRetryOlderHistory` now claims the slot through `asking()` under
  `canRetry`, which is unchanged and still gates on `RetryableFailure` only, since Retry must stay inert
  against a non-retryable failure.
- **No restart exists any more, and none is needed.** #778's two restarts — an injected
  `repositoryAvailable: Flow<Boolean>` transitioning back to `true` re-asking the newest page, and a
  refused cursor doing the same — and the monotonic `walk` generation that protected a restarted walk from
  a superseded connection's late settle are all removed. With exactly one ask ever in flight (the CAS claim
  in `claimHistorySlot`) and no path left that asks by itself, every settle belongs to the walk's current
  ask; a settle landing after a reconnect is valid precisely because the cursor it answers survived that
  reconnect, so there is nothing left for a generation counter to protect against. The #861 fix to the
  second trigger — deriving `repositoryAvailable` from `bundle.coordinator.currentRepository.map { it !=
  null }` rather than the socket-level `ConnectionStateSource`, because a relay host's repository is
  published only at `PumpState.Open`, later than the socket's `Connected` — is **not** undone: the same
  flow now backs the new `hostAvailable: StateFlow<Boolean>` (desktop's `connectedConversationHostNow`)
  that gates every ask instead of restarting one. A gesture or a Retry press while `hostAvailable` is
  `false` sends nothing — logged as `event=history_ask_skipped reason=offline` for a gesture — and the
  oldest-end slot shows the new offline notice unless the walk has already reached the start of history;
  see
  [Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777).
- **A refused cursor (`history.invalid_cursor`) resets to the newest page without asking (#1352),
  replacing #778's refused-cursor restart.** `cursorRefused()` clears `cursor` back to `""`, releases the
  outstanding-request slot and clears any stop, carrying `pagesLoaded` forward so a daemon that refuses
  every cursor cannot buy a fresh budget. Nothing asks when this fires: where #778 answered a refusal by
  re-asking immediately, #1352 instead waits for the reader's next qualifying gesture (or a Retry press,
  now gated on the ordinary `canAsk`/`canRetry` path like any other ask) to carry the empty cursor forward,
  per the ticket's "older history loads only on request" rule. A refusal of the newest-page ask (an already
  empty cursor) still has nothing to fall back to and settles as an ordinary failure instead. Since
  [#1354](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354), the same refusal also resets the **saved backwards** position, so a stale cursor
  cannot steer
  the next pull or open. Since #1832 it retains durable coverage and gap cursors; only a position
  without coverage uses `writeHistoryPosition(conversationId, null)`.
- **The `repositoryAvailable` collector in `ThreadViewModel.init` stays, but only for its #1311 side
  effect.** It still collects `repositoryAvailable.distinctUntilChanged().drop(1)`, but since #1352 that
  collector exists solely to call `closeLocalSendWindow("reconnect")` — it no longer restarts the walk.

The screen-side half — the gesture that replaced #777's oldest-row scroll trigger, and the one oldest-end
slot's five states including the new offline notice — is
[Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
and
[§ the oldest-end history retry and restart](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-retry-and-restart-778).
