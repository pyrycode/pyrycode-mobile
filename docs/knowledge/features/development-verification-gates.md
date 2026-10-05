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
The device also ignores `@Config` qualifiers and Robolectric's 320dp width,
draws text with its own font, and runs at that density: a test that needs a
412dp frame forces it with `ForcedSize`, scales pixel samples by the composition's
density, and measures a layout box rather than the text inside it.

Text width is never exact, so a test may allow slack on it and on anything a
text's width positions. Line height is different: it is fixed by the design,
not by the text inside it, so a test checks the height of a line and the
vertical rhythm it sets exactly, allowing only the one device pixel
`assertDpEquals` already grants for rounding.

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
