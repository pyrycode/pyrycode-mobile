# Manual shared test fixture repair

## Resumed 2026-10-23

Juhana requested manual consolidation of current-query completion, connection ownership and ordered shutdown. He then paused this work until a new daemon version is available. Juhana explicitly resumed the work after daemon v0.42.0 was published. The isolated daemon source is 5ba6b8dacc0916533df8b8d9c60090190a9e6f4d. No automatic resume or release watcher was created.

## Saved work

Branch `fix/shared-native-test-lifecycle`, worktree `/private/tmp/mobile-shared-fixture`. Started from main `bf539138d`. Retained terminal lifecycle repair from `feature/2491` and foreground teardown repair from `feature/2360` were merged without discarding either regression set. A test-file conflict retained both sets of methods. Neither source branch was changed.

The combined lifecycle checks passed 46 methods: RecordedTerminalLifecycleTest 5, RecordedTerminalFailureTest 3, RecordedPhoneTeardownTest 9 and SuiteConnectionsTest 29. No failures, errors or skips. Command output is `/tmp/mobile-shared-fixture-lifecycle.log`. This does not establish full native acceptance.

An unfinished test-only query-completion prototype observes actual accepted queries and downstream consumption through the existing phone pump wrapper. It extends the acquisition gate to reject old cached certification while a newer query is pending. The initial-attention fixture now feeds those observations. It has not been migrated to the model reopening scenario.

The controlled stale-checkpoint regression was started against the original acquisition predicate. Juhana requested pause during Kotlin compilation. The exact owned admission process received SIGTERM and completed its cleanup with exit 143. No failing assertion or red/green result was obtained. Output is `/tmp/mobile-shared-readiness-red.log`. The prototype predicate was restored after stopping. All new query-readiness work is unverified and must be reviewed before use.

## Resume

- Confirm the new daemon release and the effective source/binary selected by the test worker. A merged daemon change alone does not establish deployment.
- Read current orchestrator state and preserve all existing approval holds, dependencies, counters and evidence.
- Reassess overlap with any independently completed work. Reuse the preserved current-source and query-certification repairs.
- Review pending-query cancellation, owner/generation replacement and the tracker lifetime before completing the prototype.
- Run the controlled cached-snapshot regression against the original predicate, retain its actual failing assertion, restore the candidate and run the affected query/lifecycle checks.
- Integrate the smallest shared acquisition operation into the model reopening and other demonstrated consumers. Keep exact payloads, causal delivery, full tape consumption and existing deadlines.
- Obtain focused native evidence, then configured shared-fixture coverage and review. Nothing in this checkpoint authorizes a claim that all historical flakes share a cause.

## Scoped holds

Protected manual-repair holds were applied to Mobile 2351, 2356, 2360, 2368, 2469, 2476, 2486, 2491, 2493 and 2494. Existing independent daemon, attention-timer and other repairs were left alone. Do not clear the holds merely because this branch exists.

## Current implementation

Current main is integrated without rewriting the preserved history. The original snapshot-only acquisition wait now has a controlled failing result: one executed and one failed with “old certified content must not release a newer active query”. Raw XML and output are retained under `build/manual-shared-fixture/red`.

The candidate generalizes the attention acquisition gate into `ThreadAcquisitionGate` and reuses it for the model scenario's X reopen. `ThreadQueryReadiness` passively observes the actual phone pump, accepted queries and downstream-consumed replies. It reuses the production decoder and existing current-source reader. Partial batches, another conversation, refused or malformed work and cancelled collectors cannot establish completion. Scope closure and a retired negotiated owner cancel the acquisition. The original assertions, requests, recordings and deadlines stay intact.

Focused query and lifecycle validation is pending the current shared host admission. Native acceptance will use the isolated released daemon rather than the older saved worker source. Existing independent repairs and all ten scoped holds remain.
