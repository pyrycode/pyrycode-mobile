# Remote conversation repository — the Phase 4 `ConversationRepository` — thread-observable states and live tool rows

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `observeStall(conversationId)` — the thread-observable stall state (#395)

Whether a conversation's remote claude has **stopped making forward progress** (PTY quiet while not
idle, no JSONL progress — typically a screen-parser break). [#395](../codebase/395.md) decodes the
capability-gated v2 `stall` control envelope into a per-conversation `Boolean` the thread layer observes
to react to a stall (the visible reaction is sibling **#396**). The onset/inferred-clearing model lives
in [Stall state](stall-state.md); this section records only how it attaches to the repository — it rides
the **same single inbound collector** as everything else, with **no new class, no new file, no second
subscription**.

- **A fourth connection-scoped projection.** `private val stalledConversations =
  MutableStateFlow<Set<String>>(emptySet())` — membership = stalled. Written **only** from the one
  `init` inbound collector, so onset and clearing never race (single writer); `MutableStateFlow.update {}`
  matches the sibling projections' posture. Empty per connection (#351) → a stall never survives a
  reconnect.
- **Two demux hooks, both inside the existing `interactive` gate (symmetric with #385).**
  - **Onset** — a **new** `TYPE_STALL` arm: `decodeStall(envelope)?.let { stalledConversations.update {
    s -> s + it } }`. Re-receipt for an already-stalled conversation is an idempotent `Set` add.
  - **Clearing** — folded into the **existing** live-session arm: any successfully decoded
    `LiveSessionEvent` is forward progress, so `stalledConversations.update { it - event.conversationId }`
    runs alongside the unchanged `mutableLiveSessionEvents.tryEmit(event)`. **No second decode** — the
    `conversationId` is already on the decoded event; a removal of an absent id is a no-op. This clears
    on all five event types (incl. `turn_state: idle` and `turn_end`). A *malformed* or
    *unrecognized-state* live envelope decodes to `null` and so does **not** clear (we don't attribute
    forward progress we couldn't parse).
- **The method is a pure cold projection** (1:1 with `observeLastMessage`), issuing no request:

  ```kotlin
  override fun observeStall(conversationId: String): Flow<Boolean> =
      stalledConversations.map { conversationId in it }.distinctUntilChanged()
  ```

  `distinctUntilChanged()` means a stall change to **another** conversation does not re-emit this flow;
  a `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
  re-subscription through the facade) gets the current state (`false` until a stall lands) on
  subscription, fanning out from the one inbound consumer.
- **`decodeStall(envelope): String?`** mirrors `decodeLiveSessionEvent`: one `try { decode →
  conversationId } catch (IllegalArgumentException) { null }` (`SerializationException ⊂` it), so a
  malformed `stall` (missing/wrong-typed `conversation_id`) drops the one envelope while the lone
  collector survives (AC #3). The `StallPayloadDto` (`{conversation_id}` only, **no `toEvent()` mapper**
  — a stall is *state*, not a streaming event) lives in `data/network/InteractivePayloads.kt`.
- **On the interface, not the concrete repo — the deliberate inverse of `liveSessionEvents`.** Stall is
  current-value state the thread needs through the [`StableConversationRepository`](stable-conversation-repository.md)
  facade (AC #4), so `observeStall` is a **defaulted** `ConversationRepository` method (`flowOf(false)`),
  with the facade and this repo overriding it. The default absorbs the Fake/test-double cascade (no ≥5
  split), and there is no consumer cascade. Contrast `liveSessionEvents`: `replay=0` events the thread
  does not need as current-value state → concrete-only. See [[post-352-connection-scoped-repo-behind-facade]].

`security-sensitive`, but the narrowest of the interactive boundaries: the `stall` payload carries no
free-form text, the data layer surfaces only a `Boolean`, and `decodeStall` logs nothing on the drop
path. See [Stall state § Security](stall-state.md#security).

## `observeQueue(conversationId)` — the thread-observable queued backlog (#460)

A conversation's ordered list of messages the daemon has **queued** while claude is busy.
[#460](../codebase/460.md) decodes the capability-gated v2 `queue_state` snapshot envelope into a
per-conversation `List<QueuedMessage>` the thread layer observes to render the backlog (the visible render
is the consumer slice **#461**). The full-snapshot model lives in [Queued backlog](queued-backlog.md);
this section records only how it attaches to the repository — it rides the **same single inbound
collector** as everything else, with **no new class, no new file, no second subscription**.

- **A fifth connection-scoped projection.** `private val queuedByConversation =
  MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())` — key = conversation id, value = the
  full ordered backlog (FIFO/wire order). Written **only** from the one `init` inbound collector (single
  writer → snapshots never race); `MutableStateFlow.update {}` matches the sibling projections' posture.
  Empty per connection (#351) → a backlog never survives a reconnect.
- **One demux hook — a full-replace, inside the existing `interactive` gate.** A **new** `TYPE_QUEUE_STATE`
  arm: `decodeQueueState(envelope)?.let { (conversationId, queue) -> queuedByConversation.update { it +
  (conversationId to queue) } }`. Each `queue_state` is the authoritative current backlog
  (`msgqueue.Snapshot`), so the `it + (id to queue)` **overwrites** that conversation's entry and leaves
  every other conversation untouched (AC #3); wire array order is preserved verbatim (AC #1). **Unlike the
  #395 stall arm there is no second hook and no clearing** — a snapshot is replaced by the next snapshot
  (possibly empty), never cleared by forward-progress events, so the live-session arm is **not touched**.
- **The method is a pure cold projection** (1:1 with `observeStall`), issuing no request:

  ```kotlin
  override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> =
      queuedByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()
  ```

  `orEmpty()` gives empty-until-first-snapshot (AC #2); `distinctUntilChanged()` means a `queue_state` for
  **another** conversation, or a value-identical re-snapshot, does not re-emit this flow; a `StateFlow`
  always has a current value, so every collector (including a `flatMapLatest` re-subscription through the
  facade) gets the current backlog on subscription, fanning out from the one inbound consumer.
- **`decodeQueueState(envelope): Pair<String, List<QueuedMessage>>?`** mirrors `decodeStall`: one `try {
  decode → (id, list) } catch (IllegalArgumentException) { null }` (`SerializationException ⊂` it; the
  per-item `Instant.parse` throws the same). A malformed payload — bad `conversation_id`, a bad item, an
  unparseable `ts` — drops the one envelope while the lone collector survives (AC #4); **one bad item drops
  the whole snapshot**. The `QueuedMessageDto` / `QueueStatePayloadDto` decode DTOs + the `toQueue()`
  mapper live in `data/network/InteractivePayloads.kt`; `queued` is the **only** non-strict field
  (nullable-defaulted to tolerate a wire `null`/`[]` empty backlog).
- **On the interface with a default — like `observeStall`, unlike `liveSessionEvents`.** The thread needs
  the current-value backlog through the [`StableConversationRepository`](stable-conversation-repository.md)
  facade (#461 is the first consumer), so `observeQueue` is a **defaulted** `ConversationRepository` method
  (`flowOf(emptyList())`), with the facade and this repo overriding it. The default absorbs the
  Fake/test-double cascade (no ≥5 split); no consumer cascade. See [[post-352-connection-scoped-repo-behind-facade]].

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the
already-authenticated Noise channel, and **nothing in the new arm or the drop branch logs the payload**
(`QueuedMessage.text` is user-authored queued-message content). See
[Queued backlog § Security](queued-backlog.md#security).

## `observeApiRetry(conversationId)` — the thread-observable API-retry state (#593)

Whether a conversation's remote claude is **stuck retrying an API error**, and at which attempt.
[#593](../codebase/593.md) decodes the capability-gated v2 `api_retry` control envelope into a
per-conversation `ApiRetryStatus` the thread layer observes to react (the visible reaction is sibling
**#594**, not yet shipped). The onset/climb/clearing model lives in
[API-retry status](api-retry-status.md); this section records only how it attaches to the repository
— it rides the **same single inbound collector** as everything else, with **no new class beyond the
domain type and DTO, no new file, no second subscription**.

- **A sixth connection-scoped projection.** `private val apiRetryByConversation =
  MutableStateFlow<Map<String, ApiRetryStatus>>(emptyMap())` — a structural sibling of
  `queuedByConversation`, not `stalledConversations`: `api_retry` carries a counter a bare `Set`
  cannot represent. Written **only** from the one `init` inbound collector, so rising and falling
  edges never race (single writer); `MutableStateFlow.update {}` matches the sibling projections'
  posture. Empty per connection (#351) → a retry state never survives a reconnect.
- **One demux hook — a full replace, inside the existing `interactive` gate, with no branch on
  `active`.** A **new** `TYPE_API_RETRY` arm: `decodeApiRetry(envelope)?.let { (conversationId,
  status) -> apiRetryByConversation.update { it + (conversationId to status) } }`. `toStatus()` (§
  below) is the sole owner of edge semantics — a rising edge, a re-fired rising edge with a climbed
  counter, and a falling edge are all just a mapped `ApiRetryStatus` the arm replaces unconditionally;
  a second `if (active)` here would encode the same rule twice. **Unlike the #395 stall arm this has
  no second hook** — it does not touch the live-session arm, so it neither clears an active stall
  (a retry is not forward progress) nor folds a thread row (AC #4 is satisfied structurally, by the
  arm having only this one statement).
- **The method is a pure cold projection** (1:1 with `observeQueue`), issuing no request:

  ```kotlin
  override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> =
      apiRetryByConversation.map { it[conversationId] ?: ApiRetryStatus.NotRetrying }.distinctUntilChanged()
  ```

  `?: NotRetrying` gives not-retrying-until-first-frame; `distinctUntilChanged()` suppresses only
  value-*identical* re-emissions — an `api_retry` for **another** conversation does not re-emit this
  flow, while a **climbed counter is a different `Attempt` value and does reach the observer as a new
  emission**, which is precisely what `stall`'s `Set<String>` model could not do (`true → true` would
  collapse the climb). A `StateFlow` always has a current value, so every collector (including a
  `flatMapLatest` re-subscription through the facade) gets the current status on subscription.
- **`decodeApiRetry(envelope): Pair<String, ApiRetryStatus>?`** mirrors `decodeQueueState`: one
  `try { decode → dto.conversationId to dto.toStatus() } catch (IllegalArgumentException) { null }`
  (`SerializationException ⊂` it). Because `toStatus()` is **total** (never null), structural
  malformation is the only path that drops a frame — an undocumented counter shape maps to
  `ApiRetryStatus.AttemptUnknown` rather than being dropped, since a drop would discard a real retry
  onset. The `ApiRetryPayloadDto` (all four fields strict-required, no nullable latitude) and
  `toStatus()` live in `data/network/InteractivePayloads.kt`.
- **On the interface with a default — like `observeStall`/`observeQueue`, unlike `liveSessionEvents`.**
  The thread needs the current-value retry state through the
  [`StableConversationRepository`](stable-conversation-repository.md) facade (#594 is the first
  consumer), so `observeApiRetry` is a **defaulted** `ConversationRepository` method
  (`flowOf(ApiRetryStatus.NotRetrying)`), with the facade and this repo overriding it. The default
  absorbs the Fake/test-double cascade (no ≥5 split); no consumer cascade. See
  [[post-352-connection-scoped-repo-behind-facade]].

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the
already-authenticated Noise channel, `ApiRetryStatus` is a closed sealed type carrying two `Int`s and
no `String` (no daemon-supplied text can structurally reach the UI through this arm), and **nothing in
the new arm or the decode logs the payload**. See [API-retry status § Security](api-retry-status.md#security).

## `observeCompacting(conversationId)` — the thread-observable compaction state (#596)

Whether a conversation's remote claude is **currently auto-compacting its context**. [#596](../codebase/596.md)
decodes the capability-gated v2 `compacting` control envelope into a per-conversation `Boolean` the
thread layer observes (the visible reaction is sibling **#597**, not yet shipped). The onset/clearing
model lives in [Compacting state](compacting-state.md); this section records only how it attaches to
the repository — it rides the **same single inbound collector** as everything else, with **no new
domain type, no mapper, no new file, no second subscription**.

- **A seventh connection-scoped projection, and the leanest of the family.** `private val
  compactingConversations = MutableStateFlow<Set<String>>(emptySet())` — a structural sibling of
  `stalledConversations`, not `apiRetryByConversation`: `compacting` carries no counter, so the bare
  membership `Set` shape [`stall`](stall-state.md) uses is the right fit, not `api_retry`'s
  payload-carrying `Map`. Written **only** from the one `init` inbound collector, so the rising and
  falling edges never race (single writer). The write is a genuine **read-modify-write** on the set
  (`it + id` / `it - id`), unlike `apiRetryByConversation`'s pure replace, so `MutableStateFlow.update {}`
  is load-bearing here, not merely stylistic — a `.value = … + id` formulation would open a real
  check-then-mutate window. Empty per connection (#351) → a compaction state never survives a reconnect.
- **One demux hook — a membership toggle, inside the existing `interactive` gate, with the edge branch
  living in the arm itself.** A **new** `TYPE_COMPACTING` arm: `decodeCompacting(envelope)?.let {
  (conversationId, active) -> compactingConversations.update { if (active) it + conversationId else it -
  conversationId } }`. Unlike `api_retry`'s "deliberately no branch on `active`" rule, the `if (active)`
  **belongs in this arm**: there is no mapper here to own the edge semantics (the two wire fields already
  are the domain shape, a `String` and a `Boolean`), so the membership transition *is* the edge, expressed
  exactly once — not a duplicated rule. `it - conversationId` on an absent id is a no-op, so a falling
  edge with no prior rising edge is harmlessly inert, and a repeated rising edge is an idempotent `Set`
  add. Like the `api_retry`/`queue_state` siblings and unlike the live-session arm, this folds no thread
  row and does **not** clear an active stall — compaction is claude busy elsewhere, not turn forward
  progress; clearing a stall here would let a daemon suppress the phone's stall indicator by emitting
  `compacting` frames (AC #4 is satisfied structurally, by the arm having only this one statement).
- **The method is a pure cold projection** (1:1 with `observeStall`), issuing no request:

  ```kotlin
  override fun observeCompacting(conversationId: String): Flow<Boolean> =
      compactingConversations.map { conversationId in it }.distinctUntilChanged()
  ```

  Membership over an empty set gives not-compacting-until-the-first-frame with **no `?: false` default
  needed** — "absent" and "not compacting" are the same thing by construction, tidier than
  `observeApiRetry`'s stored falling edge. `distinctUntilChanged()` suppresses only value-*identical*
  re-emissions: a `compacting` for **another** conversation does not re-emit this flow, and a repeated
  rising edge is genuinely nothing new — #593's no-dedup hazard does not transfer, because that one
  existed only to let a climbing counter through, and a bool has no intermediate values to collapse. A
  `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
  re-subscription through the facade) gets the current state on subscription.
- **`decodeCompacting(envelope): Pair<String, Boolean>?`** mirrors `decodeStall`/`decodeApiRetry`: one
  `try { dto.conversationId to dto.active } catch (IllegalArgumentException) { null }`
  (`SerializationException ⊂` it). There is no unrecognized *value* to reject here (`active` is a bool),
  so — unlike `decodeLiveSessionEvent` — structural malformation is the only null path. `CompactingPayloadDto`
  (both fields strict-required, no nullable latitude) lives in `data/network/InteractivePayloads.kt`,
  with no `toX()` mapper: the wire shape already is the domain shape.
- **On the interface with a default — like `observeStall`/`observeQueue`/`observeApiRetry`, unlike
  `liveSessionEvents`.** The thread needs the current-value compaction state through the
  [`StableConversationRepository`](stable-conversation-repository.md) facade (#597 is the first
  consumer), so `observeCompacting` is a **defaulted** `ConversationRepository` method (`flowOf(false)`),
  with the facade and this repo overriding it. The default absorbs the Fake/test-double cascade (no ≥5
  split); no consumer cascade. See [[post-352-connection-scoped-repo-behind-facade]].

`security-sensitive`, but the repository stays plain orchestration: decode runs behind the
already-authenticated Noise channel, the payload carries **only** a routing id and a bool — no
daemon-supplied text or number of any kind can structurally reach the UI through this arm, the
narrowest boundary of the four sibling arms — and **nothing in the new arm or the decode logs the
payload**. See [Compacting state § Security](compacting-state.md#security).

## Live tool-call rows — `applyToolUse` / `applyToolResult` (#387)

Correlate the v2 `tool_use` (start) / `tool_result` (completion)
[`LiveSessionEvent`](live-session-events.md)s into **one evolving thread row** carrying a status
(`Running → Done`/`Failed`). [#387](../codebase/387.md) adds the correlation + status state machine;
the onset/correlation model lives in [Live tool-call](live-tool-call.md), this section records only how
it attaches to the repository. Unlike #395 (a *separate* `Set<String>` projection), a tool row **is** a
thread row, so it folds into the **existing** `threadByConversation` — see [Live tool-call § Chronological
interleave](live-tool-call.md#chronological-interleave-ac-4--why-its-free).

- **Two demux hooks, dispatched inside the existing `interactive` gated live-session arm.** The
  `.let { event -> … }` block (`RemoteConversationRepository.kt:301-305`) gains a `when (event)`
  dispatch **alongside** the unchanged #395 stall-clear and #385 `tryEmit` — **no second subscription,
  no new gate, no new collector**:

  ```kotlin
  when (event) {
      is LiveSessionEvent.ToolUse -> applyToolUse(event)
      is LiveSessionEvent.ToolResult -> applyToolResult(event)
      else -> Unit
  }
  ```

- **`applyToolUse` (`:473`) — append a `Running` row, idempotent on a repeat id.** One atomic
  `threadByConversation.update {}`: if a row with `id == toolUseId && role == Role.Tool` already
  exists, leave it untouched (a duplicate `tool_use` never adds a second row nor resets a finished one
  to `Running`); else append a `Role.Tool` `Message` with `id = toolUseId`, `content = name` (a
  non-empty fallback the UI ignores), `timestamp = Clock.System.now()`, and
  `ToolCall(name, inputSummary, output = "", status = Running)`. The `&& role == Role.Tool` namespaces
  the match so a `toolUseId` can never clobber a real `message_id` row.
- **`applyToolResult` (`:510`) — update the matching row in place, or drop.** One atomic
  `threadByConversation.update {}`: find the row with `id == toolUseId && role == Role.Tool`; if
  absent, **no-op** (a `tool_result` with no prior `tool_use`, including a result-before-use, is dropped
  — no orphan half-row); if present, replace it (position + `timestamp` preserved) with its `toolCall`
  copied as `output = resultSummary`, `status = if (isError) Failed else Done`. A duplicate
  `tool_result` re-applies the same update (idempotent / last-write-wins). `row.toolCall?.copy(...)`
  handles the theoretical null gracefully — no `!!`.
- **Why fold into `threadByConversation`, not a separate flow.** The two folds write the **same**
  `StateFlow` [`observeMessages`](remote-conversation-repository-reads-and-thread-store.md#observemessagesconversationid--the-live-thread-read-313) reads, whose
  `threadProjection` preserves **arrival order with no re-sort** — so a tool row interleaves
  chronologically with messages for free (AC #4); a ViewModel-side merge cannot, because ordering is a
  repository surface. The in-place status flip changes the `data class` row → the projected list is
  unequal → `distinctUntilChanged` re-emits the `Running → Done/Failed` transition. **Not folded into
  `lastMessages`** — a tool invocation never becomes a conversation-list preview.
- **Single writer, no race.** Both folds run on the lone `init` inbound collector (the sole writer of
  `threadByConversation` alongside `sendMessage`'s `update {}` insert), in wire arrival order, so
  insert-then-update never races; an out-of-order result simply finds no row and drops.

`security-sensitive`, but the repository stays plain orchestration: no new parse point (consumes the
already-typed `LiveSessionEvent`), `name`/`inputSummary`/`resultSummary` carried **verbatim** into the
`ToolCall` fields (output-encoding is #388's job), and **nothing in the folds or the drop/no-op branches
logs the payload** (the tool fields may carry sensitive session content). See
[Live tool-call § Security](live-tool-call.md#security).
