# Development verification — Gradle and source checks

Part of [Development verification gates](development-verification-gates.md).
Split for #2505 to keep the owning overview below its size cap.

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
`app/src/androidTest`. `app/src/sharedTest` compiles into both sets, so a change
there is covered by `test` and by the androidTest compile. When an instrumented
test or its helpers change, also run:

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

Script fixtures for installed-APK rejection must own the bytes they compare (#2196).
`SuiteDriverTest.test_wrong_installed_apk_is_rejected_before_native_execution`
uses a temporary `ROOT` with controlled app APK bytes, a validated recording and a
deliberately different installed digest supplied by mocked adb. Assert the exact
digest-mismatch error, the package-path and SHA-256 queries, and that instrumentation
was never invoked. Depending on checkout assembly outputs instead can fail with
`FileNotFoundError` before exercising rejection, or pass only after an unrelated
build. This control needs no real APK assembly or device; runtime installed-byte
validation remains strict.

The verifier keeps static checks, JVM execution, APK assembly, UI execution and
scripted execution as independently retained gates. The static invocation is
`./gradlew check -x :app:testDebugUnitTest`. Its excluded task runs immediately
afterwards through `python3 scripts/jvm-test-gate.py`, which emits joined JUnit XML on standard output. A successful full invocation may
retain Gradle-verified cached results. Exact named comparisons force fresh execution. Build logs go to
standard error. The complete recorded-suite inventory remains a dependency of
the static `check` invocation. APK assembly and instrumented Kotlin compilation
share one Gradle invocation.

Named JVM failures use `jvm-test-gate.py --tests-json '["package.Class#method"]'`.
JSON preserves Kotlin names with spaces, commas and apostrophes. UI failures use
`android-test-gate.py ui --tests package.Class#method`. Scripted failures use
`android-test-gate.py scripted-all --tests package.Class#method`. The last command
resolves the original scenario from the harness and runs only that scenario,
including its isolated daemon and relay. Normal `scripted-all` still runs every
scenario. Unknown methods, stale or missing reports, zero execution and skipped
selected methods cannot establish a passing comparison. A failed build remains
failed even when an earlier test report is present. The shared dispatcher runs
these named comparisons in the same worktree and on the main commit merged
into it. A confirmed inherited failure still requires its tracking ticket.

Direct Gradle checks acquire the same host hold through
`python3 scripts/gradle-gate.py <tasks>`.
Android and JVM gate entry points acquire it automatically, before the Android
device hold. Nested calls reuse the validated inherited descriptor. The pinned
Android cleanup owner retains both descriptors, including after parent loss.
Use `PYRY_TEST_ADMISSION_LOCK` for a common host-bound path when project containers
have separate home directories. Forward that variable into builder shells.
The managed launcher runs the pinned Gradle build inside its admitted JDK 21
client process. Matching client/build JVM options avoid the detached single-use
worker that --no-daemon alone can create. Kotlin compilation stays in that JVM.
Build-cache reuse remains enabled.

### Managed build heap and Linux acceptance (#2270)

`gradle.properties` sets `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`.
The managed foreground JDK 21 build takes this 4 GiB heap setting, with Kotlin
compilation in the same process. Host admission, test-worker limits and required
checks retain their existing contracts. The retained builder probe confirmed
4,096 MiB and UTF-8; increasing a test-worker heap would not address pressure in
this shared lint/compiler JVM.

The [accepted verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2303#issuecomment-6262970789)
records Linux acceptance on 2026-10-19 at
`2dca08a77de10a46ac078b1000ad87d2df0978ee`, run
`b313906b4707c9d234e3e43d0083b89db541cbad3e2f205879769cf132c841f1`:

| Check | Evidence |
| --- | --- |
| Combined `jvm-test-gate.py --with-build-checks` | Verified cached XML: 6,063 passed, 0 failed/errors, 0 skipped; **0 JVM tests newly executed**, `testDebugUnitTest FROM-CACHE`. Lint, formatting, debug assembly and instrumented Kotlin compilation completed successfully. |
| Fresh focused builder JVM checks at accepted source | 124 executed/passed, 0 failed/errors, 0 skipped. Markdown text 32, taps 21, streaming rendering 13, parsing 28, routing 6, streaming parser 15, Obsidian admission 5 and launch 4. |
| Android UI | 335 executed/passed, 0 failed/errors, 1 skipped: unrelated `RenameDialogCaptureTest.renameAtFigmaViewport`. |
| Scripted-all | 22 executed/passed, 0 failed/errors, 0 skipped; zero real Claude turns. |
| Script unit tests | 183 executed, OK. Pre-verification and docs guard also passed. |

The retained JVM XML includes passing bare-URL, code-span completion and finished/
streaming activation regressions; [renderer testing](markdown-text.md#testing)
names them. Cached full-suite acceptance must stay distinct from fresh focused
execution. No real-Claude/provider or external-app live run was required or
performed. macOS was outside this Linux ticket's verification scope.

Earlier run `50093862de9d52ee3cff8451ad7cda48ea03b76921fbd19752517deb38d236eb`
at `84f71f02c28a085a7d0972d66d016030eacfc7ae` remains **failed**:
`lintAnalyzeDebug` exhausted the 2 GiB Java heap while 6,063 JVM tests passed,
zero failed/skipped. The narrower original-heap control at
`6bb7e66dc558b566ae5c5b9115990fef092db30f` confirmed 2,048 MiB/UTF-8 and
passed fresh lint plus both test Kotlin compilations (38 tasks executed); it did
not reproduce the coordinated failure. It does not erase that failure or replace
complete-gate acceptance at 4 GiB.

At documentation time, joined dispatcher XML and build output were retained at
`$AGENTS_REPO_PATH/logs/verifier-gate_#2270_{4,5,6}.{log,stderr.log}`.
The fresh focused XML, heap probe, lint reports, control and earlier failed bundle
were retained under
`$AGENTS_REPO_PATH/logs/evidence/2270/heap-repair-2dca08a77/`.
The accepted verdict and uploaded bundle remain addressable by the run ID above;
these raw-file paths describe where the evidence was retained at the time.

Every JVM unit test has a 180-second limit, Robolectric and `sharedTest` classes included.
Unit tests run on the JUnit Platform's Vintage engine; `TestTimeoutWatchdog`, registered
under `app/src/test/resources/META-INF/services`, tracks each execution occurrence and
its runner thread. Terminal finish/skip callbacks cancel deadlines and release grace
waiters. Expiry and terminal cleanup share synchronized ownership: even a dequeued
old timer cannot interrupt a completed test or a later occurrence reusing its unique
ID. Duplicate starts cannot replace an armed execution; timeout counts belong to
occurrences rather than reusable IDs (#2280).

A running test past the limit prints `TEST TIMEOUT` with its name and a full thread
dump to the Gradle log, then interrupts its runner. A recorded FAILED result can
complete within the unchanged 30-second grace; SUCCESSFUL, ABORTED or skipped after
interruption still fails the gate. Remaining active after grace or a second timeout
in the JVM halts it. Container/plan teardown with unfinished children cancels their
deadlines and fails closed immediately as an execution-protocol error, preserving
execution identities and the original exception type. Vintage can finish a failed
runner container without finishing its child: this must not later look like a
rendering hang while idle SDK executors and a shutting-down worker await a stale
deadline. The original earlier full-suite initiating exception remains unclassified.

The deliberately unfinished `EscapingFixture` carries the test-only
`ControlledRunnerFixture` category, whose exact Vintage tag is unconditionally
excluded in `unitTests.all`, including retention opt-in. Selecting only its parent
class cannot prove normal discovery is safe: nested custom-runner fixtures can be
discovered independently. `ordinaryDiscoveryExcludesControlledRunnerFixture` uses
the actual Gradle `JUnitPlatformOptions.excludeTags` exposed to the test JVM and
asserts zero fixture discoveries/starts, armed deadlines or termination. The
unfiltered `vintageRunnerExceptionRetiresItsUnfinishedChild` launcher retains its
injected termination callback and proves immediate fail-closed cleanup.

Linux focused evidence at `27d54809ecd32a05c4b1ab866dfc466f4c2e9c46` on host
`7fbbed191d61`: `TestTimeoutWatchdogTest` executed/passed 16 methods with zero
failures/errors/skips, both normally and with `-PfullRetentionTests=true`. Separate
red controls for the missing fixture category and missing Gradle exclusion each
executed 16 methods, with 15 passed, one failed and zero errors/skips. The refused
prefix selector executed zero tests and is not a pass. Raw XML, configuration
snapshots and lifecycle traces were retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2280/builder-discovery-20261019-27d54809e/discovery-repair/`
under `green/`, `retention-opt-in/`, `red/` and `config-red/`.

Those focused passes initially left complete gates pending. The
[accepted same-head verifier review](https://github.com/pyrycode/pyrycode-mobile/pull/2295)
then confirmed all 16 watchdog regressions in the complete Linux JVM/build gate:
6,056 executed/passed, zero failed/skipped, with no standalone fixture report.
UI completed with 335 executed/passed, zero failed/errors and one unrelated skipped
rename capture; scripted-all completed with 22 executed/passed, zero failed/errors/
skipped. Historical aborted invocations remain failed/incomplete evidence.
See [native identity and retained evidence](markdown-reader-screen.md#testing).

`-PtestTimeoutSeconds=N` changes the limit and `0` removes it. The Gradle task
itself stops after 30 minutes, below the dispatcher's 60-minute limit. Neither
\#2280's lifecycle repair nor fixture isolation raises the 180-second limit or
30-second grace, or disables application-test watchdog coverage.

For filesystem tests that assert permission preservation, set the fixture's
starting mode explicitly before exercising the code. `mkdir(mode=0o755)` is
filtered by the inherited umask: under `077` it creates a `0700` directory, so a
later `0755` assertion can fail even when the code leaves it untouched. Use
`chmod(0o755)` on the fixture before installing a symlink. Keep the refusal,
empty-target and unchanged-mode assertions for both a symlink destination and
a child beneath it, as in `FaultEvidenceTest.test_destination_symlinks_are_rejected_without_writing_or_chmod`
([#2158](https://github.com/pyrycode/pyrycode-mobile/pull/2159#issuecomment-6166360997)).
Check permission fixtures under both `077` and `022`; retain nonzero executed
counts for focused checks and complete scripts discovery.

Scenario report enumeration does not establish launch order (#2383).
`AgentScenarioReportsTest.test_two_independent_outcomes_keep_the_first_failure`
exercises sorted and reverse-sorted `Path.rglob` results. Assert the exact multiset
of method names before building a map, which alone hides duplicates. Check each
outcome by identity and launch order separately; the retained failure must survive,
and restart must have no failure, error or skip. Sorting cannot prove completeness.
For stream and background-Agent reports (#2455, #2481), retain each execution's XML before filename reuse and
restore collected evidence even on interruption. [Selected-device ownership and
counted regression evidence](development-verification-report-ownership.md)
explain why pre-launch cleanup replaces mtime filtering.

`scripts/docs-guard.sh` is the only gate that ever sees the documentation stage's
own output before it lands on `main`. That stage writes `docs/knowledge/features/`
*after* the verifier gate and after merge, so no Gradle task — including
`spotlessCheck`, which `check` runs first and which fails fast on the whole gate
chain when it is red — ever runs against those files before they ship. The guard
closes that gap by re-checking, in shell, the two conditions `format("misc")`
in the root `build.gradle.kts` applies to every `*.md`: no trailing whitespace, and
exactly one newline at end of file (#754). A file left red here fails
`./gradlew spotlessCheck` on every branch cut from `main` afterward, and because
`check` aborts before the unit suite, lint, `assembleDebug`, the androidTest
compile and both device gates, the next several verifier passes see no device
evidence at all rather than a narrow one-file failure — run the guard and repair
everything it reports before committing, not just the files touched this run.

### Snackbar routing guard

Failures use the top-overlay [Error pill](notice-pill.md), not a snackbar.
[`SnackbarRoutingGuardTest`](../../../app/src/test/java/de/pyryco/mobile/verification/SnackbarRoutingGuardTest.kt)
runs in normal JVM `test` / `check` verification (#1750). To run it alone:

```bash
./gradlew testDebugUnitTest --tests 'de.pyryco.mobile.verification.SnackbarRoutingGuardTest'
```

Gradle supplies `snackbarProductionSourceRoots` from all configured Android
production source sets, including build-type and flavor roots. Only source-set
names starting with `test` or `androidTest` are excluded: a production build type
such as `latest` must still be scanned. Kotlin (`.kt` / `.kts`) and Java sources
are explicit unit-task inputs, so source-only routing changes invalidate cached
results. Project-relative roots, the app working directory and relative input
path sensitivity preserve cache reuse across worktrees. Missing configuration
or an empty scan fails.

The test-only Kotlin compiler PSI parser rejects unclassified `showSnackbar` and
`Snackbar` references, including callable references, aliased imports, classic
Android `Snackbar.make` and direct Compose `Snackbar` content. Java references
have no permitted classifications. Comments and plain string literals are not
routes; expressions inside Kotlin string templates are inspected. This is a
syntax contract, not whole-program dataflow analysis or a substitute for reviewing
message producers.

The remaining production snackbar route is `ArchivedDiscussionsScreen`'s
`RestoreSucceeded` branch: the success resource formatted with the restored display
name. #1851 migrated thread/reader Saved and thread `ModalUiState.Dismissed` to
screen-local Default top-overlay pills. `ThreadScreen`, `MarkdownReaderScreen` and
`ChannelListScreen` have no active snackbar route. The guard still contains the
three exact historical thread/reader classifications; their presence does not
mean those routes remain in production.

For a new notice, first review whether its producer represents a failure. Route
failures to Error pills. A new non-error snackbar needs an explicit classification
in [`SnackbarRoutingGuard`](../../../app/src/test/java/de/pyryco/mobile/verification/SnackbarRoutingGuard.kt)
and positive and negative controls in its test class. The classification pins
both the owning screen/function and the complete callback or branch, including
message derivation, Saved-only condition, effect key and enclosing `when` subject
where applicable. Syntax tokens must match; only whitespace and comments are
ignored. Review classification changes when refactoring an approved region;
neutral-looking copy or a fixed count of snackbar calls cannot establish safety.

Retained [commands and results](../../../app/src/test/resources/verification/snackbar-routing/commands-and-results.txt)
and fresh post-blocker XML record 11 executed/passed tests, zero failures/errors/
skips, and 302 scanned production files. The matching source-set negative control
executed one test with one expected assertion failure, zero errors/skips, and
303 scanned files: the injected `latest/LatestScreen.kt` route was rejected.
`post-blocker-green.xml` includes both the production assertion and the
neutral-copy added-error control. The test-only init script and fixture remain
under test resources. The earlier MainActivity share-error failure is retained
as historical evidence; #1824 fixed that production route without weakening the
guard. See [the verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/1875#issuecomment-6030551584).
