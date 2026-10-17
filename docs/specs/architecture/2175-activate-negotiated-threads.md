# Activate required negotiated daemon threads

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt`: `HelloClientPayload` default `capabilities` omit `thread`; `last_event_id` is still supplied by `RelayConnectionBundle`.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt`: `RelayConnectionBundle` wires `lastEventId = { coordinator.replayCursor.latest }` and never passes `authoritativeHostId`; `create(record)` is the only production entry and knows `record.serverId`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `activeConnection`, `onConnection`, `teardownActive`, `liveRepository`, the shared `authoritativeThreads` store that outlives connections, and `supportsBackgroundTaskStop` (the per-ack capability pattern to mirror).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `authoritativeLiveEnabled`, `openAuthoritativeThread`/`requestOlderAuthoritativeThread`/`cancelAuthoritativeThread`, `registerAuthoritativeSend`, and the legacy content requests `observeThreadSnapshot` (`backfill_since`), `requestHistory`, `requestAttentionHistory`.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: `open` requires `enabled()`; `older` requires an opened conversation and certified `olderExists`; reconnect re-opens on the fresh repository.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeAuthoritativeThread` (restore before observing, writer persistence) and `openAuthoritativeThread` (restore before query); its store comes from `delegate as? RemoteConversationRepository`, which is never true in production because the delegate is `StableConversationRepository`.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` and `ConversationRepository.kt`: `runConfigurationReadings` is the optional-provider pattern decorators forward; `TappingConversationRepository` forwards by interface delegation.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `HostRepositories.repository` and `thread` build Stable → Caching → decorator per exact host.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `receivedThread`/`threadItems` (legacy rows + live deltas), the history walk (`onDemandOlderHistory`, `onRetryOlderHistory`, `askForNewestPage`, `onDemandUnsignedHistoryGap`), `state`, and the existing `AuthoritativeThreadPresented`/read-sight/queue handling.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread`, the screen-ready presentation whose `scope` encodes host/conversation/epoch/generation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: draws `state.authoritativeThread` as the sole row source when present.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt`: `TreeHostRow` and its #1009 update-required treatment; `ChannelListScreen` host item maps the tap.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`, `RelayConnectionRegistry.kt`: `HostConversationConnection` → `HostConversationSnapshot` per-host collectors.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `harnessThreadEvidence` is the single negotiation seam for shared E2E evidence.
- `docs/specs/architecture/2148-authoritative-thread-queries.md`: lesson carried forward — reconnect shares certified facts only, never pending requests; queries are connection-owned.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: cached fields never negotiate current support; exact-host lookups.
- `../pyrycode/docs/protocol-mobile.md`, capability negotiation, thread and query messages: wire authority.

Overlapping in-flight branches (build through, additive edits only): #1947 (`ThreadViewModel.kt`, `RemoteConversationRepository.kt`), #2098 (`HostConversationSource.kt`), #2161 (`AppModule.kt`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=672-3895 (host row) and node `16-8` (thread, unchanged).

The Figma MCP server is not authorised in this session, so the frame was not re-read; the ticket supplies the values. The missing-thread host reuses the shipped #1009 update-required row exactly: server glyph and host name in `colorScheme.error`, the caption in `bodySmall` / `onSurfaceVariant` under the row and kept when folded, the edit pencil retained. Copy differs: “Update this host's Pyrycode daemon to use it.” The trailing action is the existing plug reconnect control (`cd_tree_host_reconnect`, named for the host) instead of the app-update download control. The thread presentation keeps the existing authoritative rows; no new frame.

## Context

The authoritative store, queries, cache, projection, send reconciliation and read qualification exist but production never selects them: hello omits `thread`, `authoritativeHostId` is empty, the cache wrapper cannot reach a remote store, and `ThreadViewModel` only reads legacy rows. The 2026-10-15 decision requires thread support with no production fallback. This ticket switches the production source once; legacy code stays unhooked for #2167. A decision record for “thread required, no fallback” is worth adding by the documentation stage.

Sizing: the ticket is a grandchild with six criteria and a ~1450-line estimate; `needs-human:sizing` was applied with the split that would have been proposed. Built as it stands.

