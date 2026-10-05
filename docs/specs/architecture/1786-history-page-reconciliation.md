# History page reconciliation (#1786)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeHistoryRows`, `mergeCachedRows`, `ThreadRowAnchors`, segment folds and hint fills define reconciliation.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `mergeHistoryPage`, `ProjectionState`, `endedTurns` and `remove` own the atomic history/live fold.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeMessages` retains a fixed connection merge base and rebases only at disconnect.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `AssistantSegment` records delta sequence and text length.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryReconciliationTest.kt`: new decoded-page permutations, mixed live/history order, legacy holes, collisions and real file-cache restore/reconnect.
- `app/src/test/java/de/pyryco/mobile/data/repository/AssistantSegmentTest.kt`: split-page, live echo, legacy and ended-turn regressions.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt`: decoded entries, row identities and attachment hints.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt`: real wrapper restore/reconnect and deliberate-removal assertions.
- `app/src/test/java/de/pyryco/mobile/data/repository/BackgroundTaskLifecycleTest.kt`: invisible evidence must retain neighbour positions and not split text.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: preserve capability gates, malformed-entry isolation, lifecycle hints and atomic folds.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: live echoes can split turns differently from daemon pages; ended turns need the post-merge settle pass.
- `docs/knowledge/features/caching-conversation-repository.md`: cache lookup must remain indexed; moving the base on every emission resurrects deliberately removed echoes.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Conversation history (v2), Joining a page to the live stream, and Security model are the wire SSOT.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read node `16:8` and its screenshot. The existing thread uses left assistant/right user bubbles, body-medium text, small timestamp/copy metadata and rule/label/rule session delimiters over the dark blue backdrop. Existing Material theme roles and all UI geometry/assets remain unchanged; reconciliation supplies the same renderer with corrected content and order.

## Context

An open or reconnect already asks for the newest page. Treating every incoming row as older moves newer content to the front, while the lowest-sequence cutoff loses missing middle/suffix text. This ticket repairs that one reconciliation contract; durable coverage, gap demand, persistence ordering and the real-Claude extension belong to #1787. No decision record is needed.

The sketch and final plan forecast approximately 1100 total written lines including replaced code, tests and this plan, no exported types, two production consumers and four criteria. This remains below all builder limits. In-flight #1655 touches `ThreadProjection` queue reservation and adds a separate state field; our ordering field and history fold are local/additive and do not depend on it. #1782 is already present; retain its lifecycle positioning.

## Design

Use one indexed ordinary-row reconciliation for history and cache. Expand assistant segments into temporary single-delta rows using their recorded lengths, clamped to content, then join adjacent same-turn text again. Ordinary identities retain the existing renderer key; assistant identity is `(turnId, seq)`, independent of the segment opener. Held content wins overlap. Only missing deltas enter the merge, including sequences before, between and after held sequences. Rebuilding keys uses segment openers and checks collisions against ordinary message keys.

Place incoming runs in slots between held rows using preceding/following shared identities and per-turn sequence neighbours. Slots advance through the incoming lane without rearranging held rows; a held user echo keeps its arrival position even when the daemon placed its twin elsewhere. Without shared neighbours, use ordering evidence to choose an insertion slot rather than sort the result. Cache-only offers retain their preceding cached neighbour. Lifecycle markers keep the existing separate neighbour-position pass and hint fill, remaining transparent to visible segment joining.

`ProjectionState` retains a connection-local map from logical merge identity to daemon log id. Derive these keys with the existing per-entry reducer (assistant deltas contribute sequence keys), respecting negotiated gates and malformed-entry isolation. Merge this order evidence and rows in one `state.update` and use daemon ids for disjoint history pages, including equal or inverted timestamps. Live frames never receive or compare ring ids with these log ids. Cache and live-only rows fall back to timestamps solely to select insertion slots; held rows are never timestamp-sorted. This metadata is neither coverage nor persisted schema, and `remove` clears it.

Legacy whole-turn rows have no sequence record. Match only same-turn text demonstrably contained in their content, in sequence order; suppress matched deltas, retaining distinct text that the legacy row does not contain. Keep legacy layout and text. Where a distinct delta opener conflicts with the legacy bare-turn key, use the explicit sequence-zero segment key and keep renderer identities unique. Do not let a whole-turn row blindly delete every segmented row of its turn.

