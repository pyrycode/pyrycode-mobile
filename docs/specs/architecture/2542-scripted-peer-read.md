# #2542 — Certify the scripted second-client read

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `threadWindow`, `readMarks`, `markRead`, `exchange`, `attempt` and `dialLink` establish query and connection attribution.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` retains exact-post receipt, Unread and daemon-confirmed Idle assertions.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt`: `PumpState.Open` exposes the authenticated physical connection identifier.
- `docs/knowledge/features/development-verification-report-ownership.md`: fresh counted XML owns a result; later passes cannot replace failed evidence.
- `docs/e2e-interactive-stream.md`: “Peer read clears phone attention (#1883)” supplies the existing operator proof contract.
- Core `docs/protocol-mobile.md`: “Marking a conversation read” is the wire contract; Core `MarkConversationRead`, `installDaemonThreadRelay` and `queryDaemonThread` distinguish query acquisition from global shown authority.

## Design source

No visual or navigation change. Existing scripted assertions stay intact.

## Context

The retained #2494 candidate `78e25757935229e975a7cab0c23b2504f237c19c` executed two macOS methods: one passed and one failed after the phone's read and Unread proof. The daemon refused the peer read because shown authority was unavailable. Its trace does not establish producer failure, stale acquisition or an invalid target. The embedded Core revision and installed-byte readback are unavailable. Preserve these limitations and the earlier distinct `c991edc` author pair.

One behavior is in scope: the exact completed post acquired by the current peer must be covered by a daemon-confirmed mark before the phone becomes Idle. Forecast: approximately 450 written lines, no production API migration, no exported types. No decision record is needed.

Shared-file overlaps: #2032, #2090, #2140, #2175 and #2494 touch `SecondClientPeer`; diagnostics are additive and local. #2494 remains the downstream integration owner.

## Design

First capture a bounded trace at the existing peer request boundary. Report hashed host/conversation/connection/epoch identifiers, local physical pump identity, request/reply correlation, query version and completion, numeric item identity/revision/status, requested target and confirmed mark. Never log content or raw payloads. Bind inbound trace attribution to the recorder's actual pump, rather than the currently selected replacement.

Do not infer a repair from a refusal code. Compare the trace to the supplied read-only Core source `9aa29204ef9c7f087340abe862d5811d7e0b11bc`. Implement a narrowly demonstrated helper defect only after a controlled regression fails. If the cause belongs in Core, route it to its owner with the retained evidence. A named bounded diagnostic is the next step if existing evidence cannot discriminate.

## State and concurrency model

Existing peer IO scope, recorder, redialing link and cancellation remain unchanged. Diagnostic counters are thread-safe and capped per peer. No new worker, request, retry, deadline or read write is introduced. Diagnostics observe the actual pump used by `attempt` and its inbound collector.

## State transitions and identity reuse

- Reconnect: attribute each outgoing and incoming frame to its physical pump and authenticated connection; never infer ownership from the global current link.
- Query completion: preserve correlated decoder completion, epoch/version and exact received item evidence before a read request.
- Reused conversation or item identifiers: host, physical pump and epoch attribution distinguish occurrences; no cached phone evidence is admitted.
- Close/teardown: preserve `.use`, scope cancellation and existing gate cleanup; diagnostic state has peer lifetime only.

The unchanged native ping method exercises the peer acquisition and confirmed-read sequence. Any demonstrated repair adds a named controlled regression and records these transitions under Revisions before handoff.

## Error handling

All existing failures still fail. An unavailable provider is distinct from a malformed or missing conversation and from a target clamped below the request. A successful correlated `conversation_updated` remains the only read confirmation. No accepted write is retried to obtain green.

## Testing strategy

Run affected helper unit classes and the focused managed macOS scripted ping. Native execution is required because this proof crosses the real daemon, relay, Noise connection and phone UI. Retain actual XML counts, phone/control/daemon/relay diagnostics, identities and cleanup receipts for every attempt. Reuse matching #2494 inputs where possible. A diagnostic-only result does not certify a repair. #2494's original pair at 2/2 and dispatcher configured acceptance remain pending independently.

Before PR handoff run focused checks, lint, assembly, Android-test compilation, Spotless and final pre-verify after merging main. Linux is outside this ticket's verification scope.

## Open Questions

- Does the peer read use the physical connection and epoch that completed exact-post acquisition?
- Is shown authority unavailable despite fresh same-connection list and completed query evidence, and can the cause be discriminated without a Core diagnostic?
- Which controlled regression demonstrates the eventual repair? Do not substitute an incidental later pass.

## Documentation handoff

Pending for documentation: update `docs/e2e-interactive-stream.md`, “Peer read clears phone attention (#1883),” with demonstrated cause, controlled regression, counted macOS evidence and provenance limitations. Preserve all failures and distinguish `c991edc` from the current 1/2 pair. Link #2494 as integration owner; its final integrated-proof link remains pending until downstream publication.

## Revisions

- 2026-10-24: diagnostic `e6d6d1f6e136971a4f208fe566b135c6df5345b4` on current main executed 2 native methods: 0 passed, 2 failed, no errors/skips. The durable-gap producer query timed out; following ping timed out before receiving the reply, so the peer-read boundary was not reached. Raw evidence and identities are retained in the agents evidence directory `2542/main-diagnostic-e6d6d1f6e`.
- 2026-10-24: to reach the assigned boundary without adopting the whole parent patch, temporarily reuse `ProducerThreadObservation`'s query-refusal recovery, `SecondClientPeer.catchUpProduction`, `PingReadProof` and the phone proof's current-owner evaluation from #2494 candidate `78e25757935229e975a7cab0c23b2504f237c19c`. This is diagnostic comparison source, not this child's repair. Keep it in a separate commit and revert that commit before the final child diff. Do not copy fixture cleanup owned by #2514 or unrelated parent diagnostics. The original peer-read stage remains unchanged. The temporary source remains below the 1600-line limit with a forecast of approximately 650 written lines including its removal and the eventual peer repair.
- 2026-10-24: comparison `4a9aab0560ef107be7f8bccf16c5bcc7b9317204` passed both native cases (2 executed, 2 passed, no failures/errors/skips). Its trace proves successful exact-post acquisition at query 2/version 14, post 13/revision 14, fresh metadata at query 3 and daemon confirmation of target 13 at query 4, all on one current physical peer. This later pass does not explain the retained historical refusal. The comparison omitted the parent's deliberate phone connection replacement after reply certification; the next bounded comparison adds that original condition, without changing peer acquisition/read code.

## Security review

**Verdict:** PASS for bounded diagnosis; review any demonstrated repair before implementing it.

- Trust boundaries: trace only allowlisted frame metadata at `SecondClientPeer.attempt` and the actual recorder. Hash arbitrary identifiers and allowlist status/code labels; never render or log daemon text.
- Tokens: reuse existing peer pairing and Keystore paths. Never log pairing tokens, keys or headers.
- Files/storage: no new device storage or externally supplied paths. Raw native evidence stays in private persistent evidence storage; only checked summaries may enter Git.
- Android surface: no exported component, intent, provider, WebView or permission changes.
- Cryptography: unchanged Noise IK and key lifecycle; hashing is diagnostic attribution only.
- Network/I/O: unchanged frame limits, request selection, deadlines and transport. Never add retries of accepted writes.
- Errors/logs: cap diagnostics, log numeric metadata and hashed identifiers only, preserve refusal as failure. Raw errors/payloads are excluded.
- Concurrency: counter updates are atomic; actual pump attribution avoids labeling an old frame with a replacement connection. Existing scope cancellation and recorder teardown own all work.
- Threat model: malicious relay delays/reordering still face existing correlation, decoder and deadlines; hostile daemon fields cannot inject diagnostic text. Rooted-device token theft and UI screenshot/accessibility exposure remain governed by unchanged credential/UI protections.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-24
