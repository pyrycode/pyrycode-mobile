# Remote conversation repository — the Phase 4 `ConversationRepository` — live stream, modal seams and the replay cursor

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

**The retained model menus, the one-shot `request_model_list` ask and their refusal correlation now live in
`ModelMenuProjection`** (#913), an `internal class` in `data/repository/ModelMenuProjection.kt`; **the
clarification batches, their fold and their answer/refusal sends now live in `QuestionBatchProjection`**
(`data/repository/QuestionBatchProjection.kt`), split out the same way `ThreadProjection` was (#912). Each
takes the repository's pump `send`, and `ModelMenuProjection` also takes the repository's
`negotiatedCapabilities` supplier, plus a `nextRequestId` lambda over the repository's one `requestId`
counter — a lambda rather than a bound reference, since both projections are constructed in the property
list above `requestId` and a bound `requestId::incrementAndGet` would read the field before it is
initialised. The repository still owns the single inbound collector and the `interactive` gate on each arm;
`observeModelMenu` reads `ModelMenuProjection.observe`, and the repository's public `questionBatches`,
`answerQuestionBatch` and `refuseQuestionBatch` hand off to `QuestionBatchProjection.batches` / `answer` /
`refuse` (`answerToken` stayed on the repository — it belongs to `answerModal`, not to question batches).
Where the sections below show `modelMenusByConversation`, `askedModelMenus`, `modelListAsks`,
`askForModelMenu`, `onModelListRefusal` or `decodeModelList` as a member of `RemoteConversationRepository`,
read it as the same member, now on `ModelMenuProjection`; where they show `mutableQuestionBatches`,
`foldQuestionFrame`, `heldQuestionBatch`, `sendQuestionFrame` or `questionToken`, read it as the same member,
now on `QuestionBatchProjection` (`foldQuestionFrame` was renamed `apply` on the move).

## `liveSessionEvents` — the v2 structured-stream decode seam (#385)

A hot **`val liveSessionEvents: SharedFlow<LiveSessionEvent>`** (`RemoteConversationRepository.kt:174-180`)
that surfaces the five v2 **binary → phone** structured-stream envelopes — `turn_state`,
`assistant_delta`, `tool_use`, `tool_result`, `turn_end` — decoded into one typed
[`LiveSessionEvent`](live-session-events.md) family. The decode boundary itself (DTOs + mappers +
the gate + drop semantics) is documented in [Live-session events](live-session-events.md); this
section records only how it attaches to the repository.

- **Rides the single existing `pump.inbound` collector.** One new grouped arm joins the `onInbound`
  `when (envelope.type)` demux (`:265-275`) — **no second subscription** (ticket constraint). The
  arm gates on the negotiated `interactive` capability, then calls the private
  `decodeLiveSessionEvent(envelope): LiveSessionEvent?` helper (`:296`) and `tryEmit`s the result.
  Five `TYPE_*` constants join the companion (`:770`+). The same `.let { event -> … }` block also hosts
  the #395 stall-clear and the [#387 tool-call dispatch](remote-conversation-repository-thread-observables.md#live-tool-call-rows--applytooluse--applytoolresult--applytooldenied-387-811)
  — three folds on one decoded event, one gate, one collector. `tool_denied`
  ([#811](https://github.com/pyrycode/pyrycode-mobile/issues/811)) is **not** one of the five: it is
  not a `LiveSessionEvent`, so it gets its own sibling `TYPE_TOOL_DENIED` arm in the same `onInbound`
  `when`, under the same `interactive` gate, calling `applyToolDenied` directly — no `tryEmit`, no
  stall-clear, because a denial is a report rather than forward progress.
- **`SharedFlow`, not `StateFlow`** (`replay = 0`, `extraBufferCapacity = 64`, `DROP_OLDEST`): these
  are *events*, not current-value state. The bounded buffer + `DROP_OLDEST` make `tryEmit`
  **infallible and non-blocking** — load-bearing, so a slow live-event consumer never back-pressures
  the shared inbound collector and stalls `conversations`/`message`/`ack` processing.
- **Gated by a lazy capability supplier.** A new defaulted ctor param
  `negotiatedCapabilities: () -> Set<String> = { emptySet() }` (`:103`), read **per envelope**
  (`CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, before decode, AC#2). It is a supplier, not
  a captured value: the repo is built while the pump is still `Handshaking`, but structured
  envelopes only arrive post-`Open`, so the lazy read always sees the final negotiated set. The
  [coordinator](relay-repository-coordinator.md) wires it from `(pump.state.value as?
  PumpState.Open)?.capabilities` — a non-suspending read that preserves `onConnection`
  cancellation-atomicity. The default `{ emptySet() }` ("gate closed") keeps the two-/three-arg
  constructions (tests, pre-wiring) compiling and surfacing no events — **zero edit fan-out**, the
  same defaulted-param discipline `deviceName` (#359) used.
- **On the concrete repo, not the interface** — the accepted `registerPushToken` (#359) pattern (see
  above): adding it to [`ConversationRepository`](conversation-repository.md) would force the
  [Fake](conversation-repository.md) + [facade](stable-conversation-repository.md) to plumb a flow
  this decode slice doesn't use. Facade/coordinator reachability for the UI consumers
  (#386/#387/#337) is downstream consumer-slice work.

`security-sensitive`, but the repository stays plain orchestration: the decode runs behind the
already-authenticated Noise channel, and **nothing in the new arm or the drop branch logs the
payload** (the text / tool-summary fields may carry sensitive session content). See
[Live-session events § Trust boundary](live-session-events.md#trust-boundary--no-payload-logging).

## `modalEvents` — the v2 permission/choice-modal decode seam (#437)

A second hot **`val modalEvents: SharedFlow<ModalEvent>`** (`RemoteConversationRepository.kt:230-236`), a
verbatim sibling of [`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385),
surfacing the two v2 **binary → phone** modal lifecycle envelopes — `modal_shown`, `modal_dismissed` —
decoded into the typed [`ModalEvent`](modal-events.md) `{ Shown, Dismissed }` family. The decode boundary
itself (DTOs + mappers + verbatim-string rationale) is documented in [Modal events](modal-events.md); this
section records only how it attaches to the repository.

- **Rides the same single existing `pump.inbound` collector** — **no** third subscription. A new arm joins
  the `onInbound` `when (envelope.type)` demux (`:368-381`), beside the [#395 `stall` arm](stall-state.md),
  gated **identically** on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the **reused** #385
  supplier — no new capability, no coordinator/DI change), then calls the private
  `decodeModalEvent(envelope): ModalEvent?` helper (`:479`) and `tryEmit`s the result. Two `TYPE_*`
  constants join the companion (`:1146`+).
- **Decode-and-emit only — two deliberate non-folds.** Unlike the `TYPE_TURN_STATE …` arm this arm does
  **not** fold a thread row (modals are not rows, and this seam never routes on `Shown.conversationId` —
  #816 below) and does **not** clear a [stall](stall-state.md) — a `modal_shown` means `claude` is
  *waiting* for input, **not** turn forward-progress (the inverse of every `LiveSessionEvent`, which clears
  a stall). It is the cleanest of the interactive arms: one decode, one `tryEmit`, no side effects on
  `threadByConversation` / `stalledConversations`.
- **A separate flow + family, not a sixth `LiveSessionEvent`.** Forced by the wire: `modalId`, not
  `conversation_id`, is modal events' correlation key, whereas every `LiveSessionEvent` subtype mandates a
  non-null `conversationId` the structured arm routes on. `Shown.conversationId` (daemon #1065, decoded
  since #816) is a defaulted, display-only scoping stamp — `""` means no thread, never every thread — that
  this decode seam carries but never routes on; `Dismissed` carries no conversation at all. Same
  `SharedFlow` shape (`replay = 0`, `extraBufferCapacity = 64`, `DROP_OLDEST` → infallible non-blocking
  `tryEmit`); **concrete repo only**, not the [`ConversationRepository`](conversation-repository.md)
  interface (the `liveSessionEvents` / #359 `registerPushToken` posture; the #439 render consumer's
  facade/coordinator reachability is downstream).
- **`decodeModalEvent`** copies the `decodeStall` / `decodeLiveSessionEvent` `try { when(type) … } catch
  (IllegalArgumentException) { null }` drop idiom — a malformed payload yields `null`, the one envelope is
  dropped, the lone collector survives. Both `toEvent()` mappers are **total** (`class`/`source`/`outcome`
  carried verbatim, no enum drop) — see [Modal events § Verbatim strings](modal-events.md#verbatim-strings-no-enum-coercion-ac-3).

`security-sensitive` (high-consequence — the answer #438 injects a decision into `claude`), but the
repository stays plain orchestration: decode runs behind the authenticated Noise channel, and **nothing in
the new arm or the drop branch logs the payload** (`title`/`prompt`/option-`label` are operator content —
pyrycode#701 "never log modal body text"). See [Modal events § Trust boundary](modal-events.md#trust-boundary--no-payload-logging).

## The compaction-boundary decode+fold seam (#874)

A new `TYPE_COMPACTION_BOUNDARY = "compaction_boundary"` arm joins the `onInbound` `when (envelope.type)`
demux, gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the reused #385 supplier) — a finished
compaction, folded into the thread as a `ThreadItem.CompactionBoundary` divider rather than emitted on
[`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385).

- **`decodeCompactionBoundary(envelope)`** decodes the `CompactionBoundaryPayloadDto` and
  `Instant.parse(envelope.ts)` inside one `try`/`catch (IllegalArgumentException)` — the `decodeBanner`
  drop idiom — and routes by the payload's own `conversation_id`. `appendCompactionBoundary` end-appends
  the mapped row inside one atomic `threadByConversation.update`, unless the thread already holds one
  stamped that `ts` (`holdsCompactionBoundary`) — the `appendBanner` dedup shape, because the daemon hands
  the same `ts` to both this arm and a later history page holding the same frame.
- **Renders no `LiveSessionEvent` and clears no stall.** The frame is conversation-scoped with no
  `turn_id` and can arrive with no preceding `compacting` edge; `compacting` alone still drives the
  status-area indicator (unchanged by this arm — see [Compacting
  indicator](compacting-indicator.md#edge-cases--limitations)). Exactly one write, and nothing on this
  arm logs the envelope, its `conversation_id`, or either count.
- **Counts and trigger are narrowed at the DTO mapper (`toRow`), not here** — a non-negative safe integer
  or `null` per count, an exact-`"manual"` boolean for the open `trigger` string — so no claude-authored
  token reaches the row or a log.
- The row, its label rules, and its cache exclusion are documented at [Session boundary delimiter §
  CompactionBoundaryDivider](session-boundary-delimiter.md#compactionboundarydivider-874) and
  [Conversation cache](conversation-cache.md); this section records only the decode seam.

`security-sensitive`, the same posture as the sibling arms above: decode runs behind the authenticated
Noise channel, and a malformed frame drops only itself — the lone collector survives.

## The model-refusal decode+fold seam (#875)

`TYPE_MODEL_REFUSAL_FALLBACK` (`"model_refusal_fallback"`) and `TYPE_MODEL_REFUSAL_NO_FALLBACK`
(`"model_refusal_no_fallback"`) join the `onInbound` `when (envelope.type)` demux as one shared arm, gated
on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — claude refused a turn on one model and either
retried it on another or did not, folded into the thread as a `ThreadItem.ModelRefusal` rather than
emitted on [`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385). Since the
\#912–#916 repository split moved thread writes into `ThreadProjection`, this arm is a one-line delegate —
`threadProjection.applyModelRefusal(envelope)` — with the decode, the dedup fold, the row and its cache
exclusion documented at [Model refusal row](model-refusal-row.md); this section records only the routing
arm and its two type constants. Exactly one write, and nothing here logs any field but the routing
`conversation_id` — every other field is claude's.

`security-sensitive`, the same posture as the sibling arms above: decode runs behind the authenticated
Noise channel, and a malformed frame drops only itself.

## `model_announced` / `session_facts` — the announced-model and session-facts readings (#890)

Two per-turn status readings, each its own held `StateFlow<Map<String, T>>` in its own file —
`AnnouncedModelProjection` and `SessionFactsProjection` — the [status-projection](remote-conversation-repository.md#status-projections-one-file-per-status-event)
shape, same as `ResettingProjection`. Both frames come off claude's `system/init` line, are conversation-scoped,
arrive once per turn and are **not** deduplicated by the daemon: `TYPE_MODEL_ANNOUNCED = "model_announced"`
and `TYPE_SESSION_FACTS = "session_facts"` each get their own `onInbound` arm, gated on the negotiated
`interactive` capability, calling the projection's `apply(envelope)`.

- **Decode-or-drop, the sibling idiom.** Each projection's private decoder wraps its DTO mapping in one
  `try`/`catch (IllegalArgumentException)` that returns `null` and discards the caught throwable — nothing
  logs a field, including `conversation_id`. `ModelAnnouncedPayloadDto.toReading()` also drops a frame whose
  `model` is empty, the wire contract's one field-level rule; `SessionFactsPayloadDto.toFacts()` is a total
  verbatim copy, since both its strings may legitimately be empty.
- **Latest always wins.** `apply` does a plain `update { it + (id to reading) }` — no latch on the first
  frame, no merge with the prior reading. A `/model` turn still announces the old model, so a consumer that
  latched the first announcement would go stale.
- **Cleared only by that conversation's `session_transition`**, next to `resettingProjection.clear` in the
  same arm — neither frame carries a falling edge of its own. A reconnect or host switch clears both
  readings too, for free, through `StableConversationRepository.switchToLive<T?>(null)`: a fresh connection
  publishes a fresh `RemoteConversationRepository`, and the facade's `flatMapLatest` drops the old one's
  reading. A frame naming another conversation never touches this one — `observe(conversationId)` is
  `.map { it[conversationId] }.distinctUntilChanged()` over the shared map, the same isolation every
  sibling projection gets from keying on the wire's own `conversation_id`.
- **Never writes `SessionSettings`.** `observeAnnouncedModel`/`observeSessionFacts` are read-only additions
  beside `observeSessionSettings`, `observeResetting`, and the other status reads; neither arm touches
  `sessionSettingsCommands`, the stall state, the thread or a turn. `SessionFacts.permissionMode` is
  claude's claim, never the #650 confirmed permission reading — a dedicated test
  (`neitherFrame_touchesStallThreadOrSessionSettings`) proves a `session_facts` frame claiming
  `bypassPermissions` leaves `observeSessionSettings`'s `permissionMode` unchanged.
- **Renders nothing.** Both readings are data-layer only; the UI that surfaces "what claude says it's
  running" is a separate, not-yet-shipped ticket. See [`AnnouncedModel`/`SessionFacts`](conversation-repository.md#shape)
  for the domain types and their untrusted-text KDoc.

## `context_usage` — the context-usage reading (#945)

A per-conversation reading of how full claude's context window is, held in its own file
`data/repository/ContextUsageProjection.kt` — the [status-projection](remote-conversation-repository.md#status-projections-one-file-per-status-event)
shape. `TYPE_CONTEXT_USAGE = "context_usage"` decodes the daemon's push, sent after every completed turn.
Wire SSOT: pyrycode `docs/protocol-mobile.md` § `context_usage`.

- **Decode-or-drop, the sibling idiom, plus one value reject.** `ContextUsageProjection`'s private decoder wraps
  `ContextUsagePayloadDto` decoding in the usual `try`/`catch (IllegalArgumentException) { null }`, discarding the
  caught throwable. The four scalars (`conversation_id`, `total_tokens`, `max_tokens`, `percentage`) are
  strict-required with no Kotlin default, so a missing one fails the structural decode rather than becoming a
  zero reading — the contract's own point, that a client must not read "no data" as "empty context".
  `ContextUsagePayloadDto.toReading()` then rejects a negative `percentage` (`null`, folded the same as a
  structural failure), and a malformed `as_of` throws out of `Instant.parse` into the same catch. `model` and the
  three inventories (`categories`, `mcp_tools`, `memory_files`) are not declared on the DTO at all, so
  [`MobileJson`](mobile-protocol-v2-wire-layer.md)'s `ignoreUnknownKeys` discards them at the boundary — this
  slice holds numbers only, and the desktop breakdown popover they feed has no mobile consumer yet.
- **Latest always wins** — `apply` does the same plain `readingByConversation.update { it +
  (id to reading) }` the #890 pair uses. Routing is the payload's own daemon-authored `conversation_id` — a
  frame for one conversation never touches another's reading, pinned by a dedicated test.
- **The phone sends no ask, since [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946) Rework 1.**
  `ContextUsageProjection` originally sent a fire-and-forget `request_context_usage` on a conversation's 0→1
  subscriber edge (the `ModelMenuProjection` shape) and again after a `session_transition`, tracked by an
  `observerCounts: ConcurrentHashMap<String, Int>`. [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946)
  was that ask's first production subscriber (`ThreadViewModel.runConfigFlow`), and its scripted `reconnect`
  scenario found that the daemon's `handleRequestContextUsage` answers a mid-turn ask only after the turn ends,
  blocking every later frame on that connection's serial frame worker behind it — a reconnect mid-turn then
  deadlocked, because the queued `send_message` that would end the turn was itself stuck behind the ask. The
  phone cannot tell whether a turn is open when it subscribes (a fresh connection has seen no `turn_state`), so
  asking only when believed-idle would still race the next queued turn. `ask`, `observerCounts`,
  `negotiatedCapabilities`, `nextRequestId` and the constructor's `send` parameter were removed outright rather
  than gated — `ContextUsageProjection` now takes no constructor arguments. `RequestContextUsagePayloadDto` and
  `TYPE_REQUEST_CONTEXT_USAGE` stay as wire documentation, marked unsent. The daemon-side fix is
  [pyrycode/pyrycode#2563](https://github.com/pyrycode/pyrycode/issues/2563) (open); once it lands, a future
  mobile ticket can restore the ask from git history — none exists yet.
- **`onSessionTransition` only clears, it no longer re-asks.** The conversation's old reading described a
  session that is now gone, so it is dropped (`readingByConversation.update { it - conversationId }`) with no
  follow-up request. A conversation therefore shows "unavailable" from a transition until its **next completed
  turn** on the current connection pushes a fresh reading — the same is true of a brand-new idle conversation,
  which the removed ask used to fill in immediately.
- **The reconnect case needs no dedicated hook, only for a different reason than before.**
  [`StableConversationRepository.observeContextUsage`](stable-conversation-repository.md) is
  `switchToLive<ContextUsage?>(null) { it.observeContextUsage(conversationId) }`, the `observeAnnouncedModel`
  shape: a fresh connection publishes a fresh `RemoteConversationRepository`, and `flatMapLatest` cancels the old
  subscription and subscribes the new one. State is connection-scoped by construction — one
  `ContextUsageProjection` instance per repository, and a reconnect or host switch starts from an empty map with
  nothing to carry over. Previously this 0→1 edge was also what sent a fresh connection's ask; now it sends
  nothing, and the reading simply stays absent until the next turn-end push on that connection.
- **Never writes `SessionSettings`, and is never derived from it.** `SessionSettings.usedTokens`/`.windowTokens`
  are transcript-derived numbers on an unrelated read; `percentage` here is claude's own arithmetic, held
  verbatim, and the two are never cross-checked or substituted for each other.
- **Rendered since [#946](https://github.com/pyrycode/pyrycode-mobile/issues/946), split from
  [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591).** The composer footer's `Cxt:` segment and the
  Status sheet's Context-window section both read the identical value off `ThreadRunConfig.contextPercent` — see
  [Thread composer footer § Context usage segment](thread-composer-footer.md#context-usage-segment-946) and
  [StatusSheet — running model and context window readings § `ContextWindowSection`](status-sheet-readings.md#contextwindowsection).
  A rung-3 scenario, `interactiveTurn_pingPrompt_footerShowsContextUsage`, proves one live reading after a real
  turn — see [e2e coverage](../../e2e-interactive-stream.md). See [`ContextUsage`](conversation-repository.md#shape)
  for the domain type and its untrusted-text KDoc (there is none to carry — every string on the frame is left
  undecoded).

`security-sensitive`, the same posture as the sibling arms above: decode runs behind the authenticated Noise
channel, and nothing on the arm, the projection or the ask logs a field — including `conversation_id`, the one
value every branch treats purely as a routing/map key.

## `questionBatches` — the v2 clarification-batch decode+fold seam (#822)

A **held `StateFlow<List<QuestionBatch>>`** (`QuestionBatchProjection.mutableQuestionBatches` /
`batches`, exposed on the repository as `questionBatches`, #913), not an event
stream — the deliberate difference from
[`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385) and
[`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437) above. Decodes the two v2
**binary → phone** clarification-batch envelopes — `question_shown` (claude's whole `AskUserQuestion`
call in one frame) and `question_dismissed` (its retirement) — into `QuestionBatch`
(`data/model/QuestionBatch.kt`) via the DTOs and `toBatch()` in the new `data/network/QuestionPayloads.kt`
(kept out of `InteractivePayloads.kt`, which #810 also touches). Not a modal: a batch carries its own
`conversation_id` and several can be outstanding at once, so none of this touches `ModalEvent`,
`ModalUiState` or `currentModal`.

- **Rides the same single existing `pump.inbound` collector.** A new grouped
  `TYPE_QUESTION_SHOWN, TYPE_QUESTION_DISMISSED` arm joins the `onInbound` `when (envelope.type)` demux,
  gated identically on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the reused #385 supplier). It
  calls a private `foldQuestionFrame(envelope)` that decodes inside one `try { … } catch
  (IllegalArgumentException) { }` and applies `List<QuestionBatch>.withShown` / `.withDismissed` via
  `mutableQuestionBatches.update {}` — the `decodeModalEvent` drop idiom, but folding straight into held
  state rather than `tryEmit`ting an event. A malformed frame leaves the held list untouched and the
  single collector survives; nothing is logged (the exception can quote the JSON input).
- **Held state, not `replay = 0`, because the daemon's reconcile is a burst, not one event.** Protocol §
  Reconnect / Backfill: on connect the daemon re-sends every outstanding batch for every conversation, in
  no guaranteed order. A `SharedFlow` folded downstream — the `liveSessionEvents`/`modalEvents` shape —
  can lose part of that burst to a late subscriber, the same failure mode [#492](current-modal-state.md)
  fixed for modals by hoisting the fold to the coordinator. The fix here is different: fold directly into
  a `StateFlow` at this seam, so a late reader of `questionBatches` always sees whatever the burst has
  folded so far rather than a partial replay of it. No hoist is needed for this state to survive a late
  subscriber — see the [coordinator](relay-repository-coordinator.md#questionbatches--the-v2-clarification-batch-projection-822)
  for why the state itself still resets on reconnect (the opposite of `currentModal`).
- **The empty-`questions` and repeated-id rules live on the pure fold, not the decode.**
  `QuestionShownPayloadDto.toBatch()` always produces a `QuestionBatch`, even with an empty `questions`
  list; `List<QuestionBatch>.withShown` (`data/model/QuestionBatch.kt`) is what turns an empty batch into
  a no-op (it neither adds nor replaces a held batch) and replaces a held batch sharing a
  `questionBatchId` in place rather than appending a second. `withDismissed` removes by id and is a no-op
  for an unknown or already-removed one. Both rules mirror desktop's `reduceQuestionBatches`.
- **`multi_select` needs an explicit type check, or a quoted `"false"` silently decodes true-typed.**
  [`MobileJson`](mobile-protocol-v2-wire-layer.md) is non-lenient — a JSON number where a `String` is
  expected fails decode — but it still parses a *quoted* `"false"` into a Kotlin `Boolean`, because
  `JsonPrimitive.booleanOrNull` reads the primitive's content regardless of whether it is a JSON string.
  `QuestionDto.multiSelect` is decoded as a raw `JsonPrimitive`; a private `strictBoolean()` rejects a
  string or non-boolean primitive itself, with a static message naming only the key (the
  `readEffectiveEffort` posture — never interpolate the offending value). A decode test that never
  supplies a `"multi_select":"false"` fixture passes just as well with the type check missing, so the
  string-typed case has to be an explicit rejection test, not just the `question_shown_zero.json` numeric
  `false` fixture.
- **On the concrete repo only, like `modalEvents`** — not on `ConversationRepository`. The panel
  ([#661](https://github.com/pyrycode/pyrycode-mobile/issues/661)) and the answer/refusal sends
  ([#825](https://github.com/pyrycode/pyrycode-mobile/issues/825)) reach it through
  [`RelayRepositoryCoordinator.questionBatches`](relay-repository-coordinator.md#questionbatches--the-v2-clarification-batch-projection-822),
  the same concrete-repo-then-coordinator reachability path as `modalEvents`.

`security-sensitive`, but plain orchestration: decode runs behind the authenticated Noise channel, and
`question`, `header`, `label` and `description` are claude-authored and unsanitised — held as inert
fields and never used as a key (the two ids are daemon-asserted and are the only keys), never logged, and
never placed in an exception message.

## `backgroundTasks` — the v2 background-task decode+fold seam (#677)

A **held `StateFlow<Map<String, BackgroundTaskRoster>>`** (`backgroundTaskProjection.rosters` /
`backgroundTasks`), the [`questionBatches`](#questionbatches--the-v2-clarification-batch-decodefold-seam-822)
shape rather than an event stream. Decodes the four `interactive`-gated **binary → phone** frames about
work claude left running past its turn — `background_task_started`, `background_task_updated`,
`background_task_roster` and `background_task_progress` (#1042) — into `BackgroundTask`
(`data/model/BackgroundTask.kt`) via the DTOs in `data/network/BackgroundTaskPayloads.kt` and the fold in
`data/repository/BackgroundTaskProjection.kt`. Daemon state, not turn content: no thread row is folded and
no stall is cleared.

- **Rides the same single existing `pump.inbound` collector.** A grouped `TYPE_BACKGROUND_TASK_STARTED,
  TYPE_BACKGROUND_TASK_UPDATED, TYPE_BACKGROUND_TASK_ROSTER, TYPE_BACKGROUND_TASK_PROGRESS` arm joins the
  `onInbound` `when (envelope.type)` demux, gated on the same `CAPABILITY_INTERACTIVE in
  negotiatedCapabilities()` check as the question arm. It calls `backgroundTaskProjection.apply(envelope)`,
  which decodes inside one `try { … } catch (IllegalArgumentException) { }` per frame and folds into held
  state through `mutableRosters.update {}` — a malformed frame is dropped, the single collector keeps
  running, and nothing is logged (the exception can quote the JSON input).
- **One task set per conversation, joined on `task_id` in whatever order the four frames arrive.** A
  `started` or a `roster` row upserts a task directly. An `updated` or `progress` frame for a task the
  conversation does not hold yet — a terminal update racing ahead of its `started` frame, a roster split
  across frames, or progress arriving before either — waits in a private
  `pending: conversationId -> taskId -> Slots` map, collector-confined like `mutableRosters`; a roster for
  that conversation clears its entry, keeping it bounded. Neither an `updated` nor a `progress` frame alone
  ever creates a conversation's entry — that would read as an explicit empty roster, a fact the daemon
  never stated for that conversation.
- **`background_task_progress` holds the task's current activity, replaced whole on each frame.**
  `BackgroundTask.progress: BackgroundTaskProgress?` carries its own `description` (never the task's
  opening one), `subagentType`, `lastToolName`, and the three cumulative counters `totalTokens`, `toolUses`
  and `durationMs` as `Long` — decoded that wide because the daemon's `int` is Go's 64-bit int, and because
  a legitimate long-running task can post a large `duration_ms`. A later frame replaces the earlier one
  whole; nothing is summed, and a lower counter than the last reading is accepted as sent, since claude can
  restart its own counters mid-task. `progress` keeps its own `truncatedFields`, separate from the task's
  opening `truncatedFields` — the two frames report different truncation, not the same list.
- **A finished task carries no progress, enforced where `BackgroundTask` is built, not where progress is
  applied.** `task()` and `withSlots()` both compute `isFinished` first and then set
  `progress = if (isFinished) null else slots.progress`; `Slots.with(update, terminal = true)` also clears
  `progress` on the slot itself. Applying this only inside the progress handler would miss the case where a
  terminal update and progress both arrive before the task's `started` frame and sit in the same pending
  `Slots` — the eventual start must still show a finished task with no progress, which is why the clearing
  lives at the two build sites instead.
- **A roster replaces the conversation's set wholesale**, including `dropped_tasks`; a task it omits is
  dropped, not marked finished. A row repeating a `task_id` is deduplicated to its first occurrence so
  every consumer can key a list by `taskId` — a daemon or relay bug that repeated a row would otherwise
  hand a `LazyColumn` two rows sharing one key. A negative `dropped_tasks` is out of contract for the
  wire and drops the whole frame rather than corrupting `BackgroundTaskRoster.liveCount`.
- **The finished mark is the one piece of state a reconnect must not erase.** An `updated` frame with a
  non-empty `status` (the wire's contract is `status != ""`, not a closed set of values) both fills the
  task's `finish` slot on this connection and calls `FinishedBackgroundTasks.mark(conversationId, taskId)`
  on the host-lifetime instance the coordinator owns — see
  [Background-task roster](relay-repository-coordinator-seams-and-passthroughs.md#background-task-roster-677)
  for why that instance, not this projection's held map, is what has to outlive the connection. A task's
  `isFinished` is recomputed on every fold as `finish != null || finished.contains(conversationId,
  taskId)`, so a roster that re-lists an already-finished task keeps it finished even though the roster
  itself carries no `status`.
- **Two disjoint update slots, not one.** `BackgroundTask.latestUpdate` holds the last mid-life (empty
  `status`) update; `BackgroundTask.finish` holds the last terminal one. A terminal frame never erases the
  held `patch`, and a later mid-life frame never erases a held `status`/`summary` — each keeps its own
  frame's `truncatedFields`, following the same `truncated_fields` convention as the rest of
  `InteractivePayloads.kt` (`List<String>?`, explicit `null` = nothing cut).
- **On the concrete repo only, like `questionBatches`** — not on `ConversationRepository`. The Actions-menu
  panel and live-task count that read this seam ([#678](https://github.com/pyrycode/pyrycode-mobile/issues/678))
  reach it through
  [`RelayRepositoryCoordinator.observeBackgroundTasks` / `observeLiveBackgroundTaskCount`](relay-repository-coordinator-seams-and-passthroughs.md#background-task-roster-677),
  never the raw whole-host map, for the same one-conversation-inside-another risk `observeQuestionBatch`
  guards against.

`security-sensitive`, but plain orchestration: decode runs behind the authenticated Noise channel, and
`description`, `patch`, `status`, `summary` and progress's own `description`/`subagentType`/`lastToolName`
are claude-authored and unsanitised — held as inert fields, never parsed (`patch` included), never
evaluated or executed, never used as a key besides `taskId`/`conversationId`, and never logged. Rendering
these three progress strings is deferred to the panel follow-up ticket, which must treat them as
plain, length-bounded text — never markup, a link or an action — and must tolerate a negative or
decreasing counter.

## The model-list and slash-command-list menu retentions (#791, #792, #882)

Moved to [Remote conversation repository — the model-list and slash-command-list menu retentions](remote-conversation-repository-model-and-slash-command-menus.md) on 2026-09-24 to keep this document under the 50000-byte size cap the docs guard enforces: the `TYPE_MODEL_LIST` inbound arm and its connection-scoped retention (#791), the `request_model_list` on-demand ask (#792), and the `TYPE_SLASH_COMMAND_LIST` inbound arm (#882) it left room for.

## `answerModal` / `cancelModal` — the v2 modal answer/cancel control-send (#438)

The **outbound (phone → binary) half** of the permission modal feature: the two control messages that
answer or cancel a modal the [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437)
seam (#437) surfaced. [#438](../codebase/438.md) implements them as **two concrete suspend methods** that
mirror [`registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359) (#359)
exactly — a pure request/reply over the **reused** `sendAndAwaitReply` (#346) primitive, with no new
correlation infra, no projection mutation, and no interface/facade/Fake touch:

```kotlin
suspend fun answerModal(modalId: String, optionId: String)   // modal_answer{modal_id, option_id, answer_token}
suspend fun cancelModal(modalId: String)                     // modal_cancel{modal_id}
```

Each builds an `Envelope(id = requestId.incrementAndGet(), type = TYPE_MODAL_ANSWER/_CANCEL, ts =
Clock.System.now()…, payload = MobileJson.encodeToJsonElement(dto))` and `sendAndAwaitReply`s it,
**discarding the empty `{}` ack** — success is simply "the call returned without throwing". The
[`ModalAnswerPayloadDto` / `ModalCancelPayloadDto`](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346)
encode DTOs (new `data/network/ModalOutboundPayloads.kt`) are the **encode mirror** of #437's decode DTOs.

- **`modalId`/`optionId` are echoed verbatim — never parsed or validated.** They are the opaque tokens
  the caller (#439, via the #437 decode) hands in; the daemon validates `modalId` against its own
  outstanding modal (first-answer-wins; a stale id is rejected) and maps `optionId` against its own
  recorded option list (pyrycode#701/#703/#706). The phone asserts only *which* modal and *which* offered
  option — never a conversation. A client-side check here would be security theatre that could diverge
  from the daemon.
- **`answer_token` — a deterministic, stateless idempotency key (the one real design decision).** Minted
  by a **pure** `private fun answerToken(modalId, optionId) = "${modalId.length}:$modalId:$optionId"` — no
  stored state, no random, no clock. Purity gives both AC#2 properties **by construction**: the same
  `(modalId, optionId)` always yields the same token, so a **resend of one logical answer carries the
  identical token** and the daemon collapses the replay to a no-op (stability with no remembered cache,
  no eviction lifecycle); **distinct answers always yield distinct tokens** (both ids participate — two
  different modals each answered with option id `"allow"` are distinct answers). The modal id is
  **length-prefixed** so opaque ids containing the `:` separator can't alias (`("a:b","c")` → `3:a:b:c`
  vs `("a","b:c")` → `1:a:b:c`). It is **not** authorization — that is `modalId` validity (#706) + the
  per-device answer gate (#702, default OFF); pyrycode#701 makes *secrecy an explicit non-goal*, so the
  predictable derivation is **safe** (a guessed token grants nothing) and *stronger* than a remembered
  UUID for the stability AC. (Rejected: random UUID + a cache keyed by `(modalId, optionId)` — buys
  nothing the pure derivation lacks while adding mutable shared state, thread-safety, and an eviction
  question coupling the slice to the inbound `modal_dismissed` stream.)
- **`modal_cancel` carries no token.** Cancel is not idempotency-keyed (pyrycode#701 shape `{modal_id}`);
  a re-cancel of an already-resolved modal is a stale-`modal_id` reject the daemon handles.
- **Failure surfaces deterministically (AC#4), nothing swallowed.** A not-`Open` session
  (`pump.send` → `false`) throws `IllegalStateException` synchronously (no hang); a server `error` —
  **including the ungranted-device reject** (pyrycode#702/#703) — propagates as
  [`RelayErrorException`](mobile-protocol-v2-wire-layer.md)`(code, retryable, message)`. This slice does
  **not** catch or interpret the reject: switching the modal to read-only on it is **#440's** concern.
  No new error type, no new mapping — inherited from `sendAndAwaitReply`/`mapError` verbatim.
- **`modal_dismissed` is not awaited here.** The `ack` confirms the daemon *received and will process*
  the answer; the modal's eventual *resolution* arrives asynchronously as the inbound `modal_dismissed`
  event on [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437) (#437). Two
  distinct signals — this slice owns only send + ack/error correlation.
- **Concrete-only, like `registerPushToken`/`liveSessionEvents`/`modalEvents`.** Modal answering is a
  device/control capability, not conversation CRUD, so it is **not** on the
  [`ConversationRepository`](conversation-repository.md) interface, the
  [facade](stable-conversation-repository.md), or the Fake (which would force a +2-file plumbing cascade
  for a method they never call). The #439 render consumer reaches these by fetching the concrete
  `RemoteConversationRepository` (the `registerPushToken` reachability story) — downstream consumer-slice
  work, not this slice's. Two `TYPE_MODAL_ANSWER`/`TYPE_MODAL_CANCEL` companion constants join the block.

`security-sensitive` (a high-consequence outbound control — the answer injects a permission decision into
`claude`), verdict **PASS** (architect self-review + code review): the design keeps the daemon the sole
authority (the payload carries only opaque ids + a non-authoritative dedup key), mints nothing
trust-bearing, and **never logs the payload** (the modal may name a sensitive command/path — e.g. "Allow
`rm -rf build/`"), mirroring `registerPushToken`'s never-log-the-token posture. See the
[#438 implementation notes](../codebase/438.md) for the line refs, the test matrix, and the *untested
length-prefix injectivity* lesson.

## `recordReplayCursor(envelope)` — the replay-cursor side-write (#412)

The **first line** of `onInbound` (`:218`), *before* the `when` demux, folds each interactive frame's
durable [`Envelope.eventId`](mobile-protocol-v2-wire-layer.md) into the reconnect-spanning
[`ReplayCursor`](replay-cursor.md) ([#412](../codebase/412.md)) — the high-water mark
[#413](https://github.com/pyrycode/pyrycode-mobile/issues/413) advertises as `last_event_id` on mid-turn
reconnect:

```kotlin
private fun recordReplayCursor(envelope: Envelope) {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        envelope.eventId?.let { replayCursor.record(it) }
    }
}
```

- **Rides the single existing inbound consumer** — no second `pump.inbound` subscription (the AC #2
  constraint, same as `liveSessionEvents`). The `replayCursor` is a fifth **defaulted** ctor param
  (`= ReplayCursor()`, `:117`); the [coordinator](relay-repository-coordinator.md) threads its shared,
  process-scoped instance in so the mark **survives connection churn** (this repo is rebuilt each
  reconnect — it cannot own the cursor).
- **Envelope-level, type-agnostic, pure side-write.** It reads `envelope.eventId` directly, independent
  of whether the per-type *payload* decodes — so a malformed-payload frame with a valid `event_id` still
  advances the cursor. It has **no feedback into delivery**: no `when` arm, no `liveSessionEvents`
  emission, and no delivery reads the cursor, so the recording alone cannot mute/drop a live event.
- **Gated on `interactive`** (defence-in-depth, symmetric with the structured-stream + `stall` arms);
  a non-interactive frame carries no `event_id` so nothing records (AC #3). **Throw-free** by
  construction (already-decoded `Long?` + set-membership + a pure max-fold), so it cannot kill the single
  inbound collector — see [Replay cursor](replay-cursor.md) for the holder, the fail-closed
  positive/strict-greater fold, and the trust boundary.

## The resync arm — reset the cursor + surface the gap (#417)

The **reaction** to the daemon's `resync` marker — `type = "resync"`, binary → phone, inline
`{conversation_id}`, **no** `event_id` (the daemon's signal that the position the phone advertised as
`hello.last_event_id` aged out of its bounded ring, so gap-free in-ring replay is impossible).
[#417](../codebase/417.md) adds a `TYPE_RESYNC` arm to `onInbound`'s `when (envelope.type)` demux
(`:340`), gated **identically** to the `recordReplayCursor` / `stall` / structured-event arms:

```kotlin
TYPE_RESYNC -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        replayCursor.reset()                              // unconditional on the type match
        resyncConversationId(envelope)?.let { id ->
            mutableLiveSessionEvents.tryEmit(LiveSessionEvent.ReplayGap(id))
        }
    }
}
```

- **Two effects, split by trust posture.** The **reset** ([`ReplayCursor.reset()`](replay-cursor.md),
  #412) is **unconditional** on the type match — the cursor is process-global (not per-conversation), so
  a malformed/absent `conversation_id` still clears it; the next reconnect then advertises a **fresh**
  position (omits `last_event_id`, #416) instead of mis-resuming. The **gap surface** — a
  [`LiveSessionEvent.ReplayGap`](live-session-events.md) on the **existing**
  [`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385) flow — is
  **conditional** on a decodable `conversation_id` (it needs an id to route). The principle: the safety
  action (avoid mis-resuming) must not depend on untrusted payload shape; only the routing-dependent part
  may.
- **No DTO, no decode — a throw-free structural read.** `resyncConversationId(envelope): String?`
  (`:434`) reads `conversation_id` directly off `envelope.payload` cast to `JsonObject` as a
  `JsonPrimitive` string (no `decodeFromJsonElement`, mirroring the server's payload-less inline-struct
  precedent), returning `null` when the payload is not a `JsonObject`, the field is absent, or it is not
  a JSON string. Because it is pure structural access it **cannot throw** — no `try/catch` needed
  (unlike `decodeStall` / `decodeLiveSessionEvent`), and it cannot kill the single inbound collector.
- **No record-then-reset conflict.** `recordReplayCursor` runs first (the first line of `onInbound`) but
  a `resync` carries **no** `event_id`, so it records nothing for this envelope — no ordering hazard with
  the `reset()` that follows.
- **Fail-closed `interactive` gate.** A non-interactive phone never advertised a cursor (the cursor only
  advances under the same gate, so `latest` is always `null` there), so a spurious `resync` from a
  buggy/hostile daemon is ignored — no reset (a no-op anyway), no surface.
- **`backfill_since` full reload is deferred** — no daemon-side message-history store / handler exists
  yet. This arm's contract ends at reset-the-cursor + surface-the-gap.

`security-sensitive`, but the repository stays plain orchestration: the `conversation_id` is read
throw-free and used **only** to tag the `ReplayGap` for routing (never a path, never an authz decision),
and **nothing on the arm logs** the envelope or its payload. Blast radius of a spurious/forged `resync`
is self-inflicted and bounded (a false gap-surface + a fresh re-advertise) — see
[#417 § Security](../codebase/417.md#security) and [Replay cursor § Reacting](replay-cursor.md#reacting--the-resync-marker-417).
