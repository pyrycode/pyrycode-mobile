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

```
turn_state envelope  ──(#385 decode, capability-gated)──▶  LiveSessionEvent.TurnState(convId, phase)
        │                                                          on RemoteConversationRepository
        │                                                          .liveSessionEvents (concrete, per-connection)
        ▼
RelayRepositoryCoordinator.liveSessionEvents : Flow<LiveSessionEvent>   ◀── #406 seam (generic, reconnection-surviving)
        │  injected at the AppModule ThreadViewModel factory (no new Koin binding)
        ▼
ThreadViewModel.isThinking : StateFlow<Boolean>   ◀── #406 reduction (route by conversationId, latest-phase-wins)
        │  separate parameter beside `state`
        ▼
ThreadScreen → ThinkingIndicator (#407 renders it at the foot of the list)
```

Two non-trivial hops, both reusing an established precedent:

### 1. The coordinator seam (generic, reconnection-surviving)

The decoded events live on the **concrete** `RemoteConversationRepository.liveSessionEvents`
([Live-session events](live-session-events.md), #385) — a `SharedFlow` that is **connection-scoped**
(rebuilt per connection, absent between) and deliberately **not** on the `ConversationRepository`
interface the thread ViewModel consumes. So the ViewModel cannot reach it directly.

[`RelayRepositoryCoordinator`](relay-repository-coordinator.md) — the layer that already owns the
per-connection pump and repository — exposes a stable public flow over it (see
[§ Live-session event seam](relay-repository-coordinator.md#live-session-event-seam-406) there):

```kotlin
val liveSessionEvents: Flow<LiveSessionEvent> =
    activeRemoteRepo.flatMapLatest { repo -> repo?.liveSessionEvents ?: emptyFlow() }
```

`flatMapLatest` switches to the fresh connection's repo and cancels the prior on reconnect (AC #1
"survives reconnection"); `emptyFlow()` covers between-connections. The seam stays **generic** — the
full `LiveSessionEvent` stream, not an `isThinking` projection — so #387 (tool timeline) and #337 (live
assistant text) reuse it without re-plumbing the coordinator. This is the identical reachability shape
already solved for `registerPushToken` ([#359](../codebase/359.md)/[#365](../codebase/365.md)) and the
two-part `connectionStatus` ([#392](../codebase/392.md)/[#398](../codebase/398.md)).

### 2. The ViewModel reduction (route, then latest-phase-wins)

`ThreadViewModel` takes the coordinator flow as a **defaulted** trailing ctor param
(`liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow()` — so the fake-backed graph and the existing
4-arg tests stay inert) and reduces it to a sibling `StateFlow`:

```kotlin
val isThinking: StateFlow<Boolean> =
    liveSessionEvents
        .mapNotNull { event -> thinkingTransition(event) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

`thinkingTransition` returns the next flag value, or `null` to **leave the flag unchanged**:

| event for **this** `conversationId` | result | why |
|---|---|---|
| `TurnState(phase = Thinking)` | `true` | `true` only while the latest phase is thinking (AC #2) |
| `TurnState(phase = Responding \| Idle)` | `false` | AC #2 |
| `TurnEnd` | `false` | AC #2 lists `turn_end` false; guards the thinking→turn_end-direct edge (no responding/idle between) |
| `AssistantDelta` / `ToolUse` / `ToolResult` | `null` | not a phase transition — hold the prior value |
| any event for a **different** `conversationId` | `null` | other conversations never move the flag (AC #3) |

`mapNotNull` + `stateIn` gives "latest-wins with hold" for free: a non-matching event produces no
emission, so the `StateFlow` retains its prior value; no explicit `scan`/`distinctUntilChanged`. The
`false` initial value does double duty — "before any turn-state event" (AC #2) **and** the inert
empty-flow default (AC #5).

## Why a sibling `StateFlow`, not a `ThreadUiState` field

`isThinking` mirrors `connectionState`: both are transient, connection-scoped cross-cutting signals
with a distinct source, and the stateless `ThreadScreen` already receives `connectionState` as a
**separate** parameter beside `state` (ThreadScreen.kt:70). Folding `isThinking` into the max-arity-5
`state` `combine` would force a sub-combine restructure and touch its `initialValue`, putting AC #5
("existing tests compile and pass unchanged") at risk. The sibling flow is **zero-touch** to the
combine. [#407](../codebase/407.md) followed the same separate-parameter pattern for the
[indicator composable](thinking-indicator.md) — a defaulted hoisted `isThinking: Boolean` on
`ThreadScreen`, collected at `MainActivity` beside `connectionState`.

## Lifecycle, errors, edge cases

- **Lifecycle** — `stateIn(viewModelScope, WhileSubscribed(5_000), false)`, identical to
  `connectionState`. While subscribed it collects the coordinator flow on the Main-bound
  `viewModelScope`; the reduction is pure (no dispatcher switch). On unsubscribe the upstream collection
  stops after 5 s and the `StateFlow` retains its last value.
- **Errors** — none. `liveSessionEvents` is a `SharedFlow` that never completes-with-error; decode
  failures / unknown `turn_state` values are already dropped to nothing at the #385 mapper. Absence of a
  live source is the empty flow ⇒ the flag stays `false`. No `catch`, no result type.
- **Stale-`true`-on-resume (known, accepted)** — with `replay = 0` upstream, if the agent leaves
  `thinking` while the screen is backgrounded > 5 s and re-foregrounds before a fresh event,
  `isThinking` can momentarily read a stale `true` until the next event. This matches the transient
  "right-now" posture already accepted for the [stall flag](stall-state.md) (#395) and
  `connectionState`. [#407](../codebase/407.md) shipped the UI **without** handling it — deliberately,
  since a reset would need either a data-layer change or local state in the (stateless) composable; an
  `idle`/`turn_end`-on-resubscribe reset remains a deferred follow-up if it ever reads jarring.

## Wiring

`AppModule` fetches the seam off the already-registered concrete coordinator singleton at the
`ThreadViewModel` factory — **no new Koin binding**, exactly as `SettingsViewModel` takes
`connectionStatus`:

```kotlin
viewModel {
    ThreadViewModel(get(), get(), get(), get(), get<RelayRepositoryCoordinator>().liveSessionEvents)
}
```

In the default debug build (`USE_RELAY_REPOSITORY` OFF, fake repository) there is no live coordinator
event source reaching this factory path the same way, and the defaulted empty-flow keeps the flag
inert — `isThinking` honestly holds `false` with no live daemon.

## Related

- [#406 implementation notes](../codebase/406.md) — files, line refs, patterns, lessons.
- [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) — the decode seam that
  produces `LiveSessionEvent.TurnState`; this slice realizes its "facade/coordinator reachability is
  consumer-slice work" deferral.
- [Relay repository coordinator](relay-repository-coordinator.md)
  ([#351](../codebase/351.md)/[#365](../codebase/365.md)/[#392](../codebase/392.md)) — owns + publishes
  the generic `liveSessionEvents` seam (§ Live-session event seam).
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `isThinking` joins `connectionState`
  as a sibling signal the stateless screen takes as a separate parameter.
- Sibling UI slice (shipped): [Thinking indicator](thinking-indicator.md)
  ([#407](../codebase/407.md)) — the stateless composable + its placement at the foot of the thread,
  consuming `isThinking`.
- Other consumers of the generic seam (shipped): [#387](../codebase/387.md)
  ([Live tool-call](live-tool-call.md), tool-use timeline), [#337](../codebase/337.md)
  ([Streaming assistant turns](streaming-assistant-turns.md), live assistant text).
- Precedent: `connectionStatus` injected into [`SettingsViewModel`](settings-viewmodel.md)
  ([#398](../codebase/398.md)); `registerPushToken` reached through the concrete repo
  ([#365](../codebase/365.md)). See [[post-352-connection-scoped-repo-behind-facade]].
- Server SSOT: pyrycode#607 (`turn_state` wire), #616 (capability-gated fan-out), ADR 025 § Phase 2
  structured streaming, EPIC pyrycode#596.
