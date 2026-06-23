# Current-modal state — `currentModal` on the thread ViewModel

The **state/projection half of the permission/choice-modal UI surface**: how the daemon's decoded modal
lifecycle (`modal_shown` → `modal_dismissed`) is folded into a single hoisted "which modal is currently
open" observable, `currentModal`, on [`ThreadViewModel`](thread-screen.md). Landed in
[#445](../codebase/445.md) (split from #443, the render half of #439), part of the Phase 3 permission-modal
feature (epic pyrycode#597, ADR 025). The visual overlay, the fail-safe-deny default highlight, the
inert-text output-encoding, and the dismiss-reason UI are the **sibling render slice #446**
([shipped](permission-modal-overlay.md) — it consumes this `currentModal`); answering / cancelling is
**#444**. This slice adds **no UI** — all state is hoisted to the VM.

It is the **modal twin of [`isThinking`](turn-state-thinking-flag.md) ([#406](../codebase/406.md))**: the
same coordinator-passthrough + sibling-`StateFlow` shape, with one load-bearing deviation — a `scan`
accumulator under `SharingStarted.Eagerly`, not a `mapNotNull` transition under `WhileSubscribed` (see
[Why `Eagerly`](#why-eagerly-not-whilesubscribed)).

## The data path

```
modal_shown / modal_dismissed  ──(#437 decode, capability-gated)──▶  ModalEvent.{Shown, Dismissed}
        │                                                                  on RemoteConversationRepository
        │                                                                  .modalEvents (concrete, per-connection, replay=0)
        ▼
RelayRepositoryCoordinator.modalEvents : Flow<ModalEvent>   ◀── #445 seam (reconnection-surviving, mirror of liveSessionEvents)
        │  injected at the AppModule ThreadViewModel factory (no new Koin binding)
        ▼
ThreadViewModel.currentModal : StateFlow<ModalUiState>   ◀── #445 fold (scan, modalId-keyed, last-shown-wins)
        │  separate parameter beside `state` / `isThinking` / `isStalled`
        ▼
ThreadScreen → modal overlay (#446 renders it; #444 answers it)
```

Two hops, both reusing the established [`liveSessionEvents`](live-session-events.md) precedent — but note
the projection is **app-level**, not per-conversation: modal events carry **no `conversation_id`**
([Modal events](modal-events.md)), so `modalId` is the sole correlation key and there is **one** active
modal across the app (not one per thread).

### 1. The coordinator seam (reconnection-surviving)

The decoded events live on the **concrete** `RemoteConversationRepository.modalEvents` — a `SharedFlow`
that is **connection-scoped** (rebuilt per connection, absent between) and deliberately **not** on the
`ConversationRepository` interface the thread ViewModel consumes (the
[Why on the concrete repo](modal-events.md#why-on-the-concrete-repo-not-the-interface-ac-4) posture). So
the ViewModel cannot reach it directly. [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
exposes a stable public flow over it (see [§ Modal event seam](relay-repository-coordinator.md#modal-event-seam-445)),
a byte-for-byte mirror of the `liveSessionEvents` seam:

```kotlin
val modalEvents: Flow<ModalEvent> =
    activeRemoteRepo.flatMapLatest { repo -> repo?.modalEvents ?: emptyFlow() }
```

`flatMapLatest` switches to the fresh connection's repo and cancels the prior on reconnect; `emptyFlow()`
covers between-connections. Cold `Flow`, **no `stateIn`** — these are *events* with no current value
("which modal is open" is held downstream, in `currentModal`, because the source is `replay = 0`).

### 2. The ViewModel fold (`scan`, `modalId`-keyed, last-shown-wins)

`ThreadViewModel` takes the coordinator flow as a **defaulted** trailing ctor param
(`modalEvents: Flow<ModalEvent> = emptyFlow()` — so the fake-backed graph and the existing tests stay
inert, holding `Hidden`) and folds it into a sibling `StateFlow`:

```kotlin
val currentModal: StateFlow<ModalUiState> =
    modalEvents
        .scan<ModalEvent, ModalUiState>(ModalUiState.Hidden) { state, event -> state.reduce(event) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModalUiState.Hidden)
```

The pure, side-effect-free `ModalUiState.reduce` (co-located in `ModalUiState.kt`, **no logging** of any
field) folds one event into the next state:

| receiver → event | result | why |
|---|---|---|
| any → `Shown` | `Open(...)` carrying the event **verbatim** | a later `Shown` **supersedes** any open modal (last-shown wins; AC #3) |
| `Open(id=X)` → `Dismissed(id=X)` | `Dismissed(id, outcome, source)` (verbatim reason) | the open modal resolved (AC #2) |
| `Open(id=X)` → `Dismissed(id=Y≠X)` | receiver unchanged | **spoofed-dismiss safety** — an out-of-band dismiss can't clear an unresolved modal (AC #2) |
| `Hidden` / `Dismissed` → `Dismissed` | receiver unchanged | nothing open to clear (AC #2) |

`ModalUiState` is a `sealed interface { Hidden, Open, Dismissed }`. `Open` mirrors `ModalEvent.Shown`
field-for-field (`modalId`, `modalClass`, `title`, `prompt`, `options: List<ModalOption>` in wire array
order, `defaultOptionId`); `Dismissed` mirrors `ModalEvent.Dismissed` (`modalId`, `outcome`, `source`).
Every field is carried **verbatim** — no parsing, enum-coercion, trimming, or reordering (preserves
#437's forward-compat posture). It reuses `data.model.ModalOption` (no parallel option type). `Hidden` is
the initial / resolved-and-cleared state and does double duty as the inert empty-flow default.

## Why `Eagerly`, not `WhileSubscribed`

This is the **load-bearing design call** of the slice — a deliberate deviation from the
`WhileSubscribed(5_000)` of the sibling signals (`isThinking`, `isStalled`, `connectionState`):

- `scan` **re-emits its initial accumulator on every fresh upstream collection.** Under `WhileSubscribed`,
  when the screen is gone past the stop timeout the upstream cancels; on return `scan` restarts and emits
  `Hidden`, **overwriting a retained `Open`**. Because the source `modalEvents` is `replay = 0`, the prior
  events do **not** replay to rebuild the accumulator — a still-open modal would silently clear. This is a
  deterministic consequence of `scan` + `replay = 0`, not a speculative guard.
- `Eagerly` collects for the VM lifetime, so the `scan` accumulator runs **exactly once** and is
  monotonic; `.value` is always the true current projection. This matches the coordinator's
  accumulate-a-`replay=0`-stream precedent (`currentRepository` / `connectionStatus`, both `Eagerly`).
  Cost is negligible — modals are one-at-a-time, user-driven, low-rate.
- The sibling `isThinking` is safe under `WhileSubscribed` **only because `mapNotNull` never re-emits a
  stale value on resubscription** (a non-matching event produces no emission, so `.value` is retained).
  The same policy is wrong for a `scan` accumulator. Pick the started policy from the operator
  (`scan` accumulates vs `mapNotNull` transitions), not from the sibling.

> **Kotlin gotcha:** `scan`'s accumulator type is inferred from the initial value, so
> `scan(ModalUiState.Hidden) { … }` infers `R = ModalUiState.Hidden` (the `data object`'s singleton type)
> and rejects a lambda returning `ModalUiState.Open`. Type it explicitly:
> `scan<ModalEvent, ModalUiState>(ModalUiState.Hidden)`.

## Why a sibling `StateFlow`, not a `ThreadUiState` field

`currentModal` mirrors `isThinking` / `isStalled` / `connectionState`: a transient, cross-cutting signal
with a distinct source, taken by the stateless `ThreadScreen` as a **separate** parameter beside `state`.
Folding it into the `state` `combine` would force a restructure and touch its `initialValue`. The render
slice **#446** (blocked by this one) consumes it the same way — a separate `(state, currentModal, onEvent)`
parameter on the stateless screen.

It is **app-level**, not per-conversation: there is **no `conversationId` filter** (contrast
[`thinkingTransition`](turn-state-thinking-flag.md)'s `conversationId` guard) because modal events carry
no `conversation_id`. The fold routes on `modalId` only, so a single `currentModal` represents the one
modal outstanding across the whole app.

## Lifecycle, errors, edge cases

- **Lifecycle** — `stateIn(viewModelScope, Eagerly, Hidden)`; collects for the VM lifetime on the
  Main-bound `viewModelScope`, cancelled on VM clear. The fold is pure (no dispatcher switch).
- **Errors** — none. `modalEvents` is a `SharedFlow` that never completes-with-error; malformed envelopes
  are already dropped at the #437 decode boundary, so every event reaching the fold is well-typed and the
  fold is total over the sealed `ModalEvent`. Absence of a live source is the empty flow ⇒ state stays
  `Hidden`. No `catch`, no result type.
- **Disconnect/reconnect clear (deferred, no AC)** — the coordinator passthrough emits `emptyFlow()` on a
  null repo but pushes **no "clear" event**, so a stale `Open` can persist across a connection drop. The
  render slice [#446](permission-modal-overlay.md) did **not** build it either (state-driven overlay only);
  owned by #444 + the connection signal.

## Wiring

`AppModule` fetches the seam off the already-registered concrete coordinator singleton at the
`ThreadViewModel` factory — **no new Koin binding** — mirroring the `liveSessionEvents` arg:

```kotlin
viewModel {
    ThreadViewModel(
        get(), get(), get(), get(),
        get<RelayRepositoryCoordinator>().liveSessionEvents,
        get<RelayRepositoryCoordinator>().modalEvents,
    )
}
```

In the default debug build (`USE_RELAY_REPOSITORY` OFF, fake repository) no live coordinator event source
reaches this factory path and the defaulted empty-flow keeps the state inert — `currentModal` honestly
holds `Hidden` with no live daemon.

## Related

- [#445 implementation notes](../codebase/445.md) — files, line refs, lessons.
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the decode seam that produces the
  `ModalEvent` stream; this slice realizes its "folding into a current-modal state is the consumer's
  projection" deferral.
- [Turn-state thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) — the `isThinking`
  projection slice this is the **modal twin** of (same coordinator-passthrough + sibling-`StateFlow`
  shape); contrast the `scan`/`Eagerly` vs `mapNotNull`/`WhileSubscribed` choice and the app-level vs
  per-conversation routing.
- [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the other sibling transient signal
  (`isStalled`).
- [Relay repository coordinator](relay-repository-coordinator.md) — owns + publishes the `modalEvents`
  passthrough seam (§ Modal event seam).
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `currentModal` joins `isThinking` /
  `isStalled` / `connectionState` as a sibling signal the stateless screen takes as a separate parameter.
- [Permission-modal overlay](permission-modal-overlay.md) ([#446](../codebase/446.md)) — the render slice
  (shipped) that collects this `currentModal` in the route host and draws the overlay.
- Sibling slices: **#446** the render overlay (shipped) · **#444** answering / cancelling.
- Producer SSOT: pyrycode#716 (surfaces only `permission` / `trust` classes; fail-safe-deny
  `default_option_id`, no per-option destructive marker), ADR 025 § Phase 3 modals, EPIC pyrycode#597.
