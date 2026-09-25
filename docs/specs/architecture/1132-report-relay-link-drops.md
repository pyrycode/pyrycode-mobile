# #1132 — Name unplanned daemon relay-link drops when a gate run fails

## Files read

- `scripts/e2e-emulator.sh` → `report_stale_pairing_codes` (#993), the shape to mirror; the `TEST_STATUS` block after the Gradle test task, where it is called.
- `scripts/test_e2e_emulator_gradle.py` → `EmulatorBuildBeforeMintTest.run_test_task`, which extracts the function plus the test invocation and runs them against fixture logs.
- `../pyrycode/internal/transport/wssclient.go` → the reconnect loop in `WSSClient` logs `"transport: disconnected"` with `uptime` and `err` attributes on every link end.
- `../pyrycode/cmd/pyry/main.go` → the daemon logs through `slog.NewTextHandler` on stderr, which the harness redirects to each `DAEMON_*_LOG`. Each line therefore starts `time=<RFC 3339> level=… msg="transport: disconnected" … err=…`.

## Design source

N/A — harness script only, no UI.

## Change

Add `report_relay_link_drops` beside `report_stale_pairing_codes` and call it right after it in the failed-test branch, before `exit "${TEST_STATUS}"`. It walks the same four `name:log` pairs. For each existing log it keeps lines containing the fixed string `msg="transport: disconnected"`, drops those containing the fixed string `context canceled` (the harness's own kill), and extracts only the leading `time=` token with `sed`. A daemon with at least one remaining drop gets one stderr line:

`[e2e] WARN: relay_link_dropped: <name> (<log basename>) lost its relay link at <time>, <time>; …`

Nothing else from a log line is printed, so addresses, tokens and keys never reach the gate output. Every pipeline ends in `|| true` and the loop uses plain `if`/`continue`, so the function cannot fail the run under `set -euo pipefail`, and the exit status stays the test task's.

A successful run never reaches the call, so it prints nothing new. A failed run with no unplanned drop prints nothing new either.

## Testing strategy

In `scripts/test_e2e_emulator_gradle.py`, `run_test_task` extracts both report functions (the invocation now calls both). New cases, alongside the #993 ones:

- Failed run: `daemon.log` holds a `context canceled` disconnect and a `can't assign requested address` one with a TCP address; `daemon-b.log` holds two `pong timeout` disconnects; the others hold none. Expect exit 3, exactly two `relay_link_dropped` lines naming `e2e-x (daemon.log)` with its one unplanned time and `e2e-x-b (daemon-b.log)` with both, the `context canceled` time absent, and no address, error text or secret from the fixture in stderr.
- Failed run whose only disconnects end in `context canceled`: exit 3, empty stderr.
- Successful run with an unplanned drop in a log: exit 0, empty stderr.

Run with `python3 -m unittest scripts.test_e2e_emulator_gradle` (or the file directly).

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`, beside the paragraph describing `pairing_codes_stale`: describe the `relay_link_dropped` report and that a failure overlapping a reported drop time points to the gate machine's network, not the test or the app.
- `docs/knowledge/features/development-verification.md`, next to the `report_stale_pairing_codes` reference: a one-line mention of `report_relay_link_drops`.
