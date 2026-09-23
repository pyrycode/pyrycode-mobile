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

## The walk that finally calls `requestHistory` (#777)

[#645](../codebase/645.md) shipped the fold and left `requestHistory` with no caller. [#777](../codebase/777.md)
adds the caller, and it lives **beside `ThreadViewModel`**, not in this repository — the contract above is
unchanged, and this section exists because the demand's design leans on guarantees this document already
records.

- **`ThreadHistoryDemand`** (`ui/conversations/thread/ThreadHistoryDemand.kt`) is a pure value — cursor,
  pages-loaded count, in-flight flag, and a `HistoryWalkStop?` (`AtStart` / `NotAdvancing` / `PageCap` /
  `Failed`, `null` while still walking). `ThreadViewModel` asks with it in `init` (empty cursor = newest)
  and again each time the thread screen reports the reader has reached the oldest loaded row.
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
  screen-open); leaving and re-entering a thread starts a fresh walk.
- **A failed ask keeps the cursor and page count, clears in-flight, and stops asking — no retry, as
  shipped here.** `failed()` set `stoppedBy = Failed` without touching `cursor` or `pagesLoaded`, so every
  row already loaded and the walk's position survived a failure. `HistoryWalkStop` was an enum rather than
  a `Boolean` specifically so [#778](../codebase/778.md) could reopen `Failed` alone — `AtStart` /
  `NotAdvancing` / `PageCap` stayed terminal. Nothing here retried, restarted on reconnect, or persisted
  the cursor: the projections above are connection-scoped (`threadByConversation` starts empty on each
  connection), so a cursor surviving a reconnect would be a stale-cursor bug rather than a resume point.
  [#778](#the-retry-and-the-two-restarts-778) reopened exactly that gap.
- **The opening ask stays unconditional**, resolving the plan's second Open Question: `mergeHistoryRows`
  (the § above) already skips any row the thread holds, keyed on the renderer's own row key, so a first
  page overlapping the `backfill_since` replay ring is fully absorbed with no duplicate rows. Suppressing
  the ask when the ring already holds rows would buy nothing and would skip a genuinely needed page after
  a daemon restart empties the ring.

## The retry and the two restarts (#778)

[#777](../codebase/777.md) left `Failed` as a one-way door: a page that failed left every loaded row and
the cursor in place but stopped the walk forever, and a reconnect left the walk holding a cursor the new
connection's projections could never honour. [#778](../codebase/778.md) reopens exactly that door — the
design lives beside `ThreadViewModel`, not in this repository, and this section records only what it
depends on here.

- **`HistoryWalkStop.Failed` split into `RetryableFailure` and `PermanentFailure`**, on
  `RelayErrorException.retryable` — `requestHistory`'s own contract names `history.unavailable` as the
  **only** retryable code; the unknown-conversation `IllegalArgumentException` and the closed-session
  `IllegalStateException` this repository's KDoc documents both settle permanently. `AtStart` /
  `NotAdvancing` / `PageCap` are unchanged and stay terminal.
- **`ThreadHistoryDemand` still reads `requestHistory`'s return for `cursor` and `atStart` only** — the
  retry and both restarts ask through the same `requestHistory` call this document describes above, so a
  retried or restarted page folds into `threadByConversation` exactly the way any other page does, via
  `mergeHistoryPage`'s existing dedup-by-renderer-key. Nothing on the caller side needed a second fold, and
  `ThreadHistoryDemand.kt` still imports neither `HistoryPage` nor `HistoryEntry`.
- **A restart re-asks the newest page (`cursor = ""`) on the same page budget**, never a reset one — a
  restart that reset `MAX_HISTORY_PAGES` would be a bound with an off switch, and a flapping connection
  could otherwise launder a fresh budget on every reconnect. Two triggers restart it: `requestHistory`
  throwing `RelayErrorException("history.invalid_cursor")` for a non-empty cursor (the refused-cursor
  case), and an injected `repositoryAvailable: Flow<Boolean>` transitioning back to `true` **after** having
  left it — not the availability the thread opened on, since the flow hands every collector its current
  value on subscription. Both restarts carry a monotonic `walk` generation so a settle from a connection
  that has since been superseded is dropped rather than written into the restarted walk; this is what keeps
  a reconnect from writing a dead connection's cursor into the live one.
  [#861](https://github.com/pyrycode/pyrycode-mobile/issues/861) moved the second trigger off the
  socket-level `ConnectionStateSource`: for a relay host, `RelayConnectionSupervisor.observe()` reports
  `Connected` at socket-open, before the Noise handshake publishes the repository
  (`RelayRepositoryCoordinator.currentRepository` turns non-null only at `PumpState.Open`), so a restart
  keyed on that source re-asked a still-`null` repository and settled `PermanentFailure`
  (`HistoryWalkStop.DeadEnd`) with nothing left to recover it. `ThreadDestinationFactory.thread` now derives
  `repositoryAvailable` from `bundle.coordinator.currentRepository.map { it != null }` — the same StateFlow
  that backs the thread's `StableConversationRepository` — so the restart cannot fire ahead of the facade it
  feeds. The non-retryable `IllegalStateException(NOT_CONNECTED)` branch in `launchHistoryAsk` is unchanged;
  its recovery is this restart firing once the repository arrives, which now actually happens. The default
  `flowOf(true)` for every other construction site (including the demo path's always-available fake
  repository) keeps this restart inert there, as before.
- **A refusal of the newest-page ask (empty cursor) settles permanently instead of restarting** — this is
  what keeps the restart cycle structurally impossible rather than merely capped: every non-empty-cursor
  ask the repository ever receives from this walk originates from a reader scroll or a reader press on the
  retry affordance, never from a restart.

The screen-side half — the one oldest-end slot now showing loading, a retry affordance or a dead end — is
[Thread screen § the oldest-end history retry and restart](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-retry-and-restart-778).
