# Thread status row

Always-visible single-row status surface at the bottom of [`ThreadScreen`](thread-screen.md), stacked above the [`ThreadInputBar`](thread-input-bar.md) inside the same `Scaffold.bottomBar` slot. Landed in [#145](../codebase/145.md). Shows `Model · effort` — tapping anywhere on the row invokes a hoisted `onExpandClick` callback. Post-[#254](../codebase/254.md) the row's `onExpandClick` is bound by `ThreadScreen` to a screen-internal `{ sheetVisible = true }` lambda that opens the [`StatusSheet`](status-sheet.md) — the public `onExpandClick: () -> Unit` parameter on `ThreadScreen` was deleted at the same time (no remaining external consumer; the four previews never passed it). As of [#602](../codebase/602.md), the row no longer carries a third, context-usage segment — see § Removal of the token-percent segment (#602) below.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt`). Figma reference: subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) of the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — a **deliberate, spec'd divergence** as of #602: the node is a single text layer literally named `Opus 4.7 · high · 73% used` with no two-segment variant, and this row ships two of its three segments (see below).

## What it does

Renders two pieces of information the user wants at a glance while talking to the agent — the current model (`Opus 4.7`) and the effort level (`high`) — composed into a single monospaced text run with a trailing `▴` icon. The full row is `clickable`; tapping anywhere invokes `onExpandClick`.

The values are populated by `ThreadViewModel` inside its main `combine(...)` block. As of [#253](../codebase/253.md), `model` is **no longer a stub** — `ThreadUiState.selectedModel: Model` is sourced from `appPreferences.defaultModel` (with an in-memory per-conversation override on `MutableStateFlow<Model?>`) via a pre-combined `selectedModelFlow`, and the row reads the display label via `state.selectedModel.label()` at the [`ThreadScreen`](thread-screen.md) callsite. As of [#229](../codebase/229.md), `effort` is **no longer a stub either** — `ThreadUiState.selectedEffort: Effort` is sourced from `appPreferences.defaultEffort` (with an in-memory per-conversation override on `MutableStateFlow<Effort?>`) via a pre-combined `selectedEffortFlow`, and the row reads the display label via `state.selectedEffort.label()` at the [`ThreadScreen`](thread-screen.md) callsite (using the public `Effort.label()` extension widened from `internal` in the same slice). The row's own parameter shape (`model: String`, `effort: String`) is **preserved across #253 and #229** — typing the row's parameter list against `Model` / `Effort` was deliberately rejected so the row stays primitive at the leaf, with the type information one layer up at the screen-VM boundary.

### Removal of the token-percent segment (#602)

Through #601 (see history below) the row rendered a third segment, `NN% used`, coloured by severity via a private `tokenPercentColor` helper (neutral under 50%, [warning](warning-color.md) under 95%, error at 95%+). The backing value was `ThreadViewModel.STUB_TOKEN_PERCENT = 73` — a constant nobody measured — so the row editorialised about a fabricated number at a severity that was constant by construction. [#602](../codebase/602.md) deleted the segment and its helper rather than rewording it: a compact monospace line has no room for a second explanation, and the [`StatusSheet`](status-sheet.md) — one tap away, honest as of [#601](../codebase/601.md)'s "Context usage unavailable" — is where that explanation belongs. Deleting the segment is also what makes "no severity-derived colour anywhere in the row" true by construction; severity lived only on the deleted span, and the surviving `onSurface` / `onSurfaceVariant` two-tone split is Figma's static treatment, not severity colouring.

`ThreadUiState.tokenPercent` and `ThreadViewModel`'s population of it from `STUB_TOKEN_PERCENT` are **unchanged** — the row simply stopped reading the field, which is what let this ship standalone ahead of #603 (which deletes the field) and #591 (which will wire a measured one, blocked on daemon-side pyrycode PR #1215).

## Shape

```kotlin
@Composable
fun ThreadStatusRow(
    model: String,
    effort: String,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Pure stateless presentation — no internal `remember`, no `LaunchedEffect`, no overload pair. The two primitives are the entire input contract; the spec deliberately did not wrap them in `enum class Effort` so a future re-typed swap stays shape-stable. The row recomposes when either `model` or `effort` changes; both are Compose-stable types. **Narrowed from three parameters to two in [#602](../codebase/602.md)** — `tokenPercent: Int` was dropped; the surviving parameter order (`model`, `effort`, `onExpandClick`, `modifier` last) is unchanged, so the `ComposeParameterOrder` lint rule stayed satisfied without special-casing.

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

### `AnnotatedString` for two differently-colored segments

```kotlin
val annotated: AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = onSurface))         { append(model) }
    withStyle(SpanStyle(color = onSurfaceVariant))  { append(" · $effort") }
}
```

Two spans inside one `Text` keeps the row a single layout node and gives glyph-perfect placement of the `·` separator. The alternative — sibling `Text` composables in a `Row` — would have put per-`Text` horizontal padding between the cells (the `·` would be inter-`Text` space, not glyphs). `buildAnnotatedString` is the right primitive here.

The model name renders in `colorScheme.onSurface` (full-emphasis text color). The separator-and-effort segment renders in `colorScheme.onSurfaceVariant` (de-emphasised). **This two-tone split is not severity colouring** — it is Figma `16:58`'s static treatment and stays untouched; only the third, severity-coloured segment was ever derived from a measurement, and it's gone.

**Before [#602](../codebase/602.md)** a third span appended `"$tokenPercent% used"` in a threshold-driven colour from a private `tokenPercentColor` helper (`coerceIn(0, 100)`; `< 50` → `onSurfaceVariant`, `< 95` → [`warning`](warning-color.md), `else` → `error`), and the separator span read `" · $effort · "` with a trailing ` · ` that only read correctly because the third segment followed it. #602 deleted the usage span, the helper (and its now-orphaned `Color` / `de.pyryco.mobile.ui.theme.warning` imports), and narrowed the separator to `" · $effort"` — dropping only the *trailing* ` · `. The row cannot fail or branch on an out-of-range value anymore; it renders two spans built purely from `String` parameters. The [`StatusSheet`](status-sheet.md)'s Context window section still has its own `progressColor` helper ([#230](../codebase/230.md)) for the sheet's fill chroma — unaffected by this row's removal.

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
    val tokensUsed: Int = 0,                          // new in #230 — Context window section; NOT a status-row input
    val tokensTotal: Int = 0,                         // new in #230 — Context window section; NOT a status-row input
)
```

