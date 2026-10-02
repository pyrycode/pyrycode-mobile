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
        │  scan + HostModalState.reduce (modalId-keyed, hold-all) — a null input resets to HostModalState(),
        │  stateIn(scope, Eagerly)   ◀── #492 hoists the fold here; #1337 widens it to every outstanding prompt
        ▼
RelayRepositoryCoordinator.hostModals : StateFlow<HostModalState>   ◀── #1337 the process-scoped fold: every
        │                                                               outstanding prompt + this connection's resolved ids
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
`RelayConnectionRegistry.currentModal` (the `hostModals.map { it.latestOutstanding }` view) and
`HostModalState.latestOutstanding` itself are gone; `HostAttentionState.resolve` and
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

// #1337: every outstanding prompt + this connection's resolved ids, folded once at this process-scoped
// layer. A null input (the reconnect marker) resets to an empty HostModalState — see § below.
val hostModals: StateFlow<HostModalState> =
    modalEvents
        .scan(HostModalState()) { state, event -> if (event == null) HostModalState() else state.reduce(event) }
        .stateIn(scope, SharingStarted.Eagerly, HostModalState())
```

`flatMapLatest` switches to the fresh connection's repo and cancels the prior on reconnect; `emptyFlow()`
covers between-connections. `onStart { emit(null) }` is the **reconnect marker**: it is the first emission
of the new inner flow, so it folds strictly after the old connection's collection is cancelled and strictly
before the new connection's first `modal_shown` is collected — the clear can never race a re-sent frame in
either direction. The `.scan` sits **downstream** of `flatMapLatest`, so across the switch the inner source
changes but the outer `scan` is **not** restarted — its accumulator only resets when a `null` marker reaches
it, i.e. on a **new connection being published**, never on a plain teardown (`activeConnection → null`
switches to `emptyFlow()`, which emits nothing and folds nothing — see [Lifecycle, errors,
edge cases](#lifecycle-errors-edge-cases)). `modalEvents` stays `private` because nothing outside the
coordinator reads the raw event stream; its element type widened to `ModalEvent?` in #1337 is an
implementation detail of the marker, invisible past `hostModals`.

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
| `Dismissed(id=X)`, X not held | unchanged | **spoofed-dismiss safety** — an out-of-band dismiss can't clear a prompt that isn't open (AC #3) |

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
        ?: resolved.lastOrNull { it.scopedTo(conversationId) !== ModalUiState.Hidden }
        ?: ModalUiState.Hidden
```

