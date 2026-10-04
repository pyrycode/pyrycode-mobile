# Background tasks leaves the composer Actions menu

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ComposerAction` and `footerMenu` own the rows and availability gates.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `openMenu` and Actions selection pass the count and open the panel; the top menu and pill already open it independently.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/FooterMenuTest.kt`: row order and availability assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt`: Actions ordering and dispatch coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt`: menu helpers and existing panel assertions, including unreported and empty rosters.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt`: pointer routing and Actions coexistence.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillKeyboardDeviceTest.kt`: real IME reachability requires device execution.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: background-task scenario and `openBackgroundTasks` helper callers.
- `docs/knowledge/features/thread-composer-footer-actions-menu.md`: preserve command absence and mutation gates; finished tasks may disappear from the roster.
- `docs/knowledge/features/thread-overflow-menu.md`: Background tasks is unconditional and dismisses the menu before opening the panel.
- `docs/e2e-interactive-stream.md`: existing rung-3 harness and dispatcher-owned live acceptance.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-5938

Inspected context and screenshot show a compact vertical Actions overlay above the composer, using body-small text and primary/on-primary theme roles. The frame still has Background tasks (0); the ticket's 2026-10-03 decision overrides that row only. Keep the existing overlay styling and three remaining rows.

## Change

Remove `ComposerAction.BackgroundTasks`, the count parameter and label formatting in `footerMenu`, and the corresponding count argument and selection branch in `ThreadScreen`. Actions retains Reset session, Compact session and Knowledge capture in order with existing mutation, connection and command availability gates. Move panel-test helpers and all E2E `openBackgroundTasks` callers to the count-free top menu; retain panel assertions and pill entry coverage. Keep the existing live method name for acceptance tracking, prove Actions omits the row, and prove the pill disappears on completion before opening the panel through the top menu. No task state, transport, new types or failure modes change.

Overlaps: #1642, #1660, #1665, #1682, #1689, #1690, #1691, #1693 and #1695 share screen/footer/E2E files; edits are local to independent menu and background-task blocks. Estimated total written work: about 220 inserted/deleted lines including this plan, no new exported types, fewer than ten consumer sites requiring simultaneous signature updates, two acceptance criteria, no new reject branches.

## Testing strategy

First update `FooterMenuTest` expectations and observe the old implementation fail. Run focused `FooterMenuTest`, `ComposerActionAvailabilityTest`, `ThreadViewModelComposerActionsTest`, `ThreadComposerFooterTest`, `BackgroundTaskPanelTest`, `TaskCountPillTest` and `ThreadOverflowMenuTest`. Preserve panel readings, menu dismissal, button ordering and pointer assertions. Run the affected keyboard device method for real IME coverage and the scripted stream scenario for thread integration; no new fixture is needed for this removal. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. Dispatcher must run a fresh full live suite (`Live tests: all` because the shared live helper changes), record executed/failed/skipped counts, and confirm `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` executed and passed. Live acceptance remains pending at builder handoff.
