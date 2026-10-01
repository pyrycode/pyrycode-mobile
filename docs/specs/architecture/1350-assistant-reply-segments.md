# #1350 — Start a new reply bubble after each tool call

## Files read

- `data/repository/HistoryPageReducer.kt` — `withAssistantDelta` (finds the turn's row anywhere and appends: the bug), `withFinalizedTurn`, `withToolUse` (id+role guard), `reduceHistoryPage`, `mergeHistoryRows`, `mergeCachedRows`, `alreadyHolds`. All change here.
- `data/repository/ThreadProjection.kt` — `applyAssistantDelta` / `finalizeAssistantTurn` / `applyToolUse` are thin wrappers over the folds above and stay as they are; `observe` is the one read of the store and gains the streaming normalisation.
- `ui/conversations/thread/ThreadFold.kt` — `render`'s #425 guard hides the synthetic only when a finished row carries the bare `turnId`.
- `ui/conversations/thread/ThreadRow.kt` — `listKey`: a message row keys `"msg:<id>"`, role-agnostic, so any two `MessageItem`s with one id crash the `LazyColumn`.
- `data/model/Message.kt` — `Message` gains one defaulted field.
- `data/cache/FileConversationCache.kt` — `CachedMessage`, `toRecord`, `toDomain`; the cache must keep the segment's seq record so a restored row can still join a newer page.
- `data/repository/CachingConversationRepository.kt` — `observeMessages` draws `live.mergeCachedRows(base)`; read only.
- `data/cache/ConversationCache.kt` — `cacheableThreadRows` / `settledThreadRows` drop streaming rows; read only.
- pyrycode `docs/protocol-mobile.md` § `assistant_delta` (`seq` is per lane, starts at 0 each turn; a `channel post` mints a fresh `turn_id` and sends no `turn_end`), § Page size (a page is cut by count and bytes, a walk visits each entry exactly once), § Joining a page to the live stream (an entry appended between ask and answer arrives on both lanes).
- Feature overviews `streaming-assistant-turns.md` (#425 lesson: the daemon may set `turnId == message_id`, so the synthetic guard must be source-independent), `live-tool-call.md`, `remote-conversation-repository-reads-and-thread-store-history-paging.md` (one join key per row kind; a duplicate is skipped, not merged in place).
- In-flight overlap: #1351 adds `appendLiveMessage` to `ThreadProjection` and widens `storedAttachmentReferences`; #1353 adds row kinds to `FileConversationCache`. Both are additive beside my edits; I touch neither of their blocks.

## Context

A turn that streams text, a tool, then more text draws as one bubble above the tool, because `withAssistantDelta` appends every delta of a turn to the one row keyed by `turnId`. Desktop appends a delta to the last row only when that row is assistant text of the same turn, and otherwise starts a new row. Mobile should match it, live and in replayed history.

The row id is also the merge key and the list key, so the segment key decides whether a page or the cache duplicates text, drops text or crashes the list. This deserves a decision record ("assistant reply segments: key, seq record, seam join"); flagged for the documentation stage.

## Design

### The segment and its record

`Message` gains `segment: AssistantSegment? = null`. It is non-null exactly on an assistant row folded from `assistant_delta`s, and records:

- `AssistantSegment(turnId: String, deltas: List<SegmentDelta>)`, with `firstSeq` / `lastSeq` helpers;
- `SegmentDelta(seq: Int, length: Int)`: one per folded delta, in fold order; `length` is that delta's text length in `content`, so the lengths sum to `content.length`.

`seq` is the wire's per-turn counter. Both types live in `data/model/Message.kt`, portable, no Android imports.

### The segment key

`segmentKey(turnId, openingSeq)` = the bare `turnId` when the opening delta's `seq` is `0`, else `"$turnId#$openingSeq"`. Every turn's text starts at `seq 0`, so the bare id is the turn's first segment wherever it opened. A cached row from before this change is keyed by the bare id and therefore counts as the first segment, and the #425 guard keeps matching it. A later segment's key is a function of its opening delta alone, so live and a full history reduction derive the same key.

Uniqueness is not left to the key format (a daemon could pick a `turn_id` that spells another turn's `T#5`). It is enforced where a key is minted: a delta that would open a segment under a key any `MessageItem` already carries is dropped.

### The folds (`HistoryPageReducer.kt`)

- **`withAssistantDelta`**, in order:
  1. If `event.seq` is not above the highest `seq` any segment of `event.turnId` holds, return the list unchanged (the repeated-delta guard).
  2. If the last row is an assistant row whose `segment.turnId` is this turn, append the text and a `SegmentDelta` in place, and mark the row streaming.
  3. Otherwise open a segment at the end under `segmentKey(turnId, seq)`, streaming. If the key is already held by any `MessageItem`, return the list unchanged.
- **`withFinalizedTurn`** flips every streaming assistant row of the turn (its `segment.turnId`, or a bare-id row with no segment) to settled.
- **`withToolUse`**'s repeat check becomes role-agnostic: a `tool_use` whose id any `MessageItem` already holds adds no row. This closes the reverse of step 3's guard, so a tool cannot mint a segment's key either.

### "Only the newest segment streams"

`List<ThreadItem>.withOnlyLastRowStreaming()` settles every streaming `MessageItem` except the last row, returning the same list when nothing changed. `ThreadProjection.observe` applies it, so every reader of the thread (the cache wrapper, `ThreadViewModel`, the tests) sees a segment stop the moment any row follows it, whatever wrote that row. That covers the appends added by #1351 too, with no edit to them. The store may still hold a stale streaming flag on a non-last row. Nothing reads the store except `observe`, and a later delta can only append to the last row.

### Joining a segment cut by a seam

A page that starts mid-segment cannot know the segment's opener. It opens a provisional segment under the first `seq` it sees. Live does the same when it joins mid-segment. Within one fold, two adjacent assistant rows of one turn never occur, because the fold appends to the last row. So adjacent same-turn segments in a merged list are one segment cut by a seam: a page boundary, the page-live join or the cache-live join.

`joinSegments(older, newer): Message?` joins two adjacent assistant rows when both carry a segment of the same `turnId` and `newer.firstSeq > older.firstSeq`. The joined row:

- keeps `older`'s id, timestamp and session;
- holds `older`'s deltas plus `newer`'s deltas whose `seq > older.lastSeq`, with `content` cut at the matching offset. Overlap from the ask-versus-answer race is therefore counted once;
- takes `newer`'s streaming flag, because the newer lane has the later knowledge.

Otherwise it returns `null`. A private `withJoinedSegments()` pass applies it to adjacent pairs and returns the same list when nothing joined. `mergeHistoryRows` and `mergeCachedRows` run it on their result. Joining only removes the newer row and keeps the older row's id, which was already unique in the list, so it can never mint a duplicate key.

Rows without a segment (a `message` frame, a pre-change cached row) never join. Their only join is the existing id skip.

### The synthetic (`ThreadFold.kt`)

`render`'s guard also hides the synthetic when `finished` holds any assistant row whose `segment.turnId` is the streaming turn's id. The existing bare-id clause stays. `reduceDelta` is unchanged: its `seq <= lastSeq` drop already ignores a repeat, and after the structural finalise clears `stream`, a repeated first delta starts a stream that the guard hides because the projection holds the turn's segments.

### The cache (`FileConversationCache.kt`)

`CachedMessage` gains `segment: CachedSegment? = null` (`turnId`, `seqs: List<Int>`, `lengths: List<Int>`). Because it is defaulted, documents written before this change still read, and their rows carry no segment. On read, the record is kept only when it is consistent: the lists are non-empty and equally long, seqs strictly increase, lengths are non-negative and sum to `content.length`. Otherwise the row loads with `segment = null`. A malformed record costs the join, not the document. No version bump.

## State and concurrency model

No new jobs, flows or dispatchers. Every fold still runs inside `ThreadProjection`'s existing atomic `MutableStateFlow.update`. `observe` gains one pure `map` step before `distinctUntilChanged`. `ThreadFold` stays a pure `scan` step. The cache wrapper is unchanged and still writes only settled rows. A segment settled by the normalisation is now cacheable before `turn_end`, which is correct, because it can no longer grow.

## Error handling

All folds stay total and silent: no throws, no logs. Each guard (repeated `seq`, held key, held tool id) returns the list unchanged. That fails safe: a missing piece of text rather than a crashed list. A cache segment record that fails validation drops to `null` and does not reject the document.

## Testing strategy

New `app/src/test/java/de/pyryco/mobile/data/repository/AssistantSegmentTest.kt` (JVM, no coroutines beyond `first()` on `observe`):

- **AC1, through `ThreadProjection`:** text → tool use → tool result → text gives `[T, tool, T#k]` in that order, with only `T#k` streaming. Text → `appendMessages` user row → text gives `[T, user, T#k]`. `turn_end` settles both segments.
- **AC2:** the same entries through `reduceHistoryPage` give the same ids and contents. `live.mergeHistoryRows(page)` and `live.mergeCachedRows(pageRows)` add no row. A legacy cached row (bare id, no segment, whole-turn text) under live rows adds nothing.
- **AC3:** one turn split across two pages inside segment 2, and again inside segment 1. Merge the newer page, then the older one, and the rows equal the unsplit reduction (ids, contents, nothing streaming). Live joining mid-segment with an overlapping newest page gives each delta once. A cached full segment under a provisional live segment gives one row.
- **AC4:** a repeated first delta after a tool row changes nothing. A `seq` at or below the turn's highest changes nothing. A delta whose key a tool row holds adds no row. A `tool_use` whose id an assistant row holds adds no row. The ids in every result are distinct.

`ThreadFold`: add tests beside the existing #425 guard tests in `ThreadViewModelTest`, or in a new `ThreadFoldSegmentTest` if those are not pure fold tests. One test covers a finished list holding only `T#5` of turn `T` with a live stream for `T`, which renders no synthetic. Another covers a repeated first delta after a tool row, where `render()` equals `finished`.

Cache: `FileConversationCacheThreadTest` gains a round trip of a segment row and a test that an inconsistent record loads with `segment = null`.

Existing tests that encode the old single-bubble order are updated to the new order, and each is named in the PR. The scripted `tool`, `tool-failed` and `replay-order` scenarios run in the dispatcher's gate. `replay-order` is three deltas and `end_turn` with no row between them, so it remains one bubble. No live scenario: tracked in #1417.

## Open Questions

- Do `ThreadViewModelTest`'s existing fold tests assert the old merged-bubble order anywhere? Resolve by running them. Update the expectations only where they encode the bug.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/streaming-assistant-turns.md`: per-segment assistant rows, when a new segment starts, which segment can stream (`withOnlyLastRowStreaming` in `observe`), the synthetic guard's segment clause.
- `docs/knowledge/features/live-tool-call.md`: text after a tool row opens a new segment below it; `withToolUse` is now role-agnostic on its repeat check.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`: the segment key (`turnId` / `turnId#seq`), the bare key as the pre-change cached row, the seam join (`joinSegments`) for a page boundary, the page-live overlap and the cache-live join, and the cache's segment record.
- A decision record for the segment key and seam join, if the documentation stage agrees.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. `turn_id`, `seq` and `text` cross at the existing `AssistantDeltaPayloadDto.toEvent` decode and reach the folds already typed. They are daemon-authored, so the new code treats them as hostile. `seq` is compared, never used as an index. `text` is concatenated and sliced only by offsets the fold itself recorded. `turn_id` is used only to form a key string that is compared for equality. Nothing reaches markup, a URL, a path or a log.
- [Trust boundaries / hostile daemon] SHOULD FIX, addressed in the design. A hostile `turn_id` can spell another turn's segment key (`"A#5"`), a tool id, or a `message_id`, and a list with a repeated `"msg:<id>"` key crashes the thread. Key format alone cannot prevent this, so uniqueness is enforced where keys are minted. `withAssistantDelta` drops a delta whose new key is held. `withToolUse` drops a `tool_use` whose id any message row holds. `joinSegments` only removes rows. The merges keep their id skip. Phase B tests each of these with distinct-id assertions.
- [Files & storage] SHOULD FIX, addressed in the design. The cache now persists per-delta `seq` and length. A tampered or corrupt record could make `joinSegments` slice `content` out of range. The record is validated on read (lengths sum to the content length, seqs strictly increase), and the slice offset in `joinSegments` is computed from the row's own record and clamped to `content.length`, so a bad record degrades to no join and never throws. The cache stays in app-private storage under the existing `FileConversationCache` root, and the new fields are ints plus the `turn_id` already present as the row id. No new sensitive material is stored.
- [Errors, logs] No findings. No log call is added on any branch. The folds and the cache mapping stay silent, in line with `HistoryPageReducer`'s "nothing in this file logs" rule. `AssistantSegment`'s default `toString` holds only the turn id and ints, never text.
- [Concurrency] No findings. Every fold still runs inside the existing atomic `update`. The `observe` normalisation is a pure map, and no new scope or job is added.
- [Resource use] No findings. The seq record grows by one small entry per coalesced delta, bounded by the deltas the daemon already sends for that row. The join pass is linear per merge, as the existing merges are.
- [Tokens, crypto, network, Android surface] Not touched. The design changes no transport, key material, exported component or intent.
- [Threat model] A malicious relay can drop or reorder frames, but cannot forge them inside Noise. A dropped delta leaves a gap in the text. A reordered or replayed delta is caught by the `seq` guard, so it is not duplicated and causes no crash. A hostile daemon frame is handled as above. UI-side leakage is unchanged, because the rows render through the existing `MessageBubble`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
