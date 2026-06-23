# Spec #459 — interrupt affordance on the busy turn

**Ticket:** [pyrycode-mobile#459](https://github.com/pyrycode/pyrycode-mobile/issues/459)
**Size:** S · **Security-sensitive:** no · **Split from:** #430 · **Blocked by:** #458 (merged, PR #469)

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Thread screen. The interrupt affordance is **design-owed** within this frame — same status as the `ThinkingIndicator` and the stall CTA already added to 16-8: the locked layout does not yet draw a dedicated interrupt control, so its exact placement is deferred to a future design pass. Until then, follow the app's existing Material 3 idiom (mirror `ThinkingIndicator`'s foot-of-list treatment) — a small, tappable M3 control with a stop glyph + label. Do **not** invent pixel measurements; this is a transient signal-driven affordance like the thinking spinner, not a redesign of the frame.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:264-281` — `isThinking: StateFlow<Boolean>` declaration; the **exact shape to mirror** for `isBusy` (`liveSessionEvents.mapNotNull { … }.stateIn(WhileSubscribed(5_000), false)`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:360-377` — `thinkingTransition(event)`: the conversation-id-routed phase reducer to mirror for `busyTransition` (the one-line difference: `Thinking` **or** `Responding` → true).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:473-505` — `onInterrupt()` / `sendInterrupt()` (the blocker's send path, **already built**); confirm the affordance only needs to call `onInterrupt()`. No new send/transport code.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` (full, ~94 lines) — the **template** for the new `InterruptAffordance` composable: stateless, early-return when off, `semantics { contentDescription = … }`, two `@Preview`s. Copy this structure.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:90-116` — `ThreadScreen` parameter list (where `isThinking`/`isStalled` are declared); `:263-273` — the foot-of-list Column where `QueuedBacklog` + `ThinkingIndicator` are placed (where the affordance goes).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:346-382` — the thread route: how `isThinking`/`isStalled` are collected via `collectAsStateWithLifecycle` and passed; `onModalCancel = vm::onModalCancel` is the method-ref idiom for wiring `onInterrupt`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:117-129` — confirms `interrupt = coordinator::interrupt` is **already wired** into the VM (#458). **No DI change in this slice.**
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` — `TurnState.Phase` enum is exactly `{ Thinking, Responding, Idle }`; `TurnEnd`, `AssistantDelta`, `ToolUse`, `ToolResult`, `ReplayGap` are the other event arms `busyTransition` must handle (`Idle`/`TurnEnd` → false; non-phase → null).
- `app/src/main/res/values/strings.xml:53-60` — the `cd_thread_thinking` / `thread_thinking_label` / `cd_thread_stall_promotion` neighbours; add the two new strings here, same naming convention.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` (full) — the real-graph harness driving `RemoteConversationRepository → ThreadViewModel → ThreadScreen`; `start()` (`:81-98`) is where the new `isBusy`/`onInterrupt` args attach, and where the recording interrupt lambda is injected via the VM constructor (`:68-75`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt:74-93` — `spinner_shownWhileThinking_goneAfterTurnEnd`: the **template** for the AC#4 instrumented test.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:126-248` — the full `isThinking` reduction unit-test suite; mirror it for `isBusy`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt` — optional component-test template if you add an `InterruptAffordanceTest`.

## Context

