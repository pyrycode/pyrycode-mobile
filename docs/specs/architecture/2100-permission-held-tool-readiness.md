# Permission-held running-tool harness reliability (#2100)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the named scenario, `holdToolOnPermission`, `peerStep`, `awaitConnected`, chat identity and creation helpers; preserve prior/current privileged peer custody and the three display assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowConnection.kt`: existing addressed-host authenticated wait, contrasted with the compatibility banner.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: preserve timeout causes and content-free peer diagnostics.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt`: real registry with controlled absent/handshaking hosts and selection assertions.
- `docs/e2e-interactive-stream.md`: permission-held running-tool contract and #1898's separation of reproduced readiness defects from historical attribution.
- `docs/knowledge/features/development-verification.md` and `development-verification-emulator-evidence.md`: counted execution and correlated phone/daemon evidence retention.
- PR #2096: pending `HostConnection.kt` extraction and MCP repair. Reuse its identical addressed-host helper without changing Send now or MCP.

## Context

The historical 30000 ms timeout does not identify its operation. The earlier retained prefix reports this method passing; later phone results are missing. Retained daemon logs alone cannot establish that a status assertion failed. Investigate with fixed operation labels and fresh correlated evidence; historical causation stays unresolved unless recovered evidence establishes it.

One candidate harness defect is already visible: `holdToolOnPermission` waits on selected-host banner state before creating a chat on the explicitly addressed host. The banner hides Idle and follows selection; it does not prove that host's daemon authenticated. Controlled real-registry tests must demonstrate this defect before repair. No production or wire changes are planned.

## Design

Add test-only `PermissionHeldStage` and `permissionHeldStep` for prior-peer admission, list arrival, connection readiness, conversation snapshot/creation/identity, composer readiness, current-peer admission, send, permission arrival, waiting display, privileged approval/dismissal, running display, turn completion and label removal. Emit static stage/status and bounded connection/peer state names, never identifiers, prompts or payloads. Timeout assertions preserve their cause; ordinary failures and cancellation propagate.

