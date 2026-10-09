# Stabilize the delayed discussion-rename regression (#2014)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/DiscussionRenameTest.kt`: `delayedDialogDoesNotReplaceComposer` arms a coroutine timer while the composer remains focused; the other four methods protect completion, owner readiness, reopening and diagnostic preservation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/DiscussionRename.kt`: `renameDiscussionInDialog` requires one labelled editable field inside a dialog, one enabled Save, dismissal and an external title. Its first wait must not target the composer.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt`: `RenameDialogInternal` labels and focuses its field inside the dialog window. No production change is planned.
- `app/build.gradle.kts`: shared tests run under Robolectric and also compile into instrumentation; no configuration change is planned.
- `docs/knowledge/features/rename-dialog.md`: matching field text is not rename completion, and text replacement must target the labelled dialog field.
- `docs/knowledge/features/development-verification-test-scheduling.md`: distinguish the scheduler driving a write from an observation of its result.
- `docs/knowledge/features/development-verification-gates.md`: keep editable-dialog fixtures at the existing Robolectric width and compile both consumers of shared tests.
- Compose UI test 1.10.4's installed `AndroidComposeUiTestImpl.waitUntil`: its deadline uses wall time and its polling advances the Compose clock one frame at a time.

## Context

Earlier failures timed out awaiting the dialog field. Fresh unchanged exact-method and five-method class runs both passed, so those historical failures do not establish the cause. Investigate the fixture's opening effect, virtual timer and mounted-dialog semantics with fresh counted evidence before selecting the repair. This is one test-reliability deliverable, with no production, wire or visible UI change. No decision record is needed.

No in-flight feature branch overlaps the test or helper after refreshing remote branches. The ticket is estimated at roughly 300–500 written lines including retained XML, metadata and this plan, with no exported types or consumer signature updates.

## Design

Keep the helper's labelled-field, Save, dismissal and title contract. Add narrowly scoped fixture diagnostics to distinguish a timer that has not resumed from a mounted dialog whose semantics do not match. Repair the responsible test operation, preferably by explicitly owning the virtual-time opening boundary rather than racing fixture scheduling against the helper's wall-clock deadline. Preserve the focused empty composer during a demonstrably held opening, and one submitted name after completion. Do not add retries, wall-clock sleeps, relaxed deadlines, ignores or production changes.

Retain selected before/after results under `app/src/test/resources/e2e/discussion-rename-2014/`. Each XML has adjacent command, tested revision, dirty-tree description or patch, exit status and executed/passed/failed/error/skipped counts. Keep diagnostic changes distinguishable from the final repair.

## State and concurrency model

The fixture's `LaunchedEffect(opening)` belongs to the composition and cancels at teardown. Its delay runs on Compose's test scheduler. If the repair manually controls `mainClock`, restore automatic advancement before the helper waits for external dialog-window work and completion. No repository or application lifecycle state changes.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Open requested while the empty composer is focused; field not mounted yet | `delayedDialogDoesNotReplaceComposer` explicitly proves the held state before releasing the fixture boundary. |
| Delayed mount, edit, one Save, dismissal and changed external title | `delayedDialogDoesNotReplaceComposer` retains the helper drive and submission/composer assertions. |
| Owning host absent while another is ready | `owningHostGapDoesNotLoseRenameWhenAnotherHostIsReady` remains unchanged. |
| Edited matching field without dismissal | `matchingFieldDoesNotProveRename` remains unchanged. |
| Reopen with a new name | `reopenedDialogSubmitsEachNameOnce` remains unchanged. |
| Diagnostic callback itself fails | `diagnosticFailurePreservesOriginalTimeout` remains unchanged. |

## Error handling

Keep the original helper timeout as the cause of its stage-labelled assertion. Fixture diagnostics use only test-authored state and semantics, and cannot replace the failure. A failing investigation run is retained as a failure, never described as acceptance.

## Testing strategy

First capture the failing operation with diagnostics and counted XML. Then execute the repaired exact method and full five-test class freshly using `testDebugUnitTest --tests <selector> --rerun --console=plain`. Check actual XML method names and counts, not only task exit status. Run the existing `RenameDialogTest` if the helper changes. Run lint, assembleDebug, instrumentation Kotlin compilation for the shared source, Spotless application/check, and the final assemble/pre-verify gates after merging main. Dispatcher owns the full suite; no device or real-Claude scenario is needed for this fixture-only repair.

## Open Questions

- Which boundary is responsible in a fresh failing run: timer resumption, recomposition or dialog semantics? Record the evidence and selected repair under Revisions before handoff.
