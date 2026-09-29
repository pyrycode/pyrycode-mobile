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
gives up exits 75 without starting Gradle or the e2e harness, naming the
holder's mode, worktree and start time — that exit code means a busy device,
not a test result. A `ui` run that skips itself (above) takes no hold. A direct
`./gradlew …AndroidTest` run bypasses the script and does not take the hold.

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
`Application Not Responding: …` window titles or `none`, and `error` means
recording itself failed (the other fields are absent). The record is read
just after the failing test's rules tear down, not at the exact instant of
failure: an `ActivityScenarioRule` or Compose rule has usually already closed
the test activity by the time `testFailure` fires, so `focusedApp` commonly
names whatever the teardown left focused (such as the launcher), not the test
activity. A system dialog holding focus over the test is still captured. A
crash dialog (`Application Error: …`) shows under `focus`, not `anr` — the
first real record (#1131) found one blocking `com.android.bluetooth`, not an
ANR. This ticket records evidence only; no mitigation (dismissal, retry) is
implemented against it.

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

ATD can also report zero physical system-bar insets and return black framebuffer
captures while geometry assertions pass. `MainActivityInsetsDeviceTest` injects
24 dp bars only when both reported bars are zero, preserves incoming IME insets,
and records `syntheticBars` with density and measured insets. That path supplies
geometry metadata only. For visual evidence, run the same class on the full
`pixel8Api35` (`google_apis_playstore`) image with
`-Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true`;
it requires nonzero real bars and rejects blank PNGs. Let display configuration
settle before launching the Activity. The
[committed captures and measurements](../../../app/src/androidTest/assets/insets-1149/)
show 412×892 and 360×800 dp; synthetic geometry alone cannot prove pixels.

For thread keyboard regressions, exercise a populated thread through opening,
dismissal and reopening in the real activity. Initial opening alone missed the
pan in #1166; an empty-thread comparison also failed to establish the reported
case. `MainActivityInsetsDeviceTest.populatedThreadKeyboardAt412By892` and
`populatedThreadKeyboardAt360By640` create a temporary fake channel with 30 fixed
messages. The usual demo's animated streaming message can confound scroll-anchor
checks. Establish newest and older anchors before each preservation cycle, leave
messages unchanged, and do not send or manually scroll until draft preservation
and dismissal's index/offset restoration have been checked. Then prove touch
scrolling with the IME open. Check the header in screen coordinates, actual IME
visibility and positive inset, visible messages, enabled send reachability and
the normal 16dp footer gap on both opening and reopening.

The [#1166 captures and XML](../../../app/src/androidTest/assets/insets-1166/)
retain before/open/dismissed/reopened states at 412×892 and 360×640 dp in static
dark, static light and light with wallpaper colours enabled, for both anchors.
They use the full `pixel8Api35` image and `requireRealSystemBars=true` described
above. The [baseline](../../../app/src/androidTest/assets/insets-1166/baseline/)
intentionally retains two failures with the unspecified soft-input policy: the
Back/header control disappears on reopening. Both regressions pass with the
[explicit activity policy](thread-input-bar.md#ime-handling--modifierimepadding-moved-to-the-composer-column).
When changing activity-wide soft-input or inset handling, also rerun
`MobileModalTest.ime_keeps_focused_field_final_item_and_actions_reachable` and
`landscape_ime_keeps_focused_field_following_content_and_actions_reachable`;
the retained `api33-green.xml` includes both alongside the activity checks.

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

A `BasicTextField(state: TextFieldState, ...)` field (Compose foundation 1.10.4,
found migrating [`ThreadInputBar`](thread-input-bar.md#draft-binding--cursor-at-end-undo-and-redo-885-934)
for #934) is not a drop-in replacement for the `value`/`onValueChange` overload in
tests, in three ways:

- It exposes a `ScrollBy` semantics action the legacy field never did. A selector
  that finds "the" scrollable node with `hasScrollAction()` then matches two nodes
  once such a field and a scrollable list share a screen. Match the list with a
  more specific action instead, e.g. `hasScrollToIndexAction()` or
  `hasScrollToNodeAction()`, if only the list should carry it.
- Undo and redo bypass `InputTransformation` — they write the field's buffer
  directly and never call `commitEditAsUser`. A binding that reports edits only
  through the transformation misses them; see the linked section for the
  `snapshotFlow`/`accountedText` fix this required.
- `SemanticsActions.PasteText`, unlike undo/redo, **does** route through
  `Modifier.contentReceiver` the same way a real user Paste does — both under
  Robolectric and on the device — so a paste test needs no text-toolbar
  workaround.

Robolectric's `KeyCharacterMap` ignores the Ctrl meta state, so a test that sends
a hardware Ctrl+Z to a Compose field there actually types a plain "z" and can pass
green without exercising undo at all. A test that must prove undo or redo — as
opposed to typing or pasting — needs a device.

A device's `ClipboardManager` throws `SecurityException` when a test puts a
`content://` URI on the clip that the calling app cannot read; Robolectric's
clipboard does not model this permission check, so the same test passes there and
fails only on the device sweep (#992). Production code must keep refusing the
app's own content URIs (`AttachmentReader.isForeignContentUri`), so granting the
test app read access is not an option. Guard the device run instead —
`assumeTrue("<reason>", Build.FINGERPRINT == "robolectric")` on the affected
method — and name the device-only harness that still proves the real path in the
skip reason and a KDoc line, e.g. `ComposerImagePasteDeviceTest`, which inserts a
real `MediaStore` image, pastes it and removes it afterward.

On a device, a real drag that ends with the finger still down past a scroll edge
can hold the stretch overscroll effect, which keeps drawing frames — `waitForIdle`
then never returns, because the instrumentation idle check treats those redraws as
ongoing activity. Robolectric draws no such frames, so the same test passes there
while hanging the managed device (`ThreadScreenNewestRowTest`, #992; a thread dump
located the hang inside `waitForIdle`). The fix is
`CompositionLocalProvider(LocalOverscrollFactory provides null)` around the test's
`setContent`, which drops only the visual stretch: drags still reach the list as
`NestedScrollSource.UserInput` through the nested-scroll chain, so a test
asserting scroll-yield or auto-follow behavior is unaffected.

`captureToImage()` → `forceRedraw` waits 2000 ms for a fresh frame and throws
`ComposeTimeoutException` on a loaded emulator, independently of whether the
layout assertions around it already passed — seen four times against
`ScannerFrameTest` and `PairCodeScreenTest` (#1038), including once after
`PairCodeScreenTest`'s existing leading `rule.waitForIdle()`, so waiting for idle
first is not sufficient on its own. The screenshot PNG is a review artifact, not
part of the contract under test, so a capture-only helper should retry
(`ComposeTestRule.saveScreenshot` in
`app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScreenshotCapture.kt`
retries up to three times with `waitForIdle()` before each retry) and log +
skip the PNG rather than fail the test when every attempt still times out. Catch
only `ComposeTimeoutException`; any other exception from the capture should still
fail the test. The retry cannot be proven on a healthy emulator because the
timeout does not reproduce on demand — the focused device run only proves the
PNGs are still written when capture succeeds, not that the skip path fires.

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

A fake repository seed backed by a `MutableStateFlow` — `FakeConversationRepository.setSlashCommandMenu`,
for example — needs `advanceUntilIdle()` before a `ViewModel.state.value` assertion sees it, even with an
`UnconfinedTestDispatcher` installed as `Main` (#884): the write still has to propagate through whatever
`map` / `combine` / `stateIn` chain sits between the fake's `MutableStateFlow` and the cached `state.value`.
A scripted fake's plain `MutableSharedFlow.emit`, by contrast, does not need it.

A plain-Kotlin controller constructed with `runTest`'s `backgroundScope` as its owner scope (rather
than the `TestScope` itself) never leaves its initial state under `advanceUntilIdle()` —
`backgroundScope` coroutines are not what that call drains, so every assertion fails on the test's own
setup, not on the code under test (#824). Pass the `TestScope` as the owner scope, or call
`runCurrent()` after launching in `backgroundScope`. The same gap bit a DataStore built with
`PreferenceDataStoreFactory.create(scope = backgroundScope, ...)` in a test (#953): its write actor
never ran under `advanceUntilIdle()`, so a read straight after saw the old value. Give the store its
own `CoroutineScope(StandardTestDispatcher(testScheduler) + Job())` instead and cancel it at the end —
see [Push messaging service § Testing](push-messaging-service.md#testing) for the full case. A third
instance: `RedialingLinkTest` (#1036) supervises its redial loop in `backgroundScope` by default, so a
case that needs the loop to have redialed drives `advanceTimeBy(...)` past the backoff plus
`runCurrent()`, not `advanceUntilIdle()`, for the same reason. More
generally, a `first { predicate }` wrapped in `withTimeout` against a live DataStore or other
in-memory `StateFlow` is not a safe "wait a bit": if the collector's first read overlaps the write it
is waiting for, the read returns the pre-write value and the emission it needed is never replayed, so
the wait times out no matter how high the timeout is. Prefer owning every coroutine the write uses and
reading once with a plain `first()` after draining the scheduler, over racing a wall clock against
writer code the test does not control.

A real file-backed DataStore can drop the emission for a documented reason, not just a scheduling
accident: DataStore 1.1.7's `writeData` increments the store's version before it writes the scratch
file, fsyncs it and renames it, and only afterwards updates the in-memory cache. A collector whose
first read lands in that window sees the pre-rename file content under the already-bumped version, so
`data`'s `dropWhile { it.version <= startState.version }` discards the real update once it finally
lands, as "not newer". The window is just the fsync plus the rename, so the miss rate tracks machine
load: `ThreadViewModelEffortRecallTest.aSuccessfulTap_survivesAnAppRestart` (#1094) missed 14 of 300
loop iterations unloaded and 34 of 300 under load, throwing `TimeoutCancellationException` only in the
full `./gradlew check`, never in a focused run of the one test class. There the write is driven by
production code (`ThreadViewModel.onEffortSelected` -> `EffortRecall.remember`), so the test cannot own
that coroutine the way the "owning every coroutine" advice above assumes. The fix instead waits on the
write call itself returning, not on the data stream: wrap the real `RememberedEffortStore` in a
delegating one whose `remember` calls through and then completes a `CompletableDeferred`, and await that
deferred in place of `store.data.first { predicate }`. To wait for a write you don't control, wait for
the writing call to return, never for a predicate over the read side.

A test that simulates an app restart by cancelling one `DataStore`'s owner scope and immediately
opening a second `PreferenceDataStoreFactory.create` on the same file must `join()` the first scope's
`Job`, not merely `cancel()` it: DataStore unregisters a file from its process-wide active-file set only
when the owning scope's job *completes*, and `cancel()` only requests that — it returns before the job
finishes. A second store opened in the gap sees the file still registered and its first read throws
`IllegalStateException: There are multiple DataStores active for the same file`, reproducing every time
the affected test runs alone (#1075, mirroring the pattern `AppPreferencesTest.rememberedEffort_survivesProcessDeath`
and `HostWorkspacePreferencesTest` — see [App preferences § Testing](app-preferences.md#testing) —
already used correctly). Hold the first scope's `Job` in a named `val` and call `job1.join()` right after
`scope1.cancel()`, before constructing the second store.

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

The #726 proof holds only when it runs on the thread that later calls `resetMain()` — #892 found a
case where it does not. With an `UnconfinedTestDispatcher` installed as `Main`, a resumption resumes
in place on whichever thread wakes it: the `Dispatchers.Default` worker's `StateFlow` publish resumes
the ViewModel's collector in place, and the ViewModel resumes the test body's `first {}` the same way.
From there the rest of the test body — including its `finally` and `closeAndAssertStopped()` — runs on
the publishing worker, nested inside the very `publish` it is meant to fence. `dispose()` is
`@Synchronized` on that same monitor, so the reentrant call from the worker passes trivially and the
proof is vacuous, while the cancelled ViewModel coroutines underneath still have to unwind into `Main`
after the test thread calls `resetMain()`. This surfaced as `dismissingTheChatEditorSendsNothing`
failing in `HostChannelListViewModelTest`: the leaking test
(`appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`) runs immediately before it
in JUnit's method order, and kotlinx-coroutines-test reports an exception raised between tests at the
*next* `runTest`, not the one that leaked it — check the test immediately before the failing one in the
XML report's order before trusting which test caused a flake like this. The migration from test thread
to worker was frequent (up to ~97% of iterations in a stress loop) but the resulting crash was rare,
since it also needed the worker's unwind to race the test thread's teardown — a window that a stress
loop alone did not reliably open, but a loaded machine (a full `check` run, lint running alongside) did.
A test that pairs a Koin-built `HostConversationSource` with a ViewModel must install a *dispatching*
`StandardTestDispatcher(testScheduler)` as `Main` (not an `UnconfinedTestDispatcher`) before resolving
the ViewModel, so a worker's resumption is queued for the test thread instead of running in place; both
`HostChannelListViewModelTest` and `HostDiscussionListViewModelTest` do this now. `KoinHostSources`
also records the thread that constructs it and asserts `closeAndAssertStopped()` runs on that same
thread, failing before closing anything on a mismatch — so a regression fails the test that caused it
instead of a later, unrelated one.

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

A post-test diagnostic step needs its own status capture, distinct from that
teardown. Appending `|| STATUS=$?` to the Gradle test invocation (`STATUS=0` set
just before it) captures the test task's exit code before `set -euo pipefail` can
exit the script, so a following diagnostic step still runs; the step's own
explicit `exit "${STATUS}"` then keeps the run non-zero. A helper called from that
step must be written with `if` statements, never a trailing `cond && grep` chain —
`set -e` kills the run at the call site when such a chain's last command finds
nothing, which for a log scan is the common, successful case
(`report_stale_pairing_codes` in `e2e-emulator.sh`, #993; the sibling `report_relay_link_drops`, #1132,
names each daemon whose relay link dropped for a reason other than the teardown's own kill).

In `test_e2e_emulator_gradle.py`, add new cases for one of these diagnostic steps to the
`unittest.TestCase` that already extracts and runs it (`EmulatorBuildBeforeMintTest` for the failed-test
branch), not to a new subclass of it — subclassing to reuse its extraction helpers also re-runs every
inherited test method under the subclass's name, which silently multiplies the suite (#1132: a first draft
took it from 12 tests to 17 before the new cases moved into the base class).

Splitting a Gradle build step ahead of the device test task, to keep build time out
of a time-limited window elsewhere in the harness (#993 moved e2e pairing-code
minting after `assembleDebug assembleDebugAndroidTest`, so a slow build no longer
burns the daemon's 15-minute redemption window), is not defeated by the test
task's own `--rerun` (`PYRY_FORCE_TEST_RUN=1`): that flag reruns only the test task
itself, not its dependencies, so the compile and package tasks the split build
already ran still report `UP-TO-DATE` in the test task's `--console=plain` output.

The Android gate must search only the report path selected by `DEVICE`:
`connected/debug` for `connected`, otherwise `managedDevice/debug/<DEVICE>`, under
`app/build/outputs/androidTest-results`. Freshness alone is insufficient: a fresh
report from another profile or execution path can falsely satisfy a broad search,
while searching only managed reports misses a successful connected run.
`test_android_test_gate.py` exercises both paths with fresh wrong-path reports,
missing or stale selected reports, failed XML and a failing process despite passing
XML. Preserve path selection and process-status checks together.

The host-wide device hold (#1071) is one kernel `flock` per process, taken on a
descriptor `main()` opens itself and releases when it returns — not a
process-lifetime descriptor threaded through the module. A second `flock` from
the same process on a fresh descriptor still conflicts with the first, so a test
that calls `main()` more than once must let each call finish (and so release)
before the next; nothing in the API stops a test from taking the lock twice and
blocking on itself. Every test that drives `main()` also points
`ANDROID_USER_HOME` at its own temp directory first: without that, the test
takes the real lock file beside the host's actual `~/.android/avd/gradle-managed`,
and a real gate run elsewhere on the host would block the test for the full wait
bound instead of failing fast. `DeviceHoldTest` proves the hold against a real
second process holding the lock, not a mock — including a SIGKILLed holder
releasing immediately and a holder that finishes mid-wait.

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
Installing that sink is order-sensitive when the class under test logs from a
constructor path: a test that builds `HostConversationSource` at field
initialization (rather than inside `@Before` or the test body, after the sink is
installed) reaches the source's first `reconcile` log before the sink exists, and
the resulting `android.util.Log` call fails the test as an uncaught exception
before any assertion runs (#840).

Spotless also runs ktlint's filename rule. If a Kotlin file contains one non-private
top-level class-like type, the filename must match that type, including for
`internal` types. Split a result or DTO into its own correctly named file when the
rule requires it; `spotlessApply` cannot repair the filename.

The Spotless message saying it could not autocorrect a theoretically fixable
violation is only a warning. Read the final `BUILD SUCCESSFUL` or `BUILD FAILED`
and the explicit violation list. Use `./gradlew spotlessCheck --rerun-tasks` when
cached output makes the result unclear.

Spotless's unused-import check is name-based, not usage-based. After moving code
out of a file, an import can go unused while the file still calls a same-named
member on an unrelated type — e.g. `kotlinx.coroutines.flow.map` staying imported
after the only `Flow.map` call moved elsewhere, because `List.map` still appears
in the file and the check cannot tell the two `map`s apart. `spotlessApply` will
not remove it either. Check each import a move leaves behind for an actual caller
of that specific symbol, not just any identically-spelled one (#916).

## Emulator and real evidence

An instrumented test proves behavior in its fixture. It does not prove camera
binding, lifecycle timing, relay compatibility or a real daemon round trip. The
dispatcher runs the UI gate and each zero-real-Claude scripted scenario before
verifier. For a ticket labelled `needs-real-claude`, it runs
`python3 scripts/android-test-gate.py live` after verifier and before documentation
or merge. Record the scenario, app/build version, daemon compatibility and executed
test count. XML evidence is required; a zero exit code with every scenario skipped
is not a passing proof.

The dispatcher runs the seven scripted scenarios as one step,
`python3 scripts/android-test-gate.py scripted-all`. It boots the managed
device's AVD once, headless and read-only from its snapshot, on a free console
port, and runs each scenario against it through the harness's `connected` device
with `ANDROID_SERIAL` pinned, so parallel tickets never share an emulator. Each
scenario still gets its own daemon, relay, pairing and app install. The step
names each scenario's result on stderr and stops the emulator even when the
dispatcher's time cap kills it. Before the ui gate has ever created the AVD it
falls back to the managed device per scenario. Measured 2026-09-23: 61 seconds
against 152 for seven separate runs. `scripted <scenario>` stays the focused
command for one scenario.

The current required gate profile is managed `pixel2Api33Atd`, Pixel 2 / API 33 /
AOSP ATD arm64. The full `pixel8Api35` image supplements it for real-system-bar
screenshots ([Compose evidence](#compose-evidence)); it does not replace the
required gate profile. See the
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

The demo binding (`-PuseRelayRepository=false`) does not skip onboarding: the start screen is
chosen from the paired-host store, so a demo build's channel list, thread and modal screens are
reachable only after a real pairing. Pair through the app's own paste-a-code flow against a
throwaway, isolated local test daemon and relay built from the configured sibling checkouts (no
Claude binary needed, no turns spent), then stop both afterwards. Two traps found doing this
(#680): a long scratch `HOME` makes the daemon's unix socket path too long, and
`adb shell input text` silently truncates a ~300-character pairing code, so type it in ~50-character
chunks. The demo host is never in the paired-host store, so Edit host itself refuses to open
(`host_editor_open_rejected code=unknown_host`); Edit channel shares the same modal shell and
substitutes for it in a shell-only comparison.

For camera overlays, the dispatcher must verify the real preview layer on the
managed emulator or device when the change concerns it. A unit test or a fake
preview slot cannot prove CameraX binding or that the preview respects the Compose
overlay. For relay and Noise changes, combine deterministic JVM coverage with the
appropriate UI or post-verifier live path; do not claim the latter ran unless its
output identifies the executed scenario and XML evidence.

A device gate run (`ui`, `scripted`, `scripted-all` or `live`) copies each fresh
per-test `logcat-*.txt` into that run's `build/dispatcher-tests/<mode>-*` artifact
directory next to the XML it already copies (`fresh_logcats` in
`scripts/android-test-gate.py`, the sibling of `fresh_reports`) — the next run
overwrites AGP's originals under `androidTest-results/`, so this is the only place
they survive (#1039). To diagnose a relay connection drop, read those
[`RelayLog`](relay-log.md) `event=transport_end` / `event=pump_teardown` lines
alongside the daemon's `daemon.log` by timestamp; neither side logs the other's
cause, so correlating by time is what tells a phone-side, relay-side or network
ending apart.

## Documentation evidence

Record the failure that would otherwise recur, its cause and the check that catches
it. Put product behavior in the owning feature topic. Put requirements, review
findings and unfinished work on the ticket or PR. Put workflow lessons in the
agent or dispatcher repository. The frozen `codebase/` archive is read-only, and
local Claude memory is not a substitute for a reviewed repository document.

## Archive refresh regression

The live archive test on 2026-09-20 exposed a list decoder that discarded
`is_archived` and reset every row to active on a refresh. List summaries now
preserve that field, with an active default for older server replies. The live
archive and restore scenarios enter through the list toolbar for the selected
host and wait for the restore success snackbar before leaving Archive: the
restore coroutine belongs to that destination's ViewModel. A conversation can
be created before the test's UI or repository wait returns, so fixture cleanup
records each host's conversation IDs before creation and recovers a new ID for
bounded deletion if setup fails. Preserve these checks when changing the list
mapping, Archive navigation or live fixtures. See the [rung-3 Archive coverage](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay).

For a channel prompt edit followed by Reset session, wait for a distinct second
real reply before checking `SessionPromptStatus.Matches`. Reusing the first turn's
reply text could let a display assertion match an old-session message and make the
post-reset check pass without proving the new turn rendered.
