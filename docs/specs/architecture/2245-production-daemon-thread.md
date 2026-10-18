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
- `androidTest/.../e2e/SecondClientPeer.kt`, `CLIENT_INFO`, and `recording/RecordedPermissionTest.kt`: keep raw producer diagnostics and the standalone legacy fixture explicitly legacy; the normal recorded suite uses the production phone factory.
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
| Process restart/offline restoration, then Missing with saved facts | `AuthoritativeThreadCacheTest.restartRestoresFactsAndOnlyCertifiedReconnectCheckpoint`, `NegotiatedThreadViewModelTest.offlineCachedThreadReadsUntilAMissingVerdictSuppressesIt` |
| Missing → capable; repeated supported connection; destination disposal | `NegotiatedThreadViewModelTest.opensOncePerSupportedConnectionAndCancelsOnClear`, shared negotiated screen probe |
| Exact row revision changed or presentation replaced before read | Factory integrated probe above and existing `AuthoritativeReadSightTest` |
| Admitted completion and duplicate completion | `NegotiatedThreadActivationTest.admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark` |
| Completion while an older captured query is still active | `NegotiatedThreadActivationTest.completionDuringAnOlderCapturedQueryQueuesFreshCoverageOnItsOwner` |
| Local send before the first captured batch establishes an epoch | `NegotiatedThreadActivationTest.productionSendBeforeFirstBatchShowsOnlyScopedPreAcceptanceFeedback` |
| Observer final write held across destination recollection and verdict retirement | `NegotiatedThreadViewModelTest.retirementDoesNotWaitForAnObserversFinalWrite` |
| Finite authoritative burst, controlled display frames and Missing before another frame | `NegotiatedThreadViewModelTest.authoritativeBurstsPublishOnlyAtFramesAndMissingDoesNotWaitForOne` |
| Evidence recollection across reconnect and Missing | `HarnessThreadEvidenceBindingTest.negotiatedEvidenceRetiresConnectionOwnershipAndSuppressesMissingHostFacts` |

## Error handling

Retain A's sticky Missing and generic unavailable outcomes; missing/unverified connections refuse query admission. Interrupted batches preserve complete items but never certify unfinished coverage. Restore failures retain existing empty/held-fact behaviour. Queue/send errors use existing sealed/domain results and inert UI treatment. Logs contain static lifecycle/result codes only, never payload text, keys, credentials, host secrets or decrypted bytes.

## Testing strategy

Carry the scoped integrated probes first and observe red before implementation. Preserve A's full negotiation suite, adding only the hello and three production-route probes. Run affected network codec/Noise, cache, query, send/read, ViewModel and evidence classes plus negotiated shared screen coverage. Shared tests run on Linux/Robolectric; compile instrumented Kotlin after shared changes. Run one affected recorded native class and a focused scripted scenario; retain executed/failed/skipped counts and raw evidence. Dispatcher owns complete JVM, normal recorded UI and scripted-all regression; no named live capture is required by B. Refresh affected recordings only through genuine capture/replay/strict comparison if activation changes their contracts. #2246 retains its new scenario. No new device-only test or dependency is planned.

## Open Questions

None. Validate salvage against current fences and owned cache writes instead of replacing older files wholesale.

## Revisions

2026-10-18 (repair scope recheck): After formatting, the branch measures 2,756 written lines (2,564 inserted and 192 deleted across 35 files, including this entry). Retain `needs-human:sizing`: the repairs preserve the same required-thread activation and exact ownership invariant, and cannot ship separately from activation. There is still one new exported provider type; the changed presentation constructor has one caller and the new defaulted read entry leaves unrelated consumers source-compatible. The existing grandchild depth/floor outcome applies; no further split is proposed.

2026-10-18 (queued generation evidence): The corrected factory run executed 29 tests, passed 27 and failed 2, with no skips. Actual capable and Missing replacement-read probes pass; the queued same-connection/epoch generation-reset probe exposed a remaining qualification gap. Carry the immutable producing snapshot on `AuthoritativeThreadPresentation` and require that it is still the negotiated store's current snapshot before a queued mark can run. This synchronously fences reset, repair, revision and coverage changes even before a display frame republishes them. The presentation constructor has one production caller, verified with CodeGraph. No wire or visual change. Preserve the failed run; the separate lease completion fixture is still under diagnosis.

