# Queued follow-up continuity (#2078)

## Files read

- `ThreadProjection.kt`: `settleQueuedEchoes`, `applyAssistantDelta`, `OwnEchoQueue`, `appendLiveMessage`, `mergeHistoryPage` own echo placement and assistant anchors.
- `HistoryPageReducer.kt`: `withAssistantDelta`, `mergeRows`, `withJoinedSegments` preserve legitimate separators and deduplicate sequence overlap.
- `RemoteConversationRepository.kt`: `routeInbound` supplies `TurnPhaseProjection.isOpen` to queued-echo classification.
- `TurnPhaseProjection.kt`: `apply` tracks phase frames only; deltas do not establish an open phase.
- `MessageCommands.kt`: `sendMessage` appends and records the phone-owned echo before sending.
- `ThreadProjectionTest.kt`, `RemoteConversationRepositoryTest.kt`, `AssistantSegmentTest.kt`: FIFO, idle, delivery, drop and overlap controls.
- `DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_queuedPromptDoesNotSplitHeldReply` retains the isolated reproduction from merged #1985.
- `scripts/e2e-emulator.sh`, `scripts/e2e-fixtures/stream.jsonl`: manual queued-echo selection and checkpoint release.
- `docs/knowledge/features/queued-backlog.md`: idle classification is sticky; modern delivery is authoritative; bounded legacy/no-echo limits remain.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: sequence identity alone cannot prevent an echo from splitting an anchor.
- `docs/e2e-interactive-stream.md`: existing rung-4 checkpoint harness and rung-3 ownership.
- Sibling `pyrycode/docs/protocol-mobile.md`: Queue (v2) and Security model remain wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot. The thread has opposing user/assistant bubbles, body-medium text, session rule/label/rule dividers, a title-large top bar and composer/status footer using Material scheme roles. Preserve this presentation; the change is confined to repository state and its existing device reproduction.

## Context

An own follow-up queued between assistant deltas must not become a separator in the reply it waits behind. The live anchor already passes over parked and awaiting-push echoes, but parking depends on a phase-only open-turn classification. A retained unfinished assistant segment is also evidence of an open reply when the phase frame is absent or idle. Confirm that diagnosis with red event-by-event repository probes and the retained device reproduction.

## Design

Keep queued ownership and placement inside `ThreadProjection`. At first queue classification, combine the supplied open phase with retained unfinished assistant-segment evidence from the same projection generation. Do not reclassify an echo already queued while idle. Continue using `parked + awaitingPush` for the live delta anchor, FIFO presentation, delivery confirmation and suppression. No new exported type, consumer signature, dependency, wire field or UI state is needed.

History probes exercise queued/suppressed copies, missing suffixes, overlaps and established delivered positions. Keep the existing reducer contract unless a probe demonstrates a merge defect; any necessary design change is recorded under Revisions before committing it. Never merge through delivered user rows, tools or session boundaries.

No in-flight numeric feature branch overlaps the proposed production files. Forecast: about 500 written lines including plan and probes, no new exported types, no consumer migration, three acceptance criteria and no new reject branches. This is one deliverable, legacy reply continuity.

## State and concurrency model

Use the existing connection-scoped projection `StateFlow` and repository inbound collector. Read retained segment evidence inside `state.update` so CAS retries reclassify from current rows. No additional jobs or dispatchers. The existing connection driver closes the socket on background; cache/history reconciliation retains text while a fresh connection reconstructs transient queue state.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Two deltas, own send, queued snapshot, third delta, end without an open phase frame | `queueState_heldReplyWithoutOpenPhase_keepsItsAnchor` checks every emitted checkpoint and final id/text/streaming state. |
| Duplicate/reordered queue snapshot, two busy echoes and idle-first echo | `busyQueuedEchoes_keepFifoAndStickyIdlePlacement` plus existing modern FIFO/idle controls. |
| Queue removal before delivery and duplicate confirmation | Existing `assertParkedEchoConfirmedMidReply` and modern delivery sequences; new probe checks held active text. |
| Confirmed drop followed by stale snapshot/replay | Existing drop controls and new queued-continuity probe; ownership is spent before placement. |
| Tool, delivered user row or session boundary during the same turn | Existing `AssistantSegmentTest` and new real-separator control keep separate segments. |
| History overlaps held deltas with pending or suppressed echo on either side | New `queuedEchoHistoryOverlap_preservesHeldReply` probes prefix/middle/suffix, empty/one-row pages and repeated merges. |
| Reconnect restoration and settled delivered placement | Existing assistant/cache overlap tests and new cache overlap probe. |
| Conversation/id reuse and fresh connection | Existing ownership/isolation and remove controls; retained ended reply cannot classify a new idle echo as busy. |

