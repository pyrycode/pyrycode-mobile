# #2331 — Isolate idle page arrival in the reconnect proof

## Retrospective record

Written on 2026-10-20 in response to verifier finding 1 on
`ab6b11621b3269b8b3eee67c510af6d23df5bef7`. That implementation commit
predates this document. The required plan-before-code record is absent;
this retrospective record cannot reconstruct it or satisfy that chronology.
It records the implemented diagnosis, scope and verification evidence honestly.

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`:
  `pull` supplies input to `catchUpAuthoritative`, whose exact request-count,
  continuation, coverage, source identity, content, ordering and duplicate checks remain intact.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/HistoryProofPull.kt`:
  `pullHistoryPage` supplies one movement and a resting release to the proof and its regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenHistoryTest.kt`:
  `proofPullRequestsOnceEvenWhenThePageCompletesImmediately` and
  `proofPullStopsBeforeTheDelayedPageArrives` exercise the production gesture observer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`:
  `OlderHistoryGesture` accepts continuing reader movement after a request settles.
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`:
  the reader-movement prefetch contract permits further demand during an attributed fling,
  while idle arrival and slot release initiate nothing.
- `docs/knowledge/features/development-verification-gates.md`:
  shared screen tests run on the JVM; focused device evidence needs executed counts.

## Design source

N/A: this changes test input only. No product UI, visual design or Figma change is involved.

## Change

The Linux main sweep at `1a845e57d9483e42d2b9a71c7196135e49c506eb`
failed the recorded offline-reconnect proof because a swipe produced another history
request after page completion (expected seven, observed eight). A swipe contains
repeated movements and can continue flinging after a fast response, which legitimately
permits prefetch. Replace that proof's `swipeDown` with the shared internal
`pullHistoryPage` gesture: one downward movement followed by a resting release.
This isolates idle arrival while preserving every original assertion, production
behavior and recorded fixture. The two regression cases prove this single behavior
under immediate and delayed completion. No production types, signatures, lifecycle
state, error branches or dependencies change. This rework adds only this record.

## Testing strategy

- The implementation PR records the immediate-completion control failing with the
  original swipe: thirteen requests instead of one. It records 28 history screen
  tests and eight gesture unit tests passing, and a focused Linux device selection
  passing the recorded reconnect method plus both new regressions (three passed,
  zero failures or skips).
- The verifier on `ab6b11621` confirmed 6,355 JVM tests passed, including both new
  methods. Its UI gate executed the originally failing
  `RecordedMobileSuiteTest#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`
  successfully. An unrelated permission-prompt failure passed its exact dispatcher
  rerun; retain that initial failure rather than describing the first gate as clean.
  The accepted verdict and evidence are retained in ticket bundle
  `3023a89260825a6488836ce788f648f8c54ac750bd4dc96b8d748ffae49c5752`.
- For this documentation-only rework, rerun the affected history screen and gesture
  classes and the required formatting, lint, assembly and pre-verification checks.
  Reuse the verifier's counted device evidence; no device input changed in this rework.
  Full acceptance remains with dispatcher/verifier checks. The in-depth sweep has
  not been repeated and is not claimed as passed. macOS is outside this Linux ticket's scope.

## Documentation handoff

Pending for the documentation stage, from the verifier's Documentation handoff:
update `docs/knowledge/features/development-verification-compose-evidence.md`,
section `Compose evidence`, with the controlled-gesture technique for proofs asserting
one request and inert delayed arrival. Link #2331 and the counted Linux results.
