# Live diagnostic bundles stay host-scoped (#1252)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_logData_savesTheOwningHostsArchive`, `completeBundleLogs`, `selectHost` — existing two-host marker, archive integrity checks, cleanup, and obsolete Settings helpers.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `requestDebugBundle` — exact host ID selects the connection; the selected host does not route the transfer.
- `scripts/e2e-emulator.sh` → live `TEST_TARGET` — curated real-daemon method selection.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM` and `parse_live_tests` — executed count floor and focused live selection.
- `docs/knowledge/features/relay-debug-bundle-transfer.md` → “How it works” — terminal state and one-time archive ownership.
- `docs/knowledge/features/settings-screen.md` → “What it does” — Settings now has only Notifications.
- `docs/knowledge/features/development-verification.md` → “Emulator and real evidence” — live XML and nonzero execution requirements.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` → “Debug bundle (v2)” — source of truth for paired, daemon-global archive streaming and sensitive content.

## Design source

N/A — this ticket changes a live test for a host-backed transfer, with no UI change.

## Context

The #684 live test is ignored because #1239 removed the Settings Log data action. Its archive parser and two-host marker still serve the maintainer's host-isolation claim. Reusing them through the registry keeps the proof runnable without suggesting that Settings exports logs.

## Design

Rename the scenario for its actual host-backed action. Pair host B, mint a unique discussion ID on A, and mute it so only A's daemon logs that ID. Request each host's bundle via `RelayConnectionRegistry.requestDebugBundle(serverId)` and await `COMPLETE` separately. Consume each completed archive once, validate both with `completeBundleLogs`, then assert A contains the marker and B does not. A failed or incomplete transfer fails with a static status description; no archive bytes or log text reach output. Retain marker deletion and B unpairing in `finally`.

Remove the obsolete modal/picker path and its test-only helpers and constants when they have no other callers. Keep shared archive parsing and `selectHost`, which other live scenarios use. Add the renamed method to the curated live list and raise `LIVE_MINIMUM` by one.

## State and concurrency model

The test uses the existing paired connections and `runBlocking` with `withTimeout` on each transfer's hot `StateFlow`. A terminal status releases the wait. Archive bytes remain test-local and are discarded after in-memory validation. Cleanup runs on failure as well as success.

## Error handling

`UNAVAILABLE`, `BUSY`, interrupted streams, timeout, missing archive, and invalid archive structure fail the scenario. Assertion messages report status or archive structure only. Cleanup failures log exception class only, as the existing test does.

## Testing strategy

- RED: make the ignored scenario runnable before replacing the old UI route; its removed Settings row must fail.
- GREEN: compile the changed Android test and run the single renamed method through `python3 scripts/android-test-gate.py live --tests ...`, inspect fresh XML, and report executed, failed, skipped counts.
- Check curated selection and its floor together; run Spotless, lint, and `assembleDebug` for the touched scope. This scenario spends zero Claude turns but requires the live daemon, two paired hosts, and the managed emulator, so there is no deterministic scripted twin.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md` sections “Live mode (rung 3: live relay)” and “Verification status” to name the host-backed archive proof, remove the obsolete Settings/picker claim, and record the focused live result. Update `docs/knowledge/features/relay-debug-bundle-transfer.md` section “Testing” with the live two-host proof, without suggesting a Settings export.

## Open questions

- Whether the focused live gate can use this runner's Claude authentication and managed emulator; record the actual outcome in the PR and hand off any dispatcher-owned live execution.

## Revisions

- The existing `scripts/test_android_test_gate.py` assertion hardcodes the curated floor, so the implementation updates it to 42 and asserts selection of the renamed method. Its focused selector tests pass.
- This runner's `claude auth status` reports authentication unavailable, including with normal sandbox escalation. The focused live gate exits before running a test, so executed, failed and skipped counts remain unavailable here; the `needs-real-claude` dispatcher gate must supply XML evidence and those counts after verifier.
