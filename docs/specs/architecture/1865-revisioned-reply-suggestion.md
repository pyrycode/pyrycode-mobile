# #1865 — revisioned next-reply suggestion state

## Files read

- `docs/knowledge/INDEX.md` and `features/conversation-repository.md`: additive defaults keep existing fakes and doubles usable.
- `docs/knowledge/features/remote-conversation-repository.md` and `remote-conversation-repository-mcp-status.md`: one projection per inbound state; per-connection state must stay outside `HostReadings`.
- `docs/knowledge/features/stable-conversation-repository.md`: `switchToLive` cancels old observations and supplies the disconnected value.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md` and `data/network/MobileWireCodec.kt`: `MobileJson` omits nullable fields; explicit presence and primitive-type checks are required here.
- Sibling `pyrycode/docs/protocol-mobile.md`, `reply_suggestion` and Security model: authoritative inbound contract and threats.
- Sibling `pyrycode/internal/sessions/id.go`, `ValidID`: confirms session identities use the same canonical UUIDv4 shape as conversation identities.
- `data/repository/ConversationRepository.kt`, `observeMcpStatus`: default observation and public reading types.
- `data/repository/RemoteConversationRepository.kt`, `onInbound`, `recordReplayCursor`, initializer: one inbound consumer and connection lifetime.
- `data/repository/McpStatusProjection.kt`, `apply`, `observe`: immutable StateFlow projection pattern.
- `data/repository/StableConversationRepository.kt`, `switchToLive`: live-only facade observation.
- `data/repository/RelayRepositoryCoordinator.kt`, `onConnection`: fresh repository and child scope for each pump/handshake.
- `data/repository/CachingConversationRepository.kt`, interface delegation: new observation forwards without adding persistence.
- `di/AppModule.kt`, `conversationRepositoryModule`: normal builds bind the stable facade.
- `data/network/AttachmentPayloads.kt`, `isAttachmentIdShape`: existing canonical UUIDv4 identity validator, already used for conversation routing.
- `data/repository/ThreadProjection.kt`, history reducer: unknown history types do not become state projections.
- `RemoteConversationRepositoryMcpStatusTest.kt`: channel-backed fake pump and background-scope collectors.

## Context

Expose one inbound state contract for the dependent composer ticket. No UI or ViewModel changes, new dependencies or decision record are needed. The daemon dependency is merged. In-flight #1655 touches `RemoteConversationRepository.onInbound`'s message arm; our additive suggestion arm and observation do not restructure it.

Sizing: approximately 650 written lines including this plan and tests; five production files; one exported reading type and one additive interface method; two implementation overrides, no existing consumer migration; three acceptance criteria; at most eight validation/ordering reject branches. This remains within the ticket estimate and all builder limits.

## Design

Add `ReplySuggestion(conversationId: String, sessionId: String, revision: ULong, suggestedReply: String?)` beside the repository's reading types. Its `toString` redacts all fields. The nullable text represents an explicit clear while retaining identity and revision; a null reading means no state has been received for the requested pair.

Add `observeReplySuggestion(conversationId, sessionId): Flow<ReplySuggestion?>` with `flowOf(null)` as the interface default. The fake and other doubles need no edits. The stable facade uses `switchToLive(null)` even when host-held readings are supplied; the cache wrapper forwards by interface delegation and never persists suggestions.

Add `ReplySuggestionPayloads.kt` with one internal decoder from `JsonElement` to a validated reading. Check object/key presence and exact JSON primitive kinds before reading values. Both identities use the existing canonical UUIDv4 validator. Revision is an unquoted decimal JSON integer parsed as `ULong`, positive through `ULong.MAX_VALUE`; quoted, fractional, negative, zero and overflow values are rejected. `suggested_reply` must be an explicit JSON null or a string. Strings stay verbatim, must be nonblank, valid UTF-8 with no unpaired surrogate, single-line (reject CR/LF, NEL, Unicode line and paragraph separators), and at most 1024 UTF-8 bytes. Native text does not inherit the fallback-only 240-code-point restriction.

Add `ReplySuggestionProjection` with an immutable map keyed by the conversation/session pair. An accepted frame replaces only that entry and only when its revision strictly exceeds the held revision. A clear remains in the map, preserving its watermark. `observe` maps the requested pair with distinct-until-changed so unrelated updates do not re-emit it.

Remote routing creates one projection per repository, gates the new `reply_suggestion` arm on `interactive`, and forwards observation. Frames carrying an `event_id` are rejected because suggestions are live/reconciled state, never replay. This type bypasses `recordReplayCursor` even for malformed frames. No thread projection, live-session event or turn-state operation is called.

## State and concurrency model

No new coroutine, scope or dispatcher. The existing connection child scope owns the single inbound collector. All projection writes use `MutableStateFlow.update`; validation completes before the atomic revision comparison and replacement. Hot state retains the latest value for late subscribers; each public observation is a cold projection. Host isolation follows separate remote/projection instances owned by each host coordinator. Fresh connections construct empty projections before accepting reconciliation, allowing lower revisions after daemon restart. Consumer termination resets the projection to release held state; disconnected facade observations emit null and cancel old delegates.

## Error handling

Malformed payloads drop only that frame and change neither text nor revision. Parsing returns null without throwing or retaining serialization error messages. Debug-only structured logs use static outcomes (accepted, stale, malformed, reset), with no identities, payloads, text or exception details. Domain `toString` is redacted. Existing transport size limits, authentication, retries and cancellation remain authoritative.

## Testing strategy

Write unit probes first in `RemoteConversationRepositoryReplySuggestionTest`, using the real inbound repository/decoder and channel-backed fake pumps. Watch a red run before production implementation. Cover absent/default state, exact text and ASCII/multibyte byte limits, full uint64 boundaries, strict missing/wrong-type checks, invalid identities and text, explicit/reconciled clears, malformed high-revision input followed by a lower valid update, late subscription after set and clear, duplicate/decreasing revisions and set-clear-stale-set.

Probe isolation with the same conversation in two sessions, the same session in two conversations, and identical pairs in two hosts. Reorder frames and use duplicate payload/envelope ids to prove ordering follows only revision. Replace delegates with and without a disconnected interval; check old-delegate updates cannot leak, empty new state is observed, and lower new revisions land. Exercise termination reset and shared `HostReadings` to prove suggestions are not retained there. Assert thread rows, live-session events, turn phase and replay cursor stay untouched, including a history page and an event-id-bearing suggestion.

Run focused new tests plus existing remote, stable facade and cache tests; lint, assembleDebug, Spotless. After the last merge of main, push before the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`. No device or real-Claude scenario is needed for this data-only contract.

