# Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store — history paging

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md); see that document for the rest.

## History pages fold into the same thread (#645)

[#623](../codebase/623.md) landed `requestHistory` returning a decoded `HistoryPage` of `HistoryEntry`
values (newest-first; each carrying a durable host/conversation-scoped `unsignedId`, the stored frame's wire `type`,
its still-undecoded `payload`, and a `ts`) and deliberately stopped there. [#645](../codebase/645.md) is
the fold its KDoc promised: `requestHistory` now also calls a private `mergeHistoryPage(conversationId,
page)`, which reduces the page and merges it into `threadByConversation[conversationId]` inside one
`MutableStateFlow.update {}` — a read, a merge and an assign that must stay one check-then-act, since
computing the merge outside the lambda would silently lose a concurrent live append on a CAS retry. The
page is still returned to the caller unchanged; the history-demand and cache sections below describe
the callers that now consume it.

**Page order, middle insertion and assistant overlap (#1786).** A page is no longer assumed to be older
than the thread. `reduceOrderedHistoryPage` decodes the page once, outside the update, and returns its rows
with authoritative `unsignedOrder` and `unsignedClaims` for rows and individual assistant deltas,
taken from the contextual fold, so a failed compaction divider gets its falling edge's id. Since #1909,
`mergeUnsignedHistoryRows` compares exact `ULong` positions across `Long.MAX_VALUE` through the unsigned
maximum. Inside the one `ProjectionState` update, it inserts missing rows and repairs eligible
provisional assistant deltas as described below; a repeat page changes nothing. Established held rows
retain their relative order, with separate exceptions for first durable assistant evidence and pending
own echoes with first modern delivery evidence. The merge never sorts the whole thread by timestamp. A missing
row goes after its nearest shared predecessor or before its nearest shared successor. Held rows with known log ids bound that slot, so a
reused message id or a malformed entry cannot pull a row past a known position. With no shared row, log ids
and then timestamps choose the slot. The unsigned placement map lives in `ProjectionState.historyOrder`
and is connection-local, while the received ids themselves are durable. The source repository supplies host scope and each observation
supplies conversation scope; replacing a projection cannot lend placement evidence to another scope.
`remove` drops placement evidence. Live-only content, renderer keys and replay event ids supply no
durable claims. A boundary that fills a pending divider in place carries its unsigned position unchanged
to its new identity. `ThreadSnapshot` publishes rows, suppression and `unsignedHistoryOrder` from this
same generation, deriving signed `historyOrder` only for representable positions.

**First durable assistant evidence (#1913).** A held `(turnId, seq)` delta is provisional until its
first durable history position arrives. If that position conflicts with a known held durable neighbour,
the merge extracts the delta even from a folded segment and reinserts its held version within exact
unsigned bounds, including across a user or tool separator. First evidence without such a conflict
keeps its slot. Previously durable deltas and unevidenced live-only atoms remain anchors in their
relative order; replay or a conflicting later claim cannot grant another repair. The projection derives
eligibility from the page's `unsignedOrder` minus prior scoped claims inside the same CAS update that
publishes rows and accumulated claims. Prior values win conflicts, and a CAS retry recalculates eligibility.
Timestamps, renderer keys and replay event ids never establish claims; sequence numbers identify deltas
and allow adjacent rejoining, but cannot substitute for durable positions. Signed and cache merge entry
points supply no first-evidence relocation permission. This assistant exception is independent of the
pending-own-user-echo first-delivery rule below.

Fresh admitted assistant deltas also obey durable bounds: a provisional sequence or shared-page
neighbour cannot override them. Probe sparse held sequences as well as relocation alone. With held
`user(id 4), ac(seq 0,2)`, an overlap admitting `b(seq 1/id 2)` while first evidencing `c(seq 2/id 3)`
must produce `bc, user, a`; first evidence for `a(id 1)` then produces `abc, user`. Checking only repaired
held atoms misses a fresh delta escaping its bound. Partial-page fixtures must retain the complete
page's durable ids; renumbering cuts tests conflicting claims rather than consistent arrival permutations.

Assistant text merges per delta, identified by `(turnId, seq)`. Segments split into single-delta pieces by
their recorded lengths; missing sequences enter and eligible held atoms relocate without replacing their
text, timestamp, attribution or streaming state. Adjacent pieces of
one turn then join again, so text stays on the correct side of a tool or user row, and ended turns stay
settled through the existing post-merge pass. Held text wins any overlap. A legacy whole-turn row without
sequence records suppresses only text it demonstrably contains. Renderer keys stay unique without dropping
text: surviving displayed/held claims precede newcomers, which take unused aliases or suffixes.
See [renderer ownership](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350).

**Signed consumers must retain omitted-content uncertainty (#1909).** The positive unsigned
[domain identity](data-model.md#historyentry--received-durable-identity-1909) is authoritative. Signed
construction remains compatible through `Long.MAX_VALUE`, while `HistoryEntry.id` is null above it.
Reducer `order` and snapshot `historyOrder` omit upper-range positions; reducer `claims` omits a whole
claim set when any member is unrepresentable. The signed `mergeOrderedHistoryRows` entry point retains
positive lower-range behavior. No compatibility view wraps, clamps or substitutes a different id.

Since #1910 `HistoryCoverage.received` delegates to `receivedUnsigned`, retaining the entire page's
exact unsigned claims, including mixed-range pages. Its signed projection sets `unknown=true` with
sticky `unsignedIncomplete=true` when upper-range evidence arrives. Merely filtering ids is
insufficient: separately saved `atStart` can otherwise restore omitted signed content as complete.
Later terminal pages cannot clear this signed-view uncertainty. A terminal backwards response
retains the request cursor and enters a demandable `PermanentFailure` instead of `AtStart`; a side or
gap response reopens a previously complete walk. Nonterminal cursor progression remains usable.

Cache save/read and ViewModel seeding also refuse `atStart` while flagged. Preserve the flag and usable
cursor even when non-rendering state frames or cache-excluded unrecognized rows leave no renderer rows
or signed spans: an empty cache cannot prove completeness. Regression coverage must reopen a fresh
file cache and ViewModel, receive a later signed terminal page and demand older history again; testing
`received` alone or keeping one cacheable row misses this restore failure. Unsigned persistence and
restored order are described under [saved position](#resuming-from-the-saved-position-1354).
The completed [unsigned gap path (#1911)](#resuming-from-the-saved-position-1354) consumes authoritative
coverage independently of these signed guards. Conservative completeness guards establish
no seen state: coverage proves receipt, never sight.

**Read evidence from history pages (#1912).** `reduceOrderedHistoryPage` returns, beside
rows, each durable id's claim on the row it produced and a fact per entry: visible,
understood nonvisual or unknown. Nonvisual means the entry decoded through its existing
payload decoder and is deliberately drawn without a row, such as state frames, info
banners, model and command menus, MCP and usage reports and background-task lifecycle.
A malformed or unsupported entry is unknown and blocks any read checkpoint past it, even
when later visible content exists. A page also clears a live unidentified barrier whose
type, canonical timestamp and payload it matches. A merge or re-delivery never turns an
earlier barrier into seen content or lowers a claim. The checkpoint rules themselves are
under [daemon conversation read marks](remote-conversation-repository.md#daemon-conversation-read-marks).

**History establishes modern queued delivery (#1655).** A stored user `message` with a valid
`queued_msg_id` can arrive with its answering delta 0 before the first live push, even before
the waiting turn's live end. `mergeHistoryPage` decodes this evidence through `MessagePayloadDto`
and consumes that exact entry in the **requested conversation**, independently of the payload's
routing id. Omitted/malformed identity and non-user rows do not establish modern delivery.
Queue-entry consumption does not broaden the renderer's message-id deduplication.

For own echoes, only first-delivery, pending, minted held rows have provisional positions.
Remove them from the receiving placement list, substitute their held objects into incoming rows
**before allocation**, and supply original held rows as renderer owners (#1941). Restoring objects
after allocation can duplicate a newcomer's key. Capture original delivery timestamps separately
by logical identity: incoming insertion and the run minimum use those clocks, not retained tap
clocks. With multiple provisional rows, a substituted minimum can also move an unrelated fresh
neighbour. Held receiver clocks and assistant first-evidence relocation stay unchanged. Content,
attachment hints and original timestamp survive; idle, foreign/non-user and delivered rows keep
positions. The two `HistoryAliasCorrelationTest.unchangedOrderInvariant_*` probes cover distinct
send/delivery clocks, two echoes with a newcomer before/between/after, and original/empty replay.

Commit placement, exact entry consumption, first-delivery row identity and cleared queued/
suppressed/reserved eligibility in the same CAS update. Otherwise the first replayed live push
can move a history-established echo after its answering text, and the next delta starts another
segment despite sequence deduplication. Test overlapping pages containing both the delivered row
and answering delta 0 before and after the first push, and before the live end, asserting after
each event. A user-only page does not expose this failure. See
[queue placement and recorded evidence](queued-backlog.md#verification-evidence) and
[assistant segments](remote-conversation-repository-assistant-reply-segments.md).

History's `endedTurns` evidence settles late assistant rows; it does not consume the legacy
fallback's live reservation boundary. `ProjectionState.liveEndedTurns` records live ends
atomically with rows and echo metadata, so a history end cannot suppress the first live end's
reservation and a repeated live end cannot reserve a newer pending echo. Both are connection-local.

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

**Typed reconciliation identity and renderer ownership (#1979).** Durable `HistoryEntry.unsignedId`
orders received content; it never joins to live `Envelope.eventId`. Assistant deltas join by
`(turnId, seq)`, ordinary messages by `ordinaryId` (`reconciliationId ?: id`), boundaries by
`(previousSessionId, newSessionId, occurredAt)`, and unrecognized rows by their stable derived id
`"history-${entry.unsignedId}"`. Ordinary message identity remains role-agnostic. These identities
are independent of renderer aliases and structural equality. The boundary triple prevents repeated
idle evictions of the same session pair from being dropped (#775).

A cached assistant segment and a distinct ordinary user row can share a renderer id and identical
text without being duplicates. Both are admitted, in either direction, before unique renderer keys
are allocated. Same-identity replay adds neither rows nor delta text; demonstrated legacy whole-turn
reconciliation remains authoritative. Surviving displayed owners reserve their keys before receiver
claims and newcomers; a user alias preserves ordinary identity/content for original-id replay.
Alias allocation cannot decide admission or reorder held content. See [cache ownership and collision
regressions](caching-conversation-repository.md#how-the-restore-merges-with-live-rows) and
[ordinary identity](data-model.md#message).

Ordinary missing rows enter through the ordered merge described above; the receiving thread is
never globally re-sorted. First modern deliveries of pending own echoes use that section's
explicit provisional-position exception. Duplicate ordinary rows keep
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

`ThreadRowAnchors` uses `mergeIdentity` for ordinary lookup, so a same-key assistant segment
cannot stand in for an aliased user. Lifecycle input retains each original segment's full range of
surviving neighbours: leading evidence uses the minimum retained position and trailing evidence the
maximum. Splitting that input into atoms can flush a leading marker against a later row before an
earlier sequence is considered. Attach demonstrated legacy sequence records through `ordinaryId`
to the original assistant rows before placement, preserving their segment boundaries.

Choose anchors **after** removing segments superseded by whole-turn rows: a suffix of a retained
whole turn must resolve to that whole-turn row. Anchoring a finish to a temporary suffix and removing
that suffix later can strand the finish at the front. The lifecycle regressions cover both history and
[reconnect merges](caching-conversation-repository.md#how-the-restore-merges-with-live-rows), whole-turn/
segment overlap in both directions, retained-launch variants, replay and older-page prepend. Ordinary
assistant delta anchoring and segment joining treat markers as transparent; hidden evidence cannot
split text or create visible rows. Descriptions and summaries remain inert and unlogged.

The direct collision, ordinary-anchor and combined-range regressions in `HistoryMessageIdentityTest`
cover both unsigned merge lanes and replay; the cache section names each method and records the
full-suite counts. The enabled
`CoalescedThreadWritesTest.identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect`
and `HistoryMessageIdentityTest.reconnectInvariant_unsignedCacheCollisionSurvivesFreshRestoreAndReplay`
cover empty disconnect, pending persistence and fresh-file identity/key restoration.
`AssistantParentAttributionTest.collidingTurnKeys_doNotOverwriteHeldLaneAttributionOrGainAuthority`
requires lossless content, held keys, typed replay and lane-specific parents in both directions;
users never gain assistant authority. It and the unchanged
`BackgroundTaskLifecycleTest.wholeTurnArrivingOverRetainedSegments_anchorsFinishAfterWholeTurnInHistoryAndReconnect`
also executed/passed in the full run (12 attribution and 31 lifecycle methods, 0 failed/errors/skipped),
as recorded in [the verifier evidence](https://github.com/pyrycode/pyrycode-mobile/pull/2023#issuecomment-6090873234).

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

See [Remote conversation repository — history walk and retry](remote-conversation-repository-history-walk.md) for the backwards walk, failure recovery and retired reconnect restarts.

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

**Coverage is received `HistoryEntry.unsignedId` spans, including entries that render no row (#1910).**
`unsignedSpans`, `unsignedGaps`, row/delta order and producing-entry sets, page-edge cursors and
walk anchors preserve exact positive unsigned positions through `ULong.MAX_VALUE`. IDs are
host/conversation-scoped durable daemon ids; row identities, timestamps, list latest ids and
live/ring ids establish no span. `unsignedHighWater` is the maximum covered id before the newest
ask. Overlap and adjacency coalesce without incrementing the maximum;
a hole exists only between received spans. Overlap elsewhere preserves unresolved holes. A known
gap closes only when received coverage continuously joins its older anchor. If a page splits a
hole, each resulting hole keeps an anchor in its immediately older merged span, so both remain
targetable rather than inheriting the same old anchor.

Positive signed cache documents retain rows and usable coverage under the unchanged serialized
field names. Signed construction and lower-range readers remain source-compatible: spans expose
only their representable portion and gaps/cursor anchors omit unrepresentable ids. Upper-range
evidence sets sticky `unsignedIncomplete` so signed completeness stays conservatively unknown and
cannot restore `AtStart`. Authoritative `unsignedUnknown` can still close on `at_start`; the compatibility
flag does not erase unsigned coverage or restored order. Malformed optional metadata discards the
saved position independently of readable retained rows, which restore as legacy unknown.
Restoration, position writes and page receipt send no read command and initiate no history fetch.

**Restored gap targeting is unsigned end to end (#1911).** The ViewModel projects markers from
`unsignedGaps`, `unsignedUnknownEdge` and `unsignedPositions`, preserving the exact anchor across
`Long.MAX_VALUE` through `ULong.MAX_VALUE`. Only authoritative `unsignedUnknown` supplies the zero
anchor; sticky signed uncertainty does not create a durable marker. Reader selection sends that
same unsigned anchor to `cursorForUnsigned`; page coverage and cursor refusal use `receivedUnsigned`
and `refusedUnsigned`. Restoring rows/coverage or receiving a page creates no older demand. The
[#1842 readiness and settlement handoff](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
still drains only already-counted newest arrivals.

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
marker can sit at the verified span's older edge, subject to displayed-row eligibility, unless
that page reports `at_start`. Pulls move the
edge backwards. Matching a legacy whole-turn row or overlapping a verified span never closes
ordinary legacy unknown coverage without an older durable anchor: only `at_start`, including an empty
terminal page, does. Sticky signed-view uncertainty cannot be closed by terminal pages. Arbitrary
legacy holes cannot be inferred before received pages establish spans.
An empty uncovered cache ignores old cursor/stop metadata unless `unsignedIncomplete` is set; flagged
coverage and its usable cursor survive even with no retained rows or signed spans.

**Visible markers are a projection of unresolved coverage (#1917).** An internal marker requires
delivered content in both immediately adjacent covered spans and targets the first displayed row
in the newer span. Lifecycle evidence and queued echoes cannot establish either occupancy or targets;
queue changes reproject placement. Nonempty displayed history shows at most one marker above its
oldest row, selecting the nearest unresolved edge at or before its oldest durable position and
preferring a known gap over unknown coverage at a shared edge. Empty eligible history shows none.
Hidden gaps retain their exact unsigned anchors and opaque cursors across restore and reconnect;
marker disappearance does not join spans or certify missing content. Cache schema, retention and
persistence policy are unchanged; the suspected cache-retention fragmentation source is unconfirmed.

Assistant deltas on opposite sides of a hole use display-only fragments so an eligible marker fits
between them without changing retained repository rows. Fragment boundaries use unsigned delta order
and adjacent received-span endpoints,
not the retained demand anchor. After partial fill that anchor can lie inside the extended older
span: deltas at `A` and `A+4`, followed by `A+1`, must display `A,A+1` / marker / `A+4` while still
targeting `A`. Comparing against `A` would rejoin held text across the unresolved hole and put the
marker before the whole reply; serialization would preserve that error. The partial-fill and
serialized-restoration ViewModel regressions assert fragment content, exact placement and unchanged
opaque cursor identity across the signed boundary, upper range and maximum boundary. Projected
markers sort by durable newer edge; the oldest-edge rule suppresses unknown coverage when a known
gap shares that edge. See [reader targeting](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777)
for the first-crossed gesture rule.

The repository still performs the one atomic row merge; coverage inspection never renders a page
again. History fills dedupe held live and legacy content through #1786's reconciliation. Both cache
merge paths need restored and live unsigned durable ordering: shared-neighbour placement alone misplaces a
disjoint older-gap page, especially with equal timestamps. `ThreadSnapshot` supplies rows,
suppression and `unsignedHistoryOrder` from the same projection generation. See [cache reconciliation](caching-conversation-repository.md#how-the-restore-merges-with-live-rows).

**Rows must reach disk before state can certify them.** The caching wrapper now writes the
reconciled cacheable rows before coverage/position, replacing #1354's accepted window where
position could reach disk before rows. Failed row writes cannot advance claims; interruption between writes leaves older,
conservative state. Trimming and changed/missing retained rows invalidate coverage, and the later
state write must retain the trim's backwards cursor/stop reset. See [the two file writers](conversation-cache-layout.md#the-thread-documents-two-writers-1354)
and [the wrapper's saved position](caching-conversation-repository.md#the-saved-history-position-1354).
[#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833) retains ownership of the rung-3 live
durable-gap operator-flow proof; #1911 adds no live scenario. It supplies the device proof these JVM
tests cannot. After the owned daemon restarts with its durable home kept and its replay ring
emptied, both the real-Claude offline-read method and its scripted twin show one newest ask without
a gesture, a remaining gap marker, one older page per physical reader pull, and every missed post
and the completed reply drawn once in order. The external force-stop proof shows a post made while
the app process was dead arriving once after relaunch, without scrolling, with the saved cache
intact. Counts and revisions are in the [#1833
evidence](../../e2e-interactive-stream.md#verification-status).
