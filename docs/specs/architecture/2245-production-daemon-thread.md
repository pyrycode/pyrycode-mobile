# Bind production threads to their owning daemon

## Files read

- `data/network/MobileWireModels.kt`, `HelloClientPayload`, and `NoiseIkSession.kt`, `buildHello`: capability defaults and explicit legacy test configuration.
- `di/RelayConnectionFactory.kt`, `create` and `RelayConnectionBundle`: saved-host identity, device registration and hello cursor assembly.
- `data/repository/RelayRepositoryCoordinator.kt`, `threadSupport`, `updateThreadVerdict`, `liveRepository`, `teardownActive`: preserve #2244's synchronous transport and child-scope fences.
- `data/repository/ConversationRepository.kt` and `StableConversationRepository.kt`: optional-provider forwarding alongside `runConfigurationReadings`.
- `data/repository/CachingConversationRepository.kt`, `restoreAuthoritative`, `observeAuthoritativeThread`, `openAuthoritativeThread`: restore before query and serialized owned upserts; retain Room integration.
- `di/AppModule.kt`, `ThreadDestinationFactory.repository`: Stable → cache → instrumentation decorator for the destination's exact host.
- `data/repository/RemoteConversationRepository.kt`, authoritative admission, progress and content-request refusal; `AuthoritativeThreadRoute.accept`: refresh summary only after admitted turn completion.
- `ui/conversations/thread/ThreadViewModel.kt`, `receivedThread`, `threadItems`, history actions and authoritative sight/action guards: select one producer before subscribing.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt` and `ThreadScreen.kt`: existing authoritative rows, exact revision sight, session ownership and queue actions.
- `ui/conversations/components/MessageBubble.kt`, `AssistantMessage`: preserve the body tag while its Agent remains active.
- `sharedTest/.../e2e/ThreadEvidence.kt`, `harnessThreadEvidence`: bind evidence to the selected route and actual connection.
- `NegotiatedThreadActivationTest`, `RelayConnectionFactoryTest`, and salvage `NegotiatedThreadViewModelTest`, `NegotiatedThreadScreenTest`, `HarnessThreadEvidenceBindingTest`: integrated probes, keeping A's regression tests.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md`, `conversation-cache.md`: saved facts never negotiate support; only certified coverage qualifies reads.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: capability negotiation, daemon thread updates, session state, queue and security contracts remain the wire authority.
- `docs/recorded-tests.md`: genuine capture, strict comparison and counted native evidence requirements.

Source paths above are relative to `app/src/main/java/de/pyryco/mobile/` unless another root is stated. Scoped salvage is `ec63a4644033f88c915c15b1882b5b44613373a9`, including `e8ca64b29`, `d0afe7d9e` and `bb7515227`; unchanged carried hunks retain their original text.

Overlaps: #1947 changes session-context freshness, #1766 changes streaming markdown, #2254 changes legacy cache coverage and #2256 adds removal persistence. Their edits are independent of this provider binding; keep shared-file changes local. #2175 / draft #2242 is salvage, left open for #2246.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot on 2026-10-18. Retain the dark thread with translucent header/composer, 20dp message gutters, left assistant and right user bubbles, rule/label/rule session divider, inline tools, and composer controls. Existing Material 3 scheme and body/label typography tokens remain; this changes ownership, not geometry or assets. The missing-thread host treatment already belongs to #2244.

## Context

Advertising `thread` suppresses replaced legacy frames. Hello activation, provider forwarding and exclusive screen binding therefore form one independently useful behaviour and must ship together. No fallback is permitted for saved production hosts. Demo and explicit legacy tests retain the existing implementation. #2246 owns the new restart scenario and named live captures; #2068 and #2167 remain outside scope.

Scope forecast: approximately 1,450 written lines including tests and this plan; one new exported provider type, fewer than ten required consumer updates, five criteria and no new reject-state machine. Optional defaulted constructor/provider additions leave unrelated consumers source-compatible. This is the grandchild activation slice; no further split is proposed.

## Design

Add `NegotiatedThread`, an exact-host provider carrying current `ThreadSupport`, shared authoritative store observation, connection-owned progress and open/page/cancel actions. Forward it through Stable and cache; the cache restores authoritative facts before observation and query opening using existing serialization and owned-upsert persistence. AppModule passes the coordinator provider without replacing Room setup.

`RelayConnectionFactory.create` supplies the immutable saved `serverId` and existing host-registered public device identity. Hello requests thread with existing capabilities and production stops supplying a replay cursor. Explicit legacy tests can override capabilities. Preserve A's verdict and request gates and the separate app-too-old terminal treatment.

`ThreadViewModel` selects the matching host provider before creating content subscriptions. Negotiated destinations never subscribe to legacy snapshots, deltas, queue or task roster/count content and never start legacy history walks. Authoritative projection supplies rows, nested agents/tools, accepted sends, session state and read evidence; existing pending-send feedback and action guards remain. Missing yields no presentation, including saved facts. Unverified may show saved authoritative facts with empty transient progress. Paging uses the provider. Keep the legacy path available for demo/explicit tests.

After `AuthoritativeThreadRoute` applies an admitted live `turn_end`, request conversation summary metadata through that repository's own connection scope, guarded by current capability/retirement checks. This supplies read facts, never a fabricated mark. Preserve assistant body identity in both active and settled branches.

`harnessThreadEvidence` chooses authoritative evidence for production providers and exact authoritative Remote connections; only explicit legacy repositories yield legacy evidence. Owners include exact connection, host, conversation, epoch and generation. Missing is empty and unadmitted; replacement evidence cannot match a retired owner.