## State and concurrency model

All reconciliation is pure and synchronous. No scope, dispatcher, job or flow is added. Projection order evidence lives in the same CAS state as rows; retries read current live rows and preserve echo queues. Keep `recordEnded` and the second `settleEndedTurns` pass unchanged. The cache wrapper remains a cold collector-owned flow with its existing connection-boundary merge base and suppression contract; lifecycle socket shutdown is unchanged.

## Error handling

Keep the existing per-entry decoding catches and capability gates. Missing neighbours fall back to insertion evidence, not an exception. Clamp delta text slicing. Malformed or hostile key collisions retain the held row and cannot produce duplicate renderer keys. No cursor, entry, delta, attachment hint or daemon text is logged. Existing I/O outcomes and UI errors remain unchanged.

## Testing strategy

Add decoded-page tests through `ThreadProjection.mergeHistoryPage` for disjoint older/newer/middle pages, newest-after-older, daemon id ordering with equal/inverted timestamps, overlap/repeats, sparse assistant sequences, tool/user separators and both page arrival orders. Assert content, sequence uniqueness, message-key uniqueness and settled ended turns. Add reducer tests for partial legacy matches, distinct text and collisions.

Exercise `CachingConversationRepository.observeMessages` using actual reduced page rows for middle/suffix sequence gaps, attachment hints/offers, disconnect/reconnect and deliberate removal. Run existing `HistoryPageReducerTest`, `AssistantSegmentTest`, `ThreadProjectionTest`, `CachingConversationRepositoryTest`, `BackgroundTaskLifecycleTest` and affected remote repository history tests. Run lint, assembleDebug, spotlessApply and forced spotlessCheck. No visual geometry or interaction changes, shared/device test changes or new live scenario: AC explicitly delegates rung-3 extension to #1787.

## Open Questions

None; legacy matching is deliberately limited to observable same-turn text rather than assuming all sequences are present.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, History pages fold into the same thread: older/newer/middle insertion and assistant sequence overlap.
- `docs/knowledge/features/caching-conversation-repository.md`, How the restore merges with live rows: order, middle insertion, assistant overlap and legacy matching. Retain the connection-boundary merge-base and deliberate-removal contract.

## Security review

**Verdict:** PASS

- Trust boundaries: `reduceHistoryPage` remains the sole existing typed row decode boundary, including capability gates and per-entry rejection. Temporary delta splitting uses clamped lengths; malformed content cannot throw substring bounds errors.
- Tokens/secrets: no credentials enter the merge keys or new metadata; no credential storage/lifecycle changes.
- Files/storage: no path, filename, disk format or I/O changes. Attachment hints remain inert display data. Existing app-private cache storage is outside this reconciliation change; persistence ordering is #1787.
- Android attack surface: no component, intent, provider or WebView changes; existing row renderers consume the same typed values.
- Cryptography: transport and vendored Noise handshake/key/nonce handling remain unchanged.
- Network/I/O: existing envelope/page bounds and backoff remain; only decoded logical identities and numeric log ids are retained, not raw payloads. Indexed lookups avoid a cache-by-live nested scan.
- Errors/logs: helpers remain silent; no cursor, message, identifier, payload or attachment hint reaches logs/errors/telemetry.
- Concurrency: ordering evidence and rows share `ProjectionState.update`; no independent read/write pair, coroutine or new lock. Keep the ended-turn post-fold settle pass.
- Threat model: malicious relay delay cannot rearrange held live rows and remains limited by Noise authentication. Hostile decoded frames face identity collision checks and bounded slicing. Rooted-phone credential extraction and screenshot/accessibility/keyboard leakage are unchanged, outside this data-only fix and owned by existing key storage and UI policies.
- Rework audit for PR #1812: MUST FIX — different assistant turns can share a segment renderer key, so checking only ordinary incoming keys lets cleanup evict held text. Admission must reserve held delta keys for their logical turn identities, including the explicit legacy sequence-zero alias. Contextual order tracking must consume only successfully folded rows, keep malformed entries isolated and preserve capability gates. These changes introduce no payload logging, persisted metadata, I/O or new concurrency boundary; the revised design passes this audit. Second rework audit: splitting a held segment can itself create a collision, when the rebuilt suffix's key equals another held turn's opener or an ordinary message id. Key cleanup therefore never drops a row whose logical identity differs; it gives the later claimant a `~n` suffix, so held text survives and renderer keys stay unique. A repeated identity is still one row.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-05

