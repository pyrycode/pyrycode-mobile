# Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## The repository — one projection, cold fan-out

```kotlin
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,        // connection-scoped child scope (the #351 coordinator); tests pass runTest's backgroundScope
    private val deviceName: String = "",  // connection-level device_name for register_push_token (#359); last + defaulted
) : ConversationRepository
```

The `deviceName` param ([#359](../codebase/359.md)) is the connection-level `device_name` that
[`registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359) sends — the same
value [`NoiseClientInfo.deviceName`](noise-ik-session.md) puts in the `hello` payload. It is **last and
defaulted (`""`)** so that, when [#359](../codebase/359.md) shipped it, the one production construction site
([`RelayRepositoryCoordinator.onConnection()`](relay-repository-coordinator.md)) and all existing test sites
compiled unchanged — **zero edit fan-out** (which kept #359 off the now-merged #352 conflict path).
[#365](../codebase/365.md) then **closed that defer**: the coordinator passes the live value here
(`RemoteConversationRepository(pump, childScope, deviceName)`), sourced from
[`NoiseClientInfo.deviceName`](noise-ik-session.md) (`Build.MODEL`) in `AppModule`, and its connect-time
hook is the first live caller of `registerPushToken`. So `""` is **no longer the production value** — see
[`registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359).

**The list projection.** One `private val projection = MutableStateFlow<List<Conversation>?>(null)`
(`null` = list not yet loaded); every cold read derives from it. Since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split)
it has **four writers**, all inside the single inbound collector or a caller coroutine it hands off to: the
`init` collector's authoritative full-replace on each `conversations` snapshot; the two mutations' confirmed
folds (an atomic CAS upsert via `upsertConversation`) — `createDiscussion`'s insert
([#347](../codebase/347.md)) and `promote`'s in-place upsert ([#348](../codebase/348.md)); the unsolicited
`conversation_updated` fold, which reuses that same `upsertConversation`; and `applyWorkspaceLabel`'s
cwd-keyed relabel from `workspace_updated`. All go through `MutableStateFlow.update {}`, so they retry-merge
rather than clobber — see
[State & concurrency model](remote-conversation-repository-state-errors-and-handoff.md#state--concurrency-model). `promote` additionally **reads** `projection.value`
(a lock-free snapshot) to resolve a conversation's existing cwd when its `workspace` argument is null. No
parallel mutable state.

**Single inbound consumer (the fan-out owner).** Because `pump.inbound` is hot and single-consumer, the
repository launches **exactly one** long-lived collector in `init` on the injected `scope`. That collector
demultiplexes each envelope by `Envelope.type`:

| `Envelope.type` | Handling |
|---|---|
| `"conversations"` | Decode `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload).toConversations()` (#316) → assign to `projection`. A **full-list snapshot** — both the reply to our request and any unsolicited server change-push arrive this way, so re-emission needs **no `in_reply_to` correlation**. Decode is wrapped in a per-envelope `try/catch` (a malformed snapshot is dropped, the collector survives). |
| `"message"` | Decode `MobileJson.decodeFromJsonElement<MessagePayloadDto>(payload)` (#317) → key by `conversation_id` (**read off the DTO before mapping** — the domain `Message` carries none) → `toMessage(envelope, sessionId = "")`, then **two folds** off the one decoded DTO: (a) the strictly-greater-by-`timestamp` `lastMessages` preview fold ([#329](../codebase/329.md)); (b) an arrival-order append into the `threadByConversation` thread ([#313](../codebase/313.md)). Same per-envelope `try/catch` drop; **silent** (no payload logged, content may be sensitive). The singular live/echo `message`. See [`observeLastMessage`](#observelastmessageconversationid--the-live-last-message-preview-329) and [`observeMessages`](#observemessagesconversationid--the-live-thread-read-313) below. |
| `"message_chunk"` | The `backfill_since` **response** body ([#313](../codebase/313.md)): `MobileJson.decodeFromJsonElement<MessageChunkPayloadDto>(payload)` → map **each** row via the same `toMessage(envelope, sessionId = "")` (one envelope `ts` covers every row) → append the whole batch into `threadByConversation` in one atomic `update`. Each row **self-routes** by its own `conversation_id` — no `in_reply_to` correlation. A single bad row drops the **whole chunk** in one `catch (IllegalArgumentException)`; silent. |
| `"ack"` / `"conversation_created"` / `"screen_snapshot"` | Correlated **success** reply to an outgoing request, **one shared arm** ([#346](../codebase/346.md) / [#347](../codebase/347.md) / [#375](../codebase/375.md)): `envelope.inReplyTo?.let { pendingRequests[it]?.complete(envelope.payload) }`. An `ack` payload is the empty `{}` a bare-`ack` caller (`sendMessage`) ignores; a `conversation_created` (`createDiscussion`) payload is the **bare conversation object** the mutation decodes for its typed return; a `screen_snapshot` (`requestScreenSnapshot`, #375) payload is the **rendered-screen object** the read decodes for its `text`. The payload is handed verbatim to the waiting suspend, which decodes (or ignores) it **in the caller's coroutine** — so a malformed reply never throws inside this collector. An `inReplyTo` matching no pending entry (or null) is a no-op; `complete` is idempotent (duplicate reply harmless). `screen_snapshot` and `conversation_created` are **always** correlated replies (no unsolicited push for either — the daemon never broadcasts a create), so an unmatched one is a harmless no-op. |
| `"conversation_updated"` | Split into its own arm since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split), because unlike the three types above this one has **two producers**: a frame whose `inReplyTo` matches a registered `pendingRequests` entry completes that waiter verbatim (the correlated reply to `promote`, #348 — unchanged); one that doesn't — no `inReplyTo`, or it matches no pending request — decodes via [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s `ConversationResponseDto.toConversation()` and folds into `projection` by the payload's own `id` through `upsertConversation`, decode-or-drop so a malformed push mutates nothing and the collector survives. |
| `"workspace_updated"` | New arm since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split): decodes `WorkspaceUpdatedPayloadDto` (`{path, label}`, see [mobile-protocol-v2-wire-layer.md](mobile-protocol-v2-wire-layer.md#workspace-label-pushes-721-a-new-dto-and-conversation_updateds-second-producer)) and calls `applyWorkspaceLabel(path, label)`, **unconditionally** whether or not `inReplyTo` is set — the record is identical either way, and nothing in this repository sends `rename_workspace` yet (#663 adds the sender and its completion together). Not capability-gated: a hostile daemon ignoring the negotiated set could reach the same label change through an ungated `conversations` snapshot, so gating this arm would only break the correlated half for no security gain. Decode-or-drop; malformed leaves the projection untouched and the collector alive. |
| `"error"` | Correlated **failure** reply ([#346](../codebase/346.md)): `envelope.inReplyTo?.let { pendingRequests[it]?.completeExceptionally(mapError(envelope.payload)) }` — unblocks the waiter exceptionally with the mapped domain error. `mapError` **never throws** (a malformed payload yields a fallback exception), so the lone collector survives; `completeExceptionally` is idempotent and a no-op when no entry matches. |
| anything else | **No-op** (intentional `else`, not a bug). `backfill_done` (`{delivered}`) needs no action — the `message_chunk` already delivered the history, the count is informational. |

> **Correlated reply vs unsolicited delta — a single-`Conversation` payload is handled two ways (#721).**
> `conversation_created` / `conversation_updated` are single-`Conversation` payloads mapped by
> [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s
> `ConversationResponseDto`, **not** #316's list mapper. When such a payload arrives as the **correlated
> reply** to *our own* mutation request (matching `inReplyTo`), it routes through its owning arm and the
> mutation method decodes it + confirmed-folds it into the projection — `conversation_created` for
> `createDiscussion` ([#347](../codebase/347.md)), `conversation_updated` for `promote`
> ([#348](../codebase/348.md)). When the **same** payload arrives **unsolicited** (a rename/archive made on
> *another* device, no `inReplyTo` match), [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split)
> folds it into the live projection by the payload's `id` via the same `upsertConversation` the correlated
> mutations use — no duplicate row, since `upsertConversation` dedups by id. `conversation_created` has no
> unsolicited half (the daemon never broadcasts a create), so it stays correlated-only. This is why the
> *read* path now depends on **#318 for both directions**, not #316 alone.

### #721 — apply workspace-label updates, and the `conversation_updated` split

Before [#721](../codebase/721.md), `RemoteConversationRepository.onInbound` dropped `workspace_updated`
outright (no arm, no constant) and routed `conversation_updated` through the shared correlation-only success
arm above, which discarded any frame without a matching `pendingRequests` entry — so a workspace renamed or
a conversation created on another client reached this phone only on the next full `conversations` snapshot,
even though #720 had already landed `Conversation.workspaceLabel` and `workspace_label` on both DTOs.

- **`applyWorkspaceLabel(path, label)`** is a direct sibling of `updateCurrentSessionId`: `projection.update
  { current?.map { if (it.cwd == path) it.copy(workspaceLabel = label) else it } }`. A `null`
  (pre-first-snapshot) projection stays `null` — a push with no rows to label must not invent one. A path
  matching no row is a genuine no-op (`map` returns an element-equal list, so `StateFlow` conflation
  suppresses re-emission). Archived rows are included — archiving a conversation does not un-name its
  folder — and a `String` label replaces the stored one while `null` clears it. `MutableStateFlow.update` is
  load-bearing, not stylistic: `projection` is written from caller coroutines too (`upsertConversation` via
  the correlated mutations), so a `.value = …` read-modify-write would open a real check-then-mutate window.
- **Host- and path-scoped by construction.** Each `RemoteConversationRepository` instance owns one
  connection's `projection`, so two hosts holding byte-identical `cwd` strings or conversation ids cannot
  cross-contaminate — pinned by a two-repository test over two pumps, not a runtime check. Path comparison
  is **exact-bytes**: no trim, normalization or filesystem access, matching `updateCurrentSessionId`'s
  existing posture — the daemon treats two paths differing by a trailing separator as distinct workspaces.
- **Reconnect recovery, not replay.** Neither `workspace_updated` nor an unsolicited `conversation_updated`
  carries an `event_id`, so neither rides the [replay cursor](replay-cursor.md); a client disconnected during
  a rename or a clear reads the current label off its next `conversations` snapshot, which stays
  authoritative (see the malformed-`conversations`-drop row and the `queue_state` full-replace precedent
  elsewhere in this document for the same "next snapshot wins" shape).
- **A known pre-existing clobber becomes reachable by a second route, not a new one.**
  `ConversationResponseDto.toConversation()` maps `currentSessionId`/`sessionHistory`/`isSleeping` to
  placeholders (true on the correlated arm since #348); the unsolicited fold now reaches the same mapper.
  It stays unreachable in practice because both `conversation_updated` push producers (host-side `pyry
  channel new`, and one-shot auto-naming) target conversations with no live session id yet — a future
  producer that fires mid-session would make `ThreadUiState.currentSessionId` go blank and is the follow-up
  to watch for, not a defect to guard against speculatively today.

## `observeConversations(filter)` — the live method

A **cold** `Flow`. On each collection it:

1. issues the request — `pump.send(listConversationsRequest())`, where the request is `Envelope(id =
   <AtomicLong>.incrementAndGet(), type = "list_conversations", ts = Clock.System.now().toString(),
   payload = JsonObject(emptyMap()))` (payload `{}` per protocol);
2. `emitAll`s the projection mapped through a pure `project(list, filter)` — `filterNotNull()` first, so a
   collector **blocks until the first snapshot loads**, then receives the current projection on
   subscription and every subsequent change.

`project(list, filter)` mirrors the Fake **exactly** so the UI behaves identically under either binding:

```
filter:  All        -> every row
         Channels   -> isPromoted && !archived
         Discussions-> !isPromoted && !archived
         Archived   -> archived
then:    sortedByDescending { lastUsedAt }
```

N concurrent collectors share the one projection (cold fan-out over a single hot inbound consumer) — the
multi-collector requirement is met with a single inbound consumer.

**send-on-each-subscribe is intentional.** Redundant `list_conversations` requests are absorbed by
`StateFlow` conflation (a value-equal snapshot does not re-emit), and re-subscribing (e.g. on lifecycle
resume) naturally re-issues the request — more robust than a send-once guard if an early send was dropped
pre-`Open`.

### List-tier placeholders (from #316)

The `conversations` wire summary does not carry full session/sleep/archive state, so the #316 mapper fills
four domain fields with documented list-tier defaults — `currentSessionId = ""`, `sessionHistory =
emptyList()`, `isSleeping = false`, `archived = false` — never `null`-punned. Full enrichment arrives via
the detail/message read paths (#313+), not here. (This is why `Archived` is empty under the pure list
path until a richer source lands: the list snapshot never carries `archived = true`.) The wire's
`last_message_ts` maps to **no** domain field — so the last-message preview is **not** derivable from
this payload; it comes from the message read path ([#329](../codebase/329.md), now live — see the
[`observeLastMessage`](#observelastmessageconversationid--the-live-last-message-preview-329) section
below). See [[v2-app-payload-shapes-ssot]].

## `observeLastMessage(conversationId)` — the live last-message preview (#329)

The channel list's last-message preview. Because the `conversations` wire summary carries no message
content (only `last_message_ts`, which maps to no domain field), the preview is sourced from the
**message read path**, not the list read. [#329](../codebase/329.md) implements it by **riding the live
`message` stream** (Design A — no `backfill_since` request, no `message_chunk` parsing):

- A second projection, `private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())`,
  is fed **only** by the same single `init` inbound collector via the `"message"` demux branch above —
  decode through the [#317](mobile-protocol-v2-wire-layer.md) `MessagePayloadDto.toMessage` boundary
  (`sessionId = ""`, a list-tier placeholder the preview never reads), key by the DTO's
  `conversation_id`, and fold the **strictly-greater-by-`timestamp`** message via
  `MutableStateFlow.update { }`. Strictly-greater means out-of-order older arrivals and re-delivered
  duplicates are no-ops — matching the Fake's `maxByOrNull { it.timestamp }` most-recent semantics.

- The method itself is a **pure cold projection**, issuing **no request** (it rides the stream):

  ```kotlin
  override fun observeLastMessage(conversationId: String): Flow<Message?> =
      lastMessages.map { it[conversationId] }.distinctUntilChanged()
  ```

  A `StateFlow` always has a current value, so every collector — including a `flatMapLatest`
  re-subscription from `ChannelListViewModel` — receives the current most-recent (or `null` when the
  conversation is absent/never-seen) on subscription, re-emits only on change, and supports unlimited
  concurrent collectors off the one inbound consumer.

> **Cold-start gap (still open after #313).** With Design A a quiescent conversation's preview stays
> empty until a live `message` arrives on **this** connection; it does not back-fill from history.
> [#313](../codebase/313.md) landed the backfill plumbing (`backfill_since` → `message_chunk`) but feeds
> it into the **thread** projection (`threadByConversation`), **not** `lastMessages` — so the preview
> cold-start gap is unchanged. A later follow-up can fold `message_chunk` rows into `lastMessages` (the
> chunk's one envelope `ts` covers every row, so it needs a most-recent-row reconciliation, not a
> per-row `ts`). Until then the preview is a strict improvement over nothing: real content for any
> conversation with live traffic.

## `observeMessages(conversationId)` — the live thread read (#313)

The conversation thread: a chronological `List<ThreadItem>` of **backfilled history merged ahead of the
live `message` stream**, deduped by `message_id`, in wire/arrival order — with
[`ThreadItem.SessionBoundary`](session-transition-fold.md) delimiters interleaved at session transitions
since [#336](../codebase/336.md). [#313](../codebase/313.md) implements the message half over a third
projection fed by the **same** single inbound collector; #336 unified that projection's element type so a
non-`Message` boundary can interleave by arrival order (see [Thread store §
below](#the-unified-thread-store-and-the-session_transition-fold-336)):

- A third projection, `private val threadByConversation = MutableStateFlow<Map<String,
  List<ThreadItem>>>(emptyMap())`, holds each conversation's ordered thread rows — `message_id`-deduped
  `MessageItem`s plus interleaved `SessionBoundary`s. It is written **only** by the one `init` collector,
  from the `message` arm (appends each live message), the `message_chunk` arm (appends a whole backfill
  batch), the structured-turn folds (#387 tool rows, #337 streaming deltas), and the #336 boundary fold.
  The message rows go through one accumulator:

  ```kotlin
  private fun appendMessages(rows: List<Pair<String, Message>>) {  // (conversationId, Message)
      if (rows.isEmpty()) return
      threadByConversation.update { current -> /* per row: first-seen id appends at end as a
          ThreadItem.MessageItem; repeat id replaces in place (is-MessageItem guard + indexOfFirst),
          position fixed at first occurrence */ }
  }
  ```

  Dedup is **last-write-in-place**: a `message_id` seen in both the backfill chunk and a later live
  `message` appears once, at its first position, with the later payload winning. The `is
  ThreadItem.MessageItem` guard skips any interleaved boundary so a `message_id` never matches a boundary
  row (boundaries carry no id). Batching a whole chunk into one atomic `update` avoids emitting an
  intermediate list per row.

- The method is a **cold** flow mirroring `observeConversations` exactly — issue the request, then fan
  out the per-conversation projection:

  ```kotlin
  override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
      flow {
          pump.send(backfillSinceRequest(conversationId))
          emitAll(threadByConversation                       // store already holds ThreadItems (#336) —
              .map { it[conversationId].orEmpty() }          //   no more `.map(MessageItem)` wrap
              .distinctUntilChanged())
      }
  ```

  `distinctUntilChanged()` means a change to **another** conversation's slot does not re-emit this flow.
  Re-sends `backfill_since` on every subscription; re-delivered history is absorbed by the `message_id`
  dedup, so no idempotency guard is needed, and a pre-`Open` send (returns `false`) self-heals on the
  next subscribe while the live stream still fills the thread.

- **`backfill_since` is the one outgoing request.** `backfillSinceRequest(id)` builds `Envelope(type =
  "backfill_since", payload = BackfillSincePayloadDto(since_ts = "1970-01-01T00:00:00Z", conversation_id
  = id, max_messages = 10_000))` — full history from the Unix **epoch** (the wire `since_ts` is a
  *required* RFC-3339 timestamp, so "all history" is the epoch, not an absent field) with an advisory
  `max_messages` cap. Wire SSOT: server [#272](https://github.com/pyrycode/pyrycode/issues/272)
  `BackfillSincePayload` (`internal/protocol/messaging.go`). The reply (`message_chunk` + a terminal
  `backfill_done` the repo ignores) is correlated by `inReplyTo` on the wire, but the repo never reads
  it — each chunk row self-routes by its own `conversation_id`. See [[v2-app-payload-shapes-ssot]].

- **Ordering is wire/arrival order — no client-side timestamp sort.** A `message_chunk` carries **one**
  envelope `ts` for many rows, so a timestamp sort is impossible *and* wrong; the ordered inbound stream
  delivers the backfill chunk before the live messages the server emits afterward, so appending in
  arrival order yields history-then-live naturally. The encrypted stream cannot skip a frame — do not
  reorder around a gap.

> **Session-boundary delimiters now fold in (#336, landed); the `message` path stays finished-only.**
> #313 emitted `ThreadItem.MessageItem`s only because the v2 wire then carried no session-transition
> representation. That gate closed (pyrycode#656/#657/#740/#741/#739), and
> [#336](../codebase/336.md) folds the capability-gated `session_transition` event into the thread as a
> `ThreadItem.SessionBoundary` — **live transitions only** (the `message_chunk` backfill carries no
> boundaries; historical pre-connection boundaries remain out of scope, a deliberate fidelity gap vs the
> fake). See [Session-transition fold](session-transition-fold.md) and the [Thread store §
> below](#the-unified-thread-store-and-the-session_transition-fold-336). The `message` wire type itself is
> still always *finished* (`isStreaming = false` via #317's `toMessage`) — live token-streaming rides the
> separate structured `assistant_delta` stream (#385/#337), not this path.

> **Known follow-up: `appendMessages` dedup is O(n²) over thread size** (`indexOfFirst` + `existing +
> message` per row). Deferred by evidence — no live backfill yet (`max_messages` unexercised; the
> backend dispatcher is pyrycode #248), no observed perf failure. Swap to a `LinkedHashMap<messageId,
> Message>` accumulator when large-thread backfill goes live; the change is internal to `appendMessages`.

## The unified thread store and the `session_transition` fold (#336)

[#336](../codebase/336.md) closed #313's session-boundary de-scope. The decode boundary (DTO + mapper +
reason mapping + the `workspaceCwd`-non-null-iff-`WorkspaceChange` invariant) lives in
[Session-transition fold](session-transition-fold.md); this section records only how it attaches to the
repository.

- **The store was unified to `List<ThreadItem>`.** A `SessionBoundary` is a non-`Message` `ThreadItem`
  that must interleave **in arrival order** with messages, and there is no shared ordering key to
  re-interleave two separate stores (messages are id-fixed, boundaries have no id). So
  `threadByConversation` moved from `Map<String, List<Message>>` to `Map<String, List<ThreadItem>>`, and
  the five existing `Message.id`-keyed folds (`appendMessages`, `applyToolUse`, `applyToolResult`,
  `applyAssistantDelta`, `finalizeAssistantTurn`) were lifted to `ThreadItem` **output-preserving** (the
  existing suite is the guard). Four share a `private fun List<ThreadItem>.indexOfMessage(id, role): Int`
  guard; `appendMessages` keeps an **inline id-only** `is ThreadItem.MessageItem` guard (message dedup is
  role-agnostic — routing it through the role-taking helper would change semantics). `lastMessages` (the
  messages-only preview) was **not** touched; `threadProjection` lost its `.map { MessageItem(it) }` wrap.
- **Rides the single existing inbound collector.** A new `TYPE_SESSION_TRANSITION` arm joins the
  `onInbound` demux, beside the `stall` / `queue_state` siblings — **no second subscription**. Gated
  **identically** on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the **reused** #385 supplier —
  no new capability), it calls `decodeSessionTransition(envelope)` (the `decodeStall`/`decodeQueueState`
  `try/catch (IllegalArgumentException) { null }` drop idiom) and folds via `appendSessionBoundary`.
- **Folds a thread row only — three deliberate non-actions.** Unlike the structured-stream arm it
  surfaces **nothing** on [`liveSessionEvents`](remote-conversation-repository-live-stream-and-modals.md#livesessionevents--the-v2-structured-stream-decode-seam-385)
  (a boundary is a thread row, not a streaming event), does **not** clear a [stall](stall-state.md) (a
  session transition is not turn forward-progress), and **does not dedup** — `appendSessionBoundary`
  pure-appends in arrival order (the wire carries no row id; the repo is connection-scoped per #351, so
  arrival order is correct — the same posture as `applyAssistantDelta`).
- **Routes strictly by the payload's `conversation_id`** into `threadByConversation[conversationId]`, so
  a boundary can only ever surface in `observeMessages(thatId)` — cross-routing is structurally
  impossible (no "is this conversation observed?" guard; an unobserved id simply sits unread). This is
  the fail-closed client mirror of the producer's server-side drop of unbindable transitions (#741).

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the authenticated
Noise channel, and **nothing in the new arm or the drop branch logs the payload** — `conversation_id` /
session ids / `workspace_cwd` are sensitive (a logged or mis-routed boundary is a cross-conversation
leak). See [Session-transition fold § Trust boundary](session-transition-fold.md#trust-boundary--no-payload-logging).

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
overlap test — but `ThreadScreen` keys a boundary row on `(previousSessionId, newSessionId)` alone and
reads neither `reason` nor `occurredAt`, so a page carrying two boundaries sharing that pair and differing
only in `occurredAt` would pass an equality check and still hand the `LazyColumn` two rows with one key,
which throws. The general lesson: when a list row has a client-visible identity, dedup upstream on *that*
identity, or the two can silently disagree. A `MessageItem` joins on `message_id` alone, id-only and
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

**Out-of-scope, filed:** the live lane's own `appendSessionBoundary` still has no dedup at all (see
[Session-transition fold](session-transition-fold.md)) — the merge above closes this only for the history
path. Fixing the live side means editing `appendSessionBoundary` or the renderer's key, tracked as
[#775](../codebase/775.md).

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
  case), and the injected `ConnectionStateSource` transitioning to `Connected` **after** having left it —
  not the connection the thread opened on, since that source hands every collector its current value on
  subscription. Both restarts carry a monotonic `walk` generation so a settle from a connection that has
  since been superseded is dropped rather than written into the restarted walk; this is what keeps a
  reconnect from writing a dead connection's cursor into the live one.
- **A refusal of the newest-page ask (empty cursor) settles permanently instead of restarting** — this is
  what keeps the restart cycle structurally impossible rather than merely capped: every non-empty-cursor
  ask the repository ever receives from this walk originates from a reader scroll or a reader press on the
  retry affordance, never from a restart.

The screen-side half — the one oldest-end slot now showing loading, a retry affordance or a dead end — is
[Thread screen § the oldest-end history retry and restart](thread-screen-how-it-works-list-and-status-row.md#the-oldest-end-history-retry-and-restart-778).