## State and concurrency model

Coordinator state and its shared store outlive one socket; each repository/query/progress collector belongs to its connection child scope. Retain synchronous current-transport checks even before switched collectors run. ViewModel `collectLatest` opens once per supported connection; cancellation and `onCleared` end its work. Projection uses the existing injected content worker. Cache restoration and persistence retain existing mutex ordering and atomic storage transactions; no new background scope or dispatcher is introduced. Lifecycle background close retires support and progress without discarding certified facts.

## State transitions and identity reuse

| Event | Required regression |
| --- | --- |
| Two capable hosts reuse conversation/item IDs; held restore overlaps newer live input | `RelayConnectionFactoryTest.capableDestinationsKeepCollisionsThroughHeldRestorePagingAndRecoveredSettlement` |
| Reconnect replaces pump, verdict, callbacks and progress; old Open arrives late | A's retained transport/late-callback tests and `NegotiatedThreadActivationTest.productionProjectionRetiresProgressAcrossReconnect` |
| Catch-up interrupted, then resumed; page overlaps newer row/parent and queued settlement | Factory integrated probe above and A's preserved catch-up fixture validation |
| Lost acknowledgement, duplicate/late queued update and recovered delivery | Factory integrated probe above; existing `AuthoritativeSendRepositoryTest` |
| Process restart/offline restoration, then Missing with saved facts | `NegotiatedThreadActivationTest.restartRestoresFactsAndOnlyCertifiedReconnectCheckpoint`, `NegotiatedThreadViewModelTest.offlineCachedThreadReadsUntilAMissingVerdictSuppressesIt` |
| Missing → capable; repeated supported connection; destination disposal | `NegotiatedThreadViewModelTest.opensOncePerSupportedConnectionAndCancelsOnClear`, shared negotiated screen probe |
| Exact row revision changed or presentation replaced before read | Factory integrated probe above and existing `AuthoritativeReadSightTest` |
| Admitted completion and duplicate completion | `NegotiatedThreadActivationTest.admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark` |
| Evidence recollection across reconnect and Missing | `HarnessThreadEvidenceBindingTest.negotiatedEvidenceRetiresConnectionOwnershipAndSuppressesMissingHostFacts` |

## Error handling

Retain A's sticky Missing and generic unavailable outcomes; missing/unverified connections refuse query admission. Interrupted batches preserve complete items but never certify unfinished coverage. Restore failures retain existing empty/held-fact behaviour. Queue/send errors use existing sealed/domain results and inert UI treatment. Logs contain static lifecycle/result codes only, never payload text, keys, credentials, host secrets or decrypted bytes.

## Testing strategy

Carry the scoped integrated probes first and observe red before implementation. Preserve A's full negotiation suite, adding only the hello and three production-route probes. Run affected network codec/Noise, cache, query, send/read, ViewModel and evidence classes plus negotiated shared screen coverage. Shared tests run on Linux/Robolectric; compile instrumented Kotlin after shared changes. Run one affected recorded native class and a focused scripted scenario; retain executed/failed/skipped counts and raw evidence. Dispatcher owns complete JVM, normal recorded UI and scripted-all regression; no named live capture is required by B. Refresh affected recordings only through genuine capture/replay/strict comparison if activation changes their contracts. #2246 retains its new scenario. No new device-only test or dependency is planned.

## Open Questions

None. Validate salvage against current fences and owned cache writes instead of replacing older files wholesale.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/remote-conversation-repository.md`, replacing opt-in/pending activation wording with required-thread production selection and no fallback. Explain offline saved facts versus current support/progress and Missing suppression. If recordings change, record actual capture/replay/comparison outcomes and reviewed differences in `docs/e2e-interactive-stream.md`.

## Security review

**Verdict:** PASS

- **Trust boundaries:** MUST FIX addressed in design: select the exact-host provider before subscriptions; a cached epoch or legacy row cannot grant support. Reuse `ThreadUpdateDecoder` validation and existing bounded text projection; no new raw-text rendering or URL/file interpretation.
- **Tokens:** existing host-scoped Keystore device registration remains; no new credential storage or exposure.
- **Files/storage:** preserve app-private `noBackupFilesDir` Room/file cache and atomic transactions. Existing content storage is plaintext in the app sandbox, not encryption against root access; this activation introduces no storage format or path. No daemon text becomes a filename. Held restores remain generation-fenced.
- **Android attack surface:** no component, intent, provider, permission or WebView change.
- **Cryptography:** preserve vendored Noise IK and per-session cipher ownership; no primitive, key or nonce changes.
- **Network/I/O:** preserve frame bounds, authenticated ack, timeouts and supervisor backoff. Delayed/reordered traffic cannot revive retired requests. Refresh only uses the owning repository, never the facade's replacement connection.
- **Errors/logs:** static codes only; no tokens, item bodies, decrypted bytes or transient state dumps. Generic existing UI failures remain.
- **Concurrency:** MUST FIX addressed in design: retain A's synchronous transport/child-scope gates; cancellation alone is insufficient. Provider opens after restore; progress is bound to the verdict's exact connection and removed on retirement. Existing serialized cache writer protects certification.
- **Threat model:** malicious relay delay/drop/reorder is handled by Noise plus connection/generation fencing and certified checkpoints. Hostile daemon frames reuse defensive decoding and inert text paths. Keystore wrapping of credentials remains; rooted-device plaintext-content theft and OS screenshot/accessibility/keyboard exposure are existing platform limitations, unchanged by source selection.

**Reviewer:** builder self-review per `builder/security-review.md`. **Date:** 2026-10-18.
