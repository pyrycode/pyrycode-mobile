# Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store — history paging

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md); see that document for the rest.

## History pages fold into the same thread (#645)

[#623](../codebase/623.md) landed `requestHistory` returning a decoded `HistoryPage` of `HistoryEntry`
values (newest-first; each carrying a durable per-conversation log `id`, the stored frame's wire `type`,
its still-undecoded `payload`, and a `ts`) and deliberately stopped there. [#645](../codebase/645.md) is
the fold its KDoc promised: `requestHistory` now also calls a private `mergeHistoryPage(conversationId,
page)`, which reduces the page and merges it ahead of `threadByConversation[conversationId]` inside one
`MutableStateFlow.update {}` — a read, a merge and an assign that must stay one check-then-act, since
computing the merge outside the lambda would silently lose a concurrent live append on a CAS retry. The
page is still returned to the caller unchanged; nothing in the app calls `requestHistory` yet
([#646](../codebase/646.md) owns the demand side, [#647](../codebase/647.md) the offline cache).

**One fold surface, not two.** The reduction reuses the live lane's own folds rather than mapping the
page separately. `RemoteConversationRepositoryKt`'s `appendMessages` / `applyToolUse` / `applyToolResult`
/ `applyAssistantDelta` / `finalizeAssistantTurn` were lifted into pure `List<ThreadItem>` extensions in a
new file, `data/repository/HistoryPageReducer.kt`, and the five repository methods are now thin
`MutableStateFlow.update {}` wrappers over them — the #336 move, repeated, with the existing 273-test
`RemoteConversationRepositoryTest` suite as the output-preserving guard (unchanged and green is the
evidence the lift didn't alter live behaviour). `reduceHistoryPage(entries, interactive)` reverses the
wire's newest-first page to oldest-first and folds each entry through those extensions from an empty
list, dispatching on `HistoryEntry.type` against the repository's own wire-type constants (its companion
object widened from `private` to `internal` for this — the constants are protocol vocabulary, not state,
so widening grants no new mutation). `message` / `send_message` fold ungated; the four turn-scoped types,
`session_transition` and `unrecognized_message` fold only when `interactive` was negotiated — mirroring
the live `onInbound` gate arm-for-arm, because the daemon's `request_history` handler itself carries no
such gate. Any other `type` — including one a future daemon invents — is the silent `else`; a payload
that fails its per-entry decode drops that entry only, inside the same `catch (IllegalArgumentException)`
idiom every `onInbound` arm uses, so one bad entry never fails the page. Nothing on this path logs `type`
or `payload` on any branch, matching the live lane.

**`tool_denied` ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) gets its own gated arm,
not a `decodeLiveEvent` case.** The daemon replays `tool_denied` in history through the same emit path as
`tool_result`, so `withHistoryEntry` needed a sixth arm — but it is not a `LiveSessionEvent`, so it decodes
`ToolDeniedPayloadDto` directly (the same DTO and `toDenial()` the live lane uses) rather than going
through the four-type `decodeLiveEvent` dispatch, and calls the shared `withToolDenied` fold. Gated on
`interactive` like its five siblings. A page carrying `tool_use`, `tool_denied` and `tool_result` for one
call — in that order or with the result before the denial — folds to a single `Denied` row, because
`withToolResult` (shared with the live lane, see [Live tool-call § Denied](live-tool-call.md#denied-811))
never overwrites a `Denied` status. See [Live tool-call](live-tool-call.md) for the state machine and
[Remote conversation repository § Live tool-call rows](remote-conversation-repository-thread-observables.md#live-tool-call-rows--applytooluse--applytoolresult--applytooldenied-387-811)
for the live-lane twin, `applyToolDenied`.

**The one behavioural difference from the live lane is the row clock, and it has to be hoisted, not
copied.** Three of the five lifted folds (`withToolUse`, `withAssistantDelta`, and their live callers)
stamped `Clock.System.now()` inline before the lift; sharing them with a replay path meant turning that
into a parameter. The live wrapper still passes `Clock.System.now()`; the reduction passes the entry's
stored `ts`. Skipping this would stamp a replayed tool call with the moment it was replayed rather than
the moment it happened — and nothing would fail to prove it, since thread order is arrival order and
never a timestamp sort (see `withToolUse`'s KDoc in the reducer file).

**A stored `send_message` reduces its `attachment_ids` to nameless references (#983).** The
`TYPE_SEND_MESSAGE` arm builds `attachments = dto.attachmentIds.orEmpty().filter(::isAttachmentIdShape)
.distinct().take(MessageAttachmentIds.MAX).map { MessageAttachment(it) }` — the same id-shape check and
the same `MAX = 32` bound the send path enforces, reused here against a daemon that could otherwise replay
a stored entry naming thousands of ids or a key-colliding list. A `null`/empty `attachment_ids` yields
`emptyList()`, so a text-only entry reduces exactly as it did before this ticket, and an id that fails the
shape check is dropped silently while the rest of the entry — its text and its other ids — is kept, the
same fail-open posture every other per-entry decode failure in this reducer already has. These references
carry no name or MIME hint: the wire's `attachment_ids` is bare ids, so `displayName`/`mimeType` stay
`null` until a merge (below) fills them from a twin that has one.

**A stored user `message` entry reduces its `attachment_ids` the same way (#1020).** Before this ticket
the `TYPE_MESSAGE` arm read no ids at all, so a peer's own attached file never survived a history reload
or a rebuilt thread cache — #983 had wired only the `TYPE_SEND_MESSAGE` arm, the operator's own echo. The
daemon now stores the optional field on a `message` entry too, named and shaped like `send_message`'s
(pyrycode#2596). `TYPE_MESSAGE` still decodes through `MessagePayloadDto.toMessage` first; only when the
decoded `role` is `Role.User` does it copy the result with `attachments =
storedAttachmentReferences(dto.attachmentIds)`, the same shared filter, dedup and `MAX = 32` cap
`TYPE_SEND_MESSAGE` calls — the ids mean something only on a user turn, so an assistant `message` keeps an
empty attachment list whatever its payload carries. The hint-fill below needed no change to cover it:
`withAttachmentHintsFrom` keys on `message_id` and `attachmentId`, not entry type, so a replayed user
`message` row picks up its filename from a cached twin or from [retrieval](attachment-retrieval.md)
exactly as a `send_message` row does.

**A `message_id` join now also fills a missing attachment hint from its twin, in both merge
directions (#983).** `mergeHistoryRows` and [`mergeCachedRows`](caching-conversation-repository.md#how-the-restore-merges-with-live-rows)
share one hint-fill, `withAttachmentHintsFrom`: when a kept `MessageItem` has a reference with a `null`
`displayName`/`mimeType` and a twin sharing the same `message_id` and the same `attachmentId` carries one,
the kept row adopts it — position and every other row untouched, and the function returns `this` verbatim
when nothing needs filling. It never overwrites a hint the kept row already has, so a replayed history row
can never rename a file the operator sent. Both directions need this because the two entries a `message_id`
join can encounter have opposite hint availability: the **history walk** joins a local echo (has names,
from `ThreadViewModel`'s own send) against a page row for the same send (has none, per the paragraph
above) — the echo already keeps its names via the existing skip-and-prepend, so this direction is a no-op
in practice. The **cache merge** is where it matters: after a reconnect, the live projection's row for a
sent message comes back from `requestHistory`'s replay with no names (the same page-side reduction), while
the cached twin still has them from before the disconnect — without the fill, a round-trip test against
the cache alone would pass while the thread the screen actually draws loses its names.

**A `HistoryEntry` reaches no `Envelope`, so `MessagePayloadDto.toMessage` gained a payload-level twin.**
Five of the six per-type decode arms (`ToolUsePayloadDto.toEvent`, `ToolResultPayloadDto.toEvent`,
`AssistantDeltaPayloadDto.toEvent`, `TurnEndPayloadDto.toEvent`, `UnrecognizedMessagePayloadDto.toRow`)
were already payload-level — no `Envelope` required — so only the `message` mapper needed a second entry
point. `MessagePayload.kt` now has `fun MessagePayloadDto.toMessage(timestamp: Instant, sessionId:
String): Message` as the primary mapping; the existing `toMessage(envelope, sessionId)` delegates to it
via `Instant.parse(envelope.ts)`. One mapping, two callers — the envelope form's contract (and its parse
failure mode) is unchanged. A stored `send_message` entry has no mapper of its own: its `DTO` maps
directly to a `Role.User` `Message` inline in the reducer, since `role` is not a wire field on that
payload (the sender is the operator by construction).

**Three join keys, and the merge's key is deliberately the renderer's key, not structural equality.**
The wire SSOT (pyrycode `docs/protocol-mobile.md` § *Conversation history (v2)*, sub-section *Joining a
page to the live stream*) names three keys: `HistoryEntry.id` for page-against-page (unused directly by
the merge — see below), the pair `(type, ts)` for page-against-live (not implemented by this ticket; the
merge instead re-derives presence per row kind), and `message_id` for a stored `send_message` against its
local echo. `mergeHistoryRows` never joins a `HistoryEntry.id` to a live `Envelope.eventId` — they are
different sequences that both look like small integers, and neither appears in the merge at all. What the
merge actually checks, per `ThreadItem` kind, is **the same key `ThreadScreen`'s `LazyColumn` uses to key
that row** — not `==`. This mattered in practice: the obvious dedup for a `SessionBoundary` is structural
equality (every field is payload-derived, so a page twin equals its live twin), and it passes every
overlap test — but at the time `ThreadScreen` keyed a boundary row on `(previousSessionId, newSessionId)`
alone and read neither `reason` nor `occurredAt`, so a page carrying two boundaries sharing that pair and
differing only in `occurredAt` would pass an equality check and still hand the `LazyColumn` two rows with
one key, which throws. The general lesson: when a list row has a client-visible identity, dedup upstream
on *that* identity, or the two can silently disagree. [#775](../codebase/775.md) later found that the
pair alone is not a safe join key either — an idle-evicted session keeps its id, so a session evicted
twice legitimately sends the pair twice with different instants, and both boundaries are real. The join
key (`holdsBoundary`, `internal` since #775) and the list key both moved to the full
`(previousSessionId, newSessionId, occurredAt)` triple, so the merge admits the second eviction instead
of dropping it. A `MessageItem` joins on `message_id` alone, id-only and
role-agnostic — one key serves a stored `message`/`send_message` entry, a `tool_use_id`, and a `turn_id`,
and it is also `appendMessages`' existing live-lane dedup rule. An `UnrecognizedMessage` joins on its id,
which the reducer derives as `"history-${entry.id}"` from the durable per-conversation log id — stable
across re-reduction, and disjoint from the live lane's per-process-counter `"unrecognized-<n>"` namespace
(see [Unrecognized message row](unrecognized-message-row.md)) so the two cannot collide by coincidence.
The merge is a **prepend, never a re-sort** (`fresh + this`, never a timestamp sort — the thread is
arrival-order by deliberate choice, see `applyToolUse`'s KDoc above) and a duplicate is **skipped, not
updated in place**: a page is always older than the live lane, so the only possible overlap is the narrow
ask-versus-answer race the protocol names, and in that window the live lane still owns the newer state.

**A page cannot promote a `Running` tool row to `Done`/`Failed` — only the live lane can, for now.** A
page carrying a `tool_result` for a tool row the thread already holds as `Running` (the ask-versus-answer
race) leaves that row `Running`: the reduction folds against an empty accumulator and only the merge runs
against the existing thread, and the merge skips rather than updates. That is correct for the one window
it can occur in, but it is easy to assume the merge completes a row it should only be skipping. If a
walking caller (#646) ever needs a page to complete a still-`Running` row, that is new merge behaviour, not
something this reducer already does.

**Cross-conversation write is structurally impossible, not checked.** `reduceHistoryPage` returns a bare
`List<ThreadItem>` carrying no conversation identity, and `mergeHistoryPage` routes into
`threadByConversation[conversationId]` — the conversation the client asked about — without ever reading an
entry payload's own `conversation_id`. The same structural argument closes AC #4 (a stored `turn_state` /
`stall` / `queue_state` / `api_retry` / `compacting` / modal frame cannot reopen a prompt or restart an
indicator): the reduction's return type is `List<ThreadItem>` and it holds no reference to
`stalledConversations`, the live-event stream, or the modal state, so those state frames simply have no
arm and land in the silent `else`.

**Closed by #775:** the live lane's own `appendSessionBoundary` used to have no dedup at all (see
[Session-transition fold](session-transition-fold.md)) — the merge above closed the crash only for the
history path. [#775](../codebase/775.md) gave `appendSessionBoundary`, this merge and the renderer's key
one shared `(previousSessionId, newSessionId, occurredAt)` identity, so a repeated eviction is now
admitted as its own row on every path instead of crashing the live lane.

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

## The walk that finally calls `requestHistory` (#777)

[#645](../codebase/645.md) shipped the fold and left `requestHistory` with no caller. [#777](../codebase/777.md)
adds the caller, and it lives **beside `ThreadViewModel`**, not in this repository — the contract above is
unchanged, and this section exists because the demand's design leans on guarantees this document already
records.

- **`ThreadHistoryDemand`** (`ui/conversations/thread/ThreadHistoryDemand.kt`) is a pure value — cursor,
  pages-loaded count, in-flight flag, and a `HistoryWalkStop?` (`AtStart` / `NotAdvancing` / `PageCap` /
  `Failed`, `null` while still walking). As shipped by #777, `ThreadViewModel` asked with it in `init`
  (empty cursor = newest) and again each time the thread screen reported the reader had reached the oldest
  loaded row. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) removed both triggers: older
  pages now load only on the reader's own gesture, never on open — see
  [§ the retry and the two restarts (#778)](#the-retry-and-the-two-restarts-778) below and
  [Thread screen § the oldest-end history demand](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-demand-777)
  for what replaced the scroll-driven ask.
- **The walk reads `requestHistory`'s returned `HistoryPage` for `cursor` and `atStart` only.**
  `settled(pageCursor: String, atStart: Boolean)` takes the two scalars rather than the whole `HistoryPage`
  — `ThreadHistoryDemand.kt` imports neither `HistoryPage` nor `HistoryEntry`, so no daemon-authored entry
  text can structurally reach the walk's state. This is the caller-side half of "nothing needs a second
  fold": `RemoteConversationRepository.requestHistory` already merged the page into `threadByConversation`
  before returning (the § above), and `ThreadViewModel` reads that merged result through the existing
  `observeMessages` collector exactly as it does today — the walk never touches an entry.
- **One outstanding request per conversation, claimed CAS-style.** `ThreadViewModel` claims the slot with a
  `MutableStateFlow.compareAndSet` retry loop, not a read-then-assign — the settle runs in a launched
  coroutine, so a plain check-then-act would open a window for two concurrent asks. An ask arriving while
  one is in flight is dropped, never queued.
- **Two termination rules, and only one is a security bound.** `atStart` is the wire's only true
  termination signal and is checked before the cursor comparison, because the wire leaves the returned
  cursor empty whenever `atStart` is true. `pageCursor.isEmpty() || pageCursor == cursor` (`NotAdvancing`)
  is an **honest-bug guard only** — a daemon alternating between two distinct cursor values defeats it
  while still answering `atStart = false` forever. The load-bearing bound against a deliberately
  adversarial daemon is the client-side `MAX_HISTORY_PAGES = 100` cap in `settled()`, which does not read
  anything the daemon sent to decide when to stop. The cap is per `ThreadViewModel` instance (so per
  screen-open), with a fresh count on every open — **leaving and re-entering a thread does not start a
  fresh walk any more.** As shipped by #777 it did: nothing survived a screen close. [#1354](#resuming-from-the-saved-position-1354)
  changed that by saving the walk's position (not the count) beside the cached rows, so re-entering a
  saved thread continues its cursor from where the last visit left off while the page budget still
  resets to 100 for the new open.
- **A failed ask keeps the cursor and page count, clears in-flight, and stops asking — no retry, as
  shipped here.** `failed()` set `stoppedBy = Failed` without touching `cursor` or `pagesLoaded`, so every
  row already loaded and the walk's position survived a failure. `HistoryWalkStop` was an enum rather than
  a `Boolean` specifically so [#778](../codebase/778.md) could reopen `Failed` alone — `AtStart` /
  `NotAdvancing` / `PageCap` stayed terminal. Nothing here retried, restarted on reconnect, or persisted
  the cursor **across a screen close**: the projections above are connection-scoped
  (`threadByConversation` starts empty on each connection), so a cursor surviving a reconnect would be a
  stale-cursor bug rather than a resume point. [#778](#the-retry-and-the-two-restarts-778) reopened the
  reconnect gap; [#1354](#resuming-from-the-saved-position-1354) later gave the *in-memory* cursor a
  disk-backed twin that does survive a screen close, on the far side of a received page rather than a
  failed one — see that section for why a failed ask still writes nothing to it.
- **The opening ask stayed unconditional as #777 shipped it**, resolving the plan's second Open Question:
  `mergeHistoryRows` (the § above) already skips any row the thread holds, keyed on the renderer's own row
  key, so a first page overlapping the `backfill_since` replay ring was fully absorbed with no duplicate
  rows. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) answered the question differently
  by removing the opening ask altogether — older history loads only on request, on both apps, so there is
  no first page to suppress or keep. The dedup argument still holds for every ask that remains: a page the
  reader does ask for that overlaps the ring is still fully absorbed with no duplicate rows.

## The retry and the two restarts (#778)

[#777](../codebase/777.md) left `Failed` as a one-way door: a page that failed left every loaded row and
the cursor in place but stopped the walk forever, and a reconnect left the walk holding a cursor the new
connection's projections could never honour. [#778](../codebase/778.md) reopened that door with a retry and
two self-triggered restarts. [#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) then removed
both restarts outright: their shared premise — that a cursor cannot outlive its connection — was wrong. The
cursor names a position in the daemon's append-only on-disk log (pyrycode `docs/protocol-mobile.md`, "The
cursor"), not in the connection, and the rows already drawn survive a reconnect as
`CachingConversationRepository.observeMessages`'s base. Only the retry survives from #778, joined by a new
always-available recovery path: any failure, not only a retryable one, now lets a fresh gesture ask again.
The design lives beside `ThreadViewModel`, not in this repository, and this section records only what it
depends on here.

- **`HistoryWalkStop.Failed` split into `RetryableFailure` and `PermanentFailure`** (#778), unchanged by
  #1352, on `RelayErrorException.retryable` — `requestHistory`'s own contract names `history.unavailable`
  as the **only** retryable code; the unknown-conversation `IllegalArgumentException` and the
  closed-session `IllegalStateException` this repository's KDoc documents both settle permanently. `AtStart`
  / `NotAdvancing` / `PageCap` are unchanged and stay terminal.
- **`ThreadHistoryDemand` still reads `requestHistory`'s return for `cursor` and `atStart` only** — the
  retry asks through the same `requestHistory` call this document describes above, so a retried page folds
  into `threadByConversation` exactly the way any other page does, via `mergeHistoryPage`'s existing
  dedup-by-renderer-key. Nothing on the caller side needed a second fold, and `ThreadHistoryDemand.kt` still
  imports neither `HistoryPage` nor `HistoryEntry`.
- **Neither failure is a one-way door any more (#1352).** `ThreadHistoryDemand.canAsk` holds after
  `RetryableFailure` and after `PermanentFailure` alike — only the three terminal stops (`AtStart`,
  `NotAdvancing`, `PageCap`) refuse a further ask. `asking()` claims the outstanding-request slot and
  clears a failure stop in the same step, so both a fresh gesture after any failure and the Retry press
  resume from the same `cursor` and `pagesLoaded`, loading the page that failed. The `retrying()`
  transition #778 added is gone; `onRetryOlderHistory` now claims the slot through `asking()` under
  `canRetry`, which is unchanged and still gates on `RetryableFailure` only, since Retry must stay inert
  against a non-retryable failure.
- **No restart exists any more, and none is needed.** #778's two restarts — an injected
  `repositoryAvailable: Flow<Boolean>` transitioning back to `true` re-asking the newest page, and a
  refused cursor doing the same — and the monotonic `walk` generation that protected a restarted walk from
  a superseded connection's late settle are all removed. With exactly one ask ever in flight (the CAS claim
  in `claimHistorySlot`) and no path left that asks by itself, every settle belongs to the walk's current
  ask; a settle landing after a reconnect is valid precisely because the cursor it answers survived that
  reconnect, so there is nothing left for a generation counter to protect against. The #861 fix to the
  second trigger — deriving `repositoryAvailable` from `bundle.coordinator.currentRepository.map { it !=
  null }` rather than the socket-level `ConnectionStateSource`, because a relay host's repository is
  published only at `PumpState.Open`, later than the socket's `Connected` — is **not** undone: the same
  flow now backs the new `hostAvailable: StateFlow<Boolean>` (desktop's `connectedConversationHostNow`)
  that gates every ask instead of restarting one. A gesture or a Retry press while `hostAvailable` is
  `false` sends nothing — logged as `event=history_ask_skipped reason=offline` for a gesture — and the
  oldest-end slot shows the new offline notice unless the walk has already reached the start of history;
  see
  [Thread screen § the oldest-end history demand](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-demand-777).
- **A refused cursor (`history.invalid_cursor`) resets to the newest page without asking (#1352),
  replacing #778's refused-cursor restart.** `cursorRefused()` clears `cursor` back to `""`, releases the
  outstanding-request slot and clears any stop, carrying `pagesLoaded` forward so a daemon that refuses
  every cursor cannot buy a fresh budget. Nothing asks when this fires: where #778 answered a refusal by
  re-asking immediately, #1352 instead waits for the reader's next qualifying gesture (or a Retry press,
  now gated on the ordinary `canAsk`/`canRetry` path like any other ask) to carry the empty cursor forward,
  per the ticket's "older history loads only on request" rule. A refusal of the newest-page ask (an already
  empty cursor) still has nothing to fall back to and settles as an ordinary failure instead. Since
  [#1354](#resuming-from-the-saved-position-1354), the same refusal also clears the **saved** position —
  `writeHistoryPosition(conversationId, null)` — so a stale cursor cannot keep steering both the next pull
  and the next open back to a cursor the daemon has already rejected once.
- **The `repositoryAvailable` collector in `ThreadViewModel.init` stays, but only for its #1311 side
  effect.** It still collects `repositoryAvailable.distinctUntilChanged().drop(1)`, but since #1352 that
  collector exists solely to call `closeLocalSendWindow("reconnect")` — it no longer restarts the walk.

The screen-side half — the gesture that replaced #777's oldest-row scroll trigger, and the one oldest-end
slot's five states including the new offline notice — is
[Thread screen § the oldest-end history demand](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-demand-777)
and
[§ the oldest-end history retry and restart](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-retry-and-restart-778).

## Resuming from the saved position (#1354)

[#1352](https://github.com/pyrycode/pyrycode-mobile/issues/1352) made every open start
`ThreadHistoryDemand()` from an empty cursor, so the first pull in a saved thread re-fetched the
newest page the reader already held. [#1354](https://github.com/pyrycode/pyrycode-mobile/issues/1354)
gives the walk a disk-backed resume point, mirroring desktop's received `coverage`
(`src/shared/chatHistory.ts`, `chatHistoryWriter.ts`, `historyPageBridge.ts`'s `requestOlderHistory`
in the sibling desktop checkout). The storage side — `HistoryPosition`, where it lives inside the
thread document, and the two-writer read-modify-write rule — is
[Conversation cache § The saved history position](conversation-cache.md#the-saved-history-position-1354);
this section covers only `ThreadHistoryDemand`/`ThreadViewModel`, which is where the design lives.

- **`ThreadHistoryDemand.restored(cursor, atStart)`** folds a saved position the same way `settled`
  folds a received page, but takes the two scalars rather than a `HistoryPosition` for the same
  reason `settled` takes `HistoryPage`'s two scalars: the file stays structurally unable to import a
  daemon-authored entry type. It sets `cursor` and, when `atStart` is true, `stoppedBy = AtStart` —
  the same terminal stop a live walk reaches by paging all the way back, so a restored "start of
  history" asks nothing and the oldest-end slot's offline notice stays hidden exactly as it already
  does for a walk that reached `AtStart` this visit (see [Thread screen § the oldest-end history
  demand](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-demand-777)).
  `pagesLoaded` is carried forward unchanged, so restoring a position never buys a fresh
  `MAX_HISTORY_PAGES` budget — the cap stays per screen-open (see § The walk that finally calls
  `requestHistory` above).
- **`ThreadViewModel.historySeed`**, a `Job` launched in `viewModelScope` right after `historyDemand`
  is constructed, reads `repository.readHistoryPosition(conversationId)` once and, if it finds one,
  folds it with `restored`. Reading asks nothing — `onDemandOlderHistory` is still the only path that
  calls `requestHistory`. For every in-memory repository (every unit test, and the demo
  `FakeConversationRepository`) the default `readHistoryPosition` returns without suspending, so the
  seed completes during construction and a test never has to await it explicitly.
- **A pull during the seed waits for it, rather than racing it.** `onDemandOlderHistory` checks
  `historySeed.isCompleted` first; if the read is still in flight, it launches a coroutine that joins
  the seed and re-enters, instead of either dropping the pull or letting it carry the opening empty
  cursor. Without this, a pull that landed before the disk read finished would re-fetch the newest
  page — the exact bug the ticket exists to fix — only intermittently, on whichever gesture happened
  to race the read. Extra pulls that land in the same window collapse through the ordinary `canAsk`
  check once the first of them claims the outstanding-request slot.
- **A received page's position is saved before the slot is released, inside `launchHistoryAsk`'s
  single in-flight ask.** `repository.writeHistoryPosition(conversationId, HistoryPosition(page.cursor,
  page.atStart))` runs right after `requestHistory` returns and before `historyDemand.update {
  it.settled(...) }`, so at most one position write is ever outstanding per thread, ordered by the
  same CAS claim that already serializes asks. A failed ask (any branch of `fetchHistoryPage`'s
  `catch`) returns before that write, so **a failed ask changes the saved position not at all** — the
  in-memory walk already preserves the cursor and page count on a failure (see § The walk above), and
  the saved copy now matches that same no-op. A refused cursor is the one branch that does write:
  `history.invalid_cursor` on a non-empty cursor calls `writeHistoryPosition(conversationId, null)`
  alongside `cursorRefused()`, so the next pull *and* the next open both start from the newest page —
  a stale saved cursor cannot otherwise outlive the daemon rejecting it once.
- **The position write can run ahead of the row write it describes (accepted, PR #1470 Revisions).**
  The page's rows reach the cache later, through `CachingConversationRepository.observeMessages`'s own
  collector-driven `writeThread` call, not through `launchHistoryAsk`. If that row write then fails,
  or the ViewModel is cleared before it runs, the saved position can point past rows the thread
  document does not yet hold, and the next open skips that page. Ordering the two writes would couple
  `ThreadViewModel` to the collector's independent write path for a window whose cost is one missing
  page, recoverable once the position is next cleared or the thread removed — accepted rather than
  fixed, unlike desktop, which saves coverage and rows together in one place.

See [Caching conversation repository § The saved history
position](caching-conversation-repository.md#the-saved-history-position-1354) for
`readHistoryPosition`/`writeHistoryPosition`'s forwarding on the wrapper, and that doc's § The merge
base for the gap-filling behavior this ticket narrowed: with no saved position a reconnect's history
walk could still fill a gap left by more than one page arriving while offline; once a position is
saved, the walk never returns to the newest page, so that gap now stays until the position clears.
