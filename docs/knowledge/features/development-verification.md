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

For Java helpers or test-APK services such as `MobileModalTestIme`, also run
`./gradlew compileDebugAndroidTestJavaWithJavac assembleDebugAndroidTest`.
Kotlin compilation alone does not check the Java service and packaged fixture.

Use `testDebugUnitTest --tests 'fully.qualified.TestClass'` for one JVM test class.
The aggregate `test` task does not accept the test filter in this project. In a
fresh worktree, Gradle may need `ANDROID_HOME` set because `local.properties` is
ignored. Preserve the command's exit status when inspecting output; piping Gradle
through `tail` can hide a failure.

The dispatcher owns routine device execution through
`python3 scripts/android-test-gate.py ui`, which uses the Gradle-managed Android
13 device and does not require Android Studio to be open or an emulator to be
booted manually. Report the command, XML evidence and executed count. A missing,
zero-count or failed run is not device evidence. Agents author the tests and
triage the supplied failure; a local `connectedAndroidTest` run is optional.

## Compose evidence

Compose tests should assert the contract independently of the implementation.
Give a row, state and callback a value that the production code cannot derive from
the expected assertion. For a visible transition, assert the state before the
action, wait for a positive effect of that action, then assert the resulting
absence or replacement. Use `useUnmergedTree = true` when a merged semantics
container hides per-row text or controls.