2026-10-18 (queued retry probe): `RelayConnectionFactoryTest.authoritativeReadProofIsRecheckedBeforeQueuedAttempt` retires a presentation after qualification but before the process worker runs, requires zero writes, then supplies fresh binding/revision sight before allowing a mark. The retained callback holds a weak reference to its ViewModel so pending process work cannot retain a disposed destination. `AuthoritativeReadSightTest.reconnectCannotReuseAnActiveCompositionToQualifyNewSight` also reuses the same immutable presentation/checkpoint with a fresh connection/binding and requires fresh qualification.

2026-10-18 (destination and read ownership rework): Preserve the same activation deliverable and existing grandchild sizing hold. Each supported destination acquires a connection-owned query lease after cache restoration. Multiple destinations share synchronization; releasing one lease cannot remove the conversation while another lease survives. Release captures its original repository and cannot cancel a replacement connection. `RelayConnectionFactoryTest.sameConversationDestinationsKeepPagingAndCompletionCertificationAfterOneCloses` exercises production factory/cache/ViewModel wiring, disposal, paging, completion certification and replacement disposal.

Authoritative read work carries its exact connection and a retained qualification callback through `acknowledgeAuthoritativeReadCheckpoint` and `ReadCheckpointRetries`. The callback retains the presentation, binding and seen revision map and rechecks current presentation, support, connection, summary and coverage before every attempt. Retirement drops owned pending work; replacement capable and Missing connections cannot inherit it. Legacy retries retain their existing host-owned contract. Fresh presentation/connection sight can qualify the same numeric checkpoint again. `RelayConnectionFactoryTest.authoritativeReadRetriesRequireFreshSightOnCapableReplacement` and `authoritativeReadRetriesNeverReachMissingReplacement` cover failed marks and replacement through production wiring; existing exact-revision probes remain required. No storage, wire or visual contract changes. Genuine recording refresh remains an explicit `needs-live-artifacts` handoff; historical replay failures remain failures.

2026-10-18 (resumed probe correction): The first repaired activation run executed 18 tests, with one failure in the carried older-query fixture: it expected the retry to resume at version 10 after live version 20 arrived before the opening window completed. `AuthoritativeThreadStore.certifyQuery` intentionally retains checkpoint 0 in that case, because the older capture cannot certify the newer held facts. Require both the retained zero checkpoint and a fresh zero-version query; keep the two-request and retirement assertions. The completed-reply probe still independently establishes checkpoint 10 before live revisions 19/20 and requires certified coverage plus exact sight. Preserve this 17-pass/1-fail run as a failure; no production certification rule changes.

2026-10-18 (resumed verifier rework): The preserved regressions and plan notes are incomplete work, not prior passes. Apply the completion callback outside the live-route monitor, carrying the admitted conversation and generation; the owning query scheduler rechecks generation and current connection eligibility. This preserves lock ordering with query reset and prevents a delayed completion callback from refreshing a replacement generation. The negotiated destination's empty legacy content arm does not consume display frames; complete authoritative presentations own that pacing. Existing `needs-human:sizing` and `needs-live-artifacts` holds remain; no new separable behaviour is added. Figma node `16:8` was rechecked with no geometry, token or asset changes required.

2026-10-18 (verifier rework): Completion refresh must obtain a correlated catch-up certification as well as summary metadata. Pass the admitted conversation to the owning query scheduler; refresh only an already-open conversation, coalesce requests, and queue a refresh behind an in-flight query so a boundary captured before completion cannot lose the refresh. Keep all connection/generation fences. Extend the completion probe from version 10 through live revision 19/completion 20, partial batch, certified terminal marker, exact sight and duplicate completion. Live receipt itself certifies nothing.

2026-10-18 (verifier rework): Keep cache observation shared independently of verdict collection, so its non-cancellable final flush cannot delay Missing suppression or retire-progress clearing. Each verdict branch clears presentation immediately, then projects only its own progress and frame-paces complete presentations. Empty-epoch snapshots may project scoped local pending sends, with no certified checkpoint or read revisions. Controlled-frame and held-cleanup regressions cover final burst publication and immediate retirement; production send-before-first-batch covers provisional feedback. Retain explicit legacy hello decoding coverage and stable streaming body identity while separately asserting that streaming text is not completion evidence. These repair the same activation invariants, not independently shippable behaviours; the existing grandchild sizing escalation remains.

