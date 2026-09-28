# #1246 — Diagnose early permission settlement

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, `freshSettings`, `awaitPermissionReading`, `awaitFooter`, `LinkCut` — live scenario, fresh-read and structured-log capture patterns.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendPermissionMode`, `settlePermission` — acknowledged, refused, confirmed, and expired outcomes.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayLog.kt` → `RelayLog.sink` — debug-only outcome capture seam already used by the e2e class.
- `scripts/e2e-emulator.sh` → LIVE `TEST_TARGET` — curated method selector and dedicated host setup.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM` — executed-method floor.
- `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list`, `test_live_curated_list_excludes_ignored_methods` — selector guard.
- `docs/knowledge/features/thread-composer-footer.md` § Permission mode — a write acknowledgement is separate from the child's confirmed mode; refusal has a distinct failure path.
- `docs/knowledge/features/development-verification.md` § Emulator and real evidence — live acceptance requires XML counts; the dispatcher owns the full live gate.
- `docs/e2e-interactive-stream.md` § operator-bypass permission scenario — coverage ladder and historical gate count for documentation handoff.

## Design source

N/A — this ticket changes only a live test and its runner selection.

## Context

The current live assertion infers a no-op from the duration of a pending marker. A fast clear may instead follow an early confirmed reading or a refused write. The test must inspect the write outcome and the daemon's fresh reading before judging the Run configuration selection.

## Design

In `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, preserve the dedicated host, pairing, initial bypass reading, subsequent Plan → Bypass approvals → Manual approval transitions, and outside-workspace Read proof. Capture only the structured `permission_write` outcome around the first Manual approval tap, forwarding logs through the prior `RelayLog.sink` and restoring the sink in `finally`. Fail distinctly on `refused` or `failed`; require `acked` before classifying either a fresh `default` reply as early confirmation or a fresh `bypassPermissions` reply as an acknowledged no-op. Wait for the pending UI to settle without measuring elapsed time, read settings again for the same session, and require the selected Run configuration row to match that reply. Reject unexpected modes or session changes.

Restore the method to the LIVE selector and raise `LIVE_MINIMUM` from 37 to 38. Extend the selector test to require this method explicitly, so a future drop fails even if a different method replaces its count.

## State and concurrency model

The test's log capture covers one write, using a thread-safe outcome holder because `sendPermissionMode` logs from the app's coroutine. Existing ViewModel scope owns the write and its settlement; the test does not create or alter production jobs. The capture ends after the outcome is observed and restores the original sink on every exit.

## Error handling

The method reports a refused or failed write by its structured outcome; a missing outcome times out with an explicit assertion. An acknowledged write with a fresh mode outside `default` and `bypassPermissions`, or a new session ID, fails separately. The later Read prompt and witness assertions stay intact.

## Testing strategy

- First make the selector assertion require the missing method and run that Python test red. After restoring the selector and floor, rerun the focused Python test green.
- Compile `app/src/androidTest` and run the touched-scope Gradle lint and debug build gates. The revised instrumentation method requires real Claude and the dedicated operator-bypass host; the dispatcher-owned live gate must report one executed method, zero failures, and zero skips for a focused run. Do not treat compilation or a zero-test run as live proof.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md` under the operator-bypass permission scenario and live selector history to describe the reply-based settlement rule and restored gate count (38).

## Open questions

- Does the current daemon acknowledge and then confirm `default` early, or acknowledge a no-op that remains `bypassPermissions`? The focused live result determines which branch occurs; both are valid when the selected row matches the reply.
