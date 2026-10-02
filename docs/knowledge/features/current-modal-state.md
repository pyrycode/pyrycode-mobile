# Current-modal state — the hoisted `hostModals` fold

The **state/projection half of the permission/choice-modal UI surface**: how the daemon's decoded modal
lifecycle (`modal_shown` → `modal_dismissed`) is folded into the host's outstanding prompts,
`hostModals: StateFlow<HostModalState>`. Introduced in [#445](../codebase/445.md) (split from \#443, the
render half of #439), part of the Phase 3 permission-modal feature (epic pyrycode#597, ADR 025).

**As of [#492](../codebase/492.md) the fold is hoisted to the process-scoped
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md).** It used to fold per-thread-screen inside
[`ThreadViewModel`](thread-screen.md), whose collection only began on navigating into a thread — so a
`modal_shown` fired **before any subscriber existed** was dropped by the `replay = 0`
[`modalEvents`](modal-events.md) seam, leaving the prompt stuck daemon-side while the phone showed nothing.
The coordinator outlives any screen and already owns the reconnection-surviving seam, so folding there
accumulates the projection whether or not a thread screen is subscribed. This closed a `USE_RELAY_REPOSITORY`
blocker (the bug manifests only with the relay repository live).

**As of [#1337](../../specs/architecture/1337-hold-every-outstanding-prompt.md) the fold holds every
outstanding prompt, not one.** Through #1337 the coordinator kept a single `ModalUiState` per host — a
second chat's `modal_shown` **replaced** the first chat's, and the first's later `modal_dismissed` matched
nothing (the fold's `modalId` guard only protected the one held modal from a spoofed dismiss, not a second
chat's prompt from eviction). #1337 replaces that single value with
[`HostModalState`](#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure):
every still-open prompt, keyed on `modalId`, plus the ids resolved on the current connection. Each thread
scopes the host state down to its own conversation
([`HostModalState.scopedTo`](#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure));
answering or dismissing one conversation's prompt never touches another's. This follows desktop's
`reduceModal` (`src/renderer/src/store/modalPrompts.ts`) and closes desktop #415/#510/#1140's mobile gap.
**The ViewModel re-exposes the coordinator's scoped projection** — it still folds nothing itself.

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
RelayRepositoryCoordinator.modalEvents : Flow<ModalEvent?>   ◀── #445 seam, now #492-PRIVATE; #1337 prefixes each
        │                                                        connection's inner flow with a null reconnect marker
        │  one eager collector folds HostModalState.reduce(event) / .reconnected() (a null input) into a
        │  MutableStateFlow via update {} (#1340 — was a scan + stateIn; see § below). recordModalAction(action)
        │  folds this phone's own answer/cancel/rejection actions into the same MutableStateFlow, also via
        │  update {}, so neither writer loses the other's change (#1340).
        ▼
RelayRepositoryCoordinator.hostModals : StateFlow<HostModalState>   ◀── #1337 the process-scoped fold: every
        │                                                               outstanding prompt + this connection's resolved ids
        │                                                               + (#1340) the chats showing a rejection notice
        │  HostModalState.scopedTo(conversationId)   ◀── #1337 per-thread filter (this ViewModel's own conversationId)
        ▼
ThreadViewModel.hostModal : StateFlow<HostModalState>   ◀── #1337 taken verbatim as a private ctor property (renamed from a
        │                                                     StateFlow<ModalUiState> in #492/#816; same ctor name)
        ▼
ThreadViewModel.currentModal : StateFlow<ModalUiState>   ◀── #1337 scoped via HostModalState.scopedTo, stateIn(viewModelScope, Eagerly)
        │  separate parameter beside `state` / `isThinking` / `isStalled`
        ▼
ThreadScreen → modal overlay (#446 renders it; #451 answers it, #452 renders the armed affordance)
```

**As of [#1338](#related) the conversation-list attention readers take the whole `hostModals.outstanding`
list, not a single-value projection.** `RelayRepositoryCoordinator.currentModal` and
`RelayConnectionRegistry.currentModal` (the `hostModals.map { it.latestOutstanding }` view) are gone;
`HostModalState.latestOutstanding` itself stayed as dead code until [#1340](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340)
deleted it. `HostAttentionState.resolve` and
`HostConversationSource.promptKeys` now take `List<ModalUiState.Open>` directly, matching desktop's
`selectHasOutstandingFor` — a chat waits while *any* outstanding prompt belongs to it, not only the most
recent one. No thread screen ever read `currentModal`; threads scope `hostModals` directly, unaffected by
this change. See [§ The `HostModalState` fold](#2-the-hostmodalstate-fold-1337--the-viewmodel-re-exposure)
below, and [Dependency injection — host conversation
source](dependency-injection-host-conversation-source.md) for the reader side.

The coordinator's fold is **host-level**, holding every outstanding prompt across all of that host's
conversations in one `HostModalState`, keyed on `modalId` (not a separate fold per conversation). Since
\#816, `modal_shown` carries a `conversation_id` (daemon #1065) that scopes **display**: each `ThreadViewModel`
filters the host's prompts down to its own conversation via `HostModalState.scopedTo` before exposing its own
`currentModal`, so a modal raised by conversation A never renders — or answers — in an open thread for
conversation B on the same host, and (since #1337) A's prompt is never evicted by B's. A blank/absent
`conversation_id` decodes to `""` and matches no thread (see [Modal
events](modal-events.md#conversation_id-the-one-defaulted-field-816)).

### 1. The coordinator seam (reconnection-surviving) → the hoisted fold

The decoded events live on the **concrete** `RemoteConversationRepository.modalEvents` — a `SharedFlow`
that is **connection-scoped** (rebuilt per connection, absent between) and deliberately **not** on the
`ConversationRepository` interface the thread ViewModel consumes (the
[Why on the concrete repo](modal-events.md#why-on-the-concrete-repo-not-the-interface-ac-4) posture). So
the ViewModel cannot reach it directly. [`RelayRepositoryCoordinator`](relay-repository-coordinator.md)
threads it up (a byte-for-byte mirror of the `liveSessionEvents` seam) **and folds it** into the host's
outstanding prompts:

```kotlin
// #492: modalEvents is PRIVATE — its sole consumer is hostModals. #1337 widens the element type to
// ModalEvent? and prefixes each connection's inner flow with a null "this connection just started" marker.
@OptIn(ExperimentalCoroutinesApi::class)
private val modalEvents: Flow<ModalEvent?> =
    activeConnection.flatMapLatest { conn ->
        conn?.repo?.modalEvents?.onStart<ModalEvent?> { emit(null) } ?: emptyFlow()
    }

// #1340: a MutableStateFlow, not a stateIn(scan(...)), so recordModalAction (below) can fold this phone's
// own actions into the same state synchronously. One collector, launched Eagerly in the coordinator's
// process-scoped `scope`, folds every frame: a null input (the reconnect marker) folds HostModalState
// .reconnected() — every outstanding/resolved prompt is cleared, but rejectedConversations survives
// (§ below) — and an event folds HostModalState.reduce(event), same table as #1337.
private val hostModalState = MutableStateFlow(HostModalState())
val hostModals: StateFlow<HostModalState> = hostModalState.asStateFlow()

init {
    scope.launch {
        modalEvents.collect { event ->
            hostModalState.update { state -> if (event == null) state.reconnected() else state.reduce(event) }
        }
    }
}

/**
 * Folds one of this phone's own prompt actions (#1340: an answer/cancel closing its own prompt, a refused
 * answer, or the user dismissing that refusal) into [hostModals] synchronously, through the same
 * `update {}` the wire collector above uses, so a `modal_shown` arriving mid-tap is never lost to a race
 * between the two writers.
 */
fun recordModalAction(action: ModalAction) {
    hostModalState.update { it.reduce(action) }
}
```

`flatMapLatest` switches to the fresh connection's repo and cancels the prior on reconnect; `emptyFlow()`
covers between-connections. `onStart { emit(null) }` is the **reconnect marker**: it is the first emission
of the new inner flow, so it folds strictly after the old connection's collection is cancelled and strictly
before the new connection's first `modal_shown` is collected — the clear can never race a re-sent frame in
either direction. The collector sits **downstream** of `flatMapLatest`, so across the switch the inner
source changes but the collector itself is **not** restarted — it only resets `hostModalState` to
`reconnected()` when a `null` marker reaches it, i.e. on a **new connection being published**, never on a
plain teardown (`activeConnection → null` switches to `emptyFlow()`, which emits nothing and folds nothing
— see [Lifecycle, errors, edge cases](#lifecycle-errors-edge-cases)). `modalEvents` stays `private` because
nothing outside the coordinator reads the raw event stream; its element type widened to `ModalEvent?` in
\#1337 is an implementation detail of the marker, invisible past `hostModals`.

### 2. The `HostModalState` fold (#1337) + the ViewModel re-exposure

The fold uses the pure, side-effect-free `HostModalState.reduce`, co-located with `ModalUiState` in
**`data/model/ModalUiState.kt`** (moved there in #492 so the `data`-layer coordinator can see it; `reduce`
has no Android/UI dependency), **no logging** of any field:

| receiver → event | result | why |
|---|---|---|
| any → `Shown(id=X)`, X already in `resolved` | unchanged | an answered/dismissed prompt doesn't come back before the next reconnect (AC #3) |
| any → `Shown(id=X)`, X held in `outstanding` | that entry **replaced in place**, same position | a re-shown prompt (e.g. a refreshed offer) updates rather than reorders (AC #1) |
| any → `Shown(id=X)`, X new | **appended** to `outstanding` as `Open` carrying the event verbatim | a second chat's prompt no longer evicts the first's — the #1337 fix (AC #1) |
| `Dismissed(id=X)`, X held in `outstanding` | X removed from `outstanding`, appended to `resolved` as `Dismissed(modalId, outcome, source, conversationId = held.conversationId)` | the prompt resolved; its conversation is copied from the held `Open` since the wire dismiss carries none (#816) (AC #3) |
| `Dismissed(id=X)`, X not held | unchanged | **spoofed-dismiss safety** — an out-of-band dismiss can't clear a prompt that isn't open (AC #3); this is also why the daemon's own `modal_dismissed` for a prompt this phone just answered itself is a no-op (#1340 — `AnsweredHere` already removed it from `outstanding`) |

Since [#1340](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340), `HostModalState.reduce` is overloaded on a second, local `ModalAction` sealed type — this phone's own answer/cancel/rejection-dismissal, folded beside the wire's `ModalEvent`s but never derived from one. `AnsweredHere(modalId)` for a held id removes it from `outstanding` and appends a `Dismissed(..., answeredHere = true)` to `resolved` — the same shape the wire fold produces, so the existing `Shown`-ignores-a-resolved-id row above also keeps a repeated `modal_shown` for it from reopening the card, with no second table needed. `Rejected(conversationId)` and `RejectionDismissed(conversationId)` add to and remove from `rejectedConversations` and never touch `outstanding`/`resolved`. See that doc for the ViewModel side (`recordModalAction`, `sendAnswer`/`sendCancel`, `answerRejected`).

`HostModalState(outstanding: List<ModalUiState.Open>, resolved: List<ModalUiState.Dismissed>)` replaces the
pre-#1337 single `ModalUiState` accumulator. `outstanding` is in first-shown order; `resolved` only accumulates
for the life of the **current connection** — a new connection resets both lists to empty (§ below), so
`resolved` is never a mechanism for "answered forever," only "answered since the last reconnect."

`ModalUiState` is still a `sealed interface { Hidden, Open, Dismissed }`, one entry of either `outstanding`
or `resolved`. `Open` mirrors `ModalEvent.Shown` field-for-field (`modalId`, `modalClass`, `title`, `prompt`,
`options: List<ModalOption>` in wire array order, `defaultOptionId`, and since
[#818](#the-alwaysallowrules-field-818) `alwaysAllowRules`); `Dismissed` mirrors `ModalEvent.Dismissed`
(`modalId`, `outcome`, `source`) plus the copied `conversationId`. Every field is carried **verbatim** — no
parsing, enum-coercion, trimming, or reordering (preserves \#437's forward-compat posture). It reuses
`data.model.ModalOption` (no parallel option type). `Hidden` is `HostModalState.scopedTo`'s result when a
conversation has neither an outstanding prompt nor a recorded dismissal, and still does double duty as the
inert default for a fresh/unbound `ThreadViewModel`.

Two scoping functions sit on top, both pure:

```kotlin
fun HostModalState.scopedTo(conversationId: String): ModalUiState =
    outstanding.firstOrNull { it.scopedTo(conversationId) !== ModalUiState.Hidden }
        ?: resolved.lastOrNull { it.scopedTo(conversationId) !== ModalUiState.Hidden }?.takeUnless { it.answeredHere }
        ?: ModalUiState.Hidden
```

`scopedTo(conversationId)` — what `ThreadViewModel.currentModal` is built from — is **first outstanding,
else most recent dismissal (unless this phone made it itself), else Hidden**, each step reusing
`ModalUiState.scopedTo` (unchanged since #816: a blank or non-matching `conversationId` always yields
`Hidden`). The "most recent dismissal" fallback is why `ThreadScreen`'s `LaunchedEffect(modalState.modalId)`
resolved-snackbar can fire again: reopening a conversation re-reads the same `Dismissed` from `resolved`
until the next reconnect clears it — see [§ Permission-modal overlay](permission-modal-overlay.md#the-dismissal-dismissed)
for the render-side consequence. The `takeUnless { it.answeredHere }` is [#1340](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340):
without it, a local answer's own `Dismissed` record would be handed back the same way a remote one is, and
the screen's dismissal-reason `LaunchedEffect` would announce the user's own tap. It also fixes a trap the
naive fix (just filtering `answeredHere` out of the fallback's own match) would have left: once the newest
resolution for a chat is `answeredHere` and is skipped, `lastOrNull` does not fall further back to that
chat's *older* remote dismissal — the whole fallback collapses to `Hidden` for that chat, so an older
"Resolved on another device" cannot resurface either.

### The `alwaysAllowRules` field (#818)

`ModalEvent.Shown.alwaysAllowRules` decodes the daemon's `modal_shown.always_allow` offer (daemon #2364) —
the whole rule list when `offered` is `true` and every rule fits the daemon's bounds (1 to 16 strings, each 1
to 1024 UTF-8 bytes), or the empty list on any violation (never a truncated prefix). `reduce` copies it
verbatim into `Open` like every other field above — **no special-casing in the fold**: an out-of-bounds or
absent offer simply decodes to an empty list upstream, at [`ModalShownPayloadDto.toAlwaysAllowRules`](modal-events.md),
not here. `Open` derives `val offersAlwaysAllow: Boolean = modalClass == "permission" && alwaysAllowRules
.isNotEmpty()`, the single property the render and answer paths both gate on (see [Permission-modal overlay
§ The always-allow offer](permission-modal-overlay.md#the-always-allow-offer-818) and [Modal answer flow §
The session-grant draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)). A later `Shown`
for the same `modalId` still **supersedes** the whole `Open` (the "replace in place" row of the fold table
above), so a re-offer with a different rule list replaces the old one rather than merging with it.

`ThreadViewModel` takes the coordinator's already-folded host state as a **defaulted, private** `hostModal`
ctor property (its type widened from `StateFlow<ModalUiState>` to `StateFlow<HostModalState>` in #1337; the
ctor parameter name is unchanged from the #816 rename) and derives its own scoped `currentModal` from it:

```kotlin
class ThreadViewModel(
    …,
    // #1337: the host coordinator's process-scoped, reconnection-surviving fold of every outstanding
    // prompt. #1337 scopes it to this thread as [currentModal]. Default = a fresh MutableStateFlow(…) so
    // the fake-backed Koin graph + non-modal tests stay inert.
    private val hostModal: StateFlow<HostModalState> = MutableStateFlow(HostModalState()),
    …,
) {
    // #1337: this thread's own view of the host's prompts — its own first outstanding prompt, else its
    // own most recent dismissal, else Hidden. Seeded from hostModal.value so .value is right at construction.
    val currentModal: StateFlow<ModalUiState> =
        hostModal
            .map { it.scopedTo(conversationId) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, hostModal.value.scopedTo(conversationId))
}
```

`AppModule` passes the coordinator's `hostModals` as the `hostModal` argument (the value supplied changed
in #1337; the argument name did not). `armedOptionId` combines the scoped `currentModal`, so an arm never
surfaces for a foreign prompt. `onModalOption` / `onModalCancel` do **not** read `currentModal.value` — they
read `hostModal.value.scopedTo(conversationId)` synchronously through a private `scopedModal()` helper, so
the input guard never lags the host flow by a dispatch: a tap in another thread cannot answer this thread's
prompt (or vice versa) even in the instant before the `stateIn` collector runs, and (since #1337) cannot
answer a *different conversation's* held prompt on the same host either, since `scopedTo` only ever returns
this thread's own entry. `sendAnswer` / `sendCancel` are unchanged — they still take a `modalId` and never
see a conversation.

## Why `Eagerly`, not `WhileSubscribed`

This is the **load-bearing design call** of the fold — a deliberate deviation from the
`WhileSubscribed(5_000)` of the sibling signals (`isThinking`, `isStalled`, `connectionState`). Its
rationale moved verbatim from the VM to the coordinator in #492 (the reasoning is now the coordinator's):

- A `scan`-backed `stateIn` **re-emits its initial accumulator on every fresh upstream collection.** Under
  `WhileSubscribed`, when collection stops past the timeout the upstream cancels; on resubscription `scan`
  restarts and emits an empty `HostModalState`, **overwriting every held prompt**. Because the source
  `modalEvents` is `replay = 0`, the prior events do **not** replay to rebuild the accumulator — still-open
  prompts would silently clear. This was the deterministic argument against `WhileSubscribed` while the fold
  was a `scan`. [#1340](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340)
  replaced the `scan` with a `MutableStateFlow` folded by one `Eagerly`-launched collector plus
  `recordModalAction`'s synchronous `update {}` — so the collector, not an operator restart, is now the
  thing a `WhileSubscribed` policy would have to govern, and the same conclusion holds: nothing subscribes
  to stop or restart it, so it must run for the coordinator's own lifetime, independent of any screen.
- `Eagerly` collects for the **coordinator's process lifetime** (the `scope` is `SupervisorJob() +
  dispatcher`, created at `createdAtStart` Koin init and cancelled only by `close()`), so the fold runs
  **exactly once** per coordinator and is monotonic *within a connection*; `.value` is always the true
  current projection. This matches the coordinator's own accumulate-a-`replay=0`-stream precedent
  (`currentRepository` / `connectionStatus`, both `Eagerly`). Cost is negligible — modals are low-rate and
  the outstanding/resolved lists are cleared on every reconnect (§ below; the `rejectedConversations` set
  since #1340 is the one part that is not and is bounded by the number of chats with a live rejection, never
  growing per modal), so they cannot grow across the app's whole session. **Collection begins at coordinator
  construction — before any thread screen — which is precisely why a pre-subscriber `modal_shown` is no
  longer dropped.**
- The sibling `isThinking` is safe under `WhileSubscribed` **only because `mapNotNull` never re-emits a
  stale value on resubscription** (a non-matching event produces no emission, so `.value` is retained).
  The same policy is wrong for an accumulating fold with no replay behind it. Pick the started policy from
  what the fold actually does (accumulate vs transition), not from the sibling.

## Why a sibling `StateFlow`, not a `ThreadUiState` field

`currentModal` mirrors `isThinking` / `isStalled` / `connectionState`: a transient, cross-cutting signal
with a distinct source, taken by the stateless `ThreadScreen` as a **separate** parameter beside `state`.
Folding it into the `state` `combine` would force a restructure and touch its `initialValue`. The render
slice **#446** consumes it the same way — a separate `(state, currentModal, onEvent)` parameter on the
stateless screen. Since #816 it **does** carry a `conversationId` filter, like the per-conversation
routing [`TurnPhaseProjection`](turn-state-thinking-flag.md#the-data-path) keys its writes by — but the
filter lives in the ViewModel
(`hostModal.map { it.scopedTo(conversationId) }`), not inside the coordinator's fold, which since #1337
holds every outstanding prompt across the host.

## Lifecycle, errors, edge cases

- **Lifecycle** — `hostModalState` is a `MutableStateFlow` folded by one collector launched `Eagerly` in
  the coordinator's **process-lived** `scope` (not `viewModelScope`; #1340, was a `scan`-backed `stateIn`):
  the host fold collects for the coordinator's lifetime, cancelled only by `close()`.
  `recordModalAction` folds beside it through the same `update {}`, non-suspending, so a synchronous caller
  (the VM's `sendAnswer`/`sendCancel`) sees its own write land before anything else runs. The VM layers its
  own `stateIn(viewModelScope, Eagerly, …)` on top to compute the scoped `currentModal`, cancelled with the
  VM; no parallel mutable modal state exists on either layer, and the scoping `map` is pure (no dispatcher
  switch).
- **Errors** — none. `modalEvents` is a `SharedFlow` that never completes-with-error; malformed envelopes
  are already dropped at the #437 decode boundary, so every event reaching the fold is well-typed and the
  fold is total over the sealed `ModalEvent` plus the `null` reconnect marker. Absence of a live source is
  the empty flow ⇒ the fold holds whatever it already had (no event ⇒ no `update` call — see teardown
  below). No `catch`, no result type.
- **Connection teardown still RETAINs; a new connection CLEARS the prompts but, since #1340, KEEPS the
  rejection notices (#1337 revised #492's decision; #1340 revises #1337's).**
  On a connection drop `activeConnection` goes `null → emptyFlow()`, so no event flows — including no
  reconnect marker — and the collector simply stops being fed: it **holds its last fold**, every
  still-outstanding prompt retained, *not* reset to empty. This half of #492's decision is unchanged and for
  the same reason: the answer path is guarded by **deterministic code**, never by this projection —
  `coordinator.answerModal` / `cancelModal` throw `IllegalStateException` on no active connection (since
  #1340, nothing is shown for it — the next connection re-sends the still-outstanding prompt), and
  `modalId`s are unique per instance so a stale answer can't match a fresh prompt on a new connection
  (daemon rejects → `RelayErrorException` → since #1340 the chat that sent it shows the rejection notice).
  Belt-and-suspenders with **different fabric**: the projection is UI state, the guard is deterministic code
  (the [#490](../codebase/490.md) pairing pattern).
  <br><br>
  What #492 left open — "should a connection drop clear a stale `Open`?" — #1337 answered for the **new
  connection**, not the drop: when `activeConnection` switches to a *fresh* `Connection`, the `onStart {
  emit(null) }` marker (§ above) folds `HostModalState.reconnected()` before that connection's first
  `modal_shown` is collected. **#1340 narrows what `reconnected()` actually clears**: it empties
  `outstanding` and `resolved` exactly as #1337's full reset did, but keeps `rejectedConversations` —
  the chats showing "Your answer was rejected." survive a reconnect, since nothing about a rejection is
  per-connection and only the user's own dismissal (or the end of the pairing) should clear it. Clearing
  `outstanding`/`resolved` is still safe for the reason #1337 established: the daemon **guarantees** a
  connect-time re-send of every still-outstanding prompt (`protocol-mobile.md` § Reconcile on (re)connect)
  — the "unconfirmed re-raise" gap #492 cited still doesn't exist, and an `answeredHere` resolution
  (§ below) is exactly the kind of thing that *should* drop on reconnect, since its only job was to keep a
  repeated `modal_shown` from reopening a card on the connection that already closed it. Net effect: a
  prompt held across a **plain disconnect** (no new connection yet) stays exactly as #492 left it; a prompt
  (or an `answeredHere` record) held into a **new connection** is dropped — a still-outstanding one only
  returns if the daemon re-sends it, which per the protocol it always does for anything still outstanding
  and never does for anything already answered — while a rejection notice is not dropped at all. See
  [Permission-modal overlay](permission-modal-overlay.md) for what the outstanding/resolved reset means for
  the don't-ask-again draft and the resolved-snackbar, and [Modal answer flow § Local close on tap and the
  in-chat rejection notice](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340)
  for the rejection notice itself.

## Wiring

`AppModule` fetches the **folded host state** off the already-registered concrete coordinator singleton at
the `ThreadViewModel` factory — **no new Koin binding** — mirroring the `liveSessionEvents` arg:

```kotlin
viewModel {
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        // #1337: the process-scoped fold of every outstanding prompt, cleared on each new connection
        // (since #1340, except for its rejection notices).
        hostModal = coordinator.hostModals,
        answerModal = coordinator::answerModal,
        cancelModal = coordinator::cancelModal,
        // #1340: folds this phone's own answer/cancel/rejection actions into the same hostModals state.
        recordModalAction = coordinator::recordModalAction,
        …,
    )
}
```

`AppModule` supplies `coordinator.hostModals` as `hostModal` in both real and demo builds; the
[repository build option](dependency-injection.md#how-it-works) does not gate this flow.
The defaulted `MutableStateFlow(HostModalState())` is used by direct test/preview construction
that omits the argument. A fresh coordinator also starts at an empty `HostModalState` until a modal
event arrives; connection teardown retains its held prompts as described above, and since that retained
state still flows through `scopedTo` in the VM, a retained prompt for another conversation stays invisible
to a thread that isn't its own.

## Related

- [#492 implementation notes](../codebase/492.md) — the hoist: files, the layering + teardown decisions,
  lessons.
- [#445 implementation notes](../codebase/445.md) — the original projection this hoists (the fold as it
  first landed inside the ViewModel).
- [Modal events](modal-events.md) ([#437](../codebase/437.md)) — the decode seam that produces the
  `ModalEvent` stream; this slice realizes its "folding into a current-modal state is the consumer's
  projection" deferral (now folded at the coordinator).
- [Relay repository coordinator](relay-repository-coordinator.md) — owns + publishes the `modalEvents`
  seam (now private) and the hoisted `hostModals` fold (§ Modal event seam).
- [Turn-state thinking flag](turn-state-thinking-flag.md) ([#406](../codebase/406.md)) — the `isThinking`
  projection this is the **modal twin** of; contrast the `scan`/`Eagerly` vs `mapNotNull`/`WhileSubscribed`
  choice. `isThinking` remains folded per-conversation directly in the VM; the modal *fold* stays hoisted
  and host-level at the coordinator — since #1337 it folds every conversation's outstanding prompts into
  one `HostModalState`, not a single value — and the VM applies its own per-conversation filter (`scopedTo`)
  on top, giving the two signals the same conversation-scoped shape at the point the screen consumes them.
- `docs/specs/architecture/1337-hold-every-outstanding-prompt.md` — the #1337 plan: the `HostModalState`
  design, the reconnect-marker ordering proof, and the security review for holding an unbounded list per
  host.
- [#1338](../../specs/architecture/1338-every-prompt-waits.md) (shipped) — moved `HostAttentionState.resolve`
  and `HostConversationSource.promptKeys` off the single-value `currentModal` view onto the full
  `hostModals.outstanding` list, so the conversation list shows attention for every held prompt, not only the
  most recent one. See [Dependency injection — host conversation
  source](dependency-injection-host-conversation-source.md).
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
- [Modal answer flow § Local close on tap and the in-chat rejection notice](modal-answer-flow.md#local-close-on-tap-and-the-in-chat-rejection-notice-1340)
  ([#1340](modal-answer-flow.md)) — replaced the `scan`-backed `stateIn` here with the `MutableStateFlow` +
  `recordModalAction` fold described above, added `rejectedConversations` and `reconnected()`, and the
  `answeredHere` resolution `scopedTo` now skips.
- Producer SSOT: pyrycode#716 (surfaces only `permission` / `trust` classes; fail-safe-deny
  `default_option_id`, no per-option destructive marker), ADR 025 § Phase 3 modals, EPIC pyrycode#597.
