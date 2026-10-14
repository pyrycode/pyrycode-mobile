# Authoritative catch-up and older pages

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound`, connection collector cleanup and `RelayRequests` integration; keep the single inbound collector.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `establishComplete`, `reset`; full items are upserts and progress is distinct from certification.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadRoute.kt`: live assembly, expiry and generation ownership.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadQueryDecoder.kt`: per-request correlation, bounded assembly and terminal outcomes.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadQueryPayloads.kt`: query and coverage types.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: shared connection request identity allocator.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt`: non-suspending send and connection lifetime.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryAuthoritativeTest.kt`: supplied-route fakes and legacy isolation coverage.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: sticky repair and reused-epoch generation fencing must survive query integration.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: retain the existing bounded decoder boundary.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`, “Thread query messages” and “Security model”: wire authority, captured checkpoints and one unfinished query per connection.
- `/work/Projects/pyrycode/docs/knowledge/decisions/042-daemon-built-thread.md`: daemon owns identity, order and parent closure.

## Context

#2043 and #2040 provide facts and wire decoding but no repository query lifecycle. Add an online synchronization seam for #1986/#1988 and the separate cache child. This is data-layer work: no production advertisement, UI route switch or raw-history replay changes. No decision record is needed. No overlapping in-flight branches were found.

Forecast: about 1150 written lines including plan, three modified production files, one new coordinator and repository probes. Two new internal state/coordinator types, no consumer migrations, four acceptance criteria and fewer than ten classified reject paths. This is one deliverable: certified online query synchronization. The supplied #1786 analogue includes substantially more legacy folding; this slice reuses the merged decoder and store.

## Design

Add `AuthoritativeThreadQueries`, owned by one repository/connection. It serializes synchronous admission, inbound processing, expiry, cancellation and teardown; holds one active decoder and a deduplicated FIFO of conversation/query intents. Catch-up requests read the latest certified epoch/checkpoint at dispatch time. Older intents read the last certified numeric continuation at dispatch time, and cannot run before a newest window establishes it. Use `RelayRequests.nextRequestId` for every attempt and the existing request DTOs for encoding.

Repository internal seams open/synchronize, request older history, cancel, reset, and observe complete authoritative snapshots. A defaulted shared-store constructor seam lets a future connection owner carry certified facts into a fresh repository; making that constructor internal avoids exposing internal wire types. Existing construction sites need no edits. Reconnect opens each desired conversation on the new repository rather than copying pending work.

Add value-based `AuthoritativeThreadCoverage` to each store snapshot, with certified lower/upper orders, continuation and explicit older-exists. Only successful terminal markers commit it. Newest windows may extend continuous held coverage; older pages must meet the held lower boundary exactly. Extras never affect boundaries. Captured versions and every certified coverage number must remain within the item store's inclusive safe-integer domain, zero through 9007199254740991; rejection preserves the previous checkpoint and coverage. A page may certify coverage while leaving checkpoint and repair untouched. Catch-up certification uses the captured version only; if later applied progress exceeds it, retain the prior checkpoint and repair state for retry. Never replace the captured number with later live progress.

Complete batches apply immediately with their captured conversation generation. Ignored upserts count as successfully applied; any other outcome retires the query without certification. Decoder failures, assembly expiry, query deadline, send refusal and teardown retain the previous certified facts. Only a validated terminal success/reset/error releases the remote slot. Local cancellation, repair, generation change, expiry or rejected application abandons assembly but keeps remote occupancy while consuming replies without mutation. A fresh terminal-only use of the existing decoder validates drain completion; a further 30-second drain deadline retires the connection rather than redispatching on a busy daemon. Send refusal never occupied the remote slot. Connection-local request ownership is retained for late errors; unrelated legacy errors retain their existing route. Explicit reset uses the live route to discard assembly and increments only the affected conversation generation, then requests version zero in its reported epoch. Live repair outcomes request catch-up for opened conversations; errors remain explicitly retryable rather than looping against unavailable providers.

## State and concurrency model

All coordinator entry points are synchronized and non-suspending; `SessionPump.send` is non-suspending. Lock order is coordinator then route/store; live route notifications run after its synchronized operation, avoiding inversion. No mutable snapshot survives a suspension. The repository's connection scope owns one query-expiry coroutine with an injected monotonic clock for deterministic probes. Collector completion and scope cancellation retire all pending work and cancel expiry. Remote occupancy is distinct from locally abandoned assembly; pending intents wait for a validated correlated terminal outcome. Drain expiry closes a managed pump through a defaulted connection-retirement callback, closes admission and discards pending work. A supplied non-managed pump must provide its retirement callback. Reconnect opens desired conversations on a fresh connection from certified facts only. The shared store is an atomic hot StateFlow map; observation is a cold host/conversation projection. Background closes the connection through the existing lifecycle driver. Process death loses in-memory facts; atomic persistence belongs to the cache child.

## State transitions and identity reuse

| Event | Deterministic repository probe |
| --- | --- |
| Open/reopen and competing conversations | `queriesSerializeAcrossConversationsAndUseCertifiedCheckpoint` |
| Batch overlap, replay and reordered identities | `upsertsConvergeWithoutDeletingHeldItems` |
| Dropped terminal overlapping newer live state and retry | `interruptedCatchUpRetainsNewerLiveAndCapturedCheckpoint` |
| Empty success and short pages with parent extras/ties | `pagesCertifyNumericRangesIndependentlyOfExtras` |
| Failed/malformed page and missing terminal | `failedAndExpiredQueriesKeepBoundariesRetryable` |
| Page sees newer history without newest catch-up | `pageCoverageCannotCertifyNewestHistory` |
| Reset, including reused epoch and delayed replies | `resetFencesRetiredRepliesAndPreservesOtherOwners` |
| Disconnect/reconnect, re-collected observations | `reconnectReusesOnlyCertifiedFacts` |
| Cancellation, send refusal and retry identity | `abandonedQueriesDrainRemoteSlotBeforeDispatchAcrossConversations`, `refusedSendDoesNotOccupyRemoteSlotAndRetryUsesFreshIdentity` |
| Late errors and replacement request | `retiredErrorsNeverChangeLegacyReadEvidenceOrReplacementQuery` |
| Unsupported coverage and inclusive numeric boundary | `windowCoverageRejectsUnsupportedNumbersWithoutAdvancingCertifiedFacts` |
| Missing retired terminal and reconnect | `missingRetiredTerminalClosesConnectionAndReconnectUsesCertifiedFacts` |
| Live missing-base repair | `liveRepairRequestsCatchUpForOpenedConversation` |

These tests drive the real repository/decoder/store. Existing legacy tests stay enabled. Configuration changes do not own query jobs; connection lifetime does.

## Error handling

Typed decoder outcomes and store outcomes stay internal. Additive command seams return admission booleans, snapshots expose sticky repair/reset and certified coverage independently of items. Static lifecycle/error codes are logged, never frame contents, daemon error prose, ids, epochs or message text. No exceptions enter UI state.

## Testing strategy

Write failing repository probes first, then implement. Use channel-backed fake pumps, shared stores and `runTest` clocks. Probe all four invariants with duplicate/reordered batches, empty and one-row replies, boundary ties, out-of-range parents, interrupted delivery and retired work. Run the new query class, existing authoritative store/route tests, decoder tests and legacy repository tests, then lint, assembleDebug, Spotless and final pre-verify. No screen/device/live scenario is required for this additive data seam; #1988 owns activation and combined live proof.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, “Supplied route and connection ownership” and “Repair, checkpoints and reset generations”, with query command/observation seams, remote-slot draining and connection retirement, numeric coverage and checkpoint certification, parent extras and retry behavior. Document shared-store boundaries for #2149/#1986/#1988 and the generation-captured repair notification lessons; link the daemon protocol rather than duplicating it.

## Open Questions

None. Empty initial epoch may receive a terminal reset; the coordinator follows with a zero-version window in the reported epoch. History numbers above the store's existing safe integer domain fail without certification.

## Security review

**Verdict:** PASS

- [Trust boundaries] `ThreadQueryDecoder` is the sole wire boundary. MUST FIX addressed in design: correlated decoder success alone is insufficient; generation, batch application, requested page boundary and all coverage numbers in the store's safe-integer domain must also validate before certification.
- [Tokens] No credential generation or storage changes. Request identities use the connection allocator and are correlation, not authentication.
- [Files and storage] No files, paths or persistence are introduced. Atomic cache storage remains the separate child of #1984.
- [Android attack surface] No exported component, intent, provider or renderer changes. Items remain inert bounded decoded facts.
- [Cryptography] Existing Noise and TLS transport is unchanged; no keys, nonces or secret comparisons enter this coordinator.
- [Network and I/O] Existing bounded assembler is reused. SHOULD FIX incorporated: an overall request deadline abandons missing-terminal assembly even when no fragment is pending; a separate bounded drain deadline closes the connection when remote completion cannot be established; provider errors require a fresh explicit retry, preventing a busy retry loop.
- [Errors and logs] Only static event/code pairs; query payloads, daemon error messages, decrypted content and opaque identities never reach logs or exception prose.
- [Concurrency] MUST FIX addressed in design: one remotely occupied slot independent of local assembly, fresh decoder per attempt, captured generation and teardown fencing. Local retirement must never redispatch before the daemon's terminal outcome; lost drain completion closes the connection. Request ownership persists to consume retired errors before legacy evidence tracking. A fresh connection shares facts only, never pending requests. Lock order and scope cancellation are explicit above.
- [Threat model] Delayed/dropped relay output cannot certify coverage and expires; hostile frames are bounded and rejected before mutation/certification. Token theft and UI leakage are unchanged transport/storage/presentation concerns outside this data-only slice, with production presentation owned by #1988.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-13

## Revisions

- 2026-10-13: `liveRepairDuringCatchUpCannotBeClearedByItsOldMarker` exposed that a failed live patch need not raise applied progress. The route now notifies only actual repair outcomes, with their conversation owner, outside its lock. The coordinator retires that conversation's active query and requests fresh catch-up, so an older successful marker cannot clear the newly discovered gap. This strengthens the concurrency finding above without introducing another checkpoint authority.
- 2026-10-13: `delayedLiveRepairCannotRetireWorkAfterReset` reproduced a notification crossing a reused-epoch reset after the route lock was released. Repair notifications now capture affected conversation generations under that lock; the coordinator ignores retired generations. The notification remains outside the lock to preserve lock order.

- 2026-10-13 (verifier rework): Findings 1–3 exposed late errors crossing into legacy read evidence, unchecked coverage numbers, and premature dispatch while the daemon still held its provider slot. `AuthoritativeThreadQueries` retains request ownership, drains abandoned requests through validated terminal outcomes and retires the connection after a bounded drain deadline. Pending intents dispatch only after remote completion or a send refusal, and reconnect shares certified facts only. `certifyQuery` rejects all coverage numbers above the item-store domain without changing certified facts. Slot-faithful held-provider probes replace local-send-only evidence; late-error, marker-only boundary and missing-terminal/reconnect probes cover the strengthened contracts. Security trust-boundary, network and concurrency findings above were re-reviewed and updated; no new wire verb or content logging is introduced.
