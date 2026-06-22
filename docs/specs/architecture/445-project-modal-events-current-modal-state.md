# #445 — Project the permission-modal stream into hoisted modal state

**Ticket:** [#445](https://github.com/pyrycode/pyrycode-mobile/issues/445) · **Size:** S · **Labels:** `security-sensitive`
**Split from #443** (this is slice A, projection/state; #446 is slice B, the render overlay, blocked by this).
**Depends on:** #437 (decoded `modalEvents`, shipped).

## Design source

N/A — data/state layer, no UI. The visual overlay, fail-safe-deny default highlight, inert-text
output-encoding, and dismiss-reason UI are the sibling render slice **#446** (blocked by this one).

## Context

#437 (shipped) decodes the two interactive **modal** envelopes (`modal_shown` / `modal_dismissed`)
into a `SharedFlow<ModalEvent>` on the **concrete** `RemoteConversationRepository.modalEvents`. The
stream is `replay = 0` (events, not held state) and carries **no `conversation_id`** — `modalId` is the
sole correlation key.

This slice does two things:

1. **Surface** that stream up to the thread ViewModel through a `RelayRepositoryCoordinator`
   passthrough seam — the exact mirror of the `liveSessionEvents` passthrough (the stream lives on the
   concrete repo, not the `ConversationRepository` interface/facade).
2. **Project** the stream into a single hoisted "current modal" observable state: a `Shown` sets the
   current modal; a `Dismissed` matching the open `modalId` clears it and carries the resolution reason.

Because the stream is `replay = 0`, no held current-modal state exists upstream — folding "which modal
is open" is this slice's job. Because modal events carry no `conversation_id`, the state is **app-level**
(one active modal across the app), **not** scoped per conversation — but it is hoisted on the thread
ViewModel as a sibling to the existing transient signals (`isThinking` #406, `isStalled` #395), reachable
by a stateless screen (the #446 consumer).

## Files to read first

| Path / lines | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt:29-69` | The source events. `ModalEvent.Shown` (`modalId`, `modalClass`, `title`, `prompt`, `options: List<ModalOption>`, `defaultOptionId`) and `Dismissed` (`modalId`, `outcome`, `source`); `ModalOption(id, label)`. The verbatim-carry, no-enum-coercion contract (#437). The projection's `Open`/`Dismissed` mirror these fields 1:1. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:104-116` | VM ctor + the defaulted `liveSessionEvents: Flow<…> = emptyFlow()` precedent at :111 (keeps fake-backed graph + existing tests inert). Add `modalEvents` the same way. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:232-284` | The `isThinking` (:241) / `isStalled` (:258) **sibling `StateFlow`** exposure pattern (a separate VM property, *not* a `ThreadUiState` field) and `thinkingTransition` (:273) the transition-fold precedent. `currentModal` mirrors these — but uses `scan` (accumulate) not `mapNotNull` (transition). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:163-180` | The `threadItems` `.scan(ThreadFold(...))` fold precedent — how a `scan` accumulator + `stateIn` chain is built and why the accumulator type is explicit. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:142-161` | `activeRemoteRepo` mirror (:147) + the `liveSessionEvents` `flatMapLatest` passthrough (:158-160). Add `modalEvents` as the byte-for-byte mirror. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:215-238` | `modalEvents: SharedFlow<ModalEvent>` def (replay=0, DROP_OLDEST) — the source. Confirms the type and the no-second-subscription posture. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:111-117` | `ThreadViewModel` Koin wiring (:116, currently passes `…liveSessionEvents`). Append the `modalEvents` arg mirroring it. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:52-65, 119-241, 1443-1464` | Test header (`UnconfinedTestDispatcher` as Main, :59); the `isThinking` fold tests (:119-241) — the template for the modal fold tests; `makeVm` (:1443) — add a defaulted `modalEvents` param. |

## Design

Three pieces, all additive. No interface change, no facade change, no fake change.

### 1. New UI-state type — `ModalUiState` (new file)

New file `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ModalUiState.kt` (the ktlint
filename rule: a file's single public top-level **type** must match the filename; a co-located function
does not count — see [[ktlint-filename-rule-single-class]]).

Contract sketch (this is the type contract, not an implementation):

```kotlin
sealed interface ModalUiState {
    data object Hidden : ModalUiState

    data class Open(
        val modalId: String,
        val modalClass: String,
        val title: String,
        val prompt: String,
        val options: List<ModalOption>,   // data.model.ModalOption, wire array order preserved
        val defaultOptionId: String,
    ) : ModalUiState

    data class Dismissed(
        val modalId: String,
        val outcome: String,
        val source: String,               // verbatim "remote" | "local" | "timeout" | forward-compat
    ) : ModalUiState
}
```

- `Open` mirrors `ModalEvent.Shown` field-for-field (minus the sealed-interface `override`); `Dismissed`
  mirrors `ModalEvent.Dismissed`. All fields carried **verbatim** — no parsing, enum-coercion, trimming,
  or reordering (preserves #437's forward-compat posture; AC #1/#2).
- `Hidden` is the initial / "no modal outstanding" state.
- Reuse `data.model.ModalOption` (do **not** declare a parallel option type).

### 2. The fold — pure, co-located with the type

Co-locate the projection function in `ModalUiState.kt`:

```kotlin
internal fun ModalUiState.reduce(event: ModalEvent): ModalUiState
```

Behaviour (the developer writes the body; the unit test in AC #5 is the oracle):

- **`Shown`** → `Open(...)` carrying the event's fields verbatim. Always — a `Shown` supersedes any
  current state, including an already-`Open` modal (last-shown wins; AC #3).
- **`Dismissed`** where the receiver is `Open` **and** `Open.modalId == event.modalId` →
  `Dismissed(modalId, outcome, source)` carrying the verbatim reason (AC #2).
- **`Dismissed`** in any other case (receiver `Hidden`, receiver `Dismissed`, or receiver `Open` with a
  different `modalId`) → return the receiver unchanged (no-op; AC #2 "does not match the open modal").

The fold is **pure and side-effect-free** — **no logging** of any modal field. `title` / `prompt` /
`outcome` / option `label`s may name a sensitive command or path (see `ModalEvent` KDoc and
[[v2-app-payload-shapes-ssot]]); this slice carries them as inert data only. This mirrors the no-log
contract of `thinkingTransition` and `ThreadFold.reduce`.

### 3. Coordinator passthrough — mirror `liveSessionEvents`

In `RelayRepositoryCoordinator`, add a `modalEvents` property byte-for-byte mirroring the
`liveSessionEvents` seam (:158-160). The `activeRemoteRepo: MutableStateFlow<RemoteConversationRepository?>`
already exists — no new field:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val modalEvents: Flow<ModalEvent> =
    activeRemoteRepo.flatMapLatest { repo -> repo?.modalEvents ?: emptyFlow() }
```

Cold `Flow` (events, no current value → no `stateIn`); `flatMapLatest` switches to the fresh repo's
stream on each connection and cancels the prior, so the seam survives reconnection; empty between
connections. Add the `ModalEvent` import.

### 4. ViewModel — thread the param, expose `currentModal`

- New **defaulted** ctor param on `ThreadViewModel`, mirroring `liveSessionEvents` at :111:
  `modalEvents: Flow<ModalEvent> = emptyFlow()`. Defaulted ⇒ the fake-backed Koin graph and every
  existing test stay inert (state holds `Hidden`).
- New hoisted observable, a sibling to `isThinking` / `isStalled`:
  `val currentModal: StateFlow<ModalUiState>` = `modalEvents` `.scan(initial = Hidden) { s, e -> s.reduce(e) }`
  `.stateIn(viewModelScope, SharingStarted.Eagerly, ModalUiState.Hidden)`.

**Sharing policy — `Eagerly`, a deliberate deviation from the `WhileSubscribed(5_000)` siblings.** This
is the load-bearing design call of the slice:

- `isThinking` uses `mapNotNull{…}` + `WhileSubscribed`: `mapNotNull` never re-emits a stale value on
  resubscription, so `.value` is retained correctly across the stop window.
- `scan`, by contrast, **re-emits its initial accumulator** on every fresh upstream collection. Under
  `WhileSubscribed`, when the screen is gone past the stop timeout the upstream cancels; on return `scan`
  restarts and emits `Hidden`, **overwriting a retained `Open`**. Because the source `modalEvents` is
  `replay = 0`, the prior events do **not** replay to rebuild the accumulator — a still-open modal would
  silently clear. This is a deterministic consequence of `scan` + `replay = 0`, not a speculative guard.
- `Eagerly` collects for the VM lifetime, so the `scan` accumulator runs exactly once and is monotonic;
  `.value` is always the true current projection. This matches the coordinator's
  accumulate-a-`replay=0`-stream precedent (`currentRepository` / `connectionStatus` both use `Eagerly`).
  Cost is negligible: modals are one-at-a-time, user-driven, low-rate.

**Kotlin gotcha to flag for the developer:** `scan`'s accumulator type is inferred from the initial
value. `scan(ModalUiState.Hidden) { … }` infers `R = ModalUiState.Hidden` and rejects a lambda returning
`ModalUiState.Open`. Type the initial as `ModalUiState` — e.g. `scan<ModalEvent, ModalUiState>(Hidden)`
or a `val initial: ModalUiState = ModalUiState.Hidden`.

### 5. Koin wiring

`AppModule.kt:116` — append the real flow, mirroring the `liveSessionEvents` arg:

```kotlin
ThreadViewModel(get(), get(), get(), get(),
    get<RelayRepositoryCoordinator>().liveSessionEvents,
    get<RelayRepositoryCoordinator>().modalEvents)
```

## State + concurrency model

- **Single source of state** per the new signal: the `scan` accumulator inside `currentModal`. No parallel
  `MutableStateFlow` mirror — the projection is fully declarative (mirrors `threadItems`).
- `currentModal` is a separate VM property, **not** a `ThreadUiState` field — exactly like `isThinking`,
  `isStalled`, and `connectionState`. It is a transient, app-level cross-cutting signal the stateless
  screen (#446) takes as a separate `(state, currentModal, onEvent)`-style parameter.
- Hot via `stateIn(viewModelScope, Eagerly, Hidden)`; cancelled with `viewModelScope` on VM clear.
- Coordinator `modalEvents`: cold, `flatMapLatest` over `activeRemoteRepo`; reconnection-surviving;
  empty between connections. No `stateIn` (events, not state). Dispatcher inherited from the coordinator
  scope; no manual dispatcher switching.
- The fold routes on `modalId` only (there is no `conversationId` to route on) — this is why the state is
  app-level, and why no per-conversation filter exists (contrast `thinkingTransition`'s `conversationId`
  guard).

## Error handling

No new failure modes. The source `modalEvents` is `tryEmit`-infallible (DROP_OLDEST, #437) and already
decoded — malformed envelopes were dropped at the #437 decode boundary, so every event reaching the fold
is well-typed. The fold is total over the sealed `ModalEvent` and cannot throw. No network/IO/parse paths
here. There is no UI surfacing in this slice (that is #446); no banner/dialog/log.

Out of scope (no AC, do **not** add): clearing `currentModal` on connection drop / reconnect. The
coordinator passthrough emits `emptyFlow()` on a null repo but pushes no "clear" event, so a stale `Open`
could persist across a drop. The render slice #446 + connection signal own any disconnect affordance.
Flagged as an open question, not built here.

## Security posture (`security-sensitive`)

The trust boundary — untrusted wire bytes → typed events — lives **upstream** at #437's decode seam
(`data/network`). This slice operates on already-decoded, in-process `ModalEvent`s and adds **no new
parse of untrusted input**. Its security obligations are confidentiality and fail-safe behaviour, all met
by construction:

- **Inert, verbatim carry.** Every field (`title`, `prompt`, option `label`s, `outcome`, `modalClass`,
  `source`, `defaultOptionId`) is carried into `ModalUiState` without parsing, coercion, or interpretation.
  The injection sink — rendering these as active markup/HTML — is the **render** slice #446's
  responsibility (output-encoding at render time); this slice never renders or interpolates them.
- **No logging** of any modal field anywhere in the fold or the seam (the no-log contract of
  `thinkingTransition` / `ThreadFold.reduce` / the coordinator). Fields may name a sensitive command or
  path.
- **In-memory only — no persistence.** `currentModal` lives solely in the `StateFlow`. Do **not** write
  any modal field to `SavedStateHandle` or DataStore. (FLAG_SECURE / no-`rememberSaveable` screen-capture
  hardening is the render slice #446's concern, mirroring the #379/#381 literal-screen posture — not
  built here, but this slice must not create a persisted copy that would defeat it.)
- **Fail-safe-deny preserved.** The projection carries `defaultOptionId` verbatim and **never auto-answers
  or auto-resolves** a modal (answering is #444). No path bypasses the user's explicit choice.
- **Spoofed-dismiss safety.** A `Dismissed` whose `modalId` does not match the currently-`Open` modal is a
  no-op (AC #2) — a malformed or out-of-band dismiss cannot silently clear an unresolved modal. The
  exact-`modalId` match requirement is the safety property, not just a correctness one.

**Verdict: PASS.** No new trust boundary is crossed, no injection sink is introduced, confidentiality is
held (no-log, no-persist, in-memory), and the producer's fail-safe-deny design is preserved.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "…ThreadViewModelTest"`; bare `test` works too — see
[[gradle-single-test-class-task]]). No instrumented test (no UI). Extend `ThreadViewModelTest`:

- Add a defaulted `modalEvents: Flow<ModalEvent> = emptyFlow()` param to the `makeVm` helper (:1443),
  forwarding it to the new ctor arg. Existing call sites stay inert.
- Drive the fold from a `MutableSharedFlow<ModalEvent>()` source, asserting `vm.currentModal.value`.
  With `Eagerly` + `UnconfinedTestDispatcher` (Main, :59) the projection collects at VM construction, so
  no manual `launch { collect }` ceremony is needed (unlike the `WhileSubscribed` `isThinking` tests) —
  `emit` then assert `.value`. A small helper `modalShown(...)` / `modalDismissed(...)` mirrors `turnState(...)`.

Scenarios (AC #5 — bullet list, not pre-written bodies):

- **initial** — no event emitted ⇒ `currentModal.value == ModalUiState.Hidden`.
- **Shown → Open** — emit a `Shown`; assert `Open` carries `title` / `prompt` / `options` (in array order)
  / `defaultOptionId` **verbatim** (assert the option list equals the input list including order).
- **matching Dismissed → Dismissed, all three `source` values** — `Shown(id=m1)` then `Dismissed(id=m1)`
  for `source ∈ {"remote", "local", "timeout"}`; assert state is `Dismissed(m1, outcome, source)` with
  the verbatim `source`. (Optionally one forward-compat `source` value to lock the no-coercion contract.)
- **non-matching Dismissed → no-op** — `Shown(id=m1)` then `Dismissed(id=m2)`; assert state is still
  `Open(m1)`. Also: `Dismissed` from `Hidden` ⇒ stays `Hidden`.
- **Shown supersedes Shown** — `Shown(id=m1)` then `Shown(id=m2)`; assert `Open(m2)` (last-shown wins).
- **default-inert** — a VM built with the 5-arg `makeVm` (no modal source) holds `Hidden` (mirrors the
  `isThinking_initialValue_isFalseWithNoLiveSource` test).

## Open questions

- **Disconnect/reconnect clear** (above) — should a connection drop clear a stale `Open`? Deferred to
  #446 + the connection signal; no AC here.
- **Type placement** — `ModalUiState` lives in `ui/conversations/thread/` (it is a UI projection consumed
  by #446's thread overlay). If #446 finds it wants the type one package up (shared `ui/` modal host),
  that is a #446 move, not this slice's call.

## Acceptance criteria → design mapping

1. `Shown` → observable `Open` carrying title/prompt/options(array order)/`defaultOptionId` verbatim →
   `ModalUiState.Open` + `reduce(Shown)`.
2. matching `Dismissed` → cleared + verbatim `source`; non-matching → no-op → `reduce(Dismissed)`.
3. later `Shown` supersedes (last-shown wins) → `reduce(Shown)` unconditional `Open`; fold is this slice's
   job because source is `replay = 0`.
4. keyed on `modalId` only, app-level (no `conversation_id`), hoisted observable sibling to the transient
   thread signals → `currentModal: StateFlow<ModalUiState>` beside `isThinking`/`isStalled`.
5. VM unit test driving the fold → `ThreadViewModelTest` additions above.
