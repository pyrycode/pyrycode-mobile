# #1884 — Cancel attention alerts covered by confirmed daemon read facts

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`: `handle`, `post`, `digest` and `AlertLedger` establish dedupe before gates and one notification per host/conversation.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateReadMarks`, `updateAttention` and `publish` already guard host and repository generations and retain offline read facts.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the eager `AttentionNotifier` singleton supplies exact-host lookups and app-private, backup-excluded ledger storage.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.readMarks` is independent of Running/WaitingForAnswer and never restored from cache.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` and `data/network/InteractivePayloads.kt`: `TurnEnd` and `TurnEndPayloadDto.toEvent` preserve completion identity without changing the wire payload.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` and `di/RelayConnectionRegistry.kt`: `activeConnection`, `liveRepository` and `hostConversationConnection` provide authoritative synchronous selection beside asynchronous publication.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierCoordinatorTest.kt`: real coordinator and registry descriptor, with controlled pump-state publication, exercise stale and null repository caches.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationReadMarks.kt`: nullable unsigned durable identities distinguish unsupported/unknown facts from checkpoint zero.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` and `observeHostReadMarks` record received durable identity and expose merged list/push/reply facts.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `recordLatestEntry`, `observeHostReadMarks` and `currentReadMarks` expose the same atomic repository facts without a second fold.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt`: `withNotifier` and `posted` exercise actual Android notifications through Robolectric.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierSourceTest.kt`: the new real-repository notification harness uses the established source/pump patterns to prove read/alert wiring end to end.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceAttentionTest.kt`: real repository/pump harness exercises list refresh, pushes, durable identity and repository replacement.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn` already proves phone and peer read directions; preserve unchanged.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/push-messaging-service.md`, `docs/knowledge/features/dependency-injection-host-conversation-source.md`: retain ledger-before-gates, exact-host identity, and live-only read-fact support.
- Sibling `pyrycode/docs/protocol-mobile.md`: “Marking a conversation read” and “Security model” remain the wire/security authority; no protocol change.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Read the design context and screenshot on 2026-10-08. The channel list groups compact rows and status dots beneath host headings on a dark gradient, with body-small row labels, title-small headings, on-surface text and inverse-primary rules. This ticket follows the existing shared read state and only removes a posted Android notification; no layout, asset, token, notification copy or appearance changes.

## Context

#1883 supplies daemon-authoritative unread, but a posted notification currently survives a peer read. The cancellation contract is a present confirmed `readUpTo` covering a present known `latestEntryId`, including equality and zero. Idle, thread viewing and local persisted positions are not read proof.

One deliverable, three acceptance criteria, approximately 650 written lines including tests and this plan, three production files, no new exported type, one production constructor consumer update, and fewer than ten decision branches. This fits the nearest notification analogue #685. No in-flight branch overlaps the proposed production files. No decision record is needed.

## Design

Expose an internal host-first `StateFlow<Map<String, Map<String, ConversationReadMarks>>>` from `HostConversationSource`, projected from the existing guarded attention facts during `publish`. It follows the same host removal, repository reset and offline retention rules as the existing fold. Do not infer or persist additional read facts.

Pass this flow to `AttentionNotifier` from `AppModule`, using an inert default for existing test/demo consumers. Keep the coverage predicate and cancellation consumer inside `AttentionNotifier.kt`, since cancellation has one consumer. A new or changed covered checkpoint cancels `digest(serverId, conversationId)` with notification id zero, regardless of the permission/settings/mute/foreground gates. Initial state collection also cancels an existing notification without requiring a new alert. Repeated unchanged facts do not cancel a prompt posted subsequently; changes on another conversation/host do not recancel that prompt. The current-repository lookup described in Revisions additionally verifies coverage before cancellation or completion posting.

Completion alerts keep the existing ledger and gates, then check the current flow value immediately before posting. A covered completion is spent without posting. Prompt alerts retain all existing gates and can post even while the conversation is read. Cancellation never writes repository state, answers a prompt, or changes running/busy state. Missing either read fact leaves current behaviour intact. Future known latest identities exceeding the mark permit new unread completion notifications.

## State and concurrency model

The source remains app-owned with its existing monitor and per-host jobs; `dispose` clears the additional projection. The notifier owns two collectors in its existing supervisor scope, cancelled by `dispose`. One `Mutex` serializes Android cancellation and the final coverage-check/post step. Preference collection may suspend before that critical section; coverage is read afresh after it resumes, so cancellation during a suspended gate cannot be undone by a stale post. The cancellation collector reads the current flow value inside the mutex, rather than acting on an obsolete queued emission. Its prior facts track per-conversation changes only and are replaced by the current map.

## Error handling

Cancellation of absent/repeated tags is Android's harmless no-op. Keep the existing post-time SecurityException classification, permission/settings/mute/foreground outcomes and failed-ledger-write handling. Add content-free structured lifecycle logging for read cancellation and a static read suppression outcome; no host ids, conversation ids, turn ids, durable checkpoints, hashes, text or secrets are logged.

## Testing strategy

Write notifier tests first and observe red, then implement. Robolectric tests inspect posted Android notifications and cover post-before-read, read-before-post, initial snapshots across notifier restart, repeated/absent cancellation, host isolation, null/older-daemon facts, equality/zero/unsigned comparison, pending preference gates, retained ledger, subsequent unread completions and prompt delivery after reads and unrelated updates.

Source wiring tests exercise actual repository list/push frames feeding the notifier read projection, plus real notifications from source alerts, host isolation, live generation reset and cancellation independent of Running/WaitingForAnswer. Run `AttentionNotifierTest`, `HostConversationSourceAttentionTest`, `HostConversationSourceTest` and `ConversationAttentionTest`, lint, assemble, formatting and final full unit/shared suite plus `scripts/pre-verify.py --gradle`. No device-only test is added: cancellation is fully reachable in Robolectric without FCM/background wake.

Preserve the existing rung-3 shared-mark scenario unchanged. The dispatcher owns a fresh full live gate after verification and must report executed/failed/skipped counts and explicit passage of `InteractiveStreamE2ETest.interactiveTurn_attentionDot_followsARealTurn`. Deterministic notification proof is separate evidence, not a claim that live execution passed.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/push-messaging-service.md`, “Attention alerts and the tap route (#685)”, “Logging (#685)” and “Testing (#685)” with daemon read cancellation, host isolation, unchanged local fallback and retained ledger semantics. Record the distinction between deterministic notification proof and dispatcher-produced shared-mark live scenario evidence.

## Security review

**Verdict:** PASS

- [Trust boundaries] Cancellation accepts only repository-merged `ConversationReadMarks` under the source's host/repository generation guards. Both unsigned facts must be present; local position, Idle and cached list fields cannot assert confirmed coverage.
- [Tokens] No credentials are created or consumed. Read facts remain in memory; identity fields are equality keys and never logs or rendered text.
- [Files and storage] The existing bounded digest ledger remains in `noBackupFilesDir`, with temp-then-rename writes. Read suppression must still record its digest before all gates; do not erase the ledger on cancellation.
- [Android attack surface] No component, intent filter or remote action is added. Cancel only the exact length-prefixed host/conversation digest and id zero; never use `cancelAll`. Existing immutable pending intents and validated tap routing stay intact.
- [Cryptography] Reuse SHA-256 solely for notification identity and ledger digests. Noise, Keystore, pairing and key lifecycle stay untouched.
- [Network and I/O] Reuse decoded read facts and the existing inbound consumer. No new request, socket, retry loop or frame parser; transport bounds and authentication remain unchanged.
- [Errors and logs] Use static outcomes only; do not log notification text, ids, read checkpoints, decrypted data or credentials. Existing debug-only RelayLog controls remain.
- [Concurrency] SHOULD FIX: a read can land while `notificationsEnabled.first` is suspended. Read coverage again inside the same mutex as post/cancel, and prove this ordering with a controlled flow test. Compare changed checkpoints per host/conversation so an unrelated update cannot clear a later prompt.
- [Concurrency / generation isolation] The first revision did not resolve selection isolation: a synchronous accessor over an asynchronous repository cache still selected R1 while R2 emitted events. The rework revision below resolves this by selecting the authenticated active connection synchronously through `liveRepository`, including exact transport identity and owner lifecycle. Covered triggers recheck that repository; delayed collection cannot cancel or spend R2's unread completion using R1 facts.
- [Completion identity] A conversation-wide latest comparison cannot prove that a replayed completion is unread. Preserve the decoded envelope's unsigned durable identity through `TurnEnd` and `AttentionAlert`; suppress a known completion exactly when the confirmed mark covers that identity, independently of a newer latest. Missing identity retains the existing conversation-wide fallback. Prompts never use this predicate; ledger, storage and logs remain unchanged.
- [Late confirmation] Retain a known completion checkpoint as app-authored, non-rendered decimal unsigned metadata on its Android notification. On changed read facts, inspect only this app's active notification with the exact host/conversation digest and id zero under `notificationLock`; both the trigger facts and authoritative current repository must cover its checkpoint. Notification replacement removes the old metadata, so a later prompt or newer unread completion cannot inherit the replay's coverage. Missing or unparseable metadata uses the existing latest-coverage fallback. No token, message, raw routing id, extra file, backup rule or ledger entry is added; the OS-owned notification retains the numeric checkpoint across notifier/process restart, and no checkpoint enters logs.
- [Threat model] Authenticated hostile daemon facts can suppress that daemon's own alerts but cannot cancel another host's tag. Relay delays may delay cancellation; Noise prevents forged read facts. Rooted-device token theft and screenshot/accessibility leakage remain handled by the existing protocol/storage/UI boundaries; no new plaintext or UI surface is added here.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

### 2026-10-08 — Completion delivery can overtake read-fact publication

The executed `aCompletionDeliveredBeforeTheReadFactCollectorStillPostsItsNewUnreadCheckpoint` probe failed: Unconfined consumers resumed inside the repository's `tryEmit` while `onInbound` still held the previous latest durable id. A future unread completion was spent as already read. This is within the ticket's completion/read race contract, not an unrelated bug.

`onInbound` now records a valid `turn_end` durable identity before routing that completion, using the same recording helper as its retained finally path. `ConversationListProjection.currentReadMarks` and `RemoteConversationRepository.currentReadMarks` expose the existing atomic facts, without another flow collector, request, cache or checkpoint fold. `HostConversationSource.currentReadMarks` selects the exact current host/repository under its generation guard, retains accepted facts while offline, and does not inherit old support for a replacement. `AppModule` injects this synchronous lookup into the notifier. Its mutex-protected post/cancel checks consult those current facts, while the projected flow remains the cancellation trigger. This also prevents a queued covered projection from cancelling a newly unread notification.

Revised size: approximately 700 written lines across five production files, two test files and the plan; three new internal accessors, one production notifier consumer update, no new type, unchanged acceptance criteria and fewer than ten new decision branches. Within all sizing ceilings. Include `RemoteConversationRepositoryTest` in focused checks and rerun lint/build after the ordering change. Preserve the failing probe and the existing live scenario; no wire field or event type changes.

### 2026-10-08 — Verifier rework: completion checkpoints and authoritative repository selection

Finding 1 showed that an unseen replay at entry 5 could replace a prompt while list facts were read 5/latest 6. `TurnEndPayloadDto.toEvent` now accepts the envelope's existing durable identity, retained on `LiveSessionEvent.TurnEnd` and `AttentionAlert`. The notifier compares that completion with the current confirmed mark inside its notification mutex. Cancellation still requires coverage of the conversation's current latest entry. No DTO or wire field changes; optional defaults preserve existing producers and consumers.

Finding 2 showed that `currentReadMarks` could synchronously read the wrong repository. `RelayRepositoryCoordinator.currentRepository.value` and its replay cache now select through `liveRepository` under the coordinator's existing monitor: active scope, exact supervisor transport, and the current connection's own Open pump. Collection retains the existing Open-gated `flatMapLatest`/`stateIn` publication and cancellation lifetime. The registry uses a small `hostConversationConnection` adapter, also used by the controlled notification harness, so no test substitutes a synthetic repository flow for production wiring. Source generation guards consume the authoritative value unchanged. Offline read-fact retention remains intact.

Test-first evidence: the replay/prompt regression executed once and failed because the completion replaced the prompt; both controlled coordinator regressions executed and failed because zero notifications were posted. The coordinator harness delays real pump-state collection while allowing the repository's inbound consumer and independent live-event switch to deliver entry 6. Test both an R1 cache and a null cache with retained R1 facts; check a stale covered cancellation trigger against R2, then release publication and prove a current read cancels. The existing rung-3 scenario remains unchanged and requires dispatcher-produced full-live evidence after verification.

Security re-review: PASS against the revised contracts. Authenticated envelope identity stays host-local and in memory; no content, checkpoint or identifier enters logs or storage. Ledger-before-gates, exact digest/id cancellation, immutable taps, cryptography, Android components and I/O remain unchanged. Synchronous selection introduces no new scope or job; it takes the existing coordinator monitor without suspension, and the coordinator does not call into the host source while holding it. Include `AttentionNotifierCoordinatorTest`, `RelayRepositoryCoordinatorTest`, `RelayConnectionFactoryTest` and the DTO mapping coverage in focused checks.

The existing retired-question-source test now observes the delayed repository through collection and asserts the synchronous value is already null at transport replacement. Its rejection and no-outbound-write checks remain intact; only the obsolete expectation that `.value` exposes R1 is replaced.

Revised size forecast: approximately 1,050 total written lines including this plan and all tests, nine production files, no new exported type, four new internal accessors/helpers across the complete ticket, four production consumers updated simultaneously, three acceptance criteria and fewer than ten new decision branches. No in-flight branch overlaps these files. All sizing ceilings hold.

### 2026-10-08 — Verifier rework: reconcile the currently posted completion on late confirmation

Finding 1 on `8c93a98fd` identified the post-before-read sibling: entry 5 posts while read is unknown or 4 and latest is 6, then confirmation of read 5 cannot cancel it through latest coverage. Executed source-to-Android probes reproduce both initial confirmation and an advancing mark, and a notifier-restart probe reproduces the same retained notification. Replacement-prompt and newer-unread-completion controls already pass and must stay green.

`post` will attach only a known completion's unsigned checkpoint to the Android notification's extras under a private static key. The read collector will read the currently active notification's checkpoint by exact digest tag/id zero inside its existing mutex and use `coversCompletion` with both projected and authoritative read facts. A prompt or a completion without identity replaces the notification without that extra; a newer completion replaces it with its own checkpoint. This directly reconciles the notification still present, avoids an independently stale in-memory map, and supports initial confirmation after notifier restart. No new persisted application state, wire field, source job, notification appearance/copy or ledger format is introduced. Unidentified notifications retain latest coverage, including notifications posted by older app versions.

Security re-review: PASS. The added late-confirmation finding above closes the checkpoint-lifetime gap. Numeric metadata is bounded by the existing unsigned envelope decoder, written/read solely as a checkpoint, never displayed, logged or used as a routing key. Only this app's exact host/conversation tag/id is inspected or cancelled; there is no `cancelAll`, new component, credential, transport, file, exported declaration or coroutine. Recheck current repository coverage to reject stale generation triggers. The existing mutex covers notification inspection, replacement and cancellation without suspension; OS removal remains harmless and replacement cannot retain a previous completion's checkpoint.

Focused coverage adds four real-repository source tests for initial/advancing confirmation, duplicate replay, replacement prompt, replacement newer completion, subsequent unread delivery and host isolation, plus notifier tests for restart, unsigned zero/large identity and stale authoritative facts. Preserve the existing full-live scenario unchanged and hand its fresh execution/counts to the dispatcher after verification.

Revised forecast: approximately 1,250 written lines across the existing nine production files, no new exported declaration or consumer update, three acceptance criteria and fewer than ten new decision branches. No in-flight branch overlaps the three rework code/test files. All sizing ceilings hold; documentation requirements remain pending for the documentation stage.
