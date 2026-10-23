# Scripted Agent settlement certification

## Files read
- `DeterministicInteractiveStreamE2ETest.kt`: `backgroundAgentProof` observes settlement passively after the release reply renders; both methods share this wait.
- `QueueRecoveryAgentEvidence.kt`: `settledAgentAfterRecovery` requires exact durable family reconciliation and accepted coverage for every proven row.
- `ThreadEvidence.kt`: `harnessThreadEvidence` follows supported ownership without initiating synchronization.
- `NegotiatedThread.kt`, `RelayRepositoryCoordinator.kt`: `acquire` captures the supported connection and releases only that consumer's interest.
- `AuthoritativeThreadQueries.kt`, `AuthoritativeThreadStore.kt`: `reset` starts a newest-window query; ordinary catch-up certification preserves existing order coverage.
- `RemoteConversationRepository.kt`: `resetAuthoritativeThread` is the existing connection-owned recovery entry.
- `CachingConversationRepository.kt`: `observeAuthoritativeThread` owns a cache reader; assertions must observe the store passively.
- `RecoveredAgentFamilyTest.kt`, `QueueRecoveryAgentFixtures.kt`, `AuthoritativeThreadQueriesTest.kt`: existing strict negatives, family fixtures and real query decoder test patterns.
- `scripts/e2e-background-agent-scenarios.py`, `scripts/test_e2e_background_agent_scenarios.py`: the wrapper retains XML but loses the first phone/UTP output.
- `docs/knowledge/features/remote-conversation-repository.md`, `remote-conversation-repository-reads-and-thread-store.md`: accepted query terminals alone certify coverage; checkpoint progress is separate.
- `docs/knowledge/features/development-verification.md`, `thread-screen-testing-background-agent-viewport.md`: retain counted native evidence and existing placement/navigation assertions.

## Context
Live settlement extends beyond the earlier certified 8..27 window: child 29 and following reply 37 exist at checkpoint 40. Passive observation cannot repair certification. This test-only repair preserves application ordering and the strict evidence predicate. No decision record is needed.

One behaviour slice includes its causal regression and independently auditable two-arm evidence. Forecast: about 650 written lines, two new internal declarations, one consumer update, three acceptance criteria and no new production reject branches; within the scope ceilings. Shared deterministic-class branches #1766, #2091, #2104, #2175 and #2462 have historical/additive changes; #2462's current ping changes are separate. #2505's query-readiness work is not required. Keep this edit local to settlement.

## Design
Add a shared test helper `awaitCertifiedAgentSettlement` used by the native wait. Follow `ThreadSupport` with `flatMapLatest`, acquire the exact supported connection's query interest, observe its store, and return only through unchanged `settledAgentAfterRecovery`.

When an admitted settled family and exact following reply lie outside accepted coverage, request one same-epoch reset through the current `RemoteConversationRepository.resetAuthoritativeThread`. That existing recovery path clears stale certification and obtains a newest window through the production query decoder/store. Never assign coverage from live rows, checkpoints or rendering. Reset may advance generation; captured durable identity/content/placement remains mandatory. A connection replacement creates a new owned observation and retires the previous interest. No assertion retry or extra deadline is introduced.

Archive each wrapper arm's complete managed result directory (XML, phone/logcat, UTP), correlated isolated harness evidence, source/APK hashes, exit status and hash manifest in a unique run folder before launching the next arm. Restore the original two raw XML reports for the gate's counter. Preserve nonzero child status and invalid/missing results.

## State and concurrency model
The helper runs inside the existing `withTimeout(THREAD_TIMEOUT_MS)` and owns no persistent scope. `flatMapLatest` cancels observation on support replacement; `finally` closes the captured lease on success, failure, timeout and replacement. The reset is attempted once per supported connection observation, only while its host, conversation and snapshot are current. Main/background socket closure withdraws support through existing production routing.

## State transitions and identity reuse
| Event | Proof |
| --- | --- |
| Narrow initial range, later settled rows, fresh accepted window | `AgentSettlementRefreshTest.refreshCertifiesLaterRowsThroughCurrentSource` drives real query replies; batch alone cannot pass. |
| Connection replacement during certification, colliding IDs | `AgentSettlementRefreshTest.replacementRetiresOldInterestAndRequiresCurrentCertification` plus existing `RecoveredAgentFamilyTest` owner negatives. |
| Timeout/cancellation or unsupported connection | `AgentSettlementRefreshTest.cancellationReleasesInterest` and replacement probe; no retired lease may cancel replacement work. |
| Same-epoch reset reuses fact IDs | Existing `RecoveredAgentFamilyTest` generation/incarnation/content/placement negatives, with the refresh-path regression. |
| Second native arm overwrites managed outputs or fails | Wrapper regression checks exact first-arm artifact bytes and both independent outcomes. |
| Graph/cache replacement and later settlement reconnect | Existing restart native method remains intact and runs in the focused pair. |

## Error handling
Missing/unverified support cannot certify or initiate refresh. Rejected acquisition/reset fails within the original wait. Invalid, partial or stale query replies cannot satisfy the unchanged predicate. Wrapper retention failures fail the wrapper; historical failed captures remain failures.

## Testing strategy
Write the causal regression first against a passive helper and retain its red result, then enable reset/query refresh and run it green. Use a real `RemoteConversationRepository`, fake pump, production decoder and store with initial coverage 8..27, checkpoint 40, added child 29 and reply 37. Run all `RecoveredAgentFamilyTest`, `QueueRecoveryAgentEvidenceTest` and new helper probes. Write the wrapper survival regression red before repairing archival; run its complete focused Python class.

Preserve both existing native methods, all assertions, graph/cache replacement, restart/reconnect, child navigation and deadlines. These require a real device for graph/Keystore/cache and encrypted isolated-daemon transport. Run focused Linux `scripted background-agent`, require exactly two executed and passed methods, and retain every attempt's raw evidence privately. Zero Claude turns. Run lint, assembly, instrumented compilation, forced Spotless and final pre-verification checks. Configured scripted-all acceptance belongs to dispatcher/verifier; other OS verification is outside scope.

## Documentation handoff
Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Retained expansion budget evidence (#2390)” and “Negotiated Agent restart and settlement (#2246)”, with the stale-range cause, supported refresh contract and counted Linux evidence. Preserve historical failures and the missing first-arm phone trace; distinguish focused proof from pending configured acceptance.

## Open Questions
None.

## Revisions
- 2026-10-23: `CachingConversationRepository.observeAuthoritativeThread` registers a cache reader and replaces the previous reader's hydration ownership. Keep the proof on `route.store.observe`, as the passive evidence source does, and acquire only query synchronization interest. Added `proofDoesNotReplaceTheDestinationsCacheReader` to guard this ownership boundary; the query/settlement and original deadline contracts stay unchanged.
- 2026-10-23 (verifier rework): Move each arm's complete managed output directory into its archive before the next launch; preserve preexisting outputs separately without assigning them to an arm. An early second-arm exit must have no first-arm phone/UTP artifacts. Freeze both executable selections using the launcher's PATH defaults and reuse the shared provenance helpers. Strengthen the replacement probe to require successful retired reply delivery while its inbound collector remains alive, and prove it cannot certify the replacement. These repair the existing evidence contract without adding a deliverable; forecast remains below 800 written lines, with no production changes or new exported declarations. Apply and force-check formatting for the new shared helper.
