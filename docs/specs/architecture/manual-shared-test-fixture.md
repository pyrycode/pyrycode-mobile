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

## Validation

At source `85e7b20859a6eef96e277ef962b6c7b66bc3a5e0`, the affected JVM checks executed and passed 80 methods with no failures, errors or skips. The native recorded selection executed and passed the four exact methods below with no failures, errors or skips. Both maintained gates exited zero.

- `RecordedMobileSuiteTest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation`
- `RecordedMobileSuiteTest#interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`
- `RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_replyStaysUnderAgent`
- `RecordedMobileSuiteTest#interactiveTurn_otherConversationAttentionPills_waitingAndFinished`

Raw JVM XML and output are retained in `build/manual-shared-fixture/green`. Native XML, per-method phone logs, command output and built APK hashes are retained in `build/dispatcher-tests/ui-rh2qed69`. The native gate used the clean committed source. The original failing JVM assertion remains in `build/manual-shared-fixture/red`.

An earlier formatting attempt and the next compile attempt ran no test. The formatter caught a long line. Compilation caught an existing decorator regression still using the renamed gate. Both were corrected before the passing run. Repository documentation and pre-verification guards passed.

Four selected scripted methods also executed and passed with no failures, errors or skips. They cover reconnect replay, refusal recovery, context-overflow compact recovery and reply suggestions. The maintained gate built the isolated daemon v0.42.0 source and exited zero. Its raw XML and output are retained in `build/dispatcher-tests/scripted-all-fkg6z3g3`. The harness retained each scenario's daemon and relay logs in the agents repository's evidence directory. The harness reports the custom binary's embedded daemon revision as unavailable. The clean source revision and binary hash are retained separately in the gate identity record.

The red, green and both phone gate bundles are also preserved under `$AGENTS_REPO_PATH/logs/evidence/manual-shared-fixture-20261023-85e7b2085` for review outside the temporary checkout.

## Accepted recovery integration

The final workflow read found that 2486 had independently merged and 2491 was being verified separately. Their states were preserved. Eight other tickets still have manual-repair holds: 2351, 2356, 2360, 2368, 2469, 2476, 2493 and 2494.

Accepted main `f1f9c3adb` merged cleanly into this branch as `1436e17548b815cc940352b73647b6aca520b259`. The combined query, connection, recovery and lifecycle selection executed and passed 97 JVM methods. Formatting passed. The exact scripted reconnect method then executed and passed once against the same released daemon source. Both gates exited zero, with no failures, errors or skips. No implementation change was needed for this integration.

Raw integrated JVM XML is in `build/manual-shared-fixture/integrated-jvm`. The phone XML, logs and source/binary identities are in `build/dispatcher-tests/scripted-soiasyw1`. A copy is preserved under the evidence bundle's `integrated-1436e1754` folder. The earlier four recorded and four scripted passes retain their original source and APK identities.

Configured dispatcher acceptance and independent review remain pending. Remaining manual holds and independent work retain their current state. Focused passes do not establish one cause for every historical flake.