## Revisions

### 2026-10-05 — compatibility checks

- Legacy text fully accounted for by known deltas recovers a sequence record, so novel middle text stays between its matching fragments. A focused legacy `bd` plus incoming `b`, `c`, tool, `d` test exposed the need for this. Text already fully represented by held segments leaves their layout intact. Partially matching legacy text replaces only demonstrated matches, and sequence aliases retain neighbours for distinct prefix/suffix text. A legacy row arriving over a suffix without the opener uses the incoming lane's earlier position.
- Disjoint incoming runs use their earliest timestamp when no logical/log anchor exists, preserving cache-only leading offers even when their individual clocks differ. Daemon ids bound placement among history rows; timestamps can locate held live rows within those bounds.
- Fully overlapping rows return the hinted receiver without rebuilding its text; legacy matching indexes only turns that actually have a legacy row. Pure page decoding is independent of held state and runs once before the atomic history/live merge.
- Seam joining builds each text run once with a string builder, avoiding repeated full-prefix concatenation after splitting segments into delta atoms. Ordinary key collisions cannot evict held assistant text.
- Existing prepend-only assertions now expect a newer boundary/page after older rows, a middle message between shared anchors and a trailing row after its shared predecessor. File-cache reconnect assertions compare persisted content, order, sequences and attachment hints, since transient tool result detail is intentionally omitted on disk.

### 2026-10-05 — verifier rework on PR #1812

- Restore cache-specific leading-run placement before live-only rows when a following cached neighbour survives. The verifier's local and peer queued-delivery reopen tests exposed that placing directly before that neighbour moved older history below intervening tools. Keep the wrapper's connection merge base and suppression/removal contract unchanged; per-turn sequence constraints still bound assistant placement.
- Reject incoming assistant atoms whose renderer key belongs to a different held logical turn, before seam joining or key cleanup. The only legacy opener exception may use its explicit sequence-zero alias if that alias is unreserved. Production-projection and cache repeats must preserve all held text and unique keys.
- Derive row order during the contextual page reduction rather than singleton reductions. Stateful compaction dividers receive their falling edge's daemon id; filling one in place transfers that order to its replacement identity. Track each assistant sequence's first producing entry without overwriting prior positions. Page decode remains pure, capability-gated and per-entry tolerant; rows and metadata still enter the same atomic projection update.
- Add decoded-page tests for failed dividers at equal/inverted clocks, both page orders and repeats, plus a filled divider whose later boundary changes its timestamp. Retain existing cache delivery/removal assertions and add file-cache reopen coverage with reduced page rows and inverted clocks. The revised forecast remains under 1600 written lines, with one new internal reduction result and the same two production merge consumers.

### 2026-10-05 — second verifier rework on PR #1812 (finished by hand)

- Reconstructed held-key collisions. Key cleanup assigns keys in priority order: ordinary message ids, then each turn's opening segment under its bare turn key, then other segments under `segmentKey`. A segment whose key another logical identity already holds keeps its text under the first free `<key>~n` key instead of being dropped. A repeated `(turn, sequence)` or a repeated ordinary id is still one row. The next merge splits rows back into single-delta atoms with natural keys, so the suffix key is never compared with daemon or live ids.
- Daemon-order bounds. When held rows with known daemon positions surround an incoming row's log id, the chosen slot is clamped inside them, after the shared-neighbour, sequence and timestamp choice. Per-turn sequence bounds still apply last. A reused message id or a malformed entry can no longer pull a row to the wrong side of a known position. Cache restores carry no log ids and are unchanged.
- Live divider fill. `foldCompaction` now updates rows and history order in one state update. When a boundary fills the pending divider in place, the divider's daemon position moves from the old identity to the new one, so later pages still place rows after it.
- Permanent tests in `HistoryReconciliationTest`: held-split collisions with another turn's opener and with an ordinary id, across history repeats, cache merges, file-cache persistence and reopen; reused id and malformed overlap with repeats; a live-filled divider with repeats. All three failed on the previous head, and the verifier's five probes pass.
