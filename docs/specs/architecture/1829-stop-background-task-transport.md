# Capability-gated background-task stop transport

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt`: `HelloClientPayload`, `ErrorPayload` and capability defaults.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: `nextRequestId` and independent reply waiters.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationCommands.kt`: `interrupt` establishes the no-success-reply send precedent.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound`, task projection and collector termination own routing and cleanup.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskProjection.kt`: `applyUpdated` and `applyRoster` supply decoded retirement edges, including unlisted terminal updates.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt`: `activeConnection`, `onConnection` and `teardownActive` select one host's current connection.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt`: the encrypted handshake pins the exact advertised capability list.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt`: managed-pump fixtures and host-isolation coverage.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryBackgroundTaskTest.kt`: channel-backed inbound fixture and task-state regression checks.
- `docs/knowledge/features/interrupt-send-path.md`: sending must not clear local turn state or await an acknowledgment.
- `docs/knowledge/features/relay-repository-coordinator-seams-and-passthroughs.md`: Background-task roster is connection-scoped; finished marks alone survive reconnect.
- Upstream `pyrycode/docs/protocol-mobile.md`: “Stop background task (v2)”, “Capability negotiation (v2)” and “Security model” are the wire and threat-model authority.

## Context

The task-panel child needs a transport seam without changing `ConversationRepository` or rendering UI here. A successful send means only that the connection accepted a frame. The daemon's existing task updates or roster omission establish completion. No decision record is needed.

Overlap: #1783 touches `BackgroundTaskProjection.publish`; these lifecycle hooks are additive in different symbols and do not depend on that change.

## Design

Advertise `stop_background_task` in `HelloClientPayload` defaults. Add an internal `BackgroundTaskStops` helper beside `ConversationCommands`, using `RelayRequests.nextRequestId`, the pump send and a lazy negotiated-capability supplier. Its `send(conversationId, taskId): Result<Unit>` gates on the detection capability, registers the originating pair before sending one timestamped envelope, and returns immediately after send. IDs are encoded verbatim as JSON strings. No retry, reply waiter, optimistic task mutation or interrupt is involved.

The concrete repository exposes `supportsBackgroundTaskStop: Boolean`, `stopBackgroundTask(conversationId, taskId): Result<Unit>` and `observeBackgroundTaskStopRefusals(conversationId): Flow<String>`. The refusal stream supplies opaque original task IDs, with no daemon text. The coordinator forwards sends to its captured current concrete repository and switches refusal observation with `activeConnection`. It publishes `supportsBackgroundTaskStop: StateFlow<Boolean>` derived from the current pump's `Open.capabilities`, false for every other state. The send rechecks the concrete supplier rather than trusting the asynchronously published support value.

The helper has an independent request-ID ledger. Only a decoded `ErrorPayload` with the exact stop-refusal code and matching `in_reply_to` consumes an entry and emits the locally registered task ID to its originating conversation. Reflected conversation IDs are ignored. A later request for the same pair supersedes its previous entry, preventing an old refusal from affecting the new attempt. Other codes and malformed errors leave tracking intact for a later valid refusal or lifecycle retirement.

Add defaulted callbacks to `BackgroundTaskProjection`: decoded terminal updates retire the matching pair even without a visible row; decoded rosters retire omitted task IDs for that conversation. Existing folds and rendering data are unchanged. Repository `onInbound` offers errors to this ledger alongside existing independent waiters. Both coordinator teardown and inbound collector termination end the helper and clear its ledger.

## State and concurrency model

No new scope, timer or inbound subscription. The existing connection scope and injected dispatcher own the single collector; background lifecycle closure cancels it as before. A synchronized ledger protects admission, replacement, consumption, retirement and the permanent ended flag across callers and the collector. Registration completes before the non-suspending pump send; the lock is not held over send. Failure removes only its own request ID, preserving a concurrent newer attempt. Refusal emission is atomic with consumption and retirement. Refusals use a replay-free buffered shared event flow; consumers subscribe before invoking a stop. Coordinator reads remain cold switched flows, support is eagerly derived state, and nothing persists across reconnect or process death.

## Error handling

