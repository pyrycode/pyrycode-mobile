# #2213 — Retain host channel pin timestamps

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt`: `Conversation` gains one trailing nullable default; existing constructors remain compatible.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt`: `ConversationSummaryDto` and its mapper own list decoding.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt`: mutation DTO and `toConversation` own confirmed updates.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `CachedConversation`, `toRecord`, `toDomain`, host hashing and atomic metadata replacement.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot` validates before mutation; `upsertConversation` replaces by id while retaining session/agent/archive projection rules.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` accepts correlated and unsolicited updates and drops malformed records.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: connection-local request registration and captured confirmed command results.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt`: create, promote, rename, archive and mute await confirmed results without republishing them.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt`: `setSystemPrompt` awaits inbound confirmation without writing the list.
- `app/src/main/java/de/pyryco/mobile/data/repository/WorkspaceCommands.kt`: `changeWorkspace` awaits inbound confirmation and retains its placeholder session return.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` and `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceTest.kt`: disconnected cache restore and confirmed-list replacement, exercised together in the reconnect probe.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt`: decode and map conventions.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryAgentTest.kt`: small channel-backed pump and correlated mutation fixture.
- `app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheTest.kt`: fresh-instance equality and legacy document fixtures.
- `docs/knowledge/features/data-model.md`: portable `kotlinx.datetime.Instant`, domain free of serialization annotations.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-layout.md`: backup-excluded app-private root, exact ISO text, and the cache-local record mirror requirement.
- `docs/knowledge/features/remote-conversation-repository.md`: connection-owned single inbound collector and projections.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`, `conversations`, `conversation_updated`, and Security model: wire source of truth, shipped by Core #3538.

## Context

Mobile currently drops the host's confirmed `pinned_at`. Retaining it in the domain and metadata cache is one readable/persisted contract; commands and rendering belong to later slices of #2205. No decision record is needed. #999 is the nearest analogue (57 inserted, 3 deleted scoped lines); strict timestamp, cache and invariant coverage increase this change to approximately 450 written lines including this plan. Four production files, zero new exported types, zero required consumer migrations, three acceptance criteria and no new state machine. Both sizing passes fit the limits. Remote feature-branch inspection found no overlapping production files.

## Design

Add trailing `Conversation.pinnedAt: Instant? = null`. Both DTOs gain nullable `pinnedAt` with wire name `pinned_at`, the existing `InstantIso8601Serializer`, and null default. Both mappers copy it directly. Missing and explicit null mean unpinned, including when replacing a pinned row; malformed non-null values fail at decode before projection mutation.

Add nullable ISO-text `CachedConversation.pinnedAt` with null default, serialize using `Instant.toString`, restore using `Instant.parse`. Keep version 1 and existing host namespaces and atomic writes. Do not repair unrelated missing cache fields. `ConversationListProjection` needs no implementation change: its authoritative replacement and existing metadata preservation already apply. No UI, new dependencies or new commands.

Confirmed `conversation_created` and `conversation_updated` publication belongs to `routeInbound`, before request completion. Create replies retain their correlated-only admission; updates remain unconditional. `RelayRequests.sendAndAwaitConversation` returns the row captured by the first reply, including projection-preserved metadata. All seven former command-side upsert sites use it; later duplicate replies and unsolicited updates never change the captured command result. Malformed replies still fail the command with a serialization exception, without publication.

## State and concurrency model

No jobs, scopes or flows are introduced. The connection-owned inbound collector decodes and publishes records before completing the request, in arrival order. Commands return the captured result without writing the list after suspension: atomic updates alone cannot stop an older reply from overwriting a later record. Each existing request entry retains its captured row until the caller's `finally` removes it; completion makes that capture visible to the awaiting caller. Cancellation never undoes inbound publication, and teardown still fails outstanding requests. Host-local repository instances prevent cross-host identity sharing. File I/O retains its injected dispatcher and mutex. Screen exit/background socket closure and cancellation remain owned by the existing connection lifecycle. Disk restore retains the pin independently of a live socket.

## State transitions and identity reuse

| Event | Required unit coverage |
| --- | --- |
| Valid/absent/null summary or mutation timestamp; malformed non-null input | `PinnedConversationPayloadTest` strict decode and exact mapping tests |
| Unsolicited pin, unpin and re-pin, legacy omission | `RemoteConversationRepositoryPinTest.confirmedUpdatesReplacePinsAndKeepProjectionMetadata` |
| Correlated reply plus duplicate push, delivered in either order | `RemoteConversationRepositoryPinTest.replyAndPushDeliveryIsIdempotentInEitherOrder` |
| Queued, differing reply/push records before command resumption, across every affected command | `RemoteConversationRepositoryPinTest.queuedCommandRepliesCannotOverwriteLaterConfirmedPins` |
| Malformed command reply followed by valid delivery; cancellation before caller resumption | `RemoteConversationRepositoryPinTest.malformedCommandRepliesFailWithoutPublishing` and `cancelledCommandsKeepInboundConfirmedPins` |
| Malformed later list row or update, followed by valid delivery | `RemoteConversationRepositoryPinTest.malformedPinCannotPartiallyMutateTheListOrStopUpdates` |
| Same conversation id on two hosts | `RemoteConversationRepositoryPinTest.equalConversationIdsRemainConnectionLocal` and cache host collision probe |
| Cold restore while offline, then reconnect snapshot replacement, reorder and empty list | `RemoteConversationRepositoryPinTest.restoredPinsSurviveOfflineAndReconcileWithFreshSnapshots` |
| Fresh cache instance, pinned/null records and repeated overwrite | `FileConversationCacheTest`: `pins round-trip exactly and stay isolated for colliding host conversation ids` |
| Old document without the pin field; malformed persisted pin | `FileConversationCacheTest`: `old metadata documents without a pin load as unpinned` and `malformed cached pin withholds the document without rewriting it` |

