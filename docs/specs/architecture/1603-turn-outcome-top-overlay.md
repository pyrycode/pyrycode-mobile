# Turn outcome in the thread top overlay

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`: `TurnOutcomeIndicator` and `turnRecoveryNotice`; rendering changes, classification remains.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt`: reuse its Error treatment and whole-pill action.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadStatusArea`, `statusArm` and overlay placement; remove outcome selection from the band.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: append the outcome to the existing right-aligned stack.
- `app/src/main/res/values/strings.xml`: `thread_recovery_context` becomes the combined label.
- `docs/knowledge/features/turn-outcome-indicator.md`: held recovery state clears on send and next activity; preserve its existing writers.
- `docs/knowledge/features/thread-screen.md` and `thread-top-overlay.md`: overlay shares header clearance and gutters without reserving message space.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadRecoveryNoticeTest.kt`, `ThreadStatusBandTest.kt`, `ThreadTopOverlayTest.kt`, `RunningToolIndicatorTest.kt`, `ScriptedTurnOutcomeTest.kt`: recovery action, status geometry, notice ordering and repository lifecycle coverage.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/StatusArmTest.kt`: status selection precedence.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: context-overflow daemon proof.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` and `app/src/androidTest/assets/design-1220/README.md`: real-screen capture and retained evidence workflow.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-3992

Design context and screenshot inspected on 2026-10-05. One icon-free, X-free Error pill reads “Context too long - Compact”, with errorContainer/error colors, bodySmall text, 8dp horizontal and 4dp vertical padding, and 6dp corners. At 412×892 its visible frame is 172×24 at top 97dp, right gutter 20dp; reuse the overlay's 12dp notice gap and permit longer billing/sign-in text to wrap.

## Change

Render `TurnOutcomeIndicator` as one shared Error `NoticePill` in `ThreadTopOverlay`, after the existing notices. The context pill's whole surface runs the existing Compact callback only when the published menu permits it; billing and sign-in retain agent-specific copy and remain inert. Remove the outcome arm and its inputs from private status rendering and `statusArm`, leaving active/connection precedence and the idle glyph intact. Keep outcome classification, ViewModel clearing, stopped-turn rows and Reset session unchanged. No new type, state, coroutine, failure mode, dependency or wire contract is needed.

Overlap checked against #1642, #1735, #1747, #1753, #1775 and #1782: local additive overlay wiring and distinct rendering edits can merge without a design dependency. #1747 separately measures the Offline target independently from subsequent notices; this ticket does not restructure that target. Repository search finds two direct `statusArm` consumers (the screen and test helper), despite codegraph reporting none. Forecast is approximately 450–650 written lines, no new exported declarations, fewer than ten consumers and five acceptance criteria.

## Testing strategy

First change recovery tests to demand the combined pill, idle status and whole-surface pointer routing, and observe their red result. Then run focused recovery, overlay, status-band, tool, scripted outcome, attribution, component geometry, classifier and ViewModel lifecycle tests. Update obsolete status/outcome geometry and precedence expectations to the overlay contract. The deterministic `context-overflow` scenario must tap the combined pill, show `/compact` reached the daemon and show recovery advice cleared. Device capture is required for real screen pixels: run `ThreadDesignCaptureTest#rowAndNoticeFramesAt412By892` at 412×892 on the full API 35 device, retain its fresh XML, metadata, outcome capture and Figma comparison images. Existing unrelated captures need not be refreshed in this change. Run lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck after formatting. Dispatcher owns the fresh full live suite; it must include and pass `InteractiveStreamE2ETest#interactiveTurn_reconnect_slashCommandsAndCompactStillWork`, recording executed/failed/skipped counts.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/thread/index.md`, “Turn outcome — `685:3992`”: update the verdict from fresh evidence for placement, copy, icon absence and whole-pill action. Preserve stopped-row spacing under #1608 and other owning-ticket mismatches.
- `docs/knowledge/features/turn-outcome-indicator.md` and owning thread feature documentation: describe overlay placement and unchanged lifecycle/Compact path.
- Owning thread documentation and evidence verdict: record dispatcher full live-suite counts and confirmation that the named reconnect/Compact method ran and passed after that evidence exists.

## Revisions

- 2026-10-05: the fresh hardware capture measures the context pill at 176×24px, x=216..391 and y=121..144 in the 412×892 framebuffer. Removing the real 24px status-bar offset gives the design's y=97 placement, with the same 20px right gutter. Android's shared Roboto `bodySmall` label is 4px wider than Figma's 172px instance. Keep the shared typography and 8/4dp padding and hug the full copy rather than force a width that would wrap it or compress its glyphs; record this small platform text-metric deviation in the PR and documentation handoff. Native Robolectric graphics are required for exact text geometry; legacy graphics falsely wrapped the label to 43dp height.

- 2026-10-05 (PR #1789 verifier rework): Compact inherited the overlay's 36dp minimum touch height after moving out of the status band. Wrap only the actionable context notice in a top-end-aligned, at-least-48dp clickable box, leaving the icon-free visible pill at 24dp and its existing top/right placement. The box extends downward; recovery is last in the stack, so it preserves the preceding 12dp visible gap and avoids preceding expanded targets. Keep unavailable Compact and billing/sign-in notices inert with their original layout. Let `NoticePill` disable its inner semantics merge only when the surrounding Compact target owns the merged action; the label and action then stay on one accessibility node. Separate visible-pill geometry assertions from merged action bounds. Add native-graphics pointer tests for the 48dp bounds and taps below the visible pill at both bottom edges, alone and after Re-pair, proving exactly one intended callback and non-overlapping action targets. Re-run recovery/overlay/layout tests, the deterministic context-overflow scenario, real-screen capture and focused build/format gates. No new exported symbol, state, failure mode or wire contract; this repair adds approximately 100 written lines and stays within the ticket's sizing limits.
