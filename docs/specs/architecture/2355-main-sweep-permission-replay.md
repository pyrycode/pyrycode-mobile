# Main-sweep permission replay occurrence (#2355)

## Files read

- `docs/recorded-tests.md`: What replay proves, Complete native suite and Scenario host-removal readiness preserve strict matching and distinguish focused evidence from complete acceptance.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification.md`: focused Linux checks and durable counted evidence; complete suites belong to the dispatcher.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionPrompts_heldPerConversation` retains A/B assertions and fences host removal.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` resolves the current phone owner.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PermissionIsolationCleanup.kt`: `withPermissionIsolationCleanup` orders peer close, readiness fence and host removal while preserving earlier failures.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose` waits for required requests and queued delivery within the existing deadline.
- `app/src/test/java/de/pyryco/mobile/e2e/PermissionIsolationCleanupTest.kt`: `hostRemovalWaitsForPhoneCatchUpAndScheduledDeliveryAfterPeerCompletion` controls the observed race; strict-rejection, timeout and failure-order probes remain intact.
- `docs/specs/architecture/2345-linux-permission-replay.md` and `2349-permission-isolation-cleanup.md`: prerequisite design, controlled failures and separate residual read-evidence diagnosis.
- `app/src/test/resources/verification/permission-isolation-2345/evidence.json`: exact source, raw report hashes and counted prerequisite attempts.
- Original main-sweep stdout/stderr at agents `logs/2026-10-20T23-53-58-008Z_main-sweep_3be8ab9*`: first step-45 close mismatch, not the later test-end rethrow or focus snapshot.

## Change

Consume #2345, merged as `f10409c5c48e401911e16ec287894c96f0f14b0a`, including #2349's implementation `eaeb636274498f67d50738de3f7b6ca54db3c830`. Compare this occurrence at `3be8ab9163640f10da40c2de0a61682028e49d4c` with its controlled cleanup regression: host-2 closes before two required catch-ups, with 44 matched and 16 remaining. Registry reconciliation disposes the connection after pairing removal inside the scenario, before the activity fence. The repaired ordering keeps phone producers alive until catch-up and correlated delivery finish. Retain a content-free comparison/evidence index at `app/src/test/resources/verification/permission-isolation-2355/evidence.json`, with raw evidence and hashes on the persistent agents volume. Preserve the later pre-repair sweep and distinguish #2345's separate read rejection. No duplicate implementation, recording replacement, production contract or visual change is needed. One independently useful outcome, three criteria, approximately 180 written lines, zero exported types or migrated consumers, no new state/error branches. No remote feature branch overlaps the two proposed new paths.

## Testing strategy

Reuse #2349's controlled red and #2345's accepted cleanup regression evidence; run the unchanged seven-test cleanup class and one fresh exact-method native replay on Linux after the plan commit. Inspect fresh XML, logcat, exit status and executed/passed/failed/errors/skipped counts, including the original permission stages and full tape completion. Keep complete payload matching, causal delivery, full consumption and deadlines unchanged. Reuse #2345's accepted configured UI result as prerequisite evidence, while leaving this branch's configured dispatcher UI gate pending before verifier PASS. Preserve the next dispatcher-owned in-depth main sweep as pending until counted post-repair evidence exists, including any newly failing methods; focused success cannot certify the original 1817-test sweep. If the original mismatch persists, retain it and route the residual to #2345 rather than claiming resolution. No new test logic or state is introduced. Run the role-owned lint, formatting, assembly and final pre-verification checks. macOS is outside scope.

## Documentation handoff

- Pending for documentation stage: `docs/recorded-tests.md`, Scenario host-removal readiness / Counted Linux evidence: add this original main-sweep occurrence comparison, repaired source, fresh focused results and eventual configured UI evidence. Preserve the failed sweep, the later sweep's additional retrieval failure, and the pending dispatcher-owned in-depth sweep until counted evidence exists.

## Verification

At `1782cb8b65c395fc88b80f2393f9c65afd524520` (merged repair plus this plan), fresh exact-method Linux replay `ui-zqi07wfm` exited 0: one executed/passed, zero failed/errors/skipped, 8.219 seconds, all 26 original permission stages complete. The unchanged cleanup class also exited 0: seven executed/passed, zero failed/errors/skipped. The content-free index retains report hashes and the persistent raw XML/logcat/source location. #2345's accepted verifier at `b575f0e5442c3960fd4cd3adb79e5341c6079ce9` supplies separate prerequisite UI evidence (335 executed/passed; exact method passed once). This branch's configured UI gate and next post-repair in-depth main sweep remain pending for dispatcher acceptance; no complete suite was run by this builder.
