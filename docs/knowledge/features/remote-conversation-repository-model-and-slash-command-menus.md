# Remote conversation repository — the model-list and slash-command-list menu retentions

Split out of [Remote conversation repository — live stream, modal seams and the replay cursor](remote-conversation-repository-live-stream-and-modals.md) on 2026-09-24 to keep that document under the 50000-byte size cap the docs guard enforces. The two sections below moved here verbatim and kept their headings, so their anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document and its live-stream/modals sibling for what the repository does overall.

## The model-list inbound arm — the connection-scoped retention (#791)

A new `TYPE_MODEL_LIST = "model_list"` arm joins the `onInbound` `when (envelope.type)` demux, byte-for-
byte the `queue_state` / [`stall`](stall-state.md) sibling shape: gated identically on
`CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the reused #385 supplier), calling
`ModelMenuProjection.apply(envelope)` (#913), which decodes via a private `decodeModelList(envelope):
Pair<String, ModelMenu>?` and folds the result into connection-scoped state. The frame carries the per-conversation menu of models claude will accept — identifiers, labels,
per-row effort levels, auto-mode support and truncation metadata, drawn from claude's `initialize`
control reply — arriving once per claude child spawn on the live lane (with an `event_id`) and again as a
per-conversation burst on every (re)connect (without one); the payload is identical on both paths, so
nothing here branches on delivery path. Decode boundary:
[`ModelListPayloads.kt`](mobile-protocol-v2-wire-layer-application-payloads.md#the-model-list-retention-791).

- **`decodeModelList`** copies the `decodeStall` / `decodeQueueState` `try { … } catch
  (IllegalArgumentException) { null }` drop idiom — a malformed payload yields `null`, the one envelope is
  dropped, the lone collector survives, and the conversation's previously retained menu stands because
  nothing was written. **Nothing on this path logs**: every row string is claude-authored text that
  crossed the subprocess trust boundary, and a logged `conversation_id` is a cross-conversation
  correlation leak — the uniform rule across every `onInbound` arm. `toMenu()` is total and authors no
  message at all, so the only throwables here are kotlinx-serialization's, caught and discarded rather
  than surfaced.
- **`ModelMenuProjection.modelMenusByConversation: MutableStateFlow<Map<String, ModelMenu>>`** (#913) is
  connection-scoped, in-memory state written **only** from the single existing `init` inbound collector — single writer, so snapshots
  never race, and the atomic `update {}` matches the sibling projections' memory-visibility posture. No
  new coroutine, no new scope, no new dispatcher.
- **Snapshot-replace, keyed by the frame's own `conversation_id` and nothing else.** The arm does one
  `modelMenusByConversation.update { it + (conversationId to menu) }` — a `+` on the map replaces that one
  key wholesale and leaves every other conversation untouched. Routing is **never** the envelope id (every
  frame in the reconcile burst repeats the daemon's non-load-bearing envelope id) and **never** burst
  position (the daemon walks its registry in an order that is not a contract). There is no element-level
  merge or reconciliation within one row list either — a later frame for a conversation already held
  fully replaces the prior rows, even when the new list is shorter.
- **No clearing edge, anywhere.** Nothing removes a key, and no connection edge clears the map. Absence of
  a frame is the wire's only "no list" signal, so a blanket clear on reconnect or on close would
  manufacture an unavailable reading the daemon never stated. Unlike
  [`queuedByConversation`](queued-backlog.md) and [`stalledConversations`](stall-state.md), which track a
  transient "right now" condition, a published model menu is a standing fact about the connected host for
  as long as the connection lives — the only reset this state ever gets is a fresh
  `RemoteConversationRepository` per connection (#351), which is also where "per host" comes from: the
  published vocabulary varies by machine and account, not by conversation.
- **`observeModelMenu(conversationId): Flow<ModelMenu?>`** is a cold projection —
  `modelMenusByConversation.map { it[conversationId] }.distinctUntilChanged()` — over the frames the
  daemon sends unasked, plus (since #792, below) this connection's own on-demand ask when nothing has
  arrived yet. `null` is **unavailable**: a
  normal, permanent resting state covering no live connection, a connection without `interactive`, a
  conversation this connection heard no frame for, and the window before the first frame lands — never an
  error, never a spinner, never the device `Model`/`Effort` enums, and — because the lookup is by the
  caller's own id — never another conversation's rows. The
  [`ConversationRepository`](conversation-repository.md) default (`flowOf(null)`) gives the inline test
  doubles the same reading for free, and [`StableConversationRepository`](stable-conversation-repository.md)
  passes through via `switchToLive<ModelMenu?>(null) { it.observeModelMenu(id) }` — here the
  `flatMapLatest` switch is the **host-isolation mechanism**: dropping the previous connection's
  projection on a host swap is what stops one host's vocabulary being offered for another's conversation.

**`agent`/`family` tags (#1110).** `ModelListRowDto` also decodes `agent: String?` and `family: String?`,
both `omitempty` on the wire and untouched here on frames that don't carry them — a `multi_agent` client's
`model_list` merges Claude's rows and Codex's into one list, identical for every conversation, so a row
needs its own tag to say which agent it belongs to. `toMenu` maps `agent` through `modelRowAgentOf`: absent
or `"claude"` reads `Claude`, `"codex"` reads `Codex`, anything else — including a case variant — is `null`,
a row that belongs to no conversation. `family` is copied verbatim and stays unparsed. Retention and routing
are unchanged: the filter to one conversation's own agent runs downstream, in `ThreadViewModel`, not here —
see [Conversation repository § `ModelMenu`/`ModelMenuRow`](conversation-repository.md#shape) for the field
KDoc and [Thread screen § the model-menu agent filter](thread-screen-how-it-works-state.md#the-model-menu-agent-filter-1110)
for where a merged menu becomes one conversation's.

**Renders nothing.** This slice adds no UI, no outbound verb, and does not retire the device `Model` /
`Effort` enums — [#649](https://github.com/pyrycode/pyrycode-mobile/issues/649) reads what this retains
when the composer's model/effort controls land.

`security-sensitive`: the design mirrors `queue_state`'s posture exactly — decode runs behind the
authenticated Noise channel, a hostile/buggy daemon can waste at most one envelope per malformed frame
(fail-closed, no partial menu), and routing by the frame's own `conversation_id` alone forecloses
cross-conversation injection structurally: a frame can only overwrite the menu of the conversation it
names. The untrusted-string handling itself is documented on the domain type — see
[Mobile Protocol v2 § the model-list retention](mobile-protocol-v2-wire-layer-application-payloads.md#the-model-list-retention-791).

## The on-demand ask — `request_model_list` (#792)

Closes the window #791's two unsolicited paths leave open: a conversation **created after the phone
connected** crosses neither the live per-spawn frame nor the connect-time reconcile burst, so without an
ask a new conversation's `observeModelMenu` reading stays `null` forever with nothing to trigger a
change. `request_model_list` is the third and last way a client gets a menu and the only one it can
trigger itself. Its answer is #791's own `model_list` frame, unchanged, correlated by `in_reply_to` —
so it lands through the existing decode and retention with no second payload shape and no new render
obligation. Wire request DTO: [Mobile Protocol v2 § the on-demand
ask](mobile-protocol-v2-wire-layer-application-payloads.md#the-on-demand-ask--request_model_list-792).

- **Triggered from the reading, not a new public method.** `observeModelMenu` gained an
  `.onStart { askForModelMenu(conversationId) }` — subscribing to a conversation's menu *is* wanting it,
  and this is the seam where the conversation to name is already known, the `observeSessionSettings`
  precedent of a reading issuing its own request. The ask is not exposed on `ConversationRepository`, the
  facade or the fake, so no consumer call site changes; `StableConversationRepository`'s host-swap
  re-subscription (`switchToLive`) is what re-triggers it against a new connection.
- **Fire-and-forget, the `requestDebugBundle` shape — not `sendAndAwaitReply`.** The verb's two replies
  arrive on different arms and neither can complete a correlated waiter the usual way: a success is the
  `model_list` frame #791's own arm applies by the payload's own `conversation_id`, and it must never
  also settle a deferred — that would put a broadcast-shaped frame into a registry where a stale or
  misrouted one could land in a slot it was never addressed to; a refusal is a separate `error`. Awaiting
  the send would therefore suspend until connection teardown on the very outcome the verb exists to
  produce, and a connection without `interactive` is answered with nothing at all, so there would be
  nothing to await.
- **Two ledgers, different lifetimes, both on `ModelMenuProjection`** (#913). `askedModelMenus:
  MutableSet<String>` (a `ConcurrentHashMap` key
  set) is the one-shot record — a conversation already holding a menu, or already asked, is not asked
  again — connection-scoped like `modelMenusByConversation`, so "once" means once per connection; a fresh
  connection's reconcile burst is the recovery path. `modelListAsks: ConcurrentHashMap<Long, String>`
  (request envelope id → conversation id) is the refusal correlation, consumed by whichever reply
  arrives first. It is **disjoint from `pendingRequests` by construction**, so an `in_reply_to` resolves
  in at most one map and the two correlation paths cannot consume each other's reply.
  `askedModelMenus.add` is an atomic test-and-set, not a read-then-write, so two collectors subscribing to
  the same conversation at once still produce one ask; a send the transport refused rolls both ledger
  entries back, which is not a retry (nothing re-sends) — it only declines to burn the one shot on a
  frame that never left.
- **The success arm consumes the correlation and discards it — it never becomes the retention's routing
  key.** `envelope.inReplyTo?.let(modelListAsks::remove)` runs before the existing decode; the retention
  write still routes on the payload's own `conversation_id`, never on what the correlation named. A
  security-review finding named the tempting alternative explicitly: resolving the ask's conversation id
  from `modelListAsks` and retaining under *that* id would let a daemon answer an ask for A with a
  payload naming B and land B's rows under A — the cross-conversation injection #791's
  routing-by-payload-only rule already forecloses, and this ask must not reopen it. The removal does
  **not** release `askedModelMenus` — the ask was answered.
- **The refusal arm reads only `ErrorPayload.code`, never reusing `mapError`** (which collapses
  `conversation.not_found` into an `IllegalArgumentException` and would discard the very distinction this
  branch exists to draw):
  - `model_list.unavailable` — the daemon hosts the conversation but has nothing to answer with yet, so
    the same request may succeed later. `askedModelMenus` is released so a **later subscription** may ask
    again.
  - `conversation.not_found` — the daemon does not host what was named. Terminal for that id on this
    connection; the entry stands.
  - any other code, or a payload that will not decode — fail closed, treated as terminal.

  Neither branch writes `modelMenusByConversation`: a refusal never becomes an empty menu, and both leave
  the reading at its existing `null`.
- **No retry loop.** Releasing the one-shot on `model_list.unavailable` states the wire's own contract by
  declining to suppress a future ask; nothing here schedules, backs off or re-sends, and only a **new**
  subscription asks again — which nothing in this design creates. A reply that never arrives, or a
  timeout, needs no handling: there is no waiter to expire. The conversation stays unavailable and the
  next connect's reconcile burst is the recovery path.
- **Never logs, on either arm.** The conversation id is a cross-conversation correlation key and a
  refusal's `message` is daemon-authored prose; pairing them in one line is exactly what this ticket's
  security note forbids, so the refusal branch reads `code` and discards the rest, and `askForModelMenu`
  authors no message at all — deliberately not `interrupt`'s `check(pump.send(…)) { … }` idiom, so there
  is no failure text to leak.

**Renders nothing**, the same posture as #791 — the composer's model/effort controls are
[#649](https://github.com/pyrycode/pyrycode-mobile/issues/649).

## The slash-command-list inbound arm — the connection-scoped retention (#882)

A new `TYPE_SLASH_COMMAND_LIST = "slash_command_list"` arm joins the same `onInbound` demux immediately
after the model-list arm above, gated identically on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`,
calling `SlashCommandMenuProjection.apply(envelope)` — a new `internal class` in
`data/repository/SlashCommandMenuProjection.kt`. It is the `ModelMenuProjection` shape with the ask removed:
`slash_command_list` declares no inbound verb, so unlike #791/#792 there is no on-demand ask, no
`askedModelMenus`-style ledger, and no refusal correlation to maintain — `observeSlashCommandMenu` issues
nothing on subscription, and a test pins that subscribing sends nothing. The frame carries the
per-conversation menu of slash commands claude will accept — `name`, `argument_hint`, `description`,
`aliases` and per-row `truncated_fields`, plus a frame-level `dropped_commands` — drawn from claude's
`initialize` control reply, arriving once per claude child spawn on the live lane (with an `event_id`) and
again as a per-conversation burst on every (re)connect (without one); the payload is identical on both
paths. Decode boundary:
[`SlashCommandListPayloads.kt`](mobile-protocol-v2-wire-layer-application-payloads.md#the-slash-command-list-retention-882).

The retention itself matches #791's exactly — same snapshot-replace keyed by the frame's own
`conversation_id` and nothing else, same no-clearing-edge posture (absence of a frame is the only "no
menu" signal), same cold `map { it[conversationId] }.distinctUntilChanged()` projection, same
`switchToLive<SlashCommandMenu?>(null) { … }` host-isolating passthrough on
[`StableConversationRepository`](stable-conversation-repository.md), same `flowOf(null)` interface default.
See the model-list section above for the reasoning behind each of those — not repeated here.

Two departures from the `model_list` sibling, both deliberate:

- **`decodeSlashCommandList` also drops a frame whose `conversation_id` is empty** (the daemon's `_zero`
  fixture shape) — `decodeModelList` would instead retain such a frame under the map key `""`. An entry
  nobody can route to is dead weight, and the AC's "usable `conversation_id`" wording asks for exactly this.
- **`droppedCommands` cannot be inferred from `rows.size` the way a short `ModelMenu` reading might tempt a
  reader to assume.** The daemon feeds it from two independent producer-side cuts — an entry cap and a byte
  bound — and the byte bound can fire before the entry cap does, so a non-zero `droppedCommands` can arrive
  beside *any* row count, not only a short list. Read `droppedCommands` directly; never treat `rows.size` as
  a proxy for completeness.

**SECURITY.** Every row string (`name`, `argumentHint`, `description`, each alias) is
**workspace-authored** — a lower-trust origin than `model_list`'s claude-authored text, since it crossed the
subprocess trust boundary one hop earlier (whoever wrote the repository the session runs in, not claude
itself). See [`SlashCommandMenuRow`](conversation-repository.md#shape) for the inert-text obligation this
carries into any future render consumer. `name` is not an identifier — the real fixture carries
`__remote-workflow` — and the decode neither trims, folds nor validates it; a test pins that padding and an
embedded escape sequence also survive unchanged, so a later "cleanup" cannot quietly start rejecting or
rewriting real names.

**Renders nothing.** This slice adds no UI; the Actions control and slash-completion tickets split from
[#655](https://github.com/pyrycode/pyrycode-mobile/issues/655) consume this reading, and own the
render-time sanitization the workspace-authored text still needs.
