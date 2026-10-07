# Remote conversation repository — assistant reply segments, the seam join and the turn-seq dedupe

Split out of [Remote conversation repository — reads and the thread store — history
paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) on 2026-10-03 to keep
that document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository —
the Phase 4 `ConversationRepository` — reads and the thread
store](remote-conversation-repository-reads-and-thread-store.md); see the parent history-paging document
for the rest (history pages folding into the thread, the walk that calls `requestHistory`, the retry and
restarts, and resuming from the saved position).

## Assistant reply segments: the key, the seam join, and the turn-seq dedupe (#1350)

[#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350) makes `withAssistantDelta` match desktop's
`appendDelta`: it extends the thread's **last** row only when that row is already an assistant segment of
the same `turn_id`; a tool row, a user message, a session boundary, or no row at all makes the delta open a
new segment at the end instead. A turn that goes text, tool, text now draws as two assistant rows around the
tool, live and on replay — see [Streaming assistant turns](streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350)
and [Live tool-call](live-tool-call.md) for the consumer-side read of this. The segment is `Message.segment:
AssistantSegment?` (`data/model/Message.kt`), non-null exactly on an assistant row folded from deltas:
`AssistantSegment(turnId, deltas: List<SegmentDelta>)`, one `SegmentDelta(seq, length)` per folded delta in
fold order, so the lengths sum to `content.length`. It carries no text, so its `toString` is safe to log.

**The segment key.** `segmentKey(turnId, openingSeq)` is the bare `turnId` when the opening delta's `seq` is
`0` — every turn's text starts there, so this is always the turn's first segment — and `"<turnId>#<openingSeq>"`
otherwise. Both live and a replayed page derive the same key from the same opening delta, with no shared
state between them. A row cached before segments existed carries no record and is keyed by the bare id, so it
reads as a turn's first segment under the new scheme too, and the pre-existing [#425](../codebase/425.md)
key-uniqueness guard keeps matching it unchanged.

**Two guards keep the key unique**, since it is also the thread's `LazyColumn` key and a daemon chooses
`turn_id` freely (it could pick one that spells another turn's `"<turnId>#<seq>"`, a `tool_use_id`, or a
`message_id`):

- `withAssistantDelta` drops a delta whose `seq` is not above the highest `seq` any segment of its turn
  already holds (`highestSeqOf`) before it ever looks at opening a new segment — this is also what makes a
  repeated first delta after a tool row a no-op instead of re-minting the first segment's key.
- A delta that would open a segment under a key any `MessageItem` already carries is dropped outright.
- The mirror case — `withToolUse` dropping a `tool_use` whose id any message already carries, not only an
  existing tool row — is in [Live tool-call](live-tool-call.md#tool_uses-own-repeat-check-is-id-only-not-role-namespaced-1350).

Each guard only ever suppresses a row; none of them ever completes, moves or resets one, so the worst a
hostile `turn_id` achieves is missing text, never a crashed list.

**Only the newest segment streams.** `List<ThreadItem>.withOnlyLastRowStreaming()` settles every streaming
assistant row but the last, returning the same list when nothing changed; `ThreadProjection.observe` applies
it before `distinctUntilChanged`, so every reader of the projection sees a segment stop the moment any row
follows it — a tool call, a user message, whatever put that row there. See
[Streaming assistant turns § the known gap](streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350)
for the one composition point downstream of `observe` this does not cover.
`turn_end` (`withFinalizedTurn`) still settles **every** streaming segment of the turn, plus a bare-id row
with no segment record — not only the last one — since a daemon bug or a race can otherwise leave an earlier
segment stuck streaming.

**`withAssistantDelta`'s `passOver: Set<String> = emptySet()` ([#1558](https://github.com/pyrycode/pyrycode-mobile/issues/1558)).**
The "last row" a delta extends is picked by `indexOfLast`, skipping any user row whose id is in `passOver` —
so a delta lands on the running reply even when a user row this device minted sits after it in store order
but reads below it (a [queued own echo](queued-backlog.md#own-echo-position-a-queued-message-draws-below-the-turn-it-waits-behind-1558)).
`ThreadProjection.applyAssistantDelta` passes its conversation's `parked + awaitingPush` user ids; this history reducer's own
caller passes nothing, since a page is reduced after the fact and carries no notion of "still queued." Without
this, the echo — appended to the store at tap time, ahead of the reply's first delta — would look like the
delta's "last row" and the reply would open a second segment below it instead of extending the one above.

**Delivery placement and segment continuity (#1655).** Parked rows read below the running
turn in queue-entry FIFO order even when legacy reservations have moved their store slots.
A first modern user delivery fixes a pending own echo at its stream opening; subsequent drain
snapshots cannot insert it into the answering segment. Row-id deduplication and `(turnId, seq)`
deduplication alone do not prevent a split: moving the separator after delta 0 leaves delta 1
on the other side, even if replayed delta 0 is correctly discarded.

History may establish that user row and delta 0 before the first live push. Its ordered merge
must settle placement eligibility and exact queue-entry consumption atomically, retaining held
echo metadata, so replay preserves both the separator's position and one uninterrupted reply.
See [history delivery](remote-conversation-repository-reads-and-thread-store-history-paging.md#history-pages-fold-into-the-same-thread-645).
A regression needs answering text in the overlap page on either side of the push, and collection
advanced after each event; final-only StateFlow assertions can hide a transient split. A real
intervening tool or delivered message still creates a legitimate segment boundary. The bounded
legacy fallback and no-echo/Codex timing limits are in [Own echo position](queued-backlog.md#legacy-and-no-echo-limits).

### The seam join

A history page is cut by entry count and byte size, never by turn, so a page can start or end inside a
segment; the same is true where a newest page meets the live rows, or where the cache meets either. Within
one fold a delta always extends the last row when it is the same turn's segment, so two assistant rows of one
turn ever sitting adjacent in a merged list are always **one segment a seam cut in two**, never two
independent segments that happen to be neighbours.

`joinSegments(older, newer): Message?` joins such a pair: both must record a segment of the same `turnId`,
and `newer` must have opened after `older` did (`newer.firstSeq > older.firstSeq`); anything else returns
`null` and nothing joins — a `message` frame or a row cached before segments existed has no record and never
joins. The joined row keeps `older`'s id, timestamp and session (it saw the segment's true opener), holds
`older`'s deltas plus `newer`'s deltas whose `seq` is past `older`'s last (a delta the ask-versus-answer race
delivered to both lanes is counted once, located from `newer`'s own record and clamped to its content so a
mismatched record cuts short instead of throwing), and takes `newer`'s streaming flag, since the newer lane
knows whether the turn has ended. `withJoinedSegments()` walks a merged list once and applies this to every
adjacent pair, returning the same list when nothing joined. Joining only ever removes the later row and keeps
the earlier row's already-unique id, so it can never mint a duplicate key.

### The `(turnId, seq)` dedupe — the receiver owns a turn from its lowest `seq`

An id-and-adjacency join alone assumed both lanes place one turn's rows in the same relative order, which
held for every scripted scenario built on tool calls — a tool row's position is agreed by both lanes — but
broke on a mid-turn user message: `MessageCommands.sendMessage` appends the local echo as soon as the `ack`
arrives, mid-turn, while the daemon logs a message sent while claude is busy only once it is delivered from
the queued-message backlog (pyrycode `docs/protocol-mobile.md` § `queue_state` / § `message`, #2699). The two
lanes can therefore place that message in different positions relative to the turn's deltas, which splits the
turn into differently-keyed segments on each side — the verifier reproduced both a reconnect-through-cache and
an in-session case where this drew the turn's reply text twice (PR #1420 review, 2026-10-01).

The fix: before merging a page or cached rows, `mergeHistoryRows` and `mergeCachedRows` first compute
`segmentHeads()` — for every turn the receiving thread already holds segments of, the lowest `seq` it holds
and the index of its first segment row. An incoming row runs through `olderThan(heads)`: an assistant segment
of a turn the thread holds keeps only the deltas below that turn's lowest `seq` (`takeWhile { it.seq < below
}`, content cut to match and clamped to length), and is dropped entirely when none are below it; every other
row passes through unchanged. Because deltas are recorded in increasing `seq`, what survives is always a
prefix of the record and the content, so the trimmed row's id — that of its own opening delta — stays
correct. The same `seq` can therefore never be drawn twice: the receiver owns everything from its lowest `seq`
up, and the incoming side only ever contributes what is strictly older. `withJoinedSegments()` then joins a
trimmed head to the segment it continues, exactly as for a page-boundary seam.

`mergeCachedRows` additionally floors where a trimmed cached head can land: no lower than right above the
receiving thread's first segment row for that turn (`heads[turnId].index`), never lower just because the
cached row's own position in `cached` was lower. Without that ceiling, a cached head that sat *below* the
echo in live arrival order could land below newer text the page holds, reproducing the same duplication from
the cache side.

**Pre-change cached rows.** A row cached before segments existed carries `segment == null` but is keyed by
its turn's bare id and holds that turn's whole text (as every row did before #1350).
`withoutSegmentsOfWholeTurns()` removes any segment of a turn such a row's id names from the merged result —
it only ever removes rows, never the whole-turn row itself — so the legacy row keeps drawing exactly as it
did before this ticket, with its text appearing once rather than once more per segment underneath it.

**Lesson for later tickets touching this merge:** a join keyed on id-plus-adjacency is only as safe as the
assumption that every lane orders a turn's interleaved rows the same way. A client-placed local echo breaks
that assumption structurally (the client decides when its own echo appears; the daemon decides independently
when it logs the same turn), so any future per-turn join here needs a receiver-owns-the-prefix rule like
`olderThan`/`segmentHeads`, not a positional one.

### Parent attribution through seams and merges (#1826)

`Message.parentToolUseId` is retained metadata, independent of segment keys and `(turnId, seq)`
identity. `withAssistantDelta` selects the first non-empty held parent for the same wire turn,
otherwise the incoming non-empty hint. It applies that value before sequence/key guards, so even a
duplicate replay can enrich unknown attribution without duplicating text or moving rows. Append and
new segments inherit it when later deltas omit the hint. Main and two child lanes starting at seq zero
remain separate; sharing a parent never merges two wire turns.

Before `mergeRows` atomizes either list, it selects the first non-empty held hint per turn; only a
turn without held attribution takes the first non-empty incoming hint. It applies the winner to
**every held and incoming reconstruction or replacement candidate**, including conflicting non-empty
hints. Selecting a winner in a lookup but filling only empty candidates loses the held parent when an
older incoming opener becomes the reconstructed row, or a legacy whole-turn row replaces it.
The lookup uses `segment.turnId`, falling back to the bare assistant message id for a legacy row,
and is local to this conversation's merge. Atom copies and `withJoinedSegments` retain the hint;
an unattributed opener cannot erase a known parent on rejoin. These copies change attribution only,
leaving text, duplicate identity, row keys and held-row relative order to the existing merge rules.

`AssistantParentAttributionTest` probes both history and cache paths: conflicting older openers,
prefix/middle/suffix overlap in both arrival directions, non-recoverable legacy replacement and
repeated merges. Assertions cover parent, text, keys and held separators/order, alongside older-page
prepend, duplicate live replay and conversation isolation. Comparing the attributed script with its
parentless counterpart catches accidental changes to identity or placement. See
[the cache thread document](conversation-cache-layout.md#layout) for disk-only unknown attribution and
in-memory reconnect retention.

### The cache's segment record

`FileConversationCache`'s `CachedMessage` gains `segment: CachedSegment? = null` (`turnId`, `seqs: List<Int>`,
`lengths: List<Int>`), defaulted so a document written before this change still reads with `segment = null`.
On read, the record is kept only when it is internally consistent — the lists are non-empty, equal length,
strictly increasing `seqs`, non-negative `lengths` summing to the row's `content.length` — and dropped to
`null` otherwise, which costs that row its join (it falls back to counting as a pre-change whole-turn row) but
never rejects the document.

### A `turn_end` that settles rows which have not arrived yet (#1419)

`withFinalizedTurn` (above, in the per-segment settle) only ever flips rows already sitting in the thread. Two
orderings bring a turn's rows in *after* its `turn_end` has already been seen: the newest history page can
hold only the `turn_end` while an older page still holds the turn's deltas, or a live `turn_end` can land
before the newest page carrying those deltas is merged. Either way, the late rows used to enter the thread
`isStreaming = true` and stay that way — the newest one forever, since nothing followed it to trip
`withOnlyLastRowStreaming`, and `CachingConversationRepository.cacheableThreadRows` drops streaming rows, so
they never reached the cache either.

`ThreadProjection` now keeps a second map beside `threadByConversation`: `endedTurns`,
`conversationId -> the turn ids whose turn_end this conversation has seen`, on either lane. It is
connection-scoped and in-memory like `mintedMessageIds`, grow-only (an ended turn never streams again), and
`remove(conversationId)` drops it with the thread. `finalizeAssistantTurn` and `mergeHistoryPage` both record
into it *before* touching the thread, and `withFinalizedTurn` is now `withSettledTurns(setOf(turnId))`, a
small generalization (`internal fun List<ThreadItem>.withSettledTurns(turnIds: Set<String>)`) that settles
every row of several turns at once — a merge settles every turn `endedTurns` names, not just the one the
current page mentions. `endedTurnIds(entries, interactive)` reads a page's `turn_end` entries for this
independently of `reduceHistoryPage`: **a page holding only a `turn_end` reduces to zero rows**, so the
memory of having seen it has to come from the page's own entries, gated by the same `interactive` check
`withHistoryEntry` applies, not from the reduction's output.

**The cross-lane race, and why one settle pass inside the merge isn't enough.** `mergeHistoryPage` runs on
the caller's coroutine (`ThreadViewModel`'s scope, Main); a live `turn_end` runs on the inbound collector
(`Dispatchers.Default`). The two can interleave inside a single `MutableStateFlow.update {}`'s read step.
`MutableStateFlow.update` compares old and new by `equals` and writes nothing when they're equal — so a
`finalizeAssistantTurn` whose thread flip finds none of the turn's rows yet (because the merge hasn't landed
them) writes nothing, and critically **does not make the racing merge's compare-and-set fail**. A first
version of this fix settled the merge's own incoming rows against `endedTurns` inside the merge's `update`
lambda and reasoned that the finalize's own flip would "catch" any row the merge missed — but that argument
assumed the finalize's update always changes something, which a no-op update doesn't. The verifier's rework
(PR #1442) found the resulting window: the merge reads `endedTurns` before the live `turn_end` records,
the live `turn_end`'s own flip then finds nothing to settle and writes nothing, and the merge's
compare-and-set against the stale snapshot still succeeds and commits the turn's newest row streaming.

The fix is a second, independent settle pass: after `mergeHistoryPage`'s own thread `update` commits, a
private `settleEndedTurns(conversationId)` runs one more `update` that re-reads `endedTurns.value` fresh and
settles against it, writing nothing when nothing changes. Either the live `turn_end` recorded before that
re-read (the pass settles the rows), or its own thread update starts only after the merge committed (it finds
the rows and settles them itself). A live `assistant_delta` needs no equivalent pass: unlike `turn_end`'s
flip, a delta's update always changes the thread, so a racing merge's compare-and-set either fails and
re-reads `endedTurns`, or the merge is the one that lands first and the delta's own update then sees the
settled state. One known-incomplete edge of that argument: a live delta that reads `endedTurns` just before a
concurrent merge records the same turn from a page that changes nothing in the thread (for example, a page
holding only that `turn_end`) can still commit a streaming row, because both the merge's update and its
settle pass write nothing in that case. This is harmless here only because the live lane is strictly ordered
on one collector — that same turn's own `turn_end` is still to come on the same lane and settles the row when
it arrives — and because `endedTurns` is connection-scoped, so nothing carries a stray streaming row across a
reconnect.

**Durable lesson for any future `StateFlow`-pair coordinated by write order:** "the other writer's update
will follow and catch what I missed" only holds when that writer's update is guaranteed to change its state.
An update that compares equal under `equals` is a no-op and cannot make a concurrent compare-and-set retry.
Closing a window like this needs a pass that re-reads the *other* flow's value after the first flow's own
update has committed, not an in-line read racing the other writer.

Covered by `AssistantSegmentTest`: `turnEndOnANewerPageThanItsRows_settlesThem` (history walk, newest page
holding only the `turn_end`), `liveTurnEndBeforeTheNewestPage_settlesItsRows` (live `turn_end` ahead of the
page), `turnWithNoTurnEnd_stillStreamsAfterAMerge_andALaterTurnEndSettlesIt` (a turn with no `turn_end` yet is
unaffected), plus guards for another turn, another conversation, a non-interactive page, a live delta after
its own `turn_end`, and `remove_forgetsTheConversationsEndedTurns`. No deterministic interleaving harness
exists for the two-coroutine race above; the argument for it lives in `endedTurns`' KDoc and here, not in a
test.