For hardware-keyboard button tests, request `InputMode.Keyboard` through
`LocalInputModeManager` after composition and before requesting focus. Establish
it separately in the launcher and dialog windows. Assert launcher focus before
Enter opens the modal, then assert Tab containment, action activation and focus
restoration after dismissal. A failure before opening the dialog does not test
its focus-restoration contract. See [the shared mobile modal](mobile-modal.md#focus-and-verification).

ATD images omit LatinIME; editable focus or `performTextInput` alone does not
establish that a software keyboard is visible. See Android's
[removed ATD components](https://developer.android.com/studio/test/managed-devices).
`MobileModalTest` supplies a real `MobileModalTestIme` in the test APK, using Java
and Android framework classes because its standalone service process cannot rely
on Kotlin/Compose libraries supplied only by the target APK during
instrumentation. The service declaration requires `BIND_INPUT_METHOD` and remains
under `app/src/androidTest`.

Selecting an IME after the Compose rule launches its activity can recreate that
activity and dispose content installed with `setContent`. `MobileModalTest` uses
an outer rule (`order = 0`, selected by `@WithTestIme`) to select the IME and drain
main-thread configuration delivery before the Compose rule (`order = 1`) launches
the host. Its `finally` restores the previous IME selection and the test IME's
enabled state after host teardown. A guarded view read or longer timeout cannot
revive a disposed dialog. Once the host is stable, wait on the UI thread for the
captured dialog view to initialize and gain window focus, focus the field and
show the keyboard. Assert actual IME visibility and a nonzero inset as well as
displayed content and footer bounds above the keyboard; a scroll test
with no keyboard leaves that contract untested.

Reply assertions must not depend on total substring-count growth: removing queued
prompt text can offset a newly displayed assistant reply. For fresh discussions
sending only `PING_PROMPT`, `awaitDisplayedPingReply` matches exact,
case-insensitive `ping` under a scrollable ancestor in the unmerged tree and waits
for display. Exact text excludes the full prompt; list scope excludes the title
and backlog. This is a constrained-prompt matcher, not an assistant-role detector.
`PingReplyTest` checks both no-reply states (with and without queued text), then
replaces the queue with a displayed reply while the substring count stays equal.

Off-screen lazy-list content can still exist in semantics. Establish a viewport
before asserting non-display; merely appending a row does not establish it.
`SessionBoundaryVisibilityTest` uses separate Markdown paragraphs to prove a
finalized wrap-up exceeds the viewport, appends the delimiter, scrolls to the
wrap-up at reversed index 1, and asserts the explanation is not displayed.
`awaitDisplayedSessionBoundary` then scrolls to the newest row (index 0) while
waiting and asserts display. Keep the pre-action absence guard separate from this
post-append non-display check. Both regressions run outside the excluded `e2e`
package; see the [LIVE coverage](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay).
Daemon history can locate a failed step, but cannot prove phone rendering. The
[default/explicit-workspace recheck](../../e2e-interactive-stream.md#verification-status)
demonstrates this distinction: successful daemon replies narrowed the failure
boundary, while passing displayed-reply assertions established phone rendering
after the count-based oracle was repaired.

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

The routine UI gate excludes `de.pyryco.mobile.e2e` through the instrumentation
`notPackage` argument. A test in that package can compile without running in this
gate. Keep ordinary application-wiring assertions in their owning package:
`de.pyryco.mobile.di.RepositoryBindingInstrumentedTest` checks the installed test
application's fake binding when no relay arguments are supplied. Confirm its
testcase appears in the gate XML; compilation alone does not prove isolation.

Use `runCurrent()` after pushing a fake relay item when the test path is a
channel-to-StateFlow cascade with no timer. `advanceUntilIdle()` does not
necessarily drain that background collector. Reserve it for tests whose contract
really advances virtual time. When adding a finite watchdog or timeout, drive only
the intended deadline with `advanceTimeBy(...)` followed by `runCurrent()`;
`advanceUntilIdle()` also advances newly armed watchdogs.

A JVM unit test that constructs or resolves a component backed by `Dispatchers.Default` — a Koin
singleton reached without an injected test dispatcher, for example — must stop it before that test
class's `Dispatchers.resetMain()`, not merely dispose it. The failure this produces attaches to
whichever test happens to be running next, not to the test that actually leaked the collector,
because the background thread throws asynchronously: watch for "Module with the Main dispatcher had
failed to initialize" raised from a `CoroutineScheduler$Worker` frame. Closing the owning container
in a `finally` is necessary but not sufficient — the close has to be ordered ahead of `resetMain()`
and the source has to be asserted stopped, not merely assumed disposed. `KoinHostSources`
(`app/src/test/java/de/pyryco/mobile/di/KoinHostSources.kt`) is the shared helper for
`HostConversationSource`: it registers every Koin container a test resolves the source from, closes
them, and proves each source stopped by reading the `@Synchronized` `repositoryFor` — the
load-bearing check, since it shares the monitor the worker threads take and returns `null` only once
`dispose()` has actually run, while a raw `snapshots.value.isEmpty()` read has no such
happens-before edge and can pass vacuously. `HostChannelListViewModelTest`,
`HostDiscussionListViewModelTest` and `RelayConnectionFactoryTest` call it from each resolving
test's own `finally`, and each class's `@After` calls `assertAllClosed()` before
`Dispatchers.resetMain()` runs (#726). A `JUnit4` `TestRule` cannot enforce that ordering: a rule's
`after`-block runs after every `@After` method, which is the wrong side of the reset.

Gradle gates do not exercise shell scripts. For a change under `scripts/`, extract
the changed function into a scratch file, add strict shell options and test it with
stubbed helpers. Exercise preflight guards with nonexistent `PYRY_BIN` and relay
paths so the script cannot start a real process. Keep the scratch file outside the
worktree.

Shell teardown must preserve the original test result. A final optional check
such as `[ -n "$ISO_HOME" ] && ...` can return failure when no isolated home exists,
turning a passing suite into a failed command. `e2e-emulator.sh` captures the
incoming status, uses explicit optional branches, and returns that status.
`test_e2e_emulator_cleanup.py` exercises success and failure with and without an
isolated home, including retention of failure artifacts. Check both the process
status and executed XML results; neither overrides a disagreement with the other.

The Android gate must search only the report path selected by `DEVICE`:
`connected/debug` for `connected`, otherwise `managedDevice/debug/<DEVICE>`, under
`app/build/outputs/androidTest-results`. Freshness alone is insufficient: a fresh
report from another profile or execution path can falsely satisfy a broad search,
while searching only managed reports misses a successful connected run.
`test_android_test_gate.py` exercises both paths with fresh wrong-path reports,
missing or stale selected reports, failed XML and a failing process despite passing
XML. Preserve path selection and process-status checks together.

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
binding, lifecycle timing, relay compatibility or a real daemon round trip. The
dispatcher runs the UI gate and each zero-real-Claude scripted scenario before
verifier. For a ticket labelled `needs-real-claude`, it runs
`python3 scripts/android-test-gate.py live` after verifier and before documentation
or merge. Record the scenario, app/build version, daemon compatibility and executed
test count. XML evidence is required; a zero exit code with every scenario skipped
is not a passing proof.

The current required profile is managed `pixel2Api33Atd`, Pixel 2 / API 33 / AOSP
ATD arm64; API 35 is deferred (2026-09-20 decision). See the
[revision-linked live baseline](../../e2e-interactive-stream.md#verification-status).
A configured profile name alone does not establish the runtime image. Capture ADB
properties during execution and record the SDK image revision, Claude version,
resolved runner and app/daemon revisions alongside sanitized XML and its checksum.
A missing or unbootable required device is an environment blocker, not a product
regression or a passing test.

Choose an e2e rung from the producer that emits the event. The current mobile
harness uses the daemon's stream-json runner and `fakeclaude` raw replay. Set
`PYRY_FAKE_CLAUDE_STREAM_JSON=1`, provide `PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST`,
and pair `PYRY_FAKE_CLAUDE_STREAM_REPLAY_SECOND` with
`PYRY_FAKE_CLAUDE_STREAM_REPLAY_RELEASE` when a held turn needs a second fragment.
Release it from the queued second message or relay disconnect that the scenario
defines. PTY transcript polling is historical and the current harness rejects a
PTY runner. Keep missing fixtures and skipped captures visible. Before accepting a
capture, check its redacted context, expected event count and reader version. Do
not copy credentials, pairing codes, user prompts, host paths or raw daemon
payloads into evidence.

For camera overlays, the dispatcher must verify the real preview layer on the
managed emulator or device when the change concerns it. A unit test or a fake
preview slot cannot prove CameraX binding or that the preview respects the Compose
overlay. For relay and Noise changes, combine deterministic JVM coverage with the
appropriate UI or post-verifier live path; do not claim the latter ran unless its
output identifies the executed scenario and XML evidence.

## Documentation evidence

Record the failure that would otherwise recur, its cause and the check that catches
it. Put product behavior in the owning feature topic. Put requirements, review
findings and unfinished work on the ticket or PR. Put workflow lessons in the
agent or dispatcher repository. The frozen `codebase/` archive is read-only, and
local Claude memory is not a substitute for a reviewed repository document.

## Archive refresh regression

The live archive test on 2026-09-20 exposed a list decoder that discarded
`is_archived` and reset every row to active on a refresh. List summaries now
preserve that field, with an active default for older server replies. The archive
E2E also scrolls its Settings row into view before tapping. Preserve both checks
when changing the list mapping or Settings layout.