Initially extract the existing selected-banner wait into `awaitPermissionHeldConnection`, used only by `holdToolOnPermission`, to test the actual scenario predicate. After failing controls, delegate it to `awaitHostConnection` (identical to #2096's pending helper). Both relay and daemon legs must be Connected for the addressed host, at the original 30000 ms deadline, without changing selection or retrying. Keep chat creation explicitly addressed to that same host.

Shared-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092 and #2099 edit other scenario blocks. Local additive changes do not depend on them; identical HostConnection content can merge with #2089.

## State and concurrency model

No production state or jobs change. Existing instrumented `runBlocking` waits use their existing deadlines and the registry's hot status flows; unit controls use `runTest`. Existing peer redial scope, prior-peer `use`, and observing-peer `finally` cleanup remain intact. Diagnostics hold no mutable scenario state.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Prior privileged peer closes, current peer reuses pairing | Existing named live scenario; keep both authenticated opens and peer cleanup. |
| Unrelated selected host absent while target authenticated | `permissionHeldReadinessIgnoresStoppedSelectedHost`. |
| Selected peer connected while target absent, then target reconnects | `permissionHeldReadinessWaitsForItsOwnHostEvenWhenSelectedPeerIsConnected`. |
| Target relay connected but daemon handshake held, then authenticates | `permissionHeldReadinessWaitsForDaemonHandshake`. |
| Idle or missing target | `permissionHeldReadinessIdleKeepsDeadline` and `permissionHeldReadinessRejectsMissingHost`. |
| Initial conversation snapshot absent at its deadline | `PermissionHeldConversationsTest.missingInitialSnapshotKeepsConversationsStageSnapshotAndTimeoutCause`. |
| Existing snapshot repeats without the newly created chat | `PermissionHeldConversationsTest.missingNewChatKeepsChatIdentityStageSnapshotAndTimeoutCause`. |
| Repository replaced while the newly created chat is awaited | `PermissionHeldConversationsTest.chatIdentityWaitsForNewIdAcrossRepositoryReplacement`. |
| Permission hold, approval, tool running, turn_end | Existing named live method with fixed stages; no wait or assertion weakened. |

## Error handling

Missing target fails explicitly. Timeout diagnostics identify the operation and snapshot registration, selection match, relay/daemon class names, repository availability and peer link state; preserve original causes. No blanket retries, skips or deadline increases.

## Testing strategy

Write readiness controls first against the original predicate and retain counted red XML, then green XML. Test diagnostics for ordered stages, value forwarding, coroutine/Compose timeout attribution, unchanged ordinary failures/cancellation and lazy snapshots. Run the full real-registry class, diagnostics class and existing running-tool status screen tests. The live scenario remains device-only because it needs real sockets, a privileged peer and real Claude execution. Obtain a fresh correlated phone/daemon capture for localization; the dispatcher owns the fresh configured full live gate and its method counts/provenance, which focused success cannot replace. Run the deterministic `tool` twin if available. Run lint, APK build, Android-test compilation, formatting and final pre-verify after merging main.

## Open Questions

- Resolved: no failing phone capture was recovered. Historical timeout causation remains unresolved; fresh passing captures localize all operations without attributing that failure.
- Resolved: all five controls failed against the actual original predicate, then passed against the addressed-host wait.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, permission-held running-tool section — record the demonstrated repair, fixed stages, tested revisions/counts and eventual full live gate evidence; distinguish controlled reproduction from unresolved historical attribution.

## Revisions

- 2026-10-12: Five real-registry controls executed and failed against the extracted original selected-banner predicate. They demonstrate wrong-host blocking, wrong-host false admission, handshake false admission, Idle false admission and missing-host false admission. Implement the proposed addressed-host wait; retain the original deadline and selection. The diagnostic tests also ran red (5 failures) then green (5 passes).
- 2026-10-12: Fresh baseline capture executed/passed the named method (1/1, no failures/errors/skips), with all 15 stages completed. Phone output and daemon snapshots correlate prior/current authenticated peer admission, chat creation, permission arrival and turn completion; the three display assertions passed. The baseline does not reproduce the historical timeout. Historical operation/cause and any MCP relationship remain unresolved; the readiness repair rests on controlled reproduction, not historical attribution. Preserve full-live acceptance as dispatcher-owned and request a builder return for counted XML/provenance artifacts.
- 2026-10-12: The repaired named method executed/passed (1/1, zero failures/errors/skips) at Mobile `9ce53a429`, daemon `8091a4730` with dirty-build flag recorded, Claude 2.1.280. All fifteen fixed stages completed, with phone/daemon artifacts retained. Focused success does not satisfy the required dispatcher full live gate. Full registry, diagnostics and existing running-tool/status classes executed/passed 46 tests without failures/errors/skips. The other `holdToolOnPermission` consumer remains the pre-existing ignored manual elapsed scenario; it compiled, and no manual live pass is claimed.
- 2026-10-12 (verifier finding 1): The shared `hostConversationIds` discarded its timeout cause before the Conversations and ChatIdentity stages could enrich it. Extract only the permission-held reads into `PermissionHeldConversations.kt`, preserving `ConversationFilter.All`, `firstOnLive` redial semantics, the nonempty identity difference and its single-id requirement. Use `withTimeout` at the unchanged `LIST_TIMEOUT_MS` (30000 ms), letting `permissionHeldStep` retain the original cause and add the lazy connection/peer snapshot. The unrelated conversation-list helpers and consumers stay unchanged. Both actual extracted read paths failed at the lost-cause assertion before this repair; deadline checks cover 29999/30000 ms, and positive controls preserve empty initial snapshots and identity reads across repository replacement. Rechecked in-flight overlap adds #2102, which edits a separate scenario block and is no dependency.
- 2026-10-12 (rework validation): `PermissionHeldConversationsTest` executed four controls: two failed before the timeout repair and all four passed after. The complete registry/read/stage/running-tool/status selection passed 54 tests with zero failures/errors/skips. The fresh deterministic `tool` twin passed 1/1, zero failures/errors/skips and zero real Claude turns. Full live acceptance and the three dispatcher artifacts remain pending with the dispatcher; no new focused live run or historical-causation claim is made.