## Error handling

The existing malformed-snapshot/update guards retain the entire previous live list and emit static coded logs. Cache parse failures retain existing empty-read/coded-error behavior without rewriting stored bytes; writes keep `Result<Unit>`. No timestamp, payload, owner identifier, message text, token or exception detail is added to logging or UI errors.

## Testing strategy

Write strict malformed DTO rejection tests first and observe assertion failures before implementing. Add exact nanosecond/offset instant mapping, null/absent compatibility, repository invariant probes using the production inbound path, and fresh-instance file-cache equality/legacy/corruption tests. Probes exercise duplicate delivery in both orders, two host identities with one id, malformed multi-row atomicity, single/empty/reordered snapshots and reconnect after restore. There is no operator UI change or explicit live obligation, so JVM coverage is sufficient. Run affected new classes and existing DTO, projection, cache and agent/mute repository classes on Linux. Run lint, assembly, Spotless, then final assembly and `scripts/pre-verify.py --gradle` after merging main. Full suites remain dispatcher-owned.

## Open Questions

None.

## Security review

**Verdict:** PASS

- **Trust boundaries:** Strict nullable Instant deserialization in both DTOs rejects malformed timestamp strings and non-string values before `applySnapshot`/`onInbound` can mutate state. Test the later-invalid-row case to prevent partial publication.
- **Tokens:** No credential generation, storage or exposure is introduced; pin metadata contains no authentication material.
- **Files and storage:** Keep the existing plaintext app-private `noBackupFilesDir` cache threat model. Pin text is a record value, never a path; host ids remain hashed. Atomic replacement and version 1 null defaults preserve kill/restart compatibility. A malformed cached pin withholds the document using existing error handling.
- **Android attack surface:** No component, intent, provider, keyboard, WebView or rendered text changes.
- **Cryptography:** Existing Noise IK and key stores are untouched; timestamps do not affect keys or nonces.
- **Network and I/O:** Existing frame caps, TLS, timeout and reconnect controls remain in force. No new network verb or I/O operation.
- **Errors/logs:** Reuse static malformed/cache codes; no payload, timestamp, host/conversation id, credential or decrypted content enters logs through this change.
- **Concurrency:** MUST FIX from verifier finding 1, addressed by sole inbound publication. Atomic StateFlow mutation does not prevent delayed commands from republishing stale replies. `sendAndAwaitConversation` captures the first reply's projected result before completion, then returns it without writing; queued newer records remain authoritative even after command resumption. Cancellation cannot undo a published record; the existing request cleanup and teardown retain ownership. No new job, scope, mutex or suspension within publication.
- **Threat model:** Hostile authenticated frames are rejected atomically; delayed/repeated valid records use host-confirmed arrival order without inventing recency. A malicious relay still cannot forge Noise plaintext; availability remains the supervisor's responsibility. Rooted-device credential theft remains with Keystore wrapping, and existing plaintext cache access is unchanged. UI screenshot/accessibility/token-entry leakage has no new surface in this data-only slice.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/data-model.md` and `mobile-protocol-v2-wire-layer-application-payloads.md`: document nullable host-confirmed `pinnedAt`, strict malformed timestamp rejection and missing/null compatibility.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-layout.md`, “The cache-local record must mirror every Conversation field”: document `CachedConversation.pinnedAt`, unchanged version 1, exact instant retention, legacy defaults and host isolation.
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md`, State & concurrency model, and conversation write overviews: replace the duplicate-publication rationale with inbound arrival-order publication and captured command return values; record offline restore/reconnect reconciliation in repository/cache overviews.

## Revisions

- 2026-10-17 — Verifier finding 1 exposed stale command republication after a queued newer pin update. Move all seven caller-side publication sites into inbound routing, including correlated creates, and capture command return values at the first reply. Add queued differing-value, malformed-reply and cancellation probes. Rework remains one pin-retention deliverable, below 1600 written lines, with no exported types, one constructor consumer update and seven command sites. Branch #1947 overlaps `RemoteConversationRepository.kt` in separate context-usage/session-transition blocks; edits here stay local.
