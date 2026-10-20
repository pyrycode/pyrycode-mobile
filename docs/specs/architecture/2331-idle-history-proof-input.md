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

## Revisions

### 2026-10-20 — Investigate the unrelated scripted gate failure

Verifier finding 1 on `cba4d539002b21702f9c62bba4d3e371d029f81e`
names `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_replySuggestionLongPressSends`.
The full scripted gate and its installed-app comparison both failed before long press.
The retained daemon logs show `baseline_required`, producer-gap close 4414 and
reconnect immediately after the first completion in both failures. This correlation
does not establish the cause. `ReplySuggestionE2EAssertions.assertReplySuggestionLongPress`
retains a concrete connection repository, so connection retirement is a diagnostic
lead; changing its observation alone would not prove the UI offer remains eligible.

The focused Linux `scripted reply-suggestion` run on pushed candidate
`5c3efea5405910b1a34f5f106cb670769ffb1187` executed and passed one method,
with no failures or skips. Its daemon trace has no producer gap. Preserve both
earlier failures: this focused pass does not replace complete gate acceptance or
establish that main also fails. The suggestion test, helper, fixture and harness
are identical on this candidate and main `77b5717d6d3a1dba9f1a92a251162e7f9b3770e2`.

Scope remains the idle-history proof. A suggestion/reconnect product repair is a
separate observable behavior and is not added without a demonstrated cause and
controlled regression. No production behavior, assertion, timeout or fixture changes
in this rework. The existing `session-error` disposition remains accepted inherited
evidence; it is not a new blocker or a new finding.

Pending evidence:

- Focused main comparison task
  `9237b07b0b9b35ddca3805d4a4ce92f125e33cf372ae46778ccea433b1088eab`,
  using `mobile-scripted-evidence` on the pinned main revision above.
- Dispatcher investigation assessment
  `d295acf20f083fbb1d39d9ecbb4c315da1fe774f350d25c52aa47fd347789c2d`,
  for the retained installed-app sequence and gap/reconnect timing. It executed
  zero tests and becomes eligible after this worker ends.
- Complete dispatcher gate acceptance and the inherited-failure disposition for
  reply-suggestion. Finding 1 remains unresolved until that evidence is assessed.

The retained verifier bundle is
`0d9161272b62ed952108b5cb0d2bb04e4cd9fdc46419e11ef8055a463e0a5b70`
(digest `26210acb13b28d215ef1647451273b576c363d0703bd16292bb4d9e98e1405a6`);
all 140 retained file hashes were verified. Fresh focused XML and phone logs are
retained with the rework evidence referenced in the PR.
