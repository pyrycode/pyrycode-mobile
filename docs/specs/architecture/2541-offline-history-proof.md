# #2541 — Reliable older-page recovery proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`: `catchUpAuthoritative` binds each gesture to exact request, certificate and continuous coverage progress.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/HistoryProofPull.kt`: `pullHistoryPage` injects one movement and a resting release.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeHistoryProbe.kt`: `Observation` provides current, exactly accounted receipts without creating demand.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`: immediate/delayed page controls exercise the real gesture observer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `OlderHistoryGesture` requires actual pointer provenance and movement near the oldest end.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: layout, semantics and page arrival cannot demand another page.
- `docs/recorded-tests.md`: preserve the original offline recording, complete payload/count matching, causal delivery and consumption.
- `docs/specs/architecture/2331-idle-history-proof-input.md`: repeated movement/flings can legitimately ask again after a fast response.

## Design source

N/A: test reliability with existing visuals; no product visual change or recording refresh.

## Context

The retained #2418 Linux failure times out in the older-page wait; its separate focused rerun passes. Neither identifies the failed conjunct. Start with a cold exact replay, then retain numeric gesture/request/certificate/coverage evidence before selecting the repair. This is one behaviour and its regression, expected within the ~500-line estimate; no production change is planned. No overlapping in-flight numeric feature branch changes the three candidate proof/input/screen-test files at planning time.

## Design

Keep every existing offline/cache/reply/paging/read/order assertion and the original 30000 ms deadlines. Instrument the page boundary with content-free progress so a missed/unfinished gesture, wrong accepted count, missing certificate and unchanged coverage can be distinguished. Exercise the demonstrated interleaving in a focused regression through the production gesture/query path; repair only the proof/input synchronization responsible. Do not add retries or create phone facts/certificates. A design-changing diagnosis will be recorded under Revisions before the implementation departs from this contract.

## State and concurrency model

No production state or coroutine ownership changes. Native pointer injection, Compose layout and asynchronous query completion are independent; test-side synchronization must establish their boundary without starting another demand. The history probe remains observational and binds current host/conversation/connection/source.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Cold reconnect/reset followed by older-page pulls | Exact recorded offline method, with per-page accepted/certified/coverage diagnostics. |
| Immediate completion during the deliberate pull | Existing `proofPullRequestsOnceEvenWhenThePageCompletesImmediately` plus the controlled failing-interleaving regression selected by diagnosis. |
| Delayed completion after release | Existing `proofPullStopsBeforeTheDelayedPageArrives`; arrival and revealing supplied rows stay inert. |
| Pending/duplicate/stale/foreign evidence | Existing `AuthoritativeHistoryProbeTest`/owned-query controls remain required; no broader admission rule. |

## Error handling

Preserve timeout failures and strict tape failures. Progress diagnostics contain static event names, counts, numeric boundaries and Boolean ownership only, never payload or routing identities. Preserve original failure, passing rerun and every new attempt separately with raw reports and cleanup outcomes.

## Testing strategy

Write and execute the controlled regression red before repair, then green. Run the affected shared/JVM classes and the exact `RecordedMobileSuiteTest#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` on Linux with nonzero executed count and zero failures/errors/skips. The native method is device-only because it uses real storage, application lifecycle and background replay delivery. Retain raw XML, phone/replay progress, revision/package identities and cleanup evidence on the agents evidence volume. Run lint, assembly, instrumented compilation, forced Spotless and final pre-verification. The complete configured UI gate, including this named method, remains dispatcher-owned acceptance. These are offline replays with no daemon or Claude launch.

## Open Questions

- Which page-wait conjunct fails, and which controlled interleaving reproduces it? Resolve from cold replay and diagnostics before choosing the repair.
- Does the cause remain confined to the proof/input? Reassess scope if a separate product contract repair is demonstrated.
