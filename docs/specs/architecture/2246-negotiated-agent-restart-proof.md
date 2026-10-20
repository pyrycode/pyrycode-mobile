# Negotiated Agent restart and settlement proof

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: the shared navigation/ownership assertions; five callers, one consumer opts into key-based reveal while preserving exact unmerged ownership checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_replyStaysUnderAgent` and `restartApp` supply genuine nested work and graph teardown.
- Salvage `ec63a4644033f88c915c15b1882b5b44613373a9`: `NegotiatedAgentRestartProof.kt` supplies C's original scenario; preserve the restored `TOOL_ROW_TAG` matcher and original carried commit attribution.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` retains its running/finished placement assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentReplyEvidence.kt` and `app/src/test/java/de/pyryco/mobile/e2e/AgentReplyEvidenceTest.kt`: separate held ownership from strict settlement.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: source ownership, authoritative identities and strict `settledReply` qualification.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `ThreadDestinationFactory.repository` is the decorated production destination, including negotiated support and cache.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `rebuildGraph` disposes registries while preserving on-device state.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`, `app/src/androidTest/assets/recorded/suite.json` and `scripts/e2e-emulator.sh`: native recording and scripted selections.
- `docs/recorded-tests.md`: genuine committed-source capture, strict comparison, independent replay and private evidence requirements.
- `docs/knowledge/features/conversation-cache.md`, `thread-screen.md`, `thread-screen-subagent-tool-rows.md`, `development-verification.md`: cached facts cannot establish negotiated support; nesting must follow daemon parent identities.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: capability negotiation, daemon thread updates and full thread items are the wire authority.

## Context

Prove one behaviour: a finished earlier-session Agent family survives native graph/cache reconstruction and transport replacement during a new-session send. The genuine scenario, deterministic twin and recording are proof of that same contract. Mobile #2245 and Core #3589 are merged. No production or visual change is planned; no decision record is needed.

Forecast: approximately 1,000 written lines including tests, plan and evidence; no exported production type, no signature migrations, four acceptance criteria and fewer than ten reject branches. The depth limit forbids further children. Main is current at `1a845e57d`; remote overlap includes historical #2112/#2121 and salvage #2175, plus #2311's separate reply helpers. Keep shared-file edits local and additive; none supplies a required dependency.

## Design

Reuse the genuine Agent scenario through a private proof body with a restart flag. Add a separately annotated deterministic twin using the retained scripted proof body; the original method keeps its own counted outcome. Extend the existing background-agent scripted selection to execute both methods.

Carry C's `proveNegotiatedAgentRestart` helper, resolving the production destination on each graph. Require currently Supported authoritative evidence, a completed root, exact tool parents and strictly settled child prose before resetting A to B. Await a certified persisted family, rebuild the graph, verify cache/registry identities changed while the saved family remained, and reopen the finished Agent with exact owned tool-row semantics. Reconnect after observing a real pending new-session send; require a different Supported connection, one delivered identity in session B and an empty pending set. Retain canonical identity, revision, order, parent, session and completion assertions throughout.

Carry the held-paragraph ownership helper without changing `settledReply`. Add adversarial unit probes for active prose, retired owners, mixed sessions, absent parents/tools and incomplete main replies. Register the new genuine method in the native recording wrapper and inventory without replacing existing assets or reviews. Capture through managed admission, review strict differences, and replay the committed and fresh recordings. Publish content-free receipts only; raw XML/logs/captures remain private and durable.

## State and concurrency model

No production state changes. Existing application scopes own socket/cache work. The helper's Main coroutine observes pending settlement before the synchronous send, yields before retiring its transport, and is cancelled in `finally`. Evidence is re-resolved after graph replacement and tied to the current Supported connection. Restart closes the old Activity before rebuilding Koin and closes the replacement Activity after proof.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Running child paragraph before tool completion | `AgentReplyEvidenceTest.heldParagraphProvesOwnershipWithoutSettling` plus retained running-Agent assertions |
| Retired/colliding owner or ambiguous session | held-evidence adversarial unit probes; existing `HarnessThreadEvidenceBindingTest` |
| Finished session A reset to B | both restart methods require original session/parent/order identities and finished state |
| Graph reconstruction over saved storage | both restart methods require fresh graph/cache objects and persisted certified family |
| Pending send followed by connection replacement | both restart methods require a new negotiated owner, exactly one delivered send in B and no residual pending send |
| Replay/duplicate or late acknowledgement | exact delivered count plus retained authoritative send/cache/query ownership regressions |

## Error handling

Missing support, missing parent attribution, incomplete prose, cache loss, duplicate delivery or retired ownership fail assertions. Do not infer nesting or reinterpret interrupted prose. Production defects require separate native blockers. Preserve every failed capture/comparison; no skip or zero execution counts as proof.

## Testing strategy

Write and observe failing held-evidence probes before the helper implementation. Run affected JVM/shared evidence classes, lint, APK assembly, instrumented compilation and forced formatting checks. Device-only proof needs real app storage, Activity/graph lifecycle and encrypted transport; Robolectric cannot establish those facts. Run the background-agent scripted scenario with two counted outcomes. Obtain the seven ticket-named live methods through managed admission on Linux, retaining source/binary/APK hashes, negotiated ownership receipts and counted XML. Replay affected recordings with strict outgoing comparisons. Full normal UI/scripted/JVM regression remains dispatcher-owned. Final merge, push, assembly and `scripts/pre-verify.py --gradle` precede PR handoff.

## Open Questions

None in the product contract. Fresh live/capture results remain pending until executed; documentation cannot create them.

## Revisions

