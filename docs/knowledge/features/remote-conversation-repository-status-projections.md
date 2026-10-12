# Remote repository — status projections

Part of the [remote repository](remote-conversation-repository.md). The status section
was split for #2084 to keep the parent below the overview size cap.

## Status projections: one file per status event

Since 2026-09-22 the status events the thread observes each live in their own small internal class
beside the repository: `StallProjection` (#395), `QueueProjection` (#460), `ApiRetryProjection` (#593),
`SessionErrorProjection` (#1677),
`CompactingProjection` (#596), `ThinkingProgressProjection` (#801), `UsageLimitProjection` (#802),
`AnnouncedModelProjection` (#890), `SessionFactsProjection` (#890) and `McpStatusProjection` (#1343).
`McpStatusProjection` is the one member of this family that also owns three outbound sends and their own
refusal-correlation ledger — the `ModelMenuProjection`/`request_model_list` shape, not the simpler
read-only siblings — and it is deliberately **not** promoted into
[`HostReadings`](relay-repository-coordinator.md): a reconnect starts the MCP reading from nothing on
purpose, so #1345's failure notice knows to ask again. See [Remote conversation repository — the MCP
status reading and the reconnect/toggle sends](remote-conversation-repository-mcp-status.md).
Each holds the state, the decoder and the cold read that used to sit in `RemoteConversationRepository`,
under the same names, so the per-event sections in the
[thread-observables document](remote-conversation-repository-thread-observables.md) and the
[live-stream document's `model_announced` / `session_facts` section](remote-conversation-repository-live-stream-and-modals.md#model_announced--session_facts--the-announced-model-and-session-facts-readings-890)
still describe them. The split initially changed only ownership. The supplied authoritative route adds the admission and retirement rules below; the existing event behavior remains the legacy route. `UsageLimitProjection`'s
clearing edge is self-contained — a benign `status` frame clears its own entry inside `apply`, so it
needs no hook on another event's arm the way the stall/thinking-progress clears do.
On the legacy route, `AnnouncedModelProjection` and `SessionFactsProjection` are each cleared only by the
`session_transition` arm, the same `ResettingProjection` shape, since neither `model_announced` nor
`session_facts` carries a falling edge of its own.

`AttachmentOfferProjection` (#898) holds the files the daemon offered per conversation on this
connection — state, decoder and read in the same one-class-per-event shape — but sits outside this
gated family: its `onInbound` arm calls `apply(envelope)` **unconditionally**, with no
`CAPABILITY_INTERACTIVE in negotiatedCapabilities()` check. The protocol delivers `attachment_offered`
to every attached client, outside the interactive family, the same posture as the upload leg's
`attachment_stored` (see `AttachmentUploadTransfer` below); the daemon routes nothing, so filtering on
`conversation_id` happens inside `observe`, as every other arm's projection does. First arrival wins for
a repeated attachment id. See [Conversation repository → `AttachmentOffer`](conversation-repository.md)
for the domain type, the id-shape validation and the code-point-level display-name cleaning, and its
SECURITY note.

**A first-seen offer is also a thread row (#983).** `AttachmentOfferProjection` takes the repository's
`threadProjection` as a constructor parameter — the one dependency between two status-family
projections — and, only on the first sighting of an attachment id (decided inside the same
`MutableStateFlow.update` that records the offer, not after it), calls
`threadProjection.appendMessages` with an assistant-side row built by `attachmentOfferRow`: id
`"attachment-offer-<attachmentId>"`, empty text, one `MessageAttachment` carrying the offer's cleaned
name and no MIME hint (the wire sends none). That id is derived from the validated attachment id, not a
per-process counter, so the same offer arriving again on another connection, or the row the disk cache
restores, joins on `message_id` through `ThreadProjection.appendMessages` / `mergeCachedRows` rather than
duplicating; the `attachment-offer-` prefix keeps it out of the wire's UUID `message_id`/`turn_id`/
`tool_use_id` namespace. A repeat offer on the same connection adds no row. Because the constructor now
depends on `threadProjection`, `attachmentOfferProjection`'s field declaration in
`RemoteConversationRepository` had to move below `threadProjection`'s — Kotlin initializes properties in
declaration order, so the dependency's declaration position is load-bearing, not stylistic. See [Caching
conversation repository § How the restore merges with live
rows](caching-conversation-repository.md#how-the-restore-merges-with-live-rows) for why a cache-only row
like this one needs its own merge to keep its place across a reconnect or a cold restore.

On the legacy route the repository keeps three things. Its `onInbound` arm checks the negotiated `interactive` capability
and calls the projection's `apply(envelope)`. Its `observe…` override returns the projection's
`observe(conversationId)`. And a clear that one event causes in another stays in the arm that causes
it, through the projection's `clear`: every decoded live-session event clears that conversation's
stall, and a `turn_end` or `session_transition` clears its thinking-progress reading.
Legacy session errors clear only on send entry, a decoded recognized non-idle `turn_state`,
or connection teardown; see [conversation session errors](remote-conversation-repository-state-errors-and-handoff.md#conversation-session-errors-1677)
for validation, observation and clearing rules.
`dropQueuedMessage` reads `QueueProjection.current` to resolve the echo id before it sends.

`ReplySuggestionProjection` (#1865) holds live/reconciled next-reply state, with one
immutable-map entry per `(conversationId, sessionId)`. Validation completes before the
atomic revision comparison and replacement: only a strictly higher revision replaces an
entry, and a clear stays in the map so stale sets cannot revive it. Unrelated pairs do not
re-emit the observed pair. Separate per-host remote instances isolate even identical pairs.
The inbound `reply_suggestion` arm requires negotiated `interactive`; its
[decoder checks presence and exact JSON kinds](mobile-protocol-v2-wire-layer.md#reply-suggestion-validation-1865).
Malformed input changes neither text nor watermark and leaves the consumer alive.

This projection stays outside `HostReadings` and persistent cache. Each fresh handshake
constructs an empty projection, allowing lower revisions after a daemon restart; inbound
consumer termination also resets text and watermarks. The cache wrapper forwards the
observation by interface delegation without storing it. Suggestions bypass replay-cursor
recording even when malformed, reject envelopes carrying `event_id`, and are never
restored from history. Applying one calls no thread, live-session-event or turn-state
operation. Diagnostics contain only static outcomes (accepted, stale, malformed, reset),
with no text, identities, payloads or parser exceptions.

### Supplied session ownership and family retirement (#2084)

With a nonempty authenticated `authoritativeHostId` and both `thread` and
`interactive` negotiated, `onInbound` admits live families through
`SessionLiveAdmission` before projection writes or correlated waiter completion.
Envelope conversation identity is mandatory; invalid supplied frames return without
legacy fallback. See [wire admission](mobile-protocol-v2-wire-layer.md#session-scoped-live-admission-2084)
for exact JSON-kind and settings-target validation.

Authoritative conversation summaries establish the session binding without implying
that a child is running. An ordered family clear can reconcile a new binding; a fresh
reading cannot move it. Opaque session ids are compared only for equality. Missing
provenance cannot borrow a stored binding or replace a tagged reading, and explicit
null means no session only after reconciliation. Producing bindings and retired named
session fences live beside `HostReadings`, surviving reconnect for host-held readings;
family clear/fresh acceptance stays connection-local. Thus a new connection cannot
replace held tagged facts with untagged or retired readings.

Replacement selectively retires prompts/questions, turn/stall/retry/compaction,
thinking/tool/task progress, resetting, context usage, announced model, session facts,
settings, MCP status, slash/model menus, suggestions and session errors. A family
clear resets only its own reading, creates no row and submits no answer. Once fresh
state is accepted, repeated clears for that session/family do not erase it. Fresh
inactive retry and compaction readings repair missed falling edges. MCP/model-menu
retirement also drops the owning conversation's asks and guards: otherwise a delayed
refusal could restore an unavailable reading after replacement.

Account-level rate state survives both replacement and the rate family clear.
Durable items, supplied pending echoes and queue state, and other conversations are
preserved. Live admission rejects durable event/history identities and bypasses
history, replay-cursor and read-evidence writes. Transient tool/task progress is held
in `AuthoritativeLiveProgress`, independently of legacy task rosters; see
[supplied presentation](thread-screen.md#supplied-session-readings-and-presentation-2084).

Supplied settings use `(arrivalOrdinal, settings)` internally. Distinctness is checked
before extracting the value, so equal replies still reach permission-settle polling.
Deduplicating settings by value would stop unchanged polls before confirmation; both
confirmation-before-ack and ack-before-confirmation must work. Payload write targets
are validated before publication or waiter completion, including correlated replies.
An empty settings read stays unavailable/read-only rather than borrowing the producing
binding as a write target.

`AuthoritativeSessionLiveTest` drives decoded wire-shaped envelopes through production
handling, covering identity collisions across hosts/conversations, provenance kinds,
retired pushes/replies, every family, malformed ownership/clears, inactive readings,
settings polling/targets, answered-history replay and state preservation. Retain legacy
repository/session/prompt coverage alongside these probes. Actual `notice` /
`prompt_answered` fixtures and a new connection/repository/ViewModel are essential:
plain text or remounting one ViewModel does not prove answered-history/reconnect safety.

\#1986 owns queue/send reconciliation; #1988 owns production activation, collection and
combined daemon/live proof. #1947's legacy context reset remains independent, and
\#2078's shared `ThreadProjection` queued-reply repair remains the legacy baseline.

A new status event takes the same shape: a new `…Projection.kt` holding its state, decoder and read,
plus one field, one arm and one override in the repository. The split exists so that sibling tickets
adding events in parallel stop editing the same lines of one very large file.

**The thread store followed the same split (#912), as `ThreadProjection`.** See the parent
[repository split](remote-conversation-repository.md#the-repository-split-912916-complete)
for the command classes added by #914–#916. It isn't a status event —
it's the thread itself: the ordered per-conversation rows, the minted-id ledger (#781) and the
pending-drops ledger (#859), plus every write that folds a thread row (`appendMessages`,
`appendSessionBoundary`, `applyUnrecognizedMessage`, `applyBanner`, `applyCompactionBoundary`,
`applyToolUse`/`applyToolResult`/`applyToolDenied`/`applyToolProgress`,
`applyAssistantDelta`/`finalizeAssistantTurn`, `mergeHistoryPage` and `remove`). The repository still owns
the `interactive` gate in each `onInbound` arm and still owns `requestHistory`, which records into the
projection instead of into a repository field; `sendMessage` and `dropQueuedMessage` moved on to
`MessageCommands` (#915), which records into the same projection. `observeMessages` fans out through
`threadProjection.observe(conversationId)`. See
[Remote conversation repository — reads and the thread store](remote-conversation-repository-reads-and-thread-store.md)
for the store itself.