## Design

**Negotiation.** `HelloClientPayload.capabilities` defaults to interactive, multi_agent, stop_background_task and `thread`. `RelayConnectionBundle` no longer passes a `lastEventId` supplier, so `last_event_id` is omitted. `RelayConnectionFactory.create(record)` passes `record.serverId` as the coordinator's `authoritativeHostId`; the test-only `createCompatibility` keeps the empty legacy id.

**Verdict.** New `data/repository/NegotiatedThread.kt`:

- `ThreadSupport` sealed: `Unverified` (no authenticated connection has answered since this owner started, or the capable connection ended), `Supported(connection: Any)`, `Missing(connection: Any)`. Connection identity is the connection's `RemoteConversationRepository`.
- `NegotiatedThread` abstract class (internal members carry the internal store and snapshot types): `host`, `support: StateFlow<ThreadSupport>`, `store`, `observe(conversation)`, `suspend open(conversation): Boolean`, `older(conversation): Boolean`, `cancel(conversation)`.

`RelayRepositoryCoordinator` (when `authoritativeHostId` is set) owns a `MutableStateFlow<ThreadSupport>` written by one collector: `collectLatest` over `activeConnection`, then that connection's `pump.state`. An `Open` state yields `Supported` when both `thread` and `interactive` were granted, else `Missing`. A non-`Open` state or a null connection retires `Supported` to `Unverified` but keeps `Missing` until a later ack replaces it. `collectLatest` cancels the retired connection's state collection, so a late emission cannot restore an old verdict. `threadMissing: StateFlow<Boolean>` projects it for the host row. `negotiatedThread` routes `open`/`older`/`cancel` to `liveRepository()` only when it is the very connection named by the current `Supported` verdict; `observe` reads the shared store for this host.

