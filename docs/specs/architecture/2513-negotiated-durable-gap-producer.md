# Negotiated durable-gap producer observation

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `dialLink`, `attempt`, `threadWindow` and `record`; physical peer ownership and correlated requests.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_durableGapCatchUp`; both retired producer waits.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`: `missPages` and `catchUpAuthoritative`; retain the disconnected production/restart boundary and all phone assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: `producerCompleted`, `produced` and query witness; reuse exact reply proof with canonical query input.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedThreadQueryObservation.kt`: accepted-query correlation, partial batches and source retirement.
- `app/src/test/java/de/pyryco/mobile/e2e/OwnedReplyObservationTest.kt`: query-only production fixtures and negative evidence probes.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/development-verification.md`, `docs/knowledge/features/development-verification-gates.md`: counted focused evidence, exact source ownership and separate configured acceptance.
- `docs/e2e-interactive-stream.md`, “Scripted ping durable-gap proof (#1842)”: independent producer observations must never supply phone content or certification.
- Core `docs/protocol-mobile.md`, “Thread query messages” and “Queue (v2)”: authoritative requests, separate success marker, explicit catch-up and negotiated capabilities are the wire contract.

## Context

One behaviour ships: prove completed missed production through this producer's negotiated canonical state. Both post and reply observations must migrate together; leaving either raw wait prevents the scenario from running. No application code or broad diagnostic caller migration is needed. No decision record is needed.

The original Linux ping failure remains historical at tested Mobile `2ff4cdae842b3a65b3de0e1577092adecceceba5`, not retention HEAD; `provenance-correction.json` in agents `logs/evidence/2442/rework-linux-20261023/ping/` establishes that distinction.

## Design

Add one test-only shared `ProducerThreadObservation`, constructed with host, conversation, exact physical pump, current-source predicate and negotiated capabilities. It owns an independent `AuthoritativeThreadStore`, one correlated decoder at a time and an optional `OwnedReplyObservation`. It accepts only explicitly requested canonical batches and their separate successful terminal marker. Raw content and live notifications cannot substitute. Initial catch-up establishes a certified newest baseline; subsequent catch-up starts at the last completed checkpoint, so source-only content and revision gaps are recovered without relying on push delivery. Baseline-required older pages are completed before admitting baseline evidence.

The helper freezes the baseline for `OwnedReplyObservation` immediately before the independently sent reply. Feed accepted query requests and received replies into its existing query witness, with the producer's physical pump as owner. Require delivered send identity, matching session/turn, exact text and successful terminal. Reject incomplete, reset-needed, rejected, abandoned and stale source observations. Keep canonical content private to this helper; return only frozen `ProducedReplyEvidence` to the existing phone proof.

`SecondClientPeer` adds opt-in production observation and explicit catch-up methods, and wires request/send/receive/end boundaries with physical pump identity. Existing peer callers retain their behaviour. The durable-gap scenario enables `thread`, obtains its independent baseline, disconnects the phone, queries until the exact completed final older post exists, sends once, and queries until the exact successful reply is available. Existing 90000 ms deadlines and shorter bounds remain. It then produces the newer batch and restarts the daemon through unchanged `DurableGapProof`.

Overlaps: #1766, #1947, #2032, #2090, #2091, #2104, #2140, #2175, #2311, #2462 and #2505 share helper/scenario files. Edits stay additive and local. #2505 changes the phone query-readiness wrapper; #2462 changes the adjacent original ping final-read proof. Neither is required for this independent producer helper, and neither change is imported.

## State and concurrency model

All helper mutations are synchronized. One query owns its decoder and early frames until send acceptance is known. Only successful final certification advances the checkpoint. Queries never resend the independently submitted message. The peer's existing IO scope owns collection and closes on scenario cleanup. A closed/replaced pump permanently retires its observation; reconnect does not transfer producer evidence to a replacement. Phone reconnect and cache flows remain unchanged.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Baseline batches without final | `ProducerThreadObservationTest.baselineRequiresSeparateSuccessfulFinal` rejects admission. |
| Source-only older post then reply | `canonicalCatchUpRecoversPostThenExactReplyWithoutRawFrames` checks checkpoint progression and exact frozen identity/text. |
| Query overlap, partial/rejected send, early reply | Focused helper tests reject incomplete/unaccepted evidence and exercise replies before send returns. |
| Connection replacement/close or reused route identity | Focused stale/foreign-source probes refuse post/reply evidence. |
| Epoch reset, wrong conversation, wrong correlation | Focused reset and wrong-owner probes refuse completion. |
| Identical text in unrelated reply/session/turn | Focused reply identity probes refuse completion. |
| Replayed item or repeated catch-up | Focused canonical catch-up tests retain one item per identity and resume from completed checkpoint. |
| Phone disconnect, daemon restart and phone reconnect | Original native durable-gap method retains missed-ring, cache, chronology, source identity, paging and both held-side assertions. |

## Error handling

Missing negotiated capabilities fail before any producer query. Invalid, reset or failed query outcomes retire proof rather than manufacturing completion. Partial results never advance a checkpoint or permit a new query. Stale ownership yields no proof. Existing bounded waits report structural categories only; no payload, message text, credentials or decrypted frames enter logs.

## Testing strategy

Write controlled JVM regressions first, observe red, then implement. Run the new helper tests plus existing `OwnedReplyObservationTest` and `ThreadEvidenceTest`. Probe each issue invariant, including unrelated identical text and query-only source content. Run Linux managed `scripted ping`: both original named methods must execute, with counts and all raw failure/success evidence retained. Real relay/Noise, phone storage, disconnection and daemon restart require the existing device scenario rather than Robolectric. Do not change any original assertions, retries or time bounds.

Run lint, assembly, instrumented Kotlin compilation, formatting and final `scripts/pre-verify.py --gradle`. Commit/push before final checks. Full configured acceptance remains dispatcher-owned and pending. Linux is this ticket's verification OS.

## Open Questions

None. Implementation must preserve physical source ownership even when a diagnostic peer redials.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Scripted ping durable-gap proof (#1842),” with negotiated producer observation/catch-up and counted Linux evidence. Preserve historical failures, independent phone certification and separate configured acceptance.

## Security review

**Verdict:** PASS

- Trust boundaries: canonical evidence crosses only a correlated `ThreadQueryDecoder`, accepted send and successful store certification; partial or foreign batches cannot prove production.
- Tokens: peer pairing/key handling is unchanged and memory-only. Never include credentials or payloads in diagnostics.
- Files/storage: no new runtime files or cache writes; producer evidence cannot enter phone storage. Durable private test artifacts use the managed evidence directory.
- Android attack surface: no components, intents, providers or rendering surfaces change.
- Cryptography: reuse existing Noise session/pump; no primitive, key or nonce change.
- Network/I/O: preserve decoder limits and bounded query waits; only read queries repeat, never sends. Missing capability fails closed.
- Errors/logs: diagnostics use static categories and numeric counts; source text is compared privately, never logged.
- Concurrency: synchronization fences query state; exact pump ownership and retirement prevent replacement connections from completing an old query. Replacement retains only completed snapshots and the actual original send receipt. It transfers no certification, abandons partial data, and requires a fresh negotiated query in the same epoch before producing evidence. No transport send is replayed.
- Threat model: delayed/reordered/dropped relay frames cannot certify without exact correlation and final marker. Malformed daemon frames fail the existing decoder. Rooted-device secret theft and screenshot/accessibility exposure are unchanged because no storage or UI is added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23

Forecast: approximately 850 written lines including plan/tests, one new test-facing type, two added peer call sites, four acceptance criteria and fewer than ten reject branches. The sole consumer and its regression proof form one behaviour slice within the scope policy.

## Revisions

- 2026-10-23: Core's `TestAssistantMessageTimeAfterReopen` confirms channel posts may have no session identity. `sessionlessCanonicalPostStillNeedsItsOwnCompletion` reproduced rejection of a completed sessionless post. Post admission uses its canonical item identity, order, full text and own terminal status; interactive reply admission retains exact send/session/turn and successful terminal validation through `OwnedReplyObservation.admitted`. No phone settings read supplies producer identity.
- 2026-10-23: Native `scripted-oo07311r` on `905ef6338` executed 2, passed 0, failed 2. The producer received actual 4414 during the older posts, before its next query. A logical observation must reacquire the replacement physical source. Keep only the last separately certified snapshot; abandon partial old-source batches and decoders. A replacement first negotiates capabilities, then explicitly catches up from that completed checkpoint. Reconstruct the reply witness from its original pre-send baseline and the retained actual transport-send receipt, with an optional certified producer snapshot for store seeding. This rehydrates test evidence only and never sends input again. Fresh replacement query certification, identical epoch, exact send/session/turn and current ownership remain mandatory. Old-source late frames and old close callbacks cannot affect the replacement. Malformed/reset/failed queries remain terminally rejected. Controlled tests `replacementDiscardsPartialBatchesAndRequiresItsOwnCatchUp` and `replacementRetainsOneActualSendButCannotReuseItsOldCompletion` cover both boundaries. This supersedes the earlier permanent logical retirement choice; each physical owner still retires permanently. The original ping failed at reply rendering, before its final-read proof; no #2494 attribution is made.
