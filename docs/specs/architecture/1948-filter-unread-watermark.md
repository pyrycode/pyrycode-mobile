# #1948 — Filter unread watermarks without changing read evidence

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `onInbound`, `recordLatestEntry`, `requestHistory`; both receipt lanes currently raise latest from every durable type.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationReadMarks.kt`: `merge` retains unsigned maxima per fact.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `recordLatestEntry`, `applySnapshot`, `observeHostReadMarks`; atomic conversation-keyed ledger remains the merge owner.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadReadEvidence.kt`: `checkpoint`, `received`, `understoodNonvisualEntry`; filtering unread must never grant sight or bypass unknown, malformed, missing-identity or gap barriers.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.withReadMarks`, `resolve`; shared marks replace local read inference while waiting/running keep precedence.
- `app/src/test/java/de/pyryco/mobile/data/repository/ConversationReadMarksTest.kt`: live receipt, replay, read confirmation and fresh-generation fixtures.
- `app/src/test/java/de/pyryco/mobile/data/repository/ThreadReadClaimsTest.kt`: exact-version, unknown/malformed and receipt-gap regressions.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceAttentionTest.kt`: `withRemoteSource`; real remote-repository attention integration and legacy fallback coverage.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attentionDot_followsARealTurn`; peer currently targets the raw history maximum.
- `docs/knowledge/features/remote-conversation-repository.md`: **Daemon conversation read marks** distinguishes durable receipt from presentation; preserve that separation.
- `docs/knowledge/features/channel-list-screen.md`, `thread-screen-testing-foreground-read-tracking.md`: existing dot semantics and foreground evidence contract.
- `docs/e2e-interactive-stream.md`: **Phone read-mark proof**, **Peer read clears phone attention**; existing rung-4 scripted ping twin covers phone/peer clearing.
- Sibling `pyrycode/docs/protocol-mobile.md`: **Marking a conversation read**, **A history entry**, **Security model**. Read the merged daemon #2954/#2955 revision at `daca56368e20e41e0c90becd4a31b554b4bd5812` because the sibling working checkout still describes the older watermark. `history.displayableType`, `LatestDisplayableEntryID` and their list/mark-read callers confirm the five-type contract; no daemon edits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Design context and screenshot show a dark gradient sidebar, grouped host/channel rows, small body labels and a leading 6dp status dot: hollow Idle, filled unread, green running and yellow waiting. Existing Compose components and Material theme roles keep their geometry, typography and assets; only the facts selecting the dot state change.

## Change

Add one internal type predicate beside `ConversationReadMarks`, excluding exactly `turn_state`, `stall`, `api_retry`, `compacting` and `session_transition`, with all other types (including unknown or malformed payloads) counting. Apply it before raising latest in `RemoteConversationRepository.recordLatestEntry` and before taking the history page maximum in `requestHistory`. List-supplied watermarks continue to merge as authoritative facts. Status-only pages leave latest unavailable or preserve the existing value; no status entries or durable identities are removed from receipt, replay, history rows or read evidence. Existing monotonic merging, host/generation scoping, coroutine ownership, read confirmation and UI precedence need no changes. The live peer-read target becomes the daemon's filtered latest id, explicitly backed by a received non-excluded history entry; phone sight still comes only from foreground checkpoints and all permission assertions remain.

Overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888 and #1900 touch shared files. Their live-test hunks are in other methods; #1725 extracts history I/O while retaining the same receipt fold. These are local merge overlaps, with no required new contract or redesign dependency.

Sizing: one deliverable, four criteria, approximately 500 written lines including this plan/tests, two production files, no new public types/signatures or consumer migrations, and no new error branches. The #1883 attention slice added 24/deleted 8 production lines and added 56 attention-test lines; extra receipt-path/order/barrier probes account for this ticket's larger test estimate. The written plan remains within every sizing boundary.

## Testing strategy

Write regressions first and observe the status-tail failures on unchanged production. Extend repository fixtures to drive actual live/replay and correlated history requests for each excluded type, duplicate receipt, empty/one-entry/status-only pages, overlapping pages in start/middle/end positions, reordered delivery, later non-excluded types including unknown and nonvisual metadata, two hosts/conversations and fresh history after reconnect. Assert original IDs/entries remain and checkpoint barriers still block unknown/malformed/missing/gapped receipt. Add remote-backed attention regressions for confirmed foreground read and peer read, followed by duplicate status delivery and fresh content. Run existing `ConversationAttentionTest`, `HostConversationSourceAttentionTest`, `ConversationReadMarksTest`, `ConversationListProjectionTest`, `ThreadReadClaimsTest` and thread read subscription tests.

No new device-only test: the existing rung-3 method requires real Claude and a real socket/emulator; compile its adjustment and hand its fresh full live-gate execution to the dispatcher. Required method: `de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_attentionDot_followsARealTurn`; dispatcher must report executed/failed/skipped counts and the named pass against the fixed daemon. Run focused scripted `ping` for the existing rung-4 twin. Finish lint, debug assembly, Android-test compile, formatting, merge main, push and the full unit/shared suite plus `scripts/pre-verify.py --gradle`.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/remote-conversation-repository.md`, **Daemon conversation read marks** — distinguish the filtered unread watermark from receipt/presentation evidence and its unknown-entry barriers.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, **Peer read clears phone attention** — record the dispatcher-produced named full live-gate result and executed/failed/skipped counts.

