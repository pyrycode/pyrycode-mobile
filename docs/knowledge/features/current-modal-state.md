# Current-modal state — the hoisted `currentModal` projection

The **state/projection half of the permission/choice-modal UI surface**: how the daemon's decoded modal
lifecycle (`modal_shown` → `modal_dismissed`) is folded into a single "which modal is currently open"
observable, `currentModal: StateFlow<ModalUiState>`. Introduced in [#445](../codebase/445.md) (split from
\#443, the render half of #439), part of the Phase 3 permission-modal feature (epic pyrycode#597, ADR 025).

**As of [#492](../codebase/492.md) the fold is hoisted to the process-scoped
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md).** It used to fold per-thread-screen inside
[`ThreadViewModel`](thread-screen.md), whose collection only began on navigating into a thread — so a
`modal_shown` fired **before any subscriber existed** was dropped by the `replay = 0`
[`modalEvents`](modal-events.md) seam, leaving the prompt stuck daemon-side while the phone showed nothing.
The coordinator outlives any screen and already owns the reconnection-surviving seam, so folding there
accumulates the projection whether or not a thread screen is subscribed; **the ViewModel now re-exposes the
coordinator's `StateFlow<ModalUiState>` verbatim instead of folding the raw stream itself.** This closed a
`USE_RELAY_REPOSITORY` blocker (the bug manifests only with the relay repository live).

The visual overlay, the fail-safe-deny default highlight, the inert-text output-encoding, and the
dismiss-reason UI are the **sibling render slice #446** ([shipped](permission-modal-overlay.md) — it
consumes this `currentModal`); answering / cancelling is **#444**, split into the behavior half
[#451](../codebase/451.md) ([shipped](modal-answer-flow.md)) and the render half #452 (the armed affordance
+ snackbar + route-host forward).

## The data path

```
modal_shown / modal_dismissed  ──(#437 decode, capability-gated)──▶  ModalEvent.{Shown, Dismissed}
        │                                                                  on RemoteConversationRepository
        │                                                                  .modalEvents (concrete, per-connection, replay=0)
        ▼
RelayRepositoryCoordinator.modalEvents : Flow<ModalEvent>   ◀── #445 seam, now #492-PRIVATE (reconnection-surviving)
        │  scan + ModalUiState.reduce (modalId-keyed, last-shown-wins), stateIn(scope, Eagerly)   ◀── #492 fold (hoisted here)
        ▼
RelayRepositoryCoordinator.currentModal : StateFlow<ModalUiState>   ◀── #492 the single process-scoped projection
        │  injected at the AppModule ThreadViewModel factory (no new Koin binding)
        ▼
ThreadViewModel.currentModal : StateFlow<ModalUiState>   ◀── #492 re-exposed verbatim (a `val` ctor property, no re-fold)
        │  separate parameter beside `state` / `isThinking` / `isStalled`
        ▼
ThreadScreen → modal overlay (#446 renders it; #451 answers it, #452 renders the armed affordance)
```

Note the projection is **app-level**, not per-conversation: modal events carry **no `conversation_id`**
([Modal events](modal-events.md)), so `modalId` is the sole correlation key and there is **one** active
modal across the app (not one per thread). This is *why* it hoists cleanly to the app-level coordinator.

### 1. The coordinator seam (reconnection-surviving) → the hoisted fold

