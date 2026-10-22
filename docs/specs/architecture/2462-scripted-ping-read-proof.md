# Stabilize scripted ping's final read proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` captures a repository before the final read wait; durable-gap restarts the shared daemon.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidenceOwner.matches`, `harnessThreadEvidence` and `authoritativeThreadEvidence` retain exact ownership and authoritative facts.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `acknowledgeAuthoritativeReadCheckpoint` keeps qualified sight on its producing connection.
- `app/src/main/java/de/pyryco/mobile/data/repository/ReadCheckpointRetries.kt`: retries never migrate sight to a replacement owner.
- `docs/knowledge/features/thread-screen-testing-foreground-read-tracking.md`: qualified sight is insufficient without certified coverage and daemon confirmation.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: the host-owned store survives connection churn, while support and confirmation remain connection-owned.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: managed capture retention and effective binary identities.

## Design source

N/A: test evidence only, with no visual change requested.

## Context

The retained failed #2344 paired capture shows transport failure during the final wait, then fresh qualified sight and a daemon-applied read mark. The test still polls the repository captured before that failure. Diagnose the individual predicate conjuncts before selecting the repair. No product defect is established by the earlier capture alone, and no decision record is needed.

## Design

First add bounded content-free diagnostics around the existing final predicate, preserving all assertions, deadlines (including 90000 ms), order and teardown. Report connection ownership, strict evidence-owner matching, certification, read support, shown position and confirmed position separately. Correlate owners with bounded hashes of host/conversation/epoch and process-local connection identity; never print daemon text or payloads. Retain the managed ping pair's failed captures and effective inputs privately.

If replacement is confirmed, repair only this Mobile test's observation seam: re-observe the exact selected reply on the current negotiated repository, require current authoritative certification under that owner, and consume daemon-confirmed marks from that same repository. Never transfer old sight, globally weaken `ThreadEvidenceOwner.matches`, infer acknowledgement from pending work, or accept an unrelated reply. Record the demonstrated cause and precise replacement contract under Revisions before implementing it. If evidence instead identifies an external cause, retain acceptance and route to its owning blocker.

One behaviour slice: final proof of the rendered scripted reply, with its regression. Forecast at most 600 written lines including plan, diagnostics, controlled tests and integration; zero exported production declarations, one existing consumer, three acceptance criteria, fewer than ten rejection categories. Overlaps #1766, #2091, #2104 and #2175 affect separate methods or shared rule setup; keep edits local to ping, with no dependency on those branches.

## State and concurrency model

No new production state, jobs or flows. Compose's existing polling wait continues to drive frames; each sample reads current support and rechecks connection ownership after evidence and marks are sampled. Test-only diagnostic output is capped and includes a terminal sample on failure. Repository retirement and host retry cancellation stay unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Stable connection, rendered reply, delayed certification/acknowledgement | New controlled read-proof tests and original paired ping scenario |
| Connection replacement during final wait | Controlled regression; paired scenario retains replacement attribution |
| Epoch/generation replacement or reused item ID | Negative controls requiring freshly admitted exact reply and certification |
| Foreign host/conversation, unsupported route, local pending write only | Negative controls reject these proofs |
| Replacement while sampling | Recheck owner after sample; controlled negative control |

## Error handling

Unready samples remain false within the original deadline. The original assertion failure propagates; diagnostics add no retries or longer timeout. Missing acknowledgement remains distinct from retired ownership. No new production error path.

## Testing strategy

Write a controlled regression before the repair and observe its failure. Test strict attribution, authoritative certification and confirmed marks with deterministic supplied snapshots and repositories. Run affected JVM evidence classes, lint, assembly, instrumented Kotlin compilation and forced formatting. Run managed `scripted ping` on macOS: both named methods together, 2 executed/passed and zero failures/errors/skips required. Device execution is needed for the real transport, daemon acknowledgement and foreground sight path. Preserve raw XML, phone/fixture/control/daemon logs, source and effective binary hashes and cleanup receipts privately under the agents evidence directory. Complete configured acceptance is dispatcher-owned; Linux is outside this ticket's scope.

## Open Questions

- Which exact conjunct remains false after reconnect? Resolve with the diagnostic managed run before repair.

## Security review

**Verdict:** PASS

- Trust boundaries: `ThreadEvidenceOwner.matches` stays strict. Fresh certification and exact reply identity are required; unadmitted or foreign facts cannot establish a read.
- Tokens: no credential access or storage changes. Diagnostics exclude tokens, keys, pairing data, prompts, reply text and raw frames.
- Files/storage: only test code and this plan enter Git. Private captures use the managed owner-only persistent evidence store; publish checked content-free summaries only.
- Android surface: no manifest, component, intent, clipboard or UI changes.
- Cryptography: existing Noise and TLS paths are unchanged; standard SHA-256 is used only for bounded diagnostic correlation, never authentication.
- Network/I/O: retain existing deadlines, admission and teardown; no extra queries or product retry path.
- Logs: SHOULD FIX in implementation: cap samples, hash arbitrary routing strings, use fixed labels/numbers/booleans, and never interpolate a DTO or exception message.
- Concurrency: current-owner checks bracket sampling; a replacement cannot inherit old sight or confirmation. No new coroutine scope.
- Threat model: delayed/reordered relay traffic still needs authoritative certification; hostile daemon text never enters diagnostics. Rooted-device token theft and UI screenshot exposure are unchanged by this test-only repair.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-22
