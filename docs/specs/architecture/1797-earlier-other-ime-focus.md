# #1797: establish earlier-Other IME focus reliability

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModalTest.kt`: `imeBeforeActivity`, `withTestIme`, `show`, and `ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen` establish the compact viewport, real IME, initial host focus and retained-draft/chrome-clearance assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eInstrumentationRunner.kt`: `quietSystem` already mitigates emulator Bluetooth crash dialogs in shipped commit `186c399b2e875e9bd92d18dec00b02d45bd8e12c`; `onStart` and `finish` manage animation scales and restore settings.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/FocusRecordListener.kt`: failure focus evidence is collected after rule teardown, so launcher focus alone cannot identify the original blocker.
- `app/build.gradle.kts`: `defaultConfig` wires the runner and focus listener.
- `scripts/android-test-gate.py`: `device_hold` coordinates host-wide device use; the UI gate requires `UI_GATE_FULL=1` to prevent an evidence-only branch being skipped.
- `docs/knowledge/features/question-batch-modal.md`: the inline question host and existing real-IME coverage constrain this ticket to harness reliability.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: fresh counted XML and retained per-test evidence are necessary; an exit status alone is insufficient.

## Change

First execute the existing named method unchanged with the shipped runner mitigation. If it passes, credit that repair and retain fresh counted XML plus command, exit status and tested revision under `app/src/androidTest/assets/focus-1797/`; no Kotlin change is needed. If initial focus still fails, diagnose the actual blocking window before making a local test/device-setup repair, recording that design under Revisions. Preserve initial host focus, the wholly obscured earlier field, focused draft, positive real IME inset and chrome clearance through open/dismiss/reopen. Product defects go to separate refinement. This is one deliverable, with three acceptance criteria, no new exported types or consumer migrations, and an expected 150–350 written lines including plan and evidence.

In-flight #1674 also touches `scripts/android-test-gate.py`; this ticket consumes the already-shipped device hold and does not need that branch's changes.

## Testing strategy

Build the app and test APKs before acquiring the existing `device_hold`, then execute `:app:pixel2Api33AtdDebugAndroidTest --rerun` selecting only `QuestionBatchModalTest#ime_keeps_an_earlier_other_clear_of_chrome_on_open_dismiss_and_reopen`, with `android.experimental.androidTest.numManagedDeviceShards=1` and `android.testInstrumentationRunnerArguments.disableAnimations=true`. Retain fresh XML proving exactly 1 executed, 1 passed and 0 failures/errors/skips. This remains device-only because it uses a real IME and platform window focus/insets. If shared setup changes, rerun the focused method and its whole class. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck before handoff. No new pure logic is planned, so the existing method is the regression test.

The dispatcher must separately run `UI_GATE_FULL=1 ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` on the handed-off revision and retain fresh counted XML proving the named method executed and passed. Report sweep executed/passed/failed/error/skipped counts. This pending sweep is not a builder pass; no live Claude scenario is required.
