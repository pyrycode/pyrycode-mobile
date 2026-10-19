# Development verification — change surface, Gradle gates and where a test goes

Split out of [Development verification](development-verification.md) on 2026-10-02 to keep that
document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged. Part of
[Development verification](development-verification.md); see that document for the other topics
and its links.

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

## Live Claude reply assertions

Keep ordinary reply requests and assertions flexible about wording. Require exact
wording only when that wording is the feature under test. Use recorded conversation,
turn and agent identities to distinguish replies from user prompts and other replies.
Check completion, delivery and attribution. A reply may span several text rows or
paragraphs, and a background agent's later reply may have its own turn identity.
Precise commands that create controlled test conditions and fixed app labels remain
appropriate. Display tests should use controlled fixtures when they need fixed prose.

### Shared thread evidence: legacy and authoritative (#2169)

`ThreadEvidence.kt` (`app/src/sharedTest/java/de/pyryco/mobile/e2e/`) gives both the
legacy (`observeMessages`) and the daemon-authored (`AuthoritativeThreadSnapshot`)
presentations a common evidence shape, so migration helpers do not have to special-case
either source. It is test-only: nothing in it opens a query, advertises capability or
changes a production binding.

**The shared types.** `ThreadEvidenceKind` (`Legacy` / `Authoritative`) tags a
`ThreadEvidenceOwner` — host, conversation, a reference-identity `connection` token, and,
for authoritative evidence, epoch/generation. `ThreadEvidenceOwner.matches` requires every
field equal, including reference identity on `connection`, so a reused host/conversation
pair on a different connection, or the same connection carried over a stale epoch or
generation, is a different owner and cannot settle against retained evidence. A legacy
caller that only has a repository, not a connection-scoped host, reports `host = null`
rather than inventing attribution — never read a null host as "unknown but probably this
one." A `ThreadEvidenceRecord` pairs one original `Message` with the full daemon text,
session/turn, and the rendered `agentId`/`toolRunId` the presentation layer assigned it;
for authoritative records it also keeps the raw `ThreadItemDto` fact. `ThreadEvidence`
wraps an owner, its records and an `admitted` flag, and exposes `loadedMainReply`,
`settledReply` and `userEchoCount`. `ThreadEvidenceSource` is construction-only
(`.legacy(...)` / `.authoritative(...)`); it wraps an `observe(conversationId)` flow and
performs no side effects itself.

**Loaded selection vs. strict settlement.** `loadedMainReply()` answers "is there a
reply a human could select right now" — nonblank, non-streaming, no agent block, and a
main-thread parent. It does not require delta evidence, so an ordinary history message
that was never streamed still counts. Main-thread parent means different things per
source: legacy evidence reads the raw `Message.parentToolUseId`; authoritative evidence
requires the daemon parent to be absent or explicitly `0` *and* no projected Agent block
ID, because a positive parent with no resolved block is an orphaned child, not main-thread
evidence, and a parent explicitly cleared to `0` is not the same fact as a nonempty scoped
presentation identity that happens to render in the main column. `settledReply(...)`
answers a stricter question — "is this specific session/turn/agent family's reply
completely delivered" — and the two sources settle differently:

- **Legacy** filters records by raw parent (`parentToolUseId == agentId`) within the
  session/turn, then delegates to `holdsSettledAssistantReply`, which still requires the
  original contiguous-delta and full-length checks. Nothing here loosens that contract.
- **Authoritative** filters by `fact.kind == "assistant_message"` and the projected
  `agentId`, then requires every record in that family to be inactive, carry a
  `done`/`finished` status on *its own* item, and have nonblank full text. A `turn_end`
  fact or any fallback/unsupported-kind row never joins the family and can never block or
  fake a settlement. Critically, settlement reads the assistant item's own completion, not
  whether its parent agent (which may still be running) has terminated — a still-running
  parent does not prevent its own completed child item from settling.

