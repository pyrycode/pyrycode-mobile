# Linux permission-isolation replay reliability (#2345)

## Files read

- `docs/recorded-tests.md`: Complete native suite, Scenario host-removal readiness and Atomic transport close distinguish required phone work from peer completion and preserve strict replay.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification.md`: counted focused native checks and dispatcher ownership of complete gates.
- `docs/specs/architecture/2349-permission-isolation-cleanup.md`: the required repair moved from split parent #2343 into merged #2349.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionPrompts_heldPerConversation` retains all A/B isolation assertions and now fences pairing removal.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PermissionIsolationCleanup.kt`: `withPermissionIsolationCleanup` closes the peer, waits for phone readiness, then removes the host, retaining earlier failures.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` resolves the current phone owner in replay.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` waits within the existing 30-second deadline for required requests and queued delivery.
- `app/src/test/java/de/pyryco/mobile/e2e/PermissionIsolationCleanupTest.kt`: controlled delayed catch-up/delivery, missing-work deadline, exact owner/payload rejection and failure-preservation regressions.
- Retained #2266 verifier bundle `529932756cd59581e2f8dd545096dd66ab9f8afd3781aa6b2a6688576ed78947`: original Linux failure and focused rerun, including the first stored mismatch rather than only its cleanup rethrows.

## Change

Verify the one requested behaviour on Linux after consuming #2349, merged as `313f151cc10e101675c717f750ca6bc56c13c502` with implementation `eaeb636274498f67d50738de3f7b6ca54db3c830`. The original #2266 failure closes `phone/host-2/0` through `RelayConnectionRegistry.reconcile` while a required `thread_catch_up` remains unsent (`matched=45 remaining=14`). Test-end and delivery errors rethrow that stored mismatch. Peer-observed turn end does not establish phone readiness; the activity-teardown fence runs after scenario pairing removal. Reuse #2349's controlled regression and cleanup fence; add no redundant repair if three fresh focused native runs and the existing regression confirm this cause is covered. Commit a content-free evidence index at `app/src/test/resources/verification/permission-isolation-2345/evidence.json`, retaining raw reports, diagnostics, source revisions, hashes, counts and failed attempts outside Git. If a distinct residual cause appears, revise this plan before implementation and preserve strict matching and every original isolation assertion. No production request, fixture, deadline or visual change is planned. Forecast under 300 written lines, zero new exported types or migrated callers, three acceptance criteria and no new state machine. No in-flight numeric feature branch touches either proposed new path.

## Testing strategy

Run the existing `PermissionIsolationCleanupTest` class on Linux. Reuse its prerequisite's controlled red evidence: `hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion` fails with old cleanup and passes with the fence, requiring both the actual catch-up send and delayed correlated reply before removal. Run `de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_permissionPrompts_heldPerConversation` through `scripts/android-test-gate.py ui --tests` in three separate fresh invocations. Each must execute and pass, with zero failures/errors/skips; inspect raw XML and permission-stage diagnostics. The existing native test exercises real Android graph/background scheduling; no new device-only test is added. Compare the original gate sequence/load and any later Linux occurrence before claiming the same cause. Focused passes alone do not prove suite-order independence: the configured complete Linux UI gate remains pending for dispatcher/verifier acceptance. Request wider recovery assessment only if focused evidence cannot answer a concrete residual question. macOS is outside this ticket's scope. Run lint, assembly, formatting and final `scripts/pre-verify.py --gradle` after the final merge/push; preserve counted evidence in the PR.

## Documentation handoff

- Pending for documentation stage: `docs/recorded-tests.md`, Scenario host-removal readiness / Counted evidence: add this ticket's Linux diagnosis, three fresh focused results and later configured dispatcher UI result, retaining historical failures and the distinction between focused proof and suite acceptance.
