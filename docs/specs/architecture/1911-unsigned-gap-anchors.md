# Unsigned durable gap anchors (#1911)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt`: `ThreadHistoryMarker` and independent backwards walk state.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `threadContent`, `recordCoverage`, `onDemandHistoryGap`, `historySeed`, request settlement and readiness drain.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: measured marker heights, first-crossed selection and touch/fling latch.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `visibleHistoryMarker`, `HistoryGapRow` and folded agent marker projection.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: thread gap callback binding.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: unsigned positions, cursors, received coverage and `displayRows` fragment boundaries.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: restore retains unsigned coverage independently of signed completeness guards.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: controllable history repository and lower-range demand tests.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: physical pulls, inert semantics reveal and selected-touch regressions.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: marker disappearance must never redirect a held touch to the backwards walk; readiness drains pending newest work without creating demand.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: only received continuous coverage closes gaps; cache and repository own atomic row reconciliation.
- `docs/specs/architecture/1842-newest-page-availability-handoff.md`: merged readiness and settlement handoff, retained unchanged.
- Protocol source: `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`, “Conversation history (v2)” (the sibling path is relative to the main checkout).

Remote feature branch audit found no unmerged overlapping changes in the six production files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot. The existing dark thread has opposing message bubbles, 20dp gutters, a translucent header/composer and rule/label/rule session boundaries. Preserve those components and assets. The existing gap label remains centred Material 3 `bodySmall` / `onSurfaceVariant` with its existing padding; the frame contains no separate gap design.

## Context

Dependencies #1909, #1910 and #1842 are merged. Coverage already stores exact unsigned spans and cursors, but the UI consumes signed projections that omit upper-range holes. Marker placement, measured selection and gap requests must use the same durable anchor through the unsigned maximum. No decision record is needed.

## Design

Give `ThreadHistoryMarker` an authoritative `unsignedAnchor: ULong`, with signed construction and a checked signed `anchor` compatibility accessor for existing representable callers. Never wrap, clamp or substitute upper ids. Existing lower-range fixtures remain unchanged.

Project markers from `unsignedGaps`, `unsignedUnknownEdge` and `unsignedPositions`. Unknown anchor zero remains reserved for authoritative unknown coverage; the signed compatibility uncertainty is not a new durable gap. Use unsigned row order/gaps when splitting display-only assistant fragments, leaving repository rows unchanged.

Retain the signed ViewModel callback as a lower-range adapter and add an unsigned callback. Screen wiring adds a defaulted unsigned callback that adapts representable anchors to the existing signed callback, and MainActivity binds the unsigned method. All production selection, height keys and row tags use unsigned anchors. Preserve signed row and measured-selection adapters where existing callers use them.

Request dispatch, coverage recording and refusal use `cursorForUnsigned`, `receivedUnsigned` and `refusedUnsigned`. Keep opaque cursors, page size, page budget and independent backwards cursor/stop behavior. Preserve existing conservative completeness guards and cache contracts; this ticket migrates gap targeting, not those compatibility contracts.

## State and concurrency model

No new state flow, job or ViewModel event. Existing Main-scoped `viewModelScope` requests claim the shared CAS slot and release it on settlement. A selected gap owns its touch and fling; a fresh touch resets selection. Restoration/page receipt/marker reveal create no older request. Existing readiness and settlement collectors continue draining only counted newest arrivals. Destination exit cancels jobs; lifecycle socket ownership is unchanged.

## Error handling

Reject stale/missing anchor demands before claiming the slot. Typed invalid-cursor errors remove only the refused opaque cursor and preserve the unsigned gap. Next reader pull uses the usable newest cursor or empty cursor; no automatic retry. Cancellation propagates. Other failures retain held content and static content-free logs.

## Testing strategy

Test first: run new regression against unchanged production and inspect the failing assertion. Add deterministic ViewModel probes using the controllable history repository for restored crossing-boundary and upper-range gaps, two simultaneous gaps, partial fill, rejected cursor, repeated/overlapping pages, non-rendering entries, unknown coverage and reconnect. Assert exact anchors/cursors, inert restoration/page arrival, held row identity/deduplication and independent backwards position after each event.

Add shared viewport methods `unsignedMarkersTargetFirstCrossedGap` and `unsignedGapSettlementKeepsSelectedTouch` using real touch pulls, exact unsigned tags and controlled page/marker changes. Run these named methods on the managed Android 13 device as well as Robolectric, with fresh XML counts and method confirmation. Shared tests remain in `app/src/sharedTest`; no new device-only class is needed.

Run existing history ViewModel, demand, newest handoff, unsigned coverage, cache/reconciliation and agent-marker tests, plus `ThreadScreenHistoryTest`. Run lint, assemble, Android test compilation and forced Spotless. Merge main and push before whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle`. Rung-3 durable-gap proof remains owned by #1833; no new live scenario.

## Open Questions

None. Forecast approximately 550 written lines across production, tests and plan, no new exported types, at most seven production consumers, two acceptance criteria and no new failure branches. Recount before implementation commit; all limits hold.

## Documentation handoff

Pending documentation stage:

- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, “The oldest-end history demand”: completed unsigned marker placement, measured selection, dispatch and refused cursor path; retain #1833 live-evidence ownership.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, “Resuming from the saved position”: unsigned restored gap targeting and display fragments with unchanged opaque cursors and unknown-coverage rules; retain #1833 live-evidence ownership.

## Revisions

2026-10-07: Unchanged-production regression executed one, failed one and skipped none: two restored received gaps produced only one marker. Red XML is retained at `/tmp/builder-1911/red.xml`. Migration includes `HistoryCoverage.displayRows`, whose signed fragment boundary lookups otherwise hide an upper-range gap inside a joined assistant row.

2026-10-07: Kotlin erases unsigned and signed ids to the same JVM constructor shape. The unsigned marker primary constructor puts `beforeRow` first, and named unsigned arguments distinguish it from the retained `(anchor: Long, beforeRow: String)` constructor without adding a dummy state field.

2026-10-07: Finished scope recount is approximately 650 written lines including plan and tests, six production files, no new exported types, seven production call sites and two compatibility reject checks. All size limits hold. The signed constructor/accessor and callback fixtures remain in place; the unsigned screen callback is appended to preserve positional caller compatibility.

2026-10-07: Verifier finding 1 exposed that a retained demand anchor can lie inside its older received span after partial fill. `displayRows` now derives fragmentation from adjacent received-span endpoints, retaining the original unsigned anchor solely for targeting. New partial-fill and serialized-restoration ViewModel probes assert held fragment content, exact marker placement and unchanged cursor identity across the signed boundary, the upper range and the maximum boundary. Both probes failed against unchanged production (two executed, two failed, zero skipped); evidence is in `/tmp/builder-1911/rework-red/`. Verifier finding 2 removes the copied adapter from signed screen fixtures so existing lower-range physical pulls exercise `ThreadScreen`'s production default directly. No styling, request ownership or wire contract changes. Rework brings total written work below 850 lines, with the other sizing limits unchanged.
