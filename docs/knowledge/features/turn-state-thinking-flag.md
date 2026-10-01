# Turn-state thinking flag — `isThinking` on the thread ViewModel

The **data/ViewModel half of the thinking indicator**: how the daemon's coarse `turn_state` signal
reaches the conversation thread and is reduced to a single presentation flag, `isThinking`, on
[`ThreadViewModel`](thread-screen.md). Landed in [#406](../codebase/406.md) (split from #386), part of
the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The stateless indicator composable
and its placement in the thread are the **sibling UI slice [#407](../codebase/407.md)** (now shipped —
see [Thinking indicator](thinking-indicator.md)) — this slice adds **no UI** (all state is hoisted to
the VM).

`responding` (assistant text growing) is covered by the live `assistant_delta` stream — fed into the
thread render by [Streaming assistant turns](streaming-assistant-turns.md)
([#337](../codebase/337.md)); this flag exposes **`thinking`** — the active, pre-text phase — versus
not-thinking (`responding` / `idle` / `turn_end` / no event yet), so the UI can show an at-work
indicator instead of appearing stalled.

## The data path

As of #1313, the phase is **held in the repository**, per conversation, not folded per screen — the
same shape as [`StallProjection`](stall-state.md) and [`CompactingProjection`](compacting-indicator.md):

```
turn_state / turn_end envelope ──(#385 decode, capability-gated)──▶  LiveSessionEvent
        │                                                          on RemoteConversationRepository's
        │                                                          single inbound collector, behind the
        │                                                          `interactive` gate
        ▼
TurnPhaseProjection.apply(event)   ◀── #1313: one MutableStateFlow<Map<conversationId, Phase>> per
        │                               connection; `turn_state` sets the conversation's phase,
        │                               `turn_end` of any outcome returns it to idle, every other event
        │                               is ignored. Absent means idle.
        ▼
ConversationRepository.observeTurnPhase(conversationId) : Flow<Phase>
        │  default flowOf(Idle) on the interface, so fakes need no override; RemoteConversationRepository
        │  reads the projection; StableConversationRepository routes through switchToLive(Idle), so a
        │  new connection's fresh (empty) projection reads idle until the daemon reports a phase again
        ▼
ThreadViewModel.isThinking / isBusy : StateFlow<Boolean>   ◀── map the held phase, no own reduction
        │  separate parameters beside `state`
        ▼
ThreadScreen → ThinkingIndicator (#407) / interrupt affordance (#459)
```

Because the phase is held outside any screen, a thread opened or reopened mid-turn reads the current
phase on its very first collection — there is no "wait for the next `turn_state`" window, and no
window where an unsubscribed thread's last-known value goes stale: resubscribing re-reads the
projection's current value instead of a per-ViewModel fold's frozen one.

Two non-trivial hops:

### 1. The projection (held, per connection, per conversation)

`TurnPhaseProjection` (`data/repository/TurnPhaseProjection.kt`) lives beside
[`StallProjection`](stall-state.md) and [`CompactingProjection`](compacting-indicator.md): one instance
per `RemoteConversationRepository`, so one per connection. It holds a single
`MutableStateFlow<Map<String, Phase>>` of every conversation whose latest phase is not idle — absent
means idle, so a conversation never heard from and one whose turn just ended read the same.

The repository's single inbound collector hands the projection every decoded `LiveSessionEvent`,
inside the existing `interactive` gate, beside the `stallProjection.clear` call it already makes there:
a `turn_state` sets that conversation's phase, a `turn_end` of any outcome returns it to idle, and
every other event (`AssistantDelta`, `ToolUse`, `ToolResult`, `ReplayGap`) is ignored. Every write is
keyed by the event's own `conversationId`, so one conversation's frames never move another's phase.
Replayed frames after a reconnect pass through the same arm, so they rebuild the phase exactly as live
frames do — no separate replay handling.

`ConversationRepository.observeTurnPhase(conversationId): Flow<Phase>` exposes it, defaulting to
`flowOf(Phase.Idle)` so the fake and inline test doubles need no override.
`RemoteConversationRepository` reads `turnPhaseProjection.observe(conversationId)`.
[`StableConversationRepository`](stable-conversation-repository.md) routes it through
`switchToLive(Phase.Idle) { it.observeTurnPhase(conversationId) }`, the same seam `observeStall` and
`observeCompacting` use — so with no connection, or right after a reconnect (a fresh repository means a
fresh, empty projection), every conversation reads idle until the daemon reports a phase again. That
reset is the mobile equivalent of desktop's `reconnected` arm in `reduceTimeline`
(`src/renderer/src/store/threadTimeline.ts`), which does the same per-conversation reset explicitly;
mobile gets it for free because the repository itself is rebuilt per connection.

### 2. The ViewModel read (no reduction of its own)

`ThreadViewModel` reads the held phase once and derives both flags from it — there is no longer a
per-ViewModel fold, and no `liveSessionEvents` involvement for these two flags at all:

```kotlin
private val turnPhase = repository.observeTurnPhase(conversationId)

val isThinking: StateFlow<Boolean> =
    turnPhase.map { it == Phase.Thinking }.stateIn(viewModelScope, WhileSubscribed(5_000), false)

val isBusy: StateFlow<Boolean> =
    turnPhase.map { it == Phase.Thinking || it == Phase.Responding }
        .stateIn(viewModelScope, WhileSubscribed(5_000), false)
```

The `thinkingTransition` / `busyTransition` reducers that used to route `liveSessionEvents` by
`conversationId` and hold the flag between non-phase events are gone — that routing and holding now
happen once, in the projection, and every `ThreadViewModel` reads the same per-conversation slice of
it. `liveSessionEvents` stays a `ThreadViewModel` constructor parameter and keeps feeding `turnOutcome`
(#805) and the thread's own live-event fold ([Streaming assistant
turns](streaming-assistant-turns.md)) — only `isThinking` / `isBusy` moved off it.

## Why a sibling `StateFlow`, not a `ThreadUiState` field

`isThinking` mirrors `connectionState`, `isStalled` and `isCompacting`: all are transient,
connection-scoped cross-cutting signals with a distinct source, and the stateless `ThreadScreen`
already receives `connectionState` as a **separate** parameter beside `state` (ThreadScreen.kt:70).
Folding `isThinking` into the max-arity-5 `state` `combine` would force a sub-combine restructure and
touch its `initialValue`. The sibling flow is **zero-touch** to the combine. [#407](../codebase/407.md)
followed the same separate-parameter pattern for the [indicator composable](thinking-indicator.md) — a
defaulted hoisted `isThinking: Boolean` on `ThreadScreen`, collected at `MainActivity` beside
`connectionState`.

## Lifecycle, errors, edge cases

- **Lifecycle** — `stateIn(viewModelScope, WhileSubscribed(5_000), false)`, identical to
  `connectionState` / `isStalled` / `isCompacting`. While subscribed it collects
  `repository.observeTurnPhase(conversationId)` on the Main-bound `viewModelScope`; the `map` is pure
  (no dispatcher switch). On unsubscribe the upstream collection stops after 5 s and the `StateFlow`
  retains its last value — but because the upstream is the repository's held `TurnPhaseProjection`, not
  a `replay = 0` event stream, resubscribing re-reads the **current** phase rather than replaying what
  was missed. A thread unsubscribed through a turn ending, or through a new turn starting, reads the
  right value on return with no further frame (#1313 AC2), and a thread opened mid-turn for the
  first time reads the running phase on its very first collection (#1313 AC1).
- **Errors** — none. Decode failures / unknown `turn_state` values are already dropped to nothing at
  the #385 mapper before `TurnPhaseProjection` ever sees them. Absence of a live connection is
  `StableConversationRepository`'s idle default ⇒ the flags stay `false`. No `catch`, no result type.
- **Reset on reconnect** — a reconnect replaces `RemoteConversationRepository`, so `TurnPhaseProjection`
  starts over empty; every conversation reads idle until the daemon reports a phase again
  (#1313 AC3). This replaces the "stale `true` on resume" gap the per-ViewModel fold used to have:
  that gap no longer exists, because the flags no longer depend on having been subscribed when the
  defining frame arrived.

## Related

- [#406 implementation notes](../codebase/406.md) — files, line refs, patterns, lessons from when the
  flag was still a per-ViewModel fold over the coordinator seam; superseded by the held projection
  (#1313) described above.
- [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) — the decode seam that
  produces `LiveSessionEvent.TurnState` / `TurnEnd`, the events `TurnPhaseProjection` folds.
- [Relay repository coordinator](relay-repository-coordinator.md)
  ([#351](../codebase/351.md)/[#365](../codebase/365.md)/[#392](../codebase/392.md)) — still owns the
  generic `liveSessionEvents` seam that `turnOutcome` and the thread's live-event fold keep using; the
  turn-phase flags no longer go through it.
- [Stall state](stall-state.md) (#395) and [Compacting indicator](compacting-indicator.md) — the sibling
  connection-scoped projections `TurnPhaseProjection` follows the shape of, and whose
  `observeStall` / `observeCompacting` + `switchToLive` plumbing `observeTurnPhase` reuses.
- [Stable conversation repository](stable-conversation-repository.md) — `switchToLive`, the seam that
  gives every repository-held, per-conversation signal its idle reading with no connection and its reset
  on a fresh connection.
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `isThinking` / `isBusy` join
  `connectionState` / `isStalled` / `isCompacting` as sibling signals the stateless screen takes as
  separate parameters.
- Sibling UI slice (shipped): [Thinking indicator](thinking-indicator.md)
  ([#407](../codebase/407.md)) — the stateless composable + its placement at the foot of the thread,
  consuming `isThinking`.
- Broader sibling (shipped): [Interrupt affordance](interrupt-affordance.md)
  ([#459](../codebase/459.md)) — `ThreadViewModel.isBusy`, `true` while the held phase is `thinking`
  **or** `responding` (the "a turn is running" signal driving the foot-of-list interrupt control).
  `isThinking` is `false` during `responding`, so it can't drive an affordance that must persist across
  the whole turn — hence the separate flag rather than reuse, even though both now read the same held
  `turnPhase`.
- Other consumers of the generic `liveSessionEvents` seam (shipped, unaffected by #1313):
  [#387](../codebase/387.md) ([Live tool-call](live-tool-call.md), tool-use timeline),
  [#337](../codebase/337.md) ([Streaming assistant turns](streaming-assistant-turns.md), live assistant
  text), `turnOutcome` ([Turn outcome indicator](turn-outcome-indicator.md), #805).
- Precedent: `connectionStatus` injected into [`SettingsViewModel`](settings-viewmodel.md)
  ([#398](../codebase/398.md)); `registerPushToken` reached through the concrete repo
  ([#365](../codebase/365.md)). See [[post-352-connection-scoped-repo-behind-facade]].
- Render regression coverage: [#432 scripted-stream thread harness](../codebase/432.md) — the Layer-1a
  `ScriptedThreadHarness` scripts `turn_state("thinking")` → `turn_end` and asserts the
  `cd_thread_thinking` indicator appears then clears (the spinner case). Its `pushTurnState` now also
  takes a `targetConversationId` (#1313), and `openConversation` swaps the composed `ThreadViewModel` to
  a different conversation over the same repository — `ScriptedTurnPhaseTest` uses both to push frames
  for one conversation while another is open and prove routing and the reopen-shows-it-at-once behaviour
  (AC1). The harness's subscribe-before-push rule no longer applies to `isThinking` / `isBusy`, since
  #1313 made them read held state instead of a `replay = 0` fold; it still applies to `turnOutcome` and
  the thread's own live-event fold, which stay on `liveSessionEvents` unchanged.
- Server SSOT: pyrycode#607 (`turn_state` wire), #616 (capability-gated fan-out), ADR 025 § Phase 2
  structured streaming, EPIC pyrycode#596.
