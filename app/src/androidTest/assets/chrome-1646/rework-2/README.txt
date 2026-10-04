#1646 / PR #1700 second rework, 2026-10-04.

Diagnosis
Android performClick injects pointer input. ScrollTo uses the full list viewport, so Kotlin's choice rested beneath the 69dp header and Continue beneath the composer. The failure reproduced with window focus true (Continue at y=597..640; composer at y=525). Additional runs observed transient host focus loss and confirmed that the blocked choice left Continue disabled. The repaired test waits for host focus after display resizing, scrolls each choice between measured chrome, verifies both selections, restores the actions to the newest end, and checks the tested edge is clear. It retains 48dp, nonoverlap, enabled-state, physical edge-tap and single-batch assertions.
Info banners retain their stable keyed rows but render nothing. Newest-row spacing now skips them. The append/remove regression failed at about 16dp before repair and preserves the required 12dp afterward.

Executed evidence (tests / failures / errors / skips; all commands ran to completion)
- Pointer diagnosis: 6 / 6 / 0 / 0 across six narrow device attempts; pointer-before.xml retains the focused-window reproduction. The first diagnostic compile attempt failed before executing tests and is excluded from these counts.
- Spacing red: 1 / 1 / 0 / 0, spacing-red.xml.
- Repaired pointer method: 1 / 0 / 0 / 0, pointer-green.xml; Gradle exit 0.
- Entire QuestionBatchModalTest class: 11 / 0 / 0 / 0, question-class-green.xml; Gradle exit 0. Includes physical choice/field/edge taps, security protection and real test-IME reachability.
- Focused JVM classes: 147 / 0 / 0 / 0, jvm-counts.txt and ThreadChromeTest-green.xml; Gradle exit 0. Native-graphics geometry includes the Info-banner regression, child gestures and blank-chrome touch isolation.
- Deterministic stream: 1 / 0 / 0 / 0, scripted-stream-green.xml; gate exit 0. An initial attempt without configured sibling sources executed zero tests; it is not passing evidence.
- No hardware capture methods reran during this pointer-test/Info-banner repair. Original full Pixel 8 captures, inset sidecars and current-Figma comparisons remain in the parent evidence directory and rework/. The latest verifier independently confirmed current Figma exports equal those references. Capture rendering and the reference fixtures are unchanged.

Commands
Device runs held scripts/android-test-gate.py's shared device lock. Toolchain: installed Android SDK; Android Studio bundled JBR. Named method run, then class run (one shard):
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.QuestionBatchModalTest#large_text_actions_stack_and_pointer_edges_submit_only_this_batch -Pandroid.experimental.androidTest.numManagedDeviceShards=1 --console=plain
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.QuestionBatchModalTest -Pandroid.experimental.androidTest.numManagedDeviceShards=1 --console=plain
./gradlew testDebugUnitTest --tests '*.ThreadChromeTest' --tests '*.ThreadBannerLevelTest' --tests '*.SessionBoundaryVisibilityTest' --tests '*.ThreadScreenFollowTest' --tests '*.ThreadFrameTest' --tests '*.ThreadMessageAreaTopTest' --tests '*.ThreadScreenShortStreamTest' --tests '*.ThreadScreenHistoryTest' --tests '*.ThreadScreenNewestRowTest' --tests '*.ThreadTopOverlayTest' --tests '*.ThreadCanvasPaletteTest' --tests '*.ThreadInlineQuestionTest' --tests '*.ThreadComposerFooterTest' --tests '*.SlashCommandTypeAheadScreenTest' --tests '*.ComposerAttachmentStripTest' --tests '*.ThreadInputBarDraftBindingTest' --tests '*.ThreadInputBarEnterKeyTest' --tests '*.ThreadInputBarStyleTest' --tests '*.ThreadScreenOverflowTest' --tests '*.ThreadOverflowMenuTest' --tests '*.MessageBubbleSelectionTest' --console=plain
PYRYCODE_SRC=/Users/juhanailmoniemi/Workspace/Projects/pyrycode PYRYCODE_RELAY_SRC=/Users/juhanailmoniemi/Workspace/Projects/pyrycode-relay python3 scripts/android-test-gate.py scripted stream

The dispatcher owns fresh full check, UI and scripted-all gates. Documentation remains pending for the documentation stage.

Final build checks: ./gradlew spotlessApply lint assembleDebug compileDebugAndroidTestKotlin --console=plain passed (exit 0).