Do not synthesize delta evidence for authoritative items to make them look like legacy
ones, and do not treat receipt, delivery acknowledgement, or a query checkpoint as
settlement — `userEchoCount` requires the user message's own `fact.status == "delivered"`
when a fact is present, not just that a row with matching text exists.

**Full text vs. bounded rendered text.** Authoritative records keep the full daemon
`fact` content (unwrapped from its `JsonObject`/`JsonPrimitive` wire shape) separately
from whatever the presentation layer bounds for display. A record can have complete full
text while its rendered text is blank or truncated; `loadedMainReply()` requires both the
record's `fullText` and the underlying message content to be nonblank, so a complete item
whose bounded presentation renders blank is correctly excluded from "something a user can
select," even though the daemon considers the item done.

**Identity provenance.** Authoritative identities are never derived from raw tool/task
IDs read off the wire fact — they come from `projectAuthoritativeThread` (for
`agentBlockId` / scope-qualified item IDs) and `foldToolRuns` (for `toolRunId`), the same
functions production rendering uses, so test evidence and the screen agree on what a
rendered row's identity is even when two hosts or agent families reuse the same raw tool
ID. Legacy identities come from `foldQueuedRows` → `foldBackgroundAgentBlocks` →
`foldToolRuns`, matching production's legacy presentation fold order; a legacy message
with no resolved rendered block still keeps its raw parent/segment attribution in the
record; that raw attribution is usable for legacy's own raw-parent settlement check but
never establishes main-thread *loaded-selection* attribution by itself.

**The harness binding is a single seam.** `harnessThreadEvidence(repository)` is the one
function every consumer calls; today it always returns `ThreadEvidenceSource.legacy(...)`.
Production activation will change only that function's body to follow the real
negotiation (once a daemon advertises `thread` support), and no consumer needs to migrate
again. The binding controls test observation only — it cannot itself enable an app route,
and it must never infer daemon support from cached or empty data. `ThreadEvidenceSource`'s
authoritative factory exists and is exercised by fixtures now, ahead of that activation,
precisely so the later seam change carries no risk of its own.

**Where this plugged in.** `awaitFinishedSelectionReply` and
`assertReplySuggestionLongPress` (`ReplySuggestionE2EAssertions.kt`) now observe through
`harnessThreadEvidence(repository).observe(conversationId)` instead of calling
`observeMessages` directly, but kept their existing signatures and callers unchanged.
`awaitFinishedSelectionReply` still means "a loaded main reply appeared"; the suggestion
helper's echo check still means "exactly one delivered copy of this exact text exists," and
its revisioned-clear check is untouched. `holdsSettledAssistantReply` itself was not
changed — legacy settlement still delegates to it unmodified.

**Lesson for later migrations.** When moving a stream-suite consumer off raw
`observeMessages` or a raw `AuthoritativeThreadSnapshot` read, reach for `ThreadEvidence`
rather than re-deriving ownership or settlement locally: the owner-equality check (`===`
on `connection`, not just equal host/conversation strings) is what makes
`firstOnLive`'s replacement/cancellation guarantees (see
[reconnect-slash-commands-follow-live-connection](../../specs/architecture/1029-reconnect-slash-commands-follow-live-connection.md))
compose correctly with either presentation, and re-deriving it by hand is an easy place to
accidentally accept stale evidence across a reconnect.

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

## Where a screen test goes

Compose screen tests live in `app/src/sharedTest/java`. Gradle adds that folder to
both the unit test set and the androidTest set, so every test there runs twice
over: under Robolectric in `./gradlew test` on every verifier pass, and on the
emulator in the in-depth run. Put a new screen test there by default and run it
with `./gradlew testDebugUnitTest --tests 'fully.qualified.TestClass'`. No
emulator is needed.

Put a test in `app/src/androidTest` only when it needs something Robolectric
cannot give it:

- a real input method, device shell commands or `UiAutomation`, like
  `MobileModalTest`;
