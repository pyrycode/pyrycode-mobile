# #2383 — Independent scenario report assertions

## Files read

- `scripts/test_e2e_background_agent_scenarios.py`: `AgentScenarioReportsTest.run_cases` collects reports in filesystem order; the independent-outcomes test assumes positions.
- `scripts/e2e-background-agent-scenarios.py`: `main` launches retained then restart and preserves each report independently.
- `docs/knowledge/features/development-verification-gates.md`: focused script proof stays separate from dispatcher-owned complete gates.

## Change

Match report cases by method identity after asserting the exact expected multiset of names, so missing or duplicate cases fail. Keep launch order and overall failure assertions, require retained to fail and restart to pass, and exercise both sorted and reverse-sorted `Path.rglob` enumeration in the named test. Production reporting stays unchanged. No in-flight feature branch overlaps this test file. One behaviour; approximately 40 written lines including this plan, no exported types or production consumer changes, two acceptance criteria, no new state or error branches.

## Testing strategy

First force both enumeration orders with the existing positional assertions and observe the named test fail. Then repair the assertions and run `AgentScenarioReportsTest` on Linux, retaining revision and executed/passed/failed/skipped counts in the PR. Review cardinality protection against missing and duplicated cases. Run required formatting, lint, assembly and final `pre-verify.py --gradle` checks. Complete script gates belong to the dispatcher; macOS is outside this ticket's verification scope.
