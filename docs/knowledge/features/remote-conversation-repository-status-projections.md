# Remote repository — status projections

## Status projections: one file per status event

The single inbound collector admits session-owned status/configuration families
before handing them to their projections. Stall, retry, compaction, thinking, usage,
model/facts and MCP readings remain separate from supplied content. `QueueProjection`
and raw task-roster/lifecycle reconstruction were removed in #2367. Queue/task state
comes from canonical items; transient tool/task progress augments current readings.

`AttachmentOfferProjection` retains live attachment-offer metadata and validation.
It no longer appends reconstructed content rows. Attachment transport cannot certify
history or settle a pending user item. See [live state](remote-conversation-repository-live-stream-and-modals.md).

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
observation by interface delegation without storing it. Suggestions reject envelopes carrying `event_id` and are never
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
preserved. Live admission rejects durable event/history identities and creates no
content certification or read-evidence writes. Transient tool/task progress is held
in `AuthoritativeLiveProgress`, independently of content facts; see
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
settings polling/targets, answered-history replay and state preservation. The historical probes retained legacy
repository/session/prompt coverage alongside these probes. Actual `notice` /
`prompt_answered` fixtures and a new connection/repository/ViewModel are essential:
plain text or remounting one ViewModel does not prove answered-history/reconnect safety.

\#1986 owns queue/send reconciliation; #1988 owns production activation, collection and
combined daemon/live proof. Those earlier legacy context/queued-reply algorithms
were subsequently removed in #2367; the historical evidence retains its attribution.

A new status event takes the same shape: a new `…Projection.kt` holding its state, decoder and read,
plus one field, one arm and one override in the repository. The split exists so that sibling tickets
adding events in parallel stop editing the same lines of one very large file.

The thread store is now `AuthoritativeThreadStore`. The removed `ThreadProjection`
and queue/background reconstruction have no runtime consumer. See
[authoritative items](remote-conversation-repository-reads-and-thread-store.md).
