# Context usage circle (#1660)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ContextSegment`, `contextUsageStep`, `FooterTextRow`, and `ThreadComposerFooter` own rendering and geometry.
- `app/src/main/res/values/strings.xml`: `cd_context_usage` and related descriptions supply spoken readings; add a warning description.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt`: retain reading updates, unavailable, inertness and sheet agreement assertions through accessibility.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterWidthTest.kt`: replace obsolete text wrapping assertions with fixed-circle geometry and pointer routing at compact widths.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ContextUsageStepTest.kt`: move threshold boundaries to 70 and 85.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: migrate direct footer assertions and `awaitContextSegment` from text to content descriptions, retaining reconnect and real-reading guards.
- `docs/knowledge/features/thread-composer-footer-context-usage.md`: one computed `ThreadRunConfig.contextPercent` supplies both surfaces; a footer percentage alone does not prove a context request fired.
- `docs/knowledge/features/thread-composer-footer.md` and `thread-composer-footer-testing.md`: trailing controls reserve fixed width; preserve expanded touch bounds and input clearance.
- `docs/knowledge/features/development-verification-gates.md`: shared tests use Robolectric NATIVE graphics for real text and pixels.
- `docs/e2e-interactive-stream.md`: existing rung-3 footer scenario and deterministic harness contracts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Inspected design context and screenshot on 2026-10-04: the left group has a 4 dp inset and a 16 dp gap from Context to Actions. Context is a 15 × 15 dp circle top-aligned in a 15 × 16 dp slot. Retain existing Actions typography, chevron and trailing controls. Draw the dynamic ring from the computed reading with a 2 dp stroke, primary-container track and primary/warning/error used arc per the ticket, rather than importing the static example.

## Change

Replace the trailing context text and wrapping layout with a fixed circle before Actions in the weighted left group. Reserve circle width and gap, letting Actions use the remaining width, and bottom-align the 16 dp visual slot with Actions' visual band above its touch overflow. Draw the track first, then an arc starting at -90 degrees with a negative sweep proportional to the percentage; null and zero draw no used arc, and 100 draws a complete ring. Classify below 70 as normal, 70–84 as warning and 85–100 as high. Accessible content descriptions retain the percentage and distinguish warning/high; null remains unavailable. No click action, new state, coroutine, dependency or data-source change is needed; Run configuration keeps its existing percentage.

Overlaps are local and additive: #1642, #1682, #1689, #1690, #1691, #1693 and #1695 share the live-test file; #1732 and the historical #1283 branch share strings. #1682 adds peer readiness/diagnostics to the reopen scenario; this ticket changes only its final accessible-reading assertion, without depending on that work.

Sizing: one rendering deliverable, four acceptance criteria, approximately 550 written lines including tests/plan and removed obsolete layout, zero new exported types, no changed public signatures, and no new error branches; within all limits.

## Testing strategy

Test first: threshold unit tests and migrated accessibility assertions must fail against the old renderer. Shared NATIVE-graphics tests sample real ring pixels at 0, 69, 70, 84, 85 and 100, checking top origin, counterclockwise fill, constant dark track, stroke geometry, unavailable and updates. Width tests cover 320 dp at default and 150% font scale with the real composer gutter, 15 dp ring geometry, 16 dp slot alignment, 4 dp inset, 16 dp visual gap, and pointer taps for Actions/Attach/Run configuration. Retain `ThreadFrameTest` input-clearance coverage and run existing footer, sheet and context ViewModel tests.

Run focused `testDebugUnitTest`, lint, assembleDebug, androidTest compilation, spotlessApply and forced spotlessCheck. Run scripted ping as the relevant deterministic integration check. The existing live scenario remains device-only because it needs the host daemon and a real Claude turn; migrate its semantics, plus reopening and reconnect assertions. The dispatcher must run a fresh full live suite (`all`) and report executed/failed/skipped counts and that `interactiveTurn_pingPrompt_footerShowsContextUsage` ran and passed. This builder does not claim that pending suite passed.

## Revisions

- 2026-10-04: existing device `ThreadDesignCaptureTest.captureFooter` also waits on the retired text. Migrate that await and strengthen its circle alignment checks; retain its real IME, picker and configuration pointer routing. Run the two footer capture methods with their full-image device harness, which sets the required viewport. This adds one test file and remains below the written-work limit. #1619 and #1647 also touch that capture class in unrelated tool/queue and reader methods; no dependency or footer-block overlap.
- 2026-10-04: the first compact hardware snapshot showed the dark ring despite the available warning semantics; the subsequent keyboard snapshot showed the expected yellow arc. For full-image runs requiring real bars, fence each capture on yellow pixels inside the circle as well as its 84% description. ATD retains geometry-only evidence because its framebuffer can be blank. This ensures snapshots prove the rendered reading rather than only an earlier semantics update.
- 2026-10-04: the Figma left group uses centre alignment. The enlarged-font geometry assertion exposed that bottom-aligning the fixed Context slot put it below the taller Actions row's centre. Centre the Context measurable within the left group (both children include the same bottom overflow), retaining top alignment of the 15 dp circle inside its 16 dp slot and the existing trailing-control bottom alignment. This supersedes the initial slot bottom-alignment description at enlarged fonts.
