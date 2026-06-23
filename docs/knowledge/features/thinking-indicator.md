# Thinking indicator — `ThinkingIndicator`

The **UI half of the thinking indicator** ([#407](../codebase/407.md), split from #386): a stateless
composable that renders the active conversation's pre-text `thinking` phase as a small at-work
affordance at the **foot** (bottom / most-recent edge) of the message list, so the user can tell the
agent is working rather than stalled. Part of the Phase 2 structured-streaming exit-gate (pyrycode#596,
ADR 025).

The signal it renders is the **data half** — [`ThreadViewModel.isThinking`](turn-state-thinking-flag.md)
([#406](../codebase/406.md)). This component adds **no data access**: it receives the already-reduced
flag as a hoisted boolean, exactly as [`ConnectionBanner`](connection-banner.md) receives
`ConnectionState`. The `responding` phase (assistant text growing) is covered separately by the shipped
streaming UI (#184/#185); this component owns only the `thinking` (pre-text) presentation and its
absence whenever the conversation is not thinking.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ThinkingIndicator.kt`.

## Shape

```kotlin
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
)
```

Two params, the load-bearing one (`isThinking`) without a default. The composable is a **pure function
of the boolean** — no `ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no
`ThreadUiState` field. Statelessness is an AC, not a style choice.

## What it does

- **`if (!isThinking) return`** — emits nothing when not thinking (zero composition, zero height),
  mirroring [`ConnectionBanner`](connection-banner.md)'s early-return show/hide idiom. The indicator
  clears the instant the flag flips, because it holds no local state of its own.
- When `true`, renders a start-aligned `Row` (`fillMaxWidth`, `padding(horizontal = 16.dp, vertical =
  8.dp)`, `verticalAlignment = CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(8.dp)`)
  containing:
  - a small **indeterminate** `CircularProgressIndicator` — `Modifier.size(16.dp)`, `strokeWidth =
    2.dp` (deliberately smaller than the 48dp `ScannerConnectingScreen` full-screen spinner; this is a
    foot-of-list affordance, not a loader). Default M3 `primary` colour — no override.
  - an adjacent `Text` "Thinking…" (`thread_thinking_label`), styled `MaterialTheme.typography.bodySmall`
    / `color = MaterialTheme.colorScheme.onSurfaceVariant`.
- **Accessibility** — the row carries `Modifier.semantics(mergeDescendants = true) { contentDescription
  = … }` sourced from `cd_thread_thinking` ("Agent is thinking"). Merging descendants makes TalkBack
  announce the indicator once as a single node; the decorative spinner + visible label are subsumed
  under that accessible name.

The 16dp horizontal inset matches `ConnectionBanner`'s `BannerHorizontalPadding`, so the indicator
aligns with the banner pinned above the list.

### Spacing constants

Five file-private `val`s at the top of `ThinkingIndicator.kt` — no raw `.dp` literal in the body, same
named-constant posture as [`ConnectionBanner`](connection-banner.md) / [`MessageBubble`](message-bubble.md):

```kotlin
private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStrokeWidth = 2.dp
private val SpinnerLabelGap = 8.dp
```

## Placement in the thread

The indicator is rendered by [`ThreadScreen`](thread-screen.md) as the **final child of the content
`Column`**, immediately after the `if (!state.hasMessages) EmptyThreadState(…) else LazyColumn(…)` block
closes (`ThreadScreen.kt:221`), **outside** the `if/else`:

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(…) else LazyColumn(reverseLayout = true, …) { … }
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

Why this seam:

- The `EmptyThreadState` (empty branch) and the `LazyColumn` (populated branch) each take `weight(1f)`;
  the indicator is **wrap-height** and sits directly below that weighted region, above the Scaffold
  `bottomBar` (status row + input). With `reverseLayout = true` pinning the newest message (list index
  0) to the bottom, this is the visual **foot / most-recent edge** — directly beneath the latest message
  and above where the user types.
- Being **outside** the `if/else` and **not** gated on `hasMessages`, one call site surfaces the
  indicator identically in the **empty-thread** case (thinking precedes the first assistant text, AC #2)
  and the populated case — no duplication.
- The `LazyColumn`, its `weight`/`reverseLayout`, and the streaming auto-scroll effects are untouched.

## Wiring

The flag is threaded as a **defaulted hoisted boolean**, sibling to `connectionState` — **not** a
`ThreadUiState` field:

- **`ThreadScreen`** gains `isThinking: Boolean = false` in its trailing-defaults block, after
  `modifier` (`ThreadScreen.kt:74`). Defaulting it keeps the 4 in-file previews + the 2 androidTest call
  sites + all other callers compiling untouched; only the live caller sets it. `false` = the inert "not
  thinking" default.
- **`MainActivity`** collects it in the `CONVERSATION_THREAD` destination exactly parallel to
  `connectionState` and passes it in:

  ```kotlin
  val isThinking by vm.isThinking.collectAsStateWithLifecycle()   // MainActivity.kt:349
  …
  ThreadScreen(…, connectionState = connectionState, isThinking = isThinking, …)   // :363
  ```

See [Turn-state thinking flag](turn-state-thinking-flag.md) for the upstream data path (the #406
coordinator seam → `ThreadViewModel.isThinking` reduction) that produces this flag.

## Recomposition / stability

- `isThinking: Boolean` is a stable param ⇒ `ThinkingIndicator` is **skippable** and recomposes only
  when the flag flips.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of the
  boolean to a rendered (or absent) row.
- `ThreadScreen` gains one stable `Boolean` param; the new placement is a single cheap wrap-height row
  at the foot — no impact on the `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. Each wraps `PyrycodeMobileTheme(darkTheme = …) { Surface {
ThinkingIndicator(isThinking = true) } }`, copied verbatim from the
[`EmptyThreadState`](empty-thread-state.md) template. Both show the **active** (`isThinking = true`)
state so the visual is reviewable — the `false` case renders nothing and needs no preview.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only.
- **Two new string resources** in `res/values/strings.xml`: `thread_thinking_label` ("Thinking…",
  visible) and `cd_thread_thinking` ("Agent is thinking", content description — mandatory per AC #5).
  Unlike `ConnectionBanner` (which uses Kotlin literals), this component uses `stringResource` — the
  content description must be a `cd_*` resource by the codebase a11y convention.
- **No `gradle/libs.versions.toml` edits.**

## Edge cases / limitations

- **Visual is design-owed.** The dedicated thinking-state Figma frame is **not yet drawn** in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (flagged for Juhana). Until
  it lands, the visual follows the app's existing M3 progress idiom (small indeterminate spinner +
  label). When the frame arrives, re-tune the spinner size/colour/label here — no contract change. The
  `responding`-state streaming caret is the separately-drawn affordance at `16-54`, out of scope.
- **No animation.** The show/hide is an instant early-return swap, matching `ConnectionBanner`. A
  subtle fade-in (`AnimatedVisibility`) is a design-owed nicety, deferred with the Figma frame.
- **Stale-`true`-on-resume (known, accepted).** The upstream `isThinking`
  ([`stateIn(WhileSubscribed(5_000))`](turn-state-thinking-flag.md) over a `replay = 0` source) can
  momentarily read a stale `true` on re-foreground after a long background. This is a **data-layer**
  edge deliberately not handled here — handling it would mean either a data-layer change (out of this
  UI-only slice's scope) or local state in the composable (violates the statelessness AC). It matches
  the transient "right-now" posture already accepted for [`connectionState`](connection-state.md) and
  the [stall flag](stall-state.md). File a follow-up if it reads jarring in practice.
- **Spinner-only vs. spinner + label.** The shipped variant is spinner **+** "Thinking…" label (the M3
  idiom default for clarity until the design frame lands); a spinner-only variant would equally satisfy
  the ACs. The content description is mandatory either way.
- **a11y enhancement (open, NIT).** Code review flagged that adding `liveRegion =
  LiveRegionMode.Polite` would let TalkBack announce "Agent is thinking" on appearance without the user
  navigating to the node — appropriate for a transient status affordance. Deferred as a non-blocking
  enhancement, folded into the design-owed follow-up.

## Related

- Ticket notes: [`../codebase/407.md`](../codebase/407.md) (this component) ·
  [`../codebase/406.md`](../codebase/406.md) (the data/ViewModel half it consumes).
- Spec: `docs/specs/architecture/407-thinking-indicator-thread-foot.md`.
- Upstream signal: [Turn-state thinking flag](turn-state-thinking-flag.md) — `ThreadViewModel.isThinking`,
  the `turn_state` → flag reduction this component renders.
- Host: [Thread screen](thread-screen.md) — threads `isThinking` as a third flat sibling parameter and
  mounts the indicator at the foot of the content `Column`.
- Foot-of-list sibling (shipped): [Interrupt affordance](interrupt-affordance.md)
  ([#459](../codebase/459.md)) — the "Stop the running turn" control mounted **directly below** this
  indicator, copying its stateless early-return / merged-`semantics` / light+dark-preview structure but
  gated on the broader [`isBusy`](turn-state-thinking-flag.md) flag (`thinking` **or** `responding`). During
  `thinking` both show, stacked — intentional/interim until the design-owed Figma frame reconciles them.
- Idiom mirrored: [ConnectionBanner](connection-banner.md) (stateless early-return show/hide,
  file-private spacing `val`s, 16dp horizontal inset), [EmptyThreadState](empty-thread-state.md)
  (light/dark preview template, `stringResource` usage). The existing M3 progress idiom it follows:
  `ScannerConnectingScreen` (`CircularProgressIndicator(size(48.dp))`), `StatusSheet`
  (`LinearProgressIndicator`), `LiteralScreenSurface` (`CircularProgressIndicator`).
- Parent: split from [#386](https://github.com/pyrycode/pyrycode-mobile/issues/386); grandparent
  [#368](https://github.com/pyrycode/pyrycode-mobile/issues/368).
- Server SSOT: pyrycode#607 (`turn_state` wire), #616 (capability-gated fan-out), ADR 025 § Phase 2
  structured streaming, EPIC pyrycode#596.
