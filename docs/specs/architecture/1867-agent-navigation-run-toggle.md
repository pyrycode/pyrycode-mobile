# Verify the settled Agent child run after navigation

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished` retains lifecycle and list-order proof but lacks expansion checks; `interactiveTurn_backgroundAgent_replyStaysUnderAgent` supplies the owned child selector.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` already proves scroll-only navigation, but ends with the settled run open.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerTarget` moves a target clear of overlapping thread chrome before a tap.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocksScreenTest.kt`: `markerOnlyScrollsAndLeavesACollapsedRunCollapsed` covers navigation and opening, not the next close tap.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentProseScreenTest.kt`: the owned-control and long-block tests distinguish child ownership from off-screen disposal.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `expandedRuns`, the marker effect and the `ThreadRow.ToolRun` toggle keep navigation separate from expansion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldToolRuns` keeps the root separate and keys its run by the first child tool; `listKey` exposes child membership independently of composition.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRunRow.kt`: `ToolRunRow` labels its clickable Row with expand/collapse semantics.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-subagent-tool-rows.md`, `development-verification.md` and `development-verification-gates.md`: current navigation preserves collapse; device IO requires instrumentation, while shared interaction coverage runs on Robolectric.
- `docs/e2e-interactive-stream.md`: the background Agent ladder and historical missing close proof remain the evidence boundary.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=795-7178 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=726-5573 (context and screenshots read 2026-10-07).

The launch marker has a status/description and primary-colour Go to agent link. The separate Agent root sits above indented children; the outlined Using tools header has a chevron and trailing status, with Material bodyMedium text and background/primaryContainer roles. Preserve these existing components and tokens; this is interaction proof without visual changes.

## Context

Restore one missing deliverable: current settled navigation followed by explicit opening and closing of the owned child run. A passing current run cannot establish the historical cause or credit #1907 with fixing it. No decision record is needed.

## Design

Derive the child run id from the first loaded direct child tool, never the Agent root. Keep the existing lifecycle, running navigation and reversed-list position checks. After settlement establish the closed run, navigate, assert the root is revealed and expansion remains closed, then use single physical pointer taps on the chrome-clear owned control to open and close it. Request a distinct final child paragraph so positive visibility can be checked under its Agent ownership tag.

Use a small shared test helper for expansion-label matching and the single-tap open/close proof in the live scenario, deterministic twin and focused screen regression. Before positive visibility assertions scroll the owned paragraph into composition. Check every loaded child message key using `IndexForKey`: collapsed children must be absent from the list, not merely disposed off screen. Record content-free diagnostics through a caller-supplied sink: hashed Agent/run identities, stage, click action/expansion state, visible bounds and center relative to header/composer, child-key membership and composed owned-paragraph visibility. The PR ties those logs to revision and fresh gate run identity. If a failure leaves tap delivery ambiguous, investigate callback/state evidence before any app repair; do not infer delivery from the absence of a state change.

No production change is justified before a reproduced defect. Any demonstrated cause and its regression will be recorded in Revisions. Do not add retries or sleeps.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1731, #1766 and #1833 touch other live/scripted methods; #1895 changes a different shared screen test. Read their diffs; no dependency or shared restructuring is required.

Sizing: approximately 400–450 written lines including plan, tests and diagnostics, one deliverable, four acceptance criteria, at most one production file, no exported production types or signature migrations, and no state-machine reject branches. This fits the refiner's estimate and all hard limits.

## State and concurrency model

Retain existing screen-local saveable expansion state and marker effect. Tests wait for semantic/repository state with existing bounded timeouts; each control is tapped once. No new coroutine job or dispatcher.

## Error handling

Missing ownership, wrong expansion label, covered center or retained child keys fail the scenario with content-free diagnostics. Preserve fixture release, preference restoration and peer teardown. Do not log message text, inputs, credentials or decrypted frames.

## Testing strategy

Write the focused settled navigation/open/close shared regression first. Run it with the stale root-id selector and observe failure, then use owned child identity. Exercise long child content so disappearance alone cannot pass for collapse. Run BackgroundAgentBlocksScreenTest, BackgroundAgentProseScreenTest, ToolRunFoldTest and ToolRunCollapseTest, plus the focused `background-agent` scripted gate with fresh counted XML. The live and scripted scenarios remain device-only because they drive daemon/relay IO and real pointer routing. Attempt a diagnostic development live reproduction through the credential-owning gate, with no direct credential access. The dispatcher owns the full live and scripted-all gates and their acceptance counts; leave needs-real-claude intact.

Run lint, assemble, Android-test compilation and Spotless; after the final main merge push and run the whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle` against the PR body.

## Open Questions

- Does the current live symptom reproduce? Resolve from fresh diagnostic execution; preserve the historical-cause limitation even when it passes.

## Revisions

2026-10-07 — The stale root-id negative control executed one test and failed at the owned run lookup, as intended. With the correct child id, the long-block probe delivered its open tap (all 26 child keys entered the list) but disposed the header before an on-screen label wait could succeed. Await the list-membership transition first, then scroll the existing owned header into composition once and assert its expansion semantics. This fixes the proof's disposal assumption without an app change, additional tap, scroll retry or sleep. The scripted twin obtains actual child message ids from its repository snapshot rather than assuming fixture text equals generated assistant message identity.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “Background Agent follows the newest end (#1783)” and the close-after-navigation open-item note, with the established outcome, current scroll-only then explicit open/close sequence, and fresh live-gate evidence/counts. Retain any unresolved historical-cause limitation. If app behavior changes, update the affected navigation/expansion description in `docs/knowledge/features/thread-screen-subagent-tool-rows.md`. Documentation records evidence produced during development and by the dispatcher; it does not produce live proof.
