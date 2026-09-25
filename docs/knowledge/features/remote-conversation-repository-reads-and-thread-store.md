# Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

**The thread store, the minted-id ledger and the pending drops now live in `ThreadProjection`** (#912), an `internal class` in `data/repository/ThreadProjection.kt`, split out of `RemoteConversationRepository` the way the status events were (#819 — see [Status projections](remote-conversation-repository.md#status-projections-one-file-per-status-event)). The repository still owns the connection's single inbound collector and every `interactive` capability gate; each thread frame's `onInbound` arm now hands off to a public method on the projection instead of a private method on the repository itself, and `sendMessage`, `dropQueuedMessage` and `requestHistory` record into it directly rather than into a repository field. The move changed no fold behaviour, so the sections below still describe it accurately — where a snippet shows a `private val` or `private fun` on `RemoteConversationRepository`, read it as the same member, now on `ThreadProjection` and mostly public (`observe`, `appendMessages`, `appendSessionBoundary`, `applyUnrecognizedMessage`, `applyBanner`, `applyCompactionBoundary`, `applyToolUse`, `applyToolResult`, `applyToolDenied`, `applyToolProgress`, `applyAssistantDelta`, `finalizeAssistantTurn`, `recordMinted`, `recordDrop`, `withdrawDrop`, `settleDrops`, `mergeHistoryPage`, `remove`); `removeOwnEcho`, `appendUnrecognizedMessage`, `appendBanner`, `appendCompactionBoundary` and the three `decode…` functions stayed private, just on the new class. `settleDrops` also gained an explicit `QueueProjection` parameter, since it no longer sits in the same class as `queueProjection`.

**The list projection and the last-message previews now live in `ConversationListProjection`** (#913), an `internal class` in `data/repository/ConversationListProjection.kt`, split out the same way. The repository still decodes the frames the list state doesn't own alone (`message`, `conversation_updated`, `workspace_updated` each also feed something else), but the `conversations` snapshot decode moved wholesale into `applySnapshot`, and every list write and read is now a public method on the projection: `upsertConversation`, `applyWorkspaceLabel`, `recordLastMessage`, `updateCurrentSessionId`, `remove`, `observe`, `observeLastMessage`, `current`, plus the private `project`. Where the sections below show `projection` or `lastMessages` as a `private val` on `RemoteConversationRepository`, or show one of those names as a repository method, read it as the same member, now on `ConversationListProjection`. `removeConversation` stays split across two projections: it calls `ConversationListProjection.remove` and `ThreadProjection.remove` side by side, and `promote` / `archiveWorkspace`'s one-time snapshot reads are `ConversationListProjection.current()`.

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

**The list projection**, now `ConversationListProjection.projection` (#913): a `private val projection =
MutableStateFlow<List<Conversation>?>(null)` (`null` = list not yet loaded); every cold read derives from it.
Since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split)
it has **four writers**, all inside the single inbound collector or a caller coroutine it hands off to: the
`init` collector's authoritative full-replace on each `conversations` snapshot; the two mutations' confirmed
folds (an atomic CAS upsert via `upsertConversation`) — `createDiscussion`'s insert
([#347](../codebase/347.md)) and `promote`'s in-place upsert ([#348](../codebase/348.md)); the collector's
`conversation_updated` fold, which reuses that same `upsertConversation` (unsolicited frames since #721, every
well-formed frame since #996); and `applyWorkspaceLabel`'s
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
| `"conversation_updated"` | Split into its own arm since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split), because unlike the three types above this one has **two producers**: the correlated reply to a mutation this client sent (`promote`, rename, archive, unarchive, workspace change, system prompt), and an unsolicited push with no `inReplyTo` or one matching no pending request. Since #996 the arm treats both alike: it decodes to [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s `ConversationResponseDto` and folds the decoded record into `projection` through `upsertConversation` (since [#1108](../codebase/1108.md) the DTO→`Conversation` mapping and the id-keyed fold both live inside that call), and only **then** completes any matching waiter with the raw payload. Decode-or-drop guards the fold only: a malformed frame mutates nothing, the collector survives, and a matching waiter still receives the payload and throws in its own decode. Folding before completing is what keeps a rename in the list when the caller's scope is cancelled between the reply and its resumption, for example a thread popped straight after Save; the caller's own later upsert of the same record is an idempotent re-write. Before #996 a correlated frame went to its waiter undecoded, and the caller's upsert was the only write. **This double fold is why a field's "keep the stored value when absent" rule must live inside `upsertConversation` itself, not in either caller** ([#1108](../codebase/1108.md)): the collector's arm and the mutation's own post-reply upsert both run on a correlated `conversation_updated`, so a rule applied at only one site would have the second, unconditional upsert undo it — see [`ConversationListProjection.upsertConversation`](remote-conversation-repository-send-create-promote-rename.md#confirmed-insert-via-conversationlistprojectionupsertconversation-the-projections-second-writer)'s `agent`-keeping branch. |
| `"workspace_updated"` | New arm since [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split): decodes `WorkspaceUpdatedPayloadDto` (`{path, label}`, see [mobile-protocol-v2-wire-layer.md](mobile-protocol-v2-wire-layer-application-payloads.md#workspace-label-pushes-721-a-new-dto-and-conversation_updateds-second-producer)) and calls `applyWorkspaceLabel(path, label)`, **unconditionally** whether or not `inReplyTo` is set — the record is identical either way. Since [#663](remote-conversation-repository-workspace-and-push.md#renameworkspace-and-archiveworkspace--the-two-workspace-row-verbs-desktop-already-had-663) this is also the correlated reply to `renameWorkspace`'s `rename_workspace`: a matching waiter (looked up by `inReplyTo` **before** the decode) is completed only **after** `applyWorkspaceLabel` runs, so the caller resumes onto already-relabelled rows, and a decode failure on a correlated frame fails that waiter with a static `RelayErrorException` instead of leaving it to hang until teardown. Not capability-gated: a hostile daemon ignoring the negotiated set could reach the same label change through an ungated `conversations` snapshot, so gating this arm would only break the correlated half for no security gain. Decode-or-drop for an unsolicited push; malformed leaves the projection untouched and the collector alive. |
| `"error"` | Correlated **failure** reply ([#346](../codebase/346.md)): `envelope.inReplyTo?.let { pendingRequests[it]?.completeExceptionally(mapError(envelope.payload)) }` — unblocks the waiter exceptionally with the mapped domain error. `mapError` **never throws** (a malformed payload yields a fallback exception), so the lone collector survives; `completeExceptionally` is idempotent and a no-op when no entry matches. |
| anything else | **No-op** (intentional `else`, not a bug). `backfill_done` (`{delivered}`) needs no action — the `message_chunk` already delivered the history, the count is informational. |

> **Correlated reply vs unsolicited delta — a single-`Conversation` payload is handled two ways (#721).**
> `conversation_created` / `conversation_updated` are single-`Conversation` payloads mapped by
> [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s
> `ConversationResponseDto`, **not** #316's list mapper. When such a payload arrives as the **correlated
> reply** to *our own* mutation request (matching `inReplyTo`), the mutation method decodes it and
> confirmed-folds it into the projection — `conversation_created` for `createDiscussion`
> ([#347](../codebase/347.md)), `conversation_updated` for `promote` ([#348](../codebase/348.md)) and the
> other `conversation_updated` mutations. Since #996 the collector also folds a correlated
> `conversation_updated` before completing its waiter, so the list no longer depends on the caller
> resuming. When the **same** payload arrives **unsolicited** (a rename/archive made on
> *another* device, no `inReplyTo` match), [#721](#721-apply-workspace-label-updates-and-the-conversation_updated-split)
> folds it into the live projection by the decoded record's `id` via the same `upsertConversation` the correlated
> mutations use — no duplicate row, since `upsertConversation` dedups by id. `conversation_created` has no
> unsolicited half (the daemon never broadcasts a create), so it stays correlated-only. This is why the
> *read* path now depends on **#318 for both directions**, not #316 alone.

### #721 — apply workspace-label updates, and the `conversation_updated` split

Before [#721](../codebase/721.md), `RemoteConversationRepository.onInbound` dropped `workspace_updated`
outright (no arm, no constant) and routed `conversation_updated` through the shared correlation-only success
arm above, which discarded any frame without a matching `pendingRequests` entry — so a workspace renamed or
a conversation created on another client reached this phone only on the next full `conversations` snapshot,
even though #720 had already landed `Conversation.workspaceLabel` and `workspace_label` on both DTOs.

- **`ConversationListProjection.applyWorkspaceLabel(path, label)`** (#913) is a direct sibling of
  `updateCurrentSessionId`, on the same class: `projection.update
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
  placeholders (true on the correlated arm since #348); the unsolicited fold now reaches the same mapper, and
  since #996 the collector's fold of a correlated reply does too, writing the same record the caller writes.
  It stays unreachable in practice because both `conversation_updated` push producers (host-side `pyry
  channel new`, and one-shot auto-naming) target conversations with no live session id yet — a future
  producer that fires mid-session would make `Conversation.currentSessionId` go blank and is the follow-up
  to watch for, not a defect to guard against speculatively today. `ThreadUiState.currentSessionId` — the
  thread's own copy of this field, which this clobber used to threaten — was deleted outright by
  [#807](../codebase/807.md): the run-configuration write no longer routes through `Conversation
  .currentSessionId` at all, only through `SessionSettings.sessionId` from `observeSessionSettings`, so this
  clobber can no longer reach that surface. Other consumers of the domain `Conversation.currentSessionId`
  field (e.g. `FakeConversationRepository`'s session lookups) are unaffected either way.
- **Contrast with `agent`: the same mapper, but deliberately not clobbered ([#1108](../codebase/1108.md)).**
  `currentSessionId`/`sessionHistory`/`isSleeping` reset to their placeholders on every fold through this
  mapper, because nothing before #1108 needed the finer distinction. `agent` cannot follow that precedent:
  an older daemon omits `agent` from `conversation_updated`, and if the mapped placeholder (Claude) won
  unconditionally, a Codex conversation would flip back to Claude on the next rename or archive. `upsertConversation`
  special-cases exactly this one field — a record with no `agent` key keeps the projection's stored value
  instead of taking the mapper's default — rather than reaching for a repository-wide "don't clobber
  anything" rule, which is not implied by the ticket and not evidenced by any other field yet.

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

- A second projection, `private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())` on
  `ConversationListProjection` (#913, moved off `RemoteConversationRepository` itself), is fed **only** by
  the same single `init` inbound collector via the `"message"` demux branch above —
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
  List<ThreadItem>>>(emptyMap())` on `ThreadProjection` (moved off `RemoteConversationRepository` itself,
  #912), holds each conversation's ordered thread rows — `message_id`-deduped `MessageItem`s plus
  interleaved `SessionBoundary`s. It is written **only** by the repository's one `init` collector handing
  frames to the projection's public folds, and by the projection's own `appendMessages` called from
  `sendMessage`'s confirmed insert — from the `message` arm (appends each live message), the
  `message_chunk` arm (appends a whole backfill batch), the structured-turn folds (#387 tool rows, #337
  streaming deltas), and the #336 boundary fold. The message rows go through one accumulator:

  ```kotlin
  fun appendMessages(rows: List<Pair<String, Message>>) {  // (conversationId, Message); public since #912
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
          emitAll(threadProjection.observe(conversationId))  // ThreadProjection.observe (#912);
      }                                                       // store already holds ThreadItems (#336)
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
  messages-only preview) was **not** touched; the repository's `threadProjection(conversationId)` cold
  read lost its `.map { MessageItem(it) }` wrap (that method has since been moved and renamed to
  `ThreadProjection.observe`, #912 — a different thing from the `threadProjection` field the repository
  now holds; don't confuse the two).
- **Rides the single existing inbound collector.** A new `TYPE_SESSION_TRANSITION` arm joins the
  `onInbound` demux, beside the `stall` / `queue_state` siblings — **no second subscription**. Gated
  **identically** on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the **reused** #385 supplier —
  no new capability), the repository calls `decodeSessionTransition(envelope)` (the
  `decodeStall`/`decodeQueueState` `try/catch (IllegalArgumentException) { null }` drop idiom, kept on the
  repository because the decoded boundary also feeds the session id and settings-revision writes below)
  and folds via `ThreadProjection.appendSessionBoundary` (a public method on the projection since #912;
  previously a private method on the repository itself).
- **Folds a thread row only — three deliberate non-actions.** Unlike the structured-stream arm it
  surfaces **nothing** on [`liveSessionEvents`](remote-conversation-repository-live-stream-and-modals.md#livesessionevents--the-v2-structured-stream-decode-seam-385)
  (a boundary is a thread row, not a streaming event), does **not** clear a [stall](stall-state.md) (a
  session transition is not turn forward-progress), and **does not dedup** — `appendSessionBoundary`
  pure-appends in arrival order (the wire carries no row id; the repo is connection-scoped per #351, so
  arrival order is correct — the same posture as `applyAssistantDelta`).
- **Routes strictly by the payload's `conversation_id`** into `ThreadProjection`'s
  `threadByConversation[conversationId]` slice, so a boundary can only ever surface in
  `observeMessages(thatId)` — cross-routing is structurally impossible (no "is this conversation
  observed?" guard; an unobserved id simply sits unread). This is the fail-closed client mirror of the
  producer's server-side drop of unbindable transitions (#741).

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the authenticated
Noise channel, and **nothing in the arm, the decode or the projection's fold logs the payload** —
`conversation_id` / session ids / `workspace_cwd` are sensitive (a logged or mis-routed boundary is a
cross-conversation leak); `ThreadProjection` has no logging call at all (#912 security review). See
[Session-transition fold § Trust boundary](session-transition-fold.md#trust-boundary--no-payload-logging).

## History pages fold into the same thread (#645)

Split into [Remote conversation repository — the Phase 4 `ConversationRepository` — reads and the thread store — history paging](remote-conversation-repository-reads-and-thread-store-history-paging.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — History pages fold into the same thread (#645), The walk that finally calls `requestHistory` (#777) and The retry and the two restarts (#778) — moved there verbatim, headings and anchors intact.