2026-10-20: Native repair blocker #2340 is open and linked to #2246. On Mobile `3dc31adf82b1cee3a68ade457076e7a3582b65e4`, canonical-key reveal gets past merged-tree scrolling, but the exact unmerged owned-tool visibility assertion still fails (2 executed, 1 passed, 1 failed, 0 errors/skipped). `ThreadScreen` tags `background-agent-child` only for assistant paragraphs; expanded tool rows have no owning-Agent ancestor. Keep the red test and strict matcher. Repairing production semantics is outside this test/evidence ticket. The twin has proved graph/cache replacement, exact saved certification and a fresh admitted negotiated reopening, but has not reached pending-send reconnect/settlement. All seven genuine captures, the new recording, affected strict comparisons/replays, complete regression gates and final post-merge checks remain pending. Private XML/receipts are retained with hashes under `/work/Projects/pyrycode-mobile-agents/logs/evidence/2246/builder-20261020/scripted-owned-tool-failed/`. Resume on #2340's merged repair; this is a native bug dependency, not a further split.

2026-10-20: The next counted run passed graph replacement, offline checkpoint/coverage restoration and fresh negotiated reopening, then failed merged-tree scrolling to the tool tag (2 executed, 1 passed, 1 failed). The expanded list retained every owned key. Add an optional exact child key to the shared navigation proof solely for reveal; still require `TOOL_ROW_TAG` beneath the exact restored Agent child container in the unmerged tree with positive visible bounds. Five callers were checked; only this proof opts in and existing default assertions remain unchanged.

2026-10-20: The fourth counted run reached real graph/cache reconstruction, then failed because the new registry had not yet reconciled its persisted host and the destination therefore had no negotiated provider (2 executed, 1 passed, 1 failed). `RelayConnectionRegistry` loads its entries asynchronously. Await that existing reconciliation before resolving the pre-launch production destination, while still requiring Unverified support and exact saved checkpoint/coverage before Activity launch. No socket is opened to obtain the saved proof.

2026-10-20: The next counted attempt passed delimiter display but timed out in a later wait (2 executed, 1 passed, 1 failed). Resolve session B from the current admitted authoritative `SessionBoundary`, following the existing genuine session-reset test, instead of requiring the separate conversation-summary stream to qualify thread identity. Persisted-family readiness allows a newer revision of the same canonical ID; the subsequent exact session/turn/parent/order/content/finished-state assertions remain mandatory. Add rotation and persistence receipts to identify each remaining wait independently.

2026-10-20: With valid producer roles the second scripted run reached `finished_session_a`, then failed the lazy boundary-presence wait (2 executed, 1 passed, 1 failed). Agent navigation had left the viewport away from the newest row. Reuse the existing `awaitDisplayedSessionBoundary` assertion, which scrolls the reversed lazy list to its newest edge and requires the delimiter actually displayed. This retains the boundary assertion and its no-explanation check; it does not infer rotation from cached metadata.

2026-10-20: The counted scripted run executed both methods: the retained proof passed and the restart twin timed out before its finished-family receipt. The scripted assistant envelopes supplied message IDs but omitted `message.role`; the protocol's intrinsic child-text completion requires `assistant`. Add that genuine producer field to the two existing fixture fragments. Keep the strict inactive done/finished prose assertion unchanged. The failed 2-executed/1-passed/1-failed result remains retained privately.

2026-10-20: `AuthoritativeThreadCacheTest.interruptedBatchesKeepPriorCertificationWithNewestItems` proves that saved facts can be newer than the saved certified checkpoint. Keep those two assertions separate: require the exact persisted Agent family, then require the original saved checkpoint/coverage after reconstruction without upgrading either from item revisions. The reopened Supported route must still obtain current admitted evidence. This preserves held facts without certifying an interrupted batch or inventing negotiation/progress.

2026-10-20: The first scripted attempt executed zero methods and was gracefully cancelled after its nested Gradle command waited on the host hold already owned by the gate. The new Python wrapper had closed `PYRY_TEST_ADMISSION_FD`. Preserve that original validated descriptor in each nested harness process; do not create or replace admission context. `AgentScenarioReportsTest.test_nested_harness_keeps_the_original_admission_descriptor` failed before the repair and passes with it. Original gate cleanup retained isolated-daemon/relay diagnostics; the cancelled run supplies no passing native evidence.

2026-10-20: Add `managed-capture` to the existing recording driver because its managed verification path requires an already-reviewed fixture and cannot create this new scenario's first capture. It uses the same live selection policy, credential loading, device hold, build/install, owned command and emulator cleanup as `managed-verify`; existing strict comparisons are unchanged. It selects one method or an explicit file of at most seven named methods, passing that exact subset to admission and the existing capture/replay driver. It cannot select the full live inventory implicitly. Host regressions cover selection, skips, report survival and custody on failure. The local launcher enforces one Mobile test class per focused invocation, so run the affected classes separately.

2026-10-20: The scripted fixture has a single-use release fence, so selecting both methods against one daemon would contaminate the second outcome. `scripts/e2e-background-agent-scenarios.py` runs two ordinary harness invocations under the existing managed admission, each with a fresh isolated daemon and phone state, preserving both original XML reports. The normal background-agent gate therefore counts both methods. The restart callback now exposes the existing pre-launch seam so saved checkpoint/coverage and exact family identity are checked while support is still Unverified, before Activity launch; saved content cannot masquerade as a fresh negotiation.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md` records named live/recorded/scripted outcomes, counts, Linux implementation OS, revisions, package identities, route/owner receipts, reviewed differences and retained failures. Other operating systems are outside this ticket's verification scope.
