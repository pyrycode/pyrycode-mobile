# #2244 — Opt-in connection-owned thread support

## Files read

- `data/repository/RelayRepositoryCoordinator.kt`: `activeConnection`, `onConnection`, `teardownActive`, `liveRepository` own authenticated connection lifetime.
- `data/repository/RemoteConversationRepository.kt`: `authoritativeLiveEnabled`, `observeThreadSnapshot`, `requestHistory`, `requestAttentionHistory` gate queries and legacy reads.
- `data/repository/NegotiatedThread.kt` on salvage `feature/2175`: retain only `ThreadSupport`, without provider/screen members.
- `di/HostConversationSource.kt`: `HostConversationConnection`, `HostConversationSnapshot`, `reconcile`, `update` preserve exact host/bundle identity.
- `di/RelayConnectionRegistry.kt`: `retryHost` retains foreground and exact-bundle ownership.
- `di/RelayConnectionFactory.kt`: `create`, `RelayConnectionBundle` keep the production authoritative host empty and replay cursor supplied.
- `ui/conversations/components/ConversationTreeRows.kt`: `TreeHostRow` already owns app-update priority, bounded names and compact controls.
- `ui/conversations/list/ChannelListScreen.kt`: `treeHost` routes host-specific reconnect, edit and Play Store actions.
- `res/values/strings.xml`: existing host-update and reconnect copy.
- `NegotiatedThreadActivationTest`, `TreeHostRowThreadMissingTest` on salvage; existing `RelayConnectionFactoryTest`, `HostConversationSourceTest`, `ConversationTreeRowsTest` supply patterns and regression coverage.
- `docs/knowledge/features/relay-repository-coordinator.md`, `remote-conversation-repository.md`, `dependency-injection-host-conversation-source.md`, `channel-list-screen-tree-and-controls.md`: current Open gating, exact-bundle fencing, retained host snapshots and app-update treatment must survive.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run on Robolectric; no device-only test is needed.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: capability negotiation and daemon thread supplied-delivery contracts are wire authority.
- Salvage spec `2175-activate-negotiated-threads.md`: native recovery explains why advertising thread before screen binding breaks production.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=672-3895

Read the design context and screenshot using Figma's design-to-code skill. The compact host row has an error-coloured `titleSmall` name, existing server/chevron/edit assets and a `bodySmall`/`onSurfaceVariant` caption indented under its name, retained while folded. Reuse the existing row geometry and assets; the ticket intentionally substitutes “Update this host's Pyrycode daemon to use it.” and the existing host-named reconnect control for the app-update caption/download action. App-too-old retains priority.

## Context

Split A of #2175 exposes daemon capability verdicts without activating production. #2245 owns the negotiated provider, screen/cache suppression and production switch; #2246 owns genuine captures. No new persistence, wire shape or dependency. No decision record is needed.

Overlaps: #1947 changes context-usage clearing in the remote repository; #2098 adds attention-preview diagnostics in the host source; #2175 is the salvage source. These are independent blocks or explicitly carried hunks, not dependencies.

## Design

Carry the verdict, legacy-refusal and row hunks from `3592d67f1` (`feat(thread): activate required negotiated daemon threads (#2175)`) and retain that original text in the implementation commit body. `NegotiatedThread.kt` contains only the sealed `ThreadSupport` verdict: Unverified, Supported(connection), Missing(connection). A coordinator with a non-empty `authoritativeHostId` switches one collector over `activeConnection` then that connection's `pump.state`. Only authenticated Open grants a verdict; both `thread` and `interactive` are necessary. Missing persists until the next acknowledgement; Supported retires to Unverified.

Use the existing coordinator monitor and transport identity to reject a retired collector's writes. Teardown retires Supported synchronously, including permanent close. The per-repository capability supplier also checks the connection child scope and current transport so a retained repository cannot issue a query at the replacement edge before collector cancellation runs. Existing authoritative store/query seams suffice; no negotiated provider is added.

With authoritative host configured, snapshot collection logs refusal and emits its existing snapshot without backfill; history and attention reads throw fixed app-authored `IllegalStateException("legacy_content_request_refused")` before sending. Query open/page keep their existing capability gate and retirement cancellation. Metadata/control traffic stays available. Production hello, replay cursor and factory authoritative host configuration stay unchanged.

Add a default-false `threadMissing` flow/snapshot field, collected under the host source's existing generation guard, and pass it from `treeHost` into `TreeHostRow`. A Missing row uses the error name/caption and reconnect plug even while connected. Registry retry closes that exact supervisor before retrying only when Missing; preserve foreground/disposal/bundle checks and normal reconnect supervision. Existing app-update action/terminal behaviour wins.

## State and concurrency model

The verdict collector and derived Boolean StateFlow belong to the existing coordinator SupervisorJob and injected dispatcher; close cancels them. Repository inbound/query jobs remain connection-child owned. Host-source collectors belong to the existing entry job. No new ViewModel, screen event, dispatcher, cache writer or lifecycle owner. Background closes the supervisor; supported connection loss un-verifies, and missing remains sticky. Restored facts do not enter negotiation.

