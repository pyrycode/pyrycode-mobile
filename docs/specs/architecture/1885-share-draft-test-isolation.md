# Share picker draft fixture isolation (#1885)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/share/SharePickerTest.kt`: the picker transfer, direct transfer and unknown-shortcut tests share the demo conversation's app-scoped store.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt`: `clearConversation` clears both text and pending attachment ownership for one exact pair.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the store is a singleton; `ThreadDestinationFactory.thread` receives it when each destination is created.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/share/ShareIntake.kt`: `ShareIntakeViewModel` retains an unselected captured batch and transfers only on selection.
- `docs/knowledge/features/navigation.md`: unknown shortcuts preserve the captured batch and all drafts while falling back to the picker.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md`: text and pending attachments share pair ownership and must both be isolated.
- `docs/knowledge/features/development-verification-gates.md`: retain sharedTest execution on Robolectric and the managed device; evidence must include executed counts.

## Change

Give SharePickerTest an outer JUnit fixture rule that clears its demo and alternate-host conversation pairs before each method and after the Compose rule tears down. This isolates the app-scoped text and pending attachments even when a test fails, and prevents live thread observers from rewriting cleaned state. Retain the real singleton wiring so transfer assertions still prove the production thread sees the intake's draft. Seed the unknown-shortcut test with an explicit nonempty draft, capture a file with shared text, and assert that fallback retains both in the ready picker without altering the draft or transferring attachments. Production behavior and visual layout stay unchanged. No overlapping feature branch touches this test.

## Testing strategy

Use the existing managed-device class run to reproduce the leaked state before the repair. Run the complete SharePickerTest class under Robolectric and the managed Android 13 device after the repair; inspect fresh XML counts and the formerly failing method. Existing transfer and single-consumption assertions remain intact. Run lint, assembleDebug, Android test compilation and Spotless for the touched shared test, then the required final whole unit/shared suite, assembleDebug and pre-verify after merging main. This is test fixture work with no new operator flow, so no live scenario is needed.

## Revisions

- 2026-10-07: The baseline device class ran six methods with three failures: leaked draft text, a leftover attachment and a direct-share composer lookup during navigation's root gap. After pair cleanup, both state failures passed, while `directShareWaitsForCaptureAndMergesTheExactDraftWithoutPickerOrReplay` still reached its text assertion before a Compose root existed. Wait for the editable composer node with root-optional polling before retaining the exact merged-text assertion; the destination route alone does not prove the thread has rendered on the device.
- 2026-10-07: The focused direct-share rerun timed out despite root-optional polling. Its fresh FocusRecord names `GrantPermissionsActivity`, confirming notification onboarding took focus: mounting the ordinary channel list before supplying the share lets the permission request start. Supersede the readiness wait above. Accept each navigation test's share on Main before mounting `PyryNavHost`, matching fresh share launch order, so the pending batch suppresses onboarding from the first composition. Keep all existing transfer, route and composer assertions without a longer timeout or permission-state mutation.