## Error handling

Retain decode-or-drop and sequence/key guards. Queue ids and message ids are equality-only, scoped to the conversation. No new I/O or error outcome. Preserve existing static diagnostic events; never log message bodies, routing ids, payloads or decrypted bytes.

## Testing strategy

Write the repository reproduction/probes first and observe red before repair. Run `ThreadProjectionTest`, `RemoteConversationRepositoryTest`, `AssistantSegmentTest` and relevant cache/history identity classes. Every event advances collection before asserting to avoid hiding transient splits through StateFlow conflation.

Remove the existing device reproduction's ignore. It needs the real transport/background-dispatcher and host fixture checkpoints, which Robolectric cannot supply. Run isolated `DETERMINISTIC=1 SCENARIO=stream-queued-echo bash scripts/e2e-emulator.sh` and the existing `python3 scripts/android-test-gate.py scripted stream`; inspect fresh named XML and retain counts/evidence in `/tmp/builder-2078/`. This repairs a legacy repository path and enables its existing rung-4 reproduction; no new operator feature or real-Claude scenario is introduced. Existing live coverage stays dispatcher-owned.

Run lint, assembleDebug, Android-test compilation, Spotless and the final merged-main pre-verify gate. No whole-suite builder run.

## Open Questions

- Does the retained reproduction lack open phase evidence, or expose a second merge/anchor cause? Resolve with the red probe and isolated harness; record any design adjustment under Revisions.
- Do queue/suppression history probes expose a reducer change? Keep contracts narrow and preserve legacy/no-echo limits.

## Security review

**Verdict:** PASS

- [Trust boundaries] Queue frames remain decoded by `QueueProjection`; `settleQueuedEchoes` intersects with conversation-local minted ownership. Untrusted text is never consulted to classify or identify an echo.
- [Network-authored identity] MUST FIX in the previous design: an assistant turn id can equal a parked or suppressed echo's renderer id, including an allocated alias, and cause duplicate Compose message keys when the echo is restored after allocation. Retain pending echoes in the reconciliation list and allocate all message keys together through `withUniqueMessageKeys`, preserving held ordinary identity and renderer ownership before newcomer allocation. Parked, suppressed/revealed and alias/fallback collisions have explicit probes; pending ids remain conversation-local and equality-only.
- [Tokens] No credential generation, storage, revocation or exposure changes; this repair uses only existing in-memory row state.
- [Files/storage] No storage format, path, backup or write changes. Test evidence goes to a fixed builder scratch directory.
- [Android surface] No component, intent, URI, permission or WebView changes; existing Compose renders text.
- [Cryptography] No changes to Noise, keys, counters or cryptographic comparisons.
- [Network/I/O] Existing decoding, frame limits, timeouts and reconnect backoff are unchanged. Duplicate frames must not add text or consume another conversation's entry; probes assert this.
- [Errors/logs] No new logging of ids or content; existing content-free lifecycle diagnostics remain. No telemetry change.
- [Concurrency] Read pending queue intent, reserved placement and held renderer ownership inside the same `ProjectionState` merge CAS generation. No suspension or nested write in that update, and no new coroutine lifetime.
- [Threat model] Delayed/replayed daemon frames are handled by existing sequence and entry ledgers with probes for their ordering. Malicious relay confidentiality, rooted-device token theft and UI screenshot/accessibility leakage stay with unchanged transport, Keystore and UI boundaries. Hostile plaintext remains inert display data and gains no authority from queue placement.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — captured banner boundary and queue-aware merge

The isolated reproduction showed `Responding`, a correctly parked own echo, and the held raw segment still streaming before its third delta. A banner after the echo was the actual last-row anchor. The phase-only hypothesis above was disproved for this reproduction; the experimental raw-segment fallback was removed. `settleQueuedEchoes` and `RemoteConversationRepository` keep their existing phase and idle-first contracts.

The revised design treats retained `BannerLevel.Info` rows, which compose to nothing, like invisible background lifecycle evidence in `withAssistantDelta`, `withOnlyLastRowStreaming` and `withJoinedSegments`. Visible notices and warnings remain separators. No banner text, id or timing heuristic participates in this rule.

