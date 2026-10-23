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

## Rework plan for 2505, 2026-10-23

Written before implementing the review repair. Preserve the original failing gates and saved FAIL verdict at 12aeedf5a. The queued reopening gap is a new blocking finding. The pre-acquisition gate must retain its source, connection, generation and certified-snapshot safeguards.

1. Reproduce the missing interleaving with the real AuthoritativeThreadQueries queue: certify X, leave Y active, acquire X, and prove that X cannot finish while its new request is queued or merely dispatched. Keep the immediate-dispatch regression.
2. Carry the pre-acquisition completion receipt through the real acquisition. The post-acquisition wait must consume a different successful request for X from that same current source. It must not accept X's earlier receipt, another conversation, partial work or retired sources. Preserve the existing shared timeout.
3. Retire the current registry holder on explicit wrapper close and inbound completion or cancellation. Use source identity and the same registration lock so a late old close cannot clear its replacement. Keep waiting flows connected to their holder. Add deterministic retention checks without garbage-collector timing.
4. Run controlled failing regressions, then affected unit and lifecycle checks, format, documentation and pre-verification checks. Run the affected recorded model scenario through the maintained focused gate. Preserve raw results. Full configured acceptance stays with the dispatcher.
5. Push normal commits to feature/2505 and return the existing PR to independent review through the protected operator. Preserve unrelated ticket states and failed gate evidence. Reconcile the tracking comment for inherited 2476 without presenting it as an accepted stage result.

## Integrated verification checkpoint

At e2f788962, all 22 focused query/acquisition methods passed. The full JVM/build invocation executed 5453 methods with one notification logging-cleanup ConcurrentModificationException. Its single exact same-source comparison passed. The original full run remains red and retained. No causal link to the query repair is established.

The exact recorded model method then failed twice on the same source with a generic cancelled acquisition and pending replay work at step 29. This is not accepted as a pass or assigned to the historical model flake. Before changing further behavior, add distinct cancellation reasons to identify readiness, owner, generation, scope or delegate refusal. Preserve both failures and rerun only after that diagnostic change.

## Observer-cleanup race diagnosed

The diagnostic phone run at 0f9d27d1a identifies the added final exact-receipt check as the cancellation cause: completion changed before acquisition. A newer completion can arrive while the gate joins cancelled observers. Rejecting that advancement is incorrect. Simply using the old receipt would also permit that newer pre-acquisition result to satisfy the post-acquisition wait.

Before implementing the correction, reproduce a real queued query starting during owner-observer cancellation. Then capture the current completion synchronously after observer cleanup. If a query is active, repeat the wait within the same original timeout. Keep scope, owner, generation and certified-snapshot checks. Acquire only after that final marker, then require a different completion from the same source. Preserve the diagnostic failure and both earlier native failures.

## Corrected acquisition boundary

At code a4ab9eb84620e9e7c5d12ebaf949877c4fb784b0, the marker is captured synchronously after observer cleanup. If a query is still active, the same bounded acquisition wait continues. Scope, current owner, generation and the certified snapshot are rechecked. The delegate acquires only after that marker. Post-acquisition completion must still differ from the marker and belong to the same current source and conversation.

The controlled observer-cleanup regression executed once and failed before correction at the active-query acquisition assertion. After correction, all 23 focused query and acquisition methods passed with no failures, errors or skips. Formatting passed separately from compilation. The exact recorded model method then executed and passed once on a4ab9eb84. The prior two e2f788962 cancellations and the diagnostic 0f9d27d1a cancellation remain retained. This new pass establishes the affected recorded scenario, not complete configured acceptance.

Private rework evidence is under $AGENTS_REPO_PATH/logs/evidence/2505/manual-rework-20261023. It retains the original verifier bundles, controlled red/green XML, the full JVM red and exact comparison, every native attempt, phase records, phone logs, source lineage and built APK hashes. Debug APKs intentionally embed dev rather than a source revision. No independent installed-byte readback was performed for these author checks. Preserve this limit and establish the required effective identities during complete configured acceptance.

The earlier baseline failure for Agent restart is now recorded on its existing tracker: https://github.com/pyrycode/pyrycode-mobile/issues/2476#issuecomment-6369555768. This records the original evidence only. It grants no accepted stage result and changes no ownership, hold or acceptance obligation.

The additional exact attention-navigation method executed and passed once at docs head 5eb74bede, with no failures, errors or skips and gate exit zero. Executable files are identical to a4ab9eb84. Its XML, phone log, completed phase record and separate built APK hashes are retained. Both affected native callers now pass. These author checks return the existing PR to normal independent review and complete configured acceptance. Actual normal Documentation pickup remains the requested handoff boundary.