2026-10-18: After correcting an invalid saved-snapshot fixture (two earlier 5/1 failed runs), the exact-Remote probe failed at retirement admission: 5 executed, 1 failed. Gate that evidence on `usesAuthoritativeLiveState`; the provider path retains connection-token ownership. The activation probe's first run was 16 executed, 1 failed because carried string assertions used A's JsonElement helper; compare its serialized numeric value without changing the checkpoint contract.

2026-10-18: Carry the salvage peer's explicit legacy capability list and apply it to the standalone permission fixture too. Their raw diagnostics require legacy frames; neither supplies production-phone evidence. Existing device fixtures retain their real transport/storage reasons; no new device-only test is introduced. These necessary consumers and recording refresh may exceed 1,600 written lines; preserve the grandchild slice and escalate measured overage without further splitting.

2026-10-18: Measured 1,603 written lines before this entry. Confirmed parent #2175 and grandparent #1988, posted the measurement and added `needs-human:sizing`; continue under the depth/floor rule. All 67 existing production recordings have legacy-only acknowledgements; actual native replay and any required genuine capture/comparison evidence remain explicit acceptance work.

2026-10-18: The unchanged Room held-retry probe failed in this branch (17 executed, 1 failed) and untouched main `03e455f7b` (1 executed, 1 failed), both returning null from `fresh`. Filed #2263 in Backlog with low priority and retained its assertion behind a tracker-linked ignore, per the out-of-scope bug policy. Activation and the other atomic cache probes stay enabled.

2026-10-18: Native scripted ping executed two failures on the supported production route, with `thread_decode code=attribution` followed by repair/drain reconnects. `ThreadQueryDecoder.decode` incorrectly equates an initial empty request epoch with the captured daemon epoch. The protocol permits empty epoch with version zero; admit that first captured epoch while retaining fixed epoch/version, routing and request correlation across the reply. Add `ThreadQueryDecoderTest.initialZeroVersionCatchUpLearnsEpochAndKeepsBatchesFenced` and rerun the scripted production probe. This cold-start guard is required by activation; no wire field or daemon contract changes. Also apply Spotless's required annotation layout to the tracked Room ignore.

2026-10-18: Decoder regression is green (33/33). The scripted rerun reaches legacy-only assertions: `DurableGapProof.cacheBaseline` waits on `readThread`/legacy history coverage, and the ping panel expects an unreported roster. Retain the obsolete legacy gap assertion behind a #2167-linked ignore, whose existing scope explicitly retires legacy coverage/gap tests; it supplies no production evidence. Assert the authoritative initial empty task set using `BackgroundTaskPanel`'s existing empty presentation. Preserve all remaining ping/render/read assertions. Legacy deletion and authoritative local paging remain outside B.

2026-10-18: The next native run rendered the reply but the scripted read proof still waited on a reconnect-spanning Stable facade's legacy snapshots. Bind it once to the exact supported Remote connection and shared authoritative evidence, require certified coverage of the reply revision and confirmed authoritative shown watermark, and use the canonical post revision for the explicit peer read. The read actor opts into thread support; raw diagnostic peers keep their default legacy capabilities. Preserve unseen-post attention and peer-confirmation assertions without legacy history as evidence.

2026-10-18: The exact-connection native read probe failed despite qualified sight and a daemon-confirmed read. Its assertion incorrectly required the shown watermark to reach the item's completion-only revision. The protocol's `last_shown_version` contract explicitly excludes completion-only changes. Retain certified coverage through the exact settled revision; separately require a shown watermark covering the reply's creation and confirmation through that current daemon watermark. Exact-revision sight remains covered by the production sight tests and is never inferred from numeric receipt.

2026-10-18: The next native run confirmed the reply read, then timed out after the unseen-post control caused the harness's intentional push-queue ceiling teardown (`4413`). Retired phone evidence correctly cannot observe a replacement connection. Keep phone rendering/read proof on its exact admitted connection, and obtain the later unseen post from the independent thread-enabled read actor's certified newest window. Reuse `ThreadQueryDecoder` per peer connection/request; abandon incomplete batches on replacement. Assert unread attention before the peer queries, then confirm through that peer's authoritative shown watermark. No legacy history supplies production read evidence.

## Documentation handoff


Pending documentation stage:

