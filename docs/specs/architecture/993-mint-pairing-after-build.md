# #993 — e2e harness: mint pairing codes after the Gradle build

## Files read

- `scripts/e2e-emulator.sh` → section "3. daemon", the host A/B/peer minting block ("pair against the running test daemon"), `start_bypass_daemon`, `start_answer_daemon`, the `GRADLE_TEST_ARGS` block and the `${DEVICE}DebugAndroidTest` invocation — every mint and the one Gradle call that builds, boots, installs and tests.
- `scripts/test_e2e_emulator_gradle.py` — extracts the test invocation from `GRADLE_TEST_ARGS=(` to `  --console=plain` and runs it against a stub `gradlew`; the new tests follow its shape.
- `scripts/test_e2e_emulator_two_host.py` → `function` / `run_function` helpers — how a single script function is extracted and run.
- `scripts/android-test-gate.py` → `run_scripted_all`, `main` — the script runs once per scenario with `DEVICE=connected` or the managed device, and `PYRY_FORCE_TEST_RUN=1` adds `--rerun`.
- `app/build.gradle.kts` → `defaultConfig` — `useRelayRepository` feeds `BuildConfig.USE_RELAY_REPOSITORY`; `GIT_SHA` comes from a value source that is the same across both invocations.
- `docs/knowledge/features/development-verification.md` § shell teardown — the test result must survive; the script captures and returns the incoming status.

Overlap: `origin/feature/967` appends to the LIVE `TEST_TARGET` list in the same script. Different block; a later merge may touch it.

## Design source

N/A — harness script, no UI.

## Context

The #966 live gate failed 23 of 24 methods because the Gradle phase took 28 minutes and every pairing code had been minted before it, past the daemon's 15-minute redemption window. The daemon logged `v2.handshake.reject.redemption_window_elapsed`; the test output only showed anonymous 30-second timeouts.

## Design

New run order in every mode: relay → daemons (A, B, bypass, answer) started and ready → **build** → **mint** → release watcher → test task.

1. **Build step.** A new section after the answer daemon starts:
   `"${GRADLEW}" -p "${REPO_ROOT}" assembleDebug assembleDebugAndroidTest "${GRADLE_BUILD_ARGS[@]}" --console=plain || die …`
   with `GRADLE_BUILD_ARGS=(-PuseRelayRepository=true)`. The test invocation keeps its own `GRADLE_TEST_ARGS=(-PuseRelayRepository=true)` line unchanged, so the existing extraction tests stay intact; a new test asserts the build's non-instrumentation `-P` properties equal the test invocation's. `assembleDebug`/`assembleDebugAndroidTest` are the packaging tasks both the managed-device and `connected` test tasks install, so the test invocation's compile and package tasks are `UP-TO-DATE`.
2. **Minting moves after the build.** The host A / host B / peer minting block moves, unchanged, below the build step. `start_bypass_daemon` and `start_answer_daemon` stop after their readiness loops; their two `pyry pair` calls move into new functions `mint_bypass_pairing` and `mint_answer_pairing`, defined and called after the build, each only when its daemon has no unmet code. The unmet codes (`pairing`, `peer_pairing`) and log lines are unchanged. Every literal `pair -pyry-name` in the script then sits textually after the build invocation.
3. **Failure diagnosis.** The test invocation ends `--console=plain || TEST_STATUS=$?` (with `TEST_STATUS=0` set just before the invocation), so `set -e` does not exit first. On a non-zero status the script calls `report_stale_pairing_codes` and then `exit "${TEST_STATUS}"`; the EXIT trap still runs `cleanup` with that status.
   `report_stale_pairing_codes` checks `DAEMON_LOG`, `DAEMON_B_LOG`, `DAEMON_BYPASS_LOG`, `DAEMON_ANSWER_LOG` — each only if the file exists — for the fixed string `redemption_window_elapsed`. On any match it prints one stderr line: `pairing_codes_stale: …` naming each matching daemon by instance name and log file basename. No log line is echoed, so no code, token or key can reach the output. No match → prints nothing, so the failure output is unchanged. Uses `if` statements, never a trailing `&&` list, so the function returns 0 under `set -e`, and no empty-array expansion (macOS bash 3.2 with `set -u`).
4. The header's "Execution order" comment names the new order.

## State + concurrency model

No new processes. The daemons stay up across the build as they already did across the test task.

## Error handling

