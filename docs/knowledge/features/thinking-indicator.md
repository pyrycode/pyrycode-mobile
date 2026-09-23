# Thinking indicator — `ThinkingIndicator`

The **UI half of the thinking indicator** ([#407](../codebase/407.md), split from #386): a stateless
composable that renders the active conversation's pre-text `thinking` phase as a small at-work
affordance in the composer's status band, so the user can tell the agent is working rather than stalled.
Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025).

The signal it renders is the **data half** — [`ThreadViewModel.isThinking`](turn-state-thinking-flag.md)
([#406](../codebase/406.md)). This component adds **no data access**: it receives the already-reduced
flag as a hoisted boolean, exactly as [`ConnectionBanner`](connection-banner.md) receives
`ConnectionState`. The `responding` phase (assistant text growing) is covered separately by the shipped
streaming UI (#184/#185); this component owns only the `thinking` (pre-text) presentation and its
absence whenever the conversation is not thinking.

[#803](../codebase/803.md) (split from #653) extended the component to also carry claude's live
`thinking_progress` token reading — [`ThinkingProgress`](thinking-progress-state.md), decoded by #801 —
so a multi-minute turn reads as *working* rather than *wedged*. The reading decorates this same arm; it
is not a new affordance and does not change when the arm shows (§ What it does, § The progress reading).

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ThinkingIndicator.kt`.

## Shape

```kotlin
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    progress: ThinkingProgress? = null,
)
```

`isThinking` is the load-bearing param, without a default. `progress` (#803) is optional and lands after
`modifier` (Compose lint's `ComposeParameterOrder`, #508) — `null` means no reading is available and the
component renders exactly as it did before #803. The composable is a **pure function of its params** —
no `ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no `ThreadUiState`
field. Statelessness is an AC, not a style choice.

## What it does

- **`if (!isThinking) return`** — emits nothing when not thinking (zero composition, zero height),
  mirroring [`ConnectionBanner`](connection-banner.md)'s early-return show/hide idiom. The indicator
  clears the instant the flag flips, because it holds no local state of its own. **Visibility stays
  governed by `isThinking` alone** — a live [`progress`](thinking-progress-state.md) reading does not
  independently raise the arm; `turn_state` (#406) owns the thinking phase, and letting a reading show
  the arm on its own would be a second arm wearing this one's name.
- When `true`, renders a start-aligned `Row` (`fillMaxWidth`, `padding(horizontal = 16.dp, vertical =
  8.dp)`, `verticalAlignment = CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(8.dp)`)
  containing:
  - a small **indeterminate** `CircularProgressIndicator` — `Modifier.size(16.dp)`, `strokeWidth =
    2.dp` (deliberately smaller than the 48dp `ScannerConnectingScreen` full-screen spinner; this is a
    status-band affordance, not a loader). Default M3 `primary` colour — no override.
  - an adjacent `Text`, either "Thinking…" (`thread_thinking_label`) or, with a renderable reading,
    "Thinking… ~N tokens this step" (`thread_thinking_progress_label`) — styled
    `MaterialTheme.typography.bodySmall` / `color = MaterialTheme.colorScheme.onSurfaceVariant`.
- **Accessibility** — the row carries `Modifier.semantics(mergeDescendants = true) { contentDescription
  = … }`, sourced from `cd_thread_thinking` ("Agent is thinking") or, with a renderable reading,
  `cd_thread_thinking_progress` ("Claude is thinking, about N tokens into its current reasoning step").
  Merging descendants makes TalkBack announce the indicator once as a single node; the decorative
  spinner + visible label are subsumed under that accessible name.
- **One `Row`, one `CircularProgressIndicator`, deliberately (#803).** The two label variants are chosen
  by varying only the `Text` argument and the row's content description inside a single composition —
  never by an `if (progress != null) Row { … } else Row { … }` split. Two distinct `Row`/spinner call
  sites would give Compose two groups: the first reading to arrive would dispose the old spinner and
  compose a fresh one, **restarting its rotation** at exactly the moment a reading appears — a visible
  hitch, and the inverse of "updates without flicker". This property is guaranteed **structurally**, not
  by assertion: Compose's test API cannot assert node identity across a recomposition, so a test that
  claimed to would be proving something weaker than it reads. [`ApiRetryIndicator`](api-retry-indicator.md)
  shares the identical structure for the identical reason.
- **No `remember`-cached label, no `derivedStateOf`, no local state (#803).** A cached label would
  freeze a changing reading — the inverse of the point. The reading reaches Compose already
  `distinctUntilChanged`-deduped upstream (see [Thinking-progress state](thinking-progress-state.md)), so
  no further operator belongs here either.

The 16dp horizontal inset matches `ConnectionBanner`'s `BannerHorizontalPadding`, so the indicator
aligns with the banner pinned above the list.

## The progress reading (#803)

Two things the wire contract (`docs/protocol-mobile.md § thinking_progress` in the pyrycode repo)
forbids at this render boundary, both load-bearing for the chosen strings:

1. **Not a turn total.** `estimatedTokens` is cumulative within *one inference request*, not a turn, and
   restarts near zero at every request boundary — several times inside a single turn. The label
   therefore reads "…tokens **this step**", never "…tokens" bare, so a reader cannot mistake it for a
   running total.
2. **No denominator, ever.** The per-line deltas a client receives do not sum to the turn's total and no
   field reports the residue, so there is no "N of M", no percentage, and no bar — and never will be,
   because no field on the wire could fill in the "of M". `estimatedTokensDelta` is **not rendered at
   all**: it is a rate reading, and putting it on screen is the most direct invitation to sum it, which
   the wire contract forbids.

Chosen strings (plain local resources, one integer argument, no daemon-authored text on this path — see
§ Security):

| Name | Value |
|---|---|
| `thread_thinking_progress_label` | `Thinking… ~%1$d tokens this step` |
| `cd_thread_thinking_progress` | `Claude is thinking, about %1$d tokens into its current reasoning step` |

### Display sanity gate — render-or-decline, never a clamp

A file-private predicate, cloning [`ApiRetryIndicator`](api-retry-indicator.md)'s
`isRenderableCounter()` shape:

```kotlin
private const val MAX_PLAUSIBLE_THINKING_TOKENS = 1_000_000L

private fun ThinkingProgress.isRenderableReading(): Boolean =
    estimatedTokens in 0..MAX_PLAUSIBLE_THINKING_TOKENS
```

A reading outside the band renders the plain "Thinking…" arm — the less-specific-but-true form — and is
**never rewritten**. [#801](thinking-progress-state.md) deliberately declined a lower-bound rejection at
decode, on the carry-verbatim rule every sibling mapper follows, so what reaches Compose can legally be
negative or arbitrarily large; clamping it here would re-import exactly the server-data rewrite that
decision refused. A negative count is meaningless to a reader, and an unbounded one would push the band
past the trailing contextual-action slot (#675, still unfilled).

The 1,000,000 ceiling is picked against a real quantity: the largest documented extended-thinking budget
for one request is ~64k tokens, so the cap leaves better than an order of magnitude of headroom while
keeping the rendered number to seven digits. Exceeding it is not an error.

**No grouping separators.** `%1$d` through `stringResource` formats with the configuration locale (so
locale-specific digits come free) but inserts no thousands separators. At the measured magnitudes
(1–~200 in the committed capture) this is moot, and even at the gate's ceiling the number stays seven
digits — a locale-keyed formatter was considered and rejected because caching its result in `remember`
would risk freezing the label, the exact defect § What it does guards against.

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

**Moved in [#643](../codebase/643.md).** [`ThreadScreen`](thread-screen.md) arbitrates this status slot
as a **three-way `when`** (extended from #594's two-way `if` by #597) inside a private `ThreadStatusArea`
composable (`ThreadScreen.kt:471`), the first child of the composer's `bottomBar` column — through #642
the same `when` lived at the foot of the content `Column`, above the composer rather than inside it:

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying ->
        ApiRetryIndicator(status = apiRetry, modifier = Modifier.fillMaxWidth())
    isCompacting ->
        CompactingIndicator(isCompacting = true, modifier = Modifier.fillMaxWidth())
    else ->
        ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth(), progress = thinkingProgress)
}
```

**Exactly one affordance renders; the arms never stack.** api-retry keeps the top arm ("something is
going wrong" over benign progress), then compaction, then this arm — see
[Compacting indicator § Placement](compacting-indicator.md#placement-in-the-thread) for the full
precedence rationale, unchanged by #803. `#803`'s reading rides this arm's own `else` branch, so it adds
**no new arm**: retry and compaction pre-empt a live reading for free, and mutual exclusion holds
structurally rather than by an added check.

See [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643)
for the gutter arithmetic of the composer's status band.

## Wiring

Both values are threaded as **defaulted hoisted parameters**, sibling to `connectionState` — **not**
`ThreadUiState` fields:

- **`ThreadScreen`** gains `isThinking: Boolean = false` in its trailing-defaults block, after
  `modifier` (`ThreadScreen.kt:74`), and (#803) `thinkingProgress: ThinkingProgress? = null` beside
  `isCompacting`. Defaulting both keeps every existing `ThreadScreen(` call site and preview compiling
  untouched; only the live caller and the scripted harness pass real values. `false` / `null` are the
  inert "not thinking" / "no reading" defaults.
- **`MainActivity`** collects each in the `CONVERSATION_THREAD` destination exactly parallel to
  `connectionState` and passes them in:

  ```kotlin
  val isThinking by vm.isThinking.collectAsStateWithLifecycle()             // MainActivity.kt
  val thinkingProgress by vm.thinkingProgress.collectAsStateWithLifecycle() // #803
  …
  ThreadScreen(…, isThinking = isThinking, thinkingProgress = thinkingProgress, …)
  ```

See [Turn-state thinking flag](turn-state-thinking-flag.md) for the `isThinking` data path (the #406
coordinator seam → `ThreadViewModel.isThinking` reduction) and
[Thinking-progress state](thinking-progress-state.md) for the `thinkingProgress` data path (#801's
`thinking_progress` decode → `ThreadViewModel.thinkingProgress`, a verbatim clone of the `isCompacting`
hoist over the already-injected repository — no constructor/DI/interface change).

## Recomposition / stability

- `isThinking: Boolean` is a stable param ⇒ recomposition tracks the flag directly.
- `progress: ThinkingProgress?` (#803) is a nullable `data class` ⇒ structurally stable and
  equality-comparable. The upstream `observeThinkingProgress` projection is already
  `distinctUntilChanged`, and **no further dedup operator may be added anywhere in the chain**: that one
  upstream dedup is what makes a *repeated* reading hold rather than rewrite the label, while a *falling*
  reading — a different `ThinkingProgress` value — still reaches the screen. Adding a second dedup, a
  `derivedStateOf`, or a running-maximum guard at any point would break one half of that contract or the
  other (see [Thinking-progress state § The reading is not monotonic](thinking-progress-state.md#the-reading-is-not-monotonic--carried-verbatim-no-exceptions)).
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of the params
  to a rendered (or absent) row.
- `ThreadScreen` gains one stable nullable param; the status band is a single cheap wrap-height row — no
  impact on the `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. The light preview shows a live reading
(`ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64)` — 184 is the top of the first of
the four restarts the committed capture's single turn contains, a realistic magnitude rather than a
round invented one); the dark preview shows the plain `progress = null` case — between the two, both
label variants are reviewable.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only.
- **Two string resources** from #407: `thread_thinking_label` ("Thinking…", visible) and
  `cd_thread_thinking` ("Agent is thinking", content description — mandatory per AC #5). Unlike
  `ConnectionBanner` (which uses Kotlin literals), this component uses `stringResource` — the content
  description must be a `cd_*` resource by the codebase a11y convention.
- **Two more (#803)**, both positional integer arguments, no daemon-authored text: `thread_thinking_progress_label`
  ("Thinking… ~%1$d tokens this step") and `cd_thread_thinking_progress` ("Claude is thinking, about
  %1$d tokens into its current reasoning step") — see § The progress reading.
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
  enhancement, folded into the design-owed follow-up (shared across all four status affordances — see
  [Compacting indicator § Edge cases](compacting-indicator.md#edge-cases--limitations)).
- **A stuck reading (#803, accepted, undefended).** A hostile or crashed daemon can pin a reading
  indefinitely by sending one `thinking_progress` frame and no clearing event — it stands until turn end,
  session transition, or reconnect (see
  [Thinking-progress state § Edge cases](thinking-progress-state.md#edge-cases--limitations)). Not
  handled here: a client-side timeout would be exactly the "infer something from a gap" the wire contract
  forbids. The operator is never trapped by it — the interrupt control, the composer and
  `StallPromotionBanner` all stay live beside this slot regardless of what it shows.
- **A reading can briefly describe the previous inference request (#803, code-review NIT, not fixed
  here).** The reading clears only on turn end or session transition, not when `turn_state` leaves
  `thinking` for an intermediate phase. In a turn shaped thinking → tool → thinking, the arm can come back
  showing the prior request's "~N tokens this step" until the new request's first frame crosses the
  daemon's rate threshold. This does not violate the "no turn total" wording rule — the number is still a
  genuine one-request reading — but "this step" can momentarily describe the wrong step. The fix belongs
  to [#801](thinking-progress-state.md)'s clearing policy (an additional clear on leaving `thinking`), not
  to this component; worth a follow-up if a live run under #679 shows it reading as jarring in practice.
- **Coverage is rung 2 only.** `ScriptedThinkingProgressTest` drives both edges (a rising reading, a
  falling reading, an identical repeat) and both mutual-exclusion cases (api-retry and compaction each
  winning over a live reading) through the real repository fold on
  [`ScriptedThreadHarness`](thread-screen-testing.md), in `ScriptedCompactingTest`'s shape.
  `ThinkingIndicatorTest` covers the display sanity gate directly (a negative reading and `Long.MAX_VALUE`
  both decline to the plain arm) at the component level. Live behaviour is [#679](https://github.com/pyrycode/pyrycode-mobile/issues/679)'s,
  not this ticket's — see `docs/e2e-interactive-stream.md`'s `Coverage:` entry for `thinking_progress`.

## Related

- Ticket notes: [`../codebase/407.md`](../codebase/407.md) (this component) ·
  [`../codebase/406.md`](../codebase/406.md) (the data/ViewModel half it consumes) ·
  [`../codebase/803.md`](../codebase/803.md) (the progress-reading extension).
- Specs: `docs/specs/architecture/407-thinking-indicator-thread-foot.md` ·
  `docs/specs/architecture/803-thinking-progress-status-render.md`.
- Upstream signals: [Turn-state thinking flag](turn-state-thinking-flag.md) — `ThreadViewModel.isThinking`,
  the `turn_state` → flag reduction that governs visibility — and
  [Thinking-progress state](thinking-progress-state.md) — `ThreadViewModel.thinkingProgress` /
  `observeThinkingProgress` (#801), the `thinking_progress` decode this component renders as the optional
  `progress` param.
- Host: [Thread screen](thread-screen.md) — threads `isThinking` and (#803) `thinkingProgress` as flat
  sibling parameters and arbitrates the status slot (the composer's `ThreadStatusArea` since
  [#643](../codebase/643.md); the foot of the content `Column` before it) across this component,
  [`ApiRetryIndicator`](api-retry-indicator.md), and [`CompactingIndicator`](compacting-indicator.md).
- Foot-of-list sibling (shipped): [Interrupt affordance](interrupt-affordance.md)
  ([#459](../codebase/459.md)) — the "Stop the running turn" control, since #643 the send button's stop
  variant in `ThreadInputBar` just below the status area, gated on the broader
  [`isBusy`](turn-state-thinking-flag.md) flag (`thinking` **or** `responding`) and untouched by #803: an
  in-flight turn stays interruptible regardless of what the status arm shows.
- Idiom mirrored: [ConnectionBanner](connection-banner.md) (stateless early-return show/hide,
  file-private spacing `val`s, 16dp horizontal inset), [EmptyThreadState](empty-thread-state.md)
  (light/dark preview template, `stringResource` usage), [ApiRetryIndicator](api-retry-indicator.md) (the
  display-sanity-gate and single-spinner-call-site idioms #803 clones directly). The existing M3 progress
  idiom it follows: `ScannerConnectingScreen` (`CircularProgressIndicator(size(48.dp))`), `StatusSheet`
  (`LinearProgressIndicator`), `LiteralScreenSurface` (`CircularProgressIndicator`).
- Parent: split from [#386](https://github.com/pyrycode/pyrycode-mobile/issues/386); grandparent
  [#368](https://github.com/pyrycode/pyrycode-mobile/issues/368). #803 split from
  [#653](https://github.com/pyrycode/pyrycode-mobile/issues/653), sibling data slice
  [#801](../codebase/801.md) (PR #809).
- Server SSOT: pyrycode#607 (`turn_state` wire), #616 (capability-gated fan-out), ADR 025 § Phase 2
  structured streaming, EPIC pyrycode#596; pyrycode#1386 and `docs/protocol-mobile.md § thinking_progress`
  for the #803 reading.