`pendingEchoOnEitherSideOfHistoryMerge_isNotASeparator` also failed with an extra suffix segment when a stored send echo overlapped held pending placement. Inside the existing merge CAS, exclude parked/awaiting-push own echo copies from both inputs, join the remaining rows, then restore the exact held user objects after them. A page's first modern delivery exempts its echo from this exclusion, preserving authoritative delivery placement. Idle echoes and settled deliveries stay ordinary boundaries. Incoming ordinary rows colliding with a retained pending echo's identity are excluded regardless of the incoming role, preserving the held ownership and renderer key. No reducer parameter or consumer API changes are needed.

This replaces the original concurrency proposal: queue intent and held pending rows are read from the same merge CAS generation; page decoding remains pure and outside it. No new job, dispatcher or suspension. The security review remains PASS: classification uses a closed banner enum and conversation-local pending ids, never daemon text; temporary diagnostic instrumentation is removed.

Coverage updates resolve both Open Questions. `queueState_heldReplyWithInfoBanner_keepsItsAnchor` covers banner-before/after-queue arrival, duplicate snapshots/deltas and turn end. `infoBanners_preserveLiveHistoryAndReconnectSegments` covers live streaming, page reduction, both history/cache merge directions and deduplicated invisible evidence. `pendingEchoOnEitherSideOfHistoryMerge_isNotASeparator` covers queued and suppressed copies on both sides and repeated merge, then a live suffix. `queuedEchoHistoryOverlap_preservesHeldReply` covers empty, one-row, prefix, middle and suffix pages. Existing modern delivery, drop, conversation isolation, cache overlap and no-phase idle tests remain controls; no additional cache wrapper state is needed. The no-open-phase row in the original transition table is superseded by the responding/banner row above.

Revised size: approximately 450 written lines including plan, tests and device checkpoints; two production files, no new exported types or consumer migration, three acceptance criteria and no new reject branches. The design remains one legacy continuity deliverable.

### 2026-10-11 — verifier rework: retain placement and key ownership

Findings 1 and 2 exposed that removing all pending echoes before reconciliation and appending them afterwards surrenders both a reserved legacy slot and its renderer key. Supersede that removal/restoration design: retain pending user objects in the receiving list, filter their incoming ordinary copies, and pass conversation-local pending ids into `mergeUnsignedHistoryRows` and its private segment join. The join passes over only those owned user rows, as live folding already does, while preserving the exact rows in the result. Reserved echoes keep their store slot before their answering turn; unreserved parked rows retain queued FIFO presentation through the existing snapshot fold. First modern delivery still uses the existing provisional placement exception and remains a genuine boundary.

The complete result, including hidden awaiting-push echoes, goes through the existing `withUniqueMessageKeys` allocator. No allocation bypass or second restoration pass remains. An optional internal reducer parameter defaults to empty, leaving cache and other history consumers unchanged; CodeGraph found seven callers of `mergeUnsignedHistoryRows`, four of private `mergeRows` and one segment-join caller, with only the projection requiring new context. No exported type or wire/UI API changes.

Extend both drain-first and push-first instances of `assertParkedEchoConfirmedMidReply` with repeated nonempty history merges before confirmation and another merge after confirmation, asserting original echo, reply identity/text/streaming and final settlement at every event. `parkedEcho_historyRendererCollisions_preserveHeldOwnership` and `suppressedEcho_historyRendererCollisions_preserveHeldOwnershipOnReveal` probe canonical and aliased held keys, an occupied assistant fallback key, replay and reveal. Finding 3 updates `nonvisualBannerTailRetainsPresentedStreamingContentClaims` to expect streaming through Info metadata, retain checkpoint `72uL`, and verify settlement on turn end. These supersede the corresponding transition-table controls; all prior separator, idle, modern-delivery, drop and overlap controls remain.

Security review rerun: the network-authored identity/crash finding is addressed by complete-result key allocation with held ownership priority. Reservation and join context are read inside one CAS. No additional data boundary, coroutine, persistence, log, credential, Android surface or cryptographic change arises; the other reviewed categories remain unchanged. Verdict PASS with this mitigation implemented and tested; elevated risk remains appropriate.

Forecast after rework: approximately 650 written lines total, two production files, no new exported types, one consumer call updated, three acceptance criteria and no new reject branches. No in-flight numeric feature branch overlaps the production files.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/banner-notice-row.md` and `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: record invisible Info evidence and assistant continuity.
- `docs/knowledge/features/queued-backlog.md`, "Queued display, metadata and history" and "Legacy and no-echo limits": record queue-aware reconciliation with preserved reservation and renderer ownership, retaining FIFO, idle-first and modern delivery contracts.
- `docs/e2e-interactive-stream.md`, deterministic verification guidance: record the enabled isolated queued-echo reproduction and fresh counted evidence.