The defaults serve a dual purpose:

1. The `stateIn(initialValue = ThreadUiState(conversationId, displayName))` literal at `ThreadViewModel.kt` keeps compiling unchanged.
2. The `selectedModel` / `selectedEffort` defaults match the rendered stub so the pre-subscription initial frame already paints the right strings.

`tokenPercent` (along with `tokensUsed` / `tokensTotal`, [#230](../codebase/230.md)) stays on `ThreadUiState` post-[#602](../codebase/602.md) but is **no longer read by this row** — it now backs only the [`StatusSheet`](status-sheet.md)'s Context window section (itself replaced with "Context usage unavailable" by [#601](../codebase/601.md)). It remains on the data class purely so #602 could ship without touching `ThreadViewModel`; #603 deletes it. `yoloEnabled` does **not** surface on this row either — it lives on `ThreadUiState` to back the sheet's YOLO section, but the row only shows `model · effort`. Adding YOLO to the row would defeat the architectural single-writer / intentional-friction design that [#229](../codebase/229.md) implemented (`yoloEnabled` is only togglable from inside the sheet, never from the status row).

### `ThreadViewModel` populates inside `combine`

See [`ThreadViewModel`](thread-screen.md#viewmodel) for the full main `combine(...)` block. Highlights for the row's two fields:

- **`selectedModel: Model`** ([#253](../codebase/253.md)) — pre-combined `selectedModelFlow = combine(appPreferences.defaultModel, modelOverride: MutableStateFlow<Model?>) { d, o -> o ?: d }` folded into the main combine. `STUB_MODEL` deleted.
- **`selectedEffort: Effort`** ([#229](../codebase/229.md)) — pre-combined `selectedEffortFlow = combine(appPreferences.defaultEffort, effortOverride: MutableStateFlow<Effort?>) { d, o -> o ?: d }`, mirroring `selectedModelFlow` shape. `STUB_EFFORT` deleted. Both flows are bundled into a single `runConfigFlow` (file-private `RunConfig` data class) alongside `yoloEnabled: MutableStateFlow<Boolean>` to stay under `combine`'s 5-arg overload ceiling.

`tokenPercent` is still populated (companion-object constant `STUB_TOKEN_PERCENT = 73`, alongside `STUB_TOKENS_USED` / `STUB_TOKENS_TOTAL` from [#230](../codebase/230.md)) — but as of [#602](../codebase/602.md) this row no longer reads it, so it has no bearing on what the row renders. It is `StatusSheet`'s concern now; see [that feature doc](status-sheet.md) for its Phase-4 swap plan.

The `selectedModel` default (`Model.OPUS_4_7`) renders `"Opus 4.7"` via `Model.label()`; the `selectedEffort` default (`Effort.HIGH`) renders `"high"` via `Effort.label()` — both identical to the pre-rewire string stubs.

## State + concurrency

No new flows, no new dispatchers. The `ThreadUiState` fields are populated synchronously inside the existing `combine(...)` block on `viewModelScope`. `WhileSubscribed(5_000)` lifetime policy is preserved. `ThreadStatusRow` is pure presentation — no `LaunchedEffect`, no `rememberCoroutineScope`.

The row recomposes only when `model` or `effort` changes on `ThreadUiState` — as of [#602](../codebase/602.md) it no longer reads `tokenPercent` at all, so a `tokenPercent`-only emission no longer triggers recomposition of this composable (a real recomposition-count reduction, even though the value is a stub constant today). Both remaining inputs are Compose-stable `String`s, so Compose skips recomposition when the values are unchanged across emissions.

## Error handling

No new failure modes; one existing one disappeared in [#602](../codebase/602.md) — `tokenPercentColor`'s `coerceIn(0, 100)` clamp existed only to keep an out-of-range stub from indexing off the severity ladder, and it was deleted along with its only call site. Empty `model` / `effort` strings produce a visually awkward but non-crashing row (`" ·  ·"`... rendering as just the separator); validation is **not** added here — a future real backend integration owns that contract. The row now renders two spans built purely from `String` parameters and cannot fail.

## Testing

Two unit tests remain in `ThreadViewModelTest.kt` (added in #145, renamed in [#253](../codebase/253.md) `*Stub*` → `*Default*` once `model` stopped being a stub) and still assert `tokenPercent`, unaffected by [#602](../codebase/602.md) since `ThreadViewModel` itself was untouched:

1. **`state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults`** — wrapped in `runTest { }` post-#253 for the prefs-IO context. Constructs the VM, reads `vm.state.value` without a launched collector. Asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 0`.
2. **`state_postSubscription_emitsDefaultModelEffortAndTokenPercent`** — `runTest { launch collector; advanceUntilIdle(); ... collector.cancel() }`. Asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 73`.

These two are **#603's to remove**, not #602's — #602 deliberately left `ThreadViewModel` and its tests alone so the row-only slice could ship standalone. The `selectedModel` plumbing is covered separately by four additional tests landed in [#253](../codebase/253.md) (default-tracking, default-reemission, override-without-mutation, override-stickiness) — see the [thread-screen test list](thread-screen.md#testing) for the full inventory.

**First-ever Compose coverage landed in [#602](../codebase/602.md):** `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRowTest.kt`, four tests exercising `ThreadStatusRow` directly (not through `ThreadScreen`, to keep assertions unambiguous and avoid the package's known pre-existing red, #598/#606):

- `renders_model_and_effort_only` — exact-match `onNodeWithText("Opus 4.7 · high")`; the load-bearing assertion, since `onNodeWithText`'s default `substring = false` is what would have caught a dangling trailing separator.
- `no_usage_segment_is_rendered` — asserts both `"% used"` and bare `"%"` are absent (`substring = true`), guarding against any future partial reintroduction.
- `separator_is_built_from_the_parameters` — a second model/effort pair proves the string is composed from the params, not hardcoded around the deletion.
- `expand_affordance_is_unchanged` — regression guard on the untouched tap-to-expand behaviour.

## Previews

Two `@Preview`s in `ThreadStatusRow.kt` — `ThreadStatusRowLightPreview` / `ThreadStatusRowDarkPreview`, both `showBackground = true, widthDp = 412`, labelled `"StatusRow — Light"` / `"StatusRow — Dark"`. **Collapsed from eight in [#602](../codebase/602.md)** — the original set was four light × four dark × four token-percent values (20/60/88/97) sweeping the three severity bands; once the parameter was removed, all four light blocks (and all four dark blocks) became byte-identical, so the duplicates were deleted rather than mechanically stripped of their argument (which would have left eight identical previews). The two survivors are the light-20% and dark-20% originals, renamed to drop the percentage from both the function name and the `@Preview(name = …)` label.

The four `ThreadScreen` previews (`ThreadScreenLightPreview`, `ThreadScreenDarkPreview`, `ThreadScreenAboveDelimiterDimLightPreview`, `ThreadScreenAboveDelimiterDimDarkPreview`) still set `tokenPercent = 73` on their `ThreadUiState` literals (#603 owns removing that) — harmless, since the row no longer reads the field.

Naming convention `<Component> — <Theme>` matches the [thread-input-bar](thread-input-bar.md#previews) shape established in #188 (em-dash separator).

## Edge cases / limitations

- **Tapping the row opens the [`StatusSheet`](status-sheet.md)** (since [#254](../codebase/254.md)). `ThreadScreen` owns a `rememberSaveable`-hoisted `sheetVisible` flag and binds the row's `onExpandClick = { sheetVisible = true }` internally; the sheet's selection events flow back via `onModelSelected: (Model) -> Unit` (#254), `onEffortSelected: (Effort) -> Unit` (#229), and `onYoloToggled: (Boolean) -> Unit` (#229) parameters, all bound to the matching `vm::` method references at `MainActivity`. Pre-#254 the row's tap was a no-op (`onExpandClick = {}` placeholder with a `TODO(#146)` marker); that placeholder is now gone.
- **`ThreadUiState.tokenPercent` is a dead-to-this-row field, not a dead field.** [#602](../codebase/602.md) stopped the row reading it but deliberately left it on the data class and left `ThreadViewModel` populating it from `STUB_TOKEN_PERCENT`, so the sheet and #603/#591 are unaffected. Kotlin doesn't warn on an unread `data class` property, so this produces no lint noise; #603 is the ticket that removes the field.
- **A ~32dp tap target, under the 48dp a11y minimum.** Flagged as a non-blocking NIT on #602's review — pre-existing since #145 (row height is unaffected by the segment removal), tracked for a future ticket alongside #591's re-populate rather than fixed here.
- **Row is always visible — no `AnimatedVisibility`.** The row is part of the steady-state chrome; it does not appear/disappear under any state. Compare with [`EmptyThreadState`](empty-thread-state.md) and [`WorkspaceChip`](workspace-chip.md) which gate on `!hasMessages` and `!isPromoted && !hasMessages` respectively.
- **No interaction with `WorkspaceChip` or `EmptyThreadState`.** All three live in different slots of the `Scaffold` body / `bottomBar`. The status row is the constant cap on the composer cluster regardless of which empty-state branch the body renders.

## Related

- Ticket notes: [`../codebase/145.md`](../codebase/145.md) (original implementation), [`../codebase/253.md`](../codebase/253.md) (model rewired from `STUB_MODEL` constant to typed `Model` enum sourced from `AppPreferences.defaultModel` with per-conversation override; row's `model: String` parameter shape preserved), [`../codebase/254.md`](../codebase/254.md) (row's `onExpandClick` now opens the [`StatusSheet`](status-sheet.md) — sheet visibility hoisted into `ThreadScreen` via `rememberSaveable`; `ThreadScreen.onExpandClick` parameter deleted, replaced by `onModelSelected: (Model) -> Unit`), [`../codebase/229.md`](../codebase/229.md) (effort rewired from `STUB_EFFORT` constant to typed `Effort` enum sourced from `AppPreferences.defaultEffort` with per-conversation override; row's `effort: String` parameter shape preserved), [`../codebase/230.md`](../codebase/230.md) (`tokensUsed` + `tokensTotal` added to `ThreadUiState` for the [`StatusSheet`](status-sheet.md)'s Context window section), [`../codebase/601.md`](../codebase/601.md) (sibling split child — replaced the StatusSheet's equivalent stub with "Context usage unavailable" rather than deleting the segment, since the sheet has room for the explanation this row doesn't), [`../codebase/602.md`](../codebase/602.md) (**this ticket** — deleted the row's usage segment, `tokenPercentColor`, and 6 of 8 previews; first-ever Compose coverage for the row)
- Spec: `docs/specs/architecture/145-thread-status-row.md` (original); `docs/specs/architecture/602-thread-status-row-drop-usage-segment.md` (segment removal)
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into; pre-#145 had only `ThreadInputBar` in `bottomBar`)
- Sibling: [Thread input bar](thread-input-bar.md) (the composer this row stacks above, inside the same `Column` in `bottomBar`)
- Downstream: [`StatusSheet`](status-sheet.md) ([#254](../codebase/254.md) shell + Model; [#229](../codebase/229.md) Effort + YOLO; [#230](../codebase/230.md) Context window; [#601](../codebase/601.md) "Context usage unavailable") — the sheet that `onExpandClick` opens, and the surface that now carries the context-usage explanation this row no longer attempts. The YOLO toggle has no row surface (intentional friction — YOLO is only togglable from inside the sheet).
- Blocked-by-this: #603 (deletes `ThreadUiState.tokenPercent` and the five `ThreadScreen.kt` preview literals that still pass it); sibling: #591 (serves a real context-usage figure once the daemon supports it — blocked on pyrycode PR #1215).
- Figma: [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically — a single text layer literally named `Opus 4.7 · high · 73% used`, with no two-segment variant; this row is a deliberate, spec'd divergence from it as of #602); parent [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)
