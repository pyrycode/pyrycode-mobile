# Earlier-Other IME focus evidence (#1797)

The initial host-focus timeout did not reproduce with the shipped emulator mitigation in `E2eInstrumentationRunner.quietSystem` (`186c399b2e875e9bd92d18dec00b02d45bd8e12c`). The existing method ran unchanged and passed. Credit that repair; this ticket adds evidence rather than another harness workaround. One successful focused run establishes this execution, not a guarantee against every future focus failure.

## Focused execution

Tested revision: `de58447054ea782b422b3a22d714df40424e21a8` (plan committed; Kotlin and Gradle sources unchanged from base `13ceb8b609394e9e1e9baad0c38afe9731728354`).

```sh
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.QuestionBatchModalTest#ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen' \
  -Pandroid.experimental.androidTest.numManagedDeviceShards=1 \
  -Pandroid.testInstrumentationRunnerArguments.disableAnimations=true \
  --console=plain
```

The command ran from the ticket worktree using the dispatcher SDK and the existing `scripts/android-test-gate.py` `device_hold`, after APK prebuild. Device wait: 270 seconds. Execution: 2026-10-05 07:51:57–07:52:17 UTC. Exit status: **0**. Fresh XML: **1 executed, 1 passed, 0 failed, 0 errors, 0 skipped**, on `pixel2Api33Atd_0` (one managed-device shard).

- `focused-api33.xml`: copy of the fresh managed-device `TEST-pixel2Api33Atd_0-_app-.xml`, with CRLF normalized to LF for repository whitespace checks.
- `focused-run.json`: exact command arguments, tested revision, timestamps, exit status, counts and retained XML, original XML and full per-test logcat SHA-256 checksums.
- `test-lifecycle.txt`: the per-test logcat's start/finish records. The full per-test logcat remains in the managed-device results and was also copied to `/tmp/builder-1797/`. Inspection found no `FocusRecord`, fatal exception, app crash or ANR markers in that log; the lifecycle extract does not replace XML outcome evidence.

The unchanged method still requires initial host focus, a wholly header-obscured earlier Other field, a focused retained draft, visible real keyboard with positive inset, and field clearance between header and composer after keyboard open/dismiss/reopen. No assertion was skipped or converted to an assumption.

## Pending dispatcher sweep

Run on the handed-off revision:

```sh
UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui
```

Retain fresh counted XML and command/exit evidence, confirm the named method actually executed and passed, and report executed/passed/failed/error/skipped counts. This sweep has **not** been executed by the builder and is not claimed as passed. No live Claude is required.
