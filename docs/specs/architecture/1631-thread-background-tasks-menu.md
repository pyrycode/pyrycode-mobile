# #1631 — Background tasks in the thread overflow

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt`: `ThreadOverflowMenu` owns the ordered, text-only Material 3 items.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt`: `ThreadTopAppBar` forwards menu callbacks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `backgroundTasksOpen` already opens `BackgroundTaskPanel` from Actions and the task pill.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt`: menu availability and dismiss ordering coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt`: screen-hosted Actions path and empty/unreported readings.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` exercises the existing live openers.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: scripted ping offers a stable thread with no background tasks.
- `docs/knowledge/features/thread-overflow-menu.md` and `thread-overflow-menu-wiring-tests-and-edge-cases.md`: reuse default menu rows; keep defaulted parameters after `modifier` for Compose lint.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-how-it-works-overlays-and-app-bar.md`, and `development-verification-gates.md`: panel state is conversation-local and shared screen tests run under Robolectric.
- `docs/e2e-interactive-stream.md`: rung-3 and rung-4 harness contracts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-5883

Inspected context and screenshot: a compact, text-only overflow anchored at the top-right, with flat 48dp rows on the surface-container role and on-surface text. Reuse the existing Material 3 `DropdownMenuItem` style, with no icon or separator. #1631 explicitly approves the additional row immediately after Channel info without a separate frame; surrounding pre-existing styling stays intact.

## Change

Add an unconditional Background tasks row after Channel info using `background_tasks_title`. Dismiss first, then invoke a defaulted `onBackgroundTasks` callback forwarded through `ThreadTopAppBar` to the existing screen-owned `backgroundTasksOpen` flag. This is local panel navigation, with no ViewModel event, new state, I/O, or failure mode. Actions and the pill retain their existing paths and panel logging. Overlaps with #1642 and `feature/1283-notice-placement` affect other blocks in `ThreadScreen`; keep this additive and local. Forecast: about 160 written lines, three production files, no exported types, two production consumers to update, two acceptance criteria; within all sizing limits.

## Testing strategy

First add failing shared screen assertions for the new menu row and its screen path. Cover channels/chats with mutation support on/off, immediate placement after Channel info and before memory installation, dismissal, and the empty/unreported panel readings with zero running tasks. Run existing overflow, top-bar, task-panel layout/interaction and task-pill coverage. Extend the existing rung-3 background-task scenario with the top-menu opener; dispatcher owns its live execution. Extend rung-4 scripted ping with the no-task top-menu path and run `scripted ping`. These existing e2e tests require the real host daemon and emulator; shared tests need neither. Run lint, assembleDebug, compileDebugAndroidTestKotlin, formatting and forced spotlessCheck before handoff.

## Documentation handoff

Pending documentation stage: record #1631's no-separate-frame decision in `app/src/androidTest/assets/design-1220/README.md`, so the added row is read as decided rather than drift.
