# Spec: Hoist the current-modal projection to the process-scoped coordinator (#492)

**Status:** architecture · **Size:** S · **Labels:** `security-sensitive`

## Design source

N/A — no visual change. This relocates *where* the "which modal is open" projection is folded (thread
ViewModel → process-scoped coordinator); the render slice (#446, `ThreadScreen.modalOverlay`) and its
tokens/layout are untouched. The visual-fidelity check is intentionally not applicable — the modal draws
exactly as it does today, only sooner (it can no longer be dropped before a subscriber exists).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:332-357` — the
  `currentModal` `scan`/`stateIn(Eagerly)` fold to relocate, **and its `Eagerly`-vs-`WhileSubscribed`
  KDoc rationale (`:332-348`)** — that rationale moves verbatim to the coordinator.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:111-131` — constructor
  params; the `modalEvents: Flow<ModalEvent>` param (`:119-121`) is what changes shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:376-484` —
  `armedOptionId`, `onModalOption`, `onModalCancel`, `sendAnswer`, `sendCancel`: all read
  `currentModal` / `currentModal.value`. **This behaviour must be unchanged** (AC #3).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ModalUiState.kt` (whole file, ~89 lines) —
  the projection type + the pure `internal fun ModalUiState.reduce(ModalEvent)`. This file **moves**.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:159-179` — the
  `liveSessionEvents` / `modalEvents` cold-flow seams and the `connectionStatus` `stateIn(scope,
  Eagerly, …)` precedent the new fold mirrors exactly.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:135-141` —
  `currentRepository`: the canonical "accumulate a `replay=0`-derived stream `Eagerly` so `.value` is the
  true current projection" pattern to copy.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:293-308,297,306` —
  `answerModal` / `cancelModal` null-guard (`throw IllegalStateException` between connections). This is
  the **deterministic safety net** that makes the teardown decision safe (see Error handling).
- `app/src/main/java/de/pyryco/mobile/data/model/ModalEvent.kt:1-20` — confirms `ModalEvent` /
  `ModalOption` already live in `data/model`; the destination package for `ModalUiState`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:117-136` — the `ThreadViewModel { … }` factory
  threading `coordinator.modalEvents`; the one wiring line that flips to `coordinator.currentModal`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:387-413` — the thread nav destination collecting
  `vm.currentModal` (never names the `ModalUiState` type → **no import change**; forwards the value only).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:409-524` — the five
  `currentModal_*` fold tests (drive `modalEvents` via `MutableSharedFlow<ModalEvent>`). Fold moves out of
  the VM → these move (see Testing strategy).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:526-723,2298-2340` —
  the arm/answer/cancel tests + the `makeVm` / `vmWithModalEvents` helpers whose modal param changes shape.
- `app/src/test/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinatorTest.kt:546-599,769-921` —
  `liveSessionEvents_surfaceTurnStateFromLiveConnection`, `newEnv`/`Env`/`FakeManagedPump.push`,
  `turnStateEnvelope`: the exact fixture idiom the new regression test reuses. Note the cold-flow test
  needs a `backgroundScope.launch { … collect }`; the new `currentModal` (hot `stateIn`) does **not** —
  reading `.value` with no collector is the proof.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:4094` (+ usages
  `:3441-3586`) — `modalShownEnvelope(...)`: the `modal_shown` wire shape to mirror in the coordinator
  test.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt:1-25` —
  references `ModalUiState` with no import (same package today); needs an added import after the move.

## Context

"Which modal is open" (`ModalUiState`) is folded inside `ThreadViewModel`, which exists only while a
thread screen is on the back stack. The daemon can raise a permission/choice modal while the app shows
the channel list (or is closed). The coordinator's `modalEvents` seam is `replay = 0`; the fold only
subscribes when a `ThreadViewModel` is constructed (on navigating into a thread). A `modal_shown` that
fired **before any subscriber existed** is dropped, so the prompt stays outstanding daemon-side while the
phone renders nothing.

The fix folds the projection **once, at the process-scoped coordinator**, which already owns the
reconnection-surviving `modalEvents` seam and outlives any screen. Started `Eagerly` for the coordinator's
lifetime, the fold accumulates whether or not a thread screen is subscribed; the ViewModel then re-exposes
the coordinator's `StateFlow<ModalUiState>` instead of folding the raw stream itself.

**HIGH — blocks flipping `USE_RELAY_REPOSITORY`.** Manifests only with the relay repository live.
Complements #445/#446 (projection + render) and #490 (guarded relay VM calls).

## Design

### 1. Relocate `ModalUiState` + `reduce` to `data/model` (the layering decision)

The coordinator lives in `data/` and cannot see `ui/conversations/thread/`. `reduce` is a **pure function
with no Android/UI dependency** — it imports only `ModalEvent` / `ModalOption` from `data/model`. Move the
whole `ModalUiState.kt` file:

- **From** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ModalUiState.kt`
- **To** `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt`
- Change the package declaration to `package de.pyryco.mobile.data.model`. **No other content changes** —
  the type, the `internal fun ModalUiState.reduce`, and all KDoc (verbatim-carry, no-log, exact-`modalId`
  match) are preserved byte-for-byte.

`data/model` is the correct home: it already holds the sibling projection type `ConnectionStatus` and the
source `ModalEvent`/`ModalOption`; the type stays portable (no Android import), honouring the
Compose-Multiplatform walk-back constraint. `reduce` stays `internal` — both `data/model` and
`data/repository` are in the `app` module, so the coordinator can call it. Keeping the filename
`ModalUiState.kt` satisfies the ktlint single-class-filename rule (the only top-level class-like type is
`ModalUiState`; `reduce` is a function and does not count). Do this as a `git mv` + one-line package edit
so git records a rename (importers must be updated in the same change or the build breaks — the move is
atomic).

**Rejected alternative (per the ticket's layering note):** exposing coordinator state as raw `ModalEvent`
and re-folding in the VM. That forks the fold, and re-folding a `replay = 0` stream in a screen-scoped VM
re-introduces the exact drop bug. Do not.

### 2. Coordinator owns the fold — new `currentModal: StateFlow<ModalUiState>`

In `RelayRepositoryCoordinator`, add a public hot projection mirroring `connectionStatus` /
`currentRepository`:

```kotlin
// contract — folds the replay=0 modalEvents seam once, process-scoped, Eagerly.
val currentModal: StateFlow<ModalUiState> =
    modalEvents
        .scan<ModalEvent, ModalUiState>(ModalUiState.Hidden) { state, event -> state.reduce(event) }
        .stateIn(scope, SharingStarted.Eagerly, ModalUiState.Hidden)
```

- Relocate the `Eagerly`-vs-`WhileSubscribed` KDoc rationale from `ThreadViewModel:332-348` onto this
  property (the reasoning is now the coordinator's): `scan` re-emits its seed on every fresh upstream
  collection, so a `WhileSubscribed` restart past the stop-window would overwrite a retained `Open` with
  `Hidden` and — because `modalEvents` is `replay = 0` — the prior events do not replay to rebuild it.
  `Eagerly` on the process-scoped `scope` runs the accumulator exactly once for the process lifetime, so
  `.value` is always the true current projection.
- **`modalEvents` becomes `private val`** (`:171`). After the hoist nothing outside the coordinator reads
  it (its only external consumer was `AppModule`, now flipped to `currentModal`); demoting it keeps the
  public surface minimal and makes it an implementation detail of `currentModal`. Update its KDoc
  (`:163-169`) to drop the stale "folding … is the #445 ViewModel projection's job" line.
- Imports to add: `de.pyryco.mobile.data.model.ModalUiState`, `de.pyryco.mobile.data.model.reduce`,
  `kotlinx.coroutines.flow.scan`. `SharingStarted` / `stateIn` are already imported.

The `.scan`/`.stateIn` chain sits downstream of the existing `modalEvents = activeRemoteRepo.flatMapLatest
{ … }`. Across a reconnect, `flatMapLatest` switches its inner source but the outer `scan` is **not**
restarted, so the accumulator survives connection churn (see Error handling for the teardown decision).

### 3. ViewModel consumes the hoisted projection

Replace the VM's fold with a re-exposed injected `StateFlow`:

- Constructor: replace `modalEvents: Flow<ModalEvent> = emptyFlow()` (`:119-121`) with
  `val currentModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden)`. As a `val`
  constructor property it *is* the exposed `currentModal` — no wrapping. The default (a fresh
  `MutableStateFlow(Hidden)`) keeps the fake-backed Koin graph and non-modal tests inert, exactly as the
  old `emptyFlow()` default did.
- **Delete** the `val currentModal` `scan`/`stateIn` block (`:350-357`) and its KDoc (`:332-349`).
- `armedOptionId` (`:376-379`), `onModalOption` (`:468-476`), `onModalCancel` (`:480-484`) are
  **unchanged** — they read `currentModal` / `currentModal.value`, which a `StateFlow` still satisfies.
  This is the mechanism by which AC #3 (arm/answer/cancel behaviour unchanged) holds structurally.
- Remove the now-unused `import de.pyryco.mobile.data.model.ModalEvent`; add
  `import de.pyryco.mobile.data.model.ModalUiState`. (`MutableStateFlow`, `StateFlow` already imported;
  `emptyFlow` / `scan` stay — still used by `liveSessionEvents` / `threadItems`.)

### 4. Wiring + collateral imports

- `AppModule.kt:129` — `coordinator.modalEvents` → `coordinator.currentModal` (positional arg; the param
  order is unchanged, only its value/type).
- `ThreadScreen.kt` — add `import de.pyryco.mobile.data.model.ModalUiState` (it names
  `ModalUiState.Open/Hidden/Dismissed`; previously same-package).
- `androidTest .../ThreadScreenModalTest.kt` — add the same import.
- `MainActivity.kt` — **no change** (forwards `vm.currentModal` without naming the type).

### Data flow (after)

```
RemoteConversationRepository.modalEvents (SharedFlow, replay=0, interactive-gated)
  └─ coordinator.modalEvents (private, flatMapLatest over activeRemoteRepo)
       └─ coordinator.currentModal: StateFlow<ModalUiState>   ← scan+reduce, stateIn(scope, Eagerly)
            └─ ThreadViewModel(currentModal = …)  → val currentModal (re-exposed)
                 ├─ armedOptionId = combine(currentModal, armedModalOption)
                 └─ MainActivity: vm.currentModal.collectAsStateWithLifecycle() → ThreadScreen