- real pixels saved as screenshots, like `ScannerFrameTest`;
- the Android Keystore or real on-device storage, like the `data.crypto` tests;
- the host daemon or relay, which is the e2e package;
- work on a background dispatcher that Robolectric's paused main clock does not
  drive, like `ScriptedUnrecognizedMessageTest`.

A Compose test goes in plain `app/src/test`, not `sharedTest`, when it must never run
on a real device even though it drives a Composable: `sharedTest` also runs on the
emulator in the in-depth run, and a test that exercises a real system surface — like
Android's own permission dialog — would trigger that surface for real there instead
of hitting Robolectric's shadow. `NotificationPermissionPromptTest` (#685, driving
`rememberNotificationPermissionRequest` under `createAndroidComposeRule<ComponentActivity>()`)
is the first such case — see
[Push messaging service § Testing](push-messaging-service.md#testing-685).

A test in the wrong folder fails safe. A device-only test placed in `sharedTest`
fails in `./gradlew test`. A Robolectric-capable test placed in `androidTest` still
runs on every verifier pass, only slower.

Robolectric's settings are in `app/src/test/resources/robolectric.properties`: the
test Application with the fake repository, and the emulator's Pixel 2 height. The
width stays at Robolectric's default 320dp on purpose. Any wider and a dialog
holding a text field never settles, and the test fails after 60 seconds with
`AppNotIdleException` ([robolectric#8460](https://github.com/robolectric/robolectric/issues/8460)).
A test that needs the Pixel 2 width wraps its content in
`DeviceConfigurationOverride.ForcedSize`. This does not resize a dialog's separate
Robolectric window. For a read-only modal, use a window qualifier and pass
`requiredSize` through the modal's modifier, then assert the measured content width;
otherwise the supposed 412dp fixture can still run at 320dp.
[`BackgroundTaskPanelLayoutTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelLayoutTest.kt)
uses `@Config(qualifiers = "w412dp-h892dp")` with no editable fields (#1164).
It checks the content-relative inset at two heights to reject vertical centering,
then checks text reachability and both Close actions in pinned and compact modes.
A test that measures text exactly, such as
single-line truncation or overflow, adds `@GraphicsMode(GraphicsMode.Mode.NATIVE)`
so Robolectric uses real fonts. The device ignores Robolectric annotations.

`ForcedSize` applied under Robolectric's 320dp default window rescales the
density, so the root does not measure at the exact dp passed in —
`ForcedSize(412.dp, …)` measures as 411.61dp, not 412. A test asserting an exact
forced width fails on that rounding; compare within a pixel instead
([#1334](https://github.com/pyrycode/pyrycode-mobile/issues/1334)).

The same rounding happens on the emulator, which is a Pixel 2 at density 2.625:
28dp is 73.5px, lays out as 74px and reads back as 28.19dp. A shared test that
checks a Figma dp value uses `assertDpEquals` or `pixelDp()` from
`ui/PixelSnapping.kt`, which allow one device pixel there and nothing at
Robolectric's density 1. Rows stacked down a screen add their roundings up, so
check a row against its neighbour rather than against the top of the screen.
When the total position or height itself is a design target, retain it and
justify any larger `pixels` allowance from the measured, independently rounded
segments. Check the component gaps and line boxes separately within one pixel
so a permissive total cannot hide a spacing regression. The
[thread geometry examples](thread-screen-testing-coverage.md#testing) retain 97dp and
60dp totals with measured two-pixel allowances; integral-density checks remain
exact even with that allowance. The [reader coordinate checks](markdown-reader-screen-testing.md#fractional-density-coordinate-coverage-2292)
show that even adjacent-block totals can accumulate several text-line and internal-gap
roundings. Their local two-pixel bound has separate constituent checks and a boundary
control tied to the same allowance constant, rejecting three pixels and preserving
the default one-pixel bound.
The device also ignores `@Config` qualifiers and Robolectric's 320dp width,
draws text with its own font, and runs at that density: a test that needs a
412dp frame forces it with `ForcedSize`, scales pixel samples by the composition's
density, and measures a layout box rather than the text inside it.

Text width is never exact, so a test may allow slack on it and on anything a
text's width positions. Line height is different: it is fixed by the design,
not by the text inside it, so a test checks the height of a line and the
vertical rhythm it sets exactly, allowing only the one device pixel
`assertDpEquals` already grants for rounding.

Fetch semantics nodes on the test thread before entering `runOnIdle` for
main-thread text-layout access such as `GetTextLayoutResult`. A semantics query
inside the callback can nest main-thread synchronization; a Robolectric pass
does not establish device safety for that helper.

A shared test class needs `@RunWith(AndroidJUnit4::class)`. The device runner
does not require it, but without it the JVM runs the class outside Robolectric and
every test fails on a null `Build.FINGERPRINT`.

## Device gate

The dispatcher owns routine device execution through
`python3 scripts/android-test-gate.py ui`, which uses the Gradle-managed Android
13 device and does not require Android Studio to be open or an emulator to be
booted manually. By default it runs only the test classes under
`app/src/androidTest` outside the e2e package, on one emulator. The shared screen
tests already ran in `./gradlew check`. `UI_DEVICE_ALL=1` runs every screen test
on two emulators, the in-depth run to use every now and then or before a release. Report the command, XML evidence and executed count. A missing,
zero-count or failed run is not device evidence. Agents author the tests and
triage the supplied failure; a local `connectedAndroidTest` run is optional.

The default device selection also includes the captured Claude client scenarios in
`de.pyryco.mobile.recording`. These exercise the original native UI assertions using
application messages recorded from successful real Claude runs. They start no Claude
process or real daemon. `./gradlew check` requires complete recording inventory and
counted capture/replay evidence. Use the explicit full-suite live compatibility command
when checking a new daemon or refreshing a recording. It requires a fresh real capture,
replays the reviewed and fresh recordings and compares their request contracts.
Real FCM delivery, encrypted transport and timing obligations keep their live checks.
See [recorded client tests](../../recorded-tests.md#complete-native-suite) for commands,
recording review rules and the limits of replay evidence.

### Preference ownership across ordinary tests and replay

`E2eTestApplication` owns one process-lifetime `DataStore<Preferences>` for
`app_prefs`, bound through a fresh override module in both initial startup paths
and every graph rebuild. Ordinary navigation tests also load the shared
`appModule` value into isolated Koin graphs. Closing such a graph clears shared
singleton factories without cancelling an already-active DataStore's I/O scope.
Without the Application override, the next application lookup can create a second
owner of the same file. Carrying a store only during rebuild misses this initial
graph boundary. Keep the production after-graph-close initialization contract
covered by `AppPreferencesTeardownTest`; graph closure alone does not release
DataStore file ownership.

Replay restores arguments, pairings and preferences before rebuilding the ordinary
graph. Seeded fixtures have a second cleanup boundary: restoring their original
raw DataStore snapshot does not increment `ObservablePairedServerStore.revision`.
The ordinary registry created after replay can therefore retain a synthetic host
whose persisted pairing has been removed. In `finally`, restore the snapshot and
rebuild the ordinary graph on main with no Activity alive, including on assertion
failure. A regression must first await the synthetic host in the registry, then
check connection release and exact original preferences, pairings and registry
hosts; persisted-state assertions alone can pass while a connection leaks.
See [replay isolation](../../recorded-tests.md#replay-isolation).

### Linux ownership repair evidence (#2208)

The fresh baseline at `a0c7db693245cce59d67377f672d2bec4c7dca65` reproduced
113 executed, 110 passed and three failed tests. Background-agent and context-footer
failed during duplicate-store replay setup. Delete's empty failure was an
instrumentation process-crash interruption; no independent product assertion
failure was established. The controlled ownership regression failed once before
repair (one executed/failed, zero passed/skipped). Two later registry-cleanup
probes also failed before their fix (two executed/failed, zero passed/skipped).

Final tested Mobile source was `3f4703e08ede017f69e19ba5109762bf94f88035`,
with agents `49cd6dd07c163a7d0e9583850aafb28378ec0b3c`, on Linux x86_64.
The complete command was
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui`:

| Selection | Selected/reported | Executed | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Complete profile, shared tests and both managed-device shards | 1694 | 1692 | 1692 | 0 | 2 |
| Shard 0 | 883 | 883 | 883 | 0 | 0 |
| Shard 1 | 811 | 809 | 809 | 0 | 2 |
| Separate focused isolation class plus three original failures | 8 | 8 | 8 | 0 | 0 |

All 71 `RecordedMobileSuiteTest` scenarios and five
`RecordedSuiteIsolationDeviceTest` methods executed and passed, including
`interactiveTurn_backgroundAgent_followsBottomUntilFinished`,
`interactiveTurn_pingPrompt_footerShowsContextUsage` and
`interactiveTurn_deleteConversation_removesFromListAndClosesThread`.
Selected identities, instrumentation outcomes, both shard XMLs and joined XML
reconciled exactly without duplicates or missing cases. The two existing skips
were `RenameDialogCaptureTest#renameAtFigmaViewport` and
`ComposerPasteTest#pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty`
(Robolectric-only). No skips, acceptance retries or timeout increases were added.
All 1694 retained phone logs contained zero duplicate-DataStore errors; no
inherited application failure was found in this complete profile. The shard-1
progress counter showed 813/811 because it counted the skips twice; XML establishes
the completed inventory.

The [committed evidence index](../../../app/src/androidTest/assets/datastore-2208/evidence.txt)
retains commands, source revisions and counts. Raw XML, instrumentation reports,
phone logs, reconciliation and hash manifests were retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2208/rework-full-local-3f4703e08/`.
The separate managed `mobile-in-depth` task failed during preparation with zero
tests because its cleaned environment omitted the agents path; it is not passing
evidence. [Dispatcher #294](https://github.com/pyrycode/agent-dispatcher/issues/294)
tracks that launcher defect. The complete local profile above ran at the same
pushed candidate. macOS was outside this ticket's verification scope.

### Selection and execution

The `ui` gate skips itself, exiting 0 with `Android gate: ui skipped` on stderr and
no XML, when every path the branch changes since it left `main` is under `docs/` or
`scripts/`, is Markdown, or is one of the e2e-only sources listed in
`E2E_ONLY_SOURCES`: the live and scripted stream tests and the second-client peer.
The rest of the e2e package stays in: the instrumentation runner, the test
application and the unrecognised-row sentinel serve every device test. A skip is
expected on such a branch and is not missing evidence; the scripted scenarios and
the live gate still run. `UI_GATE_FULL=1` forces the suite. A unit test fails if
any other source starts using one of the listed classes.

Every device-using mode (`ui`, `scripted`, `scripted-all`, `live`) holds one
host-wide lock on the Gradle-managed AVD before it drives an emulator, so two
runs from different worktrees never share the device at once (#1071). The lock
is a kernel `flock` on a file beside `avd/gradle-managed` under
`ANDROID_USER_HOME` (or `~/.android`), so every worktree and checkout on the
host contends for the same file regardless of where it runs from. A waiting run
prints who holds the device on stderr and, once it acquires it, how long it
waited. `ANDROID_GATE_WAIT_SECONDS` (default 300) bounds that wait; a run that
gives up exits 75 without starting the device task or the e2e harness, naming the
holder's mode, worktree and start time — that exit code means a busy device,
not a test result. A `ui` run that skips itself (above) takes no hold. A direct
`./gradlew …AndroidTest` run bypasses the script and does not take the hold.

Every mode builds the app and test APKs before it queues for the hold, so the
hold covers only boot, install and run (2026-10-05). The e2e modes build with
`-PuseRelayRepository=true`, as `scripts/e2e-emulator.sh` does, and set
`E2E_APKS_BUILT=1` so that script skips its own build. A failed build exits 1
without taking the device. `scripted-all` installs both APKs once on the emulator
it boots. Before each scenario it clears the app's data and grants back the
runtime permissions the install granted, then the harness runs the
instrumentation directly instead of Gradle's device task;
`scripts/instrument-report.py` turns its output into the JUnit report, and a crash,
an unfinished test or an empty run fails. If the install or a clear fails, the
remaining scenarios install through Gradle as before. The `ui` and scripted runs
pass `disableAnimations=true`: `E2eInstrumentationRunner` then sets the window,
transition and animator scales to 0 on an emulator for the run and restores them
when it finishes. The live run keeps animations.

`FocusRecordListener` (`app/src/androidTest/.../e2e/FocusRecordListener.kt`,
registered through the `listener` instrumentation argument in
`app/build.gradle.kts`) logs what the window manager reports when a device
test fails, so a recurring focus failure (`RootViewWithoutFocusException`, an
IME/paste/back `waitUntil` timeout) can be diagnosed instead of re-triaged as
pre-existing (#1131). It never changes a test's result or hides its original
exception; a failure while recording is itself logged and the run continues.
The gate script prints the record to **stderr**, not stdout, so it survives
worktree removal and never touches the dispatcher's XML:
`Android gate: focus record for <Class#method>: …`. The same line is also in
the kept per-test logcat under tag `FocusRecord`. A passing run has no
records — the listener only runs on failure — so a passing gate prints
nothing extra. Fields: `focus` is the window holding input focus,
`focusedApp` the focused activity record, `anr` any
`Application Not Responding: …` window titles or `none`, `activities` each
activity the failing test opened with the last lifecycle stage it reached
(`none` if it opened none), and `error` means recording itself failed (the
other fields are absent). The record is read just after the failing test's
rules tear down, not at the exact instant of failure: an
`ActivityScenarioRule` or Compose rule has usually already closed the test
activity by the time `testFailure` fires, so `focus` and `focusedApp`
commonly name whatever the teardown left focused, on the managed emulator
`com.android.fakesystemapp/.launcher.EmptyHomeActivity`, not the test
activity. Read `activities` first: `DESTROYED` means that launcher focus is a
teardown leftover and the test failed on its own assertion, so read the
assertion, not the focus. `RESUMED` beside a foreign `focus` is a real loss
of focus. #1809 is the cost of skipping this: 38 failures on the main sweep,
every one showing the launcher, were triaged as a shared focus fault when
they were density and Robolectric-size assertions (fixed by #1808). A
system dialog holding focus over the test is still captured. A
crash dialog (`Application Error: …`) shows under `focus`, not `anr` — the
first real record (#1131) found one blocking `com.android.bluetooth`, not an
ANR. The listener remains diagnostic. Separately, `E2eInstrumentationRunner.quietSystem`
now disables emulator Bluetooth, hides system crash dialogs for the run and closes existing
system dialogs (shipped at `186c399b`). It restores the dialog setting on finish and leaves
Bluetooth off. The [earlier-Other evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence)
shows the unchanged IME method passing even during a native Bluetooth crash; this mitigation
does not establish that the emulator service itself is repaired.

## Full-cap storage proofs

Ordinary `./gradlew test` excludes the four `FullRetentionTest` cases that repeatedly
rewrite and restore 100000 disk rows. Fast retention policy, cache and history tests still run.
Run `./gradlew test -PfullRetentionTests=true` after changing cache retention or saved history
position handling. The flag includes those proofs in the ordinary suite.

Pipeline implementations and their tests live in `pyrycode-mobile-agents/scripts`.
The existing product script paths are compatibility launchers.
Deploy the agents checkout first and set `AGENTS_REPO_PATH` for a non-sibling installation.
Test the moved tools with the agents repo's `bin/test-mobile-tools /path/to/pyrycode-mobile`.
