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

\#897 (split from #658) extended it again to name an **open tool call** —
`Running Bash…`, with claude's elapsed reading appended — reusing [`ToolCallRow`](tool-call-row.md)'s
`formatToolElapsed`. Unlike the progress reading, a running tool *does* change when the arm shows: it
raises the arm during the `responding` phase too, the one gap #803 left (§ The running tool).

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ThinkingIndicator.kt`.

## Shape

```kotlin
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    progress: ThinkingProgress? = null,
    runningTool: ToolCall? = null,
    agent: ConversationAgent = ConversationAgent.Claude,
)
```

`isThinking` is the load-bearing param, without a default. `progress` (#803) is optional and lands after
`modifier` (Compose lint's `ComposeParameterOrder`, #508) — `null` means no reading is available and the
component renders exactly as it did before #803. `runningTool` (#897) is the trailing param for the same
reason, and `null` means no tool is open. `agent` ([#1114](https://github.com/pyrycode/pyrycode-mobile/issues/1114))
is the new trailing param, defaulting to `Claude` so every pre-#1114 call site and preview keeps compiling
and rendering unchanged. The composable is a **pure function of its params** — no
`ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no `ThreadUiState` field.
Statelessness is an AC, not a style choice.

## What it does

- **`if (!isThinking && runningTool == null) return`** (#897 widened the pre-#897 `if (!isThinking)
  return`) — emits nothing when neither signal is live (zero composition, zero height), mirroring
  [`ConnectionBanner`](connection-banner.md)'s early-return show/hide idiom. The indicator clears the
  instant both flip false, because it holds no local state of its own. **Visibility stays governed by
  `isThinking` OR `runningTool` alone** — a live [`progress`](thinking-progress-state.md) reading does
  not independently raise the arm; `turn_state` (#406) owns the thinking phase, and letting a reading
  show the arm on its own would be a third arm wearing this one's name. `runningTool` is the one signal
  that *does* raise the arm on its own (§ The running tool) — the caller gates it on `isBusy` before
  passing it in, so a tool open during `responding` still shows, and a stale `Running` row after the
  turn has ended does not.
- When shown, renders a start-aligned `Row` (`fillMaxWidth`, `padding(horizontal = 16.dp, vertical =
  8.dp)`, `verticalAlignment = CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(8.dp)`)
  containing:
  - a small **indeterminate** `CircularProgressIndicator` — `Modifier.size(16.dp)`, `strokeWidth =
    2.dp` (deliberately smaller than the 48dp `ScannerConnectingScreen` full-screen spinner; this is a
    status-band affordance, not a loader). Default M3 `primary` colour — no override.
  - an adjacent `Text`, chosen in priority order: with an open tool, "Running <tool>…"
    (`thread_tool_running_label`) or, with an elapsed reading too, "Running <tool>… <elapsed>"
    (`thread_tool_running_elapsed_label`); otherwise "Thinking…" (`thread_thinking_label`) or, with a
    renderable progress reading, "Thinking… ~N tokens this step" (`thread_thinking_progress_label`) —
    styled `MaterialTheme.typography.bodySmall` / `color = MaterialTheme.colorScheme.onSurfaceVariant`.
- **Accessibility** — the row carries `Modifier.semantics(mergeDescendants = true) { contentDescription
  = … }`, sourced by the same priority order: `cd_thread_tool_running` / `cd_thread_tool_running_elapsed`
  for an open tool, else `cd_thread_thinking` ("Agent is thinking") or, with a renderable reading,
  `cd_thread_thinking_progress` ("Claude is thinking, about N tokens into its current reasoning step").
  Merging descendants makes TalkBack announce the indicator once as a single node; the decorative
  spinner + visible label are subsumed under that accessible name.
- **One `Row`, one `CircularProgressIndicator`, deliberately (#803, extended by #897).** All four label
  variants (plain thinking, thinking + tokens, running tool, running tool + elapsed) are chosen by
  varying only the `Text` argument, its `maxLines`/`overflow`, and the row's content description inside a
  single composition — never by an `if (…) Row { … } else Row { … }` split. Distinct `Row`/spinner call
  sites would give Compose distinct groups: the first tool to open (or reading to arrive) would dispose
  the old spinner and compose a fresh one, **restarting its rotation** at exactly the moment a tool opens
  or a reading appears — a visible hitch, and the inverse of "updates without flicker". This property is
  guaranteed **structurally**, not by assertion: Compose's test API cannot assert node identity across a
  recomposition, so a test that claimed to would be proving something weaker than it reads.
  [`ApiRetryIndicator`](api-retry-indicator.md) shares the identical structure for the identical reason.
- **No `remember`-cached label, no `derivedStateOf`, no local state (#803, #897).** A cached label would
  freeze a changing reading — the inverse of the point. The reading reaches Compose already
  `distinctUntilChanged`-deduped upstream (see [Thinking-progress state](thinking-progress-state.md)), so
  no further operator belongs here either. `runningTool`'s elapsed seconds are likewise rendered exactly
  as received: there is no `LaunchedEffect`, no local timer, and no coroutine anywhere in this component
  or its caller — the seconds advance only when a new `ToolCall` value with a new `elapsedSeconds` arrives
  (§ The running tool).

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

## The running tool (#897)

**One selector, one call, both fields.** `runningTool` is not read from a `ThreadViewModel` flow — it is
derived in [`ThreadScreen`](thread-screen.md) by a pure top-level function beside the screen's other
list-derived state:

```kotlin
internal fun openToolCall(items: List<ThreadItem>): ToolCall?
```

It returns the `toolCall` of the **last** `ThreadItem.MessageItem` whose `message.toolCall?.status ==
ToolCallStatus.Running`, or `null` — `state.items` is chronological, so the latest row in the list is the
latest call to open, and a newer open call replaces an older one for free by list position. Returning one
`ToolCall` (not a `(name, seconds)` pair assembled from two lookups) is what guarantees the name and the
elapsed reading in the label always describe the same call — the AC's explicit requirement. A subagent's
nested tool row (#896) is still a row in `items`, so the latest open call wins whatever its parent is,
matching desktop.

**The screen gates it, not the component.** `ThreadScreen` computes `runningTool = if (isBusy) openToolCall(state.items) else null`, cached with `remember(state.items)` outside the `isBusy` gate (`isBusy` is
cheap to re-check every recomposition; the list scan is not). Passing `isBusy` through means the arm can
raise a call open during the `responding` phase — the gap #803 could not close, because `isThinking` alone
never covers that phase — and a `Running` row that outlives the turn (a race between the result frame and
the reducer clearing `isBusy`) never shows a label after the fact.

**Label priority.** Inside `ThinkingIndicator`, a running tool always wins over the thinking labels: `Running
<tool>…` alone, or `Running <tool>… <elapsed>` when `runningTool.elapsedSeconds` is non-null, where
`<elapsed>` is [`formatToolElapsed`](tool-call-row.md#subject-and-elapsed-text) — the identical function
and identical format `ToolCallRow` uses, imported from the Compose-free `ToolRowFormat.kt` so this
component adds no new formatting rule. `elapsedSeconds` is non-null only while [`ToolCallStatus`](data-model.md)
is `Running` ([#812](../codebase/812.md)) and claude's heartbeat arrives roughly every 30 seconds, so a
short-lived call can close with no reading ever shown — expected, not a bug, and the label omits the
elapsed half rather than showing a stale or invented one.

**One ellipsized line, tool labels only.** When `toolName != null`, the `Text` gets `maxLines = 1` /
`TextOverflow.Ellipsis`; the thinking labels keep their pre-#897 unbounded wrapping (`Int.MAX_VALUE` /
`TextOverflow.Clip`). The tool name is daemon-supplied text from an open set (never validated, never
logged, rendered only as this one inert `Text`), so the ellipsis is the same defence
[`ToolCallRow`](tool-call-row.md#what-it-does) already applies to the identical string — a long or
multi-line name is bounded, and if it also carries an elapsed reading, that reading can be cut off along
with it. This has not been observed and is the one open question the ticket's plan left for a future
Figma pass.

**Clears itself; nothing here decides that.** The label disappears the instant `openToolCall` stops
returning this call — on `Done`/`Failed`/`Denied` ([`HistoryPageReducer`](live-tool-call.md) closes the
row and clears `elapsedSeconds`) or on `isBusy` going false. `ThinkingIndicator` does not know or care
why; it only ever sees the next `ToolCall?` value.

## Placement in the thread

**Moved in [#643](../codebase/643.md).** [`ThreadScreen`](thread-screen.md) arbitrates this status slot
— a three-way `when` at #597 (extended from #594's two-way `if`), widened to six arms as
[#804](https://github.com/pyrycode/pyrycode-mobile/issues/804) (usage limit), #872 (resetting) and #805
(turn outcome) each inserted an arm above this one, then narrowed back to a **five-way `when`** when
[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) removed the usage-limit arm — that reading
now draws as a pill in [`ThreadTopOverlay`](thread-top-overlay.md), pinned over the message area, because a
live `allowed_warning` reading was masking every arm below it including this one — inside a private
`ThreadStatusArea` composable (`ThreadScreen.kt`), the first child of the composer's `bottomBar` column —
through #642 the same `when` lived at the foot of the content `Column`, above the composer rather than
inside it:

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying ->
        ApiRetryIndicator(status = apiRetry, modifier = Modifier.fillMaxWidth())
    resetting != null ->
        ResettingIndicator(status = resetting, modifier = Modifier.fillMaxWidth())
    isCompacting ->
        CompactingIndicator(isCompacting = true, modifier = Modifier.fillMaxWidth())
    turnOutcome != null ->
        TurnOutcomeIndicator(report = turnOutcome, modifier = Modifier.fillMaxWidth())
    else ->
        ThinkingIndicator(
            isThinking = isThinking,
            modifier = Modifier.fillMaxWidth(),
            progress = thinkingProgress,
            runningTool = runningTool, // #897
            agent = agent, // #1114
        )
}
```

(`apiRetry` and `isCompacting`'s own arms also gained `agent = agent` in #1114, and `resetting`'s gained
it in #1112 — all three omitted from the ladder above for brevity; see [API-retry
indicator](api-retry-indicator.md#placement-in-the-thread), [Compacting
indicator](compacting-indicator.md#placement-in-the-thread) and [Resetting indicator § The agent
name](resetting-indicator.md#the-agent-name-1112). `turnOutcome` still has not.)

**Exactly one affordance renders; the arms never stack.** api-retry keeps the top arm ("something is
going wrong" over lower-urgency signals), then resetting, then compaction, then the turn outcome, then this
arm — see [Compacting indicator § Placement](compacting-indicator.md#placement-in-the-thread) for the full
precedence rationale. Claude's usage-limit report shared this ladder, directly below api-retry, from #804
to [#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002); see [Usage-limit indicator §
Placement](usage-limit-indicator.md#placement--the-top-overlay-not-the-status-ladder-post-1002) for why it
now draws in [Thread top overlay](thread-top-overlay.md) instead. `#803`'s progress reading and `#897`'s
running-tool label both ride this arm's own
`else` branch, so neither adds **a new arm**: every arm above still pre-empts them for free, and mutual
exclusion holds structurally rather than by an added check. `runningTool` is the one input to this arm
that is not itself an arm-selector value — `ThreadScreen` passes it only while `isBusy`, so it can be
non-null and still lose to a higher arm exactly like `thinkingProgress` does.

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

**`runningTool` (#897) is not wired this way, deliberately.** It is not a `ThreadViewModel` `StateFlow`
and there is no `MainActivity` collection line for it. `ThreadScreen` already receives both of the inputs
`openToolCall` needs — `state.items` (for the fold) and `isBusy` (for the gate) — so the plan derived it
locally (`val openTool = remember(state.items) { openToolCall(state.items) }`, then
`runningTool = if (isBusy) openTool else null`, both inside the same composer block that builds
`ThreadStatusArea`'s other arguments) rather than adding a sixth `ThreadViewModel` flow and a fourth
`MainActivity` collection line for a value the screen could already compute for free. This is the ticket's
one departure from its own size estimate, which had listed `ThreadViewModel.kt` as a production file.

### The agent name (#1114)

**`agent` is wired the opposite way from every sibling status signal above: through `ThreadUiState`, not a
hoisted `StateFlow`.** `ThreadUiState` gains `agent: ConversationAgent = ConversationAgent.Claude`, set by
`ThreadViewModel` inside the same `combine` that sets `displayName`/`isPromoted`
(`agent = conv?.agent ?: ConversationAgent.Claude` — see [Data model § `Conversation`](data-model.md)).
`ThreadScreen` reads `state.agent` and passes it straight into `ThreadStatusArea` → `StatusReading` →
this component, `ApiRetryIndicator` and `CompactingIndicator`. No new `MainActivity` collection line: unlike
`isThinking`/`thinkingProgress`/`apiRetry`/`isCompacting`/`resetting` (each a live per-turn signal with its
own `WhileSubscribed` `StateFlow`), the agent is a slow-changing conversation property the VM's existing
conversations `combine` already resolves, so giving it a sixth sibling flow would duplicate work the state
already does for free. Each of the three indicators picks its description resource with an exhaustive
`when (agent)` over the two-value `ConversationAgent` enum, so a future third agent value fails to compile
here rather than silently defaulting to Claude's text.

## Recomposition / stability

- `isThinking: Boolean` is a stable param ⇒ recomposition tracks the flag directly.
- `progress: ThinkingProgress?` (#803) is a nullable `data class` ⇒ structurally stable and
  equality-comparable. The upstream `observeThinkingProgress` projection is already
  `distinctUntilChanged`, and **no further dedup operator may be added anywhere in the chain**: that one
  upstream dedup is what makes a *repeated* reading hold rather than rewrite the label, while a *falling*
  reading — a different `ThinkingProgress` value — still reaches the screen. Adding a second dedup, a
  `derivedStateOf`, or a running-maximum guard at any point would break one half of that contract or the
  other (see [Thinking-progress state § The reading is not monotonic](thinking-progress-state.md#the-reading-is-not-monotonic--carried-verbatim-no-exceptions)).
- `runningTool: ToolCall?` (#897) is a nullable `data class` ⇒ structurally stable and
  equality-comparable, same shape as `progress`. There is no upstream dedup for it — it isn't needed:
  `openToolCall` already returns the same `ToolCall` reference-equal-by-value across recompositions where
  nothing about the open call changed, and a genuinely new reading (a new `elapsedSeconds`) is exactly
  the case that must reach the label.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of the params
  to a rendered (or absent) row. `openToolCall` itself lives in `ThreadScreen`, not here, and is likewise
  a pure function with no coroutine.
- `ThreadScreen` gains one stable nullable param; the status band is a single cheap wrap-height row — no
  impact on the `LazyColumn`'s item recomposition. `openToolCall`'s scan is `remember(state.items)`-cached
  in the caller, so it re-runs only when the list reference actually changes, not on every recomposition.

## Preview

Two `@Preview`s, one per theme, both `showBackground = true`, `widthDp = 412` — the dark variant adds
`uiMode = Configuration.UI_MODE_NIGHT_YES`. The light preview shows a live reading
(`ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64)` — 184 is the top of the first of
the four restarts the committed capture's single turn contains, a realistic magnitude rather than a
round invented one); the dark preview shows the plain `progress = null` case — between the two, both
label variants are reviewable. A third `@Preview` (#897) shows the running-tool case: `runningTool =
ToolCall(toolName = "Bash", status = ToolCallStatus.Running, elapsedSeconds = 65)`, `isThinking = false`
— proving the label raises the arm on its own with no thinking flag set.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only.
- **Two string resources** from #407: `thread_thinking_label` ("Thinking…", visible) and
  `cd_thread_thinking` ("Agent is thinking", content description — mandatory per AC #5). Unlike
  `ConnectionBanner` (which uses Kotlin literals), this component uses `stringResource` — the content
  description must be a `cd_*` resource by the codebase a11y convention.
- **Two more (#803)**, both positional integer arguments, no daemon-authored text: `thread_thinking_progress_label`
  ("Thinking… ~%1$d tokens this step") and `cd_thread_thinking_progress` ("Claude is thinking, about
  %1$d tokens into its current reasoning step") — see § The progress reading.
- **Four more (#897)**, each taking the tool name as its first positional argument (a daemon-authored
  string, unlike every prior resource here) and, for the elapsed pair, the pre-formatted elapsed string as
  its second: `thread_tool_running_label` ("Running %1$s…"), `thread_tool_running_elapsed_label`
  ("Running %1$s… %2$s"), `cd_thread_tool_running` ("Claude is running %1$s"),
  `cd_thread_tool_running_elapsed` ("Claude is running %1$s, %2$s elapsed") — see § The running tool. The
  tool name is always a format argument substituted into a fixed local format string, never part of the
  format string itself.
- **Three more ([#1114](https://github.com/pyrycode/pyrycode-mobile/issues/1114))**, each a whole sibling
  string rather than the Claude string plus an agent-name argument: `cd_thread_thinking_progress_codex`,
  `cd_thread_tool_running_codex`, `cd_thread_tool_running_elapsed_codex` — "Codex" in place of "Claude",
  otherwise byte-identical to their Claude counterparts, which #1114 leaves unedited. Whole strings, not a
  shared format with a name placeholder, so the Claude strings stay byte-identical (no existing test or
  translation changes) and a translator can inflect each sentence per agent rather than around a slotted
  name. See [Compacting indicator § Configuration](compacting-indicator.md#configuration) and [API-retry
  indicator § Configuration](api-retry-indicator.md#configuration) for the other three.
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
  forbids. The operator is never trapped by it — the interrupt control and the composer stay live beside
  this slot regardless of what it shows (as, until [#883](../../specs/architecture/883-retire-literal-screen.md)
  retired it, did the stall promotion banner).
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
- **A short tool call may never show a time (#897, expected).** claude's `tool_progress` heartbeat arrives
  roughly every 30 seconds, so `Running Bash…` alone (no elapsed suffix) is the common case for a quick
  call, not a sign anything is missing.
- **The agent-naming rollout is partial by design (#1114, narrowed by #1112).** Of the five status-ladder
  arms, this one, `ApiRetryIndicator`, `CompactingIndicator` and (since #1112) `ResettingIndicator`'s
  `WrappingUp` reading take `agent` — only `TurnOutcomeIndicator`'s labels still say "Claude"
  unconditionally. #1114's verifier flagged the gap as a non-blocking NIT for whichever sibling ticket
  covered the rest of the ladder; #1112 closed `ResettingIndicator`'s share of it (see [Resetting indicator
  § The agent name](resetting-indicator.md#the-agent-name-1112)). `TurnOutcomeIndicator` remaining
  Claude-only is not a defect in this ticket's own six strings.
- **A very long or multi-line tool name can cut off its own elapsed reading (#897, open, not observed).**
  `maxLines = 1` + ellipsis bounds the row, but a name long enough to fill it pushes the appended elapsed
  text past the ellipsis with it. Desktop has the identical limitation. No tool name long enough to trigger
  this has been seen in practice; a fix (if ever needed) belongs to the Figma frame's eventual retune, not
  a client-side truncation rule invented here.
- **Coverage now spans component, rung 2, rung 3 and rung 4 for the running-tool label (#897, e2e #950).**
  `OpenToolCallTest` (pure, no Compose) proves the selector: no rows, only-closed rows, one running row,
  two running rows (the later one wins with its own reading even when the older one has one and the newer
  doesn't), and a later closed row not hiding an earlier still-running one. `RunningToolIndicatorTest`
  (`app/src/sharedTest/.../thread/`, Robolectric) drives `ThreadScreen` through open → progress → result
  and open → denial, proves a newer open call replaces an older one, proves the label is absent when
  `isBusy` is false even with a `Running` row, and proves it still loses to the token-reading label,
  compaction and the turn-outcome arm. #950 closed the rung-3/rung-4 gap: the rung-4 `tool` scenario
  (`DeterministicInteractiveStreamE2ETest`) now also asserts the label without an elapsed reading inside
  its existing held-open window, and a new `tool-progress` scenario proves the label adds claude's elapsed
  reading after a scripted `tool_progress` heartbeat and clears when the call's `tool_result` lands while
  the turn stays busy. On rung 3, `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`
  (`InteractiveStreamE2ETest`) holds a real tool call open on a permission prompt (the #849 lever) and
  proves the label without an elapsed reading against real claude; the elapsed half stays
  `@Ignore`-gated as `interactiveTurn_longRunningTool_statusAreaShowsElapsed` — claude's first heartbeat
  lands at ~30s on the one committed capture and cannot be held reliably, so only that half remains
  manual. See `docs/e2e-interactive-stream.md` § "What rung 3 is made of" and § "Scenarios (#454)" for
  both scenarios' mechanics.

## Related

- Ticket notes: [`../codebase/407.md`](../codebase/407.md) (this component) ·
  [`../codebase/406.md`](../codebase/406.md) (the data/ViewModel half it consumes) ·
  [`../codebase/803.md`](../codebase/803.md) (the progress-reading extension). #897 and #1114 postdate the
  2026-09-05 codebase-archive freeze and have no per-ticket note.
- Specs: `docs/specs/architecture/407-thinking-indicator-thread-foot.md` ·
  `docs/specs/architecture/803-thinking-progress-status-render.md` ·
  `docs/specs/architecture/897-running-tool-status-label.md` ·
  `docs/specs/architecture/1114-agent-status-screen-reader-labels.md` (§ The agent name).
- Upstream signals: [Turn-state thinking flag](turn-state-thinking-flag.md) — `ThreadViewModel.isThinking`,
  the `turn_state` → flag reduction that governs visibility — and
  [Thinking-progress state](thinking-progress-state.md) — `ThreadViewModel.thinkingProgress` /
  `observeThinkingProgress` (#801), the `thinking_progress` decode this component renders as the optional
  `progress` param. The running-tool signal has no such upstream: `openToolCall` (#897) derives it
  in-screen from `state.items` and `isBusy`, both already flowing to `ThreadScreen` — see § The running
  tool and [`Live tool call`](live-tool-call.md) for where `ToolCall.status`/`elapsedSeconds` themselves
  come from.
- Host: [Thread screen](thread-screen.md) — threads `isThinking` and (#803) `thinkingProgress` as flat
  sibling parameters, derives (#897) `runningTool` locally via `openToolCall`, and arbitrates the status
  slot (the composer's `ThreadStatusArea` since [#643](../codebase/643.md); the foot of the content
  `Column` before it) across this component, [`ApiRetryIndicator`](api-retry-indicator.md),
  [`ResettingIndicator`](resetting-indicator.md) (#872), [`CompactingIndicator`](compacting-indicator.md),
  and [`TurnOutcomeIndicator`](turn-outcome-indicator.md) ([#805](https://github.com/pyrycode/pyrycode-mobile/issues/805)).
  [`UsageLimitIndicator`](usage-limit-indicator.md) ([#804](https://github.com/pyrycode/pyrycode-mobile/issues/804))
  shared this slot from #804 to #1002; its reading now draws as a pill in [Thread top
  overlay](thread-top-overlay.md), pinned over the message area rather than sharing the status slot, so it
  never again masks this arm.
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
  (`LinearProgressIndicator`), `LiteralScreenSurface` (`CircularProgressIndicator`). The single-ellipsized-line
  treatment for the tool name (#897) mirrors [`ToolCallRow`](tool-call-row.md#what-it-does) and desktop's
  `toolWorkingCopy` label (`docs/knowledge/features/conversation-shell-working-indicator.md` in the desktop
  repo).
- Parent: split from [#386](https://github.com/pyrycode/pyrycode-mobile/issues/386); grandparent
  [#368](https://github.com/pyrycode/pyrycode-mobile/issues/368). #803 split from
  [#653](https://github.com/pyrycode/pyrycode-mobile/issues/653), sibling data slice
  [#801](../codebase/801.md) (PR #809). #897 split from
  [#658](https://github.com/pyrycode/pyrycode-mobile/issues/658); its e2e follow-up shipped as
  [#950](https://github.com/pyrycode/pyrycode-mobile/issues/950) (see § Edge cases / limitations above).
- Server SSOT: pyrycode#607 (`turn_state` wire), #616 (capability-gated fan-out), ADR 025 § Phase 2
  structured streaming, EPIC pyrycode#596; pyrycode#1386 and `docs/protocol-mobile.md § thinking_progress`
  for the #803 reading. #897 renders only client-observed `ToolCall` state and adds no wire dependency.
