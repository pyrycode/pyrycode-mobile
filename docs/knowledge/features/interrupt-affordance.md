# Interrupt affordance — the busy-turn "Stop" control

The **visible half** of remote interrupt: while the open conversation's agent is running a turn, the
thread screen shows a "Stop" control; tapping it requests a stop for that conversation,
and the control disappears when its turn ends. Landed in [#459](../codebase/459.md) (split from #430, `blockedBy` #458), Phase 3 of
epic pyrycode#597 (phone control), ADR 025.

**As of [#643](../codebase/643.md), the control the user sees is the composer's message-input button
in its stop variant, not a standalone affordance** — see [Placement & wiring](#placement--wiring). The
`isBusy` signal, the `onInterrupt` send path and this composable's own file/tests are otherwise
unchanged; only the production mount point moved.

Unlike the thinking indicator — whose data (`isThinking`, [#406](../codebase/406.md)) and UI
(`ThinkingIndicator`, [#407](../codebase/407.md)) were split across two tickets — #459 ships **both**
halves: a new `ThreadViewModel.isBusy` flow **and** the `InterruptAffordance` composable. This doc covers
both. The **send path** the tap invokes — `onInterrupt()` → `interrupt` with the
open conversation's id — preserves the callback introduced in [#458](../codebase/458.md).
See [Interrupt send path](interrupt-send-path.md) for explicit targeting (#626).

## The "a turn is running" signal — `ThreadViewModel.isBusy`

`isBusy` is the **broader sibling of [`isThinking`](turn-state-thinking-flag.md)**: `true` while the
latest `turn_state` for this `conversationId` is `thinking` **or** `responding`, `false` for `idle` /
`turn_end` / before any event. `isThinking` is `true` for `thinking` **only** — so it can't drive the
interrupt control, which must stay visible across the *whole* in-flight turn (including the
`responding`/assistant-text phase). The signal is routed by `conversationId`
exactly like `isThinking`; another conversation's events do not change this flag.

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

**Retired from the screen in [#643](../codebase/643.md).** Through #459–#642, `InterruptAffordance` mounted
in `ThreadScreen`'s foot-of-list `Column`, immediately below `ThinkingIndicator` — the two visibly
**stacked** whenever `isThinking && isBusy` were both true (during the `thinking` phase specifically),
a code-review NIT flagged at the time as interim and owed to the Figma `16:8` pass.

**#643 applied that pass and removed the call site instead of reconciling the stack**, following
desktop's #678 precedent: the thread screen's message-input button (in [`ThreadInputBar`](thread-input-bar.md#the-message-input-button--one-control-two-actions))
now carries the stop action as one of two states, replacing the standalone control rather than
repositioning it. `isBusy` and `onInterrupt` still reach the screen exactly as before — as defaulted
hoisted params on `ThreadScreen` (see below) — they are just threaded one slot further, into the
composer's `ThreadInputBar` call, instead of into a standalone `InterruptAffordance` call:

```kotlin
// ThreadScreen's bottomBar, post-#643 — the composer column, not the foot of the content Column
ThreadStatusArea(apiRetry, isCompacting, isThinking)   // the old ThinkingIndicator slot, moved here too
ThreadInputBar(onSend = onSendMessage, isBusy = isBusy, onInterrupt = onInterrupt, …)
ThreadStatusRow(model = …, effort = …, …)   // retired by #808 — see Thread composer footer
```

The send/stop precedence in `ThreadInputBar` is explicit and text-first:

| `text` | `isBusy` | description | action |
|---|---|---|---|
| non-blank | either | `cd_send_message` | `onSend` |
| blank | `true` | `cd_thread_interrupt` | `onInterrupt` |
| blank | `false` | `cd_send_message` (disabled) | — |

Text present wins over an in-flight turn deliberately: sending while the agent is busy is a shipped
path (the daemon queues it, [`QueuedBacklog`](queued-backlog-section.md) renders it, #461/#467), and a
stop variant that pre-empted a typed message would remove the only tap that reaches that path. Stop
therefore owns the button exactly when the composer is empty — the state anyone actually reaching for
stop is in. The flagged-at-review consequence: **stop is unreachable while a draft sits in the
composer**, so a user who has started typing must clear the field to interrupt. Deliberate (one design
button slot, queue-while-busy is real) but worth watching under a real in-flight turn — [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679) carries the live proof of the reframed screen.

**The composable and its file remain, without a production call site.** `InterruptAffordance.kt` was not
touched by #643 and keeps its own tests (below); `codegraph_callers` finds none outside its own file.
Deliberate and in-scope per the ticket ("the composable and its file remain"), but it is dead production
code until something claims it — flagged in review as worth a follow-up to either retire the file or
record what still keeps it alive. If you're looking for the live control, it's the button in
[`ThreadInputBar`](thread-input-bar.md#the-message-input-button--one-control-two-actions), not this file.

The flag + action reach `ThreadScreen` as **defaulted** hoisted params, sibling to `isThinking` (and, until [#883](../../specs/architecture/883-retire-literal-screen.md) retired it, `isStalled`) — this part is unchanged by #643:

- **`ThreadScreen`** has `isBusy: Boolean = false` and `onInterrupt: () -> Unit = {}` as defaulted
  parameters (`ThreadScreen.kt:123-124`, unchanged in position since #459). Defaults keep the in-file
  previews + androidTest + all other named-argument call sites inert; only the live caller sets them.
  #643 changed which composable inside `ThreadScreen` receives them (`ThreadInputBar` instead of
  `InterruptAffordance`), not the parameters themselves.
- **`MainActivity`** collects `val isBusy by vm.isBusy.collectAsStateWithLifecycle()` in the thread route
  (beside `isThinking`) and passes `isBusy = isBusy` + `onInterrupt = vm::onInterrupt`. The
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
     ThreadInputBar's message-input button (stop variant, isBusy && text.isBlank()) ── tap ──▶ onInterrupt()
                                                 ▼
                  sendInterrupt() ──▶ interrupt(conversationId) ──▶ targeted `interrupt` frame
```

Pre-#643 this last hop was `InterruptAffordance(isBusy) ── tap ──▶ onInterrupt()`; the signal and
send path on either side of that hop are untouched, only the control that turns the tap into the call.

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

**Resolved by [#643](../codebase/643.md) for production** — both edge cases below described this
composable's own foot-of-list mount and no longer apply to the live screen, since `InterruptAffordance`
has no production call site any more (see [Placement & wiring](#placement--wiring)). They still describe
`InterruptAffordance.kt` itself, which is unchanged and could resurface elsewhere:

- **Visual was design-owed; now resolved for the live control.** Figma `16:8` never drew this standalone
  composable — it draws only the composer's single message-input button, which is what the live screen
  shows now. `InterruptAffordance` itself keeps its interim `FilledTonalButton` + stop glyph + "Stop"
  treatment (M3 idiom, no Figma counterpart of its own).
- **Thinking-phase dual-affordance stacking — was intentional/interim, gone from production.** Through
  #459–#642, `isThinking && isBusy` both `true` during the `thinking` phase meant the foot rendered the
  spinner and the standalone "Stop" button stacked. #643 removed the standalone call site, so the live
  screen never stacks them — the status area (thinking/retry/compacting) and the composer's send/stop
  button are two different rows by construction, not by suppressing either signal.
- **No animation.** Show/hide is an instant early-return swap inside `InterruptAffordance` itself, matching
  `ThinkingIndicator`. Not relevant to the live control, which swaps icon/description on the message-input
  button instead.

## Testing

Test-first, mirroring the `isThinking` coverage.

- **Unit (`ThreadViewModelTest`)** — seven tests over the `isBusy` reduction: initial `false`; `thinking`
  → `true`; **`responding` → `true` asserting `isBusy && !isThinking`** (the distinguishing case);
  `idle`/`turn_end` → `false`; other-conversation isolation; non-phase events hold the flag.
- **Instrumented (`ScriptedThreadRenderTest`, AC#4)** — `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd`
  rides the real-graph [`ScriptedThreadHarness`](../codebase/432.md): assert initial
  absence → `thinking` shows Stop → `responding` hides the thinking spinner while
  Stop stays visible → tap once → assert one callback targeting `c1` and Stop still
  visible → `turn_end` hides Stop. The harness records callback ids with
  `interrupt = { interruptTargets += it }` and subscribes `isBusy` in `start()`'s
  composition pass before any `push*`. Recording only a count would miss a wrong
  target. These fixtures prove callback routing and visibility, not a real daemon
  stop; [#679's live scenario](interrupt-send-path.md#testing) remains separate.
  **Since [#643](../codebase/643.md), "Stop" here means the message-input button's stop
  variant** (`cd_thread_interrupt` on `ThreadInputBar`'s button, empty field + `isBusy`), not the
  retired standalone control — the test kept passing unchanged because it locates the control by
  content description, not by composable identity or screen position.
  Instrumented-source changes require `./gradlew compileDebugAndroidTestKotlin`;
  aggregate JVM tests, lint and assemble do not compile them. See
  [development verification](development-verification.md#gradle-and-source-checks).
- **Instrumented, added in [#643](../codebase/643.md) (`ThreadFrameTest.kt`)** — `inputButton_stopsWhileBusyWithEmptyField`
  and `inputButton_sendsWhenTextPresent` pin the send/stop precedence table directly on the stateless
  `ThreadInputBar`; `busyThread_hasExactlyOneStopControl` mounts the full `ThreadScreen` with
  `isBusy`/`isThinking` both set and asserts exactly one `cd_thread_interrupt` node exists — the
  regression guard against the two affordances ever stacking again.

## Related

- Ticket notes: [`../codebase/459.md`](../codebase/459.md) (original implementation), [`../codebase/643.md`](../codebase/643.md)
  (retired the standalone call site onto `ThreadInputBar`'s button). Specs:
  `docs/specs/architecture/459-interrupt-affordance.md`, `docs/specs/architecture/643-thread-header-and-composer-layout.md`.
- Send path (the tap target, sibling slice): [Interrupt send path](interrupt-send-path.md)
  ([#458](../codebase/458.md), targeting updated in #626) — `onInterrupt()` supplies
  the open conversation's id through `coordinator::interrupt` into the wire payload.
- The signal it broadens: [Turn-state thinking flag](turn-state-thinking-flag.md)
  ([#406](../codebase/406.md)) — `isThinking`, the `thinking`-only flag `isBusy` mirrors and broadens.
- Component template + foot-of-list sibling: [Thinking indicator](thinking-indicator.md)
  ([#407](../codebase/407.md)). Other foot/transient affordances:
  [Queued backlog section](queued-backlog-section.md) ([#461](../codebase/461.md)/[#467](../codebase/467.md)).
  The stall promotion banner ([#396](../codebase/396.md)) was another until [#883](../../specs/architecture/883-retire-literal-screen.md) retired it.
- Upstream seam: [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) →
  [Relay repository coordinator](relay-repository-coordinator.md) `liveSessionEvents`.
- Host: [Thread screen](thread-screen.md) — `isBusy` is the fifth hoisted sibling `StateFlow`. Until
  [#643](../codebase/643.md) the affordance was the foot-of-list `Column`'s newest member; since #643
  its action lives on [`ThreadInputBar`](thread-input-bar.md#the-message-input-button--one-control-two-actions)'s
  message-input button instead — see [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#interrupt-affordance-placement-post-459-retired-from-the-screen-in-643)
  for the placement history.
- Parent / epic: split from [#430](https://github.com/pyrycode/pyrycode-mobile/issues/430); Phase 3 epic
  pyrycode#597, ADR 025; server SSOT pyrycode#707 (`interrupt` wire).
</content>
