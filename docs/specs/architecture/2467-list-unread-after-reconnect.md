# Diagnose and repair list unread after connection replacement

## Files read

- `docs/specs/architecture/2462-scripted-ping-final-read-proof.md`: retained final-proof design and failed later unread acceptance; preserve its exact-owner proof and reconnect.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` waits for unread after admitting a durable post, then peer-confirmed idle without reopening.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateAttention` and `currentReadMarks` join current repository facts with transient attention.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.resolve` preserves waiting, running, unread precedence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot` and `observeHostReadMarks` publish read facts independently of row equality.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `refreshAuthoritativeReadFacts` requests daemon summaries after admitted live completion; query certification publishes completion facts separately.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadRoute.kt`: `applyOutcome` invokes effects only for applied live updates.
- `app/src/test/java/de/pyryco/mobile/data/repository/NegotiatedThreadActivationTest.kt`: `admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark` drives the real negotiated repository with controlled supplied frames.
- `app/src/test/java/de/pyryco/mobile/data/repository/ShownVersionReadMarksTest.kt`: unavailable shown metadata must never reuse a held watermark or legacy receipt.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` and `channel-list-viewmodel-projection.md`: attention joins independently of list equality; connection generations cannot inherit authoritative confirmation.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: certified content and connection-owned read facts have separate authority.

## Design source

N/A: nonvisual metadata/attention diagnosis and repair; preserve the unread dot and all existing row semantics. The refinement comment explicitly identifies this as nonvisual work.

## Context

One useful behaviour: a list-only post remains unread after the rendered reply was confirmed on a replacement connection, until peer confirmation covers the post. Diagnosis and regression ship with that repair. No decision record is needed.

Read both named #2462 captures from managed bundle `8edb493b472ac9e00e68cdfb6409a124d2fb261350303e5ab2d2d0e39477151a` (bundle digest `089310a0b5015277e07ff3811510c37b237ded093c4bddd6bb071b722dacedeb`). The passing capture executed/passed 2; the failed capture executed 2, passed 1, failed 1, with zero errors/skips in both. Both complete final proof with checkpoint 12, shown/read 9/9 on the current connection and retired confirmed 0. Both admit the post after thread release and request a summary refresh; only the passing phone trace records later changed read facts. This narrows investigation but does not select a cause. Retain original manifests: the downloaded publication contains tails in place of two full durable-gap logs and two protobuf hash differences; do not claim original byte completeness. Receipts preserve effective APK, daemon and relay identities.

Base refreshed to retained `origin/feature/2462` at `9ac27ec8a`; it is already an ancestor. Target the reviewed repair PR at `feature/2462`. Sharing paths with #1766, #1947, #2091, #2098, #2104, #2175, #2325, #2338, #2463 and #2464 creates no dependency: current edits stay local to ping and the demonstrated attention/read seam. #2462 supplies the intended base; never add a reciprocal blocker.

## Design

First instrument the unchanged unread wait with bounded content-free samples and a terminal sample. Separate expected source row and rendered row presence, current repository ownership, authoritative read/shown availability and values, waiting/running/busy precedence, attention projection and rendered unread/idle/running/waiting semantics. Correlate host/conversation with SHA-256 and connections with process-local identity. A synchronous internal source diagnostic reads existing state under its monitor, sends nothing and exposes no daemon text.

Select the repair only after these facts and controlled repository tests establish the failing boundary. Keep changes within Mobile's current host/conversation/connection read projection; do not infer read confirmation from local sight, content receipt, pending writes or cached facts, or shown availability from a first row emission. Record the demonstrated cause and repair contract under Revisions before implementing it. An external cause requires its owning native blocker and no sibling implementation.

Forecast 600 total written lines including plan, diagnostics and tests, at most two production files, zero new exported types or signature migrations, three criteria and fewer than ten rejection branches. Reassess against the actual repair before committing it.

## State and concurrency model

Keep existing host collectors and connection cancellation. Diagnostic sampling adds no job, network request or state to product behavior. Compose polling retains its original 90000 ms deadline; terminal sampling runs in `finally` without retrying. Any repaired asynchronous projection must remain owned by its repository scope and reject retired generations.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Confirmed rendered reply, controlled reconnect, thread exit, post admission | Existing paired native ping including final proof, unread and peer-idle assertions |
| Read metadata and post/completion arrive in different orders | Controlled repository regression against the demonstrated cause |
| Duplicate/replayed facts, empty/one-row summaries | Repository regression; shown/read projection tests |
| Host/conversation identifiers reused on another connection | Current-owner diagnostic and focused host attention tests; no inherited support |
| Unavailable shown metadata, pending/local read only | `ShownVersionReadMarksTest`; never classify unavailable as unread |
| Competing prompt/running activity, then peer read | `ConversationAttentionTest`, `ShownVersionReadMarksTest`, paired native ping |

## Error handling

Unready facts keep the original predicate false. Diagnostics use fixed labels, booleans, numeric marks and hashed attribution only. Propagate the original timeout/failure unchanged. No new retry, timeout extension, skip, teardown change or wire contract.

## Testing strategy

Write and execute a controlled failing regression before the repair, then pass it against production logic. Run affected repository/attention tests, lint, assembly, instrumented compilation, Spotless and final `pre-verify.py --gradle` after merging main. The existing native scenario requires real Noise/relay transport, daemon state and foreground read qualification, so remains device-only. Run Linux managed `scripted ping` with both original methods together: 2 executed/passed, zero errors/failures/skips. Preserve raw XML, phone, fixture/control/daemon/relay traces, exit/cleanup receipts, exact revisions and effective input hashes privately in agents `logs/evidence/2467/`. Historical macOS failure stays failed; macOS execution is outside scope. Complete configured acceptance belongs to the dispatcher.

## Open Questions

- Which boundary fails after the post? Resolve through bounded current-owner/metadata/projection/semantics diagnostics and record the controlled regression here.

## Security review

**Verdict:** PASS

- Trust boundary: only current daemon-supplied read and available shown facts may classify attention. Strict final-proof ownership and negotiated capability checks stay unchanged.
- Tokens/credentials: no credential access or storage changes. Diagnostics must exclude tokens, keys, pairing payloads, prompts, message bodies and DTO rendering.
- Files/storage: private evidence stays outside Git in the persistent owner-only evidence directory; retain original manifests and publication discrepancies. No product storage changes.
- Android surface: no component, permission, intent, clipboard, accessibility or visual changes.
- Cryptography: SHA-256 is diagnostic correlation only; existing Noise and TLS remain unchanged.
- Network/I/O: diagnostics issue no requests; preserve deadlines and managed cleanup. A repair may refresh authoritative metadata only through the existing connection-owned request path.
- Logs: SHOULD FIX in implementation: cap samples, always retain a terminal sample, use fixed labels and hashes, never exception text or raw routing strings.
- Concurrency: source diagnostics hold the existing monitor; ownership must bracket sampled repository facts. No new scope; a retired repository cannot establish current confirmation.
- Threat model: dropped, reordered or duplicate relay traffic cannot substitute receipt or missing metadata for read authority. Hostile daemon text is never interpolated. Rooted-device theft and screenshot exposure remain unchanged by this repair.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-22
