# Interrupt affordance — the busy-turn "Stop" control

The **visible half** of remote interrupt: while the open conversation's agent is running a turn, the
thread screen shows a "Stop" control; tapping it stops claude mid-response and the control disappears
when the turn ends. Landed in [#459](../codebase/459.md) (split from #430, `blockedBy` #458), Phase 3 of
epic pyrycode#597 (phone control), ADR 025.

Unlike the thinking indicator — whose data (`isThinking`, [#406](../codebase/406.md)) and UI
(`ThinkingIndicator`, [#407](../codebase/407.md)) were split across two tickets — #459 ships **both**
halves: a new `ThreadViewModel.isBusy` flow **and** the `InterruptAffordance` composable. This doc covers
both. The **send path** the tap invokes — `onInterrupt()` → the bare `interrupt` wire frame — is the
sibling slice [#458](../codebase/458.md); see [Interrupt send path](interrupt-send-path.md).

## The "a turn is running" signal — `ThreadViewModel.isBusy`

`isBusy` is the **broader sibling of [`isThinking`](turn-state-thinking-flag.md)**: `true` while the
latest `turn_state` for this `conversationId` is `thinking` **or** `responding`, `false` for `idle` /
`turn_end` / before any event. `isThinking` is `true` for `thinking` **only** — so it can't drive the
interrupt control, which must stay visible across the *whole* in-flight turn (including the
`responding`/assistant-text phase). Because claude serialises turns, at most one conversation is busy at
a time; the signal is routed by `conversationId` exactly like `isThinking`.

It is declared **identically** to `isThinking` — same source, operator, and lifecycle — over the same
[`liveSessionEvents`](live-session-events.md) coordinator seam ([#406](../codebase/406.md)):

```kotlin
val isBusy: StateFlow<Boolean> =
    liveSessionEvents
        .mapNotNull { event -> busyTransition(event) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)
```

`busyTransition` **mirrors `thinkingTransition` exactly**; the **only** difference is the phase
predicate. It returns the next flag value, or `null` to leave the flag unchanged:

| event for **this** `conversationId` | result | vs. `isThinking` |
|---|---|---|
| `TurnState(phase = Thinking)` | `true` | same |
| `TurnState(phase = Responding)` | **`true`** | **`false`** — the one broadening |
| `TurnState(phase = Idle)` | `false` | same |
| `TurnEnd` | `false` | same |
| `AssistantDelta` / `ToolUse` / `ToolResult` / `ReplayGap` | `null` (hold) | same |
| any event for a **different** `conversationId` | `null` | same (other conversations never move it) |

`mapNotNull` + `stateIn` gives "latest-wins with hold" for free — a non-transition event produces no
emission, so the `StateFlow` retains its prior value across deltas/tool events within a turn; no explicit
`scan`/`distinctUntilChanged`. The `false` initial value covers both "no event yet" and the inert
empty-flow default.

**Why a dedicated reducer, not `combine(isThinking, …)`.** The architect's deliberate call: a dedicated
`busyTransition` adds no operator, reuses the exact established sibling shape, and is simpler than deriving
`isBusy` by combining `isThinking` with a second "responding" flow. `isBusy` is a **hoisted sibling
`StateFlow`, not a `ThreadUiState` field** — the same posture as `connectionState` / `isThinking` /
`isStalled`, taken by the stateless screen as a separate parameter (per the ticket Technical Notes).

## The control — `InterruptAffordance`

A stateless composable at `ui/conversations/components/InterruptAffordance.kt`, a pure function of the
hoisted flag + the tap callback, copying [`ThinkingIndicator`](thinking-indicator.md)'s structure:

```kotlin
@Composable
fun InterruptAffordance(
    isBusy: Boolean,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`if (!isBusy) return`** — emits nothing when no turn is running (the `ThinkingIndicator` /
  `ConnectionBanner` early-return show/hide idiom). Holds **no local state**, no `rememberSaveable` — it
  appears and disappears purely as the hoisted flag flips.
- When busy, renders a centered `Row` (`fillMaxWidth`, two file-private padding `val`s) wrapping an M3
  **`FilledTonalButton`** (`onClick = onInterrupt`) with an `Icons.Filled.Stop` glyph +
  `thread_interrupt_label` ("Stop") text. M3 buttons satisfy the ≥48dp touch target by default
  (`minimumInteractiveComponentSize`). Fully themed — no hardcoded colour / shape / text style.
- **Accessibility / test handle** — the button carries
  `Modifier.semantics(mergeDescendants = true) { contentDescription = cd_thread_interrupt }` ("Stop the
  running turn"), so TalkBack announces it once and the screen test locates it by description (the
  `ThinkingIndicator` approach).
- Two light/dark `@Preview`s, both `isBusy = true` (the `false` case renders nothing).

The exact visual (button vs. chip, icon, colour slot, placement) is **design-owed** — see
[Edge cases](#edge-cases--limitations).

## Placement & wiring

Mounted in [`ThreadScreen`](thread-screen.md)'s foot-of-list `Column`, **immediately below**
[`ThinkingIndicator`](thinking-indicator.md) (`ThreadScreen.kt:279`):

```kotlin
Column {
    ConnectionBanner(...)
    StallPromotionBanner(...)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    QueuedBacklog(...)
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
    InterruptAffordance(isBusy = isBusy, onInterrupt = onInterrupt, modifier = Modifier.fillMaxWidth())
}
```

Foot-of-list mirrors the transient-affordance placement already used for the thinking spinner — the
control sits at the most-recent edge (`reverseLayout = true`), above the composer. It is a wrap-height
sibling row, **not** a `LazyColumn` item, so list keying / auto-scroll are untouched.

The flag + action are threaded as **defaulted** hoisted params, sibling to `isThinking`/`isStalled`:

- **`ThreadScreen`** gains `isBusy: Boolean = false` and `onInterrupt: () -> Unit = {}` (`:102`, after
  `modifier`). Defaults keep the in-file previews + androidTest + all ~20 other named-argument call sites
  inert; only the live caller sets them.
- **`MainActivity`** collects `val isBusy by vm.isBusy.collectAsStateWithLifecycle()` in the thread route
  (beside `isThinking`/`isStalled`) and passes `isBusy = isBusy` + `onInterrupt = vm::onInterrupt`. The
  `vm::onInterrupt` bound method-ref is recomposition-stable (the `onModalCancel = vm::onModalCancel`
  idiom).
- **No DI change** — `interrupt = coordinator::interrupt` was already wired into the VM factory in #458.

### Data flow

```
turn_state{thinking|responding} ─┐
turn_end / turn_state{idle} ─────┤  (liveSessionEvents, per conversation; #406 seam)
                                 ▼
        ThreadViewModel.busyTransition ──▶ isBusy: StateFlow<Boolean>
                                                 │ collectAsStateWithLifecycle (MainActivity)
                                                 ▼
                          ThreadScreen(isBusy, onInterrupt = vm::onInterrupt)
                                                 ▼
              InterruptAffordance(isBusy) ── tap ──▶ onInterrupt()
                                                 ▼  (already built, #458)
                  sendInterrupt() ──▶ coordinator::interrupt ──▶ bare `interrupt` frame
```

## Lifecycle, errors, edge cases

- **Lifecycle** — `isBusy` is `stateIn(viewModelScope, WhileSubscribed(5_000), false)`, identical to
  `isThinking`/`connectionState`. The reduction is pure (no dispatcher switch); on unsubscribe the
  upstream stops after 5 s and the `StateFlow` retains its last value.
- **No error handling in this slice.** `onInterrupt` is fire-and-forget; [#458](../codebase/458.md)
  already swallows the not-connected (`IllegalStateException`) and unreachable (`RelayErrorException`)
  paths inert, surfacing no error (the ticket explicitly defers any user-visible interrupt-failure
  surface). The affordance neither inspects a result nor shows a failure — its visibility is driven
  **solely** by `isBusy`; a failed send does not change it (the next real `turn_state`/`turn_end` does).
- **Stale-`true`-on-resume (known, accepted)** — inherited from `isThinking`: with a `replay = 0`
  upstream, if the turn ends while the screen is backgrounded > 5 s and re-foregrounds before a fresh
  event, `isBusy` can momentarily read a stale `true` until the next event. Same transient "right-now"
  posture accepted for `isThinking` / `isStalled` / `connectionState`; an `idle`/`turn_end`-on-resubscribe
  reset is a deferred follow-up.

### Edge cases / limitations

- **Visual is design-owed.** Figma [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)
  does **not** draw a dedicated interrupt control (confirmed against the locked layout — same status as
  `ThinkingIndicator` and the stall CTA). The interim treatment is the M3 idiom (`FilledTonalButton` +
  stop glyph + "Stop") at the foot of the list. When the frame draws the control, re-tune visual /
  placement here — no contract change.
- **Thinking-phase dual-affordance stacking (intentional, interim).** During `thinking`, both
  `isThinking` and `isBusy` are `true`, so the foot renders the thinking spinner **and** the "Stop" button
  stacked; only during `responding` does "Stop" appear alone. Correct per the AC (in-flight spans thinking
  + responding); the design-owed follow-up reconciles the stacking. Flagged by code review (non-blocking
  NIT) so the future design pass knows it is deliberate.
- **No animation.** Show/hide is an instant early-return swap, matching `ThinkingIndicator`. A fade-in is a
  design-owed nicety, deferred with the frame.

## Testing

Test-first, mirroring the `isThinking` coverage.

- **Unit (`ThreadViewModelTest`)** — seven tests over the `isBusy` reduction: initial `false`; `thinking`
  → `true`; **`responding` → `true` asserting `isBusy && !isThinking`** (the distinguishing case);
  `idle`/`turn_end` → `false`; other-conversation isolation; non-phase events hold the flag.
- **Instrumented (`ScriptedThreadRenderTest`, AC#4)** — `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd`
  rides the real-graph [`ScriptedThreadHarness`](../codebase/432.md): `pushTurnState("responding")` → wait
  until the affordance (by `cd_thread_interrupt`) shows → `performClick()` → assert
  `interruptInvocations() == 1` → `pushTurnEnd("t1")` → wait until gone. The harness injects a **recording**
  interrupt lambda (`interrupt = { interruptCount++ }`) into the VM and subscribes `isBusy` in `start()`'s
  composition pass so the `replay = 0` upstream is live before any `push*`. Opens with **`responding`**
  specifically to prove the control shows when the thinking spinner is hidden. `androidTest` is **not**
  compiled by the mandatory gates — run `./gradlew compileDebugAndroidTestKotlin`
  ([[androidtest-not-compiled-by-mandatory-gates]]).

## Related

- Ticket notes: [`../codebase/459.md`](../codebase/459.md). Spec:
  `docs/specs/architecture/459-interrupt-affordance.md`.
- Send path (the tap target, sibling slice): [Interrupt send path](interrupt-send-path.md)
  ([#458](../codebase/458.md)) — `onInterrupt()` → `coordinator::interrupt` → bare `interrupt` frame.
- The signal it broadens: [Turn-state thinking flag](turn-state-thinking-flag.md)
  ([#406](../codebase/406.md)) — `isThinking`, the `thinking`-only flag `isBusy` mirrors and broadens.
- Component template + foot-of-list sibling: [Thinking indicator](thinking-indicator.md)
  ([#407](../codebase/407.md)). Other foot/transient affordances:
  [Stall promotion banner](stall-promotion-banner.md) ([#396](../codebase/396.md)),
  [Queued backlog section](queued-backlog-section.md) ([#461](../codebase/461.md)/[#467](../codebase/467.md)).
- Upstream seam: [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) →
  [Relay repository coordinator](relay-repository-coordinator.md) `liveSessionEvents`.
- Host: [Thread screen](thread-screen.md) — `isBusy` is the fifth hoisted sibling `StateFlow`; the
  affordance is the foot-of-list `Column`'s newest member.
- Parent / epic: split from [#430](https://github.com/pyrycode/pyrycode-mobile/issues/430); Phase 3 epic
  pyrycode#597, ADR 025; server SSOT pyrycode#707 (`interrupt` wire).
</content>
