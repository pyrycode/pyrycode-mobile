# #2543 — Extract the retained producer recovery

Source: #2494 / PR #2531 at 78e25757935229e975a7cab0c23b2504f237c19c. Base e2bd0712c.

## Scope
Only retryable producer-query refusal recovery, its causal tests and bounded native diagnostic. Keep accepted-send ownership, final markers, partial-store rollback and original deadlines unchanged. No phone final-read or durable-gap-cleanup extraction in this slice.

## Plan before extraction
1. Copy exact retained ProducerThreadObservation/SecondClientPeer changes and their tests.
2. Keep the native producer diagnostic consumer with its helper.
3. Verify diff, source provenance and composition identity; partial-tree compile/tests and normal independent acceptance remain required.
4. Preserve every historical failed execution, source/binary limit and #2494's final 2/2/native/configured acceptance.

## Verification boundary
Existing full-candidate evidence is retained, not a fresh standalone-slice execution. No acceptance or historical cause is declared by extracting these bytes.

## Files read
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ProducerThreadObservation.kt`: `decode`, `retryableRefusal`, `replaceConnection`, `rebuildReply`, `answered` and `diagnostic` own recovery and its evidence fences.
- `app/src/test/java/de/pyryco/mobile/e2e/ProducerThreadObservationTest.kt`: retained causal regressions and existing ownership/final-marker controls.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: accepted-send ownership and successful reply certification remain authoritative.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `catchUpProduction` retries only reads within `awaiting`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_durableGapCatchUp` consumes the passive diagnostic.
- `docs/knowledge/features/development-verification-recovery-receipt-evidence.md`: a later query needs its own terminal; focused passes cannot reclassify historical failures.
- `docs/knowledge/features/development-verification-gradle.md`: compile both sharedTest consumers and preserve counted native evidence.
- Core `docs/protocol-mobile.md` in the supplied `PYRYCODE_SRC`: authoritative wire and security contract, unchanged by this test-only extraction.

## Context
The extracted bytes already implement one useful recovery behaviour. Keep them and their consumers together; no new product API or wire contract is needed. The existing plan-first commits remain intact. No new decision record is needed.

## Design
Only an accepted, correlated query on the current physical source may finish or release a wait. Exact retryable `thread.query_failed` releases that read without granting completion. Abandon the decoder, discard the partial store, restore `committed`, clear completion and rebuild reply observation using the original actual send receipt. A subsequent successful current-source final must certify the recovered facts. Permanent refusals, malformed/reset and foreign/stale frames retain their existing rejection fences.

`SecondClientPeer.catchUpProduction` loops after an answered refusal, with the existing 50 ms delay and one surrounding deadline. It never resends an accepted message or a read write. `diagnostic` reads synchronized scalar state only; the native finally block emits fixed labels, booleans and numeric metadata.

No new exported types, consumer signatures, state machines or dependencies. Scope forecast: approximately 350–400 total written lines including this amendment, below 1,600; three acceptance criteria, zero API migrations. `answered` has one native consumer. Overlaps include #2542 (separate peer-read tracing), #2544 and #2494 (downstream consumers); preserve their direction. Remote legacy overlaps #1766, #2032, #2090, #2091, #2104, #2140, #2175, #2462, #2511 and #2514 require no symbols introduced by this change. Keep shared edits local.

## State and concurrency model
The existing peer scope owns recording and redial jobs; synchronized observer methods serialize source/query/store transitions. Recovery adds no coroutine or flow. The existing `awaiting` and `withTimeout` bounds enclose retries and replacements. Source retirement invalidates admission; replacement restores data but never a completion certificate.

## State transitions and identity reuse
All rows name retained `ProducerThreadObservationTest` probes unless qualified otherwise.

