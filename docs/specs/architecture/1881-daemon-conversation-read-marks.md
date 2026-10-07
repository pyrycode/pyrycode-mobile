# #1881 — Daemon conversation read marks

## Files read

- `data/network/ConversationsPayload.kt`: `ConversationSummaryDto` and `toConversations`, list decode boundary and existing metadata.
- `data/network/ConversationResponseDto.kt`: shared update/create decoder and `toConversation`.
- `data/repository/ConversationListProjection.kt`: `applySnapshot`, `upsertConversation`, `observeSnapshots`, and atomic list writers.
- `data/repository/ConversationCommands.kt`: `setMuted`, the confirmed update precedent.
- `data/repository/RelayRequests.kt`: waiter registration, error mapping, cancellation and teardown sweep; reused unchanged.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, correlated update folding before completion, and connection-owned collector.
- `data/repository/ConversationRepository.kt`: optional defaults and Result-returning boundary conventions.
- `data/repository/StableConversationRepository.kt`: `switchToLive` and host-prompt snapshot-or-result delegation.
- `data/repository/FakeConversationRepository.kt`: `requestHistory` uses message positions starting at 1 as durable fake ids.
- `data/repository/CachingConversationRepository.kt`: interface delegation leaves live readings outside disk restoration.
- `data/network/ReplySuggestionPayloads.kt`: strict unsigned decimal validation precedent.
- `data/network/RelayLog.kt`: debug-only content-free logging.
- `docs/knowledge/features/remote-conversation-repository.md`: channel-backed tests must use `runCurrent`, not `advanceUntilIdle`.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: nullable defaults and primitive coercion require explicit validation.
- `docs/knowledge/features/stable-conversation-repository.md`: replacement cancels the old observation; one-shots stay on their entry delegate.
- `docs/knowledge/features/data-model.md`: adding persistent conversation fields would require tracing cache storage; this live reading avoids that migration.
- `RemoteConversationRepositoryMuteTest`, `ConversationListProjectionTest`, and commit `133471a8`: nearest analogue, 351 added lines.
- Daemon repository `docs/protocol-mobile.md`: “Marking a conversation read”, “Application envelope”, application type rows, and “Security model”; wire source of truth.

## Context

Mobile needs a live, host-local shared read checkpoint before viewport and attention consumers can agree with desktop. This slice adds only the observable facts and confirmed repository command. Local `ReadPosition`, UI, notifications, history identities, and cache formats stay as they are. No decision record is needed.

Sizing: one independently checkable repository read/write contract, three acceptance criteria, approximately 950 written lines including tests and plan, two new exported types, no existing consumer requiring a simultaneous signature update, and fewer than ten reject branches. The higher forecast than the refiner accounts for exact reply-type/identity validation and empty-snapshot probes. No overlapping remote feature branch touched the design files at planning time.

## Design

Add `ConversationReadMarks(readUpTo: ULong?, latestEntryId: ULong?)` beside the repository contract. `observeReadMarks(conversationId)` emits null before either live fact is heard, including an older daemon omitting both. A valid zero is retained. A known read field establishes support on this connection; the latest id may still be unknown after an update push. The observable is separate from cached `Conversation` rows, with interface default null and stable disconnected fallback null.

List and update DTOs add nullable, defaulted read fields using one strict unsigned-decimal serializer. Only unquoted integer tokens from zero through `ULong.MAX_VALUE` are accepted; malformed numeric kinds throw a static decode error inside existing inbound guards. Explicit null is unavailable, never zero. Mapping other conversation fields stays unchanged.

`ConversationListProjection` holds rows and per-conversation read facts in one atomic state. Snapshots replace list rows as before while merging each supplied fact by unsigned maximum; upserts merge only their supplied read mark and preserve the latest id. Omitted values never clear held facts. The fact ledger survives an empty or excluding snapshot within the connection, preventing a later stale reappearance from regressing it. A new remote repository starts with no ledger. Host ownership is the existing one-repository-per-host-connection ownership.

`markConversationRead(conversationId, upTo: ULong): Result<ULong>` sends the two required fields with an integer `up_to` and returns the confirmed stored mark, which may be less than the requested id. `ConversationCommands` tracks outstanding read request ids and target identities, reusing `RelayRequests` registration/await/teardown. Its routing hook consumes only those correlated replies: require `conversation_updated`, the requested conversation, and a present valid mark before folding and completing. A plain ack or another response type is failure. Unsolicited updates continue through the ordinary fold. No optimistic mark and no automatic retry.