## Security review

**Verdict:** PASS

- [Trust boundaries] Authenticated daemon input enters the existing envelope/history decoders. The new predicate compares only an exact type string, never trusts a payload as sight, and deliberately counts unknown types. IDs stay unsigned and the existing positive-ID/conversation guards remain; regression probes cover misleading payloads and missing identity.
- [Tokens, secrets] No credential creation, storage or access changes; existing Keystore-wrapped pairing and token handling remain. Tests use synthetic content only.
- [Files and storage] Read facts remain ephemeral, per repository generation, outside persistent caches. No new paths, file I/O, decrypted-body persistence or backup surface.
- [Android attack surface] No component, intent, push, permission or WebView changes. The live scenario preserves its permission checks.
- [Cryptography] Existing `Noise_IK_25519_ChaChaPoly_BLAKE2s` authentication/AEAD and nonce lifecycle remain untouched.
- [Network and I/O] No new request, retry or frame allowance. History returns all original entries; daemon list facts remain authoritative. Existing transport limits, TLS and backoff apply.
- [Errors, logs and telemetry] No new failure branch or daemon-text rendering/logging. Existing static read-confirmation lifecycle logs cover the flow; filtering is a pure classification and emits no entry type, payload, content, ID or credential.
- [Concurrency] Both paths reuse the atomic `ConversationListProjection` fold. No coroutine, suspension, mutex or lifetime changes; reconnect creates a fresh ledger and history cannot reintroduce status-only latest facts.
- [Threat model] Relay drop/reorder/replay cannot bypass presentation barriers; monotonic and reconnect probes cover receipt permutations. Hostile daemon unknown/malformed entries retain barriers rather than becoming acknowledged content. Token theft and UI screenshots/accessibility/keyboard exposure use unchanged existing protections; this change adds no secret surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

### 2026-10-08 — Repair the named live phone-read failure

The fixed-daemon live gate timed out awaiting A's phone read. A focused probe identified one unresolved `rate_limited` receipt, with no unidentified receipts or gaps. This existing usage-window state frame was missing from `understoodNonvisualEntry`, so valid receipt before/after the visible reply blocked its foreground checkpoint. Recognize only a successfully decoded `RateLimitedPayloadDto` as nonvisual evidence. Its benign `allowed` clearing edge is understood even though `toReading` returns null; other statuses remain opaque per the protocol. This does not add an unread exclusion: `rate_limited` still advances unread, and receipt alone still cannot create a visible checkpoint.

`ThreadReadClaimsTest.understoodUsageWindowReceiptsExtendPresentationButMalformedWindowsRemainBarriers` fails before the repair and covers benign, warning and future opaque statuses, malformed frames both before and after the reply, and the non-interactive barrier. The live scenario also backs A's assertion target with received history, retains all permission checks, and reports content-free evidence counts on timeout. Run its named focused live gate after repair; the dispatcher still owns the fresh full live acceptance result.

Security review of this revision: PASS. Reuse the existing strict-required DTO decoder and frame limits; do not branch on opaque status strings or log them. Malformed payloads, unknown entry types, missing identities, exact-version presentation and receipt gaps keep their existing barriers. No new storage, request, coroutine scope, cryptographic or Android attack surface. The timeout diagnostic contains only static event names, counts and Boolean facts. The existing read-confirmation logs cover this classification without adding production logging.

Additional files read: `InteractivePayloads.kt` (`RateLimitedPayloadDto`, `toReading`) and sibling `docs/protocol-mobile.md` (**rate_limited**) define the existing clearing-edge and opaque-status contract. Production edits now span three files; no exported declaration or consumer migration is added. Total written work remains below 600 lines and the sizing boundaries still hold. `feature/1283-notice-placement` shares the thread screen but does not overlap this evidence classification; no dependency is required.
