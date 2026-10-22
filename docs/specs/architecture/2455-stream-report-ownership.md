# Stream scenario report ownership (#2455)

## Files read

- `scripts/e2e-stream-scenarios.py`: `main` selects device reports, validates each method and retains XML across scenario launches and interruption.
- `scripts/test_e2e_stream_scenarios.py`: `StreamScenarioReportsTest.run_cases` reuses `TEST-run.xml`; existing tests cover independent failure, skip, missing report, interruption and admission forwarding.
- `scripts/e2e-emulator.sh`: stream delegation and installed instrumentation report output use the device-selected report directory.
- `docs/knowledge/features/development-verification.md`: owning verification topic and current evidence boundaries.
- `docs/knowledge/features/development-verification-gates.md`: assert report identities as a multiset, independently of filesystem enumeration order.
- `docs/knowledge/features/development-verification-test-scheduling.md`: restrict report collection to the selected device path and preserve process failure checks.
- Original verifier evidence for #2373/PR #2454: run `36c833f91ea567fc3a3247bef09ab2e54ad5deceb4393bbf2bf4be0c9dde1fec`, digest `3b3d6332da7e1f8389f0bc44ec127dfa8d603fca3cd6e7deb1fd8e35d90bda9f`, downloaded under `/tmp/builder-2455/original-ticket-evidence/`. Its scripts stderr retains 211 tests, one report-count failure, zero errors/skips. Its agent log records the identical baseline failure after 49 focused passes.

## Context

A report whose filesystem mtime precedes `main`'s wall-clock start is omitted from collection. Because omitted XML is neither retained nor removed, the second scenario can overwrite the first report at the same path. This accounts for a concrete report-loss mechanism; it does not establish the originating Linux host's clock behaviour. Original failures remain failed evidence.

This is one behaviour slice: reliable per-execution report accounting, including its causal regression. No product UI, wire contract, dependency or decision record changes. The refiner's ~220-line estimate is consistent with a forecast of roughly 230 written lines including this plan and tests. Three acceptance criteria, no new exported types, no consumer migration or new reject branches. The analogue #2367 added these scripts in 163 lines. No in-flight feature branch overlaps either script after fetching origin.

## Design

Before each scenario's subprocess launches, remove existing `TEST-*.xml` only within the selected device's report directory. After that execution, collect all matching XML there without comparing timestamps. The existing host/device admission gives the harness exclusive execution ownership; this boundary makes even future-dated old XML ineligible and allows genuinely new XML with an earlier mtime.

Preserve the current exact-one-testcase, expected-class/method and failure/error/skip checks, subprocess status, admission forwarding, per-scenario archive and temporary retention. Copy and remove each collected report before another launch can reuse its filename. Finally restore collected reports on normal completion or interruption. No other scenario harness changes.

## State and concurrency model

Synchronous subprocess execution remains sequential. The existing admission descriptor is forwarded unchanged. `retained` owns reports already collected; the temporary directory and `finally` restoration retain their current lifetimes. No new jobs, threads or application state.

## State transitions and identity reuse

| Event | Contract and regression |
| --- | --- |
| A newly written XML has an mtime before launch | `test_earlier_report_mtime_keeps_both_independent_outcomes` covers both passing reports and passing-first/skipped-second, asserting identities and outcomes. |
| Both launches reuse `TEST-run.xml` | The causal test and existing `test_two_independent_outcomes_keep_the_first_failure` preserve both independent outcomes, regardless of enumeration order. |
| A previous execution leaves future-dated XML | `test_future_dated_preexisting_report_cannot_satisfy_an_execution` rejects a missing first report and excludes old XML. |
| The second launch writes nothing | `test_missing_second_report_is_not_a_pass_or_duplicate_first_execution` rejects reuse of the first outcome. |
| The second launch interrupts | `test_interruption_preserves_the_already_counted_outcome` retains the first XML, including when its mtime precedes launch. |
| Child execution inherits admission | `test_nested_harness_keeps_the_original_admission_descriptor` preserves the original descriptor. |

## Error handling

Keep nonzero child statuses and report validation failures unsuccessful. Extend focused tests to explicitly cover error, zero-count, wrong-method and wrong-class XML alongside the existing failed, skipped and missing outcomes. Exceptions continue to propagate after the existing restoration; no retries, sleeps or freshness tolerance.

## Testing strategy

Write the controlled-timestamp regressions first and run `StreamScenarioReportsTest` against unchanged production code, retaining the expected red with revision and macOS identity. The fixture sets explicit mtimes and a controlled launch clock; the causal case makes the first XML older and the second newer, reproducing the original report-count failure. Also cover both XML files being older than launch.

After the repair, run the entire affected Python class, retaining counted raw green output and checking archived XML as well as restored reports. These mocked subprocess tests need no emulator. Dispatcher owns complete scripts discovery and configured gates on macOS. Linux remains outside this ticket's verification scope. No native application method changed, so no scripted device scenario is needed for the Python ownership repair.

Run required formatting, lint, APK assembly and final `scripts/pre-verify.py --gradle` checks; keep Gradle output visible and push before final checks. The PR will retain original evidence references plus controlled red/green counts and commands.

## Open Questions

None. The selected-directory execution boundary replaces timestamp selection; no product decision is missing.
