# Assistant parent attribution (#1826)

## Files read

- `data/network/InteractivePayloads.kt`: `AssistantDeltaPayloadDto`, `toEvent` and sibling tool defaults define the decode boundary.
- `data/model/LiveSessionEvent.kt`: `AssistantDelta` is the typed live/history event.
- `data/model/Message.kt`: `Message`, `AssistantSegment` and `SegmentDelta` carry retained assistant rows.
- `data/repository/HistoryPageReducer.kt`: `withAssistantDelta`, `mergeRows`, `deltaRows`, `legacyDeltaMatches` and `withJoinedSegments` own live/history/cache reconciliation.
- `data/repository/ThreadProjection.kt`: `applyAssistantDelta`, `mergeHistoryPage` and `observe` keep routing conversation-local and update atomically.
- `data/repository/RemoteConversationRepository.kt`: the interactive inbound arm decodes and projects the same event it publishes.
- `data/repository/CachingConversationRepository.kt`: `observeMessages` retains reconciled rows in memory across reconnect.
- `data/cache/FileConversationCache.kt`: `CachedSegment.toDomain` and message serialization deliberately omit attribution.
- `data/network/ToolPayloadsTest.kt`, `data/repository/AssistantSegmentTest.kt`, `HistoryReconciliationTest.kt`, `RemoteConversationRepositoryTest.kt`: defaults, segment seams, cache and repository test patterns.
- `docs/knowledge/INDEX.md`, `features/data-model.md`, `features/conversation-cache.md`, `features/remote-conversation-repository.md`: ownership and compatibility conventions.
- `docs/knowledge/decisions/0007-assistant-reply-segment-key-and-seam-join.md` and `features/remote-conversation-repository-assistant-reply-segments.md`: never key by parent or rely on segment adjacency alone for duplicate identity. Current reducer splits to delta atoms before rejoining.
- Wire SSOT: `../pyrycode/docs/protocol-mobile.md`, `assistant_delta` and `Security model`, read from the dispatcher-provided `PYRYCODE_SRC` checkout.

## Context

Mobile drops an already-shipping grouping hint before either live or history projection. This data slice supplies it to the sibling UI ticket. No new lane, completion protocol, UI or cache schema is introduced, and no decision record is needed.

Sizing: approximately 650–800 total written lines, four production files, three new focused test files plus existing coverage; no new exported types, no mandatory fixture migration, three acceptance criteria and no new rejection branches. The #810 analogue wrote 381 inserted and 13 deleted lines; this slice adds reconciliation probes. No other fetched numeric feature branch overlaps the four planned production files.

## Design

Add trailing `parentToolUseId: String = ""` to `AssistantDeltaPayloadDto`, `LiveSessionEvent.AssistantDelta` and `Message`. On `Message` this is assistant attribution; tool attribution remains on `ToolCall`. The retained field belongs on `Message` rather than `AssistantSegment` because legacy cache rows may have no segment record. The mapper copies it verbatim. Absent JSON defaults empty; explicit null still rejects the envelope.

`withAssistantDelta` fills empty attribution on already-held assistant rows of the same wire turn, including a duplicate replay, before applying the existing sequence/key guards. It preserves known non-empty attribution on append and newly opened segments even when the next frame omits the hint. No comparison, identity, text or placement rule uses the parent.

Before `mergeRows` splits rows, fill empty assistant attribution from same-turn evidence on either side. A non-empty held value wins; otherwise the first non-empty incoming value fills the gap. The lookup key is the segment's wire turn id, or the bare assistant message id for a legacy whole-turn row. Both sides receive the retained value so legacy replacement and segment reconstruction cannot discard it. `deltaRows` already uses `Message.copy`; `withJoinedSegments` explicitly retains a non-empty value when the opener is unattributed. Split/rejoin, duplicate suppression and key repair therefore retain attribution without modifying their identities or ordering.

Disk serialization is unchanged. Cache-only rows remain empty until same-conversation wire/history evidence arrives. Reconciled in-memory rows retain known attribution across reconnect; a process restart can lose it until fresh evidence arrives, as the ticket permits.

## State and concurrency model

No new jobs, flows, dispatcher or mutable ledger. Pure list folds stay within `ThreadProjection`'s existing atomic state updates, and cache reconciliation stays in its existing collector. Maps used to fill attribution are local to one conversation's merge; nothing is shared across conversations or connections. Existing lifecycle cancellation and background socket closure remain the owners of connection lifetime.

## Error handling

