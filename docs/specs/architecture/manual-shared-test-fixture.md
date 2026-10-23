# Manual shared test fixture repair

## Resumed 2026-10-23

Juhana requested manual consolidation of current-query completion, connection ownership and ordered shutdown. He then paused this work until a new daemon version is available. Juhana explicitly resumed the work after daemon v0.42.0 was published. The isolated daemon source is 5ba6b8dacc0916533df8b8d9c60090190a9e6f4d. No automatic resume or release watcher was created.

## Saved work

Branch `fix/shared-native-test-lifecycle`, worktree `/private/tmp/mobile-shared-fixture`. Started from main `bf539138d`. Retained terminal lifecycle repair from `feature/2491` and foreground teardown repair from `feature/2360` were merged without discarding either regression set. A test-file conflict retained both sets of methods. Neither source branch was changed.

The combined lifecycle checks passed 46 methods: RecordedTerminalLifecycleTest 5, RecordedTerminalFailureTest 3, RecordedPhoneTeardownTest 9 and SuiteConnectionsTest 29. No failures, errors or skips. Command output is `/tmp/mobile-shared-fixture-lifecycle.log`. This does not establish full native acceptance.

The first stale-checkpoint regression attempt was stopped during compilation at Juhana's pause request. No assertion ran in that attempt. The resumed attempt below establishes the failing result. The pause changed no retained ticket state or source branch.

## Scoped holds

Protected manual-repair holds were applied to Mobile 2351, 2356, 2360, 2368, 2469, 2476, 2486, 2491, 2493 and 2494. Existing independent daemon, attention-timer and other repairs were left alone. Do not clear the holds merely because this branch exists.

## Current implementation

Current main is integrated without rewriting the preserved history. The original snapshot-only acquisition wait now has a controlled failing result: one executed and one failed with “old certified content must not release a newer active query”. Raw XML and output are retained under `build/manual-shared-fixture/red`.

The candidate generalizes the attention acquisition gate into `ThreadAcquisitionGate` and reuses it for the model scenario's X reopen. `ThreadQueryReadiness` passively observes the actual phone pump, accepted queries and downstream-consumed replies. It reuses the production decoder and existing current-source reader. Partial batches, another conversation, refused or malformed work and cancelled collectors cannot establish completion. Scope closure and a retired negotiated owner cancel the acquisition. After the real acquisition, the model scenario also waits for that destination's own query before Back can cancel its producer. Both waits share the existing timeout. The original assertions, requests and recordings stay intact.

Focused query and lifecycle validation is pending the current shared host admission. Native acceptance will use the isolated released daemon rather than the older saved worker source. Existing independent repairs and all ten scoped holds remain.
