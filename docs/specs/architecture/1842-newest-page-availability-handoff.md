# Reconnect newest-page delivery (#1842)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `hostAvailable`, the opening collector, `askForNewestPage`, `drainNewestPages`, and all history settlement paths share one slot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt`: newest side requests preserve the backwards cursor and stop.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: `HistoryRepo` and existing open, reconnect, cancellation, coverage and reader-demand regressions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemandTest.kt`: slot settlement and saved-position contracts.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: marker visibility and page arrival are inert without reader movement.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryCacheReworkTest.kt`: `trimmingResetsWalk` crosses the real 100,000-row cache cap and reloads saved cursor/stop and coverage through fresh file-cache instances.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: availability arrivals queue newest work; older and gap paging remain reader-driven.
- `docs/e2e-interactive-stream.md`: rung-4 owned-daemon proof; rung-3 remains with #1833.
- #1833 at `6c887a66`: `DurableGapProof`, `DurableHistoryProbe`, `TappingConversationRepository`, `DaemonFaultControl.posts`, and the owned post/peer/ping selection helpers.

Overlap: #1818 adds a separate ViewModel action; #1731 and #1766 add separate script branches; #1833 owns the proof being reused. These are local/additive overlaps, with no dependency on unmerged production work.
Rework overlap: #1909 adds separate unsigned-history cases in `HistoryCacheReworkTest`; the timeout adjustment stays local to `trimmingResetsWalk` and needs none of that branch's changes.

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

2026-10-07: The bounded display wait also failed after 30 seconds (two executed, original ping passed, durable failed, none skipped). This is persistent reader positioning, not delayed projection: a delivered history page can keep the cached row's viewport anchor. The proof does not own automatic scroll following. Check the one availability request and replay-exclusion assertions before semantics-revealing the already delivered newest row; retain its display assertion and explicitly require no additional request after revealing it. This mirrors the existing marker-visibility check, sends no history and changes none of the settled-cache/replay waits. All physical gap pulls and chronological/deduplication assertions remain intact.

2026-10-07: With row reveal fixed, the durable method reached replay exclusion and failed because the completed reply was inside the 200-entry newest page. The imported fixture's 60-post batches do not put the reply outside that page. Strengthen the fixture to 120 posts per batch (240 total), within the existing bounded post control. Keep the completed-reply and older-post exclusions, all settled-cache/replay waits, at least two reader pulls, exact chronological post identities and no automatic older requests. This changes test volume, not delivery or coverage rules; no history request is issued by the harness.

2026-10-07: The enlarged fixture passed newest-page exclusion, multi-page reader catch-up, deduplication and settled-reply checks, then failed reply placement between the batches. The owned `channel.post` control acknowledges acceptance before asynchronous delivery finishes. The protocol confirms posts themselves emit no `turn_end`; retain the existing reply wait and additionally wait for the peer to observe the first batch's final `assistant_delta` before sending the reply. This certifies the intended durable ordering without requesting history or weakening any assertion. The protocol source is the sibling `pyrycode/docs/protocol-mobile.md`, `assistant_delta`; daemon acceptance/delivery are `channelDelivery.accept` and its delivery loop.

2026-10-07: First-batch observation passed, but the final reply-placement assertion failed again (two executed, original ping passed, durable failed, none skipped). The preceding revision misread the post-completion contract: the current protocol's `assistant_delta` and `turn_end` sections and `channelPostEmitterV2.complete` explicitly include post completion with `producer: channel_post`. The generic peer wait was returning an already recorded post completion before the interactive reply finished. Retain first-batch observation and require a bounded non-post `turn_end` for this conversation's only interactive reply before accepting the newer batch. All delivery, exclusion, gesture, ordering and deduplication assertions remain unchanged.

2026-10-07: Verifier findings 1 and 2 identify the two real-file `trimmingResetsWalk` cases exceeding `runTest`'s one-minute wall deadline. They construct no ViewModel or concurrent collector: the body reconciles 100,001 rows, writes the production 100,000-row retained document and reloads it through a fresh repository. The verifier's baseline took 39.524/38.726 seconds; its PR failures took 65.889/71.487 seconds. A fresh unchanged rework run passed both in 17.082/15.043 seconds, confirming load-sensitive execution duration rather than a deterministic suspended handoff. Give only this helper a bounded three-minute deadline; retain the production row cap, disk implementation, restore path and every trimming, saved-position, coverage and reader-demand assertion. Run both named cases and their full class, then the whole unit/shared suite, to provide fresh counted evidence. No cache production change is needed. Total written work remains below 1,600 lines.

## Documentation handoff

Pending for the documentation stage, carrying forward the verifier's handoff:

- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`, "The oldest-end history demand": record the confirmed free-slot/pending-arrival failure and readiness-to-drain handoff; retain one arrival per transition, single-slot delivery and reader-driven older paging.
- `docs/e2e-interactive-stream.md`, scripted ping guidance: document the durable twin, 120-post batches, inert semantics reveal and producer-aware completion wait. Keep #1833's ownership of live and external force-stop evidence.
