# Repair the owned Agent run's chrome reveal

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: `questionAnswerTarget` relocates in the drawing viewport, applies one ScrollBy correction and guards the tap center.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentProseScreenTest.kt`: owned-run and long-prose fixtures preserve attribution and collapse proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent` historically failed while revealing the attributed paragraph, before preparing its close tap; fixture/preference cleanup must remain.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: other consumers retain the existing helper contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadMessageList` uses reverse layout and a chrome-aware relocation spec.
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

## Revisions

2026-10-08 — The pinned revision's stack identifies the attributed paragraph reveal, not the following opened-run close reveal. The failed rectangle's 343px width also matches that paragraph; the run control is 974px wide on the managed phone. Correct the initial reading in Context: the historical failure precedes the close preparation and does not establish a missed toggle.

2026-10-08 — Resolved the harness race with `lateOwnedProseGrowthIsRemeasuredBeforeClosingItsRun`. With native text at 420dpi, the real screen follows the newest end and a later owned paragraph grows between the helper's geometry sample and correction. The early owned paragraph moves from `(148,296)-(491,339)` to `(148,121)-(491,164)`, while chrome remains `182..854`. The old helper's requested shift remains zero, and its chrome guard fails before the close tap (one executed, one failed, zero skipped; retained red XML in `/tmp/builder-1973/reveal-red.xml`). Normal underlap, drawing-edge clipping and the focused device probe all passed without growth; ordinary late row insertion while reading preserved the anchor and did not reproduce the fault. This distinguishes subsequent layout movement from wrong scroll direction or a missed toggle.

The live scenario continues receiving owned child updates: its progress predicate accepts any task progress, not an observed HTTP hold. The existing historical stderr lacks pre/post geometry, so it cannot identify which particular upstream update produced that occurrence. Two fresh diagnostic live runs passed one executed, zero failed, zero skipped each; those passes are not the diagnosis. The controlled production-screen reproduction establishes the stale-measurement failure mechanism without asserting an app or daemon defect.

Replace the single assumed-final correction with at most three fresh geometry measurements/corrections. Remeasure both chrome edges and the same owned target after each displacement; return only once the physical center clears the measured chrome, otherwise retain the failing guard. This is bounded geometry feedback, with no additional click, sleep or timeout. It supersedes the initial no-retry wording: changing layout requires a fresh displacement, not repetition of the old request. Preserve the helper signature through a defaulted content-free evidence sink. The live method logs only geometry for its paragraph and close-control reveals. Regressions retain closed/open/closed visibility and assert collapsed prose is absent from the lazy list's key mapping, so disposal cannot impersonate closing. Size remains below 300 written lines, with no production change or exported production API.

The repaired reproduction sees the same initial zero shift and subsequent covered bounds, then measures those new bounds and requests 65px. The paragraph lands at `(148,186)-(491,229)`, center 207.5px, below the 182px header edge. The close follows a separately guarded single pointer tap on the owned run and removes the prose key. All affected shared tests passed: BackgroundAgentProseScreenTest 9, BackgroundAgentBlocksScreenTest 15, ThreadInlineQuestionTest 14; zero failures or skips. Both live transitions now use explicit center pointer taps. Fresh dispatcher full live/scripted acceptance remains pending after verification.

The final growth fixture scopes `DeviceConfigurationOverride.ForcedSize` to 320×480dp so the same growth overflows on JVM and device. It positively asserts that the injected growth actually covers the measured paragraph. A single-measurement negative control on this final fixture failed one executed test, zero skipped, with bounds `(143,147)-(478,189)` against header 177 and composer 862. Restoring bounded remeasurement retains the guard and green close proof. The ordinary fixture remains unchanged for all existing tests.
