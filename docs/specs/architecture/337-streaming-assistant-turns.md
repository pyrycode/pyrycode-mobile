# Spec #337 — Surface live streaming assistant turns (`assistant_delta`) in the thread

**Size:** S · **`security-sensitive`** (conversation-scoping is an info-disclosure property — see § Security review).
Split from #313. Builds on the shipped decode substrate #385 and the `isThinking`/`isStalled`
VM-layer pattern (#406/#396).

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:157-258` | **The edit site.** The `state` combine (5 flows; `items` comes solely from `observeMessages`) and the `isThinking`/`isStalled` sibling-StateFlow precedent: `mapNotNull`/`scan`-style reduction over `liveSessionEvents`, the **conversation-id guard first** (`thinkingTransition` line 249), `AssistantDelta → null` (drops text today). `liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow()` ctor param (line 105) already wired. |
| `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` (full) | The event shapes. `AssistantDelta(conversationId, turnId, seq, text)`, `TurnEnd(conversationId, turnId, stopReason)`. **`TurnState` carries no `turnId`.** The class KDoc's verbatim-untrusted-text contract (lines 16-21) — load-bearing for § Security. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-23, 160-180` | `observeMessages(conversationId): Flow<List<ThreadItem>>` and `ThreadItem.MessageItem(message)` / `SessionBoundary`. The finished-message thread from #313 lands here. |
| `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` | The synthetic streaming message shape: `Message(id, sessionId, role, content, timestamp, isStreaming, toolCall=null)`. Field is **`content`** (not `text`); `Role.Assistant`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt:90-129` | `AssistantMessage` routes `isStreaming==true` → `StreamingAssistantBody` (typewriter reveal, #184) and `isStreaming==false` → `MarkdownText`. **No new render** — the synthetic item flows through this unchanged. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:143-206` | `LazyColumn`, `reverseLayout=true`, key `"msg:${message.id}"`, the `hasStreamingMessage` derivedState + auto-scroll. Confirms the streaming item needs a **stable per-turn id distinct from the finished `message_id`**. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:190-207, 363-379` | `liveSessionEvents: SharedFlow` (replay=0, DROP_OLDEST) decodes `assistant_delta`/`turn_end` via `toEvent()`; `observeMessages` delivers the finished `message` live. **Two separate hot flows** → no wire-ordering guarantee between `turn_end` and the finished `message` (drives the dedup design). |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:119-239, 1185-1204` | The test idiom to mirror: `makeVm(..., liveSessionEvents)`, `vmWithLiveEvents`, the `MutableSharedFlow<LiveSessionEvent>` + `collect{}` + `advanceUntilIdle` pattern, the `turnState` helper, `ACTIVE_CONV`. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:102` | `ThreadViewModel(..., get<RelayRepositoryCoordinator>().liveSessionEvents)` — **already wired; do not touch.** |
| Memory `phase4-v2-wire-no-streaming.md` + server spec `pyrycode#632` | Confirms: the finished `message` payload is `{conversation_id, message_id, role, text}` — **no `turn_id`**; the structured stream (`assistant_delta`…`turn_end`) and the coarse `message` path are separate server emitters (uncoordinated ordering). `MessagePayloadDto.toMessage`'s `isStreaming=false` STAYS correct. |

## Context

The live backend thread (#313) currently snaps in only *finished* messages: `ThreadUiState.items`
comes solely from `observeMessages`, where every `MessageItem.message.isStreaming == false`. The v2
streaming wire landed (server pyrycode#572→#589, bridge #608→#615/#616, all CLOSED) — but as a
**separate `assistant_delta` envelope**, not an `isStreaming` flag on `message`. The mobile decode
substrate (#385) already turns those envelopes into `LiveSessionEvent` on
`RemoteConversationRepository.liveSessionEvents`. Today that stream feeds **only** `isThinking`
(#406), which maps `AssistantDelta → null` and discards its text.

This slice accumulates `AssistantDelta` text into a single growing `isStreaming == true`
`MessageItem` in the thread, and settles it to the finished `message` when the turn ends — so the
real backend thread streams the way the fake-backed thread already does. **No new visual, no
data-layer change, no DI change** — purely the `ThreadViewModel` `items` derivation.

## Design

### Altitude: VM-layer, single file

All work is in `ThreadViewModel.kt`. The established board pattern is VM-layer consumption of
`liveSessionEvents` (`isThinking`, `isStalled`). Unlike those two — which are *sibling* `StateFlow`s
the screen takes as separate params — the streaming message is a **`ThreadItem` in the thread**, so
it must land in `ThreadUiState.items`, not in a sibling flow. The seam is therefore the `items`
derivation, exactly the choice the ticket flagged.

All new types are **private** to `ThreadViewModel.kt` (no new exported type, no ktlint single-class
filename trigger — see memory `ktlint-filename-rule-single-class`).

### Why a fold over *both* flows (not a sibling flow + stateless combine)

The finalise/dedup step must reconcile the in-flight turn with the finished `message` from #313's
path — and **the finished `message` carries no `turn_id`** (verified against the server structs,
memory `phase4-v2-wire-no-streaming`). So correlation cannot be by id. Worse, on mobile
`observeMessages` and `liveSessionEvents` are two separate hot flows fed by uncoordinated server
emitters — **there is no reliable ordering between `turn_end` and the finished `message`**. A naive
"clear the streaming item on `turn_end`" (the ticket's fallback suggestion) therefore flickers in
*both* race directions: a gap if `turn_end` wins, a transient duplicate if the finished `message`
wins. AC #3 forbids both.

The robust, ordering-independent strategy keeps the finished list and the streaming accumulator in a
**single fold** so the dedup is a structural comparison, not an ordering assumption:

> At turn start, snapshot the set of assistant `MessageItem` ids already in the finished list. The
> streaming item is finalised (dropped) the moment the finished list gains an assistant `MessageItem`
> whose id is **not** in that snapshot — i.e. *this turn's persisted message has arrived*, whether it
> arrived before or after `turn_end`.

### New private types

The merged-input sum type the fold consumes:

```kotlin
private sealed interface ThreadInput {
    data class Finished(val items: List<ThreadItem>) : ThreadInput  // from observeMessages
    data class Live(val event: LiveSessionEvent) : ThreadInput      // from liveSessionEvents
}
```

The fold state — the latest finished projection plus the current streaming turn (or `null` when
idle):

```kotlin
private data class StreamingTurn(
    val turnId: String,
    val text: String,                       // accumulated AssistantDelta text, in seq order
    val lastSeq: Int,                        // ordering/dup guard for the current turn
    val ended: Boolean,                      // turn_end seen → render isStreaming=false (settled)
    val baselineAssistantIds: Set<String>,   // assistant MessageItem ids present at turn start
)

private data class ThreadFold(val finished: List<ThreadItem>, val stream: StreamingTurn?)
```

### Reduction contract

`private fun ThreadFold.reduce(input: ThreadInput, conversationId: String): ThreadFold` — pure,
side-effect-free, **no logging** (see § Security). Branch table:

| Input | Action |
|---|---|
| `Finished(items)` | Set `finished = items`. If `stream != null` **and** `items` contains an assistant `MessageItem` whose id ∉ `stream.baselineAssistantIds` → the turn's persisted message arrived → `stream = null` (finalise). |
| `Live(AssistantDelta d)`, `d.conversationId == conversationId`, **new** `turnId` (`stream == null` or `stream.turnId != d.turnId`) | Start a turn: `stream = StreamingTurn(d.turnId, d.text, d.seq, ended=false, baselineAssistantIds = assistantIds(finished))`. A new `turnId` supersedes any unfinalised prior turn. |
| `Live(AssistantDelta d)`, same `turnId`, `d.seq > stream.lastSeq` | Append: `text = stream.text + d.text`, `lastSeq = d.seq`. |
| `Live(AssistantDelta d)`, same `turnId`, `d.seq <= stream.lastSeq` | Ignore (out-of-order / replayed delta — AC #2 "in seq order"). |
| `Live(TurnEnd e)`, `e.conversationId == conversationId`, `stream?.turnId == e.turnId` | `stream = stream.copy(ended = true)` — settles the typewriter; the item stays rendered until the finished `message` replaces it. `stopReason` is unused this slice. |
| `Live(_)` for any other conversation, or `TurnState`/`ToolUse`/`ToolResult` | No-op (the conversation-id guard is checked **first**, mirroring `thinkingTransition`). |

`assistantIds(items)` = the set of `MessageItem.message.id` where `role == Role.Assistant`.

### Render contract

`private fun ThreadFold.render(): List<ThreadItem>` — returns `finished` when `stream == null`;
otherwise `finished + ThreadItem.MessageItem(syntheticStreamingMessage)` appended last (newest).
The synthetic `Message`:

- `id = stream.turnId` — stable per turn, distinct namespace from the server `message_id`, so the
  LazyColumn key `"msg:$turnId"` never collides with `"msg:$messageId"` (and they are never both
  present: the finalise step drops the streaming item in the same emission the finished message
  appears).
- `role = Role.Assistant`, `content = stream.text`, `isStreaming = !stream.ended`.
- `sessionId` / `timestamp` — render-irrelevant for an appended assistant row (`AssistantMessage`
  shows only `content`); derive from the last finished `MessageItem` (fallback: empty string /
  `Instant.fromEpochMilliseconds(0)`). **Do not** call a live clock — keep the fold pure/testable.

### Flow wiring (replaces the `observeMessages` arg in the `state` combine)

```kotlin
private val threadItems: Flow<List<ThreadItem>> =
    merge(
        repository.observeMessages(conversationId).map(ThreadInput::Finished),
        liveSessionEvents.map(ThreadInput::Live),
    )
        .scan(ThreadFold(emptyList(), null)) { fold, input -> fold.reduce(input, conversationId) }
        .map { it.render() }
        .distinctUntilChanged()
```

Then the existing `state` combine swaps `repository.observeMessages(conversationId)` for
`threadItems` — **combine arity stays at 5**, every downstream field (`items`, `hasMessages`)
unchanged in shape. `hasMessages` becoming true for a streaming-only thread is correct (the streaming
item *is* a message → show the list, not the empty state). The item is appended after the last
session boundary → full alpha (not de-emphasized), correct for the newest row.

### Data flow

```
observeMessages(convId): Flow<List<ThreadItem>> ──map→ Finished ─┐
                                                                 ├─ merge ─ scan(ThreadFold) ─ map{render} ─ distinctUntilChanged ─→ threadItems
liveSessionEvents: SharedFlow<LiveSessionEvent> ──map→ Live ─────┘                                                                        │
                                                                                                                          (used in the 5-flow `state` combine in place of observeMessages)
```

## State + concurrency model

- **Single source of state.** `threadItems` feeds the one `state: StateFlow<ThreadUiState>`; no
  parallel mutable state. The fold's accumulator lives inside the `scan` operator, scoped to the
  `state` combine's `stateIn(viewModelScope, WhileSubscribed(5_000))`. On resubscribe after the 5 s
  window the scan restarts from `ThreadFold(emptyList(), null)`; `observeMessages` re-emits the
  current finished list to rebuild, and any in-flight accumulated streaming text is re-derived from
  zero (acceptable — same lifecycle as `isThinking`; the user is not on-screen during the window).
- **Dispatcher.** Inherits the VM's Main-bound flow collection; the fold is pure CPU (string concat,
  set membership) — no dispatcher hop, no IO.
- **Hot vs cold.** `liveSessionEvents` is a shared hot flow (replay=0); `observeMessages` is cold and
  re-emits on subscribe. `merge` + `scan(initial)` handles the cold-first / hot-later interleaving;
  the initial `ThreadFold` renders `emptyList()`, absorbed by `distinctUntilChanged`.
- **Inert default.** The fake graph and existing tests inject `liveSessionEvents = emptyFlow()`;
  `merge` then yields only the `observeMessages` arm and the thread behaves exactly as #313 — every
  pre-existing `ThreadViewModelTest` stays green with no fixture change.
- **No new coroutine, no `GlobalScope`, no manual cancellation** — the `scan` is an operator inside
  the existing `stateIn`, cancelled with it.

## Error handling

The reduction is total over `ThreadInput` and the sealed `LiveSessionEvent` (exhaustive `when`, no
`else`). Failure modes:

- **Wrong-conversation event** — dropped by the conversation-id guard (first check). Not an error;
  it is the AC #2/#3 confidentiality property.
- **Out-of-order / duplicate `seq`** — ignored via the `seq <= lastSeq` guard (AC #2).
- **`turn_end` with no matching in-flight turn** (e.g. `turnId` mismatch, or already finalised) —
  no-op.
- **Turn that never produces a finished `message`** (cancelled / refusal `stopReason`) — `turn_end`
  sets `ended=true`; the accumulated text remains rendered as a settled `isStreaming=false` message
  (the partial output claude produced), and is superseded when the next turn starts. No infinite
  "streaming" state, no orphan. This is the graceful degradation, not an error path.

No exceptions are thrown or caught; no result type crosses a layer. The UI surfaces nothing new —
failures degrade to "the finished-message-only thread from #313."

## Testing strategy

Unit (`./gradlew testDebugUnitTest --tests "...ThreadViewModelTest"` — note: bare `test --tests`
fails, see memory `gradle-single-test-class-task`). Drive the VM with a controllable
`liveSessionEvents` (`MutableSharedFlow<LiveSessionEvent>`) **and** a controllable `observeMessages`.
Add a small test-double repo exposing a `MutableStateFlow<List<ThreadItem>>` for `observeMessages`
(the existing `FakeConversationRepository` cannot push a finished message mid-test); mirror the
`vmWithLiveEvents` idiom and assert on `vm.state.value.items`. Scenarios (bullets, not code):

- **growing streaming message** — seed `turn_state{thinking}` then `AssistantDelta(seq 0 "Hel")`,
  `(seq 1 "lo")` → `items` ends with one `MessageItem`, `content == "Hello"`, `isStreaming == true`,
  re-emitting on each delta; only **one** streaming item exists.
- **seq order + dedup** — deltas arriving `seq 0,1` accumulate; a replayed `seq 1` or an out-of-order
  `seq 0` after `seq 1` is ignored (text unchanged) (AC #2).
- **conversation-id scoping** — an `AssistantDelta` for `"other-conversation"` never adds an item to
  this thread (AC #2; mirrors `isThinking_otherConversation_doesNotAffectFlag`).
- **finalise: `turn_end` then finished message** — after deltas, emit `turn_end`; assert the item is
  now `isStreaming == false` with the full accumulated text and still single; then push the finished
  `message` (new assistant `MessageItem`, distinct id) into `observeMessages` → assert `items`
  contains **only** the finished message (streaming item dropped), no duplicate (AC #3, #5).
- **finalise: finished message before `turn_end`** (the race) — push the finished `message` first →
  streaming item dropped immediately, no duplicate; the later `turn_end` is a no-op (AC #3).
- **ordering / dedup with backfill** — a pre-seeded finished thread (several messages); a streaming
  turn appends exactly one item at the end without reordering the prior items; on finalise the
  prior items are untouched (AC #4).
- **cancelled turn** — deltas then `turn_end{cancelled}` with no finished message ever → the settled
  `isStreaming=false` partial message remains (no orphan streaming state).
- **inert default** — `liveSessionEvents = emptyFlow()` → `items` equals the `observeMessages`
  projection verbatim (the #313 thread), proving no regression.

`ComposeTestRule` is **not** needed — there is no new composable; `MessageBubble`/`ThreadScreen`
already render `isStreaming` (#184) and have their own tests. The behaviour under test is entirely
the VM reduction.

## Open questions

- **Reconnect mid-turn (#402 family, deferred server-side).** If a backfill on reconnect adds a
  *new-to-us* assistant message id while a turn is in flight, the baseline-id dedup could finalise
  the streaming item early. This is an extreme edge (reconnect during an active turn) and the
  reconnect-replay handling is itself deferred (#402/#416/#417). Accepted: worst case is an early
  settle to the finished text — never a duplicate. Revisit if observed.
- **`sessionId`/`timestamp` provenance.** Derived from the last finished item; the live events carry
  no `sessionId`. Acceptable because the appended assistant row renders neither. If a future ticket
  surfaces per-message timestamps on assistant rows, this needs a real clock injected.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

No new visual is introduced. Node `16-8` (the live structured-turn thread region) is context only:
the growing assistant turn renders as an ordinary assistant message at the bottom of the thread
(blue user bubbles above, markdown/code-block assistant bodies, the `read_file` tool row, the
session-boundary delimiter) — i.e. the already-built `MessageBubble` streaming affordance from #184
(`StreamingAssistantBody` typewriter while `isStreaming`, `MarkdownText` once settled). This slice
only feeds the real backend signal into that unchanged render. Visual-fidelity review for the
thread region is owned by its build tickets (#128/#129/#184), not this one.

## Security review

**Verdict:** PASS

Run adversarially against the spec above, assuming it has holes. The ticket's threat focus, and the
reason it carries `security-sensitive`: **another conversation's assistant text must never appear in
this thread, and the verbatim untrusted delta text must reach no unsafe sink.**

**Findings:**

- **[Trust boundaries — per-conversation confinement]** No findings, by the load-bearing guard. The
  reduction checks `event.conversationId == conversationId` **first** for every `Live(...)` input
  (mirrors `thinkingTransition`, ThreadViewModel.kt:249) — an `AssistantDelta`/`TurnEnd` for any
  other conversation is dropped before it can touch `stream`. The `baselineAssistantIds` snapshot and
  the finished list both come from `repository.observeMessages(conversationId)`, already
  conversation-scoped by #313. There is no path by which a foreign conversation's text enters this
  thread's `items`. A dedicated test (`conversation-id scoping`) is the oracle.
- **[Trust boundaries — verbatim untrusted text into a render sink]** No findings — **no new sink.**
  `AssistantDelta.text` is carried verbatim/unsanitised by design (LiveSessionEvent.kt:16-21). This
  slice only **concatenates** it into `Message.content` and routes it through the *same*
  `MessageBubble` path (`StreamingAssistantBody` / `MarkdownText`, #129/#184) that already renders
  finished assistant `message` text — identical trust class, identical sink. The VM never
  interprets, parses, or executes the text. Output-encoding/markdown safety is owned by the existing
  `MarkdownText` (#129), unchanged here. No HTML/WebView/`eval`-shaped sink is introduced.
- **[Error messages, logs, telemetry]** No findings — and an explicit MUST: the reduction performs
  **no logging** (`Log`/`Timber`) of delta text, accumulated content, `turnId`, or any payload
  field. This mirrors the server-side AC#5 no-application-output-log discipline (pyrycode#632). The
  fold is pure; there is no error string to leak. Code-review must reject any debug log added over
  delta content.
- **[Tokens, secrets, credentials]** N/A — the VM is far downstream of all credential handling; it
  sees decoded `LiveSessionEvent`s only, never a token, key, or nonce.
- **[File / storage operations]** N/A — no path handling, no file I/O, no persistence. The synthetic
  message is in-memory and ephemeral (cleared on finalise / supersession / resubscribe).
- **[Inter-process / Android attack surface]** N/A — no Intent, deep link, PendingIntent, provider,
  or WebView. `data/` stays portable (no `android.*` — the fold is pure Kotlin/coroutines).
- **[Cryptographic primitives]** N/A — no randomness, no crypto. `turnId` (a server-minted grouping
  key, not a secret) is reused verbatim as the synthetic message id; even a non-crypto value would be
  acceptable for a UI key.
- **[Network & I/O — unbounded accumulation / DoS]** SHOULD-NOTE, not a finding. `stream.text` grows
  per turn; the bound is one turn's coalesced assistant output (server token-bounded by
  `max_tokens`), held as a single `String` and cleared on finalise/supersession. A hostile/buggy
  server could stream a very large turn, but the same exposure already exists for the finished
  `message.text` rendered by #313 — no new amplification, no per-event allocation growth beyond the
  one accumulator. Frame-size caps are enforced upstream at the Noise/WebSocket layer
  (`RemoteConversationRepository`), out of this slice's scope.
- **[Concurrency]** No findings. No new coroutine or scope — the `scan` is an operator inside the
  existing `stateIn(viewModelScope, WhileSubscribed)`, cancelled with it. No shared mutable state
  outside the flow (the accumulator is local to `scan`). `liveSessionEvents` is read-only here.
  Resubscribe restarts the fold cleanly from the initial accumulator. No `check-then-mutate` on a
  `StateFlow`.
- **[Threat model alignment]** The mobile-relevant threats are covered: foreign-conversation leakage
  (the conversation-id guard), untrusted-content rendering (no new sink; existing encoder), and
  log/telemetry leakage of session content (no logging). Reconnect-replay tampering is deferred to
  the #402/#416/#417 family (named in § Open questions); it cannot inject *foreign-conversation*
  content here because the guard still applies to replayed events.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-17
