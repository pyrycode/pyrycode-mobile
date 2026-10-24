# Canonical session-error recovery observer (#2522)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `runCase`, `deliveries`, `completed`; replace retired evidence while retaining phone assertions and cleanup.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `observeProduction`, `catchUpProduction`, `dialLink`, `attempt`; existing negotiated, authenticated observer queries.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ProducerThreadObservation.kt`: completed queries and current-source evidence, including reconnect certification.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: `producerCompleted`, `authoritativeReply`; session/turn and successful terminal precedent.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: source snapshot retains hidden dropped facts, unlike projected rows.
- `app/src/test/java/de/pyryco/mobile/e2e/ProducerThreadObservationTest.kt`: real decoder/query test patterns.
- `scripts/e2e-session-error.py`: independent held/fresh child input counts remain unchanged.
- `docs/knowledge/features/mobile-protocol-v2-wire-layer.md`: exact unsigned wire tokens.
- `docs/knowledge/features/development-verification-peer-reopen-evidence.md`: independent query evidence cannot substitute for phone admission or complete-suite acceptance.
- Core `docs/protocol-mobile.md` at `64ef0e0fba71a723b7fdc97141cfc144f6355664`: Full thread item, Queue observation and delivery identity, Reply batches and successful completion; source of truth.

## Context

Retired queue/content frames make the independent recovery observer wait forever. This is one behaviour slice: migrate the recovery scenario's entire causal observation chain. No production repair or decision record is needed. Forecast: about 850 written lines including this plan, two new test-facing types, one scenario consumer, four acceptance criteria, no production signatures changed. Overlap: #2338 changes diagnostics in the same scenario; keep changes local and preserve its existing work.

## Design

Negotiate thread and interactive on the scenario's independent peer. Establish `observeProduction` before sending. Use its explicit catch-up from the last successful checkpoint for every bounded observation, including final no-replay checks. Reuse the existing query decoder and source ownership machinery unchanged.

Add `SessionErrorRecoveryObservation` under sharedTest, with `RecoveryAcceptance` carrying permanent item ID, exact unsigned queue handle, correlation metadata and text. It reads only current certified source snapshots from its captured producer, rejects epoch changes, and excludes pre-baseline deliveries. Queue reconstruction includes every active queued user item. Identity checks compare the permanent acceptance ID and immutable metadata across revisions; neither text nor correlation merges acceptances. Dropped proof requires the held item explicitly hidden, inactive and dropped, with no order. Fresh delivery must have a different acceptance ID; the held resolution remains in the source snapshot.

Completion requires that exact acceptance delivered with a positive source order and recorded session, followed by main-thread assistant content in the same session and a new turn, followed in source order by that turn's successful terminal item. Reject unrelated same-text content, old turns, session boundaries, intervening delivered users, active/failed content and wrong-session terminals. Existing live session_error and phone status assertions remain independent. No progress injection or send retries.

## State and concurrency model

The peer owns its existing coroutine scope/socket and cancels them on close. Query polling runs sequentially inside the original bounded peer waits; no additional background jobs or flows. ProducerThreadObservation synchronizes query admission and invalidates evidence while a query is partial or its source is closed. The local observer retains only baseline identities/epoch; connection replacement must complete the producer's own catch-up before facts become usable.

## State transitions and identity reuse

| Event | Regression coverage |
| --- | --- |
| Queued acceptance then retained delivery with missed pushes | `canonicalCatchUpReplacesRetiredFramesForBothArms` |
| Give-up then fresh acceptance, including identical correlation/text | `dropThenFreshNeverMergesAcceptances` |
| Partial query, wrong correlation/conversation/source, stale source, reset | `incompleteAndForeignEvidenceCannotProveRecovery` |
| Close/reconnect and replay | `replacementRequiresCurrentCertification` |
| Stale revisions and another queued item | `queueIncludesAllActiveItemsAndPreservesTerminalResolution` |
| Wrong session/turn, idle only, unrelated text, terminal before content | `completionRequiresOwnedOrderedSuccessfulTerminal` |
| Repeated final catch-up | causal regression retains one delivered ID; existing child-input counters prove exactly one child input |
| Pairing removal and preceding host restoration | unchanged named deterministic device method |

## Error handling

Unavailable evidence yields no observation, never an empty queue or successful completion. Invalid immutable acceptance metadata fails with static diagnostics. Existing query rejection/reset fails closed. All existing deadlines remain, including the 20-second phone status-clear check.

## Testing strategy

Write the causal regression first and observe the old raw predicates fail without retired frames. Add pure JVM invariant probes driving real ProducerThreadObservation queries and decoder. Run the new class and existing producer/owned-reply classes. Run scripted `session-error` twice on Linux for fresh then reused effective inputs, inspecting counted XML, both arm journals, source/binary/APK identities and cleanup receipts. The device method requires real encrypted relay, Keystore, child process and native UI timing; Robolectric cannot supply that proof. No real-Claude or other-OS check. Run lint, assembleDebug, instrumented compilation, Spotless and final pre-verify. Full configured scripted acceptance and results handoff to #2338/#2327 remain dispatcher-owned.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “Session-error recovery (#1731)”: record canonical observer boundary, causal regression, counted Linux results and persistent evidence paths. Preserve original failures; distinguish Core #3711's producer defect from this observer incompatibility, and focused proof from pending complete acceptance/#2327.

## Security review

**Verdict:** PASS

- Trust boundaries: only ProducerThreadObservation's current correlated successful queries admit source facts; hidden drop resolution is read from the snapshot, never inferred from absence. Negative probes cover stale/foreign/partial evidence.
- Tokens: reuse existing in-memory peer pairing and Keystore stores; no new credential generation, persistence or exposure.
- Files/storage: raw evidence remains private on the persistent agents volume with owner-only permissions; publish only counts and hashes. No daemon-authored path becomes a file name.
- Android surface: no manifest, exported component, UI, intent or provider changes.
- Cryptography: reuse existing Noise_IK transport without key/nonce changes.
- Network/I/O: existing bounded decoder and transport retained; query polling remains within original deadlines and read-only queries alone may repeat.
- Errors/logging: new diagnostics contain static stages, counts and status labels only; no tokens, pairing material, prompts, decrypted frames or identifiers in public logs/assertions.
- Concurrency: sequential polling, existing synchronized source lifecycle, no new jobs or locks. Reconnect cannot certify old partial data.
- Threat model: relay delay/reorder/loss cannot establish completion without the current correlated marker; hostile decoded content remains inert and unlogged. Rooted-device token theft and UI keyboard/accessibility leakage remain protected by existing storage/pairing paths, unchanged by this test-only repair.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-24
