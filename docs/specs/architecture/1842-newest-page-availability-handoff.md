# Reconnect newest-page delivery (#1842)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `hostAvailable`, the opening collector, `askForNewestPage`, `drainNewestPages`, and all history settlement paths share one slot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt`: newest side requests preserve the backwards cursor and stop.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: `HistoryRepo` and existing open, reconnect, cancellation, coverage and reader-demand regressions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemandTest.kt`: slot settlement and saved-position contracts.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: marker visibility and page arrival are inert without reader movement.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: availability arrivals queue newest work; older and gap paging remain reader-driven.
- `docs/e2e-interactive-stream.md`: rung-4 owned-daemon proof; rung-3 remains with #1833.
- #1833 at `6c887a66`: `DurableGapProof`, `DurableHistoryProbe`, `TappingConversationRepository`, `DaemonFaultControl.posts`, and the owned post/peer/ping selection helpers.

Overlap: #1818 adds a separate ViewModel action; #1731 and #1766 add separate script branches; #1833 owns the proof being reused. These are local/additive overlaps, with no dependency on unmerged production work.

## Context

The raw availability collector can observe true and increment `pendingNewest` before the eager `hostAvailable` projection publishes true. `drainNewestPages` then returns with pending work, a free history slot and derived availability false. Publishing derived true has no drain handoff. With no outstanding request to settle, the arrival remains stranded. A controlled collector schedule will confirm this candidate before production repair. No new decision record is needed.

## Design

Keep raw availability transitions as the arrival counter so repeated true emits do not add work and distinct observed reconnects survive lag or a busy slot. Add a ViewModel-owned collector of true `hostAvailable` states that only calls `drainNewestPages`. This supplies the missing readiness handoff without creating arrivals. Existing settlement drains remain responsible for busy slots. The CAS slot, request page size, walk/side selection, coverage and row merging are unchanged.

No new production type, signature, UI state or event is introduced. This is request-delivery logic with no visual design change.

## State and concurrency model

Both availability collectors and requests belong to `viewModelScope` on Main. The raw collector still waits for `historySeed`; the readiness collector cannot send until an arrival has been counted. Pending work is consumed only when the existing slot is claimed. Destination clear cancels collectors, active requests and pending delivery. The connection lifecycle and repository dispatchers remain unchanged.

## Error handling

Keep existing typed failures and cancellation propagation. A failed newest request consumes one arrival and never retries itself. Slot release can drain other queued newest arrivals, but creates no older/gap demand. All diagnostics stay static/content-free; no cursors, credentials or entries are published.

## Testing strategy

Add `ThreadNewestPageHandoffTest` using StandardTestDispatcher and a source flow that gates only the eager availability projection before it emits true. Drive the production collectors and public reader callbacks; never invoke private drains. First run the delayed-availability regression against unchanged production and require an assertion failure after releasing the gate. Cover offline and initial available opens, repeated true emissions, multiple arrivals queued behind older/retry/gap/newest work, successful/failed settlement before readiness, failed newest consumption, saved cursor/stop preservation, and destination cancellation. Observe repository requests and maximum concurrency.

Reuse only #1833's deterministic durable-gap method/support and helper tests. Preserve settled-cache and replay-exclusion waits, marker inertness, two-or-more pulls, chronological deduplication and completed reply assertions. The device test requires real relay/daemon restart, persistent Android cache and physical reader gestures. Scripted ping selects both the original ping and durable twin; scripted-all inherits that selection. External force-stop and live changes are excluded.

Run the three named history classes, the new handoff class, probe helper and existing coverage/reconciliation/cache tests with counted XML. Run Python helper/selection tests, shell syntax, focused scripted ping, lint, assemble, Android-test compilation and forced Spotless. After merging main and pushing, run the whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle`.

## Open Questions

Resolved by the red regression below.

Sizing: approximately 900 written lines including reused test support, scheduling probes and this plan; zero new exported production types, zero public consumer migrations, four acceptance criteria, no new rejection branches. Within all hard boundaries.

## Revisions

2026-10-07: The unchanged-production regression executed one test, failed once and skipped none. After raw true was observed, derived availability was false and the repository saw zero asks. Releasing the derived collector made `state.hostAvailable` true, but requests remained empty instead of one newest request. This confirms the free-slot/pending-arrival stranded state and the missing readiness-to-drain handoff. The repair adds only that handoff. Red XML is retained at `/tmp/builder-1842/handoff-red.xml`.

2026-10-07: First scripted ping executed two methods: original ping passed; durable catch-up received the newest page and gap, then failed its immediate display assertion. Repository/cache predicates can complete ahead of Compose projection/layout. Preserve the display assertion and wait up to the existing 30-second readiness deadline for it; this adds no scroll or history demand and changes none of the cache/replay waits or exclusion assertions. If the display does not settle, the proof still fails. Added #1833's two pure durable invariants for replay/page permutations and serialized gap-cursor restoration. Final forecast is approximately 970 written lines.
