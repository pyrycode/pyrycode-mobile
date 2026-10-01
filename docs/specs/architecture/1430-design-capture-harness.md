# #1430 — combined-build capture harness and onboarding audit

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` — `launch` (inset listener that flags synthetic bars), `openKeyboard`/`awaitKeyboard` (test IME, window-focus recovery), `capture` (`uiAutomation.takeScreenshot`, blank-frame check), the paired-startup `PairedServerCollectionStore` override. The harness lifts these shapes; the class itself stays unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadActivityIndicatorCaptureTest.kt` and `.../components/ToolRowDesignCaptureTest.kt` — the two copies of the `order = 0` viewport `TestRule` and private `@Viewport` annotation that this ticket extracts.
- `docs/knowledge/features/development-verification.md` § "Compose evidence" — ATD black frames, `requireRealSystemBars=true` on `pixel8Api35`, IME selection and `wm size` must settle before the activity launches (#1402). Lesson carried: resize and IME selection happen before `ActivityScenario.launch`, never under a running activity.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` — `appModule`'s `viewModel { … ThreadDestinationFactory.thread … }` and `viewModel { ScannerViewModel(…) }`; `ThreadDestinationFactory.thread`'s demo early return passes only defaults, which is why the fake graph cannot emit the thread's coordinator inputs.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — the constructor inputs the override must fill.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — `observeAttachmentOffers`, `observeSessionFacts`, `observeContextUsage` default to empty on the fake.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt` and `MainActivity.kt` (`Routes.SCANNER`) — scanner states are event-driven; `ScannerConnectingScreen` is only referenced from previews.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` — non-e2e device runs start Koin with `conversationRepositoryModule(useRelay = false)`, so the demo host and `FakeConversationRepository` back every capture.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Onboarding frames on the Mobile page, re-read 2026-10-01, all 412×892: Welcome `6:32`, Scanner `13:2`, Scanner — Denied `32:2`, Scanner — Connecting `32:20` (spinner, "Connecting to your pyrycode server…", host address), Pair Screen `533:2147` (two filled fields with clear icons, Pair and Cancel). Exports come from `get_screenshot`. Fixed dark theme only. No Mobile-page frame shows fingerprint confirmation; `487:2559` "Pairing verify" is a Components-page modal instance, not a Mobile frame.

## Context

Visual drift is reported across the app, and earlier per-ticket captures used component hosts. This ticket builds the shared harness later surface-group audits use (all of it test-only, no file under `app/src/main/` changes) and proves it on onboarding. The third capture user triggers the planned viewport-rule extraction. No decision record needed; the README under `design-1220/` is the harness's contract for later audits.

## Design

All Kotlin lives under `app/src/androidTest/java/de/pyryco/mobile/design/` (new package):

- `ViewportRule` + public `@Viewport(size: String, fontScale: Float = 1f)` annotation. Outer `order = 0` rule: records `wm size`, `wm density` and `settings get system font_scale`, applies density 160, the method's size (default `412x892`) and font scale, waits for idle, runs, restores all three in `finally`. `ThreadActivityIndicatorCaptureTest` and `ToolRowDesignCaptureTest` drop their copies and use it unchanged otherwise (their font scale stays in `LocalDensity`, so the annotation keeps the default `1f`).
- `DesignInputs` — the Koin override. Holds one hot flow per input the fake graph cannot emit: `connectionState`, `liveSessionEvents` (`MutableSharedFlow`), `hostModal`, `questionBatch`, `backgroundTasks`, `backgroundTaskCount`, `pairingRejected`, `attachmentOffers`, `sessionFacts`, `contextUsage`, and for onboarding `pairingStatus` (what the scanner's post-confirm wait observes). `module()` redefines `ThreadViewModel` with every input wired, its repository being the demo `FakeConversationRepository` behind a `ConversationRepository by fake` delegate that overrides only the three repository flows; and redefines `ScannerViewModel` over an in-memory `PairedServerStore`, a no-op `RelayConnectionController` and `pairingStatus`. Each created view model is published (`thread`, `scanner`) so a test can drive event-only states. `install()` loads the module over the app graph; `uninstall()` reloads `appModule` definitions for the two view models. Later audits set values; they never edit this class.
- `DesignCapture` — a JUnit rule used with `createEmptyComposeRule`. `@Before`-time setup: enables and selects `MobileModalTestIme`, grants camera and notification permission, records and restores theme preferences (sets DARK, wallpaper off), and controls the startup paired snapshot (`paired` flag) through the same `PairedServerCollectionStore` delegate as `MainActivityInsetsDeviceTest`. API: `launch()`, `capture(folder, name, node, figmaNode)` (writes PNG plus a `.txt` with size, density, font scale, bars, IME, synthetic flag; skips PNG on synthetic bars; asserts a non-blank frame and, under `requireRealSystemBars=true`, real bars), `openKeyboard(node)`, `closeKeyboard()`, `openMenu(node)` (clicks, then waits for a second compose root, the popup), `insets()`.
- `OnboardingDesignCaptureTest` — welcome, scanner, denied (via `scanner.onEvent(PermissionDenied)` on the live VM, no system dialog), pairing confirmation and verifying (QR payload fixture through `QrDecoded`, then Confirm with `pairingStatus` never answering), pair screen, pair screen with the keyboard open; each at 412×892 into `design-1220/onboarding/`.
- `DesignHarnessSmokeTest` — compact `@Viewport("320x700", 1.5f)` welcome capture proving non-black real-bar frames; a paired thread run that sets every `DesignInputs` input, opens the demo thread, asserts each input flow has a subscriber (reached the view model), asserts `backgroundTaskCount` and `connectionState` land in the view model, and opens the overflow menu with `openMenu`.
- `scripts/design-compare.py` — `design-compare.py APP FIGMA OUT_PREFIX`: scales the Figma export to the capture's size if they differ, writes `OUT_PREFIX-side-by-side.png` (Figma | app, labelled) and `OUT_PREFIX-overlay.png` (50 % blend). Pillow only. `scripts/test_design_compare.py` covers both outputs and the resize.
- `app/src/androidTest/assets/design-1220/README.md` (layout, index format, commit, inspection date, commands) and `onboarding/index.md` with captures, Figma exports, comparison images and verdicts.

Overlap with in-flight branches: none expected on new files; the two migrated test classes are the only shared files.

## State and concurrency model

Test-only. Input flows are `MutableStateFlow`s owned by the `DesignInputs` instance for one test; the view models collect them in `viewModelScope`, cancelled when the activity scenario closes. Scanner verification waits on `pairingStatus` and is cancelled by the scanner's own cancel or scenario close.

## Error handling

A missing `additionalTestOutputDir`, a blank frame, synthetic bars under `requireRealSystemBars=true`, or an IME that never shows fails the test. Restoration (size, density, font scale, IME, preferences, Koin definitions) runs in `finally`/`@After`.

## Testing strategy

Device-only: real pixels, the real activity, `wm`/IME shell control (Robolectric cannot supply any of these). Focused runs on the full image:

```
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=<the four classes>' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

covering the two migrated classes (AC1), the smoke class (AC2) and the onboarding class (AC4). `scripts/test_design_compare.py` runs with the scripts' unit tests. No rung-3 scenario: this is test tooling, not an operator-facing flow.

## Open Questions

- Whether Koin 4.0.4's `loadKoinModules` overrides a `viewModel` definition the way it overrides `single` (it keys both by type and qualifier). Settled by the smoke test's subscriber assertions.
- Whether every thread input is collected without extra UI state (for example the question batch only while a batch exists). Settled by the smoke test; an input that needs a precondition is documented in the README.

## Revisions

- **2026-10-01, smoke run:** the first device run found two thread inputs without a subscriber, which settles both Open Questions. Koin 4.0.4 does override the `viewModel` definitions. (1) `ThreadViewModel` reads `questionBatch` only when it gets no `QuestionDraftStore`, because production binds the app store to a host coordinator. The override therefore passes no draft stores, as the demo host does. (2) No thread code reads `observeAttachmentOffers` at this commit. `DesignInputs.attachmentOffers` is still overridden, but it is left out of `threadInputs`, the subscriber check, and the README says so. The smoke test now names any unsubscribed input when it fails. Uninstalling reloads copies of `appModule`'s two view-model definitions, because reloading `appModule` itself would re-create its eager singletons.
