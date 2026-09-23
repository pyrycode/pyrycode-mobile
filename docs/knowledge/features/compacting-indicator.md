# Compacting indicator — `CompactingIndicator`

The **UI half of the compacting status** ([#597](../codebase/597.md), split from #583): a stateless
composable that, while the active conversation's remote claude is auto-compacting its context, replaces
the generic thinking spinner at the foot of the thread with a distinct "Compacting conversation" status,
so a tens-of-seconds silent compaction reads as busy rather than hung.

The signal it renders is the **data half** — [`ThreadViewModel.isCompacting`](compacting-state.md)
([#596](../codebase/596.md)). This component adds **no data access**: it receives the already-decoded
`Boolean` as a hoisted value, exactly as [`ThinkingIndicator`](thinking-indicator.md) receives
`isThinking`. It clones `ThinkingIndicator`'s M3 idiom and package placement; unlike
[`ApiRetryIndicator`](api-retry-indicator.md) it introduces no new decision, because there is no counter
to gate — the wire payload (`{conversation_id, active}`) carries nothing beyond the edge itself.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `CompactingIndicator.kt`.

## Shape

```kotlin
@Composable
fun CompactingIndicator(
    isCompacting: Boolean,
    modifier: Modifier = Modifier,
)
```

Pure function of `isCompacting`: no `ViewModel` reference, no flow collection, no `remember`, no
`LaunchedEffect`, no `ThreadUiState` field.

## What it does

- **`if (!isCompacting) return`** — emits nothing when not compacting, mirroring
  [`ThinkingIndicator`](thinking-indicator.md)'s early-return idiom. `ThreadScreen`'s three-way `when`
  (§ Placement) normally keeps it from being called at all in that state; the early return keeps the
  composable total anyway — defence in depth, the same posture both siblings ship.
- Exactly one rendered case — there is no counter-shown/counter-less split of the kind
  [`ApiRetryIndicator`](api-retry-indicator.md) needs, because the flag carries no payload.
- Renders a `Row` (`fillMaxWidth`, 16dp horizontal / 8dp vertical padding, `CenterVertically`,
  `Arrangement.spacedBy(8.dp)`) containing a small indeterminate `CircularProgressIndicator`
  (`size(16.dp)`, `strokeWidth = 2.dp`) and an adjacent `Text` (`bodySmall` / `onSurfaceVariant`) — the
  identical M3 shape as `ThinkingIndicator` and `ApiRetryIndicator`.
- **Accessibility** — `Modifier.semantics(mergeDescendants = true) { contentDescription = … }` on the
  `Row` so it reads as **one** merged TalkBack node (AC #1), sourced from `cd_thread_compacting`.
- **Indeterminate, deliberately.** No progress bar: the upstream detector streams no compaction
  progress (no counter, percent, or ETA on the wire), so an indeterminate spinner is the honest
  rendering and a progress bar would invent data.

## Placement in the thread

**Moved in [#643](../codebase/643.md).** [`ThreadScreen`](thread-screen.md) arbitrates this status
slot — a **three-way `when`** at the time of #597 (extended from #594's two-way `if`), a **four-way
`when`** since [#804](https://github.com/pyrycode/pyrycode-mobile/issues/804) added
[`UsageLimitIndicator`](usage-limit-indicator.md) between
api-retry and this arm, a **five-way `when`** since
[#805](https://github.com/pyrycode/pyrycode-mobile/issues/805) added
[`TurnOutcomeIndicator`](turn-outcome-indicator.md) directly below this arm, and a **six-way `when`**
since [#872](https://github.com/pyrycode/pyrycode-mobile/issues/872) inserted
[`ResettingIndicator`](resetting-indicator.md) directly above this arm — inside a private
`ThreadStatusArea` composable (`ThreadScreen.kt:471`), the
first child of the composer's `bottomBar` column — through \#642 the same `when` lived at the foot of the
content `Column`, above the composer rather than inside it. The arms, flags and precedence below api-retry
are unchanged by any of these moves; only the mount point, a 4dp-remainder horizontal inset (see [Thread
screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)),
and the inserted arm are new:

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying ->
        ApiRetryIndicator(status = apiRetry, modifier = Modifier.fillMaxWidth())
    usageLimit != null ->
        UsageLimitIndicator(reading = usageLimit, modifier = Modifier.fillMaxWidth())
    resetting != null ->
        ResettingIndicator(status = resetting, modifier = Modifier.fillMaxWidth())
    isCompacting ->
        CompactingIndicator(isCompacting = true, modifier = Modifier.fillMaxWidth())
    turnOutcome != null ->
        TurnOutcomeIndicator(report = turnOutcome, modifier = Modifier.fillMaxWidth())
    else ->
        ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

**Exactly one affordance renders; the arms never stack.** Two things about the ordering:

- **Precedence stays single-sourced in the screen, not the ViewModel** — #594's deliberate decision,
  extended rather than revisited. `isThinking` keeps meaning "the `turn_state` phase" (other tests
  assert it directly), so suppressing it at its source would make the `ThreadViewModel` contract lie.
- **api-retry keeps the top arm, ahead of the usage-limit report as well as compaction.** None of the
  three signals have been observed overlapping and no AC is spent on any combination, but the tie-break
  has a reason on record: api-retry is the "something is going wrong" signal, a usage-limit report is
  informational, and compaction is benign progress — the benign affordance must never mask a signal that
  something may be wrong. This is also the render-side answer to the ticket's bounded security question (§
  Security) — see [Compacting state § Security](compacting-state.md#security) for the data-layer half and
  [Usage-limit indicator § Placement](usage-limit-indicator.md#placement-in-the-thread) for why that arm
  sits immediately above this one.

`[isCompacting]`, `[apiRetry]`, and (since #804) the usage-limit reading are all **conversation-level, not
turn-scoped** — each must show
regardless of what `turn_state` says, including while `turn_state` is `idle`. `StallPromotionBanner`
(a **separate** affordance above the message list) and the interrupt control (the send button's stop
variant in `ThreadInputBar` since [#643](../codebase/643.md), below this slot in the same composer
column — see [Interrupt affordance](interrupt-affordance.md#placement--wiring)) are both untouched by
this arm — a compacting conversation stays interruptible and a stall can never be hidden by an endless
stream of `compacting` frames.

## Wiring

Threaded exactly like `isStalled` / `apiRetry` — a **defaulted hoisted value**, sibling to `isThinking`,
**not** a `ThreadUiState` field:

- **`ThreadViewModel`** exposes `isCompacting: StateFlow<Boolean>` beside `connectionState` /
  `isThinking` / `isStalled` / `apiRetry`, a verbatim clone of `isStalled`'s hoist — sourced from the
  **already-injected** repository, **no constructor / DI / interface change**:

  ```kotlin
  val isCompacting: StateFlow<Boolean> =
      repository
          .observeCompacting(conversationId)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = false,
          )
  ```

  No extra operator: `observeCompacting` already applies `distinctUntilChanged` in the remote impl and
  defaults to `false` on the interface and the facade. **Dedup is correct here**, deliberately unlike
  `apiRetry`'s "and none may be added" KDoc caveat — that warning exists only because a climbed counter
  must reach the screen as a fresh emission, and it inverts for a `Boolean`, which has no intermediate
  values to collapse. The KDoc says so explicitly, so a future reader does not import the inverted rule
  by pattern-matching the sibling above it.
- **`ThreadScreen`** gains `isCompacting: Boolean = false` in its trailing-defaults block, **after**
  `modifier` (Compose lint's `ComposeParameterOrder`, #508), beside `apiRetry`. Defaulting it keeps
  all ~27 existing `ThreadScreen(` call sites compiling untouched; only `MainActivity` and the scripted
  harness gain an argument.
- **`MainActivity`** collects it via `vm.isCompacting.collectAsStateWithLifecycle()` beside `isStalled`
  / `apiRetry`, and passes it through — two lines, mirroring the `apiRetry` wiring.

See [Compacting state](compacting-state.md) for the upstream data path (#596's `compacting` decode →
`observeCompacting` projection) that produces this value.

## Recomposition / stability

- `isCompacting: Boolean` is a stable type, so the new `ThreadScreen` parameter adds no recomposition
  instability.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of
  `isCompacting` to a rendered (or absent) row. The label is a constant `stringResource`, so there is
  nothing to freeze.
- `ThreadScreen` gains one param and a `when` arm at an existing single-child slot — no impact on the
  `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. Both show `isCompacting = true` (there is only one rendered
case).

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only. No `gradle/libs.versions.toml`
  edits.
- **Two new string resources** in `res/values/strings.xml`, appended beside the API-retry strings.
  Neither takes a format argument — nothing daemon-supplied reaches either string:

  | Name | Value |
  |---|---|
  | `thread_compacting_label` | `Compacting conversation` |
  | `cd_thread_compacting` | `Claude is compacting the conversation` |

## Edge cases / limitations

- **Visual is design-owed.** No compacting treatment is drawn in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — the same design-owed gap
  already recorded for [`ThinkingIndicator`](thinking-indicator.md),
  [`ApiRetryIndicator`](api-retry-indicator.md), and [`StallPromotionBanner`](stall-promotion-banner.md).
  Re-verified via `get_metadata` during #597's code review: the frame is Top App Bar → Message list →
  Status row → Composer, with no status-affordance row drawn. Until it lands the visual follows the
  app's existing M3 progress idiom; when the frame arrives, re-tune spinner/typography here — no
  contract change.
- **No `liveRegion`** on any of the four status affordances (this indicator, `ThinkingIndicator`,
  `ApiRetryIndicator`, `StallPromotionBanner`) — a pre-existing, out-of-scope gap across the whole
  family, folded into the design-owed a11y follow-up rather than fixed per-component (see
  [API-retry indicator § Edge cases](api-retry-indicator.md#edge-cases--limitations)).
- **No animation.** The three-way swap is an instant `when`-branch change, matching every sibling
  status affordance.
- **A stuck-active flag renders an indefinite status.** Deliberately undefended — see
  [Compacting state § Edge cases](compacting-state.md#edge-cases--limitations) for the data-layer
  framing (a hostile/crashed daemon could send a rising edge and never a falling one; #596 already
  declined a timeout at the layer that owns the state, and a reconnect clears it). The mitigation this
  slice owes is structural, not temporal: `StallPromotionBanner` and `InterruptAffordance` both stay
  visible regardless, so the operator is never trapped by the indicator.
- **Never observed in production today**, same as the data half: the daemon emits `compacting` only
  from the PTY-runner detector family; production runs the stream-json interactive runner, which has no
  emitter. Rung 3 (real claude) and rung 4 (`fakeclaude`) cannot exercise this component end-to-end for
  that reason — see `docs/e2e-interactive-stream.md`'s `Coverage:` entry. Coverage is rung 2 only:
  `ScriptedCompactingTest`, driving both edges (including "shows while `turn_state` is idle" and
  "clearing while idle leaves no status") through the real repository fold.

## Related

- Ticket notes: [`../codebase/597.md`](../codebase/597.md) (this component) ·
  [`../codebase/596.md`](../codebase/596.md) (the data/repository half it consumes).
- Spec: `docs/specs/architecture/597-compacting-status-render.md`.
- Upstream signal: [Compacting state](compacting-state.md) — `ThreadViewModel.isCompacting` /
  `observeCompacting`, the `compacting` decode this component renders.
- Host: [Thread screen](thread-screen.md) — threads `isCompacting` as another flat sibling parameter
  and arbitrates the status slot across five affordances (the composer's `ThreadStatusArea` since
  [#643](../codebase/643.md); the foot of the content `Column` before it).
- Idioms mirrored: [Thinking indicator](thinking-indicator.md) (the direct clone — early-return,
  sibling-`StateFlow`, defaulted-hoisted-parameter, merged-`semantics`, design-owed M3 default,
  light/dark previews), [API-retry indicator](api-retry-indicator.md) (the immediately-preceding render
  slice — same slot, same precedence discipline, but carries a display sanity gate this one correctly
  does not clone), [Usage-limit indicator](usage-limit-indicator.md)
  ([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804), above this arm in the
  ladder), [Resetting indicator](resetting-indicator.md)
  ([#872](https://github.com/pyrycode/pyrycode-mobile/issues/872), immediately above this arm in the
  ladder — the direct clone of this component's `Row` shape), [Turn-outcome indicator](turn-outcome-indicator.md)
  ([#805](https://github.com/pyrycode/pyrycode-mobile/issues/805), immediately below this arm in the
  ladder), [Stall promotion banner](stall-promotion-banner.md) (the different-slot,
  independently-co-rendering counterpoint, untouched by this ticket).
- Parent: split from [#583](https://github.com/pyrycode/pyrycode-mobile/issues/583); sibling data slice
  [#596](../codebase/596.md) (PR #600, `da4c3f9`), natively blocking this ticket.
- Server SSOT: pyrycode#1074 (design, merged PR pyrycode#1160, 2026-07-21),
  `internal/protocol/interactive.go`, `docs/protocol-mobile.md § compacting`.
