# Development verification

Shared verification guidance for Kotlin, Jetpack Compose and the Android
emulator. Read the current source and the owning feature topic before applying a
historical lesson.

## Establish the change surface

Search production callers, test doubles and Compose call sites before changing a
type or callback. A declaration search misses callers hidden behind a ViewModel,
repository facade or defaulted composable parameter. Search both the symbol and
the user-visible text when removing a field or action, because KDoc, fixtures and
semantics labels can keep the old contract alive after the compiler is satisfied.

Trace the declared interface type to its assigned implementation. Matching method
names do not prove that two repositories or transports have the same behavior. For
a new sealed event or wire arm, inspect every type switch, mapper, logging site,
state projection and test table. A default branch can silently ignore a new case.

## Gradle and source checks

Use the smallest gate that proves the change, then run the repository guard before
handing off documentation:

```bash
./scripts/docs-guard.sh
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

The aggregate `test`, lint and assemble tasks do not compile
`app/src/androidTest`. When an instrumented test or its helpers change, also run:

```bash
./gradlew compileDebugAndroidTestKotlin
```

Use `testDebugUnitTest --tests 'fully.qualified.TestClass'` for one JVM test class.
The aggregate `test` task does not accept the test filter in this project. In a
fresh worktree, Gradle may need `ANDROID_HOME` set because `local.properties` is
ignored. Preserve the command's exit status when inspecting output; piping Gradle
through `tail` can hide a failure.

Run `./gradlew connectedAndroidTest` only with an available Android device or
emulator. Report whether a device ran the tests, rather than describing a compile
or unit result as device evidence.

## Compose evidence

Compose tests should assert the contract independently of the implementation.
Give a row, state and callback a value that the production code cannot derive from
the expected assertion. For a visible transition, assert the state before the
action, wait for a positive effect of that action, then assert the resulting
absence or replacement. Use `useUnmergedTree = true` when a merged semantics
container hides per-row text or controls.

Compose parameter-order lint is a real gate. Put required parameters before
defaulted ones, and keep `modifier` before trailing lambdas according to the
project convention. A screen can compile while `lint` still fails. Keep
Android-only assumptions out of portable domain models and repository contracts.
Platform adapters such as `data/crypto` and `data/preferences` are intentionally
Android-specific, and should stay behind interfaces.

When a suspend send path catches `IllegalStateException`, rethrow
`CancellationException` first. On the JVM cancellation is an
`IllegalStateException` subtype, so a broad catch can turn teardown into a fake
send failure. Tests should cover both cancellation and the intended failure types.

For `@Composable` signature changes, treat a code graph result as advisory. The
current graph can collapse callers into file self-references and miss a real
consumer. Search all `*.kt` files under `app/src` for the composable name and for
the rendered text being removed. This covers production, JVM tests and
`androidTest` call sites in one pass. Record the graph gap when it affects the
blast-radius decision.

## Test scheduling and harnesses

Use `runCurrent()` after pushing a fake relay item when the test path is a
channel-to-StateFlow cascade with no timer. `advanceUntilIdle()` does not
necessarily drain that background collector. Reserve it for tests whose contract
really advances virtual time. When adding a finite watchdog or timeout, drive only
the intended deadline with `advanceTimeBy(...)` followed by `runCurrent()`;
`advanceUntilIdle()` also advances newly armed watchdogs.

Gradle gates do not exercise shell scripts. For a change under `scripts/`, extract
the changed function into a scratch file, add strict shell options and test it with
stubbed helpers. Exercise preflight guards with nonexistent `PYRY_BIN` and relay
paths so the script cannot start a real process. Keep the scratch file outside the
worktree.

## Probe the evidence itself

An injection or sanitizer test must forge the exact line shape its reader matches.
For the unrecognized-row sentinel, the hostile value must include the report's row
prefix, not only a newline. Assert the total report line count as an independent
fence. Temporarily widen the sanitizer, run the test and read the resulting failure
before restoring the guard. This proves the assertion can fail instead of merely
proving that the current implementation passes.

## JVM logging and formatting

Plain JVM tests have no Robolectric runtime and this module does not enable default
Android return values. A reachable `android.util.Log.*` call throws "not mocked".
Route log emission through an injectable sink, as `data/network/RelayLog.kt` does,
so tests can capture the call and restore process-global flags after each test.

Spotless also runs ktlint's filename rule. If a Kotlin file contains one non-private
top-level class-like type, the filename must match that type, including for
`internal` types. Split a result or DTO into its own correctly named file when the
rule requires it; `spotlessApply` cannot repair the filename.

The Spotless message saying it could not autocorrect a theoretically fixable
violation is only a warning. Read the final `BUILD SUCCESSFUL` or `BUILD FAILED`
and the explicit violation list. Use `./gradlew spotlessCheck --rerun-tasks` when
cached output makes the result unclear.

## Emulator and real evidence

An instrumented test proves behavior in its fixture. It does not prove camera
binding, lifecycle timing, relay compatibility or a real daemon round trip. Run the
emulator ladder described in [interactive stream e2e](../../e2e-interactive-stream.md)
when the change reaches that surface, and record the scenario, app/build version,
daemon compatibility and executed test count. A zero exit code with every live
scenario skipped is not a passing live proof.

Choose an e2e rung from the producer that emits the event. A stream-json-only
event cannot be proven by a PTY runner, and a PTY-only event cannot be proven by a
scripted stream fixture. Keep missing fixtures and skipped captures visible. Before
accepting a capture, check its redacted context, expected event count and reader
version. Do not copy credentials, pairing codes, user prompts, host paths or raw
daemon payloads into evidence.

For camera overlays, verify the real preview layer on an emulator or device when
the change concerns it. A unit test or a fake preview slot cannot prove CameraX
binding or that the preview respects the Compose overlay. For relay and Noise
changes, combine deterministic JVM coverage with the appropriate emulator or
real-daemon path; do not claim the latter ran unless its output identifies the
executed scenario.

## Documentation evidence

Record the failure that would otherwise recur, its cause and the check that catches
it. Put product behavior in the owning feature topic. Put requirements, review
findings and unfinished work on the ticket or PR. Put workflow lessons in the
agent or dispatcher repository. The frozen `codebase/` archive is read-only, and
local Claude memory is not a substitute for a reviewed repository document.