The decoded events live on the **concrete** `RemoteConversationRepository.modalEvents` — a `SharedFlow`
that is **connection-scoped** (rebuilt per connection, absent between) and deliberately **not** on the
`ConversationRepository` interface the thread ViewModel consumes (the
[Why on the concrete repo](modal-events.md#why-on-the-concrete-repo-not-the-interface-ac-4) posture). So
the ViewModel cannot reach it directly. [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
threads it up (a byte-for-byte mirror of the `liveSessionEvents` seam) **and folds it** into the current
projection:

```kotlin
// #492: modalEvents is now PRIVATE — its sole consumer is currentModal.
@OptIn(ExperimentalCoroutinesApi::class)
private val modalEvents: Flow<ModalEvent> =
    activeConnection.flatMapLatest { conn -> conn?.repo?.modalEvents ?: emptyFlow() }

// #492: the single hoisted "which modal is open" projection, folded once at this process-scoped layer.
val currentModal: StateFlow<ModalUiState> =
    modalEvents
        .scan<ModalEvent, ModalUiState>(ModalUiState.Hidden) { state, event -> state.reduce(event) }
        .stateIn(scope, SharingStarted.Eagerly, ModalUiState.Hidden)
```

`flatMapLatest` switches to the fresh connection's repo and cancels the prior on reconnect; `emptyFlow()`
covers between-connections. The `.scan` sits **downstream** of `flatMapLatest`, so across a reconnect the
inner source switches but the outer `scan` is **not** restarted — the accumulator survives connection churn
(see [Lifecycle, errors, edge cases](#lifecycle-errors-edge-cases) for the retain-on-teardown decision).
`modalEvents` was demoted to `private` in #492 because after the hoist nothing outside the coordinator
reads the raw event stream.

### 2. The fold (`scan`, `modalId`-keyed, last-shown-wins) + the ViewModel re-exposure

The fold uses the pure, side-effect-free `ModalUiState.reduce` — as of #492 co-located with the
`ModalUiState` type in **`data/model/ModalUiState.kt`** (moved from `ui/conversations/thread/` so the `data`-layer
coordinator can see it; `reduce` has no Android/UI dependency), **no logging** of any field:

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
\#437's forward-compat posture). It reuses `data.model.ModalOption` (no parallel option type). `Hidden` is
the initial / resolved-and-cleared state and does double duty as the inert default.

`ThreadViewModel` takes the coordinator's already-folded projection as a **defaulted** `val` ctor property
and re-exposes it with **no wrapping**:

```kotlin
class ThreadViewModel(
    …,
    // #492: the coordinator's process-scoped, reconnection-surviving "current modal" projection,
    // folded once at the coordinator (no longer per-thread-screen) and re-exposed here verbatim.
    // Default = a fresh MutableStateFlow(Hidden) so the fake-backed Koin graph + non-modal tests stay inert.
    val currentModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden),
    …,
)
```

As a `val` ctor property it **is** the exposed `currentModal` — the VM does not re-derive it. The
`armedOptionId` / `onModalOption` / `onModalCancel` / `sendAnswer` / `sendCancel` logic reads
`currentModal` / `currentModal.value` unchanged — a `StateFlow` still satisfies them, which is the
structural mechanism by which the arm/answer/cancel behaviour (and its tests) stays unchanged.

## Why `Eagerly`, not `WhileSubscribed`

This is the **load-bearing design call** of the fold — a deliberate deviation from the
`WhileSubscribed(5_000)` of the sibling signals (`isThinking`, `isStalled`, `connectionState`). Its
rationale moved verbatim from the VM to the coordinator in #492 (the reasoning is now the coordinator's):

- `scan` **re-emits its initial accumulator on every fresh upstream collection.** Under `WhileSubscribed`,
  when collection stops past the timeout the upstream cancels; on resubscription `scan` restarts and emits
  `Hidden`, **overwriting a retained `Open`**. Because the source `modalEvents` is `replay = 0`, the prior
  events do **not** replay to rebuild the accumulator — a still-open modal would silently clear. This is a
  deterministic consequence of `scan` + `replay = 0`, not a speculative guard.
- `Eagerly` collects for the **coordinator's process lifetime** (the `scope` is `SupervisorJob() +
  dispatcher`, created at `createdAtStart` Koin init and cancelled only by `close()`), so the `scan`
  accumulator runs **exactly once** and is monotonic; `.value` is always the true current projection. This
  matches the coordinator's own accumulate-a-`replay=0`-stream precedent (`currentRepository` /
  `connectionStatus`, both `Eagerly`). Cost is negligible — modals are one-at-a-time, user-driven, low-rate.
  **Collection begins at coordinator construction — before any thread screen — which is precisely why a
  pre-subscriber `modal_shown` is no longer dropped.**
- The sibling `isThinking` is safe under `WhileSubscribed` **only because `mapNotNull` never re-emits a
  stale value on resubscription** (a non-matching event produces no emission, so `.value` is retained).
  The same policy is wrong for a `scan` accumulator. Pick the started policy from the operator
  (`scan` accumulates vs `mapNotNull` transitions), not from the sibling.

> **Kotlin gotcha:** `scan`'s accumulator type is inferred from the initial value, so
> `scan(ModalUiState.Hidden) { … }` infers `R = ModalUiState.Hidden` (the singleton type) and rejects a
> lambda returning `ModalUiState.Open`. Type it explicitly: `scan<ModalEvent, ModalUiState>(ModalUiState.Hidden)`.

## Why a sibling `StateFlow`, not a `ThreadUiState` field

`currentModal` mirrors `isThinking` / `isStalled` / `connectionState`: a transient, cross-cutting signal
with a distinct source, taken by the stateless `ThreadScreen` as a **separate** parameter beside `state`.
Folding it into the `state` `combine` would force a restructure and touch its `initialValue`. The render
slice **#446** consumes it the same way — a separate `(state, currentModal, onEvent)` parameter on the
stateless screen. It is **app-level**, not per-conversation: there is **no `conversationId` filter**
(contrast [`thinkingTransition`](turn-state-thinking-flag.md)'s guard) because modal events carry no
`conversation_id`.

## Lifecycle, errors, edge cases

- **Lifecycle** — `stateIn(scope, Eagerly, Hidden)` on the coordinator's **process-lived** scope (not
  `viewModelScope` any more): collects for the coordinator's lifetime, cancelled only by `close()`. The VM's
  `currentModal` is just a reference to that one `StateFlow`; no parallel mutable modal state exists. The
  fold is pure (no dispatcher switch).
- **Errors** — none. `modalEvents` is a `SharedFlow` that never completes-with-error; malformed envelopes
  are already dropped at the #437 decode boundary, so every event reaching the fold is well-typed and the
  fold is total over the sealed `ModalEvent`. Absence of a live source is the empty flow ⇒ state stays
  `Hidden`. No `catch`, no result type.
- **Connection teardown = RETAIN, not reset (the #492 security-relevant decision).** On a connection drop
  `activeConnection` goes `null → emptyFlow()`, so no event flows and the `scan` **holds its last
  accumulator** — a still-`Open` modal is retained, *not* reset to `Hidden`. This is safe because the answer
  path is guarded by **deterministic code**, never by this projection: `coordinator.answerModal` /
  `cancelModal` throw `IllegalStateException` on no active connection (surfaced as a one-shot
  `modalSendErrors` snackbar), and `modalId`s are unique per instance so a stale answer can't match a fresh
  modal on a new connection (daemon rejects → `RelayErrorException` → same one-shot error). Belt-and-
  suspenders with **different fabric**: the projection is UI state, the guard is deterministic code (the
  [#490](../codebase/490.md) pairing pattern). **Clearing would be worse** — absent a *confirmed* daemon
  replay-on-reconnect (the replay cursor advances past seen events, so re-raise is not guaranteed), a reset
  would blank a still-outstanding modal and it would not come back, re-introducing the exact bug #492 fixes.
  Net effect of a stale `Open` is at worst a cosmetic lingering prompt superseded by the next
  `Shown`/`Dismissed`. If a future wire signal *guarantees* re-raise-or-dismiss on reconnect, a follow-up
  could switch to clear-on-teardown (out of scope — don't build for an unobserved failure).

## Wiring

`AppModule` fetches the **folded projection** off the already-registered concrete coordinator singleton at
the `ThreadViewModel` factory — **no new Koin binding** — mirroring the `liveSessionEvents` arg:

```kotlin
viewModel {
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        // #492: the process-scoped "current modal" projection, folded once at the coordinator.
        coordinator.currentModal,
        answerModal = coordinator::answerModal,
        cancelModal = coordinator::cancelModal,
        …,
    )
}
```

In the default debug build (`USE_RELAY_REPOSITORY` OFF, fake repository) no live coordinator event source
reaches this factory path; the defaulted `MutableStateFlow(Hidden)` keeps the state inert — `currentModal`
honestly holds `Hidden` with no live daemon.

## Related

- [#492 implementation notes](../codebase/492.md) — the hoist: files, the layering + teardown decisions,
  lessons.
- [#445 implementation notes](../codebase/445.md) — the original projection this hoists (the fold as it
  first landed inside the ViewModel).
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the decode seam that produces the
  `ModalEvent` stream; this slice realizes its "folding into a current-modal state is the consumer's
  projection" deferral (now folded at the coordinator).
- [Relay repository coordinator](relay-repository-coordinator.md) — owns + publishes the `modalEvents`
  seam (now private) and the hoisted `currentModal` fold (§ Modal event seam).
- [Turn-state thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) — the `isThinking`
  projection this is the **modal twin** of; contrast the `scan`/`Eagerly` vs `mapNotNull`/`WhileSubscribed`
  choice and the app-level vs per-conversation routing. (`isThinking` remains folded in the VM — it is
  per-conversation and screen-scoped; the modal projection is app-level, hence the hoist.)
- [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the other sibling transient signal
  (`isStalled`).
- [Guarded repo launch](guarded-repo-launch.md) ([#490](../codebase/490.md)) — the deterministic
  answer/cancel guard that makes the retain-on-teardown decision safe.
- [Thread screen](thread-screen.md) — the `ThreadViewModel` host; `currentModal` joins `isThinking` /
  `isStalled` / `connectionState` as a sibling signal the stateless screen takes as a separate parameter.
- [Permission-modal overlay](permission-modal-overlay.md) ([#446](../codebase/446.md)) — the render slice
  (shipped) that collects this `currentModal` in the route host and draws the overlay.
- Sibling slices: **#446** the render overlay (shipped) · **#444** answering / cancelling, split into
  [**#451**](modal-answer-flow.md) the behavior (shipped) and **#452** the render of the armed affordance.
- Producer SSOT: pyrycode#716 (surfaces only `permission` / `trust` classes; fail-safe-deny
  `default_option_id`, no per-option destructive marker), ADR 025 § Phase 3 modals, EPIC pyrycode#597.
