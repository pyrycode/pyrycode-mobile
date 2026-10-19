## Files read

- `InteractiveStreamE2ETest.kt`: `interactiveTurn_peerStartedTurn_continuesOnPhone` and `withOwnedReply` now reuse the application's subscription, preserve navigation and check each retained row while composed.
- `E2eTestApplication.kt`: `tappedRelayRepositoryModule` and `passiveThreadOwner` bind observations to the actual host and current connection.
- `PassiveThreadObservation.kt`: `PassiveThreadProbe`, `PassiveThreadObservation` and `retainedPeerUser` reject stale subscriptions and preserve exact user identity/content and uniqueness.
- `docs/specs/architecture/2317-peer-turn-reopen.md` and `app/src/androidTest/assets/peer-reopen-2317/investigation.txt`: retained failed cold-read experiments, repaired passes and the superseding passive-observation design.
- `docs/knowledge/INDEX.md`, `features/thread-screen.md` and `features/development-verification.md`: lazy composition is distinct from retained data; counted native reports and persistent raw evidence are required.

## Change

Reconcile the Linux sweep timeout at `d20e92cd4b5b58ef0ce6df1f87ca8b2c297b7699` with repair #2317, merged through PR #2319 at `dde1b41a6fde1dd0e545f9ad0e357b6256092fc8`. The diagnosed extra cold assertion collection consumed the recorded reopen backfill before the reopened application subscription; offscreen data loss was not established. Reuse the merged repair without changing production code, tests, strict replay inputs, recording bytes, assertions or bounds. Add only a checked, content-free evidence summary at `app/src/androidTest/assets/peer-reopen-2318/evidence.txt`, retaining raw logs/XML, revisions and verified hashes privately on the persistent agents volume. Preserve every earlier failed result as failed.

One deliverable: establish the reported peer-turn regression's resolution evidence. Forecast: approximately 100 written lines, two files, zero new types or consumer updates, three acceptance criteria and zero new error branches. Both sketch and written plan fit the limits. Remote feature-branch comparison found no overlap with these two new paths. No UI-visible change, Figma check, new lifecycle state or documentation-stage requirement applies.

## Testing strategy

On Linux, execute `python3 scripts/android-test-gate.py ui --tests de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_peerStartedTurn_continuesOnPhone` on the post-repair candidate. Inspect fresh raw XML for one executed/passed method and zero failures/errors/skips, and phone diagnostics for initial completion plus reopened user/reply display through the current subscription. Verify retained #2317 failure/pass evidence and original sweep counts independently. The existing device test exercises asynchronous replay, subscription ownership and native lazy composition; no new test or capture is needed for this evidence-only change.

Run lint, assembly, instrumented Kotlin compilation, Spotless apply/forced check and final assembly plus `scripts/pre-verify.py --gradle` after the final merge of main. Push evidence before final checks and open a PR. The dispatcher owns this ticket's complete configured gates, including the named method in its UI gate before verifier PASS, and the subsequent Linux main sweep. Retain its revision, raw report and executed/passed/failed/skipped counts before declaring the sweep regression resolved. A focused pass alone does not prove suite-order independence. If a residual failure occurs, retain it and investigate only this scenario; request wider diagnosis through the shared investigation route when focused checks cannot answer it. Other operating systems are outside this ticket's verification scope.
