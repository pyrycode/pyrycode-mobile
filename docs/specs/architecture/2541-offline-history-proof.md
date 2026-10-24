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
| Pending/duplicate/stale/foreign evidence | Existing `RecoveryQueryAccountingTest`/owned-query controls remain required; no broader admission rule. |

## Error handling

Preserve timeout failures and strict tape failures. Progress diagnostics contain static event names, counts, numeric boundaries and Boolean ownership only, never payload or routing identities. Preserve original failure, passing rerun and every new attempt separately with raw reports and cleanup outcomes.

## Testing strategy

Write and execute the controlled regression red before repair, then green. Run the affected shared/JVM classes and the exact `RecordedMobileSuiteTest#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` on Linux with nonzero executed count and zero failures/errors/skips. The native method is device-only because it uses real storage, application lifecycle and background replay delivery. Retain raw XML, phone/replay progress, revision/package identities and cleanup evidence on the agents evidence volume. Run lint, assembly, instrumented compilation, forced Spotless and final pre-verification. The complete configured UI gate, including this named method, remains dispatcher-owned acceptance. These are offline replays with no daemon or Claude launch.

## Open Questions

- Which page-wait conjunct fails, and which controlled interleaving reproduces it? Resolve from cold replay and diagnostics before choosing the repair.
- Does the cause remain confined to the proof/input? Reassess scope if a separate product contract repair is demonstrated.

## Revisions

### 2026-10-24 — cold replay and immediate-growth control

Diagnostic source `1a20f29c99687cc042f9325a3ce55f51a1fd29f1` passed the exact Linux recorded method once: 1 executed/passed, 0 failures/errors/skips. The first Gradle build began on the initial checkout; the diagnostic file was committed before instrumented compilation. The retained source hashes identify the actual inputs. Every one of the nine page gestures returned, added exactly one accepted request and certificate, retained current ownership and advanced lower coverage. This is a passing diagnostic attempt, not a causal repair or controlled red.

The new `proofPullRequestsOnceWhenImmediateArrivalExtendsTheScrollableContent` control appends 25 rows on immediate demand through the real production gesture observer. It passes with the original pull helper. The affected history screen class passes 21/21 with zero failures/errors/skips. This rules out that particular immediate-growth experiment; it does not establish suite-order independence or fix the reported flake.

Original #2418 failure and its separate passing rerun are retained in bundle `75d33b59089857c2321a09e78ab6b92d0f4665d1c21f4263de6c95e1db13c73c`, digest `ffa79088e6c3759a4cb5b453e4f23e4e09de7854832200596216b026cfd84bc0`. They contain the timeout and counted outcomes but no page progress or raw phone trace. Their former worktree artifact directories no longer exist. Copies and this run's raw XML, phone/query/coverage trace, source/package hashes and successful cleanup outcome are preserved at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2541/builder-linux-20261024/`.

The failing conjunct remains unresolved. No retry, deadline, recording, matching/count assertion or production behaviour has changed. A scoped recovery assessment should test the eight recorded methods immediately preceding the original failure, followed by this method, with the new page diagnostics. Preceding-test state and machine load remain hypotheses requiring evidence; complete configured acceptance and a demonstrated red/green repair remain pending.