`scopedTo(conversationId)` — what `ThreadViewModel.currentModal` is built from — is **first outstanding,
else most recent dismissal, else Hidden**, each step reusing `ModalUiState.scopedTo` (unchanged since #816:
a blank or non-matching `conversationId` always yields `Hidden`). The "most recent dismissal" fallback is
why `ThreadScreen`'s `LaunchedEffect(modalState.modalId)` resolved-snackbar can fire again: reopening a
conversation re-reads the same `Dismissed` from `resolved` until the next reconnect clears it — see [§
Permission-modal overlay](permission-modal-overlay.md#the-dismissal-dismissed) for the render-side
consequence.

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

- `scan` **re-emits its initial accumulator on every fresh upstream collection.** Under `WhileSubscribed`,
  when collection stops past the timeout the upstream cancels; on resubscription `scan` restarts and emits
  an empty `HostModalState`, **overwriting every held prompt**. Because the source `modalEvents` is
  `replay = 0`, the prior events do **not** replay to rebuild the accumulator — still-open prompts would
  silently clear. This is a deterministic consequence of `scan` + `replay = 0`, not a speculative guard.
- `Eagerly` collects for the **coordinator's process lifetime** (the `scope` is `SupervisorJob() +
  dispatcher`, created at `createdAtStart` Koin init and cancelled only by `close()`), so the `scan`
  accumulator runs **exactly once** per coordinator and is monotonic *within a connection*; `.value` is
  always the true current projection. This matches the coordinator's own accumulate-a-`replay=0`-stream
  precedent (`currentRepository` / `connectionStatus`, both `Eagerly`). Cost is negligible — modals are
  low-rate and the lists are cleared on every reconnect (§ below), so they cannot grow across the app's
  whole session. **Collection begins at coordinator construction — before any thread screen — which is
  precisely why a pre-subscriber `modal_shown` is no longer dropped.**
- The sibling `isThinking` is safe under `WhileSubscribed` **only because `mapNotNull` never re-emits a
  stale value on resubscription** (a non-matching event produces no emission, so `.value` is retained).
  The same policy is wrong for a `scan` accumulator. Pick the started policy from the operator
  (`scan` accumulates vs `mapNotNull` transitions), not from the sibling.

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

- **Lifecycle** — `stateIn(scope, Eagerly, HostModalState())` on the coordinator's **process-lived** scope
  (not `viewModelScope`): the host fold collects for the coordinator's lifetime, cancelled only by
  `close()`. The VM layers its own `stateIn(viewModelScope, Eagerly, …)` on top to compute the scoped
  `currentModal`, cancelled with the VM; no parallel mutable modal state exists on either layer, and the
  scoping `map` is pure (no dispatcher switch).
- **Errors** — none. `modalEvents` is a `SharedFlow` that never completes-with-error; malformed envelopes
  are already dropped at the #437 decode boundary, so every event reaching the fold is well-typed and the
  fold is total over the sealed `ModalEvent` plus the `null` reconnect marker. Absence of a live source is
  the empty flow ⇒ the fold holds whatever it already had (no event ⇒ no reduce call — see teardown below).
  No `catch`, no result type.
- **Connection teardown still RETAINs; a new connection now CLEARS (#1337 revises #492's decision).**
  On a connection drop `activeConnection` goes `null → emptyFlow()`, so no event flows — including no
  reconnect marker — and the `scan` **holds its last accumulator**: every still-outstanding prompt is
  retained, *not* reset to empty. This half of #492's decision is unchanged and for the same reason: the
  answer path is guarded by **deterministic code**, never by this projection — `coordinator.answerModal` /
  `cancelModal` throw `IllegalStateException` on no active connection (surfaced as a one-shot
  `modalSendErrors` snackbar), and `modalId`s are unique per instance so a stale answer can't match a fresh
  prompt on a new connection (daemon rejects → `RelayErrorException` → same one-shot error). Belt-and-
  suspenders with **different fabric**: the projection is UI state, the guard is deterministic code (the
  [#490](../codebase/490.md) pairing pattern).
  <br><br>
  What #492 left open — "should a connection drop clear a stale `Open`?" — #1337 answers for the **new
  connection**, not the drop: when `activeConnection` switches to a *fresh* `Connection`, the `onStart {
  emit(null) }` marker (§ above) resets the fold to an empty `HostModalState` before that connection's first
  `modal_shown` is collected. This is now safe because the daemon **guarantees** a connect-time re-send of
  every still-outstanding prompt (`protocol-mobile.md` § Reconcile on (re)connect) — the "unconfirmed
  re-raise" gap #492 cited no longer exists. Net effect: a prompt held across a **plain disconnect** (no new
  connection yet) stays exactly as #492 left it; a prompt held into a **new connection** is dropped and only
  returns if the daemon re-sends it — which per the protocol it always does for anything still outstanding,
  and never does for anything already answered. See [Permission-modal overlay](permission-modal-overlay.md)
  for what this means for the don't-ask-again draft and the resolved-snackbar.

## Wiring

`AppModule` fetches the **folded host state** off the already-registered concrete coordinator singleton at
the `ThreadViewModel` factory — **no new Koin binding** — mirroring the `liveSessionEvents` arg:

```kotlin
viewModel {
    ThreadViewModel(
        get(), get(), get(), get(),
        coordinator.liveSessionEvents,
        // #1337: the process-scoped fold of every outstanding prompt, cleared on each new connection.
        hostModal = coordinator.hostModals,
        answerModal = coordinator::answerModal,
        cancelModal = coordinator::cancelModal,
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
- Producer SSOT: pyrycode#716 (surfaces only `permission` / `trust` classes; fail-safe-deny
  `default_option_id`, no per-option destructive marker), ADR 025 § Phase 3 modals, EPIC pyrycode#597.