| Event | Required evidence / test |
| --- | --- |
| Partial batch then retryable refusal and late old final | `retryableQueryRefusalRestartsFromCertifiedCheckpointWithoutPartialFacts` |
| Repeated refusals at the original deadline | `repeatedRetryableQueryRefusalsKeepTheOriginalDeadlineAndNeverSend` |
| Refusal, retirement and replacement reusing a query ID | `refusedQueryCannotReuseAnEndedOwnerOrRepeatTheAcceptedSend` |
| Replacement after partial assembly / earlier successful query | `replacementDiscardsPartialBatchesAndRequiresItsOwnCatchUp`, `replacementRetainsOneActualSendButCannotReuseItsOldCompletion` |
| Permanent refusal | `nonretryableQueryRefusalStillRejectsTheProducer` |
| Send acceptance delivered after retirement or from foreign source | `acceptedSendCallbackSurvivesRetirementButForeignCallbacksCannotAcceptIt` |
| Early replies, rejected send, stale/foreign packets and final-marker absence | `earlyRepliesRequireAcceptedSendAndRetirementRemainsPermanent`, `partialStaleForeignAndAbandonedQueriesNeverProveProduction`, `baselineRequiresSeparateSuccessfulFinal` |
| Source loss during preparation or after completed post | `sourceLossBetweenPreparationStepsReturnsNoQueryWithoutHidingInvariants`, `sourceLossAfterCompletedPostRecoversArmingWithinOriginalBound` |
| Passive diagnostic read during pending/rejected/retired states | `diagnosticIsPassiveAndDistinguishesRejectedPendingAndRetiredEvidence` |

No process persistence, configuration state or screen lifecycle is added; this is an in-memory test peer.

## Error handling
Recover only the exact retryable refusal. Other decoder, application and certification errors retain terminal rejection. Diagnostic reason values are fixed local labels, never daemon error text. Timeouts remain failures with the original bounds.

## Testing strategy
Run the retained producer, owned-reply, owned-thread-query and thread-evidence JVM classes; preserve their raw reports and causal red history from #2494. No repair is rewritten solely to recreate historical red. Compile the native consumers, run lint, assemble, formatting and final pre-verification. Run the affected managed `scripted ping` scenario on macOS and retain every outcome and source/input identity. Real relay/Noise, background pump and durable-gap observation require the native harness, so those existing tests remain device-only. The dispatcher owns complete configured gates and independent security acceptance. Linux is outside this ticket's verification scope.

## Open Questions
None about the recovery contract. Standalone checks and configured independent acceptance remain pending execution; parent integrated 2/2 proof remains #2494's responsibility.

## Documentation handoff
Pending documentation stage: update only `docs/e2e-interactive-stream.md`, “Scripted ping durable-gap proof (#1842),” with refusal rollback, current-source final certification, unchanged deadlines, diagnostic privacy and counted standalone evidence. Link this plan and preserved parent evidence. Keep the full historical #2494 plan / PR #2531 intact. #2494 owns the original complete macOS pair at 2/2, configured acceptance and final integrated documentation; #2542 owns later peer-read refusal. Mark integrated proof pending until published; this child accepts neither ticket.

## Security review
**Verdict:** PASS (builder self-review; independent review remains required).

- Trust boundaries: `received` gates physical source and exact `inReplyTo`; `sent` gates transport acceptance, and `ThreadQueryDecoder` supplies typed outcomes. Rollback discards uncertified data; a new final is mandatory. Retained probes exercise hostile late and foreign frames.
- Tokens: no credential generation, storage or rotation changes; original accepted-send evidence stays in memory and is never printed.
- Files/storage: no new persistence or daemon-derived paths. Raw test captures stay in private evidence custody outside Git.
- Android attack surface: only test-source consumers change; no exported component, intent, provider or WebView changes.
- Cryptography: existing Noise suite and Keystore paths are reused unchanged; no keys, nonces or secret comparisons are introduced.
- Network/I/O: the existing decoder and early-frame bounds remain; repeated refusals cannot extend `awaiting` or `withTimeout`. Delayed/dropped/reordered relay traffic cannot supply a current-source certificate.
- Errors/logs: `rejection` is selected from fixed local labels; `diagnostic` contains booleans and scalar counts/query/checkpoint metadata. No payload, host/conversation identity, credentials, keys or daemon error text enter either diagnostic consumer. Passive-read/privacy regression retained.
- Concurrency: synchronized transitions and physical source identity prevent borrowing old completion. Replacement clears query completion and rehydrates only the historical actual accepted receipt; no transport send occurs there.
- Threat model: hostile/malformed daemon frames and relay delay/reorder are fenced as above. Token theft and screenshot/accessibility exposure receive no changed surface in this test-only work; existing production protections remain responsible.

**Reviewer:** builder, per `builder/security-review.md`. **Date:** 2026-10-24.

## Revisions
- 2026-10-24: builder continuation expands the preserved extraction plan with explicit invariant probes, security self-review and documentation handoff before verification. Recovery implementation and historical evidence remain unchanged.