```

## State + concurrency model

- **Single source of state for the modal projection is now the coordinator**, not the VM. The VM holds a
  reference to that one `StateFlow`; it never re-derives it. No parallel mutable modal state exists.
- `currentModal` is hot and process-scoped: `stateIn(scope = coordinator scope, SharingStarted.Eagerly)`.
  The coordinator scope is `SupervisorJob() + dispatcher`, created at `createdAtStart` Koin init and
  cancelled only by `close()` (effectively process lifetime). Collection begins at coordinator
  construction — before any thread screen — which is precisely why a pre-subscriber `modal_shown` is no
  longer dropped.
- Dispatcher: inherits the coordinator's injected `dispatcher` (`Dispatchers.Default` in production,
  `StandardTestDispatcher` in tests). The fold is pure and cheap (modals are one-at-a-time, user-driven,
  low-rate), so no dispatcher switch is warranted.
- VM-side `armedOptionId` remains `stateIn(viewModelScope, Eagerly, null)` combining the injected
  `currentModal` — screen-scoped, correct: the arm is transient UI state, not the durable projection.

## Error handling — the connection-teardown decision (security-relevant)

A process-scoped fold outlives connection churn. **Decision: the projection retains its last value across
`teardownActive` (connection drop) — no reset to `Hidden`.** This is the natural behaviour of the
`scan` downstream of `flatMapLatest` (between connections `activeRemoteRepo` is `null` → `emptyFlow()`, so
no event flows and `scan` holds its last accumulator), and it is the correct choice:

- **Retaining is safe** because the answer path is guarded by *deterministic code*, not the projection:
  `coordinator.answerModal` / `cancelModal` throw `IllegalStateException` when no connection is active
  (`:297,:306`), which the VM catches and surfaces as a one-shot `modalSendErrors` snackbar — **no answer
  is ever sent on a dead connection**. `modalId`s are unique per modal instance, so even if a *new*
  connection is up, a stale answer cannot match a fresh modal (the daemon rejects the unknown `modalId` →
  `RelayErrorException` → same one-shot error). This is belt-and-suspenders with *different fabric*: the
  projection is UI state; the guard is deterministic.
- **Clearing would be worse.** Resetting to `Hidden` on a transient drop would blank an
  still-outstanding modal, and (absent a *confirmed* daemon replay-on-reconnect for already-observed
  modals — the replay cursor advances past seen events, so this is **not** guaranteed) it would not come
  back — re-introducing the exact "outstanding daemon-side, phone shows nothing" bug this ticket fixes.
- Net effect of a stale `Open` after a drop: at worst a cosmetic lingering prompt that a subsequent
  `modal_shown` (last-shown-wins) or a replayed `modal_dismissed` supersedes. Never a confidentiality or
  integrity defect. See Open questions for the follow-up that would let a future ticket clear-on-teardown.

Preserved failure-mode invariants (all inherited unchanged from the moved `reduce` and the untouched
coordinator body):
- **No logging** of any modal field anywhere on this path (`reduce` and the coordinator both emit no
  logs; the new `stateIn` adds none).
- **Verbatim inert carry** — `ModalUiState.Open` mirrors `ModalEvent.Shown` field-for-field; no parse,
  coerce, trim, or reorder. Output-encoding stays the render slice's (#446) job.
- **Exact-`modalId` dismiss match** — an out-of-band `modal_dismissed` cannot clear a non-matching open
  modal (spoofed-dismiss safety).

## Testing strategy — unit (`./gradlew testDebugUnitTest`)

Run a single class with `testDebugUnitTest --tests "<FQCN>"` (bare `test` is aggregate).

**A. Pure fold logic → new `app/src/test/.../data/model/ModalUiStateTest.kt`.** The fold left the VM, so
test `reduce` directly (no VM, no flow, no coroutines — simpler than today's `MutableSharedFlow`-driven
versions). Relocate the five behaviours from `ThreadViewModelTest:409-524` as direct `reduce` calls:
- `Hidden.reduce(Shown)` → `Open` carrying every field verbatim, `options` in wire order.
- `Open(m1).reduce(Dismissed(m1))` → `Dismissed` with verbatim `outcome`/`source`.
- `Open(m1).reduce(Dismissed(m2))` → receiver unchanged (`Open(m1)`).
- `Hidden.reduce(Dismissed(m1))` → `Hidden`; `Dismissed(x).reduce(Dismissed(y))` → unchanged.
- `Open(m1).reduce(Shown(m2))` → `Open(m2)` (last-shown-wins, unconditional supersede).

**B. Coordinator integration (the AC #4 regression) → add to `RelayRepositoryCoordinatorTest.kt`.** Reuse
`newEnv` / `FakeManagedPump.push`; add a `modalShownEnvelope` helper mirroring
`RemoteConversationRepositoryTest:4094` (type `modal_shown`, payload
`{"modal_id","class","title","prompt","options":[{"id","label"}],"default_option_id"}`).
- **Pre-subscriber accumulation (mandated):** bring up a connection, `pump.open(capabilities =
  setOf(CAPABILITY_INTERACTIVE))`, `pump.push(modalShownEnvelope("m1", …))`, `runCurrent()` — with **no**
  collector on `currentModal`. Assert `env.coordinator.currentModal.value` is `Open(modalId = "m1", …)`.
  Reading `.value` with no subscriber is the proof that the `Eagerly` process-scoped fold accumulated an
  event fired before any thread screen existed.
- **Retain across churn:** after the `Open` above, `env.connections.value = null; runCurrent()`; assert
  `currentModal.value` is still `Open("m1")` (documents the teardown decision).
- Close with `env.coordinator.close()` (matches the existing tests' teardown).

**C. VM consumption → rework in `ThreadViewModelTest.kt`.** Change the `makeVm`/`vmWithModalEvents` modal
param from `modalEvents: Flow<ModalEvent>` to a `StateFlow<ModalUiState>` (default
`MutableStateFlow(ModalUiState.Hidden)`); rename the helper accordingly (e.g. `vmWithModal`).
- Replace the deleted fold tests with one re-exposure sanity test: inject a `MutableStateFlow<ModalUiState>`,
  set `.value = Open(...)`, assert `vm.currentModal.value` reflects it.
- The arm/answer/cancel tests (`:526-723`) now establish an open modal by setting the injected
  `StateFlow`'s `.value = ModalUiState.Open(...)` directly (no `Shown` emit / `advanceUntilIdle`). Their
  assertions — single-tap-default answers, second-confirm arming, cancel clears arm, one-shot error on
  send failure without mutating `currentModal`, Cancellation-first swallow — are **unchanged** (AC #3).

**D. Compose (`./gradlew connectedAndroidTest`).** `ThreadScreenModalTest.kt` needs only the added
`ModalUiState` import; its render assertions are unchanged (#446 is untouched). Note: androidTest is not
compiled by the mandatory gates — verify with `compileDebugAndroidTestKotlin`.

## Scope note (why S, not split)

Six of the pipeline's quantitative red lines are clear: **0** new exported types (`ModalUiState` is moved,
not new; `currentModal` is a property, not a type); **~0** net new production LOC (the coordinator gains a
~6-line fold; the VM *deletes* a ~26-line fold — a net reduction); **3** consumer sites for the
`modalEvents`→`currentModal` change (AppModule, VM ctor, test helper); **4** ACs; **0** new
error/reject branches. The design touches 5 production files, but this is a **file-relocation artifact,
not hidden work**: `ModalUiState.kt` (atomic move, one package line), `RelayRepositoryCoordinator.kt` and
`ThreadViewModel.kt` (the two substantive edits — and the VM edit is net-negative), plus two one-line
touches (`ThreadScreen.kt` import, `AppModule.kt` wiring) forced *by the move itself*. A move cannot
relocate a symbol without atomically touching its definition **and** every importer in the same commit
(compilation), so no split produces a compiling, non-dead-code child under the 5-file line — the
"coordinator fold only" slice the ticket already rejected would strand dead code and leave the AC
untestable. Projected developer cost ~30–40 turns, well within budget.

## Open questions

- **Daemon modal replay-on-reconnect.** The teardown decision (retain) is conservative precisely because
  it is unconfirmed whether the daemon re-raises an already-observed-but-still-outstanding modal after a
  reconnect (the replay cursor advances past seen events). If a future wire signal *guarantees* re-raise
  or dismissal on reconnect, a follow-up could switch to clear-on-teardown for a cleaner UX. Out of scope
  here — do not build a defence for an unobserved failure.
- **Process-lifetime retention of a sensitive prompt.** Hoisting extends the in-memory lifetime of an
  unresolved `Open`'s `title`/`prompt` (possibly a sensitive command/path) from screen-scope to
  process-scope. This is inherent to the fix (the projection must outlive the screen), stays in the
  user's own app heap (never disked, logged, or transmitted), and is superseded on the next `Shown`/
  `Dismissed`. Considered and accepted — flagged here for the security pass, not a blocker.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the untrusted→trusted boundary (daemon `modal_shown` → typed
  `ModalEvent`) lives at the *unchanged* `data/network` decode point; `reduce` carries fields verbatim as
  inert data and its "never interpreted here / output-encoding is #446's job" KDoc moves byte-for-byte.
  Process-scoping crosses no tenant boundary (one fixed paired daemon).
- [Tokens/secrets] N/A — no token created/stored/compared/logged on the changed path; `answer_token`
  handling in `RemoteConversationRepository.answerModal` is untouched.
- [File/storage] N/A (positive) — projection is in-memory `StateFlow`, never persisted (no
  `rememberSaveable`/DataStore); the sensitive `prompt` never reaches disk. No path derived from any modal
  field.
- [Android IPC] N/A — no exported component, deep link, PendingIntent, ContentProvider, or WebView added;
  the change is Koin wiring + an in-process fold.
- [Crypto] N/A — no primitive introduced; the `modalId` `==` in `reduce` is an integrity match on a
  non-secret daemon-chosen correlation id (moved unchanged), not a secret compare.
- [Network & I/O] No findings — no new I/O; modal events flow through the existing interactive-gated seam.
  The `scan` holds a single `ModalUiState` (last-shown-wins), so a `modal_shown` flood is O(1) memory.
- [Logs/telemetry] No findings — the no-log contract is preserved (moved `reduce` + coordinator both
  no-log; the new `stateIn` adds none; the VM *deletes* the fold). No telemetry or crash reporter in the
  stack. Developer/code-review must keep the projection out of any `Log`/`Timber` call (documented in the
  moved `reduce`/coordinator KDoc and in Error handling).
- [Concurrency] No MUST FIX — the process-scoped `Eagerly` collection is the intended fix (owned by the
  coordinator's process-lived scope, not a leak). A hot app-level flow is correct: modals carry no
  `conversation_id` (existing semantics — no per-conversation confidentiality boundary). The one new path
  (a stale `Open` answered after reconnect on a fresh connection) is fail-closed: a unique `modalId` cannot
  match a fresh modal → daemon rejects (`modal.stale_id`) → `RelayErrorException` → one-shot error; the
  `answerModal`/`cancelModal` null-guard covers the no-connection case. The `Eagerly`-not-`WhileSubscribed`
  and retain-not-reset-on-teardown decisions are stated explicitly to pre-empt the confused-developer traps.
- [Threat model] No findings — the only mobile-specific angle is extended in-memory retention of a
  sensitive `prompt`; it stays heap-only, is never rendered while merely retained, and is superseded on the
  next `Shown`/`Dismissed` (accepted, Open questions). Screen-overlay / accessibility-eavesdropping threats
  target the render slice (#446), which is unchanged — out of scope for this relocation.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-04