## Open Questions

None. UI rendering/sending remains with the dependent child.

## Security review

**Verdict:** PASS

- [Trust boundaries] Exact-kind and explicit-presence checks in `decodeReplySuggestion` prevent absent nulls and coerced primitives from clearing state. Canonical UUIDv4 identities prevent invalid routing keys; the bounded single-line UTF-8 string is still untrusted inert data, never executable input.
- [Tokens] No credentials are generated, read or stored. The new reading is received only inside the existing authenticated Noise session.
- [Files and storage] State exists only in the connection projection, never in `HostReadings`, disk cache, replay or history. No filesystem path or file operation is introduced.
- [Android surface] No exported component, intent, push, provider, keyboard or WebView path changes. Display and user submission are owned by the dependent composer child.
- [Cryptography] Existing Noise implementation and key storage are unchanged; revisions are ordering values, never nonces or secrets.
- [Network and I/O] Existing frame cap and transport timeouts stay in force. No outgoing request/retry. A hostile authenticated daemon can grow pair state during a connection like sibling projections; global inbound rate/cardinality policy is outside this feature and stays with the transport owner.
- [Errors and logs] SHOULD FIX: redact `ReplySuggestion.toString` and log only static outcomes. Never log text, identities, payloads or parser exceptions. Validate this with a captured logger sink and marker text.
- [Concurrency] The revision comparison and map replacement occur together in `update`, with no suspension or detached job. Clear watermark remains until the connection ends; new connection starts empty, avoiding false rejection after restart.
- [Threat model] Relay delay/drop cannot revive an older revision; authentication and AEAD address forgery/misrouting. Hostile daemon frames fail closed and leave the consumer alive. Token theft/rooted-device storage and UI screenshot/accessibility leakage are unchanged by this data-only feature and stay with existing platform protections and the composer render stage.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-07
