# Thread status row

Always-visible single-row status surface at the bottom of [`ThreadScreen`](thread-screen.md), stacked above the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. Landed in [#145](../codebase/145.md). Shows `Model · effort · NN% used ▴` with threshold-driven coloring on the trailing `NN% used` segment; tapping anywhere on the row invokes a hoisted `onExpandClick` callback. Post-[#254](../codebase/254.md) the row's `onExpandClick` is bound by `ThreadScreen` to a screen-internal `{ sheetVisible = true }` lambda that opens the [`StatusSheet`](status-sheet.md) — the public `onExpandClick: () -> Unit` parameter on `ThreadScreen` was deleted at the same time (no remaining external consumer; the four previews never passed it).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt`). Figma reference: subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) of the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Renders three pieces of information the user wants at a glance while talking to the agent — the current model (`Opus 4.7`), the effort level (`high`), and the proportion of the context window consumed (`73% used`) — composed into a single monospaced text run with a trailing `▴` icon. The full row is `clickable`; tapping anywhere invokes `onExpandClick`. The token-percent segment changes color across three threshold bands so the user notices the context window filling up at a glance: neutral under 50%, [warning](warning-color.md) between 50% and 94%, error at 95% or above.

The values are populated by `ThreadViewModel` inside its main `combine(...)` block. As of [#253](../codebase/253.md), `model` is **no longer a stub** — `ThreadUiState.selectedModel: Model` is sourced from `appPreferences.defaultModel` (with an in-memory per-conversation override on `MutableStateFlow<Model?>`) via a pre-combined `selectedModelFlow`, and the row reads the display label via `state.selectedModel.label()` at the [`ThreadScreen`](thread-screen.md) callsite. As of [#229](../codebase/229.md), `effort` is **no longer a stub either** — `ThreadUiState.selectedEffort: Effort` is sourced from `appPreferences.defaultEffort` (with an in-memory per-conversation override on `MutableStateFlow<Effort?>`) via a pre-combined `selectedEffortFlow`, and the row reads the display label via `state.selectedEffort.label()` at the [`ThreadScreen`](thread-screen.md) callsite (using the public `Effort.label()` extension widened from `internal` in the same slice). Only `tokenPercent` remains a companion-object constant (`STUB_TOKEN_PERCENT = 73`); Phase 4 swaps it for a read off a backend `AgentStatus` flow. The row's own parameter shape (`model: String`, `effort: String`, `tokenPercent: Int`) is **preserved across both #253 and #229** — typing the row's parameter list against `Model` / `Effort` was deliberately rejected so the row stays primitive at the leaf, with the type information one layer up at the screen-VM boundary.

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
            model = state.selectedModel.label(),         // String → enum-derived in #253
            effort = state.selectedEffort.label(),       // String → enum-derived in #229
            tokenPercent = state.tokenPercent,
            onExpandClick = { sheetVisible = true },     // wired internally in #254
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Pre-#145 the `bottomBar` slot held only `ThreadInputBar(onSend = onSendMessage)`. Post-#145 a `Column` wraps both children inside the same `bottomBar` slot. Post-[#253](../codebase/253.md) the `model =` argument is sourced from the typed `state.selectedModel: Model` via a new [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `data/preferences/Model.kt`; post-[#229](../codebase/229.md) the `effort =` argument is similarly sourced from `state.selectedEffort: Effort` via the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `ui/settings/EffortPickerDialog.kt:77` (widened from `internal` to public in #229 when the StatusSheet became the second consumer alongside the [#233](../codebase/233.md) `EffortPickerDialog`). The row's own `model: String` / `effort: String` parameters are unchanged. Post-[#254](../codebase/254.md) the `onExpandClick` argument inside `bottomBar` is wired to an internal `{ sheetVisible = true }` lambda that flips a `var sheetVisible by rememberSaveable { mutableStateOf(false) }` flag hoisted at the top of the `ThreadScreen` body — the row stays a stateless surface; the screen owns sheet visibility. Crucially, `Modifier.imePadding()` already lives **inside** `ThreadInputBar` on its own outer `Column` (since [#188](../codebase/188.md)), not on the `Scaffold` bottomBar — so only the input bar lifts when the soft keyboard opens, and the status row stays visually anchored above it. No new `imePadding` is added on the outer wrapper.

In #145, `ThreadScreen` grew one new defaulted parameter `onExpandClick: () -> Unit = {}` to plumb the row's tap up to the host. In [#254](../codebase/254.md), that parameter was **deleted** — `ThreadScreen` owns the trigger via an internal lambda passed straight to the row, and a new `onModelSelected: (Model) -> Unit = {}` parameter took its slot to forward the sheet's radio-row taps. [#229](../codebase/229.md) appended two more sheet callbacks (`onEffortSelected: (Effort) -> Unit = {}` + `onYoloToggled: (Boolean) -> Unit = {}`) alongside `onModelSelected`. The signature stays flat-callback rather than folding into a sealed `ThreadEvent` — see the [thread-screen shape note](thread-screen.md#shape) for the deferred fold.

### `MainActivity` destination

The row's tap behaviour is fully internal to `ThreadScreen` post-[#254](../codebase/254.md). `MainActivity` does **not** pass anything for the row's `onExpandClick`; instead it binds the StatusSheet's per-section mutators so the sheet's selections reach [`ThreadViewModel`](thread-screen.md#viewmodel):

```kotlin
// MainActivity.kt:204-215 (post-#229)
ThreadScreen(
    state = state,
    // ...
    onModelSelected = vm::onModelSelected,     // #253
    onEffortSelected = vm::onEffortSelected,   // #229
    onYoloToggled = vm::onYoloToggled,         // #229
    // ...
)
```

The `// TODO(#146): open Status Sheet` placeholder + the `onExpandClick = {}` line that #145 introduced were both deleted in #254. The grep-able breadcrumb for the wiring is now the `if (sheetVisible) { StatusSheet(...) }` block inside `ThreadScreen` itself.

### `ThreadUiState` fields

```kotlin
data class ThreadUiState(
    // ...nine pre-existing fields unchanged...
    val selectedModel: Model = Model.OPUS_4_7,        // String → Model in #253
    val selectedEffort: Effort = Effort.HIGH,         // String → Effort in #229
    val yoloEnabled: Boolean = false,                 // new in #229 — sibling field on ThreadUiState; NOT a status-row input
    val tokenPercent: Int = 0,
)
```

The defaults serve a dual purpose:

1. The `stateIn(initialValue = ThreadUiState(conversationId, displayName))` literal at `ThreadViewModel.kt` keeps compiling unchanged.
2. The `selectedModel` / `selectedEffort` defaults match the rendered stub so the pre-subscription initial frame already paints the right strings — only `tokenPercent` differs between the initial frame (`0`) and post-subscription (`73`). That asymmetry is deliberate and pinned by the two test methods below.

`yoloEnabled` does **not** surface on this row — it lives on `ThreadUiState` to back the [`StatusSheet`](status-sheet.md)'s YOLO section, but the row only shows `model · effort · NN% used ▴`. Adding YOLO to the row would defeat the architectural single-writer / intentional-friction design that [#229](../codebase/229.md) implemented (`yoloEnabled` is only togglable from inside the sheet, never from the status row).

### `ThreadViewModel` populates inside `combine`

See [`ThreadViewModel`](thread-screen.md#viewmodel) for the full main `combine(...)` block. Highlights for the row's three fields:

- **`selectedModel: Model`** ([#253](../codebase/253.md)) — pre-combined `selectedModelFlow = combine(appPreferences.defaultModel, modelOverride: MutableStateFlow<Model?>) { d, o -> o ?: d }` folded into the main combine. `STUB_MODEL` deleted.
- **`selectedEffort: Effort`** ([#229](../codebase/229.md)) — pre-combined `selectedEffortFlow = combine(appPreferences.defaultEffort, effortOverride: MutableStateFlow<Effort?>) { d, o -> o ?: d }`, mirroring `selectedModelFlow` shape. `STUB_EFFORT` deleted. Both flows are bundled into a single `runConfigFlow` (file-private `RunConfig` data class) alongside `yoloEnabled: MutableStateFlow<Boolean>` to stay under `combine`'s 5-arg overload ceiling.
- **`tokenPercent: Int`** — still companion-object constant `STUB_TOKEN_PERCENT = 73`. Phase 4 swaps this for an `agentStatus.tokenPercent` read off a new backend flow arm; the `selectedModel` / `selectedEffort` arms are already wired and the Phase-4 surgery would extend them with backend-sourced "current value" overrides above the existing `*Override` flows, or persist the overrides themselves — either is a non-breaking widening.

The stub `tokenPercent = 73` lands inside the warning band on purpose — the developer and the reviewer see the threshold-driven warning color in the running app, not just in previews. The `selectedModel` default (`Model.OPUS_4_7`) renders `"Opus 4.7"` via `Model.label()`; the `selectedEffort` default (`Effort.HIGH`) renders `"high"` via `Effort.label()` — both identical to the pre-rewire string stubs.

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

- **Tapping the row opens the [`StatusSheet`](status-sheet.md)** (since [#254](../codebase/254.md)). `ThreadScreen` owns a `rememberSaveable`-hoisted `sheetVisible` flag and binds the row's `onExpandClick = { sheetVisible = true }` internally; the sheet's selection events flow back via `onModelSelected: (Model) -> Unit` (#254), `onEffortSelected: (Effort) -> Unit` (#229), and `onYoloToggled: (Boolean) -> Unit` (#229) parameters, all bound to the matching `vm::` method references at `MainActivity`. Pre-#254 the row's tap was a no-op (`onExpandClick = {}` placeholder with a `TODO(#146)` marker); that placeholder is now gone.
- **Only `tokenPercent` is still stub-populated in the VM.** Phase 4 replaces the last `STUB_TOKEN_PERCENT` companion-object constant with a read off a backend `AgentStatus` flow. The row's own parameter shape (`String`, `String`, `Int`) is the stable contract — no `enum class Effort` or `value class TokenPercent` wrapper at the row leaf; the type information lives at the VM/screen boundary. Validation of out-of-range or empty values is Phase 4's responsibility. As of [#253](../codebase/253.md), `model` is sourced from `appPreferences.defaultModel` with an in-memory per-conversation override; as of [#229](../codebase/229.md), `effort` is sourced from `appPreferences.defaultEffort` the same way. The screen derives both label strings at the boundary.
- **One-frame visual asymmetry between initial frame and post-subscription frame.** `tokenPercent` defaults to `0` on `ThreadUiState` (rendered in the under-50% neutral color) and flips to `73` (warning band) on the `combine`'s first emission. The fake's `combine` upstream emits synchronously on first subscription, so the initial frame is invisible in the running app — but the asymmetry is pinned by the two test methods on purpose, so a reviewer doesn't accidentally "fix" the data-class default to `73`. The `selectedModel` axis is symmetric across the data-class default ([#253](../codebase/253.md) — both frames render `Model.OPUS_4_7` → `"Opus 4.7"`), and the `selectedEffort` axis is symmetric the same way ([#229](../codebase/229.md) — both frames render `Effort.HIGH` → `"high"`); only `tokenPercent` remains the load-bearing asymmetry.
- **No animation on threshold transitions.** When `tokenPercent` crosses 50 or 95, the percent-segment color swaps instantly. An `animateColorAsState` per-color fade is a follow-up if designer signs off on a duration.
- **Row is always visible — no `AnimatedVisibility`.** The row is part of the steady-state chrome; it does not appear/disappear under any state. Compare with [`EmptyThreadState`](empty-thread-state.md) and [`WorkspaceChip`](workspace-chip.md) which gate on `!hasMessages` and `!isPromoted && !hasMessages` respectively.
- **No interaction with `WorkspaceChip` or `EmptyThreadState`.** All three live in different slots of the `Scaffold` body / `bottomBar`. The status row is the constant cap on the composer cluster regardless of which empty-state branch the body renders.

## Related

- Ticket notes: [`../codebase/145.md`](../codebase/145.md) (this implementation), [`../codebase/253.md`](../codebase/253.md) (model rewired from `STUB_MODEL` constant to typed `Model` enum sourced from `AppPreferences.defaultModel` with per-conversation override; row's `model: String` parameter shape preserved), [`../codebase/254.md`](../codebase/254.md) (row's `onExpandClick` now opens the [`StatusSheet`](status-sheet.md) — sheet visibility hoisted into `ThreadScreen` via `rememberSaveable`; `ThreadScreen.onExpandClick` parameter deleted, replaced by `onModelSelected: (Model) -> Unit`), [`../codebase/229.md`](../codebase/229.md) (effort rewired from `STUB_EFFORT` constant to typed `Effort` enum sourced from `AppPreferences.defaultEffort` with per-conversation override; row's `effort: String` parameter shape preserved — only `tokenPercent` remains a stub)
- Spec: `docs/specs/architecture/145-thread-status-row.md`
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into; pre-#145 had only `ThreadInputBar` in `bottomBar`)
- Sibling: [Thread input bar](thread-input-bar.md) (the composer this row stacks above, inside the same `Column` in `bottomBar`)
- Upstream: [Warning color slot](warning-color.md) (the `colorScheme.warning` slot the 50–94% band consumes, landed in [#119](../codebase/119.md))
- Downstream: [`StatusSheet`](status-sheet.md) ([#254](../codebase/254.md) shell + Model; [#229](../codebase/229.md) Effort + YOLO; [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) Context window pending) — the sheet that `onExpandClick` opens. The #229 Effort wire-through preserves the row's `effort: String` parameter shape; the YOLO toggle has no row surface (intentional friction — YOLO is only togglable from inside the sheet).
- Figma: [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically); parent [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)
