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

**[#1572](https://github.com/pyrycode/pyrycode-mobile/issues/1572): one newest page at open and
on each host-availability arrival while the thread stays open.** The collector waits for the saved
position seed and repository availability, so offline open asks nothing until the host arrives.
After seeding, raw `repositoryAvailable` counts one arrival for the initial available state and
each observed false-to-true transition; repeated true emissions add nothing. Pending arrivals
survive an occupied slot across newest, older, retry and gap requests, as introduced by
[#1832](../../specs/architecture/1832-durable-history-gaps.md). They also survive lagging derived
availability: [#1842](../../specs/architecture/1842-newest-page-availability-handoff.md) confirmed
that raw true could queue work while `hostAvailable` was false and the slot was free. When derived
true finally published, no request settlement remained to drain that arrival. Logging
`history_newest_ask` alone therefore did not prove a request reached the repository.

The `hostAvailable` readiness collector now drains existing pending newest work without counting
another arrival. Request settlement supplies the other drain handoff, including after success or
failure while readiness still lags. Pending work is consumed only after claiming the shared slot;
at most one history request runs at once. A failed newest ask consumes its arrival without an
automatic retry. Destination exit cancels active requests and pending delivery. Readiness, slot
release, page arrival and marker visibility create no older/gap demand; those requests still
require reader movement or the existing explicit Retry action.

`ThreadNewestPageHandoffTest` gates the eager availability projection while driving production
collectors, rather than invoking a private drain or issuing harness history. Its delayed-readiness
regression exposed the free-slot failure against unchanged production. Keep this schedule alongside
offline open, repeated availability, successful/failed busy-slot settlement and exit cancellation
probes; an immediate availability fixture can pass while leaving the handoff broken. The
[durable emulator twin](../../e2e-interactive-stream.md#scripted-ping-durable-gap-proof-1842)
checks repository delivery after replay is emptied, then uses reader pulls for older catch-up.

The newest reply now retains durable entry coverage and page-edge cursors. A side ask leaves the
independent backwards cursor/stop untouched; a newest page that is also that walk's next page seeds
it normally. High-water and known/unknown gaps survive restore and reconnect. Coverage concerns
received entry ids, including non-rendering envelopes, never row ids or live frames. Overlap or
adjacency creates no hole; overlap elsewhere never erases a known hole. A known marker closes only
when coverage joins its older durable anchor. Nonempty legacy caches remain unknown despite saved
`atStart`, matching rows or verified overlap; only `at_start`, including an empty terminal page,
closes that unknown region. Empty uncovered caches get no conservative marker. See
[Resuming from the saved position](remote-conversation-repository-reads-and-thread-store-history-paging.md#resuming-from-the-saved-position-1354)
for the coverage and migration contract.

**Gap markers reuse the oldest-end loading label style.** `HistoryGapRow` shows “Load earlier
messages”, centred in `bodySmall` / `onSurfaceVariant` with the same gutter and padding, without a
count. Entries are not messages: one assistant reply can span many envelopes. Markers sit before
their newer content, between held older/newer rows. Display-only assistant fragments allow a marker
inside a split turn; tool folding keeps the first newer tool row visible. Non-rendering spans can
place a standalone marker at the newest content edge, including a thread with no message rows.

A joined background-agent block is the exception to that split
([#1827](thread-screen-subagent-tool-rows.md#attributed-assistant-prose-in-agent-blocks-1827)).
`foldHistoryToolRuns` keeps the block whole across a gap, because splitting it let child prose escape
its closed Agent run. `foldedAgentHistoryMarkers` instead moves a marker attached to a hidden block row
onto the closed run header. The marker keeps its original anchor and cursor, so a pull there asks for
the same gap. An expanded run header claims no marker: opening the run returns each marker to its
original delivered row. A header that also kept its first tool's marker drew one durable gap twice
when open, and unique row keys alone did not catch it. `BackgroundAgentProseTest` and
`BackgroundAgentProseScreenTest` attach gaps to the first Agent tool and to child prose, and assert
each anchor appears exactly once through closed, open, closed and collapse-off states.

**Unsigned gap targeting (#1911) preserves one identity from placement through dispatch.**
`ThreadHistoryMarker.unsignedAnchor` is authoritative through `ULong.MAX_VALUE`, including after
restoration. Projection uses `unsignedGaps`, `unsignedUnknownEdge` and `unsignedPositions`; measured
height keys, row tags and folded-agent markers keep that same unsigned anchor. Zero identifies only
authoritative unknown coverage, never uncertainty introduced by the signed compatibility view.
The signed marker constructor and checked `anchor` accessor remain for lower-range callers; upper
positions must use `unsignedAnchor` rather than wrapping or substituting an id.

A real reader pull calls `onDemandUnsignedHistoryGap(anchor)`, wired by `MainActivity` to
`ThreadViewModel.onDemandUnsignedHistoryGap`. The appended, defaulted screen callback forwards
representable anchors to the existing signed callback; the signed ViewModel method remains a
lower-range adapter. Measured marker bounds select the first marker crossed toward older content
when several are visible, before considering ordinary oldest-end demand. Stale or missing anchors
are rejected before claiming the shared request slot. Dispatch and settlement use `cursorForUnsigned`,
`receivedUnsigned` and `refusedUnsigned`, retaining the selected anchor across the signed boundary
and through the maximum id. Merely revealing a marker, semantics scrolling or receiving a page
asks nothing. Each pull costs at most one page, including cursorless walks that reread a covered
page. Each gap keeps its own opaque cursor and leaves the backwards walk's cursor/stop alone;
saved `AtStart` cannot block gap demand. A typed invalid-cursor refusal invalidates only the refused
opaque cursor, retains its marker and waits for the next pull, using the latest usable newest-page
cursor or empty cursor. Once a touch selects a gap,
its latch gates every further history demand through that drag and its continuing fling, before
consulting marker visibility. Page settlement can remove the marker, and movement can take it
offscreen; neither may redirect the same touch into the independent backwards walk. A fresh
touch resets selection. Four controlled-response `ThreadScreenHistoryTest` regressions cover
marker removal and movement offscreen during both drag and fling; keeping the marker present
throughout a test would miss this fallthrough. Shared physical-fling fixtures convert their dp/s
velocity using the test rule's density; a fixed pixel/s value can stop short on a managed device
while passing under Robolectric.

`unsignedMarkersTargetFirstCrossedGap` and `unsignedGapSettlementKeepsSelectedTouch` exercise exact
upper-range tags and physical pulls. The signed fixture
`markersAreBetweenContent_andAReaderPullTargetsTheFirstCrossedGap` omits the unsigned callback so it
checks the production default adapter. The [#1911 verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1924#issuecomment-6044984944)
and fresh affected-class XML confirm all three passed on managed Android 13: 26 executed/passed,
0 failed, 0 skipped. The configured device-only UI gate excludes these shared methods; its separate
run passed 199 executed tests, with 0 failed and 1 skipped. This is affected-class device evidence,
not execution of the named methods inside that gate. Rung-3 durable-gap operator-flow evidence
remains owned by #1833; #1911 adds no live scenario.

The shared `inFlight` flag still drives the oldest-end Loading row during newest/gap asks, even
when the independent backwards walk is at `AtStart`; this is the existing #1572 visual quirk.
On a device, [#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833) proves this demand
against a durable gap of more than two 200-entry pages, in both the real-Claude offline-read method
and its scripted twin. Only availability asks the newest page. Semantically revealing a gap marker,
the newest row or a page arrival issues no request. Each physical pull asks exactly one older page,
and at least two pulls are needed to close the gap. See the [#1833
evidence](../../e2e-interactive-stream.md#verification-status).

**Two current viewports of older loaded content trigger prefetch during reader movement**
([#1769](https://github.com/pyrycode/pyrycode-mobile/issues/1769)). `OlderHistoryGesture` and
`Modifier.olderHistoryPull` replace the old gesture-start-only 200dp band. Each qualifying
`onPostScroll` checks current geometry after the list consumes movement, using
`consumed.y + available.y > 0f` for the reversed list's older direction. A drag or fling can
enter the threshold after starting outside it. The observer consumes nothing, and an end
pull still qualifies when the list itself consumes no distance. Subsequent actual movement
can ask again after a page settles during the same drag or fling, unless that touch selected
a gap. Prefetch reduces spinner stalls but cannot prevent them when the response is slower
than the reader.

**Touch provenance must survive only the touch's own fling.** The pointer modifier observes a
real `PointerType.Touch` down without consuming it; pointer completion closes the drag window,
and cancellation clears all provenance. `UserInput` checks require an active touch. A touch
that produced movement can transfer provenance through a positive-velocity `onPreFling`;
only that attributed fling accepts `SideEffect` movement, and `onPostFling` clears it.
Semantics `ScrollBy` can emit `UserInput` without a pointer or a later fling, so the nested-scroll
source alone cannot identify reader movement. Programmatic and semantics scrolling, idle
page arrival, relayout and request-slot release never initiate or queue prefetch. Movement
toward newer content does not ask.

**`isNearOldestEnd` estimates hidden history rather than requiring the oldest row to be laid out.**
Under `reverseLayout = true`, item offsets run from the viewport's bottom. The threshold passed
by `ThreadScreen` is `layoutInfo.viewportSize.height * 2f`, read on each movement. For the highest
visible history index, the remaining distance is:

```text
edge.offset + edge.size + afterContentPadding - viewportEndOffset
    + unseenRows * (meanVisibleHistoryRowHeight + mainAxisItemSpacing)
```

When the oldest row is visible, `unseenRows` is zero and its edge gives the exact distance.
Otherwise the mean measured history-row height estimates unseen mixed-height content. Prompt
and tail rows are excluded from those measurements; no visible history row means no estimate
and no ask. Empty history is always at its end. The after-content padding term retains the
correction from [#1562](https://github.com/pyrycode/pyrycode-mobile/issues/1562): reversed-list top
padding is folded into `viewportEndOffset` and must be added back. `ThreadOldestEndBandTest`
pins hidden-row estimation, spacing/padding, prompt/tail exclusion and viewport changes;
screen coverage exercises real drags/flings entering the range and page-arrival anchoring.

**Every thread history request supplies `limit = 200`.** The private
`THREAD_HISTORY_PAGE_SIZE` constant covers backwards pages and Retry, opening/reconnect newest
side asks, and gap pages. Server-clamped, short or empty pages keep the existing cursor and
`atStart` termination rules; requesting 200 does not imply receiving 200. The ViewModel's shared
slot allows at most one request in flight per conversation. Offline and terminally stopped
backwards walks cannot ask, and prefetch before saved-position seeding completes is dropped
rather than replayed when seeding finishes. Opening/reconnect newest asks still wait for that
seed; explicit Retry retains its existing failure and availability gates. See
[history paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) for
merge, cursor and reconnect ownership.

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
`visibleHistoryMarker(...)` first, then
`listState.layoutInfo.isNearOldestEnd(oldestRowIndex, viewportSize.height * 2f, promptRowCount)`
when no marker is visible.

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
brought one back for the never-loaded case, which [#1572](https://github.com/pyrycode/pyrycode-mobile/issues/1572)
widened to every host arrival, so the gap now also covers every one of those asks: a thread with no
messages or gap markers drawn renders `EmptyThreadState` with no loading feedback while a newest-page ask is in flight,
exactly as a reader's own pull on an empty thread does. Still open.

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
- **The demand trigger checks position only during reader movement, and the oldest-end slot's own
  presence cannot initiate it.** § *The oldest-end history demand* above replaced the #777/#778 scroll-position
  predicate with `OlderHistoryGesture`/`olderHistoryPull`, which reads `LazyListLayoutInfo` live rather than
  a cached row count — mounting or unmounting the oldest-end slot changes the list's total item count but
  never changes where the *oldest thread row* sits, so the slot's own appearance still cannot retrigger the
  gesture that mounted it, for the same reason the old count-based predicate was careful to exclude it.
