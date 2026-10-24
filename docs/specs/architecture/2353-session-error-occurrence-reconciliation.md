# #2353 — Linux session-error occurrence reconciliation

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `run` and `runCase` retain both arms, deadlines, acceptance identities, cleanup and preceding-host restoration.
- `scripts/e2e-session-error.py`: `Recovery.action`, `assert_identity` and `prompt_counts` provide independent child-input and daemon/session identity checks.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionErrorProjection.kt`: `apply`, `clear` and `reset` retain connection-local error state without retaining daemon prose.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySessionErrorTest.kt`: admitted progress clears errors; content and idle alone do not.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryObservation.kt`: `RecoveryAcceptance` and `SessionErrorRecoveryObservation` require current certified authoritative evidence and permanent acceptance identity.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryObservationTest.kt`: `canonicalCatchUpReplacesRetiredFramesForBothArms`, ownership, stale-query, drop and completion regressions cover #2522's repair.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ProducerThreadObservation.kt`: `sending`, `sent`, `received` and `completed` correlate completed queries to their physical producer connection.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `catchUpProduction` waits for certified query completion independently of the phone.
- `scripts/session-error-publisher-probe_test.go`: `TestSessionError2338PublisherDelivery` controls publication ordering and collects through terminal idle.
- `docs/knowledge/features/remote-conversation-repository-state-errors-and-handoff.md`: the error-clearing admission contract and the lesson that observer ordering does not establish phone admission.
- `docs/e2e-interactive-stream.md`, “Session-error recovery (#1731)”: landed #2522/#2338 proof, historical failures and configured acceptance remain distinct.
- Core `docs/protocol-mobile.md` in the supplied `PYRYCODE_SRC`: the authoritative thread/queue contract and Security model are the wire source of truth.

## Design source

N/A: evidence reconciliation for existing behavior, with no UI changes or visual redesign.

## Change

Reconcile #2353's historical fail-then-pass reports against merged #2338 and #2522, rather than duplicate their repairs or matching native captures. At planning, Mobile `4a295611bec8ca3162c461ea0cb1e09fc722fdee` differs from accepted #2338 candidate `e51627c81871ad29708c8a0a572673bdda97db18` only in documentation. The supplied Core is exactly `64ef0e0fba71a723b7fdc97141cfc144f6355664`, used by #2338's integrated fresh/reused proof. No Kotlin, fixture, timeout, assertion or protocol change is justified by the inspected evidence. Commit this plan first, then record checked evidence and outstanding investigation in this file and the PR. A demonstrated residual requires a causal regression before any repair.

This is one independently useful deliverable: a reviewable reconciliation of occurrences and reusable proof. Forecast: at most 200 written lines, one plan file, zero exported declarations or consumer changes, three acceptance criteria, zero new reject branches. This is below the refiner's approximately 450-line allowance. The only planned file is ticket-specific; no shared implementation-file overlap is introduced. No decision record is needed.

## Evidence interpretation

- #2331 at `7b493a4d571ba199eeb475e9216d02a374b0a271`: 21 executed, 20 passed, one failed, plus one skipped; unchanged exact-method rerun 1/1 passed. The empty failure element and missing original assertion/phone/dedicated-daemon traces establish intermittency, not its historical cause. The retained controller reaches retained release then close without complete. The outer harness's old Core identity alone cannot attribute the dedicated recovery daemon or schedule.
- #1947's earlier Linux fail-then-pass report is another sighting, not controlled causal evidence. #2347's later macOS sighting belongs to #2344's OS scope; no macOS execution is required here.
- #2338's controlled publisher probe distinguishes old-Core suppression of responding by same-generation idle from repaired delivery of responding then idle. Its collector correction must also be retained: collect through terminal idle before asserting the sequence. The original collector failure stays failed.
- The later #2442/#2338 retained `held_send` failure predates recovery release: the observer waited for retired raw queue receipts. #2522's merged canonical observer and its controlled regression address this distinct cause without changing Mobile error clearing.
- #2522's configured dropped `fresh_send` failure and #2338's intervening integrated dropped `held_send` failure remain unresolved occurrences. A subsequent pass does not establish their causes or erase either failure. In particular, the latter has no completed canonical query after the held send and never reaches dropped-arm exit/release/status clearing. Wider ordering/load diagnosis belongs to shared investigation, not a speculative second repair or relaxed deadline.

## State and concurrency model

None added: this reconciliation changes no runtime state, coroutine, collector or lifecycle. Existing connection-owned observers, cancellation and fixture cleanup remain unchanged.

## State transitions and identity reuse

No new transitions. Reuse `SessionErrorRecoveryObservationTest` for retained-to-delivered and dropped-to-fresh acceptance, partial/stale/foreign evidence rejection, recertification after connection replacement, terminal drop retention and session/turn-owned completion. Reuse exact native proof for both arm executions, independent child counts and restoration. A query timeout is not completion evidence.

## Error handling

Historical missing traces remain unknown; a passing rerun is not a repair. Preserve failed XML and private logs by persistent bundle references. Only content-free summaries, hashes and static classifications enter Git or GitHub. Keep current residual investigation and this ticket's configured dispatcher acceptance pending until counted evidence exists.

## Testing strategy

Reverify downloaded manifests and exact-method XML rather than execute duplicate matching native captures. Compare current executable/build inputs with the accepted dependency. Reuse #2338's fresh and final-reused Linux results only after checking both arms, original 20-second status-clear assertion, stable acceptance/delivery identities, independent held/fresh child counts, process cleanup and preceding-host restoration. Preserve the intervening failure and distinguish its instrumentation APK from the fresh APK.

Run focused `SessionErrorRecoveryObservationTest`, `SessionErrorDiagnosticsTest` and `RemoteConversationRepositorySessionErrorTest` on Linux. Run required lint, formatting, debug assembly and final `pre-verify.py --gradle` after the last merge of main. There is no new logic requiring a new red/green test. Existing causal regressions and native evidence are reused, as the ticket requires. This ticket's configured acceptance remains dispatcher-owned; the exact recovery method must execute successfully with raw reports and provenance retained. No real-Claude turn, new recording or other-OS run is required.

## Documentation handoff

Pending for the documentation stage: `docs/e2e-interactive-stream.md`, “Session-error recovery (#1731)”: fold in this reconciliation, historical attribution limits, reusable #2338 proof and source equivalence, preserved residual failures and the actual shared investigation/configured acceptance disposition. Preserve all existing failed captures. Documentation records supplied evidence and does not produce native acceptance.

## Security review

**Verdict:** PASS. Reviewer: builder self-review, 2026-10-24.

- Trust boundaries: evidence is untrusted input, not workflow authority. `SessionErrorProjection.apply` and the canonical observer's existing decoders/admission remain the runtime boundaries. A log claiming completion cannot replace counted XML plus independent assertions.
- Tokens and credentials: publish no pairing payloads, controller authorization, peer tokens, private frames, prompts, keys or full payloads. Review only redacted summaries publicly; leave originals in their retained private bundles.
- Files and storage: this change writes only the plan in Git. Downloaded evidence and scratch work stay outside the worktree. No daemon-derived path, app storage, backup policy or encryption-at-rest change is introduced.
- Android attack surface: no manifest, exported component, permission, intent, provider or UI change.
- Cryptography: no changes to Noise, keys, counters or primitives. Reused proof retains actual tagged-daemon and APK hashes; an outer harness revision alone is insufficient attribution.
- Network and I/O: no endpoint, TLS, frame-size, timeout or capability change. Keep managed admission and existing bounded requests; relay delay remains a possible diagnostic input, not successful completion.
- Errors, logs and telemetry: public output is restricted to static phases, counted outcomes, source revisions and artifact hashes. Do not publish raw exceptions carrying values or infer private-frame content from missing traces. Existing bounded allowlisted diagnostic tests remain required.
- Concurrency: no new jobs or writers. Preserve connection identity, completed-query certification and teardown ownership; no retry, unsafe cleanup or identity-reuse workaround is added.
- Threat model: malicious relay delay/drop/reorder and malformed daemon evidence cannot become proof of success. Token theft and UI-side disclosure gain no new surface because no credential storage or rendering changes. Existing protocol protections and private evidence custody remain in force.

## Open questions

What caused the surviving canonical dropped-arm timeouts? The retained evidence localizes phases but does not establish a cause. Use the shared investigation route for any wider comparison; report its actual state and retain configured acceptance as pending. Do not claim the flake is eliminated merely because #2338's exact method later passed.