Phase 3 (epic pyrycode#597) gives the phone the ability to interrupt a running turn. The wire send path landed in **#458** (merged): `ThreadViewModel.onInterrupt()` emits the bare `interrupt` control frame through `coordinator::interrupt`, already injected via Koin. What is missing is the **visible affordance**: while a turn is in flight on the open conversation, the thread screen must show a control that calls `onInterrupt()`, and the control must disappear when the turn ends.

The thread VM today exposes `isThinking` — `true` only during the `thinking` phase. This slice needs a broader **"a turn is running"** signal: `true` across `thinking` **and** `responding`, `false` on `idle` / `turn_end` / before any event. Because claude serialises turns, at most one conversation is busy at a time; the signal is routed by `conversationId` exactly like `isThinking`.

## Design

Four production files. The whole slice is "add a sibling `StateFlow`, render a stateless affordance off it, wire the existing send action."

### 1. `ThreadViewModel.kt` — new `isBusy` flow (mirror `isThinking`)

Add a public `val isBusy: StateFlow<Boolean>` declared identically to `isThinking` (same `liveSessionEvents.mapNotNull { … }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)`), backed by a new private reducer:

```kotlin
val isBusy: StateFlow<Boolean>          // sibling to isThinking; true across thinking AND responding
private fun busyTransition(event: LiveSessionEvent): Boolean?   // null = leave flag unchanged
```

`busyTransition` contract (mirror `thinkingTransition` exactly; the **only** difference is the phase predicate):
- `event.conversationId != conversationId` → `null` (AC: other conversations never move the flag — same routing as `thinkingTransition`).
- `TurnState` → `phase == Phase.Thinking || phase == Phase.Responding` (the broadening vs. `isThinking`).
- `TurnEnd` → `false`.
- `AssistantDelta` / `ToolUse` / `ToolResult` / `ReplayGap` → `null` (not phase transitions).

Keep the KDoc parallel to `isThinking`'s, noting the `responding`-inclusive predicate and that `false` covers both "no event yet" and the inert empty-flow default. **Do not** derive `isBusy` by combining `isThinking` with a second flow — a dedicated reducer is simpler, adds no operator, and matches the established sibling pattern. `isBusy` is a hoisted sibling `StateFlow`, **not** a `ThreadUiState` field (per ticket Technical Notes and the `isThinking`/`isStalled` precedent).

### 2. `InterruptAffordance.kt` — new stateless composable (`ui/conversations/components/`)

A pure function of the hoisted flag plus the action callback, copying `ThinkingIndicator`'s structure (early-return idiom, `semantics`, previews):

```kotlin
@Composable
fun InterruptAffordance(
    isBusy: Boolean,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Contract:
- `if (!isBusy) return` — emits nothing when no turn is running (mirrors `ThinkingIndicator`/`ConnectionBanner`).
- When busy, render a **tappable** M3 control (e.g. `FilledTonalButton` or `AssistChip`) whose `onClick = onInterrupt`, carrying a stop glyph + the `thread_interrupt_label` text and a merged-descendants `contentDescription = stringResource(R.string.cd_thread_interrupt)` so the instrumented test can locate it by description (same locate-by-`contentDescription` approach as `ThinkingIndicator`). Ensure a ≥48dp touch target (M3 buttons satisfy this by default).
- Holds **no local state** — `isBusy` and the tap callback are both hoisted. No `rememberSaveable`.
- Two `@Preview`s (light/dark) mirroring `ThinkingIndicator`'s, rendering with `isBusy = true`.
- File holds one composable + previews; functions don't trip the ktlint single-class-per-file rule, so `InterruptAffordance.kt` is correct.

Exact visual (icon choice, button vs. chip, color slot) is design-owed — pick the closest existing M3 idiom; a follow-up design pass tunes it when the Figma frame draws the control.

### 3. `ThreadScreen.kt` — declare params, place the affordance

- Add two defaulted parameters next to `isThinking`/`isStalled`: `isBusy: Boolean = false` and `onInterrupt: () -> Unit = {}`. Defaults keep the previews and any other caller inert.
- Place `InterruptAffordance(isBusy = isBusy, onInterrupt = onInterrupt, modifier = Modifier.fillMaxWidth())` in the foot-of-list `Column`, adjacent to the existing `ThinkingIndicator(...)` call (`:272`). Foot-of-list mirrors the transient-affordance placement already used for thinking; the developer adjusts to the locked layout when design lands.

### 4. `MainActivity.kt` — collect + wire

In the thread route (`:346-382`), add `val isBusy by vm.isBusy.collectAsStateWithLifecycle()` beside the `isThinking`/`isStalled` collectors, then pass `isBusy = isBusy` and `onInterrupt = vm::onInterrupt` into `ThreadScreen(...)`. Method-ref idiom matches `onModalCancel = vm::onModalCancel`.

### 5. `strings.xml` — two new strings (resource XML, not a `.kt` file)

Add near the `cd_thread_*` neighbours (`:53-60`):
- `thread_interrupt_label` — the visible label (e.g. `"Stop"`).
- `cd_thread_interrupt` — the content description (e.g. `"Stop the running turn"`).

### Data flow

```
turn_state{thinking|responding} ─┐
turn_end / turn_state{idle} ─────┤
   (liveSessionEvents, per conv) │
                                 ▼
        ThreadViewModel.busyTransition ──> isBusy: StateFlow<Boolean>
                                                 │ collectAsStateWithLifecycle (MainActivity)
                                                 ▼
                          ThreadScreen(isBusy, onInterrupt = vm::onInterrupt)
                                                 │
                                                 ▼
              InterruptAffordance(isBusy) ── tap ──> onInterrupt()
                                                 │ (already built, #458)
                                                 ▼
                  sendInterrupt() ──> coordinator::interrupt ──> bare `interrupt` frame
```

## State + concurrency model

- `isBusy` is a cold→hot `StateFlow` on `viewModelScope`, `SharingStarted.WhileSubscribed(5_000)`, `initialValue = false` — identical lifecycle to `isThinking`. `mapNotNull` retains the last emitted value for events that don't transition (the `null` arm), so the flag holds steady across deltas/tool events within a turn.
- The reducer emits only on a genuine busy/not-busy transition (`distinctUntilChanged` is implicit via `StateFlow` value-equality on `Boolean`).
- No new `viewModelScope.launch`, no new dispatcher, no new channel. The send path's concurrency was settled in #458 (fire-and-forget on `viewModelScope`).
- Subscription liveness in the harness: `isBusy` (a `replay = 0` upstream like `isThinking`) only collects once `start()`'s `collectAsState` composes. Add `isBusy = vm.isBusy.collectAsState().value` to the harness `start()` so its subscription is established in the same composition pass as `state`/`isThinking` — `awaitReady()`'s top-bar proof then still guarantees the live collector is subscribed before any `push*`.

## Error handling

None in this slice. `onInterrupt` is fire-and-forget; #458 already swallows the not-connected (`IllegalStateException`) and (unreachable) `RelayErrorException` paths with inert catches and surfaces no error (the ticket explicitly defers any user-visible interrupt-failure surface). The affordance neither inspects a result nor shows a failure. The control's visibility is driven solely by `isBusy`; a failed send does not change it (the next real `turn_state`/`turn_end` does).

## Testing strategy

Test-first, mirroring the `isThinking` coverage that already exists.

**Unit — `ThreadViewModelTest` (`./gradlew testDebugUnitTest`), mirror `isThinking` suite (`:126-248`):**
- `isBusy` initial value is `false` with no live source.
- `turn_state{thinking}` → `true`.
- `turn_state{responding}` → `true` — **the case that distinguishes `isBusy` from `isThinking`** (for which `responding` is `false`).
- `turn_state{idle}` → `false`.
- `turn_end` → `false`.
- a `turn_state` for a **different** `conversationId` does not move the flag.
- non-phase events (`assistant_delta` / `tool_use` / `tool_result`) leave the flag unchanged.

**Instrumented screen test — `ScriptedThreadRenderTest` (`./gradlew connectedAndroidTest`; AC#4 deliverable):**
Add one `@Test` riding `ScriptedThreadHarness`, modelled on `spinner_shownWhileThinking_goneAfterTurnEnd`:
- Harness change: construct the VM with a **recording** `interrupt` lambda (`interrupt = { interruptCount++ }`) and expose `fun interruptInvocations(): Int`; pass `isBusy = vm.isBusy.collectAsState().value` and `onInterrupt = vm::onInterrupt` into `ThreadScreen` in `start()`.
- Scenario: `pushTurnState("responding")` → wait until the affordance (by `cd_thread_interrupt`) is displayed → `performClick()` on it → assert `interruptInvocations() == 1` → `pushTurnEnd("t1")` → wait until the affordance no longer exists. Push `"responding"` specifically so the test proves the affordance shows when `isThinking` would be `false` (busy ⊋ thinking).
- Keep assertions **tolerant** (presence/absence via `onAllNodesWithContentDescription(...).fetchSemanticsNodes()` + generous `waitUntil`), per the ladder-doc rule the harness already follows. Never assert on event counts or timing.
- **`androidTest` is not compiled by the mandatory gates** — the developer must run `./gradlew compileDebugAndroidTestKotlin` to catch breaks with no device attached.

**Optional component test — `InterruptAffordanceTest`** (mirror `ThinkingIndicatorTest`): shows when `isBusy = true`, absent when `false`, tap invokes the callback. Largely subsumed by the screen test; add only if cheap. Not required by the ACs.

## Acceptance criteria mapping

- **AC#1** (affordance shown while in flight, hidden otherwise) → `isBusy` reducer + `InterruptAffordance` early-return; unit tests + screen test.
- **AC#2** (tap invokes interrupt-send exactly once) → `onInterrupt = vm::onInterrupt`; screen test asserts `interruptInvocations() == 1`.
- **AC#3** (turn end hides it, no user action) → `TurnEnd`/`Idle` → `false` in `busyTransition`; screen test's `pushTurnEnd` → affordance gone.
- **AC#4** (instrumented screen test drives the full path) → the `ScriptedThreadRenderTest` addition.

## Open questions

- **Exact placement / visual of the control** when the Figma frame draws it — deferred (design-owed, same status as `ThinkingIndicator`). Foot-of-list is the interim placement; a follow-up design pass relocates/restyles if needed. Flag in the PR so review knows the visual-fidelity check is intentionally interim.
- **Stop glyph source** — use a Material icon already available in the project's icon set (`Icons.*`); if none fits, a text-only label is acceptable for the interim visual.
