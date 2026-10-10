# Send the switch_agent request (#1117)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: additive command defaults keep unrelated doubles compatible.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt`: command ownership and shared `RelayRequests` counter.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: ordinary correlated waiters cannot confirm this operation.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `routeInbound` folds updates before completion; inbound collector finally owns cleanup.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt`: required id and nullable raw agent retain presence information.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `upsertConversation` preserves omitted agent and publishes confirmed rows.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: snapshot current delegate for one-shot calls.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt`: atomic named-record mutation and recording seams.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `teardownActive` synchronously ends pending operations before cancellation.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryStopTaskTest.kt`: channel-backed pump and content-free log assertions.
- `docs/knowledge/features/conversation-repository.md`, `remote-conversation-repository.md`, `stable-conversation-repository.md`: preserve portable commands, semantic default failures and connection identity.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: `switch_agent`, Refusals and failures, and Security model are the wire source of truth.

## Context

The daemon switch contract and agent row projection are merged. #1118 owns the picker, confirmation UI and operator-flow proof. This repository-only ticket introduces no screen or new dependency and does not need a decision record. No overlapping in-flight feature branches were found.

## Design

Add `switchAgent(conversationId: String, agent: ConversationAgent, model: String, effort: String? = null): Result<Unit>` to the repository, with a typed unsupported default. `SwitchAgentFailure` carries a client-owned category and retryable flag, a static message and no cause. Its category enum distinguishes the six documented daemon codes, local unavailable and fallback failure.

`ConversationCommands` owns an internal `SwitchAgentCommands` helper. It receives pump send, negotiated capabilities and the shared request-id supplier. A private serializable payload uses required strings without defaults and nullable effort; `MobileJson` retains empty model/effort and omits null effort. Requests never optimistically mutate the list.

The helper registers a pending target and deferred before sending. The ordinary `conversation_updated` arm continues decoding and folding exactly as before, then hands the valid record and original payload to confirmation. Require explicit string id and agent in the original wire object, matching the pending conversation and requested wire agent. Cached/domain defaults cannot confirm. Errors route separately by request id, decode `ErrorPayload`, classify only its code, preserve retryability, and discard message and decode exceptions. Ack, progress and transition frames cannot complete a switch.

Stable snapshots its current delegate and forwards all four arguments unchanged; absence returns typed unavailable. Fake records arguments in a redacted call record, exposes a configurable typed failure, and on success changes only the named record agent. Unknown fake ids fail as not-found.

## State and concurrency model

A synchronized pending map belongs to one remote connection. Admission, registration and non-suspending send are atomic against end. Confirmation, correlated refusal, cancellation removal and end use the same monitor; there are no nested mutexes or new jobs. Each caller awaits its own deferred in its existing scope and always removes its registration in finally. Cancellation propagates, including cancellation thrown by send. Inbound collector finally and synchronous coordinator teardown both end admission and settle every pending call as unavailable. A fresh connection constructs a fresh ledger. No client deadline or retry: wrap-up can take about 90 seconds. Process death retains no client pending work; server-confirmed state is read on reconnection.

## State transitions and identity reuse

| Event | Contract and test |
| --- | --- |
| Both switch directions; other/missing/null/unknown/different agent updates | Only explicit matching valid row confirms after folding: `confirmationRequiresExplicitMatchingWireIdentityInBothDirections`. |
| Progress, transition and correlated ack | Remain pending: same confirmation test. |
| Multiple conversations, duplicate confirmation, late cleanup refusal | Independent targets, completion once and committed success stands: `concurrentTargetsAndCommittedSuccessAreIndependent`. |
| Repeated request ids across new connections | Each fresh ledger uses its own connection; ended repository refuses admission: `teardownSettlesAllAndRejectsLaterCalls`. |
| Background, disconnect, collector completion or cancellation | Synchronous end and collector finally settle all; new repo alone can admit: `teardownSettlesAllAndRejectsLaterCalls`, `inboundCompletionAndScopeCancellationEndPendingCalls`. |
| Caller cancellation, then another call for the same conversation | Remove only the cancelled registration; stale correlated refusal cannot settle successor: `callerCancellationReleasesRegistration`. |
| Refused/throwing send, including reentrant teardown | Typed local failure and no retained waiter: `sendFailureAndReentrantTeardownReleasePendingWork`. |
| Confirmation during synchronous send | Registration precedes send: `confirmationArrivingDuringSendIsNotLost`. |
| Caller cancelled before invocation | No send: `alreadyCancelledCallerSendsNothing`. |

Configuration changes and flow recollection add no operation jobs or replay; cancellation follows the caller, and observations keep the existing projection behavior.

## Error handling

Six codes map to six typed categories. Unknown or malformed correlated errors use a static fallback (valid unknown errors retain decoded retryability; malformed ones use false). Missing either capability returns unsupported; refused send or ended connection returns unavailable. No failure includes daemon message, request arguments, payload or caught exception. Offline failure states only that switching failed; it does not claim wrap-up was undone. Logs use only `event=switch_agent` and static outcome names, including sent, confirmed, cancellation and classified failure.

## Testing strategy

Write remote deterministic `runTest` tests first using a channel-backed pump and unconfined collector. Assert exact payload key presence for both agents and null/empty/nonempty effort; both capability gates; nonconfirmation matrix; row visibility at completion; refusal code/retryable matrix and sanitized diagnostics; send/teardown/cancellation lifecycle; shared request ids. Add stable tests against replaced current delegates and fake success/failure/unknown-id tests. Run affected existing remote, stable, fake and coordinator tests. No screen/device tests or live scenario here: #1118 owns the operator-facing caller and flow proof.

Forecast: about 850-1000 written lines including plan and tests, three exported types, no existing consumer signature changes, five acceptance criteria and at most ten failure categories/branches. Implementation edits remain local and additive.

## Open Questions

None.

## Security review

**Verdict:** PASS

- [Trust boundaries] `routeInbound` decodes the complete row before confirmation; confirmation also requires original wire string presence. Malformed or unknown agents never prove commitment. No new daemon text reaches UI.
- [Tokens, secrets] No new credentials or storage. Failures and fake call diagnostics use static text; request arguments never enter logs or exception causes.
- [Files and storage] No file or persistence changes; conversation id/model/effort are outbound JSON only, never paths or cache keys.
- [Android attack surface] No exported component, intent, WebView, push or permission changes; portable repository API only.
- [Cryptography] Existing Noise IK and encrypted pump remain untouched. Request-id counter is correlation, not a secret or nonce.
- [Network and I/O] Reuse bounded authenticated envelope transport. Both negotiated gates precede sending; no timeout/retry is introduced because daemon wrap-up is long. Connection end terminates waiters.
- [Errors, logs, telemetry] MUST FIX addressed in design: generic `RelayRequests.mapError` exposes daemon text, so switches never register in that waiter map and decode refusals through their own sanitized category path. Logs contain only event and outcome.
- [Concurrency] MUST FIX addressed in design: teardown could race registration after sweeping; one synchronized end/admission boundary prevents orphaned pending calls. Finally removes caller registrations; coordinator ends synchronously before cancelling collector.
- [Threat model] Relay drop/delay is handled by connection teardown and caller cancellation; no request replay. Hostile authenticated frames must pass full decoding and explicit wire identity. Existing transport limits, Keystore wrapping, rooted-device protection and screenshot/keyboard exposure are unchanged and outside this repository-only change.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-10
