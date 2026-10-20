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
- `ClaudeUsageOwnershipTest.kt`: controlled in-flight ownership-check/retirement interleaving through the real coordinator and remote repository.
- `NegotiatedThreadActivationTest.kt`, `di/RelayConnectionFactoryTest.kt`: preserve content-request prohibitions, host isolation and command correlation alongside connect-time allowance requests.
- `docs/knowledge/features/remote-conversation-repository.md`, `stable-conversation-repository.md`, `relay-repository-coordinator.md`: pairing lifetime and connection-local request ownership.
- Core `docs/protocol-mobile.md`, “Claude usage allowance reading” and “Security model”: wire source of truth, including landed Core #3575/#3576.
- Analogue `f0000c142` (#1317): 526 additions, 60 deletions; reuse its pairing owner rather than add another store.

## Context

Expose one usable behaviour: observe and refresh the paired host's latest account allowance through the real repository and thread facade. Decoder, held state, requests and lifecycle tests are its necessary plumbing. UI rendering is another child of #2226. No decision record is needed.

Estimated total work: 850 lines including plan, tests and adjustments to existing request-count assertions; six production files, five exported model declarations, one updated production construction site, three acceptance criteria and fewer than ten decode rejection categories. No signature replacement or migration is required. Rechecked against the finished plan: within every ceiling.

Rework sizing: approximately 1100 total written lines after the controlled concurrency regression and traffic-assertion repairs. These repair the same host-ownership behaviour and its required verification; no independently useful behaviour or new exported production declaration is added, and only the coordinator supplies the new optional lock argument.

Overlapping branches #1947, #2175, #2245 and #2322 touch shared repository files; their context/thread changes require no allowance dependency. Keep additions local.

Rework overlap check: #2089, #2091, #2100, #2118 and #2138 add readiness tests in `RelayConnectionFactoryTest`; their separate test methods require no dependency. Existing repository overlaps remain unrelated to the allowance commit boundary.

## Design

- Add portable sealed `ClaudeUsageReading` with `None`, `Unavailable`, `Observed` and nested `Window`. Observed carries an `Instant` and independently nullable windows; each window has independently nullable `Double` utilization and `Long` reset seconds. Unknown wire state maps to Unavailable without retaining arbitrary text. Null observation means no accepted frame for this pairing yet.
- A single internal `decodeClaudeUsage` boundary in the model file validates a JSON object and a required string state. Known states validate every supplied known field's primitive/object shape; observed requires a parseable observation timestamp. Optional fields must be absent or correctly typed, never coerced strings/nulls. Finite utilization remains unclamped and reset seconds retain the full signed 64-bit range. Unknown keys are ignored. Unknown string states become Unavailable without interpreting future fields.
- `HostReadings` holds one `MutableStateFlow<ClaudeUsageReading?>`, not a conversation-keyed map. Accepted frames replace it whole in serialized arrival order; equal values conflate naturally. Its existing `whileOpen` wrapper clears observers on pairing disposal.
- Add default `observeClaudeUsage()` and no-op `requestClaudeUsage()` to `ConversationRepository`. Remote observation reads its holder; refresh sends one `request_claude_usage` with `{}` using the shared request-ID allocator, with no waiter, conversation lookup, capability gate or model call. Refused sends are absorbed.
- Route `claude_usage` before conversation/session admission and all history/replay writes. Both correlated replies and pushes use the same decoder and replacement. The coordinator supplies its own monitor as `connectionOwnerLock`, alongside the live-ownership supplier checking current transport, child scope and authenticated pump state. Decode outside that monitor, then hold it across the final ownership check and whole-reading replacement. Allowance sends also hold it across validation and send. Retirement and new connection publication already hold the same monitor, so an in-flight old handler either commits before retirement or is rejected afterwards; it cannot overwrite a new connection's reading.
- The coordinator requests once after each pump reaches authenticated Open, using its child scope. The facade reads the injected host holder directly during connection gaps and delegates refresh to the current repository. Its compatibility path without a holder uses the existing switched-null convention.

## State and concurrency model

Each connection's inbound coroutine serializes its own arrivals, but different connection scopes can run concurrently. The coordinator's monitor serializes the final allowance ownership check and replacement with `onConnection`, `teardownActive` and pairing `close`. No suspension occurs while holding it. Decoding occurs outside the monitor and must revalidate on entry. A synchronous transport change can invalidate an owner before teardown collection; its supplier still rejects that source, and the replacement cannot publish its own reading until the old commit releases the monitor. Cancellation alone is not the synchronization guarantee. Allowance requests use the same monitor through `pump.send`; the existing coordinator-to-pump lock order is retained, with no new reverse acquisition.

StateFlow provides fan-out and equality conflation. No new long-lived scope, dispatcher, timer, polling loop, request ledger or persistence is added. Open requests run on the coordinator child scope and cancel with it. Background/socket closure retains the pairing holder; unpair/re-pair closes it. Process restart starts without a reading.

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
| Old handler pauses after its final ownership operands pass while a new connection attempts to publish None | `ClaudeUsageOwnershipTest.replacementCannotOvertakeAnInFlightAllowanceCommit` |
| Pairing disposal, same host re-paired and old frames | `ClaudeUsageRepositoryTest.pairingDisposalClearsAndRePairStartsEmpty` |
| History, replay and rate-limited independence | `ClaudeUsageRepositoryTest.allowanceNeverChangesRowsReplayOrRateLimitedState` |
| Replay connection reads race, replies stay behind actions/checkpoints | `RecordedExchangeTest.allowanceCanRaceConnectionReadsWithExactPayloadCountsAndCorrelation`, `RecordedExchangeTest.allowanceCannotBorrowAReplyAcrossAnAction`, `RecordedSuiteTapeTest.allowanceReadRacesOtherReadsWithoutReleasingAFutureActionsReply`, `RecordedSuiteTapeTest.allowanceCannotCrossAnObservationCheckpoint` |
| Replay reconnect reuses IDs; foreign owners, malformed/extra/missing requests and persistent failures | `RecordedSuiteTapeTest.allowanceRequestsKeepExactHostConnectionAndFailureOwnership` and both Python allowance contract tests |

## Error handling

Malformed payloads return null from the decoder and preserve held state. Unknown state strings are valid unavailable replacements. Offline refresh is a no-op; false/throwing sends preserve readings, with no retry until an explicit refresh or new connection. Logs contain static event/outcome codes only, never payloads, quota values, state text or decode exceptions. No errors enter UI state.

## Testing strategy

Write decode and real remote/coordinator/facade tests first and observe red before implementation. Use fake managed pumps with `runTest` and injected StandardTestDispatcher; collectors run in backgroundScope. Exercise actual empty request objects and the empty-capability Open path, with no conversation list. Existing coordinator, stable-facade, context and rate-limit tests remain covered. Invariant probes vary arrival order, equal/replayed frames, empty/single windows, reused IDs and reconnect boundaries through production routing. No emulator, recorded scenario or live Claude turn is required for this data-only contract.

The ownership regression uses independent `Dispatchers.Default` workers and a bounded latch in a fake pump's final state read, after capturing authenticated Open. It verifies the coordinator monitor is held and controls whether the replacement publishes before or after the old handler resumes. Without the monitor it forces the stale overwrite; with it retirement waits and the replacement remains final. It uses real coordinator and repository routing, without production test hooks or sleeps. Run both affected activation and factory classes to check sibling traffic assumptions as well as the seven named failures.

Run focused JVM classes, lint, assembleDebug and forced Spotless on Linux; retain fresh XML/raw reports, counts and candidate revision outside the worktree. Complete suites remain dispatcher-owned. Merge main, push, then run assembleDebug and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None.

## Revisions

- 2026-10-20 (main merge and verifier finding 2 investigation): Merge `1a845e57d` while retaining all upstream thread replay tests, the current-protocol ping recording and its matching receipts; preserve the three allowance tape probes. The earlier allowance ping capture remains historical evidence, superseded by the upstream recording rather than spliced into it. All 67 suite recordings plus the standalone permission recording now need genuine allowance-aware replacements. The merged tape class passes 47/47, Python recording checks pass 48/48 and APK assembly passes. The unchanged named claim-extension probe passes 1/1 in 53.361 seconds, which does not erase the verifier's complete-gate and exact-repeat failures. Its four real-disk cases share a minute, as did the neighboring probe repaired in #2269. Add content-free monotonic phase markers outside its measured SQL interval and close each final reader before completion, retaining all 20/18000-row fixtures, sibling/change/removal/reopen assertions and exact work counters. First retain a diagnostic at the original deadline; only measured completion can justify a bounded method-local deadline. No cache production change or global timeout is planned. No in-flight branch overlaps this test. This adds about 40 written lines to the roughly 1300-line branch, with no exported declaration, consumer migration, product state or error branch.
- 2026-10-20 (focused genuine replacement): Capture and fresh replay of `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` each executed/passed one original native scenario at Mobile `f3b1f8f3b6567c2172f03cc50e1568899a688071`, Core `bf1e90156c96c4addcd7faa8e70cd892f0cd28a8`, Claude Code 2.1.280 on Linux/API 33. Promote the exact captured bytes (SHA-256 `353b79c4341dabdf5b4faf26f1a61f3a42859487276bc9b8dfe6f759f6ef79fa`) and its capture/replay receipts in `app/src/androidTest/assets/recorded/suite.json`. Commands, connection lifecycle and checkpoint names/order match the old capture. Reviewed differences are one exact empty allowance request with a genuine correlated reply, and the capture device's push-registration name; token, platform and registration count match. The old replay and strict comparison still failed and remain retained as failures, with an explicit replacement-review note rather than a passing compatibility receipt. The permission recording and remaining 66 suite recordings still require genuine capture, review, promotion and counted replay; a single successful case cannot establish their compatibility.
- 2026-10-20 (verifier replay finding 1): Authenticated Open also runs in both recorded adapters. Treat `request_claude_usage` as an ordinary host-scoped read in `RecordedExchange.queries`, `RecordedSuiteTape.queryTypes` and Python `QUERIES` (inherited by the suite comparator). Keep exact empty payloads, per-owner IDs/counts, correlation and existing command/checkpoint reply fences; never synthesize allowance replies or tolerate absent requests. Add adapter and comparator regressions for racing reads, malformed requests, duplicate/missing traffic, sticky mismatches and reconnect/host ownership. Existing repository probes retain malformed-response and retirement coverage. Genuine replacements are required for the standalone permission asset and all 67 suite assets, with inventory hashes and reviewed capture/replay receipts. Focused device reproduction and subsequent replay remain required; complete capture/compatibility and acceptance use the managed investigation route and remain pending until counted artifacts arrive. Estimated repair adds 300 lines, keeping written work below 1600 with no exported production declarations. Branches #1947, #2052 and #2245 overlap replay tests; #2245 changes thread-query replay, while this additive allowance classification needs none of its changes.
- 2026-10-20: `malformedKnownFieldsRejectWithoutCoercion` demonstrated that `JsonPrimitive.longOrNull` coerces exponent notation into an integer. Parse reset token content with `String.toLongOrNull` instead, retaining the existing strict integer contract and regression assertion.
- 2026-10-20: Plain JVM coordinator tests previously had no connect-time diagnostics. Capture/restore `RelayLog.sink` in allowance tests (including a static-outcome privacy assertion), quiet/restore it in existing coordinator tests, and select each existing command by type when checking sends now that Open also requests allowance. Production contracts are unchanged.
- 2026-10-20 (verifier finding 1): Per-connection serialization and two ownership reads leave an in-flight check/write race across connection scopes. Share the coordinator monitor with the remote repository and hold it across final ownership validation and allowance replacement, and across allowance validation/send. `onConnection`, `teardownActive` and pairing disposal use that same monitor. The controlled concurrency regression pauses after the final ownership operands pass and proves that retirement cannot overtake the commit. Elevated risk remains appropriate.
- 2026-10-20 (verifier findings 2–3): Update activation and factory assertions to allow the required authenticated Open allowance request while preserving exact command payloads, request-ID correlation, host isolation and prohibitions on legacy backfill. No production request gating changes.

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
- Concurrency: MUST FIX from verifier finding 1 addressed in the revised design: the coordinator monitor covers final ownership validation and replacement as one critical section shared with retirement, new ownership publication and pairing close. A paused old handler cannot resume its write after a new connection commits. Decoding outside the monitor rechecks ownership inside it; cancellation is only cleanup. Sends use the same monitor and retain the existing coordinator-to-pump lock order. Pairing close makes held observations null. All new coroutines use the cancelled child scope. `ClaudeUsageOwnershipTest.replacementCannotOvertakeAnInFlightAllowanceCommit` probes the actual concurrent boundary.
- Threat model: hostile authenticated frames are type-checked and retain at most one bounded-envelope reading; duplicates cannot grow state. Relay confidentiality/misrouting remains protected by existing Noise authentication. Disk token theft and UI screenshot/keyboard leakage introduce no new surface in this data-only change.
- Replay repair: test-only query classification adds no production trust boundary, credentials, storage, Android surface, cryptography, I/O or coroutine. Exact payload and per-connection request/correlation checks remain mandatory. Unit-test frames are synthetic regression inputs only; shipped provider content must come from the genuine capture path, with existing secret scanning, bounds, producer metadata and counted receipts. Unexpected sends remain sticky failures. No payload/exception logging is added.
- Timeout investigation: test-only phases report static phase names, fixture sizes, mutation mode and elapsed milliseconds. No provider content, credentials or production surface is added. The real-disk work, assertions and localized mutation counters stay intact; explicit close bounds each reader's lifetime. A green retry never reclassifies the retained failures as inherited.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-20
