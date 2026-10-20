# Host-held Claude allowance (#2325)

## Files read

- `data/repository/HostReadings.kt`: pairing-owned readings and `whileOpen` disposal.
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, `routeInbound`, shared request IDs and `requestContextUsage`; allowance must bypass conversation admission and replay bookkeeping.
- `data/repository/ConversationRepository.kt`: optional observation/request defaults preserve fakes.
- `data/repository/StableConversationRepository.kt`: direct held reads and snapshot request forwarding.
- `data/repository/RelayRepositoryCoordinator.kt`: `onConnection`, authenticated Open hook, child scope cancellation and `close`.
- `data/repository/RelayRequests.kt`: `nextRequestId`, without adding reply waiters.
- `data/repository/UsageLimitProjection.kt`: separate conversation warning semantics remain unchanged.
- `data/repository/RelayRepositoryCoordinatorTest.kt`, `RemoteConversationRepositoryContextUsageTest.kt`, `HostReadingFrames.kt`: native fake-pump and held-reading test patterns.
- `docs/knowledge/features/remote-conversation-repository.md`, `stable-conversation-repository.md`, `relay-repository-coordinator.md`: pairing lifetime and connection-local request ownership.
- Core `docs/protocol-mobile.md`, “Claude usage allowance reading” and “Security model”: wire source of truth, including landed Core #3575/#3576.
- Analogue `f0000c142` (#1317): 526 additions, 60 deletions; reuse its pairing owner rather than add another store.

## Context

Expose one usable behaviour: observe and refresh the paired host's latest account allowance through the real repository and thread facade. Decoder, held state, requests and lifecycle tests are its necessary plumbing. UI rendering is another child of #2226. No decision record is needed.

Estimated total work: 850 lines including plan, tests and adjustments to existing request-count assertions; six production files, five exported model declarations, one updated production construction site, three acceptance criteria and fewer than ten decode rejection categories. No signature replacement or migration is required. Rechecked against the finished plan: within every ceiling.

Overlapping branches #1947, #2175, #2245 and #2322 touch shared repository files; their context/thread changes require no allowance dependency. Keep additions local.

## Design

- Add portable sealed `ClaudeUsageReading` with `None`, `Unavailable`, `Observed` and nested `Window`. Observed carries an `Instant` and independently nullable windows; each window has independently nullable `Double` utilization and `Long` reset seconds. Unknown wire state maps to Unavailable without retaining arbitrary text. Null observation means no accepted frame for this pairing yet.
- A single internal `decodeClaudeUsage` boundary in the model file validates a JSON object and a required string state. Known states validate every supplied known field's primitive/object shape; observed requires a parseable observation timestamp. Optional fields must be absent or correctly typed, never coerced strings/nulls. Finite utilization remains unclamped and reset seconds retain the full signed 64-bit range. Unknown keys are ignored. Unknown string states become Unavailable without interpreting future fields.
- `HostReadings` holds one `MutableStateFlow<ClaudeUsageReading?>`, not a conversation-keyed map. Accepted frames replace it whole in serialized arrival order; equal values conflate naturally. Its existing `whileOpen` wrapper clears observers on pairing disposal.
- Add default `observeClaudeUsage()` and no-op `requestClaudeUsage()` to `ConversationRepository`. Remote observation reads its holder; refresh sends one `request_claude_usage` with `{}` using the shared request-ID allocator, with no waiter, conversation lookup, capability gate or model call. Refused sends are absorbed.
- Route `claude_usage` before conversation/session admission and all history/replay writes. Both correlated replies and pushes use the same decoder and replacement. Guard allowance reads/writes/sends with the connection owner's live-ownership supplier, wired by the coordinator to the current transport, child scope and authenticated pump state. Retired connections cannot overwrite the pairing's held reading.
- The coordinator requests once after each pump reaches authenticated Open, using its child scope. The facade reads the injected host holder directly during connection gaps and delegates refresh to the current repository. Its compatibility path without a holder uses the existing switched-null convention.

## State and concurrency model

The existing connection-owned inbound coroutine serializes all replacement writes without suspension. StateFlow provides fan-out and equality conflation. No new long-lived scope, dispatcher, timer, polling loop, request ledger or persistence is added. Open requests run on the coordinator child scope and cancel with it. Background/socket closure retains the pairing holder; unpair/re-pair closes it. Process restart starts without a reading.

## State transitions and identity reuse

| Event | Focused native test |
| --- | --- |
| Correlated reply, push, duplicate and reversed observation times | `ClaudeUsageRepositoryTest.arrivalOrderReplacesWholeReadingsAndDuplicatesDoNotAccumulate` |
| Missing/zero fields, empty/missing windows and extreme reset values | `ClaudeUsageDecodeTest.optionalWindowsAndFieldsPreserveAbsenceAndRawNumbers` |
| None/unknown state replaces observed | `ClaudeUsageRepositoryTest.unavailableStatesReplaceAndMalformedFramesPreserve` |
| Malformed before and after reconnect | `ClaudeUsageRepositoryTest.reconnectRefreshesWithoutBlankingAndRetiredSourcesCannotReplace` |
| Multiple conversation consumers on one host, another host using the same IDs | `ClaudeUsageRepositoryTest.sameHostFacadesShareAndOtherHostsStayIsolated` |
| Offline/refused refresh and repeated collection | `ClaudeUsageRepositoryTest.refreshIsUngatedAndRefusalsDoNotStopObservation` |
| Handshaking, authenticated Open, repeated Open, direct transport replacement | `ClaudeUsageRepositoryTest.reconnectRefreshesWithoutBlankingAndRetiredSourcesCannotReplace` |
| Pairing disposal, same host re-paired and old frames | `ClaudeUsageRepositoryTest.pairingDisposalClearsAndRePairStartsEmpty` |
| History, replay and rate-limited independence | `ClaudeUsageRepositoryTest.allowanceNeverChangesRowsReplayOrRateLimitedState` |

## Error handling

Malformed payloads return null from the decoder and preserve held state. Unknown state strings are valid unavailable replacements. Offline refresh is a no-op; false/throwing sends preserve readings, with no retry until an explicit refresh or new connection. Logs contain static event/outcome codes only, never payloads, quota values, state text or decode exceptions. No errors enter UI state.

## Testing strategy

Write decode and real remote/coordinator/facade tests first and observe red before implementation. Use fake managed pumps with `runTest` and injected StandardTestDispatcher; collectors run in backgroundScope. Exercise actual empty request objects and the empty-capability Open path, with no conversation list. Existing coordinator, stable-facade, context and rate-limit tests remain covered. Invariant probes vary arrival order, equal/replayed frames, empty/single windows, reused IDs and reconnect boundaries through production routing. No emulator, recorded scenario or live Claude turn is required for this data-only contract.

Run focused JVM classes, lint, assembleDebug and forced Spotless on Linux; retain fresh XML/raw reports, counts and candidate revision outside the worktree. Complete suites remain dispatcher-owned. Merge main, push, then run assembleDebug and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: `docs/knowledge/features/remote-conversation-repository.md`, allowance reading section: document host-wide observation/refresh, full replacement, field absence and reconnect/pairing lifetime, linking Core's protocol instead of copying it. Keep `rate_limited` documented as a separate warning signal.

## Security review

**Verdict:** PASS

- Trust boundaries: `decodeClaudeUsage` is the sole typed boundary. MUST FIX addressed in design: do not use lenient primitive coercion; reject strings standing in for numbers and malformed observation instants. Unknown state text is discarded, never rendered or retained.
- Tokens: no credential generation, storage or access is added. No raw payloads or decode exceptions may reach logs/errors.
- Files/storage: allowance is in-memory pairing state only; no paths, files, backups or atomic-write changes.
- Android surface: no component, intent, provider, WebView or UI changes; data remains portable.
- Cryptography: existing authenticated Noise IK channel and key lifecycle remain the boundary; no new primitives or nonce handling.
- Network/I/O: reuse bounded envelopes and existing transport timeouts/backoff. One request at Open plus explicit requests; no polling, reply waiters or model calls. A malicious relay can deny/delay delivery, leaving a held reading without inventing freshness.
- Errors/logging: static accepted/malformed/refused/unavailable categories only. Unknown string states never become log labels; caught exceptions are discarded.
- Concurrency: MUST FIX addressed in design: the allowance handler and sender check current connection ownership, including synchronous transport replacement before cancelled collectors catch up. Pairing close makes held observations null. All new coroutines use the cancelled child scope.
- Threat model: hostile authenticated frames are type-checked and retain at most one bounded-envelope reading; duplicates cannot grow state. Relay confidentiality/misrouting remains protected by existing Noise authentication. Disk token theft and UI screenshot/keyboard leakage introduce no new surface in this data-only change.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-20
