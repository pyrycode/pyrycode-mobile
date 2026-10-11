# #2009 — Attention read proof across phone and peer

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn`, `awaitRowAttention`; preserve the two read stages, row isolation and held permission checks.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `readMarks`, `history`, `markRead`, `awaitReadMark`; daemon facts are separate from phone receipt and presentation.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `checkpoint`, `received`; zero stored gaps does not rule out a numeric receipt hole.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `recordReadEnvelope`, `observeSnapshot`; compare the actual presented version, not an independently reduced row.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot`, `upsertConversation`, `recordLatestEntry`; read/latest facts merge monotonically within a connection.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.withReadMarks`, `resolve`; Waiting and Running take precedence over shared unread.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `updateReadMarks`, `updateAttention`; generation fencing and host-qualified projection.
- `app/src/test/java/de/pyryco/mobile/data/repository/ScriptedPingReadEvidenceTest.kt`, `ThreadReadRuntimeReceiptsTest.kt`: existing daemon-exported receipt controls.
- `docs/knowledge/features/remote-conversation-repository.md`: daemon read marks; #1989 and #2070 establish daemon ownership of omitted receipts, without identifying every historical live sequence.
- `docs/knowledge/features/channel-list-viewmodel-projection.md`, `thread-screen-testing-foreground-read-tracking.md`: attention joins and foreground read qualification.
- `docs/e2e-interactive-stream.md`: Phone read-mark proof and Peer read clears phone attention; existing rung-4 scripted ping twin.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: Marking a conversation read, A history entry and Security model are the wire source of truth.
- Sibling `cmd/pyry/queued_legacy_receipts_test.go`, `legacy_runtime_receipts_test.go`: real-store producers and serialized legacy pages for controlled causal comparison.

## Context

The original #1968 failure reached B after daemon confirmation, but retained stderr is unavailable on this runner. The supplied assertion is evidence of row semantics only, not phone receipt. October 10/11 retained stderr instead fails A: three rows/versions, no unknown or unidentified facts and no stored gap, yet no checkpoint reaches the target. October 11 used Mobile `6629a7a538fce2338a083507d8a24ee8a81d252b` and daemon `d49b8392e00b8de4273998393413594d57f30047`. That daemon contains #3026/#3029 but predates #3167. The queued-send omission is a concrete lead requiring reproduction. No decision record is needed.

## Design

Keep production contracts and visuals unchanged unless controlled evidence identifies a Mobile defect. Add scenario-local, content-free stage evidence separating peer daemon confirmation, phone repository read/latest, phone receipt coverage, host projection and merged row semantics. A diagnostic failure must preserve the original assertion and deadline. Add controlled regression coverage using real daemon-exported pages and the production Mobile projection/attention fold, retaining negative controls for missing/unknown receipts and unseen versions. Compare original and repaired daemon revisions in isolated scratch checkouts; never modify the production daemon. If a new daemon defect is established, reuse or file its owning blocker rather than weakening Mobile evidence. Record the proven causal design under Revisions before implementation of any repair that differs from this contract.

No new public type, dependency, wire field, UI state or event is planned. Estimated total written work: 550 lines, one deliverable, four criteria, no consumer migration and no new rejection branches. Overlap branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 touch other live scenarios; edits remain local to the named attention scenario.

## State and concurrency model

Existing repository generations and app-owned HostConversationSource collectors remain unchanged. Test collectors belong to coroutineScope and cancel on failure; peers close in finally. Diagnostics sample the owning host and never infer read from list presence, history receipt or a successful send. Live and history delivery retain original durable identities.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Completed A on list, then foreground read | Named live scenario; controlled phone checkpoint and confirmed-mark regression |
| B permission held then answered | Existing Waiting, settle, A Idle and B Unread live assertions |
| Peer confirms B while phone remains on list | Named live scenario; controlled remote confirmation and host attention regression |
| Duplicate/overlapping receipt, fresh generation, same conversation ID on another host | Existing ThreadReadRuntimeReceiptsTest and HostConversationSourceAttentionTest |
| Missing/unknown receipt or unseen content version | Controlled regression and existing ThreadReadClaimsTest |

## Error handling

Unknown, malformed, unidentified and numeric-hole barriers stay conservative. Diagnostics do not replace exceptions or retry actions. A daemon confirmation cannot be reported as phone receipt. Failure before B leaves B unresolved.

## Testing strategy

Write and run the causal regression against the original fixture before substituting the repaired fixture. Run focused unit classes for read claims, runtime/queued receipts, read marks, retry ownership and host attention. Run the existing scripted ping twin after repair. The real-device scenario needs actual Noise connections, background dispatchers and daemon history; Robolectric cannot provide those. The ticket assigns fresh full rung-3 proof to the dispatcher; do not claim a focused pass as acceptance. Run focused live only if needed for causal diagnosis. Run lint, assembly, Android test compilation, formatting and final pre-verification. Retain counted results and Mobile/daemon revisions.

## Open Questions

- Does #3167 explain the retained A condition under the original daemon? Resolve with controlled real-store pages, not aggregate counts.
- Does B have a separate receipt/watermark or projection failure? Capture daemon and phone facts separately; A-only failure/pass cannot settle this.

## Documentation handoff

Pending for documentation stage: update `docs/e2e-interactive-stream.md`, Peer read clears phone attention (#1883), with diagnosed cause, repair, actual full gate counts/revisions and named method result. Preserve phone-derived presentation versus history-backed peer read distinctions.

## Security review

**Verdict:** PASS

- [Trust boundaries] Use existing strict wire DTOs and exact-version projection; unknown or malformed receipts never become sight. Daemon list facts remain assertion targets only.
- [Tokens] No credential access or new storage. The live gate owns credential lookup; diagnostics never include pairing records or tokens.
- [Files/storage] Synthetic exported fixtures and counted evidence only under app/src; scratch output outside the worktree. No daemon/user payloads, paths or identities in retained diagnostics.
- [Android attack surface] No exported component, intent, provider or rendering change. Existing row content descriptions remain the observable contract.
- [Cryptography] Existing Noise IK and transport remain unchanged; no keys or decrypted payload bytes enter evidence.
- [Network/I/O] Existing frame limits, timeouts and cancellation apply. No new resend, retry or deadline extension.
- [Errors/logs] Counts, static stage labels and booleans only; do not log authored text, exception payloads or credentials.
- [Concurrency] All diagnostic child work stays in the scenario scope, peers close in finally, and observations retain host/generation identity.
- [Threat model] Delayed/dropped relay frames must remain failures, never inferred reads. Rooted-device theft and UI leakage receive no new surface; existing Keystore storage and text rendering remain. Hostile frames retain strict decode and evidence barriers.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-11