**Provider.** `ConversationRepository` gains `val negotiatedThread: NegotiatedThread? get() = null`. `StableConversationRepository` takes it as a constructor argument (AppModule passes the bundle coordinator's). `CachingConversationRepository` takes its store from `delegate.negotiatedThread` when it names this `serverId` (falling back to the existing remote-delegate lookup used by its tests) and exposes a wrapper whose `observe` is its existing restore-then-observe-and-persist flow and whose `open` restores before delegating. Interface delegation carries it through `TappingConversationRepository`.

**Legacy request retirement.** In `RemoteConversationRepository`, a non-empty `authoritativeHostId` retires legacy content requests: `observeThreadSnapshot` sends no `backfill_since`, `requestHistory` and `requestAttentionHistory` fail with `IllegalStateException` before sending. Thread queries remain gated by `authoritativeLiveEnabled`, so a missing-thread or not-yet-acknowledged connection sends no content request of either kind.

**Screen binding.** `ThreadViewModel` reads `repository.negotiatedThread` once. When null (demo host, legacy tests) nothing changes. When present:

- `receivedThread` and `threadItems` produce no legacy rows (no `threadSnapshots` subscription, no delta fold), so legacy rows, history/backfill reducers, cached legacy rows and attention backfill feed nothing.
- the history walk entry points do not call `requestHistory`; `onDemandOlderHistory`/`onRetryOlderHistory` call `negotiated.older`.
- one `viewModelScope` job collects `support` with `collectLatest` and calls `open(conversation)` for each new `Supported` connection; `onCleared` cancels the conversation's query.
- `authoritativeThread` in `ThreadUiState` comes from `combine(support, observe(conversation))`: `Missing` → null (no content even from cache); otherwise a snapshot with an epoch projects through `projectAuthoritativeThread(host, conversation, snapshot)` on the content worker; no epoch → null.

The existing screen publication (`AuthoritativeThreadPresented`), queue actions, task stop and exact-revision read qualification then operate on production presentations with the exact host from the route.

**Host row.** `HostConversationConnection` gains `threadMissing` (default false); `RelayConnectionRegistry` supplies the coordinator's; `HostConversationSnapshot.threadMissing` is collected beside `connectionStatus`. `TreeHostRow(threadMissing)` draws the error accent, the plug reconnect control and the caption `tree_host_thread_missing`; the app-update treatment keeps priority when both apply. `ChannelListScreen` passes the flag; its tap already maps a connected host to `TreeHostReconnectTapped`.

**Harness binding.** `harnessThreadEvidence(repository)` returns `ThreadEvidenceSource.authoritative(host, repository, negotiated::observe)` whenever the repository carries `negotiatedThread`, and for a raw `RemoteConversationRepository` with a non-empty authoritative host it observes that repository's store. Only a repository with neither stays legacy (the demo/fake route). An authoritative production run can therefore never be satisfied by legacy evidence.

## State and concurrency model

- Verdict: one coordinator-scope collector, `collectLatest` over `activeConnection` then `pump.state`; writes one `MutableStateFlow`. Cancelled by `close()` with the coordinator scope.
- Store: the coordinator's `AuthoritativeThreadStore`, shared across connections (certified facts only); queries remain connection-owned inside each repository and end with its child scope.
- ViewModel: an `open` job (`collectLatest` on `support`) and the presentation flow, both in `viewModelScope`; `onCleared` calls `cancel`. Projection runs on `contentScheduling.worker` when supplied.
- Background close tears the connection down; the verdict becomes `Unverified` (or stays `Missing`); the next ack re-evaluates and the ViewModel re-opens on the new connection.

## State transitions and identity reuse

| Event | Test |
| --- | --- |
| Capable ack, reconnect, second capable ack (new connection identity) | `NegotiatedThreadActivationTest.reconnectReevaluatesVerdictAndReopensOnlyOnTheNewConnection` |
| Missing ack, disconnect, later capable ack | `NegotiatedThreadActivationTest.missingVerdictSurvivesDisconnectUntilACapableAckReplacesIt` |
| Late pump-state emission from a retired connection | `NegotiatedThreadActivationTest.retiredConnectionCannotRestoreItsVerdict` |
| Two hosts with colliding conversation/item ids | `NegotiatedThreadActivationTest.collidingHostsKeepSeparateStoresAndVerdicts` |
| Missing-thread host: zero content requests | `NegotiatedThreadActivationTest.missingThreadHostSendsNoContentRequests` |
| Restart: cache restored before open; offline reading; missing suppresses cache | `NegotiatedThreadViewModelTest.offlineCachedThreadReadsUntilAMissingVerdictSuppressesIt` |
| ViewModel re-open per connection, cancel on clear | `NegotiatedThreadViewModelTest.opensOncePerSupportedConnectionAndCancelsOnClear` |
| Legacy producers suppressed on the negotiated route | `NegotiatedThreadViewModelTest.negotiatedRouteNeverSubscribesToLegacyRowsOrHistory` |
| Connection/session progress, offline clearing, replacement and late old-session input | `NegotiatedThreadActivationTest.productionProjectionRetiresProgressAcrossReconnect`; `NegotiatedThreadScreenTest.negotiatedScreenShowsAdmittedProgressAndSuppressesOfflineFactsForMissingPeer` |
| Two capable destinations, selection, held cache restore, interrupted catch-up, recovered queued send, paging and exact revision sight | `RelayConnectionFactoryTest.capableDestinationsKeepCollisionsThroughHeldRestorePagingAndRecoveredSettlement` |
| Interrupted catch-up + queued settlement + exact-revision read | existing `AuthoritativeThreadQueriesTest`, `AuthoritativeSendReconciliationTest`, `AuthoritativeReadSightTest` matrices, now reached through the production route by `NegotiatedThreadActivationTest.productionRouteSettlesLostAckOnceAndKeepsNewerFactsAcrossInterruptedCatchUp` |

## Error handling

Retired legacy reads throw `IllegalStateException`, which every existing caller already classifies as a static-coded failure. `open`/`older` return `false` when the verdict or connection is not current. No exception reaches UI state. Logs: `event=thread_negotiation verdict=supported|missing|unverified` and `event=legacy_content_request_refused`; no ids, epochs or content.

## Testing strategy

Unit (`app/src/test`): `NegotiatedThreadActivationTest` drives real coordinators with fake pumps whose `Open` state carries chosen capabilities and records sent envelopes; hello advertisement is asserted by the existing `MobileWireCodecTest`/`NoiseSessionFactoryTest` updated for the new default. `NegotiatedThreadViewModelTest` drives `ThreadViewModel` with a fake repository exposing a controllable `NegotiatedThread`. Shared screen (`app/src/sharedTest`): `TreeHostRowThreadMissingTest` for caption, control and fold behaviour; `ThreadEvidenceTest` extended for harness binding. Focused runs: those classes plus the existing authoritative, caching, coordinator and channel-list classes they touch.

Device/live: the new genuine scenario (finished earlier-session agent after restart, nested tools, reconnect during settlement), its scripted twin, the named `InteractiveStreamE2ETest` live methods, the recorded-suite refresh and the #2221 nesting proof require a thread-capable isolated daemon and real Claude. They are attempted after the PR is open; any not completed in this run are reported precisely as pending with no PASS claim.

## Open Questions

- Does a retry on a connected missing-thread host redial? `RelayConnectionSupervisor.retry` is the existing control; a daemon update restarts the daemon and drops the socket anyway. Resolve during implementation by reading `retry`'s connected-state behaviour.
- Live tool progress counters (`observeAuthoritativeProgress`) are not joined to the projection in this ticket unless the screen needs them for parity; record the outcome under Revisions.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`: record named live, recorded and scripted results, counts, revisions and failures supplied by this ticket's runs.
- `docs/knowledge/features/thread-screen.md` (or the owning negotiation topic): document required-thread host behaviour, the missing-thread host row and the no-fallback selection.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new decoder. The verdict reads only the authenticated `hello_ack` capabilities already parsed into `PumpState.Open.capabilities`; thread facts still enter only through `ThreadQueryDecoder`/`AuthoritativeThreadRoute`. SHOULD FIX: the verdict must be computed from the same connection's `Open` state and never from cache; enforced by deriving it inside `collectLatest` over `activeConnection`.
- [Tokens] No credential change. `HelloClientPayload.toString` keeps redacting the token; dropping `last_event_id` removes a resumption hint, not a secret.
- [Files and storage] The authoritative cache stays the existing app-private `noBackupFilesDir` document written atomically by `FileConversationCache`. MUST FIX addressed in design: a restored snapshot cannot establish support; a `Missing` verdict suppresses cached content.
- [Android attack surface] No exported component, intent or provider change. The new caption is an app string; the host name stays bounded by `boundedRowText`.
- [Cryptography] Noise IK and TLS unchanged; no keys or nonces touched.
- [Network and I/O] SHOULD FIX: a missing-thread or pre-ack connection must send no history, backfill, replay or thread query. Enforced twice: legacy requests refuse in `RemoteConversationRepository` whenever an authoritative host is configured, and thread queries require `authoritativeLiveEnabled`. Reconnect backoff is unchanged.
- [Errors and logs] Static event codes only; no conversation ids, epochs, item text or capability lists beyond the fixed verdict word.
- [Concurrency] MUST FIX addressed in design: a retired connection's late state emission or late `open` must not restore a verdict or open queries on the replacement; `collectLatest` cancels retired collection and `open` checks the live repository is the `Supported` connection. ViewModel jobs are `viewModelScope`-owned and cancel their query on clear.
- [Threat model] A hostile relay can drop the ack (verdict stays unverified, nothing is requested) or force reconnects (each re-evaluates; held facts stay certified-only). A daemon granting `thread` without `interactive` is treated as missing. Token theft and UI leakage are unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17

## Revisions

- 2026-10-17 (recovery): Shared harness evidence must carry the negotiated connection, not the reconnect-spanning Stable/Caching facade, as its owner. Add a negotiated evidence source combining the route's verdict with its snapshot: supported verdicts use their exact connection, offline evidence has a distinct route owner, and missing-thread evidence is unadmitted and empty even with cached facts. `HarnessThreadEvidenceBindingTest.negotiatedEvidenceRetiresConnectionOwnershipAndSuppressesMissingHostFacts` guards this boundary. Security review remains PASS: no new production trust boundary or storage; the evidence adapter cannot mistake a saved fact for a negotiated grant. The native proof uses the existing task-id/optional-tool-id Agent join rather than requiring an optional content field.
- 2026-10-17 (recovery): Measured branch work before final checks: 2,125 inserted + 81 deleted lines across 32 files, exceeding the 1,600-line ceiling. The existing `needs-human:sizing` decision and grandchild rule still apply; continue building rather than split again. Tightened the native scenario sequence so session A ends before the graph restart: the reopened Agent is already in an earlier session, and the reconnecting send belongs to session B. Page fixture completion also uses the requested `before_order` as its exclusive upper bound; invalid page completion cannot certify a read.
- 2026-10-17 (recovery): The negative producer probe exposed a surviving legacy `observeQueue` subscription. The negotiated branch now supplies empty legacy queue/roster/count inputs. Shared stop capability and controls remain; item-backed presentations supply task lifecycle. The incapable-peer factory probe now asserts no queue/task content or stop command from legacy rosters; the existing legacy component tests remain unchanged.
- 2026-10-17 (recovery): Added genuine `interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect`. It reuses the existing running Agent UI proof, then `proveNegotiatedAgentRestart` waits for a terminal authoritative Agent in the real cache, rebuilds the native graph, verifies its own retained tools in the reopened UI, resets the session and retires the transport during an identified pending send. A fresh supported connection must recover exactly one delivered item; historical tool/session ownership must persist. The scripted `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` executes the same proof after its original assertions, through the existing `background-agent` gate. Native execution is required for graph restart, Keystore-backed pairing and real transport. The recording manifest/runner explicitly include the new genuine method as pending until capture/review supplies its asset and counted evidence; no synthetic recording is added.
- 2026-10-17 (recovery): The factory probe initially supplied page-window fields for nonzero catch-up completion. Corrected its fixture to the protocol's `from_version` completion while preserving window metadata for actual pages. Production validation remains unchanged. The factory/cache probe now directly combines all named interleavings rather than relying on component matrices for settlement/read integration.
- 2026-10-17 (recovery): The progress-free projection leaves admitted tool/task readings invisible. Add connection-qualified `NegotiatedThread.progress(conversationId, connection)`, forwarded by the cache; the coordinator accepts only its current supported repository. The ViewModel switches projection with the verdict, collecting progress only for that connection and projecting empty progress offline. Session admission and item/session matching remain in the existing repository/projector. Security review: PASS; cancelled collectors plus exact connection checks fence late readings, and no transient readings enter persistence. `NegotiatedThreadActivationTest.productionProjectionRetiresProgressAcrossReconnect` covers live, retirement, stale input and replacement through Stable → Caching → ViewModel. Correct the interrupted batch fixture to `id=2, rev=2` and validate all activation batch identities/revisions centrally before delivery. Extend production factory probes to capable hosts with colliding identities and integrate restore, paging, settlement and read evidence; component matrices remain intact. New live/scripted scenario and counted acceptance remain required.
- 2026-10-17: Open question on reconnect resolved. `RelayConnectionSupervisor.retry` is a no-op while the loop is connected, so the host row's tap would not renegotiate. `RelayConnectionRegistry.retryHost` now closes the supervisor first when the host's verdict is `Missing`, then retries, so the tap redials that host only; other hosts and the disconnected-host retry are unchanged.
- 2026-10-17: Live tool-progress counters are not joined to the projection; the presentation uses the progress-free `projectAuthoritativeThread` overload, which also keeps offline reading from claiming current transient progress.
- 2026-10-17: Test names settled during implementation. The integrated production-route probe is `NegotiatedThreadActivationTest.interruptedCatchUpAcrossReconnectKeepsNewerRevisionsAndPriorCheckpoint`; lost-acknowledgement settlement stays covered by `AuthoritativeSendReconciliationTest`, which already drives the repository's send registration that the production host id now enables. Added `capableHostReplacesLegacyBackfillWithAThreadQuery`, `NegotiatedThreadViewModelTest.anotherHostsRouteIsNotThisDestinationsProducer`, `TreeHostRowThreadMissingTest` and `HarnessThreadEvidenceBindingTest`.
