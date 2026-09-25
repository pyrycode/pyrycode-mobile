# #1131 — Record which window holds focus when a device test fails

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eInstrumentationRunner.kt` → `E2eInstrumentationRunner` — the runner every device run uses; the new listener sits beside it in the e2e package, which the `ui` gate already treats as serving every device test.
- `app/build.gradle.kts` → `defaultConfig { testInstrumentationRunner }` — where the `listener` instrumentation argument is registered, so no test class changes.
- `scripts/android-test-gate.py` → `run_on_device`, `run_scripted_all`, `fresh_logcats`, `E2E_ONLY_SOURCES`, `device_only_classes`, `device_hold` — both device paths already copy each fresh per-test logcat; the record is read from those same files. The new listener has no `@Test`, so `device_only_classes` ignores it, and it is not in `E2E_ONLY_SOURCES`, so a change to it still runs the `ui` suite.
- `scripts/test_android_test_gate.py` → `test_live_run_keeps_fresh_per_test_logcat_even_when_the_report_is_broken` — the fixture shape (fake Gradle run writing fresh logcat files) the new gate tests mirror.
- `docs/knowledge/features/development-verification.md` § "Device gate" — current gate behaviour; the documentation stage adds the record's description there.
- A real managed-device per-test logcat (`logcat-<class>-<method>.txt`): lines are `MM-DD HH:MM:SS.mmm  PID  TID L Tag: message`, bounded by the runner's `TestRunner: started:` / `finished:` lines, so anything logged from `testFailure` (which JUnit fires before any listener's `testFinished`) lands in the failing test's file.

## Design source

N/A — test infrastructure only; no UI.

## Context

The `ui` gate keeps failing the same 11 focus-dependent tests on unrelated trees (`RootViewWithoutFocusException`, `waitUntil` timeouts). The per-test logcats do not name the window holding focus at API 33 default log levels, and they die with the worktree. This ticket delivers evidence only: a record of the window manager's focus state at the moment of failure, printed to the gate's stderr so the dispatcher log keeps it. No mitigation (dialog dismissal, retry) is added.

## Design

### Device side — `FocusRecordListener` (new, `app/src/androidTest/.../e2e/FocusRecordListener.kt`)

`class FocusRecordListener : org.junit.runner.notification.RunListener()`, registered through the `AndroidJUnitRunner` `listener` argument: `testInstrumentationRunnerArguments["listener"] = "de.pyryco.mobile.e2e.FocusRecordListener"` in `defaultConfig`. Gradle `-Pandroid.testInstrumentationRunnerArguments.*` properties (the gate's `class`/`notPackage`, the e2e harness's relay args) add keys beside it; none of them uses `listener`.

- `override fun testFailure(failure: Failure)` — only failures, not assumption failures. Runs `dumpsys window` through `InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand`, reads the output fully, and logs one line at `Log.w` with tag `FocusRecord`:
  `test=<Class#method> focus=<mCurrentFocus value> focusedApp=<mFocusedApp value> anr=<ANR window titles or none>`
- Parsing is a pure function on the dump text, `fun focusRecord(test: String, dump: String): String`:
  - `focus` — the distinct values after `mCurrentFocus=` on any line, joined with ` | `; `unknown` when none.
  - `focusedApp` — likewise for `mFocusedApp=`.
  - `anr` — the distinct window titles `Application Not Responding: <process>` (the title `AppNotRespondingDialog` gives its window) found in `Window{… <title>}` entries; `none` when absent.
  - Each value is cut to a fixed length so one line stays well under logcat's per-entry limit.
- Test name: `description.className + "#" + description.methodName`, falling back to `description.displayName` for a class-level failure (no method).
- **Never changes the result.** The whole body is wrapped in `try/catch (Throwable)`; a failure while recording logs `test=<name> error=<ExceptionClass>: <message>` under the same tag and returns. This matters because JUnit's `RunNotifier` turns an exception thrown by a listener into an extra test failure and drops the listener. The listener does not touch `failure`, so the original exception is reported unchanged.
- **Bounded.** `dumpsys` bounds each service dump itself (10 s default), so a wedged window manager costs at most that; no extra threading.
- Content-free: the record carries window titles, package/activity names and the test name — no app data.

### Host side — `scripts/android-test-gate.py`

- `focus_records(paths) -> list[str]`: scans the given logcat files for lines containing `FocusRecord: ` and returns the text after it, in file order, de-duplicated.
- `print_focus_records(paths)`: for each record prints to **stderr** `Android gate: focus record for <Class#method>: <fields>` (the test name parsed from the leading `test=` field). Prints nothing when there are no records — a passing run has none, because the listener writes only on failure.
- Called in `run_on_device` right after the fresh logcats are copied and before the report is judged (so a broken report still shows the records), and in `run_scripted_all` per scenario beside its logcat copy. Nothing goes to stdout: the dispatcher's XML stays as is.

## State + concurrency model

No coroutines or app state. The listener runs on the instrumentation's test thread inside JUnit's notifier; the shell read is synchronous and bounded by `dumpsys`'s own timeout.

## Error handling

- Device: any `Throwable` while recording → one `FocusRecord` `error=` line; the run continues and the test's own failure stands.
- Host: an unreadable logcat file (`OSError`) is skipped by `focus_records`; the existing `except (ValueError, OSError)` in `run_on_device` is not reached by it, so recording can never turn a pass into a gate failure.

## Testing strategy

- `scripts/test_android_test_gate.py` (no emulator), mirroring the fake-Gradle fixture of `test_live_run_keeps_fresh_per_test_logcat_even_when_the_report_is_broken`, driving `ui` mode through `main()`:
  - failing run: a failing testcase plus a fresh logcat holding a `FocusRecord: test=C#bad focus=… focusedApp=… anr=…` line → stderr contains `focus record for C#bad` with the fields; stdout (XML) does not contain `FocusRecord`.
  - passing run: logcats without records → stderr has no `focus record` line.
  - a stale logcat's record is not printed; an `error=` record is printed like any other.
- `focusRecord` parsing is small and exercised by the real record below; no JVM unit test — the class lives in `androidTest`, where a device test of a listener would need a failing test to exercise it.
- **Real record (AC 3):** a throwaway failing device test (not committed), run focused on the managed device under the host device hold; the logcat record and the gate's `print_focus_records` stderr output on that run go in the PR body.
- `./gradlew compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, `spotlessApply`.

## Documentation handoff

Pending for the documentation stage: in `docs/knowledge/features/development-verification.md`, under `## Device gate`, say where a failing device test's focus record appears in the gate output (stderr, `Android gate: focus record for <Class#method>: …`, also in the kept per-test logcat under tag `FocusRecord`) and what its fields mean (`focus` = window with input focus, `focusedApp` = focused activity record, `anr` = ANR dialog windows or `none`, `error` = recording itself failed).

## Open questions

- Whether `mCurrentFocus` / `mFocusedApp` appear once or per display in the API 33 dump — handled by collecting distinct values; confirm on the real record.
- Whether a Gradle `-P…testInstrumentationRunnerArguments.class=` run keeps the DSL `listener` argument — confirmed by the real record run, which passes `class=`.