- Build failure → `die` naming the build step; no codes minted.
- Test failure → exit with the test task's status; `pairing_codes_stale` printed only on a log match.

## Testing strategy

New tests in `scripts/test_e2e_emulator_gradle.py` (no emulator):
- Build invocation, extracted from `GRADLE_BUILD_ARGS=(` to `--console=plain`, runs against the stub with `-p <root> assembleDebug assembleDebugAndroidTest -PuseRelayRepository=true --console=plain`, and its `-P` properties equal the test invocation's non-instrumentation ones.
- Ordering: the build invocation's position precedes every `pair -pyry-name` in the script text, and each mint function is called after the build.
- Failure scan: the test block through its failure handler, plus `report_stale_pairing_codes`, run with a stub `gradlew` that exits 3: (a) the host B and answer logs contain `redemption_window_elapsed` → output names exactly those two, exit 3, and no stub token/code/key value appears; (b) no log matches (one log absent) → no `pairing_codes_stale`, exit 3; (c) stub exits 0 with matching logs → no message, exit 0.

Focused proof: `python3 scripts/android-test-gate.py scripted ping` — pairing still works after the reorder, and the test invocation's output shows the package tasks `UP-TO-DATE`. AC 3 (`android-test-gate.py live`) is the dispatcher's post-verifier live run.

## Documentation handoff (pending, documentation stage)

`docs/e2e-interactive-stream.md`: the paragraph beginning "The script: starts the relay → mints a device token" must describe the new order — relay and daemons, then the build, then the minting, then the test task — and name `pairing_codes_stale` and what it means (a daemon rejected a handshake because the pairing code passed its 15-minute redemption window).

## Open questions

- Does `--rerun` (from `PYRY_FORCE_TEST_RUN=1`) force the packaging tasks too? Expected no — it is a task option for the test task only. Resolve with the scripted run.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the daemon logs are daemon-authored and untrusted; `report_stale_pairing_codes` reads them only through a fixed-string `grep -qF` whose result is a yes/no, never echoed, evaluated or interpolated. The names it prints are the harness's own instance names and log basenames, not log content.
- [Tokens, secrets] No findings — the build invocation runs before any code exists and carries no instrumentation arguments, so it cannot leak one. The stale message names daemons only; the unit test asserts that no stub token, pairing code or server key appears in the output. Minting later shortens how long each code sits unused before the test redeems it. Pairing codes and tokens passed as `-P` Gradle arguments (visible in the host's process list) are pre-existing and OUT OF SCOPE here.
- [File / storage] No findings — the scanned logs live in the run's private `mktemp -d` `WORK_DIR`; the scan only reads, and a missing log is skipped by an explicit existence check. Cleanup's retention of logs on failure is unchanged.
- [Inter-process / Android surface] No findings — no app, manifest or intent change; `assembleDebug`/`assembleDebugAndroidTest` build the same APKs the test task already built.
- [Crypto] No findings — no key, nonce or handshake change; the redemption window is the daemon's and is untouched.
- [Network & I/O] No findings — daemons now idle through the build while connected to the relay (local, or production on LIVE). They held no new pairing during that time, and they were already connected before minting, so the exposure is unchanged; the build failure path `die`s and the EXIT trap stops them.
- [Error messages, logs] No findings — `pairing_codes_stale` is a static code plus instance names; the build-failure `die` names the step only.
- [Concurrency / shutdown] No findings — `TEST_STATUS` capture keeps `set -e` from skipping the scan, the explicit `exit "${TEST_STATUS}"` keeps the run non-zero, and the scan is written with `if` statements so it cannot itself fail the run under `set -e` or trip `set -u` on an empty array under bash 3.2.
- [Threat model] No findings — a hostile relay could at most delay the handshake; it cannot inject the marker into a host's own daemon log. A false `pairing_codes_stale` would only add a diagnostic line to a run that has already failed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- 2026-09-24, open question resolved, no design change: `--rerun` applies only to the device test task. In `python3 scripts/android-test-gate.py scripted ping` (`PYRY_FORCE_TEST_RUN=1`), the test invocation reported every `compile*`, `dexBuilder*`, `mergeProjectDex*`, `packageDebug` and `packageDebugAndroidTest` task `UP-TO-DATE`; only `pixel2Api33AtdSetup`, the test task and its result-proto merge ran. The ordering test counts eight `pyry pair` sites, since host A has an isolated-HOME branch and a real-HOME branch.
