# Fragmented history display projection (#1917)

## Files read

- `HistoryCoverage.kt`: `displayRows`, `unsignedPositions`, and `historyKeys` currently multiply gaps by rows/deltas; unsigned spans and cursors remain authoritative.
- `ThreadViewModel.kt`: `threadContent`, `threadItems`, and `historyCoverage` join held content with display metadata; demand remains independently owned.
- `ThreadHistoryRows.kt`: `historyMarkersFor` and `foldedAgentHistoryMarkers` consume stable history keys and exact unsigned anchors.
- `HistoryCoverageTest.kt`, `UnsignedHistoryCoverageTest.kt`, and `ThreadViewModelTest.kt`: existing fragment, restored unsigned marker, and single-page demand contracts.
- `ThreadScreenHistoryTest.kt`: physical reader pulls and marker settlement must still cost one page per touch.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-oldest-end-history-demand.md`: preserve closed-agent targeting and touch demand latching.
- `docs/knowledge/features/conversation-cache.md`: restored coverage remains unresolved independently of retained displayed rows.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected the populated dark thread screenshot and design context: messages share 20dp gutters and a 16dp row rhythm, with the composer below the viewport. Reuse `HistoryGapRow` unchanged (`bodySmall` / `onSurfaceVariant`, centred); only marker eligibility and placement change.

## Context

Restored fragmented coverage makes channel opening perform a gap-by-row hash scan on main. The repair changes display projection only; coverage storage, retention, wire identity and paging contracts stay unchanged. Dependencies #1909, #1910 and #1911 are closed and present. No in-flight branches overlap the planned production files. No decision record is needed.

## Design

Prepare history keys once per held row, retaining delta keys when splitting assistant segments into display fragments. Binary-search normalized unsigned spans to detect fragment boundaries and assign prepared displayed keys to spans. Record the first displayed row key in each occupied span, then project internal gaps only when their immediately adjacent spans are both occupied. Preserve row order and multiplicity; fragments retain the shipped collision-safe ids.

For nonempty displayed history, select at most one unresolved edge at or before the oldest durable displayed position. Pick the nearest edge, preferring known coverage over unknown coverage on a tie, and attach it to the oldest row. Hidden gaps remain in the unmodified coverage. Empty history projects no markers. Marker anchors remain `ULong` throughout.

A repository-local projection result holds rows and anchor/key pairs, without UI dependencies. Existing consumers keep `ThreadHistoryMarker` and their stable keys. No consumer signature changes are required beyond a defaulted worker dispatcher on the ViewModel. Forecast: approximately 850 total written lines, two internal result types, at most ten test/helper updates, four acceptance criteria and no new error branches; within the sizing boundaries.

## State and concurrency model

Keep repository/live folding and demand ownership on main. Combine immutable input snapshots, then use `mapLatest` and `withContext` on an injected Default worker for fragmentation, hashing and marker lookup. Check cancellation through the bounded loops. A superseding snapshot cancels old work and prevents stale publication; ViewModel teardown cancels the worker via `viewModelScope`. The no-gap/no-unknown fast path does not need history keys. Connection loss retains held metadata and creates no projection-driven demand.

## Error handling

Projection is pure and introduces no fetch or error state. Coverage validation and typed paging refusal remain unchanged. Cancellation propagates rather than becoming a UI failure. Emit a content-free debug projection completion event with row and marker counts.

## Testing strategy

Write failing deterministic projection tests first. Restore a serialized fixture representing 36000 durable entries and 18000 separated spans, with sparse and large displayed subsets. Assert exact anchors/keys, absent adjacent sides, oldest-edge selection, known/unknown ties, empty history, unsigned positions and split assistant content. Count prepared keys and position lookups to bound work independently of elapsed time. Probe ordering, replay and restoration invariants without mutating coverage.

Use separate controlled main/worker schedulers in ViewModel tests to hold projection pending while composer edits progress, supersede inputs and clear the destination. Update the empty-history marker expectation and inject a test worker only where restored coverage is exercised. Preserve and run existing unsigned marker, folded-agent and single-page-per-pull tests.

Add a managed-device regression for the sparse restored fixture using the real worker dispatcher and input method; verify expected markers, composer editing, scrolling and one page per physical pull. It belongs in `androidTest` because paused Robolectric main cannot prove real worker/main and IME progress. Run its named UI-gate selection and record nonzero executed/failed/skipped counts. Rung-3 durable-gap operator proof remains owned by #1833.

## Open Questions

None.

## Revisions

- 2026-10-07: The focused projection and device tests passed, but reading `foldHistoryToolRuns` exposed another gap-by-row scan in the render consumer. Carry the prepared display-row reference beside each marker's existing history key and unsigned anchor, use identity sets and collision-safe message ids for folding (agent attribution copies message rows), and retarget that reference onto a closed agent header. Existing manually constructed markers retain the history-key fallback. This removes hashing from the main-thread render path for projected markers and preserves marker identity/targeting. The scope now includes `ThreadHistoryRows.kt` and `ThreadHistoryDemand.kt`; forecast remains below 1000 written lines with two internal result types and no required consumer signature migrations.

- 2026-10-07: The scripted ping durable-gap twin recovered all fixture content after two pulls, then tried to demand a nonexistent marker while cached coverage still reported unresolved metadata. Adapt `DurableGapProof.catchUp` to walk the missing fixture posts/reply, then wait independently for displayed marker closure. Preserve its minimum two pulls, exact request counts, no page-arrival demand, uniqueness, settled reply and rendered chronology assertions. Hidden cache gaps remain unresolved; persistence policy is untouched. The rung-3 scenario stays owned by #1833; only its shared eligibility/timing assumptions are adapted here.