Stable reads switch to the live delegate; writes snapshot it once and return failure when absent. Cache wrappers inherit both via delegation. Fake reads derive latest ids from its numbered history and writes atomically apply `max(held, min(upTo, latest))`; unknown targets fail. No new dependency.

## State and concurrency model

All live data belongs to the existing connection scope. Rows and the read ledger share `MutableStateFlow.update` CAS merges so caller upserts cannot lose inbound facts. The outstanding request identity table is concurrent, registered before send and removed in finally. The sole inbound collector validates and folds before completing the waiter. Cancellation propagates, and existing `failAllPending` handles transport closure or scope teardown. The lifecycle driver still closes the socket on background. The stable facade never retries a pending write on a replacement host/connection.

## Error handling

Decode errors drop malformed pushes/snapshots without terminating the collector. A malformed/missing mark, wrong response type or wrong target returns a sanitized `protocol.malformed_reply` failure and changes no read facts. Server refusal returns a sanitized domain failure with allowlisted codes and retryability; disconnection returns a fixed failure. Caller cancellation is rethrown. Debug structured events contain static outcomes only, never ids, marks, DTOs, names, paths, daemon messages, credentials or bytes.

## Testing strategy

Test first with JVM tests using the real DTOs, projections and remote repository, channel-backed pumps and `runCurrent`. Assert zero versus absence, full unsigned bounds, numeric kind/range rejection, collector survival and unchanged metadata. Probe list/push order permutations, duplicate and stale replies/pushes, overlap at the beginning/middle/end of lists, one/empty/excluding snapshots, independent identities and hosts, and delegate replacement between arrivals. Test exact outbound integer JSON, clamped/no-op completion, unsolicited non-completion, wrong correlation/type/identity, malformed reply, server errors, send refusal, mid-await teardown and cancellation. Fake and stable contract tests prove clamping, delegation, disconnect absence and in-flight connection ownership. Run existing payload/list/mute/agent/fake/stable/cache tests as focused regression coverage.

No UI, shared screen or device tests change. This data-only slice has no operator flow or live scenario; viewport/UI slices own those. Run focused unit tests, lint, assembleDebug and formatting, then push before the final main merge and whole unit suite, assembleDebug and `scripts/pre-verify.py --gradle`.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/remote-conversation-repository.md` (read projection and confirmed commands) and `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` (application payloads) with absence versus zero, confirmed command/reply, and missing-latest preservation. Keep daemon `docs/protocol-mobile.md` as the wire specification.

## Security review

**Verdict:** PASS

- [Trust boundaries] Strict unsigned token validation rejects quoted, negative, fractional, exponent, boolean, composite and overflowing values. Nullable absence cannot become checkpoint zero. Reply type and target validation precede folding; a valid payload for another conversation cannot satisfy this write.
- [Tokens] No new credential handling; the command carries only a conversation identity and durable ordinal inside the existing authenticated pump. Neither is logged.
- [Files and storage] Live facts are not persisted, restored or used as paths. Existing cache and Keystore storage are unchanged.
- [Android attack surface] No component, intent, WebView, notification or UI entry point changes.
- [Cryptography] Existing Noise IK and TLS remain unchanged; no new cryptographic primitive or secret comparison.
- [Network and I/O] Existing transport frame bounds, timeouts and reconnect backoff stay authoritative. Existing waiter teardown prevents hanging on disconnect. Retry belongs to the viewport slice.
- [Errors and telemetry] SHOULD FIX: sanitize server failure codes/messages at this new Result boundary and log only static classified outcomes, including malformed reads. Parser diagnostics and untrusted fields must never be logged.
- [Concurrency] Atomic rows/facts state and unsigned maximum prevent lost updates and regressions; the request table is registered before send and removed on every exit. Cancellation must propagate rather than become a success/failure value.
- [Threat model] Malicious relay: existing AEAD prevents content forgery; delayed/reordered valid facts cannot regress the ledger. Hostile daemon: strict decoding and request identity/type validation; malformed frames do not stop collection. Token theft and UI-side leakage retain existing Keystore/revocation and UI mitigations; this slice handles neither secrets nor rendering. Authenticated daemon lies about a syntactically valid mark are outside the client’s trust contract and remain daemon-owned.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
