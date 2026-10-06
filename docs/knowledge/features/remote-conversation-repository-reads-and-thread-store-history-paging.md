# Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store — history paging

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md); see that document for the rest.

## History pages fold into the same thread (#645)

[#623](../codebase/623.md) landed `requestHistory` returning a decoded `HistoryPage` of `HistoryEntry`
values (newest-first; each carrying a durable per-conversation log `id`, the stored frame's wire `type`,
its still-undecoded `payload`, and a `ts`) and deliberately stopped there. [#645](../codebase/645.md) is
the fold its KDoc promised: `requestHistory` now also calls a private `mergeHistoryPage(conversationId,
page)`, which reduces the page and merges it into `threadByConversation[conversationId]` inside one
`MutableStateFlow.update {}` — a read, a merge and an assign that must stay one check-then-act, since
computing the merge outside the lambda would silently lose a concurrent live append on a CAS retry. The
page is still returned to the caller unchanged; the history-demand and cache sections below describe
the callers that now consume it.

**Page order, middle insertion and assistant overlap (#1786).** A page is no longer assumed to be older
than the thread. `reduceOrderedHistoryPage` decodes the page once, outside the update, and returns its rows
with each row's daemon log id, taken from the contextual fold, so a failed compaction divider gets its
falling edge's id. Inside the one `ProjectionState` update, `mergeOrderedHistoryRows` inserts only rows the
thread lacks, so an older, newer or middle page lands in daemon order and a repeat page changes nothing.
Held rows never move and are never sorted by timestamp. A missing row goes after its nearest shared
predecessor or before its nearest shared successor. Held rows with known log ids bound that slot, so a
reused message id or a malformed entry cannot pull a row past a known position. With no shared row, log ids
and then timestamps choose the slot. The log ids live in `ProjectionState.historyOrder`, are connection-local,
are never compared with live event ids, and are dropped by `remove`. A boundary that fills a pending divider
in place carries the divider's log id to its new identity.

Assistant text merges per delta, identified by `(turnId, seq)`. Segments split into single-delta pieces by
their recorded lengths; only missing sequences enter, before, between or after held ones. Adjacent pieces of
one turn then join again, so text stays on the correct side of a tool or user row, and ended turns stay
settled through the existing post-merge pass. Held text wins any overlap. A legacy whole-turn row without
sequence records suppresses only text it demonstrably contains. Renderer keys stay unique without dropping
text: ordinary ids claim keys first, then each turn's opener, then other segments, and a segment whose key a
different identity holds takes a `~n` suffix.

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
For ordinary rows, the merge is a **prepend, never a re-sort** (never a timestamp sort — the thread
is arrival-order by deliberate choice, see `applyToolUse`'s KDoc above). Duplicate ordinary rows keep
the held state, apart from missing attachment hints: in the narrow ask-versus-answer overlap window
the protocol names, the live lane still owns the newer state.

**Invisible background-task positions (#1782).** `ThreadItem.BackgroundTaskLifecycle` retains
launch and finish evidence alongside ordinary entries. Within the requested conversation its identity
is `(taskId, terminal != null)`: a null terminal denotes launch, a non-null `BackgroundTaskUpdate`
denotes finish. Neither pagination indexes, history/replay ids nor timestamps define that identity.
Live and history use the same pure folds under the negotiated `interactive` gate; newest-first pages
reduce in reverse order. Empty task ids add no marker. Any non-empty update status is terminal,
including an unknown status; mid-life updates, progress and rosters add no position.

A finish may precede its launch or Agent/Task tool row. Later launch evidence fills unknown tool-call
id, description, task type and truncation report by task id without moving the retained finish or
replacing first-seen content. The tool-call id joins a tool row whenever it loads; existing tool-parent
links stay intact. Overlap retains one marker per task/phase, fills missing launch fields before
skipping twins, and completes launch-to-finish joins across page seams. An empty/replacing panel roster
cannot remove this evidence or manufacture completion; see [application payloads](mobile-protocol-v2-wire-layer-application-payloads.md#background-task-payloads-1782).

Fresh evidence needs the page's ordinary-row neighbours even when overlap discards those rows.
`withHistoryLifecyclePositions` inserts after the preceding retained neighbour; leading evidence waits
for the first overlapping neighbour and goes before it, or at the front if none exists. Insertion slots
advance monotonically, so older ordinary backfill cannot pull later evidence across a retained anchor.
Rows sharing a slot keep page order, and retained markers keep their positions relative to existing
rows through replay and older-page prepend. This preserves terminal-before-start arrival rather than
sorting by phase. Anchor lookup uses typed row identities and assistant `(turnId, seq)` overlap, never
text or timestamps. Differently keyed or partly discarded segments still represent retained neighbours.

Choose anchors **after** removing segments superseded by whole-turn rows: a suffix of a retained
whole turn must resolve to that whole-turn row. Anchoring a finish to a temporary suffix and removing
that suffix later can strand the finish at the front. The lifecycle regressions cover both history and
[reconnect merges](caching-conversation-repository.md#how-the-restore-merges-with-live-rows), whole-turn/
segment overlap in both directions, retained-launch variants, replay and older-page prepend. Ordinary
assistant delta anchoring and segment joining treat markers as transparent; hidden evidence cannot
split text or create visible rows. Descriptions and summaries remain inert and unlogged.

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

Split into [Remote conversation repository — assistant reply
segments](remote-conversation-repository-assistant-reply-segments.md) on 2026-10-03 when this document
passed the size cap; every subsection's heading and anchor moved unchanged. Covers the segment key and its
two uniqueness guards, the seam join across a page or cache boundary, the `(turnId, seq)` dedupe for a
mid-turn local echo, the cache's segment record, and the #1419 `turn_end`-before-its-rows race.

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
  [Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
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
  [Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777).
- **A refused cursor (`history.invalid_cursor`) resets to the newest page without asking (#1352),
  replacing #778's refused-cursor restart.** `cursorRefused()` clears `cursor` back to `""`, releases the
  outstanding-request slot and clears any stop, carrying `pagesLoaded` forward so a daemon that refuses
  every cursor cannot buy a fresh budget. Nothing asks when this fires: where #778 answered a refusal by
  re-asking immediately, #1352 instead waits for the reader's next qualifying gesture (or a Retry press,
  now gated on the ordinary `canAsk`/`canRetry` path like any other ask) to carry the empty cursor forward,
  per the ticket's "older history loads only on request" rule. A refusal of the newest-page ask (an already
  empty cursor) still has nothing to fall back to and settles as an ordinary failure instead. Since
  [#1354](#resuming-from-the-saved-position-1354), the same refusal also resets the **saved backwards** position, so a stale cursor
  cannot steer
  the next pull or open. Since #1832 it retains durable coverage and gap cursors; only a position
  without coverage uses `writeHistoryPosition(conversationId, null)`.
- **The `repositoryAvailable` collector in `ThreadViewModel.init` stays, but only for its #1311 side
  effect.** It still collects `repositoryAvailable.distinctUntilChanged().drop(1)`, but since #1352 that
  collector exists solely to call `closeLocalSendWindow("reconnect")` — it no longer restarts the walk.

The screen-side half — the gesture that replaced #777's oldest-row scroll trigger, and the one oldest-end
slot's five states including the new offline notice — is
[Thread screen § the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
and
[§ the oldest-end history retry and restart](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-retry-and-restart-778).

## Resuming from the saved position (#1354)

`HistoryPosition(cursor, atStart, coverage = null)` keeps the independent backwards walk's
opaque cursor and stop state. Since [#1832](../../specs/architecture/1832-durable-history-gaps.md),
it also carries received durable entry coverage, which the ViewModel restores once through
`historySeed`. A reader pull during that seed waits for it; reading the seed originates no ask.
The page count remains per screen-open rather than being restored as a fresh budget.

**Newest asks retain evidence and wait for the request slot.** #1572 introduced one newest ask
at open and on each host-availability arrival while the thread remains open. The collector waits
for `historySeed` and repository availability. #1832 counts pending arrivals rather than dropping
an arrival behind an older request: completion releases the slot and drains deferred newest work.
Newest, ordinary older and gap requests share that slot. A newest failure consumes its arrival
without retry; clearing the ViewModel cancels requests and pending work. Page arrival or marker
visibility never originates another request.

For an empty-cursor walk the newest page also seeds the ordinary backwards cursor/stop. Otherwise
it is a side ask: its durable ids and page-edge cursor update coverage, but its cursor and `atStart`
do not replace the independent backwards position. A previously verified empty terminal page can
seed a fresh backwards walk when a later availability page brings entries. Saved `AtStart` blocks
ordinary oldest-end demand, never demand for an unresolved gap. The shared `inFlight` flag still
shows the oldest-end Loading row during a side ask, including on a stopped backwards walk.

**Coverage is received `HistoryEntry.id` spans, including entries that render no row.** IDs are
host/conversation-scoped durable daemon ids; row identities, timestamps and live/ring ids establish
no span. High-water is the maximum covered id before the newest ask. Overlap and adjacency coalesce;
a hole exists only between received spans. Overlap elsewhere preserves unresolved holes. A known
gap closes only when received coverage continuously joins its older anchor. If a page splits a
hole, each resulting hole keeps an anchor in its immediately older merged span, so both remain
targetable rather than inheriting the same old anchor.

Each gap retains an opaque walk cursor, starting from the page immediately above it. A cursorless
hole uses the nearest stored page-edge cursor above it; cursors are never constructed from entry
ids. Each reader pull asks at most one page, even when it rereads covered content. The returned
cursor advances that targeted walk while ordinary backwards cursor/stop remain unchanged. A
refused gap cursor leaves the marker in place and invalidates that cursor; the next gesture uses
the latest usable newest-page cursor, or the empty cursor if none is usable. There is no automatic
retry, full catch-up loop, forward read or caught-up signal. Ordinary backwards cursor refusal
resets only that walk when coverage exists, preserving gaps and their cursors for later demand.

**Legacy rows prove identity, not completeness.** A nonempty cache without coverage is unknown,
with or without saved `atStart`. Its rows remain readable. After the newest page, one conservative
marker sits at the verified span's older edge unless that page reports `at_start`. Pulls move the
edge backwards. Matching a legacy whole-turn row or overlapping a verified span never closes
unknown coverage without an older durable anchor: only `at_start`, including an empty terminal
page, does. Arbitrary legacy holes cannot be inferred before received pages establish spans.
An empty uncovered cache ignores old cursor/stop metadata and gets no conservative marker.

Markers sit between held older and newer content, before their newer row. A non-rendering newer
span can leave a standalone marker at the newest content edge. Assistant deltas on opposite sides
of a hole use display-only fragments so the marker fits between them without changing retained
repository rows. Known and unknown markers sharing a row/edge sort by their durable newer edge,
keeping unknown coverage chronologically older. See [reader targeting](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
for the first-crossed gesture rule.

The repository still performs the one atomic row merge; coverage inspection never renders a page
again. History fills dedupe held live and legacy content through #1786's reconciliation. Both cache
merge paths need restored and live durable ordering: shared-neighbour placement alone misplaces a
disjoint older-gap page, especially with equal timestamps. `ThreadSnapshot` supplies rows,
suppression and durable order from the same projection generation. See [cache reconciliation](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

**Rows must reach disk before state can certify them.** The caching wrapper now writes the
reconciled cacheable rows before coverage/position, replacing #1354's accepted window where
position could reach disk before rows. Failed row writes cannot advance claims; interruption between writes leaves older,
conservative state. Trimming and changed/missing retained rows invalidate coverage, and the later
state write must retain the trim's backwards cursor/stop reset. See [the two file writers](conversation-cache.md#the-thread-documents-two-writers-1354)
and [the wrapper's saved position](caching-conversation-repository.md#the-saved-history-position-1354).
Independent live, deterministic multi-page-gap and external force-stop proof belongs to
[#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833); #1832 does not establish those results.