The existing strict decode/drop boundary rejects explicit null and malformed payloads without exposing exceptions or parent strings. Empty means absent/main/cache-unknown; it never erases a known parent. Conflicting non-empty hints retain the held value and grant no authority. All current sequence, collision and replay guards remain intact and silent.

## Testing strategy

Tests first, using real JSON decoding and production folds. `AssistantDeltaPayloadsTest` proves verbatim mapping, absent/empty defaults and null rejection. `AssistantParentAttributionTest` exercises `ThreadProjection.observe` with main and two child lanes starting independently at zero, append, replay, history and conversation isolation. It compares keys/content/order against the identical parentless script.

History probes exercise overlap in both arrival orders, prefix/middle/suffix holes, older-page prepend, empty and one-entry pages, differently split held/history copies, duplicate live replay and reconnect/cache reconciliation. Legacy rows with and without recoverable segment records must gain attribution from evidence. A real file-cache round trip proves no field/schema migration and then enrichment through the repository cache wrapper.

Run focused new tests and existing `AssistantSegmentTest`, `HistoryReconciliationTest`, `ThreadProjectionTest`, `ToolPayloadsTest`, `RemoteConversationRepositoryTest` and `CachingConversationRepositoryTest`; lint, assemble, Spotless and final whole unit/shared suite plus `scripts/pre-verify.py --gradle`. This is a data contract with no changed operator action/rendering; no new live, scripted or device-only scenario is required.

## Open Questions

None. Representation and conflict precedence are settled above.

## Security review

**Verdict:** PASS

- [Trust boundaries] The DTO/mapper is the sole JSON-to-event boundary on both lanes. Parent strings remain verbatim inert grouping hints; no sink interprets them as a command, path, URL or authority. Explicit null remains rejected. Conversation routing is supplied by the existing projection/page caller, never by the parent.
- [Tokens, secrets and credentials] No credentials are created, read or changed. Parent strings can contain sensitive data and must not enter logs or exception messages. Add tests for hostile-looking values and cross-conversation isolation.
- [Files and storage] The cache schema and its app-private custody are unchanged. Attribution is not serialized, used in filenames or used as a cache key. Legacy rows are enriched only in memory by same-turn evidence.
- [Android attack surface] No component, intent, provider, push handler, renderer or WebView changes; this slice adds no external entry point beyond the existing authenticated decode.
- [Cryptography] Noise session, nonce counters and Keystore storage are unchanged. Attribution grants no capability and is never compared to secrets.
- [Network and I/O] Existing envelope caps, transport timeouts and authenticated inbound collector remain in force. No network request uses parent data, and no allocation is sized by it beyond storing the decoded string already bounded by the envelope.
- [Errors, logs and telemetry] The reducer and mapper remain silent. No new logging is needed for a retained optional field; existing lifecycle logs stay content-free. Do not describe retained parent data as safe to log.
- [Concurrency] Local immutable copies inside existing atomic updates; no extra subscription, coroutine or state ledger. Reconnect evidence is merged without moving held rows.
- [Threat model] A hostile relay still encounters Noise authentication/AEAD and existing bounded transport; reorder/replay probes operate on typed evidence without weakening wire protections. A hostile daemon can supply arbitrary grouping hints but cannot gain authority, cross-conversation lookup or execution. Rooted-device token theft and UI screenshot/accessibility leakage stay with existing crypto and UI owners; this data-only change adds neither storage nor render surfaces. Protocol prompt-injection, server-id race, static-key compromise and deferred rate limiting are unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-06

## Revisions

- 2026-10-06, verifier finding 1: `withAssistantParents` filled only empty hints, so an older conflicting incoming opener or non-recoverable legacy replacement could discard the held parent. Apply the selected turn hint to every held and incoming candidate before atomization. The first non-empty held hint wins; only a turn with no held hint takes incoming evidence. Conflict probes cover older openers, prefix/suffix/middle overlap, legacy replacement and replay through both history and cache merges, preserving text, keys and held-row order. This tightens implementation of the existing precedence contract; attribution remains inert and conversation-local, with no new logging or storage.

## Documentation handoff

Pending for the documentation stage, as requested by the verifier:

- `docs/knowledge/features/data-model.md`, `Message`, and `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md`, assistant payloads: document DTO/event/message attribution, absent versus null decoding and conversation-local inert handling.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`, seam join and merge: document held-parent precedence and conflict probes. Selecting a winner is insufficient unless every reconstruction or replacement candidate receives it.
- `docs/knowledge/features/conversation-cache.md`, thread document: unchanged disk serialization, cache-only unknown attribution and in-memory enrichment/reconnect retention.
