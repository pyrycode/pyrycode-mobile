# API-retry indicator — `ApiRetryIndicator`

The **UI half of the API-retry status** ([#594](../codebase/594.md), split from #582): a stateless
composable that, while the active conversation's remote claude is stuck retrying an API error, replaces
the generic thinking spinner at the foot of the thread with a distinct "Retrying — attempt N/M" status,
so a multi-minute retry storm reads differently from normal reasoning instead of being indistinguishable
from it.

The signal it renders is the **data half** — [`ThreadViewModel.apiRetry`](api-retry-status.md)
([#593](../codebase/593.md)). This component adds **no data access**: it receives the already-decoded
`ApiRetryStatus` as a hoisted value, exactly as [`ThinkingIndicator`](thinking-indicator.md) receives
`isThinking`. It clones `ThinkingIndicator`'s M3 idiom and package placement, and additionally owns the
one new decision this slice introduces: a display sanity gate on the counter.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ApiRetryIndicator.kt`.

## Shape

```kotlin
@Composable
fun ApiRetryIndicator(
    status: ApiRetryStatus,
    modifier: Modifier = Modifier,
)
```

`status` carries no default — the caller (`ThreadScreen`) always passes a real value. Pure function of
`status`: no `ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no
`ThreadUiState` field.

**Passing the repository-package `ApiRetryStatus` straight into a `components/` composable is
established**, the same pattern as `MessageBubble(message: Message)` / `QueuedBacklog(queued:
List<QueuedMessage>)` — no UI-layer mirror type was introduced.

## What it does

- **`if (status is ApiRetryStatus.NotRetrying) return`** — emits nothing when not retrying, mirroring
  [`ThinkingIndicator`](thinking-indicator.md)'s early-return idiom. `ThreadScreen`'s precedence rule (§
  Placement) normally keeps it from being called at all in that state; the early return keeps the
  composable total anyway — defence in depth, same posture as its sibling.
- Two rendered cases, chosen by whether the counter passes the display sanity gate:
  - **counter shown** (`Attempt` that passes the gate) — `thread_api_retry_label` /
    `cd_thread_api_retry`, both positionally formatted with `current` and `total`
    ("Retrying — attempt 3/10").
  - **counter-less** (`AttemptUnknown`, **or** an `Attempt` that fails the gate) —
    `thread_api_retry_label_unknown` / `cd_thread_api_retry_unknown` ("Retrying…").
- Renders a `Row` (`fillMaxWidth`, 16dp horizontal / 8dp vertical padding, `CenterVertically`,
  `Arrangement.spacedBy(8.dp)`) containing a small indeterminate `CircularProgressIndicator`
  (`size(16.dp)`, `strokeWidth = 2.dp`) and an adjacent `Text` (`bodySmall` /
  `onSurfaceVariant`) — the identical M3 shape as `ThinkingIndicator`.
- **Accessibility** — `Modifier.semantics(mergeDescendants = true) { contentDescription = … }` on the
  `Row` so it reads as **one** merged TalkBack node (AC #1), sourced from whichever `cd_*` resource
  matches the rendered case.

### The display sanity gate (AC #2)

A private predicate on `ApiRetryStatus.Attempt`, plus a private named bound, both defined and KDoc'd in
`ApiRetryIndicator.kt` — the AC's "documented where the fallback happens":

```kotlin
private const val MAX_PLAUSIBLE_ATTEMPTS = 99

private fun ApiRetryStatus.Attempt.isRenderableCounter(): Boolean =
    current in 1..total && total <= MAX_PLAUSIBLE_ATTEMPTS
```

One condition catches all three shapes the AC names:

| Rejected shape | Caught by |
|---|---|
| unparsed `0/0`, or any non-positive counter | `current in 1..total` (lower bound) |
| incoherent `9/3` | `current in 1..total` (upper bound) |
| absurd `1/2147483647` | `total <= 99` |

`current >= 1 && current <= total` already implies `total >= 1`, so `total` needs no separate lower
bound.

**This is a render-or-decline gate, never a clamp.** An unusable counter is not shown and no value is
rewritten — clamping here would re-import at the display layer exactly the server-data rewrite [#593's
mapper](api-retry-status.md) refused at the decode boundary, breaking the carry-verbatim posture every
sibling mapper shares.

**`0/0` and negatives cannot actually arrive as `Attempt`** — #593's `toStatus()` already folds them to
`AttemptUnknown`. The lower bound here is defence in depth against the *type* permitting what the
*mapper* forbids, not a second mapper or a re-derivation of #593's logic.

**Bound rationale (99):** claude's API-retry budget is single-digit in practice; 99 leaves an order of
magnitude of headroom while keeping each number to two digits, which is what keeps the label inside the
narrow foot-of-list row at the 412dp reference width. It is deliberately generous — a real retry that
somehow exceeds it degrades to the counter-less status rather than being hidden or truncated, and the
fix if that ever happens is a one-line const change, not a config knob.

## Placement in the thread

**Moved in [#643](../codebase/643.md).** [`ThreadScreen`](thread-screen.md) arbitrates this status slot
inside a private `ThreadStatusArea` composable (`ThreadScreen.kt:471`), the first child of the
composer's `bottomBar` column — through #642 it was the foot of the content `Column`, above the
composer rather than inside it. Originally an either/or replacing the bare `ThinkingIndicator(...)`
call, [#597](../codebase/597.md) extended it to a **three-way `when`** to admit
[`CompactingIndicator`](compacting-indicator.md); #643 relocated the whole `when` verbatim (arms, flags
and precedence untouched) and gave it its own 4dp-remainder horizontal inset so its content lands on
the composer's shared 20dp gutter (see [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the gutter arithmetic):

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying ->
        ApiRetryIndicator(status = apiRetry, modifier = Modifier.fillMaxWidth())
    usageLimit != null ->
        UsageLimitIndicator(reading = usageLimit, modifier = Modifier.fillMaxWidth())
    isCompacting ->
        CompactingIndicator(isCompacting = true, modifier = Modifier.fillMaxWidth())
    turnOutcome != null ->
        TurnOutcomeIndicator(report = turnOutcome, modifier = Modifier.fillMaxWidth())
    else ->
        ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

**One status slot; retry wins whenever active, then the usage-limit report, then compaction.** The
`api_retry` signal is conversation-level and outlives the `thinking` turn phase, so it must show
*regardless of what `turn_state` says* — including while `turn_state` is `idle` — and no two arms may ever
render stacked (AC #1). The precedence decision lives here, in the screen, deliberately **not** in the
ViewModel: `isThinking` stays defined purely as the `turn_state` phase (other tests assert it directly), so
suppressing it at its source would make the `ThreadViewModel` contract lie. api-retry keeps the top arm
over the usage-limit arm and compaction because it is the "something is going wrong" signal while the
other two are lower-urgency (a usage-limit report is informational, compaction is benign progress) — the
benign affordance must never mask the alarming one (the arms have never been observed overlapping, so no
AC is spent on the combination). See [Usage-limit indicator](usage-limit-indicator.md#placement-in-the-thread)
([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804)) for why that arm sits between this one and
compaction. The interrupt control — mounted directly below
this slot until [#643](../codebase/643.md), now the send button's stop variant in `ThreadInputBar`
just below the status area (see [Interrupt affordance](interrupt-affordance.md#placement--wiring)) —
is untouched by this arm: an in-flight turn stays interruptible while retrying or compacting.

Until [#883](../../specs/architecture/883-retire-literal-screen.md) retired it, this was also the
counterpoint to `StallPromotionBanner`, which lived in a different slot entirely (above the list, below
`ConnectionBanner`) and was independent — every signal in this slot could legitimately co-render with it.

## Wiring

Threaded exactly like `isStalled` — a **defaulted hoisted value**, sibling to `isThinking`, **not** a
`ThreadUiState` field:

- **`ThreadViewModel`** exposes it as a sibling `StateFlow<ApiRetryStatus>` beside `connectionState` /
  `isThinking` / `isStalled`, a verbatim clone of `isStalled`'s hoist — sourced from the **already-
  injected** repository, **no constructor / DI / interface change**:

  ```kotlin
  val apiRetry: StateFlow<ApiRetryStatus> =
      repository.observeApiRetry(conversationId)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = ApiRetryStatus.NotRetrying)
  ```

  No extra operator: #593's remote impl already applies `distinctUntilChanged`, and the facade already
  defaults to `NotRetrying` — which covers both "no live connection" and "not retrying". **Adding
  `distinctUntilChanged`, `derivedStateOf`, or a bare `remember { }` here would freeze a climbing
  counter** and break AC #1 — `Attempt` is a `data class`, so a climbed counter is structurally a
  *different* value and recomposition follows for free without any extra dedup.
- **`ThreadScreen`** gains `apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying` in its trailing-
  defaults block, **after** `modifier` (Compose lint's `ComposeParameterOrder`, #508), placed beside
  `isStalled` to keep the status signals grouped. Defaulting it keeps all ~9 existing `ThreadScreen(`
  call sites compiling untouched; only `MainActivity` and the scripted harness gain an argument.
- **`MainActivity`** collects it in the `CONVERSATION_THREAD` destination exactly parallel to
  `isStalled` and passes it in — two lines, mirroring the `isStalled` wiring.

See [API-retry status](api-retry-status.md) for the upstream data path (#593's `api_retry` decode →
`observeApiRetry` projection) that produces this value.

## Recomposition / stability

- `status: ApiRetryStatus` is a stable-by-structural-equality param (`data class`/`data object`), so
  recomposition tracks value changes correctly, including a climbed counter.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of `status`
  to a rendered (or absent) row.
- `ThreadScreen` gains one param and an either/or branch at an existing single-child slot — no impact on
  the `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. The light preview shows the counter-shown case
(`Attempt(3, 10)`); the dark preview shows the counter-less case (`AttemptUnknown`) — between the two,
both rendered branches are covered.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only. No `gradle/libs.versions.toml`
  edits.
- **Four new string resources** in `res/values/strings.xml`, all positional (`%1$d`/`%2$d` where
  applicable — the `archived_tab_channels` precedent, never string concatenation):

  | Name | Value |
  |---|---|
  | `thread_api_retry_label` | `Retrying — attempt %1$d/%2$d` |
  | `thread_api_retry_label_unknown` | `Retrying…` |
  | `cd_thread_api_retry` | `Claude is retrying, attempt %1$d of %2$d` |
  | `cd_thread_api_retry_unknown` | `Claude is retrying, attempt count unknown` |

## Edge cases / limitations

- **Visual is design-owed.** No retry treatment is drawn in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — the same design-owed gap
  already recorded for [`ThinkingIndicator`](thinking-indicator.md) (and, until [#883](../../specs/architecture/883-retire-literal-screen.md)
  retired it, the stall promotion banner). Until it lands the visual follows the app's
  existing M3 progress idiom; when the frame arrives, re-tune spinner/typography here — no contract
  change.
- **No `liveRegion` on any of the status affordances** (this indicator, its counter-less sibling,
  `ThinkingIndicator`) — flagged as a non-gating NIT in #594's code review, same
  class of deferred a11y enhancement as `ThinkingIndicator`'s. Folded into the design-owed follow-up
  rather than fixed per-component.
- **No animation.** The swap between thinking / retrying / neither is an instant early-return/either-or
  change, matching every sibling status affordance.
- **Never observed in production today**, same as the data half: the daemon emits `api_retry` only from
  the PTY-runner detector family; production runs the stream-json interactive runner, which has no
  emitter. Rung 3 (real claude) and rung 4 (`fakeclaude`) cannot exercise this component end-to-end for
  that reason — see [API-retry status § Related](api-retry-status.md) and
  `docs/e2e-interactive-stream.md`'s `Coverage:` entry. Coverage is rung 2 only:
  `ScriptedApiRetryTest`, driving both edges and both counter cases through the real repository fold.

## Related

- Ticket notes: [`../codebase/594.md`](../codebase/594.md) (this component) ·
  [`../codebase/593.md`](../codebase/593.md) (the data/repository half it consumes).
- Spec: `docs/specs/architecture/594-api-retry-status-render.md`.
- Upstream signal: [API-retry status](api-retry-status.md) — `ThreadViewModel.apiRetry` /
  `observeApiRetry`, the `api_retry` decode this component renders.
- Host: [Thread screen](thread-screen.md) — threads `apiRetry` as another flat sibling parameter and
  arbitrates the status slot (the composer's `ThreadStatusArea` since [#643](../codebase/643.md);
  the foot of the content `Column` before it), a five-way `when` across it,
  [`UsageLimitIndicator`](usage-limit-indicator.md), [`CompactingIndicator`](compacting-indicator.md),
  [`TurnOutcomeIndicator`](turn-outcome-indicator.md), and `ThinkingIndicator`.
- Idioms mirrored: [Thinking indicator](thinking-indicator.md) (the direct clone — early-return,
  sibling-`StateFlow`, defaulted-hoisted-parameter, merged-`semantics`, design-owed M3 default,
  light/dark previews, file-private spacing `val`s intentionally **not** shared/refactored across the
  two components). The stall promotion banner was the different-slot, independently-co-rendering
  counterpoint until [#883](../../specs/architecture/883-retire-literal-screen.md) retired it.
- Sibling render slices: [Compacting indicator](compacting-indicator.md) ([#597](../codebase/597.md)) —
  joined this slot as the third arm, ordered below api-retry so the alarming signal is never masked by
  the benign one; [Usage-limit indicator](usage-limit-indicator.md)
  ([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804)) — joined between api-retry and
  compaction for the same reason.
- Parent: split from [#582](https://github.com/pyrycode/pyrycode-mobile/issues/582); sibling data slice
  [#593](../codebase/593.md) (PR #595, `022c0b8`).
- Known unrelated pre-existing failure discovered while verifying this ticket:
  [#598](https://github.com/pyrycode/pyrycode-mobile/issues/598) — `ThreadScreenChannelInfoTest`
  ambiguous-match flake, reproduced on `main` before this change, not fixed here.
- Server SSOT: pyrycode#1074 (design, merged PR pyrycode#1160, 2026-07-21),
  `internal/protocol/interactive.go:99`, `docs/protocol-mobile.md § api_retry`.