## State transitions and identity reuse

| Event | Unit/shared probe |
| --- | --- |
| All granted capability combinations; cached store already populated | `NegotiatedThreadActivationTest.onlyBothAuthenticatedCapabilitiesGrantSupport` |
| Supported disconnect/non-open, then a fresh capable reconnect | `reconnectReevaluatesVerdictAndReopensOnlyOnTheNewConnection` |
| Missing disconnect, handshaking, subsequent capable acknowledgement | `missingVerdictSurvivesDisconnectUntilACapableAckReplacesIt` |
| Direct replacement, late old Open, retained query callback, permanent close | `retiredConnectionCannotRestoreItsVerdictOrSendQueries` |
| Two capable hosts reuse conversation/item IDs, then one loses support | `collidingHostsKeepSeparateStoresAndVerdicts` |
| Missing beside Supported; snapshot/history/attention before/after acknowledgement | `missingThreadHostSendsNoContentRequests` |
| Connected host-specific retry, recovery and normal failure supervision; background, stale bundle, disposal | `ThreadMissingHostRetryTest` |
| Host flow projection, replacement, removal and recovery | `ThreadMissingHostRetryTest` through real `HostConversationSource` |
| Fold/unfold, recovery and app-update priority | `TreeHostRowThreadMissingTest` |
| Production remains legacy despite capable fake pump | `productionConfigurationRemainsLegacy` plus unchanged codec/session/factory tests |

## Error handling

No new remote error type. Legacy methods follow their existing throwing contract with a fixed refusal code; snapshot collection remains observable without a send. Unsupported authoritative queries return false. Negotiation logs only `event=thread_negotiation verdict=supported|missing|unverified`; refusal logs only `event=legacy_content_request_refused`. No host, item, epoch, capability list or content enters these logs.

## Testing strategy

Write a behavioural legacy-snapshot refusal test first and observe the unwanted backfill on main, then carry implementation and remaining probes. Adapt salvage real-coordinator/fake-pump tests to existing repository entry points without provider/cache/ViewModel dependencies. Fake pumps retain their chosen Open state after close to challenge retirement; record envelopes and certify colliding store items with existing batch fixtures. Registry probes use real supervisors/coordinators and fake transports/pumps; MockK only assembles factory/bundle holders, without mocking repository behaviour. Shared row probes assert caption, fold/recovery, host-labelled controls and app-update priority.

Run affected negotiation, retry, host source, coordinator, authoritative-query, remote-history, production hello/factory and row/list tests on Linux. Run lint, assembleDebug, shared/android Kotlin compile, spotlessApply and forced spotlessCheck. Push before the final merge/assemble/pre-verify gate. Complete suite and normal recorded coverage remain dispatcher-owned; A has no live acceptance requirement. Estimated total written work: 1,100–1,350 lines, four new verdict declarations, one consumer requiring update, four acceptance criteria and fewer than ten rejection branches; within the hard ceilings.

## Open Questions

None. Code confirms `retry()` alone cannot redial an already connected supervisor; close-before-retry is required for Missing.

## Documentation handoff

Pending for documentation stage:
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, “Host row reconnect control (#840)”: daemon caption, folded behaviour, reconnect and app-update priority.
- `docs/knowledge/features/remote-conversation-repository.md`: connection-owned/sticky verdicts; A remains opt-in and #2245 activates production. Record supplied Linux results; documentation does not produce evidence.

## Security review

**Verdict:** PASS

- Trust boundaries: `PumpState.Open` is the existing authenticated acknowledgement boundary. MUST FIX addressed in design: saved facts cannot grant support; derive only from the current transport/pump, requiring both capabilities.
- Tokens/credentials: no generation, storage, revocation or token rendering changes. Verdict toString and logs reveal only static words.
- Files/storage: no path, persistence, backup or cache implementation changes. Populated store facts never establish support. #2245 owns screen/cache suppression when production activates.
- Android attack surface: no exported components, intents, providers or WebView changes. Caption is an app resource; existing `boundedRowText` bounds host labels/descriptions.
- Cryptography: Noise IK, TLS, key storage, counters and rekey remain untouched.
- Network/I/O: SHOULD FIX implemented by unconditional opt-in legacy refusal and existing supported-query gate. Metadata/control remain usable; no limits or timeout changes.
- Errors/logs: fixed exception/event codes, no received text or identity interpolated. Add a log-capture assertion for the new event allowlist.
- Concurrency: MUST FIX addressed in design: switching collectors alone cannot fence synchronous callbacks before cancellation. Check transport/child-scope ownership in the capability supplier, guard verdict writes under the coordinator monitor, and retire verdict on teardown. Registry retry keeps its existing lifecycle/bundle lock; no extra lock ordering or suspension is introduced.
- Threat model: a malicious relay may delay/drop connections, leaving Unverified or sticky Missing with no content requests. Hostile capability combinations fail closed. No new daemon text crosses the UI boundary. Rooted token theft and screenshot/IME leakage retain existing protections, outside this change's surface.

**Reviewer:** builder, self-review per `builder/security-review.md`  
**Date:** 2026-10-17
