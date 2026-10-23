# Background Agent report ownership (#2481)

## Files read

- `scripts/e2e-background-agent-scenarios.py`: `main` selects, validates and retains each execution's XML, restoring collected reports in `finally`.
- `scripts/test_e2e_background_agent_scenarios.py`: `AgentScenarioReportsTest.run_cases` reuses one report filename; current checks cover failure retention, both enumeration orders, skip, missing output, interruption and admission forwarding.
- `scripts/e2e-stream-scenarios.py`: `main` demonstrates the merged #2455 execution boundary without timestamp comparisons.
- `scripts/test_e2e_stream_scenarios.py`: `StreamScenarioReportsTest` demonstrates controlled timestamps and rejection fixtures.
- `docs/knowledge/features/development-verification.md` and `development-verification-gates.md`: focused checks require counted outcomes and identity multisets independent of enumeration order.
- `docs/knowledge/features/development-verification-test-scheduling.md`, “Stream report execution ownership (#2455)”: exclusive admission permits selected-directory cleanup; future-dated old XML must not satisfy a launch.
- `docs/specs/architecture/2455-stream-report-ownership.md`: nearest shipped analogue and causal loss mechanism.

## Context

The launch clock and filesystem mtime need not agree. A new report below the launch timestamp is neither collected nor removed, so the next execution overwrites it at the same filename. Controlled timestamps will demonstrate this mechanism; they do not recover the historical host's precise clock behaviour. The original Linux gate and invocation-39 comparison remain failures.

One independently useful behaviour ships here: reliable per-execution Agent report retention, together with its regression. Forecast approximately 180–220 written lines including this plan and tests, consistent with the refiner's ~180-line estimate and #2455 analogue. Three acceptance criteria, zero new exported types, zero consumer migrations and no additional reject branches. No in-flight feature branch overlaps either scoped file after fetching origin. No product UI, wire change, dependency or decision record.

## Design

Immediately before each scenario subprocess, remove existing `TEST-*.xml` within the selected device report directory. Collect all matching XML after execution without a wall-clock comparison. The inherited admission descriptor supplies exclusive execution ownership and remains forwarded unchanged. Do not broaden cleanup to other device directories.

Keep exact-one-testcase, expected-class/method, failure/error/skip and child exit-status checks. Copy each report unchanged into existing temporary retention and remove its source before the next execution can reuse the filename. Keep `finally` restoration on completion and interruption. No shared helper or sibling harness changes are needed.

The fixture will explicitly set report mtimes and freeze the old launch clock independently. It will retain original XML bytes for exact comparison with restored reports and cover invalid outcomes without launching a daemon or emulator.

## State and concurrency model

Subprocesses remain synchronous and sequential. Existing `retained` paths and temporary-directory lifetime remain unchanged. No new threads, jobs, flows or application state. Cleanup happens inside existing admission ownership.

## State transitions and identity reuse

| Event | Contract and test |
| --- | --- |
| First report predates launch; second reuses its filename | `test_earlier_report_mtime_keeps_both_independent_outcomes` preserves both identities and original outcomes for passing/passing and passing/skipped, in both enumeration orders. Also test both reports predating launch. |
| Prior execution leaves future-dated XML | `test_future_dated_preexisting_report_cannot_satisfy_an_execution` excludes stale XML and rejects a missing first execution. |
| First execution fails and second passes | `test_two_independent_outcomes_keep_the_first_failure` retains the first status and original bytes in both enumeration orders. |
| Second execution writes nothing | `test_missing_second_report_is_not_a_pass_or_duplicate_first_execution` rejects reuse of the first report. |
| Second execution interrupts | `test_interruption_preserves_the_already_counted_outcome` restores the first report even with an earlier mtime. |
| Child inherits admission | `test_nested_harness_keeps_the_original_admission_descriptor` checks descriptor forwarding. |

## Error handling

Preserve nonzero child status and rejection of failed, errored, skipped, zero-count, missing and wrong-method/class XML. `test_invalid_report_cannot_count_as_success` adds explicit coverage for existing rejection branches. Exceptions still propagate after restoration. No sleeps, retries or freshness tolerance.

## Testing strategy

Write tests first. Against unchanged production, freeze launch time at 2,000,000,000 ns, set the first report to 1,000,000,000 ns and the second to 3,000,000,000 ns: omission followed by overwrite must reproduce the lost report. Retain this red output, then implement the boundary and run the entire affected `AgentScenarioReportsTest` class for green with source revisions, OS, exit statuses and executed/passed/failed/error/skipped counts. Check original XML bytes, identities and outcomes, not only status.

This worker runs Linux; the current ticket-OS policy supersedes the refined body's stale macOS wording. macOS is outside verification scope. These are synthetic Python harness checks; no Android scenario or native application test changes. Dispatcher owns complete scripts discovery and remaining configured gates on Linux; focused proof does not replace those gates. #2388 retains its independent consumer repair and unfinished feature gates.

Run required formatting, lint, debug assembly and final `scripts/pre-verify.py --gradle`, keeping Gradle output visible and pushing before final checks. Preserve original #2388 gate evidence references and causal red/green logs in the PR.

## Open Questions

None. Selected-directory execution ownership follows the already merged sibling repair.
