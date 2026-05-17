# Thread status row

Always-visible single-row status surface at the bottom of [`ThreadScreen`](thread-screen.md), stacked above the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. Landed in [#145](../codebase/145.md). Shows `Model · effort · NN% used ▴` with threshold-driven coloring on the trailing `NN% used` segment; tapping anywhere on the row invokes a hoisted `onExpandClick` callback. The Status Sheet that this row taps into lives in [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146) — until that lands, `MainActivity` binds `onExpandClick = {}`.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt`). Figma reference: subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) of the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Renders three pieces of information the user wants at a glance while talking to the agent — the current model (`Opus 4.7`), the effort level (`high`), and the proportion of the context window consumed (`73% used`) — composed into a single monospaced text run with a trailing `▴` icon. The full row is `clickable`; tapping anywhere invokes `onExpandClick`. The token-percent segment changes color across three threshold bands so the user notices the context window filling up at a glance: neutral under 50%, [warning](warning-color.md) between 50% and 94%, error at 95% or above.

The values are populated by `ThreadViewModel` inside its main `combine(...)` block. As of [#253](../codebase/253.md), `model` is **no longer a stub** — `ThreadUiState.selectedModel: Model` is sourced from `appPreferences.defaultModel` (with an in-memory per-conversation override on `MutableStateFlow<Model?>`) via a pre-combined `selectedModelFlow`, and the row reads the display label via `state.selectedModel.label()` at the [`ThreadScreen`](thread-screen.md) callsite. `effort` and `tokenPercent` are still companion-object constants (`STUB_EFFORT = "high"`, `STUB_TOKEN_PERCENT = 73`); Phase 4 swaps both for reads off a backend `AgentStatus` flow. The row's own parameter shape (`model: String`, `effort: String`, `tokenPercent: Int`) is **preserved across #253** — typing the row's parameter list against `Model` was deliberately rejected so the row stays primitive at the leaf, with the type information one layer up at the screen-VM boundary.

## Shape

```kotlin
@Composable
fun ThreadStatusRow(
    model: String,
    effort: String,
    tokenPercent: Int,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Pure stateless presentation — no internal `remember`, no `LaunchedEffect`, no overload pair. The three primitives are the entire input contract; the spec deliberately did not wrap them in `enum class Effort` or `value class TokenPercent` so the Phase-4 swap is shape-stable. The row recomposes when any of `model` / `effort` / `tokenPercent` changes; all three are Compose-stable types.

## How it works

### Single `Row` with three text spans + trailing icon

```kotlin
Row(
    modifier = modifier
        .fillMaxWidth()
        .clickable(onClick = onExpandClick)
        .semantics { role = Role.Button }
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .alpha(0.85f),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = annotated,
        modifier = Modifier.weight(1f),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    Spacer(modifier = Modifier.width(4.dp))
    Icon(
        imageVector = Icons.Filled.KeyboardArrowUp,
        contentDescription = stringResource(R.string.cd_thread_status_expand),
        tint = onSurfaceVariant,
        modifier = Modifier.size(14.dp),
    )
}
```

The `clickable` rides on the whole `Row` — the icon is **not** wrapped in its own `IconButton`. The `semantics { role = Role.Button }` lands TalkBack on the "Button" affordance for the entire row, matching the surrounding accessibility profile ([`WorkspaceChip`](workspace-chip.md), the title `Text` in [`ThreadTopAppBar`](thread-screen.md#threadtopappbar--figma-168-chrome)).

### `AnnotatedString` for three differently-colored segments

```kotlin
val annotated: AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = onSurface))         { append(model) }
    withStyle(SpanStyle(color = onSurfaceVariant))  { append(" · $effort · ") }
    withStyle(SpanStyle(color = percentColor))      { append("$tokenPercent% used") }
}
```

Three spans inside one `Text` keeps the row a single layout node and gives glyph-perfect placement of the `·` separators. The alternative — three sibling `Text` composables in a `Row` — would have put per-`Text` horizontal padding between the cells (the `·` would be inter-`Text` space, not glyphs) and variable widths per cell. `buildAnnotatedString` is the right primitive here.

The model name renders in `colorScheme.onSurface` (full-emphasis text color). The middle separator-and-effort segment renders in `colorScheme.onSurfaceVariant` (de-emphasised). The trailing token-percent segment renders in the threshold-driven color from the helper below.

### `tokenPercentColor` — threshold helper

```kotlin
@Composable
private fun tokenPercentColor(tokenPercent: Int): Color {
    val clamped = tokenPercent.coerceIn(0, 100)
    return when {
        clamped < 50 -> MaterialTheme.colorScheme.onSurfaceVariant
        clamped < 95 -> MaterialTheme.colorScheme.warning
        else -> MaterialTheme.colorScheme.error
    }
}
```

Three bands: `[0, 50)` → `onSurfaceVariant` (neutral, same as the middle separator); `[50, 95)` → [`warning`](warning-color.md) (the M3 slot landed in [#119](../codebase/119.md)); `[95, 100]` → `error`. The clamp is defensive over the Phase-4 backend swap — a real `AgentStatus.tokenPercent` could conceivably overshoot 100 during a usage spike, and clamping in the helper means the threshold rules never branch incorrectly.

`< 95` (not `<= 95`) reflects the AC's `95%+` boundary as "95 itself is in the error band, not the warning band". The four preview values (20, 60, 88, 97) sweep all three bands and one within-band intermediate.

### `Modifier.alpha(0.85f)` at the `Row` level

The Figma `16:58` node carries `opacity-85` on the whole status-row layout. Applying `Modifier.alpha(0.85f)` to the `Row` covers both the `Text` and the trailing `Icon` in one place. The alternative — composing alpha into each `SpanStyle.color = onSurface.copy(alpha = 0.85f)` — works for the text portion but leaves the icon rendering at full opacity, breaking the visual cluster. `Modifier.alpha(...)` runs in the draw layer and uniformly applies to every descendant of the `Row`.

### Trailing icon — vector, not Unicode glyph

`Icons.Filled.KeyboardArrowUp` at 14.dp tinted `onSurfaceVariant`. The spec listed `▴` (Unicode glyph appended to the `AnnotatedString`) as a parallel option; the vector won on two axes:

- **Accessibility.** `contentDescription = stringResource(R.string.cd_thread_status_expand)` gives TalkBack a clean "Expand status details" label. A Unicode glyph inside the `AnnotatedString` has no slot for a content description.
- **Tint stability.** The glyph would inherit the trailing-span color (the threshold color — wrong; the icon should stay `onSurfaceVariant` regardless of token-%). The vector tint is explicit.

The 14.dp size was tuned to read at the same baseline as the 12.sp text without dominating; 16.dp felt heavy.

## Wiring

### `ThreadScreen` mount

```kotlin
bottomBar = {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThreadStatusRow(
            model = state.selectedModel.label(),   // String → enum-derived in #253
            effort = state.effort,
            tokenPercent = state.tokenPercent,
            onExpandClick = onExpandClick,
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Pre-#145 the `bottomBar` slot held only `ThreadInputBar(onSend = onSendMessage)`. Post-#145 a `Column` wraps both children inside the same `bottomBar` slot. Post-[#253](../codebase/253.md) the `model =` argument is sourced from the typed `state.selectedModel: Model` via a new [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `data/preferences/Model.kt` — the row's own `model: String` parameter is unchanged. Crucially, `Modifier.imePadding()` already lives **inside** `ThreadInputBar` on its own outer `Column` (since [#188](../codebase/188.md)), not on the `Scaffold` bottomBar — so only the input bar lifts when the soft keyboard opens, and the status row stays visually anchored above it. No new `imePadding` is added on the outer wrapper.

`ThreadScreen` grows one new defaulted parameter `onExpandClick: () -> Unit = {}` matching the existing optional-callback style. The signature stays flat-callback rather than folding into a sealed `ThreadEvent` — see the [thread-screen shape note](thread-screen.md#shape) for the deferred fold.

### `MainActivity` destination

```kotlin
// MainActivity.kt:209-210 (post-#145)
// TODO(#146): open Status Sheet
onExpandClick = {},
```

Placeholder until [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146) wires the Status Sheet. The `TODO(#146)` marker is grep-able.

### `ThreadUiState` fields

```kotlin
data class ThreadUiState(
    // ...seven pre-existing fields unchanged...
    val selectedModel: Model = Model.OPUS_4_7,   // String → Model in #253
    val effort: String = "high",
    val tokenPercent: Int = 0,
)
```

The defaults serve a dual purpose:

1. The `stateIn(initialValue = ThreadUiState(conversationId, displayName))` literal at `ThreadViewModel.kt` keeps compiling unchanged.
2. The `selectedModel` / `effort` defaults match the rendered stub so the pre-subscription initial frame already paints the right strings — only `tokenPercent` differs between the initial frame (`0`) and post-subscription (`73`). That asymmetry is deliberate and pinned by the two test methods below.

### `ThreadViewModel` populates inside `combine`

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
    private val connectionStateSource: ConnectionStateSource,
    private val appPreferences: AppPreferences,    // new in #253
) : ViewModel() {
    private val modelOverride = MutableStateFlow<Model?>(null)         // new in #253

    private val selectedModelFlow: Flow<Model> =                       // new in #253
        combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }

    val state: StateFlow<ThreadUiState> = combine(
        repository.observeConversations(ConversationFilter.All),
        repository.observeMessages(conversationId),
        pendingWorkspacePicker,
        selectedModelFlow,                                             // 4th source added in #253
    ) { conversations, items, pickerVisible, selectedModel ->
        ThreadUiState(
            // ...existing fields...
            selectedModel = selectedModel,                             // was `model = STUB_MODEL` pre-#253
            effort = STUB_EFFORT,
            tokenPercent = STUB_TOKEN_PERCENT,
        )
    }.stateIn(...)

    fun onModelSelected(model: Model) {                                // new in #253 — synchronous, does not write prefs
        modelOverride.value = model
    }

    companion object {
        // Phase 4 swap point: replace with backend AgentStatus flow.
        // STUB_MODEL was here pre-#253; deleted when model became a typed prefs-sourced flow.
        private const val STUB_EFFORT = "high"
        private const val STUB_TOKEN_PERCENT = 73
    }
}
```

Phase-4 swap for the remaining two stubs is two find-and-replace steps: delete the companion, swap each `STUB_*` reference inside `combine` for a `agentStatus.field` read off a new flow arm. The `selectedModel` arm is already wired to a flow — Phase 4 may extend it with a backend-sourced "current model" override that sits **above** `modelOverride` (e.g. if the agent backend reports a model switch), or persist `modelOverride` itself; either is a non-breaking widening of the same flow shape.

The stub `tokenPercent = 73` lands inside the warning band on purpose — the developer and the reviewer see the threshold-driven warning color in the running app, not just in previews. The `selectedModel` default (`Model.OPUS_4_7`) renders `"Opus 4.7"` via `Model.label()`, identical to the pre-#253 string stub.

## State + concurrency

No new flows, no new dispatchers. The three `ThreadUiState` fields are populated synchronously inside the existing `combine(...)` block on `viewModelScope`. `WhileSubscribed(5_000)` lifetime policy is preserved. `ThreadStatusRow` is pure presentation — no `LaunchedEffect`, no `rememberCoroutineScope`.

The row recomposes only when one of `model` / `effort` / `tokenPercent` changes on `ThreadUiState`. All three are Compose-stable types (`String`, `String`, `Int`), so Compose skips recomposition when the values are unchanged across emissions.

## Error handling

No new failure modes. Empty `model` / `effort` strings produce a visually awkward but non-crashing row (`" ·  · 73% used"`); validation is **not** added here — Phase 4's real backend integration owns that contract. `tokenPercent` out-of-range is clamped to `[0, 100]` inside `tokenPercentColor` before the threshold compare.

## Testing

Two unit tests in `ThreadViewModelTest.kt` (added in #145, renamed in [#253](../codebase/253.md) `*Stub*` → `*Default*` once `model` stopped being a stub):

1. **`state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults`** — wrapped in `runTest { }` post-#253 for the prefs-IO context. Constructs the VM, reads `vm.state.value` without a launched collector. Asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 0`. Pins the data-class default contract — the pre-subscription initial frame that `stateIn(initialValue = …)` publishes.
2. **`state_postSubscription_emitsDefaultModelEffortAndTokenPercent`** — `runTest { launch collector; advanceUntilIdle(); ... collector.cancel() }`. Asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 73`. Pins the VM's explicit population inside `combine` — this is the assertion that fails if the Phase-4 swap drops `effort` or `tokenPercent`, or if the prefs-defaulted `selectedModel` stops propagating through `selectedModelFlow`.

The two-test split is deliberate: the initial frame and the post-subscription frame differ on `tokenPercent` (0 vs 73) by design. Covering both pins the data-class defaults *and* the combine-block population separately. The `selectedModel` plumbing is covered separately by four additional tests landed in [#253](../codebase/253.md) (default-tracking, default-reemission, override-without-mutation, override-stickiness) — see the [thread-screen test list](thread-screen.md#testing) for the full inventory.

No Compose UI tests. `ThreadStatusRow` is rendered exclusively through `ThreadScreen`'s `bottomBar`, and the project currently has no `ThreadScreen` compose tests under `app/src/androidTest/`. The eight previews are the visual gate.

## Previews

Eight `@Preview`s in `ThreadStatusRow.kt`, four light × four dark × four token-% values (20, 60, 88, 97), all `showBackground = true, widthDp = 412`:

- `StatusRow — Light, 20%` (`darkTheme = false`, neutral `onSurfaceVariant` band)
- `StatusRow — Light, 60%` (warning band)
- `StatusRow — Light, 88%` (within-band warning, visually halfway)
- `StatusRow — Light, 97%` (error band — above the `>= 95` boundary)
- Four matching `StatusRow — Dark, NN%` previews under `darkTheme = true`

The sweep verifies all three threshold buckets (`< 50`, `50..94`, `>= 95`) plus a within-band intermediate (88) under both themes — sixteen color × token combinations would have been overkill but eight is the canonical preview density for a threshold-driven primitive.

The four `ThreadScreen` previews (`ThreadScreenLightPreview`, `ThreadScreenDarkPreview`, `ThreadScreenAboveDelimiterDimLightPreview`, `ThreadScreenAboveDelimiterDimDarkPreview`) were updated post-#145 to set `tokenPercent = 73` on their `ThreadUiState` literals so the row is visible (in the warning band) in the screen-level previews too.

Naming convention `<Component> — <Theme>, <Variant>` matches the [thread-input-bar](thread-input-bar.md#previews) shape established in #188 (em-dash separator, `<Theme>, <Variant>` two-axis suffix).

## Edge cases / limitations

- **`onExpandClick = {}` is a placeholder at the destination.** The Status Sheet itself is the sibling [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146); until that ticket lands, tapping the row fires the ripple and does nothing observable. The `TODO(#146)` marker in `MainActivity.kt:209` is the wiring breadcrumb.
- **`effort` and `tokenPercent` are still stub-populated in the VM.** Phase 4 replaces the remaining two `STUB_*` companion-object constants with reads off a backend `AgentStatus` flow. The row's own parameter shape (`String`, `String`, `Int`) is the stable contract — no `enum class Effort` or `value class TokenPercent` wrapper here. Validation of out-of-range or empty values is Phase 4's responsibility. As of [#253](../codebase/253.md), `model` is no longer in this bucket — `ThreadUiState.selectedModel: Model` is sourced from `appPreferences.defaultModel` with an in-memory per-conversation override, and the screen derives the label string at the boundary.
- **One-frame visual asymmetry between initial frame and post-subscription frame.** `tokenPercent` defaults to `0` on `ThreadUiState` (rendered in the under-50% neutral color) and flips to `73` (warning band) on the `combine`'s first emission. The fake's `combine` upstream emits synchronously on first subscription, so the initial frame is invisible in the running app — but the asymmetry is pinned by the two test methods on purpose, so a reviewer doesn't accidentally "fix" the data-class default to `73`. As of [#253](../codebase/253.md) the `selectedModel` axis is symmetric — both frames render `Model.OPUS_4_7` (data-class default) which `Model.label()` maps to `"Opus 4.7"`, identical to the pre-#253 stub.
- **No animation on threshold transitions.** When `tokenPercent` crosses 50 or 95, the percent-segment color swaps instantly. An `animateColorAsState` per-color fade is a follow-up if designer signs off on a duration.
- **Row is always visible — no `AnimatedVisibility`.** The row is part of the steady-state chrome; it does not appear/disappear under any state. Compare with [`EmptyThreadState`](empty-thread-state.md) and [`WorkspaceChip`](workspace-chip.md) which gate on `!hasMessages` and `!isPromoted && !hasMessages` respectively.
- **No interaction with `WorkspaceChip` or `EmptyThreadState`.** All three live in different slots of the `Scaffold` body / `bottomBar`. The status row is the constant cap on the composer cluster regardless of which empty-state branch the body renders.

## Related

- Ticket notes: [`../codebase/145.md`](../codebase/145.md) (this implementation), [`../codebase/253.md`](../codebase/253.md) (model rewired from `STUB_MODEL` constant to typed `Model` enum sourced from `AppPreferences.defaultModel` with per-conversation override; row's `model: String` parameter shape preserved)
- Spec: `docs/specs/architecture/145-thread-status-row.md`
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into; pre-#145 had only `ThreadInputBar` in `bottomBar`)
- Sibling: [Thread input bar](thread-input-bar.md) (the composer this row stacks above, inside the same `Column` in `bottomBar`)
- Upstream: [Warning color slot](warning-color.md) (the `colorScheme.warning` slot the 50–94% band consumes, landed in [#119](../codebase/119.md))
- Downstream: [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146) wires the Status Sheet that `onExpandClick` will eventually open
- Figma: [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically); parent [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)
