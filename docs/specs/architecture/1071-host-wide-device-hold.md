# #1071 — Host-wide device hold for the Android test gate

## Files read

- `scripts/android-test-gate.py` → `main` — mode dispatch, the `ui` early skip (`ui_suite_skippable`), the Go builds, then the single place each mode starts driving a device (`subprocess.run(command)` or `run_scripted_all`). The hold goes between the Go builds and that point.
- `scripts/android-test-gate.py` → `managed_avd` — already resolves `$ANDROID_USER_HOME` or `~/.android`, then `avd/gradle-managed`; the hold's path reuses that root.
- `scripts/android-test-gate.py` → `run_scripted_all`, `raise_interrupt` — SIGTERM becomes `KeyboardInterrupt` so the emulator is stopped; the hold must survive that path and still release.
- `scripts/test_android_test_gate.py` → `ui_command`, `test_ui_gate_skips_gradle_only_when_the_branch_cannot_affect_the_suite`, `test_live_gate_collects_only_fresh_reports_from_selected_device_path` — how `main` is driven under patches; every existing `main` test must point the hold at a temp home so it never touches the real `~/.android`.
- `docs/knowledge/features/development-verification.md` § "Device gate" — the documented gate contract (documentation stage owns the update).
- `~/.android/avd/gradle-managed/` on the host — Gradle keeps its own `*.lock` files and `active_gradle_devices` inside; the hold lives beside the folder, not in it, so a Gradle cleanup of that folder cannot delete a held lock file.

In-flight overlap: `feature/1086` adds one `LIVE_MINIMUM` line to the same script; not a dependency, build through.

## Context

Two independent runs on the same Gradle-managed AVD (`pixel2Api33Atd`) at once make the 11 window-focus device tests fail. Nothing serializes device runs across worktrees. The fix is a deterministic host-wide hold inside the gate script. No ADR needed.

## Design

A kernel advisory lock (`fcntl.flock`, exclusive) on one file shared by the whole host:

- **Path:** `device_hold_path()` → `<ANDROID_USER_HOME or ~/.android>/avd/pyrycode-device-gate.lock`. Extract the home expression `managed_avd` already uses into `avd_root()` so both share it.
- **Acquire:** `hold_device(mode) -> int | None` — opens the file (creating parents), tries `LOCK_EX | LOCK_NB`; on contention prints once to stderr that it is waiting and who holds it, then polls every second until acquired or the bound passes. On acquire, truncates and writes a one-line JSON record `{"mode", "worktree", "started", "pid"}` for waiters to read, and, if it waited, prints `Android gate: device free after <N>s waiting`. Returns the open descriptor (kept open for the rest of the process) or `None` on give-up.
- **Give up:** after `ANDROID_GATE_WAIT_SECONDS` (default **300**), `main` prints `Android gate: device busy, not a test result: gave up after <N>s; held by <mode> from <worktree> since <started>` and returns exit code **75** (`EX_TEMPFAIL`), with no stdout XML and no Gradle/e2e process started. An unreadable or empty record prints `held by an unknown run`. A non-numeric or negative value is a `parser.error`.
- **Release:** the descriptor is never passed to a child (Python descriptors are non-inheritable and `subprocess` closes fds), so the lock belongs to the gate process alone and the kernel drops it when the process exits for any reason, SIGKILL included. No stale-record cleanup is needed: a record is only read while `flock` says the file is held.
- **Where in `main`:** after the `ui` skip, argument checks, auth pre-flight and Go builds; immediately before the `Android gate: <mode> …; artifacts` line. So the skip takes no hold, and every device-driving mode (`ui`, `scripted`, `scripted-all`, `live`) does — including `DEVICE=connected`, kept simple rather than special-cased.

Default 300 s: device-only `ui` runs in the dispatcher's recent gate logs took 1m39s–2m22s of Gradle time, so a full wait plus a run stays inside the 10-minute pre-verifier cap.

## State + concurrency model

No threads. One process-lifetime descriptor. Polling uses `time.monotonic` and `time.sleep(1)`; both patchable in tests.

## Error handling

- Lock file cannot be opened (permissions, read-only home): `OSError` → `Android gate failed: cannot take the device hold: <error>`, exit 1. A gate that cannot serialize must not run unserialized silently.
- Give-up: exit 75 as above.

## Testing strategy

In `scripts/test_android_test_gate.py`, no emulator, real `flock` against a temp `ANDROID_USER_HOME`:

- A second process (a `subprocess` Python child holding the lock with a written record) makes `hold_device` give up after a patched short bound; stderr names the holder's mode, worktree and start time.
- Holder child killed with SIGKILL → the next `hold_device` acquires at once (kernel release).
- Holder releases mid-wait → `hold_device` acquires and prints the wait time.
- `main` in `ui` mode with the device held gives exit 75, never calls `subprocess.run`, prints no stdout.
- `main` `ui` skip path takes no hold (lock file never created) — extend `test_ui_gate_skips_gradle_only_when_the_branch_cannot_affect_the_suite`.
- Each mode takes the hold before its device command: assert inside the patched `subprocess.run` that the lock is held (a non-blocking attempt from a fresh descriptor fails).
- Invalid `ANDROID_GATE_WAIT_SECONDS` is refused.
- Existing `main` tests set `ANDROID_USER_HOME` to their temp dir.

AC1 (real overlap) is proven on the host: two worktrees of unchanged `main` run `UI_GATE_FULL=1 … ui` simultaneously before the change, then two worktrees of `main` + this script after it; results go in the PR.

## Documentation handoff (pending — documentation stage)

`docs/knowledge/features/development-verification.md`, `## Device gate`: state that device-using gate modes hold the managed device across the whole host; that `ANDROID_GATE_WAIT_SECONDS` sets the wait bound, default 300 seconds, with exit 75 on give-up; and that a direct `./gradlew …AndroidTest` run does not take the hold.

## Open questions

- Whether the "before" overlap reproduces the 11 failures on today's host; record either way in the PR.
