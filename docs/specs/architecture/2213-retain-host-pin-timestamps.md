# #2213 — Retain host channel pin timestamps

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt`: `Conversation` gains one trailing nullable default; existing constructors remain compatible.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt`: `ConversationSummaryDto` and its mapper own list decoding.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt`: mutation DTO and `toConversation` own confirmed updates.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `CachedConversation`, `toRecord`, `toDomain`, host hashing and atomic metadata replacement.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot` validates before mutation; `upsertConversation` replaces by id while retaining session/agent/archive projection rules.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound` accepts correlated and unsolicited updates and drops malformed records.
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

Add nullable ISO-text `CachedConversation.pinnedAt` with null default, serialize using `Instant.toString`, restore using `Instant.parse`. Keep version 1 and existing host namespaces and atomic writes. Do not repair unrelated missing cache fields. `ConversationListProjection` needs no implementation change: its authoritative replacement and existing metadata preservation already apply. No UI, new dependencies, commands or ordering changes.

## State and concurrency model

No jobs, scopes or flows are introduced. The connection-owned inbound collector continues to decode before atomic `MutableStateFlow.update`; host-local repository instances prevent cross-host identity sharing. File I/O retains its injected dispatcher and mutex. Screen exit/background socket closure and cancellation remain owned by the existing connection lifecycle. Disk restore retains the pin independently of a live socket.

## State transitions and identity reuse

| Event | Required unit coverage |
| --- | --- |
| Valid/absent/null summary or mutation timestamp; malformed non-null input | `PinnedConversationPayloadTest` strict decode and exact mapping tests |
| Unsolicited pin, unpin and re-pin, legacy omission | `RemoteConversationRepositoryPinTest.confirmedUpdatesReplacePinsAndKeepProjectionMetadata` |
| Correlated reply plus duplicate push, delivered in either order | `RemoteConversationRepositoryPinTest.replyAndPushDeliveryIsIdempotentInEitherOrder` |
| Malformed later list row or update, followed by valid delivery | `RemoteConversationRepositoryPinTest.malformedPinCannotPartiallyMutateTheListOrStopUpdates` |
| Same conversation id on two hosts | `RemoteConversationRepositoryPinTest.equalConversationIdsRemainConnectionLocal` and cache host collision probe |
| Cold restore while offline, then reconnect snapshot replacement, reorder and empty list | `RemoteConversationRepositoryPinTest.restoredPinsSurviveOfflineAndReconcileWithFreshSnapshots` |
| Fresh cache instance, pinned/null records, old documents without field, repeated overwrite | new pin-focused tests beside existing `FileConversationCacheTest` metadata tests |

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
- **Concurrency:** Existing connection isolation, atomic StateFlow mutation and cache mutex remain authoritative; no new background work or suspension between live-state read and replacement.
- **Threat model:** Hostile authenticated frames are rejected atomically; delayed/repeated valid records use host-confirmed arrival order without inventing recency. A malicious relay still cannot forge Noise plaintext; availability remains the supervisor's responsibility. Rooted-device credential theft remains with Keystore wrapping, and existing plaintext cache access is unchanged. UI screenshot/accessibility/token-entry leakage has no new surface in this data-only slice.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-17
