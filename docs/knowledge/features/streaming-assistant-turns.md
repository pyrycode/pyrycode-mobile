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
        liveSessionEvents.map(ThreadInput::Live),
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
`sessionId`/`timestamp` derived from the last finished item (render-irrelevant; no live clock, so the
fold stays pure).

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

The synthetic item's id is the `turnId`, a distinct namespace from the server `message_id`, so the
`LazyColumn` key `"msg:$turnId"` never collides with `"msg:$messageId"` — and they are never both
present (finalise drops the streaming item in the **same** emission the finished message appears, so the
row swaps seamlessly with identical content).

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
- **The #184 typewriter restarts on every delta.** `StreamingAssistantBody` keys its reveal on
  `content`, so each appended delta restarts the 50-char/sec reveal from 0. By existing #184 design,
  now exercised live for the first time; self-limiting (reveal outpaces typical delta cadence, settles
  to static `MarkdownText` on `turn_end`). VM-side reveal hoisting is a possible future follow-up if a
  bursty live stream reads as a stutter — see [Message bubble](message-bubble.md).

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

- [#337 implementation notes](../codebase/337.md) — files, line refs, patterns, lessons.
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
- Memory: [[phase4-v2-wire-no-streaming]] — streaming is a separate `assistant_delta` envelope, not an
  `isStreaming` flag on `message`.
- Server SSOT: pyrycode#572→#589 (`assistant_delta` wire + relay emit), #608→#615/#616 (event-stream
  bridge), #607 (wire types + capabilities), ADR 025 § Phase 2 structured streaming, EPIC pyrycode#596.