- `docs/knowledge/features/thread-screen.md`, "What it does": required-thread production selection with no fallback, offline saved facts versus current support/progress, Missing suppression, prompt retirement and frame-paced presentations.
- `docs/knowledge/features/remote-conversation-repository.md`, "Opt-in thread negotiation (#2244)": replace opt-in/pending-activation wording; describe owning-connection completion refresh and terminal-marker certification separately from summary metadata.
- `docs/e2e-interactive-stream.md`, recording evidence: when genuine refreshed recordings arrive, record actual capture/replay/comparison outcomes and reviewed differences.
- `docs/recorded-tests.md`, "What replay proves": update obsolete pending-activation prose after the recording handoff, preserving the standalone explicitly legacy fixture and all strict evidence requirements.

## Security review

**Verdict:** PASS

- **Trust boundaries:** MUST FIX addressed in design: select the exact-host provider before subscriptions; a cached epoch or legacy row cannot grant support. Reuse `ThreadUpdateDecoder` validation and existing bounded text projection; no new raw-text rendering or URL/file interpretation.
- **Tokens:** existing host-scoped Keystore device registration remains; no new credential storage or exposure.
- **Files/storage:** preserve app-private `noBackupFilesDir` Room/file cache and atomic transactions. Existing content storage is plaintext in the app sandbox, not encryption against root access; this activation introduces no storage format or path. No daemon text becomes a filename. Held restores remain generation-fenced.
- **Android attack surface:** no component, intent, provider, permission or WebView change.
- **Cryptography:** preserve vendored Noise IK and per-session cipher ownership; no primitive, key or nonce changes.
- **Network/I/O:** preserve frame bounds, authenticated ack, timeouts and supervisor backoff. Delayed/reordered traffic cannot revive retired requests. Refresh only uses the owning repository, never the facade's replacement connection.
- **Bootstrap admission:** an empty epoch learns the first captured epoch only for a zero-version catch-up. Request/conversation correlation and the captured epoch/version remain mandatory across every subsequent batch and terminal marker.
- **Errors/logs:** static codes only; no tokens, item bodies, decrypted bytes or transient state dumps. Generic existing UI failures remain.
- **Concurrency:** MUST FIX addressed in design: retain A's synchronous transport/child-scope gates; cancellation alone is insufficient. Provider opens after restore; progress is bound to the verdict's exact connection and removed on retirement. Existing serialized cache writer protects certification.
- **Rework adversarial pass:** MUST FIX findings on final-flush retirement and completion certification are addressed above: persistence cleanup cannot own the support switch; a refresh queues behind an older captured query and only its terminal marker supplies certification. Frame pacing cannot delay the verdict's clearing emission. A pending local send grants neither an epoch nor read evidence. No additional token, storage, Android, crypto, network, logging or threat-model boundary changes.
- **Resumed callback review:** completion callbacks run after releasing the live-route monitor, avoiding inverse acquisition against query reset. The captured generation and owning connection are checked before a refresh is queued; cancellation/reset drops queued refresh work through the existing scheduler fences. Neither the metadata response nor a partial query batch certifies a rendered revision.
- **Destination and retry boundary review:** MUST FIX addressed: query leases are counted on their producing connection, released idempotently, and never routed through a replacement facade. Authoritative pending reads retain exact presentation/revision qualification and the original repository; a synchronous source and qualification check precedes each attempt. Missing and retired evidence cannot cross the retry boundary. The callback introduces no new coroutine scope and retains no payload in logs; process-owned retry cleanup drops retired work. Legacy-only retries remain separate from authoritative qualification.
- **Current snapshot review:** MUST FIX addressed after the queued generation probe: current rendered controls can lag a synchronous store reset or repair until the next frame. A retained read proof must still name the exact current immutable store snapshot, including its generation, revisions and certification, before sending. The snapshot remains internal, is already the presentation's input and adds no storage or logging boundary; its redacted `toString` remains in force.
- **Threat model:** malicious relay delay/drop/reorder is handled by Noise plus connection/generation fencing and certified checkpoints. Hostile daemon frames reuse defensive decoding and inert text paths. Keystore wrapping of credentials remains; rooted-device plaintext-content theft and OS screenshot/accessibility/keyboard exposure are existing platform limitations, unchanged by source selection.

**Reviewer:** builder self-review per `builder/security-review.md`. **Date:** 2026-10-18.
