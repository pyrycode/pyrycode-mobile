# Resetting indicator — `ResettingIndicator`

The **UI half of the reset-phase signal** ([#872](https://github.com/pyrycode/pyrycode-mobile/issues/872),
split from #630): a stateless composable that, while the open conversation's Reset session is running,
shows which phase it is in — writing a handoff note, or restarting with the handoff note's outcome —
instead of leaving the thread paused with no explanation for the whole wrap-up turn and respawn.

The signal it renders is the **data half** — [`ThreadViewModel.resetting`](resetting-state.md)
([#871](https://github.com/pyrycode/pyrycode-mobile/issues/871)). This component adds **no data
access**: it receives the already-decoded `ResetStatus?` as a hoisted value, the same shape
[`CompactingIndicator`](compacting-indicator.md) receives `isCompacting`. It clones that sibling's M3
row shape verbatim; the only new decision is the label mapping, because — unlike `compacting`'s bare
`Boolean` — a `ResetStatus` carries two independent enum fields that must resolve to one label.

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `ResettingIndicator.kt`.

## Shape

```kotlin
@Composable
fun ResettingIndicator(
    status: ResetStatus?,
    modifier: Modifier = Modifier,
)
```

Pure function of `status`: no `ViewModel` reference, no flow collection, no `remember`, no
`LaunchedEffect`, no `ThreadUiState` field.

## What it does

- **`if (status == null) return`** — emits nothing while no reset is running, the same early-return
  idiom as `CompactingIndicator`/`ThinkingIndicator`. `ThreadStatusArea`'s `when` normally keeps it from
  being called at all in that state; the early return keeps the composable total anyway.
- Renders the identical `Row` shape as `CompactingIndicator` (`fillMaxWidth`, 16dp horizontal / 8dp
  vertical padding, `CenterVertically`, 8dp spacing) containing a small indeterminate
  `CircularProgressIndicator` (`size(16.dp)`, `strokeWidth = 2.dp`) and a `Text` (`bodySmall` /
  `onSurfaceVariant`) — **indeterminate, deliberately**: neither phase streams a counter, percent, or ETA
  on the wire, so a determinate bar would invent data.
- **`resettingLabelRes(status): @StringRes Int`** (`internal`, unit-tested directly) is total over both
  enums, not a partial mapping of the three documented wire pairings:

  | `phase` | `handoff` | Resource | Text |
  |---|---|---|---|
  | `WrappingUp` | any | `thread_resetting_wrapping_up` | "Claude is writing a handoff note for the next session" |
  | `Restarting` | `Written` | `thread_resetting_restarting_written` | "Restarting · handoff note saved" |
  | `Restarting` | `Skipped` | `thread_resetting_restarting_skipped` | "Restarting without a handoff note" |
  | `Restarting` | `Pending` | `thread_resetting_restarting` | "Restarting" |

  `WrappingUp` ignores the handoff field regardless of its value — the outcome is only meaningful once
  restarting. `Restarting`+`Pending` is not a documented wire combination (the wire resolves the outcome
  in the same frame that raises `restarting`), but the mapping stays total rather than partial so a future
  wire change can't produce an unrenderable `ResetStatus`; that arm claims no outcome rather than guessing
  one.
- **Accessibility** — `Modifier.semantics(mergeDescendants = true) { contentDescription = label }` on the
  `Row`, sourced from the **same** `label` the `Text` renders (the [`UsageLimitIndicator`](usage-limit-indicator.md)
  precedent: one wording source, not a separate `cd_*` string).

## Placement in the thread

Joins [`ThreadScreen`](thread-screen.md)'s single mutually-exclusive status slot (`ThreadStatusArea`,
`ThreadScreen.kt`), ranked **between** api-retry and compaction — the full ladder, top wins:

```kotlin
when {
    apiRetry != ApiRetryStatus.NotRetrying -> ApiRetryIndicator(status = apiRetry, modifier = slot)
    resetting != null                      -> ResettingIndicator(status = resetting, modifier = slot)
    isCompacting                           -> CompactingIndicator(isCompacting = true, modifier = slot)
    turnOutcome != null                    -> TurnOutcomeIndicator(report = turnOutcome, modifier = slot)
    else                                   -> ThinkingIndicator(isThinking = isThinking, modifier = slot, progress = thinkingProgress, runningTool = runningTool)
}
```

**[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) removed the usage-limit arm that used to
sit directly above this one.** From #872 to #1002 the ladder read api-retry → usage limit → resetting →
compaction → turn outcome → thinking; claude's usage-limit report and the pairing-error notice now draw as
pills in [`ThreadTopOverlay`](thread-top-overlay.md), pinned over the message area instead of sharing this
slot, because a live `allowed_warning` reading was masking every arm below it (see [Thread top overlay §
Why this moved](thread-top-overlay.md#why-this-moved-1002)). This ladder is turn status only now:
api-retry → resetting → compaction → turn outcome → thinking/running tool.

Two things about where this arm sits, both from the original #872 ticket and pinned by
`resetting_winsOverThinkingAndCompaction` / `apiRetry_winsOverResetting`:

- **Below api-retry**, the one remaining "something may be wrong" signal, so a running reset never hides
  it.
- **Above compaction, turn outcome and thinking** — the wrap-up is itself a claude turn. Without this
  ordering, the reset the user started would read as generic thinking, or (since a wrap-up turn can
  legitimately trigger auto-compaction) as a compaction happening inside it. It also outranks a turn
  outcome left over from the turn immediately before the reset.

See [Compacting indicator § Placement](compacting-indicator.md#placement-in-the-thread) for the rest of the
ladder's history and rationale, and [Resetting state § Edge cases](resetting-state.md#edge-cases--limitations)
for why the arm is deliberately undefended against a daemon that raises but never lowers it: a
`session_transition` or reconnect always clears the upstream reading, and the arm blocks no input —
the composer and the interrupt control stay live throughout, the same posture `CompactingIndicator`
ships.

## Wiring

Threaded exactly like `isCompacting` — a **defaulted hoisted value**, sibling to `usageLimit`, **not** a
`ThreadUiState` field:

- **`ThreadViewModel`** exposes `resetting: StateFlow<ResetStatus?>` beside `isCompacting` / `usageLimit`,
  a verbatim clone of `isCompacting`'s hoist — sourced from the **already-injected** repository, no
  constructor / DI / interface change:

  ```kotlin
  val resetting: StateFlow<ResetStatus?> =
      repository
          .observeResetting(conversationId)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = null,
          )
  ```

  No extra operator: [`observeResetting`](resetting-state.md) already dedups (`distinctUntilChanged` in
  the projection), and a phase change is a distinct `ResetStatus`, so it reaches the screen without one.
  Scoping to the open conversation and its host comes from the VM's own `conversationId` on the
  host-bound repository it already holds — no new scoping logic, proven by
  `resetting_observesOnlyOwnConversationId` and the scripted other-conversation case.
- **`ThreadScreen`** gains `resetting: ResetStatus? = null` in its trailing-defaults block, beside
  `usageLimit`. Defaulting it keeps every pre-#872 `ThreadScreen(` call site and preview compiling
  unchanged; only `MainActivity` and the scripted harness gain an argument.
- **`MainActivity`** collects it via `vm.resetting.collectAsStateWithLifecycle()` beside `usageLimit` /
  `isCompacting`, and passes it through — two lines, mirroring the `isCompacting` wiring.

## Recomposition / stability

- `ResetStatus` is a `data class` of two enums — a stable type — so the new `ThreadScreen` parameter adds
  no recomposition instability.
- No internal mutable state, no `remember`, no side effect, no coroutine — pure projection of `status` to
  a rendered (or absent) row.
- `ThreadScreen` gains one param and a `when` arm at an existing single-child slot — no impact on the
  `LazyColumn`'s item recomposition.

## Preview

Two `@Preview`s, one per theme (`widthDp = 412`, dark adds `uiMode = Configuration.UI_MODE_NIGHT_YES`),
each stacking all three reachable rows (`WrappingUp`, `Restarting`/`Written`, `Restarting`/`Skipped`) in
one `Column` — unlike `CompactingIndicator`'s single-case preview, because this component has more than
one rendered case.

## Configuration

- **No new dependencies.** Existing Compose Material 3 imports only. No `gradle/libs.versions.toml`
  edits.
- **Four new string resources** in `res/values/strings.xml`, appended beside the compacting and
  usage-limit strings. None takes a format argument — nothing daemon-supplied reaches any of them, and
  each is both the visible label and (via the merged `semantics`) the content description:

  | Name | Value |
  |---|---|
  | `thread_resetting_wrapping_up` | `Claude is writing a handoff note for the next session` |
  | `thread_resetting_restarting_written` | `Restarting · handoff note saved` |
  | `thread_resetting_restarting_skipped` | `Restarting without a handoff note` |
  | `thread_resetting_restarting` | `Restarting` |

## Edge cases / limitations

- **Visual is design-owed**, the same gap already recorded for
  [`ThinkingIndicator`](thinking-indicator.md), [`ApiRetryIndicator`](api-retry-indicator.md) and
  [`CompactingIndicator`](compacting-indicator.md#edge-cases--limitations): Figma `111:3525` draws one
  generic status row ("Thinking..." in `Schemes/Primary`), not a resetting-specific treatment, so the
  arm follows the shipped M3 idiom (`onSurfaceVariant`, not the Figma `Primary` scheme) until a
  resetting-specific frame lands.
- **No animation.** The `when`-branch swap between phases, and into/out of this arm, is instant, matching
  every sibling status affordance.
- **A stuck rising edge renders an indefinite status** if a daemon sends one and never the falling one —
  deliberately undefended, the same posture [`resetting-state.md`](resetting-state.md#edge-cases--limitations)
  and [`compacting-state.md`](compacting-state.md#edge-cases--limitations) already accept at the data
  layer. The render-side mitigation is structural, not temporal: the composer and the interrupt control
  both stay live regardless, so the arm can mask only compaction, turn outcome and thinking (the three
  arms below it), never block interaction.
- **Wrapping-up has live proof; restarting stays rung-2 only.**
  [#965](https://github.com/pyrycode/pyrycode-mobile/issues/965) extended the curated `LIVE=1`
  `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` to wait for the wrapping-up label live,
  with the delimiter's explanation still absent, before every resetting label clears — causally held by
  the daemon's own real-claude wrap-up turn, not raced on timing. The **restarting** phase spans only the
  kill and respawn in the daemon's `startFreshRunner`, with no lever to hold it open against a real
  daemon, so it stays proven only by `ScriptedResettingTest`, which drives the three-frame sequence
  (`wrapping_up`/`pending` → `restarting`/`written` → falling edge), the `skipped` outcome, a
  `session_transition` clear, the other-conversation case, and both ladder edges (wins over compaction and
  thinking; loses to api-retry) through the real `#871` repository fold. **Negative scoping assertions
  need an ordered barrier, not a plain "assert absent" right after the push**: a `waitUntil`/`assertDoesNotExist`
  run immediately after `pushResetting(targetConversationId = "c-other")` can pass even when the scoping is
  broken, because the single inbound collector may not have processed that frame yet. `resetElsewhere_neverShowsHere`
  therefore pushes the foreign reset first and then this conversation's own `compacting` frame as a
  barrier — the collector handles frames in order, so once compaction (which ranks below resetting) is
  showing, the foreign reset must already have been processed and correctly not surfaced here. The same
  hazard applies to any future scripted test asserting a cross-conversation negative on a shared ordered
  collector.

## Related

- Ticket: [#872](https://github.com/pyrycode/pyrycode-mobile/issues/872) (this component, split from
  #630) · [#871](https://github.com/pyrycode/pyrycode-mobile/issues/871) (the data/repository half it
  consumes).
- Spec: `docs/specs/architecture/872-resetting-indicator.md`.
- Upstream signal: [Resetting state](resetting-state.md) — `ThreadViewModel.resetting` /
  `observeResetting`, the `resetting` decode this component renders.
- Host: [Thread screen](thread-screen.md) — threads `resetting` as another flat sibling parameter and
  arbitrates the status slot across the six-arm ladder.
- Idioms cloned: [Compacting indicator](compacting-indicator.md) (the direct clone — `Row` shape,
  early-return, sibling-`StateFlow` hoist, defaulted-hoisted-parameter, merged `semantics`, design-owed M3
  default, light/dark previews); [Usage-limit indicator](usage-limit-indicator.md) (the merged
  `contentDescription`-equals-label idiom — its own arm sat immediately above this one in the ladder from
  #872 to #1002, when it moved to [Thread top overlay](thread-top-overlay.md)).
- Server SSOT: pyrycode#2478, `docs/protocol-mobile.md § resetting`.
