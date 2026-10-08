# Repair the owned Agent run's chrome reveal

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerTarget` relocates in the drawing viewport, applies one ScrollBy correction and guards the tap center.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentProseScreenTest.kt`: owned-run and long-prose fixtures preserve attribution and collapse proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent` fails while revealing the opened run, before its close tap; fixture/preference cleanup must remain.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: other consumers retain the existing helper contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ChromeAwareThreadList` uses reverse layout and a chrome-aware relocation spec.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md` and `development-verification-compose-evidence.md`: ownership differs from run identity; drawing-viewport display does not prove a chrome-clear physical target.
- `docs/e2e-interactive-stream.md`: existing rung-3 prose proof and rung-4 background-agent twin.
- `docs/specs/architecture/1904-background-agent-run-selector.md` and `1867-agent-navigation-run-toggle.md`: preserve child ownership and explicit closed/open/closed transitions.
- Initial #1948 gate stderr: the opened control ends at y=146..189, center=167.5, with header bottom=182 and composer top=1540. This establishes reveal failure before tapping, but alone cannot distinguish incomplete correction from later movement.

## Context

Restore the existing harness proof without a product change. No decision record is needed. The diagnosis must include measured pre/post correction evidence, not inference from the same-tree rerun.

## Design

Reproduce the covered expanded owned control with existing long-prose screen fixtures and instrument content-free geometry around the helper's relocation/correction. Fix the demonstrated harness cause with the smallest compatible change. Preserve the chrome guard, owned matcher, one physical tap per transition, exactly-one nested prose, continuation and cleanup. If the evidence instead requires product or daemon work, return for refinement rather than expanding scope.

Sizing: approximately 250–350 written lines including regression and evidence, zero production files, no new exported API or caller migration, three acceptance criteria and one deliverable. Only shared helper/screen tests and, if necessary, local diagnostics in the named live method change. Remote branches touching the live file are #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888, #1900 and #1948; prefer leaving that file unchanged. No shared-helper or prose-regression overlap exists.

## State and concurrency model

Use the existing Compose test clock and synchronized geometry reads. Diagnose ScrollBy completion and layout changes explicitly. Add no application job, state or dispatcher; do not replace the failure with retries, sleeps or a longer timeout.

## Error handling

Covered/missing owned targets and failed closed transitions remain assertion failures. Evidence includes only bounds, chrome edges, requested/consumed displacement and static stage names; never message content or identifiers.

## Testing strategy

Write and execute the reproducing shared regression before changing the helper. Run BackgroundAgentProseScreenTest, BackgroundAgentBlocksScreenTest and ThreadInlineQuestionTest for compatible interactions. Check the regression on the managed device if the asynchronous geometry depends on instrumentation. Run the focused background-agent scripted scenario and a development live run of the named method, recording fresh selected methods and executed/failed/skipped counts. Existing e2e tests require a device for daemon/relay IO. The dispatcher owns full live and scripted-all acceptance after verification; a focused pass does not satisfy that acceptance criterion.

Run lint, assemble, Android-test Kotlin compilation and Spotless. Push after the final main merge, then run the full unit/shared suite, assemble and pre-verify with the PR body.

## Open Questions

- Which measured operation leaves the close control covered: an incomplete correction, wrong correction or later movement? Resolve with red/green regression and sanitized evidence in Revisions before handoff.
