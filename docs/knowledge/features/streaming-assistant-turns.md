# Streaming assistant turns — live `assistant_delta` accumulation in the thread

How the daemon's incremental `assistant_delta` stream becomes a **single growing
`isStreaming == true` assistant message** in the conversation thread that settles into the finished
message when the turn ends. This is the data/ViewModel path that makes the **real backend** thread
stream the way the fake-backed thread already does (#184). Landed in [#337](../codebase/337.md) (split
from [#313](../codebase/313.md)), part of the Phase 2 structured-streaming exit-gate (pyrycode#596,
ADR 025).

It is the third VM-layer consumer of the generic
[`liveSessionEvents`](live-session-events.md) seam, after the
[thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) and the
[live tool-call row](live-tool-call.md) ([#387](../codebase/387.md)) — but the first whose output is a
**thread row** rather than a sibling signal. **No new visual:** the growing message is the already-built
[`MessageBubble`](message-bubble.md) streaming affordance (#184); this slice only feeds the real
backend signal into that unchanged render.

## The data path

```
assistant_delta / turn_end envelopes  ──(#385 decode, capability-gated)──▶  LiveSessionEvent.AssistantDelta(convId, turnId, seq, text)
        │                                                                    LiveSessionEvent.TurnEnd(convId, turnId, stopReason)
        │                                                                       on RemoteConversationRepository.liveSessionEvents
        ▼
RelayRepositoryCoordinator.liveSessionEvents : Flow<LiveSessionEvent>   ◀── #406 generic seam (reconnection-surviving)
        │  injected at the AppModule ThreadViewModel factory (already wired by #406; no new Koin binding)
        ▼
ThreadViewModel.threadItems  ◀── #337: merge(observeMessages, liveSessionEvents) → scan(ThreadFold) → render
        │  swapped in place for the observeMessages arm of the 5-flow `state` combine
        ▼
ThreadUiState.items : List<ThreadItem>   →  ThreadScreen LazyColumn  →  MessageBubble (isStreaming ? StreamingAssistantBody : MarkdownText)
```

Unlike `isThinking`/`isStalled` — sibling `StateFlow`s the stateless screen takes as **separate**
params — the streaming message is a `ThreadItem` **in the thread**, so it must land in
`ThreadUiState.items`. The seam is therefore the `items` derivation itself: the #313 finished-message
projection from `observeMessages` and the live `liveSessionEvents` stream are folded together and the
result replaces the `observeMessages` arm of the `state` combine (arity stays 5).

## The fold

```kotlin
private val threadItems: Flow<List<ThreadItem>> =
    merge(
        repository.observeMessages(conversationId).map(ThreadInput::Finished),
        liveSessionEvents.map { ThreadInput.Live(it) },
    ).scan(ThreadFold(emptyList(), null)) { fold, input -> fold.reduce(input, conversationId) }
        .map { it.render() }
        .distinctUntilChanged()
```

The accumulator holds the latest finished projection plus the current streaming turn (`null` between
turns):

```kotlin
private data class StreamingTurn(
    val turnId: String,
    val text: String,                       // accumulated AssistantDelta text, in seq order
    val lastSeq: Int,                        // ordering/dup guard for the current turn
    val ended: Boolean,                      // turn_end seen → render isStreaming=false (settled)
    val baselineAssistantIds: Set<String>,   // assistant MessageItem ids present at turn start — the finalise oracle
    val startedAt: Instant,                  // first live delta's phone-clock arrival
)
private data class ThreadFold(val finished: List<ThreadItem>, val stream: StreamingTurn?)
```

`reduce` is **pure, total, and never logs** any payload field. The branch table (live events route by
`conversationId` **first**):

| Input | Action |
|---|---|
| `Finished(items)` | Set `finished = items`. If `stream != null` and `items` gains an assistant `MessageItem` whose id ∉ `baselineAssistantIds` → the turn's persisted message arrived → `stream = null` (**finalise**). |
| `AssistantDelta` for **this** conv, **new** `turnId` | Start a turn: snapshot `baselineAssistantIds = assistantIds(finished)`, `text = delta.text`, `lastSeq = seq`. Supersedes any unfinalised prior turn. |
| `AssistantDelta`, same `turnId`, `seq > lastSeq` | Append: `text += delta.text`, `lastSeq = seq`. |
| `AssistantDelta`, same `turnId`, `seq <= lastSeq` | Ignore (replayed / out-of-order — AC #2). |
| `TurnEnd` for **this** conv, matching `turnId` | `stream.ended = true` — settle the typewriter; keep the row until the finished message replaces it. `stopReason` unused. |
| any live event for **another** conversation, or `TurnState`/`ToolUse`/`ToolResult` | No-op. |

`render` returns `finished` when idle; otherwise `finished + MessageItem(synthetic)` appended last, with
`id = turnId`, `role = Assistant`, `content = stream.text`, `isStreaming = !stream.ended`,
`sessionId` derived from the last finished item and `timestamp = stream.startedAt`.
`ThreadInput.Live.receivedAt` captures the phone clock before reduction; the first
accepted delta stores it in `startedAt`. Appends, ignored deltas, projection
backfill and turn end retain it; a new turn captures its own arrival. Reduction
and rendering remain pure. Borrowing the preceding row's timestamp (or epoch zero
with no history) incorrectly classifies a new live-first reply as pre-open and
skips its reveal. The timestamp is consumed by
[thread-open reveal initialization](message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184),
so it is part of rendering behavior rather than incidental metadata. A **key-uniqueness guard** ([#425](../codebase/425.md)) sits at the top of `render`,
before the synthetic is built: the synthetic is appended **only when no finished `MessageItem` already
carries this turn's id** —
`if (finished.any { it is ThreadItem.MessageItem && it.message.id == turn.turnId }) return finished`.
See *Why the synthetic key never collides* below for why this is load-bearing against the live daemon.

## Finished rows are now per-segment, not one bubble per turn ([#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350))

Before #1350, the finished projection folded every `assistant_delta` of one turn into a single assistant
row, found wherever it sat, so text claude wrote after a tool call drew in that one bubble, above the
tool. `HistoryPageReducer.withAssistantDelta` — shared by the live lane and history replay — now extends
the **last** row only when it is already a segment of the same turn; anything else (a tool row, a user
message, a session boundary, or no row) opens a new segment at the end. A turn that goes text, tool, text
now draws as two assistant rows with the tool row between them, live and on replay alike. The segment key,
the per-delta record, and how a page boundary or a merge rejoins a segment a seam cut in two are in
[Remote conversation repository § Assistant reply segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350).

This changes what the `ThreadFold` accumulator above folds against, in two ways:

- **Only the newest segment can stream.** `ThreadProjection.observe` settles every streaming row but the
  last (`withOnlyLastRowStreaming`) before `Finished` ever reaches this fold, so a segment that a tool row
  or a user message now follows is already static by the time it arrives here. Since [#1558](https://github.com/pyrycode/pyrycode-mobile/issues/1558),
  `observe` runs that settle rule over the thread **with this device's still-queued own echoes taken out**,
  then appends them back at the end — so a queued echo, which sits after the running reply in store order,
  never counts as the row that makes the reply "not last" and stops its streaming caret. **One known gap:**
  `CachingConversationRepository.observeMessages` runs `mergeCachedRows` *after* that normalisation, so a
  cached row the merge places above the live stream can still draw as streaming past its own turn — the
  normalisation covers the projection, not a later composition of it. The verifier's PR #1420 review flagged
  this as a SHOULD FIX, reproducible as a mid-turn reconnect; it was not fixed in #1350. A fix re-applies
  `withOnlyLastRowStreaming()` at that composition point, with a reconnect-mid-turn test — the general lesson
  being that a normalisation applied inside one projection's `observe` does not cover a reader that composes
  that projection with another source afterwards.
- **The key-uniqueness guard gained a segment clause**, described below.

## Why the dedup is structural, not id-correlated

The finalise step must reconcile the in-flight turn with the finished `message` from #313's path, but:

1. **The finished `message` carries no `turn_id`** — `{conversation_id, message_id, role, text}`
   (server SSOT, [[phase4-v2-wire-no-streaming]]). Correlation by id is impossible.
2. **`observeMessages` and `liveSessionEvents` are two separate hot flows** fed by uncoordinated server
   emitters — there is **no wire ordering** between `turn_end` and the finished `message`.

So the streaming item is dropped the moment the finished list gains an assistant message id that was
**absent at turn start** — *this turn's persisted message has arrived*, whether before or after
`turn_end`. This is **race-direction-independent**: the ticket's fallback ("clear on `turn_end`") would
flicker a gap if `turn_end` won the race and a transient duplicate if the finished `message` won — AC #3
forbids both. Both directions are tested.

### Why the synthetic key never collides — and why the structural finalise alone wasn't enough

The synthetic item's id is the `turnId`, and the thread keys every `MessageItem` as `"msg:<id>"`. #337
originally **assumed** `turnId` lived in "a distinct namespace from the server `message_id`" and that the
synthetic and the finished message were "never both present." Both assumptions held against the fake and
**broke against the live pyrycode `main` daemon** ([#425](../codebase/425.md), surfaced by the #421
rung-3 e2e): the daemon may set `turnId == message_id`, so the synthetic and the finished message produce
the **same** `"msg:<id>"` key, and when both are in the list `LazyColumn` throws
`IllegalArgumentException: Key … was already used`.

The structural finalise above does **not** catch this. In the live failure ordering the finished
projection carrying the colliding message is present *before* the first delta starts the turn, so the
colliding id is **already in `baselineAssistantIds`** at turn start — the finalise only fires on an
assistant id **absent** at turn start, so it never sees a "new" id and never drops the synthetic. (This is
the same `baselineAssistantIds` mechanism, hitting the one ordering #337's § Open questions framed
backwards — it expected a backfilled *new* id to finalise *early*, "never a duplicate"; the real failure
is the opposite, a *baseline* id that finalise can never react to.)

The fix is the render-time key-uniqueness guard ([#425](../codebase/425.md)): the synthetic is appended
only when no finished `MessageItem` already carries `turn.turnId`. It is **source-independent** (makes no
assumption about the relationship between `turnId` and `message_id`) and **total over every interleaving**,
so the `"msg:<id>"` key is unique however the fold state arose. It satisfies both no-crash (AC #1) and
settle-to-exactly-one (AC #2) in one clause — a `stream:`-key prefix would stop the crash but still
double-render the colliding-at-baseline window. The guard and the baseline-diff finalise are
**belt-and-suspenders, both deterministic**: the finalise drops the stream in the non-colliding case, the
guard guarantees key uniqueness in the colliding case; neither relies on the other. In the colliding
ordering `stream` lingers (non-rendered) until the next turn supersedes it — harmless, and
`distinctUntilChanged` absorbs the no-op re-emissions so there is no flicker.

**The guard gained a segment clause ([#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350)).**
Once a turn's text can fold into more than one finished row, a bare-id check alone is not enough: a later
segment is keyed `"<turnId>#<seq>"`, not `turn.turnId`, so the original guard would miss it and redraw the
turn's live text a second time beside the finished segment. `ThreadFold.render` now also holds the synthetic
back when the finished list carries *any* assistant row whose `segment.turnId` equals the streaming turn's
id (`Message.isSegmentOf`), alongside the unchanged bare-id clause that still matches a row cached before
segments existed.

## Lifecycle, errors, edge cases

- **Single source of state.** `threadItems` feeds the one `state` `StateFlow`; the fold's accumulator
  lives inside the `scan`, scoped to the combine's `stateIn(viewModelScope, WhileSubscribed(5_000))`. On
  resubscribe after the 5 s window the scan restarts from `ThreadFold(emptyList(), null)`;
  `observeMessages` re-emits the current finished list and any in-flight accumulated text is re-derived
  from zero — same lifecycle as `isThinking`, acceptable because the user is off-screen during the
  window.
- **Inert default.** The fake graph and existing tests inject `liveSessionEvents = emptyFlow()`; `merge`
  then yields only the `observeMessages` arm, so the thread behaves exactly as #313 — no regression.
- **Errors — none.** The reduction is total over the sealed `LiveSessionEvent` (exhaustive `when`, no
  `else`); no exceptions thrown or caught, no result type crosses a layer. Wrong-conversation events,
  out-of-order `seq`, and unmatched `turn_end` all degrade to no-op.
- **Cancelled / refusal turn** (`turn_end` with no finished `message` ever) — the settled
  `isStreaming = false` partial text remains rendered (the partial output claude produced) and is
  superseded when the next turn starts. No infinite "streaming" state, no orphan.
- **Reveal lifetime follows the streaming body's composition.** A row timestamped
  before the thread opened starts fully visible; a post-open row starts at zero,
  as does a standalone bubble without an opening time. Reopening captures a fresh
  opening time, so the arrived prefix is immediate. Since #1754,
  `StreamingAssistantBody` consumes the latest text on a stable 33 ms clock, revealing
  whitespace-delimited words at about 30 words/sec and larger steps when behind. Its
  15-tick countdown resets only when caught up; arrivals preserve the prefix and
  outstanding deadline, reaching each snapshot within 495 ms of reveal-clock time
  plus presentation. The earlier content-keyed producer retained its state value,
  contrary to the historical claim that every delta reset the prefix to zero, but
  restarted its delay and local budget; frequent arrivals could prevent progress.
  Both reveal and independent caret producers cancel on disposal. Finalization
  switches to full static `MarkdownText`, removing the caret. See
  [Message bubble](message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184)
  and its [step and frequent-arrival coverage](message-bubble-testing.md#testing).
- **End-to-end reveal observation remains pending.**
  [#1765](https://github.com/pyrycode/pyrycode-mobile/issues/1765) owns the manually
  gated real-Claude `InteractiveStreamE2ETest` observation and the held-stream
  `DeterministicInteractiveStreamE2ETest` twin, observing the reply before turn end
  and caret removal on finalization. Neither observation has been executed for
  #1754; the local step and Compose-clock tests cover its timing contract.

## Testing

The held-stream deterministic scenario captures the displayed prefix's reply ID
before sending the fixture-release message. That send is real user input and can
produce another assistant turn after the fixture completes. Select exactly one
assistant with the captured `held.id` for completion, retaining duplicate-ID
rejection and the exact text, non-streaming, idle, displayed-body and caret checks.
A conversation-wide assistant singleton can fail despite correct streaming (#1793).
See the [stream scenario](../../e2e-interactive-stream.md#scenarios-454) for the
causal release and its harness.

## Security

`security-sensitive`. Two threats, both covered:

- **Per-conversation confinement (AC #2/#3).** The reduction checks `event.conversationId ==
  conversationId` **first** for every live event (mirrors `thinkingTransition`) — a foreign
  conversation's `assistant_delta`/`turn_end` is dropped before it can touch the accumulator. The
  `baselineAssistantIds` snapshot and the finished list are both conversation-scoped by #313. The
  `assistantDelta_forOtherConversation_neverAddsItem` test is the oracle.
- **No new render sink, no logging.** The verbatim untrusted `AssistantDelta.text` is only
  **concatenated** into `Message.content` and routed through the *same* `MessageBubble` path
  (`StreamingAssistantBody` / `MarkdownText`) that already renders finished assistant text — identical
  trust class, identical sink, output-encoding owned by the existing `MarkdownText` (#129). The fold
  performs **no logging** of delta text, `turnId`, or any payload field (an explicit MUST — honoring the
  [`LiveSessionEvent`](live-session-events.md) verbatim-untrusted-text contract).

Architect self-review **PASS**; code review **PASS** with zero findings.

## Related

- [#337 implementation notes](../codebase/337.md) — files, line refs, patterns, lessons (point-in-time;
  predates the #425 collision correction above).
- [#425 implementation notes](../codebase/425.md) — the render-time key-uniqueness guard that lands the
  `turnId == message_id` fix on `main`; corrects the now-false "distinct namespace / never both present"
  assumptions.
- [#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350) — per-segment assistant rows: a turn's
  text, tool, text now draws as two bubbles around the tool row, live and on replay. See
  [Remote conversation repository § Assistant reply segments](remote-conversation-repository-assistant-reply-segments.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350)
  and [ADR 0007](../decisions/0007-assistant-reply-segment-key-and-seam-join.md).
- [#1419](https://github.com/pyrycode/pyrycode-mobile/issues/1419) — a `turn_end` can reach the client before
  the rows it ends (a history page holding only the `turn_end`, or a live `turn_end` ahead of the page with
  the deltas); those rows used to enter streaming and never settle. See
  [Remote conversation repository § A turn_end that settles rows which have not arrived yet](remote-conversation-repository-assistant-reply-segments.md#a-turn_end-that-settles-rows-which-have-not-arrived-yet-1419).
- [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) — the decode seam that
  produces `LiveSessionEvent.AssistantDelta`/`TurnEnd`; this slice realizes its "assistant_delta
  accumulation belongs to a consumer slice" deferral.
- [Turn-state thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) — the sibling VM
  reduction of the same seam (to `isThinking`); the `liveSessionEvents` consumption + already-wired ctor
  param precedent.
- [Live tool-call](live-tool-call.md) ([#387](../codebase/387.md)) — the other thread-row consumer of
  the seam (tool rows); that one folds into `data/`, this one folds at the VM.
- [Remote conversation repository](remote-conversation-repository.md) /
  [Conversation repository](conversation-repository.md) ([#313](../codebase/313.md)) — the
  finished-message `observeMessages` thread this folds the live stream into.
- [Message bubble](message-bubble.md) (#184) — the unchanged streaming render
  (`StreamingAssistantBody` typewriter while `isStreaming`, `MarkdownText` once settled).
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; the `state` combine's second arm is
  now `threadItems`.
- [Relay repository coordinator](relay-repository-coordinator.md) ([#406](../codebase/406.md)) — owns
  the generic `liveSessionEvents` seam this consumes.
- Render regression coverage: [#432 scripted-stream thread harness](../codebase/432.md) — the Layer-1a
  `ScriptedThreadHarness` drives scripted deltas through **this** repo fold into `ThreadScreen` and asserts
  the finalized message, joining the previously-separate fold (unit) and render (component) coverage.
- Memory: [[phase4-v2-wire-no-streaming]] — streaming is a separate `assistant_delta` envelope, not an
  `isStreaming` flag on `message`.
- Server SSOT: pyrycode#572→#589 (`assistant_delta` wire + relay emit), #608→#615/#616 (event-stream
  bridge), #607 (wire types + capabilities), ADR 025 § Phase 2 structured streaming, EPIC pyrycode#596.