Unsupported, disconnected/ended and rejected or exceptional sends return failure with static local messages. Cancellation is rethrown after retiring that attempt. A valid refusal is an asynchronous event, not a failure of the already-returned send and never a completion claim. Unknown/missing/stale/duplicate correlations, malformed payloads and other codes emit nothing. Structured debug lifecycle logs contain only fixed event names and static result codes; never identifiers, reflected IDs, payloads or exception messages.

## Testing strategy

Write JVM tests first for capability advertisement, exact envelope and fresh IDs, immediate return with no reply, unchanged thread/turn/task state, support gates and failed sends. Exercise an inbound refusal during the fake send, origin routing despite a conflicting reflected ID, duplicate and nonmatching errors, strict malformed-error handling, terminal and roster retirement, superseding requests and teardown. Coordinator tests use real repositories with managed pump fakes for pre-handshake, unsupported, open, closed, reconnect and independent hosts, including identical conversation/task/request IDs. Run existing repository, coordinator and task-projection tests to protect reply waiters and rendering data. Run lint, assemble, forced Spotless, final full JVM suite and `scripts/pre-verify.py --gradle`. No screen/device/live scenario is needed for this dormant data-layer seam; UI and operator-facing actions belong to the task-panel child.

## Open Questions

None. The protocol fixes the no-success-reply and correlation-only contracts.

## Security review

**Verdict:** PASS

- [Trust boundaries] The helper decodes errors once at `applyRefusal`, requires the exact code and local request correlation, and emits only locally registered opaque keys. Reflected IDs and daemon message text never enter the consumer signal. Malformed JSON types must be rejected without coercion.
- [Tokens] No secret creation or storage. Request IDs are connection-local counters for correlation, not authorization or random tokens.
- [Files and storage] No paths, disk writes, caches or backup changes. Identifiers remain in-memory lookup keys and JSON data.
- [Android attack surface] No exported components, intents, deep links, push changes or UI rendering.
- [Cryptography] Existing authenticated Noise IK session and key lifecycle stay unchanged; the helper creates application envelopes only.
- [Network and I/O] Detection capability is not authorization; daemon `interactive` enforcement remains authoritative. No reply await can hang, no reconnect replay is added, and existing frame bounds, TLS and backoff remain intact.
- [Errors, logs and telemetry] SHOULD FIX: map every send exception to static failure data and use fixed lifecycle/result log fields only. Never emit identifiers, reflected IDs, raw frames, exception text or daemon-authored strings, even in debug.
- [Concurrency] SHOULD FIX: guard the ended flag and ledger together, register before send, consume once, retire by decoded lifecycle edges and remove only the failed attempt. Explicit coordinator teardown disables stale repository admission before cancellation cleanup.
- [Threat model] A malicious relay can delay/drop frames but cannot cause a wait for success or route a refusal without local correlation inside Noise. Hostile authenticated daemon errors are decoded defensively and cannot use reflected IDs to redirect the signal. Disk token theft and UI screenshot/accessibility/keyboard leakage are unchanged concerns owned by existing key-storage and UI boundaries; this slice adds neither storage nor UI.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-06

## Revisions

2026-10-06: Verifier finding 1 reported the replay-order timeout after reconnect. Main now contains `a824b728` from #1783: the opening fixture omitted Claude's replayed initial user, allowing the daemon's idle placement fallback to insert its confirmation between assistant deltas and split the reply. Merge main rather than duplicate its repair. The fixture now emits the user echo before thinking; its harness regression pins that order, and `interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect` additionally checks exact reply text, delta identities and user-before-reply placement. Run the focused scripted replay-order gate with fresh counted XML on this integrated branch. The stop transport contract and security review remain unchanged; no new production replay behavior, collector, storage or logging is introduced.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/remote-conversation-repository-control-sends.md`, a per-task stop section beside interrupt: document the concrete support/send/refusal APIs, subscribing before send, static failures, and send acceptance without completion. Completion comes from terminal task updates or roster omission; detection grants no authorization.
- Pending documentation stage: `docs/knowledge/features/relay-repository-coordinator-seams-and-passthroughs.md`, Background-task roster and outbound stop passthrough: document current-host/current-open-connection support, correlation-only origin routing, supersession, terminal/roster/send-failure/connection retirement, strict required error-field types, and content-free logging.
