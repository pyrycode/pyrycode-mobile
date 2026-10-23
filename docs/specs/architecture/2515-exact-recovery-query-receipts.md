# Exact durable-gap recovery receipts

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedThreadQueryObservation.kt`: `sending`, `sent`, `received`, `owner`, `close`; passive decoder, single readiness flag and destructive retirement.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AuthoritativeHistoryProbe.kt`: accepted requests, certificates and resets currently read independently.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: `OwnedReplyProbe.wrap` observes before production dispatch; `OwnedReplyObservation` is the second witness consumer.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DurableGapProof.kt`: `catchUpAuthoritative` initial and final recovery admission, retained-row and paging assertions.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: `dispatch`, `accept`, `retire`, `abandon`; production slot and send/terminal lifecycle, unchanged by this ticket.
- `app/src/main/java/de/pyryco/mobile/data/network/ThreadQueryDecoder.kt`: `decode` correlates actual request IDs and validates separate terminal coverage.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: `certifyQuery` validates captured checkpoint and generation.
- `app/src/test/java/de/pyryco/mobile/e2e/OwnedReplyObservationTest.kt`: existing query-only, early-delivery, reset and saved-checkpoint controls.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: retain XML, phone and daemon logs separately with effective binary identities and cleanup.
- `docs/knowledge/features/authoritative-reader-paging.md`: current source ownership and exact generation fences remain binding.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: source of truth for thread queries and Security model; no wire changes.

## Context

The preserved #2513 candidate reaches recovered content but observes two accepted
requests and only one terminal receipt. Existing timestamps establish production
completion of a second query, not its passive disposition. First add structural
per-request lifecycle tracing and run the original Linux scripted ping pair with
the preserved producer candidate. Record the exact missing correlation and cause
under Revisions before selecting any additional observation repair.

One deliverable: require each accepted recovery query's own valid terminal before
proof admission. The regression belongs with this repair. Forecast: approximately
750 written lines, four existing helper/consumer files and one focused test file,
two new test-facing snapshot/receipt types, two consumers, five acceptance criteria,
at most eight reject classes. No production edits or decision record expected.
The current in-flight #2311 touches the existing owned-reply test file; new probes
will live in a separate class. #2513 changes producer and scenario setup, not the
planned shared-proof functions; compose its preserved commit for native validation.

## Design

`OwnedThreadQueryObservation` keeps exact query identity, captured connection,
host, conversation, source epoch and generation from sending through acceptance
and terminal disposition. Retain accepted records on reset/close, including an
acceptance returning after retirement. Refused sends are excluded. A duplicate
terminal never completes another request. Failed/abandoned work blocks admission.
Reset completes only its own request and requires a new-generation certification;
other outstanding requests are abandoned without successful receipts.

Replace global query readiness with per-request completion plus the reset's fresh
certification requirement. Keep existing bounded early-reply buffering and decoder
validation. Expose immutable accounting snapshots under the probe monitor, with
exact reference matching to certificate/reset requests. Final recovery captures
one coherent ready snapshot together with current recovered evidence under that
same monitor. A later send invalidates readiness until its own valid terminal.
Keep the final explicit receipt assertion as an identity-based check as well as
the existing count assertion. Preserve all other proof assertions and deadlines.

Tracing uses observation-local query ordinals, static lifecycle codes, numerical
versions/generations and equality booleans only. Exact wire IDs and connection
objects stay in memory. Never log payloads, message text, host/conversation/epoch
strings, tokens, credentials or cryptographic material.

## State and concurrency model

No new jobs or flows. `AuthoritativeHistoryProbe` serializes its witness calls and
snapshot/admission with its existing monitor; `OwnedReplyObservation` serializes
its own witness separately. Transport observation remains before production
delivery. Acceptance and an inbound response can occur in either order. Reads
must not combine independent mutable probe snapshots across an admission point.

## State transitions and identity reuse

| Event | Required focused regression |
| --- | --- |
| First certificate, then accepted follow-up before admission | `laterAcceptedQueryNeedsItsOwnTerminal` |
| Overlapping accepted queries, terminal order reversed or duplicated | `oneSuccessCannotCompleteAnotherRequest` |
| Inbound batches/terminal before send returns, accepted/refused | `earlyDeliveryWaitsForItsOwnAcceptance` |
| Partial, malformed or failed response, then another success | `incompleteWorkCannotBorrowCompletion` |
| Close/current-owner retirement before or after acceptance | `retirementRetainsAcceptedAccounting` |
| Reset with reused epoch, then fresh replacement and stale old response | `resetKeepsOldRequestsAndRequiresReplacementCertification` |
| Wrong connection/epoch/generation | `staleSourceCannotSupplyReceipt`, `staleGenerationCannotRetainEarlierReadiness` |
| Actual native recovery and unchanged ordinary ping | original `scripted ping` pair |
| Passive terminal precedes production application of the same frame | `passiveTerminalWaitsForProductionCertification` |

## Error handling

Preserve decoder rejection and repair outcomes. Pending, failed, abandoned or
retired accepted queries remain visible and fail readiness. Reset is an attributed
terminal, never replacement certification. Bound accounting and fail closed on
capacity/duplicate request identity. No UI errors or production behavior changes.

## Testing strategy

Write the controlled JVM regressions first and observe failure before repairing.
Run the new focused class, `OwnedReplyObservationTest`, and `ThreadEvidenceTest`.
Probe all ticket invariants with actual production decoders/store certification.
Run the original Linux managed `scripted ping` pair with #2513 producer candidate
`c2a165f10af099c73d17b9ad253d5a0fb2034792`, retaining the exact composed diff and
source/binary/APK identities. Native execution is necessary for actual encrypted
transport, daemon restart, Keystore/storage and source lifecycle timing.
Keep 30000 ms recovery waits, 90000 ms scenario deadlines and every existing
assertion. Full configured acceptance belongs to the dispatcher. Run focused
lint, assembly, instrumented compilation, formatting and final pre-verification.

## Open Questions

- Resolved by the controlled native trace below; no production defect found.

## Revisions

### 2026-10-23 — observed admission ordering

The diagnostic composed candidate (plan commit plus retained producer patch and
passive trace patch) ran original `scripted ping`: 2 executed, 1 passed, 1 failed,
0 errors/skips. Private evidence is agents
`logs/evidence/2515/builder-linux-20261023/diagnostic-red/`, with matching harness
`logs/evidence/2515/harness/fault-85000f3ef1e53f1f2890ddc5b5fdebf0/`.

Recovery query ordinal 1 captured generation 0, epoch equality true, version 2;
accepted at 17:05:56.361, its batches and terminal certified version 495 at
17:05:57.375. Query ordinal 2 captured the same current connection/generation,
requested version 495 and was accepted at 17:05:57.386. The proof had already
passed its initial readiness poll and then failed `one accepted saved-tail
request` on the separately read list of two requests. Its `finally` calls `end`,
discarding the pending observation. The actual follow-up terminal is processed by
production at 17:05:57.492–57.653, after passive observation has ended. This is
the same late-query boundary as the retained #2513 final 2-versus-1 failure;
that older trace cannot retrospectively establish an exact missing wire ID.
No early send-return delivery was observed in this capture; retain its negative
control because the transport supports that ordering.

Use a versioned immutable probe snapshot and revalidate that exact revision after
reading current content. This avoids taking the probe monitor while suspending
in repository observation. Capture both initial and final evidence this way.
The saved-tail assertion counts the exact baseline epoch/version request, while
every subsequent accepted follow-up remains in the receipt ledger and must
certify its own requested interval. Validate same-source follow-ups just as the
existing reset branch validates post-reset follow-ups. This preserves the one
saved-tail-request assertion without misclassifying legitimate later recovery
queries as another saved-tail request. Existing aggregate final receipt count
remains, augmented by exact identity matching. No retries or bounds change.

The explicit same-epoch generation probe then executed 10 tests, with the nine
accounting checks passing and that probe failing: a previously certified witness
still returned its old owner after the underlying store reset. Fence `owner`
against the store's actual epoch and generation, and compare the captured probe
owner with the repository evidence owner during admission. The snapshot token
also remains invalid after a later request completes; it cannot become current
again merely because counts happen to match.

### 2026-10-23 — passive delivery precedes production certification

The composed `3c86f06b5e74bd068c4814a2572aa199bd8c0382` pair executed 2,
passed 1 and failed 1 (0 errors/skips). It failed the unchanged `current source
certifies every actual recovery` assertion. Exact receipts alone are insufficient:
`OwnedReplyProbe.wrap` calls the passive observer before `emit` delivers the
terminal to production. Recovery ordinal 1 certified 495 passively at
17:25:59.493; production completed at 17:25:59.606. The content-bearing snapshot
still held checkpoint 2 during that interval. Private raw evidence and effective
identities are in `production-boundary-red/` beside `diagnostic-red/`.

Move source admission into the shared `Observation.admits` seam and probe this
ordering with a real decoder certificate and a held production snapshot. In the
existing wait, require the production checkpoint to cover the greatest observed
terminal version and its own completed version, in addition to exact owner and
receipt identity. Keep both original assertions after admission. This adds no
retry, timeout or production behavior; it completes the coherence boundary that
the final proof already asserts.

### 2026-10-23 — production snapshot precedes renderer collection

The next composed pair at `4a99f8ef5597315922196a7009edc4a45e823d5f` executed 2,
passed 1 and failed 1 (0 errors/skips). It passed exact receipts, current source
certification, retained cache facts and chronological recovered content, then the
unchanged newest-row search found a LazyColumn still containing one cached row.
Both admissions at 17:40:18.821 and 17:40:18.866 saw certified checkpoint 495;
production's complete event followed at 17:40:18.910. Raw evidence is retained in
`render-red/`. No persistent production rendering defect is established.

Keep the existing final 30000 ms admission wait open until the real LazyColumn's
`IndexForKey` resolves the newest recovered row's production `listKey`. Compute
that key through `projectAuthoritativeThread`, not a guessed key or visible-text
count; this works even when the row is offscreen and does not scroll or demand a
page. Revalidate the same observation revision after the UI sample. Keep every
original scroll, text, chronology and inert-demand assertion afterwards. This
adds no acceptance loop or deadline and touches no production rendering code.

## Security review

**Verdict:** PASS

- Trust boundaries: the passive witness uses `ThreadQueryDecoder` and production
  store certification, never producer evidence to populate/certify phone state.
- Tokens: no credential generation/storage changes; diagnostics exclude all
  tokens, pairing data, wire identities and decrypted content.
- Files/storage: raw evidence goes to owner-only private agents evidence paths;
  no untrusted values enter paths. No production storage or backup changes.
- Android attack surface: no components, intents, providers or render changes.
- Cryptography: reuse actual Noise transport unchanged; no key/nonce operations.
- Network/I/O: keep decoder byte/count bounds and every original timeout;
  delayed, duplicated or reordered responses cannot authorize another request.
- Errors/logs: SHOULD FIX in implementation: test the structural log redaction
  boundary and override receipt/snapshot stringification to redact identities.
- Concurrency: coherent admission and retained accepted identities prevent a
  success, reset, close or late send-return from erasing outstanding work.
- Threat model: dropped/reordered relay delivery fails closed; hostile daemon
  frames retain decoder validation. Rooted-device token theft and UI capture
  protections are unchanged, outside this test-only repair's attack surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23
