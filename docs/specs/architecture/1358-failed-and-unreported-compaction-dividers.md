# #1358 Mark failed and unreported compactions in the thread

## Files read

- `data/network/InteractivePayloads.kt`: `CompactingPayloadDto` (decodes only `conversation_id`, `active`), `CompactionBoundaryPayloadDto` and its `toRow`. The two outcome fields are added here.
- `data/repository/ConversationRepository.kt`: `ThreadItem.CompactionBoundary`, the divider row and its `occurredAt` identity. Gains `failed`.
- `data/repository/ThreadProjection.kt`: `applyCompactionBoundary`, `appendCompactionBoundary`, `remove`. The live lane's fold and its per-conversation compaction state go here.
- `data/repository/RemoteConversationRepository.kt`: the `TYPE_COMPACTING` arm of `onInbound`, which today feeds only `CompactingProjection.apply`.
- `data/repository/CompactingProjection.kt`: the status indicator. Untouched; AC 3 requires its tests unchanged, so its `apply(envelope)` signature stays.
- `data/repository/HistoryPageReducer.kt`: `reduceHistoryPage`, the `TYPE_COMPACTION_BOUNDARY` arm of `withHistoryEntry`, `holdsCompactionBoundary`, `mergeHistoryRows`, `joinIdentity`. The shared fold lives here so both lanes run one copy.
- `data/cache/FileConversationCache.kt`: `CachedCompaction`, `toRecord`, `toDomain`. Gains a defaulted `failed`.
- `ui/conversations/components/SessionBoundaryDelimiter.kt`: `compactionBoundaryLabel`, which gains desktop's failed branch.
- `androidTest/.../e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_reconnect_slashCommandsAndCompactStillWork`.
- Desktop `src/renderer/src/store/threadTimeline.ts`, the `compacting` and `compactionBoundary` arms of `reduceTimelineContent`, and `compactionBoundaryTitle` in `compactionBoundaryViewModel.ts`: the behaviour copied.
- pyrycode `docs/protocol-mobile.md` § `compacting` and § `compaction_boundary`: the wire contract, cited not restated.
- `docs/knowledge/features/session-boundary-delimiter.md` § CompactionBoundaryDivider (#874): the divider's identity is `occurredAt`, read by `ThreadRow.listKey`, `holdsCompactionBoundary` and the cache's identity check; a duplicate crashes the `LazyColumn`. That lesson drives every guard below.

In-flight overlaps, all additive and in other blocks: #1355 (`ThreadProjection.kt` KDoc), #1354 (`ConversationRepository.kt`, `FileConversationCache.kt` history position), #1346 and #1340 (`InteractivePayloads.kt` turn-end cost, the e2e test file).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The divider is the `Session reset` rule / label / rule row (`119:3843`), already drawn by `CompactionBoundaryDivider` through `RuleLabelRow` with its existing tokens. The failed state reuses it unchanged with the label "Compaction failed"; no new component, token or asset.

## Context

Mobile draws a compaction divider only from `compaction_boundary`. A failed compaction, or one that ends without a boundary frame, leaves nothing in the thread, while desktop draws one from the `compacting` falling edge and lets the later boundary fill it in. This ticket copies desktop's fold onto both mobile lanes. No decision record is needed: it is a port of desktop's settled behaviour.

## Design

**Wire decode.** `CompactingPayloadDto` gains `@SerialName("compact_result") val compactResult: String = ""` and `@SerialName("compact_error") val compactError: String = ""`. A new `internal fun CompactingPayloadDto.failed(): Boolean` returns `compactResult == "failed" || compactError.isNotEmpty()`. Neither string leaves the DTO: callers hold only the boolean.

**Row.** `ThreadItem.CompactionBoundary` gains a trailing `val failed: Boolean = false`. Defaulted and last, so every existing constructor call compiles unchanged. A divider drawn from a falling edge is `CompactionBoundary(preTokens = null, postTokens = null, manual = false, occurredAt = <edge ts>, failed = <failed()>)`.

**Shared fold** (in `HistoryPageReducer.kt`, beside the other shared folds):

- `internal data class CompactionFold(val compacting: Boolean = false, val pending: Instant? = null)`: whether a compaction is open, and the `occurredAt` of the divider a later boundary would fill in.
- `internal fun List<ThreadItem>.withCompactingEdge(fold: CompactionFold, active: Boolean, failed: Boolean, occurredAt: Instant): Pair<List<ThreadItem>, CompactionFold>`:
  - `active == fold.compacting`: no change (desktop's repeated-edge guard; a falling edge with no rising edge before it draws nothing).
  - rising edge: rows unchanged, fold becomes `compacting = true, pending = null` (forgets any pending divider).
  - falling edge: append the edge divider unless `holdsCompactionBoundary` already holds its `ts`; fold becomes `compacting = false, pending = occurredAt` when not failed, else `null`.
- `internal fun List<ThreadItem>.withCompactionBoundary(fold: CompactionFold, row: CompactionBoundary): Pair<List<ThreadItem>, CompactionFold>`; the fold always leaves with `pending = null`, `compacting` unchanged:
  - the pending divider is found by its `occurredAt`; if found it is replaced **in place** by `row`, so the row takes the boundary frame's `ts` (and its counts and manual flag) at the edge divider's position;
  - if another divider already holds `row`'s `ts` (a history page that raced the live lane and brought this compaction's replaced row first), the pending divider is removed instead, since both describe the same compaction, and no second row with that key is written;
  - with no pending divider, the existing behaviour: append unless held.

**Live lane.** `ThreadProjection` gains `private val compactionFolds = MutableStateFlow<Map<String, CompactionFold>>(emptyMap())` and `fun applyCompacting(envelope: Envelope)`, which decodes `CompactingPayloadDto` and the envelope `ts` (a malformed one drops the frame for the thread, as `decodeCompactionBoundary` does) and runs `withCompactingEdge`. `applyCompactionBoundary` runs `withCompactionBoundary` instead of the plain append; `appendCompactionBoundary` is replaced by one private `foldCompaction(conversationId, step)` that reads the conversation's fold, applies `step` inside the thread's `MutableStateFlow.update`, writes nothing when the rows are unchanged, then stores the next fold. `remove` drops the conversation's fold with its thread.

`ThreadProjection` decoding `compacting` itself departs from its class KDoc ("a frame that also feeds anything else is decoded by the repository"), because AC 3 pins `CompactingProjection.apply(envelope)` unchanged; the method KDoc says so. `RemoteConversationRepository`'s `TYPE_COMPACTING` arm calls `threadProjection.applyCompacting(envelope)` after `compactingProjection.apply(envelope)`, behind the same `interactive` gate.

**History lane.** `reduceHistoryPage` keeps a local `CompactionFold` across its oldest-first fold. A stored `compacting` or `compaction_boundary` entry, behind the `interactive` gate, goes through a private `withCompactionEntry(entry, fold)` that decodes it with the entry's `ts` and runs the same two folds; a malformed entry costs only itself. The `TYPE_COMPACTION_BOUNDARY` arm moves out of `withHistoryEntry` into this path. The fold has no reference to `CompactingProjection`, so a stored edge still cannot restart the status indicator; the "not reducible" paragraph of `reduceHistoryPage`'s KDoc is updated to say a stored `compacting` now draws a divider only.

Because live and history run the same fold over the same frames with the same `ts` values, each divider has one identity on both lanes: an edge divider's edge `ts`, or a replaced divider's boundary `ts`. `mergeHistoryRows` and `joinIdentity` join on that `occurredAt` already and do not change.

**Cache.** `CachedCompaction` gains `val failed: Boolean = false`; `toRecord` writes it, `toDomain` reads it. A document written before this change has no key and reads as not failed.

**Label.** `compactionBoundaryLabel` returns "Compaction failed" when `item.failed`, else today's text. An unreported divider (no counts, not manual) already reads "Conversation compacted".

## State and concurrency model

`compactionFolds` has one writer, the repository's single inbound collector (the two `apply*` calls), plus `remove`. The thread itself has several writers (the collector, `sendMessage`, history merges), so the fold's rows step runs inside `threadByConversation.update` and may retry; the next fold is a pure function of the previous fold and the frame, never of the thread, so a retry yields the same fold and storing it after the update is safe. The pending divider is located by `occurredAt` at fold time, not by index, so a history merge that prepends rows in between cannot misdirect the replacement. Connection-scoped and in-memory like `endedTurns`; a fresh connection starts with no compaction open and no pending divider, as desktop does after `reset`.

The history reduction's fold is local to one `reduceHistoryPage` call. Known seams, accepted as desktop accepts them: a page cut between a rising and falling edge draws no edge divider on that page; a page cut between a falling edge and its boundary, or a restart between them, leaves the edge divider unreported beside the boundary's own row. Each yields distinct keys, never a duplicate.

## Error handling

- Malformed `compacting` payload or `ts`: the thread fold drops the frame silently (the status indicator still decodes it independently, as today).
- Malformed stored entry: costs only that entry, through the reducer's existing `IllegalArgumentException` drop.
- An unrecognised `compact_result` token is not failure; only `failed` or a non-empty `compact_error` is.
- Duplicate-key safety: every write of a divider checks `holdsCompactionBoundary` first, and the in-place replacement removes rather than duplicates when the boundary's `ts` is already held. The cache's existing duplicate-identity `require` is unchanged and stays the backstop.

Nothing logs; no new log line is needed since no new failure path is classified.

## Testing strategy

Unit tests, `app/src/test/`:

- `ThreadProjectionTest` (AC 1): true → false failed by `compact_result` adds one "failed" divider at the edge `ts`; true → false with a non-empty `compact_error` likewise; true → false success adds one unreported divider, and a following boundary replaces it in place (boundary `ts`, counts, manual) with no second row; a boundary with no edge appends one; a second rising edge forgets the pending divider so a later boundary appends; a falling edge with no rising edge adds nothing; a boundary whose `ts` a merged page already holds removes the pending divider rather than duplicating.
- `HistoryPageReducerTest` (AC 2): a page carrying the same frames reduces to the same rows as the live lane; merging it into a thread holding them live adds none; a stored rising edge alone still yields no row (existing `reduce_stateAndModalTypes_produceNoRowsAtAll` stays green); the gate drops stored `compacting` without `interactive`.
- `FileConversationCacheThreadTest` (AC 2): a failed divider round-trips; a document without the `failed` key reads not failed.
- `CompactionBoundaryLabelTest`: "Compaction failed" for a failed row.
- `CompactingProjection` tests (AC 3) and `RemoteConversationRepositoryTest`'s compacting and stored-state-frame tests run unchanged; one repository test proves the `TYPE_COMPACTING` arm reaches the thread.

Shared screen test: `ScriptedCompactingTest` re-run, since its falling edges now also draw a divider.

Rung 3 (AC 4): `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` today reads the divider text once; with this change it first shows "Conversation compacted" and is then filled in, so the test waits until the divider credits the compaction to you, then asserts exactly one compaction divider ("Conversation compacted" or "Compaction failed") in the thread. Compiled locally; the live gate runs it. No rung-4 twin: the deterministic `fakeclaude` emits no `compacting` (the rung-2 note in `ScriptedCompactingTest`), and the projection tests above hold every state.

## Open Questions

None.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/session-boundary-delimiter.md` § CompactionBoundaryDivider (#874): the failed and pending states, and the replaced divider's `ts` identity.
- `docs/e2e-interactive-stream.md`, the `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` paragraph: the one-divider assertion.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. `compact_result` and `compact_error` are claude-authored (protocol § `compacting`). They cross into the process only in `CompactingPayloadDto`, and `CompactingPayloadDto.failed()` reduces them to one boolean at that boundary. No string reaches `ThreadItem`, the cache or Compose, so the "render as inert text" obligation never arises: the label is client-owned copy chosen by `compactionBoundaryLabel`. A hostile daemon or claude can fabricate a failure or a boundary, which yields a misleading label and never an action. Nothing keys retry, routing or state on it, the same posture the protocol states.
- [Trust boundaries] SHOULD FIX. Pin the boundary in a test: a falling edge whose `compact_error` holds markup and a URL produces a row equal to the expected `CompactionBoundary(null, null, false, ts, failed = true)`, which proves by data-class equality that no wire text is held.
- [Tokens] No findings. The ticket touches no token, key or credential.
- [Files & storage] No findings. The cache gains one boolean in the existing app-private, atomically written thread document (`writeAtomically`). No path is derived from wire data. The new key is defaulted, so an older document still decodes rather than being discarded.
- [Android attack surface] No findings. The ticket adds no component, intent filter, pending intent or WebView.
- [Cryptography] No findings. The ticket adds no crypto and leaves the Noise session untouched.
- [Network & I/O] No findings. `compact_error` is held only transiently in the DTO and is bounded by the transport's existing inbound frame cap; the daemon's 256-byte bound is not relied on.
- [Errors & logs] No findings. Nothing in the new fold logs on any branch, matching `ThreadProjection` and the reducer. `compact_error` and `conversation_id` must never be logged.
- [Concurrency] No findings. `compactionFolds` has a single writer, the inbound collector, and its next value is independent of the thread, so a retried `update` stores the same fold. The duplicate-key crash, a hostile-daemon DoS route, is closed on every write: an edge divider checks `holdsCompactionBoundary`, and a replacement whose `ts` is already held removes the pending row instead of writing a second key. The cache's identity `require` remains the backstop.
- [Concurrency] OUT OF SCOPE. A daemon alternating edges can add one divider per falling edge without bound, as it already can with `compaction_boundary` frames carrying distinct `ts` values. Bounding thread growth is a general projection concern and is not introduced here. It needs no ticket of its own until a thread-size bound is designed.
- [Threat model] Malicious relay: it can drop or delay frames but not forge them inside Noise. A dropped boundary leaves an unreported "Conversation compacted" divider, and a dropped falling edge leaves only the boundary's own divider. Neither hangs anything or leaks plaintext. Hostile daemon frame: decoded defensively, and a malformed one costs only itself. Token theft and UI leakage: no change.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-02
