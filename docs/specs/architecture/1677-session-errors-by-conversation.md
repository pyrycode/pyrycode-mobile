# Session errors by conversation (#1677)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt`: `TurnStatePayloadDto`, `StallPayloadDto`, and `toEvent` establish the decode boundary and recognized phases.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: default observation bodies preserve fake and test-double compatibility.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound`, `decodeLiveSessionEvent`, both `sendMessage` overloads, and collector cleanup are the integration edges.
- `app/src/main/java/de/pyryco/mobile/data/repository/StallProjection.kt` and `ApiRetryProjection.kt`: connection-scoped state and cold per-conversation observations are the model to mirror, with narrower clearing rules.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `switchToLive` blanks transient state between connections, even when other readings are held.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: interface delegation passes non-history observations through without persistence.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt`: sends suspend for acknowledgement and may fail; clearing must precede delegation.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTurnPhaseTest.kt`, `RemoteConversationRepositoryAttachmentTest.kt`, and `CachingConversationRepositoryTest.kt`: channel pump, logging capture, and facade/decorator test patterns.
- `docs/knowledge/features/conversation-repository.md`, `remote-conversation-repository.md`, `stall-state.md`, and `stable-conversation-repository.md`: default interface methods avoid cascades; late observers need state rather than replay-free events; fresh repositories own transient projections.
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md`: atomic pure updates merge inbound and caller writes without clobbering other conversations.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: plain JVM tests must capture and restore `RelayLog`'s sink.
- Sibling `pyrycode/docs/protocol-mobile.md`, Application message types, Error codes, and Security model; `pyrycode/internal/protocol/messaging.go`, `SessionErrorPayload`: authoritative wire contract, including no client resend for either known code.

## Context

The daemon already emits conversation-scoped `session_error`, but mobile drops it. This single deliverable exposes the latest nullable wire code for #1678 to consume. Daemon prose is validated then discarded. No UI, persistence, wire changes, new dependencies, or decision record are needed.

Sizing: forecast about 650 written lines including plan, production and tests, five production files, two internal types, no new exported types, no existing consumer signature changes, four acceptance criteria, and fewer than ten rejection/clearing branches. This is below the refiner's approximately 1000-line estimate and the 1600-line ceiling. #593's state projection is the nearest analogue; this slice carries only a nullable string rather than mapped counter variants.

Overlap: #1642 adds independent queue-control methods in `ConversationRepository`, `RemoteConversationRepository`, and `StableConversationRepository`; keep additions local and build through it.

## Design

Add `observeSessionError(conversationId): Flow<String?> = flowOf(null)` to the repository contract. Override it in remote via a new internal `SessionErrorProjection` and in stable via `switchToLive<String?>(null)`. `CachingConversationRepository` continues to delegate without a new override; fakes inherit the default.

An internal decode-only `SessionErrorPayloadDto` in `InteractivePayloads.kt` carries the three required JSON primitives. Its mapper validates that all are JSON strings, then returns only conversation id and code. Missing fields, non-primitive fields, null, boolean and numeric values drop the frame. Unknown and empty string codes remain valid wire strings, with no client-side interpretation or resend. Additional fields are ignored as usual. The DTO and daemon `message` never enter retained state.

`SessionErrorProjection` holds a `MutableStateFlow<Map<String, String>>`. `apply` decodes through `MobileJson` and atomically replaces one conversation's code. `observe` maps the key and uses `distinctUntilChanged`, so a new collector reads the current value and unrelated updates cause no emission. `clear` removes one key, and `reset` empties connection state.

`onInbound` routes `session_error` only behind negotiated `interactive`. Its existing decoded live-event arm clears the error only for `TurnState` with a recognized phase other than `Idle`. To prevent primitive-to-string coercion from treating a malformed routing id as valid progress, the `turn_state` decode arm checks that `conversation_id` and `state` are JSON strings before the existing DTO decode. Other live events, idle/unknown/malformed turn states, session transitions and unrelated conversations do not clear this state.

Both public send overloads clear their conversation before invoking `MessageCommands`, including attachment sends and calls that fail before acknowledgement. No automatic request, retry or message resend follows an error. Collector teardown resets the projection; a new repository also starts empty. The error never enters `HostReadings`, replay reconstruction or the cache.

## State and concurrency model

Reuse the repository's sole inbound collector on the injected connection scope and dispatcher. No new job, scope, timer, or sharing operator. Inbound frames, caller sends and teardown may write concurrently; every state mutation uses an atomic, pure `update` with no suspension. Caller clearing happens before sending and before awaiting, so a later inbound error remains visible even while the send is pending. Connection scope cancellation and pump completion run existing cleanup plus the new reset. The stable facade's cold read switches with `flatMapLatest`, cancelling the prior observation and reporting null while disconnected.

## Error handling

Malformed session payloads return no decoded value and leave held state intact; decode exceptions are caught as `IllegalArgumentException` without logging their text. Unknown codes are retained verbatim as untrusted wire data. Send exceptions retain their existing domain contract and cannot restore a cleared error. Debug-only structured lifecycle logs use static outcomes for applied, malformed, cleared and disconnected; no raw id, code, prose, payload or exception is logged.

## Testing strategy

Write repository tests first and observe failure before implementation. A channel-backed `SessionPump` exercises the real inbound collector. Assert initial and late current values, both known codes and unknown code, replacement, duplicate suppression, conversation isolation, capability gating, every missing/wrong-typed field, malformed-frame survival, and prose exclusion from logs and thread state. Prove thinking/responding clears, idle/unknown/malformed turn states and all other structured live-event types do not, and other-conversation edges do not clear.

Prove both send overloads clear before acknowledgement, sends for another conversation preserve the error, send failure does not restore it, and a new error received during an awaiting send remains after acknowledgement. Through stable facade over actual remote instances, prove initial disconnected null, live current value, gap null, detached-old-repository isolation and fresh reconnect null without outbound traffic. Cover the held-readings facade path and the caching decorator; assert the default fake observation is null.

Run focused new tests plus existing remote, stable, turn-phase, attachment and caching coverage. Run `lint`, `assembleDebug`, `spotlessApply`, and forced `spotlessCheck`; inspect executed test counts. No screen/device test or real-Claude scenario is required for this data-only contract.

## Open Questions

None. #1678 owns user-facing interpretation and rendering; this contract exposes only nullable wire codes.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] SHOULD FIX: string DTO decoding can coerce JSON numbers. Required primitive fields must have `isString` checked before a session error is accepted; the turn-state decode must likewise reject a non-string routing id before allowing a clear. Tests cover both boundaries.
- [Tokens, secrets and credentials] No new credential access or storage. Daemon prose can contain sensitive material; only validated id/code leave the decode-local DTO.
- [Files and storage] The projection is memory-only, never a cache entry, path, file name or backup; the caching wrapper delegates the observation unchanged.
- [Android attack surface] No component, intent, provider, WebView or render path changes. #1678 owns fixed user-facing copy.
- [Cryptography] Existing authenticated Noise session and vendored cipher/nonce handling are unchanged; no cryptographic primitive is added.
- [Network and I/O] Existing transport/frame limits and supervisor backoff remain unchanged. No request or retry is issued for session errors; malformed input cannot terminate inbound collection. Per-conversation map growth follows the existing paired-daemon projection posture, with entries removed on clear and all entries discarded on teardown.
- [Errors, logs and telemetry] SHOULD FIX: raw unknown codes and exception messages must never be interpolated into logs. Use only static event/outcome strings and test with sensitive prose and an arbitrary code. No telemetry or release log path is added.
- [Concurrency] Atomic pure updates protect independent inbound/send writers. No new coroutine or lifecycle owner; teardown resets state and stable switches away from old instances. A later error during a pending send must survive acknowledgement.
- [Threat model] Malicious relay confidentiality and impersonation remain protected by Noise; drop/delay/flood do not trigger client resends. Hostile daemon fields are validated and prose discarded. Disk token theft and UI screenshot/keyboard leakage gain no surface because this change stores no credentials or rendered text; existing pairing/Keystore protections remain unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
